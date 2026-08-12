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

    @Test
    fun `extending moves the cap out by the step`() {
        val snooze = snooze(Duration.ofHours(1))

        assertEquals(
            start.plus(Duration.ofMinutes(90)),
            snooze.extendedCap(Duration.ofMinutes(30)),
        )
    }

    @Test
    fun `extending stops at the backstop`() {
        // Repeated taps may not walk a snooze past the 8-hour default (SPEC.md
        // §7), measured from the start rather than from the current cap.
        val snooze = snooze(Duration.ofMinutes(7 * 60 + 50))

        assertEquals(
            start.plus(ActiveSnooze.DEFAULT_CAP),
            snooze.extendedCap(Duration.ofMinutes(30)),
        )
    }

    @Test
    fun `extending never reaches the configurable maximum`() {
        // The bug this pins: clamping to MAX_CAP let sixteen taps take a
        // default snooze from 8 hours to 24 — the backstop every other exit
        // falls back to, walked past half an hour at a time.
        var cap = ActiveSnooze.DEFAULT_CAP
        repeat(40) {
            cap = Duration.between(start, snooze(cap).extendedCap(Duration.ofMinutes(30)))
        }

        assertEquals(ActiveSnooze.DEFAULT_CAP, cap)
    }

    @Test
    fun `extending at the ceiling returns the cap unchanged`() {
        // How the caller tells "extended" from "cannot extend" without
        // re-deriving the clamp: nothing moved, so nothing is claimed to.
        val snooze = snooze(ActiveSnooze.DEFAULT_CAP)

        assertEquals(snooze.capExpiresAt, snooze.extendedCap(Duration.ofMinutes(30)))
    }

    @Test
    fun `a cap already past the backstop declines rather than jumping back`() {
        // Only a per-place setting could produce one, and none exists yet — but
        // a clamp that returned the ceiling here would *shorten* the snooze on
        // a tap that asked to lengthen it, which is the one direction `+30 min`
        // must never move.
        val snooze = snooze(Duration.ofHours(12))

        assertEquals(snooze.capExpiresAt, snooze.extendedCap(Duration.ofMinutes(30)))
    }

    @Test
    fun `a retry applies to the record it was queued for`() {
        val snooze = snooze()

        assertTrue(ActiveSnooze.retryStillApplies(snooze, snooze.startedAt))
    }

    @Test
    fun `a retry does not apply to a newer snooze's record`() {
        // The failure this guards, in both directions: a durable erase retry
        // outlives the snooze it was armed for, fires after the user has armed
        // a new one, and takes that snooze's cap with it while its zen rule
        // stays on — and a durable release retry ends that new snooze outright,
        // blaming a reboot that never happened.
        val newer = snooze().copy(startedAt = start.plusSeconds(60))

        assertFalse(ActiveSnooze.retryStillApplies(newer, start))
    }

    @Test
    fun `a retry applies when there is no record left to protect`() {
        assertTrue(ActiveSnooze.retryStillApplies(null, start))
    }

    @Test
    fun `an unidentified retry is not refused`() {
        // No caller can produce one, and refusing it would strand a released
        // record that a later cold start would restore into a live snooze.
        val snooze = snooze()

        assertTrue(ActiveSnooze.retryStillApplies(snooze, null))
    }

    @Test
    fun `a retry is not confused by a cap that moved`() {
        // startedAt is the identity precisely because the cap does not stay
        // put: `+30 min` moves it, and matching on it would let a retry queued
        // before an extension find "a different record" and give up.
        val snooze = snooze()
        val extended = snooze.copy(capExpiresAt = snooze.extendedCap(Duration.ofMinutes(30)))

        assertTrue(ActiveSnooze.retryStillApplies(extended, snooze.startedAt))
    }

    @Test
    fun `a retry is not confused by a snooze whose tracking degraded`() {
        // The other two fields that move under a live snooze. A release retry
        // is armed on the reboot path, where the mode and the anchor are the
        // most likely things to have changed by the time it fires — matching on
        // the whole record would drop a retry that is still the right one.
        val snooze = snooze()
        val degraded = snooze.copy(
            mode = TrackingMode.DURATION_ONLY,
            anchor = snooze.anchor.copy(ssid = null, lat = null, lon = null),
        )

        assertTrue(ActiveSnooze.retryStillApplies(degraded, snooze.startedAt))
    }

    @Test
    fun `a deadline further off than the maximum cap counts as expired`() {
        // What a record looks like once the wall clock is wound back while no
        // process is alive: its stored deadline is suddenly weeks away. No
        // snooze can legitimately have more than MAX_CAP left, so this is a
        // moved clock, and how much real time actually passed is unknowable.
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val snooze = ActiveSnooze(
            anchor = Anchor(capturedAt = now),
            startedAt = now,
            capExpiresAt = now.plus(Duration.ofDays(30)),
            mode = TrackingMode.FULL,
        )

        assertTrue(
            "an impossible remaining duration must resolve toward ending",
            snooze.isExpired(now),
        )
    }

    @Test
    fun `a deadline at exactly the maximum cap is still running`() {
        // The boundary belongs to the legitimate side: a 24 h snooze armed a
        // moment ago has exactly MAX_CAP left and must not end on the spot.
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val snooze = ActiveSnooze(
            anchor = Anchor(capturedAt = now),
            startedAt = now,
            capExpiresAt = now.plus(ActiveSnooze.MAX_CAP),
            mode = TrackingMode.FULL,
        )

        assertFalse("a full-length snooze must not end at arm", snooze.isExpired(now))
    }
}
