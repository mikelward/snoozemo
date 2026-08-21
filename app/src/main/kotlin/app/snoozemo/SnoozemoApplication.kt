package app.snoozemo

import android.app.Application
import app.snoozemo.dnd.PrefsZenRuleIdStore
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.DebugLogging
import app.snoozemo.snooze.SnoozeNotifications

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
class SnoozemoApplication : Application() {

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
    }
}
