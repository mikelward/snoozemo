package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionTest {

    @Test
    fun `granted is granted whatever the history says`() {
        assertEquals(
            NotificationPermission.GRANTED,
            NotificationPermission.of(granted = true, everDenied = false, rationale = false),
        )
        assertEquals(
            NotificationPermission.GRANTED,
            NotificationPermission.of(granted = true, everDenied = true, rationale = true),
        )
    }

    @Test
    fun `a fresh install can be asked in place`() {
        // The state that makes `everDenied` necessary: the platform reports no
        // rationale here *and* when the permission is permanently denied, so
        // without the flag this reads as blocked and sends a first-run user to
        // Settings for a prompt the app could have shown itself.
        assertEquals(
            NotificationPermission.ASKABLE,
            NotificationPermission.of(granted = false, everDenied = false, rationale = false),
        )
    }

    @Test
    fun `a dialog dismissed without an answer stays askable`() {
        // Pressing back on the dialog is not a denial — the system doesn't
        // count it against the two prompts it will show, so the dialog is still
        // there to be shown. This reads exactly like a fresh install, which is
        // why the flag has to track a denial that actually landed rather than a
        // request that was launched: counting the launch would send this user to
        // Settings and stop the tile asking, for a prompt still available.
        assertEquals(
            NotificationPermission.ASKABLE,
            NotificationPermission.of(granted = false, everDenied = false, rationale = false),
        )
    }

    @Test
    fun `one denial still leaves a prompt to show`() {
        // `rationale` is true only between the first explicit denial and the
        // permanent one, so it is both the answer here and the signal the
        // caller persists.
        assertEquals(
            NotificationPermission.ASKABLE,
            NotificationPermission.of(granted = false, everDenied = true, rationale = true),
        )
    }

    @Test
    fun `denied before and no longer prompted is blocked`() {
        // The defect this whole type exists for: launching the request here
        // returns a denial immediately and shows the user nothing, so a row
        // that offered to ask would be a tap that does nothing.
        assertEquals(
            NotificationPermission.BLOCKED,
            NotificationPermission.of(granted = false, everDenied = true, rationale = false),
        )
    }
}
