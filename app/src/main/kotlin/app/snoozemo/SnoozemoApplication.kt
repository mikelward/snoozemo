package app.snoozemo

import android.app.Application
import android.os.Handler
import android.os.Looper
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.crash.CrashReporting
import app.snoozemo.dnd.PrefsZenRuleIdStore
import app.snoozemo.dnd.RingerOutcome
import app.snoozemo.dnd.SnoozeRingerStore
import app.snoozemo.dnd.installRingerHandBackRetry
import app.snoozemo.dnd.installRingerStuckNotice
import app.snoozemo.presence.installPresenceWakeup
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.CapAlarm
import app.snoozemo.snooze.DebugLogging
import app.snoozemo.snooze.EndSheetStore
import app.snoozemo.snooze.logRecentProcessExitsInBackground
import app.snoozemo.snooze.handBackRingerNow
import app.snoozemo.snooze.reconcileRingerInBackground
import app.snoozemo.snooze.SnoozeNotifications
import app.snoozemo.snooze.SnoozeService

/**
 * Application entry point, and where the arm path's warming lives (SPEC.md
 * §4.1): anything a tile tap needs should already be in memory before the tap,
 * so arming never waits on disk.
 *
 * Today that is the zen rule id — the first thing read on the way to
 * `setAutomaticZenRuleState(STATE_TRUE)` — and the active-snooze record, whose
 * `edit()` waits on the same kind of file load. The settings and the last known
 * SSID join them as later phases add them.
 */
class SnoozemoApplication : Application(), androidx.work.Configuration.Provider {

    /**
     * On-demand WorkManager, replacing the removed startup initializer
     * (SPEC.md §4.1; Codex, PR #75): initialization then happens at the
     * first `getInstance` — the backstop schedule, which runs after the
     * rule is already on — instead of on the process startup a cold tile
     * tap is racing through.
     */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // Off the main thread, and this early, because a cold-process tile tap
        // reaches the rule id within milliseconds of this returning: the
        // trampoline and the service both start after it.
        PrefsZenRuleIdStore(this).warm()
        ActiveSnoozeStore(this).warm()
        // Notification channels too: creating them is a pair of synchronous
        // binder calls that the service would otherwise make in its onCreate,
        // which on a cold tap sits between the tap and the rule going on.
        SnoozeNotifications.warm(this)
        // The end-condition sheet's own gate (SPEC.md §4.4). Not on the arm path
        // — the trampoline reads it after the service start — but a cold read
        // would land in front of the sheet's first frame over a transparent
        // window, and off by default means most installs never read it at all.
        EndSheetStore(this).warm()
        // How loud a snooze may be (SPEC.md §5.9). Warmed for the same reason
        // as the rule id: the arm path reads it — after the rule is on, never
        // before it — so a cold tap should find it in memory rather than
        // waiting on the file.
        SnoozeRingerStore(this).warm()
        // The debug log's rotation and file sink (SPEC.md §4.6). Spawns its
        // own thread, so the cold tap above never waits on it; entries
        // recorded before the sink registers still reach the file, since the
        // sink writes the whole buffer on the next entry after.
        DebugLogging.install(this)
        // Why the previous processes ended (SPEC.md §4.6). Queued on the debug
        // log's own installation worker, which is both off this thread — the
        // query is an ActivityManager binder call and nothing diagnostic may
        // sit in front of a cold tile tap — and ordered behind the stored
        // setting being applied to the recording gate. install() only enqueues
        // that, so a collector on its own thread could otherwise record while
        // the user's setting says Off. Must stay after install() for the FIFO
        // ordering to mean anything.
        logRecentProcessExitsInBackground(this)
        // What to do when a hand-back fails every immediate attempt (SPEC.md
        // §5.9), installed **before** anything can attempt one (Codex, PR #176).
        // The other way round, the check below could find a stale loan, fail its
        // hand-back fast, and ask for a retry while this was still null — and
        // that check is the very one it would have been asking to repeat.
        //
        // It exists because a completed release erases the record and cancels
        // the alarms: the loan alone schedules nothing, and neither a process
        // restart nor the app being opened is *guaranteed* to happen. An alarm
        // is, and it wakes nothing in the normal case — armed only after
        // refused writes, which a successful borrow makes very unlikely
        // (SPEC.md §9). How long to wait comes from the loan's own tally of
        // failures, so a refusal that is permanent stops asking rather than
        // waking the phone every minute for good.
        installRingerHandBackRetry { delay -> ringerHandBackRefused(delay) }
        // And what to do when those run out: the phone is quieter than its owner
        // set it, the snooze has ended so the ongoing card is gone, and nothing
        // left in the app will fix it by itself — so they are told (principle 2,
        // SPEC.md §5.9 rule 5). Installed here for the same ordering reason as
        // the retry above: the check below can reach either report.
        installRingerStuckNotice { stuck ->
            val notifications = SnoozeNotifications(this)
            if (stuck) notifications.showRingerStuck() else notifications.dropRingerStuck()
        }
        // The ringer itself, if a snooze ended without handing it back (SPEC.md
        // §5.9). Its own thread like the two above, so nothing diagnostic or
        // corrective sits in front of a cold tile tap. The loan and the record
        // are checked together under the ringer controller's own lock, so a cold
        // tile tap arming alongside this cannot end up with no ceiling — see
        // `reconcileRingerInBackground`.
        reconcileRingerInBackground(this)
        // Crash reporting's own gate (SPEC.md §12). Spawns its own worker like
        // the line above, so the cold tap never waits on the preferences read
        // — and it is what makes the opt-out real: the play manifest starts
        // Crashlytics with collection off, so an install where the user has
        // opted out never begins collecting. A no-op on `direct`, which has no
        // reporter to gate.
        CrashReporting.install(this)
        // A presence observation arriving into a process the system restarted
        // — a geofence exit is often the very thing that restarts it — has
        // nobody to receive it until the service restores the snooze, so this
        // is what turns that wake-up into a restore (SPEC.md §8.1). The
        // observation itself waits in the monitor's mailbox and is collected
        // when the restored watch attaches.
        //
        // A refused start gets a durable successor, not just a log line: the
        // mailbox is in-process and nothing else is guaranteed to come — a
        // geofence never fires twice for one crossing — so a refusal here
        // would strand a known departure signal until the cap (flagged by
        // Codex on PR #73). A short check-in alarm retries from an alarm
        // receiver's own, better-privileged start window.
        //
        // The retry carries its own alarm action, never the cap's: the cap
        // receiver's no-service fallback is an immediate release — the right
        // last resort for a spent cap alarm, and an end hours early under the
        // wrong reason for a retry armed a minute ago (Codex, PR #73). Its
        // own action also leaves the pending cap alarm in place, so even a
        // retry refused again on firing stays bounded by it.
        installPresenceWakeup { presenceWake() }
    }

    /**
     * The ringer is owed back and every immediate attempt failed.
     *
     * The same two-rung ladder [presenceWake] uses, and for the same reason: an
     * `AlarmManager` wake-up is the durable successor, and where even that is
     * refused this process is alive — it is running this very callback — so its
     * own handler is a real, bounded one. If the process dies before either
     * lands, the loan is still on disk and the start-up check picks it up.
     *
     * [delayMillis] is the caller's, not this ladder's: the pacing belongs to
     * the loan, which knows how many rounds it has already spent. The in-process
     * rung keeps its own short interval, since it exists for a refused *alarm*
     * rather than a refused write.
     */
    private fun ringerHandBackRefused(delayMillis: Long) {
        if (CapAlarm.armRingerRetry(this, delayMillis)) {
            ringerRetries = 0
            return
        }
        if (ringerRetries++ >= MAX_IN_PROCESS_WAKE_RETRIES) {
            SnoozeDebugLog.warning("ringer: hand-back retries exhausted; the loan waits for the next start or app open")
            return
        }
        SnoozeDebugLog.warning("ringer: the hand-back alarm was refused; retrying in process")
        // Inline in the callback, not handed to a background thread (Codex,
        // PR #176). By the time this rung is reached there is no alarm
        // scheduled and quite possibly no active component owning the process,
        // so a thread spawned here can be killed before it writes — and the
        // whole point of this rung is that it is the last thing left. Doing
        // the work *inside* the callback means a process that lives long
        // enough to run it at all finishes the hand-back.
        //
        // Main-thread I/O on purpose, and none of the paths that rule protects:
        // this is not the tile tap, the trampoline, or a screen's first frame.
        // It is three warm preference reads and one `setRingerMode`, half a
        // minute after startup, on a path that only runs when `AlarmManager`
        // has already refused.
        wakeRetryHandler.postDelayed(
            {
                // And the budget is spent per *episode*, not for the life of
                // the process (Codex, PR #176): only a successful alarm reset
                // it, so a rung that worked here left the count raised and the
                // next stranded loan inherited it. Anything but a refusal means
                // the loan is resolved — handed back, disowned, or never there.
                if (handBackRingerNow(this) !is RingerOutcome.Refused) ringerRetries = 0
            },
            IN_PROCESS_WAKE_RETRY_MS,
        )
    }

    private var ringerRetries = 0

    /**
     * One rung down when even the alarm is refused: this process is alive —
     * it is running this very callback — so its own handler is a real,
     * bounded successor, exactly as the service's release ladder uses one
     * (flagged by Codex on PR #73 when the alarm's refusal went unchecked).
     * If the process dies before a retry lands, the in-memory mailbox dies
     * with it and the cap bounds the snooze — the same residual the
     * `PresenceState`-persistence slice is recorded to close.
     */
    private val wakeRetryHandler = Handler(Looper.getMainLooper())

    private var wakeRetries = 0

    private fun presenceWake() {
        if (SnoozeService.restore(this)) {
            wakeRetries = 0
            return
        }
        SnoozeDebugLog.warning("presence wake-up refused; arming a check-in to retry")
        if (CapAlarm.armPresenceRetry(this, PRESENCE_WAKE_RETRY_MS)) {
            wakeRetries = 0
            return
        }
        if (wakeRetries++ >= MAX_IN_PROCESS_WAKE_RETRIES) {
            SnoozeDebugLog.warning("presence wake-up retries exhausted; the cap bounds the snooze")
            return
        }
        SnoozeDebugLog.warning("presence wake-up and its alarm both refused; retrying in process")
        wakeRetryHandler.postDelayed(::presenceWake, IN_PROCESS_WAKE_RETRY_MS)
    }

    private companion object {
        /** How soon the alarm retries a refused presence wake-up. */
        const val PRESENCE_WAKE_RETRY_MS = 60_000L

        /** The in-process rung, bounded like the service's own ladder. */
        const val IN_PROCESS_WAKE_RETRY_MS = 30_000L
        const val MAX_IN_PROCESS_WAKE_RETRIES = 10
    }
}
