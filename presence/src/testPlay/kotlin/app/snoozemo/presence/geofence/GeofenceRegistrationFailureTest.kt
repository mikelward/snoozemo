package app.snoozemo.presence.geofence

import app.snoozemo.core.CapabilityLossCause
import app.snoozemo.core.DegradationCause
import com.google.android.gms.location.GeofenceStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recoverable/fatal split — the one distinction the monitor must never
 * get wrong (SPEC.md §6.1). Degradation keeps the snooze armed and can be
 * taken back; capability loss ends it and cannot.
 */
class GeofenceRegistrationFailureTest {

    @Test
    fun `geofencing switched off degrades rather than ends`() {
        assertEquals(
            GeofenceRegistrationFailure.Recoverable(DegradationCause.LOCATION_SERVICES_OFF),
            GeofenceRegistrationFailure.fromStatusCode(GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE),
        )
    }

    @Test
    fun `a missing grant ends the snooze with the reason`() {
        assertEquals(
            GeofenceRegistrationFailure.Fatal(CapabilityLossCause.LOCATION_PERMISSION_REVOKED),
            GeofenceRegistrationFailure.fromStatusCode(
                GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION,
            ),
        )
        assertEquals(
            GeofenceRegistrationFailure.Fatal(CapabilityLossCause.LOCATION_PERMISSION_REVOKED),
            GeofenceRegistrationFailure.fromSecurityException(),
        )
    }

    @Test
    fun `an unclassified refusal fails open rather than pretending to watch`() {
        // Includes rate limiting and codes this build has never heard of: a
        // snooze that ends early is the cheap side of that trade (principle 1).
        for (code in intArrayOf(
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES,
            GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS,
            GeofenceStatusCodes.GEOFENCE_REQUEST_TOO_FREQUENT,
            -1,
        )) {
            assertEquals(
                GeofenceRegistrationFailure.Fatal(CapabilityLossCause.MONITORING_UNAVAILABLE),
                GeofenceRegistrationFailure.fromStatusCode(code),
            )
        }
    }
}
