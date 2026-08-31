package app.snoozemo.core

import java.time.Instant

/**
 * Choosing an end time from the calendar (SPEC.md §4.3).
 *
 * The idea is the maintainer's: a snooze taken for a meeting should be able to
 * end when the meeting does, without the user working out the time and stepping
 * to it. The whole of the decision lives here, as a pure function over instants,
 * so it is testable with no Android and no calendar provider — the reader in
 * `:app` supplies candidates and this says which one, if any, is worth offering.
 */
object MeetingEnd {

    /**
     * The end time to offer for [snooze], or null when there is nothing worth
     * offering.
     *
     * [candidateEnds] is every event end the reader found, in any order. What
     * comes back is the **earliest** one that would actually change something:
     *
     * - **Later than the floor.** The service declines anything inside
     *   [ActiveSnooze.MIN_CAP], so an earlier end is a button that can only
     *   fail. A meeting ending in the next few minutes is exactly when this is
     *   most tempting and least useful.
     * - **Earlier than the cap.** The service honors a time at or past the cap
     *   by doing nothing and reports it applied — so offering one would be a
     *   button that looks like it worked and changed no deadline at all, which
     *   is the quietly-wrong outcome this app ranks second-worst.
     *
     * Earliest rather than latest because overlapping meetings are common and
     * the first one to end is the one the user is plausibly waiting out; a later
     * end is always reachable by leaving the snooze on its cap.
     *
     * No event **identity** is taken, and none is wanted: a time is the whole
     * of what the button needs, and a title would put a meeting's name on a
     * lock screen (`AGENTS.md`, *Privacy*).
     */
    fun offerFor(
        snooze: ActiveSnooze?,
        candidateEnds: List<Instant>,
        now: Instant,
    ): Instant? {
        val cap = snooze?.capExpiresAt ?: return null
        val floor = now.plus(ActiveSnooze.MIN_CAP)
        return candidateEnds
            .filter { it.isAfter(floor) && it.isBefore(cap) }
            .minOrNull()
    }
}
