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

    /**
     * Starts watching [anchor]. The flow runs until [stop] or cancellation.
     *
     * [sinceElapsedRealtimeMs] is the snooze's arm moment in elapsed
     * realtime — the engine's evidence seed, per
     * `PresenceState.latestEvidenceMs`'s contract. The *caller* supplies it
     * because only the caller can: on a restore it is derived from the
     * record's stored clock frame, and the monitor's own "now" is exactly
     * wrong there — it post-dates the very observation a restart was woken
     * to deliver, so seeding with it silently discarded every exit that
     * arrived while the process was dead (flagged by Codex on PR #73).
     * Anything older than the seed is stale by construction; anything the
     * snooze should act on is newer.
     */
    fun start(anchor: Anchor, sinceElapsedRealtimeMs: Long): Flow<PresenceUpdate>

    /**
     * Stops watching and releases everything `start` acquired — network
     * callbacks, sensor trigger registrations, location requests, geofence
     * registrations. Idempotent.
     */
    fun stop()

    /**
     * The [TrackingMode]s this monitor can honestly run for [anchor] — the
     * set the controller confines every mode claim to, at arm and on every
     * update. [TrackingMode.DURATION_ONLY] is always an honest answer and is
     * treated as present whether or not it is listed.
     *
     * The monitor answers rather than [TrackingMode.from], because the anchor
     * alone cannot: `from` says what the captured *fields* allow, and only
     * the monitor knows which of them anything is actually watching. The
     * `direct` flavor's stand-in watches nothing, so a full anchor is still
     * a timer there; the geofence monitor has no Wi-Fi watch yet, so an
     * SSID-only anchor is too. A mode is a claim about what is watching
     * (SPEC.md §6.1, §8.1), and this is where the claim gets its warrant.
     *
     * A *set*, not a single ceiling, because degradation moves through modes
     * the ceiling cannot vouch for (flagged by Codex on PR #73): a fenced
     * anchor's ceiling is FULL, but when location degrades, the fallback
     * claim is `WIFI_ONLY` — and whether *that* is honest depends on whether
     * anything watches Wi-Fi, which the ceiling alone cannot say. The
     * controller lowers an unsupported claim to the nearest less capable
     * supported mode.
     */
    fun supportedModes(anchor: Anchor): Set<TrackingMode>
}

/**
 * One report from the engine: what just happened, and how well tracking is
 * currently working.
 *
 * The two halves are deliberately different shapes. [event] is *news* — it
 * fires once, when something changes, and null is the common answer.
 * [degradation] is a **level**: the engine's current view of whether location
 * can still answer the question, restated on every update whether it moved or
 * not, exactly like [LocationDuty].
 *
 * That difference is the lesson of PR #33. Recovery was originally reported as
 * an event, and every ordering question then became "did the announcement
 * survive?" — through an escalation that outranked it, through a Wi-Fi
 * association that suppressed location before it could be sent again, through a
 * second fix that overwrote it. Nine review rounds, most of them that one
 * question wearing different clothes. A level cannot be lost in transit, so
 * none of those questions exist: the controller compares what it is told to
 * what it believes, and acts on the difference.
 */
data class PresenceUpdate(
    val event: PresenceEvent?,
    /** Null when location is answering normally; the reason when it is not. */
    val degradation: DegradationCause?,
)

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
     * Presence tracking cannot be done at all any more, and no amount of waiting
     * will change that. The controller ends the snooze with
     * [EndReason.LOST_CAPABILITY] and says why (SPEC.md §8.2, D7) — staying
     * armed on state it cannot verify is exactly the "silently quiet phone"
     * failure.
     *
     * This is an *event* and [PresenceUpdate.degradation] is a level, and the
     * split is the point: a degradation keeps the snooze armed in a lesser mode
     * and can be taken back, while this ends the snooze and cannot. The
     * difference must be in the type rather than in a value the controller has
     * to interpret — the cost of getting it wrong in this direction is a phone
     * that never comes back.
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

    /**
     * The anchor was captured but nothing is running to watch it, so the
     * caller armed below what the anchor supports — today every arm, until
     * the monitor wiring consumes the anchor; durably, the `direct` flavor
     * until Phase 7's foreground monitor. Distinct from [NO_LOCATION_FIX]
     * because the debug log records the cause, and "no fix" over a fix just
     * persisted is the kind of false reason §4.6's log exists to rule out
     * (flagged by Codex on PR #71).
     */
    NOTHING_WATCHING,
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
