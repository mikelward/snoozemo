package app.snoozemo.snooze

import android.app.Application
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.net.Uri
import android.service.notification.Condition
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.SnoozeIdentity
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleState
import app.snoozemo.core.ZenTrigger
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.dnd.R as DndR
import app.snoozemo.dnd.RingerController
import app.snoozemo.dnd.RingerOutcome
import app.snoozemo.dnd.StuckRuleStore
import app.snoozemo.dnd.ZenRule
import app.snoozemo.dnd.ZenRuleIdStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The rule's icon in Settings and the Modes UI (SPEC.md §5.3): the `Zzz` mark
 * rather than the platform's default for `TYPE_OTHER`.
 *
 * Most of this is about rules already on a phone, made before the rule carried
 * an icon. Giving one its icon is an `updateAutomaticZenRule`, and the platform
 * drops the condition of a rule updated that way — so on a running snooze it
 * would switch Do Not Disturb off under it. Robolectric's `NotificationManager`
 * does not model that drop, so these tests pin the guards instead: no update
 * while the rule is on, and an arm that overlaps an update — which it never
 * waits for — puts its own state back afterwards.
 */
@RunWith(RobolectricTestRunner::class)
// A plain `Application`, for the reason `ZenRingerWiringTest` gives: the real
// one starts a ringer reconcile on a daemon thread under every test.
@Config(sdk = [36], application = Application::class)
class ZenRuleIconTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private val notifications: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val mark: Int get() = DndR.drawable.ic_snooze_mark

    @Before
    fun setUp() {
        // Process-wide, so one test's attempt would otherwise stop the next.
        AndroidZenController.resetIconForTest()
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        // A zero here would make every "has the mark" assertion below pass
        // against a rule with no icon at all.
        assertNotEquals(0, mark)
    }

    @Test
    fun `a new rule carries the mark`() {
        val store = MemoryStore()

        assertEquals(ZenRuleState.READY, controller(store).ensureRule())

        assertEquals(mark, notifications.getAutomaticZenRule(requireNotNull(store.ruleId())).iconResId)
    }

    @Test
    fun `an older rule is given the mark while it is off`() {
        val id = olderRule()
        setState(id, Condition.STATE_FALSE)

        assertEquals(ZenRuleState.READY, controller(MemoryStore(id)).ensureRule())

        assertEquals(mark, notifications.getAutomaticZenRule(id).iconResId)
        // Updated in place, never replaced: the user's customizations live on
        // that rule, and a second one would litter the Modes list.
        assertEquals(setOf(id), notifications.automaticZenRules.keys)
    }

    @Test
    fun `a running snooze's rule waits for the snooze to end`() {
        val id = olderRule()
        setState(id, Condition.STATE_TRUE)
        val zen = controller(MemoryStore(id))

        assertEquals(ZenRuleState.READY, zen.ensureRule())

        assertEquals("no update while the rule is on", 0, notifications.getAutomaticZenRule(id).iconResId)
        assertEquals(Condition.STATE_TRUE, notifications.getAutomaticZenRuleState(id))

        // Skipping is not giving up: once the rule is off, the next preparation
        // does it.
        setState(id, Condition.STATE_FALSE)
        zen.ensureRule()

        assertEquals(mark, notifications.getAutomaticZenRule(id).iconResId)
    }

    @Test
    fun `a rule whose state cannot be read is left for later`() {
        // Robolectric reports a rule that has never had a state as
        // `STATE_UNKNOWN`, which stands in for a platform that will not say.
        val id = olderRule()

        controller(MemoryStore(id)).ensureRule()

        assertEquals(0, notifications.getAutomaticZenRule(id).iconResId)
    }

    @Test
    fun `an icon the user chose is kept`() {
        val chosen = android.R.drawable.star_on
        val id = olderRule(icon = chosen)
        setState(id, Condition.STATE_FALSE)

        controller(MemoryStore(id)).ensureRule()

        assertEquals(chosen, notifications.getAutomaticZenRule(id).iconResId)
    }

    @Test
    fun `a switched-off rule is given the mark and still reported as switched off`() {
        val id = olderRule(enabled = false)
        setState(id, Condition.STATE_FALSE)

        assertEquals(ZenRuleState.DISABLED, controller(MemoryStore(id)).ensureRule())

        assertEquals(mark, notifications.getAutomaticZenRule(id).iconResId)
        assertEquals(false, notifications.getAutomaticZenRule(id).isEnabled)
    }

    @Test
    fun `an arm does not wait for an icon update in flight`() {
        val id = olderRule()
        setState(id, Condition.STATE_FALSE)
        val zen = controller(MemoryStore(id))
        var outcome: ZenOutcome? = null
        val arm = Thread {
            outcome = zen.setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))
        }

        AndroidZenController.iconUpdateForTest {
            arm.start()
            // `STATE_TRUE` lands while the update still holds its place. Waiting
            // for it first would put two binder calls between the tap and the
            // rule going on, which the arm path forbids.
            awaitUntil("the arm's write, while the update is still running") {
                notifications.getAutomaticZenRuleState(id) == Condition.STATE_TRUE
            }
        }
        arm.join(JOIN_MILLIS)

        assertTrue("$outcome", outcome is ZenOutcome.Applied)
    }

    @Test
    fun `an arm whose state an icon update dropped puts it back`() {
        val id = olderRule()
        setState(id, Condition.STATE_FALSE)
        val zen = controller(MemoryStore(id))
        var outcome: ZenOutcome? = null
        val arm = Thread {
            outcome = zen.setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))
        }

        AndroidZenController.iconUpdateForTest {
            arm.start()
            awaitUntil("the arm to write and then wait for the update") {
                notifications.getAutomaticZenRuleState(id) == Condition.STATE_TRUE &&
                    arm.state == Thread.State.BLOCKED
            }
            // What the platform does to the rule when the update lands after
            // the arm's write. Robolectric's update does not, so the test does.
            setState(id, Condition.STATE_FALSE)
        }
        arm.join(JOIN_MILLIS)

        assertEquals(Condition.STATE_TRUE, notifications.getAutomaticZenRuleState(id))
        assertTrue("$outcome", outcome is ZenOutcome.Applied)
    }

    @Test
    fun `a release that lands after the arm is not undone`() {
        val id = olderRule()
        setState(id, Condition.STATE_FALSE)
        val zen = controller(MemoryStore(id))
        val arm = Thread {
            zen.setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))
        }

        AndroidZenController.iconUpdateForTest {
            arm.start()
            awaitUntil("the arm to write and then wait for the update") {
                notifications.getAutomaticZenRuleState(id) == Condition.STATE_TRUE &&
                    arm.state == Thread.State.BLOCKED
            }
            // The newer intent. Re-asserting the arm over it would leave the
            // phone quiet with nothing running — principle 1's failure.
            assertTrue(zen.setSnoozed(false, ZenTrigger.USER_ACTION, "Home") is ZenOutcome.Applied)
        }
        arm.join(JOIN_MILLIS)

        assertEquals(Condition.STATE_FALSE, notifications.getAutomaticZenRuleState(id))
    }

    /**
     * Spins until [condition] holds. The deadline only turns a hang into a
     * failure that names what never happened; nothing here waits a fixed time.
     */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + JOIN_MILLIS * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "never saw $what" }
            Thread.onSpinWait()
        }
    }

    private fun controller(store: ZenRuleIdStore) = AndroidZenController(
        context = context,
        store = store,
        configurationActivity = ComponentName(context.packageName, AndroidZenController.CONFIGURATION_ACTIVITY_CLASS),
        ringer = UntouchedRinger,
        stuckRule = NotStuck,
    )

    /** The rule as a build before the icon made it: everything else the same. */
    private fun olderRule(enabled: Boolean = true, icon: Int = 0): String {
        val builder = AutomaticZenRule.Builder(ZenRule.NAME, Uri.parse(ZenRule.CONDITION_ID))
            .setType(AutomaticZenRule.TYPE_OTHER)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setConfigurationActivity(
                ComponentName(context.packageName, AndroidZenController.CONFIGURATION_ACTIVITY_CLASS),
            )
            .setManualInvocationAllowed(true)
            .setEnabled(enabled)
        if (icon != 0) builder.setIconResId(icon)
        return notifications.addAutomaticZenRule(builder.build())
    }

    private fun setState(id: String, state: Int) =
        notifications.setAutomaticZenRuleState(id, Condition(Uri.parse(ZenRule.CONDITION_ID), "", state))

    private class MemoryStore(private var id: String? = null) : ZenRuleIdStore {
        override fun ruleId(): String? = id
        override fun setRuleId(id: String): Boolean {
            this.id = id
            return true
        }
        override fun clear(): Boolean {
            id = null
            return true
        }
    }

    private object UntouchedRinger : RingerController {
        override fun quiet(snooze: SnoozeIdentity?): RingerOutcome = RingerOutcome.Untouched
        override fun giveBack(): RingerOutcome = RingerOutcome.Untouched
        override fun forgetCeiling() = Unit
    }

    private object NotStuck : StuckRuleStore {
        override fun stuck(): Boolean = false
        override fun setStuck(stuck: Boolean): Boolean = true
    }

    private companion object {
        const val JOIN_MILLIS = 10_000L
    }
}
