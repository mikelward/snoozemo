package app.snoozemo.core

/**
 * Whether a fresh rule check refutes a rule-status message still on screen.
 *
 * `ensureRule` can answer [ZenRuleState.READY] long after an earlier check said
 * the rule was disabled or uncreatable — the user flips the switch back on in
 * Settings, or the platform recovers — and the screen used to keep the stale
 * claim standing beside a rule that demonstrably works (deferred from Codex's
 * PR #8 review). Only READY retires it: that answer *observed* a usable rule,
 * which is exactly what the message denies. [ZenRuleState.MISSING_ACCESS]
 * observes nothing about the rule — access is gone, and the rule may well still
 * be disabled behind it — so the claim stands until an answer that actually
 * contradicts it. DISABLED and FAILED don't retire the message either; they
 * *replace* it with the current one.
 *
 * Narrow on purpose: the message slot this guards is shared with arm/end
 * failures, which a rule check knows nothing about, so the caller retires only
 * a rule-status message when this says so — never the slot wholesale.
 */
object StaleRuleClaim {

    fun refutedBy(state: ZenRuleState): Boolean = state == ZenRuleState.READY
}
