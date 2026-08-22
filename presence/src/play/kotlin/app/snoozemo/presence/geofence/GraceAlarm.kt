package app.snoozemo.presence.geofence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.snoozemo.core.SnoozeDebugLog

/**
 * The real alarm behind [app.snoozemo.core.PresenceState.graceDeadlineMs]
 * (SPEC.md §6.6): the engine can decide a snooze has become unverifiable,
 * but only an alarm can wake the phone when the five minutes run out — an
 * unarmed deadline is a Wi-Fi-only snooze that never ends, which is
 * principle 1's failure by way of doing nothing.
 *
 * Reconciled, not armed-and-forgotten: the monitor calls [reconcile] with
 * the engine's current deadline after every signal, and this class keeps the
 * platform alarm matching it — armed when a deadline stands, silent when it
 * cleared (Wi-Fi came back, or a fix confirmed presence; an alarm outliving
 * its reason must not end a snooze, and the engine's own due-check is the
 * second line behind this cancel).
 *
 * Elapsed realtime, like the cap and for the same reason: the deadline was
 * written in that frame and a wall-clock alarm slides with the clock.
 */
internal object GraceAlarm {

    fun reconcile(context: Context, deadlineElapsedMs: Long?) {
        // The last-asked-for deadline, read fresh by a delayed retry rather
        // than replayed from its own stale closure (Codex, PR #77): a retry
        // scheduled for a rejected deadline A that fires after a newer,
        // *successful* reconcile to B would otherwise re-arm the shared
        // `PendingIntent` for A — silently overwriting B's alarm with a
        // stale or already-past one, and leaving the snooze's real deadline
        // unwatched. Re-reading this instead of A makes a superseded retry
        // harmless by construction: it just re-confirms whatever is current.
        lastRequestedDeadline.set(deadlineElapsedMs)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        runCatching {
            if (deadlineElapsedMs == null) {
                alarmManager.cancel(pendingIntent(context))
                retriesLeft.set(RETRIES)
                return
            }
            // `setAndAllowWhileIdle`, not the exact form: exactness costs the
            // SCHEDULE_EXACT_ALARM permission — a distribution question, not
            // an implementation detail (SPEC.md §3) — and the cap already
            // accepts the same inexactness for the same reason. Deferral
            // concentrates while the device is stationary, and the departure
            // this deadline bounds is exactly what ends that.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadlineElapsedMs,
                pendingIntent(context),
            )
            SnoozeDebugLog.event("grace alarm armed")
            retriesLeft.set(RETRIES)
        }.onFailure { rejectArming(context, deadlineElapsedMs, it) }
    }

    /**
     * A rejected arm gets a durable successor, not only a log line (Codex,
     * PR #77): "the next signal re-arms it" is false precisely for the case
     * this alarm exists to cover — a Wi-Fi-only anchor already off its
     * network, where nothing else in the presence pipeline runs (no fence,
     * no location duty, the backstop's probe and repair pokes both gate on
     * work this anchor has none of) until Wi-Fi itself changes again, which
     * an already-gone network may never do. Bounded in process, the same
     * shape as the app's other alarm ladders, so a persistently refusing
     * `AlarmManager` cannot turn this into a once-a-minute wake loop; the
     * budget refills on the next successful reconcile (armed or canceled).
     * The retry itself reconciles against [lastRequestedDeadline] rather
     * than the [deadlineElapsedMs] that just failed, for the reason
     * documented there: a superseded retry must not overwrite a newer
     * alarm.
     */
    private fun rejectArming(context: Context, deadlineElapsedMs: Long?, cause: Throwable) {
        SnoozeDebugLog.warning("grace alarm refused", cause)
        // A failed cancel needs no ladder: an alarm left standing outlives
        // its reason, but `Presence.graceElapsed` already treats a stale
        // firing as a no-op — the same reasoning that lets the deadline
        // itself go unpersisted across a restart.
        if (deadlineElapsedMs == null) return
        if (retriesLeft.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            Handler(Looper.getMainLooper()).postDelayed(
                { reconcile(context, lastRequestedDeadline.get()) },
                RETRY_MS,
            )
        } else {
            SnoozeDebugLog.warning("grace alarm retries exhausted; the duration cap bounds the snooze")
        }
    }

    private val retriesLeft = java.util.concurrent.atomic.AtomicInteger(RETRIES)

    /** See [reconcile]'s comment on why a retry re-reads this. */
    private val lastRequestedDeadline = java.util.concurrent.atomic.AtomicReference<Long?>(null)

    /** Per-deadline bound on arming retries; refilled on the next success. */
    private const val RETRIES = 3

    /** The in-process retry's pause. */
    private const val RETRY_MS = 60_000L

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_GRACE,
            Intent(context, GraceAlarmReceiver::class.java).setAction(ACTION_GRACE_ELAPSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal const val ACTION_GRACE_ELAPSED = "app.snoozemo.presence.action.GRACE_ELAPSED"
    private const val REQUEST_GRACE = 1
}

/**
 * Delivers the due deadline into the running monitor — or, with no monitor,
 * into the bridge's mailbox, whose wake-up restores the service exactly as a
 * geofence exit's does. The engine re-checks the deadline against its own
 * state on arrival, so a stale firing is a no-op, never an end.
 */
internal class GraceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != GraceAlarm.ACTION_GRACE_ELAPSED) return
        SnoozeDebugLog.event("grace alarm fired")
        GeofenceSignalBridge.deliver(
            GeofenceObservation.GraceElapsed(SystemClock.elapsedRealtime()),
        )
    }
}
