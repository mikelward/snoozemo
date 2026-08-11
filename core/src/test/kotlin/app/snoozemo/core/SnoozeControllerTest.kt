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
        val began = controller.beginArming()
        controller.onAnchorCaptured(anchor)
        return began
    }

    @Test
    fun `the rule goes on at the tap, before any anchor exists`() {
        // The phone must be quiet from the tap, not from the fix (SPEC.md §4.1):
        // anchor capture takes up to 10 s and DND cannot wait for it.
        assertTrue(controller.beginArming())

        assertEquals(SnoozeState.ARMING, controller.state)
        assertEquals(listOf(true to ZenTrigger.USER_ACTION), zen.calls)
    }

    @Test
    fun `a snooze that is still arming is honestly duration-only`() {
        // Nothing is captured yet, so nothing can detect a departure yet.
        controller.beginArming()

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
    }

    @Test
    fun `the anchor landing completes the arm without touching the rule again`() {
        controller.beginArming()

        controller.onAnchorCaptured(anchor)

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.FULL, controller.active?.mode)
        assertEquals(1, zen.calls.size)
    }

    @Test
    fun `no fix within the ceiling still arms, degraded, and says so`() {
        // Arming must never feel slow or refuse (SPEC.md §4.1).
        controller.beginArming()

        controller.onAnchorUnavailable(ssid = "ExampleWifi")

        assertEquals(SnoozeState.ARMED, controller.state)
        assertEquals(TrackingMode.WIFI_ONLY, controller.active?.mode)
        assertEquals(1, listener.degraded.size)
    }

    @Test
    fun `no fix and no Wi-Fi arms as a timer, and says that too`() {
        controller.beginArming()

        controller.onAnchorUnavailable(ssid = null)

        assertEquals(TrackingMode.DURATION_ONLY, controller.active?.mode)
        assertEquals(1, listener.degraded.size)
    }

    @Test
    fun `a fix arriving after the snooze ended does not resurrect it`() {
        controller.beginArming()
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

        assertFalse(controller.beginArming())

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
        controller.beginArming(cap = Duration.ofHours(1))
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
        controller.beginArming(cap = Duration.ofHours(2))
        controller.onAnchorCaptured(anchor)

        controller.onCapCheck()
        controller.onCapCheck()

        assertEquals(SnoozeState.ARMED, controller.state)
    }

    @Test
    fun `a cap longer than the backstop is clamped`() {
        controller.beginArming(cap = Duration.ofDays(7))

        assertEquals(start.plus(ActiveSnooze.MAX_CAP), controller.active?.capExpiresAt)
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
}
