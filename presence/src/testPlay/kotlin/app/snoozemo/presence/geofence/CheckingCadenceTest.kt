package app.snoozemo.presence.geofence

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckingCadenceTest {

    @Test
    fun `confirming fixes come at the confirmation gap`() {
        // Two qualifying fixes at least 30 s apart is the whole confirmation
        // rule; one request per gap satisfies it by construction.
        assertEquals(CheckingCadence.CONFIRM_SPACING_MS, CheckingCadence().nextDelayMs)
    }

    @Test
    fun `a provider answering nothing gets backed off, not hammered`() {
        // The battery bound (SPEC.md §9): the engine has called tracking
        // degraded by the same threshold, so full rate buys nothing.
        val cadence = CheckingCadence()

        repeat(CheckingCadence.BACKOFF_AFTER - 1) { cadence.onNothing() }
        assertEquals(CheckingCadence.CONFIRM_SPACING_MS, cadence.nextDelayMs)

        cadence.onNothing()
        assertEquals(CheckingCadence.BACKOFF_SPACING_MS, cadence.nextDelayMs)
    }

    @Test
    fun `one delivered fix restores the confirmation rate`() {
        val cadence = CheckingCadence()
        repeat(CheckingCadence.BACKOFF_AFTER) { cadence.onNothing() }

        cadence.onFixDelivered()

        assertEquals(CheckingCadence.CONFIRM_SPACING_MS, cadence.nextDelayMs)
    }

    @Test
    fun `the backoff threshold matches the engine's degradation threshold`() {
        // Backing off earlier would slow the very fixes the engine still
        // counts toward its own verdict; later would pay full rate after the
        // user has already been told tracking degraded.
        assertEquals(
            app.snoozemo.core.Presence.DEGRADED_AFTER_USELESS_OBSERVATIONS,
            CheckingCadence.BACKOFF_AFTER,
        )
    }
}
