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

    private fun snooze(
        capIn: Duration = ActiveSnooze.DEFAULT_CAP,
        ceilingIn: Duration = ActiveSnooze.DEFAULT_CAP,
    ) = ActiveSnooze(
        anchor = Anchor(capturedAt = now, ssid = "ExampleWifi"),
        startedAt = now,
        capExpiresAt = now.plus(capIn),
        mode = TrackingMode.DURATION_ONLY,
        capCeilingAt = now.plus(ceilingIn),
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
    fun `a meeting ending after the backstop is not offered`() {
        // The service clamps a time past the backstop, so this would be a
        // button that looks like it worked and set a different deadline.
        //
        // Asked of the backstop rather than of the cap (maintainer,
        // 2026-09-11): a chosen time now moves the cap either way, so a
        // meeting between a shortened cap and the backstop is a time the
        // service will honor — see `a meeting past a shortened cap is still
        // offered`.
        val short = snooze(capIn = Duration.ofHours(1), ceilingIn = Duration.ofHours(1))

        assertNull(MeetingEnd.offerFor(short, listOf(at(90)), now))
    }

    @Test
    fun `a meeting ending exactly at the backstop is not offered`() {
        val short = snooze(capIn = Duration.ofHours(1), ceilingIn = Duration.ofHours(1))

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

    @Test
    fun `several offers come back earliest first`() {
        val offers = MeetingEnd.offersFor(snooze(), listOf(at(240), at(90), at(180)), now, limit = 2)

        assertEquals(listOf(at(90), at(180)), offers)
    }

    @Test
    fun `two meetings ending together are one choice`() {
        // Back-to-back calls sharing an end, or a meeting and someone's
        // reminder for it: the second row would be the same time twice.
        val offers = MeetingEnd.offersFor(snooze(), listOf(at(90), at(90), at(180)), now, limit = 2)

        assertEquals(listOf(at(90), at(180)), offers)
    }

    @Test
    fun `the same ends are excluded as for a single offer`() {
        val insideFloor = now.plus(Duration.ofMinutes(1))
        val pastTheCap = now.plus(ActiveSnooze.DEFAULT_CAP).plus(Duration.ofMinutes(1))

        val offers = MeetingEnd.offersFor(
            snooze(),
            listOf(insideFloor, pastTheCap, at(90), at(180)),
            now,
            limit = 4,
        )

        assertEquals(listOf(at(90), at(180)), offers)
    }

    @Test
    fun `a meeting past a shortened cap is still offered`() {
        // The bound is the backstop, not the cap the snooze currently carries
        // (maintainer, 2026-09-11). A chosen time moves the cap either way, so
        // a meeting inside the backstop is a time the service will take — and
        // read from the cap, stepping down to an hour took every later meeting
        // off the screen for good.
        val shortened = snooze(capIn = Duration.ofHours(1), ceilingIn = Duration.ofHours(8))

        val offers = MeetingEnd.offersFor(shortened, listOf(at(90), at(300)), now, limit = 4)

        assertEquals(listOf(at(90), at(300)), offers)
    }

    @Test
    fun `a meeting past the backstop is still excluded`() {
        // The backstop moved the bound out; it did not remove it. Nothing
        // reaches past where the snooze was always going to end (SPEC.md §7).
        val shortened = snooze(capIn = Duration.ofHours(1), ceilingIn = Duration.ofHours(2))

        val offers = MeetingEnd.offersFor(shortened, listOf(at(90), at(300)), now, limit = 4)

        assertEquals(listOf(at(90)), offers)
    }

    @Test
    fun `fewer qualifying ends than asked for is not an error`() {
        val offers = MeetingEnd.offersFor(snooze(), listOf(at(90)), now, limit = 2)

        assertEquals(listOf(at(90)), offers)
    }

    @Test
    fun `a single offer is the first of several`() {
        // The claim the KDoc makes: one function, one set of rules, so the
        // notification's action and a screen listing more cannot disagree.
        val ends = listOf(at(240), at(90), at(180))

        assertEquals(
            MeetingEnd.offerFor(snooze(), ends, now),
            MeetingEnd.offersFor(snooze(), ends, now, limit = 1).firstOrNull(),
        )
    }

    @Test
    fun `no snooze running offers nothing to a list either`() {
        assertEquals(emptyList<java.time.Instant>(), MeetingEnd.offersFor(null, listOf(at(90)), now, limit = 2))
    }

    @Test
    fun `a cap named directly offers on the same rules as a record`() {
        // The idle screen's offer to start has no record to read a cap from
        // (SPEC.md §4.4); what bounds it is the cap the snooze would start
        // with, and a meeting the running rows would offer must be one the
        // idle rows offer too.
        val ends = listOf(at(240), at(90), at(180), now.plus(ActiveSnooze.MIN_CAP))
        val cap = now.plus(ActiveSnooze.DEFAULT_CAP)

        assertEquals(
            MeetingEnd.offersFor(snooze(), ends, now, limit = 2),
            MeetingEnd.offersBefore(cap, ends, now, limit = 2),
        )
        assertEquals(listOf(at(90), at(180)), MeetingEnd.offersBefore(cap, ends, now, limit = 2))
    }

    @Test
    fun `a cap named directly still excludes the cap itself`() {
        // At or past the cap is honored by doing nothing, on either path.
        val cap = at(120)

        assertEquals(
            listOf(at(90)),
            MeetingEnd.offersBefore(cap, listOf(at(90), at(120), at(150)), now, limit = 3),
        )
    }

    @Test
    fun `asking for none offers none`() {
        assertEquals(
            emptyList<java.time.Instant>(),
            MeetingEnd.offersFor(snooze(), listOf(at(90)), now, limit = 0),
        )
    }
}
