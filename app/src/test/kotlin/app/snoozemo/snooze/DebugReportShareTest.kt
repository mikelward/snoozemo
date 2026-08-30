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

    @Test
    fun `a landed clipboard copy consumes the crash pin`() {
        var pinConsumed = false

        val result = DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { _, onResult -> pinConsumed = true; onResult(true) },
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
            consumeCrashPin = { _, _ -> pinConsumed = true },
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
            consumeCrashPin = { _, _ -> pinConsumed = true },
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
            consumeCrashPin = { _, _ -> },
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
            consumeCrashPin = { _, onResult -> onResult(false) },
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
            consumeCrashPin = { _, _ -> pinConsumed = true },
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
        SnoozeDebugLog.resetForTest()
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

        val result = DebugReport.share(
            context,
            clipboardWrite = { _, text -> sharedText = text; true },
            chooserLaunch = { _, _ -> true },
        )

        assertTrue(result.clipboardCopied)
        val text = requireNotNull(sharedText)
        assertTrue(text.contains("Version: unknown (-1)"))
    }

    @Test
    fun `an exception from consumeCrashPin does not take the share down with it`() {
        val result = DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { _, _ -> throw RuntimeException("boom") },
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
            consumeCrashPin = { _, _ -> },
        )
        assertTrue(DebugReport.lastShareFailed)

        DebugReport.share(
            context,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { _, _ -> },
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
            consumeCrashPin = { _, _ -> },
        )
        assertFalse("the newer, faster attempt succeeded", DebugReport.lastShareFailed)

        DebugReport.share(
            context,
            attempt = attempt1,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = { _, _ -> },
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
            consumeCrashPin = { _, _ -> },
        )
        assertFalse(DebugReport.lastShareFailed)

        val attempt2 = DebugReport.nextAttempt()
        DebugReport.share(
            context,
            attempt = attempt2,
            payloadCollect = { DebugReport.Payload("irrelevant", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = { _, _ -> },
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
            consumeCrashPin = { _, _ -> },
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
            consumeCrashPin = { _, _ -> },
        )

        var clipboardWriteCalled = false
        var chooserLaunchCalled = false
        val staleResult = DebugReport.share(
            context,
            attempt = attempt1,
            payloadCollect = { DebugReport.Payload("the stale first attempt's report", pinConsumeSafe = true) },
            clipboardWrite = { _, _ -> clipboardWriteCalled = true; true },
            chooserLaunch = { _, _ -> chooserLaunchCalled = true; true },
            consumeCrashPin = { _, _ -> },
        )

        assertFalse("a superseded attempt must never write the clipboard", clipboardWriteCalled)
        assertFalse("a superseded attempt must never open a chooser", chooserLaunchCalled)
        assertFalse("its own returned Result reports nothing delivered either", staleResult.clipboardCopied)
        assertFalse(staleResult.reachedUser)
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
            payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = false) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { _, _ -> },
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
            payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = false) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _ -> false },
            consumeCrashPin = { _, _ -> },
        )

        assertFalse("precondition: this attempt genuinely failed", result.reachedUser)
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
            payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = false) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _ -> true },
            consumeCrashPin = { _, _ -> },
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
                    payloadCollect = { DebugReport.Payload("the report", pinConsumeSafe = false) },
                    clipboardWrite = { _, _ ->
                        val now = inDelivery.incrementAndGet()
                        maxConcurrent.updateAndGet { seen -> maxOf(seen, now) }
                        Thread.sleep(10)
                        inDelivery.decrementAndGet()
                        true
                    },
                    chooserLaunch = { _, _ -> true },
                    consumeCrashPin = { _, _ -> },
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

    /**
     * `deliveriesCompleted`/`lastDeliveryResult` used to be recorded only
     * *after* the pin-consume wait finished, which can take up to
     * `CONSUME_PIN_TIMEOUT_MS` — so a retry tapped once the first attempt's
     * clipboard write and chooser launch had already both landed, with only
     * that cleanup step still outstanding, blocked on `deliveryLock` for
     * the same duration before it could even see the outcome waiting for
     * it, reading as the retry silently doing nothing (Codex, PR #89,
     * twenty-seventh round on this mechanism). The outcome — and now
     * `shareInFlight` — is applied, and `deliveryLock` released, before the
     * pin-consume wait begins, so attempt 2 here, tapped strictly after
     * attempt 1's own delivery, gets its own real, prompt retry rather than
     * waiting out attempt 1's still-open cleanup step. The gate matters as
     * much as the lock now: holding the button disabled through that cleanup
     * would read the same way to the user.
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
                consumeCrashPin = { _, onResult ->
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
                consumeCrashPin = { _, onResult -> onResult(true) },
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
            consumeCrashPin = { _, _ -> },
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
            consumeCrashPin = { _, _ -> },
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
            consumeCrashPin = { _, _ -> },
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
                consumeCrashPin = { _, _ -> },
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
            consumeCrashPin = { _, _ -> },
        )

        assertEquals(0, heard)
    }
}
