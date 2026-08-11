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
     */
    data object StillHere : PresenceEvent

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
 * Why tracking degraded. An enum rather than a message so the controller
 * branches on a value and the user-facing wording stays in the UI layer, where
 * it can be translated — and so no sanitizing is needed at the boundary, since
 * a coordinate, an SSID, or a place name cannot be smuggled through an enum.
 */
enum class DegradationCause {
    /** No fix within the arming ceiling, or none since. */
    NO_LOCATION_FIX,

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
