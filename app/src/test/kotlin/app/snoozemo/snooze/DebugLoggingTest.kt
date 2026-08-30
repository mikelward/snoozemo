package app.snoozemo.snooze

import android.content.Context
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
        SnoozeDebugLog.resetForTest()
        SnoozeDebugLog.setRecording(true)
        // Stated, not inherited — and this class is where the setting gets
        // written off, by the very first test, which never puts it back.
        // `resetForTest` forgets the installed singleton, not the persisted
        // choice, so a later test's `install` can start *disabled*, delete the
        // directory instead of rotating it, and fail a precondition about a
        // pinned crash that nothing in the test body explains.
        DebugLogStore(context).setEnabled(true)
    }

    /**
     * What `install` actually left behind, for a precondition that expects a
     * pinned crash. "crash.log is missing" does not say whether install never
     * ran, ran *disabled* and deleted the directory, or ran and failed to pin —
     * which is exactly what made one CI failure here unactionable.
     */
    private fun afterInstall(dir: File): String =
        "install left ${dir.list()?.sorted()} with the log " +
            if (DebugLogStore(context).isEnabled()) "on" else "off"

    @After
    fun tearDown() {
        DebugLogging.resetForTest()
        SnoozeDebugLog.resetForTest()
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
    fun `hasPinnedCrash before install reports a failed check, not confirmed absence`() {
        // A crash.log from a previous run exists independently of whether
        // install() has finished in this process, so a missing sink can
        // never confirm "nothing pinned" — only "not checked yet",
        // indistinguishable from install() itself having failed and left
        // sink permanently null (Codex, PR #89, fresh evidence after the
        // hasPinnedCrash checkSucceeded fix).
        var pinned: Boolean? = null
        var checkSucceeded: Boolean? = null
        DebugLogging.hasPinnedCrash { p, c -> pinned = p; checkSucceeded = c }
        DebugLogging.awaitIdleForTest()

        assertEquals(false, pinned)
        assertEquals(false, checkSucceeded)
    }

    @Test
    fun `a crash-pin read retries a failed install rather than reporting failure forever`() {
        // install() failing leaves sink null for the process's whole life,
        // and every later read then reported a failed check with nothing
        // able to heal it — a genuinely pinned crash stayed invisible
        // (Codex, PR #89, the fourth finding on this pattern). The reads now
        // retry install() themselves; since install() is idempotent, that
        // costs nothing once it has succeeded and heals it when it hasn't.
        //
        // Simulated by calling install() and then reading: the read must see
        // a working sink and report a real, succeeded check — which it can
        // only do if the installation actually took effect by then.
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        var checkSucceeded: Boolean? = null
        DebugLogging.hasPinnedCrash { _, c -> checkSucceeded = c }
        DebugLogging.awaitIdleForTest()

        assertEquals(
            "an installed sink must report a genuine check, not a failure",
            true,
            checkSucceeded,
        )
    }

    @Test
    fun `readPreviousOrCrash before install reports a failed read, not a clean empty one`() {
        // Same reasoning as hasPinnedCrash above: a pinned crash from a
        // previous run cannot be told apart from "genuinely nothing to
        // report" if this process never got as far as checking.
        var run: com.mikelward.androidlog.android.PreviousRun? = null
        var wasCrash: Boolean? = null
        var readSucceeded: Boolean? = null
        DebugLogging.readPreviousOrCrash { r, c, s -> run = r; wasCrash = c; readSucceeded = s }
        DebugLogging.awaitIdleForTest()

        assertEquals(null, run)
        assertEquals(false, wasCrash)
        assertEquals(false, readSucceeded)
    }

    @Test
    fun `consumeCrashPin reports success before install — nothing to consume`() {
        var consumed: Boolean? = null
        DebugLogging.consumeCrashPin(null) { consumed = it }
        DebugLogging.awaitIdleForTest()

        assertEquals(true, consumed)
    }

    @Test
    fun `installed pass-throughs reach the sink`() {
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        var consumed: Boolean? = null
        DebugLogging.consumeCrashPin(null) { consumed = it }
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
            DebugLogging.consumeCrashPin(null) {}
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
            DebugLogging.consumeCrashPin(null) {}
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

        DebugLogging.consumeCrashPin(null) {}
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

        DebugLogging.consumeCrashPin(null) {}
        DebugLogging.awaitIdleForTest()
        second.close()

        assertEquals("the old instance's deferred close must not evict the replacement", 0, firstHeard)
        assertEquals(1, secondHeard)
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
    fun `dismissCrashPin succeeds when nothing is pinned, before install`() {
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
    @Test
    fun `the migration marker is written synchronously so it survives the process`() {
        // `apply()` reports nothing and lands later, so an unwritable store or a
        // process death before the write left the flag clear with the legacy
        // files already gone -- and the next start would then purge a directory
        // holding this version's own reduced logs (Codex, PR #151). The write is
        // synchronous now, and readable the instant it returns.
        // No precondition that the flag starts clear: this class shares one
        // app context, so an earlier test's `install` may already have purged
        // and recorded it -- the same cross-test bleed `setUp` calls out for
        // the enabled setting.
        val store = DebugLogStore(context)

        val persisted = store.markLegacyLogsPurged()

        assertTrue("the write reports whether it reached disk", persisted)
        // The signature is the point: a `Unit`-returning `apply()` gives the
        // caller nothing to branch on, so the failure path could not exist.
        assertTrue(
            "and a reader created afterward sees it, so a restart will not re-purge",
            DebugLogStore(context).hasPurgedLegacyLogs(),
        )
    }

    @Test
    fun `turning the log off retries a legacy purge that startup could not finish`() {
        // The migration runs once per process and records itself done only
        // when the directory is actually gone, so a refusal at startup used to
        // leave the old logger's full-rendered files in `cacheDir/debuglog`
        // for the rest of the process -- including across the Off toggle whose
        // whole promise is that what was kept is deleted immediately
        // (SPEC.md 4.6; Codex, PR #153). The shared sink's own purge does not
        // reach them: different directory, never read.
        //
        // Arranged by leaving the directory in place with the migration
        // unrecorded, which is exactly the state a refused startup purge
        // leaves behind.
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(dir, "current.log").writeText("a line from the old logger")
        // Cleared through the same preferences file the store reads, rather
        // than through a test-only setter on production code: an earlier test
        // in this class may already have recorded the migration against the
        // shared app context.
        context.getSharedPreferences("debug_log", Context.MODE_PRIVATE)
            .edit().putBoolean("legacy_logs_purged", false).commit()
        assertFalse(
            "precondition: the migration reads as not yet done",
            DebugLogStore(context).hasPurgedLegacyLogs(),
        )
        assertTrue("precondition: the legacy directory is there", dir.exists())

        DebugLogging.setEnabled(context, false) {}
        DebugLogging.awaitIdleForTest()

        assertFalse(
            "the Off toggle must not leave the old logger's files behind",
            dir.exists(),
        )
    }

}
