package app.snoozemo.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.dnd.SnoozeRingerStore
import app.snoozemo.snooze.DebugLogStore
import app.snoozemo.snooze.DebugLogging
import app.snoozemo.snooze.EndSheetSetting
import app.snoozemo.snooze.EndSheetStore
import app.snoozemo.snooze.SnoozeRingerSetting
import app.snoozemo.snooze.WelcomeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The write counters that hold `MainActivity`'s settings reads off while a
 * tap is still on its worker.
 *
 * Each of these rows shows the tapped value at once and writes it in the
 * background, so the read that runs after every start would repaint the
 * *stored* value — the one the tap is replacing — over a tap that has not
 * landed yet. `askWhenToUnsnoozeWrites`, `snoozeRingerWrites` and
 * `debugLogWrites` are what stop that, and until now they were held by
 * inspection and symmetry rather than by a test: there was no way to keep a
 * write in flight across a stop/start.
 *
 * There is now, and it came from the store locks (PR #246): holding a file's
 * write lock parks the worker inside the store's own commit, which is exactly
 * the window between the tap and the completion callback.
 *
 * **Each row gets both halves**, because the gate is the difference between
 * them and a test of the held case alone would pass just as well if the read
 * never ran at all — the read is behind a frame callback and a `post`, so
 * "did it run" is a real question, not a formality. The control proves it
 * runs; the gated case proves it declines.
 *
 * The crash-reporting card's read is the fourth of these and is **not** here:
 * it is gated on `CrashReporting.isAvailable`, which reads false under
 * Robolectric because `FirebaseApp.getApps` is empty (`CrashReporterTest`
 * documents that), so the branch cannot be entered at all from a unit test.
 * `TODO.md` carries what covering it would take.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityStoreReadGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Past the welcome flow, so the settings rows are the screen being
        // started rather than a card in front of them.
        WelcomeStore(context).markSeen()
    }

    @Test
    fun `a restart with nothing in flight repaints the switch from the store`() {
        EndSheetStore(context).setEnabled(false)
        val controller = launch()

        // Changed underneath the screen, with no tap and so nothing on a
        // worker: what the restart reads is the store, and this is what proves
        // the read runs at all.
        EndSheetStore(context).setEnabled(true)

        restart(controller)

        assertEquals(true, controller.get().askWhenToUnsnooze)
    }

    @Test
    fun `a restart while a switch write is in flight keeps the tapped value`() {
        EndSheetStore(context).setEnabled(false)
        val controller = launch()

        holdingWritesTo(EndSheetStore.Companion::holdWritesForTest, EndSheetSetting::awaitIdleForTest) {
            controller.get().setAskWhenToUnsnooze(true)
            restart(controller)

            assertEquals(
                "the stored `false` must not repaint over a tap still on the worker",
                true,
                controller.get().askWhenToUnsnooze,
            )
        }
    }

    @Test
    fun `a restart with nothing in flight repaints the ringer from the store`() {
        SnoozeRingerStore(context).setChosen(SnoozeRinger.VIBRATE)
        val controller = launch()

        SnoozeRingerStore(context).setChosen(SnoozeRinger.DEFAULT)

        restart(controller)

        assertEquals(SnoozeRinger.DEFAULT, controller.get().snoozeRinger)
    }

    @Test
    fun `a restart while a ringer write is in flight keeps the tapped value`() {
        SnoozeRingerStore(context).setChosen(SnoozeRinger.VIBRATE)
        val controller = launch()

        holdingWritesTo(SnoozeRingerStore.Companion::holdWritesForTest, SnoozeRingerSetting::awaitIdleForTest) {
            controller.get().chooseSnoozeRinger(SnoozeRinger.DEFAULT)
            restart(controller)

            assertEquals(SnoozeRinger.DEFAULT, controller.get().snoozeRinger)
        }
    }

    @Test
    fun `a restart with nothing in flight repaints the debug log from the store`() {
        DebugLogStore(context).setEnabled(true)
        val controller = launch()

        // This row's worker, as well as the main looper: the debug log writes
        // its own store from startup work, so a value set before that lands is
        // simply overwritten and this test would be asserting nothing.
        assertTrue("precondition: the debug-log worker drained", DebugLogging.awaitIdleForTest())
        DebugLogStore(context).setEnabled(false)

        restart(controller)

        assertEquals(false, controller.get().debugLogEnabled)
    }

    @Test
    fun `a restart while a debug-log write is in flight keeps the tapped value`() {
        DebugLogStore(context).setEnabled(false)
        val controller = launch()

        holdingWritesTo(DebugLogStore.Companion::holdWritesForTest, DebugLogging::awaitIdleForTest) {
            controller.get().setDebugLog(true)
            restart(controller)

            assertEquals(true, controller.get().debugLogEnabled)
        }
    }

    private fun launch(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup().also { drainWrites() }

    /** A stop and a start, with the after-first-frame read allowed to run. */
    private fun restart(controller: ActivityController<MainActivity>) {
        controller.pause().stop().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Lets any callback already queued reach the main thread. */
    private fun drainWrites() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Runs [body] with [hold]'s file lock held on another thread, so a write
     * dispatched inside it parks on the worker and its counter stays non-zero.
     *
     * [drain] is not optional and is the reason this helper exists rather than
     * three copies of the latch dance: the settings workers are process-wide
     * and outlive a Robolectric test method, so returning while a write is
     * still on one hands the next test a store that changes underneath its own
     * setup, and posts a callback into a torn-down activity (Codex, PR #247).
     * Joining `holder` waits only for the thread that held the lock, which is
     * the one thread that was never going to be the problem.
     */
    private fun holdingWritesTo(
        hold: (() -> Unit) -> Unit,
        drain: (Long) -> Boolean,
        body: () -> Unit,
    ) {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            hold {
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue("precondition: the write lock is held", holding.await(5, TimeUnit.SECONDS))
        try {
            body()
        } finally {
            release.countDown()
            holder.join()
            assertTrue("the held write must finish before the next test", drain(5))
            // And its completion callback, which lands on the main thread.
            drainWrites()
        }
    }
}
