package app.snoozemo.presence.geofence

import app.snoozemo.core.Departure
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckingCadenceTest {

    @Test
    fun `the spacing is the confirmation gap itself, not a copy of its value`() {
        // The two were a hard-coded `30_000L` here and a `Duration.ofSeconds(30)`
        // in `:core`, so shortening the gap the engine accepts fixes across
        // would have left the burst asking at the old rate — a latency win that
        // never happens (`TODO.md`). Asserted against `Departure` rather than
        // against 30_000 so a change to the gap moves this test's expectation
        // with it instead of failing it.
        assertEquals(Departure.CONFIRMATION_GAP.toMillis(), CheckingCadence().nextDelayMs)
    }

    @Test
    fun `a burst built with a different gap paces at that gap`() {
        val gapMs = 12_000L
        val cadence = CheckingCadence(gapMs)

        assertEquals(gapMs, cadence.nextDelayMs)

        // And the backoff is unaffected by it: the battery bound is about a
        // provider answering nothing, not about how close together two
        // qualifying fixes have to be.
        repeat(CheckingCadence.BACKOFF_AFTER) { cadence.onNothing() }
        assertEquals(CheckingCadence.BACKOFF_SPACING_MS, cadence.nextDelayMs)

        cadence.onFixDelivered()
        assertEquals(gapMs, cadence.nextDelayMs)
    }

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
    fun `a platform recovery forgives a backoff the outage earned`() {
        // Location switched back on: the reason the provider was answering
        // nothing is provably over, so serving out five more minutes of
        // backoff would leave the snooze reporting degraded tracking long
        // after the outage ended.
        val cadence = CheckingCadence()
        repeat(CheckingCadence.BACKOFF_AFTER) { cadence.onNothing() }

        cadence.onPlatformRecovered()

        assertEquals(CheckingCadence.CONFIRM_SPACING_MS, cadence.nextDelayMs)
    }

    @Test
    fun `the bound still holds if the provider goes on failing after a recovery`() {
        val cadence = CheckingCadence()
        repeat(CheckingCadence.BACKOFF_AFTER) { cadence.onNothing() }
        cadence.onPlatformRecovered()

        repeat(CheckingCadence.BACKOFF_AFTER) { cadence.onNothing() }

        assertEquals(CheckingCadence.BACKOFF_SPACING_MS, cadence.nextDelayMs)
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
