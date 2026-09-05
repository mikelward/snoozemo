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
 * rule is deliberately narrow: **the rule a running snooze was armed with is
 * ours, and otherwise only the id Snoozemo holds now.**
 *
 * The two differ when the user deletes the rule and the tile's next shade-open
 * `ensureRule` mints a replacement. Judged against the current id alone, the
 * original's `REMOVED` broadcast names an id the app no longer holds and is
 * read as somebody else's — so the snooze it was enforcing runs on, unenforced,
 * to its cap. A short-lived memory of the displaced id was tried here and
 * withdrawn: it produced a fresh defect on each of three review rounds, because
 * ownership inferred from a value that moves needs a new guard for every way
 * it can move (Codex, PR #36). The snooze records its enforcing rule instead
 * ([ActiveSnooze.ruleId]), which removes the inference rather than guarding it.
 */
object RuleOwnership {

    /**
     * @param changed the rule id the platform is reporting about.
     * @param current the id Snoozemo holds now.
     * @param enforcing the id the running snooze was armed with, when there is
     *   a running snooze and its record names one. Takes precedence over
     *   [current]: a status change is about *this* snooze's rule or it is not
     *   about this snooze at all.
     *
     * Answers **false whenever it cannot tell** — a missing or unreadable id
     * is no evidence the rule is ours, and the safe reading of that is to
     * leave a running snooze alone rather than end it on a guess.
     */
    fun isOurs(changed: String?, current: String?, enforcing: String? = null): Boolean {
        if (changed.isNullOrEmpty()) return false
        return changed == (enforcing ?: current)
    }
}
