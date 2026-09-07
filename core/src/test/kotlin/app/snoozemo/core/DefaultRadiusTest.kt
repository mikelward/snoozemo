package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * How big "here" is, at the value the app actually ships (`SPEC.md` §6.6).
 *
 * The mechanism traces in `DepartureTest` pin their own radius, so this is the
 * one place the *product* number is asserted — and the one place its cost is
 * written down. Both matter: the default moved 150 → 100 on a maintainer's
 * call, and what that buys and what it spends should be visible to whoever
 * moves it next rather than rediscovered from a failing trace.
 */
class DefaultRadiusTest {

    private val anchor = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = Instant.EPOCH,
        ssid = "ExampleWifi",
    )

    private fun fix(northM: Double, accuracyM: Float) = Fix(
        lat = northM / 111_320.0,
        lon = 0.0,
        accuracyM = accuracyM,
        elapsedRealtimeMs = 0L,
    )

    @Test
    fun `here is a large building, not a room and not a neighborhood`() {
        assertEquals(100, Anchor.DEFAULT_RADIUS_M)
        assertEquals(100, anchor.radiusM)
    }

    @Test
    fun `a departure costs the radius, the band and both readings' vagueness`() {
        // The number a user sees is not the radius: what is compared is
        // `distance - uncertainty`, and the uncertainty is *both* confidence
        // radii in quadrature — this anchor's 20 m as well as the fix's own.
        // So a sharp fix departs past ~172 m and a vague one considerably
        // later. Shrinking the radius moves this floor; it does not make the
        // app decisive.
        assertTrue(Departure.qualifies(fix(northM = 173.0, accuracyM = 10f), anchor))
        assertTrue(
            "a 10 m fix against a 20 m anchor is not out at 172 m",
            !Departure.qualifies(fix(northM = 172.0, accuracyM = 10f), anchor),
        )
        assertTrue("a 40 m fix needs 22 m more", !Departure.qualifies(fix(northM = 180.0, accuracyM = 40f), anchor))
        assertTrue(Departure.qualifies(fix(northM = 195.0, accuracyM = 40f), anchor))
    }

    @Test
    fun `the confidently-inside zone shrank with it, and that is the cost`() {
        // `STILL_HERE` needs `distance + uncertainty <= radius`, so a phone
        // sitting 120 m from where it armed used to read as comfortably inside
        // at a 150 m radius and now reads as ambiguous. Three ambiguous
        // readings in a row degrade tracking to Wi-Fi-only
        // (`DEGRADED_AFTER_USELESS_OBSERVATIONS`), which is the large-venue
        // case this app is aimed at — a different floor, the far end of a
        // site. Asserted rather than commented so the trade is visible to
        // whoever moves the default next.
        //
        // Counting the anchor's own accuracy narrowed this zone again: with a
        // 20 m anchor and a 10 m fix the uncertainty is ~22 m, so confidently
        // inside now means within ~77 m rather than ~90 m. That is the honest
        // reading of a vague capture rather than a regression — the app never
        // knew where the origin was to 90 m — and the way to widen it back is
        // a sharper anchor, not a smaller subtraction.
        val farSideOfALargeVenue = fix(northM = 120.0, accuracyM = 10f)

        assertEquals(
            DepartureVerdict.INCONCLUSIVE,
            Departure.consider(farSideOfALargeVenue, anchor, DepartureProgress.NONE).verdict,
        )
        assertEquals(
            "85 m is now ambiguous too, since the anchor itself is only good to 20 m",
            DepartureVerdict.INCONCLUSIVE,
            Departure.consider(fix(northM = 85.0, accuracyM = 10f), anchor, DepartureProgress.NONE).verdict,
        )
        assertEquals(
            "and it is not a departure either — ambiguous, not outside",
            DepartureVerdict.STILL_HERE,
            Departure.consider(fix(northM = 70.0, accuracyM = 10f), anchor, DepartureProgress.NONE).verdict,
        )
    }
}
