package app.snoozemo.snooze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What `awaitWarmForTest` does when the warm-up does not finish in time
 * (Codex, PR #217).
 *
 * `MainActivityNotificationBannerTest` deletes a notification channel and
 * asserts it reads as missing, so it needs the startup warm-up to be *over*,
 * not merely waited for: `Thread.join(millis)` returns normally whether or not
 * the thread ended, and a runner slow enough to hit that would put the race
 * back with nothing saying so. Failing there is the honest outcome, and this
 * is what pins it.
 *
 * The wedged thread is a latch this test releases in `@After`, rather than a
 * real warm-up: no real one can be made to hang on demand, and a thread left
 * parked would leak into every later test in the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeNotificationsWarmJoinTest {

    private val release = CountDownLatch(1)
    private var wedged: Thread? = null

    @After
    fun releaseWedgedThread() {
        release.countDown()
        wedged?.join(WEDGED_JOIN_MILLIS)
        SnoozeNotifications.setWarmThreadForTest(null)
    }

    @Test
    fun `a warm-up that outlasts the wait fails the test rather than returning`() {
        wedge()

        try {
            SnoozeNotifications.awaitWarmForTest(timeoutMillis = SHORT_WAIT_MILLIS)
            fail("Expected the wait to fail while the warm-up was still running")
        } catch (expected: IllegalStateException) {
            // The message is what tells whoever hits this on CI why their
            // channel assertion cannot be trusted, so it is part of the
            // behavior rather than incidental.
            assertTrue(
                "Unhelpful failure: ${expected.message}",
                expected.message.orEmpty().contains("still running"),
            )
        }
    }

    @Test
    fun `a warm-up that has finished passes`() {
        // The other direction: without this the case above would pass on a
        // wait that failed unconditionally, which would redden every test that
        // calls it.
        val finished = Thread {}.apply { start() }
        SnoozeNotifications.setWarmThreadForTest(finished)

        SnoozeNotifications.awaitWarmForTest(timeoutMillis = WEDGED_JOIN_MILLIS)
    }

    private fun wedge() {
        val thread = Thread { release.await(WEDGED_JOIN_MILLIS, TimeUnit.MILLISECONDS) }
            .apply { isDaemon = true; start() }
        wedged = thread
        SnoozeNotifications.setWarmThreadForTest(thread)
    }

    private companion object {
        /** Short enough that the failing case does not slow the suite down. */
        const val SHORT_WAIT_MILLIS = 50L

        /** Generous, because it is only ever waited on after the latch is released. */
        const val WEDGED_JOIN_MILLIS = 10_000L
    }
}
