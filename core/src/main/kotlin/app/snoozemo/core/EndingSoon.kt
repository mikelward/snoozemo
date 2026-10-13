package app.snoozemo.core

import java.time.Duration

/**
 * Which ending a "snooze ending soon" warning is about (SPEC.md §4.3).
 *
 * Three, because the three are different promises to the user and take
 * different ways out: a time can be pushed back, while a departure is answered
 * by saying where "here" is.
 */
enum class EndingSoonCause {
    /** The cap is [EndingSoon.LEAD] or less away — a chosen time, or the failsafe. */
    TIME,

    /** The anchor's Wi-Fi is gone and the §6.6 grace period is counting down. */
    WIFI_LOST,

    /** One fix has landed outside the place; the next one, 30 s on, ends the snooze. */
    LEAVING,
}

/** What the notification offers in place of letting the snooze end (SPEC.md §4.3). */
enum class KeepSnoozing {
    /** `+30 min`: the cap moves out by [ActiveSnooze.EXTENSION]. */
    PLUS_THIRTY,

    /**
     * `Still here`: the place stretches to cover where the phone is now
     * ([AnchorRetake]) and departure goes on being watched from there — the
     * same retake `Until I leave` runs over a running snooze.
     */
    STILL_HERE,
}

/**
 * When a snooze warns that it is about to end, and what the warning offers
 * (SPEC.md §4.3, "Ending soon").
 *
 * Pure, like the rest of the decision logic (SPEC.md §11): the service and the
 * alarm receiver ask the same questions here, so the card that buzzes and the
 * card that does not cannot disagree about the same snooze.
 */
object EndingSoon {

    /** How long before a timed end the warning fires. */
    val LEAD: Duration = Duration.ofMinutes(5)

    /**
     * How far past [LEAD] a fired warning alarm still counts as on time.
     *
     * The alarm is inexact and fires at or after its trigger, so `remaining`
     * is normally at most [LEAD]; the slack covers the two clock frames the
     * record reads its deadline in disagreeing by a little. A wake-up that
     * finds much more than that left is one whose cap moved after it was
     * armed, and it says nothing.
     */
    val LEAD_SLACK: Duration = Duration.ofMinutes(1)

    /**
     * How long from now to arm the timed warning for a snooze with
     * [remaining] left, or null when the warning's moment has already passed.
     *
     * Null rather than "now": a restore inside the last five minutes would
     * otherwise buzz again for a warning the user may already have seen and
     * dismissed. The ongoing card's countdown is still there.
     */
    fun timedWarningIn(remaining: Duration): Duration? =
        remaining.minus(LEAD).takeIf { !it.isNegative && !it.isZero }

    /** Whether a fired warning alarm, finding [remaining] left, should post. */
    fun timedWarningDue(remaining: Duration): Boolean =
        !remaining.isZero && !remaining.isNegative && remaining <= LEAD.plus(LEAD_SLACK)

    /**
     * The presence ending [snooze] is close to, or null for none.
     *
     * Only a snooze that still ends on leaving: one narrowed to its timer
     * ignores a departure ([PresenceEvent.endReasonFor]), so warning about one
     * would announce an ending that is not coming.
     *
     * [EndingSoonCause.LEAVING] before [EndingSoonCause.WIFI_LOST]: the
     * engine clears grace when location answers, so the two should not stand
     * together, but if they ever do the one 30 s away is the one to name.
     *
     * A geofence exit or an anchor-Wi-Fi drop on its own is **not** here. Each
     * *starts* the departure test rather than concluding it, and a warning on
     * them would buzz on every router blip (`TODO.md`, the ending-soon entry).
     */
    fun presenceCause(
        snooze: ActiveSnooze?,
        graceDeadlineMs: Long?,
        confirmingDeparture: Boolean,
    ): EndingSoonCause? = when {
        snooze == null || !snooze.endsOnDeparture -> null
        confirmingDeparture -> EndingSoonCause.LEAVING
        graceDeadlineMs != null && snooze.effectiveMode == TrackingMode.WIFI_GRACE -> EndingSoonCause.WIFI_LOST
        else -> null
    }

    /**
     * What the card for [snooze] offers to keep it going, or null for nothing
     * that would work — **absent, never disabled**, the rule the calendar
     * action follows (SPEC.md §4.3).
     *
     * [cause] is the warning's, or null for the ongoing card.
     *
     * - **A time** is pushed back with `+30 min`, while there is room under the
     *   ceiling. The failsafe at its ceiling has none: the backstop is the one
     *   bound nothing here may move (§7), so that warning offers `End now` alone.
     * - **A departure** is answered with `Still here`, because `+30 min` cannot
     *   touch it: the cap of a snooze ending on leaving already sits at its
     *   ceiling, so the step changed nothing and said so (maintainer,
     *   2026-10-13: "For a location snooze can we use the existing extend
     *   radius logic?").
     * - **The ongoing card** offers whichever matches the snooze's own next
     *   end: `+30 min` when the cap is the deadline it fronts
     *   ([ActiveSnooze.capCountdownShown]), `Still here` while a departure is
     *   watched.
     */
    fun keepSnoozing(snooze: ActiveSnooze, cause: EndingSoonCause?): KeepSnoozing? {
        val plusThirty = KeepSnoozing.PLUS_THIRTY.takeIf { snooze.canExtend }
        val stillHere = KeepSnoozing.STILL_HERE.takeIf {
            snooze.endsOnDeparture && snooze.effectiveMode.tracksDeparture
        }
        return when (cause) {
            EndingSoonCause.TIME -> plusThirty
            EndingSoonCause.WIFI_LOST, EndingSoonCause.LEAVING -> stillHere
            null -> if (snooze.capCountdownShown) plusThirty else stillHere ?: plusThirty
        }
    }
}
