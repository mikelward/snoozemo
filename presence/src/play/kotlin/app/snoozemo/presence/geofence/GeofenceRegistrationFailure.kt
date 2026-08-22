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

        /** A `SecurityException` from the registration call: the grant is gone. */
        fun fromSecurityException(): GeofenceRegistrationFailure =
            Fatal(CapabilityLossCause.LOCATION_PERMISSION_REVOKED)

        /**
         * Classifies an `ApiException` status code from `addGeofences`.
         *
         * Only [GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE] is recoverable:
         * it means geofencing is switched off underneath us — location
         * services off is the ordinary cause — and flipping it back on is the
         * user's to do, with the snooze still bounded by Wi-Fi and the cap
         * meanwhile. The permission code is the grant gone by another name.
         * Everything else — too many fences, too many pending intents, rate
         * limiting, codes this build has never heard of — is treated as
         * monitoring unavailable, deliberately: pretending an unclassified
         * refusal will heal is how a phone stays silent on state nothing is
         * watching, and a snooze that ends early is the cheap side of that
         * trade (principle 1). A retry ladder for genuinely transient codes
         * is a refinement to add if the field shows one, not a default.
         */
        fun fromStatusCode(statusCode: Int): GeofenceRegistrationFailure = when (statusCode) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
                Recoverable(DegradationCause.LOCATION_SERVICES_OFF)
            GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
                Fatal(CapabilityLossCause.LOCATION_PERMISSION_REVOKED)
            else -> Fatal(CapabilityLossCause.MONITORING_UNAVAILABLE)
        }
    }
}
