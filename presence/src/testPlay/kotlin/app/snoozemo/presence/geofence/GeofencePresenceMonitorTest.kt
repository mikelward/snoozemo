package app.snoozemo.presence.geofence

import app.snoozemo.core.Anchor
import app.snoozemo.core.CapabilityLossCause
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.PresenceEvent
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.snoozemo.core.DegradationCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a Wi-Fi-only anchor needs the durable recheck alarm`() {
        // No usable fix, but an SSID to re-read: the watch dies with the
        // service and there is no fence, so the alarm is the only durable
        // thing left.
        assertTrue(
            GeofencePresenceMonitor.needsWifiRecheck(
                Anchor(capturedAt = Instant.EPOCH, ssid = "ExampleWifi"),
            ),
        )
    }

    @Test
    fun `a duration-only anchor never arms the recheck alarm`() {
        // No fix and no SSID: nothing was ever watching Wi-Fi, so arming a
        // repeating restore would drain battery for a snooze with nothing to
        // check — the regression Codex caught on PR #105.
        assertFalse(GeofencePresenceMonitor.needsWifiRecheck(Anchor(capturedAt = Instant.EPOCH)))
    }

    @Test
    fun `an anchor with a usable fix never arms the recheck alarm`() {
        // A fix means a fence, and the fence is the durable watch — the
        // recheck alarm is only the no-fence stand-in.
        assertFalse(
            GeofencePresenceMonitor.needsWifiRecheck(
                Anchor(
                    capturedAt = Instant.EPOCH,
                    ssid = "ExampleWifi",
                    lat = 0.0,
                    lon = 0.0,
                    fixAccuracyM = 20f,
                ),
            ),
        )
    }

    @Test
    fun `a services outage normally outranks a refused registration`() {
        // The rule this PR does not change: a refused registration says
        // nothing about whether the subsystem works, while a services outage
        // indicts it outright.
        assertEquals(
            DegradationCause.LOCATION_SERVICES_OFF,
            GeofencePresenceMonitor.platformLevelOf(
                registration = DegradationCause.NOTHING_WATCHING,
                services = DegradationCause.LOCATION_SERVICES_OFF,
            ),
        )
    }

    @Test
    fun `a missing grant outranks a latched services outage`() {
        // Codex, PR #149. The services slot clears only on a delivered fix,
        // and no fix can arrive once the grant is gone — so without this the
        // cause would outlive its own refutation, name the wrong remedy for
        // the rest of the snooze, and (because `LOCATION_SERVICES_OFF` is not
        // a duration-only cause) leave an SSID anchor claiming `WIFI_ONLY`
        // with no grant to read that SSID with.
        for (grant in listOf(
            DegradationCause.LOCATION_PERMISSION_GONE,
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
        )) {
            assertEquals(
                grant,
                GeofencePresenceMonitor.platformLevelOf(
                    registration = grant,
                    services = DegradationCause.LOCATION_SERVICES_OFF,
                ),
            )
        }
    }

    @Test
    fun `with neither slot set nothing is reported`() {
        assertNull(GeofencePresenceMonitor.platformLevelOf(registration = null, services = null))
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
                PresenceEvent.CapabilityLost(CapabilityLossCause.MONITORING_UNAVAILABLE),
            ),
        )
    }
}
