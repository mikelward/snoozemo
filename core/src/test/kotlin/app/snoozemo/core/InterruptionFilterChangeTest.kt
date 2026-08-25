package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether a filter change ends a snooze. Written
 * against the *filter*, never against our own rule's state, so these cases
 * hold under every answer to the unresolved platform question about what
 * turning Do Not Disturb off does to an app-owned rule (TODO.md, hardware
 * item 6).
 */
class InterruptionFilterChangeTest {

    @Test
    fun `the phone going audible mid-snooze ends it`() {
        assertEquals(
            InterruptionFilterAction.EndSnooze,
            InterruptionFilterChange.resolve(audible = true, state = SnoozeState.ARMED),
        )
    }

    @Test
    fun `it ends a snooze that is mid-check too`() {
        // CHECKING is still a snooze with the rule on; a departure test in
        // flight is no reason to keep claiming a phone is quiet when it rings.
        assertEquals(
            InterruptionFilterAction.EndSnooze,
            InterruptionFilterChange.resolve(audible = true, state = SnoozeState.CHECKING),
        )
    }

    @Test
    fun `a still-quiet phone changes nothing`() {
        // Another app's rule ending, or our own filter tightening: something
        // is still being held back, so the snooze is doing its job.
        for (state in SnoozeState.entries) {
            assertEquals(
                "state=$state",
                InterruptionFilterAction.None,
                InterruptionFilterChange.resolve(audible = false, state = state),
            )
        }
    }

    @Test
    fun `arming is not ended by its own starting filter`() {
        // The phone is still audible between the tile tap and our rule taking
        // effect. Acting there would let every snooze end itself on the way up.
        assertEquals(
            InterruptionFilterAction.None,
            InterruptionFilterChange.resolve(audible = true, state = SnoozeState.ARMING),
        )
    }

    @Test
    fun `an audible phone with no snooze running is nothing to do`() {
        assertEquals(
            InterruptionFilterAction.None,
            InterruptionFilterChange.resolve(audible = true, state = SnoozeState.IDLE),
        )
        assertEquals(
            InterruptionFilterAction.None,
            InterruptionFilterChange.resolve(audible = true, state = SnoozeState.RELEASED),
        )
    }
}
