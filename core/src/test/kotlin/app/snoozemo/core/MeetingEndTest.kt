package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which calendar end time is worth offering on the ongoing notification
 * (SPEC.md §4.5).
 */
class MeetingEndTest {

    private val now: Instant = Instant.parse("2026-01-01T13:00:00Z")

    private fun snooze(capIn: Duration = ActiveSnooze.DEFAULT_CAP) = ActiveSnooze(
        anchor = Anchor(capturedAt = now, ssid = "ExampleWifi"),
        startedAt = now,
        capExpiresAt = now.plus(capIn),
        mode = TrackingMode.DURATION_ONLY,
    )

    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    @Test
    fun `the earliest meeting that would change something is the one offered`() {
        // Overlapping meetings are ordinary, and the first to end is the one
        // the user is plausibly waiting out. A later end is still reachable by
        // leaving the snooze on its cap.
        val offer = MeetingEnd.offerFor(snooze(), listOf(at(180), at(90), at(240)), now)

        assertEquals(at(90), offer)
    }

    @Test
    fun `order in does not decide the answer`() {
        val ends = listOf(at(90), at(180))

        assertEquals(
            MeetingEnd.offerFor(snooze(), ends, now),
            MeetingEnd.offerFor(snooze(), ends.reversed(), now),
        )
    }

    @Test
    fun `a meeting ending inside the floor is not offered`() {
        // The service declines anything inside `MIN_CAP`, so this is a button
        // that can only fail — and a meeting ending in a few minutes is exactly
        // when it is most tempting to show one.
        val insideFloor = now.plus(ActiveSnooze.MIN_CAP).minus(Duration.ofMinutes(1))

        assertNull(MeetingEnd.offerFor(snooze(), listOf(insideFloor), now))
    }

    @Test
    fun `a meeting ending at the floor exactly is not offered`() {
        assertNull(MeetingEnd.offerFor(snooze(), listOf(now.plus(ActiveSnooze.MIN_CAP)), now))
    }

    @Test
    fun `a meeting ending after the cap is not offered`() {
        // The service honors a time past the cap by doing nothing and reports it
        // applied, so this would be a button that looks like it worked and moved
        // no deadline at all.
        val short = snooze(capIn = Duration.ofHours(1))

        assertNull(MeetingEnd.offerFor(short, listOf(at(90)), now))
    }

    @Test
    fun `a meeting ending exactly at the cap is not offered`() {
        val short = snooze(capIn = Duration.ofHours(1))

        assertNull(MeetingEnd.offerFor(short, listOf(at(60)), now))
    }

    @Test
    fun `the earliest is chosen from those that qualify, not overall`() {
        // The one inside the floor must not win by being earliest.
        val insideFloor = now.plus(Duration.ofMinutes(1))
        val offer = MeetingEnd.offerFor(snooze(), listOf(insideFloor, at(90), at(200)), now)

        assertEquals(at(90), offer)
    }

    @Test
    fun `no snooze running means nothing to offer`() {
        assertNull(MeetingEnd.offerFor(null, listOf(at(90)), now))
    }

    @Test
    fun `an empty calendar offers nothing`() {
        assertNull(MeetingEnd.offerFor(snooze(), emptyList(), now))
    }
}
