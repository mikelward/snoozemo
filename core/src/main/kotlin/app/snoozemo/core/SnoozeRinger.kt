package app.snoozemo.core

/**
 * The device's three ringer states, ordered loudest first (SPEC.md §5.9).
 *
 * A domain enum rather than `AudioManager`'s `RINGER_MODE_*` ints, for the
 * reason every other platform value reaching this module is one: the decision
 * about what to do with the ringer belongs where a JVM test can reach it, and
 * `:dnd` stays the only place that knows which int means which (SPEC.md §11).
 */
enum class RingerMode {
    /** Audible: rings, and vibrates as well if the user's vibrate setting says so. */
    NORMAL,

    /** Silent, with vibration. */
    VIBRATE,

    /** Silent, without vibration. */
    SILENT,
    ;

    /**
     * Whether this mode is louder than [other].
     *
     * Declaration order *is* the ordering, loudest first, so `ordinal` answers
     * it — named rather than compared inline because the direction is
     * load-bearing in both decisions below, and `<` on an enum does not read
     * as "louder than" to anyone who has not just read this file.
     */
    fun isLouderThan(other: RingerMode): Boolean = ordinal < other.ordinal
}

/**
 * How loud a snooze is allowed to be (SPEC.md §5.9) — a **ceiling**, not a
 * value to force (maintainer, 2026-09-02).
 *
 * Do Not Disturb decides *what* reaches the user during a snooze; this decides
 * how loud the things that do reach them may be. They are separate axes in
 * Android, and neither `ZenPolicy` nor `ZenDeviceEffects` carries a
 * ring-or-vibrate choice — the global ringer mode is the only thing that can
 * express this, which is what [RingerHandover] exists to handle carefully.
 *
 * "Ceiling" has two consequences a reader would otherwise be surprised by:
 *
 * - A phone already quieter than the ceiling is **left alone**. Silent with
 *   [VIBRATE] chosen stays silent; raising it would be Snoozemo making a phone
 *   louder than its owner set it, which is not this setting's job.
 * - [VIBRATE] is a ceiling on the platform's terms too. `RINGER_MODE_VIBRATE`
 *   always vibrates the *ringer*, but a notification vibrates only if the
 *   user's own vibrate setting is on — so a message that gets through may
 *   arrive with no buzz at all. Accepted rather than worked around: there is no
 *   API to force it, and forcing it would be asserting a floor this setting
 *   deliberately does not have.
 */
enum class SnoozeRinger {
    /** No ceiling. Whatever gets through rings exactly as it would with no snooze. */
    RING,

    /** Nothing louder than a vibration. */
    VIBRATE,

    /** Nothing at all. */
    SILENT,
    ;

    /**
     * The loudest mode a snooze may leave the phone in, or null for [RING] —
     * which imposes nothing, and therefore never touches the ringer at all.
     */
    val ceiling: RingerMode?
        get() = when (this) {
            RING -> null
            VIBRATE -> RingerMode.VIBRATE
            SILENT -> RingerMode.SILENT
        }

    companion object {
        /**
         * **Vibrate** (maintainer, 2026-09-02).
         *
         * The opposite default to most of this app's switches, and deliberately:
         * the reason to reach for a snooze is usually that you do not want the
         * phone making noise where you are, and Do Not Disturb on its own still
         * rings for everyone it lets through. A default of [RING] would mean the
         * common case needed configuring before it behaved as expected, and
         * [SILENT] would make the priority senders the user chose unreachable —
         * which principle 1 rules out as a default even where it is a legitimate
         * choice.
         */
        val DEFAULT = VIBRATE

        /**
         * [name]'s constant, or [DEFAULT] for anything this version cannot read.
         *
         * A stored name this build does not know is a downgrade, or a constant
         * renamed under a record — and the honest answer to both is the default
         * rather than a throw on the arm path.
         */
        fun named(name: String?): SnoozeRinger = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The ringer Snoozemo has taken over for a running snooze, and what it owes
 * back (SPEC.md §5.9).
 *
 * Two values rather than one, because giving it back needs both answers: what
 * the phone was, and what Snoozemo set it to. Without the second there is no
 * way to tell "still as we left it" from "the user has changed it since", and
 * the app would put the ringer back over a choice somebody made deliberately
 * mid-snooze.
 */
data class BorrowedRinger(
    /** What the phone was in before the snooze, and what it goes back to. */
    val restoreTo: RingerMode,

    /**
     * What Snoozemo set it to, or null where the record cannot say.
     *
     * Null is not a normal state — a borrow writes both values in one commit —
     * but a record written by a build whose constants have since been renamed
     * reads this way, and the safe reading of "cannot verify" is to hand the
     * ringer back anyway rather than leave a phone quiet on the strength of an
     * unreadable field.
     */
    val setTo: RingerMode?,

    /**
     * Whether the mode change was confirmed after this record was written.
     *
     * The record lands *before* the ringer moves — that ordering is what buys
     * the way back — so there is a window in which a loan exists and the change
     * has not happened (Codex, PR #176). A process death there used to leave the
     * snooze loud for its whole length: a restore found a loan outstanding, and
     * "a loan is never overwritten" answered by doing nothing at all.
     *
     * So an unapplied loan is *finished* rather than skipped. Re-writing a mode
     * that did in fact land is harmless, which is what makes this safe in the
     * other direction: a write that succeeded and died before its marker is
     * simply set again.
     *
     * Defaults to true, so a record from a build without the marker keeps
     * today's behavior rather than being re-applied on sight.
     */
    val applied: Boolean = true,
)

/** What to do about the ringer, and what the borrow record becomes. */
sealed interface RingerStep {

    /**
     * Take the ringer over: record [borrowed] **first**, then set
     * [BorrowedRinger.setTo].
     *
     * That order is the whole safety property. A mode changed before the record
     * lands is a phone left quiet with nothing anywhere naming what it should
     * go back to; a record written before a change that then fails is a stale
     * loan, which the next give-back re-verifies against the live mode and
     * discards. One of those is recoverable and the other is principle 1's
     * failure.
     */
    data class Borrow(val borrowed: BorrowedRinger) : RingerStep

    /** Set [mode], then drop the record — in that order, for the same reason. */
    data class GiveBack(val mode: RingerMode) : RingerStep

    /**
     * Drop the record and leave the ringer alone: the user has moved it since,
     * so it is theirs now and Snoozemo has nothing left to give back.
     */
    data object Disown : RingerStep

    /** Nothing to do. */
    data object Nothing : RingerStep
}

/**
 * What the ringer owes once the zen rule write has answered (SPEC.md §5.9).
 *
 * The ringer is nested inside the rule in both directions — arming lowers it
 * only once the rule is confirmed on, releasing hands it back *before* the rule
 * goes off — so what to do next depends on which direction this was and what
 * the platform said. Pure and here rather than inline in the adapter because
 * every interesting case is a failure the adapter cannot manufacture under
 * Robolectric, and each one was found by review rather than by a test.
 */
enum class RingerFollowUp {
    /** The rule is on: lower the ringer to the chosen ceiling. */
    QUIET,

    /**
     * The snooze is over: the ringer goes back and the ceiling is forgotten.
     *
     * Reached from a *release* that confirmed the rule is off, and from an
     * **arm** that established there was never anything silencing the phone —
     * which ends the snooze everywhere in the app without a second zen call, so
     * nothing else would ever give a loan back.
     */
    HAND_BACK_AND_FORGET,

    /**
     * A release the platform refused, which keeps the snooze running: the
     * ringer already handed back has to go back down.
     */
    RE_QUIET,

    /**
     * Leave the ringer alone. Either an arm the platform refused over a loan it
     * did not take — so the loan is still owed to a snooze that is still
     * running — or a refused release whose hand-back found the ringer had
     * become the user's own.
     */
    NOTHING,

    /**
     * Hand back **this arm's own borrow**, and keep the ceiling.
     *
     * A refused arm stays armed for the cap to retry, but under the ceiling-
     * first order (SPEC.md §5.9) it has already taken the ringer on the
     * strength of a rule write that did not land — which the old order could
     * not do, because it never got that far. Giving it back keeps what the user
     * observes unchanged: a snooze that is not being enforced leaves the ringer
     * where they had it, and a retry that succeeds borrows afresh.
     *
     * Distinct from [HAND_BACK_AND_FORGET], which is an *ending* and drops the
     * ceiling with the loan. Here the snooze is still running, and a live
     * snooze with its ceiling forgotten could not report the shortfall it is
     * now certainly having.
     */
    HAND_BACK,
}

/**
 * The follow-up [outcome] calls for, having been asked to set snoozed
 * [snoozed].
 *
 * [ringerDisowned] is whether the hand-back that already ran on the release path
 * recognized the ringer as the user's own (SPEC.md §5.9 rule 4). It is an input
 * here rather than a conditional at the call site because that is the only way
 * it is testable: a release the platform *refuses* cannot be produced through
 * the adapter under Robolectric, which is how this whole family of cases keeps
 * arriving as review findings rather than test failures.
 *
 * [freshlyBorrowed] is whether *this* arm took the ringer, as opposed to
 * finding a loan the running snooze already owned — and it is the difference
 * between [RingerFollowUp.HAND_BACK] and [RingerFollowUp.NOTHING] on a refused
 * arm (Codex, PR #259). A re-assertion writes nothing for an outstanding loan
 * and *finishes* rather than takes one whose write never landed, so handing
 * back on either would undo a live snooze's ceiling on the strength of a
 * refusal that keeps that very snooze armed. An input for the same reason as
 * the one above: `PLATFORM_REFUSED` is the one refusal the adapter cannot be
 * made to produce under Robolectric.
 */
fun ringerFollowUp(
    snoozed: Boolean,
    outcome: ZenOutcome,
    ringerDisowned: Boolean = false,
    freshlyBorrowed: Boolean = false,
    nothingEnforcing: Boolean = false,
): RingerFollowUp = when {
    snoozed && outcome is ZenOutcome.Applied -> RingerFollowUp.QUIET
    // Both directions of "nothing of ours is silencing the phone" agree: no
    // policy access, no rule, or the rule already switched off.
    outcome.confirmsNothingSilencing -> RingerFollowUp.HAND_BACK_AND_FORGET
    snoozed -> if (freshlyBorrowed) RingerFollowUp.HAND_BACK else RingerFollowUp.NOTHING
    // The snooze runs on, so the ceiling would go back down — but re-applying
    // it here would find no loan, borrow again, and lower the very ringer the
    // hand-back had just left as theirs (Codex, PR #176).
    ringerDisowned -> RingerFollowUp.NOTHING
    // The same conclusion from the other direction (Codex, PR #260). The
    // re-quiet above rests on the snooze still being *enforced* — a refused
    // release means the rule still exists and may accept the change next time,
    // so the phone is still quiet and the ceiling still belongs under it. Where
    // the caller already knows our rule is deactivated, that premise is false:
    // lowering the ringer again would leave the phone under a ceiling with
    // nothing silencing it, until a retry or the cap. Principle 1 says leave it
    // audible, and the hand-back this release already ran is what does.
    nothingEnforcing -> RingerFollowUp.NOTHING
    else -> RingerFollowUp.RE_QUIET
}

/**
 * What an arm that had to un-stick its own rule may claim (SPEC.md §5.9).
 *
 * A ceiling write that *finishes an earlier loan* runs with our rule possibly
 * active, and the platform's coupling turns Do Not Disturb off in response. So
 * that arm turns the rule off and on again — and when the "on" half does not
 * take, the phone is audible and the arm must not report something that keeps
 * the snooze armed over it.
 *
 * Only one failure needs saying differently. Every other [ZenFailure] already
 * reports `nothingLeftToRelease`, so it already ends the snooze; only
 * [ZenFailure.PLATFORM_REFUSED] keeps it, on the promise that a rule which
 * still exists may accept the change next time. After this write that promise
 * can be false in the worst way.
 *
 * But the same code arrives from a second place with the opposite need: a rule
 * the user disabled whose condition could not be reset, where the record is
 * what will eventually drive that condition off. Ending there leaves a trap —
 * the day they re-enable the rule it starts silencing the phone with nothing in
 * the app that knows to end it, which is principle 1's failure and the worse of
 * the two.
 *
 * The code cannot tell those apart, so this does not try to: [activation] is
 * the platform's own answer, read only on this branch. `INACTIVE` or `MISSING`
 * is the first case; `DISABLED` is the second.
 *
 * **An accepted re-arm is checked too, but only when [resetLanded] is false**
 * (Codex, PR #259). The un-stick is an off-then-on pair, and the off half is
 * what makes the on half mean anything: a deactivated rule stays deactivated
 * until its owner sets `STATE_FALSE` first, so a `STATE_TRUE` accepted after a
 * refused reset lands on a rule the platform goes on ignoring — and the arm's
 * own confirmation only checks that the rule exists and is enabled, which a
 * deactivated one still is. Reported `Applied`, that is `Snoozing` over an
 * audible phone.
 *
 * It is gated on the reset rather than asked of every un-stuck arm because a
 * read taken microseconds after a successful `STATE_TRUE` is where a lagging
 * answer would do most damage, so the platform is only asked where there is
 * already reason to doubt the arm.
 *
 * **And where it cannot answer, that arm is reported unconfirmed rather than
 * either applied or ended.** `PLATFORM_REFUSED` is the third answer and the
 * only true one there: the snooze stays, the cap keeps retrying, the release
 * still runs, and a later re-assertion can un-stick the rule properly — where
 * `Applied` would stop the retry over a snooze that may not exist and an ending
 * would erase the record of one that may, leaving Do Not Disturb on with
 * nothing that knows to turn it off.
 *
 * **[reArmAccepted] is what finally parts the two**, and it is the fact rather
 * than an inference from one (Codex, PR #259, the fifth round on this handling).
 * The trap needs a `STATE_TRUE` the platform *accepted* — that is what sets the
 * condition, and the refusal comes afterwards, from the arm's own confirmation
 * noticing the rule is switched off. A refusal over a write that never landed
 * cannot have armed anything. So a refusal after an accepted write keeps its
 * retry unconditionally, whatever any read says, and only the other kind is
 * eligible to be called an ending.
 *
 * With that in hand [ZenRuleActivation.UNKNOWN] can fall back to
 * [resetLanded] — the only evidence left where the platform offers none, and
 * API 34 offers none ever, since below API 35 neither that read nor the
 * `DEACTIVATED` broadcast exists. A reset that landed is knowledge of its own:
 * we turned the rule off and the platform accepted, so a re-arm that wrote
 * nothing leaves nothing enforcing, and keeping the snooze armed would report
 * `Snoozing` over a phone this path made audible. Where neither landed there is
 * no evidence at all and the retry stands.
 *
 * The earlier version of this traded the trap for that case on API 34 and said
 * so. It no longer has to: the two are told apart by what was written, which
 * every platform can answer.
 *
 * **The requirement outlives the arm, so it is state rather than an
 * inference** (Codex, PR #260; maintainer's call, 2026-09-11). The un-stick
 * fires on a one-shot signal — a ceiling write that finished an earlier loan —
 * and that write marks the loan applied, so the retry this function's own
 * `PLATFORM_REFUSED` asks for would see an ordinary re-assertion and report
 * `Applied` over a rule the platform is still ignoring. The caller records the
 * requirement before the cycle and clears it only on an `Applied` outcome —
 * which, after the checks below, is the one answer that means the rule really
 * is on; the alternative was cycling on *every*
 * re-assertion, which needs no state and pays the flicker on every cap re-arm
 * and every restore instead of only on the rare finishing arm.
 *
 * **The retry it asks for is the next arm reading the requirement off disk**,
 * so a write of that record which did not land leaves the promise unbacked.
 * That residual is open and recorded in `TODO.md` rather than patched: both
 * ways out of it have now been tried in review and each broke the other's
 * case.
 *
 * All of it arrives as inputs rather than as conditionals at the call site
 * because that is the only way any of it is testable: `PLATFORM_REFUSED` — the
 * one refusal that keeps a snooze armed — cannot be produced through the
 * adapter under Robolectric, which is how this family of cases kept arriving as
 * review findings rather than test failures.
 */
fun unstuckArmOutcome(
    reArmed: ZenOutcome,
    unstuck: Boolean,
    resetLanded: Boolean,
    reArmAccepted: Boolean,
    activation: () -> ZenRuleActivation,
): ZenOutcome {
    if (!unstuck) return reArmed
    val worthChecking = when (reArmed) {
        is ZenOutcome.Applied -> !resetLanded
        // A refusal *after* an accepted write may have left a condition set on
        // a rule the user switched off, and the record is what will eventually
        // drive it back off. That one is never an ending.
        is ZenOutcome.NotApplied ->
            reArmed.reason == ZenFailure.PLATFORM_REFUSED && !reArmAccepted
    }
    if (!worthChecking) return reArmed
    val activation = activation()
    return when (activation) {
        ZenRuleActivation.ACTIVE -> reArmed
        ZenRuleActivation.INACTIVE, ZenRuleActivation.MISSING ->
            ZenOutcome.NotApplied(ZenFailure.RULE_TURNED_OFF)
        // **An un-stick nobody could verify is never reported as confirmed**
        // (Codex, PR #259, the sixth round here). The remaining case is an
        // accepted `STATE_TRUE` over a reset that did not land, which the
        // platform will not adjudicate — and always will not, below API 35. It
        // is one of two things: a rule the ringer write deactivated and this
        // write could not revive, or one that really did arm because nothing
        // was active to deactivate.
        //
        // Neither `Applied` nor an ending is honest about that. `Applied` stops
        // the retry and claims a snooze that may not exist; an ending erases
        // the record of one that may, leaving Do Not Disturb on with nothing
        // that knows to turn it off — principle 1's failure, and the reason the
        // earlier version reached for `Applied` here. `PLATFORM_REFUSED` is the
        // third answer and the only true one: *not confirmed, come back for it*.
        // The snooze stays, the cap keeps retrying, the release still runs, and
        // a later re-assertion can un-stick the rule properly.
        // A rule the user switched off keeps its retry **only while there is a
        // condition left to drive off**. That is what the retry is for: a
        // disabled rule whose condition is still set starts silencing the phone
        // the day they re-enable it, and the record is the only thing that
        // would ever clear it.
        //
        // Where our own reset landed and no re-arm was accepted, the condition
        // is already clear, so there is no trap to keep the record for — and a
        // disabled rule enforces nothing, so keeping the snooze would show
        // `Snoozing` over a phone that is not quiet (Codex, PR #260). That is
        // the failure the ending exists to report, and `RULE_DISABLED` is both
        // its honest reason and one the release paths already treat as over.
        //
        // Only this arm's own reset counts here. `confirmSilenced` runs a
        // `STATE_FALSE` of its own when an accepted `STATE_TRUE` turns out to
        // have hit a disabled rule, but it reports `RULE_DISABLED`, which never
        // reaches this branch — the check above admits only `PLATFORM_REFUSED`
        // (Codex, PR #260, after an earlier round added an inference for it
        // that could not fire).
        ZenRuleActivation.DISABLED ->
            if (resetLanded && !reArmAccepted) {
                ZenOutcome.NotApplied(ZenFailure.RULE_DISABLED)
            } else {
                reArmed
            }
        ZenRuleActivation.UNKNOWN -> when {
            reArmed is ZenOutcome.Applied -> ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
            resetLanded -> ZenOutcome.NotApplied(ZenFailure.RULE_TURNED_OFF)
            else -> reArmed
        }
    }
}

/**
 * Who owns the ringer while a snooze runs (SPEC.md §5.9), as a pure decision
 * over the setting, the live mode, and the outstanding loan.
 *
 * Pure and in `:core` for the reason `RuleOwnership` and `ClockChange` are:
 * every interesting case here is a two- or three-way disagreement between what
 * was recorded and what the device says now, and those are cheap to enumerate
 * in a JVM test and expensive to reach on a device.
 */
object RingerHandover {

    /**
     * What arming should do, having read [current] and found [borrowed]
     * outstanding.
     *
     * An outstanding loan wins over everything, and that is not an
     * optimization: arming is re-asserted on restore and by the cap alarm's own
     * re-arm, so a second borrow would overwrite `restoreTo` with the *quiet*
     * mode Snoozemo itself set — the phone's own way back, replaced by where it
     * already is, and no later give-back could tell.
     *
     * A [current] that could not be read also declines to borrow. There is
     * nothing to record as the way back, and taking the ringer without one is
     * exactly the state this whole mechanism exists to never be in.
     */
    fun quiet(
        setting: SnoozeRinger,
        current: RingerMode?,
        borrowed: BorrowedRinger?,
    ): RingerStep {
        if (borrowed != null) {
            // Never overwritten — a second borrow would record the quiet mode as
            // the way back — *except* where the recorded borrow never actually
            // happened, which is finished rather than left (Codex, PR #176).
            //
            // "Never happened" is evidence, not an assumption: the live mode has
            // to be exactly where the record says it was found. Anything else
            // and finishing the borrow would move a ringer somebody else moved
            // first — a pending `VIBRATE` borrow raising a phone its owner has
            // since set to silent, which is the leave-quieter-alone rule broken
            // by the very mechanism meant to honor it (Codex, PR #176). Where
            // the mode is unreadable, has already reached the ceiling, or sits
            // at some third value, this takes nothing: the release path's own
            // give-back and disown rules read the same record and answer it
            // correctly, and the card meanwhile reports the ceiling honestly as
            // not holding.
            val unfinished = !borrowed.applied &&
                borrowed.setTo != null &&
                current == borrowed.restoreTo
            return if (unfinished) RingerStep.Borrow(borrowed) else RingerStep.Nothing
        }
        val ceiling = setting.ceiling ?: return RingerStep.Nothing
        if (current == null) return RingerStep.Nothing
        // Already at or below the ceiling: nothing to take, and nothing owed.
        // The ceiling is not a target (`SnoozeRinger`) — a silent phone under a
        // `VIBRATE` snooze stays silent.
        if (!current.isLouderThan(ceiling)) return RingerStep.Nothing
        return RingerStep.Borrow(BorrowedRinger(restoreTo = current, setTo = ceiling))
    }

    /**
     * What releasing should do, given the outstanding [borrowed] loan and the
     * live [current] mode.
     *
     * An unreadable [current] hands the ringer back rather than holding it. It
     * means the user's own change cannot be ruled out — but the two mistakes are
     * not priced alike: putting the ringer back over a deliberate change is an
     * annoyance the user can undo in one gesture, and *not* putting it back
     * leaves a phone silent after a snooze it was told had ended, which is
     * principle 1's failure.
     */
    fun giveBack(borrowed: BorrowedRinger?, current: RingerMode?): RingerStep {
        if (borrowed == null) return RingerStep.Nothing
        if (current == null) return RingerStep.GiveBack(borrowed.restoreTo)
        // Unverifiable for a different reason — the record cannot say what was
        // set — and answered the same way, per `BorrowedRinger.setTo`.
        val setTo = borrowed.setTo ?: return RingerStep.GiveBack(borrowed.restoreTo)
        if (current != setTo) return RingerStep.Disown
        return RingerStep.GiveBack(borrowed.restoreTo)
    }
}

/**
 * How long to wait before asking for the ringer back again, and when to stop
 * asking (SPEC.md §5.9).
 *
 * A hand-back that fails every immediate attempt asks for a durable retry, and
 * without this that retry re-armed itself at a fixed minute forever (Codex,
 * PR #176). Where the refusal is *permanent* — a fixed-volume policy appeared,
 * notification-policy access was revoked — nothing about the next attempt is
 * different, so a fixed interval buys a wake-up a minute, indefinitely, for a
 * write that cannot land. That is a battery cost with no upside (SPEC.md §9)
 * and no moment at which the app admits it is stuck.
 *
 * So the interval doubles and the sequence ends. Ending is not giving the
 * ringer up: the loan stays on disk, and the next snooze's release, the next
 * process start, and the next time the app is opened all still retry from it.
 * What ends is the *scheduling*, which is the only part that costs anything.
 */
object RingerHandBack {

    /** The first pause, short because a transient refusal usually clears fast. */
    const val FIRST_RETRY_MILLIS = 60_000L

    /** The ceiling on the doubling, so a long sequence still checks hourly. */
    const val LONGEST_RETRY_MILLIS = 60L * 60_000L

    /**
     * How many scheduled retries a single loan gets. Ten spans about five
     * hours with the doubling below — long enough to outlast a transient
     * refusal, short enough that a permanent one stops costing wake-ups the
     * same day.
     */
    const val MAX_RETRIES = 10

    /**
     * The pause after [failures] failed hand-backs, or null once the sequence
     * is spent.
     *
     * [failures] counts hand-backs, not scheduling attempts: it is the loan's
     * own persisted tally, so a process that dies between two alarms resumes
     * the sequence instead of restarting it.
     */
    fun retryDelayMillis(failures: Int): Long? {
        if (failures < 1) return FIRST_RETRY_MILLIS
        if (failures > MAX_RETRIES) return null
        // `toLong()` before the shift, and bounded by the cap rather than by
        // the shift width: `1 shl 31` overflows, and the cap is reached long
        // before that anyway.
        val doubled = FIRST_RETRY_MILLIS * (1L shl (failures - 1).coerceAtMost(20))
        return doubled.coerceAtMost(LONGEST_RETRY_MILLIS)
    }
}
