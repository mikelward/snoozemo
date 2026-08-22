package app.snoozemo.presence.geofence

import app.snoozemo.core.CapabilityLossCause
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.PresenceEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settle decision at the duty reconcile — the pure half of the monitor,
 * pinned here because the timing bug it prevents lives in a race no JVM test
 * can schedule: `send` only queues the update, so an ending event must leave
 * the exit held for a teardown that beats the collector (Codex, PR #73).
 */
class GeofencePresenceMonitorTest {

    @Test
    fun `a refuted or degraded check settles the held exit`() {
        assertTrue(GeofencePresenceMonitor.settlesHeldExit(LocationDuty.SANITY, null))
        assertTrue(
            GeofencePresenceMonitor.settlesHeldExit(LocationDuty.SANITY, PresenceEvent.StillHere),
        )
    }

    @Test
    fun `an active check keeps the exit held`() {
        assertFalse(GeofencePresenceMonitor.settlesHeldExit(LocationDuty.ACTIVE, null))
    }

    @Test
    fun `an ending answer leaves the settling to the end itself`() {
        // The update is only queued at this point; settling now would lose a
        // confirmed departure to a teardown that beats the collector.
        assertFalse(
            GeofencePresenceMonitor.settlesHeldExit(LocationDuty.SANITY, PresenceEvent.Departed),
        )
        assertFalse(
            GeofencePresenceMonitor.settlesHeldExit(
                LocationDuty.SANITY,
                PresenceEvent.CapabilityLost(CapabilityLossCause.LOCATION_PERMISSION_REVOKED),
            ),
        )
    }
}
