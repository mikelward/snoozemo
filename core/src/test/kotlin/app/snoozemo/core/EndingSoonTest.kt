package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndingSoonTest {

    private val start: Instant = Instant.parse("2026-08-11T09:00:00Z")

    private val anchor = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = start,
        ssid = "ExampleWifi",
    )

    /** A snooze ending on leaving, armed with the default failsafe at its ceiling. */
    private fun leaving(mode: TrackingMode = TrackingMode.FULL) = ActiveSnooze(
        anchor = anchor,
        startedAt = start,
        capExpiresAt = start.plus(ActiveSnooze.DEFAULT_CAP),
        mode = mode,
    )

    /** A snooze the user narrowed to a time an hour out. */
    private fun timer() = leaving().copy(
        capExpiresAt = start.plus(Duration.ofHours(1)),
        endsOnDeparture = false,
        timerOnlyRequested = true,
    )

    @Test
    fun `the timed warning is armed five minutes before the end`() {
        assertEquals(Duration.ofMinutes(55), EndingSoon.timedWarningIn(Duration.ofHours(1)))
    }

    @Test
    fun `a snooze already inside the last five minutes arms no warning`() {
        // A restore there would buzz again for a warning the user may already
        // have seen and swiped away.
        assertNull(EndingSoon.timedWarningIn(Duration.ofMinutes(5)))
        assertNull(EndingSoon.timedWarningIn(Duration.ofMinutes(3)))
        assertNull(EndingSoon.timedWarningIn(Duration.ZERO))
    }

    @Test
    fun `a fired warning posts only inside its window`() {
        assertTrue(EndingSoon.timedWarningDue(Duration.ofMinutes(5)))
        assertTrue("an inexact alarm fires late, not early", EndingSoon.timedWarningDue(Duration.ofMinutes(1)))
        assertTrue("the two clock frames can disagree a little", EndingSoon.timedWarningDue(Duration.ofSeconds(330)))
        assertFalse("a cap moved after the alarm was armed", EndingSoon.timedWarningDue(Duration.ofMinutes(35)))
        assertFalse("already over: the cap check owns that", EndingSoon.timedWarningDue(Duration.ZERO))
    }

    @Test
    fun `an open confirmation window warns of leaving`() {
        assertEquals(EndingSoonCause.LEAVING, EndingSoon.presenceCause(leaving(), null, confirmingDeparture = true))
    }

    @Test
    fun `a running grace period warns of the lost Wi-Fi`() {
        assertEquals(
            EndingSoonCause.WIFI_LOST,
            EndingSoon.presenceCause(leaving(TrackingMode.WIFI_GRACE), 300_000L, confirmingDeparture = false),
        )
    }

    @Test
    fun `leaving outranks a grace period that somehow stands beside it`() {
        assertEquals(
            EndingSoonCause.LEAVING,
            EndingSoon.presenceCause(leaving(TrackingMode.WIFI_GRACE), 300_000L, confirmingDeparture = true),
        )
    }

    @Test
    fun `a check with nothing confirmed yet is not a warning`() {
        // A geofence exit or a Wi-Fi drop starts the test; it does not end it.
        assertNull(EndingSoon.presenceCause(leaving(), null, confirmingDeparture = false))
    }

    @Test
    fun `a snooze narrowed to its timer is not warned about a departure`() {
        // A departure no longer ends it (`endReasonFor`), so the warning would
        // announce an ending that is not coming.
        assertNull(EndingSoon.presenceCause(timer(), null, confirmingDeparture = true))
        assertNull(EndingSoon.presenceCause(null, 300_000L, confirmingDeparture = true))
    }

    @Test
    fun `a departure warning offers still here`() {
        assertEquals(KeepSnoozing.STILL_HERE, EndingSoon.keepSnoozing(leaving(), EndingSoonCause.LEAVING))
        assertEquals(
            KeepSnoozing.STILL_HERE,
            EndingSoon.keepSnoozing(leaving(TrackingMode.WIFI_GRACE), EndingSoonCause.WIFI_LOST),
        )
    }

    @Test
    fun `a timed warning offers thirty more minutes while there is room`() {
        assertEquals(KeepSnoozing.PLUS_THIRTY, EndingSoon.keepSnoozing(timer(), EndingSoonCause.TIME))
    }

    @Test
    fun `the failsafe at its ceiling offers nothing to push it back`() {
        // The backstop is the one bound nothing may move (SPEC.md §7); a
        // button that could only answer "at max" is a worse card than none.
        assertNull(EndingSoon.keepSnoozing(leaving(), EndingSoonCause.TIME))
    }

    @Test
    fun `the ongoing card of a snooze ending on leaving offers still here, not a step that cannot move`() {
        // Its cap already sits at the ceiling, so `+30 min` only ever said
        // "at max" (maintainer, 2026-10-13).
        val snooze = leaving()
        assertFalse(snooze.canExtend)
        assertEquals(KeepSnoozing.STILL_HERE, EndingSoon.keepSnoozing(snooze, cause = null))
    }

    @Test
    fun `the ongoing card of a timer offers thirty more minutes`() {
        assertEquals(KeepSnoozing.PLUS_THIRTY, EndingSoon.keepSnoozing(timer(), cause = null))
    }

    @Test
    fun `a degraded snooze at its ceiling offers neither`() {
        // Nothing watches a departure, so a retake would be refused, and the
        // cap cannot move: absent, never disabled.
        assertNull(EndingSoon.keepSnoozing(leaving(TrackingMode.DURATION_ONLY), cause = null))
    }
}
