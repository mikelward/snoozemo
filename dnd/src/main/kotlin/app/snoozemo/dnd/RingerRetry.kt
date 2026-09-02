package app.snoozemo.dnd

import app.snoozemo.core.SnoozeDebugLog
import java.util.concurrent.atomic.AtomicReference

/**
 * How `:dnd` asks for another go at handing the ringer back (SPEC.md §5.9).
 *
 * A hand-back that fails every immediate attempt leaves the phone quiet with
 * only the loan to say so, and once a release has completed there is nothing
 * left scheduled that would look at it: the record is erased and the alarms are
 * canceled (Codex, PR #176). Process start and opening the app both re-check,
 * but neither is *guaranteed* to happen, and a phone left silent after a snooze
 * it was told had ended is principle 1's failure — the one this app puts first.
 *
 * So the retry is asked for rather than assumed, and `:app` installs what
 * actually schedules it. The shape is `installPresenceWakeup`'s, and for the
 * same reason: the durable successor is an `AlarmManager` wake-up, which lives
 * in `:app`, and this module must not depend on it.
 *
 * A no-op until something is installed, which is the honest behavior for a
 * process that has not finished starting: the loan survives either way, and the
 * checks at start-up and app-open are what pick it up then.
 */
private val handBackRetry = AtomicReference<((Long) -> Unit)?>(null)

/**
 * Registers what to do when a hand-back has failed every immediate attempt.
 * Called once, from `Application.onCreate`.
 *
 * The callback takes the delay to wait, because the pacing belongs to the loan
 * rather than to whatever schedules it: the interval doubles with the loan's own
 * persisted tally of failures ([app.snoozemo.core.RingerHandBack]), so a
 * permanently refused hand-back stops costing a wake-up a minute (Codex,
 * PR #176).
 */
fun installRingerHandBackRetry(onRefused: (delayMillis: Long) -> Unit) {
    handBackRetry.set(onRefused)
}

/**
 * Asks for a durable retry. Contained, because this runs on the release path:
 * an exception escaping here would cost the release itself, over a phone that
 * is merely quieter than it should be.
 */
internal fun requestRingerHandBackRetry(delayMillis: Long) {
    val retry = handBackRetry.get()
    if (retry == null) {
        SnoozeDebugLog.warning("ringer: no hand-back retry is installed yet; the loan waits for the next check")
        return
    }
    runCatching { retry(delayMillis) }.onFailure {
        SnoozeDebugLog.failure(it, "ringer: scheduling a hand-back retry failed; the loan waits for the next check")
    }
}

/**
 * Whether a hand-back has run out of retries, for `:app` to tell the user
 * (SPEC.md §5.9).
 *
 * The retry sequence ends so a permanently refused write stops waking the
 * phone — and when it ends the loan is still outstanding, so the phone is
 * quieter than its owner set it with nothing left that will fix it by itself.
 * That is principle 2's case exactly: do the safe thing and **say so**. The
 * ongoing card cannot, because the snooze has ended and taken it down, so this
 * asks for the one channel left.
 *
 * Two-way on purpose. A later hand-back — the next snooze's release, the
 * start-up check, opening the app — is what makes the notice untrue, and
 * nothing else would take it down: `false` is that report, and it is sent from
 * every path that leaves nothing owed, including a loan the user's own change
 * disowned.
 */
private val stuckNotice = AtomicReference<((Boolean) -> Unit)?>(null)

/**
 * Registers what to do when the ringer is stuck, and when it stops being.
 * Called once, from `Application.onCreate`.
 */
fun installRingerStuckNotice(onStuck: (stuck: Boolean) -> Unit) {
    stuckNotice.set(onStuck)
}

/**
 * Reports the stuck state. Contained like [requestRingerHandBackRetry], and for
 * the same reason: this runs on the release path, where an exception would cost
 * the release itself over a notice.
 */
internal fun reportRingerStuck(stuck: Boolean) {
    val notice = stuckNotice.get() ?: return
    runCatching { notice(stuck) }.onFailure {
        SnoozeDebugLog.failure(it, "ringer: reporting the stuck ringer failed")
    }
}

