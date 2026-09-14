package app.snoozemo.snooze

import android.content.Intent
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

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
        // A test that fails a snooze open under a refusing alarm leaves the
        // release/retry work posted to the main looper; drain it with alarms
        // available again so nothing carries into the next class. Resetting the
        // process-wide refuse flag is part of the same hygiene.
        TogglableAlarmManager.refuse = false
        shadowOf(getMainLooper()).idle()
    }

    private fun setMotionEnd(record: ActiveSnooze?, value: Boolean) =
        startService(SnoozeService.ACTION_SET_MOTION_END, record) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, value)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

    private fun stored(): ActiveSnooze? = ActiveSnoozeStore(appContext).load()

    /**
     * A second start on the **same** service instance, so an alarm refusal a
     * test arms after the service is up meets this action rather than the
     * restore that seeded it — a fresh instance would re-arm the cap on its own
     * restore first and fail there instead.
     */
    private fun ServiceController<TestSnoozeService>.send(action: String, startId: Int, extras: Intent.() -> Unit = {}) =
        get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java).setAction(action).apply(extras),
            0,
            startId,
        )

    /** The pending delay of the latest armed cap-check alarm. */
    private fun armedCapDelay(): Duration {
        val alarmManager = appContext.getSystemService(android.app.AlarmManager::class.java)
        val alarm = shadowOf(alarmManager).scheduledAlarms.last { scheduled ->
            shadowOf(scheduled.operation).savedIntent.action == SnoozeService.ACTION_CHECK_CAP
        }
        return Duration.ofMillis(alarm.triggerAtMs - android.os.SystemClock.elapsedRealtime())
    }

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
    fun `until I move drops a chosen time and restores the failsafe cap`() {
        // End conditions are mutually exclusive with a chosen time (maintainer,
        // 2026-09-13): a movement exit replaces it. The timer-only intent comes
        // off *and* the shortened cap goes back to the 8h failsafe, so the snooze
        // cannot end at the old chosen time hidden behind `Snoozing until you
        // move` (SPEC.md §4.4; Codex, PR #278).
        val chosen = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            timerOnlyRequested = true,
        )
        assertTrue("precondition: a shortened chosen cap", chosen.capExpiresAt.isBefore(chosen.capCeilingAt))

        setMotionEnd(chosen, value = true)

        val after = stored()
        assertEquals("the movement exit is armed", true, after?.endsOnMotion)
        assertEquals("the chosen time is dropped", false, after?.timerOnlyRequested)
        assertEquals("the cap is back at the failsafe", chosen.capCeilingAt, after?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `until I move leaves an already-failsafe cap alone`() {
        // The restore is a no-op when the cap already sits at its ceiling: a
        // plain movement snooze keeps its failsafe, nothing to move.
        val record = snoozeFixture(now)
        assertFalse(
            "precondition: cap already at the failsafe",
            record.capExpiresAt.isBefore(record.capCeilingAt),
        )

        setMotionEnd(record, value = true)

        val after = stored()
        assertEquals(true, after?.endsOnMotion)
        assertEquals("the cap is untouched", record.capExpiresAt, after?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    @Config(shadows = [TogglableAlarmManager::class])
    fun `the failsafe restore survives the cap alarm being unavailable`() {
        // Record-first (Codex, PR #278, P1): the ceiling is persisted before the
        // alarm moves, so a refused alarm leaves the record at the ceiling and the
        // old *shorter* alarm behind — which fires early and reschedules to the
        // ceiling. The restore has durably taken; the tap succeeds and nothing is
        // surfaced as failed.
        val chosen = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            timerOnlyRequested = true,
        )
        assertTrue("precondition: a shortened chosen cap", chosen.capExpiresAt.isBefore(chosen.capCeilingAt))
        // Restore with the alarm accepting, so the seed's own cap arm succeeds;
        // only the failsafe re-arm the tap attempts is refused.
        val service = startService(SnoozeService.ACTION_RESTORE, chosen)

        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_SET_MOTION_END, startId = 2) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        val after = stored()
        assertEquals("the movement exit is armed", true, after?.endsOnMotion)
        assertEquals("the timer-only intent is dropped", false, after?.timerOnlyRequested)
        assertEquals("the cap is at the failsafe on the record", chosen.capCeilingAt, after?.capExpiresAt)
        assertTrue("the sensor is listening", TestSnoozeService.motionRegistrar.armed)
        assertEquals("the tap applied", EndChoiceResult.APPLIED, reported)
    }

    @Test
    @Config(shadows = [TogglableAlarmManager::class])
    fun `an early cap check re-arms the exact wake to the ceiling after a refused restore`() {
        // Where the refused failsafe re-arm heals (Codex, PR #278, P1). After
        // `restoreCapToFailsafe` persists the ceiling but its exact re-arm is
        // refused, the old shorter alarm is left behind. When it fires early,
        // `rescheduleIfUnfinished` finds the snooze unexpired and re-arms the
        // exact wake to the controller's cap — the shared heal for every
        // lengthening path — rather than returning and leaving the cap on the
        // deferrable backstop alone.
        val chosen = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            timerOnlyRequested = true,
        )
        val service = startService(SnoozeService.ACTION_RESTORE, chosen)

        // The restore's ceiling re-arm is refused, so the exact alarm stays at
        // the chosen hour while the record lengthens to the failsafe.
        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_SET_MOTION_END, startId = 2) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }
        assertEquals(
            "precondition: the record lengthened to the ceiling",
            chosen.capCeilingAt,
            stored()?.capExpiresAt,
        )

        // The old shorter alarm fires early, with alarms available again. The
        // last cap armed in-process is the chosen hour, not the record's ceiling,
        // so the reconcile sees the ceiling uncovered and re-arms the exact wake
        // to it — no marker on the intent, just the in-process cap it last armed
        // measured against the record.
        TogglableAlarmManager.refuse = false
        service.send(SnoozeService.ACTION_CHECK_CAP, startId = 3)

        assertEquals(
            "the exact wake is re-armed to the ceiling, not left at the chosen hour",
            Duration.between(now, chosen.capCeilingAt),
            armedCapDelay(),
        )
        assertEquals("and the snooze is still running to the ceiling", chosen.capCeilingAt, stored()?.capExpiresAt)
    }

    @Test
    @Config(shadows = [TogglableAlarmManager::class])
    fun `a cap check whose re-arm is also refused fails open and ends the snooze`() {
        // The heal's own refusal (Codex, PR #278, fifth finding). When the
        // restore's ceiling re-arm is refused AND the early cap check's re-arm is
        // refused too, there is no further heal before the deferrable backstop —
        // so the shared choke point fails open (`failOpenUnschedulableCap`),
        // ending the snooze rather than leaving DND on the backstop past the cap
        // (SPEC.md §7, principle 1).
        val chosen = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            timerOnlyRequested = true,
        )
        val service = startService(SnoozeService.ACTION_RESTORE, chosen)

        // Alarms refused from here on: the restore's ceiling re-arm fails, then
        // the cap check's re-arm fails too.
        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_SET_MOTION_END, startId = 2) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }
        // The early-firing shortened alarm reaches the cap check: the ceiling is
        // uncovered (the last cap armed was the chosen hour), so its re-arm is
        // attempted, and refused too.
        service.send(SnoozeService.ACTION_CHECK_CAP, startId = 3)

        assertNull("the snooze is ended fail-open, not left on the backstop", stored())
        assertEquals(
            "and the rule is driven off",
            false,
            TestSnoozeService.zen.calls.lastOrNull()?.first,
        )
    }

    @Test
    @Config(shadows = [TogglableAlarmManager::class])
    fun `a backstop cap check leaves a healthy snooze running when a re-arm would be refused`() {
        // The sixth finding (Codex, PR #278). BackstopWorker pokes
        // ACTION_CHECK_CAP on every periodic wake while the real cap alarm is
        // still scheduled. On a healthy snooze — the record's cap covered by the
        // alarm the arm scheduled for it — the reconcile does nothing: it re-arms
        // only an uncovered cap, so a poke whose scheduled alarm still ends the
        // snooze on time never re-arms-and-fails-open. Alarms are refused for the
        // poke to prove that a re-arm which WOULD fail is never even attempted.
        val chosen = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            timerOnlyRequested = true,
        )
        // Restored with alarms available, so the cap alarm is armed for exactly
        // the record's cap and the reconcile knows that cap is covered.
        val service = startService(SnoozeService.ACTION_RESTORE, chosen)

        // A backstop poke with alarms refused: the cap is already covered, so the
        // guard returns before any re-arm and the refusal never bites.
        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_CHECK_CAP, startId = 2)

        assertNotNull(
            "the healthy snooze survives a backstop poke",
            stored(),
        )
        assertEquals(
            "the rule is left on, not driven off",
            true,
            TestSnoozeService.zen.calls.lastOrNull()?.first,
        )
    }

    @Test
    @Config(shadows = [TogglableAlarmManager::class])
    fun `a duplicate delivery of an already-healed cap alarm leaves the snooze running`() {
        // The eighth finding (Codex, PR #278). An early cap alarm can be
        // delivered twice. The first delivery heals — it re-arms the exact wake
        // to the ceiling, so the in-process cap last armed becomes the ceiling.
        // The duplicate then finds the record's cap already covered and does
        // nothing, rather than re-arming redundantly and failing open on a
        // transient refusal. No marker distinguishes the two deliveries; the
        // in-process cap already covering the record does.
        val chosen = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            timerOnlyRequested = true,
        )
        val service = startService(SnoozeService.ACTION_RESTORE, chosen)

        // The restore's ceiling re-arm is refused, leaving the old shorter alarm
        // behind while the record lengthens to the ceiling.
        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_SET_MOTION_END, startId = 2) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        // First delivery of the old alarm, alarms available: it heals, re-arming
        // the exact wake to the ceiling.
        TogglableAlarmManager.refuse = false
        service.send(SnoozeService.ACTION_CHECK_CAP, startId = 3)
        assertEquals(
            "precondition: the heal armed the ceiling",
            Duration.between(now, chosen.capCeilingAt),
            armedCapDelay(),
        )

        // The duplicate of that same old alarm arrives with alarms refused. The
        // ceiling the heal armed now covers the record, so the duplicate must not
        // re-arm-and-fail-open the snooze it already covers.
        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_CHECK_CAP, startId = 4)

        assertNotNull("the snooze survives the duplicate delivery", stored())
        assertEquals(
            "the rule is left on, not driven off",
            true,
            TestSnoozeService.zen.calls.lastOrNull()?.first,
        )
    }

    @Test
    @Config(shadows = [TogglableAlarmManager::class])
    fun `a backstop poke on a freshly-armed chosen-time snooze does not fail open`() {
        // The ninth finding (Codex, PR #278). A snooze started from the idle
        // chosen-time row arms its cap through the pre-arm — the one cap arm that
        // does not route through the in-service helper — so the pre-arm records
        // the cap it covers itself. Without that, the reconcile would not know the
        // fresh snooze was already covered: the first backstop poke would re-arm
        // and fail open on a refusal, ending a snooze whose cap was never lost.
        val chosenAt = now.plus(Duration.ofHours(1))
        val service = startService(SnoozeService.ACTION_ARM) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, chosenAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }
        assertEquals("precondition: armed to the chosen time", chosenAt, stored()?.capExpiresAt)

        // A backstop poke with alarms refused: the pre-armed cap still covers the
        // record, so the guard returns before any re-arm and the refusal never
        // bites.
        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_CHECK_CAP, startId = 2)

        assertNotNull("the freshly-armed snooze survives a backstop poke", stored())
        assertEquals(
            "the rule is left on, not driven off",
            true,
            TestSnoozeService.zen.calls.lastOrNull()?.first,
        )
    }

    @Test
    fun `a failed failsafe restore keeps the exit and reports partial`() {
        // The exit the user asked for is armed and its shortened cap stays visible
        // (capCountdownShown), so a restore that cannot persist the ceiling keeps
        // the exit and surfaces the failure for a retry rather than unwinding a
        // good write (maintainer, 2026-09-13; Codex, PR #278). The outcome is
        // PARTIAL, not APPLIED (maintainer, 2026-09-14; Codex, PR #286): the exit
        // took but the cap did not reach the failsafe, so the chooser must stay up
        // as the retry surface rather than close on APPLIED — which, with
        // notifications denied, would take the card too and leave the failure
        // silent behind the shortened cap.
        val chosen = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            timerOnlyRequested = true,
        )
        val service = startService(SnoozeService.ACTION_RESTORE, chosen)

        // Refuse only the write that moves the cap to the ceiling; the exit's own
        // write and the intent clear (both at the chosen time) land.
        TestSnoozeService.refuseRecordUpdateWhen = { it.capExpiresAt == chosen.capCeilingAt }
        service.send(SnoozeService.ACTION_SET_MOTION_END, startId = 2) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }
        TestSnoozeService.refuseRecordUpdateWhen = null

        val after = stored()
        assertEquals("the movement exit is kept", true, after?.endsOnMotion)
        assertTrue("the sensor is listening", TestSnoozeService.motionRegistrar.armed)
        assertEquals("the cap is left at the chosen time, still visible", chosen.capExpiresAt, after?.capExpiresAt)
        assertEquals(
            "the tap is partial — the exit took but the cap did not reach the failsafe",
            EndChoiceResult.PARTIAL,
            reported,
        )
        assertTrue(
            "and the failure is surfaced for a retry",
            shadeShows(stringOf(app.snoozemo.R.string.failure_could_not_set_end)),
        )
    }

    @Test
    fun `a retry over an armed exit clears a lingering timer-only intent`() {
        // Codex, PR #278: retrying "Until I move" over a partial timer whose
        // motion exit is already armed can leave applyMotionEnd's own intent
        // clear refused — it reports APPLIED because that tap added no exit — so
        // folding the clear into the failsafe cap write is what settles it, and
        // the snooze does not read back a false partial timer.
        val partial = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            endsOnMotion = true,
            timerOnlyRequested = true,
        )
        assertTrue("precondition: a partial timer", partial.isPartialTimer)
        val service = startService(SnoozeService.ACTION_RESTORE, partial)

        // Refuse applyMotionEnd's own intent clear (the shortened-cap write that
        // drops timerOnly), leaving the fold in restoreCapToFailsafe to do it.
        TestSnoozeService.refuseRecordUpdateWhen =
            { it.capExpiresAt == partial.capExpiresAt && !it.timerOnlyRequested }
        service.send(SnoozeService.ACTION_SET_MOTION_END, startId = 2) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }
        TestSnoozeService.refuseRecordUpdateWhen = null

        val after = stored()
        assertEquals("the exit stays armed", true, after?.endsOnMotion)
        assertEquals("the timer-only intent is cleared by the fold", false, after?.timerOnlyRequested)
        assertEquals("the cap is at the failsafe", partial.capCeilingAt, after?.capExpiresAt)
        assertFalse("so it no longer reads as a partial timer", after!!.isPartialTimer)
        assertEquals(EndChoiceResult.APPLIED, reported)
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
