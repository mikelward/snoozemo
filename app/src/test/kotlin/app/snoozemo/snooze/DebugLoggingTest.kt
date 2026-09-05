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
    /**
     * A stall record has to say who queued the install, not only what it was
     * doing: the 2026-09-04 stack named a legacy purge parked in `commit()`
     * and nothing about which class's install that was, since the worker is
     * shared by every class in the sandbox and the submitter had long
     * returned (`TODO.md`). Staged as a real timeout after this test's own
     * install, since the record is the snapshot a timeout takes and an
     * earlier class's timeout may still be the one on file. Pinned against
     * the three facts the next occurrence needs: the submitting frames, the
     * application the install was for, and whether that is the caller's own.
     */
    @Test
    fun `the stall record names who submitted the last install`() {
        DebugLogging.install(context)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            DebugLogging.blockWorkerForTest(release)
            assertFalse("precondition: the drain has to time out", DebugLogging.awaitIdleForTest(timeoutSeconds = 1))
        } finally {
            release.countDown()
        }
        assertTrue("the worker must be usable again", DebugLogging.awaitIdleForTest())

        val stall = DebugLogging.workerStall(context)

        assertTrue("the submission must be recorded: $stall", stall.contains("last install submitted"))
        assertTrue(
            "with its wall-clock instant, which is what maps it to a class in the JUnit report: $stall",
            Regex("submitted at \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}").containsMatchIn(stall),
        )
        assertTrue(
            "the submitting frames must reach the class that called install: $stall",
            stall.contains("DebugLoggingTest"),
        )
        assertTrue(
            "the record must name the application the install was for: $stall",
            stall.contains(DebugLogging.describeApp(context)),
        )
        assertTrue(
            "and say whether it is the caller's own: $stall",
            stall.contains("this application's own"),
        )
        assertTrue(
            "an install that ran is reported as finished, not as the stall: $stall",
            stall.contains("finished") && !stall.contains("still queued"),
        )
        assertFalse("a plain install is not described as a retry: $stall", stall.contains("inline retry"))
    }

    @Test
    fun `a wedged inline retry is reported as the running install`() {
        // The retry path: an install has run but left no sink — what a
        // failed install looks like — and a crash read re-runs it inline on
        // the worker. Held open there, a drain timeout must catch it as the
        // install the worker is inside, flagged as the retry it is, rather
        // than reporting no running install over a stack plainly inside one
        // (Codex, PR #188).
        DebugLogging.install(context)
        DebugLogging.forgetSinkForTest()
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            DebugLogging.holdInstallForTest(release)
            DebugLogging.hasPinnedCrash { _, _ -> }
            assertFalse("precondition: the drain has to time out", DebugLogging.awaitIdleForTest(timeoutSeconds = 1))
        } finally {
            release.countDown()
        }
        assertTrue("the worker must be usable again", DebugLogging.awaitIdleForTest())

        val stall = DebugLogging.workerStall(context)

        assertTrue("the retry is the running install: $stall", stall.contains("the install the worker was running"))
        assertTrue("and is this test's own: $stall", stall.contains("this application's own"))
        assertTrue("flagged as the retry it is: $stall", stall.contains("(an inline retry)"))
        assertTrue("caught while running: $stall", stall.contains("running for"))
    }

    @Test
    fun `an install queued behind a wedged worker is reported as queued, not wedged`() {
        // The cross-class shape (Codex, PR #188): the worker is stuck in an
        // earlier task when a later class's `onCreate` submits its install.
        // The record must say that install is waiting, not that it stalled.
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            DebugLogging.blockWorkerForTest(release)
            DebugLogging.install(context)
            assertFalse("precondition: the drain has to time out", DebugLogging.awaitIdleForTest(timeoutSeconds = 1))
        } finally {
            release.countDown()
        }
        assertTrue("the worker must be usable again", DebugLogging.awaitIdleForTest())

        // Read only now, after the queued install has run to completion: the
        // verdict has to come from the timeout's snapshot, not a live read
        // that would find the install finished (Codex, PR #188).
        val stall = DebugLogging.workerStall(context)

        assertTrue("the queued install is reported as queued: $stall", stall.contains("still queued"))
        assertTrue("and not as the running one: $stall", stall.contains("no install was running"))
        assertTrue("while still naming whose it is: $stall", stall.contains("this application's own"))
        assertFalse("the live state must not leak into the verdict: $stall", stall.contains("finished"))
    }

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

    /**
     * A read that never comes back must fail at the bound, not park the worker.
     *
     * The library's `readPreviousRun()` is synchronous, and until androidlog
     * 2.0.50 it waited on the sink's own worker with no timeout — so a stalled
     * read parked `DebugLogging`'s worker for the life of the process, taking
     * the recording gate and the user's opt-out with it. The library bounds its
     * own wait now; this bound is the half that does not depend on which
     * version an install resolved, and it is what turns a stall into
     * `readSucceeded = false` rather than a silent "nothing to send".
     */
    @Test
    fun `a read that never returns fails at the bound and is interrupted`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.CountDownLatch(1)

        val startedAt = System.nanoTime()
        val thrown = runCatching {
            DebugLogging.awaitBounded(timeoutSeconds = 1) {
                started.countDown()
                try {
                    // Longer than any bound under test, so the wait can only
                    // end at the timeout or the interrupt below.
                    Thread.sleep(30_000)
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
            }
        }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("the read must have actually started", started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(
            "a stalled read must surface as a timeout, not a null result: $thrown",
            thrown is java.util.concurrent.TimeoutException,
        )
        assertTrue("it must fail at its own bound, not wait the read out: $elapsedMs ms", elapsedMs < 15_000)
        // Otherwise every stalled read leaks a thread that stays blocked for as
        // long as whatever wedged it does. What the interrupt reaches is this
        // *thread*, and only it — the test below is the production shape, where
        // the work it is waiting on belongs to somebody else's executor.
        assertTrue(
            "the abandoned read must be interrupted rather than left running",
            interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
    }

    /**
     * The interrupt releases this caller; it does not cancel the sink's work.
     *
     * The test above hands `awaitBounded` a lambda that does the blocking
     * itself, so its interrupt lands on the work. Production nests: the lambda
     * calls `DebugFileSink.readPreviousRun()`, which blocks *waiting on* the
     * sink's own single worker, so the interrupt reaches that wait and nothing
     * further. The read task keeps its place in the sink's queue, and so does
     * everything behind it — an opt-out's purge included (Codex, PR #169).
     *
     * That residue is deliberate, not an oversight, and propagating the
     * cancellation would be worse: the sink's executor has one thread shared by
     * every file operation, so interrupting the task at its head could land on
     * an unrelated write, and dequeuing the read would not unwedge a worker
     * that is stuck on something else anyway. `TODO.md` carries what is still
     * owed — reporting the opt-out honestly when the purge behind it cannot run.
     */
    @Test
    fun `an interrupted wait leaves the work it was waiting on alone`() {
        val sinkWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-sink-worker").apply { isDaemon = true }
        }
        val running = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workInterrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val onSinkWorker = sinkWorker.submit {
                running.countDown()
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    workInterrupted.set(true)
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(
                "the sink's task must have started, or this proves nothing",
                running.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )

            val thrown = runCatching {
                // `readPreviousRun()`'s shape: block on the sink's worker.
                DebugLogging.awaitBounded(timeoutSeconds = 1) { onSinkWorker.get() }
            }.exceptionOrNull()

            assertTrue(
                "the caller must still give up at its own bound: $thrown",
                thrown is java.util.concurrent.TimeoutException,
            )
            assertFalse(
                "the sink's own task must be left running, not interrupted",
                workInterrupted.get(),
            )
            assertFalse("and it must still be pending", onSinkWorker.isDone)
        } finally {
            release.countDown()
            sinkWorker.shutdownNow()
        }
    }

    /** And the ordinary path still answers with what the read produced. */
    @Test
    fun `a read that returns in time answers with its result`() {
        assertEquals("done", DebugLogging.awaitBounded { "done" })
    }
}
