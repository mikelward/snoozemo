package app.snoozemo.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The two properties the type exists for: writes to one file take turns, and
 * writes to different files do not.
 *
 * The first is what keeps every commit on `SharedPreferences`' inline path, so
 * none is handed to `QueuedWork` for Robolectric to drop — see the class's own
 * KDoc for the stall that cost four diagnoses. The second is why the lock is
 * per file rather than one global one, and it is the half a single-lock
 * implementation would silently pass the first test with.
 */
@RunWith(RobolectricTestRunner::class)
class SerializedPreferencesTest {

    private fun prefs(fileName: String) =
        SerializedPreferences(ApplicationProvider.getApplicationContext(), fileName)

    @Test
    fun `a write to a held file waits for the write in flight`() {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val holder = thread {
            SerializedPreferences.holdWritesForTest("held") {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the lock is held", holding.await(5, TimeUnit.SECONDS))

        val writer = thread {
            prefs("held").putBoolean("key", true)
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
        assertTrue(prefs("held").getBoolean("key", false))
    }

    @Test
    fun `a write scope holds the lock across every commit inside it`() {
        // The scope is what makes a restore-on-refusal write one operation.
        // Serializing only the commits lets two threads interleave as *read,
        // write (refused), [another thread writes and is told it stuck], roll
        // back* — the second caller's setting lost the instant it is made, and
        // it was told otherwise (Codex, PR #246).
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val other = CountDownLatch(1)
        val holder = thread {
            prefs("scoped").write {
                // Two commits inside one scope, as every store's write has.
                prefs("scoped").putBoolean("first", true)
                inside.countDown()
                release.await(30, TimeUnit.SECONDS)
                prefs("scoped").putBoolean("second", true)
            }
        }
        assertTrue("precondition: the scope is open", inside.await(5, TimeUnit.SECONDS))

        val writer = thread {
            prefs("scoped").putBoolean("outside", true)
            other.countDown()
        }
        try {
            assertFalse(
                "another thread cannot commit between the scope's own commits",
                other.await(500, TimeUnit.MILLISECONDS),
            )
        } finally {
            release.countDown()
        }
        assertTrue("and goes through once the scope closes", other.await(5, TimeUnit.SECONDS))
        holder.join()
        writer.join()
    }

    @Test
    fun `a write to another file is not held up`() {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val holder = thread {
            SerializedPreferences.holdWritesForTest("held") {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the lock is held", holding.await(5, TimeUnit.SECONDS))

        val writer = thread {
            prefs("unrelated").putBoolean("key", true)
            done.countDown()
        }
        try {
            assertTrue(
                "an unrelated file's write goes straight through",
                done.await(5, TimeUnit.SECONDS),
            )
        } finally {
            release.countDown()
        }
        holder.join()
        writer.join()
    }

    @Test
    fun `two instances naming one file share its lock`() {
        // The stores are constructed freely — `CrashReporting` builds a fresh
        // `CrashReportingStore` inside each worker task — so an
        // instance-scoped lock would serialize nothing at all.
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val first = prefs("shared")
        val second = prefs("shared")
        val holder = thread {
            SerializedPreferences.holdWritesForTest("shared") {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the lock is held", holding.await(5, TimeUnit.SECONDS))

        assertTrue("precondition: the instances are distinct", first !== second)
        val writer = thread {
            second.putBoolean("key", true)
            done.countDown()
        }
        try {
            assertFalse("the second instance waits too", done.await(500, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        holder.join()
        writer.join()
        assertTrue(first.getBoolean("key", false))
    }
}
