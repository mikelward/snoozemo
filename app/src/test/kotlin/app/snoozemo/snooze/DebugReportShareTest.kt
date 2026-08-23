package app.snoozemo.snooze

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeDebugLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        SnoozeDebugLog.clearSinksForTest()
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.setRecording(true)
    }

    @After
    fun tearDown() {
        DebugReport.resetForTest()
        DebugLogging.resetForTest()
        SnoozeDebugLog.clearSinksForTest()
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.setRecording(true)
    }

    @Test
    fun `a landed clipboard copy consumes the crash pin`() {
        var pinConsumed = false

        val result = DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { onResult -> pinConsumed = true; onResult(true) },
        )

        assertTrue(result.clipboardCopied)
        assertTrue(result.reachedUser)
        assertTrue("a landed copy is durable proof of delivery", pinConsumed)
    }

    @Test
    fun `a failed clipboard copy never touches the pin, even if the chooser opened`() {
        var pinConsumed = false

        val result = DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { pinConsumed = true },
        )

        assertFalse(result.clipboardCopied)
        assertTrue("the chooser opening is not proof of delivery", result.reachedUser)
        assertFalse("no durable proof means the pin stays for a retry", pinConsumed)
    }

    @Test
    fun `neither route landing is reported, and the pin is untouched`() {
        var pinConsumed = false

        val result = DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = { pinConsumed = true },
        )

        assertFalse(result.clipboardCopied)
        assertFalse(result.reachedUser)
        assertFalse(pinConsumed)
    }

    @Test
    fun `a report collection failure shares a fallback instead of crashing`() {
        var sharedText: String? = null

        val result = DebugReport.share(
            context,
            payloadCollect = { error("collection broke") },
            clipboardWrite = { _, text -> sharedText = text; true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = {},
        )

        assertTrue(result.clipboardCopied)
        val text = requireNotNull(sharedText)
        assertTrue(text.startsWith("Snoozemo debug log"))
        assertTrue(text.contains("Report collection failed: java.lang.IllegalStateException"))
        // Only the failing type is named, never its message — this path can
        // run from exactly the collection step the privacy floor is about.
        assertFalse(text.contains("collection broke"))
    }

    @Test
    fun `a share that fails to consume the pin still reports what it delivered`() {
        val result = DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { onResult -> onResult(false) },
        )

        // A refused consume is a file-layer detail the share's own outcome
        // does not change: the copy still landed, so the failure text on
        // screen must not fire — the pin just gets picked up again by the
        // next hasPinnedCrash read.
        assertTrue(result.clipboardCopied)
        assertTrue(result.reachedUser)
    }

    @Test
    fun `a payload that could not confirm the pinned crash was included does not consume the pin`() {
        var pinConsumed = false

        val result = DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = false) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { pinConsumed = true },
        )

        // The clipboard copy landed, but the collector couldn't confirm the
        // pinned crash actually made it into the shared text (its read
        // timed out or failed) — consuming the pin here would delete the
        // only evidence of a crash the user never actually saw reported.
        assertTrue(result.clipboardCopied)
        assertFalse("collection couldn't confirm the crash was included, so the pin must survive", pinConsumed)
    }

    @Test
    fun `the real payload collector runs cleanly under Robolectric and reports the truth`() {
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.event("the cap alarm was armed")
        var sharedText: String? = null

        val result = DebugReport.share(
            context,
            clipboardWrite = { _, text -> sharedText = text; true },
            chooserLaunch = { _, _ -> true },
        )

        assertTrue(result.clipboardCopied)
        val text = requireNotNull(sharedText)
        assertTrue(text.startsWith("Snoozemo debug log"))
        assertTrue(text.contains("the cap alarm was armed"))
        // No debug-log install has run in this test, so there is nothing
        // pinned — the real DebugLogging.consumeCrashPin behind the default
        // seam must still complete cleanly rather than hanging the share.
        assertTrue(text.contains("--- State ---"))
    }

    @Test
    fun `a pinned crash that reads back blank is not consumed, and the report says so`() {
        // A crash marker can land without its content ever reaching disk —
        // process death between the marker write and the run's own content
        // write. wasCrash reads true from the marker alone, so a blank
        // crash.log must be treated the same as an omitted read, never as
        // a clean empty previous run — otherwise a "successful" share
        // consumes the only evidence a crash happened at all, having never
        // actually carried it (Codex, PR #89, third round on this
        // mechanism).
        val dir = File(context.cacheDir, "debuglog")
        dir.mkdirs()
        File(dir, "current.log").writeText("")
        File(dir, "current.log.crash").writeText("1")
        DebugLogging.install(context)
        DebugLogging.awaitIdleForTest()
        assertTrue("precondition: the blank crash is genuinely pinned", File(dir, "crash.log").exists())

        var sharedText: String? = null
        val result = DebugReport.share(
            context,
            clipboardWrite = { _, text -> sharedText = text; true },
            chooserLaunch = { _, _ -> true },
        )
        DebugLogging.awaitIdleForTest()

        assertTrue(result.clipboardCopied)
        assertTrue(
            "a blank crash must not be silently consumed by a share that never actually carried it",
            File(dir, "crash.log").exists(),
        )
        assertTrue(requireNotNull(sharedText).contains("could not be included in this report"))
    }

    @Test
    fun `an exception from consumeCrashPin does not take the share down with it`() {
        val result = DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { throw RuntimeException("boom") },
        )

        assertEquals(DebugReport.Result(clipboardCopied = true, reachedUser = true), result)
    }

    // --- lastShareFailed / watchShareOutcome (Codex, PR #89: a config change
    // must not strand the outcome on a dead activity's own closure) ---

    @Test
    fun `lastShareFailed reflects the most recently completed share`() {
        DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = {},
        )
        assertTrue(DebugReport.lastShareFailed)

        DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = {},
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
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = {},
        )
        assertFalse("the newer, faster attempt succeeded", DebugReport.lastShareFailed)

        DebugReport.share(
            context,
            attempt = attempt1,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = {},
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
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = {},
        )
        assertFalse(DebugReport.lastShareFailed)

        val attempt2 = DebugReport.nextAttempt()
        DebugReport.share(
            context,
            attempt = attempt2,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = {},
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
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = {},
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
            payloadCollect = { DebugReport.Payload("the retry's report", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = {},
        )

        var clipboardWriteCalled = false
        var chooserLaunchCalled = false
        val staleResult = DebugReport.share(
            context,
            attempt = attempt1,
            payloadCollect = { DebugReport.Payload("the stale first attempt's report", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> clipboardWriteCalled = true; true },
            chooserLaunch = { _, _ -> chooserLaunchCalled = true; true },
            consumeCrashPin = {},
        )

        assertFalse("a superseded attempt must never write the clipboard", clipboardWriteCalled)
        assertFalse("a superseded attempt must never open a chooser", chooserLaunchCalled)
        assertFalse("its own returned Result reports nothing delivered either", staleResult.clipboardCopied)
        assertFalse(staleResult.reachedUser)
    }

    /**
     * A gap the ticket check above doesn't close: it only catches an
     * attempt superseded *before* it starts delivering, not one whose own
     * tap lands while an earlier, still-current attempt is already
     * mid-delivery — that earlier attempt can't be interrupted once its
     * chooser launch has started, so a naive fix would still let the later
     * tap open a second chooser and copy a second time once it finally
     * reached the front of the queue (Codex, PR #89, nineteenth round on
     * this mechanism). Attempt 1's chooserLaunch blocks until released,
     * simulating it still being "in flight" when attempt 2 is drawn and
     * dispatched.
     */
    @Test
    fun `a tap that lands mid-delivery reuses that delivery's outcome instead of opening a second chooser`() {
        val chooserStarted = CountDownLatch(1)
        val releaseChooser = CountDownLatch(1)
        val attempt2Ready = CountDownLatch(1)
        val clipboardWrites = AtomicInteger(0)
        val chooserLaunches = AtomicInteger(0)

        val attempt1 = DebugReport.nextAttempt()
        var result1: DebugReport.Result? = null
        val thread1 = Thread {
            result1 = DebugReport.share(
                context,
                attempt = attempt1,
                // Safe, not just successful: a reuse must require both, or
                // an incomplete delivery would be reused as if it were
                // complete (Codex, PR #89, fresh evidence).
                payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = true) },
                clipboardWrite = { _, _ -> clipboardWrites.incrementAndGet(); true },
                chooserLaunch = { _, _ ->
                    chooserLaunches.incrementAndGet()
                    chooserStarted.countDown()
                    releaseChooser.await(2, TimeUnit.SECONDS)
                    true
                },
                // Must resolve immediately: pinConsumeSafe = true above
                // means this attempt now actually invokes it (a landed
                // clipboard copy plus a safe payload), and an unresolved
                // onResult would hang this thread out to CONSUME_PIN_TIMEOUT_MS.
                consumeCrashPin = { onResult -> onResult(true) },
            )
        }
        thread1.start()
        assertTrue(
            "precondition: attempt 1 is mid-delivery",
            chooserStarted.await(2, TimeUnit.SECONDS),
        )

        val attempt2 = DebugReport.nextAttempt()
        var result2: DebugReport.Result? = null
        val thread2 = Thread {
            result2 = DebugReport.share(
                context,
                attempt = attempt2,
                // Signals readiness only once share() has already taken its
                // own deliveriesCompleted snapshot (the very first thing it
                // does), so the test can safely release attempt 1 knowing
                // attempt 2's snapshot predates attempt 1's delivery.
                payloadCollect = { attempt2Ready.countDown(); DebugReport.Payload("the report", pinConsumeSafe = false) },
                clipboardWrite = { _, _ -> clipboardWrites.incrementAndGet(); true },
                chooserLaunch = { _, _ -> chooserLaunches.incrementAndGet(); true },
                consumeCrashPin = {},
            )
        }
        thread2.start()
        assertTrue(
            "precondition: attempt 2 has started and taken its own snapshot",
            attempt2Ready.await(2, TimeUnit.SECONDS),
        )

        releaseChooser.countDown()
        thread1.join(2_000)
        thread2.join(2_000)

        assertEquals("only one delivery ever writes the clipboard", 1, clipboardWrites.get())
        assertEquals("only one delivery ever opens a chooser", 1, chooserLaunches.get())
        assertTrue(
            "attempt 2's own outcome must reflect what attempt 1 actually delivered, " +
                "not a fabricated failure for a delivery it never performed",
            result2!!.reachedUser,
        )
        assertEquals(result1, result2)
    }

    /**
     * A first version of the mid-delivery fix above reused *any* overlapping
     * outcome, success or failure — but reusing a failure means the second
     * tap's own clipboardWrite/chooserLaunch are never even attempted, so a
     * user who taps Share again while a failed first attempt is still
     * resolving gets nothing for it: no chooser opens, and they just see the
     * same failure message reappear, indistinguishable from the second tap
     * having done nothing at all (Codex, PR #89, twenty-first round on this
     * mechanism). Only a *successful* overlapping delivery is reused now;
     * a failed one falls through so the latest attempt gets a genuine retry.
     * Attempt 2 here is configured to succeed where attempt 1 fails, so a
     * naive reuse-on-any-outcome bug would show up as a false failure.
     */
    @Test
    fun `a failed overlapping delivery still lets the latest attempt make its own real retry`() {
        val chooserStarted = CountDownLatch(1)
        val releaseChooser = CountDownLatch(1)
        val attempt2Ready = CountDownLatch(1)
        val clipboardWrites2 = AtomicInteger(0)
        val chooserLaunches2 = AtomicInteger(0)
        var watchFired = 0

        val watch = DebugReport.watchShareOutcome { watchFired++ }
        try {
            val attempt1 = DebugReport.nextAttempt()
            val thread1 = Thread {
                DebugReport.share(
                    context,
                    attempt = attempt1,
                    payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = false) },
                    clipboardWrite = { _, _ -> false },
                    chooserLaunch = { _, _ ->
                        chooserStarted.countDown()
                        releaseChooser.await(2, TimeUnit.SECONDS)
                        false
                    },
                    consumeCrashPin = {},
                )
            }
            thread1.start()
            assertTrue(
                "precondition: attempt 1 is mid-delivery",
                chooserStarted.await(2, TimeUnit.SECONDS),
            )

            val attempt2 = DebugReport.nextAttempt()
            var result2: DebugReport.Result? = null
            val thread2 = Thread {
                result2 = DebugReport.share(
                    context,
                    attempt = attempt2,
                    payloadCollect = { attempt2Ready.countDown(); DebugReport.Payload("the report", pinConsumeSafe = false) },
                    clipboardWrite = { _, _ -> clipboardWrites2.incrementAndGet(); true },
                    chooserLaunch = { _, _ -> chooserLaunches2.incrementAndGet(); true },
                    consumeCrashPin = {},
                )
            }
            thread2.start()
            assertTrue(
                "precondition: attempt 2 has started and taken its own snapshot",
                attempt2Ready.await(2, TimeUnit.SECONDS),
            )

            releaseChooser.countDown()
            thread1.join(2_000)
            thread2.join(2_000)

            assertEquals("attempt 2 must actually attempt its own clipboard write", 1, clipboardWrites2.get())
            assertEquals("attempt 2 must actually attempt its own chooser launch", 1, chooserLaunches2.get())
            assertTrue("attempt 2's own retry succeeded, unlike attempt 1's", result2!!.reachedUser)
            assertFalse(
                "the latest attempt's own success must be what's visible, not attempt 1's stale failure",
                DebugReport.lastShareFailed,
            )
            assertTrue("watchShareOutcome must fire for attempt 2's own outcome", watchFired > 0)
        } finally {
            watch.close()
        }
    }

    /**
     * A delivery can reach the user without safely including a pinned
     * crash — the previous-run read that produces the crash text can time
     * out or fail, independent of whether the clipboard write or chooser
     * launch themselves succeed. Reusing that outcome for an overlapping
     * retry would silently swallow the retry's own chance at a payload that
     * genuinely captures the crash: the user taps Share again specifically
     * because they want the crash included, and a reused "success" that
     * never included it reads as a retry that did nothing (Codex, PR #89,
     * fresh evidence). Attempt 1 here reaches the user (`reachedUser =
     * true`) but with `pinConsumeSafe = false`; attempt 2, tapped mid-
     * delivery, must fall through to its own real delivery rather than
     * reuse attempt 1's incomplete one.
     */
    @Test
    fun `an incomplete overlapping delivery still lets the latest attempt make its own real retry`() {
        val chooserStarted = CountDownLatch(1)
        val releaseChooser = CountDownLatch(1)
        val attempt2Ready = CountDownLatch(1)
        val clipboardWrites2 = AtomicInteger(0)
        val chooserLaunches2 = AtomicInteger(0)

        val attempt1 = DebugReport.nextAttempt()
        val thread1 = Thread {
            DebugReport.share(
                context,
                attempt = attempt1,
                payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = false) },
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ ->
                    chooserStarted.countDown()
                    releaseChooser.await(2, TimeUnit.SECONDS)
                    true
                },
                consumeCrashPin = {},
            )
        }
        thread1.start()
        assertTrue(
            "precondition: attempt 1 is mid-delivery",
            chooserStarted.await(2, TimeUnit.SECONDS),
        )

        val attempt2 = DebugReport.nextAttempt()
        var result2: DebugReport.Result? = null
        val thread2 = Thread {
            result2 = DebugReport.share(
                context,
                attempt = attempt2,
                payloadCollect = { attempt2Ready.countDown(); DebugReport.Payload("the report", pinConsumeSafe = true) },
                clipboardWrite = { _, _ -> clipboardWrites2.incrementAndGet(); true },
                chooserLaunch = { _, _ -> chooserLaunches2.incrementAndGet(); true },
                // Must resolve immediately, same reason as the sibling test
                // above: attempt 2 actually delivers here, with a landed
                // copy and a safe payload, so this is genuinely invoked.
                consumeCrashPin = { onResult -> onResult(true) },
            )
        }
        thread2.start()
        assertTrue(
            "precondition: attempt 2 has started and taken its own snapshot",
            attempt2Ready.await(2, TimeUnit.SECONDS),
        )

        releaseChooser.countDown()
        thread1.join(2_000)
        thread2.join(2_000)

        assertEquals("attempt 2 must actually attempt its own clipboard write", 1, clipboardWrites2.get())
        assertEquals("attempt 2 must actually attempt its own chooser launch", 1, chooserLaunches2.get())
        assertTrue("attempt 2's own retry reached the user", result2!!.reachedUser)
    }

    /**
     * The mid-delivery overlap checks above all draw [DebugReport.share]'s
     * own `deliveriesAtEntry` snapshot on the same background thread that
     * runs the rest of `share()` — but that thread's actual scheduling can
     * lag the tap that started it by an unpredictable amount. A retry
     * tapped while attempt 1 is still delivering, but whose thread isn't
     * scheduled until *after* attempt 1 finishes, would sample
     * `deliveriesCompleted` post-increment and miss the overlap it
     * genuinely occurred during (Codex, PR #89, twenty-second round on this
     * mechanism). This test reproduces exactly that ordering: attempt 2's
     * ticket is drawn (on this thread, like a real tap) while attempt 1 is
     * still mid-delivery, but attempt 2's own `share()` call isn't made
     * until *after* attempt 1 has fully finished.
     */
    @Test
    fun `a ticket drawn during an active delivery is still detected as overlapping, even if its own share call starts later`() {
        val chooserStarted = CountDownLatch(1)
        val releaseChooser = CountDownLatch(1)
        val clipboardWrites2 = AtomicInteger(0)
        val chooserLaunches2 = AtomicInteger(0)

        val attempt1 = DebugReport.nextAttempt()
        var result1: DebugReport.Result? = null
        val thread1 = Thread {
            result1 = DebugReport.share(
                context,
                attempt = attempt1,
                // Safe, not just successful — same reasoning as the sibling
                // mid-delivery reuse test above (Codex, PR #89, fresh
                // evidence).
                payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = true) },
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ ->
                    chooserStarted.countDown()
                    releaseChooser.await(2, TimeUnit.SECONDS)
                    true
                },
                // Must resolve immediately — see the sibling mid-delivery
                // reuse test's own comment on this.
                consumeCrashPin = { onResult -> onResult(true) },
            )
        }
        thread1.start()
        assertTrue(
            "precondition: attempt 1 is mid-delivery",
            chooserStarted.await(2, TimeUnit.SECONDS),
        )

        // The tap happens now, synchronously on this thread — exactly like
        // MainActivity calling nextAttempt() on the tap thread — while
        // attempt 1 is still delivering.
        val attempt2 = DebugReport.nextAttempt()

        // Let attempt 1 fully finish *before* attempt 2's own share() call
        // is ever made, simulating a background thread whose scheduling
        // lagged behind the tap that started it.
        releaseChooser.countDown()
        thread1.join(2_000)
        assertNotNull("precondition: attempt 1 finished", result1)

        val result2 = DebugReport.share(
            context,
            attempt = attempt2,
            payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = false) },
            clipboardWrite = { _, _ -> clipboardWrites2.incrementAndGet(); true },
            chooserLaunch = { _, _ -> chooserLaunches2.incrementAndGet(); true },
            consumeCrashPin = {},
        )

        assertEquals(
            "attempt 2's ticket was drawn during attempt 1's delivery, so it must " +
                "reuse that outcome rather than deliver again, even though its own " +
                "share() call only ran after attempt 1 had already finished",
            0,
            clipboardWrites2.get(),
        )
        assertEquals(0, chooserLaunches2.get())
        assertEquals(result1, result2)
    }

    /**
     * `deliveriesCompleted`/`lastDeliveryResult` used to be recorded only
     * *after* the pin-consume wait finished, which can take up to
     * `CONSUME_PIN_TIMEOUT_MS` — so a retry tapped once the first attempt's
     * clipboard write and chooser launch had already both landed, with only
     * that cleanup step still outstanding, blocked on `deliveryLock` for
     * the same duration before it could even see the outcome waiting for
     * it, reading as the retry silently doing nothing (Codex, PR #89,
     * twenty-seventh round on this mechanism). The outcome is now recorded
     * and `deliveryLock` released before the pin-consume wait begins, so
     * attempt 2 here — tapped strictly after attempt 1's own delivery, not
     * during it — finds no overlap and gets its own real, prompt retry
     * rather than waiting out attempt 1's still-open cleanup step.
     */
    @Test
    fun `a retry tapped after delivery finishes gets its own prompt attempt, without waiting on a slow pin consume`() {
        val pinConsumeStarted = CountDownLatch(1)
        val releasePinConsume = CountDownLatch(1)
        val attempt2Done = CountDownLatch(1)
        val clipboardWrites2 = AtomicInteger(0)
        val chooserLaunches2 = AtomicInteger(0)

        val attempt1 = DebugReport.nextAttempt()
        val thread1 = Thread {
            DebugReport.share(
                context,
                attempt = attempt1,
                payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = true) },
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _ -> true },
                consumeCrashPin = { onResult ->
                    pinConsumeStarted.countDown()
                    releasePinConsume.await(2, TimeUnit.SECONDS)
                    onResult(true)
                },
            )
        }
        thread1.start()
        assertTrue(
            "precondition: attempt 1 has fully delivered and is now only waiting on a slow pin consume",
            pinConsumeStarted.await(2, TimeUnit.SECONDS),
        )

        // The tap happens now, strictly after attempt 1's own clipboard
        // write and chooser launch — deliveriesCompleted already reflects
        // attempt 1's delivery by this point, so this is not the
        // mid-delivery overlap the tests above cover.
        val attempt2 = DebugReport.nextAttempt()
        var result2: DebugReport.Result? = null
        val thread2 = Thread {
            result2 = DebugReport.share(
                context,
                attempt = attempt2,
                payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = true) },
                clipboardWrite = { _, _ -> clipboardWrites2.incrementAndGet(); true },
                chooserLaunch = { _, _ -> chooserLaunches2.incrementAndGet(); true },
                consumeCrashPin = { onResult -> onResult(true) },
            )
            attempt2Done.countDown()
        }
        thread2.start()

        assertTrue(
            "attempt 2 must not block behind attempt 1's still-open pin consume — " +
                "deliveryLock must already be released once only cleanup is left to do",
            attempt2Done.await(500, TimeUnit.MILLISECONDS),
        )
        assertEquals("a tap after full delivery gets its own real clipboard write", 1, clipboardWrites2.get())
        assertEquals("a tap after full delivery gets its own real chooser launch", 1, chooserLaunches2.get())
        assertNotNull(result2)
        assertTrue(result2!!.reachedUser)

        releasePinConsume.countDown()
        thread1.join(2_000)
        thread2.join(2_000)
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
            payloadCollect = { DebugReport.Payload("text", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = {},
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
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = {},
        )
        assertTrue(DebugReport.lastShareFailed)

        // The "replacement activity" draws its own ticket the same way —
        // through DebugReport, not a field it owns — so it is guaranteed
        // higher than firstAttempt regardless of anything reset on its side.
        val replacementAttempt = DebugReport.nextAttempt()
        DebugReport.share(
            context,
            attempt = replacementAttempt,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = {},
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
                payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
                clipboardWrite = { _, _ -> false },
                chooserLaunch = { _, _ -> false },
                consumeCrashPin = {},
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
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = {},
        )

        assertEquals(0, heard)
    }
}
