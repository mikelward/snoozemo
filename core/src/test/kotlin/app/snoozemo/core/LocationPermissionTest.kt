package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPermissionTest {

    @Test
    fun `both held is granted whatever the history says`() {
        assertEquals(
            LocationPermission.GRANTED,
            LocationPermission.of(
                foregroundGranted = true,
                backgroundGranted = true,
                foregroundEverDenied = true,
                foregroundRationale = true,
                backgroundEverDenied = true,
                backgroundRationale = true,
            ),
        )
    }

    @Test
    fun `a fresh install can be asked for foreground in place`() {
        assertEquals(
            LocationPermission.ASKABLE,
            LocationPermission.of(
                foregroundGranted = false,
                backgroundGranted = false,
                foregroundEverDenied = false,
                foregroundRationale = false,
                backgroundEverDenied = false,
                backgroundRationale = false,
            ),
        )
    }

    @Test
    fun `foreground denied once still leaves a prompt to show`() {
        assertEquals(
            LocationPermission.ASKABLE,
            LocationPermission.of(
                foregroundGranted = false,
                backgroundGranted = false,
                foregroundEverDenied = true,
                foregroundRationale = true,
                backgroundEverDenied = false,
                backgroundRationale = false,
            ),
        )
    }

    @Test
    fun `foreground denied for good is blocked regardless of background`() {
        // The defect this exists to avoid: launching the foreground request
        // here returns a denial immediately, so a row offering to ask would be
        // a tap that does nothing — whatever background's own history says.
        assertEquals(
            LocationPermission.BLOCKED,
            LocationPermission.of(
                foregroundGranted = false,
                backgroundGranted = false,
                foregroundEverDenied = true,
                foregroundRationale = false,
                backgroundEverDenied = false,
                backgroundRationale = false,
            ),
        )
    }

    @Test
    fun `foreground alone is askable for the background step next`() {
        // The platform only grants background once foreground is already held,
        // so once foreground is in, the missing piece — and the one whose
        // history decides the answer — is background's alone.
        assertEquals(
            LocationPermission.ASKABLE,
            LocationPermission.of(
                foregroundGranted = true,
                backgroundGranted = false,
                foregroundEverDenied = true,
                foregroundRationale = false,
                backgroundEverDenied = false,
                backgroundRationale = false,
            ),
        )
    }

    @Test
    fun `background denied for good is blocked even though foreground still works`() {
        assertEquals(
            LocationPermission.BLOCKED,
            LocationPermission.of(
                foregroundGranted = true,
                backgroundGranted = false,
                foregroundEverDenied = false,
                foregroundRationale = false,
                backgroundEverDenied = true,
                backgroundRationale = false,
            ),
        )
    }
}
