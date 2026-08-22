package app.snoozemo.presence

import app.snoozemo.core.Anchor
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.Presence
import app.snoozemo.core.PresenceSignal
import app.snoozemo.core.PresenceState
import app.snoozemo.core.PresenceUpdate

/**
 * The one stateful step every monitor shares: feed a signal to the engine,
 * carry the state, and shape the result into the [PresenceUpdate] the
 * controller consumes.
 *
 * Deliberately tiny and deliberately in the shared source set — the geofence
 * monitor and Phase 7's foreground monitor differ in which sensors they
 * register, never in what a signal means, and a second copy of this loop is
 * where the two flavors would start to drift. Pure over an injected clock
 * seed, so a JVM test can drive it without Android.
 *
 * Not thread-safe on its own: a monitor calls [accept] from one confined
 * context (the flow's callback scope), which is also what keeps the engine's
 * state a plain value.
 */
internal class PresenceFeed(
    private val anchor: Anchor,
    seedElapsedRealtimeMs: Long,
) {

    /**
     * Seeded with the arm moment per `PresenceState.latestEvidenceMs`'s
     * contract: a cached last-known location from before the tile was tapped
     * must not be the first thing the engine believes.
     */
    private var state = PresenceState(latestEvidenceMs = seedElapsedRealtimeMs)

    /** What the engine currently wants from location (SPEC.md §6.7). */
    val duty: LocationDuty
        get() = Presence.duty(state, anchor)

    /**
     * The engine's own degradation level, for a restate that arrives outside
     * any signal — a repair clearing the *platform* level must not speak for
     * the feed's, or a synthesized `null` promotes a snooze whose fixes are
     * still failing (Codex, PR #75). Only a usable fix clears this one.
     */
    val degradation: app.snoozemo.core.DegradationCause?
        get() = state.degradation

    /**
     * Feeds one signal through and returns what to report: the event (usually
     * null) and the degradation level restated, exactly the two halves
     * [PresenceUpdate] documents.
     */
    fun accept(signal: PresenceSignal): PresenceUpdate {
        val step = Presence.advance(state, signal, anchor)
        state = step.state
        return PresenceUpdate(event = step.event, degradation = step.state.degradation)
    }
}
