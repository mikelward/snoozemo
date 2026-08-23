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
    }

    @After
    fun tearDown() {
        DebugReport.resetForTest()
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
