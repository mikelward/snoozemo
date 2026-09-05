package app.snoozemo.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.RuleOwnership
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenRuleActivation
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.RingerFollowUp
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ringerFollowUp
import app.snoozemo.core.confirmsNothingSilencing
import app.snoozemo.core.ZenRuleState
import app.snoozemo.core.SnoozeIdentity
import app.snoozemo.core.ZenTrigger

/**
 * The only place in the app that touches `NotificationManager` or
 * `AutomaticZenRule` (SPEC.md §11), and the one that drives the ringer with
 * them ([RingerController]) — `:dnd` owns the device's quiet state, both
 * halves of it.
 *
 * @param configurationActivity the settings screen the platform deep-links to
 *   from the rule in Settings and in the Modes UI. Passed in rather than
 *   referenced directly, so `:dnd` doesn't depend on `:app`.
 * @param ringer how loud a running snooze is allowed to be (SPEC.md §5.9).
 *   Driven from here, and that is the whole reason it lives here: `setSnoozed`
 *   is the one call every arm and release in the app already passes through —
 *   the service, the cap alarm, the backstop, the restore path — so a ceiling
 *   applied here cannot be forgotten by one of them. Wired into the six
 *   existing call sites separately, it would have been six chances to miss.
 */
class AndroidZenController(
    private val context: Context,
    private val store: ZenRuleIdStore,
    private val configurationActivity: ComponentName,
    private val ringer: RingerController,
) : ZenController {

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    override fun policyAccess(): PolicyAccess =
        if (notificationManager.isNotificationPolicyAccessGranted) {
            PolicyAccess.GRANTED
        } else {
            PolicyAccess.DENIED
        }

    /**
     * Serialized process-wide, because the rule is a singleton and creating it
     * is a read-then-write.
     *
     * Two threads can reach this at once — the screen reconciles policy access
     * on a worker while a tile arm drives another controller instance, and the
     * receivers build their own — and without the lock both can see no id, both
     * create a rule, and both persist. One id wins; the other rule is orphaned,
     * and if the arm activated the loser then the phone is being silenced by a
     * rule nothing records.
     *
     * A lock across binder calls, which is normally worth avoiding, and safe
     * here for one reason: this is never on the fast arm path. `setSnoozed`
     * reaches it only after the warm id has already failed, and every other
     * caller is startup or a broadcast.
     */
    /**
     * Contained here rather than at each call site, because every caller is on
     * a path where a throw costs more than the preparation is worth.
     *
     * The worst is the cap wake-up: `reconcilePolicyAccess` runs before the
     * action is dispatched, so an exception from *preparing the rule* unwinds
     * `onStartCommand` before the release that wake-up existed to perform —
     * and the alarm behind it is one-shot and already spent. The screen and
     * `setSnoozed` are milder but the same shape.
     *
     * `FAILED` is the honest answer and already means what this is: the lookup
     * didn't complete, so the rule's state is unknown rather than absent.
     * `setSnoozed` maps it to `PLATFORM_REFUSED`, the retryable outcome.
     */
    override fun ruleActivation(ruleId: String?): ZenRuleActivation {
        // API 35, and honestly unanswerable below it. That is not a gap this
        // read introduces: AUTOMATIC_RULE_STATUS_DEACTIVATED is API 35 too, so
        // on 34 neither mechanism exists and the drift of SPEC.md §5.8 is
        // simply not observable.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return ZenRuleActivation.UNKNOWN
        }
        // The snooze's own rule when the caller names one; otherwise the id
        // the app holds now. No id at all is "cannot tell", never "gone": a
        // failed store read would otherwise end a snooze as a lost capability
        // on the strength of a disk error.
        val ruleId = ruleId ?: runCatching { store.ruleId() }.getOrNull()
            ?: return ZenRuleActivation.UNKNOWN

        val state = runCatching { notificationManager.getAutomaticZenRuleState(ruleId) }
            .getOrElse {
                Log.w(TAG, "Reading the zen rule's activation state failed.", it)
                return ZenRuleActivation.UNKNOWN
            }
        if (state == Condition.STATE_TRUE) return ZenRuleActivation.ACTIVE

        // Only now, and only because the answer wasn't a plain yes: the
        // platform reports STATE_UNKNOWN for a rule that is merely unreadable
        // *and* for one that has been deleted, and those need opposite
        // responses (Codex, PR #36). One extra lookup separates them, off the
        // arm path and only on the branch that needs it.
        val rule = runCatching { notificationManager.getAutomaticZenRule(ruleId) }
            .getOrElse {
                Log.w(TAG, "Reading the zen rule back failed.", it)
                return ZenRuleActivation.UNKNOWN
            }
        return when {
            rule == null -> ZenRuleActivation.MISSING
            // Before the condition is read, not after: a disabled rule reports
            // `STATE_FALSE` too, so judging by the condition alone would call a
            // capability we have lost a preference the user expressed (Codex,
            // PR #36).
            !rule.isEnabled -> ZenRuleActivation.DISABLED
            state == Condition.STATE_FALSE -> ZenRuleActivation.INACTIVE
            // It exists, it is enabled, and the platform will not say. Not off,
            // so nothing ends.
            else -> ZenRuleActivation.UNKNOWN
        }
    }

    override fun ownsRule(ruleId: String?, enforcing: String?): Boolean {
        // Read, never created: this runs on a broadcast, and a status change for
        // somebody else's rule must not be the thing that mints ours.
        //
        // The running snooze's own rule first, then the id we hold *now*, and
        // nothing more. A short-lived memory of the id a replacement had just
        // displaced lived here briefly and was taken out again (Codex, PR #36):
        // it closed a narrow race and opened three wider ones over as many
        // review rounds, because ownership inferred from whatever the app
        // happens to hold needs a new guard for every way that value can move.
        // The snooze naming its rule is what replaced the inference.
        val ours = runCatching { store.ruleId() }.getOrElse {
            Log.w(TAG, "Reading the rule id failed; treating the changed rule as not ours.", it)
            return false
        }
        return RuleOwnership.isOurs(ruleId, current = ours, enforcing = enforcing)
    }

    override fun ensureRule(): ZenRuleState =
        runCatching { synchronized(RULE_CREATION) { ensureRuleLocked() } }.getOrElse {
            Log.e(TAG, "Preparing the zen rule failed; reporting the state as unknown.", it)
            ZenRuleState.FAILED
        }

    private fun ensureRuleLocked(): ZenRuleState {
        if (policyAccess() == PolicyAccess.DENIED) return ZenRuleState.MISSING_ACCESS

        // A persisted id is only good while the platform still has the rule: the
        // user can delete it from Settings, and a restore or an app-data clear
        // can leave the id pointing at nothing. Verify rather than assume, or
        // the first arm after a deletion fails with the id looking fine.
        store.ruleId()?.let { existing ->
            val lookup = runCatching { notificationManager.getAutomaticZenRule(existing) }
            when {
                // Confirmed gone. Drop the id and make a new rule.
                lookup.isSuccess && lookup.getOrNull() == null -> store.clear()

                // Present, but switched off in Settings or the Modes UI. The
                // platform still accepts a state change on a disabled rule and
                // still reports success — while the phone goes on ringing. So
                // this must not read as READY, or the app would say "Snoozing"
                // over an audible phone, which is the worst lie it can tell.
                lookup.getOrNull()?.isEnabled == false -> return ZenRuleState.DISABLED

                lookup.isSuccess -> return ZenRuleState.READY

                // The lookup itself failed, which is NOT the same as the rule
                // being gone — and treating it as such is dangerous. Clearing the
                // id here would create a *second* rule, and a later release would
                // set STATE_FALSE on the replacement while the original stayed
                // active: a phone left quiet with no id left that can turn it
                // back off. Keep the id and report the failure instead.
                else -> {
                    Log.e(TAG, "Reading the existing zen rule failed; keeping its id.", lookup.exceptionOrNull())
                    return ZenRuleState.FAILED
                }
            }
        }

        return runCatching { notificationManager.addAutomaticZenRule(buildRule()) }
            .fold(
                onSuccess = { id ->
                    // The id has to be on disk before this reports READY,
                    // because the caller's next move is to turn the rule on.
                    // A rule that is active with its id unrecorded is one no
                    // later process can turn off — it would create a second
                    // rule and release that instead, while the first went on
                    // silencing the phone with nothing left that names it.
                    if (store.setRuleId(id)) {
                        ZenRuleState.READY
                    } else {
                        // Take the rule back rather than leaving one we can't
                        // name. Safe to do here and only here: it was created a
                        // moment ago and has never been set to STATE_TRUE, so
                        // it is inert — this is not turning off something that
                        // is silencing the phone (SPEC.md §5.6).
                        Log.e(TAG, "The new zen rule's id could not be stored; removing the rule.")
                        runCatching { notificationManager.removeAutomaticZenRule(id) }.onFailure {
                            // An inert rule nobody can name is clutter, not a
                            // hazard: it silences nothing until its state is
                            // set, and only this app would ever set it.
                            Log.w(TAG, "Removing the unrecorded zen rule failed; it stays inert.", it)
                        }
                        ZenRuleState.FAILED
                    }
                },
                onFailure = { error ->
                    // Never swallowed: the caller turns this into something the
                    // user can see, because an app that can't create its rule
                    // can't snooze at all and must say so rather than appearing
                    // to work.
                    Log.e(TAG, "Creating the zen rule failed.", error)
                    ZenRuleState.FAILED
                },
            )
    }

    /**
     * The rule, and the ringer with it (SPEC.md §5.9).
     *
     * The ringer is nested *inside* the rule, in both directions, and the
     * asymmetry is deliberate:
     *
     * - **Arming** sets the rule first and lowers the ringer only once the rule
     *   is confirmed on. Nothing may come between the tap and `STATE_TRUE`
     *   (`AGENTS.md`, the arm path), and a *fresh* arm that is then refused has
     *   taken nothing to give back. A **re-assertion** is the exception, and
     *   `RingerFollowUp` is where it is decided: one that establishes nothing
     *   was silencing the phone ends the snooze without a second zen call
     *   anywhere in the app, so it owes back whatever an earlier arm took.
     * - **Releasing** hands the ringer back *before* the rule goes off, because
     *   the loan record is what a retry reads and a release can fail: undoing
     *   the quiet while the record still exists leaves every retry path intact,
     *   where doing it after a release that cleared the record would leave a
     *   phone on vibrate with nothing left that knows better. The cost is a
     *   microseconds-wide window where the ringer is back and the rule is still
     *   on, which can only ever make the phone *louder* than intended for one
     *   call it was about to allow anyway — microseconds only when the release
     *   succeeds, so a refused one re-applies the ceiling rather than leaving
     *   that window open until the next re-assertion.
     *
     * A failed ringer change never fails the snooze. It is reported — the debug
     * log carries the reason either way — and the arm or release stands: the
     * worst it costs is a phone that rings for the people Do Not Disturb was
     * already letting through, which is not the failure this app fears.
     */
    override fun setSnoozed(
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
        snooze: SnoozeIdentity?,
    ): ZenOutcome {
        // Carried across the rule write, because a hand-back that recognized the
        // user's own mid-snooze change must not be undone by the re-quiet below
        // (Codex, PR #176): that path would find no loan, borrow again, and
        // lower the very ringer rule 4 had just left as theirs.
        val disowned = !snoozed && giveBackTheRinger() is RingerOutcome.Disowned
        val outcome = setRuleState(snoozed, trigger, placeName)
        when (ringerFollowUp(snoozed, outcome, ringerDisowned = disowned)) {
            RingerFollowUp.QUIET -> quietTheRinger(snooze)
            RingerFollowUp.HAND_BACK_AND_FORGET -> {
                // Already done above on the release path; an **arm** that ended
                // the snooze has not done it at all (Codex, PR #176).
                if (snoozed) giveBackTheRinger()
                forgetTheCeiling()
            }
            // The hand-back above ran unconditionally, and the window that
            // buys is microseconds wide only when the release succeeds. On a
            // refusal the snooze runs on, so it would last until some later
            // re-assertion with the phone above its ceiling the whole time
            // (Codex, PR #176). Borrowing again here records the mode just
            // restored, which is the pre-snooze one, so the way back is
            // unchanged. A ringer the hand-back disowned never reaches here —
            // `ringerDisowned` sends it to `NOTHING`, since it is theirs for
            // the rest of the snooze.
            RingerFollowUp.RE_QUIET -> quietTheRinger(snooze)
            RingerFollowUp.NOTHING -> Unit
        }
        return outcome
    }

    /**
     * Contained, because this is not the snooze. An exception escaping the
     * ringer would unwind `end()` and then `onStartCommand` — the same failure
     * the diagnosis branch below is contained against — and cost the release
     * that the wake-up existed to perform, over a phone that is merely a little
     * louder than asked.
     */
    private fun quietTheRinger(snooze: SnoozeIdentity?) {
        runCatching { ringer.quiet(snooze) }.onFailure {
            SnoozeDebugLog.failure(it, "ringer: applying the chosen ceiling threw; the snooze stands")
        }
    }

    /**
     * Contained for the same reason, and the stakes here are the release's.
     *
     * Returns what it managed, so the caller can tell a *disowned* ringer from
     * one that was handed back or never taken. A throw reads as neither: with
     * nothing known, re-applying the ceiling is the direction that keeps a
     * running snooze quiet.
     */
    private fun giveBackTheRinger(): RingerOutcome? = runCatching { ringer.giveBack() }
        .onFailure {
            SnoozeDebugLog.failure(it, "ringer: handing it back threw; the loan is kept so this retries")
        }
        .getOrNull()

    /** Contained like the two above; losing this record costs a line of honesty. */
    private fun forgetTheCeiling() {
        runCatching { ringer.forgetCeiling() }.onFailure {
            SnoozeDebugLog.failure(it, "ringer: forgetting the ceiling threw; the next arm overwrites it")
        }
    }

    private fun setRuleState(
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
    ): ZenOutcome {
        // Straight to the warmed id, with no checks in front of it. AGENTS.md's
        // arm-path rule is explicit that no NotificationManager policy IPC may
        // sit between the tap and the rule going STATE_TRUE, and a "is access
        // granted / does the rule still exist" preamble is exactly that: two
        // binder round-trips on the one path in the app that has to feel
        // instant. Onboarding and startup have already prepared this id
        // (`ensureRule`), so the common case needs no preparation here at all.
        val warmId = store.ruleId()
        if (warmId != null) {
            val applied = trySetState(warmId, snoozed, trigger, placeName)
            if (applied is ZenOutcome.Applied) return confirmSilenced(warmId, snoozed, placeName)
        }

        // Only now — having already failed, or never having had an id — is it
        // worth paying for diagnosis. This path is slow and that is fine: it is
        // not the happy path, and the alternative is being fast and wrong.
        // Contained, because this runs on the release path too. An escaping
        // exception here doesn't degrade a diagnosis — it unwinds `end()` and
        // then `onStartCommand`, so the cap check that woke the service never
        // reaches the code that puts a fresh alarm behind an unfinished
        // release. The alarm that woke it is spent, and the phone stays quiet
        // with nothing left scheduled to change that.
        //
        // `PLATFORM_REFUSED`, never `NO_POLICY_ACCESS`: the latter is one of
        // the failures that means *nothing is silencing the phone*, so a
        // release would take it as an ending and erase the record. A read that
        // threw tells us nothing of the sort. Unknown has to stay retryable.
        val access = runCatching { policyAccess() }.getOrElse {
            Log.e(TAG, "Reading policy access failed while diagnosing a refused write.", it)
            return ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
        }
        if (access == PolicyAccess.DENIED) {
            return ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS)
        }
        when (ensureRule()) {
            ZenRuleState.READY -> Unit
            ZenRuleState.DISABLED -> return ZenOutcome.NotApplied(ZenFailure.RULE_DISABLED)
            ZenRuleState.MISSING_ACCESS -> return ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS)
            // PLATFORM_REFUSED, not NO_RULE, and the distinction has teeth:
            // FAILED means the *lookup* threw, which is exactly why ensureRule
            // keeps the existing id rather than creating a second rule. So we
            // don't know the rule is gone — and `NO_RULE.nothingLeftToRelease`
            // would tell a release to complete, erasing the record and the cap
            // while that rule may still be silencing the phone. Unknown is
            // retryable; only a rule we know is absent is an ending.
            ZenRuleState.FAILED -> return ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
        }
        val ruleId = store.ruleId() ?: return ZenOutcome.NotApplied(ZenFailure.NO_RULE)
        // Retried rather than abandoned, because this runs on the *release* path
        // too, where giving up means leaving the phone silent.
        //
        // Confirmed again even though `ensureRule` returned READY a line ago.
        // That check is a moment stale by the time the state lands, and the
        // window is not theoretical — `ensureRule` takes a lock and makes its
        // own binder calls. A rule switched off inside it would be accepted
        // here and reported as a successful snooze over an audible phone.
        val applied = trySetState(ruleId, snoozed, trigger, placeName)
        return if (applied is ZenOutcome.Applied) {
            confirmSilenced(ruleId, snoozed, placeName)
        } else {
            applied
        }
    }

    /**
     * Checks that an accepted arm will actually be heard, and rejects it if not.
     *
     * The platform accepts `setAutomaticZenRuleState` on a rule the user has
     * switched off in Settings or the Modes UI, and reports success — while the
     * phone goes on ringing. Taken at face value, the fast path above would
     * record a snooze and put `Snoozing` on the tile and the notification over
     * an audible phone, which is the worst thing this app can say.
     *
     * Done **after** the state change rather than before it, so nothing is added
     * between the tile tap and the rule going `STATE_TRUE` (`AGENTS.md`, the arm
     * path). Warming the enabled state at startup instead was the alternative
     * and is not sufficient on its own: the user can switch the rule off at any
     * point after the last `ensureRule`, and the cached answer would be stale in
     * exactly the case that matters.
     *
     * Only on the way *in*. A release never needs it — the goal there is the
     * rule not silencing anything, and a disabled rule already isn't.
     *
     * A lookup that fails refuses the arm. It is not evidence that anything is
     * wrong — but it is not evidence that anything is right either, and the two
     * unknowns are not equally priced. Claiming the snooze means `Snoozing` on
     * the tile and the notification over a phone that may be ringing all
     * afternoon, which is principle 2's failure and the one thing this function
     * exists to prevent; refusing costs a working snooze the user can re-arm
     * with one tap, and it resolves toward ending (SPEC.md D7).
     *
     * `PLATFORM_REFUSED` specifically, because the rule may well be on:
     * `STATE_TRUE` was accepted a moment ago. That is the retryable code, so
     * the caller keeps a release scheduled for the condition rather than
     * concluding there is nothing to come back for.
     */
    private fun confirmSilenced(ruleId: String, snoozed: Boolean, placeName: String): ZenOutcome {
        if (!snoozed) return ZenOutcome.Applied(ruleId)

        val lookup = runCatching { notificationManager.getAutomaticZenRule(ruleId) }
        val rule = lookup.getOrElse {
            Log.e(TAG, "Confirming the zen rule is on failed; refusing an arm we cannot verify.", it)
            return ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
        }
        return when {
            // Accepted a state change for a rule that isn't there. Nothing is
            // silencing the phone, and `NO_RULE` says so — an arm reported this
            // way is refused, and a release treats it as already over.
            rule == null -> {
                Log.e(TAG, "The zen rule accepted a state change but no longer exists.")
                ZenOutcome.NotApplied(ZenFailure.NO_RULE)
            }
            // Switched off by the user. Snoozemo does not turn it back on —
            // that switch is theirs (SPEC.md §5.1) — so the arm is refused and
            // the reason is surfaced.
            !rule.isEnabled -> {
                Log.e(TAG, "The zen rule is switched off, so arming would silence nothing.")
                // But put the condition back first. This runs *after* a
                // successful STATE_TRUE — that is what made the disabled rule
                // detectable — and refusing the arm here tears down the record
                // and the cap. Leaving the condition true would arm a trap: the
                // day the user re-enables the rule in Settings it starts
                // silencing the phone, with no snooze, no notification and no
                // alarm anywhere in the app that knows to end it.
                if (resetCondition(ruleId, placeName)) {
                    ZenOutcome.NotApplied(ZenFailure.RULE_DISABLED)
                } else {
                    // The reset was refused, so the trap is armed and this call
                    // cannot disarm it. `RULE_DISABLED` would be the wrong thing
                    // to say now: it means `nothingLeftToRelease`, which tells
                    // every release path that there is nothing to come back for
                    // — and there is. `PLATFORM_REFUSED` is the honest code and
                    // the useful one, because it is the retryable one: callers
                    // keep the record and the cap, and those are precisely what
                    // will drive the condition off later.
                    Log.e(TAG, "Could not reset the disabled rule's condition; keeping the retry.")
                    ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
                }
            }
            else -> ZenOutcome.Applied(ruleId)
        }
    }

    /**
     * Takes back a `STATE_TRUE` that [confirmSilenced] has decided must not
     * stand, retrying a few times before giving up.
     *
     * Retried at all because the call it is undoing *succeeded* moments ago on
     * the same rule — a refusal here is a transient the next attempt is likely
     * to get past, and each one is a single binder call off the arm path.
     * Bounded rather than persistent because this is the unwind of a refused
     * arm: nothing is waiting on it, and a loop that couldn't give up would
     * hold the wake-up that reached it.
     */
    private fun resetCondition(ruleId: String, placeName: String): Boolean {
        repeat(CONDITION_RESET_ATTEMPTS) {
            if (trySetState(ruleId, false, ZenTrigger.CONTEXT, placeName) is ZenOutcome.Applied) {
                return true
            }
        }
        return false
    }

    private fun trySetState(
        ruleId: String,
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
    ): ZenOutcome {
        val state = if (snoozed) Condition.STATE_TRUE else Condition.STATE_FALSE
        val summary = if (snoozed) "Snoozing at $placeName" else "Left $placeName"
        val condition = Condition(CONDITION_URI, summary, state, trigger.toConditionSource())

        return runCatching { notificationManager.setAutomaticZenRuleState(ruleId, condition) }
            .fold(
                onSuccess = { ZenOutcome.Applied(ruleId) },
                onFailure = { error ->
                    // The release path's worst case: if this throws while ending a
                    // snooze, the phone stays silent. Report it so the caller can
                    // retry and tell the user, rather than assuming success.
                    Log.e(TAG, "Setting the zen rule state to $snoozed failed.", error)
                    ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
                },
            )
    }

    override fun ruleId(): String? = store.ruleId()

    private fun buildRule(): AutomaticZenRule =
        AutomaticZenRule.Builder(ZenRule.NAME, CONDITION_URI)
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setZenPolicy(defaultPolicy())
            .setConfigurationActivity(configurationActivity)
            .setTriggerDescription(TRIGGER_DESCRIPTION)
            .setManualInvocationAllowed(true)
            .setEnabled(true)
            .build()

    /**
     * The shape most people already expect from DND, and one that keeps a
     * genuine emergency reachable (SPEC.md §5.5). Total silence is available in
     * settings but is deliberately not the default: defaulting a
     * location-triggered mechanism to "nothing gets through" is how someone
     * misses something that mattered.
     */
    private fun defaultPolicy(): ZenPolicy = ZenPolicy.Builder()
        .allowAlarms(true)
        .allowMedia(true)
        .allowSystem(true)
        .allowRepeatCallers(true)
        .build()

    private fun ZenTrigger.toConditionSource(): Int = when (this) {
        ZenTrigger.USER_ACTION -> Condition.SOURCE_USER_ACTION
        ZenTrigger.CONTEXT -> Condition.SOURCE_CONTEXT
    }

    companion object {
        /**
         * Guards [ensureRule]. Shared rather than per-instance because four
         * places construct a controller and they all target the same one rule.
         */
        private val RULE_CREATION = Any()

        /** Bounds [resetCondition]; small, because it is undoing a call that just worked. */
        private const val CONDITION_RESET_ATTEMPTS = 3

        private const val TAG = "ZenController"
        private val CONDITION_URI: Uri = Uri.parse(ZenRule.CONDITION_ID)
        private const val TRIGGER_DESCRIPTION = "While you're at a place you snoozed"

        /**
         * The settings screen the rule deep-links to, named by string for the
         * same reason the tile's trampoline intent is (`SnoozeTileService`):
         * `:app` depends on this module and not the reverse, so there is
         * nothing to reference. A fully-qualified class name is stable under
         * `applicationIdSuffix`, which the package name is not — the package
         * comes from the context at build time. `:app`'s tests pin this string
         * to the real class, so a rename cannot silently break the deep link.
         */
        const val CONFIGURATION_ACTIVITY_CLASS = "app.snoozemo.ui.MainActivity"

        /**
         * The controller as every production caller builds it, so modules that
         * cannot see `:app` — the tile, warming the rule while the shade opens
         * — construct the same one instead of a divergent copy.
         */
        fun default(context: Context): AndroidZenController {
            val app = context.applicationContext
            return AndroidZenController(
                context = app,
                store = PrefsZenRuleIdStore(app),
                configurationActivity = ComponentName(app.packageName, CONFIGURATION_ACTIVITY_CLASS),
                ringer = AudioRingerController.default(app),
            )
        }
    }
}
