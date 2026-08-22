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
