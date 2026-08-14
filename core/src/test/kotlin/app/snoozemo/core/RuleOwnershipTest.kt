package app.snoozemo.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps another app's rule from ending a Snoozemo snooze.
 */
class RuleOwnershipTest {

    @Test
    fun `the rule we hold now is ours`() {
        assertTrue(RuleOwnership.isOurs("rule-1", current = "rule-1"))
    }

    @Test
    fun `another app's rule is never ours`() {
        assertFalse(RuleOwnership.isOurs("someone-else", current = "rule-1"))
    }

    @Test
    fun `a rule we have already replaced is not ours`() {
        // Deliberate, and the reason is recorded in TODO.md: ownership is not
        // inferred from what the app held a moment ago. A REMOVED broadcast
        // racing a replacement is therefore missed, which is a known gap whose
        // fix is to record the enforcing rule on the snooze — not to guess
        // backwards from a value that moves.
        assertFalse(RuleOwnership.isOurs("rule-1", current = "rule-2"))
    }

    @Test
    fun `nothing is ours when we hold no id at all`() {
        assertFalse(RuleOwnership.isOurs("rule-1", current = null))
    }

    @Test
    fun `a nameless change is never ours`() {
        // "Cannot tell" resolves to leaving a running snooze alone, never to
        // ending one on a guess.
        assertFalse(RuleOwnership.isOurs(null, current = "rule-1"))
        assertFalse(RuleOwnership.isOurs("", current = "rule-1"))
    }
}
