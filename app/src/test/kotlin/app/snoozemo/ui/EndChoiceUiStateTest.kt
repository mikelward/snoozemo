package app.snoozemo.ui

import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.EndCondition
import app.snoozemo.core.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The three rules the main screen's offer has to get right, on the JVM.
 *
 * All three go wrong quietly — a row that shouldn't be there, a row that fails
 * on every tap — so each is asserted in both directions rather than only in
 * the direction that hides the bug.
 */
class EndChoiceUiStateTest {

    private val now: Instant = Instant.parse("2026-09-08T12:00:00Z")

    private fun snooze(
        startedAt: Instant = now,
        capIn: Duration = ActiveSnooze.DEFAULT_CAP,
        mode: TrackingMode = TrackingMode.FULL,
    ) = ActiveSnooze(
        anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
        startedAt = startedAt,
        capExpiresAt = startedAt.plus(capIn),
        mode = mode,
    )

    private val condition = EndCondition(
        endsAt = now.plus(Duration.ofHours(1)),
        floor = now.plus(ActiveSnooze.MIN_CAP),
        ceiling = now.plus(ActiveSnooze.DEFAULT_CAP),
    )

    private fun state(
        record: ActiveSnooze?,
        offerFor: Instant? = now,
        meetingEnds: List<Instant> = emptyList(),
        at: Instant = now,
    ) = endChoiceUiState(
        condition = condition,
        offerFor = offerFor,
        record = record,
        meetingEnds = meetingEnds,
        now = at,
        committing = false,
        failed = false,
        format = { "at ${it.epochSecond}" },
    )

    @Test
    fun `no offer means no rows`() {
        assertNull(
            endChoiceUiState(
                condition = null,
                offerFor = null,
                record = snooze(),
                meetingEnds = emptyList(),
                now = now,
                committing = false,
                failed = false,
                format = { "" },
            ),
        )
    }

    @Test
    fun `a tracking snooze is offered the departure`() {
        assertTrue(state(snooze())!!.tracksDeparture)
    }

    @Test
    fun `a snooze still capturing its anchor is not offered a departure yet`() {
        // The absence of an answer is not a yes: whether anything will watch
        // for a departure is unknown until the anchor lands, and the row
        // *lengthens* a cap — so offering it during capture risks an
        // eight-hour ceiling on a snooze that turns out to track nothing.
        assertFalse(state(snooze(mode = TrackingMode.SETTLING))!!.tracksDeparture)
    }

    @Test
    fun `a snooze resolving a departure keeps the row`() {
        // The grace period is a departure being resolved, not one nobody is
        // looking for.
        assertTrue(state(snooze(mode = TrackingMode.WIFI_GRACE))!!.tracksDeparture)
    }

    @Test
    fun `a duration-only snooze is not`() {
        // Nothing is watching for a departure, so the row would name an end
        // that cannot arrive.
        assertFalse(state(snooze(mode = TrackingMode.DURATION_ONLY))!!.tracksDeparture)
    }

    @Test
    fun `an unread record withholds the whole offer`() {
        // The offer is restored from saved state before the asynchronous
        // record read lands. Nothing here can be confirmed against a running
        // snooze yet, and reading null as "tracks departure" would offer to
        // put a duration-only snooze's cap back to its ceiling.
        assertNull(state(record = null))
    }

    @Test
    fun `another snooze's record cannot answer for this offer`() {
        val other = snooze(startedAt = now.minus(Duration.ofHours(2)))

        assertNull(state(record = other, offerFor = now))
    }

    @Test
    fun `a cap that crosses inside the floor takes the offer with it`() {
        // The same shape as a meeting sliding inside the floor, one row up:
        // the screen sits open, the clock moves, and there is no longer a time
        // the service would accept. Left standing, every tap on the time row
        // is refused and the controller cannot reseed past it — the record it
        // would rebuild from no longer offers a choice.
        val running = snooze(capIn = Duration.ofMinutes(40))

        assertNotNull(state(running))
        assertNull(state(running, at = now.plus(Duration.ofMinutes(20))))
    }

    @Test
    fun `meeting rows are offered earliest first, capped at two`() {
        val ends = listOf(
            now.plus(Duration.ofHours(3)),
            now.plus(Duration.ofHours(1)),
            now.plus(Duration.ofHours(2)),
        )

        val meetings = state(snooze(), meetingEnds = ends)!!.meetings

        assertEquals(2, meetings.size)
        assertEquals(now.plus(Duration.ofHours(1)), meetings[0].at)
        assertEquals(now.plus(Duration.ofHours(2)), meetings[1].at)
    }

    @Test
    fun `a label names the instant beside it`() {
        // The pairing is what a tap depends on: the row a user reads and the
        // time the tap sends have to be the same choice.
        val meetings = state(snooze(), meetingEnds = listOf(now.plus(Duration.ofHours(1))))!!.meetings

        assertEquals("at ${meetings[0].at.epochSecond}", meetings[0].label)
    }

    @Test
    fun `a meeting that has slid inside the floor is dropped as the clock moves`() {
        // Gathered when the record changed, still on screen a while later. The
        // service declines a time inside the floor, so leaving the row up
        // gives a button that fails on every tap.
        val ends = listOf(now.plus(Duration.ofMinutes(40)))

        assertEquals(1, state(snooze(), meetingEnds = ends)!!.meetings.size)
        assertTrue(
            state(snooze(), meetingEnds = ends, at = now.plus(Duration.ofMinutes(20)))!!
                .meetings.isEmpty(),
        )
    }

    @Test
    fun `a later meeting takes the place of one that has gone stale`() {
        // Every candidate is kept rather than the first two, so the row a
        // dropped meeting vacates can actually be filled: keeping only two
        // meant a third perfectly offerable meeting was discarded before the
        // clock ever reached it.
        val ends = listOf(
            now.plus(Duration.ofMinutes(40)),
            now.plus(Duration.ofHours(2)),
            now.plus(Duration.ofHours(3)),
        )

        assertEquals(
            listOf(ends[0], ends[1]),
            state(snooze(), meetingEnds = ends)!!.meetings.map { it.at },
        )
        assertEquals(
            listOf(ends[1], ends[2]),
            state(snooze(), meetingEnds = ends, at = now.plus(Duration.ofMinutes(20)))!!
                .meetings.map { it.at },
        )
    }
}
