package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertFalse
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
 * Both cases run with **no notification-policy access**, which is what makes
 * them possible without a platform that can hold a zen rule: the rule write
 * fails either way, and each assertion is about what the ringer did regardless.
 * That is not a workaround — it is the pair of invariants that matter most.
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

    private fun controller(ringer: RingerController) = AndroidZenController(
        context = context,
        store = NoRuleStore,
        configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
        ringer = ringer,
    )

    private fun controller(ringer: RingerController, store: ZenRuleIdStore) = AndroidZenController(
        context = context,
        store = store,
        configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
        ringer = ringer,
    )

    @Before
    fun clearTheLog() {
        SnoozeDebugLog.resetForTest()
    }

    @Test
    fun `a refused arm takes the ringer from nobody`() {
        val ringer = RecordingRinger()

        val outcome = controller(ringer).setSnoozed(true, ZenTrigger.USER_ACTION, "Home")

        // Nothing is silencing the phone, so quieting it would leave a ringer
        // taken for a snooze that never started — and a loan owed against no
        // record that could ever hand it back.
        assertTrue(outcome is ZenOutcome.NotApplied)
        assertFalse(ringer.quieted)
        // And it hands back whatever an earlier one took. This is the *arm* a
        // restore makes, so a loan can already be outstanding from before the
        // process died — and `SnoozeController` treats this reason as
        // `LOST_CAPABILITY` and finalizes the snooze without a second zen call,
        // so nothing else would ever reach the release branch (Codex, PR #176).
        assertTrue(ringer.handedBack)
        assertTrue(ringer.forgotten)
    }

    @Test
    fun `an arm whose rule is not yet in effect leaves the ringer alone`() {
        val ringer = RecordingRinger()
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        val outcome = controller(ringer, RememberingStore()).setSnoozed(
            true,
            ZenTrigger.USER_ACTION,
            "Home",
            SnoozeIdentity(1_000L),
        )

        // The platform accepted the rule write — and still reports the rule as
        // not in effect, which is the state a device capture on 2026-09-10
        // caught the `ACTIVATED` broadcast arriving in. Every arm in that
        // capture that lowered the ringer here lost its rule milliseconds
        // later; every arm that wrote nothing survived. So an accepted write is
        // not the signal, and the ceiling waits for the read-back.
        assertTrue(outcome.toString(), outcome is ZenOutcome.Applied)
        assertFalse(ringer.quieted)
        // Said rather than silent: a ceiling that has not been applied is the
        // state a later reader has to be able to tell from one that has
        // (AGENTS.md, principle 2).
        val deferral = SnoozeDebugLog.snapshot().first { it.contains("ceiling deferred") }
        assertTrue(deferral, deferral.contains("ceiling deferred until the rule is in effect"))
        assertTrue(deferral, deferral.contains("rule=INACTIVE"))
        // Only the *mode change* waits. The choice is captured now, under this
        // snooze's own identity, or a re-assertion after a setting change would
        // run the snooze at a ceiling chosen for the next one (Codex, PR #250).
        assertEquals(listOf<SnoozeIdentity?>(SnoozeIdentity(1_000L)), ringer.capturedFor)
    }

    @Test
    fun `the deferred ceiling lands when the rule is observed in effect`() {
        val ringer = RecordingRinger()
        val snooze = SnoozeIdentity(1_000L)

        controller(ringer).applyRingerCeiling(snooze)

        // What the `ACTIVATED` broadcast calls once the read-back agrees the
        // rule really is on. Under the snooze's own identity, so it reuses that
        // snooze's ceiling record rather than reading the setting afresh
        // (SPEC.md §5.9 rule 2).
        assertTrue(ringer.quieted)
        assertEquals(listOf<SnoozeIdentity?>(snooze), ringer.quietedFor)
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
    ) : RingerController {
        var quieted = false
            private set
        var handedBack = false
            private set
        var forgotten = false
            private set
        val quietedFor = mutableListOf<SnoozeIdentity?>()

        /** Every ceiling captured without a mode change, in order. */
        val capturedFor = mutableListOf<SnoozeIdentity?>()

        override fun captureCeiling(snooze: SnoozeIdentity?): RingerOutcome {
            capturedFor += snooze
            return RingerOutcome.Untouched
        }

        override fun finishCeiling(snooze: SnoozeIdentity?): RingerOutcome {
            quieted = true
            quietedFor += snooze
            return RingerOutcome.Untouched
        }

        override fun forgetCeiling() {
            forgotten = true
        }

        override fun quiet(snooze: SnoozeIdentity?): RingerOutcome {
            quieted = true
            quietedFor += snooze
            return RingerOutcome.Untouched
        }

        override fun giveBack(): RingerOutcome {
            handedBack = true
            return handBack
        }
    }

    private object ThrowingRinger : RingerController {
        override fun quiet(snooze: SnoozeIdentity?): RingerOutcome = error("the ringer is unreachable")
        override fun captureCeiling(snooze: SnoozeIdentity?): RingerOutcome = error("the ringer is unreachable")
        override fun finishCeiling(snooze: SnoozeIdentity?): RingerOutcome = error("the ringer is unreachable")
        override fun giveBack(): RingerOutcome = error("the ringer is unreachable")
        override fun forgetCeiling(): Unit = error("the ringer is unreachable")
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

    /** A store with no rule, so the rule write fails for a stated reason. */
    private object NoRuleStore : ZenRuleIdStore {
        override fun ruleId(): String? = null
        override fun setRuleId(id: String): Boolean = false
        override fun clear(): Boolean = true
    }
}
