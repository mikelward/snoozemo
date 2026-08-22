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
     * The §6.6 grace alarm fired: the deadline the engine armed for an
     * unverifiable snooze has come due. Held if no monitor is attached — the
     * alarm is spent and will not say this again — and outranked only by an
     * exit. A replay against a state whose deadline has moved on is a no-op
     * by the engine's own check, so holding it is free.
     */
    data class GraceElapsed(override val atElapsedRealtimeMs: Long) : GeofenceObservation

    /**
     * The platform reported geofencing is not currently available — location
     * switched off system-wide is the ordinary cause. Recoverable in
     * principle, so it degrades rather than ends (SPEC.md §8.4).
     */
    data class Unavailable(override val atElapsedRealtimeMs: Long) : GeofenceObservation

    /**
     * The §6.10 backstop asking a running monitor for one resting fix — a
     * geofence that never fired says nothing, so the probe is what hands the
     * engine evidence to test (SPEC.md §6.10). Not an observation of the
     * world, so it is never held: a poke with no monitor is dropped, because
     * the backstop that poked also restored the service, and a restored
     * monitor takes its own starting probe.
     */
    data class SanityPoke(override val atElapsedRealtimeMs: Long) : GeofenceObservation

    /**
     * A warm wake asking the running monitor to re-attempt the fence
     * registration — repair without replacing the collection, so the
     * engine's accumulated failure memory survives (Codex, PR #75). Like
     * the sanity poke, a question rather than news: never held, because a
     * cold restore registers on its own way through.
     */
    data class RepairPoke(override val atElapsedRealtimeMs: Long) : GeofenceObservation
}
