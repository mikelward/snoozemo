package app.snoozemo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.snooze.WelcomeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tile tap that could not snooze, arriving while the welcome flow is still
 * open (maintainer, 2026-09-07).
 *
 * Before this, the tap built a second `MainActivity`, whose own welcome gate
 * reopened the flow at card 1 over the half-finished one: a user part-way
 * through the cards tapped the tile, got no snooze, and was sent back to the
 * beginning with nothing saying why. The flow resumes now — the running
 * instance keeps its card, and a cold one is rebuilt from the card the store
 * remembers — and the tap says so on whichever card that is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityWelcomeTileTapTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("welcome", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun blockedTap(id: String = "tap-1") = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_OPEN_PERMISSIONS, true)
        .putExtra(EXTRA_BLOCKED_TAP_ID, id)

    @Test
    fun `a tap during the flow keeps the card and says so`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.screen = Screen.WELCOME
        activity.welcomeCard = WelcomeCard.TILE

        // Through the controller, since `onNewIntent` is the framework's to
        // call — which is also what a tile tap does to a running instance.
        controller.newIntent(blockedTap())

        // Where they were, not the recap and not card 1.
        assertEquals(Screen.WELCOME, activity.screen)
        assertEquals(WelcomeCard.TILE, activity.welcomeCard)
        // And not silent: the tap produced no snooze and the flow looks
        // unchanged, which on its own reads as the tile being broken.
        assertTrue(activity.welcomeTapBlocked)
    }

    @Test
    fun `the message survives a rotation`() {
        // Codex, PR #220: a rotation restores from saved state, which skips the
        // launch-intent gate — so a flag left out of the bundle takes the only
        // explanation of the failed tap with it, and the card comes back
        // looking exactly as it did before the tap. That is the silence this
        // whole change exists to end.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().screen = Screen.WELCOME
        controller.get().welcomeCard = WelcomeCard.TILE
        controller.newIntent(blockedTap())

        val rotated = controller.recreate().get()

        assertEquals(Screen.WELCOME, rotated.screen)
        assertEquals(WelcomeCard.TILE, rotated.welcomeCard)
        assertTrue(rotated.welcomeTapBlocked)
    }

    @Test
    fun `a tap that rebuilds a killed process is not mistaken for a rotation`() {
        // Codex, PR #220: Android can keep the task while killing the process,
        // and the tap that rebuilds it arrives *with* a state bundle — which a
        // null-bundle test read as a rotation and dropped, leaving a second
        // failed tap with nothing saying why. Rotation is told apart by the
        // recreation marker, which a new process does not have.
        val restored = Bundle().apply {
            putString("screen", Screen.WELCOME.name)
            putString("welcomeCard", WelcomeCard.TILE.name)
        }

        val activity = Robolectric.buildActivity(MainActivity::class.java, blockedTap(id = "tap-2"))
            .create(restored)
            .start()
            .resume()
            .get()

        assertEquals(Screen.WELCOME, activity.screen)
        assertEquals(WelcomeCard.TILE, activity.welcomeCard)
        assertTrue(activity.welcomeTapBlocked)
    }

    @Test
    fun `a stale intent is not taken for a second tap`() {
        // Codex, PR #220: the extra sticks to the launch intent, so Android
        // hands the same one back when it rebuilds a task whose process it
        // killed. Without the tap's own id this looked exactly like a fresh
        // tap, and a user who had long since left the flow was pushed onto the
        // recap with no tap behind it.
        val restored = Bundle().apply {
            putString("screen", Screen.MAIN.name)
            putString("firstBlockedTapId", "tap-1")
            putString("lastBlockedTapId", "tap-1")
        }

        val activity = Robolectric.buildActivity(MainActivity::class.java, blockedTap(id = "tap-1"))
            .create(restored)
            .start()
            .resume()
            .get()

        assertEquals(Screen.MAIN, activity.screen)
    }

    @Test
    fun `first run remembers card one, so an update cannot lose the flow`() {
        // Codex, PR #220: remembering only from the first `Next` left a
        // first-run user who backgrounded on card 1 with no breadcrumb, and an
        // update landing before the killed process came back flipped
        // `freshInstall` to false — onboarding then never appeared again.
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertEquals(WelcomeCard.WHAT.name, WelcomeStore(context).lastCard())
    }

    @Test
    fun `an earlier tap cannot come back after a later one`() {
        // Codex, PR #220: `setIntent` replaces only this instance's intent, so
        // a task whose launch intent was tap A can redeliver A after tap B
        // arrived through `onNewIntent`. Remembering B alone let A route the
        // user to the recap with no tap behind it.
        val controller = Robolectric.buildActivity(MainActivity::class.java, blockedTap(id = "A"))
            .setup()
        controller.get().screen = Screen.MAIN
        controller.newIntent(blockedTap(id = "B"))
        controller.get().screen = Screen.MAIN

        // The task hands back its original launch intent.
        controller.newIntent(blockedTap(id = "A"))

        assertEquals(Screen.MAIN, controller.get().screen)
    }

    @Test
    fun `the launch tap is remembered however many follow it`() {
        // Codex, PR #220: a capped list aged out the launch intent's id first —
        // the one entry that must never be dropped, since the task can still
        // redeliver that intent. Two ids are kept now, and the first is one of
        // them, so no number of taps in between can evict it.
        val controller = Robolectric.buildActivity(MainActivity::class.java, blockedTap(id = "A"))
            .setup()
        repeat(12) { n ->
            controller.get().screen = Screen.MAIN
            controller.newIntent(blockedTap(id = "later-$n"))
        }
        controller.get().screen = Screen.MAIN

        controller.newIntent(blockedTap(id = "A"))

        assertEquals(Screen.MAIN, controller.get().screen)
    }

    @Test
    fun `a tap outside the flow still lands on the recap`() {
        // The other direction, which is the behavior this must not undo: past
        // the flow, the setup screen is the thing that repairs a blocked tap.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.screen = Screen.MAIN

        controller.newIntent(blockedTap())

        assertEquals(Screen.PERMISSIONS, activity.screen)
        assertFalse(activity.welcomeTapBlocked)
    }

    @Test
    fun `a cold launch resumes the card the flow was left on`() {
        // The case the running instance cannot cover: the tap can arrive long
        // after the process is gone, and the flow is then rebuilt from nothing.
        WelcomeStore(context).rememberCard(WelcomeCard.RULE.name)

        val activity = Robolectric.buildActivity(MainActivity::class.java, blockedTap())
            .setup()
            .get()

        assertEquals(Screen.WELCOME, activity.screen)
        assertEquals(WelcomeCard.RULE, activity.welcomeCard)
        assertTrue(activity.welcomeTapBlocked)
    }

    @Test
    fun `a replay resumes too, though the flow has been seen`() {
        // Codex, PR #220: a replay from the help icon is neither unseen nor a
        // fresh install, so the gate that reopens the cards would refuse it and
        // a blocked tap would land on the recap — the resume kept in every case
        // except the one where the user asked for the flow deliberately.
        WelcomeStore(context).markSeen()
        WelcomeStore(context).rememberCard(WelcomeCard.ENDS.name)

        val activity = Robolectric.buildActivity(MainActivity::class.java, blockedTap())
            .setup()
            .get()

        assertEquals(Screen.WELCOME, activity.screen)
        assertEquals(WelcomeCard.ENDS, activity.welcomeCard)
        assertTrue(activity.welcomeTapBlocked)
    }

    @Test
    fun `a card this build does not show reads as no memory too`() {
        // Codex, PR #220: the telemetry card is dropped where nothing collects,
        // and a breadcrumb naming it used to come back as a card the flow does
        // not contain — `Next` did nothing and the dots read card 1 while a
        // fifth card was on screen. Robolectric has no Firebase, so this build
        // is exactly that case.
        WelcomeStore(context).rememberCard(WelcomeCard.TELEMETRY.name)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertEquals(Screen.WELCOME, activity.screen)
        assertEquals(WelcomeCard.WHAT, activity.welcomeCard)
    }

    @Test
    fun `a replay whose card this build lacks still reopens, at card one`() {
        // Codex, PR #220: a replay left on the telemetry card, reopened by a
        // build that omits it, is still a replay in progress — answering both
        // "is a flow open?" and "where?" with the validated card closed the
        // gate on it and sent the tap to the recap instead.
        WelcomeStore(context).markSeen()
        WelcomeStore(context).rememberCard(WelcomeCard.TELEMETRY.name)

        val activity = Robolectric.buildActivity(MainActivity::class.java, blockedTap())
            .setup()
            .get()

        assertEquals(Screen.WELCOME, activity.screen)
        assertEquals(WelcomeCard.WHAT, activity.welcomeCard)
        assertTrue(activity.welcomeTapBlocked)
    }

    @Test
    fun `a card name this build no longer has reads as no memory`() {
        // A card dropped in a later build must cost a restart of the flow, not
        // a crash — the stored value is a name, and nothing guarantees the next
        // build still has it.
        WelcomeStore(context).rememberCard("A_CARD_THAT_WAS_REMOVED")

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertEquals(Screen.WELCOME, activity.screen)
        assertEquals(WelcomeCard.WHAT, activity.welcomeCard)
    }

    @Test
    fun `leaving the flow forgets the card`() {
        val store = WelcomeStore(context)
        store.rememberCard(WelcomeCard.TILE.name)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.screen = Screen.WELCOME
        activity.welcomeTapBlocked = true

        activity.leaveWelcomeForTest()

        // Nothing left to resume, so the next blocked tap belongs on the recap
        // rather than back inside a flow the user has finished with.
        assertEquals(null, store.lastCard())
        assertFalse(activity.welcomeTapBlocked)
    }
}
