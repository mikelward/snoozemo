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
        endsOnMotion: Boolean = false,
    ) = ActiveSnooze(
        anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
        startedAt = startedAt,
        capExpiresAt = startedAt.plus(capIn),
        mode = mode,
        endsOnMotion = endsOnMotion,
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

    /**
     * The `When I move` row is a `play`-only capability, because only that
     * flavor declares a foreground service — and this file runs on both, so
     * every assertion here is against the flavor's own answer rather than a
     * hard-coded one. On `direct` the row is correctly offered nowhere, and
     * these read as "still nowhere".
     */
    private fun offered(
        record: ActiveSnooze? = snooze(),
        hasSensor: Boolean = true,
    ): Boolean = offersMotionEnd(record, deviceHasMotionSensor = { hasSensor })

    @Test
    fun `until I move is offered on a running snooze, chosen or not`() {
        // A choice like `Until I leave`, not a switch (maintainer, 2026-09-10):
        // the row stays offered once chosen, and a second tap changes nothing,
        // so there is no state for the offer to carry.
        assertEquals(buildHoldsForegroundService, offered())
        assertEquals(buildHoldsForegroundService, offered(snooze(endsOnMotion = true)))
    }

    @Test
    fun `until I move is offered whatever the tracking mode`() {
        // Where location can see nothing is where this row is the only answer
        // left, so gating it on tracking withheld it from the snooze that
        // needed it most (maintainer, 2026-09-10).
        for (mode in TrackingMode.entries) {
            assertEquals("$mode", buildHoldsForegroundService, offered(snooze(mode = mode)))
        }
    }

    @Test
    fun `until I move is withheld on a phone with no such sensor`() {
        // Offering it would produce a row the service rolls straight back —
        // a choice that undoes itself is worse than one that was never there
        // (Codex, PR #252).
        assertFalse(offered(hasSensor = false))
    }

    @Test
    fun `an unavailable motion row says which of the two reasons it is`() {
        // The row's absence used to be silent, so "this build does not have
        // the feature" and "this phone cannot do it" looked identical from the
        // outside (maintainer, 2026-09-10). Asserted against the same function
        // the screen acts on, so the reason and the behavior cannot drift.
        //
        // Flavor-aware rather than hard-coded: `direct` never gets past the
        // first clause, so pinning either answer would be a test that agrees
        // with itself on one flavor.
        if (buildHoldsForegroundService) {
            assertNull("nothing is wrong when both hold", motionEndUnavailability { true })
            assertEquals(
                "this device has no significant-motion sensor",
                motionEndUnavailability { false },
            )
        } else {
            assertEquals(
                "the build is answered first, and the sensor never asked",
                "this build holds no foreground service",
                // `throw`, not `Assert.fail`: a Java `void` method is `Unit`
                // to Kotlin, so `fail` does not satisfy a `() -> Boolean`.
                motionEndUnavailability { throw AssertionError("the sensor must not be asked") },
            )
        }
    }

    @Test
    fun `until I move needs a running snooze, and asks the platform nothing`() {
        // The sensor lookup is a `SensorManager` call made from composition,
        // so an idle screen must not reach it — the common first frame has no
        // snooze at all (Codex, PR #252).
        var asked = 0

        assertFalse(offersMotionEnd(null, deviceHasMotionSensor = { asked++; true }))

        assertEquals("the platform was not asked", 0, asked)
    }

    @Test
    fun `a build with no foreground service asks the platform nothing either`() {
        // `direct` cannot hold one, so the answer could not change the outcome.
        var asked = 0

        val offered = offersMotionEnd(snooze(), deviceHasMotionSensor = { asked++; true })

        assertEquals(buildHoldsForegroundService, offered)
        assertEquals(if (buildHoldsForegroundService) 1 else 0, asked)
    }

    @Test
    fun `until I move's availability is answered apart from the time offer`() {
        // Once the cap comes inside `MIN_CAP` there is no time left to choose
        // and `endChoiceUiState` withholds the whole offer. This answer is
        // about the build and the phone, not the cap, so it does not move —
        // it is the screen that withholds the row along with the group it now
        // sits in (maintainer, 2026-09-10), and it does so from that gate, not
        // from this one.
        val nearlyOver = snooze(capIn = Duration.ofMinutes(5), endsOnMotion = true)

        assertNull("no time left to choose", state(nearlyOver))
        assertEquals(
            "while the availability answer is unaffected by that gate",
            buildHoldsForegroundService,
            offered(nearlyOver),
        )
    }

    @Test
    fun `neither question is answered from another snooze's record`() {
        // Fails closed the way every other field here does: an offer this
        // cannot confirm belongs to the running snooze must not draw a row
        // over that snooze.
        val state = state(
            record = snooze(startedAt = now, endsOnMotion = true),
            offerFor = now.minus(Duration.ofMinutes(5)),
        )

        assertNull(state)
    }
}
