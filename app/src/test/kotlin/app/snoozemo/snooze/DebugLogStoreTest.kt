package app.snoozemo.snooze

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class DebugLogStoreTest {

    private val store get() = DebugLogStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `the log is on by default`() {
        // The maintainer decision behind SPEC.md §4.6: off-by-default
        // guarantees the first occurrence of every unrepeatable failure is
        // the one nobody captured.
        assertTrue(store.isEnabled())
    }

    @Test
    fun `writes from two threads take turns`() {
        // `commit()` writes inline only as the file's sole write in flight; two
        // overlapping commits send the second through the platform's QueuedWork,
        // whose pending work Robolectric drops between tests — the debug-log
        // worker then waits on a latch nothing will open, which was the
        // `ProcessExitReasonsTest` stall (TODO.md). Serializing the store's
        // writes keeps every commit on the inline path, so a write from the
        // test thread can overlap the worker's startup install safely.
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val holder = thread {
            DebugLogStore.holdWritesForTest {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the lock is held", holding.await(5, TimeUnit.SECONDS))
        val writer = thread {
            store.setEnabled(false)
            done.countDown()
        }
        try {
            assertFalse("a write waits for the one in flight", done.await(500, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        assertTrue("and goes through once that one has finished", done.await(5, TimeUnit.SECONDS))
        holder.join()
        writer.join()
        assertFalse(store.isEnabled())
    }

    @Test
    fun `the choice persists`() {
        assertTrue(store.setEnabled(false))
        assertFalse(store.isEnabled())
        assertTrue(store.setEnabled(true))
        assertTrue(store.isEnabled())
    }
}
