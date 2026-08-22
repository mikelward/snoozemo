package app.snoozemo.presence.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.snoozemo.core.SnoozeDebugLog
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

/**
 * Where the platform delivers the fence's transitions (SPEC.md §6.10,
 * source 1).
 *
 * Thin on purpose, like the tile: it turns the Play Services payload into a
 * [GeofenceObservation] and hands it across [GeofenceSignalBridge]; every
 * decision about what an exit *means* lives in the engine, where a JVM test
 * can reach it. Not exported — the sender is our own mutable PendingIntent,
 * and an exported receiver would let any app fake a departure.
 */
class GeofenceTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val event = GeofencingEvent.fromIntent(intent) ?: return

        if (event.hasError()) {
            // GEOFENCE_NOT_AVAILABLE mid-watch is the platform saying it has
            // stopped watching — location switched off is the ordinary cause.
            // Anything else is logged and left to the sanity poll: an error
            // code is not evidence about where the user is.
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                GeofenceSignalBridge.deliver(
                    GeofenceObservation.Unavailable(SystemClock.elapsedRealtime()),
                )
            } else {
                SnoozeDebugLog.warning("geofence event carried error ${event.errorCode}")
            }
            return
        }

        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            // The observation keeps the platform's own timing where it offers
            // one: a transition can be delivered late (Doze, batching), and
            // the engine's staleness rules need the moment it *happened*.
            val atMs = event.triggeringLocation
                ?.let { it.elapsedRealtimeNanos / NANOS_PER_MILLI }
                ?: SystemClock.elapsedRealtime()
            GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atMs))
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
