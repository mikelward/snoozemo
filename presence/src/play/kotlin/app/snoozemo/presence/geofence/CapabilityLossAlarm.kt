package app.snoozemo.presence.geofence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.snoozemo.core.SnoozeDebugLog

/**
 * The wake mechanism behind [CapabilityLossStore]: a persisted cause with
 * nothing scheduled to act on it just sits on disk until whatever *next*
 * wakes the process — worst case, the multi-hour duration cap, the exact
 * failure this whole item exists to close. Armed for the soonest the
 * platform will allow, not a real deadline: unlike [GraceAlarm], there is no
 * countdown here, only a decision already made that needs a prompt hand-off.
 *
 * `setAndAllowWhileIdle`, not the exact form, for the same distribution
 * reason [GraceAlarm] gives (`SCHEDULE_EXACT_ALARM`, SPEC.md §3) — and the
 * decision this backs is itself already a fail-open the cap would otherwise
 * bound, so a deferred wake in Doze is a small delay on top of an existing
 * backstop, not a new failure mode.
 */
internal object CapabilityLossAlarm {

    fun arm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime(),
                pendingIntent(context),
            )
            SnoozeDebugLog.event("capability-loss alarm armed")
        }.onFailure {
            // No retry ladder, unlike `GraceAlarm`'s: the durable store this
            // backs is what a restore reads directly (`GeofencePresenceMonitor.start`),
            // so a refused arm only costs the prompt wake, not the decision
            // itself — the next thing that starts the service (the cap, a
            // tap, a reboot) finds the store and ends the snooze anyway.
            SnoozeDebugLog.warning("capability-loss alarm refused; the store still carries the decision", it)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        runCatching { alarmManager.cancel(pendingIntent(context)) }
            .onFailure { SnoozeDebugLog.warning("capability-loss alarm cancel failed", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CAPABILITY_LOSS,
            Intent(context, CapabilityLossAlarmReceiver::class.java).setAction(ACTION_CAPABILITY_LOST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal const val ACTION_CAPABILITY_LOST = "app.snoozemo.presence.action.CAPABILITY_LOST"
    private const val REQUEST_CAPABILITY_LOSS = 2
}

/**
 * Delivers the wake-up into the running monitor — or, with no monitor, into
 * the bridge's mailbox, whose wake-up restores the service exactly as a
 * geofence exit's or a due grace deadline's does. The observation itself
 * carries no cause: [GeofencePresenceMonitor] reads the actual decision from
 * [CapabilityLossStore], keyed to whichever snooze is currently restoring, on
 * delivery — this firing is only the prompt, not the payload, so a stale
 * firing from an already-superseded snooze finds nothing to act on rather
 * than misapplying an old decision to a new one (the same identity check
 * [GraceDeadlineStore] already relies on).
 */
internal class CapabilityLossAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CapabilityLossAlarm.ACTION_CAPABILITY_LOST) return
        SnoozeDebugLog.event("capability-loss alarm fired")
        GeofenceSignalBridge.deliver(
            GeofenceObservation.CapabilityLoss(SystemClock.elapsedRealtime()),
        )
    }
}
