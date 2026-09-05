package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The four causes of "live record over an off rule" (SPEC.md §5.8), which this
 * function exists to enumerate in one place rather than have three wake-up
 * paths re-derive separately — the drift that produced ten review findings.
 */
class EndingForTest {

    private fun state(lifecycle: SnoozeLifecycle, reason: EndReason? = null) =
        SnoozeRecordState(lifecycle, reason)

    @Test
    fun `a rule still enforcing ends nothing`() {
        for (lifecycle in SnoozeLifecycle.entries) {
            assertNull(
                "an active rule is the snooze working, whatever the record says",
                endingFor(state(lifecycle), ZenRuleActivation.ACTIVE),
            )
        }
    }

    @Test
    fun `an unreadable rule ends nothing`() {
        // A failed binder call that ended a snooze would turn the phone back on
        // in the middle of the meeting it was silencing; the cap bounds the
        // alternative.
        for (lifecycle in SnoozeLifecycle.entries) {
            assertNull(endingFor(state(lifecycle), ZenRuleActivation.UNKNOWN))
        }
    }

    @Test
    fun `an arm that never finished is not an ending`() {
        // Cause one. The record is written before the rule, so this is the shape
        // a crash in that window leaves — and it looks identical to the user
        // switching Do Not Disturb off. Reading it as an ending would delete a
        // snooze the user asked for and was never told they did not get.
        assertNull(endingFor(state(SnoozeLifecycle.ARMING), ZenRuleActivation.INACTIVE))
    }

    @Test
    fun `an off rule under a release of ours keeps that release's reason`() {
        // Cause two: our own release got the rule off and died before erasing
        // the record. Falling back to the user would make a cap or a departure
        // silent, since the user's ending is the one with no notification.
        assertEquals(
            EndReason.DURATION_CAP,
            endingFor(state(SnoozeLifecycle.RELEASING, EndReason.DURATION_CAP), ZenRuleActivation.INACTIVE),
        )
        assertEquals(
            EndReason.DEPARTURE,
            endingFor(state(SnoozeLifecycle.RELEASING, EndReason.DEPARTURE), ZenRuleActivation.INACTIVE),
        )
    }

    @Test
    fun `a releasing record with no reason left falls back to the user`() {
        // A downgrade can leave a reason this build does not know. The user's
        // ending is the honest fallback: silent, and attributed to nobody who
        // did not act.
        assertEquals(
            EndReason.DND_TURNED_OFF,
            endingFor(state(SnoozeLifecycle.RELEASING), ZenRuleActivation.INACTIVE),
        )
    }

    @Test
    fun `an off rule under a running snooze is the user's doing`() {
        // Cause three. Nothing of ours was releasing, so the switch was reached
        // by the user — their action, and it says nothing they don't know.
        assertEquals(
            EndReason.DND_TURNED_OFF,
            endingFor(state(SnoozeLifecycle.ARMED), ZenRuleActivation.INACTIVE),
        )
    }

    @Test
    fun `a rule that is gone or switched off is a lost capability whoever was releasing`() {
        // Cause four. Under a snooze that was actually running it does not
        // consult the record: a deleted or disabled rule is a capability nobody
        // chose to lose, so the same answer holds however it was being ended.
        // That is what keeps this apart from cause two, where the record is the
        // only thing that knows.
        for (lifecycle in SnoozeLifecycle.entries - SnoozeLifecycle.ARMING) {
            for (activation in listOf(ZenRuleActivation.MISSING, ZenRuleActivation.DISABLED)) {
                assertEquals(
                    "$lifecycle beside $activation",
                    EndReason.LOST_CAPABILITY,
                    endingFor(state(lifecycle, EndReason.DURATION_CAP), activation),
                )
            }
        }
    }

    @Test
    fun `an interrupted arm is finished rather than refused, whatever the rule reads as`() {
        // The arm window is answered before the rule state is consulted, and a
        // missing rule is the case that makes the ordering matter: both callers
        // re-assert through `setSnoozed(true)`, which recreates a rule that is
        // gone. Reading it as a lost capability would refuse a snooze the user
        // asked for and could have had (Codex, PR #201).
        for (activation in ZenRuleActivation.entries) {
            assertNull(
                "an unfinished arm beside $activation",
                endingFor(state(SnoozeLifecycle.ARMING), activation),
            )
        }
    }

    @Test
    fun `the states are ordered so a record only moves forward`() {
        // The ordering is what `ActiveSnoozeStore.update` promotes along, so an
        // ordinary rewrite of a live record cannot erase a release another
        // process recorded a moment earlier.
        assertEquals(
            listOf(
                SnoozeLifecycle.ARMING,
                SnoozeLifecycle.ARMED,
                SnoozeLifecycle.RELEASING,
                SnoozeLifecycle.RELEASED,
            ),
            SnoozeLifecycle.entries.sorted(),
        )
    }

    @Test
    fun `only arming says the rule never went on`() {
        assertEquals(false, SnoozeLifecycle.ARMING.ruleWentOn)
        for (lifecycle in SnoozeLifecycle.entries - SnoozeLifecycle.ARMING) {
            assertEquals("$lifecycle", true, lifecycle.ruleWentOn)
        }
    }
}
