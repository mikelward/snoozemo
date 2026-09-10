package app.snoozemo.snooze

import android.app.NotificationManager
import android.content.Intent
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.SnoozeLifecycle
import app.snoozemo.core.SnoozeRecordState
import app.snoozemo.core.ZenRuleActivation
import app.snoozemo.core.identity
import java.time.Instant
import org.junit.Assert.assertEquals
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

    /**
     * The ongoing card as currently posted, by identity.
     *
     * Robolectric's shadow keeps the notifications that are *up*, not a log of
     * posts, so a repost replaces rather than appends — and every post builds a
     * fresh `Notification`, which is what makes the object the observation.
     */
    private fun ongoingCard(): android.app.Notification? {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val title = appContext.getString(app.snoozemo.R.string.ongoing_title)
        return org.robolectric.Shadows.shadowOf(manager).allNotifications
            .lastOrNull { org.robolectric.Shadows.shadowOf(it).contentTitle?.toString() == title }
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
    fun `an activation of our own rule applies the ceiling the arm deferred`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)
        TestSnoozeService.zen.activation = ZenRuleActivation.ACTIVE
        TestSnoozeService.zen.ceilingsApplied.clear()

        sendStatus(OWN_RULE_ID, NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED)

        // The moment the arm waits for. `ZenRuleStatusChange.resolve` answers
        // `None` here — an activation is never an ending — so this is the one
        // branch of the whole §5.8 path that used to do nothing at all, and it
        // is now where the ringer ceiling actually lands.
        //
        // Under the running snooze's own identity, so the ceiling record is
        // this snooze's rather than a previous one's (SPEC.md §5.9 rule 2).
        assertEquals(
            listOf(snoozeFixture(now).identity),
            TestSnoozeService.zen.ceilingsApplied,
        )
    }

    @Test
    fun `applying the deferred ceiling reposts the ongoing card`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)
        TestSnoozeService.zen.activation = ZenRuleActivation.ACTIVE
        val before = ongoingCard()
        assertTrue("the arm posts a card to repost", before != null)

        sendStatus(OWN_RULE_ID, NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED)

        // The card was posted at the arm, before the ceiling had been applied,
        // and applying it here is not a state transition — so without this
        // repost a refused ringer write would leave a phone ringing under a
        // card that does not say so until the half-hourly backstop (Codex,
        // PR #250).
        assertTrue("the card was not reposted", ongoingCard() !== before)
    }

    @Test
    fun `an activation whose read-back is not active applies nothing`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)
        // Exactly the shape a device capture caught on 2026-09-10: the
        // `ACTIVATED` broadcast arrives while the rule reads back `INACTIVE`,
        // milliseconds before a `DEACTIVATED` that ended the snooze. Writing
        // the ringer here is the very thing that appears to knock the rule
        // down, so the broadcast alone must not be enough.
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE
        TestSnoozeService.zen.ceilingsApplied.clear()

        sendStatus(OWN_RULE_ID, NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED)

        assertEquals(emptyList<Any?>(), TestSnoozeService.zen.ceilingsApplied)
    }

    @Test
    fun `a later wake catches up a ceiling the activation raced`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)
        // The residual race: the rule's one `ACTIVATED` arrives while the
        // read-back still disagrees, so the broadcast cannot be the moment the
        // ceiling lands and there is no second one coming.
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE
        sendStatus(OWN_RULE_ID, NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED)
        assertEquals(emptyList<Any?>(), TestSnoozeService.zen.ceilingsApplied)

        // The rule really is in effect by the next wake the snooze already pays
        // for, and that is where the ceiling catches up — without it the phone
        // stays above its ceiling for the whole snooze (Codex, PR #250).
        TestSnoozeService.zen.activation = ZenRuleActivation.ACTIVE
        startService(SnoozeService.ACTION_CHECK_CAP)

        assertEquals(
            listOf(snoozeFixture(now).identity),
            TestSnoozeService.zen.ceilingsApplied,
        )
    }

    @Test
    fun `a wake with the rule still not in effect catches nothing up`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE
        TestSnoozeService.zen.ceilingsApplied.clear()

        startService(SnoozeService.ACTION_CHECK_CAP)

        // The catch-up is not a way around the gate: it applies the ceiling
        // only where the read-back agrees, same as the broadcast does.
        assertEquals(emptyList<Any?>(), TestSnoozeService.zen.ceilingsApplied)
    }

    @Test
    fun `an activation of somebody else's rule applies nothing`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)
        TestSnoozeService.zen.activation = ZenRuleActivation.ACTIVE
        TestSnoozeService.zen.ceilingsApplied.clear()

        sendStatus("some-other-app's-rule", NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED)

        // Another app's rule going on says nothing about ours being in effect,
        // and the read-back above is about *our* rule — so without the
        // ownership gate a foreign activation would be enough to lower the
        // ringer for our snooze.
        assertEquals(emptyList<Any?>(), TestSnoozeService.zen.ceilingsApplied)
    }

    @Test
    fun `a broadcast with no rule id is neither`() {
        runningSnooze()
        startService(SnoozeService.ACTION_RESTORE)

        sendStatus(null, NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED)

        assertTrue(ruleStatusLine(), ruleStatusLine().contains("rule=unnamed"))
    }
}
