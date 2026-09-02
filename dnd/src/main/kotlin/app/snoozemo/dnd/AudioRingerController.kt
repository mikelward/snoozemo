package app.snoozemo.dnd

import android.content.Context
import android.media.AudioManager
import android.util.Log
import app.snoozemo.core.BorrowedRinger
import app.snoozemo.core.RingerHandBack
import app.snoozemo.core.RingerHandover
import app.snoozemo.core.RingerMode
import app.snoozemo.core.RingerStep
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.SnoozeRinger

/**
 * The only place in the app that touches `AudioManager` (SPEC.md §11).
 *
 * A thin adapter over [RingerHandover]: read the live mode, ask `:core` what to
 * do, write the record, write the mode, verify, report. Every branch that
 * decides anything lives in the pure decision so it can be enumerated by a JVM
 * test; what is here is the platform contact and the ordering.
 *
 * **The permission is the one the app already has.** `setRingerMode`'s own
 * reference says that from N onward "ringer mode adjustments that would toggle
 * Do Not Disturb are not allowed unless the app has been granted Notification
 * Policy Access" — which is `ACCESS_NOTIFICATION_POLICY` (SPEC.md §5.2), the
 * grant without which there is no snooze to be quiet for. So this adds no
 * manifest permission and no second prompt. Android 15's restriction on
 * changing global Do Not Disturb names `setInterruptionFilter` and
 * `setNotificationPolicy` only; the ringer is a separate axis and unaffected.
 */
class AudioRingerController(
    private val context: Context,
    private val setting: SnoozeRingerStore,
    private val loans: RingerLoanStore,
) : RingerController {

    private val audioManager: AudioManager?
        get() = runCatching { context.getSystemService(AudioManager::class.java) }
            .getOrElse {
                Log.e(TAG, "Reading AudioManager failed.", it)
                null
            }

    /**
     * Serialized process-wide, because the loan is a singleton and every
     * decision over it is a read-then-write.
     *
     * Two threads reach this at once in a case that is routine rather than
     * exotic: a cold tile tap runs the arm while the start-up hand-back check
     * runs on its own thread (Codex, PR #176). Without the lock, that check can
     * read a *stale* loan, see no record yet, and then hand the ringer back
     * after the arm has already declined to borrow over that same loan —
     * leaving a running snooze with no ceiling at all.
     *
     * A lock across binder calls, which is normally worth avoiding, and safe
     * here for the reason `AndroidZenController.RULE_CREATION` is: nothing on
     * it runs between the tap and the rule going on. Everything here happens
     * *after* the rule is confirmed on.
     */
    override fun quiet(): RingerOutcome = synchronized(RINGER) { quietLocked() }

    override fun giveBack(): RingerOutcome = synchronized(RINGER) { giveBackLocked() }

    override fun forgetCeiling() = synchronized(RINGER) { rememberChoice(null) }

    /**
     * Hands the ringer back only if [snoozeRunning] says nothing is holding it.
     *
     * The predicate is evaluated **inside** the lock, which is the whole point:
     * the start-up check's "is a loan outstanding" and "is a snooze running"
     * have to be one atomic decision with respect to an arm, or the two can
     * interleave into a snooze with no ceiling (see [RINGER]). Passed in rather
     * than read here because the answer lives in `:app`'s record, which `:dnd`
     * cannot see.
     */
    fun giveBackIfIdle(snoozeRunning: () -> Boolean): RingerOutcome = synchronized(RINGER) {
        // The same ladder the release path takes, and for a sharper reason here
        // (Codex, PR #176): this is what the retry alarm's own receiver runs, so
        // a one-shot alarm that reaches an unreadable record is already spent —
        // returning without asking for a successor leaves nothing scheduled at
        // all.
        val loan = readLoan() ?: return@synchronized escalateUnreadableState()
        val running = runCatching(snoozeRunning).getOrElse {
            SnoozeDebugLog.failure(it, "ringer: could not tell whether a snooze is running; leaving the loan alone")
            // Unknown is answered as "running", which keeps a phone that is
            // meant to be quiet quiet — but it is *not* an answer, so it also
            // asks for a successor (Codex, PR #176). Left as a bare
            // `Untouched`, an unreadable record reached from the one-shot retry
            // alarm spent that alarm and scheduled nothing, so a genuinely
            // stranded loan under it had nothing left coming.
            if (loan.borrowed != null) escalateUnreadableState("whether a snooze is running is unreadable")
            return@synchronized RingerOutcome.Untouched
        }
        if (running) {
            // A snooze this process is about to restore, or one whose release
            // is still being retried. Its own release gives the ringer back;
            // stepping in here would un-quiet a phone still meant to be quiet.
            SnoozeDebugLog.event("ringer: a live snooze holds the ringer; left to its release")
            return@synchronized RingerOutcome.Untouched
        }
        // Dropped here, before the loan is even looked at, because the predicate
        // has just established that nothing is running: the choice record is
        // then stale whatever the loan says, and every path out of this function
        // has to drop it (Codex, PR #176, twice). [giveBackLocked] deliberately
        // leaves it — that runs on the release path, where a refused *rule*
        // write keeps the snooze alive — and nothing calls `forgetCeiling` for
        // this one, so a hand-back here used to resolve the loan and leave the
        // ceiling behind for the next arm to adopt as its own.
        rememberChoice(null)
        // Asked *after* the predicate, unlike the loan-first shape this had at
        // first: a snooze that never borrowed still records a choice, so the
        // no-loan case had something to clear even before the line above moved.
        if (loan.borrowed == null) return@synchronized RingerOutcome.Untouched
        SnoozeDebugLog.event("ringer: a snooze ended without handing it back; doing it now")
        giveBackLocked()
    }

    /**
     * The live mode when the phone is louder than the chosen ceiling, or null
     * when it is at or below it — the observed answer to "is the ceiling
     * actually holding", for the ongoing notification to say so (principle 2).
     *
     * The *mode* is read live rather than remembered, deliberately: a stored
     * "the borrow was refused" would go stale the moment the user turned the
     * ringer back up mid-snooze, and reading it catches that case for free. The
     * *ceiling* it is judged against comes from the choice recorded when this
     * snooze armed — see [RingerLoanStore.activeChoice] for why neither the loan
     * nor the live setting can stand in for it.
     *
     * Null where no ceiling is in force, or where the record itself could not be
     * read — with nothing to judge against there is nothing to claim.
     */
    fun shortfall(): RingerShortfall? {
        // The ceiling **in force for the running snooze**, which comes from its
        // own record and neither from the loan's `setTo` nor from what the
        // setting says now (Codex, PR #176). A choice changed mid-snooze governs
        // the *next* snooze, so reading the setting made the card lie in both
        // directions; and a ceiling can be in force with no loan at all — a
        // phone already quiet enough never borrowed, and a *refused* change is
        // exactly the case that has to be reported.
        // The choice record only, for the reason `inForce` gives: a loan says
        // nothing about which snooze it belongs to.
        val ceiling = runCatching { loans.activeChoice()?.ceiling }.getOrNull() ?: return null
        // Unreadable is its own answer rather than "no shortfall": arming faced
        // the same unreadable mode and declined to borrow, so a ceiling that is
        // in force is certainly not holding (Codex, PR #176).
        val current = currentMode() ?: return RingerShortfall.Unknown
        return RingerShortfall.Louder(current).takeIf { current.isLouderThan(ceiling) }
    }

    private fun quietLocked(): RingerOutcome {
        val loan = readLoan() ?: return RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED)
        val chosen = inForce() ?: return RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED)

        // Before any binder call, so choosing `Ring` costs the arm path nothing
        // at all — not a mode read, not a fixed-volume check.
        if (chosen.ceiling == null && loan.borrowed == null) {
            SnoozeDebugLog.event("ringer: no ceiling chosen; left as it is")
            return RingerOutcome.Untouched
        }

        return when (val step = RingerHandover.quiet(chosen, currentMode(), loan.borrowed)) {
            is RingerStep.Borrow -> borrow(step.borrowed, chosen)
            // The three "correct, and say which" cases: a loan already
            // outstanding (a re-asserted arm), a phone already at or below the
            // ceiling, or a mode that could not be read so there was no way
            // back to record.
            RingerStep.Nothing -> {
                SnoozeDebugLog.event("ringer: nothing to take for a ${chosen.name} ceiling")
                RingerOutcome.Untouched
            }
            // Neither is reachable from `quiet` — it never gives back or
            // disowns — and both are named rather than defaulted so that adding
            // a step later cannot silently fall through the arm path.
            is RingerStep.GiveBack, RingerStep.Disown -> {
                SnoozeDebugLog.warning("ringer: arming was handed $step, which it cannot act on")
                RingerOutcome.Untouched
            }
        }
    }

    private fun giveBackLocked(): RingerOutcome {
        // An unreadable record is a **refused** hand-back, not a finished one:
        // there may well be a loan under it, and nothing else would ever come
        // back for it — the release that reaches this ignores the refusal, turns
        // the rule off and forgets the ceiling, so a phone already lowered to
        // vibrate would stay there with no alarm and nothing to say so (Codex,
        // PR #176). So it takes the same durable ladder a refused write does.
        val loan = readLoan() ?: return escalateUnreadableState()
        // The choice is deliberately **not** cleared here. This runs before the
        // zen rule goes off, and a refused rule write keeps the snooze running
        // (Codex, PR #176) — a live snooze with its ceiling forgotten could not
        // report the shortfall it is now certainly having, and its next restore
        // would adopt the setting meant for the following snooze.
        // `forgetCeiling` is called once the release is confirmed.
        // No loan, no binder calls: this runs on every release and as a
        // start-up reconcile, and the overwhelmingly common answer is "nothing
        // was ever taken".
        val borrowed = loan.borrowed ?: return RingerOutcome.Untouched

        return when (val step = RingerHandover.giveBack(borrowed, currentMode())) {
            is RingerStep.GiveBack -> release(step.mode)
            RingerStep.Disown -> {
                // Their ringer now, so the record goes and the mode stays. A
                // failed clear is retried rather than shrugged off (Codex,
                // PR #176): "the next give-back disowns it again" holds only
                // while the live mode still differs from `setTo`, and a user who
                // later picks that same mode turns the zombie loan into a
                // hand-back that overrides them.
                if (!loans.clearContained()) {
                    askForCleanupRetry("the user moved it and the loan could not be dropped")
                }
                // Their ringer, so nothing is owed and any notice is stale.
                reportRingerStuck(false)
                SnoozeDebugLog.event("ringer: moved by the user during the snooze; left as they set it")
                RingerOutcome.Disowned
            }
            RingerStep.Nothing -> RingerOutcome.Untouched
            is RingerStep.Borrow -> {
                SnoozeDebugLog.warning("ringer: releasing was handed $step, which it cannot act on")
                RingerOutcome.Untouched
            }
        }
    }

    /**
     * Record first, then set, then verify — the order [RingerStep.Borrow]
     * documents, with each failure undoing exactly what it has to.
     */
    private fun borrow(borrowed: BorrowedRinger, chosen: SnoozeRinger): RingerOutcome {
        val manager = audioManager ?: return refuse(RingerFailure.PLATFORM_REFUSED, "no AudioManager")
        // Asked only here, on the one branch that is about to write. A device
        // with a fixed volume policy refuses ringer changes outright, so
        // recording a loan against a change that cannot happen would leave a
        // record owed for a mode nothing moved.
        if (runCatching { manager.isVolumeFixed }.getOrDefault(false)) {
            return refuse(RingerFailure.VOLUME_FIXED, "the device has a fixed volume policy")
        }
        // Recorded as **not yet applied**, so a process death in the window
        // between this write and the mode change leaves something that can be
        // finished rather than a loan a restore would skip (Codex, PR #176).
        if (!loans.record(borrowed.copy(applied = false))) {
            // `commit` returning false still updated the in-memory map, so this
            // process now holds a loan the disk does not (Codex, PR #176). Left
            // there, a service recreated without the process dying would find an
            // unapplied loan over an unmoved ringer, *finish* the borrow, and
            // quiet the phone — and the next process death would then reload a
            // disk with no loan at all, which is a quiet phone with nothing
            // anywhere that knows what to put back. Dropping it costs this arm
            // its ceiling, which is the refusal we are already reporting.
            loans.clearContained()
            return refuse(RingerFailure.NOT_RECORDED, "the way back could not be stored")
        }
        val target = borrowed.setTo
            ?: return refuse(RingerFailure.PLATFORM_REFUSED, "no ceiling to set").also { loans.clearContained() }

        // `REFUSED` only, never `UNVERIFIED`: an unconfirmed borrow keeps its
        // loan, because if the write did land, clearing it would leave the phone
        // quiet with no way back. A later give-back re-reads the live mode and
        // disowns the loan if it turns out nothing was ever taken.
        val written = write(manager, target)
        if (written == Written.REFUSED) {
            // Nothing was taken, so nothing is owed. Left behind, the loan would
            // put the ringer back to a mode it never left on the next release —
            // audible rather than silent, but still a change the user did not
            // ask for.
            if (!loans.clearContained()) {
                SnoozeDebugLog.warning("ringer: a refused change left a loan behind; it is re-checked against the live mode next time")
            }
            return RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED)
        }
        // A **confirmed** write only, because the marker's whole job is to say
        // the change really happened (Codex, PR #176). An unverified one keeps
        // `applied = false`: if the setter silently did nothing while the
        // read-back threw, a marker written here would make every later
        // re-assertion see an applied loan and take no action at all, leaving
        // the phone above the ceiling for the whole snooze. Unmarked, the next
        // re-assertion finishes the borrow — one redundant set of a mode that
        // may already be right, which is the cheaper of the two.
        if (written == Written.UNVERIFIED) {
            SnoozeDebugLog.warning(
                "ringer: the ${chosen.name} ceiling was set but could not be read back; a re-assertion finishes it",
            )
        } else if (!runCatching { loans.markApplied() }.getOrDefault(false)) {
            // A lost marker costs one redundant re-write on the next
            // re-assertion, which is why this is contained rather than checked.
            SnoozeDebugLog.warning("ringer: the applied marker was not stored; a re-assertion re-sets the same mode")
        }
        SnoozeDebugLog.event(
            "ringer: ${chosen.name} ceiling applied — ${target.name}, back to ${borrowed.restoreTo.name} at the end",
        )
        return RingerOutcome.Set(target)
    }

    /**
     * Set, then drop the record — and on a refusal keep it, so a retry still can.
     *
     * Retried here rather than only by the next start-up or the next snooze
     * (Codex, PR #176). Nothing schedules work from the loan once a release
     * completes: the record is erased and the alarms are canceled, so a process
     * that stays alive afterwards may never reach the start-up check, and the
     * phone would sit quiet until some later snooze ended. Bounded rather than
     * persistent because a refusal here is almost certainly transient — the
     * borrow proved this device accepts the call — and because this runs on the
     * release path, where a loop that could not give up would hold the wake-up
     * that reached it. `resetCondition` is retried for the same reason and in
     * the same shape.
     */
    private fun release(mode: RingerMode): RingerOutcome {
        // Through the same escalation as a refused write, not a plain refusal
        // (Codex, PR #176): the zen release goes ahead either way and takes the
        // record and every other alarm with it, so a give-back that returns
        // without scheduling anything is a phone left quiet with nothing
        // watching. The cause differs; what is owed does not.
        val manager = audioManager
            ?: return escalate(mode, "there was no AudioManager to hand the ringer back with")
        // `SET` only: an unconfirmed hand-back keeps the loan and retries,
        // because the setter can silently do nothing and clearing the loan on
        // that would leave the phone quiet with nothing left that knows better
        // (Codex, PR #176).
        //
        // `any` rather than `repeat`, because it short-circuits: `return@repeat`
        // is a continue, so the loop would go on setting a ringer it had
        // already put back.
        val handedBack = (1..HAND_BACK_ATTEMPTS).any { write(manager, mode) == Written.SET }
        if (!handedBack) {
            return escalate(mode, "$HAND_BACK_ATTEMPTS writes were refused")
        }
        if (!loans.clearContained()) {
            askForCleanupRetry("handed back to ${mode.name} but the loan could not be dropped")
        }
        // Whether or not a notice is showing: nothing is owed now, and this is
        // the only path that can say so.
        reportRingerStuck(false)
        SnoozeDebugLog.event("ringer: handed back to ${mode.name}")
        return RingerOutcome.Set(mode)
    }

    /**
     * Sets [mode] and confirms it took, in three outcomes rather than two.
     *
     * Verified rather than assumed, the same discipline
     * [AndroidZenController.confirmSilenced] applies to the rule and for the
     * same reason: `setRingerMode` is documented to have *no effect* on a
     * fixed-volume device and to be disallowed without policy access, and
     * neither is promised to throw. An unverified write would let the app
     * report a ceiling the user can hear it did not get.
     *
     * [Written.UNVERIFIED] is its own answer because collapsing it into either
     * of the others is wrong on one of the two paths (Codex, PR #176). Called a
     * success, a release clears the loan — and if the write silently did nothing
     * the phone is quiet with no way back at all, which is principle 1's
     * failure. Called a failure, a borrow clears the loan it just recorded — and
     * if the write *did* land, that is the same failure by the other route. So
     * each caller prices it, and both of them keep the loan.
     */
    private fun write(manager: AudioManager, mode: RingerMode): Written {
        val set = runCatching { manager.ringerMode = mode.toPlatform() }
        if (set.isFailure) {
            SnoozeDebugLog.failure(set.exceptionOrNull()!!, "ringer: setting ${mode.name} was refused")
            return Written.REFUSED
        }
        val readBack = runCatching { manager.ringerMode }.getOrElse {
            Log.w(TAG, "Reading the ringer mode back failed; the write cannot be confirmed.", it)
            SnoozeDebugLog.warning("ringer: ${mode.name} was accepted but could not be read back")
            return Written.UNVERIFIED
        }
        if (readBack.toDomain() == mode) return Written.SET
        SnoozeDebugLog.warning("ringer: ${mode.name} was accepted but the phone is still ${readBack.toDomain()?.name ?: "unreadable"}")
        return Written.REFUSED
    }

    /**
     * A hand-back that did not happen, escalated rather than merely reported.
     *
     * The loan stays: it is the only thing that knows what to put back, and
     * every later release, the start-up check and opening the app all retry from
     * it — dropping it here would leave a phone quiet for good, which is
     * principle 1's failure. What this adds is the *durable* attempt, because
     * nothing else is left watching: a completed zen release erases the record
     * and cancels the alarms, so the loan alone schedules nothing, and the
     * checks at start-up and app-open are likely rather than guaranteed.
     *
     * Paced by the loan's own tally, so a **permanent** refusal — a fixed-volume
     * policy, revoked policy access — stops asking rather than buying a wake-up
     * a minute forever for a write that cannot land. When the sequence is spent
     * the user is told, since they are the only one who can fix it from there
     * (principle 2). All of it from Codex, PR #176, over three rounds.
     */
    private fun escalate(mode: RingerMode, why: String): RingerOutcome {
        val failures = tallyHandBackFailure()
        // A tally that cannot be stored ends the sequence rather than restarting
        // it (Codex, PR #176): each alarm may wake a fresh process, which would
        // read the same stale count, schedule the same first delay, and never
        // reach a terminal state — an unbounded wake-up, which is the exact cost
        // the pacing exists to avoid.
        val delay = failures?.let { RingerHandBack.retryDelayMillis(it) }
        if (delay == null) {
            SnoozeDebugLog.warning(
                "ringer: could not hand it back to ${mode.name} (${failures?.let { "round $it" } ?: "the tally is unwritable"}); " +
                    "no more wake-ups, the loan waits for the next snooze or app open",
            )
            reportRingerStuck(true)
            return RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED)
        }
        SnoozeDebugLog.warning(
            "ringer: could not hand it back to ${mode.name} — $why; retrying in ${delay / 60_000L} min (round $failures)",
        )
        requestRingerHandBackRetry(delay)
        return RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED)
    }

    /**
     * The record itself could not be read, so what is owed is unknown.
     *
     * Paced by the same tally as a refused write, because the cause is as
     * likely to be transient — a preferences file caught mid-write, a moment
     * out of memory — as permanent. Where the tally cannot be written the
     * wake-ups stop *without* the stuck notice the write path posts: a store
     * that cannot be read cannot say a loan exists at all, and telling someone
     * their ringer is stuck when nothing ever took it is a false alarm.
     * Exhausting the rounds is the other case — by then a stranded loan is the
     * likeliest reading, so the notice stands.
     */
    private fun escalateUnreadableState(what: String = "the loan record is unreadable"): RingerOutcome {
        val failures = tallyHandBackFailure()
        val delay = failures?.let { RingerHandBack.retryDelayMillis(it) }
        if (delay == null) {
            SnoozeDebugLog.warning(
                "ringer: $what and " +
                    (failures?.let { "the retries are spent (round $it)" } ?: "the tally is unwritable") +
                    "; no more wake-ups",
            )
            if (failures != null) reportRingerStuck(true)
            return RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED)
        }
        SnoozeDebugLog.warning("ringer: $what; retrying in ${delay / 60_000L} min (round $failures)")
        requestRingerHandBackRetry(delay)
        return RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED)
    }

    /**
     * Counts this failed round against the loan and returns the new total.
     *
     * Null where the count could not be stored, which [escalate] reads as "stop
     * scheduling": a tally that never persists cannot bound anything, and each
     * alarm firing into a fresh process would re-read the same stale count
     * forever. The loan still survives, the user is still told, and the checks
     * at start-up, app-open and the next release still retry from it — so the
     * ringer is not given up, only the wake-ups are.
     */
    private fun tallyHandBackFailure(): Int? {
        val failures = runCatching { loans.handBackFailures() }.getOrDefault(0) + 1
        val wrote = runCatching { loans.recordHandBackFailures(failures) }.getOrDefault(false)
        if (!wrote) {
            SnoozeDebugLog.warning("ringer: the hand-back tally was not recorded; the retry sequence cannot be bounded")
            return null
        }
        return failures
    }

    /** What [write] managed. See its doc for why the middle case is its own. */
    private enum class Written { SET, UNVERIFIED, REFUSED }

    /**
     * The live mode, or null where it cannot be read.
     *
     * Null is a real answer both decisions handle — arming declines to take a
     * ringer it cannot record a way back for, releasing hands it back anyway —
     * so it is passed through rather than defaulted here.
     */
    private fun currentMode(): RingerMode? {
        val manager = audioManager ?: return null
        return runCatching { manager.ringerMode }.getOrElse {
            Log.w(TAG, "Reading the ringer mode failed.", it)
            null
        }?.toDomain()
    }

    /**
     * The loan, wrapped so an unreadable store is not mistaken for an empty one.
     *
     * Returning null from [RingerLoanStore.borrowed] means "nothing borrowed",
     * which arming would answer by borrowing again — overwriting the way back
     * with the quiet mode Snoozemo itself set. A read that *failed* has to say
     * so separately, and both callers refuse on it.
     */
    private fun readLoan(): Loan? = runCatching { Loan(loans.borrowed()) }.getOrElse {
        SnoozeDebugLog.failure(it, "ringer: the loan record is unreadable; leaving the ringer alone")
        null
    }

    private class Loan(val borrowed: BorrowedRinger?)

    /**
     * The choice this snooze is running under, captured once at its first arm.
     *
     * A record already there **wins over the setting**, which is what makes a
     * re-assertion — a restore after process death, a reboot, the cap alarm's
     * own re-arm — reuse the ceiling the snooze started with rather than adopt
     * one made after it started (Codex, PR #176). Without that, changing
     * `Vibrate` to `Silent` mid-snooze would either quiet the phone on the next
     * restore or leave the card reporting against a ceiling nothing is holding.
     *
     * Null only where the setting itself could not be read: there is then no
     * ceiling this arm can honestly claim, and the caller declines.
     */
    private fun inForce(): SnoozeRinger? {
        runCatching { loans.activeChoice() }
            .onFailure { SnoozeDebugLog.failure(it, "ringer: the choice in force is unreadable") }
            .getOrNull()
            ?.let { return it }
        // The loan is deliberately **not** a fallback here, though it was for
        // one round (Codex, PR #176, twice — the second time against the fix
        // the first asked for). A loan does not say *whose* snooze it is: one
        // left outstanding by a refused hand-back would be read as the next
        // snooze's choice, so a new `Silent` snooze would run at the old
        // `Vibrate` ceiling with the card reporting no shortfall at all. That
        // is worse than the gap it closed — a fresh snooze silently at the
        // wrong ceiling, against a re-assertion adopting the user's own newer
        // choice after a storage failure. Both want the same real fix, an
        // identity on the record, which `TODO.md` carries.
        val chosen = runCatching { setting.chosen() }.getOrElse {
            SnoozeDebugLog.failure(it, "ringer: the chosen ceiling is unreadable; leaving the ringer alone")
            return null
        }
        rememberChoice(chosen)
        return chosen
    }

    /**
     * Writes the choice now in force, or forgets it, and carries on either way.
     *
     * Contained rather than checked, unlike the loan: losing this record costs a
     * line of honesty on the card and a re-assertion that re-reads the setting,
     * where losing the loan costs the phone its ringer.
     */
    private fun rememberChoice(choice: SnoozeRinger?) {
        val wrote = runCatching { loans.recordChoice(choice) }.getOrElse {
            SnoozeDebugLog.failure(it, "ringer: recording the choice in force failed")
            false
        }
        if (wrote) return
        if (choice != null) {
            SnoozeDebugLog.warning("ringer: the choice in force was not recorded; the card cannot report a shortfall")
            return
        }
        // A *clear* that fails is the direction worth retrying (Codex, PR #176):
        // the record left behind is read as authoritative by the next arm, so a
        // stale `Silent` could quiet a snooze the user configured as `Ring`.
        // The hand-back alarm's own receiver re-runs the idle check, which
        // re-enters this — so asking for it is enough, and it is bounded by the
        // same tally as everything else on that ladder.
        askForCleanupRetry("the ceiling in force could not be dropped")
    }

    /**
     * A **record** that would not go away, retried on the hand-back ladder.
     *
     * The ringer itself is where it should be on every path that reaches here —
     * what is left behind is a row on disk that a later read would take as
     * authoritative. `SharedPreferences.commit` returning false means the
     * in-memory map was updated and the file was not, so this process sees the
     * record gone and the next one sees it return (Codex, PR #176): a stale
     * ceiling can then quiet a snooze configured as `Ring`, and a stale *loan*
     * whose `setTo` the user happens to select becomes a hand-back that
     * overrides them.
     *
     * The tally is **advanced**, not merely read: each alarm may wake a fresh
     * process, so a delay computed from a count nothing wrote back would
     * schedule the same first minute forever and never reach a terminal round.
     * No stuck notice at either end, unlike a stranded loan — the phone's own
     * ringer is right here, and a stale record is not something the user could
     * act on if they were told.
     */
    private fun askForCleanupRetry(what: String) {
        SnoozeDebugLog.warning("ringer: $what; asking for a retry")
        val delay = tallyHandBackFailure()?.let { RingerHandBack.retryDelayMillis(it) }
        if (delay == null) {
            SnoozeDebugLog.warning("ringer: no more wake-ups for the stale record; the next check drops it")
            return
        }
        requestRingerHandBackRetry(delay)
    }

    private fun RingerLoanStore.clearContained(): Boolean = runCatching { clear() }.getOrElse {
        SnoozeDebugLog.failure(it, "ringer: dropping the loan record failed")
        false
    }

    private fun refuse(reason: RingerFailure, why: String): RingerOutcome {
        SnoozeDebugLog.warning("ringer: the chosen ceiling was not applied — $why")
        return RingerOutcome.Refused(reason)
    }

    private fun RingerMode.toPlatform(): Int = when (this) {
        RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
    }

    private fun Int.toDomain(): RingerMode? = when (this) {
        AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        // A mode this build does not know is not guessed at: both decisions
        // treat an unreadable mode as one they cannot reason about.
        else -> null
    }

    companion object {
        private const val TAG = "RingerController"

        /**
         * Process-wide, not per-instance, because the loan is: four places
         * build one of these against the same file in the same process.
         */
        private val RINGER = Any()

        /**
         * How many times a hand-back is retried before the loan is left for a
         * later attempt. Small, because each is one binder call on a path with
         * a wake-up waiting behind it.
         */
        private const val HAND_BACK_ATTEMPTS = 3

        /**
         * The controller as every production caller builds it, so the modules
         * that cannot see `:app` construct the same one rather than a divergent
         * copy — the same reason [AndroidZenController.default] exists.
         */
        fun default(context: Context): AudioRingerController {
            val app = context.applicationContext
            return AudioRingerController(
                context = app,
                setting = SnoozeRingerStore(app),
                loans = PrefsRingerLoanStore(app),
            )
        }
    }
}
