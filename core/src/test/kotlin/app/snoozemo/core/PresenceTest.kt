package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The presence engine: escalation, the duty cycle, and the anti-flap rules
 * (SPEC.md §6.3, §6.7, §6.10).
 *
 * Written as sequences rather than single calls, because every failure this file
 * is about is a *sequence* failure — a router rebooting, a provider emitting
 * junk for ten minutes, an alarm arriving after the reason for it went away.
 * A test that feeds one signal to a fresh state cannot see any of them.
 *
 * Coordinates are fictional: the anchor is (0, 0) and every fix is an offset
 * north of it (`AGENTS.md`, privacy).
 */
class PresenceTest {

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun metersNorth(m: Double): Double = m / 111_320.0

    private fun fix(northM: Double, accuracyM: Float, atSeconds: Long) = Fix(
        lat = metersNorth(northM),
        lon = 0.0,
        accuracyM = accuracyM,
        elapsedRealtimeMs = atSeconds * 1_000L,
    )

    /** An anchor that can answer questions: coordinates well inside the accuracy gate. */
    private val tracked = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = t0,
        ssid = "ExampleWifi",
    )

    /** An anchor with Wi-Fi but nothing location can measure against (SPEC.md §8.4). */
    private val wifiOnly = Anchor(capturedAt = t0, ssid = "ExampleWifi")

    /** Neither signal. Only the duration cap will ever end this one. */
    private val durationOnly = Anchor(capturedAt = t0)

    /** Feeds a sequence and returns every event, nulls included, so silence is assertable. */
    private fun replay(
        anchor: Anchor,
        from: PresenceState = PresenceState(),
        vararg signals: PresenceSignal,
    ): Pair<List<PresenceEvent?>, PresenceState> {
        var state = from
        val events = signals.map { signal ->
            val step = Presence.advance(state, signal, anchor)
            state = step.state
            step.event
        }
        return events to state
    }

    /**
     * Every step's reported health, so a level that moves at the wrong moment is
     * as visible as an event fired at the wrong moment used to be.
     */
    private fun levels(
        anchor: Anchor,
        from: PresenceState = PresenceState(),
        vararg signals: PresenceSignal,
    ): List<DegradationCause?> {
        var state = from
        return signals.map { signal ->
            state = Presence.advance(state, signal, anchor).state
            state.degradation
        }
    }

    private fun associated(atSeconds: Long) =
        PresenceSignal.AnchorWifiAssociated(atSeconds * 1_000L)

    private fun wifiLost(atSeconds: Long) = PresenceSignal.AnchorWifiLost(atSeconds * 1_000L)

    private fun motion(atSeconds: Long) = PresenceSignal.SignificantMotion(atSeconds * 1_000L)

    private fun geofenceExit(atSeconds: Long) = PresenceSignal.GeofenceExit(atSeconds * 1_000L)

    private fun noFix(atSeconds: Long) = PresenceSignal.FixUnavailable(atSeconds * 1_000L)

    private fun graceElapsed(atSeconds: Long) = PresenceSignal.GraceElapsed(atSeconds * 1_000L)

    private fun presentUnconfirmed(atSeconds: Long) =
        PresenceSignal.AnchorWifiPresentUnconfirmed(atSeconds * 1_000L)

    /**
     * The state a monitor rebuilds when the grace alarm restores a Wi-Fi-only
     * snooze mid-outage: the seeded deadline is loaded, the suppressor starts
     * off (`PresenceFeed`), and the seed's own evidence is the arm moment.
     */
    private fun restoredWithGrace(deadlineSeconds: Long) = PresenceState(
        atAnchorWifi = false,
        latestEvidenceMs = 0L,
        graceDeadlineMs = deadlineSeconds * 1_000L,
    )

    private fun arrived(northM: Double, accuracyM: Float, atSeconds: Long) =
        PresenceSignal.FixArrived(fix(northM, accuracyM, atSeconds))

    /** A reading that says "somewhere in this town" and nothing more. */
    private fun vague(atSeconds: Long) = arrived(northM = 400.0, accuracyM = 500f, atSeconds)

    /** A reading that puts the whole uncertainty circle inside the radius. */
    private fun atHome(atSeconds: Long) = arrived(northM = 10.0, accuracyM = 20f, atSeconds)

    /** A reading well outside the radius, accuracy already allowed for. */
    private fun outside(atSeconds: Long) = arrived(northM = 400.0, accuracyM = 20f, atSeconds)

    @Test
    fun `sitting on the anchor's wifi does no location work at all`() {
        // §6.7's entire battery argument in one assertion: a phone on a desk
        // inside its anchor's network should not be asking for fixes.
        val (_, state) = replay(tracked, signals = arrayOf(associated(0)))

        assertEquals(LocationDuty.NONE, Presence.duty(state, tracked))
    }

    @Test
    fun `losing the anchor's wifi escalates but does not end the snooze`() {
        val (events, state) = replay(tracked, signals = arrayOf(associated(0), wifiLost(60)))

        assertEquals(listOf(null, PresenceEvent.ProbablyLeft), events)
        assertEquals(PresencePhase.CHECKING, state.phase)
        // Wi-Fi is a suppressor, never a trigger (D4) — location has to answer now.
        assertEquals(LocationDuty.ACTIVE, Presence.duty(state, tracked))
    }

    @Test
    fun `a router reboot does not produce a storm of events`() {
        // The flap this engine exists to absorb: Wi-Fi drops and returns twice
        // in two minutes while the phone never moves. Four transitions, four
        // events, and back to no location work — not one event per callback.
        val (events, state) = replay(
            tracked,
            signals = arrayOf(
                associated(0),
                wifiLost(30),
                wifiLost(31),
                wifiLost(32),
                associated(45),
                associated(46),
                wifiLost(90),
                associated(100),
            ),
        )

        assertEquals(
            listOf(
                null,
                PresenceEvent.ProbablyLeft,
                null,
                null,
                PresenceEvent.StillHere,
                null,
                PresenceEvent.ProbablyLeft,
                PresenceEvent.StillHere,
            ),
            events,
        )
        assertEquals(LocationDuty.NONE, Presence.duty(state, tracked))
    }

    @Test
    fun `moving about the house is not leaving it`() {
        // Significant motion while associated with the anchor's network is
        // someone standing up. D4 already has better evidence than the
        // accelerometer, so this must not start a location request.
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(0), motion(30), motion(600)),
        )

        assertEquals(listOf(null, null, null), events)
        assertEquals(PresencePhase.RESTING, state.phase)
        assertEquals(LocationDuty.NONE, Presence.duty(state, tracked))
    }

    @Test
    fun `motion escalates once the anchor's wifi is gone`() {
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(motion(30), motion(40)),
        )

        assertEquals(listOf(PresenceEvent.ProbablyLeft, null), events)
        assertEquals(PresencePhase.CHECKING, state.phase)
    }

    @Test
    fun `a geofence exit is checked even while the anchor's wifi is associated`() {
        // The two subsystems disagreeing is exactly when a fix is worth taking.
        // The cost of being wrong here is one location request; the cost of
        // trusting Wi-Fi blindly is a departure the app never notices.
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(0), geofenceExit(120)),
        )

        assertEquals(listOf(null, PresenceEvent.ProbablyLeft), events)
        assertEquals(PresencePhase.CHECKING, state.phase)
        // And it actually asks for the fix. Escalating without this is a phase
        // change and nothing else — the disagreement noted and never settled
        // (Codex, PR #31).
        assertEquals(LocationDuty.ACTIVE, Presence.duty(state, tracked))
    }

    @Test
    fun `a repeated wifi callback does not call off the check it never settled`() {
        // Network callbacks repeat — a capabilities change, a re-association,
        // the same network reported twice. Treating each as fresh evidence would
        // let a repeat cancel the fix the geofence disagreement asked for, which
        // is exactly the stale-association case the escalation exists for
        // (Codex, PR #31).
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(0), geofenceExit(120), associated(130), associated(140)),
        )

        assertEquals(listOf(null, PresenceEvent.ProbablyLeft, null, null), events)
        assertEquals(PresencePhase.CHECKING, state.phase)
        assertEquals(LocationDuty.ACTIVE, Presence.duty(state, tracked))

        // Only the fix settles it.
        val settled = Presence.advance(state, atHome(150), tracked)
        assertEquals(PresenceEvent.StillHere, settled.event)
        assertEquals(LocationDuty.NONE, Presence.duty(settled.state, tracked))
    }

    @Test
    fun `a fix taken before the check began cannot settle it`() {
        // Android hands out cached and queued readings freely — a last-known
        // location, an update that sat in a queue while the phone slept. One of
        // them placing the user at the anchor answers an older question, and
        // letting it cancel the check would leave a real departure waiting for
        // the backstop or the cap (Codex, PR #31).
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(geofenceExit(300), atHome(120)),
        )

        assertEquals(listOf(PresenceEvent.ProbablyLeft, null), events)
        assertEquals(PresencePhase.CHECKING, state.phase)
        assertEquals(LocationDuty.ACTIVE, Presence.duty(state, tracked))

        // A reading taken after the exit is the one that counts, either way.
        val fresh = Presence.advance(state, atHome(330), tracked)
        assertEquals(PresenceEvent.StillHere, fresh.event)
        assertEquals(PresencePhase.RESTING, fresh.state.phase)
    }

    @Test
    fun `an old reading cannot end a snooze that nothing was checking`() {
        // The dangerous half, because no check is running to reject it: a
        // batched delivery hands over a reading from before the newest one, and
        // it is far enough out to end the snooze on its own (Codex, PR #31).
        // Evidence only moves forward.
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(atHome(600), arrived(northM = 5_000.0, accuracyM = 20f, atSeconds = 60)),
        )

        assertEquals(listOf(null, null), events)
        assertEquals(PresencePhase.RESTING, state.phase)
        assertTrue(!state.resolved)
    }

    @Test
    fun `a reading from before the snooze started is not believed`() {
        // A last-known location cached before the tile was tapped could be from
        // anywhere the phone has been, so the monitor seeds the engine with the
        // moment the snooze started and nothing older counts.
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false, latestEvidenceMs = 500_000L),
            signals = arrayOf(arrived(northM = 5_000.0, accuracyM = 20f, atSeconds = 60)),
        )

        assertEquals(listOf(null), events)
        assertTrue(!state.resolved)
    }

    @Test
    fun `a geofence exit from before the phone was last seen here is old news`() {
        // Geofence delivery is famously late (SPEC.md §6.10), so an exit can
        // arrive long after something newer placed the phone at the anchor.
        // Escalating on it starts active location work for a question already
        // answered (Codex, PR #31).
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(300), geofenceExit(120)),
        )

        assertEquals(listOf(null, null), events)
        assertEquals(PresencePhase.RESTING, state.phase)
        assertEquals(LocationDuty.NONE, Presence.duty(state, tracked))

        // An exit after that association is current, and does escalate.
        val current = Presence.advance(state, geofenceExit(400), tracked)
        assertEquals(PresenceEvent.ProbablyLeft, current.event)
        assertEquals(LocationDuty.ACTIVE, Presence.duty(current.state, tracked))
    }

    @Test
    fun `a wifi loss from before the phone rejoined is old news`() {
        // A queued loss delivered after a newer association: something later
        // said the phone was here, so the loss cannot escalate — and on a
        // Wi-Fi-only anchor it must not arm a grace period that would end the
        // snooze five minutes on (Codex, PR #31).
        val (events, state) = replay(
            wifiOnly,
            signals = arrayOf(associated(120), wifiLost(90)),
        )

        assertEquals(listOf(null, null), events)
        assertEquals(PresencePhase.RESTING, state.phase)
        assertTrue("the anchor's network is still associated", state.atAnchorWifi)
        assertNull(state.graceDeadlineMs)
    }

    @Test
    fun `the same timeout delivered three times is one failure`() {
        // The duplicate-counting bug the fix path had, in the path that reports
        // failures: three deliveries of one timeout would report degraded
        // tracking and arm the grace period off a single failed request.
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(0), wifiLost(60), noFix(90), noFix(90), noFix(90)),
        )

        assertEquals(listOf(null, PresenceEvent.ProbablyLeft, null, null, null), events)
        assertEquals(1, state.uselessObservations)
        assertNull(state.degradation)
        assertNull("no grace period may be armed by one failed request", state.graceDeadlineMs)
    }

    @Test
    fun `an association from before the check cannot call it off`() {
        // The last observation to skip the staleness rule, and the worst one to
        // skip it: a queued association delivered after the geofence exit that
        // outdates it would cancel a real departure check with evidence from
        // before it started, leaving the phone silent to the cap (Codex, PR #31).
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(geofenceExit(300), associated(120)),
        )

        assertEquals(listOf(PresenceEvent.ProbablyLeft, null), events)
        assertEquals(PresencePhase.CHECKING, state.phase)
        assertEquals(LocationDuty.ACTIVE, Presence.duty(state, tracked))

        // An association from *after* the exit is the real thing, and settles it.
        val current = Presence.advance(state, associated(330), tracked)
        assertEquals(PresenceEvent.StillHere, current.event)
        assertEquals(PresencePhase.RESTING, current.state.phase)
    }

    @Test
    fun `the same reading delivered three times is one observation`() {
        // A cached reading handed over repeatedly is one thing location said.
        // Counted three times it reaches the degradation threshold and arms the
        // grace period — a snooze shortened by a delivery quirk (Codex, PR #31).
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(0), wifiLost(60), vague(90), vague(90), vague(90)),
        )

        assertEquals(listOf(null, PresenceEvent.ProbablyLeft, null, null, null), events)
        assertEquals(1, state.uselessObservations)
        assertNull(state.degradation)
        assertNull("no grace period may be armed by one reading", state.graceDeadlineMs)
    }

    @Test
    fun `a timeout from a request that was already answered does not count`() {
        // The failure to get a fix goes stale exactly like a fix does: a request
        // whose ceiling expires after Wi-Fi came back reports on a question
        // already settled. Counting it would leave the snooze one observation
        // from being called degraded — and, after a later Wi-Fi loss, arm the
        // grace period a cycle early (Codex, PR #31).
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(0), wifiLost(60), associated(120), noFix(90)),
        )

        assertEquals(
            listOf(
                null,
                PresenceEvent.ProbablyLeft,
                PresenceEvent.StillHere,
                null,
            ),
            events,
        )
        assertEquals(0, state.uselessObservations)
        assertNull(state.degradation)
    }

    @Test
    fun `a reading in flight when wifi returns does not override it`() {
        // Wi-Fi drops, a fix is requested, and the phone rejoins the anchor's
        // network before that reading lands. The reading is older than the
        // recovery, so it answers an older question — and it happens to be a
        // qualifying one, which would otherwise start ending a snooze that the
        // stronger signal had just said was still here (Codex, PR #31).
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(0), wifiLost(60), associated(120), outside(90)),
        )

        assertEquals(
            listOf(
                null,
                PresenceEvent.ProbablyLeft,
                PresenceEvent.StillHere,
                null,
            ),
            events,
        )
        assertEquals(PresencePhase.RESTING, state.phase)
        assertEquals(LocationDuty.NONE, Presence.duty(state, tracked))
    }

    @Test
    fun `a stale fix is not counted as a failed observation`() {
        // Ignored outright rather than counted: it is not location failing, it
        // is an answer to a different question. Counting it would report
        // degraded tracking for a provider that is working.
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(geofenceExit(300), vague(10), vague(20), vague(30)),
        )

        assertEquals(listOf(PresenceEvent.ProbablyLeft, null, null, null), events)
        assertEquals(0, state.uselessObservations)
        assertNull(state.degradation)
    }

    @Test
    fun `the contradicted geofence exit stops asking once a fix settles it`() {
        // The other half: the check is bounded by the answer, so a spurious exit
        // costs one fix rather than a permanently awake location request.
        val (_, state) = replay(
            tracked,
            signals = arrayOf(associated(0), geofenceExit(120), atHome(150)),
        )

        assertEquals(PresencePhase.RESTING, state.phase)
        assertEquals(LocationDuty.NONE, Presence.duty(state, tracked))
    }

    @Test
    fun `a fix inside the radius settles a geofence exit`() {
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(geofenceExit(120), atHome(150)),
        )

        assertEquals(
            listOf(
                PresenceEvent.ProbablyLeft,
                PresenceEvent.StillHere,
            ),
            events,
        )
        assertEquals(PresencePhase.RESTING, state.phase)
        assertEquals(LocationDuty.SANITY, Presence.duty(state, tracked))
    }

    @Test
    fun `readings that locate nobody do not de-escalate`() {
        // The rule the whole file is built around. A 400 m/±500 m reading is not
        // evidence of presence, so it must not send the duty cycle back to sleep
        // precisely when location has stopped working.
        val (_, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(geofenceExit(0), vague(60), vague(120)),
        )

        assertEquals(PresencePhase.CHECKING, state.phase)
        assertEquals(LocationDuty.ACTIVE, Presence.duty(state, tracked))
    }

    @Test
    fun `tracking health goes down on the third failure and stays down`() {
        // The level replaces the one-shot report (PR #34). "Reported once" is now
        // the controller's business — it acts on changes — so what the engine
        // owes is a level that moves at the right moment and then holds still.
        val signals = arrayOf(vague(0), vague(60), vague(120), vague(180), vague(240))
        val from = PresenceState(atAnchorWifi = false)

        val (events, state) = replay(tracked, from = from, signals = signals)

        assertEquals("health is a level, not news", listOf(null, null, null, null, null), events)
        assertEquals(
            listOf(
                null,
                null,
                DegradationCause.FIXES_TOO_VAGUE,
                DegradationCause.FIXES_TOO_VAGUE,
                DegradationCause.FIXES_TOO_VAGUE,
            ),
            levels(tracked, from = from, signals = signals),
        )
        assertEquals(DegradationCause.FIXES_TOO_VAGUE, state.degradation)
    }

    @Test
    fun `fixes that never arrive are reported as a different failure`() {
        // Nothing coming back and something useless coming back look identical
        // to the app and completely different to the user, so they must not
        // share a cause (SPEC.md §8.1).
        assertEquals(
            listOf(null, null, DegradationCause.NO_LOCATION_FIX),
            levels(
                tracked,
                from = PresenceState(atAnchorWifi = false),
                signals = arrayOf(noFix(0), noFix(60), noFix(120)),
            ),
        )
    }

    @Test
    fun `a run of bad fixes is forgiven once a real one arrives`() {
        val signals = arrayOf(vague(0), vague(60), vague(120), atHome(180))
        val from = PresenceState(atAnchorWifi = false)

        val (events, state) = replay(tracked, from = from, signals = signals)

        // No event anywhere: the phase never moved (a useless reading is not
        // evidence of presence, so it does not open a check), and health is a
        // level rather than news.
        assertEquals(listOf(null, null, null, null), events)
        assertEquals(
            listOf(null, null, DegradationCause.FIXES_TOO_VAGUE, null),
            levels(tracked, from = from, signals = signals),
        )
        assertEquals(0, state.uselessObservations)
        assertNull(state.degradation)
    }

    @Test
    fun `a wifi-only snooze does not report location as broken`() {
        // The anchor never had a usable fix, so every reading is inconclusive by
        // construction. Reporting that as degradation would tell the user
        // something is wrong, repeatedly, about a snooze whose notification
        // already says it is running on Wi-Fi (SPEC.md §8.4).
        val (events, state) = replay(
            wifiOnly,
            signals = arrayOf(associated(0), vague(60), vague(120), vague(180), vague(240)),
        )

        assertEquals(listOf(null, null, null, null, null), events)
        assertNull(state.degradation)
        assertEquals(LocationDuty.NONE, Presence.duty(state, wifiOnly))
    }

    @Test
    fun `a duration-only snooze asks nothing of location`() {
        val (events, state) = replay(durationOnly, signals = arrayOf(motion(0), vague(60)))

        assertEquals(listOf(PresenceEvent.ProbablyLeft, null), events)
        assertEquals(LocationDuty.NONE, Presence.duty(state, durationOnly))
    }

    @Test
    fun `two qualifying fixes thirty seconds apart end the snooze`() {
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(wifiLost(0), outside(30), outside(70)),
        )

        assertEquals(
            listOf(PresenceEvent.ProbablyLeft, null, PresenceEvent.Departed),
            events,
        )
        assertTrue(state.resolved)
    }

    @Test
    fun `nothing is reported after the snooze has ended`() {
        // `endSnooze` is required to be idempotent (SPEC.md §7); the cheapest way
        // to hold that is for the engine to stop producing reasons to call it.
        val (events, state) = replay(
            tracked,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(
                outside(30),
                outside(70),
                associated(80),
                outside(120),
                graceElapsed(600),
            ),
        )

        assertEquals(
            listOf(PresenceEvent.ProbablyLeft, PresenceEvent.Departed, null, null, null),
            events,
        )
        assertTrue(state.resolved)
        assertEquals(LocationDuty.NONE, Presence.duty(state, tracked))
    }

    @Test
    fun `a wifi-only snooze ends when wifi stays away`() {
        // §6.6's grace period: with no location to confirm against, Wi-Fi that
        // does not come back resolves by ending the snooze (D7, fail open).
        val (events, state) = replay(
            wifiOnly,
            signals = arrayOf(associated(0), wifiLost(60)),
        )

        assertEquals(
            60_000L + Presence.WIFI_GRACE.toMillis(),
            state.graceDeadlineMs,
        )

        val expiry = Presence.advance(state, graceElapsed(360), wifiOnly)
        assertEquals(PresenceEvent.Departed, expiry.event)
    }

    @Test
    fun `wifi returning inside the grace period calls it off`() {
        val (_, state) = replay(
            wifiOnly,
            signals = arrayOf(associated(0), wifiLost(60), associated(120)),
        )

        assertNull("the deadline must not outlive its reason", state.graceDeadlineMs)
        // The alarm the caller armed still fires; it must do nothing.
        val stale = Presence.advance(state, graceElapsed(360), wifiOnly)
        assertNull(stale.event)
    }

    @Test
    fun `losing wifi does not start a grace period while location still works`() {
        // The grace period is for unverifiable states only. A tracked anchor can
        // answer the question properly, and ending on a timer instead would
        // discard the evidence the app went to the trouble of collecting.
        val (_, state) = replay(tracked, signals = arrayOf(associated(0), wifiLost(60)))

        assertNull(state.graceDeadlineMs)
    }

    @Test
    fun `a snooze that loses both signals ends rather than running to the cap`() {
        // The ordinary route into the worst case: armed healthy, walked out of a
        // building, Wi-Fi gone and every fix since too vague to place anyone.
        // Nothing here can confirm a departure, so the grace period starts when
        // location gives up rather than never (SPEC.md §6.6, D7).
        val (events, state) = replay(
            tracked,
            signals = arrayOf(associated(0), wifiLost(60), vague(90), vague(150), vague(210)),
        )

        assertEquals(
            listOf(
                null,
                PresenceEvent.ProbablyLeft,
                null,
                null,
                null,
            ),
            events,
        )
        assertEquals(DegradationCause.FIXES_TOO_VAGUE, state.degradation)
        assertNotNull("an unverifiable snooze must have a deadline", state.graceDeadlineMs)
        assertEquals(210_000L + Presence.WIFI_GRACE.toMillis(), state.graceDeadlineMs)

        val expiry = Presence.advance(state, graceElapsed(600), tracked)
        assertEquals(PresenceEvent.Departed, expiry.event)
    }

    @Test
    fun `a returning user beats the grace alarm that restored the snooze`() {
        // The race this whole change exists for (SPEC.md §6.6): the grace alarm
        // restores a Wi-Fi-only snooze mid-outage and replays a due
        // `GraceElapsed` before the watch's async callback can report that the
        // user is back on the anchor's network. The seed's `PresentUnconfirmed`
        // makes that firing defer for the confirmation window instead of
        // ending the snooze, and the association that follows calls it off.
        val (events, state) = replay(
            wifiOnly,
            from = restoredWithGrace(300),
            signals = arrayOf(presentUnconfirmed(310), graceElapsed(300)),
        )

        assertEquals("the due deadline must defer, not end the snooze", listOf(null, null), events)
        assertEquals(
            "deferred by exactly the confirmation window",
            300_000L + Presence.WIFI_CONFIRM.toMillis(),
            state.graceDeadlineMs,
        )

        val back = Presence.advance(state, associated(315), wifiOnly)
        assertNull("the returning user clears the deadline", back.state.graceDeadlineMs)

        val stale = Presence.advance(back.state, graceElapsed(330), wifiOnly)
        assertNull("nothing left to fire on", stale.event)
    }

    @Test
    fun `the confirmation window extends from the firing, not the original deadline`() {
        // A slow restore (Codex, PR #106): the grace alarm fired at the
        // deadline but a dead process took ~40 s to restore and replay the
        // held firing, so the monitor stamps it with the *handling* time
        // (340 s here) rather than the stale fire time (300 s). The window
        // must run a full `WIFI_CONFIRM` from when it is actually handled, or
        // the async callback gets no room to confirm a return. A deadline
        // extended off the stale 300 s would already be in the past.
        val (_, state) = replay(
            wifiOnly,
            from = restoredWithGrace(300),
            signals = arrayOf(presentUnconfirmed(340), graceElapsed(340)),
        )

        assertEquals(
            "a full window from the handling time, not the stale deadline",
            340_000L + Presence.WIFI_CONFIRM.toMillis(),
            state.graceDeadlineMs,
        )

        // And the callback lands inside that real window.
        val back = Presence.advance(state, associated(360), wifiOnly)
        assertNull("the return within the fresh window is honored", back.state.graceDeadlineMs)
    }

    @Test
    fun `a user who really left still ends after the deferred window`() {
        // Fail-open is preserved (D7): the defer buys one confirmation window,
        // and a callback naming a *different* network — the user left and
        // joined some other Wi-Fi — lets the window's own firing end the
        // snooze rather than deferring again.
        val (_, deferred) = replay(
            wifiOnly,
            from = restoredWithGrace(300),
            signals = arrayOf(presentUnconfirmed(310), graceElapsed(300)),
        )

        val elsewhere = Presence.advance(deferred, wifiLost(315), wifiOnly)
        assertEquals(
            "a different network does not re-arm a fresh grace",
            300_000L + Presence.WIFI_CONFIRM.toMillis(),
            elsewhere.state.graceDeadlineMs,
        )

        val end = Presence.advance(elsewhere.state, graceElapsed(330), wifiOnly)
        assertEquals(PresenceEvent.Departed, end.event)
    }

    @Test
    fun `the grace deadline defers at most once when no callback comes`() {
        // The bound on the defer: if the async callback never arrives at all,
        // the confirmation flag is spent by the first firing, so the window's
        // own firing ends the snooze. One extra window, never a stall.
        val (_, deferred) = replay(
            wifiOnly,
            from = restoredWithGrace(300),
            signals = arrayOf(presentUnconfirmed(310), graceElapsed(300)),
        )

        val end = Presence.advance(deferred, graceElapsed(330), wifiOnly)
        assertEquals(PresenceEvent.Departed, end.event)
    }

    @Test
    fun `a deferral already spent is not granted again after a restart`() {
        // The durability bound (Codex, PR #106): a process death inside the
        // confirmation window restores the *extended* deadline and the spent
        // flag, and the new seed re-sets `awaiting` — but the deadline must
        // resolve now, not defer a second time. Without the persisted flag,
        // repeated reclamation inside the window would extend the deadline
        // over and over and hold DND to the cap.
        val restored = PresenceState(
            atAnchorWifi = false,
            latestEvidenceMs = 0L,
            graceDeadlineMs = 330_000L,
            confirmationDeferralUsed = true,
        )
        val (_, state) = replay(
            wifiOnly,
            from = restored,
            signals = arrayOf(presentUnconfirmed(340)),
        )

        val end = Presence.advance(state, graceElapsed(330), wifiOnly)
        assertEquals(PresenceEvent.Departed, end.event)
    }

    @Test
    fun `a new outage after recovery earns a fresh deferral`() {
        // The bound is per-episode, not once forever: it resets when the
        // deadline clears, so a user who returns and later leaves again gets a
        // fresh confirmation window rather than an instant end.
        val (_, deferred) = replay(
            wifiOnly,
            from = restoredWithGrace(300),
            signals = arrayOf(presentUnconfirmed(310), graceElapsed(300)),
        )

        val back = Presence.advance(deferred, associated(315), wifiOnly)
        assertNull("recovery clears the deadline", back.state.graceDeadlineMs)
        assertFalse("and resets the deferral bound with it", back.state.confirmationDeferralUsed)

        // A later outage arms a new grace episode, and its first due firing
        // may defer once again.
        val lostAgain = Presence.advance(back.state, wifiLost(400), wifiOnly)
        val deadline2 = lostAgain.state.graceDeadlineMs
        assertNotNull("a fresh outage arms a fresh deadline", deadline2)

        val unconfirmed = Presence.advance(lostAgain.state, presentUnconfirmed(410), wifiOnly)
        val elapsed = Presence.advance(unconfirmed.state, PresenceSignal.GraceElapsed(deadline2!!), wifiOnly)
        assertNull("the fresh episode defers again rather than ending", elapsed.event)
    }

    @Test
    fun `a restore that finds no wifi ends without deferring`() {
        // The genuine-departure restore: the seed found no Wi-Fi (the user
        // left), so it reports a loss, not `PresentUnconfirmed`. A due deadline
        // must resolve immediately — nothing to wait for.
        val (_, state) = replay(
            wifiOnly,
            from = restoredWithGrace(300),
            signals = arrayOf(wifiLost(310)),
        )

        val end = Presence.advance(state, graceElapsed(300), wifiOnly)
        assertEquals(PresenceEvent.Departed, end.event)
    }

    @Test
    fun `an unconfirmed seed does nothing off the grace-restore path`() {
        // On an ordinary arm there is no deadline for the flag to touch: it
        // arms nothing, changes no phase, and leaves the association callback
        // that follows to behave exactly as it always does.
        val (events, state) = replay(
            wifiOnly,
            from = PresenceState(atAnchorWifi = false),
            signals = arrayOf(presentUnconfirmed(10), associated(20)),
        )

        assertEquals(listOf(null, null), events)
        assertNull("nothing armed a grace period", state.graceDeadlineMs)
        assertEquals(PresencePhase.RESTING, state.phase)
        assertEquals(LocationDuty.NONE, Presence.duty(state, wifiOnly))
    }

    @Test
    fun `a fix that finds the user calls off the grace period`() {
        val (_, state) = replay(
            tracked,
            signals = arrayOf(associated(0), wifiLost(60), vague(90), vague(150), vague(210)),
        )
        assertNotNull(state.graceDeadlineMs)

        val found = Presence.advance(state, atHome(240), tracked)

        assertEquals(PresenceEvent.StillHere, found.event)
        assertNull("location answered, so the timer has nothing left to do", found.state.graceDeadlineMs)
    }

    @Test
    fun `a qualifying fix restores health without settling the check`() {
        // A reading good enough to measure with proves location works even while
        // it says the user may be leaving. As an event that combination had
        // nowhere to go (Codex, PR #33, four rounds); as a level it needs no
        // announcement at all.
        val (_, degraded) = replay(
            tracked,
            signals = arrayOf(associated(0), wifiLost(60), vague(90), vague(150), vague(210)),
        )
        assertEquals(DegradationCause.FIXES_TOO_VAGUE, degraded.degradation)
        assertEquals(PresencePhase.CHECKING, degraded.phase)

        val recovered = Presence.advance(degraded, outside(240), tracked)

        assertNull("location answered", recovered.state.degradation)
        assertEquals("the check it arrived during carries on", PresencePhase.CHECKING, recovered.state.phase)
    }

    @Test
    fun `health survives every ordering that used to lose it`() {
        // The four sequences Codex found against the event-shaped version (PR
        // #33), replayed against the level. Each one asks the same question —
        // is location working right now — and the level answers it the same way
        // whatever order the signals arrive in, which is the entire argument for
        // the rewrite.
        val degraded = arrayOf(vague(90), vague(150), vague(210))

        // 1. Escalation and recovery on the same signal: no event can carry both,
        //    and the level does not need to be carried.
        assertEquals(
            listOf(DegradationCause.FIXES_TOO_VAGUE, null, null),
            levels(tracked, signals = arrayOf(*degraded, outside(300), associated(360)).drop(2).toTypedArray(),
                from = replay(tracked, signals = arrayOf(vague(90), vague(150))).second),
        )

        // 2. Rejoining the anchor's network, which suppresses location entirely
        //    and used to be the event with no second chance.
        val (_, afterFix) = replay(
            tracked,
            from = replay(tracked, signals = degraded).second,
            signals = arrayOf(outside(300)),
        )
        val rejoined = Presence.advance(afterFix, associated(360), tracked)
        assertNull("still healthy after the association", rejoined.state.degradation)
        assertEquals(LocationDuty.NONE, Presence.duty(rejoined.state, tracked))

        // 3. A second qualifying fix inside the confirmation window, which used
        //    to overwrite the debt the first one left.
        val second = Presence.advance(afterFix, outside(320), tracked)
        assertNull(second.state.degradation)

        // 4. And it goes back down when location breaks again.
        val (_, broken) = replay(
            tracked,
            from = second.state,
            signals = arrayOf(vague(380), vague(440), vague(500)),
        )
        assertEquals(DegradationCause.FIXES_TOO_VAGUE, broken.degradation)
    }

    @Test
    fun `a stale usable fix still restores health`() {
        // The two halves of a fix go stale at different rates (Codex, PR #33).
        // A queued reading delivered just after the phone rejoins the anchor's
        // network says nothing about where the user is — but it is the only
        // proof location recovered, and after the association nothing else will
        // ever arrive to say so.
        val (_, degraded) = replay(tracked, signals = arrayOf(vague(90), vague(150), vague(210)))
        val rejoined = Presence.advance(degraded, associated(300), tracked)
        assertEquals(
            "Wi-Fi says nothing about location",
            DegradationCause.FIXES_TOO_VAGUE,
            rejoined.state.degradation,
        )
        assertEquals(LocationDuty.NONE, Presence.duty(rejoined.state, tracked))

        // Taken after the failures, before the association, delivered after it.
        val late = Presence.advance(rejoined.state, atHome(250), tracked)

        assertNull(late.state.degradation)
        assertNull("the presence half is still discarded", late.event)
        assertEquals("and it must not move the evidence bar", rejoined.state.latestEvidenceMs, late.state.latestEvidenceMs)
    }

    @Test
    fun `a usable fix captured before the failures proves nothing`() {
        // The overstating direction, and the one this whole change exists to
        // prevent (Codex, PR #33). A cached reading from before the trouble
        // started, handed over long afterwards, would otherwise clear the
        // degradation on evidence older than the problem — hiding a broken
        // tracker rather than reporting it.
        val (_, degraded) = replay(tracked, signals = arrayOf(vague(90), vague(150), vague(210)))
        val rejoined = Presence.advance(degraded, associated(300), tracked)

        val cached = Presence.advance(rejoined.state, atHome(60), tracked)

        assertEquals(
            "older than the failures it claims to be over",
            DegradationCause.FIXES_TOO_VAGUE,
            cached.state.degradation,
        )
    }

    @Test
    fun `a stale vague fix proves nothing either way`() {
        val (_, degraded) = replay(tracked, signals = arrayOf(vague(90), vague(150), vague(210)))
        val rejoined = Presence.advance(degraded, associated(300), tracked)

        val late = Presence.advance(rejoined.state, vague(250), tracked)

        assertNull("it could not have placed anyone", late.event)
        assertEquals(DegradationCause.FIXES_TOO_VAGUE, late.state.degradation)
    }

    @Test
    fun `location recovering after wifi came back is still reported`() {
        // Wi-Fi returning is not evidence about location, so it must not erase
        // the memory that location is broken (Codex, PR #33). If it did, the fix
        // that arrives afterward would find nothing outstanding to resolve, emit
        // nothing, and leave the controller claiming Wi-Fi-only tracking for a
        // snooze whose location came back.
        val (_, state) = replay(
            tracked,
            signals = arrayOf(
                associated(0),
                wifiLost(60),
                vague(90),
                vague(150),
                vague(210),
                associated(240),
            ),
        )
        assertEquals(
            "the association resolves the check, not the degradation",
            DegradationCause.FIXES_TOO_VAGUE,
            state.degradation,
        )

        val recovered = Presence.advance(state, atHome(300), tracked)

        assertNull("the association already settled the check", recovered.event)
        assertNull("and this is what says location is back", recovered.state.degradation)
    }

    @Test
    fun `a qualifying fix calls off the grace period too`() {
        // Location recovering with a reading that qualifies is still location
        // recovering, so the timer that stands in for a confirmation the app
        // could not run must not fire while that confirmation is running
        // (Codex, PR #31).
        val (_, state) = replay(
            tracked,
            signals = arrayOf(associated(0), wifiLost(60), vague(90), vague(150), vague(210)),
        )
        assertNotNull(state.graceDeadlineMs)

        val qualifying = Presence.advance(state, outside(240), tracked)

        assertNull(qualifying.state.graceDeadlineMs)
        // And the ordinary two-fix path is what ends it, 30 s later.
        val confirmed = Presence.advance(qualifying.state, outside(280), tracked)
        assertEquals(PresenceEvent.Departed, confirmed.event)
    }

    @Test
    fun `failures that alternate do not flap the level`() {
        // Vague fixes and no fixes at all, interleaved. Moving the level on a
        // changed cause would have the controller rewrite the record and the
        // notification per sample — the flapping this engine exists to prevent,
        // produced by its own reporting. Both causes lower tracking the same way
        // and say the same thing to the user.
        assertEquals(
            listOf(
                null,
                null,
                DegradationCause.FIXES_TOO_VAGUE,
                DegradationCause.FIXES_TOO_VAGUE,
                DegradationCause.FIXES_TOO_VAGUE,
                DegradationCause.FIXES_TOO_VAGUE,
            ),
            levels(
                tracked,
                from = PresenceState(atAnchorWifi = false),
                signals = arrayOf(vague(0), noFix(60), vague(120), noFix(180), vague(240), noFix(300)),
            ),
        )
    }

    @Test
    fun `a no-fix report can never make a same-millisecond wifi loss look stale`() {
        // The monitor delivers "no usable fix" and constructs the Wi-Fi
        // watch in the same call frame on a Wi-Fi-only anchor's cold start,
        // so both can compute the same `SystemClock.elapsedRealtime()`
        // millisecond (raised as a same-millisecond staleness race by Codex
        // on PR #77: whichever the engine sees second could be stamped no
        // later than the first's `latestEvidenceMs` and dropped as old
        // news). It cannot actually happen here: `useless` — what a no-fix
        // report reaches — is a declared no-op for an anchor with no usable
        // fix (SPEC.md §8.4; the anchor this scenario is about, by
        // definition), so it never touches `latestEvidenceMs` and can never
        // poison anything that follows it, in either order.
        val (_, lossFirst) = replay(wifiOnly, signals = arrayOf(wifiLost(0), noFix(0)))
        assertEquals(
            Presence.WIFI_GRACE.toMillis(),
            lossFirst.graceDeadlineMs,
        )

        val (_, noFixFirst) = replay(wifiOnly, signals = arrayOf(noFix(0), wifiLost(0)))
        assertEquals(
            "order must not matter: a no-op report cannot poison the loss that follows it",
            Presence.WIFI_GRACE.toMillis(),
            noFixFirst.graceDeadlineMs,
        )
    }

    @Test
    fun `an anchor with no wifi never gets a grace period`() {
        // There was no network to lose, so a deadline armed off one would end a
        // snooze for a signal it never depended on.
        val noWifi = Anchor(lat = 0.0, lon = 0.0, fixAccuracyM = 20f, capturedAt = t0)
        val (_, state) = replay(noWifi, signals = arrayOf(wifiLost(60), vague(90), vague(150), vague(210)))

        assertNull(state.graceDeadlineMs)
    }

    @Test
    fun `the duty cycle follows the phase rather than the calendar`() {
        val resting = PresenceState(atAnchorWifi = false)
        assertEquals(LocationDuty.SANITY, Presence.duty(resting, tracked))

        val checking = Presence.advance(resting, motion(0), tracked).state
        assertEquals(LocationDuty.ACTIVE, Presence.duty(checking, tracked))

        val backHome = Presence.advance(checking, atHome(60), tracked).state
        assertEquals(LocationDuty.SANITY, Presence.duty(backHome, tracked))
    }
}
