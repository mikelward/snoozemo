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
import app.snoozemo.dnd.StuckRuleStore
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
        stuckRule = InMemoryStuckStore(),
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
            stuckRule = InMemoryStuckStore(),
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
            stuckRule = InMemoryStuckStore(),
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

    /**
     * The one case ordering cannot reach (Codex, PR #259).
     *
     * A re-assertion — a cap re-arm, or a restore after process death — runs
     * with our rule *already active*, and `RingerHandover.quiet` writes the
     * ringer on one of those: the `unfinished` branch, finishing a loan whose
     * own write never landed. That write deactivates the rule, and `STATE_TRUE`
     * cannot revive it, so the rule is turned off first to un-stick it.
     *
     * Driven through the ringer's reported outcome rather than by staging a
     * real half-written loan, because the flag is the contract between the two:
     * `AudioRingerController` decides what finishing means, and this asserts
     * what the controller does when told. `AudioRingerControllerTest` owns the
     * other half.
     */
    @Test
    fun `a ceiling write that finished an earlier loan turns the rule off before on`() {
        val ringer = RecordingRinger(
            quietOutcome = RingerOutcome.Set(RingerMode.VIBRATE, finishedAnEarlierLoan = true),
        )
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        val outcome = AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
            stuckRule = InMemoryStuckStore(),
        ).setSnoozed(true, ZenTrigger.CONTEXT, "Home", SnoozeIdentity(1_000L))

        assertTrue(outcome.toString(), outcome is ZenOutcome.Applied)
        assertTrue(
            SnoozeDebugLog.snapshot().toString(),
            SnoozeDebugLog.snapshot().any { it.contains("turning it off so it can go on again") },
        )
    }

    /**
     * The un-stick's own failure, through the adapter: an arm that turned the
     * rule off and could not get it back on must not leave a snooze reporting
     * itself over an audible phone.
     *
     * Asserted as the *property* rather than one code, because which refusal
     * the platform produces is the platform's business and `:core` owns the
     * mapping (`RingerHandoverTest`, the `unstuckArmOutcome` cases). What this
     * pins is that the adapter reaches that decision at all, and that the arm
     * ends rather than staying armed for a retry.
     */
    @Test
    fun `a rule turned off that will not go back on says so, not that it may retry`() {
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)
        val ringer = RecordingRinger(
            quietOutcome = RingerOutcome.Set(RingerMode.VIBRATE, finishedAnEarlierLoan = true),
        )

        val outcome = AndroidZenController(
            context = context,
            // A rule this store names but the platform does not have, so the
            // re-arm cannot take however the write is answered.
            store = FixedStore("not-a-real-rule"),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
            stuckRule = InMemoryStuckStore(),
        ).setSnoozed(true, ZenTrigger.CONTEXT, "Home", SnoozeIdentity(1_000L))

        assertTrue(outcome.toString(), outcome is ZenOutcome.NotApplied)
        assertTrue(
            outcome.toString(),
            (outcome as ZenOutcome.NotApplied).reason.nothingLeftToRelease,
        )
        // And the ringer this arm took goes back, so a snooze that is not being
        // enforced leaves the phone where the user had it.
        assertTrue(ringer.handedBack)
    }

    /**
     * And the half that makes the requirement survive a retry (PR #260): an arm
     * that finishes no loan still un-sticks the rule when a previous one wrote
     * the requirement down — which is the only way the retry an unconfirmed arm
     * asks for can know, since the finishing write marks the loan applied and
     * the next `quiet` reports nothing.
     */
    @Test
    fun `a recorded requirement makes the next arm un-stick the rule, then clears`() {
        val ringer = RecordingRinger(quietOutcome = RingerOutcome.Set(RingerMode.VIBRATE))
        val stuckRule = InMemoryStuckStore(stuck = true)
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        val outcome = AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
            stuckRule = stuckRule,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        assertTrue(outcome.toString(), outcome is ZenOutcome.Applied)
        assertTrue(
            SnoozeDebugLog.snapshot().toString(),
            SnoozeDebugLog.snapshot().any { it.contains("turning it off so it can go on again") },
        )
        // And an arm that got the rule back on drops the requirement, so the
        // flag costs exactly one extra cycle rather than every arm from here.
        assertEquals(false, stuckRule.stuck())
    }

    /**
     * And the ambiguous read goes the same way (Codex, PR #260): an unreadable
     * record says nothing, and answering "not stuck" would skip the cycle on
     * exactly the retry the record exists for.
     */
    @Test
    fun `an unreadable requirement is read as needing the cycle`() {
        val ringer = RecordingRinger(quietOutcome = RingerOutcome.Set(RingerMode.VIBRATE))
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
            stuckRule = ThrowingStuckStore,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        assertTrue(
            SnoozeDebugLog.snapshot().toString(),
            SnoozeDebugLog.snapshot().any { it.contains("turning it off so it can go on again") },
        )
        // And the throw is reported rather than swallowed into the guess.
        assertTrue(
            SnoozeDebugLog.snapshot().toString(),
            SnoozeDebugLog.snapshot().any { it.contains("needs un-sticking threw") },
        )
    }

    /**
     * And a read that threw is not a durable record (Codex, PR #260): the arm
     * whose signal is new must still write it, since the guess that kept the
     * cycle running lives only in this process. A read can fail transiently
     * where the commit after it lands.
     */
    @Test
    fun `an unreadable record does not pass for one already on disk`() {
        val ringer = RecordingRinger(
            quietOutcome = RingerOutcome.Set(RingerMode.VIBRATE, finishedAnEarlierLoan = true),
        )
        val stuckRule = UnreadableStuckStore()
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
            stuckRule = stuckRule,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        assertTrue(
            SnoozeDebugLog.snapshot().toString(),
            SnoozeDebugLog.snapshot().any { it.contains("turning it off so it can go on again") },
        )
        // The requirement was written down rather than assumed already there.
        assertTrue(stuckRule.written.toString(), stuckRule.written.contains(true))
    }

    /** Unreadable, but writable — the pair that tells the two apart. */
    private class UnreadableStuckStore : StuckRuleStore {
        val written = mutableListOf<Boolean>()
        override fun stuck(): Boolean = error("the record is unreadable")
        override fun setStuck(stuck: Boolean): Boolean {
            written += stuck
            return true
        }
    }

    /** A record no one can read, which is the ambiguity the arm has to resolve. */
    private object ThrowingStuckStore : StuckRuleStore {
        override fun stuck(): Boolean = error("the record is unreadable")
        override fun setStuck(stuck: Boolean): Boolean = error("the record is unwritable")
    }

    /**
     * The un-stick requirement, in memory: the real one is a preferences file
     * shared process-wide, which would carry a flag one test set into the next.
     */
    private class InMemoryStuckStore(private var stuck: Boolean = false) : StuckRuleStore {
        override fun stuck(): Boolean = stuck
        override fun setStuck(stuck: Boolean): Boolean {
            this.stuck = stuck
            return true
        }
    }

    /** A store pinned to one id, so every write goes to the same rule. */
    private class FixedStore(private val id: String) : ZenRuleIdStore {
        override fun ruleId(): String = id
        override fun setRuleId(id: String) = true
        override fun clear() = true
    }

    /**
     * And the negative, which is what keeps the un-stick off the common path: a
     * fresh arm takes the ringer without finishing anything, has no rule of its
     * own to lose, and must not turn Do Not Disturb off on its way in.
     */
    @Test
    fun `a fresh arm does not turn the rule off first`() {
        val ringer = RecordingRinger(quietOutcome = RingerOutcome.Set(RingerMode.VIBRATE))
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
            stuckRule = InMemoryStuckStore(),
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        assertTrue(
            SnoozeDebugLog.snapshot().toString(),
            SnoozeDebugLog.snapshot().none { it.contains("turning it off so it can go on again") },
        )
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
