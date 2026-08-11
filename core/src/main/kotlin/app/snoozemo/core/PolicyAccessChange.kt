package app.snoozemo.core

/**
 * What to do when notification-policy access changes while the app is running.
 *
 * Pure, so the rule that matters most here is testable without a device: **losing
 * access mid-snooze ends the snooze** (SPEC.md §8.2, D7). Snoozemo can no longer
 * drive its own rule, so it cannot know whether the phone is still silent and
 * cannot turn it back on later — staying armed would be the failure the whole
 * design is arranged against, a phone that stays quiet with nothing left to
 * release it.
 *
 * The platform delivers this via
 * `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED`, which fires for grant and
 * revocation alike and says only that *something* changed — so the caller reads
 * the current state and passes it here rather than inferring a direction.
 */
object PolicyAccessChange {

    fun resolve(access: PolicyAccess, snoozing: Boolean): PolicyAccessAction = when {
        access == PolicyAccess.DENIED && snoozing -> PolicyAccessAction.EndSnooze
        access == PolicyAccess.DENIED -> PolicyAccessAction.None
        // Granted: create the rule if onboarding never got the chance to. This is
        // the "do the work ahead of time" half — the rule must exist before a tile
        // tap needs it, not be created on the arm path.
        else -> PolicyAccessAction.EnsureRule
    }
}

/** The one action a policy-access change can call for. */
sealed interface PolicyAccessAction {

    /**
     * End the running snooze, with [EndReason.LOST_CAPABILITY] and a reason the
     * user can read. Never "keep going and hope".
     */
    data object EndSnooze : PolicyAccessAction

    /** Access arrived; make sure the long-lived rule exists (SPEC.md §5.3). */
    data object EnsureRule : PolicyAccessAction

    /** Access is gone but nothing was running. Nothing to do. */
    data object None : PolicyAccessAction
}
