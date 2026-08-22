package app.snoozemo.ui

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
}
