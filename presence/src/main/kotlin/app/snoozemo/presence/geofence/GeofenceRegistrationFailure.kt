package app.snoozemo.presence.geofence

import app.snoozemo.core.CapabilityLossCause
import app.snoozemo.core.DegradationCause
import com.google.android.gms.location.GeofenceStatusCodes

/**
 * What a refused geofence registration means for the snooze — the
 * recoverable/fatal split of SPEC.md §6.1, which is the one distinction the
 * monitor must never get wrong: a degradation keeps the snooze armed in a
 * lesser mode and can be taken back, while a capability loss ends it and
 * cannot.
 */
internal sealed interface GeofenceRegistrationFailure {

    /**
     * Watching can genuinely resume without a new snooze — the cause is a
     * device state the user can change back. The snooze stays armed, degraded,
     * with Wi-Fi and the duration cap still able to end it.
     */
    data class Recoverable(val cause: DegradationCause) : GeofenceRegistrationFailure

    /**
     * The fence could not be established and waiting will not change that.
     * Fail open (D7): the controller ends the snooze and says why, because
     * staying armed on state nothing is watching is the silently-quiet-phone
     * failure.
     */
    data class Fatal(val cause: CapabilityLossCause) : GeofenceRegistrationFailure

    companion object {

        /**
         * A `SecurityException` from the registration call: the grant went
         * between the permission check and the call.
         *
         * Degrades rather than ends (maintainer, 2026-08-30) — see
         * [permissionFailure] for which cause and why.
         */
        fun fromSecurityException(hasFineLocation: Boolean): GeofenceRegistrationFailure =
            permissionFailure(hasFineLocation)

        /**
         * The one classification both permission-shaped refusals share.
         *
         * `addGeofences` answers "insufficient location permission" for two
         * different states, and they need different things from the user:
         * **fine location held, background not** — the ordinary while-in-use
         * grant, where geofencing is unavailable outright on API 29+ — versus
         * **fine location gone**, a revoked or downgraded grant. Telling them
         * apart needs the live permission state, which is why this takes it
         * rather than reading the status code alone.
         *
         * Both are **recoverable**. Until 2026-08-30 both ended the snooze, on
         * the reading that tracking we cannot do is the ambiguous state D7 says
         * to resolve toward ending. What that missed is that the duration cap
         * is mandatory and user-set, so duration-only is bounded by
         * construction — the backstop principle 1 names is already in place,
         * and ending early only discards the snooze the user asked for. The
         * card says which permission is missing either way, so this is a
         * degradation the user can act on, not a silent one.
         */
        private fun permissionFailure(hasFineLocation: Boolean): GeofenceRegistrationFailure =
            if (hasFineLocation) {
                Recoverable(DegradationCause.NO_LOCATION_IN_BACKGROUND)
            } else {
                Recoverable(DegradationCause.LOCATION_PERMISSION_GONE)
            }

        /**
         * Classifies an `ApiException` status code from `addGeofences`.
         *
         * Two codes are recoverable. [GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE]
         * means geofencing is switched off underneath us — location services
         * off is the ordinary cause — and flipping it back on is the user's to
         * do. [GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION]
         * is a missing grant, split by [permissionFailure] into the two states
         * it covers.
         *
         * Everything else — too many fences, too many pending intents, rate
         * limiting, codes this build has never heard of — is treated as
         * monitoring unavailable, deliberately: pretending an unclassified
         * refusal will heal is how a phone stays silent on state nothing is
         * watching, and a snooze that ends early is the cheap side of that
         * trade (principle 1). That trade still holds *here* precisely because
         * we cannot name the reason; the permission cases moved off it because
         * we can. A retry ladder for genuinely transient codes is a refinement
         * to add if the field shows one, not a default.
         */
        fun fromStatusCode(statusCode: Int, hasFineLocation: Boolean): GeofenceRegistrationFailure =
            when (statusCode) {
                GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
                    Recoverable(DegradationCause.LOCATION_SERVICES_OFF)
                GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
                    permissionFailure(hasFineLocation)
                else -> Fatal(CapabilityLossCause.MONITORING_UNAVAILABLE)
            }
    }
}
