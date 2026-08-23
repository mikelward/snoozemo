package app.snoozemo.ui

import androidx.test.core.app.ApplicationProvider
import app.snoozemo.BuildConfig
import app.snoozemo.PlayUpdateState
import app.snoozemo.UpdateProgress
import app.snoozemo.playUpdateDismissalKey
import app.snoozemo.snooze.PlayUpdateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The update banner's state machine, driven directly the same way
 * [MainActivityLifecycleTest] drives access refreshes — [PlayUpdateChecker]
 * talks to Play Services, which has no seam a JVM test can drive
 * deterministically, so these call the banner's own setters instead.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityPlayUpdateTest {

    @Test
    fun `a failed recheck does not clobber a download already in flight`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // 0 == Play Core's InstallStatus.UNKNOWN — nothing started yet.
        activity.setPlayUpdateAvailable(5, 0)
        // What `startPlayUpdate()` does the moment the user taps Update.
        activity.setPlayUpdateProgress(UpdateProgress.Starting)

        // The recheck's failure lands before the install listener's first
        // PENDING/DOWNLOADING callback — the exact race Codex flagged (PR
        // #99): a version that reset `Starting` here would snap a real,
        // in-progress download's banner back to "Update", exposing a button
        // whose cached update handle was already consumed.
        activity.setPlayUpdateCheckFailed()

        val update = activity.playUpdate as PlayUpdateState.Available
        assertEquals(
            "a failed recheck must not reset a download already in flight",
            UpdateProgress.Starting,
            update.progress,
        )
    }

    /**
     * `MainActivity` carries no state across a configuration change, so a
     * recreated instance's read of [PlayUpdateStore] is the only way it
     * learns a dismissal happened — there is no in-memory value to inherit
     * the way a retained `ViewModel` would carry one. A write deferred to a
     * background thread could lose that race to the recreation's own read
     * (Codex, PR #99); this asserts the write is visible to a completely
     * fresh [PlayUpdateStore] the instant the tap returns, which is what
     * makes that race impossible rather than merely unlikely.
     */
    @Test
    fun `a dismissal is visible to a freshly read store the instant the tap returns`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.setPlayUpdateAvailable(5, 0)

        activity.dismissPlayUpdate()

        // A brand new store instance, not the one `dismissPlayUpdate` wrote
        // through — the same seam a recreated activity's own post-first-frame
        // read would use.
        val reread = PlayUpdateStore(ApplicationProvider.getApplicationContext())
        assertEquals(
            "a fresh store read right after the tap must already see the dismissal",
            playUpdateDismissalKey(5, BuildConfig.VERSION_CODE),
            reread.dismissedVersionCode,
        )
    }

    /**
     * `checkPlayUpdate()`'s answer can land before the store's own
     * post-first-frame read has run — that read is wired up in `onStart`,
     * so `.create()` alone (unlike `.setup()`, which drives the activity all
     * the way through `onResume` and lets Robolectric's Choreographer shadow
     * drain the posted read too) reproduces the gap deterministically. A
     * `0`-means-"nothing dismissed" default would flash an already-dismissed
     * banner onto the screen for a frame before the real answer arrived and
     * hid it again (Codex, PR #99) — [MainActivity.dismissedPlayUpdateVersionCode]
     * is `Int?`, null-until-read, specifically to read that gap as
     * "not yet confirmed safe to show" rather than "confirmed not dismissed".
     */
    @Test
    fun `an update is not shown as available before dismissal state has loaded`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        activity.setPlayUpdateAvailable(5, 0)

        val update = activity.displayedPlayUpdate as PlayUpdateState.Available
        assertTrue(
            "the banner must not flash on screen before dismissal state is confirmed",
            update.isDismissed,
        )
    }
}
