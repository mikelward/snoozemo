package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoozeControllerTest {

    private val start: Instant = Instant.parse("2026-08-11T09:00:00Z")

    /** A plausible device uptime when these tests begin. */
    private var uptimeMillis: Long = Duration.ofHours(30).toMillis()

    private var wall: Instant = start

    /**
     * The test's clock. Setting it advances *both* frames by the same amount,
     * which is what an undisturbed device does — a test that moved wall time
     * alone would be silently testing a clock change.
     */
    private var now: Instant
        get() = wall
        set(value) {
            uptimeMillis += Duration.between(wall, value).toMillis()
            wall = value
        }

    private val readClock: () -> ClockReading = {
        ClockReading(wallMillis = wall.toEpochMilli(), uptimeMillis = uptimeMillis)
    }

    private val anchor = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = start,
        ssid = "ExampleWifi",
    )

    private class FakeZen(
        var access: PolicyAccess = PolicyAccess.GRANTED,
        var outcome: ZenOutcome = ZenOutcome.Applied,
    ) : ZenController {
        val calls = mutableListOf<Pair<Boolean, ZenTrigger>>()
        val identities = mutableListOf<SnoozeIdentity?>()
        override fun policyAccess() = access
        override fun ruleActivation() = ZenRuleActivation.ACTIVE
        override fun ownsRule(ruleId: String?) = true
        override fun ensureRule() = ZenRuleState.READY
        override fun setSnoozed(
            snoozed: Boolean,
            trigger: ZenTrigger,
            placeName: String,
            snooze: SnoozeIdentity?,
        ): ZenOutcome {
            calls += snoozed to trigger
            identities += snooze
            return outcome
        }
        override fun ruleId() = "fake-rule-id"
    }

    private class Recorder : SnoozeController.Listener {
        val states = mutableListOf<Pair<SnoozeState, EndReason?>>()
        /** Every tracking report, in order: the mode it carried and why. */
        val tracking = mutableListOf<Pair<TrackingMode, DegradationCause?>>()
        val failures = mutableListOf<Pair<ZenFailure, Boolean>>()
        /** The mode each transition carried, so "reported once, already correct" is testable. */
        val announced = mutableListOf<TrackingMode?>()
        val releasing = mutableListOf<EndReason>()

        override fun onReleasing(reason: EndReason) {
            releasing += reason
        }

        override fun onStateChanged(state: SnoozeState, snooze: ActiveSnooze?, reason: EndReason?) {
            states += state to reason
            announced += snooze?.mode
        }
        override fun onTrackingChanged(snooze: ActiveSnooze, degradation: DegradationCause?) {
            tracking += snooze.mode to degradation
        }
        override fun onZenFailure(failure: ZenFailure, whileArming: Boolean) {
            failures += failure to whileArming
        }
    }

    private val zen = FakeZen()

    /** The controller under test in the per-reason loop below. */
    private lateinit var pending: FakeZen
    private val listener = Recorder()
    private val controller = SnoozeController(zen, readClock, listener)

    /** The common case: arm, then the anchor lands within the ceiling. */
    private fun armFully(anchor: Anchor = this.anchor): Boolean {
        val began = controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())
        controller.onAnchorCaptured(anchor)
        return began
    }

    @Test
    fun `the rule goes on at the tap, before any anchor exists`() {
        // The phone must be quiet from the tap, not from the fix (SPEC.md §4.1):
        // anchor capture takes up to 10 s and DND cannot wait for it.
        assertTrue(controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock()))

        assertEquals(SnoozeState.ARMING, controller.state)
        assertEquals(listOf(true to ZenTrigger.USER_ACTION), zen.calls)
    }

    @Test
    fun `every zen call names the snooze it is for`() {
        // The ringer's choice record carries this so a ceiling one snooze left
        // behind is never read as the next one's (SPEC.md §5.9 rule 2).
        armFully()
        val expected = SnoozeIdentity(start.toEpochMilli())
        assertEquals(listOf(expected), zen.identities)

        controller.end(EndReason.MANUAL)

        assertEquals(listOf(expected, expected), zen.identities)
    }

    @Test
    fun `a restore re-asserts under the record's own identity`() {
        val startedEarlier = start.minus(Duration.ofHours(1))
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = startedEarlier,
            capExpiresAt = start.plus(Duration.ofHours(7)),
            mode = TrackingMode.FULL,
        )

        controller.restore(running)

        assertEquals(listOf(SnoozeIdentity(startedEarlier.toEpochMilli())), zen.identities)
    }

    @Test
    fun `a snooze that is still arming is honestly duration-only`() {
        // Nothing is captured yet, so nothing can detect a departure yet.
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }

    @Test
    fun `the anchor landing completes the arm without touching the rule again`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        controller.onAnchorCaptured(anchor)

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.FULL, controller.active?.mode)
        assertEquals(1, zen.calls.size)
    }

    @Test
    fun `no fix within the ceiling still arms, degraded, and says so`() {
        // Arming must never feel slow or refuse (SPEC.md §4.1).
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        controller.onAnchorCaptured(Anchor(capturedAt = start, ssid = "ExampleWifi"))

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        // The anchor is what limits the mode here, so the recorded reason is
        // the missing fix — not the caller.
        assertEquals(
            listOf(TrackingMode.WIFI_ONLY to DegradationCause.NO_LOCATION_FIX),
            listener.tracking.toList(),
        )
    }

    @Test
    fun `no fix and no Wi-Fi arms as a timer, and says that too`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        controller.onAnchorCaptured(Anchor(capturedAt = start))

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        assertEquals(1, listener.tracking.size)
    }

    @Test
    fun `the machinery's supported set wins where it watches less than the anchor allows`() {
        // A mode is a claim about what is watching, not about what was written
        // down: machinery that watches nothing arms duration-only however
        // complete the captured anchor is (SPEC.md §8.1, §6.1).
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        controller.onAnchorCaptured(anchor, setOf(TrackingMode.DURATION_ONLY))

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        // The anchor itself is kept whole — it is what the machinery watches.
        assertEquals("ExampleWifi", controller.active?.anchor?.ssid)
        assertEquals(true, controller.active?.anchor?.hasUsableFix)
        // And the recorded reason is the machinery's limit, not a fix the
        // anchor plainly has — the debug log must not misstate which limit
        // bit (Codex, PR #71).
        assertEquals(
            listOf(TrackingMode.DURATION_ONLY to DegradationCause.NOTHING_WATCHING),
            listener.tracking.toList(),
        )
    }

    @Test
    fun `a degradation cannot fall back to a mode nothing watches`() {
        // The geofence monitor has no Wi-Fi watch, so a fenced anchor that
        // loses location degrades straight to a timer — WIFI_ONLY there would
        // promise a fallback watch that does not exist (Codex, PR #73).
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())
        controller.onAnchorCaptured(
            anchor,
            setOf(TrackingMode.FULL, TrackingMode.DURATION_ONLY),
        )
        assertEquals(TrackingMode.FULL, controller.active?.mode)

        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }

    @Test
    fun `a cause change is news even when the mode stays put`() {
        // The whole reason the record carries a cause: NO_LOCATION_FIX and
        // FIXES_TOO_VAGUE map to one mode and mean completely different things
        // to a user. A mode-only test for "did anything change" would leave
        // the notification saying the first reason while the second is true.
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())
        controller.onAnchorCaptured(anchor, setOf(TrackingMode.FULL, TrackingMode.DURATION_ONLY))
        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))
        val afterFirst = listener.tracking.size
        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        assertEquals(DegradationCause.NO_LOCATION_FIX, controller.active?.degradation)

        controller.onPresenceUpdate(update(degradation = DegradationCause.FIXES_TOO_VAGUE))

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        assertEquals(DegradationCause.FIXES_TOO_VAGUE, controller.active?.degradation)
        assertEquals(afterFirst + 1, listener.tracking.size)
        assertEquals(DegradationCause.FIXES_TOO_VAGUE, listener.tracking.last().second)
    }

    @Test
    fun `recovering clears the recorded cause`() {
        // A snooze that recovered must not keep explaining a degradation it no
        // longer has — the record is what a restore reposts the notification
        // from.
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())
        controller.onAnchorCaptured(anchor, setOf(TrackingMode.FULL, TrackingMode.DURATION_ONLY))
        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))
        assertEquals(DegradationCause.NO_LOCATION_FIX, controller.active?.degradation)

        controller.onPresenceUpdate(update(degradation = null))

        assertEquals(TrackingMode.FULL, controller.active?.mode)
        assertNull(controller.active?.degradation)
    }

    @Test
    fun `an update cannot promote the mode past what the machinery supports`() {
        // The first harmless-looking report would otherwise undo the arm's
        // honesty: a null degradation reads as "the anchor's full capability",
        // which nothing may claim while the machinery watches less.
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())
        controller.onAnchorCaptured(anchor, setOf(TrackingMode.DURATION_ONLY))

        controller.onPresenceUpdate(update(degradation = null))

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        // Only the arm's own degradation report — no promotion to announce.
        assertEquals(1, listener.tracking.size)
    }

    @Test
    fun `restore clamps the record's claim to the ceiling it is handed`() {
        // The record was written under some ceiling, but not provably this
        // one: an app update can lower what the machinery supports.
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = start,
            capExpiresAt = start.plus(Duration.ofHours(8)),
            mode = TrackingMode.FULL,
        )

        controller.restore(running, supported = setOf(TrackingMode.DURATION_ONLY))
        controller.onPresenceUpdate(update(degradation = null))

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }

    @Test
    fun `the anchor's fields cap the mode whatever the machinery offers`() {
        // The other direction of the same honesty: machinery offering FULL
        // cannot lend an anchor coordinates it never captured, and with the
        // Wi-Fi mode unwatched, an SSID-only anchor lands on the timer.
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        controller.onAnchorCaptured(
            Anchor(capturedAt = start, ssid = "ExampleWifi"),
            setOf(TrackingMode.FULL, TrackingMode.DURATION_ONLY),
        )

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }

    @Test
    fun `a fix arriving after the snooze ended does not resurrect it`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())
        controller.end(EndReason.MANUAL)

        controller.onAnchorCaptured(anchor)

        assertEquals(SnoozeState.IDLE, controller.state)
        assertNull(controller.active)
    }

    @Test
    fun `an arm the platform refuses is not an arm`() {
        // No "armed anyway": a snooze the platform doesn't know about would be a
        // lie to the user, and the failure has to be visible.
        zen.outcome = ZenOutcome.NotApplied(ZenFailure.RULE_DISABLED)

        assertFalse(controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock()))

        assertEquals(SnoozeState.IDLE, controller.state)
        assertNull(controller.active)
        assertEquals(listOf(ZenFailure.RULE_DISABLED to true), listener.failures)
    }

    @Test
    fun `the record says armed as soon as the rule is on, not when the anchor lands`() {
        // Anchor capture can be up to the 10 s ceiling after the rule goes on
        // (SPEC.md §4.1). For that whole window the record used to claim the arm
        // never completed, so a process death in it — then the user turning Do
        // Not Disturb off — had the next wake-up "finish" an already-finished
        // arm and silence the phone again (Codex, PR #36).
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        assertEquals(true, controller.active?.armed)
    }

    @Test
    fun `a tile tap writes nothing before the phone gets its sound back`() {
        // Principle 5, and the marker would have bought nothing here anyway:
        // losing a MANUAL ending to a crash falls back to DND_TURNED_OFF, which
        // is silent and user-attributed exactly like MANUAL is. So the path
        // with someone waiting on it does no disk work (Codex, PR #36).
        armFully()

        controller.end(EndReason.MANUAL)

        assertEquals(emptyList<EndReason>(), listener.releasing)
    }

    @Test
    fun `an ending the phone decided on records its reason first`() {
        armFully()

        controller.end(EndReason.DURATION_CAP)

        assertEquals(listOf(EndReason.DURATION_CAP), listener.releasing)
    }

    @Test
    fun `the user turning DND off is reported to the platform as a user action`() {
        // SPEC.md §5.4: the Modes UI shows this so the user can tell "I did
        // this" from "my phone did this". Reaching the platform's own toggle is
        // still the user reaching a switch.
        armFully()

        controller.end(EndReason.DND_TURNED_OFF)

        assertEquals(false to ZenTrigger.USER_ACTION, zen.calls.last())
    }

    @Test
    fun `only the endings the user caused are reported as user actions`() {
        for (reason in EndReason.entries) {
            val fresh = SnoozeController(FakeZen().also { zen -> pending = zen }, readClock, Recorder())
            fresh.beginArming(ActiveSnooze.capExpiryFor(now), readClock())
            fresh.onAnchorCaptured(anchor)
            fresh.end(reason)
            val expected = when (reason) {
                EndReason.MANUAL, EndReason.DND_TURNED_OFF -> ZenTrigger.USER_ACTION
                EndReason.DEPARTURE, EndReason.DURATION_CAP, EndReason.LOST_CAPABILITY ->
                    ZenTrigger.CONTEXT
            }
            assertEquals("$reason", false to expected, pending.calls.last())
        }
    }

    @Test
    fun `ending is idempotent and always drives the rule off`() {
        // The three exits race, so every one of them must be safe to fire
        // without checking first (SPEC.md §7).
        armFully()
        controller.end(EndReason.MANUAL)
        controller.end(EndReason.MANUAL)
        controller.end(EndReason.DEPARTURE)

        assertEquals(SnoozeState.IDLE, controller.state)
        assertNull(controller.active)
        assertEquals(3, zen.calls.count { it.first == false })
    }

    @Test
    fun `ending while idle is safe`() {
        controller.end(EndReason.DURATION_CAP)

        assertEquals(SnoozeState.IDLE, controller.state)
    }

    @Test
    fun `the cap fires even when nothing else has`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now, Duration.ofHours(1)), readClock())
        controller.onAnchorCaptured(anchor)

        now = start.plus(Duration.ofMinutes(59))
        controller.onCapCheck()
        assertEquals(SnoozeState.ARMED, controller.state)

        now = start.plus(Duration.ofHours(1))
        controller.onCapCheck()

        assertEquals(SnoozeState.IDLE, controller.state)
        assertEquals(EndReason.DURATION_CAP, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `an early or repeated cap alarm cannot end a snooze before its time`() {
        // Alarms fire late, early, and twice; the controller re-checks rather
        // than trusting the caller.
        controller.beginArming(ActiveSnooze.capExpiryFor(now, Duration.ofHours(2)), readClock())
        controller.onAnchorCaptured(anchor)

        controller.onCapCheck()
        controller.onCapCheck()

        assertEquals(SnoozeState.ARMED, controller.state)
    }

    @Test
    fun `a cap longer than the backstop is clamped`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now, Duration.ofDays(7)), readClock())

        assertEquals(start.plus(ActiveSnooze.MAX_CAP), controller.active?.capExpiresAt)
    }

    @Test
    fun `the record takes the caller's deadline verbatim`() {
        // The alarm is scheduled from this same instant before the controller
        // ever sees it. Deriving a second deadline here put the record a few
        // milliseconds later, so the alarm could fire, find the snooze not yet
        // expired, and be spent — leaving no duration exit at all.
        val capExpiresAt = start.plus(Duration.ofHours(3)).plusMillis(7)

        controller.beginArming(capExpiresAt, readClock())

        assertEquals(capExpiresAt, controller.active?.capExpiresAt)
    }

    /** Healthy tracking, which is the common case and the boring one. */
    private fun update(
        event: PresenceEvent? = null,
        degradation: DegradationCause? = null,
        graceActive: Boolean = false,
        // Defaults to what the monitor actually pairs with the cause, so a
        // test naming a withholding cause gets the suppressor that always
        // travels with it in production (Codex, PR #165, sixth pass). A case
        // that wants a cause *without* the suppressor — a stale observation
        // recorded after location came back — passes false explicitly, and
        // there is a test below that does.
        locationAccessLost: Boolean = degradation?.blocksLocationReads == true,
    ) = PresenceUpdate(event, degradation, graceActive, locationAccessLost)

    @Test
    fun `grace running reports WIFI_GRACE, not WIFI_ONLY, even with FULL machinery`() {
        // The bug this mode exists to fix (Codex, PR #31): WIFI_ONLY means
        // "Wi-Fi is what's tracking this", which stops being true the instant
        // Wi-Fi is what was just lost — even for an anchor whose machinery
        // could otherwise run FULL.
        armFully()

        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX, graceActive = true))

        assertEquals(TrackingMode.WIFI_GRACE, controller.active?.mode)
    }

    @Test
    fun `grace is checked ahead of degradation, since it can start before degradation does`() {
        // For a Wi-Fi-only anchor (no usable fix), grace starts the moment
        // Wi-Fi is lost — before enough failed observations have accumulated
        // for `degradation` to move off null. A caller that checked
        // `degradation == null` first would still report WIFI_ONLY on this
        // exact update.
        armFully(Anchor(capturedAt = start, ssid = "ExampleWifi"))

        controller.onPresenceUpdate(update(degradation = null, graceActive = true))

        assertEquals(TrackingMode.WIFI_GRACE, controller.active?.mode)
    }

    @Test
    fun `WIFI_GRACE is never degraded past WIFI_ONLY for want of its own explicit support`() {
        // No real monitor's supportedModes() ever names WIFI_GRACE explicitly
        // (GeofencePresenceMonitor answers FULL/WIFI_ONLY/DURATION_ONLY) — it
        // is the same watch as WIFI_ONLY reporting a worse answer, not a
        // capability of its own, so `honest()` must not walk it all the way
        // down to DURATION_ONLY for that reason alone.
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())
        controller.onAnchorCaptured(
            Anchor(capturedAt = start, ssid = "ExampleWifi"),
            setOf(TrackingMode.WIFI_ONLY, TrackingMode.DURATION_ONLY),
        )

        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX, graceActive = true))

        assertEquals(TrackingMode.WIFI_GRACE, controller.active?.mode)
    }

    @Test
    fun `grace clearing reports WIFI_ONLY again`() {
        armFully()
        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX, graceActive = true))
        assertEquals(TrackingMode.WIFI_GRACE, controller.active?.mode)

        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX, graceActive = false))

        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
    }

    @Test
    fun `probably-left escalates but never ends the snooze`() {
        // No single source ends a snooze on its own evidence (SPEC.md §6.10).
        armFully()

        controller.onPresenceUpdate(update(PresenceEvent.ProbablyLeft))

        assertEquals(SnoozeState.CHECKING, controller.state)
        assertEquals(1, zen.calls.size)
    }

    @Test
    fun `coming back de-escalates to armed`() {
        armFully()
        controller.onPresenceUpdate(update(PresenceEvent.ProbablyLeft))

        controller.onPresenceUpdate(update(PresenceEvent.StillHere))

        assertEquals(SnoozeState.ARMED, controller.state)
    }

    @Test
    fun `health recovering puts tracking back and says so`() {
        // A degraded line left up after tracking recovered is a false statement
        // about the app's own state, and it teaches the user to ignore the line
        // that matters (principle 2).
        armFully()
        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))
        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)

        controller.onPresenceUpdate(update())

        assertEquals(TrackingMode.FULL, controller.active?.mode)
        assertEquals(
            listOf(
                TrackingMode.WIFI_ONLY to DegradationCause.NO_LOCATION_FIX,
                TrackingMode.FULL to null,
            ),
            listener.tracking,
        )
    }

    @Test
    fun `an unchanged level is not reported again`() {
        // The level is restated on every update; only movement is news. Without
        // this the record and the notification would be rewritten every 90
        // seconds — the flapping the engine avoids, reintroduced at the
        // controller.
        //
        // This used to assert the same of a *different* cause under one mode
        // (`NO_LOCATION_FIX` then `FIXES_TOO_VAGUE`), on the reasoning that
        // the mode had not moved so nothing had. That reasoning was the bug:
        // the two mean opposite things to a user — location is broken, versus
        // location works but cannot place you here — and the notification now
        // says which. The case moved to its own test below; what stays true,
        // and is what this test was really protecting, is that restating one
        // unchanged level reposts nothing.
        armFully()
        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))
        listener.tracking.clear()

        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))
        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))

        assertEquals(
            "the same level restated moved nothing",
            emptyList<Pair<TrackingMode, DegradationCause?>>(),
            listener.tracking,
        )
    }

    @Test
    fun `health recovering mid-check does not end the check`() {
        armFully()
        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))
        controller.onPresenceUpdate(update(PresenceEvent.ProbablyLeft, DegradationCause.NO_LOCATION_FIX))

        controller.onPresenceUpdate(update())

        assertEquals("the user may still turn out to have left", SnoozeState.CHECKING, controller.state)
        assertEquals(TrackingMode.FULL, controller.active?.mode)
        assertEquals(TrackingMode.FULL to null, listener.tracking.last())
    }

    @Test
    fun `tracking never claims more than the anchor ever supported`() {
        // An anchor captured with a 500 m fix has nothing for the departure test
        // to measure against, however healthy location becomes later.
        armFully(anchor.copy(fixAccuracyM = 500f))
        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        listener.tracking.clear()

        controller.onPresenceUpdate(update(PresenceEvent.StillHere))

        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        assertEquals(emptyList<Pair<TrackingMode, DegradationCause?>>(), listener.tracking)
    }

    @Test
    fun `losing location without an SSID falls all the way to duration-only`() {
        // Claiming WIFI_ONLY for an anchor with no network would tell the user
        // tracking is better than it is.
        armFully(anchor.copy(ssid = null))

        controller.onPresenceUpdate(update(degradation = DegradationCause.LOCATION_SERVICES_OFF))

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }

    @Test
    fun `a confirmed departure ends the snooze as context, not user action`() {
        armFully()

        controller.onPresenceUpdate(update(PresenceEvent.Departed))

        assertEquals(SnoozeState.IDLE, controller.state)
        assertEquals(false to ZenTrigger.CONTEXT, zen.calls.last())
        assertEquals(EndReason.DEPARTURE, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `an unexplainable loss of tracking ends the snooze rather than staying armed`() {
        // Fail open (SPEC.md D7) — and this is now the *only* capability loss
        // left, deliberately. The permission cases moved to degradation on
        // 2026-08-30 because we can name what is wrong and the cap bounds the
        // fallback; this one is "something refused and we don't know what",
        // where staying armed is how a phone stays silent with nothing left to
        // release it.
        armFully()

        controller.onPresenceUpdate(
            update(PresenceEvent.CapabilityLost(CapabilityLossCause.MONITORING_UNAVAILABLE)),
        )

        assertEquals(SnoozeState.IDLE, controller.state)
        assertEquals(EndReason.LOST_CAPABILITY, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `a lost location grant degrades instead of ending`() {
        // The reversal itself (maintainer, 2026-08-30). The duration cap is
        // mandatory, so duration-only is bounded by construction; ending here
        // discarded the user's snooze without buying safety the cap did not
        // already give.
        armFully()

        controller.onPresenceUpdate(
            update(event = null, degradation = DegradationCause.LOCATION_PERMISSION_GONE),
        )

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        assertEquals(DegradationCause.LOCATION_PERMISSION_GONE, controller.active?.degradation)
    }

    @Test
    fun `a withholding cause without the suppressor keeps Wi-Fi mode`() {
        // Codex, PR #165, sixth pass. The mode follows what is actually
        // suppressed, not which cause is in the slot, and the two come apart:
        // a `GEOFENCE_NOT_AVAILABLE` observation delivered late — after the
        // user has switched location back on — records
        // `LOCATION_SERVICES_OFF` while Wi-Fi is reading names again and no
        // suppressor was ever delivered. Grace can still arm and end the
        // snooze at the anchor, so `Timer only` would promise the cap over a
        // snooze that ends minutes later.
        //
        // This is the mirror image of the failure the branch above fixes, and
        // reading the cause produced one or the other whichever way it was
        // written.
        armFully()

        controller.onPresenceUpdate(
            update(
                degradation = DegradationCause.LOCATION_SERVICES_OFF,
                locationAccessLost = false,
            ),
        )

        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        assertEquals(
            DegradationCause.LOCATION_SERVICES_OFF,
            controller.active?.degradation,
        )
    }

    @Test
    fun `the suppressor outranks a running grace period whatever named it`() {
        // The suppressor is delivered on causes the permission probe cannot
        // name at all, so this must not quietly depend on a cause being
        // present: a redaction no gate explains latches and declares with the
        // slot holding a best-effort label, and the mode has to follow the
        // declaration.
        armFully()

        controller.onPresenceUpdate(
            update(degradation = null, graceActive = true, locationAccessLost = true),
        )

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }


    @Test
    fun `a lost grant never falls back to Wi-Fi, even with an anchor SSID`() {
        // Reading an SSID needs the same grant that just went — there is no
        // separate Wi-Fi permission — and a background read under a
        // while-in-use grant comes back redacted, which the tracker treats as
        // *not associated*. WIFI_ONLY here would report departures with the
        // phone on its own network.
        armFully()
        assertNotNull(controller.active?.anchor?.ssid)

        for (cause in listOf(
            DegradationCause.LOCATION_PERMISSION_GONE,
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
            // Location switched off system-wide belongs with the two grants
            // (Codex, PR #165): it withholds the SSID by the same mechanism,
            // and the monitor now says so the moment a read comes back
            // redacted. Left out, the card claimed `Wi-Fi only` while the
            // engine had already shut every grace path — a snooze running to
            // the cap under a line saying something else was tracking it.
            DegradationCause.LOCATION_SERVICES_OFF,
        )) {
            controller.onPresenceUpdate(update(event = null, degradation = cause))

            assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        }
    }

    @Test
    fun `a lost grant outranks a running grace period`() {
        // The one cause that sits above `graceActive` (Codex, PR #149).
        // WIFI_GRACE says Wi-Fi is bounding a departure — but under a dead
        // grant the SSID reads as absent because the *permission* is, which
        // is plausibly what started the grace period at all, so the honest
        // answer is the timer. Safe to say only because the engine clears
        // the deadline on the same classification; a card promising the cap
        // while an alarm still ended the snooze would be worse than this bug.
        armFully()

        for (cause in listOf(
            DegradationCause.LOCATION_PERMISSION_GONE,
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
            DegradationCause.LOCATION_SERVICES_OFF,
        )) {
            controller.onPresenceUpdate(update(degradation = cause, graceActive = true))

            assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        }
    }

    @Test
    fun `every other cause still yields to grace`() {
        // Guards the narrowness of the line above: it must be the causes
        // that *withhold location data* which outrank grace, not degradation
        // in general, or PR #31's bug returns wearing the fix for PR #149's.
        //
        // The example moved from `LOCATION_SERVICES_OFF` to these two (Codex,
        // PR #165) because the services switch turned out to belong on the
        // other side of the line — it withholds the SSID like a dead grant
        // does. What is left here is the shape this test was always about:
        // location is working and simply not answering usefully, so Wi-Fi is
        // genuinely intact and a grace period bounding a departure is honest.
        armFully()

        for (cause in listOf(
            DegradationCause.NO_LOCATION_FIX,
            DegradationCause.FIXES_TOO_VAGUE,
        )) {
            controller.onPresenceUpdate(update(degradation = cause, graceActive = true))

            assertEquals(TrackingMode.WIFI_GRACE, controller.active?.mode)
        }
    }

    @Test
    fun `arming with an unusable anchor arms degraded and says so`() {
        val vague = anchor.copy(fixAccuracyM = 500f)

        assertTrue(armFully(vague))

        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        assertEquals(1, listener.tracking.size)
    }

    @Test
    fun `a restored snooze whose cap already passed ends immediately`() {
        // A reboot does not extend a snooze (SPEC.md §8.3).
        val expired = ActiveSnooze(
            anchor = anchor,
            startedAt = start.minus(Duration.ofHours(9)),
            capExpiresAt = start.minus(Duration.ofHours(1)),
            mode = TrackingMode.FULL,
        )

        controller.restore(expired)

        assertEquals(SnoozeState.IDLE, controller.state)
        assertEquals(EndReason.DURATION_CAP, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `a restored snooze still within its cap keeps running`() {
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = start.minus(Duration.ofHours(1)),
            capExpiresAt = start.plus(Duration.ofHours(7)),
            mode = TrackingMode.FULL,
        )

        controller.restore(running)

        assertEquals(SnoozeState.ARMED, controller.state)
        // Identical but for `armed`, which the re-assertion has just made true
        // (see the §5.8 restore tests below).
        assertEquals(running.copy(armed = true), controller.active)
    }

    @Test
    fun `a release the platform refuses keeps the snooze so it can be retried`() {
        // The record is the only thing that can turn the rule off later: it keeps
        // the cap alarm armed and the tile and notification on screen. Clearing
        // it would leave the rule active with nothing left that knows to try
        // again — a phone silent indefinitely.
        armFully()
        zen.outcome = ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)

        controller.end(EndReason.MANUAL)

        assertEquals(SnoozeState.ARMED, controller.state)
        assertNotNull(controller.active)
        assertEquals(listOf(ZenFailure.PLATFORM_REFUSED to false), listener.failures)
    }

    @Test
    fun `losing policy access mid-snooze ends it rather than stranding it`() {
        // The failure the retry rule would otherwise cause: revoking access
        // deletes the app's rule, so every retry fails the same way forever and
        // the record leaves the app claiming "Snoozing" over a phone that is
        // already ringing. SPEC.md §8.2 promises revocation *ends* the snooze.
        armFully()
        zen.outcome = ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS)

        controller.end(EndReason.LOST_CAPABILITY)

        assertEquals(SnoozeState.IDLE, controller.state)
        assertNull(controller.active)
        // Reported as well as acted on, so the reason isn't left to be guessed.
        assertEquals(listOf(ZenFailure.NO_POLICY_ACCESS to false), listener.failures)
        assertEquals(EndReason.LOST_CAPABILITY, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `a release fails open whenever nothing is left holding the phone quiet`() {
        // The whole distinction in one place: only a refusal of a rule that
        // still exists is worth retrying.
        assertTrue(ZenFailure.NO_POLICY_ACCESS.nothingLeftToRelease)
        assertTrue(ZenFailure.NO_RULE.nothingLeftToRelease)
        assertTrue(ZenFailure.RULE_DISABLED.nothingLeftToRelease)
        assertFalse(ZenFailure.PLATFORM_REFUSED.nothingLeftToRelease)

        for (failure in ZenFailure.entries.filter { it.nothingLeftToRelease }) {
            val zen = FakeZen()
            val controller = SnoozeController(zen, readClock, Recorder())
            controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

            zen.outcome = ZenOutcome.NotApplied(failure)
            controller.end(EndReason.DURATION_CAP)

            assertNull("$failure should not strand the snooze", controller.active)
        }
    }

    @Test
    fun `a retried release succeeds once the platform recovers`() {
        armFully()
        zen.outcome = ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
        controller.end(EndReason.DURATION_CAP)

        zen.outcome = ZenOutcome.Applied
        controller.end(EndReason.DURATION_CAP)

        assertEquals(SnoozeState.IDLE, controller.state)
        assertNull(controller.active)
    }

    @Test
    fun `adopting a snooze to end it never turns the rule on first`() {
        // An End tap that recreates the service must not re-assert DND on the
        // way to turning it off: if that release were then refused, the flap
        // leaves the phone quiet behind the user's explicit exit.
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = start.minus(Duration.ofHours(1)),
            capExpiresAt = start.plus(Duration.ofHours(7)),
            mode = TrackingMode.FULL,
        )

        controller.adopt(running)
        controller.end(EndReason.MANUAL)

        // Exactly one call, and it is the release.
        assertEquals(listOf(false to ZenTrigger.USER_ACTION), zen.calls)
        assertNull(controller.active)
    }

    @Test
    fun `adopting emits no transition, so nothing announces a snooze about to end`() {
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = start,
            capExpiresAt = start.plus(Duration.ofHours(7)),
            mode = TrackingMode.FULL,
        )

        controller.adopt(running)

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(emptyList<Pair<SnoozeState, EndReason?>>(), listener.states)
    }

    @Test
    fun `adopting leaves a running snooze alone`() {
        armFully()
        val armed = controller.active

        controller.adopt(
            ActiveSnooze(
                anchor = anchor,
                startedAt = start.minus(Duration.ofHours(3)),
                capExpiresAt = start.plus(Duration.ofHours(1)),
                mode = TrackingMode.FULL,
            ),
        )

        assertEquals(armed, controller.active)
    }

    @Test
    fun `a restore that re-asserts the rule records that the rule is on`() {
        // An arm that died before the rule went on leaves `armed = false`, and
        // the §5.8 read uses exactly that to tell an unfinished arm from a user
        // who turned Do Not Disturb off. Restoring re-asserts the rule, so the
        // record has to stop saying the arm never finished — otherwise the next
        // process death lands in the same branch and re-silences a phone the
        // user had just un-silenced (Codex, PR #36).
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = start.minus(Duration.ofHours(1)),
            capExpiresAt = start.plus(Duration.ofHours(7)),
            mode = TrackingMode.FULL,
            armed = false,
        )

        controller.restore(running)

        // Same value the listener is handed, and so the same value the service
        // persists — `restore` sets both from one record.
        assertEquals(true, controller.active?.armed)
    }

    @Test
    fun `a restore the platform refuses does not claim the rule is on`() {
        // The refusal path deliberately stays armed without the rule being
        // confirmed, so it must not mark the record either — that would erase
        // the very distinction the test above preserves.
        zen.outcome = ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = start.minus(Duration.ofHours(1)),
            capExpiresAt = start.plus(Duration.ofHours(7)),
            mode = TrackingMode.FULL,
            armed = false,
        )

        controller.restore(running)

        assertEquals(false, controller.active?.armed ?: false)
    }

    @Test
    fun `a restored snooze re-asserts the rule rather than assuming it survived`() {
        // The record surviving does not mean the rule's condition did (SPEC.md
        // §8.1) — otherwise the app shows a snooze over a phone that rings.
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = start.minus(Duration.ofHours(1)),
            capExpiresAt = start.plus(Duration.ofHours(7)),
            mode = TrackingMode.FULL,
        )

        controller.restore(running)

        assertEquals(listOf(true to ZenTrigger.CONTEXT), zen.calls)
    }

    @Test
    fun `a restore that cannot re-assert the rule ends the snooze instead of claiming it`() {
        zen.outcome = ZenOutcome.NotApplied(ZenFailure.RULE_DISABLED)
        val running = ActiveSnooze(
            anchor = anchor,
            startedAt = start,
            capExpiresAt = start.plus(Duration.ofHours(7)),
            mode = TrackingMode.FULL,
        )

        controller.restore(running)

        assertEquals(SnoozeState.IDLE, controller.state)
        assertNull(controller.active)
        assertEquals(EndReason.LOST_CAPABILITY, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `an already-expired record is released, never re-asserted`() {
        // Re-asserting first would silence the phone again on the way to ending
        // it — briefly if the release works, and until some later retry if it
        // doesn't. Ending is the same call either way, so there is nothing to
        // buy by turning the rule on first.
        val snooze = ActiveSnooze(
            anchor = anchor,
            startedAt = start,
            capExpiresAt = start.plus(Duration.ofHours(1)),
            mode = TrackingMode.FULL,
        )
        now = start.plus(Duration.ofHours(2))

        controller.restore(snooze)

        assertEquals(listOf(false to ZenTrigger.CONTEXT), zen.calls)
        assertNull(controller.active)
        assertEquals(EndReason.DURATION_CAP, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `a restore the platform merely refuses keeps the snooze`() {
        // Re-asserting a rule that is already on can be refused transiently, and
        // a refusal is not evidence the rule went off. Treating it as an ending
        // would erase the record and cancel the cap — the only two things that
        // could ever turn it back off — over a phone that is still quiet.
        val snooze = ActiveSnooze(
            anchor = anchor,
            startedAt = start,
            capExpiresAt = start.plus(Duration.ofHours(2)),
            mode = TrackingMode.FULL,
        )
        zen.outcome = ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)

        controller.restore(snooze)

        assertEquals(snooze, controller.active)
        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(listOf(ZenFailure.PLATFORM_REFUSED to true), listener.failures)
    }

    @Test
    fun `a restore with nothing left to release ends the snooze`() {
        val snooze = ActiveSnooze(
            anchor = anchor,
            startedAt = start,
            capExpiresAt = start.plus(Duration.ofHours(2)),
            mode = TrackingMode.FULL,
        )
        zen.outcome = ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS)

        controller.restore(snooze)

        assertNull(controller.active)
        assertEquals(SnoozeState.IDLE, controller.state)
        assertEquals(EndReason.LOST_CAPABILITY, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `extending moves the cap and reports the new one`() {
        armFully()
        val extendedCap = controller.active!!.capExpiresAt.plus(Duration.ofMinutes(30))

        val extended = controller.extendTo(extendedCap)

        assertEquals(extendedCap, extended?.capExpiresAt)
        assertEquals(extendedCap, controller.active?.capExpiresAt)
        // Reported, so the notification's countdown follows the cap rather than
        // going stale until the next unrelated transition.
        assertEquals(SnoozeState.ARMED, listener.states.last().first)
    }

    @Test
    fun `extending refuses to pull the cap earlier`() {
        // The caller re-arms the alarm first and only then calls this, so a cap
        // that isn't later means the alarm didn't move either — accepting it
        // would show a countdown the alarm was never set for.
        armFully()
        val original = controller.active!!.capExpiresAt

        assertNull(controller.extendTo(original))
        assertNull(controller.extendTo(original.minusSeconds(60)))
        assertEquals(original, controller.active?.capExpiresAt)
    }

    @Test
    fun `extending nothing does nothing`() {
        assertNull(controller.extendTo(start.plus(Duration.ofHours(1))))
        assertNull(controller.active)
    }

    @Test
    fun `a chosen time pulls the cap in and reports the new one`() {
        // The end-condition sheet's time row (SPEC.md §4.4). Not a fourth exit
        // — the same one deadline, moved.
        armFully()
        val chosen = start.plus(Duration.ofHours(1))

        val shortened = controller.lowerCapTo(chosen)

        assertEquals(chosen, shortened?.capExpiresAt)
        assertEquals(chosen, controller.active?.capExpiresAt)
        // Reported, so the notification's countdown follows the cap in rather
        // than promising the eight hours it opened with.
        assertEquals(SnoozeState.ARMED, listener.states.last().first)
    }

    @Test
    fun `a chosen time refuses to push the cap out`() {
        // The mirror of the extension's refusal, and for the same reason read
        // the other way: the caller re-arms the alarm first, so a cap that isn't
        // earlier means the alarm didn't move either. `+30 min` is the only
        // thing that lengthens a snooze (SPEC.md §4.3).
        armFully()
        val original = controller.active!!.capExpiresAt

        assertNull(controller.lowerCapTo(original))
        assertNull(controller.lowerCapTo(original.plusSeconds(60)))
        assertEquals(original, controller.active?.capExpiresAt)
    }

    @Test
    fun `choosing a time on nothing does nothing`() {
        assertNull(controller.lowerCapTo(start.plus(Duration.ofHours(1))))
        assertNull(controller.active)
    }

    @Test
    fun `a chosen cap still fires, at the chosen time`() {
        // The invariant a shortened cap must not break either: the cap always
        // fires, and now it fires sooner than the default would have.
        armFully()
        val chosen = start.plus(Duration.ofHours(1))
        controller.lowerCapTo(chosen)

        now = chosen.minusSeconds(1)
        controller.onCapCheck()
        assertNotNull(controller.active)

        now = chosen
        controller.onCapCheck()
        assertNull(controller.active)
        assertEquals(EndReason.DURATION_CAP, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `an extended cap still fires, at the new time`() {
        // The invariant the extension must not break: the cap always fires.
        armFully()
        val extendedCap = controller.active!!.capExpiresAt.plus(Duration.ofMinutes(30))
        controller.extendTo(extendedCap)

        now = extendedCap.minusSeconds(1)
        controller.onCapCheck()
        assertNotNull(controller.active)

        now = extendedCap
        controller.onCapCheck()
        assertNull(controller.active)
        assertEquals(EndReason.DURATION_CAP, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `a restated record replaces the running one, so the next write carries it`() {
        // The bug this exists for: the clock change wrote the repaired record to
        // disk while the controller kept the pre-change one, and `+30 min`
        // derived its update from memory — putting the pre-change deadline
        // straight back over the repair.
        // A four-hour snooze, so `+30 min` still has room under the eight-hour
        // backstop once the shift has been reconciled away — a default one is
        // already at the backstop two hours in and would decline to extend,
        // which is a different rule (see `ActiveSnoozeTest`).
        controller.beginArming(
            ActiveSnooze.capExpiryFor(now, Duration.ofHours(4)),
            readClock(),
        )
        controller.onAnchorCaptured(anchor)
        val running = controller.active!!
        // Two real hours in, the clock goes back three.
        uptimeMillis += Duration.ofHours(2).toMillis()
        wall = start.minus(Duration.ofHours(1))
        val restated = requireNotNull(running.reconciledOnto(readClock()))

        assertEquals(restated, controller.reconciledTo(restated))
        assertEquals(restated, controller.active)

        // And the extension that follows now builds on the restated deadline.
        val extended = controller.extendTo(controller.active!!.extendedCap(Duration.ofMinutes(30)))
        assertEquals(Duration.ofHours(2).plusMinutes(30), extended!!.remaining(readClock()))
    }

    @Test
    fun `a restated record for some other snooze is ignored`() {
        // The record and the controller can disagree about *which* snooze is
        // running — a broadcast that read the record before the user armed a new
        // one, say — and adopting a stranger's deadline is how the live snooze
        // ends up bounded by a time nothing chose for it.
        armFully()
        val running = controller.active!!
        val stranger = running.copy(
            startedAt = start.minus(Duration.ofHours(4)),
            capExpiresAt = start.plus(Duration.ofHours(20)),
        )

        assertNull(controller.reconciledTo(stranger))
        assertEquals(running, controller.active)
    }

    @Test
    fun `restating nothing does nothing`() {
        // Idle: a clock change with no snooze running has nothing to restate,
        // and must not conjure one back into memory.
        val orphan = ActiveSnooze(
            anchor = anchor,
            startedAt = start,
            capExpiresAt = start.plus(Duration.ofHours(8)),
            mode = TrackingMode.FULL,
        )

        assertNull(controller.reconciledTo(orphan))
        assertNull(controller.active)
    }

    @Test
    fun `a clock change during arming cannot separate the record from its alarm`() {
        // The deadline, the alarm and the record's offset all come from one
        // reading. Taken from two, a wall-clock change landing between them
        // pairs a pre-change deadline with a post-change offset — and the cap
        // then reads as hours further off than the alarm is set for, so the
        // alarm fires, finds the snooze unexpired, and is spent for nothing.
        val atArm = readClock()
        val capExpiresAt = ActiveSnooze.capExpiryFor(atArm)

        // The user sets the clock back three hours mid-arm. Uptime is untouched;
        // only the wall reading moves, which is what a clock change is.
        wall = start.minus(Duration.ofHours(3))

        controller.beginArming(capExpiresAt, atArm)

        // Eight hours of real time later — when the alarm actually fires — the
        // cap must be due, however far the wall clock was moved.
        uptimeMillis += Duration.ofHours(8).toMillis()
        assertTrue(
            "the record must agree with the alarm the same reading armed",
            controller.active!!.isExpired(readClock()),
        )
    }
}
