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
        override fun policyAccess() = access
        override fun ensureRule() = ZenRuleState.READY
        override fun setSnoozed(snoozed: Boolean, trigger: ZenTrigger, placeName: String): ZenOutcome {
            calls += snoozed to trigger
            return outcome
        }
    }

    private class Recorder : SnoozeController.Listener {
        val states = mutableListOf<Pair<SnoozeState, EndReason?>>()
        /** Every tracking report, in order: the mode it carried and why. */
        val tracking = mutableListOf<Pair<TrackingMode, DegradationCause?>>()
        val failures = mutableListOf<Pair<ZenFailure, Boolean>>()
        /** The mode each transition carried, so "reported once, already correct" is testable. */
        val announced = mutableListOf<TrackingMode?>()
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
    fun `the caller's stated mode wins where it claims less than the anchor allows`() {
        // A mode is a claim about what is watching, not about what was written
        // down: a caller that has started nothing arms duration-only however
        // complete the captured anchor is (SPEC.md §8.1).
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        controller.onAnchorCaptured(anchor, TrackingMode.DURATION_ONLY)

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        // The anchor itself is kept whole — it is what the next slice watches.
        assertEquals("ExampleWifi", controller.active?.anchor?.ssid)
        assertEquals(true, controller.active?.anchor?.hasUsableFix)
        // And the recorded reason is the caller's limit, not a fix the anchor
        // plainly has — the debug log must not misstate which limit bit
        // (Codex, PR #71).
        assertEquals(
            listOf(TrackingMode.DURATION_ONLY to DegradationCause.NOTHING_WATCHING),
            listener.tracking.toList(),
        )
    }

    @Test
    fun `a stated mode cannot claim more than the anchor supports`() {
        // The same lie in the more dangerous direction: FULL over an anchor
        // with no coordinates is a departure test nothing can run.
        controller.beginArming(ActiveSnooze.capExpiryFor(now), readClock())

        controller.onAnchorCaptured(
            Anchor(capturedAt = start, ssid = "ExampleWifi"),
            TrackingMode.FULL,
        )

        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
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
    private fun update(event: PresenceEvent? = null, degradation: DegradationCause? = null) =
        PresenceUpdate(event, degradation)

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
        armFully()
        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))
        listener.tracking.clear()

        controller.onPresenceUpdate(update(degradation = DegradationCause.NO_LOCATION_FIX))
        controller.onPresenceUpdate(update(degradation = DegradationCause.FIXES_TOO_VAGUE))

        assertEquals(
            "same mode either way, so neither update moved anything",
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
    fun `losing the ability to track ends the snooze rather than staying armed`() {
        // Fail open (SPEC.md D7): staying armed on state nothing can verify is
        // how a phone stays silent with nothing left to release it.
        armFully()

        controller.onPresenceUpdate(
            update(PresenceEvent.CapabilityLost(CapabilityLossCause.LOCATION_PERMISSION_REVOKED)),
        )

        assertEquals(SnoozeState.IDLE, controller.state)
        assertEquals(EndReason.LOST_CAPABILITY, listener.states.last { it.second != null }.second)
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
        assertEquals(running, controller.active)
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
