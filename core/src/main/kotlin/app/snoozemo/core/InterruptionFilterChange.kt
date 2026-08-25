package app.snoozemo.core

/**
 * What to do when the system's interruption filter changes underneath a
 * running snooze.
 *
 * §5.6 covers the *pre-existing* Do Not Disturb case at arm time; this is the
 * state changing while a snooze is already running — in practice, the user
 * reaching into the shade and turning Do Not Disturb off. The snooze is over
 * at that moment whether or not the platform deactivated Snoozemo's own rule:
 * the phone is audible, which is the only thing the snooze was ever for. Left
 * alone, the record, the tile and the ongoing notification would all go on
 * saying a snooze is running while the phone rings — state drift in the
 * direction that makes the app look broken, and drift the user caused
 * deliberately and expects to be noticed.
 *
 * **Deliberately written against the filter, not against our rule's state.**
 * Whether turning Do Not Disturb off deactivates an app-owned
 * `AutomaticZenRule`, leaves it active-but-overridden, or something else again
 * is an unanswered platform question (TODO.md, hardware item 6). Reading the
 * filter sidesteps it: audible means the snooze is not silencing anything,
 * under every one of those answers. It is also the fail-open direction
 * principle 1 asks for — the mistake this can make is ending a snooze early,
 * never leaving a phone quiet with nothing to release it.
 *
 * The two neighboring cases are deliberately *not* handled here: Do Not
 * Disturb turned on while Snoozemo is idle (harmless — at most the tile reads
 * "not snoozing" beside a quiet phone), and another app's rule ending while
 * ours is on (the filter is still not audible, so there is nothing to do).
 *
 * The platform delivers this via `ACTION_INTERRUPTION_FILTER_CHANGED`, which
 * fires for every change in both directions and says only that *something*
 * changed — so the caller reads the current filter and passes it here rather
 * than inferring a direction, exactly as [PolicyAccessChange] does.
 */
object InterruptionFilterChange {

    /**
     * @param audible whether the phone is currently interrupting normally —
     *   the platform's "no Do Not Disturb at all" filter.
     * @param state where the controller believes the snooze is.
     */
    fun resolve(audible: Boolean, state: SnoozeState): InterruptionFilterAction = when {
        !audible -> InterruptionFilterAction.None
        // ARMED and CHECKING are the states in which the rule is on and the
        // phone is supposed to be quiet. ARMING is deliberately excluded: the
        // filter is still audible until our own rule takes effect, so acting
        // there would let a snooze end itself between the tile tap and the
        // rule landing — the arm path's own transient read as a user turning
        // it off.
        state == SnoozeState.ARMED || state == SnoozeState.CHECKING ->
            InterruptionFilterAction.EndSnooze
        else -> InterruptionFilterAction.None
    }
}

/** The one action an interruption-filter change can call for. */
sealed interface InterruptionFilterAction {

    /**
     * End the running snooze, with [EndReason.DND_TURNED_OFF]. An ordinary
     * end: nothing to warn about, nothing to retry, and no notification —
     * the user just made the phone audible and can hear the result.
     */
    data object EndSnooze : InterruptionFilterAction

    /** Nothing Snoozemo needs to do. */
    data object None : InterruptionFilterAction
}
