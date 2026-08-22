package app.snoozemo

import android.app.Application
import android.os.Handler
import android.os.Looper
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.dnd.PrefsZenRuleIdStore
import app.snoozemo.presence.installPresenceWakeup
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.CapAlarm
import app.snoozemo.snooze.DebugLogging
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
        // The debug log's rotation and file sink (SPEC.md §4.6). Spawns its
        // own thread, so the cold tap above never waits on it; entries
        // recorded before the sink registers still reach the file, since the
        // sink writes the whole buffer on the next entry after.
        DebugLogging.install(this)
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
