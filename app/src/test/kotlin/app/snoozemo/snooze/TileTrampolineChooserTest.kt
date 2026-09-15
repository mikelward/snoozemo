package app.snoozemo.snooze

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper.getMainLooper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.ui.EXTRA_TILE_CHOOSER
import app.snoozemo.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * With "ask when to unsnooze" on, an arm tap opens the main screen as the
 * end-condition chooser instead of arming (SPEC.md §4.4; [EXTRA_TILE_CHOOSER]).
 *
 * The chooser is choose-then-arm: the tap must NOT start the service, since the
 * user's row tap on that screen is what arms. So each case asserts both which
 * activity was started AND whether a service start was enqueued — the service
 * never runs under Robolectric, so this is the trampoline's own decision.
 */
@RunWith(RobolectricTestRunner::class)
class TileTrampolineChooserTest {

    private val appContext: Application = ApplicationProvider.getApplicationContext()

    private var controller: ActivityController<TileTrampolineActivity>? = null

    @Before
    fun setUp() {
        // The cache is process-static, so a prior test's value would decide this
        // one's routing; start from "off" and let each case set what it needs.
        EndSheetStore.resetCacheForTest()
        EndSheetStore(appContext).setEnabled(false)
        TestSnoozeService.reset(java.time.Instant.ofEpochMilli(System.currentTimeMillis()))
        shadowOf(appContext.getSystemService(KeyguardManager::class.java))
            .setKeyguardLocked(false)
        shadowOf(appContext).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(appContext.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        EndSheetStore.resetCacheForTest()
    }

    private fun tap(action: String): TileTrampolineActivity {
        val intent = Intent(appContext, TileTrampolineActivity::class.java).setAction(action)
        return Robolectric.buildActivity(TileTrampolineActivity::class.java, intent)
            .also { controller = it }
            // Post-onboarding: an arm tap during the unfinished welcome flow
            // resumes it rather than reaching the chooser (SPEC.md §4.2), so the
            // flow is finished here. Published after setUp's warm-up and before
            // this activity's onCreate reads the gate.
            .also { WelcomeGate.publish(false) }
            .setup()
            .also { shadowOf(getMainLooper()).idle() }
            .get()
    }

    private fun startedActivity(): Intent? = shadowOf(appContext).nextStartedActivity
    private fun startedService(): Intent? = shadowOf(appContext).nextStartedService

    @Test
    fun `an arm with the chooser on opens the chooser and does not arm`() {
        EndSheetStore(appContext).setEnabled(true)

        tap(SnoozeService.ACTION_ARM)

        val opened = startedActivity()
        assertEquals(MainActivity::class.java.name, opened?.component?.className)
        assertTrue(
            "the launch is flagged as the tile chooser",
            opened?.getBooleanExtra(EXTRA_TILE_CHOOSER, false) == true,
        )
        assertNull("choose-then-arm: nothing is armed here", startedService())
    }

    @Test
    fun `an arm with the chooser off arms and opens nothing`() {
        EndSheetStore(appContext).setEnabled(false)

        tap(SnoozeService.ACTION_ARM)

        assertNull("no chooser when the setting is off", startedActivity())
        assertEquals(
            "the instant-arm path still starts the service",
            SnoozeService::class.java.name,
            startedService()?.component?.className,
        )
    }

    @Test
    fun `an arm behind the keyguard still opens the chooser, not a keyguard query`() {
        // Telling a locked tap apart would need `isKeyguardLocked` before the
        // arm, which §6.9 keeps off the arm path (Codex, PR #284). So the chooser
        // opens either way — it shows after the user unlocks — and nothing is
        // armed here. The instant locked arm stays the off default, where this
        // branch never runs.
        EndSheetStore(appContext).setEnabled(true)
        shadowOf(appContext.getSystemService(KeyguardManager::class.java))
            .setKeyguardLocked(true)

        tap(SnoozeService.ACTION_ARM)

        assertEquals(MainActivity::class.java.name, startedActivity()?.component?.className)
        assertNull("choose-then-arm: nothing is armed here", startedService())
    }

    @Test
    fun `a chooser that cannot open falls back to an instant arm`() {
        // If opening the app as the chooser throws (nothing can host it), the
        // Ask-on tap must not silently do nothing — it falls back to the instant
        // arm so a user who tapped to snooze still gets one (SPEC.md §4.1). The
        // fallback routes through the same start-and-recover the Ask-off arm uses,
        // so a refused fallback arm is remembered and surfaced rather than only
        // logged (Codex, PR #284); the refused branch itself has no Robolectric
        // seam — `startService` always succeeds here — so this covers the arm.
        EndSheetStore(appContext).setEnabled(true)
        shadowOf(appContext).checkActivities(true)
        appContext.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(appContext, MainActivity::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )

        tap(SnoozeService.ACTION_ARM)

        assertEquals(
            "the fallback still arms when the chooser cannot open",
            SnoozeService::class.java.name,
            startedService()?.component?.className,
        )
    }

    @Test
    fun `ending never opens the chooser`() {
        // The chooser is the way in; a user ending a snooze is on their way out.
        EndSheetStore(appContext).setEnabled(true)

        tap(SnoozeService.ACTION_END)

        assertNull(startedActivity())
        assertEquals(
            SnoozeService::class.java.name,
            startedService()?.component?.className,
        )
    }
}
