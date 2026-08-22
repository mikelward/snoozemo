package app.snoozemo.presence

import android.content.Context
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.presence.geofence.GeofencePresenceMonitor

/**
 * The flavor seam's constructor (SPEC.md §3.4): each flavor source set
 * defines this same function, and callers above the seam never know which
 * monitor they got.
 */
fun defaultPresenceMonitor(context: Context): PresenceMonitor =
    GeofencePresenceMonitor(context)
