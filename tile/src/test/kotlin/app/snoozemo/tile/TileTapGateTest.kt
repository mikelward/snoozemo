package app.snoozemo.tile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile's quick-second-tap rule (SPEC.md §7), on an injected clock. */
class TileTapGateTest {

    @Test
    fun `the first tap acts`() {
        assertTrue(TileTapGate().accept(nowMs = 10_000L))
    }

    @Test
    fun `a second tap inside the window is ignored`() {
        // The capture's shape: arm, then a tap meant as part of it ending the
        // snooze moments later.
        val gate = TileTapGate()
        gate.accept(10_000L)

        assertFalse(gate.accept(10_000L + 250L))
        assertFalse(gate.accept(10_000L + TileTapGate.SETTLE_MS - 1))
    }

    @Test
    fun `a tap after the window acts`() {
        val gate = TileTapGate()
        gate.accept(10_000L)

        assertTrue(gate.accept(10_000L + TileTapGate.SETTLE_MS))
    }

    @Test
    fun `ignored taps do not hold the window open`() {
        // Measured from the last tap that acted, so tapping repeatedly can
        // never leave the tile inert.
        val gate = TileTapGate()
        gate.accept(10_000L)
        gate.accept(10_000L + TileTapGate.SETTLE_MS / 2)
        gate.accept(10_000L + TileTapGate.SETTLE_MS - 1)

        assertTrue(gate.accept(10_000L + TileTapGate.SETTLE_MS))
    }

    @Test
    fun `a clock that reads earlier than the last tap acts`() {
        // Elapsed realtime does not go backwards within a boot, but a stored
        // reading must never be what keeps the tile from acting.
        val gate = TileTapGate()
        gate.accept(10_000L)

        assertTrue(gate.accept(9_000L))
    }
}
