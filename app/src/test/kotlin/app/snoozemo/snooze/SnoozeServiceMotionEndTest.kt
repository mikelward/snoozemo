package app.snoozemo.snooze

import android.os.Looper.getMainLooper
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenOutcome
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * `When I move` end to end through the service (SPEC.md §4.4).
 *
 * The sensor is the one thing no test environment can produce — not a JVM
 * test, and not an emulator either — so the registrar is a fake and a firing
 * is a method call. Everything either side of it is real: the action, the
 * controller, the record on disk, and the release.
 *
 * Asserted in **both** directions throughout, because every failure here is
 * quiet: a watch that never arms leaves a snooze running to its cap behind a
 * switch that says otherwise, and a watch that arms when it should not ends
 * someone's snooze for a reason they did not ask for.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceMotionEndTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /** The commit these intents claim to come from; see [setUp]. */
    private val REQUEST = 43L

    private var reported: EndChoiceResult? = null
    private var watch: AutoCloseable? = null

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied(OWN_RULE_ID)
        TogglableAlarmManager.refuse = false
        // The row's own channel: every exit from `setMotionEnd` has to answer
        // through it, or the rows sit inert forever behind a tap that was
        // accepted and then could not be kept (Codex, PR #255).
        reported = null
        EndChoiceOutcome.reset()
        watch = EndChoiceOutcome.watch(REQUEST) { reported = it }
    }

    @After
    fun tearDown() {
        watch?.close()
        watch = null
    }

    private fun setMotionEnd(record: ActiveSnooze?, value: Boolean) =
        startService(SnoozeService.ACTION_SET_MOTION_END, record) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, value)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

    private fun stored(): ActiveSnooze? = ActiveSnoozeStore(appContext).load()

    @Test
    fun `nothing listens until the user asks`() {
        startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now))

        assertFalse("an ordinary snooze arms no sensor", TestSnoozeService.motionRegistrar.armed)
        assertEquals(0, TestSnoozeService.motionWatchesBuilt)
    }

    @Test
    fun `asking for it arms the sensor and records the choice`() {
        setMotionEnd(snoozeFixture(now), value = true)

        assertTrue(TestSnoozeService.motionRegistrar.armed)
        assertEquals(true, stored()?.endsOnMotion)
        assertEquals("and the row is told it took", EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `a choice the record could not keep is reported refused`() {
        // The screen cannot see a refused write; as a plain choice the row
        // has no state to fall back on either, so the answer is the only way
        // the user learns the exit they asked for is not there (Codex, PR
        // #255).
        TestSnoozeService.refuseRecordUpdates = true

        setMotionEnd(snoozeFixture(now), value = true)

        assertEquals(EndChoiceResult.REFUSED, reported)
        assertFalse("and nothing was armed over a record that does not say so", TestSnoozeService.motionRegistrar.armed)
    }

    @Test
    fun `a choice rolled back for want of a sensor is reported refused`() {
        // The rollback lives in the reconcile, which runs inside the choice
        // being applied — so by the time the answer is decided, the flag the
        // write set has already been cleared again, and the row hears that
        // rather than the write.
        TestSnoozeService.motionRegistrar.available = false

        setMotionEnd(snoozeFixture(now), value = true)

        assertEquals(EndChoiceResult.REFUSED, reported)
        assertEquals("the record agrees with the answer", false, stored()?.endsOnMotion)
    }

    @Test
    fun `a choice the snooze already carries is applied by doing nothing`() {
        // `Until I leave` on a snooze already running to its ceiling gets the
        // same answer; a second tap on the row must not read as a failure.
        setMotionEnd(snoozeFixture(now).copy(endsOnMotion = true), value = true)

        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    /**
     * The state a rollback whose own write was refused leaves behind: the
     * flag on the record, nothing listening, and a promise in the log that
     * the next transition retries. Reached the way it is reachable in the
     * field, through a restore.
     */
    private fun leaveFlagOnWithNothingListening(): ActiveSnooze {
        TestSnoozeService.motionRegistrar.available = false
        TestSnoozeService.refuseRecordUpdates = true
        val record = snoozeFixture(now, capIn = Duration.ofHours(4)).copy(endsOnMotion = true)
        startService(SnoozeService.ACTION_RESTORE, record)
        assertEquals("the setup this rests on: the flag stayed on", true, stored()?.endsOnMotion)
        assertFalse("with nothing listening", TestSnoozeService.motionRegistrar.armed)
        TestSnoozeService.refuseRecordUpdates = false
        return stored()!!
    }

    @Test
    fun `a retry over a stale flag is answered from the sensor, not the flag`() {
        // Answering "already on" from the record alone reported the exit
        // applied — and cleared the failure card — over a snooze nothing could
        // end on motion (Codex, PR #255, second finding in this mechanism). A
        // second tap is the retry the log promised, so it reconciles first and
        // answers from what that leaves.
        val stale = leaveFlagOnWithNothingListening()

        setMotionEnd(stale, value = true)

        assertEquals(EndChoiceResult.REFUSED, reported)
        assertEquals("and the rollback the earlier write refused is recorded now", false, stored()?.endsOnMotion)
    }

    @Test
    fun `a retry over a stale flag arms the sensor once it is there`() {
        // The other direction, so the test above cannot pass by refusing
        // every retry: with the sensor available again the retry is what
        // finally registers it.
        val stale = leaveFlagOnWithNothingListening()
        TestSnoozeService.motionRegistrar.available = true

        setMotionEnd(stale, value = true)

        assertEquals(EndChoiceResult.APPLIED, reported)
        assertTrue("because this time something is listening", TestSnoozeService.motionRegistrar.armed)
        assertEquals(true, stored()?.endsOnMotion)
    }

    @Test
    fun `a choice for a snooze that has ended is reported gone`() {
        val running = snoozeFixture(now, capIn = Duration.ofHours(4))

        startService(SnoozeService.ACTION_SET_MOTION_END, running) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
            putExtra(
                SnoozeService.EXTRA_MOTION_FOR_SNOOZE,
                running.startedAt.minusSeconds(600).toEpochMilli(),
            )
        }

        assertEquals("gone, so the rows dismiss rather than offering a retry", EndChoiceResult.GONE, reported)
    }

    @Test
    fun `moving ends the snooze`() {
        setMotionEnd(snoozeFixture(now), value = true)

        TestSnoozeService.motionRegistrar.fire()

        assertNull("the record is erased like any other ending", stored())
        assertEquals(
            "and the rule is driven off",
            false,
            TestSnoozeService.zen.calls.lastOrNull()?.first,
        )
    }

    @Test
    fun `turning it back off stops the sensor listening`() {
        val record = snoozeFixture(now)
        setMotionEnd(record, value = true)
        assertTrue(TestSnoozeService.motionRegistrar.armed)

        setMotionEnd(stored(), value = false)

        assertFalse(TestSnoozeService.motionRegistrar.armed)
        assertEquals(false, stored()?.endsOnMotion)
    }

    @Test
    fun `a snooze that asked for it re-arms after process death`() {
        // The whole reason the flag is on the record rather than in memory:
        // a restore that came back without the watch would leave a snooze the
        // user asked to end on movement running silently to its cap.
        val record = snoozeFixture(now).copy(endsOnMotion = true)

        startService(SnoozeService.ACTION_RESTORE, record)

        assertTrue(TestSnoozeService.motionRegistrar.armed)
    }

    @Test
    fun `a duration-only snooze may take it, and keeps the process for it`() {
        // The case the row exists for: a meeting room where location can see
        // nothing. The service promotes to foreground for the motion exit
        // itself, so this is not gated on tracking (maintainer, 2026-09-10).
        val record = snoozeFixture(now).copy(
            mode = TrackingMode.DURATION_ONLY,
            anchor = snoozeFixture(now).anchor.copy(lat = null, lon = null, fixAccuracyM = null, ssid = null),
        )

        setMotionEnd(record, value = true)

        assertTrue(TestSnoozeService.motionRegistrar.armed)
        assertEquals(true, stored()?.endsOnMotion)
    }

    @Test
    fun `a restore whose sensor is refused clears the choice`() {
        // The toggle is not the only path that creates a watch — an arm and a
        // restore do too — so validating at the tap alone let a restart bring
        // a snooze back promising `or when you move` with nothing registered
        // (Codex, PR #252, third finding in this mechanism). The check lives
        // beside the registration now, so every path gets it.
        TestSnoozeService.motionRegistrar.available = false
        val record = snoozeFixture(now, capIn = Duration.ofHours(4)).copy(endsOnMotion = true)

        startService(SnoozeService.ACTION_RESTORE, record)

        assertEquals(false, stored()?.endsOnMotion)
        assertEquals(
            "and the card stops claiming it",
            stringOf(app.snoozemo.R.string.ongoing_ends_when_you_leave),
            shadeText(),
        )
    }

    @Test
    fun `a clearing that cannot be recorded leaves the record and the card agreeing`() {
        // The rollback's own write can fail too, and discarding its result
        // left the card saying the exit was off while the record still carried
        // it — a restart would then arm it again against what the user last
        // saw (Codex, PR #252, third finding about this record).
        //
        // Agreeing at `true` is the safe half: the cap still bounds the
        // snooze, so what is lost is an exit that is not listening yet, and
        // the next transition builds a fresh watch and retries.
        TestSnoozeService.motionRegistrar.available = false
        TestSnoozeService.refuseRecordUpdates = true
        val record = snoozeFixture(now, capIn = Duration.ofHours(4)).copy(endsOnMotion = true)

        startService(SnoozeService.ACTION_RESTORE, record)

        assertEquals(
            "the stored choice is what could not be moved",
            true,
            stored()?.endsOnMotion,
        )
        val plain = stringOf(app.snoozemo.R.string.ongoing_ends_when_you_leave)
        assertEquals(
            "so the card keeps saying what the record says",
            appContext.getString(app.snoozemo.R.string.ongoing_or_when_you_move, plain),
            shadeText(),
        )
    }

    @Test
    fun `a tap claiming a snooze that has ended changes nothing`() {
        // The screen can hold a tap behind a permission dialog for as long as
        // the user sits on it, and its own record is refreshed asynchronously
        // — so the snooze it was tapped for and the one running when it
        // arrives can differ. A claim that does not match is the snooze this
        // choice was for being over (Codex, PR #252).
        val running = snoozeFixture(now, capIn = Duration.ofHours(4))

        startService(SnoozeService.ACTION_SET_MOTION_END, running) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(
                SnoozeService.EXTRA_MOTION_FOR_SNOOZE,
                running.startedAt.minusSeconds(600).toEpochMilli(),
            )
        }

        assertFalse("nothing is listening", TestSnoozeService.motionRegistrar.armed)
        assertEquals("and the running snooze keeps its own exits", false, stored()?.endsOnMotion)
    }

    @Test
    fun `a tap claiming the running snooze is applied`() {
        // The other direction, so the guard above cannot pass by refusing
        // every claim.
        val running = snoozeFixture(now, capIn = Duration.ofHours(4))

        startService(SnoozeService.ACTION_SET_MOTION_END, running) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_MOTION_FOR_SNOOZE, running.startedAt.toEpochMilli())
        }

        assertTrue(TestSnoozeService.motionRegistrar.armed)
        assertEquals(true, stored()?.endsOnMotion)
    }

    @Test
    fun `a tracking change does not write back a choice the reconcile just cleared`() {
        // `onTrackingChanged` is handed the record as it was *before* it runs,
        // and reconciling inside it can clear `endsOnMotion` and persist that
        // — so writing the argument back afterwards would undo the clear on
        // disk and put the exit back on the card (Codex, PR #252).
        //
        // Reached the way it is reachable in the field: a rollback that could
        // not be recorded leaves the flag standing with no watch behind it,
        // and the next mode move is where it is cleared.
        TestSnoozeService.motionRegistrar.available = false
        TestSnoozeService.refuseRecordUpdates = true
        val record = snoozeFixture(now, capIn = Duration.ofHours(4)).copy(endsOnMotion = true)

        startService(SnoozeService.ACTION_RESTORE, record)
        assertEquals(
            "the setup this rests on: the rollback could not be recorded",
            true,
            stored()?.endsOnMotion,
        )

        TestSnoozeService.refuseRecordUpdates = false
        assertTrue(
            TestSnoozeService.presence.updates.tryEmit(
                PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX),
            ),
        )
        shadowOf(getMainLooper()).idle()

        // The record is what a restart reads, so it is what the finding is
        // about; the card is posted from the same value by the same call.
        assertEquals(
            "the clear survives the callback's own write",
            false,
            stored()?.endsOnMotion,
        )
    }

    @Test
    fun `a second movement does not end an already-ended snooze`() {
        setMotionEnd(snoozeFixture(now), value = true)
        TestSnoozeService.motionRegistrar.fire()
        val callsAfterEnding = TestSnoozeService.zen.calls.size

        TestSnoozeService.motionRegistrar.fire()

        assertEquals(callsAfterEnding, TestSnoozeService.zen.calls.size)
    }

    @Test
    fun `a choice that cannot be written never reaches memory either`() {
        // Memory and disk must not disagree about an exit: a process death
        // would reload the stored value and arm or disarm the sensor against
        // what the user had just chosen and been shown (Codex, PR #252).
        //
        // Pinned as *never armed* rather than as armed-then-reverted, which is
        // the whole of the ordering fix: the record is written before anything
        // in memory believes the choice, so a refused write leaves nothing
        // behind to undo. The revert this replaces could not tell a failed
        // write from one the transition had already committed.
        TestSnoozeService.refuseRecordUpdates = true

        setMotionEnd(snoozeFixture(now, capIn = Duration.ofHours(4)), value = true)

        assertEquals(
            "the sensor was never asked for at all",
            0,
            TestSnoozeService.motionRegistrar.arms,
        )
        assertFalse("nothing is listening", TestSnoozeService.motionRegistrar.armed)
        assertEquals("and the card never claimed it", false, stored()?.endsOnMotion)
    }

    @Test
    fun `the ongoing card says the snooze also ends on movement`() {
        // Principle 2: a second exit the user cannot see is one they cannot
        // predict. Asserted against the formatted resource rather than a
        // literal, so approved copy can be reworded without breaking this.
        val plain = stringOf(app.snoozemo.R.string.ongoing_ends_when_you_leave)
        val expected = appContext.getString(app.snoozemo.R.string.ongoing_or_when_you_move, plain)

        setMotionEnd(snoozeFixture(now, capIn = Duration.ofHours(4)), value = true)

        assertEquals(expected, shadeText())
    }

    @Test
    fun `a device with no sensor has the choice rolled back, not recorded`() {
        // Otherwise the record keeps the choice and the card promises `or when
        // you move` over an exit that can never fire — silence the user cannot
        // see, which is principle 2's failure rather than principle 1's
        // (Codex, PR #252).
        TestSnoozeService.motionRegistrar.available = false

        setMotionEnd(snoozeFixture(now, capIn = Duration.ofHours(4)), value = true)

        assertEquals(false, stored()?.endsOnMotion)
        assertEquals(
            "and the card never claims it",
            stringOf(app.snoozemo.R.string.ongoing_ends_when_you_leave),
            shadeText(),
        )
    }

    @Test
    fun `the ongoing card says nothing about movement until it is asked for`() {
        startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now, capIn = Duration.ofHours(4)))

        assertEquals(stringOf(app.snoozemo.R.string.ongoing_ends_when_you_leave), shadeText())
    }
}
