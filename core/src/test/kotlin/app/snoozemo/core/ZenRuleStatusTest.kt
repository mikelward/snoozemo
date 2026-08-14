package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reacting to Snoozemo's own zen rule changing underneath it (SPEC.md §5.8).
 */
class ZenRuleStatusTest {

    private fun resolve(status: ZenRuleStatus, ours: Boolean = true, snoozing: Boolean = true) =
        ZenRuleStatusChange.resolve(status, ours, snoozing)

    @Test
    fun `the user turning DND off ends the snooze`() {
        assertEquals(
            ZenRuleStatusAction.EndSnooze(EndReason.DND_TURNED_OFF),
            resolve(ZenRuleStatus.DEACTIVATED),
        )
    }

    @Test
    fun `another app's rule is never ours to react to`() {
        // SPEC.md §5.6 both ways: Snoozemo turns off only its own rule, and
        // ends a snooze only for its own rule. A user schedule or another
        // app's mode deactivating says nothing about ours.
        for (status in ZenRuleStatus.entries) {
            assertEquals(
                "$status on someone else's rule",
                ZenRuleStatusAction.None,
                resolve(status, ours = false),
            )
        }
    }

    @Test
    fun `a deleted rule ends a running snooze and is recreated when idle`() {
        // Deleted means further state changes are ignored, so a running snooze
        // has nothing enforcing it — fail open (D7).
        assertEquals(
            ZenRuleStatusAction.EndSnooze(EndReason.LOST_CAPABILITY),
            resolve(ZenRuleStatus.REMOVED),
        )
        assertEquals(
            ZenRuleStatusAction.RecreateRule,
            resolve(ZenRuleStatus.REMOVED, snoozing = false),
        )
    }

    @Test
    fun `a disabled rule ends a running snooze but is not re-enabled behind the user`() {
        assertEquals(
            ZenRuleStatusAction.EndSnooze(EndReason.LOST_CAPABILITY),
            resolve(ZenRuleStatus.DISABLED),
        )
        assertEquals(
            "the user disabled it deliberately",
            ZenRuleStatusAction.None,
            resolve(ZenRuleStatus.DISABLED, snoozing = false),
        )
    }

    @Test
    fun `our rule being turned on by someone else is not a snooze`() {
        // Adopting a snooze the user never asked us for would be worse than the
        // tile reading "not snoozing" beside a quiet phone.
        assertEquals(ZenRuleStatusAction.None, resolve(ZenRuleStatus.ACTIVATED, snoozing = false))
    }

    @Test
    fun `a rule that cannot be read never ends a snooze`() {
        // The whole reason activation is four values (Codex, PR #36). A failed
        // binder call reading as "the user turned it off" would end a snooze —
        // turning the phone back on in the middle of the meeting it was
        // silencing, from a *diagnostic* that failed.
        assertNull(ZenRuleActivation.UNKNOWN.endReason())
        assertNull(ZenRuleActivation.ACTIVE.endReason())
    }

    @Test
    fun `a deleted rule is a lost capability, not something the user chose`() {
        // These two look alike and are not: one is the user reaching a switch,
        // the other is a rule deleted out from under us. The first says nothing
        // (they know), the second has to explain itself (§4.5) — so collapsing
        // them would hide a capability failure behind a silent ending.
        assertEquals(EndReason.DND_TURNED_OFF, ZenRuleActivation.INACTIVE.endReason())
        assertEquals(EndReason.LOST_CAPABILITY, ZenRuleActivation.MISSING.endReason())
    }

    @Test
    fun `a disabled rule reads as lost capability, matching the broadcast`() {
        // A disabled rule reports STATE_FALSE like a deactivated one, so the
        // condition alone cannot separate them (Codex, PR #36). The two paths
        // describe the same platform state — one promptly, one late — so they
        // have to reach the same reason, or the explanation the user gets
        // depends on whether our process happened to be alive.
        assertEquals(EndReason.LOST_CAPABILITY, ZenRuleActivation.DISABLED.endReason())
        assertEquals(
            ZenRuleStatusAction.EndSnooze(EndReason.LOST_CAPABILITY),
            ZenRuleStatusChange.resolve(ZenRuleStatus.DISABLED, ours = true, snoozing = true),
        )
    }

    @Test
    fun `an unrecognized status changes nothing`() {
        assertEquals(ZenRuleStatusAction.None, resolve(ZenRuleStatus.UNKNOWN))
    }

    @Test
    fun `a status arriving with no snooze running never ends one`() {
        for (status in ZenRuleStatus.entries) {
            val action = resolve(status, snoozing = false)
            assertEquals(
                "$status must not end a snooze that isn't running",
                false,
                action is ZenRuleStatusAction.EndSnooze,
            )
        }
    }

    @Test
    fun `a stale deactivation cannot end the snooze that came after it`() {
        // The rule outlives every snooze it enforces, so a delayed broadcast
        // names an id that still passes the ownership check. Without the
        // read-back the new snooze ends on the old one's news — silently,
        // since this is the ending with no notification (Codex, PR #36).
        assertEquals(
            ZenRuleStatusAction.None,
            ZenRuleStatusChange.resolve(
                status = ZenRuleStatus.DEACTIVATED,
                ours = true,
                snoozing = true,
                stillActive = true,
            ),
        )
    }

    @Test
    fun `a rule that really is off still ends the snooze`() {
        assertEquals(
            ZenRuleStatusAction.EndSnooze(EndReason.DND_TURNED_OFF),
            ZenRuleStatusChange.resolve(
                status = ZenRuleStatus.DEACTIVATED,
                ours = true,
                snoozing = true,
                stillActive = false,
            ),
        )
    }

    @Test
    fun `an unreadable rule vetoes nothing`() {
        // The veto exists to reject stale news, not to keep a snooze alive on a
        // failed read — that would be the phone left quiet by a diagnostic.
        assertEquals(
            ZenRuleStatusAction.EndSnooze(EndReason.DND_TURNED_OFF),
            ZenRuleStatusChange.resolve(
                status = ZenRuleStatus.DEACTIVATED,
                ours = true,
                snoozing = true,
                stillActive = null,
            ),
        )
    }

    @Test
    fun `the veto cannot invent an ending`() {
        // It only ever subtracts: another app's rule stays not-ours whatever
        // the read-back says.
        assertEquals(
            ZenRuleStatusAction.None,
            ZenRuleStatusChange.resolve(
                status = ZenRuleStatus.DEACTIVATED,
                ours = false,
                snoozing = true,
                stillActive = false,
            ),
        )
    }
}
