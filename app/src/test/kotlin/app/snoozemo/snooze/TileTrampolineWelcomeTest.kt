package app.snoozemo.snooze

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper.getMainLooper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.ui.EXTRA_OPEN_PERMISSIONS
import app.snoozemo.ui.EXTRA_TILE_CHOOSER
import app.snoozemo.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * A tile tap during the unfinished welcome flow resumes it rather than arming
 * (SPEC.md §4.2; [WelcomeGate]).
 *
 * Even a tap that *could* arm — everything granted below — is taken back to the
 * flow, so a user part-way through the cards is not left with a snooze started
 * mid-setup. The decision is the trampoline's, read from the warmed gate on the
 * arm path, so each case asserts both which activity was started AND whether a
 * service start was enqueued — the service never runs under Robolectric, so this
 * is the trampoline's own routing.
 */
@RunWith(RobolectricTestRunner::class)
class TileTrampolineWelcomeTest {

    private val appContext: Application = ApplicationProvider.getApplicationContext()

    private var controller: ActivityController<TileTrampolineActivity>? = null

    @Before
    fun setUp() {
        // Both caches are process-static, so a prior test's value would decide
        // this one's routing; start each from a known state.
        WelcomeGate.publish(false)
        EndSheetStore.resetCacheForTest()
        EndSheetStore(appContext).setEnabled(false)
        TestSnoozeService.reset(java.time.Instant.ofEpochMilli(System.currentTimeMillis()))
        // Everything a tap needs to actually arm, so a redirect can only be the
        // welcome gate rather than a missing grant.
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
        WelcomeGate.publish(false)
        EndSheetStore.resetCacheForTest()
    }

    private fun tap(action: String): TileTrampolineActivity {
        val intent = Intent(appContext, TileTrampolineActivity::class.java).setAction(action)
        return Robolectric.buildActivity(TileTrampolineActivity::class.java, intent)
            .also { controller = it }
            .setup()
            .also { shadowOf(getMainLooper()).idle() }
            .get()
    }

    private fun startedActivity(): Intent? = shadowOf(appContext).nextStartedActivity
    private fun startedService(): Intent? = shadowOf(appContext).nextStartedService

    @Test
    fun `an arm during the unfinished flow resumes it and does not snooze`() {
        WelcomeGate.publish(true)

        tap(SnoozeService.ACTION_ARM)

        val opened = startedActivity()
        assertEquals(MainActivity::class.java.name, opened?.component?.className)
        assertTrue(
            "the app is opened to resume the flow",
            opened?.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false) == true,
        )
        assertNull("nothing is armed during the flow", startedService())
    }

    @Test
    fun `an arm after the flow is finished arms as normal`() {
        WelcomeGate.publish(false)

        tap(SnoozeService.ACTION_ARM)

        assertNull("no app is opened once the flow is done", startedActivity())
        assertEquals(
            "the instant-arm path starts the service",
            SnoozeService::class.java.name,
            startedService()?.component?.className,
        )
    }

    @Test
    fun `ending during the unfinished flow still ends`() {
        // Only ARM is redirected: a rare snooze running during onboarding must
        // still be endable, so the phone is never left quiet (principle 1).
        WelcomeGate.publish(true)

        tap(SnoozeService.ACTION_END)

        assertNull("ending is not a resume", startedActivity())
        assertEquals(
            SnoozeService::class.java.name,
            startedService()?.component?.className,
        )
    }

    @Test
    fun `resuming the flow falls back to an instant arm if the app cannot open`() {
        // The resume branch starts no service, so a launch that throws would
        // leave the tap doing nothing — the silent failure principle 2 forbids.
        // It falls back to the instant arm instead, as the chooser does (Codex,
        // PR #291). The refused-arm recovery has no Robolectric seam
        // (`startService` always succeeds here), so this covers the arm.
        WelcomeGate.publish(true)
        shadowOf(appContext).checkActivities(true)
        appContext.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(appContext, MainActivity::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )

        tap(SnoozeService.ACTION_ARM)

        assertEquals(
            "the tap does something rather than nothing when the app cannot open",
            SnoozeService::class.java.name,
            startedService()?.component?.className,
        )
    }

    @Test
    fun `resuming the flow takes precedence over the chooser`() {
        // The welcome check runs before the "ask when to unsnooze" branch, so an
        // arm during the flow resumes it rather than opening the chooser — even
        // with that setting on.
        WelcomeGate.publish(true)
        EndSheetStore(appContext).setEnabled(true)

        tap(SnoozeService.ACTION_ARM)

        val opened = startedActivity()
        assertEquals(MainActivity::class.java.name, opened?.component?.className)
        assertTrue(
            "the flow resumes, not the chooser",
            opened?.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false) == true,
        )
        assertFalse(
            "and it is not flagged as the chooser",
            opened?.getBooleanExtra(EXTRA_TILE_CHOOSER, false) == true,
        )
        assertNull("nothing is armed during the flow", startedService())
    }
}
