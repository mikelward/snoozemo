package app.snoozemo.presence

import android.content.Context
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.presence.geofence.GeofencePresenceMonitor

import app.snoozemo.presence.geofence.GeofenceSignalBridge

/**
 * The flavor seam's constructor (SPEC.md §3.4): each flavor source set
 * defines this same function, and callers above the seam never know which
 * monitor they got.
 */
fun defaultPresenceMonitor(context: Context): PresenceMonitor =
    GeofencePresenceMonitor(context)

/**
 * Installs [onWake] to run when a presence observation arrives with no
 * monitor to receive it — a geofence exit restarting a dead process being
 * the case that matters (SPEC.md §8.1). The observation is held in the
 * bridge's mailbox; [onWake] should restore whatever owns the snooze, whose
 * restarted monitor then collects the held observation on attach. Installed
 * once, at process start, by the layer that can see the service — this
 * module cannot.
 */
fun installPresenceWakeup(onWake: () -> Unit) {
    GeofenceSignalBridge.installWakeup(onWake)
}

/**
 * The §6.10 backstop asking a *running* monitor for one resting fix — the
 * warm half of the periodic probe. A poke with no monitor attached is
 * dropped, deliberately: the backstop that pokes has also restored the
 * service, and the monitor that restore creates takes its own starting
 * probe, so nothing is lost and nothing is woken twice.
 */
fun pokePresenceSanity() {
    GeofenceSignalBridge.deliver(
        app.snoozemo.presence.geofence.GeofenceObservation.SanityPoke(
            android.os.SystemClock.elapsedRealtime(),
        ),
    )
}

/**
 * A warm wake asking the running monitor to re-attempt its fence
 * registration in place — repair that keeps the engine's failure memory,
 * where a monitor restart would forget it and let an unanswered probe
 * promote a broken snooze back to full tracking (Codex, PR #75). Dropped
 * with no monitor attached: a cold restore registers on its own way through.
 */
fun pokePresenceRepair() {
    GeofenceSignalBridge.deliver(
        app.snoozemo.presence.geofence.GeofenceObservation.RepairPoke(
            android.os.SystemClock.elapsedRealtime(),
        ),
    )
}
