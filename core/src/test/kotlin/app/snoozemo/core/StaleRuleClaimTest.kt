package app.snoozemo.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleRuleClaimTest {

    @Test
    fun `a usable rule refutes the standing claim`() {
        assertTrue(StaleRuleClaim.refutedBy(ZenRuleState.READY))
    }

    @Test
    fun `missing access observes nothing about the rule, so the claim stands`() {
        // Revoking access does not say the rule was re-enabled; retiring the
        // message here would clear a claim that may still be true.
        assertFalse(StaleRuleClaim.refutedBy(ZenRuleState.MISSING_ACCESS))
    }

    @Test
    fun `disabled and failed replace the message rather than retiring it`() {
        assertFalse(StaleRuleClaim.refutedBy(ZenRuleState.DISABLED))
        assertFalse(StaleRuleClaim.refutedBy(ZenRuleState.FAILED))
    }
}
