package app.snoozemo.snooze

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeDebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager

/**
 * The exit reason is the whole diagnostic value here: it separates a failure of
 * ours from the system reclaiming the process, which for this app is the
 * difference between a bug and a snooze the platform killed out from under.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessExitReasonsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun startRecording() {
        // The log is on by default (SPEC.md §4.6), but a sibling test turns
        // recording off, and the object is a process-wide singleton — so set it
        // explicitly rather than depending on test order.
        SnoozeDebugLog.setRecording(true)
        // Robolectric constructs SnoozemoApplication before this runs, and its
        // onCreate queues the exit collector on DebugLogging's worker. Left
        // undrained, that collection can land *after* a test seeds its own exit
        // history and duplicate the records the test's own call writes — so an
        // assertion on how many lines the buffer holds fails on timing (Codex,
        // PR #125). Draining first, then clearing, is what makes the ordering
        // explicit rather than probable.
        drainDebugLogWorker()
        SnoozeDebugLog.resetForTest()
    }

    /**
     * Blocks until the debug log's installation worker has run everything queued
     * so far, including the startup collection.
     *
     * The worker is single-threaded and FIFO, so a task queued now has run only
     * once every earlier one has. It goes through the test seam rather than
     * `afterRecordingGateApplied`, which is production API entitled to *skip*
     * its task when the recording gate is off or was never applied: latching on
     * it makes the wait conditional on state this test does not own, and a skip
     * is indistinguishable from a worker that never got there. The seam's task
     * runs unconditionally, so the wait either completes or is a real failure.
     *
     * Keeps the ten seconds the old wait had rather than the seam's default
     * five: the bound is real time in a JVM competing with every other Gradle
     * worker, and the point of this change is to remove a wait that could
     * never finish, not to leave less room for one that is merely slow.
     */
    private fun drainDebugLogWorker() {
        // The message carries the worker's own stack, because the fact this
        // assertion used to report -- that a trivial task did not reach the
        // front of a FIFO queue -- never said what was ahead of it, and two
        // diagnoses guessed from that alone were both wrong (`TODO.md`). The
        // worker is shared by every test class in the sandbox, so the culprit
        // is usually queued by a class that has already finished.
        if (!DebugLogging.awaitIdleForTest(timeoutSeconds = 10)) {
            fail(
                "the debug-log worker did not drain; startup collection may still be " +
                    "in flight.\n${DebugLogging.workerStall()}",
            )
        }
    }

    @After
    fun stopRecording() {
        SnoozeDebugLog.setRecording(false)
        SnoozeDebugLog.resetForTest()
    }

    private fun seedExit(
        reason: Int,
        importance: Int = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    ) {
        val exitInfo = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setImportance(importance)
            .setTimestamp(1_700_000_000_000L)
            .setDescription("stopped by the installer")
            .build()
        shadowOf(context.getSystemService(ActivityManager::class.java))
            .addApplicationExitInfo(exitInfo)
    }

    @Test
    fun namesTheReasonsThatSeparateOurFailuresFromThePlatformKillingUs() {
        // Ours to fix.
        assertEquals("crash", exitReasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("crashNative", exitReasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("anr", exitReasonName(ApplicationExitInfo.REASON_ANR))
        // Not ours — the system reclaiming or replacing the process. These are
        // the ones no in-process signal can see, which is why this exists, and
        // the ones that explain a snooze nothing was left alive to end.
        assertEquals("lowMemory", exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("packageUpdated", exitReasonName(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals(
            "packageStateChange",
            exitReasonName(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE),
        )
        assertEquals("userRequested", exitReasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test
    fun keepsTheNumberOfAReasonItDoesNotRecognize() {
        // A platform addition should degrade to something still diagnosable
        // rather than collapsing into an indistinguishable "unknown", which the
        // platform already uses for a reason of its own.
        assertEquals("unrecognized(9999)", exitReasonName(9999))
        assertEquals("unknown", exitReasonName(ApplicationExitInfo.REASON_UNKNOWN))
    }

    @Test
    fun namesThePriorityAndroidAssignedTheProcess() {
        // The priority Android assigned the process, separating a routine
        // background reclaim from a death the system counted as user-aware
        // work. Not proof of a visible screen — a receiver or the snooze's own
        // foreground service reaches foreground importance too.
        assertEquals(
            "foreground",
            processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND),
        )
        assertEquals(
            "cached",
            processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED),
        )
        assertEquals(
            "gone",
            processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE),
        )
        assertEquals("unrecognized(7)", processImportanceName(7))
    }

    @Test
    fun recordsEachRecentExitWithItsReasonNamed() {
        // The mapping tests above prove the names are right; this proves the
        // query actually runs and its answers reach the log. Without it the
        // suite stays green if the collection is deleted, asks for the wrong
        // package, or drops its results on the floor — which is the feature.
        seedExit(ApplicationExitInfo.REASON_CRASH)
        seedExit(ApplicationExitInfo.REASON_PACKAGE_UPDATED)

        logRecentProcessExits(context)

        val lines = SnoozeDebugLog.snapshot().filter { it.contains("processExit ") }
        assertEquals(2, lines.size)
        assertTrue(lines.toString(), lines.any { it.contains("reason=crash") })
        assertTrue(lines.toString(), lines.any { it.contains("reason=packageUpdated") })
        assertTrue(lines.toString(), lines.all { it.contains("importance=foreground") })
    }

    @Test
    fun saysSoWhenThePlatformHasNoExitRecords() {
        // A fresh install, or a device that has pruned its records. The line
        // matters because its absence would otherwise be ambiguous with the
        // query having failed or never run.
        logRecentProcessExits(context)

        assertTrue(SnoozeDebugLog.snapshot().any { it.contains("processExits none") })
    }

    @Test
    fun recordsNothingWhileTheDebugLogIsOff() {
        // The log is on by default, but turning it off means off — it stops
        // recording and deletes what it kept (SPEC.md §4.6, docs/PRIVACY.md).
        // These records must honor that switch like every other entry rather
        // than becoming collection the user cannot stop.
        //
        // DebugLogging.afterRecordingGateApplied goes further and skips the
        // collection entirely when the gate says Off, so the queries are never
        // even issued. That stronger property is about the production queueing
        // path, not this direct call, so it is not what this test covers; the
        // guard is small and commented at the decision.
        SnoozeDebugLog.setRecording(false)
        seedExit(ApplicationExitInfo.REASON_LOW_MEMORY)

        logRecentProcessExits(context)

        assertTrue(SnoozeDebugLog.snapshot().isEmpty())
    }
}
