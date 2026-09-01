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
        // Drained before the watch is registered, deliberately: `install`
        // queues the sink's own first crash derivation, which publishes
        // through this same watch. Registering while that is still in flight
        // leaves the count depending on which lands first — 0 or 1 by
        // scheduling, which is a test that passes most of the time.
        DebugLogging.awaitIdleForTest()

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
    fun `an upgrade purges the old directory even though the older marker is set`() {
        // The previous release's own sink lived in `cacheDir/debuglog`, purged
        // that same directory for the full-to-reduced migration, recorded
        // `legacy_logs_purged`, and then went on writing there. So every
        // upgrade from it arrives with that key already true and the directory
        // full of that release's logs. Reusing the key would skip this purge
        // and leave them for good, with the Off toggle trusting the same stale
        // answer and reporting a privacy control that succeeded (Codex,
        // PR #153).
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(dir, "current.log").writeText("a line from the previous release")
        val prefs = context.getSharedPreferences("debug_log", Context.MODE_PRIVATE)
        prefs.edit()
            // Exactly the state an upgrade lands in: the older migration's
            // marker set, this one's absent.
            .putBoolean("legacy_logs_purged", true)
            .remove("shared_logger_directory_purged")
            .commit()
        assertFalse(
            "precondition: this migration reads as not yet done",
            DebugLogStore(context).hasPurgedLegacyLogs(),
        )

        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        assertFalse("the previous release's logs are gone", dir.exists())
        assertFalse("and nothing is left to warn about", DebugLogging.lastDisableCleanupFailed)
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
            .edit().putBoolean("shared_logger_directory_purged", false).commit()
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

    @Test
    fun `an Off toggle that could not delete this run's log says so on screen`() {
        // The shared sink reports a failed opt-out purge *into the log* and
        // holds the line until recording comes back -- which the user has just
        // turned off, so it may never land at all. Without a caller-visible
        // outcome the switch clears its warning and tells a user who has just
        // used a privacy control that it worked (Codex, PR #153).
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        // Wedged after the rotation has settled, so this is the purge failing
        // rather than a prior run the purge keeps on purpose: neither
        // deletable (not empty) nor truncatable.
        val current = File(context.cacheDir, "androidlog.log")
        current.delete()
        File(current, "wedged").mkdirs()
        assertTrue("precondition: the file the purge must remove is wedged", current.isDirectory)

        DebugLogging.setEnabled(context, false) {}
        DebugLogging.awaitIdleForTest()

        assertTrue(DebugLogging.lastDisableCleanupFailed)

        // Cleared by the same operation next succeeding, so a retry that works
        // retires the warning rather than leaving it up for the process.
        current.deleteRecursively()
        DebugLogging.setEnabled(context, true) {}
        DebugLogging.awaitIdleForTest()
        DebugLogging.setEnabled(context, false) {}
        DebugLogging.awaitIdleForTest()
        assertFalse(DebugLogging.lastDisableCleanupFailed)
    }

    @Test
    fun `a dismissal the storage refused says why the banner is still up`() {
        // A refused dismissal leaves the banner up by construction, so the user
        // is never told it worked -- but without this they are left tapping a
        // control with no visible effect and no reason, and the one line that
        // explains it is in a log they would have to go and read (Codex,
        // PR #153).
        val dir = context.cacheDir
        // The crashed run's plain name is already taken, so the rename off the
        // crash suffix is refused rather than replacing the run that is there.
        File(dir, "androidlog-prev-1.crash.log").writeText("the run that crashed\n")
        File(dir, "androidlog-prev-1.log").writeText("an earlier run, never shared\n")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        DebugLogging.dismissCrashPin()
        DebugLogging.awaitIdleForTest()
        assertTrue("precondition: it really was refused", File(dir, "androidlog-prev-1.crash.log").exists())
        assertTrue(DebugLogging.lastDismissFailed)

        // And a retry that works clears it.
        File(dir, "androidlog-prev-1.log").delete()
        DebugLogging.dismissCrashPin()
        DebugLogging.awaitIdleForTest()
        assertFalse(DebugLogging.lastDismissFailed)
    }

    /**
     * A worker that is not coming back must make the drain **fail**, not hang.
     *
     * That distinction is the whole value of the stall reporting: a drain that
     * returns false is a test failure carrying [DebugLogging.workerStall]'s
     * stack, while one that blocks is a suite that stops with nothing said.
     * `awaitIdleForTest` used to drain the sink after its latch expired, which
     * on the one path worth reporting — a worker parked in `readPreviousRun()`
     * waiting on the sink's own worker — queues behind that same task and
     * never returns (Codex, PR #168).
     *
     * **What this does not cover, stated rather than implied:** the stalled
     * *sink* itself. Reaching that needs a way to hold the sink's worker, and
     * the library exposes none, so this stages the wedge on our side instead.
     * The ordering it pins is what makes the report reachable either way.
     */
    @Test
    fun `a wedged worker fails the drain instead of hanging`() {
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            DebugLogging.blockWorkerForTest(release)

            val startedAt = System.nanoTime()
            val drained = DebugLogging.awaitIdleForTest(timeoutSeconds = 1)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertFalse("the worker is occupied, so nothing queued behind it ran", drained)
            // Its own bound, with room for a loaded machine -- but far short of
            // the seam's 30 s backstop, which is what a wait on the blocked
            // worker would have cost.
            assertTrue("the drain must return at its own bound, not wait out the block", elapsedMs < 15_000)
            assertTrue(
                "the failure must be able to say what the worker was doing",
                DebugLogging.workerStall().contains("snoozemo-debug-log-init"),
            )
        } finally {
            release.countDown()
        }
        // The worker recovers once released: this class shares it with every
        // other one in the sandbox, so leaving it wedged would fail them all --
        // which is the bug being studied, not something to reproduce for real.
        assertTrue("the worker must be usable again", DebugLogging.awaitIdleForTest())
    }

    /**
     * The reported stall must describe the worker **at the timeout**, not
     * whenever the failing test got round to asking.
     *
     * A task that finishes just after the bound expires leaves the worker idle
     * by the time the message is built, so a live reading would name anything
     * except the operation that blew it — and this reporting exists precisely
     * to explain a failure nobody has caught in the act (Codex, PR #168).
     *
     * Deterministic rather than racing the boundary, which is the same
     * property under test: the blocker is released *after* the drain has
     * already timed out, and the worker is then drained to completion, so it
     * is provably idle when the stall is read. A live reading could not
     * mention the block; a snapshot taken at the timeout must.
     */
    @Test
    fun `the reported stall describes the worker at the timeout, not afterwards`() {
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            DebugLogging.blockWorkerForTest(release)
            assertFalse("precondition: the drain has to time out", DebugLogging.awaitIdleForTest(timeoutSeconds = 1))
        } finally {
            release.countDown()
        }
        // Provably past the block now, so nothing about it is still visible on
        // the worker -- yet it is what the failure needs to name.
        assertTrue("the worker must be usable again", DebugLogging.awaitIdleForTest())

        val stall = DebugLogging.workerStall()
        assertTrue(
            "the stall must still name the blocked wait, not the idle worker: $stall",
            stall.contains("CountDownLatch"),
        )
        // And it must be offered as a stall rather than hedged: the worker was
        // provably still behind the drain's own task when the bound expired,
        // which is the case the qualifier below is *not* for.
        assertFalse(
            "a validated snapshot must not carry the slow-worker caveat: $stall",
            stall.contains("cannot be placed either side of it"),
        )
    }
}
