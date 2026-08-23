package app.snoozemo.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Navigation position survives a configuration change (Codex, PR #82).
 *
 * `MainActivity` has no `android:configChanges` declared, so a rotation
 * recreates it from scratch — a fresh instance whose `screen` and
 * `permissionsOrigin` fields start back at their defaults unless
 * `onSaveInstanceState`/`onCreate` round-trip them. Everything else this
 * class tracks (`access`, `notifications`, the record) is re-derived from the
 * platform or from disk on every fresh instance, so losing it to a rotation
 * is invisible; navigation position has no such source of truth, which is
 * exactly what made this regress silently.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityNavigationStateTest {

    @Test
    fun `SettingsScreen survives a rotation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().screen = Screen.SETTINGS

        val recreated = controller.recreate().get()

        assertEquals(Screen.SETTINGS, recreated.screen)
    }

    @Test
    fun `Permissions opened from Settings still returns to Settings after a rotation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.screen = Screen.PERMISSIONS
        activity.permissionsOrigin = Screen.SETTINGS

        val recreated = controller.recreate().get()

        // Both have to survive together — a `screen` that comes back right
        // with a `permissionsOrigin` reset to Main would send Done/back to
        // the wrong screen just as visibly as losing `screen` itself.
        assertEquals(Screen.PERMISSIONS, recreated.screen)
        assertEquals(Screen.SETTINGS, recreated.permissionsOrigin)
    }
}
