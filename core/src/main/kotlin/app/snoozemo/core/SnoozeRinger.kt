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
     * Leave the ringer alone. Either an arm the platform refused, which stays
     * armed for the cap to retry — so the loan is still owed to a snooze that is
     * still running — or a refused release whose hand-back found the ringer had
     * become the user's own.
     */
    NOTHING,
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
 */
fun ringerFollowUp(
    snoozed: Boolean,
    outcome: ZenOutcome,
    ringerDisowned: Boolean = false,
): RingerFollowUp = when {
    snoozed && outcome is ZenOutcome.Applied -> RingerFollowUp.QUIET
    // Both directions of "nothing of ours is silencing the phone" agree: no
    // policy access, no rule, or the rule already switched off.
    outcome.confirmsNothingSilencing -> RingerFollowUp.HAND_BACK_AND_FORGET
    snoozed -> RingerFollowUp.NOTHING
    // The snooze runs on, so the ceiling would go back down — but re-applying
    // it here would find no loan, borrow again, and lower the very ringer the
    // hand-back had just left as theirs (Codex, PR #176).
    ringerDisowned -> RingerFollowUp.NOTHING
    else -> RingerFollowUp.RE_QUIET
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
