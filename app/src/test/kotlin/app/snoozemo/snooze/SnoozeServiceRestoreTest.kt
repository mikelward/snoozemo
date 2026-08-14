package app.snoozemo.snooze

import app.snoozemo.core.EndReason
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleActivation
import app.snoozemo.core.ZenTrigger
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What happens when the user turns Do Not Disturb off while no process of ours
 * is alive to hear it (SPEC.md §5.7).
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
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
    }

    /** A record whose arm completed, which is what a running snooze looks like. */
    private fun armed(snooze: app.snoozemo.core.ActiveSnooze) {
        ActiveSnoozeStore(appContext).save(snooze.copy(armed = true))
    }

    @Test
    fun `an armed snooze stays armed across a record update`() {
        // The marker used to live beside the record, where every later save — a
        // clock change, a tracking update — wiped it, so a running snooze read
        // back as an unfinished arm and got its rule re-asserted over a Do Not
        // Disturb the user had switched off (Codex, PR #36). On the record it
        // survives an update because it is part of what is being updated.
        val store = ActiveSnoozeStore(appContext)
        val snooze = snoozeFixture(now).copy(armed = true)
        store.save(snooze)

        store.save(snooze.copy(placeName = "Work"))

        assertEquals(true, store.load()?.armed)
    }

    @Test
    fun `an arm that died before the rule went on is finished, not blamed on the user`() {
        // The record is written before the rule on purpose, so a crash in that
        // window leaves a live record over an off rule — the same shape as the
        // user having switched Do Not Disturb off (Codex, PR #36). Reading it
        // that way would silently delete a snooze they asked for and never got.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
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
