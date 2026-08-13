package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transferred-snooze guard (SPEC.md §12).
 *
 * The failure this is arranged against is a snooze armed on an old phone
 * silencing a new one at its first boot, so the tests are written around which
 * combinations may restore rather than around the enum's shape.
 */
class RecordOriginTest {

    private val thisDevice = "a".repeat(64)
    private val otherDevice = "b".repeat(64)

    @Test
    fun `a record this device wrote restores`() {
        val origin = RecordOrigin.of(recorded = thisDevice, current = thisDevice)
        assertEquals(RecordOrigin.THIS_DEVICE, origin)
        assertTrue(origin.mayRestore)
    }

    @Test
    fun `a record from another device never restores`() {
        val origin = RecordOrigin.of(recorded = otherDevice, current = thisDevice)
        assertEquals(RecordOrigin.ANOTHER_DEVICE, origin)
        assertFalse("a transferred snooze must not silence this phone", origin.mayRestore)
    }

    @Test
    fun `an unstamped record does not restore on a device that stamps`() {
        // Either a build from before stamping existed or a transfer from a
        // handset that could not stamp. Indistinguishable, and only one is
        // safe, so the safe reading wins.
        val origin = RecordOrigin.of(recorded = null, current = thisDevice)
        assertEquals(RecordOrigin.UNATTRIBUTED, origin)
        assertFalse(origin.mayRestore)
    }

    @Test
    fun `a device with no stamp of its own still runs its own snoozes`() {
        // The important asymmetry. Refusing here would end every snooze on such
        // a handset at the next process death — failing closed, not safe.
        val origin = RecordOrigin.of(recorded = null, current = null)
        assertEquals(RecordOrigin.UNVERIFIABLE, origin)
        assertTrue("a device that cannot check must not refuse everything", origin.mayRestore)
        assertTrue("and must say the check is not running", origin.isDegraded)
    }

    @Test
    fun `a stamped record on a device that cannot stamp is refused`() {
        // Codex, PR #26. This asserted the opposite until the reasoning was
        // checked: a device with no identity cannot have written a *stamped*
        // record, so the stamp is evidence something else did. Reading it as
        // the device's limitation disabled the backstop on exactly the phone
        // least able to detect a transfer.
        val origin = RecordOrigin.of(recorded = thisDevice, current = null)
        assertEquals(RecordOrigin.UNCONFIRMABLE, origin)
        assertFalse(
            "an unstampable phone must not restore a record it cannot have written",
            origin.mayRestore,
        )
    }

    @Test
    fun `a blank stamp is absent, not a match`() {
        // Two half-failed writes must not compare equal — that would turn the
        // most suspicious record on disk into the one that passes.
        assertEquals(RecordOrigin.UNVERIFIABLE, RecordOrigin.of(recorded = "", current = ""))
        assertEquals(RecordOrigin.UNATTRIBUTED, RecordOrigin.of(recorded = "  ", current = thisDevice))
        assertEquals(RecordOrigin.UNCONFIRMABLE, RecordOrigin.of(recorded = thisDevice, current = " "))
    }

    @Test
    fun `an unstampable device restores only a record that is also unstamped`() {
        // The pair that has to stay distinct: same missing device stamp, and
        // the record's own stamp is what separates "consistent" from
        // "impossible".
        assertTrue(RecordOrigin.of(recorded = null, current = null).mayRestore)
        assertFalse(RecordOrigin.of(recorded = thisDevice, current = null).mayRestore)
    }

    @Test
    fun `only a positive match or an unverifiable device restores`() {
        // Guards the enum against a later value defaulting into "restorable".
        val restorable = RecordOrigin.entries.filter { it.mayRestore }.toSet()
        assertEquals(setOf(RecordOrigin.THIS_DEVICE, RecordOrigin.UNVERIFIABLE), restorable)
    }
}
