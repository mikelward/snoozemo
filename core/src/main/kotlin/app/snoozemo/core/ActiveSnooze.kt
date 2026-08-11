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

    companion object {
        /** Shown until saved places land and the anchor can be named. */
        const val DEFAULT_PLACE_NAME: String = "Here"

        /** The cap the user gets without choosing one (SPEC.md §7). */
        val DEFAULT_CAP: Duration = Duration.ofHours(8)

        /**
         * The absolute backstop. A time chosen in the end-condition sheet lowers
         * the cap; nothing — including `+30 min` — may push it above this
         * (SPEC.md §4.3, §7).
         */
        val MAX_CAP: Duration = Duration.ofHours(24)

        /** The floor the sheet's `−` may not go below. */
        val MIN_CAP: Duration = Duration.ofMinutes(30)
    }
}
