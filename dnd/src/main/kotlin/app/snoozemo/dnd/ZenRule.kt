package app.snoozemo.dnd

/**
 * Identity of Snoozemo's one long-lived [android.app.AutomaticZenRule].
 *
 * One rule is created at first successful onboarding and reused for every
 * snooze, never one rule per snooze: rules are user-visible objects in Settings,
 * and churning them would litter the user's DND screen (SPEC.md §5.3). The
 * manager that creates it and drives its state lands in Phase 1.
 */
object ZenRule {

    /** Stable app-owned condition URI, persisted with the rule id. */
    const val CONDITION_ID: String = "snoozemo://snooze"

    /** The name the rule appears under in Settings and the Modes UI. */
    const val NAME: String = "Snoozemo"
}
