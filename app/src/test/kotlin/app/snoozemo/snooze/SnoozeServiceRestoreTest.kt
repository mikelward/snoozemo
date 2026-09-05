package app.snoozemo.snooze

import app.snoozemo.core.SnoozeLifecycle
import app.snoozemo.core.EndReason
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleActivation
import app.snoozemo.core.ZenTrigger
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What happens when the user turns Do Not Disturb off while no process of ours
 * is alive to hear it (SPEC.md §5.8).
 *
 * The broadcast is missed by definition here, so the only thing that can notice
 * is the state read on the next wake-up — and the bug this covers is that the
 * restore *destroyed the evidence first* by re-asserting the rule, so the read
 * that followed could only ever see `ACTIVE` (Codex, PR #36).
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceRestoreTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
    }

    /** A record whose arm completed, which is what a running snooze looks like. */
    private fun armed(snooze: app.snoozemo.core.ActiveSnooze) {
        ActiveSnoozeStore(appContext).arm(snooze.copy(lifecycle = SnoozeLifecycle.ARMED))
    }

    @Test
    fun `an armed snooze stays armed across a record update`() {
        // The marker used to live beside the record, where every later save — a
        // clock change, a tracking update — wiped it, so a running snooze read
        // back as an unfinished arm and got its rule re-asserted over a Do Not
        // Disturb the user had switched off (Codex, PR #36). On the record it
        // survives an update because it is part of what is being updated.
        val store = ActiveSnoozeStore(appContext)
        val snooze = snoozeFixture(now).copy(lifecycle = SnoozeLifecycle.ARMED)
        store.arm(snooze)

        store.update(snooze.copy(placeName = "Work"))

        assertEquals(SnoozeLifecycle.ARMED, store.load()?.lifecycle)
    }

    @Test
    fun `an arm that died before the rule went on is finished, not blamed on the user`() {
        // The record is written before the rule on purpose, so a crash in that
        // window leaves a live record over an off rule — the same shape as the
        // user having switched Do Not Disturb off (Codex, PR #36). Reading it
        // that way would silently delete a snooze they asked for and never got.
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        startService(SnoozeService.ACTION_REFRESH)

        // Finished rather than discarded: the rule goes on and the record stays.
        assertEquals(listOf(true to ZenTrigger.CONTEXT), TestSnoozeService.zen.calls)
        assertNotNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a snooze is not restored over a Do Not Disturb the user turned off`() {
        // The record is still on disk — nothing was alive to clear it — so a
        // refresh, a clock change or a reboot arrives with a stale snooze and a
        // rule the user has already switched off.
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        armed(snoozeFixture(now))
        startService(SnoozeService.ACTION_REFRESH)

        // Never re-asserted. Restoring would turn the phone back to silent
        // *after an explicit instruction not to be* — the app overriding the
        // user, which is worse than any snooze ending early.
        assertEquals(
            emptyList<Pair<Boolean, ZenTrigger>>(),
            TestSnoozeService.zen.calls.filter { it.first },
        )
        // And the snooze is over rather than left running to its cap: the
        // record is gone, so nothing adopts it at the next wake-up.
        assertNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a snooze whose rule was replaced ends as a lost capability, not as the user's doing`() {
        // The sequence the current-id inference got wrong (Codex, PR #36): the
        // user deletes the rule, the tile's next shade-open mints a
        // replacement, and the next wake-up's read-back — asked about the rule
        // the app holds *now* — sees a rule that is enabled and off and calls
        // it the user turning Do Not Disturb off. That ending is the silent
        // one, so a lost capability disappeared behind it.
        TestSnoozeService.zen.activationById["the-deleted-rule"] = ZenRuleActivation.MISSING
        // The replacement looks fine: enabled, and never turned on for this snooze.
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        armed(snoozeFixture(now).copy(ruleId = "the-deleted-rule"))
        startService(SnoozeService.ACTION_REFRESH)

        // Asked about the record's rule, not the current one.
        assertEquals(listOf<String?>("the-deleted-rule"), TestSnoozeService.zen.activationAskedFor)
        // Ended, never re-asserted, and told as what it is.
        assertNull(ActiveSnoozeStore(appContext).load())
        assertEquals(
            emptyList<Pair<Boolean, ZenTrigger>>(),
            TestSnoozeService.zen.calls.filter { it.first },
        )
        assertTrue(
            "a rule that is gone is a lost capability, which explains itself",
            shadeShows(stringOf(app.snoozemo.R.string.ended_lost_capability)),
        )
    }

    @Test
    fun `an ending the app decided on keeps its reason across a crash`() {
        // The window: we turned the rule off for the cap, then the process died
        // before the record was cleared. The next wake-up finds a live record
        // over an off rule — which looks exactly like the user having turned Do
        // Not Disturb off, and that ending is deliberately silent, so the real
        // reason would vanish along with the notification explaining it
        // (Codex, PR #36).
        // Saved first, then marked: arming clears the marker, so writing it
        // before the record would be wiped by the very save that creates the
        // snooze it describes.
        armed(snoozeFixture(now))
        ActiveSnoozeStore(appContext).markReleasing(EndReason.DURATION_CAP)
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        startService(SnoozeService.ACTION_REFRESH)

        // The trigger is the visible half of the attribution (SPEC.md §5.4):
        // the cap is the phone deciding, not the user.
        assertEquals(listOf(false to ZenTrigger.CONTEXT), TestSnoozeService.zen.calls)
    }

    @Test
    fun `a refused ending on an off rule keeps trying rather than waiting for the cap`() {
        // The read-back runs only on restoring wake-ups, which nothing
        // guarantees will repeat before the cap — so a release refused here
        // used to leave the record, tile and card claiming a snooze over a
        // deactivated rule for the rest of it (Codex, PR #36). Every other
        // ending on the service re-arms the cap and escalates; this one has to.
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE
        TestSnoozeService.zen.outcome = ZenOutcome.NotApplied(app.snoozemo.core.ZenFailure.PLATFORM_REFUSED)

        armed(snoozeFixture(now, capIn = java.time.Duration.ofHours(7)))
        startService(SnoozeService.ACTION_REFRESH)

        assertEquals(
            "the ending must be asked for",
            listOf(false to ZenTrigger.USER_ACTION),
            TestSnoozeService.zen.calls,
        )
        assertTrue(
            "a refused ending must escalate past a cap that is hours away",
            scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_CAP_LOST },
        )
    }

    @Test
    fun `a pending reason survives a restore that finds the rule still on`() {
        // The shape a refused release leaves after a process death: a live
        // record, a rule still on, and the reason the retry ladder is still
        // pursuing. The restore leaves it for that retry (SPEC.md §5.8;
        // maintainer, 2026-09-05) — only a completed or abandoned ending
        // clears it.
        armed(snoozeFixture(now))
        ActiveSnoozeStore(appContext).markReleasing(EndReason.DURATION_CAP)
        TestSnoozeService.zen.activation = ZenRuleActivation.ACTIVE

        startService(SnoozeService.ACTION_REFRESH)

        assertEquals(EndReason.DURATION_CAP, ActiveSnoozeStore(appContext).releasingReason())
        assertNotNull("the snooze itself keeps running", ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `with no marker the user gets the credit`() {
        // The other side of the same branch: nothing of ours was mid-release, so
        // an off rule really was the user reaching the switch — and that ending
        // stays silent, because they know.
        TestSnoozeService.zen.activation = ZenRuleActivation.INACTIVE

        armed(snoozeFixture(now))
        startService(SnoozeService.ACTION_REFRESH)

        assertEquals(listOf(false to ZenTrigger.USER_ACTION), TestSnoozeService.zen.calls)
    }

    @Test
    fun `a rule that is still on restores normally`() {
        // The other half, and the reason this can't just skip restoring: an
        // ordinary wake-up mid-snooze must still pick the record back up and
        // keep enforcing.
        TestSnoozeService.zen.activation = ZenRuleActivation.ACTIVE

        armed(snoozeFixture(now))
        startService(SnoozeService.ACTION_REFRESH)

        assertEquals(listOf(true to ZenTrigger.CONTEXT), TestSnoozeService.zen.calls)
    }
}
