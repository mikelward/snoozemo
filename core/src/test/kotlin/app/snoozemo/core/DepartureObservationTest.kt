package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The readout the departure test hands the screen (`SPEC.md` §4.6).
 *
 * The point of the type is that the number on screen is the number the test
 * used, so these assert both directions of that: what it reports agrees with
 * [Departure.qualifies] on the same fix, and it goes stale rather than sitting
 * there once fixes stop arriving.
 *
 * Coordinates are fictional — the anchor is at (0, 0) and every fix is an
 * offset north of it (`AGENTS.md`, privacy).
 */
class DepartureObservationTest {

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun metersNorth(m: Double): Double = m / 111_320.0

    private fun fix(northM: Double, accuracyM: Float, atSeconds: Long = 0) = Fix(
        lat = metersNorth(northM),
        lon = 0.0,
        accuracyM = accuracyM,
        elapsedRealtimeMs = atSeconds * 1_000L,
    )

    private val anchor = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = t0,
        ssid = "ExampleWifi",
    )

    @Test
    fun `the distance reported is the distance measured`() {
        val observation = Departure.observe(fix(northM = 200.0, accuracyM = 10f), anchor)!!

        assertEquals(200.0, observation.distanceM, 1.0)
        assertEquals(10f, observation.accuracyM, 0f)
        assertEquals(anchor.radiusM, observation.radiusM)
    }

    @Test
    fun `the meters to go are what this fix still needs, accuracy included`() {
        // 200 m out, 10 m of accuracy, a 150 m radius: 40 m of margin against a
        // 50 m band, so 10 m still to go. A vaguer fix at the same distance
        // needs more, which is the whole reason the readout is per-fix.
        val sharp = Departure.observe(fix(northM = 200.0, accuracyM = 10f), anchor)!!
        val vague = Departure.observe(fix(northM = 200.0, accuracyM = 40f), anchor)!!

        assertEquals(10.0, sharp.remainingM, 1.0)
        assertEquals(40.0, vague.remainingM, 1.0)
        assertFalse(sharp.qualifies)
        assertFalse(vague.qualifies)
    }

    @Test
    fun `there is nothing left to go once the fix qualifies`() {
        val fix = fix(northM = 400.0, accuracyM = 15f)
        val observation = Departure.observe(fix, anchor)!!

        assertTrue(observation.qualifies)
        // Never negative: the screen says "confirming" here, and a negative
        // count of meters remaining would be a number with no meaning.
        assertEquals(0.0, observation.remainingM, 0.0)
    }

    @Test
    fun `what it reports agrees with the test that ends the snooze`() {
        // The two must not be able to disagree — a screen saying "0 m to go"
        // beside a snooze that has not budged is exactly the confusion this
        // readout exists to prevent.
        for (distance in listOf(0.0, 150.0, 199.0, 201.0, 500.0)) {
            val f = fix(northM = distance, accuracyM = 10f)
            assertEquals(
                "at $distance m",
                Departure.qualifies(f, anchor),
                Departure.observe(f, anchor)!!.qualifies,
            )
        }
    }

    @Test
    fun `an anchor with no coordinates has nothing to measure`() {
        val noFixAnchor = Anchor(capturedAt = t0, ssid = "ExampleWifi")

        assertNull(Departure.observe(fix(northM = 400.0, accuracyM = 15f), noFixAnchor))
    }

    @Test
    fun `a reading goes stale rather than sitting on the screen`() {
        val at = Duration.ofMinutes(10).toMillis()
        val observation = Departure.observe(
            fix(northM = 200.0, accuracyM = 10f, atSeconds = Duration.ofMinutes(10).seconds),
            anchor,
        )!!

        assertTrue("its own moment", observation.isFresh(at))
        assertTrue(observation.isFresh(at + DepartureObservation.FRESH_FOR_MS))
        assertFalse(observation.isFresh(at + DepartureObservation.FRESH_FOR_MS + 1))
    }

    @Test
    fun `a reading from the future is not fresh either`() {
        // Boot resets elapsed realtime, so a reading held across one would sit
        // ahead of the clock reading it. Absent beats wrong.
        val observation = Departure.observe(
            fix(northM = 200.0, accuracyM = 10f, atSeconds = Duration.ofMinutes(10).seconds),
            anchor,
        )!!

        assertFalse(observation.isFresh(Duration.ofMinutes(9).toMillis()))
    }
}
