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

    /** An anchor with both signals: a fix precise enough to test, and an SSID. */
    private fun bothSignals(at: Instant = now) = Anchor(
        capturedAt = at,
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 25f,
        ssid = "ExampleWifi",
    )

    private fun snooze(
        startedAt: Instant = now,
        capIn: Duration = ActiveSnooze.DEFAULT_CAP,
        mode: TrackingMode = TrackingMode.FULL,
        endsOnMotion: Boolean = false,
        timerOnlyRequested: Boolean = false,
        anchor: Anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
        // What decides whether the rows are offered at all: a chosen time moves
        // the cap either way, so the §7 backstop is the edge of what is
        // choosable rather than the cap. Defaults to the cap, which is what an
        // unshortened snooze carries.
        ceilingIn: Duration = capIn,
    ) = ActiveSnooze(
        anchor = anchor,
        startedAt = startedAt,
        capExpiresAt = startedAt.plus(capIn),
        mode = mode,
        endsOnMotion = endsOnMotion,
        timerOnlyRequested = timerOnlyRequested,
        capCeilingAt = startedAt.plus(ceilingIn),
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
        buildTracksDeparture: Boolean = app.snoozemo.presence.PRESENCE_TRACKS_DEPARTURE,
    ) = endChoiceUiState(
        condition = condition,
        offerFor = offerFor,
        record = record,
        meetingEnds = meetingEnds,
        now = at,
        committing = false,
        failed = false,
        buildTracksDeparture = buildTracksDeparture,
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

    // `Until I leave`'s card names only the signals the snooze actually has
    // (SPEC.md §4.4). Asserted in every direction, because each wrong answer
    // is a sentence that reads fine and is false about this snooze.

    @Test
    fun `both signals name both`() {
        val state = state(snooze(anchor = bothSignals()))!!
        assertTrue(state.departureUsesWifi)
        assertTrue(state.departureUsesArea)
    }

    @Test
    fun `an anchor with no network names only the area`() {
        val state = state(snooze(anchor = bothSignals().copy(ssid = null)))!!
        assertFalse(state.departureUsesWifi)
        assertTrue(state.departureUsesArea)
    }

    @Test
    fun `an anchor with no usable fix names only the Wi-Fi`() {
        // A fix this vague cannot distinguish leaving from standing still, so
        // the SSID is doing the whole job.
        val vague = bothSignals().copy(fixAccuracyM = 5_000f)
        assertFalse(vague.hasUsableFix)
        val state = state(snooze(anchor = vague, mode = TrackingMode.WIFI_ONLY))!!
        assertTrue(state.departureUsesWifi)
        assertFalse(state.departureUsesArea)
    }

    @Test
    fun `a degraded snooze stops naming the area its anchor still carries`() {
        // The anchor is captured once and never rewritten, so it still has a
        // usable fix long after location stopped producing them and
        // `modeFor` lowered the snooze to WIFI_ONLY. Reading the anchor alone
        // would go on claiming the area while only Wi-Fi can end this snooze
        // (Codex, PR #261).
        val anchor = bothSignals()
        assertTrue(anchor.hasUsableFix)
        val state = state(snooze(anchor = anchor, mode = TrackingMode.WIFI_ONLY))!!
        assertTrue(state.departureUsesWifi)
        assertFalse(state.departureUsesArea)
    }

    @Test
    fun `a grace period names the Wi-Fi that is bounding it`() {
        val state = state(snooze(anchor = bothSignals(), mode = TrackingMode.WIFI_GRACE))!!
        assertTrue(state.departureUsesWifi)
        assertFalse(state.departureUsesArea)
    }

    @Test
    fun `a settling snooze names both rather than narrowing mid-capture`() {
        // Nothing determined yet. Narrowing here would flip the sentence as
        // the fix landed, a few seconds after the card was opened; the row's
        // own gate is what keeps a snooze that really has neither from
        // reaching this card at all.
        val settling = Anchor(capturedAt = now)
        assertFalse(settling.hasUsableFix)
        val state = state(snooze(anchor = settling, mode = TrackingMode.SETTLING))!!
        assertTrue(state.departureUsesWifi)
        assertTrue(state.departureUsesArea)
    }

    @Test
    fun `a settling snooze that already has its SSID still names both`() {
        val settling = Anchor(capturedAt = now, ssid = "ExampleWifi")
        val state = state(snooze(anchor = settling, mode = TrackingMode.SETTLING))!!
        assertTrue(state.departureUsesWifi)
        assertTrue(state.departureUsesArea)
    }

    @Test
    fun `an offer to start names both`() {
        val state = state(record = null, offerFor = null)!!
        assertTrue(state.departureUsesWifi)
        assertTrue(state.departureUsesArea)
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
    private fun offered(hasSensor: Boolean = true): Boolean =
        offersMotionEnd(deviceHasMotionSensor = { hasSensor })

    @Test
    fun `until I move is offered where the build and the phone allow`() {
        // A choice like `Until I leave`, not a switch (maintainer, 2026-09-10):
        // nothing about the snooze — whether it already ends on motion, what
        // it tracks, whether one is running at all — decides the row. The
        // idle screen offers it as a way to start (maintainer, 2026-09-10),
        // so the question is only the build and the phone.
        assertEquals(buildHoldsForegroundService, offered())
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
    fun `a build with no foreground service asks the platform nothing`() {
        // `direct` cannot hold one, so the answer could not change the outcome.
        var asked = 0

        val offered = offersMotionEnd(deviceHasMotionSensor = { asked++; true })

        assertEquals(buildHoldsForegroundService, offered)
        assertEquals(if (buildHoldsForegroundService) 1 else 0, asked)
    }

    @Test
    fun `until I move's availability is answered apart from the time offer`() {
        // Once the backstop comes inside `MIN_CAP` there is no time left to
        // choose and `endChoiceUiState` withholds the whole offer. This answer
        // is about the build and the phone, not the snooze, so it does not
        // move — it is the screen that withholds the row along with the group
        // it now sits in (maintainer, 2026-09-10), and it does so from that
        // gate, not from this one.
        val nearlyOver = snooze(capIn = Duration.ofMinutes(5), endsOnMotion = true)

        assertNull("no time left to choose", state(nearlyOver))
        assertEquals(
            "while the availability answer is unaffected by that gate",
            buildHoldsForegroundService,
            offered(),
        )
    }

    @Test
    fun `nothing running offers a way to start`() {
        // The idle screen's rows (SPEC.md §4.4, maintainer, 2026-09-10): an
        // offer that names no snooze needs no record, and a tap on it arms.
        val offer = state(record = null, offerFor = null)!!

        assertTrue(offer.startsASnooze)
        // `Until I leave` too, as the plain arm (maintainer, 2026-09-11) —
        // wherever this build can track one at all.
        assertEquals(app.snoozemo.presence.PRESENCE_TRACKS_DEPARTURE, offer.tracksDeparture)
        assertEquals("at ${condition.endsAt.epochSecond}", offer.formattedTime)
    }

    @Test
    fun `the offer to start withholds until I leave on a build that cannot track one`() {
        // The sheet's rule, applied to the idle rows: a departure nothing
        // will watch for is not what the row names.
        assertFalse(state(record = null, offerFor = null, buildTracksDeparture = false)!!.tracksDeparture)
        assertTrue(state(record = null, offerFor = null, buildTracksDeparture = true)!!.tracksDeparture)
    }

    @Test
    fun `a snooze arriving under an offer to start withholds it`() {
        // Armed from the tile while the idle rows stood: a tap now would be
        // answered `GONE`, so the rows go until the record read replaces them
        // with the running snooze's own — the same fail-closed shape as the
        // unread record above, in the other direction.
        assertNull(state(record = snooze(), offerFor = null))
    }

    @Test
    fun `an offer to start filters meetings against its own ceiling`() {
        // Bounded by the cap a snooze started now would carry — the offer's
        // ceiling — since there is no record to read one from. Same rules
        // as the running rows: later than the floor, earlier than the cap.
        val ends = listOf(
            now.plus(Duration.ofMinutes(10)),
            now.plus(Duration.ofHours(2)),
            now.plus(Duration.ofHours(1)),
            condition.ceiling.plus(Duration.ofMinutes(1)),
        )

        val meetings = state(record = null, offerFor = null, meetingEnds = ends)!!.meetings

        assertEquals(
            listOf(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2))),
            meetings.map { it.at },
        )
    }

    @Test
    fun `a refinement never reads as an offer to start`() {
        assertFalse(state(snooze())!!.startsASnooze)
    }

    @Test
    fun `the offer carries the identity it was drawn for`() {
        // What a tap is matched against the controller's current offer with
        // (Codex, PR #256): the snooze's own `startedAt`, or null for an offer
        // to start.
        assertEquals(now, state(snooze())!!.offerFor)
        assertNull(state(record = null, offerFor = null)!!.offerFor)
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

    @Test
    fun `a timer left with an exit armed draws the line`() {
        // The partial line reads the record's own `isPartialTimer` rather than
        // a saved flag, so it is right the moment the host reads the record
        // back — a rotation, a process death and a reboot included, since the
        // record survives all three.
        assertTrue(state(snooze(timerOnlyRequested = true))!!.partial)
    }

    @Test
    fun `a snooze the user never narrowed draws no line`() {
        // The other direction: nothing partial about a snooze still running to
        // its ceiling, and a line saying otherwise is principle 2's failure in
        // reverse — reporting a narrowing that never happened.
        assertFalse(state(snooze())!!.partial)
    }
}
