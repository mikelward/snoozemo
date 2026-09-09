package app.snoozemo.snooze

import android.content.Intent
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.SnoozeLifecycle
import app.snoozemo.core.SnoozeRecordState
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleActivation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * Which wake-ups ask the platform whether the user turned Do Not Disturb off,
 * and what the answer is taken to mean (SPEC.md §5.8, §4.6).
 *
 * The read covers two gaps, both named where it is justified: registration
 * "can be refused", and "the process only lives between wake-ups, so a snooze
 * can outlive the only thing watching it". On a snooze this process is already
 * holding, with the receiver registered, neither is open — the broadcast would
 * have carried any status change, so the read can only repeat it or contradict
 * it.
 *
 * Contradict it is what it did. Every wake-up that is not an arm or an explicit
 * ending took the read, `ACTION_SET_CAP` included — so **choosing an end time**
 * asked whether the user had reached the switch, on a snooze they were at that
 * moment refining, and an inactive answer ended it.
 *
 * So these cases split by what the process could have heard rather than by
 * which action arrived: a cold wake-up still reads, a held snooze does not, and
 * a held snooze whose receiver was refused reads again — that last one is the
 * gap the skip must not close.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreReadDiagnosticTest {

    private val now: Instant = Instant.parse("2026-08-22T09:00:00Z")

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        SnoozeDebugLog.resetForTest()
        // The restore has to succeed for the service to be holding anything; a
        // refusing default would end every snooze before a cap could be chosen.
        TestSnoozeService.zen.outcome = ZenOutcome.Applied(OWN_RULE_ID)
    }

    /** The restore read's line, or null if the read was skipped. */
    private fun restoreReadLine(): String? =
        SnoozeDebugLog.snapshot().singleOrNull { it.contains("restore read:") }

    /**
     * A snooze that is genuinely **running**, which is what a refinement acts
     * on — the record armed *and* marked past `ARMING`.
     *
     * The distinction is not bookkeeping: `endingFor` refuses to classify an
     * `ARMING` record at all, whatever the rule reads as, because the record is
     * written before the rule and that window belongs to an arm that never
     * finished. A fixture left in `ARMING` therefore reports "nothing to do"
     * for every activation and would make these cases pass while proving
     * nothing.
     */
    private fun runningSnooze() {
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))
        ActiveSnoozeStore(appContext).setState(SnoozeRecordState(SnoozeLifecycle.ARMED))
    }

    /**
     * A second start on the **same** service instance, which is the whole
     * point: a fresh instance has picked nothing up yet, so it is the cold case
     * however the record on disk reads. Only a service still holding the snooze
     * it restored can demonstrate the skip.
     */
    private fun ServiceController<TestSnoozeService>.send(action: String, startId: Int) =
        get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java).setAction(action),
            0,
            startId,
        )

    /**
     * A service that has restored the snooze and is holding it, with the
     * platform now reporting the rule inactive.
     *
     * The order matters and is not decoration: the restore drives the rule to
     * `STATE_TRUE`, and the fake follows the platform in remembering that — so
     * an activation set *before* the restore is overwritten by it, and a test
     * built that way would pass because the reading was benign rather than
     * because the read was skipped.
     */
    private fun heldSnoozeWithTheRuleReportedOff(): ServiceController<TestSnoozeService> {
        runningSnooze()
        val service = startService(SnoozeService.ACTION_RESTORE)
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE
        // The cold restore is entitled to its own read; clear the log so what
        // remains belongs to the wake-up under test.
        SnoozeDebugLog.resetForTest()
        return service
    }

    @Test
    fun `choosing an end time on a snooze this process holds does not read the rule back`() {
        val service = heldSnoozeWithTheRuleReportedOff()
        val readsBefore = TestSnoozeService.zen.activationAskedFor.size

        service.send(SnoozeService.ACTION_SET_CAP, startId = 2)

        // Asked directly of the fake, not inferred from the log: the log line
        // is the diagnostic, the read itself is the behavior.
        assertEquals(
            TestSnoozeService.zen.activationAskedFor.toString(),
            readsBefore,
            TestSnoozeService.zen.activationAskedFor.size,
        )
        assertNull(SnoozeDebugLog.snapshot().toString(), restoreReadLine())
        // And the user-visible half: absent the skip this classifies as
        // `DND_TURNED_OFF` and the snooze ends here, which is the whole bug.
        // An ending drives the rule back off, so a single un-snooze call is
        // exactly what must not appear.
        assertTrue(
            TestSnoozeService.zen.calls.toString(),
            TestSnoozeService.zen.calls.none { (snoozed, _) -> !snoozed },
        )
    }

    @Test
    fun `a cold wake-up still reads, because nothing was listening`() {
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        runningSnooze()
        startService(SnoozeService.ACTION_SET_CAP)

        // The gap the read exists for: no process of ours was alive when the
        // user reached the switch, so no broadcast reached anything.
        val line = requireNotNull(restoreReadLine())
        assertTrue(line, line.contains("start=SET_CAP"))
        assertTrue(line, line.contains("rule=INACTIVE"))
        assertTrue(line, line.contains("record=ARMED"))
        assertTrue(line, line.contains("verdict=DND_TURNED_OFF"))
    }

    @Test
    fun `a held snooze whose receiver was refused reads anyway`() {
        // Liveness alone is not a watch. The real registration throws, so the
        // real flag records this process as alive and blind — the first of the
        // two gaps, and the one the skip must leave open.
        TestSnoozeService.refuseRuleStatusReceiver = true

        val service = heldSnoozeWithTheRuleReportedOff()

        service.send(SnoozeService.ACTION_SET_CAP, startId = 2)

        val line = requireNotNull(restoreReadLine())
        assertTrue(line, line.contains("verdict=DND_TURNED_OFF"))
    }

    @Test
    fun `a surviving read still says so`() {
        TestSnoozeService.zen.activation = ZenRuleActivation.ACTIVE

        runningSnooze()
        startService(SnoozeService.ACTION_SET_CAP)

        val line = requireNotNull(restoreReadLine())
        assertTrue(line, line.contains("rule=ACTIVE"))
        // The no-op is kept for the same reason the rule-status line keeps its
        // own: a snooze that survives a read is as informative as one that does
        // not, and a line that appeared only on endings would make every
        // capture look like the read always ends them.
        assertTrue(line, line.contains("verdict=nothing to do"))
    }

    @Test
    fun `the arm is not a restore, and reads nothing`() {
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        startService(SnoozeService.ACTION_ARM)

        // The read is skipped on the arm path deliberately — it would cost a
        // policy IPC between the tap and the phone going quiet — so there is no
        // line, and the absence is what says the arm was not classified.
        assertNull(SnoozeDebugLog.snapshot().toString(), restoreReadLine())
    }
}
