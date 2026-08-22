package app.snoozemo.presence.geofence

/**
 * What the geofence receiver saw, in the shape the monitor consumes.
 *
 * A tiny sealed type rather than the raw `GeofencingEvent`, so the handoff
 * across [GeofenceSignalBridge] carries no Play Services type — and so the
 * bridge and the monitor's handling of each case stay testable on the JVM.
 */
internal sealed interface GeofenceObservation {

    val atElapsedRealtimeMs: Long

    /** The platform reported the device left the registered fence. */
    data class Exit(override val atElapsedRealtimeMs: Long) : GeofenceObservation

    /**
     * The platform reported geofencing is not currently available — location
     * switched off system-wide is the ordinary cause. Recoverable in
     * principle, so it degrades rather than ends (SPEC.md §8.4).
     */
    data class Unavailable(override val atElapsedRealtimeMs: Long) : GeofenceObservation
}
