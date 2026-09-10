package app.snoozemo.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.DEFAULT_FONT_SCALE
import app.snoozemo.core.MAX_FONT_SCALE
import app.snoozemo.storage.SerializedPreferences
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

/** Where the chosen text size is kept (`SPEC.md` §4.8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontSizeStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = FontSizeStore(context, FILE)

    @Test
    fun `writes from two threads take turns`() {
        // `FontSizeSetting` writes this file on its own worker while a pinch or
        // a slider drag is still on screen, so a second writer can overlap it.
        // Two overlapping commits send the second through the platform's
        // QueuedWork, whose pending work Robolectric drops between tests,
        // parking the worker on a latch nothing will open for the rest of the
        // JVM — the `ProcessExitReasonsTest` stall (TODO.md).
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val holder = thread {
            SerializedPreferences.holdWritesForTest(FILE) {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the lock is held", holding.await(5, TimeUnit.SECONDS))
        val writer = thread {
            store().setScale(MAX_FONT_SCALE)
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
        assertEquals(MAX_FONT_SCALE, store().read().scale, 0.0001f)
    }

    @Test
    fun `an install that has never chosen reads the system size, with pinch on`() {
        val read = store().read()
        assertEquals(DEFAULT_FONT_SCALE, read.scale, 0.0001f)
        assertTrue("pinch is how the setting is found at all", read.pinchEnabled)
    }

    @Test
    fun `a chosen size survives to a later reader`() {
        assertTrue(store().setScale(1.23f))
        // A separate instance, as a cold process would be.
        assertEquals(1.23f, store().read().scale, 0.0001f)
    }

    @Test
    fun `a size beyond this build's range is clamped on read`() {
        // Written by a build with a wider range, or by hand: it must not size a
        // screen past what this one lays out.
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat("scale", 4f).commit()
        assertEquals(MAX_FONT_SCALE, store().read().scale, 0.0001f)
    }

    @Test
    fun `turning the pinch off is remembered on its own`() {
        assertTrue(store().setScale(1.4f))
        assertTrue(store().setPinchEnabled(false))
        val read = store().read()
        assertEquals(1.4f, read.scale, 0.0001f)
        assertEquals(false, read.pinchEnabled)
    }

    private companion object {
        // Its own file, so these assertions aren't racing the running app's.
        const val FILE = "font_size_test"
    }
}
