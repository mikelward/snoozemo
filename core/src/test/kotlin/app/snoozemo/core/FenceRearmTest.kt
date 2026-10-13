package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geofence re-registration after a false alarm (SPEC.md §6.10), replayed
 * as sequences of delivered signals and the engine's answers.
 */
class FenceRearmTest {

    private val exit = PresenceSignal.GeofenceExit(1_000L)
    private val fix = PresenceSignal.FixArrived(Fix(lat = 0.0, lon = 0.0, accuracyM = 10f, elapsedRealtimeMs = 2_000L))
    private val wifiBack = PresenceSignal.AnchorWifiAssociated(3_000L)

    /** Replays [steps] from [start], returning every step taken. */
    private fun replay(
        vararg steps: Pair<PresenceSignal, PresenceEvent?>,
        start: FenceRearm = FenceRearm(),
    ): List<FenceRearm.Step> {
        var rearm = start
        return steps.map { (signal, event) -> rearm.after(signal, event).also { rearm = it.next } }
    }

    @Test
    fun `an exit the departure test rejects re-registers the fence`() {
        // The field log's shape: the exit fired, one fix put the user inside,
        // and the fence never fired for the real departure that followed.
        val steps = replay(
            exit to PresenceEvent.ProbablyLeft,
            fix to PresenceEvent.StillHere,
        )

        assertFalse("the exit only starts the check", steps[0].rearmNow)
        assertTrue(steps[1].falseAlarm)
        assertTrue(steps[1].rearmNow)
        assertEquals(FenceRearm.BUDGET - 1, steps[1].next.left)
    }

    @Test
    fun `the anchor's Wi-Fi coming back after an exit is a false alarm too`() {
        val steps = replay(
            exit to PresenceEvent.ProbablyLeft,
            wifiBack to PresenceEvent.StillHere,
        )

        assertTrue(steps[1].rearmNow)
    }

    @Test
    fun `a still-here with no exit behind it leaves the fence alone`() {
        // A check started by a Wi-Fi drop or the motion trigger: the fence
        // never fired, so it is still armed for the real departure.
        val steps = replay(fix to PresenceEvent.StillHere)

        assertFalse(steps[0].falseAlarm)
        assertFalse(steps[0].rearmNow)
        assertEquals(FenceRearm.BUDGET, steps[0].next.left)
    }

    @Test
    fun `a confirmed departure does not re-register`() {
        val steps = replay(
            exit to PresenceEvent.ProbablyLeft,
            fix to PresenceEvent.Departed,
        )

        assertFalse(steps[1].rearmNow)
    }

    @Test
    fun `an unanswered check keeps the exit pending until it settles`() {
        val steps = replay(
            exit to PresenceEvent.ProbablyLeft,
            fix to null,
            fix to null,
            fix to PresenceEvent.StillHere,
        )

        assertTrue(steps[1].next.exitPending)
        assertTrue(steps[3].rearmNow)
    }

    @Test
    fun `false alarms stop re-registering once the budget is spent`() {
        // A platform that keeps believing the phone is outside while fixes keep
        // placing it inside would otherwise spend a checking burst per round.
        val round = arrayOf(exit to PresenceEvent.ProbablyLeft, fix to PresenceEvent.StillHere)
        val steps = replay(*round, *round, *round, *round)

        assertEquals(
            "one re-registration per false alarm, up to the budget",
            List(FenceRearm.BUDGET) { true } + false,
            steps.filter { it.falseAlarm }.map { it.rearmNow },
        )
        assertEquals(0, steps.last().next.left)
    }

    @Test
    fun `a spent budget reports its false alarm once, not on every later still-here`() {
        val steps = replay(
            exit to PresenceEvent.ProbablyLeft,
            fix to PresenceEvent.StillHere,
            wifiBack to PresenceEvent.StillHere,
            start = FenceRearm(left = 0),
        )

        assertTrue(steps[1].falseAlarm)
        assertFalse(steps[2].falseAlarm)
    }

    @Test
    fun `a registration forgets an exit fired against the one it replaced`() {
        val pending = FenceRearm().after(exit, PresenceEvent.ProbablyLeft).next

        val step = pending.registered().after(fix, PresenceEvent.StillHere)

        assertFalse(step.rearmNow)
    }

    @Test
    fun `a restore or repair asks for an initial exit, the arm itself does not`() {
        // The arm's capture just placed the phone inside, so an exit at its
        // registration is a stale platform belief and would spend a burst.
        assertFalse(FenceRearm().initialExitOnRegistration(firstForAnchor = true))
        assertTrue(FenceRearm().initialExitOnRegistration(firstForAnchor = false))
    }

    @Test
    fun `a spent budget registers without an initial exit`() {
        assertFalse(FenceRearm(left = 0).initialExitOnRegistration(firstForAnchor = false))
    }
}
