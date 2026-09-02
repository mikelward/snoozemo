package app.snoozemo.dnd

import android.content.Context
import android.util.Log
import app.snoozemo.core.BorrowedRinger
import app.snoozemo.core.RingerMode
import app.snoozemo.core.SnoozeRinger

/**
 * Remembers the ringer mode Snoozemo has taken over, and what it owes back
 * (SPEC.md §5.9).
 *
 * This record is the **only** thing that can put the ringer back: the mode is
 * global device state that outlives the process and the reboot, so a snooze
 * whose loan is lost leaves a phone quiet with nothing anywhere that knows what
 * it used to be. That is why [record] is checked by its caller and why the
 * borrow declines when it returns false — the same reasoning
 * [ZenRuleIdStore.setRuleId] carries for the rule id, and the same failure it
 * prevents.
 *
 * An interface for the same two reasons that one is: the decision it feeds is
 * testable without Android, and the storage may move to DataStore with the rest
 * of the settings.
 */
interface RingerLoanStore {

    /** The outstanding loan, or null if Snoozemo does not hold the ringer. */
    fun borrowed(): BorrowedRinger?

    /** Records [borrowed] durably, returning false if it did not reach disk. */
    fun record(borrowed: BorrowedRinger): Boolean

    /**
     * Marks the outstanding loan as applied, once the mode change is confirmed.
     * False if the write did not reach disk.
     */
    fun markApplied(): Boolean

    /** Forgets the loan, returning false if the removal did not reach disk. */
    fun clear(): Boolean

    /**
     * The choice the **running snooze** is running under, or null when no
     * snooze holds one.
     *
     * Its own record rather than the loan, because a ceiling can be in force
     * with nothing borrowed (Codex, PR #176): a phone already at or below it is
     * left alone, and a *refused* change leaves no loan either — which is
     * precisely the case the ongoing card has to report. Nor can the live
     * setting stand in for it: a choice changed mid-snooze governs the *next*
     * snooze, so reading it would make the card lie in both directions.
     *
     * The *choice* rather than the mode it implies, so that [SnoozeRinger.RING]
     * — which has no ceiling — is still a record. Without one, a re-assertion of
     * a `Ring` snooze would find nothing and adopt whatever the setting says
     * now, quieting a phone mid-snooze (Codex, PR #176).
     */
    fun activeChoice(): SnoozeRinger?

    /**
     * Records the choice now in force, or forgets it when [choice] is null.
     * False if the write did not reach disk.
     */
    fun recordChoice(choice: SnoozeRinger?): Boolean

    /**
     * How many hand-backs of the outstanding loan have failed.
     *
     * Persisted with the loan rather than held in memory, because the sequence
     * it paces outlives the process: the retry is an alarm, and the process it
     * wakes may be a fresh one (Codex, PR #176). Zero when the loan is new or
     * absent.
     */
    fun handBackFailures(): Int

    /** Stores the tally above, returning false if it did not reach disk. */
    fun recordHandBackFailures(failures: Int): Boolean
}

/**
 * `SharedPreferences`, like the rule id and for the same reasons: a handful of
 * small values, read at start-up and on the arm path, where a coroutine or a cold
 * disk read has no business.
 */
class PrefsRingerLoanStore(context: Context) : RingerLoanStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Absent only when **neither** value is there.
     *
     * A record whose fields cannot be parsed is not treated as no record at all,
     * and the distinction is the difference between a phone that gets its ringer
     * back and one that does not. Both keys land in a single `commit`, so a
     * half-written pair is not a thing this app writes — but a build whose enum
     * constants have been renamed under an existing record reads exactly that
     * way, and answering "nothing borrowed" there would leave a snoozed phone
     * silent for good. So an unreadable way back becomes [RingerMode.NORMAL],
     * the audible direction, and an unreadable `setTo` becomes null, which
     * [app.snoozemo.core.RingerHandover.giveBack] reads as "cannot verify, hand
     * it back anyway".
     */
    override fun borrowed(): BorrowedRinger? {
        val restoreRaw = prefs.getString(KEY_RESTORE_TO, null)
        val setRaw = prefs.getString(KEY_SET_TO, null)
        if (restoreRaw == null && setRaw == null) return null
        val restoreTo = mode(restoreRaw)
        if (restoreTo == null) {
            Log.w(TAG, "The borrowed ringer's way back is unreadable; assuming it was audible.")
        }
        return BorrowedRinger(
            restoreTo = restoreTo ?: RingerMode.NORMAL,
            setTo = mode(setRaw),
            // True for a record written before the marker existed, so an older
            // loan behaves exactly as it did rather than being re-applied.
            applied = prefs.getBoolean(KEY_APPLIED, true),
        )
    }

    /**
     * `commit`, not `apply`, and checked — the guarantee is the point.
     *
     * `apply` updates the in-memory map and hands the file write to a background
     * thread, so a process that died in that gap would leave the ringer quiet
     * with no record of the mode to put back. One synchronous write, on the arm
     * path but **after** the rule is already on, is what buys the way back.
     *
     * Both keys in one edit, so a reader can never see a way back without the
     * mode it pairs with.
     */
    override fun record(borrowed: BorrowedRinger): Boolean = prefs.edit()
        .putString(KEY_RESTORE_TO, borrowed.restoreTo.name)
        .putString(KEY_SET_TO, borrowed.setTo?.name)
        .putBoolean(KEY_APPLIED, borrowed.applied)
        // A new loan starts its retry sequence over. Left behind, a spent tally
        // from an earlier snooze would deny this one its retries entirely.
        .remove(KEY_FAILURES)
        .commit()

    /**
     * Also `commit`. A failure here is the harmless direction — a stale loan is
     * re-verified against the live mode by the next give-back and discarded —
     * but it is returned anyway so the caller can log rather than guess.
     */
    override fun clear(): Boolean = prefs.edit()
        .remove(KEY_RESTORE_TO)
        .remove(KEY_SET_TO)
        .remove(KEY_APPLIED)
        .remove(KEY_FAILURES)
        .commit()

    /**
     * An unreadable value reads as no snooze holding one, which is the quiet
     * direction on purpose: a card that cannot tell says nothing rather than
     * naming a ceiling it did not measure, and an arm that cannot tell captures
     * the current choice rather than inheriting an unreadable one.
     *
     * Deliberately **not** [SnoozeRinger.named], whose job is the opposite: a
     * *setting* falls back to the default, because a snooze must have some
     * ceiling; a record must not, because inventing one would claim a snooze is
     * running.
     */
    override fun activeChoice(): SnoozeRinger? = prefs.getString(KEY_CHOICE, null)
        ?.let { name -> SnoozeRinger.entries.firstOrNull { it.name == name } }

    /** `commit` like the pair above, so a card built moments later sees it. */
    override fun recordChoice(choice: SnoozeRinger?): Boolean = prefs.edit()
        .apply { if (choice == null) remove(KEY_CHOICE) else putString(KEY_CHOICE, choice.name) }
        .commit()

    /** `commit` like the record itself: the marker is only useful if it survives. */
    override fun markApplied(): Boolean = prefs.edit().putBoolean(KEY_APPLIED, true).commit()

    override fun handBackFailures(): Int = prefs.getInt(KEY_FAILURES, 0)

    override fun recordHandBackFailures(failures: Int): Boolean = prefs.edit()
        .putInt(KEY_FAILURES, failures)
        .commit()

    private fun mode(name: String?): RingerMode? =
        RingerMode.entries.firstOrNull { it.name == name }

    private companion object {
        const val FILE_NAME = "ringer_loan"
        const val KEY_RESTORE_TO = "restore_to"
        const val KEY_SET_TO = "set_to"
        const val KEY_CHOICE = "active_choice"
        const val KEY_APPLIED = "applied"
        const val KEY_FAILURES = "hand_back_failures"
        const val TAG = "RingerLoan"
    }
}
