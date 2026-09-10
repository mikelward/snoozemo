package app.snoozemo.dnd

import app.snoozemo.core.RingerMode
import app.snoozemo.core.SnoozeIdentity

/**
 * Snoozemo's contact with the ringer (SPEC.md §5.9).
 *
 * A sibling of [app.snoozemo.core.ZenController] rather than part of it, because
 * the two answer different questions: the zen rule decides *what* reaches the
 * user while a snooze runs, and this decides how loud what reaches them is
 * allowed to be. They are driven together — [AndroidZenController] owns one of
 * these, so every arm and release in the app passes through both — and that is
 * a wiring decision, not a reason to merge the contracts.
 *
 * Declared in `:dnd` rather than `:core` because nothing in the state machine
 * calls it. What `:core` holds is the *decision*
 * ([app.snoozemo.core.RingerHandover]); what this holds is the mechanism and
 * the reporting.
 *
 * Every call reports what happened, for the reason `ZenController` does: a
 * ringer that did not move is something the user can hear, so it may not be
 * inferred from a call that returned.
 */
interface RingerController {

    /**
     * Brings the phone down to the chosen ceiling for a snooze that is now
     * running, recording what to put back first.
     *
     * Idempotent across re-asserted arms — a restore after process death, the
     * cap alarm's own re-arm — because an outstanding loan is never overwritten.
     *
     * [snooze] is whose ceiling this is: the choice recorded for it is reused
     * by its own re-assertions and never by a later snooze's arm (SPEC.md §5.9
     * rule 2). Null only where no record is at hand, which keeps the record's
     * old, identity-less reading.
     */
    fun quiet(snooze: SnoozeIdentity? = null): RingerOutcome

    /**
     * Hands the ringer back, if Snoozemo took it and the user has not moved it
     * since. Safe to call with nothing outstanding, which is what makes it
     * usable both on the release path and as a start-up reconcile.
     */
    fun giveBack(): RingerOutcome

    /**
     * Forgets the ceiling the snooze was holding, once that snooze has actually
     * ended.
     *
     * Separate from [giveBack] because the two do not happen together: the
     * ringer is handed back *before* the zen rule goes off, so that a refused
     * rule write cannot leave a quiet phone with no loan — and a refused rule
     * write keeps the snooze running (Codex, PR #176). Clearing the record with
     * the hand-back would then leave a live snooze with no ceiling on record,
     * so the card could not report a shortfall it is now certainly having, and
     * a restore would adopt the setting meant for the *next* snooze.
     */
    fun forgetCeiling()

    /**
     * How many times the platform's ringer-mode setter has been **attempted**
     * since this process started — a monotonic count, for the diagnostic that
     * asks whether a mode write happened inside a given window (SPEC.md §4.6).
     *
     * Counted rather than inferred from an outcome, because the outcome cannot
     * answer it (Codex, PR #251). A single call can hand an earlier loan back —
     * setter and all — and then report `Untouched` because the new ceiling had
     * nothing to take, so reading the outcome would record "no write" across an
     * interval that contained one, and clear a suspect that was guilty.
     *
     * Zero for a controller that owns no ringer.
     */
    val modeWrites: RingerWriteCounts
        get() = RingerWriteCounts(started = 0, finished = 0)
}

/**
 * How many times the platform's ringer setter has been **started** and
 * **finished** in this process — monotonic, so a window's worth is a
 * subtraction (SPEC.md §4.6).
 *
 * Two numbers rather than one, because an observer bracketing a window has to
 * answer "did a write happen in here" and a single count cannot: a counter
 * bumped before the assignment can be read, descheduled, and only then reach
 * the setter, landing the write inside the window with both samples already
 * carrying it. [isSettled] is the question that catches that.
 */
data class RingerWriteCounts(val started: Int, val finished: Int) {

    /** No setter call is in flight: every one that started has finished. */
    val isSettled: Boolean get() = started == finished
}

/** What a ringer call actually did. Never silently discarded. */
sealed interface RingerOutcome {

    /** The phone is now in [mode]. */
    data class Set(val mode: RingerMode) : RingerOutcome

    /**
     * The ringer was deliberately left where it is: no ceiling chosen, already
     * at or below it, or a loan already outstanding. All of them are correct
     * outcomes, and the debug log says which.
     */
    data object Untouched : RingerOutcome

    /**
     * The user moved the ringer mid-snooze, so the loan was dropped and the
     * mode left as they set it (SPEC.md §5.9 rule 4).
     *
     * Its own outcome rather than [Untouched] because a caller has to be able
     * to *not re-take* it: a release the platform then refuses keeps the snooze
     * running and would otherwise re-apply the ceiling over the very change
     * this recognized as theirs (Codex, PR #176).
     */
    data object Disowned : RingerOutcome

    /**
     * It should have moved and did not. [reason] is what the debug log records,
     * because a ceiling the user chose and did not get is something they will
     * hear and would otherwise have to guess at.
     */
    data class Refused(val reason: RingerFailure) : RingerOutcome
}

/** Why a ringer change didn't happen. */
enum class RingerFailure {
    /**
     * `AudioManager.isVolumeFixed` — the device has a fixed volume policy and
     * refuses ringer changes outright. Not a fault, and nothing to retry.
     */
    VOLUME_FIXED,

    /**
     * The way back could not be written down, so the ringer was left alone
     * rather than taken with no record of what it was.
     *
     * The whole reason this is a refusal and not a warning: a mode changed
     * without a durable record of the previous one is a phone that stays quiet
     * after the snooze ends, with nothing anywhere that knows better. Declining
     * to quiet costs the user an audible ring during one snooze, which is the
     * direction principle 1 chooses every time.
     */
    NOT_RECORDED,

    /** The platform rejected the change, or the record could not be read. */
    PLATFORM_REFUSED,
}

/**
 * How far the phone is from the ceiling in force, for the ongoing card to say
 * so (SPEC.md §5.9).
 *
 * Three answers rather than two, because "louder than the ceiling" and "cannot
 * tell" are different things to tell the user and the second one is not nothing
 * (Codex, PR #176): an arm that could not read the mode declined to borrow, so
 * the ceiling is certainly not holding — but which mode the phone is in is
 * exactly what is unknown, and naming one would be a fresh wrong answer in the
 * same place. Absent — a null [RingerController] answer — is the fourth state
 * and the common one: no ceiling in force, or one that is holding.
 */
sealed interface RingerShortfall {

    /** The phone is audibly louder than the ceiling, and this is what it is. */
    data class Louder(val mode: RingerMode) : RingerShortfall

    /** A ceiling is in force and the live mode could not be read. */
    data object Unknown : RingerShortfall
}

