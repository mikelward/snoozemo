package app.snoozemo.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.snooze.WelcomeStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where a tile tap that could not produce a snooze actually lands (`SPEC.md`
 * §4.1).
 *
 * The trampoline's half of this is covered by `TileTrampolineSetupTest`; what
 * is asserted here is that the destination it asks for is honored, because the
 * app routes to the setup screen on its own **only** for missing Do Not Disturb
 * access. A tap blocked by notifications would otherwise open the ordinary arm
 * screen, which carries no notification row — nothing happened and nothing said
 * why, the silence the whole gate exists to end (Codex, PR #215).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityTileSetupRouteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("welcome", Context.MODE_PRIVATE).edit().clear().commit()
        // Spent, so the welcome flow is not what these launches land on: the
        // cards deliberately come first on a fresh install, and this route sits
        // behind them.
        WelcomeStore(context).markSeen()
    }

    private fun launch(extra: Boolean?): MainActivity {
        val intent = Intent(context, MainActivity::class.java)
        if (extra != null) intent.putExtra(EXTRA_OPEN_PERMISSIONS, extra)
        return Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
    }

    @Test
    fun `the tile's setup request lands on the permissions screen`() {
        // Granted, so the access routing that predates this cannot be what puts
        // the screen there — otherwise this passes for the wrong reason and
        // would keep passing with the extra ignored entirely.
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        assertEquals(Screen.PERMISSIONS, launch(extra = true).screen)
    }

    @Test
    fun `an ordinary launch does not`() {
        // The other direction: the app opened from the launcher with everything
        // granted belongs on Main, and a route that fired without being asked
        // would put every launch on a setup screen.
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        assertEquals(Screen.MAIN, launch(extra = null).screen)
    }

    @Test
    fun `a rotation does not send the user back`() {
        // The intent that started the activity is still attached after a
        // configuration change, so a route that read it unconditionally would
        // throw a user who had navigated back to Main onto the setup screen on
        // every rotation.
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_PERMISSIONS, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        controller.get().screen = Screen.MAIN

        assertEquals(Screen.MAIN, controller.recreate().get().screen)
    }
}
