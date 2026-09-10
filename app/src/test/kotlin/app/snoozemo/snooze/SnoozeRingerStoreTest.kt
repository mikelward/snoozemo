package app.snoozemo.snooze

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.dnd.SnoozeRingerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The one setting this feature has (SPEC.md §5.9), and the fact that it
 * survives.
 *
 * Robolectric rather than a fake for the reason `NotificationPromptStoreTest`
 * gives: an in-memory double would pass while the write never reached disk, and
 * a ceiling that silently reverts to the default on every launch is exactly the
 * failure a user would report as "it forgot".
 */
@RunWith(RobolectricTestRunner::class)
// A plain `Application`, not `SnoozemoApplication`: its `onCreate` starts
// `reconcileRingerInBackground` on a daemon thread, and Robolectric builds the
// application for every test — so that thread races the test body for the
// process-wide ringer lock. Reaching it while no snooze record is on disk, it
// does exactly its job: drops the ceiling as stale and hands the loan back,
// under a fixture that put both there by hand. The tests below reach that path
// deliberately where they mean to; a stray copy of it running under every
// statement is what made them fail about one run in ten.
@Config(sdk = [36], application = Application::class)
class SnoozeRingerStoreTest {

    private fun newStore() = SnoozeRingerStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `writes from two threads take turns`() {
        // `SnoozeRingerSetting` writes this file on its own long-lived worker
        // while the tests here and in `AudioRingerControllerTest` write it from
        // the test thread. Two overlapping commits send the second through the
        // platform's QueuedWork, whose pending work Robolectric drops between
        // tests, parking the worker on a latch nothing will open for the rest
        // of the JVM (TODO.md) — and a rollback outside the lock can overwrite
        // a concurrent write already reported as stuck.
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val holder = thread {
            SnoozeRingerStore.holdWritesForTest {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the lock is held", holding.await(5, TimeUnit.SECONDS))
        val writer = thread {
            newStore().setChosen(SnoozeRinger.DEFAULT)
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
        assertEquals(SnoozeRinger.DEFAULT, newStore().chosen())
    }

    @Test
    fun `a fresh install is vibrate only`() {
        assertEquals(SnoozeRinger.VIBRATE, newStore().chosen())
    }

    @Test
    fun `a choice survives the instance`() {
        assertTrue(newStore().setChosen(SnoozeRinger.SILENT))

        // A new instance, because the point is the disk and not the object: the
        // screen, the service and the tile each build their own.
        assertEquals(SnoozeRinger.SILENT, newStore().chosen())
    }

    @Test
    fun `choosing ring is stored rather than read back as the default`() {
        // The one option that looks like "unset" from the outside, so it is the
        // one a store that persisted nothing would appear to handle correctly.
        assertTrue(newStore().setChosen(SnoozeRinger.RING))

        assertEquals(SnoozeRinger.RING, newStore().chosen())
    }
}
