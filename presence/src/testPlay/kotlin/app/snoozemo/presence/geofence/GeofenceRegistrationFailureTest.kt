package app.snoozemo.presence.geofence

import app.snoozemo.core.CapabilityLossCause
import app.snoozemo.core.DegradationCause
import com.google.android.gms.location.GeofenceStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            GeofenceRegistrationFailure.fromStatusCode(
                GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE,
                hasFineLocation = true,
            ),
        )
    }

    @Test
    fun `a missing background grant degrades and names that grant`() {
        // Fine location held, background not: the ordinary while-in-use grant,
        // where geofencing is unavailable outright on API 29+. Degrades since
        // 2026-08-30 — the cap bounds the fallback, so ending discarded the
        // snooze for nothing.
        assertEquals(
            GeofenceRegistrationFailure.Recoverable(DegradationCause.NO_LOCATION_IN_BACKGROUND),
            GeofenceRegistrationFailure.fromStatusCode(
                GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION,
                hasFineLocation = true,
            ),
        )
        assertEquals(
            GeofenceRegistrationFailure.Recoverable(DegradationCause.NO_LOCATION_IN_BACKGROUND),
            GeofenceRegistrationFailure.fromSecurityException(hasFineLocation = true),
        )
    }

    @Test
    fun `a revoked grant degrades and names a different one`() {
        // The same platform answer, a different user-visible problem: grant
        // location at all, not merely in the background. Collapsing the two
        // would be the failure PR #141 exists to prevent.
        assertEquals(
            GeofenceRegistrationFailure.Recoverable(DegradationCause.LOCATION_PERMISSION_GONE),
            GeofenceRegistrationFailure.fromStatusCode(
                GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION,
                hasFineLocation = false,
            ),
        )
        assertEquals(
            GeofenceRegistrationFailure.Recoverable(DegradationCause.LOCATION_PERMISSION_GONE),
            GeofenceRegistrationFailure.fromSecurityException(hasFineLocation = false),
        )
    }

    @Test
    fun `the two permission causes never collapse to one`() {
        // Guards the split itself: a refactor that dropped the grant read
        // would still classify both, just identically, and every assertion
        // above would keep passing if they shared a cause.
        val background = GeofenceRegistrationFailure.fromSecurityException(hasFineLocation = true)
        val revoked = GeofenceRegistrationFailure.fromSecurityException(hasFineLocation = false)

        assertNotEquals(background, revoked)
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
            // Asserted under both grant states: an unclassified refusal is
            // fatal whatever the permissions say, since the permission read
            // only ever separates the two permission causes.
            for (fine in booleanArrayOf(true, false)) {
                assertEquals(
                    GeofenceRegistrationFailure.Fatal(CapabilityLossCause.MONITORING_UNAVAILABLE),
                    GeofenceRegistrationFailure.fromStatusCode(code, hasFineLocation = fine),
                )
            }
        }
    }
}
