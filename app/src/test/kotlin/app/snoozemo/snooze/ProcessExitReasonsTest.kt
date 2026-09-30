package app.snoozemo.snooze

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeDebugLog
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.ProcessExits
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
                    "in flight.\n${DebugLogging.workerStall(ApplicationProvider.getApplicationContext())}",
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
        description: String = "stopped by the installer",
    ) {
        val exitInfo = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setImportance(importance)
            .setTimestamp(1_700_000_000_000L)
            .setDescription(description)
            .build()
        shadowOf(context.getSystemService(ActivityManager::class.java))
            .addApplicationExitInfo(exitInfo)
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

        // Pinned, so the ring can't evict them before a report is shared.
        val lines = SnoozeDebugLog.pinnedSnapshot().filter { it.contains("processExit ") }
        assertEquals(2, lines.size)
        assertTrue(lines.toString(), lines.any { it.contains("reason=crash") })
        assertTrue(lines.toString(), lines.any { it.contains("reason=packageUpdated") })
        assertTrue(lines.toString(), lines.all { it.contains("importance=foreground") })
        // SPEC.md §4.6 lists the platform's description among the fields.
        assertTrue(lines.toString(), lines.all { it.contains("description=stopped by the installer") })
        assertTrue(
            SnoozeDebugLog.pinnedSnapshot().toString(),
            SnoozeDebugLog.pinnedSnapshot().any { it.contains("ownPackage lastUpdateTime=") },
        )
    }

    @Test
    fun aFullBatchOfLongExitsReachesTheReportAfterTheRingHasDroppedIt() {
        // The shape the report's reserve has to hold: every record the
        // collector keeps, each with a description far past its bound, pushed
        // out of the ring by a busy run before anyone shares a report.
        repeat(ProcessExits.DEFAULT_MAX_RECORDS) {
            seedExit(ApplicationExitInfo.REASON_ANR, description = "Input dispatching timed out ".repeat(100))
        }
        logRecentProcessExits(context)
        repeat(DebugLog.DEFAULT_MAX_ENTRIES + 50) { SnoozeDebugLog.event("busy %s", it) }
        // Precondition: only the pinned copy can carry them now.
        assertTrue(SnoozeDebugLog.snapshot().none { it.contains("processExit ") })

        val report = recentLogForReport()

        val exits = report.filter { it.contains("processExit reason=anr") }
        assertEquals(report.toString(), ProcessExits.DEFAULT_MAX_RECORDS, exits.size)
        assertTrue(report.toString(), report.any { it.contains("ownPackage lastUpdateTime=") })
        // And the recent lines still follow them.
        assertTrue(report.toString(), report.last().endsWith("busy ${DebugLog.DEFAULT_MAX_ENTRIES + 49}"))
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
