package app.snoozemo.core

/**
 * Whether a zen rule the platform is reporting about is Snoozemo's own.
 *
 * The gate on every rule-status broadcast, because the platform reports
 * changes to *all* rules and §5.6's "only its own rule" governs reading as
 * much as writing — another app's mode ending, or a user schedule's, must
 * never end a Snoozemo snooze.
 *
 * Pure so the one rule that matters is testable without a device, and that
 * rule is deliberately narrow: **only the id Snoozemo holds now is ours.**
 *
 * That leaves a known gap, recorded rather than papered over (TODO.md, "Tie a
 * snooze to the rule that was enforcing it"). `ensureRule` replaces an id whose
 * rule has gone missing, and the tile calls it every time the shade opens, so a
 * `REMOVED` broadcast can name the id that replacement just dropped — and is
 * then read as somebody else's. A short-lived memory of the displaced id was
 * tried here and withdrawn: it produced a fresh defect on each of three review
 * rounds, because ownership inferred from a value that moves needs a new guard
 * for every way it can move (Codex, PR #36). The recorded fix records the
 * enforcing rule on the snooze itself, which removes the inference rather than
 * guarding it.
 */
object RuleOwnership {

    /**
     * @param changed the rule id the platform is reporting about.
     * @param current the id Snoozemo holds now.
     *
     * Answers **false whenever it cannot tell** — a missing or unreadable id
     * is no evidence the rule is ours, and the safe reading of that is to
     * leave a running snooze alone rather than end it on a guess.
     */
    fun isOurs(changed: String?, current: String?): Boolean {
        if (changed.isNullOrEmpty()) return false
        return changed == current
    }
}
