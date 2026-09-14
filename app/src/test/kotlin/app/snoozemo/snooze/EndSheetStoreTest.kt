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
class EndSheetStoreTest {

    private val store get() = EndSheetStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `writes from two threads take turns`() {
        // The same serialization `DebugLogStore` was given after the
        // `ProcessExitReasonsTest` stall (TODO.md), and for the same exposure:
        // `EndSheetSetting` writes this file on its own long-lived worker while
        // tests write it from the test thread. Two overlapping commits send
        // the second through the platform's QueuedWork, whose pending work
        // Robolectric drops between tests, and the worker is then parked on a
        // latch nothing will open for the rest of the JVM.
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val holder = thread {
            EndSheetStore.holdWritesForTest {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the lock is held", holding.await(5, TimeUnit.SECONDS))
        val writer = thread {
            store.setEnabled(true)
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
        assertTrue(store.isEnabled())
    }

    @Test
    fun `the tile does not ask by default`() {
        // The maintainer decision behind SPEC.md §4.4's gate, and the opposite
        // of the debug log's: one tap from the shade with nothing in the way is
        // goal 1, so the sheet is something a user opts into.
        assertFalse(store.isEnabled())
    }

    @Test
    fun `the choice persists`() {
        assertTrue(store.setEnabled(true))
        assertTrue(store.isEnabled())
        assertTrue(store.setEnabled(false))
        assertFalse(store.isEnabled())
    }

    @Test
    fun `a completed write reaches a screen that replaced the one which asked`() {
        // A configuration change destroys the screen mid-write. Its own callback
        // can only reach the dead activity, so without a process-level watch the
        // replacement shows the pre-tap value until the next launch (Codex,
        // PR #118).
        val replacement = java.util.concurrent.CountDownLatch(1)
        val watch = EndSheetSetting.watchSaveOutcome { replacement.countDown() }

        try {
            EndSheetSetting.setEnabled(appContext, enabled = true) {}

            assertTrue(
                "the replacement screen has to hear that the write finished",
                replacement.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertTrue(store.isEnabled())
            assertFalse("and a write that stuck is not reported as refused", EndSheetSetting.lastSaveRefused)
        } finally {
            watch.close()
        }
    }

    @Test
    fun `the cache defaults off until warmed`() {
        // The tile reads this without a disk hit (SPEC.md §6.9), and an unwarmed
        // read must fall back to "off" so a cold tap arms instantly rather than
        // waiting.
        EndSheetStore.resetCacheForTest()

        assertFalse(EndSheetStore.cachedEnabled())
    }

    @Test
    fun `the cache follows a write, so the next tap sees the toggle`() {
        EndSheetStore.resetCacheForTest()

        store.setEnabled(true)
        assertTrue(EndSheetStore.cachedEnabled())

        store.setEnabled(false)
        assertFalse(EndSheetStore.cachedEnabled())
    }

    @Test
    fun `a read warms the cache`() {
        EndSheetStore.resetCacheForTest()
        store.setEnabled(true)
        EndSheetStore.resetCacheForTest()

        store.isEnabled()

        assertTrue(EndSheetStore.cachedEnabled())
    }

    @Test
    fun `a stale read does not clobber a newer write`() {
        // The warm-up read and a settings write can overlap: isEnabled reads the
        // old value, and before it publishes to the cache a write commits the new
        // one. The stale read must not overwrite it, or the next tile tap takes
        // the opposite route to the toggle the user just made (Codex, PR #284).
        EndSheetStore.resetCacheForTest()
        store.setEnabled(false)
        EndSheetStore.resetCacheForTest()

        // Interpose the write between the read's disk read and its publish, the
        // exact ordering the race needs — deterministic, not timing-based.
        EndSheetStore.afterReadBeforePublishForTest = {
            EndSheetStore.afterReadBeforePublishForTest = null // once; setEnabled reads too
            store.setEnabled(true)
        }
        try {
            store.isEnabled()
        } finally {
            EndSheetStore.afterReadBeforePublishForTest = null
        }

        assertTrue("the newer write wins over the stale read", EndSheetStore.cachedEnabled())
    }

    @Test
    fun `a toggle publishes to the cache before the disk write completes`() {
        // A tile tap between flipping the setting and the FIFO disk write
        // finishing must route by the new value, so the choice is published to the
        // cache on the calling thread up front, ahead of the worker (Codex,
        // PR #284).
        EndSheetStore.resetCacheForTest()
        store.setEnabled(false)             // start from off, on disk and in cache
        EndSheetStore.resetCacheForTest()   // cache back to the unwarmed default

        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            EndSheetStore.holdWritesForTest {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the write lock is held", holding.await(5, TimeUnit.SECONDS))
        try {
            EndSheetSetting.setEnabled(appContext, enabled = true) {}
            // The worker's write is parked on the held lock, so a true cache here
            // can only be the up-front optimistic publish, not the write's.
            assertTrue(
                "the toggle is visible to a tap before the write lands",
                EndSheetStore.cachedEnabled(),
            )
        } finally {
            release.countDown()
        }
        holder.join()
        assertTrue("the queued write drains", EndSheetSetting.awaitIdleForTest())
        assertTrue("and the cache still reflects the choice", EndSheetStore.cachedEnabled())
    }

    @Test
    fun `it keeps its own file, so the debug log's switch is not this one`() {
        // Two one-key boolean stores with the same key name: a shared file would
        // make each switch silently move the other.
        store.setEnabled(true)
        DebugLogStore(ApplicationProvider.getApplicationContext()).setEnabled(false)

        assertTrue(store.isEnabled())
    }
}
