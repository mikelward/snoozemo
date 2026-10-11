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

    /**
     * The app saw a location grant land (SPEC.md §8.2): the one detector of
     * a *permission* change Android leaves in-process, since it broadcasts
     * none and a revocation kills the process. Asks the running monitor to
     * re-ask the grants now — repair a fence its loss refused, re-read a
     * Wi-Fi-only anchor's grant — rather than wait for the backstop or the
     * 15-minute recheck, inside whose window grace stays shut. A question
     * like the other pokes, so never held: a cold restore re-registers and
     * re-asks on its own way through.
     */
    data class GrantPoke(override val atElapsedRealtimeMs: Long) : GeofenceObservation

    /**
     * [WifiRecheckAlarm] fired: the periodic re-read that stands in for a
     * geofence on an anchor that has none (SPEC.md §6.6).
     *
     * A question rather than news, like the pokes — it carries nothing about
     * the world, because the alarm cannot see the world; what it asks for is
     * the Wi-Fi watch to be *running* so it can. But unlike a poke it is
     * never dropped for want of a monitor: no monitor is precisely the case
     * it exists for, and the wake is the whole answer. Never retained
     * either — a restore rebuilds the watch, which reads the current
     * association on its own, so there is nothing here worth replaying to a
     * later attach.
     */
    data class WifiRecheck(override val atElapsedRealtimeMs: Long) : GeofenceObservation

    /**
     * [CapabilityLossAlarm] fired: a `CapabilityLost` ending was decided and
     * durably recorded in [CapabilityLossStore], and this is the prompt to
     * act on it — not the payload. No cause travels with it: the monitor
     * re-reads the store keyed to whichever snooze is currently restoring, so
     * a stale firing from an already-superseded snooze finds nothing and is
     * a no-op, the same identity guard [GraceDeadlineStore] relies on.
     * Retained like an exit or a due grace deadline — the alarm is one-shot
     * and will not say this again — but ranked *below* both (Codex, PR #95,
     * second pass): unlike them, this prompt might turn out to be stale
     * before the store confirms anything, and their own evidence exists
     * nowhere else, so an unvalidated capability-loss prompt must never
     * discard a real exit or a due grace still waiting on a monitor.
     */
    data class CapabilityLoss(override val atElapsedRealtimeMs: Long) : GeofenceObservation
}
