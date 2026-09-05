package app.snoozemo.core

/**
 * Where a snooze record is in its life, as one value (SPEC.md §5.8).
 *
 * This replaces three independent flags — "the rule went on", "a release is in
 * flight, for this reason", "the record is retired" — that between them encoded
 * the same four states. Independent flags can disagree, and reading them meant
 * re-deriving the state at every wake-up: the app had to work out, separately in
 * each place that asked, what a live record beside an off rule meant. Review
 * finding after review finding landed in that derivation, several of them the fix
 * for the previous one reaching only one of the places (`TODO.md`).
 *
 * The states are ordered, and a record only ever moves **forward** through them
 * — [ActiveSnoozeStore.update] promotes and never demotes, and only a new arm
 * starts over at [ARMING]. That is what makes an ordinary rewrite of a live
 * record — a clock rebase, an extension, a tracking change — unable to erase a
 * release another process recorded a moment earlier, which is the shape of the
 * bug this ordering exists to make impossible rather than to remember.
 */
enum class SnoozeLifecycle {
    /**
     * The record is written; the rule is not confirmed on.
     *
     * The record is deliberately written *before* the rule (SPEC.md §4.1), so a
     * crash in that window leaves this. It is the state that distinguishes an
     * arm that never finished from a snooze the user has since un-silenced —
     * both look like a live record over an off rule, and they want opposite
     * answers: finish the arm, or end the snooze.
     */
    ARMING,

    /** The rule went on, and nothing is trying to turn it off. */
    ARMED,

    /**
     * An ending **the app decided on** is being attempted.
     *
     * Written before the zen write it describes, so a process that dies between
     * turning the rule off and erasing the record still knows the ending was
     * its own rather than the user's — the one ending that is deliberately
     * silent (§4.5). A refusal leaves this standing, because the retry pursues
     * the same ending; the user's own ending supersedes it instead, back to
     * [ARMED] being irrelevant — see [ActiveSnoozeStore.supersedeRelease].
     */
    RELEASING,

    /**
     * The snooze is over and the record is retired.
     *
     * [ActiveSnoozeStore.load] refuses a record in this state. Written only
     * after the rule is confirmed off: writing it before a zen write that can
     * still fail would strand a live snooze with Do Not Disturb on and nothing
     * left to turn it back off.
     */
    RELEASED,
    ;

    /** Whether the rule was confirmed on for this record. */
    val ruleWentOn: Boolean get() = this != ARMING
}

/**
 * A record's lifecycle together with the reason a [SnoozeLifecycle.RELEASING]
 * record is pursuing.
 *
 * One value because the two are written and read as one: a state of `RELEASING`
 * with no reason, or a reason left beside `ARMED`, are combinations nothing
 * should be able to produce.
 */
data class SnoozeRecordState(
    val lifecycle: SnoozeLifecycle,
    /** Why the app is ending this snooze, for [SnoozeLifecycle.RELEASING] only. */
    val releasingReason: EndReason? = null,
)

/**
 * **The one place that says what a live record beside a given rule state means**
 * (SPEC.md §5.8), or null to leave the snooze running.
 *
 * Every wake-up that reads the rule back asks this — the service's own restore,
 * its reconcile, and the no-service fallback — so the four causes of "live
 * record over an off rule" are enumerated once instead of being re-derived,
 * differently, in three files.
 */
fun endingFor(state: SnoozeRecordState, activation: ZenRuleActivation): EndReason? {
    // An arm that never completed is not an ending at all, **whatever the rule
    // reads as**. The record is written before the rule, so this is the window
    // a crash landed in; reading it as an ending would silently delete a snooze
    // the user asked for and was never told they did not get.
    //
    // Ahead of the activation, not inside it, because a *missing* rule here is
    // recoverable rather than lost: both callers re-assert through
    // `setSnoozed(true)`, whose `ensureRule` recreates a rule that is gone and
    // finishes the arm. Classifying it as a lost capability first would refuse
    // a snooze the user could have had (Codex, PR #201).
    if (state.lifecycle == SnoozeLifecycle.ARMING) return null

    return when (activation) {
        // Cannot tell, or nothing to conclude. "Unreadable" never ends a
        // snooze: a failed binder call that did would turn the phone back on in
        // the middle of the meeting it was silencing, and the cap bounds the
        // alternative.
        ZenRuleActivation.UNKNOWN, ZenRuleActivation.ACTIVE -> null

        // Deleted, or switched off in Settings, under a snooze that was
        // running. A capability we no longer have rather than a choice anyone
        // made about *this* snooze — and that holds whoever was releasing it,
        // so the record's own state does not enter into it. Fails open, and
        // explains itself (D7).
        ZenRuleActivation.MISSING, ZenRuleActivation.DISABLED -> EndReason.LOST_CAPABILITY

        // The rule is off and still ours. Which of the remaining causes it is
        // depends on what this record was doing, which is the whole reason the
        // state is recorded rather than inferred.
        ZenRuleActivation.INACTIVE -> when (state.lifecycle) {
            // Answered above, before the rule state was consulted.
            SnoozeLifecycle.ARMING -> null

            // Our own release turned the rule off and died before erasing the
            // record. The reason it recorded is the true one; falling back to
            // the user is what §4.5 makes silent, so a cap or a departure
            // would vanish.
            SnoozeLifecycle.RELEASING -> state.releasingReason ?: EndReason.DND_TURNED_OFF

            // Nothing of ours was releasing, so the switch was reached by the
            // user. Their action, so it reads as one (§5.4) and says nothing
            // they do not already know. `load` refuses a released record, so
            // nothing should be holding one to ask about; ending it again is
            // the harmless answer if anything does.
            SnoozeLifecycle.ARMED, SnoozeLifecycle.RELEASED -> EndReason.DND_TURNED_OFF
        }
    }
}
