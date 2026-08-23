package app.snoozemo.presence

import app.snoozemo.core.Anchor
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.TrackingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The `direct` flavor's stand-in until Phase 7's `ForegroundPresenceMonitor`
 * lands (SPEC.md §3.4): it watches nothing and says nothing, so every snooze
 * on this flavor is honestly duration-only — the mode derivation at arm time
 * is what tells the user so, and the duration cap is what ends the snooze.
 *
 * Deliberately not a fake that pretends to track: a monitor that emitted
 * plausible updates would let the flavor claim presence tracking it does not
 * have, which is principle 2's failure wearing test clothes.
 */
class DurationOnlyPresenceMonitor : PresenceMonitor {

    override fun start(anchor: Anchor, sinceElapsedRealtimeMs: Long, armedAtEpochMs: Long): Flow<PresenceUpdate> =
        emptyFlow()

    override fun stop() = Unit

    /** Watching nothing supports nothing: every snooze is honestly a timer. */
    override fun supportedModes(anchor: Anchor): Set<TrackingMode> =
        setOf(TrackingMode.DURATION_ONLY)
}
