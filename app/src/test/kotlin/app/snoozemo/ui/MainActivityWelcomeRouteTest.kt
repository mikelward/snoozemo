package app.snoozemo.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.snooze.WelcomeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which screen a launch lands on, now that the welcome flow comes first
 * (`SPEC.md` §4.2).
 *
 * The routing is the part of this feature that can strand someone: the flow
 * must run once and only once, must survive a rotation mid-way, and must not
 * swallow the permissions recap on the way out — that recap is where a user who
 * skipped every grant is told what they skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityWelcomeRouteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("welcome", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a fresh install lands on the welcome flow`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertEquals(Screen.WELCOME, activity.screen)
        assertEquals(WelcomeCard.WHAT, activity.welcomeCard)
    }

    @Test
    fun `an install that has seen the flow lands on the main screen`() {
        WelcomeStore(context).markSeen()

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // Not where it lands, but where it does not: the flow is spent. Where
        // it goes instead is the access routing that predates it — with no Do
        // Not Disturb access, the interstitial — and asserting that here would
        // be this test claiming ownership of a decision it does not make.
        assertNotEquals(Screen.WELCOME, activity.screen)
    }

    @Test
    fun `the flag is written when the flow is left, not when it is entered`() {
        // A flag set on arrival would be spent by a process death mid-flow, and
        // the user would never see the cards they were part-way through. A flow
        // that can be lost to a crash is worse than one shown twice.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse("entering must not spend it", WelcomeStore(context).seen())

        controller.get().screen = Screen.MAIN
        assertFalse("moving screens is not leaving the flow", WelcomeStore(context).seen())
    }

    @Test
    fun `a rotation mid-flow keeps its card`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().welcomeCard = WelcomeCard.TILE

        val recreated = controller.recreate().get()

        // Restarting the flow from card 1 on every rotation would be its own
        // small trap: the user has to walk back through what they just read.
        assertEquals(Screen.WELCOME, recreated.screen)
        assertEquals(WelcomeCard.TILE, recreated.welcomeCard)
    }

    @Test
    fun `a restored screen wins over the fresh-install route`() {
        // Process death on Settings comes back to Settings, even though the
        // flow has never been marked seen — the saved state is a fact about
        // where the user was, and the flow's own once-only check is about
        // installs, not launches.
        WelcomeStore(context).markSeen()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().screen = Screen.SETTINGS

        assertEquals(Screen.SETTINGS, controller.recreate().get().screen)
    }

    @Test
    fun `a replay still routes to the recap on the way out`() {
        // The gate that made this wrong was `routedToPermissionsOnce`, spent by
        // the first access refresh long before any replay — so Skip and Done
        // always returned to the main screen, on the one journey where the user
        // is most likely to be looking for what they skipped (Codex, PR #204).
        WelcomeStore(context).markSeen()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Whatever the launch decided, replaying is a fresh journey.
        activity.screen = Screen.WELCOME
        activity.welcomeCard = WelcomeCard.WHAT

        activity.leaveWelcomeForTest()

        // Robolectric grants no Do Not Disturb access, so something is missing.
        assertEquals(Screen.PERMISSIONS, activity.screen)
    }

    @Test
    fun `leaving the flow never spends the automatic route`() {
        // The exit used to burn `applyAccess`'s once-only route on its way out.
        // A tap that beats the access reading finds it still null, which is not
        // a missing permission — so the exit goes to the main screen, and with
        // the route spent the `DENIED` landing a moment later could no longer
        // show the interstitial. The user was left on a screen whose Arm button
        // is disabled with nothing explaining why (Codex, PR #204).
        //
        // Asserted on the exit rather than on that timing, because the flag is
        // what the two decisions were sharing: leaving the flow answers the
        // flow's own contract, and `applyAccess` still owes its one route
        // whichever way this went. `WelcomeExitTest` covers the unread reading
        // itself, which no Robolectric launch reproduces — every permission but
        // access answers before the activity settles.
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(Screen.WELCOME, activity.screen)
        // Held, not spent, while the cards are up.
        assertFalse(activity.routedToPermissionsOnceForTest())

        activity.leaveWelcomeForTest()

        assertFalse(
            "the exit must leave applyAccess's route for applyAccess",
            activity.routedToPermissionsOnceForTest(),
        )
    }

    @Test
    fun `the flow is shown once per install`() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(Screen.WELCOME, first.get().screen)
        // Leaving is what marks it, and `Skip` and the last card both do that.
        WelcomeStore(context).markSeen()
        first.pause().stop().destroy()

        val second = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertNotEquals(Screen.WELCOME, second.screen)
        assertTrue(WelcomeStore(context).seen())
    }
}
