package app.snoozemo

import android.app.Activity
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.common.IntentSenderForResultStarter
import com.google.android.play.core.install.InstallStateUpdatedListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * The checker's two failure-shaped edges: an install that never starts, and
 * the install listener's lifetime against an activity that goes away mid-check.
 * `play`-flavor test source set, like [PlayUpdateStateTest] — this exercises
 * the real Play Core `AppUpdateManager` interface, which `direct`'s own copy
 * of [PlayUpdateChecker] never implements.
 */
@RunWith(RobolectricTestRunner::class)
class PlayUpdateCheckerTest {

    /**
     * Tapping Restart hands off to Play, and on success the app is
     * restarted — so the only outcome that comes back is a failure (a busy
     * installer, a transient Play error), where the tap visibly does
     * nothing. That must not be discarded silently (`AGENTS.md`, error
     * handling).
     */
    @Test
    fun `an install that fails to start is logged, not swallowed`() {
        ShadowLog.clear()
        val manager = FakeAppUpdateManager(
            completeUpdateResult = Tasks.forException(IllegalStateException("installer busy")),
        )
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)

        checker.completeFlexibleUpdate()
        // Play's task callbacks are posted to the main looper.
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the failed install must leave a trace",
            ShadowLog.getLogs().any { it.msg.contains("Play update install failed to start") },
        )
    }

    /**
     * The banner's own visibility into the same failure — the tap that
     * would otherwise look like it did nothing (Codex, PR #99).
     */
    @Test
    fun `an install that fails to start also notifies the caller`() {
        val manager = FakeAppUpdateManager(
            completeUpdateResult = Tasks.forException(IllegalStateException("installer busy")),
        )
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)
        var notified = false

        checker.completeFlexibleUpdate(onFailure = { notified = true })
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the caller must hear about a failed install", notified)
    }

    @Test
    fun `a successful hand-off logs no failure and does not notify`() {
        ShadowLog.clear()
        val manager = FakeAppUpdateManager(completeUpdateResult = Tasks.forResult(null))
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)
        var notified = false

        checker.completeFlexibleUpdate(onFailure = { notified = true })
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("a successful install must not notify a failure", !notified)

        assertTrue(ShadowLog.getLogs().none { it.msg.contains("Play update install failed to start") })
    }

    @Test
    fun `a check landing after the activity is gone registers no listener`() {
        // Play's check is asynchronous: a rotation while it is in flight
        // runs onDestroy's cleanup first, and the late success callback then
        // reaches registerInstallListener. Registering there would leave a
        // listener behind that nothing ever unregisters — one per
        // recreation.
        val manager = FakeAppUpdateManager(completeUpdateResult = Tasks.forResult(null))
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)

        checker.unregisterInstallListener()
        checker.registerInstallListener()

        assertEquals("a destroyed checker must register nothing", 0, manager.registrations)
    }

    @Test
    fun `a live checker registers its listener once`() {
        // The positive control: without the teardown the latch must not get
        // in the way, and a second call stays idempotent.
        val manager = FakeAppUpdateManager(completeUpdateResult = Tasks.forResult(null))
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)

        checker.registerInstallListener()
        checker.registerInstallListener()

        assertEquals(1, manager.registrations)
    }

    /**
     * Only [completeUpdate] and the listener calls are exercised here; the
     * rest satisfies the interface. Two of the request-code overloads are
     * deprecated in Play's API — we don't call them, but an implementation
     * still has to declare them.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    private class FakeAppUpdateManager(
        private val completeUpdateResult: Task<Void>,
    ) : AppUpdateManager {
        /** Live registrations: incremented on register, decremented on unregister. */
        var registrations = 0
            private set

        override fun completeUpdate(): Task<Void> = completeUpdateResult

        override fun getAppUpdateInfo(): Task<AppUpdateInfo> =
            Tasks.forException(UnsupportedOperationException("not used"))

        override fun startUpdateFlow(
            info: AppUpdateInfo,
            activity: Activity,
            options: AppUpdateOptions,
        ): Task<Int> = Tasks.forException(UnsupportedOperationException("not used"))

        override fun registerListener(listener: InstallStateUpdatedListener) {
            registrations++
        }

        override fun unregisterListener(listener: InstallStateUpdatedListener) {
            registrations--
        }

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            launcher: ActivityResultLauncher<IntentSenderRequest>,
            options: AppUpdateOptions,
        ): Boolean = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            appUpdateType: Int,
            activity: Activity,
            requestCode: Int,
        ): Boolean = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            appUpdateType: Int,
            starter: IntentSenderForResultStarter,
            requestCode: Int,
        ): Boolean = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            activity: Activity,
            options: AppUpdateOptions,
            requestCode: Int,
        ): Boolean = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            starter: IntentSenderForResultStarter,
            options: AppUpdateOptions,
            requestCode: Int,
        ): Boolean = false
    }
}
