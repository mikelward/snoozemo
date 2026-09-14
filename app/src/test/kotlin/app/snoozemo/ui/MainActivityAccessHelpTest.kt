package app.snoozemo.ui

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The "how to grant Do Not Disturb access" help dialog (maintainer,
 * 2026-09-14). Tapping the access row must open the dialog rather than the
 * system settings directly — that settings page is a list the user has to find
 * Snoozemo in and toggle on — and only its confirm launches the settings.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityAccessHelpTest {

    @Test
    fun `the access row opens the help dialog rather than settings`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.askDoNotDisturbAccess()

        assertTrue(activity.showAccessHelp)
        // Nothing launched yet: the settings list waits behind the dialog.
        assertEquals(null, shadowOf(activity).peekNextStartedActivity())
    }

    @Test
    fun `confirming opens the access settings and closes the dialog`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showAccessHelp = true

        activity.confirmDoNotDisturbAccess()

        assertFalse("Confirm must close the dialog", activity.showAccessHelp)
        assertEquals(
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            shadowOf(activity).peekNextStartedActivity()?.action,
        )
    }

    @Test
    fun `dismissing closes the dialog without opening settings`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showAccessHelp = true

        activity.dismissAccessHelp()

        assertFalse(activity.showAccessHelp)
        assertEquals(null, shadowOf(activity).peekNextStartedActivity())
    }
}
