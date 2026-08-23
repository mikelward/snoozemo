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

    @Test
    fun `disabling the log fires watchCrashPinOutcome once the deleted pin's fate is known`() {
        // Turning the switch off deletes any pinned crash along with
        // everything else (SPEC.md §4.6), but that delete is queued
        // asynchronously on the sink's own worker — without wiring this
        // through, a crash banner already on screen kept offering to share
        // a file that no longer existed (Codex, PR #89).
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(dir, "current.log").writeText("the run that crashed")
        File(dir, "current.log.crash").writeText("1")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()
        assertTrue("precondition: rotation pinned the crash", File(dir, "crash.log").exists())

        var pinned: Boolean? = null
        val watch = DebugLogging.watchCrashPinOutcome {
            DebugLogging.hasPinnedCrash { pinned = it }
        }

        try {
            DebugLogging.setEnabled(context, false) {}
            DebugLogging.awaitIdleForTest()
            // The watch's own re-read (`hasPinnedCrash`) is itself queued
            // asynchronously from inside the delete's own completion — a
            // second drain is what waits for that nested hop too.
            DebugLogging.awaitIdleForTest()
        } finally {
            watch.close()
        }

        assertFalse("crash.log is gone", File(dir, "crash.log").exists())
        assertEquals("the watch's own re-read must see the pin is gone", false, pinned)
    }

    @Test
    fun `a leftover retried at an already-Off install also sets lastDisableCleanupFailed`() {
        // A process restart under a setting that was already Off is exactly
        // when a leftover from an earlier refused delete gets retried — not
        // only the interactive toggle path (Codex, PR #89).
        DebugLogStore(context).setEnabled(false)
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(File(dir, "crash.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        assertTrue(DebugLogging.lastDisableCleanupFailed)
    }

    @Test
    fun `a refused delete during disable sets lastDisableCleanupFailed, not silent success`() {
        // Off is supposed to mean nothing is kept (SPEC.md §4.6); a refused
        // delete must not read the same as a clean one, or nothing tells
        // the user their delete request only partly landed (Codex, PR #89).
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        // An occupied directory refuses deletion the same way a failing
        // filesystem does: delete() returns false rather than throwing.
        File(File(dir, "crash.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()
        assertFalse(DebugLogging.lastDisableCleanupFailed)

        DebugLogging.setEnabled(context, false) {}
        DebugLogging.awaitIdleForTest()

        assertTrue(DebugLogging.lastDisableCleanupFailed)
    }

    @Test
    fun `a successful re-enable clears a prior disable's cleanup failure`() {
        // Without this, lastDisableCleanupFailed never resets once set —
        // only a disable ever writes it — so it would resurface at any
        // later restart even though the switch has been back On for a
        // while and nothing is actually wrong (Codex, PR #89).
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(File(dir, "crash.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()
        DebugLogging.setEnabled(context, false) {}
        DebugLogging.awaitIdleForTest()
        assertTrue("precondition: the disable left a real leftover", DebugLogging.lastDisableCleanupFailed)

        DebugLogging.setEnabled(context, true) {}
        DebugLogging.awaitIdleForTest()

        assertFalse(DebugLogging.lastDisableCleanupFailed)
    }

    @Test
    fun `a quick re-enable invalidates a still in-flight disable's own cleanup callback`() {
        // setEnabled's Off/On calls are ordered on DebugLogging's own
        // worker, but the delete they trigger runs on the sink's own
        // *separate* worker, asynchronously — without disableGeneration,
        // a re-enable's clear could be overwritten later by the disable's
        // own callback finally completing and reporting its (genuine)
        // failure, showing a cleanup warning under a switch already back
        // On (Codex, PR #89, second round on this field). No await
        // between the two calls: both queue on DebugLogging's worker in
        // order, so the enable's own generation bump is guaranteed to
        // land before either callback is checked, regardless of exactly
        // when the sink's own delete actually finishes.
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(File(dir, "crash.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        DebugLogging.setEnabled(context, false) {}
        DebugLogging.setEnabled(context, true) {}
        DebugLogging.awaitIdleForTest()

        assertFalse(
            "the disable's own callback must not overwrite the re-enable's clear, " +
                "even though the delete it reports on genuinely failed",
            DebugLogging.lastDisableCleanupFailed,
        )
    }

    @Test
    fun `a quick re-enable still notifies the crash-pin watch once the earlier delete completes`() {
        // The bug this guards: gating onCrashPinOutcome on the same
        // disableGeneration check that guards lastDisableCleanupFailed
        // suppressed the pin-state notification too — a crash.log the
        // disable's own delete genuinely removed would leave crashPending
        // stuck true on screen, since nothing else re-reads hasPinnedCrash
        // while the activity stays on the same screen (Codex, PR #89, third
        // round on this field).
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(dir, "current.log").writeText("the run that crashed")
        File(dir, "current.log.crash").writeText("1")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()
        assertTrue("precondition: the crash is pinned", File(dir, "crash.log").exists())

        var fired = 0
        val watch = DebugLogging.watchCrashPinOutcome { fired++ }
        try {
            // Same no-await, no-race-needed shape as the cleanup-failure
            // test above: both DebugLogging-worker tasks (and the
            // generation bump) are guaranteed to land before the sink's
            // own delete actually completes.
            DebugLogging.setEnabled(context, false) {}
            DebugLogging.setEnabled(context, true) {}
            DebugLogging.awaitIdleForTest()
        } finally {
            watch.close()
        }

        assertTrue(
            "the crash-pin watch must still fire once the superseded delete finishes, " +
                "even though its own cleanup-failure verdict is discarded",
            fired > 0,
        )
        assertFalse("the crash really was deleted by the disable's own delete", File(dir, "crash.log").exists())
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
    fun `dismissCrashPin's own retry resolves to its own outcome, not a stuck earlier one`() {
        // dismissCrashPin clears lastDismissFailed synchronously before
        // dispatching its own consume, so a config change or restart while
        // a retry is still in flight can no longer reload a *previous*
        // attempt's stale outcome (Codex, PR #89) — that synchronous
        // ordering is a language guarantee, not something a threaded test
        // can pin without racing the same worker it's asserting about, so
        // this instead pins the deterministic, fully-awaited end state: a
        // retry that succeeds must land on its own success, never leave
        // the earlier failure standing.
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(dir, "current.log").writeText("the run that crashed")
        File(dir, "current.log.crash").writeText("1")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()
        val blocker = File(dir, "previous.log")
        File(blocker, "occupied").apply { parentFile!!.mkdirs() }.writeText("x")
        DebugLogging.dismissCrashPin()
        DebugLogging.awaitIdleForTest()
        assertTrue("precondition: the first dismiss genuinely failed", DebugLogging.lastDismissFailed)

        // Fix the fixture so the retry can actually succeed this time.
        blocker.deleteRecursively()
        DebugLogging.dismissCrashPin()
        DebugLogging.awaitIdleForTest()

        assertFalse(DebugLogging.lastDismissFailed)
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
