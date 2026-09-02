package app.snoozemo.snooze

import android.content.Context
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.dnd.AudioRingerController
import app.snoozemo.dnd.RingerOutcome

/**
 * Hands the ringer back when a snooze ended without doing it (SPEC.md §5.9).
 *
 * Run at process start **and** when the app is opened. Start-up alone was not
 * enough (Codex, PR #176): once a release completes, the record is erased and
 * the alarms are canceled, so nothing schedules work from the loan — and a
 * process that stays alive would never reach this. Opening the app is the other
 * moment a user whose phone is unexpectedly quiet is very likely to reach.
 *
 * The release path is what normally gives it back, and a crash, a force-stop,
 * or a platform refusal can leave that undone — with the ringer being global
 * device state that survives the process and the reboot, so nothing else would
 * put it right.
 *
 * **Not the only backstop, and deliberately the earlier one.** A stale loan
 * already self-heals across one snooze cycle: the next arm sees a loan
 * outstanding and declines to borrow again, and that snooze's release hands the
 * ringer back to the mode from before the crash. So this only decides whether
 * the phone is audible again *now* or after the next snooze — which is worth a
 * start-up thread, and not worth putting anything on the arm path for.
 *
 * **The loan check and the record check are one atomic decision**, which is
 * why this goes through `giveBackIfIdle` rather than reading the record itself
 * and then calling `giveBack` (Codex, PR #176). A cold process started by a
 * tile tap runs this alongside an arm, and reading the two separately can
 * interleave badly: this thread sees a *stale* loan, then sees no record yet,
 * then the arm writes its record and declines to borrow over that same stale
 * loan, and then this thread hands the ringer back — leaving the new snooze
 * running with no ceiling at all. Evaluating the record inside the controller's
 * own lock is what removes the window; ordering the two reads cannot.
 */
internal fun reconcileRingerInBackground(context: Context) {
    val app = context.applicationContext
    Thread { handBackRingerNow(app) }.apply { isDaemon = true }.start()
}

/**
 * The same check, run on the caller's thread, returning what it managed — null
 * where the check itself threw.
 *
 * For the alarm receiver, which must not spawn a thread and return: a broadcast
 * keeps its process alive only for `onReceive`, so work handed to an unowned
 * thread can be killed halfway — and this alarm is the last scheduled exit from
 * a silent phone. It is cheap enough to finish inline: a preferences read and
 * one `AudioManager` call, against a receiver's ~10 seconds.
 *
 * The outcome is returned because the in-process retry rung has to know whether
 * it worked: its budget is process-global, so a rung that succeeded and did not
 * clear the count would spend it on behalf of the *next* stranded loan (Codex,
 * PR #176).
 */
internal fun handBackRingerNow(context: Context): RingerOutcome? {
    val app = context.applicationContext
    return runCatching {
        AudioRingerController.default(app).giveBackIfIdle {
            // `readUnverified`, not `load` (Codex, PR #176). The question here
            // is "is there a record at all", where `load`'s question is "is
            // there a snooze this device may restore" — and the attribution
            // filter between them is a real window on the arm path: the
            // `ARMING` transition writes through `saveAsync`, which skips the
            // device-stamp lookup on purpose, so for the moment before the
            // post-arm blocking save stamps it, `load` rejects a live arming
            // record as unattributed. This predicate would then read "nothing
            // running" and hand a stale loan back over a snooze that had just
            // armed and declined to borrow.
            //
            // The residual is the opposite case — a genuinely foreign record
            // makes this read "running" and defers the hand-back — and it is
            // the right way round to be wrong here only because it is
            // temporary: the discard path clears such a record, and the next
            // check then finds nothing and hands the ringer back.
            ActiveSnoozeStore(app).readUnverified() != null
        }
    }.onFailure {
        SnoozeDebugLog.failure(it, "ringer: the hand-back check failed")
    }.getOrNull()
}
