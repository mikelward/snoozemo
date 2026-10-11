package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** `Until I leave` retaken somewhere else: one circle holding both places. */
class AnchorRetakeTest {

    // Synthetic positions on the equator (AGENTS.md, *Privacy*).
    private fun anchorAt(northM: Double, accuracy: Float = 10f, radius: Int = Anchor.DEFAULT_RADIUS_M, ssid: String? = null) =
        Anchor(
            lat = northM / AnchorRetake.M_PER_DEG_LAT,
            lon = 0.0,
            fixAccuracyM = accuracy,
            capturedAt = Instant.EPOCH,
            ssid = ssid,
            radiusM = radius,
        )

    private fun distance(a: Anchor, b: Anchor): Double =
        Departure.distanceM(Fix(a.lat!!, a.lon!!, 10f, 0L), b)!!

    @Test
    fun `two spots become the smallest circle holding both whole`() {
        val old = anchorAt(0.0)
        val fresh = anchorAt(200.0)

        val merged = AnchorRetake.merge(old, fresh)

        // (200 + 100 + 100) / 2, centred halfway.
        assertEquals(200.0, merged.radiusM.toDouble(), 1.0)
        assertEquals(100.0, distance(merged, old), 0.5)
        assertEquals(100.0, distance(merged, fresh), 0.5)
        // Both original circles sit inside it.
        assertTrue(distance(merged, old) + old.radiusM <= merged.radiusM + 0.5)
        assertTrue(distance(merged, fresh) + fresh.radiusM <= merged.radiusM + 0.5)
    }

    @Test
    fun `a spot inside the old circle keeps the old circle`() {
        // Already-large place, tapped near its centre: nothing grows.
        val old = anchorAt(0.0, radius = 300)
        val fresh = anchorAt(50.0)

        val merged = AnchorRetake.merge(old, fresh)

        assertEquals(300.0, merged.radiusM.toDouble(), 1.0)
        assertEquals(0.0, distance(merged, old), 0.5)
    }

    @Test
    fun `a retake folds into an already-grown circle`() {
        val first = AnchorRetake.merge(anchorAt(0.0), anchorAt(200.0))
        val second = AnchorRetake.merge(first, anchorAt(400.0))

        // (300 + 200 + 100) / 2 — still holds every spot so far.
        assertEquals(300.0, second.radiusM.toDouble(), 1.0)
        assertTrue(distance(second, anchorAt(0.0)) + 100 <= second.radiusM + 0.5)
    }

    @Test
    fun `past the cap the fresh spot stands alone`() {
        val fresh = anchorAt(1_000.0)

        assertSame(fresh, AnchorRetake.merge(anchorAt(0.0), fresh))
    }

    @Test
    fun `just under the cap still merges`() {
        // (780 + 100 + 100) / 2 = 490.
        assertEquals(490.0, AnchorRetake.merge(anchorAt(0.0), anchorAt(780.0)).radiusM.toDouble(), 1.0)
    }

    @Test
    fun `without a fix on either side there is nothing to merge`() {
        val noFix = Anchor(capturedAt = Instant.EPOCH, ssid = "ExampleWifi")
        val fresh = anchorAt(200.0)

        assertSame(fresh, AnchorRetake.merge(noFix, fresh))
        assertSame(noFix, AnchorRetake.merge(fresh, noFix))
    }

    @Test
    fun `the network and the vaguer accuracy come through`() {
        val merged = AnchorRetake.merge(
            anchorAt(0.0, accuracy = 30f, ssid = "OldWifi"),
            anchorAt(200.0, accuracy = 10f, ssid = "ExampleWifi"),
        )

        assertEquals("ExampleWifi", merged.ssid)
        assertEquals(30f, merged.fixAccuracyM!!, 0f)
    }

    @Test
    fun `two spots either side of the antimeridian meet near it`() {
        // About 200 m apart across ±180°; the centre must not land near 0°.
        val step = 100.0 / AnchorRetake.M_PER_DEG_LAT
        val old = Anchor(lat = 0.0, lon = 180.0 - step, fixAccuracyM = 10f, capturedAt = Instant.EPOCH)
        val fresh = Anchor(lat = 0.0, lon = -180.0 + step, fixAccuracyM = 10f, capturedAt = Instant.EPOCH)

        val merged = AnchorRetake.merge(old, fresh)

        assertEquals(200.0, merged.radiusM.toDouble(), 1.0)
        assertEquals(100.0, distance(merged, old), 1.0)
        assertEquals(100.0, distance(merged, fresh), 1.0)
    }
}
