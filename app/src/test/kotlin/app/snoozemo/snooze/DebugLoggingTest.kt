package app.snoozemo.snooze

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeDebugLog
import java.io.File
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
        var readSucceeded: Boolean? = null
        DebugLogging.readPreviousOrCrash { t, c, s -> text = t; wasCrash = c; readSucceeded = s }
        DebugLogging.awaitIdleForTest()

        assertEquals(null, text)
        assertEquals(false, wasCrash)
        // Nothing before install has run means there is nothing pinned to
        // miss — reported as a successful (empty) read, not a failed one.
        assertEquals(true, readSucceeded)
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

    // --- watchCrashPinOutcome (Codex, PR #89: a Share/Dismiss tap's own
    // completion must not be the only way its result reaches the screen —
    // a configuration change can hand the screen to a replacement instance
    // first) ---

    @Test
    fun `watchCrashPinOutcome fires after consumeCrashPin completes, before install`() {
        var fired = 0
        val watch = DebugLogging.watchCrashPinOutcome { fired++ }

        try {
            DebugLogging.consumeCrashPin {}
            DebugLogging.awaitIdleForTest()
        } finally {
            watch.close()
        }

        assertEquals(1, fired)
    }

    @Test
    fun `watchCrashPinOutcome fires once a real sink's consume completes too`() {
        DebugLogging.install(context)
        var fired = 0
        val watch = DebugLogging.watchCrashPinOutcome { fired++ }

        try {
            DebugLogging.consumeCrashPin {}
            DebugLogging.awaitIdleForTest()
        } finally {
            watch.close()
        }

        assertEquals(1, fired)
    }

    @Test
    fun `closing watchCrashPinOutcome stops it from hearing later completions`() {
        var fired = 0
        DebugLogging.watchCrashPinOutcome { fired++ }.close()

        DebugLogging.consumeCrashPin {}
        DebugLogging.awaitIdleForTest()

        assertEquals(0, fired)
    }

    @Test
    fun `a later watch registration replaces the earlier one, like watchSaveOutcome`() {
        var firstHeard = 0
        var secondHeard = 0
        val first = DebugLogging.watchCrashPinOutcome { firstHeard++ }
        // The replacement registers before the old instance's close runs —
        // the same ordering `onStop` guarantees on a configuration change.
        val second = DebugLogging.watchCrashPinOutcome { secondHeard++ }
        first.close()

        DebugLogging.consumeCrashPin {}
        DebugLogging.awaitIdleForTest()
        second.close()

        assertEquals("the old instance's deferred close must not evict the replacement", 0, firstHeard)
        assertEquals(1, secondHeard)
    }

    // --- dismissCrashPin / watchDismissOutcome / lastDismissFailed (Codex,
    // PR #89: a refused Dismiss tap must not look like it did nothing) ---

    @Test
    fun `dismissCrashPin succeeds when nothing is pinned, before install`() {
        DebugLogging.dismissCrashPin()
        DebugLogging.awaitIdleForTest()

        assertFalse(DebugLogging.lastDismissFailed)
    }

    @Test
    fun `dismissCrashPin sets lastDismissFailed when the file layer refuses to consume`() {
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(dir, "current.log").writeText("the run that crashed")
        File(dir, "current.log.crash").writeText("1")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()
        assertTrue("precondition: rotation pinned the crash", File(dir, "crash.log").exists())
        // Make both the rename and the copy fallback refuse, the same
        // fixture DebugFileSinkTest's own refusal test uses.
        File(File(dir, "previous.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        DebugLogging.dismissCrashPin()
        DebugLogging.awaitIdleForTest()

        assertTrue(DebugLogging.lastDismissFailed)
        assertTrue("the crash log survives a failed consume", File(dir, "crash.log").exists())
    }

    @Test
    fun `watchDismissOutcome fires after dismissCrashPin completes`() {
        var fired = 0
        val watch = DebugLogging.watchDismissOutcome { fired++ }

        try {
            DebugLogging.dismissCrashPin()
            DebugLogging.awaitIdleForTest()
        } finally {
            watch.close()
        }

        assertEquals(1, fired)
    }

    @Test
    fun `closing watchDismissOutcome stops it from hearing later completions`() {
        var fired = 0
        DebugLogging.watchDismissOutcome { fired++ }.close()

        DebugLogging.dismissCrashPin()
        DebugLogging.awaitIdleForTest()

        assertEquals(0, fired)
    }
}
