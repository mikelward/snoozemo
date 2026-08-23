package app.snoozemo.snooze

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeDebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
