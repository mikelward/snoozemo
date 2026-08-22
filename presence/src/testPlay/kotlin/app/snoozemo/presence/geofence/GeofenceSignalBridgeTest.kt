package app.snoozemo.presence.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceSignalBridgeTest {

    @Test
    fun `delivers to the attached monitor and stops after close`() {
        val seen = mutableListOf<GeofenceObservation>()
        val handle = GeofenceSignalBridge.attach { seen += it }

        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 1_000))
        handle.close()
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 2_000))

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(1_000)), seen)
    }

    @Test
    fun `a superseded monitor's late close cannot evict its replacement`() {
        // On a restart the replacement attaches before the old instance's
        // teardown runs — the same overlap the screen's watches deal with.
        val old = GeofenceSignalBridge.attach { }
        val seen = mutableListOf<GeofenceObservation>()
        val replacement = GeofenceSignalBridge.attach { seen += it }

        old.close()
        GeofenceSignalBridge.deliver(GeofenceObservation.Unavailable(atElapsedRealtimeMs = 3_000))
        replacement.close()

        assertTrue(seen.single() is GeofenceObservation.Unavailable)
    }

    @Test
    fun `an observation with no monitor is dropped without throwing`() {
        // The drop is said in the debug log; what this pins is that a
        // broadcast into an empty process cannot crash it.
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 4_000))
    }
}
