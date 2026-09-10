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
import app.snoozemo.dnd.RingerWriteCounts
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
    fun `an accepted arm still takes the ringer, and records the rule either side`() {
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

        // **Unchanged**, which is the point of this pass: the reads either side
        // of the ceiling are a measurement, and an arm that stopped taking the
        // ringer because of one would be a behavior change smuggled in as
        // instrumentation.
        assertTrue(outcome.toString(), outcome is ZenOutcome.Applied)
        assertTrue(ringer.quieted)
        assertEquals(listOf<SnoozeIdentity?>(snooze), ringer.quietedFor)

        // Both fields, because it is the *pair* that discriminates: the same
        // answer twice clears the write, and `ACTIVE` then `INACTIVE` convicts
        // it. One field alone would say only what a single arm looked like.
        val line = SnoozeDebugLog.snapshot().last { it.contains("rule around the ceiling write") }
        assertTrue(line, line.contains("before="))
        assertTrue(line, line.contains("after="))
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
    fun `with the log off the arm takes the ringer and probes nothing`() {
        // Counted through `modeWrites`, which nothing but the instrumentation
        // reads — so a non-zero count *is* the measurement having run, and zero
        // is the gate holding. The probes used to be counted through the store,
        // which stopped working once they were given the arm's own rule id and
        // no longer resolve one (Codex, PR #251).
        fun armCountingProbes(recording: Boolean): Int {
            SnoozeDebugLog.resetForTest()
            SnoozeDebugLog.setRecording(recording)
            val ringer = RecordingRinger()
            val outcome = AndroidZenController(
                context = context,
                store = RememberingStore(),
                configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
                ringer = ringer,
            ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

            // The arm is untouched either way — the reads are the experiment,
            // not the product.
            assertTrue(outcome.toString(), outcome is ZenOutcome.Applied)
            assertTrue(ringer.quieted)
            return ringer.counterReads
        }

        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        val whileRecording = armCountingProbes(recording = true)
        val whileOff = armCountingProbes(recording = false)

        // Nothing at all with the log off, which is the whole of the gate: the
        // first probe delays the ringer, and charging that to a user who can
        // capture nothing is a behavior change bought for no evidence (Codex,
        // PR #251).
        assertEquals("the measurement ran with the log off", 0, whileOff)
        assertTrue("the measurement did not run with the log on", whileRecording > 0)
        SnoozeDebugLog.setRecording(true)
    }

    @Test
    fun `the measurement counts no setter call when nothing was written`() {
        // The control (Codex, PR #251): a ringer that takes nothing spans the
        // same interval and the same two reads with no write in it, so a rule
        // that moves across such an arm moved on its own. Without this the pair
        // cannot tell our write from the platform's own timing, which is the
        // whole question.
        val ringer = RecordingRinger(quietOutcome = RingerOutcome.Untouched)
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        val line = SnoozeDebugLog.snapshot().last { it.contains("rule around the ceiling write") }
        assertTrue(line, line.contains("setterCalls=0"))
    }

    @Test
    fun `the measurement counts a setter call when one happened`() {
        val ringer = RecordingRinger(quietOutcome = RingerOutcome.Set(RingerMode.VIBRATE))
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        // Both halves of the discriminator on one line, so a capture is read
        // without correlating it against a neighbour.
        val line = SnoozeDebugLog.snapshot().last { it.contains("rule around the ceiling write") }
        assertTrue(line, line.contains("setterCalls=1"))
    }

    @Test
    fun `the measured interval covers the read after the write, not just the write`() {
        // `took=` is read as "how long was the window a transition could have
        // landed in unseen", and that window closes after the *second* rule
        // read. Timed around the write alone, everything after it fell outside
        // the number — so an arm whose post-write work was slow reported a
        // short interval, which `TODO.md` reads as grounds to rule timing out
        // and convict the write, the opposite repair (Codex, PR #251).
        //
        // The stall is the stimulus, not a wait: it runs on the counter sample
        // taken after the write and before the second probe, so it is inside
        // the window the new timer brackets and outside the one the old timer
        // did. The assertion is a lower bound on a monotonic clock.
        var stalled = false
        val ringer = RecordingRinger(
            quietOutcome = RingerOutcome.Set(RingerMode.VIBRATE),
            onCounterRead = { read ->
                if (read == POST_WRITE_COUNTER_READ) {
                    stalled = true
                    Thread.sleep(STALL_MILLIS)
                }
            },
        )
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        val line = SnoozeDebugLog.snapshot().last { it.contains("rule around the ceiling write") }
        assertTrue("the stall never happened, so this proves nothing: $line", stalled)
        val took = Regex("took=(\\d+)us").find(line)?.groupValues?.get(1)?.toLong()
        assertTrue("no readable duration in: $line", took != null)
        // Comfortably under the stall, so scheduling slop cannot fail it while
        // still being far above anything the rest of the window accounts for.
        assertTrue("took=${took}us, under a ${STALL_MILLIS}ms stall: $line", took!! >= STALL_MILLIS * 700)
    }

    @Test
    fun `a write in the gap at an edge is reported as a range, not a conviction`() {
        // The fifth interleaving on this counter, and the one that ended the
        // single-sample design (Codex, PR #251). A setter finishing between the
        // sample taken after the write and the one taken after the second probe
        // is counted, but it ran after the transition was observed and cannot
        // have caused it — reported as a plain `setterCalls=1`, that is a false
        // conviction, since `TODO.md` reads a moved rule with a write in the
        // window as the shape that points at us.
        //
        // Here another controller's setter lands in exactly that gap: the hook
        // runs after read 3 is taken, so the inner pair misses it and the outer
        // pair sees it.
        lateinit var ringer: RecordingRinger
        ringer = RecordingRinger(
            quietOutcome = RingerOutcome.Untouched,
            onCounterRead = { read ->
                if (read == POST_WRITE_COUNTER_READ) ringer.anotherControllerWrote()
            },
        )
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        AndroidZenController(
            context = context,
            store = RememberingStore(),
            configurationActivity = ComponentName(context.packageName, "app.snoozemo.ui.MainActivity"),
            ringer = ringer,
        ).setSnoozed(true, ZenTrigger.USER_ACTION, "Home", SnoozeIdentity(1_000L))

        val line = SnoozeDebugLog.snapshot().last { it.contains("rule around the ceiling write") }
        // The arm itself wrote nothing, so the inner count is 0 while the outer
        // one saw the stray write: neither arm of the experiment, and the line
        // says so instead of picking one.
        assertTrue("expected a range, got: $line", line.contains("setterCalls=0..1"))
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
         * Runs after each `modeWrites` read, given that read's 1-based index —
         * the seam the instrumented arm genuinely uses, now that the rule
         * probes take the arm's own id and touch no store (Codex, PR #251).
         * Read 3 is the sample taken after the write and before the second
         * probe, so a hook there lands inside the measured window.
         */
        private val onCounterRead: (Int) -> Unit = {},
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

        private var writes = 0

        var counterReads = 0
            private set

        override val modeWrites: RingerWriteCounts
            get() {
                counterReads++
                val counts = RingerWriteCounts(started = writes, finished = writes)
                // *After* the value is taken, so a hook that writes lands in the
                // gap after this sample rather than inside it.
                onCounterRead(counterReads)
                return counts
            }

        /**
         * A setter that ran somewhere other than this arm — the startup
         * reconciler, on its own controller. The counter is process-wide, so
         * the arm sees it as an extra completed write it did not make.
         */
        fun anotherControllerWrote() {
            writes++
        }

        override fun quiet(snooze: SnoozeIdentity?): RingerOutcome {
            quieted = true
            quietedFor += snooze
            // Counted like the real one: what the arm is measured on is whether
            // the setter was reached, not what the call returned. Started and
            // finished move together here, since nothing is left in flight.
            if (quietOutcome is RingerOutcome.Set) writes++
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

/** Long enough to dwarf a Robolectric binder read, short enough not to slow the suite. */
private const val STALL_MILLIS = 60L

/**
 * The `modeWrites` sample the instrumented arm takes after the ringer write and
 * before the second rule read: outer-before, inner-before, **inner-after**,
 * outer-after. Pinned here because both tests below depend on landing in that
 * exact gap, and a fourth sample added at either edge would move it.
 */
private const val POST_WRITE_COUNTER_READ = 3
