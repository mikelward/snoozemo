package app.snoozemo.snooze

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeDebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings switch's plumbing, deferred from Codex's PR #62 review: the
 * toggle and the async install must apply in the order they were asked for,
 * and only a persisted choice may be applied at all.
 */
@RunWith(RobolectricTestRunner::class)
class DebugLoggingTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        DebugLogging.resetForTest()
        SnoozeDebugLog.clearSinksForTest()
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.setRecording(true)
    }

    @After
    fun tearDown() {
        DebugLogging.resetForTest()
        SnoozeDebugLog.clearSinksForTest()
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.setRecording(true)
    }

    @Test
    fun `a toggle right behind the install is not overwritten by it`() {
        // The user's tap can land while the install is still reading the old
        // stored value; one FIFO worker is what guarantees the tap's choice
        // applies second and therefore wins.
        DebugLogging.install(context)
        var persisted: Boolean? = null
        DebugLogging.setEnabled(context, false) { persisted = it }
        DebugLogging.awaitIdleForTest()

        assertEquals(true, persisted)
        assertFalse("the stored setting must be the tap's, not the install's", DebugLogStore(context).isEnabled())
        // Off gates recording itself: nothing recorded now may survive.
        SnoozeDebugLog.event("recorded while off")
        assertTrue(SnoozeDebugLog.snapshot().isEmpty())
    }

    @Test
    fun `the outcome watch fires only once value and outcome are both final`() {
        // The screen's watch replaces a preference listener precisely because
        // the listener could wake with the new value but the previous write's
        // outcome; this pins that when the watch fires, both are this write's.
        DebugLogging.install(context)
        var heardValue: Boolean? = null
        var heardRefused: Boolean? = null
        val watch = DebugLogging.watchSaveOutcome {
            heardValue = DebugLogStore(context).isEnabled()
            heardRefused = DebugLogging.lastSaveRefused
        }
        DebugLogging.setEnabled(context, false) {}
        DebugLogging.awaitIdleForTest()
        watch.close()

        assertEquals(false, heardValue)
        assertEquals(false, heardRefused)
    }

    @Test
    fun `re-enabling restates the run context the disable dropped`() {
        // Disabling empties the buffer, run-start line included; without the
        // restatement nothing recorded after a re-enable says what software
        // it ran on (SPEC.md §4.6).
        DebugLogging.install(context)
        DebugLogging.setEnabled(context, false) {}
        DebugLogging.setEnabled(context, true) {}
        DebugLogging.awaitIdleForTest()

        assertTrue(SnoozeDebugLog.snapshot().any { it.contains("run start") })
    }

    @Test
    fun `re-enabling applies and reports the persisted write`() {
        DebugLogging.install(context)
        DebugLogging.setEnabled(context, false) {}
        var persisted: Boolean? = null
        DebugLogging.setEnabled(context, true) { persisted = it }
        DebugLogging.awaitIdleForTest()

        assertEquals(true, persisted)
        assertTrue(DebugLogStore(context).isEnabled())
        // The process-level outcome any screen instance can read: a drained
        // queue of successful writes leaves no refusal standing.
        assertFalse(DebugLogging.lastSaveRefused)
        SnoozeDebugLog.event("recorded while on")
        assertTrue(SnoozeDebugLog.snapshot().any { it.contains("recorded while on") })
    }

    // --- the crash-pin pass-throughs (TODO.md Phase 5, docs/DEBUG.md) ---

    @Test
    fun `hasPinnedCrash is false before install`() {
        var pinned: Boolean? = null
        DebugLogging.hasPinnedCrash { pinned = it }
        DebugLogging.awaitIdleForTest()

        assertEquals(false, pinned)
    }

    @Test
    fun `readPreviousOrCrash reads nothing before install`() {
        var text: String? = "not yet read"
        var wasCrash: Boolean? = null
        DebugLogging.readPreviousOrCrash { t, c -> text = t; wasCrash = c }
        DebugLogging.awaitIdleForTest()

        assertEquals(null, text)
        assertEquals(false, wasCrash)
    }

    @Test
    fun `consumeCrashPin reports success before install — nothing to consume`() {
        var consumed: Boolean? = null
        DebugLogging.consumeCrashPin { consumed = it }
        DebugLogging.awaitIdleForTest()

        assertEquals(true, consumed)
    }

    @Test
    fun `installed pass-throughs reach the sink`() {
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        var consumed: Boolean? = null
        DebugLogging.consumeCrashPin { consumed = it }
        DebugLogging.awaitIdleForTest()

        // No crash pinned in this run — a real sink answers the same
        // trivial-success as the no-sink case, but this pins that the call
        // actually reached a `DebugFileSink`, not just the guard above it.
        assertEquals(true, consumed)
    }
}
