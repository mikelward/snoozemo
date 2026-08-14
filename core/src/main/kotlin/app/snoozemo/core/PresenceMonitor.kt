package app.snoozemo.core

import kotlinx.coroutines.flow.Flow

/**
 * Watches whether the user is still at [Anchor] (SPEC.md §6.1).
 *
 * The two flavors differ only below this interface, so this is the seam the
 * distribution decision of SPEC.md §3 is confined to.
 *
 * The *contract* lives in `:core` with its consumer, and the implementations
 * live in `:presence`. Putting it the other way around would point `:core` at
 * `:presence` for the interface while `:presence` points back at `:core` for
 * [Anchor] — a dependency cycle the moment `SnoozeController` takes one of
 * these by injection.
 */
interface PresenceMonitor {

    /** Starts watching [anchor]. The flow runs until [stop] or cancellation. */
    fun start(anchor: Anchor): Flow<PresenceEvent>

    /**
     * Stops watching and releases everything `start` acquired — network
     * callbacks, sensor trigger registrations, location requests, geofence
     * registrations. Idempotent.
     */
    fun stop()
}

/** What the presence engine has concluded, in increasing order of confidence. */
sealed interface PresenceEvent {

    /**
     * Positive evidence the user has not left — associated with the anchor SSID,
     * or a fix inside the radius. De-escalates back to `ARMED`.
     *
     * [confirmedBy] is what actually answered, and it is on the event because
     * the controller uses this to *undo* a degradation (SPEC.md §8.1): the two
     * sources prove different things, and restoring more than the evidence
     * supports is the same false statement as leaving a stale degraded line up.
     * Rejoining the anchor's network says nothing about whether location
     * started working again.
     */
    data class StillHere(val confirmedBy: PresenceEvidence) : PresenceEvent

    /**
     * Something suggests departure but nothing has confirmed it: Wi-Fi dropped,
     * significant motion fired, a geofence exit arrived. Escalates to `CHECKING`
     * and never ends a snooze on its own — no single source's evidence is enough
     * (SPEC.md §6.10).
     */
    data object ProbablyLeft : PresenceEvent

    /**
     * The departure test confirmed it: two qualifying fixes at least 30 s apart,
     * or one fix unambiguously beyond the radius (SPEC.md §6.6).
     */
    data object Departed : PresenceEvent

    /**
     * A capability that had degraded is working again, with presence still
     * unsettled — a fix good enough to measure with that puts the user outside
     * the radius, mid-check.
     *
     * Distinct from [StillHere] because it makes no claim about where the user
     * is: the check it arrived during carries on, and the controller changes
     * only the tracking mode. Without it that recovery has nowhere to travel,
     * since a step carries one event and the alternatives are an escalation the
     * controller needs or nothing at all (SPEC.md §8.1).
     */
    data class TrackingRecovered(val confirmedBy: PresenceEvidence) : PresenceEvent

    /**
     * Tracking is working less well than it should, but the snooze stays armed:
     * no fix yet, location services switched off, no location from a background
     * start. The controller drops to a lesser [TrackingMode] and **says so** in
     * the ongoing notification rather than quietly becoming a timer
     * (SPEC.md §8.1).
     *
     * Recoverable by construction — the fix arrives, the user turns location
     * back on, the `Resume tracking` action is tapped — so this must stay
     * distinct from [CapabilityLost], which is not.
     */
    data class Degraded(val cause: DegradationCause) : PresenceEvent

    /**
     * Presence tracking cannot be done at all any more, and no amount of waiting
     * will change that. The controller ends the snooze with
     * [EndReason.LOST_CAPABILITY] and says why (SPEC.md §8.2, D7) — staying
     * armed on state it cannot verify is exactly the "silently quiet phone"
     * failure.
     *
     * Separate from [Degraded] because the two demand opposite responses, and
     * the difference must be in the type: a controller that had to tell them
     * apart by reading a display string would eventually get it wrong, and the
     * cost of getting it wrong in this direction is a phone that never comes
     * back.
     */
    data class CapabilityLost(val cause: CapabilityLossCause) : PresenceEvent
}

/**
 * Which signal confirmed presence, and so which capability has just been
 * observed working.
 *
 * The ceiling on what a recovery may restore. An enum rather than a boolean
 * because "location is back" and "Wi-Fi is back" are different claims about the
 * app's own state, and the notification makes exactly that claim to the user.
 */
enum class PresenceEvidence {
    /**
     * Associated with the anchor's SSID. Proves Wi-Fi tracking works; proves
     * nothing about location, which may still be off, denied, or blind.
     */
    ANCHOR_WIFI,

    /**
     * A fix good enough to place the user inside the radius. Location is
     * working, so tracking can go back to whatever the anchor supports.
     */
    LOCATION_FIX,
}

/**
 * Why tracking degraded. An enum rather than a message so the controller
 * branches on a value and the user-facing wording stays in the UI layer, where
 * it can be translated — and so no sanitizing is needed at the boundary, since
 * a coordinate, an SSID, or a place name cannot be smuggled through an enum.
 */
enum class DegradationCause {
    /** No fix within the arming ceiling, or none since. */
    NO_LOCATION_FIX,

    /**
     * Fixes are arriving, but none of them can place the user: every reading is
     * vaguer than the distance being measured, so the departure test returns
     * [DepartureVerdict.INCONCLUSIVE] each time.
     *
     * Distinct from [NO_LOCATION_FIX] because the two look identical to the app
     * and completely different to the user — nothing is broken here, the phone
     * is somewhere with no signal to fix against — and because a cause that lies
     * about which is which makes the notification's degraded line untrustworthy,
     * which is the only place §8.1 has to say anything at all.
     */
    FIXES_TOO_VAGUE,

    /** Location is switched off system-wide (SPEC.md §8.4). */
    LOCATION_SERVICES_OFF,

    /**
     * Started from a background context, where a while-in-use grant yields no
     * location and no unredacted SSID — the reboot and process-death case
     * (SPEC.md §8.1, §8.3). Recovers when the user taps `Resume tracking`.
     */
    NO_LOCATION_IN_BACKGROUND,
}

/** Why tracking became impossible. Each of these ends the snooze. */
enum class CapabilityLossCause {
    /** The location permission was revoked or downgraded to coarse mid-snooze. */
    LOCATION_PERMISSION_REVOKED,

    /**
     * The monitor could not be established or re-established at all — a geofence
     * that will not register, a monitor that failed to restart after process
     * death. Fail open rather than pretend to be watching.
     */
    MONITORING_UNAVAILABLE,
}
