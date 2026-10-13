package app.snoozemo.core

/**
 * When to register the geofence again, so a departure the platform has already
 * counted can still fire (SPEC.md §6.10).
 *
 * The fence fires on an inside-to-outside crossing. Once an exit has fired,
 * Play Services believes the phone is outside, and a later real departure
 * crosses nothing. So an exit the departure test rejects — a false alarm —
 * leaves the fence spent for the rest of the snooze unless it is registered
 * again with an initial exit trigger, which fires at once if the platform
 * still believes the phone is outside (field log, 2026-10-05: a snooze that
 * outlived a 300 m walk; maintainer, 2026-10-13).
 *
 * **Bounded, because a re-fire costs a checking burst.** A platform that keeps
 * believing the phone is outside while fixes keep placing it inside would
 * otherwise spend one burst per round for the whole snooze. After [BUDGET]
 * false alarms the fence is left as it is, and the motion trigger and the
 * backstop are what catch a departure. [left] is per snooze, so the monitor
 * persists it across process death.
 *
 * A value, like [PresenceState], so a JVM test can replay a sequence of
 * signals through it.
 */
data class FenceRearm(
    /** False alarms this snooze may still answer with a re-registration. */
    val left: Int = BUDGET,
    /** Whether an exit has fired since the fence was last registered and not been answered yet. */
    val exitPending: Boolean = false,
) {

    /**
     * The next value; whether this delivery was a false alarm; and whether to
     * re-register the fence now, which a false alarm does while budget lasts.
     */
    data class Step(val next: FenceRearm, val falseAlarm: Boolean, val rearmNow: Boolean)

    /**
     * Advances on one delivered [signal] and the [event] the engine answered
     * it with.
     *
     * A geofence exit marks the fence as fired. A [PresenceEvent.StillHere]
     * after that is the false alarm: the departure test, or the anchor's
     * Wi-Fi, says the phone never left, and only a re-registration lets the
     * fence fire again. Any other event leaves the fence as it is: a
     * departure ends the snooze, and a check still running has not answered.
     */
    fun after(signal: PresenceSignal, event: PresenceEvent?): Step {
        val fired = exitPending || signal is PresenceSignal.GeofenceExit
        if (!fired || event != PresenceEvent.StillHere) {
            return Step(copy(exitPending = fired), falseAlarm = false, rearmNow = false)
        }
        // Answered once, by nothing: later still-here readings are not new
        // false alarms, and logging each one would bury the one that was.
        if (left <= 0) return Step(copy(exitPending = false), falseAlarm = true, rearmNow = false)
        return Step(FenceRearm(left = left - 1, exitPending = false), falseAlarm = true, rearmNow = true)
    }

    /**
     * After any registration: the platform's belief starts over, so an exit
     * fired against the previous registration no longer says anything about
     * this one.
     */
    fun registered(): FenceRearm = copy(exitPending = false)

    /**
     * Whether a registration other than a re-arm should ask for an initial
     * exit: a restore, or a repair of a registration that failed.
     *
     * Not the first registration of an anchor — the arm itself, or a retake.
     * That capture just placed the phone inside, so an exit at registration
     * would be a stale platform belief, and it would spend a checking burst
     * re-testing a place the user has not left. Every later registration of
     * the same anchor may follow a departure nothing was watching for, or a
     * platform still believing an earlier exit, however soon after the
     * capture it comes (Codex, PR #332: an anchor-age test missed a restore
     * two minutes in, after a brisk walk out of the fence). So it asks, while
     * the budget lasts, because each rejected answer spends from it.
     *
     * [firstForAnchor] is told, not inferred: the service says when it
     * starts a watch on a place it captured moments ago, so a restore is
     * never mistaken for the arm, and nothing about it rests on a write that
     * could fail.
     */
    fun initialExitOnRegistration(firstForAnchor: Boolean): Boolean = left > 0 && !firstForAnchor

    companion object {
        /** False alarms per snooze answered with a re-registration (maintainer, 2026-10-13). */
        const val BUDGET = 3
    }
}
