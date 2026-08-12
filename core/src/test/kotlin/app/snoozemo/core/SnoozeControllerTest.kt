package app.snoozemo.core

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoozeControllerTest {

    private val start: Instant = Instant.parse("2026-08-11T09:00:00Z")
    private var now: Instant = start
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
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
        val degraded = mutableListOf<DegradationCause>()
        val failures = mutableListOf<Pair<ZenFailure, Boolean>>()
        override fun onStateChanged(state: SnoozeState, snooze: ActiveSnooze?, reason: EndReason?) {
            states += state to reason
        }
        override fun onDegraded(snooze: ActiveSnooze, cause: DegradationCause) {
            degraded += cause
        }
        override fun onZenFailure(failure: ZenFailure, whileArming: Boolean) {
            failures += failure to whileArming
        }
    }

    private val zen = FakeZen()
    private val listener = Recorder()
    private val controller = SnoozeController(zen, clock, listener)

    /** The common case: arm, then the anchor lands within the ceiling. */
    private fun armFully(anchor: Anchor = this.anchor): Boolean {
        val began = controller.beginArming(ActiveSnooze.capExpiryFor(now))
        controller.onAnchorCaptured(anchor)
        return began
    }

    @Test
    fun `the rule goes on at the tap, before any anchor exists`() {
        // The phone must be quiet from the tap, not from the fix (SPEC.md §4.1):
        // anchor capture takes up to 10 s and DND cannot wait for it.
        assertTrue(controller.beginArming(ActiveSnooze.capExpiryFor(now)))

        assertEquals(SnoozeState.ARMING, controller.state)
        assertEquals(listOf(true to ZenTrigger.USER_ACTION), zen.calls)
    }

    @Test
    fun `a snooze that is still arming is honestly duration-only`() {
        // Nothing is captured yet, so nothing can detect a departure yet.
        controller.beginArming(ActiveSnooze.capExpiryFor(now))

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }

    @Test
    fun `the anchor landing completes the arm without touching the rule again`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now))

        controller.onAnchorCaptured(anchor)

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.FULL, controller.active?.mode)
        assertEquals(1, zen.calls.size)
    }

    @Test
    fun `no fix within the ceiling still arms, degraded, and says so`() {
        // Arming must never feel slow or refuse (SPEC.md §4.1).
        controller.beginArming(ActiveSnooze.capExpiryFor(now))

        controller.onAnchorUnavailable(ssid = "ExampleWifi")

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        assertEquals(1, listener.degraded.size)
    }

    @Test
    fun `no fix and no Wi-Fi arms as a timer, and says that too`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now))

        controller.onAnchorUnavailable(ssid = null)

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        assertEquals(1, listener.degraded.size)
    }

    @Test
    fun `a fix arriving after the snooze ended does not resurrect it`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now))
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

        assertFalse(controller.beginArming(ActiveSnooze.capExpiryFor(now)))

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
        controller.beginArming(ActiveSnooze.capExpiryFor(now, Duration.ofHours(1)))
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
        controller.beginArming(ActiveSnooze.capExpiryFor(now, Duration.ofHours(2)))
        controller.onAnchorCaptured(anchor)

        controller.onCapCheck()
        controller.onCapCheck()

        assertEquals(SnoozeState.ARMED, controller.state)
    }

    @Test
    fun `a cap longer than the backstop is clamped`() {
        controller.beginArming(ActiveSnooze.capExpiryFor(now, Duration.ofDays(7)))

        assertEquals(start.plus(ActiveSnooze.MAX_CAP), controller.active?.capExpiresAt)
    }

    @Test
    fun `the record takes the caller's deadline verbatim`() {
        // The alarm is scheduled from this same instant before the controller
        // ever sees it. Deriving a second deadline here put the record a few
        // milliseconds later, so the alarm could fire, find the snooze not yet
        // expired, and be spent — leaving no duration exit at all.
        val capExpiresAt = start.plus(Duration.ofHours(3)).plusMillis(7)

        controller.beginArming(capExpiresAt)

        assertEquals(capExpiresAt, controller.active?.capExpiresAt)
    }

    @Test
    fun `probably-left escalates but never ends the snooze`() {
        // No single source ends a snooze on its own evidence (SPEC.md §6.10).
        armFully()

        controller.onPresenceEvent(PresenceEvent.ProbablyLeft)

        assertEquals(SnoozeState.CHECKING, controller.state)
        assertEquals(1, zen.calls.size)
    }

    @Test
    fun `coming back de-escalates to armed`() {
        armFully()
        controller.onPresenceEvent(PresenceEvent.ProbablyLeft)

        controller.onPresenceEvent(PresenceEvent.StillHere)

        assertEquals(SnoozeState.ARMED, controller.state)
    }

    @Test
    fun `a confirmed departure ends the snooze as context, not user action`() {
        armFully()

        controller.onPresenceEvent(PresenceEvent.Departed)

        assertEquals(SnoozeState.IDLE, controller.state)
        assertEquals(false to ZenTrigger.CONTEXT, zen.calls.last())
        assertEquals(EndReason.DEPARTURE, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `losing the ability to track ends the snooze rather than staying armed`() {
        // Fail open (SPEC.md D7): staying armed on state nothing can verify is
        // how a phone stays silent with nothing left to release it.
        armFully()

        controller.onPresenceEvent(
            PresenceEvent.CapabilityLost(CapabilityLossCause.LOCATION_PERMISSION_REVOKED),
        )

        assertEquals(SnoozeState.IDLE, controller.state)
        assertEquals(EndReason.LOST_CAPABILITY, listener.states.last { it.second != null }.second)
    }

    @Test
    fun `degrading keeps the snooze but reports the drop`() {
        armFully()

        controller.onPresenceEvent(PresenceEvent.Degraded(DegradationCause.NO_LOCATION_FIX))

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        assertEquals(listOf(DegradationCause.NO_LOCATION_FIX), listener.degraded)
    }

    @Test
    fun `degrading without an SSID falls all the way to duration-only`() {
        // Claiming WIFI_ONLY for an anchor with no network would tell the user
        // tracking is better than it is.
        val noWifi = anchor.copy(ssid = null)
        armFully(noWifi)

        controller.onPresenceEvent(PresenceEvent.Degraded(DegradationCause.LOCATION_SERVICES_OFF))

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }

    @Test
    fun `arming with an unusable anchor arms degraded and says so`() {
        val vague = anchor.copy(fixAccuracyM = 500f)

        assertTrue(armFully(vague))

        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        assertEquals(1, listener.degraded.size)
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
            val controller = SnoozeController(zen, clock, Recorder())
            controller.beginArming(ActiveSnooze.capExpiryFor(now))

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
}
