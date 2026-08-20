package app.snoozemo.core

/**
 * What the platform says has happened to **Snoozemo's own** zen rule, reported
 * through `ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED` (SPEC.md §5.7).
 *
 * A named enum rather than the platform's ints so the decision below is a plain
 * JVM test — and so the one status that matters most is impossible to confuse
 * with the one that reads like it.
 */
enum class ZenRuleStatus {
    /** Turned on by the user or by cross-device sync, not by us. */
    ACTIVATED,

    /**
     * **Turned off by the user** — the shade's Do Not Disturb toggle, or the
     * Modes UI.
     *
     * The platform calls this "snoozed", which here means the opposite of what
     * this app means by it: our snooze is over.
     */
    DEACTIVATED,

    /** The rule still exists but is switched off, so state changes are pointless. */
    DISABLED,

    /** The rule was deleted. Further state changes are ignored by the platform. */
    REMOVED,

    /** Anything this version doesn't recognize. Keep reporting state as normal. */
    UNKNOWN,
}

/**
 * Whether Snoozemo's rule is on, read back from the platform (SPEC.md §5.7).
 *
 * Four values and not a Boolean, because the three ways of *not* being active
 * demand different answers and collapsing them loses the difference: the
 * platform reports an unknown state as readily as a real one, and a rule that
 * has been deleted is a lost capability rather than a user preference (Codex,
 * PR #36).
 */
enum class ZenRuleActivation {
    /** On, and enforcing. Nothing to do. */
    ACTIVE,

    /** Off. Someone turned it off, and it was not us. */
    INACTIVE,

    /** The rule is gone. Nothing can be enforced and nothing can be set. */
    MISSING,

    /**
     * The rule still exists but is switched off, so state changes are pointless.
     *
     * Separate from [MISSING] to match [ZenRuleStatus.DISABLED], and separate
     * from [INACTIVE] because a disabled rule also reports `STATE_FALSE` — the
     * condition alone cannot tell "the user turned Do Not Disturb off" from
     * "this rule can no longer enforce anything" (Codex, PR #36).
     */
    DISABLED,

    /**
     * Unreadable, or a state this version doesn't recognize.
     *
     * **Never treated as off.** A failed binder call that ended a snooze would
     * turn the phone back on in the middle of the meeting it was silencing, so
     * "cannot tell" resolves to leaving things alone — and the duration cap is
     * what stops that being unbounded.
     */
    UNKNOWN,
}

/**
 * How a snooze should end given what the rule turned out to be doing, or null
 * to leave it running.
 */
fun ZenRuleActivation.endReason(): EndReason? = when (this) {
    // The user reached the platform's own switch. Their action, so it reads as
    // one (SPEC.md §5.4) and says nothing they don't already know.
    ZenRuleActivation.INACTIVE -> EndReason.DND_TURNED_OFF
    // Deleted out from under us: further state changes are ignored, so this is
    // a capability we no longer have rather than a choice anyone made. Failing
    // open, and explaining itself (D7).
    // Both are capabilities we no longer have rather than choices anyone made
    // about this snooze, which is the same call the broadcast path makes for
    // `REMOVED` and `DISABLED`. The two paths have to agree: they describe the
    // same platform state, one promptly and one late.
    ZenRuleActivation.MISSING, ZenRuleActivation.DISABLED -> EndReason.LOST_CAPABILITY
    ZenRuleActivation.ACTIVE, ZenRuleActivation.UNKNOWN -> null
}

/** What to do about it. */
sealed interface ZenRuleStatusAction {

    /** The snooze cannot continue, and this is why (SPEC.md §4.5). */
    data class EndSnooze(val reason: EndReason) : ZenRuleStatusAction

    /** Nothing is running, but the rule has to exist before the next tap. */
    data object RecreateRule : ZenRuleStatusAction

    data object None : ZenRuleStatusAction
}

/**
 * Turns a status change into a decision (SPEC.md §5.7).
 *
 * Split out from the receiver because the interesting cases are exactly the ones
 * a device test reaches last: another app's rule changing, a status arriving
 * while nothing is running, and the two failure modes that look alike and are
 * not.
 */
object ZenRuleStatusChange {

    /**
     * @param ours whether the changed rule is Snoozemo's. **The first question,
     *   always**: §5.6 says Snoozemo touches only its own rule, and reacting to
     *   another app's — or to a user schedule's — would end a snooze for
     *   something that has nothing to do with us.
     * @param snoozing whether a snooze is currently running.
     */
    fun resolve(status: ZenRuleStatus, ours: Boolean, snoozing: Boolean): ZenRuleStatusAction {
        if (!ours) return ZenRuleStatusAction.None

        return when (status) {
            // The user turned Do Not Disturb off with a snooze running. They
            // meant it, so it ends like any other manual ending — but it must
            // *be* an ending rather than drift: the record, the tile and the
            // notification would otherwise go on claiming a snooze the platform
            // has already stopped enforcing.
            //
            // Ending is also what unsticks the rule. A deactivated rule stays
            // deactivated until the owner sets it back to `STATE_FALSE`, so a
            // snooze left running here would leave the *next* tap turning on a
            // rule the platform ignores — the tile saying `Snoozing` over a
            // phone that still rings. That is principle 1's failure, reached by
            // doing nothing.
            ZenRuleStatus.DEACTIVATED ->
                if (snoozing) ZenRuleStatusAction.EndSnooze(EndReason.DND_TURNED_OFF)
                else ZenRuleStatusAction.None

            // The rule is gone and further state changes are ignored, so a
            // running snooze has nothing enforcing it and no way to end itself
            // cleanly — fail open and say so (D7).
            ZenRuleStatus.REMOVED ->
                if (snoozing) ZenRuleStatusAction.EndSnooze(EndReason.LOST_CAPABILITY)
                else ZenRuleStatusAction.RecreateRule

            // Exists but switched off. Same consequence for a running snooze,
            // and deliberately *not* recreated when idle: the user disabled it
            // on purpose, and re-enabling it behind their back would be the app
            // arguing with them. `ensureRule` finds it at the next arm.
            ZenRuleStatus.DISABLED ->
                if (snoozing) ZenRuleStatusAction.EndSnooze(EndReason.LOST_CAPABILITY)
                else ZenRuleStatusAction.None

            // Someone turned our rule on without us. Out of scope by decision:
            // at worst the tile reads "not snoozing" beside a quiet phone, and
            // adopting a snooze the user never asked us for would be worse.
            ZenRuleStatus.ACTIVATED -> ZenRuleStatusAction.None

            // Unrecognized on this version. Carry on reporting state normally,
            // which is what the platform asks for.
            ZenRuleStatus.UNKNOWN -> ZenRuleStatusAction.None
        }
    }
}
