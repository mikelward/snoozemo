package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.RingerMode
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.SnoozeIdentity
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenTrigger
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.dnd.RingerController
import app.snoozemo.dnd.RingerOutcome
import app.snoozemo.dnd.ZenRuleIdStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * That the ringer is driven from the zen controller at all, and in the right
 * order (SPEC.md §5.9).
 *
 * The wiring is the point of this test rather than the ceiling itself, which
 * `RingerHandoverTest` and `AudioRingerControllerTest` already cover. It sits
 * here because `setSnoozed` is the single call every arm and release in the app
 * passes through — the service, the cap alarm, the backstop, the restore path —
 * so a ringer that stopped being driven from it would go unnoticed by every
 * other test in the suite while the ceiling silently stopped applying.
 *
 * The refusal cases run with **no notification-policy access**, which is what
 * makes them possible without a platform that can hold a zen rule: the rule
 * write fails, and the assertion is about what the ringer did regardless.
 *
 * **Order is now part of what this pins**, not just wiring: the ceiling is
 * written before the rule, because the platform turns Do Not Disturb off in
 * response to our own `setRingerMode` (see the ordering test below).
 */
@RunWith(RobolectricTestRunner::class)
// A plain `Application`, not `SnoozemoApplication`: its `onCreate` starts
// `reconcileRingerInBackground` on a daemon thread, and Robolectric builds the
// application for every test — so that thread races the test body for the
// process-wide ringer lock. Reaching it while no snooze record is on disk, it
// does exactly its job: drops the ceiling as stale and hands the loan back,
// under a fixture that put both there by hand. The tests below reach that path
// deliberately where they mean to; a stray copy of it running under every
// statement is what made them fail about one run in ten.
@Config(sdk = [36], application = Application::class)
class ZenRingerWiringTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearTheLog() {
        SnoozeDebugLog.resetForTest()
        // Process-wide, so a test that turns it off would silence every test
        // after it.
        SnoozeDebugLog.setRecording(true)
    }

    private fun controller(ringer: RingerController) = AndroidZenController(
        context = context,
        store = NoRuleStore,
        configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
        ringer = ringer,
    )

    @Test
    fun `a refused arm hands back the ringer it took a moment earlier`() {
        val ringer = RecordingRinger()

        val outcome = controller(ringer).setSnoozed(true, ZenTrigger.USER_ACTION, "Home")

        // The ceiling now goes on *before* the rule write, so a refusal arrives
        // with the ringer already taken — where the old order never got that
        // far. What the user observes is unchanged either way: a snooze that is
        // not being enforced leaves the ringer where they had it.
        assertTrue(outcome is ZenOutcome.NotApplied)
        assertTrue(ringer.quieted)
        assertTrue(ringer.handedBack)
        // And this also hands back whatever an *earlier* arm took. This is the
        // arm a restore makes, so a loan can already be outstanding from before
        // the process died — and `SnoozeController` treats this reason as
        // `LOST_CAPABILITY` and finalizes the snooze without a second zen call,
        // so nothing else would ever reach the release branch (Codex, PR #176).
        assertTrue(ringer.forgotten)
    }

    @Test
    fun `an accepted arm takes the ringer, under the snooze's own identity`() {
        val ringer = RecordingRinger()
        val snooze = SnoozeIdentity(1_000L)
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        val outcome = AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", snooze)

        assertTrue(outcome.toString(), outcome is ZenOutcome.Applied)
        assertTrue(ringer.quieted)
        assertEquals(listOf<SnoozeIdentity?>(snooze), ringer.quietedFor)
    }

    /**
     * The regression test for the bug this order exists to fix (device capture,
     * API 37): `AudioManager.setRingerMode` turns Do Not Disturb **off** for an
     * app holding Notification Policy Access, so a ceiling written while our own
     * rule was active deactivated it within milliseconds and ended the snooze
     * that had just started — silently, since `DND_TURNED_OFF` posts nothing.
     *
     * Asserted through the **store** rather than the rule's state, because that
     * is the seam Robolectric answers honestly: the platform's zen state under a
     * shadow says nothing about what a real one does with the coupling, whereas
     * "had the rule been written by the time the ringer moved?" is ordering, and
     * ordering is exactly the invariant. The empty store is load-bearing — a
     * warm id would be non-null under either order and the test would pass
     * vacuously — so it starts empty and the arm mints one.
     */
    @Test
    fun `the ceiling is taken before the rule is written`() {
        val store = RememberingStore()
        var ruleWhenQuieted: String? = "not quieted at all"
        val ringer = RecordingRinger(onQuiet = { ruleWhenQuieted = store.ruleId() })
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        val outcome = AndroidZenController(
            context = context,
            store = store,
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        assertTrue(outcome.toString(), outcome is ZenOutcome.Applied)
        assertTrue(ringer.quieted)
        // Null means the ringer moved before any rule existed to be turned off.
        // Under the old order this held the freshly-minted id.
        assertNull("the ringer was taken after the rule write", ruleWhenQuieted)
        // And the arm really did mint one, so the null above is ordering rather
        // than a rule write that never happened.
        assertNotNull(store.ruleId())
    }

    /** A store that keeps what it is given, so an arm can actually succeed. */
    private class RememberingStore : ZenRuleIdStore {
        private var id: String? = null
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

    @Test
    fun `a release hands the ringer back even when the rule write fails`() {
        val ringer = RecordingRinger()

        val outcome = controller(ringer).setSnoozed(false, ZenTrigger.CONTEXT, "Home")

        // The ordering that matters: the hand-back runs *before* the rule write
        // and does not depend on it. A release that only gave the ringer back on
        // success would leave a phone quiet for exactly as long as the failure
        // lasted, with the loan the only thing that knew — which is principle
        // 1's failure, and the reason this is not conditional.
        assertTrue(outcome is ZenOutcome.NotApplied)
        assertTrue(ringer.handedBack)
        // And the ceiling *is* forgotten, because this particular failure — no
        // rule at all — is `nothingLeftToRelease`: the rest of the app
        // finalizes the snooze on it, so holding its ceiling would leave a
        // stale one for the next snooze to inherit (Codex, PR #176).
        assertTrue(ringer.forgotten)
    }

    @Test
    fun `a refused release re-quiets under the snooze's own identity`() {
        val ringer = RecordingRinger()
        val snooze = SnoozeIdentity(1_000L)
        // Access granted, unlike the cases above: with no rule id the release
        // goes to diagnosis, which creates a rule whose id the store then
        // refuses — `PLATFORM_REFUSED`, the one refusal that keeps the snooze
        // running, so the ringer handed back before the write goes down again.
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        val outcome = controller(ringer).setSnoozed(false, ZenTrigger.CONTEXT, "Home", snooze)

        assertEquals(ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED), outcome)
        assertTrue(ringer.handedBack)
        // Under the same identity the arm used, so the re-quiet reuses this
        // snooze's own ceiling record rather than treating it as another
        // snooze's and reading the setting afresh (SPEC.md §5.9 rule 2).
        assertEquals(listOf<SnoozeIdentity?>(snooze), ringer.quietedFor)
    }

    @Test
    fun `a ringer that throws does not take the snooze with it`() {
        val outcome = controller(ThrowingRinger).setSnoozed(false, ZenTrigger.CONTEXT, "Home")

        // Contained on purpose: an exception escaping here would unwind the
        // release and then `onStartCommand`, costing the very release the
        // wake-up existed to perform, over a phone that is merely louder than
        // asked.
        assertEquals(true, outcome is ZenOutcome.NotApplied)
    }

    private class RecordingRinger(
        private val handBack: RingerOutcome = RingerOutcome.Untouched,
        private val quietOutcome: RingerOutcome = RingerOutcome.Untouched,
        /**
         * Runs inside `quiet`, so a test can read the world as the ceiling is
         * being applied rather than after the whole arm has finished. That is
         * what makes the ordering assertable at all: afterwards, both orders
         * look identical.
         */
        private val onQuiet: () -> Unit = {},
    ) : RingerController {
        var quieted = false
            private set
        var handedBack = false
            private set
        var forgotten = false
            private set
        val quietedFor = mutableListOf<SnoozeIdentity?>()

        override fun forgetCeiling() {
            forgotten = true
        }

        override fun quiet(snooze: SnoozeIdentity?): RingerOutcome {
            quieted = true
            quietedFor += snooze
            onQuiet()
            return quietOutcome
        }

        override fun giveBack(): RingerOutcome {
            handedBack = true
            return handBack
        }
    }

    private object ThrowingRinger : RingerController {
        override fun quiet(snooze: SnoozeIdentity?): RingerOutcome = error("the ringer is unreachable")
        override fun giveBack(): RingerOutcome = error("the ringer is unreachable")
        override fun forgetCeiling(): Unit = error("the ringer is unreachable")
    }

    /** A store with no rule, so the rule write fails for a stated reason. */
    private object NoRuleStore : ZenRuleIdStore {
        override fun ruleId(): String? = null
        override fun setRuleId(id: String): Boolean = false
        override fun clear(): Boolean = true
    }
}
