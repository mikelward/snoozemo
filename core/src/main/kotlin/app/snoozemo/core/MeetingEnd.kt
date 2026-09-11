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
    ): Instant? = offersFor(snooze, candidateEnds, now, limit = 1).firstOrNull()

    /**
     * The first [limit] end times worth offering for [snooze], earliest first.
     *
     * Every rule [offerFor] applies is applied here — it is this function with
     * a limit of one — so the notification's single action and a screen showing
     * several can never disagree about what qualifies.
     *
     * **Distinct, because two meetings ending together are one choice.** A
     * shared end is ordinary (a block of back-to-back calls, a meeting and the
     * reminder someone set for it), and offering the same time twice spends a
     * button on nothing and asks the user which of two identical rows they
     * meant.
     *
     * Still no event identity, for the reason [offerFor] gives: what comes back
     * is times, and a caller that wanted to say *which* meeting would have to
     * read something this deliberately never asks the provider for.
     *
     * **Bounded by [ActiveSnooze.capCeilingAt], not the cap the snooze
     * currently carries** (maintainer, 2026-09-11). A chosen time moves the cap
     * either way now, so the backstop is the edge of what the service would
     * accept — and read from the cap, shortening to an hour took every meeting
     * beyond it off the screen permanently, which is the same one-way door
     * `EndCondition.ceilingFor` was fixed for. A meeting inside the backstop is
     * a time the service will take, so it is a row worth drawing.
     */
    fun offersFor(
        snooze: ActiveSnooze?,
        candidateEnds: List<Instant>,
        now: Instant,
        limit: Int,
    ): List<Instant> = offersBefore(snooze?.capCeilingAt ?: return emptyList(), candidateEnds, now, limit)

    /**
     * [offersFor] against a cap named directly rather than read off a record.
     *
     * The idle screen offers the same rows as a way to *start* a snooze
     * (SPEC.md §4.4), and there is no record to read a cap from until it has —
     * what bounds that offer is the cap the snooze would start with. One rule
     * for both, so a meeting the running rows would offer is one the idle rows
     * offer too, and neither offers one the service would decline.
     */
    fun offersBefore(
        cap: Instant,
        candidateEnds: List<Instant>,
        now: Instant,
        limit: Int,
    ): List<Instant> {
        if (limit <= 0) return emptyList()
        val floor = now.plus(ActiveSnooze.MIN_CAP)
        return candidateEnds
            .filter { it.isAfter(floor) && it.isBefore(cap) }
            .distinct()
            .sorted()
            .take(limit)
    }
}
