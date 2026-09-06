package app.snoozemo.snooze

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper.getMainLooper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.ui.EXTRA_OPEN_PERMISSIONS
import app.snoozemo.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * A tile tap that cannot produce a snooze opens the app (`SPEC.md` §4.1).
 *
 * The failure this covers is silent by construction: without Do Not Disturb
 * access the arm sets no rule, and the `Couldn't snooze` card that would say so
 * needs the very notification permission the other half of the gate is about.
 * Tapping the tile then does *nothing at all* — no snooze, no explanation — and
 * nothing in the app says why. Every case here asserts on which activity the tap
 * started, because that is the whole of what the fix does.
 *
 * The service never runs under Robolectric, so these assert the trampoline's own
 * decision rather than an arm outcome.
 */
@RunWith(RobolectricTestRunner::class)
class TileTrampolineSetupTest {

    private val appContext: Application = ApplicationProvider.getApplicationContext()

    private var controller: ActivityController<TileTrampolineActivity>? = null

    @Before
    fun setUp() {
        TestSnoozeService.reset(java.time.Instant.ofEpochMilli(System.currentTimeMillis()))
        // Unlocked unless a test says otherwise: the gate is skipped behind the
        // keyguard, so a leaked lock would make every routing case here pass for
        // the wrong reason.
        shadowOf(appContext.getSystemService(KeyguardManager::class.java))
            .setKeyguardLocked(false)
        // Granted, so a missing permission never sends the tap down the prompt
        // branch instead of the one under test. The cases that are *about* the
        // permission set it themselves.
        shadowOf(appContext).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        setPolicyAccess(true)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
    }

    private fun setPolicyAccess(granted: Boolean) {
        shadowOf(appContext.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(granted)
    }

    private fun tapTile(): TileTrampolineActivity {
        val intent = Intent(appContext, TileTrampolineActivity::class.java)
            .setAction(SnoozeService.ACTION_ARM)
        return Robolectric.buildActivity(TileTrampolineActivity::class.java, intent)
            .also { controller = it }
            .setup()
            .also { shadowOf(getMainLooper()).idle() }
            .get()
    }

    /** The intent this tap started an activity with, or null if it started none. */
    private fun startedIntent(): Intent? = shadowOf(appContext).nextStartedActivity

    /** The activity this tap started, or null if it started none. */
    private fun startedActivity(): String? = startedIntent()?.component?.className

    @Test
    fun `a tap without policy access opens the app`() {
        setPolicyAccess(false)

        tapTile()

        assertEquals(MainActivity::class.java.name, startedActivity())
    }

    @Test
    fun `a tap that opens the app asks for the setup screen`() {
        // The destination is the whole point, and it has to be explicit: the
        // app routes to the setup screen on its own only for missing Do Not
        // Disturb access, and the main screen has no notification row at all.
        // A plain launch would answer a tap blocked by notifications with the
        // ordinary arm screen and nothing saying why (Codex, PR #215).
        setPolicyAccess(false)

        tapTile()

        assertEquals(
            true,
            startedIntent()?.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false),
        )
    }

    @Test
    fun `a tap with everything granted opens nothing`() {
        // The other direction, and the one that matters most: the gate must not
        // interrupt a working snooze. Without this pair the assertion above
        // would pass on a trampoline that opened the app on every tap.
        tapTile()

        assertNull(startedActivity())
    }

    @Test
    fun `a tap behind the keyguard opens nothing`() {
        // A screen started from behind the lock screen is not seen now and
        // surfaces later with no connection to the tap that caused it — worse
        // than the silent failure it was meant to explain. Arming locked is a
        // supported case, and there the ongoing notification is the only report
        // available.
        setPolicyAccess(false)
        shadowOf(appContext.getSystemService(KeyguardManager::class.java))
            .setKeyguardLocked(true)

        tapTile()

        assertNull(startedActivity())
    }

    @Test
    fun `an end tap is never routed`() {
        // Only the way in. A user ending a snooze is on their way out of the
        // app's way, and a screen in front of that is the opposite of "always
        // available, always instant" (SPEC.md §7).
        setPolicyAccess(false)
        val intent = Intent(appContext, TileTrampolineActivity::class.java)
            .setAction(SnoozeService.ACTION_END)
        Robolectric.buildActivity(TileTrampolineActivity::class.java, intent)
            .also { controller = it }
            .setup()
            .also { shadowOf(getMainLooper()).idle() }

        assertNull(startedActivity())
    }
}
