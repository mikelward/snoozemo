package app.snoozemo.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rotation path: a tap's own completion callback can only reach the
 * instance that made it, so a configuration change mid-write leaves the
 * replacement screen showing the pre-tap value and swallowing a refused save
 * (Codex, PR #113 — the same failure `DebugLogging` already carries
 * `watchSaveOutcome` for). The watch is what corrects the replacement, so
 * these pin that it fires and that the store is settled by the time it does.
 *
 * Latches rather than sleeps: the write runs on `CrashReporting`'s own FIFO
 * worker, so the ordering is made explicit instead of waited out.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReportingWatchTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Writes the setting and **waits for the worker to finish it**.
     *
     * `CrashReporting.setEnabled` returns as soon as it has queued the work on
     * a FIFO worker shared by every test in this class. A restore left
     * unawaited therefore lands whenever it lands — including after the next
     * test has registered its watch and asked for the opposite value, which
     * fires that watch on the previous test's write and reads the wrong
     * setting back out. Awaiting makes the ordering explicit rather than
     * leaving it to how fast the runner happens to be.
     */
    private fun setEnabledAndWait(enabled: Boolean) {
        val done = CountDownLatch(1)
        CrashReporting.setEnabled(context, enabled = enabled) { done.countDown() }
        assertTrue("the crash-reporting write never completed", done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the watch fires with the store already settled`() {
        // What the replacement screen reads when the watch wakes it. Captured
        // inside the callback, because reading after the fact would pass even
        // if the watch had fired before the write landed — which is the bug.
        var seenByWatch: Boolean? = null
        val fired = CountDownLatch(1)
        val watch = CrashReporting.watchSaveOutcome {
            seenByWatch = CrashReportingStore(context).isEnabled()
            fired.countDown()
        }

        try {
            CrashReporting.setEnabled(context, enabled = false) {}
            assertTrue("the watch never fired", fired.await(5, TimeUnit.SECONDS))
            assertEquals(false, seenByWatch)
            assertFalse(CrashReporting.lastSaveRefused)
        } finally {
            watch.close()
            setEnabledAndWait(enabled = true)
        }
    }

    @Test
    fun `closing the handle unregisters that registration only`() {
        val first = CrashReporting.watchSaveOutcome { }
        val secondFired = CountDownLatch(1)
        val second = CrashReporting.watchSaveOutcome { secondFired.countDown() }

        try {
            // The earlier handle must not unregister the later registrant —
            // otherwise a screen replaced mid-write would silently lose its
            // own watch to the dead instance's `onStop`.
            first.close()
            CrashReporting.setEnabled(context, enabled = false) {}
            assertTrue(
                "closing the first handle unregistered the second watch",
                secondFired.await(5, TimeUnit.SECONDS),
            )
        } finally {
            second.close()
            setEnabledAndWait(enabled = true)
        }
    }
}
