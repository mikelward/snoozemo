package app.snoozemo.core

import java.time.Duration
import java.time.Instant

/**
 * Where "here" was when the tile was tapped (SPEC.md §6.2).
 *
 * The SSID — never the BSSID — is the Wi-Fi anchor: in any building with a mesh
 * or several access points the BSSID changes as the phone roams between them
 * while the user has obviously not gone anywhere, which would produce constant
 * false departures in exactly the large venues the app is most useful in. The
 * BSSID is carried for diagnostics only.
 */
data class Anchor(
    val lat: Double? = null,
    val lon: Double? = null,
    val fixAccuracyM: Float? = null,
    val capturedAt: Instant,
    val ssid: String? = null,
    val bssid: String? = null,
    val radiusM: Int = DEFAULT_RADIUS_M,
) {
    /**
     * Whether this anchor has coordinates precise enough to test departures
     * against. A fix vaguer than this says "somewhere in the neighborhood",
     * which cannot distinguish leaving from standing still, so an anchor that
     * fails this runs Wi-Fi-only rather than pretending to track (SPEC.md §8.4).
     */
    val hasUsableFix: Boolean
        get() = lat != null && lon != null &&
            fixAccuracyM != null && fixAccuracyM <= MAX_ANCHOR_ACCURACY_M

    companion object {
        const val DEFAULT_RADIUS_M: Int = 150

        /** Discard coordinates vaguer than this when capturing an anchor. */
        const val MAX_ANCHOR_ACCURACY_M: Float = 200f
    }
}

/**
 * The persisted record of a running snooze, written on every state transition so
 * process death is fully recoverable (SPEC.md §11).
 *
 * [capExpiresAt] is absolute rather than a duration-from-start: a reboot must not
 * extend a snooze, so the cap continues from its original start time however many
 * times the process dies in between (SPEC.md §8.3).
 */
data class ActiveSnooze(
    val anchor: Anchor,
    val startedAt: Instant,
    val capExpiresAt: Instant,
    // Deliberately has no default. A snooze whose anchor capture timed out would
    // otherwise be recorded as FULL and restored claiming healthy tracking, and
    // the mode is exactly what the notification's degraded line is written from
    // (SPEC.md §8.1) — the wrong value here is a silent failure, not a cosmetic
    // one. Callers state it, or derive it with [TrackingMode.from].
    val mode: TrackingMode,
    val placeName: String = DEFAULT_PLACE_NAME,
) {
    /**
     * How long is left before the cap fires, floored at zero. Never negative: an
     * overdue cap is expressed as [isExpired], not as a negative countdown that a
     * caller might format into the notification as "ends in -4m".
     */
    fun remaining(now: Instant): Duration {
        val left = Duration.between(now, capExpiresAt)
        return if (left.isNegative) Duration.ZERO else left
    }

    /**
     * Whether the cap has fired. Inclusive of the exact instant, so a cap that
     * lands precisely on `now` ends the snooze — the fail-open direction
     * (SPEC.md D7).
     */
    fun isExpired(now: Instant): Boolean = !now.isBefore(capExpiresAt)

    /**
     * Where the cap lands if the notification's `+30 min` is tapped (SPEC.md
     * §4.3), clamped to [DEFAULT_CAP] measured from [startedAt] — repeated taps
     * may not walk a snooze past the backstop.
     *
     * The ceiling is the **8-hour default**, not [MAX_CAP]. SPEC.md §7 is
     * explicit: a time chosen in the end-condition sheet only ever *lowers* the
     * cap, "the 8-hour default remains an absolute backstop above any chosen
     * value, and `+30 min` may not push past it." Clamping to [MAX_CAP] instead
     * let sixteen taps walk a default snooze from 8 hours to 24 — sixteen hours
     * of silence past the backstop the whole design leans on, reached by a
     * button whose only job is to add half an hour.
     *
     * Stated as a ceiling rather than a subtraction so a cap already *above* the
     * default — which only a per-place setting could produce, and none exists
     * yet — declines to extend rather than jumping backwards.
     *
     * Returns [capExpiresAt] unchanged when the ceiling is already reached, so a
     * caller can tell "extended" from "cannot extend" by comparison rather than
     * re-deriving the clamp and getting it subtly different.
     */
    fun extendedCap(by: Duration): Instant {
        val ceiling = startedAt.plus(DEFAULT_CAP)
        val extended = capExpiresAt.plus(by)
        return if (extended.isAfter(ceiling)) maxOf(capExpiresAt, ceiling) else extended
    }

    companion object {
        /** Shown until saved places land and the anchor can be named. */
        const val DEFAULT_PLACE_NAME: String = "Here"

        /** The cap the user gets without choosing one (SPEC.md §7). */
        val DEFAULT_CAP: Duration = Duration.ofHours(8)

        /**
         * The ceiling on a cap the user asks for — the top of the configurable
         * 30 min – 24 h range (SPEC.md §7).
         *
         * Not the ceiling `+30 min` clamps to: that is [DEFAULT_CAP], because
         * extending is not choosing a cap. See [extendedCap].
         */
        val MAX_CAP: Duration = Duration.ofHours(24)

        /** The floor the sheet's `−` may not go below. */
        val MIN_CAP: Duration = Duration.ofMinutes(30)

        /**
         * The absolute instant a snooze started at [now] with a requested
         * [cap] should end, clamped to [MIN_CAP]–[MAX_CAP].
         *
         * Exists so the cap is settled **once**, before anything is scheduled or
         * recorded. The alarm and the record have to name the same moment: two
         * derivations from two clock readings put them milliseconds apart, and
         * an alarm that fires just before its own record counts as expired is a
         * spent alarm and a snooze with no duration exit.
         */
        fun capExpiryFor(now: Instant, cap: Duration = DEFAULT_CAP): Instant =
            now.plus(cap.coerceIn(MIN_CAP, MAX_CAP))

        /**
         * Whether a retry queued for the snooze that started at [queuedFor] is
         * still entitled to act on the record now on disk ([onDisk]).
         *
         * Both retries the app schedules — erasing a released record, and
         * ending a snooze whose cap could not be rescheduled — can outlive the
         * snooze they were queued for: the alarm behind each is durable, the
         * process is not, and the user can arm a *new* snooze before either
         * fires. Both would then act on the wrong snooze. A stale erase deletes
         * the new record, taking its cap with it while its zen rule stays on —
         * a phone left quiet with nothing scheduled to bring it back, produced
         * by the mechanism meant to prevent exactly that. A stale release ends
         * the snooze the user just armed, and explains it as a reboot that
         * couldn't resume, which never happened.
         *
         * [startedAt] is the identity, because it is the one field that is fixed
         * for a snooze's whole life: the cap moves with `+30 min`, the mode and
         * the anchor change as tracking degrades.
         *
         * Both nulls mean "nothing to disagree with" and answer true — an absent
         * record has nothing to protect, and an unidentified retry is one no
         * caller can currently produce, so refusing it would only strand a
         * record that a later cold start would restore.
         */
        fun retryStillApplies(onDisk: ActiveSnooze?, queuedFor: Instant?): Boolean =
            onDisk == null || queuedFor == null || onDisk.startedAt == queuedFor
    }
}
