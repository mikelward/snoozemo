package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSnoozeTest {

    private val start: Instant = Instant.parse("2026-08-11T09:00:00Z")

    private val anchorWithFix = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 25f,
        capturedAt = start,
        ssid = "ExampleWifi",
    )

    private fun snooze(cap: Duration = ActiveSnooze.DEFAULT_CAP) = ActiveSnooze(
        anchor = anchorWithFix,
        startedAt = start,
        capExpiresAt = start.plus(cap),
        mode = TrackingMode.from(anchorWithFix),
    )

    @Test
    fun `remaining counts down to the cap`() {
        val snooze = snooze()

        assertEquals(Duration.ofHours(8), snooze.remaining(start))
        assertEquals(Duration.ofHours(4), snooze.remaining(start.plusSeconds(4 * 3600)))
    }

    @Test
    fun `remaining is floored at zero once the cap has passed`() {
        // A negative countdown would reach the notification as "ends in -4m".
        val snooze = snooze()

        assertEquals(Duration.ZERO, snooze.remaining(start.plus(Duration.ofHours(12))))
    }

    @Test
    fun `the cap fires on its exact instant, not after it`() {
        // Fail open: an ambiguous boundary resolves toward ending the snooze.
        val snooze = snooze()

        assertFalse(snooze.isExpired(start.plus(Duration.ofHours(8)).minusMillis(1)))
        assertTrue(snooze.isExpired(start.plus(Duration.ofHours(8))))
        assertTrue(snooze.isExpired(start.plus(Duration.ofHours(9))))
    }

    @Test
    fun `an anchor with a vague fix is not usable for departure tests`() {
        val vague = Anchor(
            lat = 0.0,
            lon = 0.0,
            fixAccuracyM = Anchor.MAX_ANCHOR_ACCURACY_M + 1f,
            capturedAt = start,
        )

        assertFalse(vague.hasUsableFix)
        assertTrue(vague.copy(fixAccuracyM = 25f).hasUsableFix)
    }

    @Test
    fun `an anchor with no fix at all is not usable, however precise the SSID`() {
        // Arming indoors with no signal: Wi-Fi-only mode, not a pretend fix.
        val wifiOnly = Anchor(capturedAt = start, ssid = "ExampleWifi")

        assertFalse(wifiOnly.hasUsableFix)
    }

    @Test
    fun `a snooze defaults to the eight hour cap and an unnamed place`() {
        val snooze = snooze()

        assertEquals(ActiveSnooze.DEFAULT_PLACE_NAME, snooze.placeName)
        assertEquals(start.plus(Duration.ofHours(8)), snooze.capExpiresAt)
    }

    @Test
    fun `tracking mode is the most capable one the anchor supports`() {
        assertEquals(TrackingMode.FULL, TrackingMode.from(anchorWithFix))

        val noFix = Anchor(capturedAt = start, ssid = "ExampleWifi")
        assertEquals(TrackingMode.WIFI_ONLY, TrackingMode.from(noFix))

        val nothing = Anchor(capturedAt = start)
        assertEquals(TrackingMode.DURATION_ONLY, TrackingMode.from(nothing))
    }

    @Test
    fun `a fix too vague to test against does not count as full tracking`() {
        // The failure this guards: arming indoors on a 500 m cell fix and
        // recording FULL, so the notification claims tracking that cannot work.
        val vagueFix = anchorWithFix.copy(fixAccuracyM = Anchor.MAX_ANCHOR_ACCURACY_M + 1f)

        assertEquals(TrackingMode.WIFI_ONLY, TrackingMode.from(vagueFix))
        assertEquals(
            TrackingMode.DURATION_ONLY,
            TrackingMode.from(vagueFix.copy(ssid = null)),
        )
    }
}
