package app.snoozemo.presence.geofence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.snoozemo.core.SnoozeDebugLog
import java.time.Duration

/**
 * The durable half of D4's Wi-Fi watch, for the one anchor that has nothing
 * else (SPEC.md §6.6, §6.10).
 *
 * `PlatformWifiWatch` is a `NetworkCallback`, and a callback lives in a
 * process. This app runs no foreground service by design (SPEC.md §3.4), so
 * Android stops the snooze's service within about a minute of the app going
 * to the background — and the watch closes with it. For a fenced anchor that
 * costs nothing: the geofence is registered with the system, outlives the
 * process, and wakes the app for the departure. An anchor with **no usable
 * fix has no fence to register**, so once its service is gone, nothing at all
 * is listening when the anchor's Wi-Fi drops: the user walks out, the phone
 * moves to mobile data, and the snooze that promised to end five minutes
 * later runs on in silence — principle 1's failure, reached by the ordinary
 * route of arming indoors.
 *
 * This is what stands in for the fence there: a wake-up registered with the
 * platform rather than held in memory, which restores the service so the
 * watch is rebuilt and reads the association again. It cannot be an
 * event-driven one. Android delivers no implicit Wi-Fi or connectivity
 * broadcast to a manifest receiver, and the `PendingIntent` form of
 * `registerNetworkCallback` corresponds to `onAvailable` only — there is no
 * durable "this network went away" to subscribe to. So the mechanism is a
 * repeating alarm and the cost is latency: a departure is typically noticed
 * within about [PERIOD] and the snooze ends one §6.6 grace period after that
 * — typically, because a while-idle alarm's delivery is best-effort, not
 * guaranteed (see [PERIOD]); the duration cap remains the only hard bound.
 *
 * Elapsed realtime and `setAndAllowWhileIdle`, like the cap and the grace
 * alarm and for the same reasons: a wall-clock alarm slides when the clock is
 * moved, and exactness would cost `SCHEDULE_EXACT_ALARM` — a distribution
 * question, not an implementation detail (SPEC.md §3).
 *
 * **It retires itself.** Only a monitor arms it, so a firing that finds no
 * monitor and wakes a service that finds no snooze re-arms nothing and the
 * chain ends there — the same self-healing shape as the periodic backstop's
 * retirement, and what keeps a lost cancel from leaving a repeating wake-up
 * running against no snooze at all.
 */
internal object WifiRecheckAlarm {

    /**
     * How often a Wi-Fi-only snooze re-reads the association it cannot
     * otherwise be told about.
     *
     * Half the backstop's period, because this is the *only* thing covering
     * these snoozes while the backstop is one recovery layer among several
     * for every other kind — and because §6.6 promises five minutes, so a
     * request of 30 reads as a broken promise where 15 reads as a degraded
     * one. `setAndAllowWhileIdle` tends to fire sooner than the
     * `WorkManager` period Doze defers to a maintenance window, but the
     * cadence is **best-effort, not a bound** (Codex, PR #105): the
     * roughly-nine-minute figure is a quota *between* an app's while-idle
     * alarms, not a ceiling on delivery, so deep Doze, a restricted standby
     * bucket, or OEM battery management can push a firing well past the
     * period. The duration cap stays the only hard bound on any snooze
     * (principle 1); this only shortens the *typical* departure latency,
     * and its failure direction is a late end, never no end.
     *
     * The battery it spends is stated in SPEC.md §9: on the order of 32
     * wakes across an 8-hour snooze, each one a service start and a
     * network-state read — no location request, no radio — and only for an
     * anchor with no usable fix. Reversible: one constant (TODO.md,
     * Decisions needing review).
     */
    val PERIOD: Duration = Duration.ofMinutes(15)

    /**
     * Keeps the alarm matching whether this snooze needs it — armed for a
     * Wi-Fi-only anchor, silent otherwise — re-arming on each call, since
     * the platform alarm is one-shot and a signal that just arrived has
     * reset the clock on how stale the association can be.
     */
    fun reconcile(context: Context, needed: Boolean) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        runCatching {
            if (!needed) {
                alarmManager.cancel(pendingIntent(context))
                return
            }
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + PERIOD.toMillis(),
                pendingIntent(context),
            )
            SnoozeDebugLog.event("Wi-Fi recheck armed: %s min", PERIOD.toMinutes())
        }.onFailure {
            // No retry ladder, deliberately, and this is the difference
            // between this alarm and the grace one: a refused grace alarm
            // leaves a snooze whose five minutes nothing will ever end,
            // while a refused recheck falls back to the §6.10 backstop and
            // then the cap — layers that are still standing. Said, not
            // swallowed: what the user loses is departure latency, which is
            // exactly what a stuck snooze's report needs to show.
            SnoozeDebugLog.failure(it, "Wi-Fi recheck refused; the backstop and the cap still bound it")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_WIFI_RECHECK,
            Intent(context, WifiRecheckAlarmReceiver::class.java).setAction(ACTION_WIFI_RECHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal const val ACTION_WIFI_RECHECK = "app.snoozemo.presence.action.WIFI_RECHECK"

    /** Distinct from the grace alarm's, though the target class already is. */
    private const val REQUEST_WIFI_RECHECK = 2
}

/**
 * Delivers the recheck into the running monitor — or, with none, into the
 * bridge, whose wake-up restores the service exactly as a geofence exit's
 * does. Nothing about the world travels with it: what it asks for is a
 * *reader*, and the reader is the Wi-Fi watch a restored monitor builds.
 */
internal class WifiRecheckAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WifiRecheckAlarm.ACTION_WIFI_RECHECK) return
        SnoozeDebugLog.event("Wi-Fi recheck fired")
        GeofenceSignalBridge.deliver(
            GeofenceObservation.WifiRecheck(SystemClock.elapsedRealtime()),
        )
    }
}
