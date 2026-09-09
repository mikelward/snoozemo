package app.snoozemo.snooze

import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.SnoozeLifecycle
import app.snoozemo.core.SnoozeRecordState
import app.snoozemo.core.ZenRuleActivation
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the restore read saw, in the log a user hands over (SPEC.md §4.6).
 *
 * This is the *second* way a snooze ends as `DND_TURNED_OFF`, and until now it
 * explained nothing: the decision went to logcat, which does not reach a shared
 * report, so a capture showed a tap and then an ending with no way to tell this
 * path from the rule-status broadcast and no way to see what it read.
 *
 * The cases below double as the record of a suspected cause. `ACTION_SET_CAP`
 * is not on the list of starts that skip the read, so **choosing an end time
 * takes it** — on a snooze that is running and being refined — and an inactive
 * reading there is classified as the user reaching the Do Not Disturb switch.
 * That matches a device report ("I snooze now, then tap until 10:30, but it
 * ended the snooze now") in a way the broadcast path does not, and it was
 * established by reading the code; this pins it as behavior instead. When the
 * fix lands these expectations are what has to change, loudly.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreReadDiagnosticTest {

    private val now: Instant = Instant.parse("2026-08-22T09:00:00Z")

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        SnoozeDebugLog.resetForTest()
    }

    private fun restoreReadLine(): String =
        SnoozeDebugLog.snapshot().single { it.contains("restore read:") }

    /**
     * A snooze that is genuinely **running**, which is what a refinement acts
     * on — the record armed *and* marked past `ARMING`.
     *
     * The distinction is not bookkeeping: `endingFor` refuses to classify an
     * `ARMING` record at all, whatever the rule reads as, because the record is
     * written before the rule and that window belongs to an arm that never
     * finished. A fixture left in `ARMING` therefore reports "nothing to do"
     * for every activation and would have made these cases pass while proving
     * nothing.
     */
    private fun runningSnooze() {
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))
        ActiveSnoozeStore(appContext).setState(SnoozeRecordState(SnoozeLifecycle.ARMED))
    }

    @Test
    fun `choosing an end time reads the rule back, and an inactive read classifies as the user's`() {
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        runningSnooze()
        startService(SnoozeService.ACTION_SET_CAP)

        val line = restoreReadLine()
        // Every field the next capture has to answer with, and the reason each
        // is there: which start ran the read, what it read, what the record
        // said, and what those two together were taken to mean.
        assertTrue(line, line.contains("start=SET_CAP"))
        assertTrue(line, line.contains("rule=INACTIVE"))
        assertTrue(line, line.contains("record=ARMED"))
        assertTrue(line, line.contains("verdict=DND_TURNED_OFF"))
    }

    @Test
    fun `a snooze that survives the read says so too`() {
        TestSnoozeService.zen.activation = ZenRuleActivation.ACTIVE

        runningSnooze()
        startService(SnoozeService.ACTION_SET_CAP)

        val line = restoreReadLine()
        assertTrue(line, line.contains("rule=ACTIVE"))
        // The no-op is kept for the same reason the rule-status line keeps
        // its own: a snooze that survives a read is as informative as one that
        // does not, and a line that appeared only on endings would make every
        // capture look like the read always ends them.
        assertTrue(line, line.contains("verdict=nothing to do"))
    }

    @Test
    fun `the arm is not a restore, and reads nothing`() {
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        startService(SnoozeService.ACTION_ARM)

        // The read is skipped on the arm path deliberately — it would cost a
        // policy IPC between the tap and the phone going quiet — so there is
        // no line, and the absence is what says the arm was not classified.
        assertTrue(
            SnoozeDebugLog.snapshot().toString(),
            SnoozeDebugLog.snapshot().none { it.contains("restore read:") },
        )
    }
}
