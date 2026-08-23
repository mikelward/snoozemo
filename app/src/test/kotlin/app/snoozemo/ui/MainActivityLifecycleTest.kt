package app.snoozemo.ui

import app.snoozemo.snooze.DebugLogging
import app.snoozemo.snooze.DebugReport
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The screen's defense against acting on stale policy-access readings,
 * deferred from Codex's PR #8 review: the lifecycle check rejects a worker
 * landing while the screen is stopped, but the same instance can be started
 * again — and a reading taken in the previous visible session, landing before
 * the deferred refresh issues a fresh generation, would end a snooze armed in
 * between. Stopping must therefore invalidate every reading still in flight.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityLifecycleTest {

    @Test
    fun `stopping the screen invalidates in-flight access readings`() {
        // The paused Robolectric looper keeps the deferred refresh and the
        // workers' main-thread hops queued, so the generation observed here is
        // exactly what an in-flight reading would carry.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val issued = activity.latestAccessRefresh

        controller.pause().stop()

        assertTrue(
            "onStop must issue a new generation, or a reading taken while " +
                "visible could act after the screen is started again",
            activity.latestAccessRefresh > issued,
        )
    }

    /**
     * A share that finishes while this activity is stopped fires
     * `DebugReport.watchShareOutcome` on a callback nothing is registered
     * for — `onStop` already unregistered it — so `shareFailed` must be
     * synced from `DebugReport.lastShareFailed` again at the next `onStart`,
     * or the failure message is lost for good (Codex, PR #89).
     */
    @Test
    fun `a restart picks up a share outcome missed while stopped`() {
        DebugReport.resetForTest()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        controller.pause().stop()
        assertFalse("precondition: nothing has failed yet", activity.shareFailed)

        // The share itself still runs to completion and updates the
        // process-level outcome regardless of whether anything is watching —
        // this is what a background thread's completion looks like landing
        // after onStop.
        DebugReport.share(
            activity.applicationContext,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = {},
        )

        controller.start()

        assertTrue(
            "onStart must sync shareFailed from the outcome missed while stopped",
            activity.shareFailed,
        )
        DebugReport.resetForTest()
    }

    /**
     * Same shape as the share-outcome test above, for a Dismiss tap's own
     * refused consume: `DebugLogging.watchDismissOutcome` fires on nothing
     * while stopped, so `dismissFailed` must be synced from
     * `DebugLogging.lastDismissFailed` again at the next `onStart` (Codex,
     * PR #89).
     */
    @Test
    fun `a restart picks up a dismiss outcome missed while stopped`() {
        DebugLogging.resetForTest()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        controller.pause().stop()
        assertFalse("precondition: nothing has failed yet", activity.dismissFailed)

        val dir = File(activity.applicationContext.cacheDir, "debuglog")
        dir.mkdirs()
        File(dir, "current.log").writeText("the run that crashed")
        File(dir, "current.log.crash").writeText("1")
        DebugLogging.install(activity.applicationContext)
        DebugLogging.awaitIdleForTest()
        // Make both the rename and the copy fallback refuse, the same
        // fixture `DebugFileSinkTest`'s own refusal test uses.
        File(File(dir, "previous.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        // The dismiss itself still runs to completion and updates the
        // process-level outcome regardless of whether anything is watching —
        // this is what a background worker's completion looks like landing
        // after onStop.
        DebugLogging.dismissCrashPin()
        DebugLogging.awaitIdleForTest()

        controller.start()

        assertTrue(
            "onStart must sync dismissFailed from the outcome missed while stopped",
            activity.dismissFailed,
        )
        DebugLogging.resetForTest()
    }
}
