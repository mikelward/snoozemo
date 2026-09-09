package app.snoozemo.snooze

import android.app.NotificationManager
import android.content.Intent
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.SnoozeLifecycle
import app.snoozemo.core.SnoozeRecordState
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rule-status broadcast reaches the decision that acts on it (SPEC.md §5.8).
 *
 * Everything below this wiring is covered in `:core` — `ZenRuleStatusChange`
 * decides, `RuleOwnership` compares — and all of it was passing while the
 * broadcast's rule id never arrived at all. `ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED`
 * carries `EXTRA_AUTOMATIC_ZEN_RULE_ID`; the receiver read
 * `EXTRA_AUTOMATIC_RULE_ID`, which is a different constant with a different
 * value, belonging to the `ACTION_AUTOMATIC_ZEN_RULE` *configuration* intent.
 *
 * The consequence was silent and total: `ruleId` was always null,
 * `RuleOwnership.isOurs` returns false on its first line for a null id, and so
 * `ours` could never be true — no status change was ever ours, and the whole
 * §5.8 path, its stale-broadcast veto included, could not run in the field.
 * Nothing failed, because nothing tested the wiring.
 *
 * This repo has been bitten by the same near-name pair once already, on the
 * Settings side (PR #88), where reading the javadoc was not enough.
 */
@RunWith(RobolectricTestRunner::class)
class RuleStatusReceiverTest {

    private val now: Instant = Instant.parse("2026-08-22T09:00:00Z")

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        SnoozeDebugLog.resetForTest()
    }

    private fun runningSnooze() {
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))
        ActiveSnoozeStore(appContext).setState(SnoozeRecordState(SnoozeLifecycle.ARMED))
    }

    private fun sendStatus(ruleId: String?, status: Int) {
        appContext.sendBroadcast(
            Intent(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED).apply {
                ruleId?.let { putExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID, it) }
                putExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS, status)
            },
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun ruleStatusLine(): String =
        SnoozeDebugLog.snapshot().last { it.contains("rule status:") }

    @Test
    fun `a status change for our own rule is recognized as ours`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)

        sendStatus(OWN_RULE_ID, NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED)

        // The field the extra decides. `another` here means the id never
        // arrived, which is what reading the configuration intent's extra off
        // this broadcast produces.
        assertTrue(ruleStatusLine(), ruleStatusLine().contains("rule=ours"))
    }

    @Test
    fun `a status change for somebody else's rule is not ours`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)

        sendStatus("some-other-app's-rule", NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED)

        // Both directions, so a receiver that hard-coded "ours" would fail too.
        assertTrue(ruleStatusLine(), ruleStatusLine().contains("rule=another"))
    }

    @Test
    fun `a broadcast with no rule id is neither`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)

        sendStatus(null, NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED)

        assertTrue(ruleStatusLine(), ruleStatusLine().contains("rule=unnamed"))
    }
}
