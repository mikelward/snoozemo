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
    fun `a rule we have already replaced is not ours when no snooze names it`() {
        // Nothing running, so there is no snooze whose rule it could be, and
        // ownership is not guessed backwards from a value that moves.
        assertFalse(RuleOwnership.isOurs("rule-1", current = "rule-2"))
    }

    @Test
    fun `the rule a running snooze was armed with is ours after a replacement`() {
        // The gap this closes (Codex, PR #36): the user deletes the rule, the
        // tile mints a replacement, and the original's REMOVED broadcast then
        // names an id the app no longer holds. The snooze it was enforcing
        // recorded it, so it is still ours — and the snooze ends as a lost
        // capability instead of running on, unenforced, to its cap.
        assertTrue(RuleOwnership.isOurs("rule-1", current = "rule-2", enforcing = "rule-1"))
    }

    @Test
    fun `the replacement is not the running snooze's rule`() {
        // The other direction: a change to the rule the app holds now is not
        // about a snooze armed on a different one.
        assertFalse(RuleOwnership.isOurs("rule-2", current = "rule-2", enforcing = "rule-1"))
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
