package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyAccessChangeTest {

    @Test
    fun `losing access while snoozing ends the snooze`() {
        // The invariant this class exists for: without policy access Snoozemo
        // cannot drive its own rule, so it can neither confirm the phone is
        // silent nor bring it back. Staying armed is the failure the whole
        // design is arranged against.
        assertEquals(
            PolicyAccessAction.EndSnooze,
            PolicyAccessChange.resolve(PolicyAccess.DENIED, snoozing = true),
        )
    }

    @Test
    fun `losing access while idle does nothing`() {
        assertEquals(
            PolicyAccessAction.None,
            PolicyAccessChange.resolve(PolicyAccess.DENIED, snoozing = false),
        )
    }

    @Test
    fun `gaining access creates the rule before it is needed`() {
        // Not on the arm path: the tile tap must never be what discovers the
        // rule is missing.
        assertEquals(
            PolicyAccessAction.EnsureRule,
            PolicyAccessChange.resolve(PolicyAccess.GRANTED, snoozing = false),
        )
    }

    @Test
    fun `access granted while already snoozing still just ensures the rule`() {
        // Can happen after a revoke-and-regrant, or a grant that races the
        // broadcast. Nothing to end, and the rule may need recreating.
        assertEquals(
            PolicyAccessAction.EnsureRule,
            PolicyAccessChange.resolve(PolicyAccess.GRANTED, snoozing = true),
        )
    }
}
