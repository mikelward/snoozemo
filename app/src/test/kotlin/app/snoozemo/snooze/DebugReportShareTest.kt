package app.snoozemo.snooze

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import app.snoozemo.core.SnoozeDebugLog
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.CollectedReport
import com.mikelward.androidlog.android.ShareOutcome
import com.mikelward.androidlog.android.DebugReport as SharedReport
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DebugReport.share]: both delivery routes are attempted independently, the
 * crash pin is consumed on and only on a landed clipboard copy, a collection
 * failure shares a fallback instead of crashing, and a share that reaches
 * neither route is distinguishable from one that does. Every dependency is
 * injected, so every outcome is drivable without a real `Activity`,
 * `ClipboardManager`, or share target — mirroring the sibling Simmo repo's
 * `DebugReportShareTest`.
 */
@RunWith(RobolectricTestRunner::class)
class DebugReportShareTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    // Isolates DebugReport's process-level state (lastShareFailed, and now
    // the attempt-ordering guard) between tests — without this, a test using
    // an explicit attempt number could leave a later, default-attempt (0)
    // test's own outcome silently discarded as "stale".
    @Before
    fun setUp() {
        DebugReport.resetForTest()
        DebugLogging.resetForTest()
        SnoozeDebugLog.resetForTest()
        SnoozeDebugLog.setRecording(true)
        // Stated, not inherited. Every test here installs the file sink and
        // expects it to rotate; a sink installed under a *stored* setting of
        // off deletes the directory instead, and the tests that turn the log
        // off live in other classes, so nothing in this one would say why.
        // `resetForTest` clears the singleton but not the persisted choice.
        DebugLogStore(context).setEnabled(true)
    }

    @After
    fun tearDown() {
        DebugReport.resetForTest()
        DebugLogging.resetForTest()
        SnoozeDebugLog.resetForTest()
        SnoozeDebugLog.setRecording(true)
    }

    /** What the real delivery put on the clipboard. */
    private fun clipboardText(): String? =
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.primaryClip?.getItemAt(0)?.text?.toString()

    /** A report of [text] alone, with no earlier runs. */
    private fun report(text: String): CollectedReport = SharedReport.collect(DebugLog(), null) { text }

    /**
     * A delivery from two routes, decided as the library decides it: the
     * chooser opening is SHARED, a copy alone COPIED_ONLY, neither FAILED.
     */
    private fun deliverVia(
        clipboardWrite: (Context, String) -> Boolean,
        chooserLaunch: (Context, String) -> Boolean,
    ): (Context, CollectedReport) -> ShareOutcome = { c, report ->
        val copied = clipboardWrite(c, report.text)
        val launched = chooserLaunch(c, report.text)
        when {
            launched -> ShareOutcome.SHARED
            copied -> ShareOutcome.COPIED_ONLY
            else -> ShareOutcome.FAILED
        }
    }

    @Test
    fun `the outcome is the delivery's own, and only a failed one is reported as failed`() {
        for (outcome in ShareOutcome.values()) {
            val result = DebugReport.share(context, collect = { report("irrelevant") }, deliver = { _, _ -> outcome })
            assertEquals(outcome, result)
            assertEquals(outcome.name, outcome == ShareOutcome.FAILED, DebugReport.lastShareFailed)
        }
    }

    @Test
    fun `a collection that throws shares a fallback and settles the tap`() {
        var sharedText: String? = null
        val attempt = DebugReport.nextAttempt()

        val result = DebugReport.share(
            context,
            attempt = attempt,
            collect = { error("collection broke") },
            deliver = deliverVia(
                clipboardWrite = { _, text -> sharedText = text; true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        assertEquals(ShareOutcome.SHARED, result)
        assertFalse("the Share buttons come back", DebugReport.shareInFlight)
        val text = requireNotNull(sharedText)
        assertTrue(text.startsWith("Snoozemo debug log"))
        // The fallback's own line names only the failing type. The failure
        // itself is in the app's log, which the report carries under the log's
        // own rules (SPEC §4.6), so the next report can say what went wrong.
        val fallbackLine = text.lines().single { it.startsWith("Report collection failed:") }
        assertEquals("Report collection failed: java.lang.IllegalStateException", fallbackLine)
        assertTrue(SnoozeDebugLog.snapshot().any { it.contains("debug report: building it failed") })
    }

    @Test
    fun `a fallback that throws too is a failed share that still settles the tap`() {
        var delivered = false
        val attempt = DebugReport.nextAttempt()

        val result = DebugReport.share(
            context,
            attempt = attempt,
            collect = { error("collection broke") },
            deliver = { _, _ -> delivered = true; ShareOutcome.SHARED },
            fallback = { error("fallback broke") },
        )

        assertEquals(ShareOutcome.FAILED, result)
        assertFalse("nothing to deliver", delivered)
        assertFalse("the Share buttons come back", DebugReport.shareInFlight)
        assertTrue(DebugReport.lastShareFailed)
        assertTrue(SnoozeDebugLog.snapshot().any { it.contains("debug report: the fallback could not be built either") })
    }

    @Test
    fun `a delivery that throws is reported as failed rather than taking the share down`() {
        val result = DebugReport.share(
            context,
            collect = { report("irrelevant") },
            deliver = { _, _ -> throw RuntimeException("boom") },
        )

        assertEquals(ShareOutcome.FAILED, result)
        assertTrue(DebugReport.lastShareFailed)
        // In the app's own log, so the next report can say what went wrong.
        assertTrue(SnoozeDebugLog.snapshot().any { it.contains("debug report: delivering it failed") })
    }

    @Test
    fun `the real payload collector runs cleanly under Robolectric and reports the truth`() {
        SnoozeDebugLog.resetForTest()
        SnoozeDebugLog.event("the cap alarm was armed")
        var sharedText: String? = null

        val result = DebugReport.share(
            context,
            deliver = deliverVia(
                clipboardWrite = { _, text -> sharedText = text; true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        assertEquals(ShareOutcome.SHARED, result)
        val text = requireNotNull(sharedText)
        assertTrue(text.startsWith("Snoozemo debug log"))
        assertTrue(text.contains("the cap alarm was armed"))
        assertTrue(text.contains("--- State ---"))
        assertTrue("${text.length}", text.length <= SharedReport.MAX_REPORT_CHARS)
    }

    /**
     * No log installed while recording is on: there should be one, so the
     * earlier runs are missing rather than absent, and the report says so
     * instead of reading like one with nothing to send (Codex, PR #89).
     */
    @Test
    fun `earlier runs that could not be read are named in the report`() {
        var sharedText: String? = null

        DebugReport.share(
            context,
            deliver = deliverVia(
                clipboardWrite = { _, text -> sharedText = text; true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        assertTrue(sharedText!!, sharedText!!.contains("could not be included in this report"))
    }

    @Test
    fun `an installed log with nothing earlier says nothing is missing`() {
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()
        var sharedText: String? = null

        DebugReport.share(
            context,
            deliver = deliverVia(
                clipboardWrite = { _, text -> sharedText = text; true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        assertFalse(sharedText!!, sharedText!!.contains("could not be included"))
    }

    /**
     * `appVersionName`/`appVersionCode` fell back to "unknown"/-1 on a
     * `PackageManager` exception without logging it (Codex, PR #89) — a
     * failed lookup then read exactly like a normal one that happened to
     * turn up nothing, with no trace of why. Removing the app's own package
     * from the shadow `PackageManager` forces the same
     * `NameNotFoundException` `getPackageInfo` would throw on a genuine
     * lookup failure; the share must still complete with the documented
     * fallback rather than hanging or crashing.
     */
    @Test
    fun `a package-manager lookup failure falls back cleanly instead of hanging the share`() {
        org.robolectric.Shadows.shadowOf(context.packageManager).removePackage(context.packageName)
        var sharedText: String? = null

        DebugReport.share(
            context,
            deliver = deliverVia(
                clipboardWrite = { _, text -> sharedText = text; true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        val text = requireNotNull(sharedText)
        assertTrue(text.contains("Version: unknown (-1)"))
    }

    // --- lastShareFailed / watchShareOutcome (Codex, PR #89: a config change
    // must not strand the outcome on a dead activity's own closure) ---

    @Test
    fun `lastShareFailed reflects the most recently completed share`() {
        DebugReport.share(
            context,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> false },
                chooserLaunch = { _, _ -> false },
            ),
        )
        assertTrue(DebugReport.lastShareFailed)

        DebugReport.share(
            context,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
            ),
        )
        assertFalse("a later successful share supersedes the earlier failure", DebugReport.lastShareFailed)
    }

    @Test
    fun `a slower older attempt does not overwrite a faster newer attempt's outcome`() {
        // Both tickets are drawn upfront — tap 1, then tap 2 — so the ticket
        // counter already sits at 2 by the time either share() call runs.
        // Calling attempt 2's share (a fast retry) before attempt 1's (a
        // slow first try) is exactly what "tap 1, then tap 2, but 2
        // finishes first" looks like from DebugReport's point of view
        // (Codex, PR #89).
        val attempt1 = DebugReport.nextAttempt()
        val attempt2 = DebugReport.nextAttempt()

        DebugReport.share(
            context,
            attempt = attempt2,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
            ),
        )
        assertFalse("the newer, faster attempt succeeded", DebugReport.lastShareFailed)

        DebugReport.share(
            context,
            attempt = attempt1,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> false },
                chooserLaunch = { _, _ -> false },
            ),
        )

        assertFalse(
            "an older attempt finishing late must not un-report the newer attempt's success",
            DebugReport.lastShareFailed,
        )
    }

    @Test
    fun `a newer attempt's own genuine failure still applies over an already-applied older success`() {
        val attempt1 = DebugReport.nextAttempt()
        DebugReport.share(
            context,
            attempt = attempt1,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
            ),
        )
        assertFalse(DebugReport.lastShareFailed)

        val attempt2 = DebugReport.nextAttempt()
        DebugReport.share(
            context,
            attempt = attempt2,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> false },
                chooserLaunch = { _, _ -> false },
            ),
        )

        assertTrue(DebugReport.lastShareFailed)
    }

    @Test
    fun `an older attempt completing while a newer one is still in flight is discarded, not just raced`() {
        // Tap 1 starts a share; the user taps again before it finishes,
        // issuing attempt 2's ticket — attempt 2 is now "in flight" but
        // hasn't completed. Attempt 1 then finishes (fails). Comparing only
        // against the highest *applied* attempt would have let this apply,
        // since nothing had been applied yet — showing a stale failure the
        // user's still-pending retry hadn't earned, and one that would
        // never clear if the retry then stalled (Codex, PR #89, third round
        // on this mechanism). Comparing against the *issued* ticket instead
        // means attempt 1's completion is discarded outright, the moment a
        // newer ticket exists — not merely outraced by one that finishes
        // first.
        val attempt1 = DebugReport.nextAttempt()
        DebugReport.nextAttempt() // attempt 2's ticket issued; its share never runs in this test

        DebugReport.share(
            context,
            attempt = attempt1,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> false },
                chooserLaunch = { _, _ -> false },
            ),
        )

        assertFalse(
            "an older attempt must not surface its outcome once a newer ticket has been issued, " +
                "even if the newer attempt hasn't completed yet",
            DebugReport.lastShareFailed,
        )
    }

    @Test
    fun `a superseded attempt skips delivery entirely, not just the outcome it would have applied`() {
        // The bug this guards: the ticket only ever gated lastShareFailed,
        // never the clipboard write or chooser launch themselves — so a
        // slower older attempt still wrote the clipboard and opened a
        // chooser even after a faster retry had already delivered its own
        // report, unordered and capable of clobbering the retry's content
        // (Codex, PR #89, fourth round on this mechanism). Attempt 2 here
        // delivers first — the faster retry — then attempt 1 arrives at
        // delivery already superseded; its clipboardWrite/chooserLaunch
        // must never be called at all.
        val attempt1 = DebugReport.nextAttempt()
        val attempt2 = DebugReport.nextAttempt()

        DebugReport.share(
            context,
            attempt = attempt2,
            collect = { report("the retry's report") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        var clipboardWriteCalled = false
        var chooserLaunchCalled = false
        val staleResult = DebugReport.share(
            context,
            attempt = attempt1,
            collect = { report("the stale first attempt's report") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> clipboardWriteCalled = true; true },
                chooserLaunch = { _, _ -> chooserLaunchCalled = true; true },
            ),
        )

        assertFalse("a superseded attempt must never write the clipboard", clipboardWriteCalled)
        assertFalse("a superseded attempt must never open a chooser", chooserLaunchCalled)
        assertNull("its own result reports nothing delivered either", staleResult)
    }

    /**
     * The gate that replaced a much larger mechanism.
     *
     * This class used to reconcile a concurrent second tap *after the fact*,
     * at the delivery layer: a completed-delivery counter, the last
     * delivery's outcome and its pin-safety, and a per-ticket map of counter
     * snapshots, so a second attempt could work out it was a duplicate and
     * reuse the first one's outcome instead of opening a second chooser.
     * That machinery grew a field or a condition per review round and was
     * itself the largest source of defects in this file (PR #89, twelve
     * findings). Coalescing at the tap removes the question instead of
     * answering it: `nextAttempt()` raises `shareInFlight` synchronously on
     * the tap thread, callers disable their Share affordance on it, and the
     * concurrent second tap simply cannot be made.
     */
    @Test
    fun `issuing a ticket marks a share in flight, and its completion clears it`() {
        assertFalse("precondition: nothing in flight", DebugReport.shareInFlight)

        val attempt = DebugReport.nextAttempt()
        assertTrue(
            "the flag must be up by the time nextAttempt() returns — it runs on the tap " +
                "thread, so a background thread's scheduling delay must not leave a window " +
                "in which the button is still enabled",
            DebugReport.shareInFlight,
        )

        DebugReport.share(
            context,
            attempt = attempt,
            collect = { report("the report") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        assertFalse("the completed attempt re-enables the affordance", DebugReport.shareInFlight)
    }

    /**
     * A share that fails still has to clear the gate — otherwise the button
     * stays disabled forever and the user can never retry, which is strictly
     * worse than the failure it is reporting.
     */
    @Test
    fun `a failed share still clears the in-flight gate so a retry is possible`() {
        val attempt = DebugReport.nextAttempt()

        val result = DebugReport.share(
            context,
            attempt = attempt,
            collect = { report("the report") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> false },
                chooserLaunch = { _, _ -> false },
            ),
        )

        assertEquals("precondition: this attempt genuinely failed", ShareOutcome.FAILED, result)
        assertTrue("the failure is what's visible", DebugReport.lastShareFailed)
        assertFalse(
            "a failed share must still re-enable its own affordance, or the retry it is " +
                "asking for can never be tapped",
            DebugReport.shareInFlight,
        )
    }

    /**
     * A superseded attempt must not clear the gate: the flag belongs to the
     * newer attempt still running, and re-enabling the button underneath it
     * would re-admit exactly the concurrent tap the gate exists to prevent.
     */
    @Test
    fun `a superseded attempt does not clear the gate the newer attempt still holds`() {
        val stale = DebugReport.nextAttempt()
        DebugReport.nextAttempt()

        DebugReport.share(
            context,
            attempt = stale,
            collect = { report("the report") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        assertTrue(
            "the newer attempt is still running, so the affordance stays disabled",
            DebugReport.shareInFlight,
        )
    }

    /**
     * Gating is a UI contract, and `deliveryLock` is the floor beneath it:
     * this function must still behave if some future caller doesn't honor
     * the gate. Two genuinely concurrent calls are serialized rather than
     * interleaved, so neither can overwrite the other's clipboard write
     * mid-flight or open a chooser while the other's is opening (Codex,
     * PR #89, fourth round on this mechanism). They do each deliver — with
     * the reuse machinery gone, an overlapping call is no longer
     * retroactively deduplicated, which is precisely why the gate exists at
     * the tap instead.
     */
    @Test
    fun `concurrent deliveries are serialized, never interleaved`() {
        val inDelivery = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val threads = (1..4).map {
            Thread {
                DebugReport.share(
                    context,
                    collect = { report("the report") },
                    deliver = deliverVia(
                        clipboardWrite = { _, _ ->
                        val now = inDelivery.incrementAndGet()
                        maxConcurrent.updateAndGet { seen -> maxOf(seen, now) }
                        Thread.sleep(10)
                        inDelivery.decrementAndGet()
                        true
                    },
                        chooserLaunch = { _, _ -> true },
                    ),
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertEquals(
            "no two deliveries may ever be inside the clipboard write at once",
            1,
            maxConcurrent.get(),
        )
    }

    @Test
    fun `nextAttempt tickets are strictly increasing and process-level, not caller-scoped`() {
        // The bug this guards: an earlier version drew the ticket from a
        // field on the activity, which a configuration change resets to
        // zero — behind the process-level high-water mark DebugReport
        // already compares against, so every share the replacement
        // instance fired read as stale (Codex, PR #89, second round). A
        // fresh "caller" here is simulated by simply not touching any
        // local state between calls — nextAttempt is the only source of
        // truth, so there is nothing to reset.
        val first = DebugReport.nextAttempt()
        val second = DebugReport.nextAttempt()
        val third = DebugReport.nextAttempt()

        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test
    fun `nextAttempt clears a prior failure so a config change mid-retry can't reload it`() {
        // The bug this guards: a caller only clears its own local
        // shareFailed when it starts a retry, never DebugReport's own
        // lastShareFailed — so if the activity is recreated while that
        // retry is still in flight, the replacement's onStart reloads the
        // *previous* attempt's still-true outcome and shows a failure the
        // retry already superseded (Codex, PR #89).
        val firstAttempt = DebugReport.nextAttempt()
        DebugReport.share(
            context,
            attempt = firstAttempt,
            collect = { report("text") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> false },
                chooserLaunch = { _, _ -> false },
            ),
        )
        assertTrue("precondition: the first attempt genuinely failed", DebugReport.lastShareFailed)

        // Simulates a retry's tap — nextAttempt() alone, before the retry's
        // own share() call has had any chance to complete.
        DebugReport.nextAttempt()

        assertFalse(
            "issuing the next ticket must clear the previous attempt's failure immediately, " +
                "not only once this new attempt's own result is in",
            DebugReport.lastShareFailed,
        )
    }

    @Test
    fun `a share fired with a ticket from a simulated fresh activity is never stale`() {
        // Attempt 1 lands first (a slow first tap), then a "configuration
        // change" hands the screen to a replacement instance whose own
        // first share still draws its ticket from nextAttempt() — never
        // starting back over at 1 — so its outcome is never discarded as
        // older than a tap that, from the ticket source's point of view,
        // came first.
        val firstAttempt = DebugReport.nextAttempt()
        DebugReport.share(
            context,
            attempt = firstAttempt,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> false },
                chooserLaunch = { _, _ -> false },
            ),
        )
        assertTrue(DebugReport.lastShareFailed)

        // The "replacement activity" draws its own ticket the same way —
        // through DebugReport, not a field it owns — so it is guaranteed
        // higher than firstAttempt regardless of anything reset on its side.
        val replacementAttempt = DebugReport.nextAttempt()
        DebugReport.share(
            context,
            attempt = replacementAttempt,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        assertFalse(
            "the replacement instance's own share must not be discarded as stale",
            DebugReport.lastShareFailed,
        )
    }

    @Test
    fun `watchShareOutcome fires after a share completes, with the result already applied`() {
        var heardFailed: Boolean? = null
        val watch = DebugReport.watchShareOutcome { heardFailed = DebugReport.lastShareFailed }

        try {
            DebugReport.share(
                context,
                collect = { report("irrelevant") },
                deliver = deliverVia(
                    clipboardWrite = { _, _ -> false },
                    chooserLaunch = { _, _ -> false },
                ),
            )
        } finally {
            watch.close()
        }

        assertEquals(true, heardFailed)
    }

    @Test
    fun `closing a watch stops it from hearing later shares`() {
        var heard = 0
        DebugReport.watchShareOutcome { heard++ }.close()

        DebugReport.share(
            context,
            collect = { report("irrelevant") },
            deliver = deliverVia(
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
            ),
        )

        assertEquals(0, heard)
    }

    /**
     * Sharing lowers the banner and deletes nothing (maintainer, 2026-09-30).
     * It used to delete the runs the report carried, and so refused to lower
     * the banner over a crash the report's budget had cut: those runs cut the
     * same way on every share, so the banner never came down (Codex, PR #305).
     *
     * Arranged with a crashed run far past the section's bound, the case that
     * used to leave the banner up for good.
     */
    @Test
    fun `sharing a crash too big for the report lowers the banner and keeps the run`() {
        val dir = context.cacheDir
        File(dir, "androidlog.log").writeText((1..4_000).joinToString("\n") { "line $it padding padding" })
        File(dir, "androidlog.log.crash").writeText("1")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        var pinnedBefore: Boolean? = null
        DebugLogging.hasPinnedCrash { pinned, _ -> pinnedBefore = pinned }
        DebugLogging.awaitIdleForTest()
        assertEquals("precondition: a crash is pinned", true, pinnedBefore)

        val result = DebugReport.share(context)
        val sharedText: String? = clipboardText()
        DebugLogging.awaitIdleForTest()

        assertEquals("delivered through the library", ShareOutcome.SHARED, result)
        // The premise: the report could not carry the whole run.
        assertTrue(sharedText!!.contains("line 4000 padding padding"))
        assertFalse(sharedText!!.contains("line 1 padding padding\n"))
        var pinnedAfter: Boolean? = null
        DebugLogging.hasPinnedCrash { pinned, _ -> pinnedAfter = pinned }
        DebugLogging.awaitIdleForTest()
        assertEquals("the banner comes down", false, pinnedAfter)
        assertTrue(
            "and the whole run is still on the phone",
            dir.listFiles()!!.any { it.name != "androidlog.log" && it.readText().contains("line 1 padding padding\n") },
        )
    }

    /**
     * A fallback that lands is still the user acting on the banner, so it
     * comes down like any other share (Codex, PR #326).
     */
    @Test
    fun `a fallback share still lowers the crash banner`() {
        val dir = context.cacheDir
        File(dir, "androidlog.log").writeText("the run that crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        var pinnedBefore: Boolean? = null
        DebugLogging.hasPinnedCrash { pinned, _ -> pinnedBefore = pinned }
        DebugLogging.awaitIdleForTest()
        assertEquals("precondition: a crash is pinned", true, pinnedBefore)

        val result = DebugReport.share(context, collect = { error("collection broke") })
        val sharedText: String? = clipboardText()
        DebugLogging.awaitIdleForTest()

        assertEquals(ShareOutcome.SHARED, result)
        assertTrue(sharedText!!.contains("Report collection failed: java.lang.IllegalStateException"))
        assertTrue("the crashed run rides along", sharedText.contains("the run that crashed"))
        var pinnedAfter: Boolean? = null
        DebugLogging.hasPinnedCrash { pinned, _ -> pinnedAfter = pinned }
        DebugLogging.awaitIdleForTest()
        assertEquals("the banner comes down", false, pinnedAfter)
    }

    /**
     * A crash the read could not open is skipped and left on disk, and the
     * report carries the ordinary run beside it with the library's notice that
     * a run could not be read (Codex, PR #153). Sharing deletes neither, and
     * lowers the banner anyway.
     */
    @Test
    fun `a crash the read could not open stays on disk with the run the report carried`() {
        val dir = context.cacheDir
        File(dir, "androidlog.log").writeText("the run that crashed\n")
        File(dir, "androidlog.log.crash").writeText("1")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()

        // An ordinary earlier run beside it, which is what makes the handle
        // non-blank once the crash has been skipped.
        File(dir, "androidlog-prev-ordinary.log").writeText("an ordinary earlier run\n")
        // The rotated crash log becomes unreadable: a self-referencing symlink
        // fails with a real filesystem error rather than "not found", so the
        // library skips it and leaves it in place. The banner stays up, since
        // an entry that will not say never lowers it.
        val rotated = dir.listFiles()?.single { it.name.endsWith(".crash.log") }
            ?: error("install left ${dir.list()?.sorted()} with no rotated crash log")
        assertTrue(rotated.delete())
        Files.createSymbolicLink(rotated.toPath(), rotated.toPath())

        var pinnedBefore: Boolean? = null
        DebugLogging.hasPinnedCrash { pinned, _ -> pinnedBefore = pinned }
        DebugLogging.awaitIdleForTest()
        assertEquals("precondition: the crash is still pinned", true, pinnedBefore)

        val result = DebugReport.share(context)
        val sharedText: String? = clipboardText()
        DebugLogging.awaitIdleForTest()

        assertEquals("delivered through the library", ShareOutcome.SHARED, result)
        // The report carries what it could read, and the library's own notice
        // says a run is missing from it -- so the reader is told.
        assertTrue(sharedText ?: "", sharedText!!.contains("an ordinary earlier run"))
        assertTrue(sharedText!!, sharedText!!.contains("could not be read"))
        assertTrue(
            "the run the report carried is not deleted by the share",
            File(dir, "androidlog-prev-ordinary.log").exists(),
        )
        assertTrue(
            "and the crash it could not read is still there to send",
            dir.listFiles()?.any { it.name.endsWith(".crash.log") } == true,
        )
        var pinnedAfter: Boolean? = null
        DebugLogging.hasPinnedCrash { pinned, _ -> pinnedAfter = pinned }
        DebugLogging.awaitIdleForTest()
        // Down: the share is the user acting on the banner, and a log nobody
        // can read must not hold it up for good (maintainer, 2026-10-11).
        assertEquals("and the banner comes down", false, pinnedAfter)
    }
}
