package app.snoozemo.ui

import app.snoozemo.PlayUpdateState
import app.snoozemo.UpdateProgress
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * `play`-flavor test source set: exercises [MainActivity.onPlayUpdateInstallStatus]'s
 * dependence on the real `InstallStatus.FAILED`/`CANCELED` → [UpdateProgress.Idle]
 * mapping, which `direct`'s own [app.snoozemo.progressForInstallStatus] never
 * makes (it always echoes its fallback) — the race this covers can't happen
 * on that flavor, so it belongs here rather than in the shared
 * `MainActivityPlayUpdateTest`.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityPlayUpdateRecheckTest {

    /**
     * `PlayUpdateChecker.startUpdate()` clears its cached `AppUpdateInfo`
     * the moment the sheet opens — single-use, per Play's own API — and
     * nothing else refreshes it until the next `checkForUpdate()`. That
     * normally happens on the next `onResume()`, but a download that fails
     * or is canceled while the user never left this screen is heard only
     * through the install listener, with no resume in between (Codex, PR
     * #99): without a recheck triggered from there too, a repeat Update tap
     * would find no handle and silently fall back to the external Play
     * listing instead of retrying the in-app flow.
     */
    @Test
    fun `a download that fails without an intervening resume triggers a fresh check`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.setPlayUpdateAvailable(5, 0)
        activity.setPlayUpdateProgress(UpdateProgress.Downloading)

        // 5 == Play Core's InstallStatus.FAILED, heard directly from the
        // install listener rather than via another checkForUpdate() answer.
        activity.onPlayUpdateInstallStatus(5)

        // Play update checks are disabled outside release builds
        // (`BuildConfig.PLAY_UPDATE_CHECKS_ENABLED`), so the recheck this
        // triggers resolves synchronously to "nothing waiting" in this test
        // — which is exactly the signal that a recheck ran at all. Before
        // this fix, the banner stayed on `Available(progress = Idle)`
        // instead: the stale build's Update button, backed by a handle
        // Play had already consumed.
        assertEquals(
            "a FAILED/CANCELED transition with no resume in between must trigger a fresh check",
            PlayUpdateState.NotAvailable,
            activity.playUpdate,
        )
    }
}
