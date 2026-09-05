package app.snoozemo.snooze

import android.content.Intent
import app.snoozemo.core.ClockReading
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenTrigger
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * What a wall-clock change does to the snooze the service is holding
 * (SPEC.md §7).
 *
 * The decision is `ClockChange`'s and is covered in `:core`. What is only
 * reachable here is the half that made it a bug: whether the *running*
 * controller ends up carrying the repaired record, or keeps the pre-change one
 * for the next write to put back on disk.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceClockChangeTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /** A four-hour snooze, so `+30 min` has room to move under either frame. */
    private val cap: Duration = Duration.ofHours(4)

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
        // The rule goes on and comes off as asked; the refusing default is for
        // the escalation's tests, and here it would end every snooze before the
        // clock could be changed under it.
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
        // Static, so a refusing test must not leak its refusal forward.
        TogglableAlarmManager.refuse = false
    }

    /**
     * Two real hours into the snooze, with the wall clock then set back three.
     * Uptime knows two hours are left; wall time claims five.
     */
    private fun woundBack(): ClockReading = ClockReading(
        wallMillis = now.minus(Duration.ofHours(1)).toEpochMilli(),
        uptimeMillis = TestSnoozeService.FIXTURE_UPTIME_MILLIS + Duration.ofHours(2).toMillis(),
    )

    /** What the record would be read as after a reboot a minute later. */
    private fun afterReboot(from: ClockReading): ClockReading =
        ClockReading(wallMillis = from.wallMillis + 60_000, uptimeMillis = 60_000)

    /**
     * A second start on the **same** service instance, which is the whole point
     * — a fresh one would reload the record from disk and could never carry the
     * stale in-memory copy this is about.
     */
    private fun ServiceController<TestSnoozeService>.send(action: String, startId: Int) =
        get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java).setAction(action),
            0,
            startId,
        )

    @Test
    fun `an extension after a clock change keeps the restated deadline`() {
        // The bug: the receiver wrote the repaired record while the running
        // controller kept the pre-change one, and `+30 min` derived its update
        // from memory — putting the pre-change deadline back on disk, where the
        // next reboot adopts it and holds Do Not Disturb open by the shift.
        TestSnoozeService.testReading = woundBack()
        val service = startService(SnoozeService.ACTION_CLOCK_CHANGED, snoozeFixture(now, capIn = cap))

        service.send(SnoozeService.ACTION_EXTEND, startId = 2)

        val stored = requireNotNull(ActiveSnoozeStore(appContext).load())
        val atBoot = afterReboot(TestSnoozeService.testReading)
        assertEquals(
            "the extension must build on the restated deadline, not the pre-change one",
            Duration.ofHours(2).plusMinutes(30).minusMinutes(1),
            stored.rebasedOnto(atBoot).remaining(atBoot),
        )
    }

    @Test
    fun `extensions after a clock change stop at the eight-hour backstop`() {
        // The same repair read through the disk: the ceiling `+30 min` clamps to
        // has to be persisted in the restated frame, or a later process reloads
        // one still measured from `startedAt` — three hours of slack that never
        // existed, and eleven real hours of silence at the end of it.
        TestSnoozeService.testReading = woundBack()
        val service = startService(SnoozeService.ACTION_CLOCK_CHANGED, snoozeFixture(now, capIn = cap))

        repeat(16) { tap -> service.send(SnoozeService.ACTION_EXTEND, startId = tap + 2) }

        val stored = requireNotNull(ActiveSnoozeStore(appContext).load())
        assertEquals(
            // Three hours in — the fixture starts an hour before `now`, and the
            // reading is two hours of uptime past it — so five is all the
            // eight-hour backstop has left to give.
            "no run of taps may buy past the eight-hour backstop",
            Duration.ofHours(5),
            stored.remaining(TestSnoozeService.testReading),
        )
    }

    @Test
    fun `a clock change writes the restated record through to disk`() {
        // The repair itself, from the service rather than the receiver: after a
        // backwards change only uptime knows how long is left, and the next boot
        // resets it. The deadline has to be rewritten while wall time can still
        // be read.
        TestSnoozeService.testReading = woundBack()

        startService(SnoozeService.ACTION_CLOCK_CHANGED, snoozeFixture(now, capIn = cap))

        val stored = requireNotNull(ActiveSnoozeStore(appContext).load())
        val atBoot = afterReboot(TestSnoozeService.testReading)
        assertEquals(
            Duration.ofHours(2).minusMinutes(1),
            stored.rebasedOnto(atBoot).remaining(atBoot),
        )
    }

    /**
     * The pending delay of the armed cap-check alarm, measured against the
     * shadow's own elapsed clock — the frame `armCheckIn` schedules in.
     */
    private fun armedCapDelay(): Duration {
        val alarmManager = appContext.getSystemService(android.app.AlarmManager::class.java)
        val alarm = org.robolectric.Shadows.shadowOf(alarmManager).scheduledAlarms.last { scheduled ->
            org.robolectric.Shadows.shadowOf(scheduled.operation).savedIntent.action ==
                SnoozeService.ACTION_CHECK_CAP
        }
        return Duration.ofMillis(alarm.triggerAtMs - android.os.SystemClock.elapsedRealtime())
    }

    @Test
    fun `a forward clock change pulls the armed alarm in to match the countdown`() {
        // The precision §7 used to give up: the alarm counts in elapsed
        // realtime, so a forward jump left it at the original delay while the
        // record and countdown read the shortened wall answer — zero on
        // screen, silence until the alarm. The restate makes the record's
        // frame provably fresh, which is what licenses the re-arm.
        val service = startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now, capIn = cap))
        assertEquals("armed at the original four hours", Duration.ofHours(4), armedCapDelay())

        // The clock jumps two hours forward; no real time passes.
        TestSnoozeService.testReading = ClockReading(
            wallMillis = now.plus(Duration.ofHours(2)).toEpochMilli(),
            uptimeMillis = TestSnoozeService.FIXTURE_UPTIME_MILLIS,
        )
        service.send(SnoozeService.ACTION_CLOCK_CHANGED, startId = 2)

        assertEquals(
            "the alarm must now name the same moment the countdown does",
            Duration.ofHours(2),
            armedCapDelay(),
        )
    }

    @Test
    fun `a backward clock change re-arms at the uptime truth`() {
        // The direction the never-re-arm rule was written for, safe here
        // because the re-arm happens only behind the restate that just wrote
        // the frame it reads: the recomputed delay is uptime's answer — two
        // real hours left — not the five the moved wall clock claims.
        val service = startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now, capIn = cap))

        TestSnoozeService.testReading = woundBack()
        service.send(SnoozeService.ACTION_CLOCK_CHANGED, startId = 2)

        assertEquals(
            "a backwards change must never push the alarm out",
            Duration.ofHours(2),
            armedCapDelay(),
        )
    }

    @Test
    @org.robolectric.annotation.Config(shadows = [TogglableAlarmManager::class])
    fun `a restate whose re-arm is refused ends the snooze`() {
        // The record has just been rewritten to a deadline the refused alarm
        // will not honor — after a forward jump, a countdown at zero over a
        // silent phone for the size of the shift, with only a log saying why
        // (flagged by Codex on PR #63). Same answer as a restate that cannot
        // be written: end, and say so through the ended notification.
        val service = startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now, capIn = cap))

        TogglableAlarmManager.refuse = true
        TestSnoozeService.testReading = woundBack()
        service.send(SnoozeService.ACTION_CLOCK_CHANGED, startId = 2)

        assertNull(
            "a cap that cannot be scheduled where it is promised ends the snooze",
            ActiveSnoozeStore(appContext).load(),
        )
        assertTrue(
            "the rule must be driven off, not just forgotten",
            TestSnoozeService.zen.calls.contains(false to ZenTrigger.CONTEXT),
        )
    }

    @Test
    fun `the no-service fallback restates the record and re-arms the cap`() {
        // The receiver's own performer, reached only when the service refuses
        // to start — the path no device takes on purpose, which is exactly why
        // it needs its own test (Codex, PR #63). Driven through the real
        // receiver with a context whose startService throws, so the fallback
        // is what does the work; the record is built against the shadow
        // clocks, since this path reads SnoozeClock rather than the harness.
        val wallNow = System.currentTimeMillis()
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        // A stale offset claiming the boot began two hours later than it did:
        // the uptime frame reads four hours left, the wall frame two — the
        // forward-jump disagreement the restate exists to settle.
        val record = snoozeFixture(now, capIn = cap).copy(
            capExpiresAt = Instant.ofEpochMilli(wallNow + Duration.ofHours(2).toMillis()),
            bootReference = (wallNow - elapsedNow) - Duration.ofHours(2).toMillis(),
        )
        ActiveSnoozeStore(appContext).arm(record)
        val refusing = object : android.content.ContextWrapper(appContext) {
            override fun startService(service: Intent?): android.content.ComponentName? =
                throw IllegalStateException("the platform refuses the start")
        }

        TimeChangedReceiver().onReceive(refusing, Intent(Intent.ACTION_TIME_CHANGED))

        val stored = requireNotNull(ActiveSnoozeStore(appContext).load())
        // Within a second rather than exact: this path reads the real shadow
        // clocks, which tick a few milliseconds between the fixture's reading
        // and the receiver's. The stale offset differs by two hours, so the
        // tolerance cannot blur what is being asserted.
        val offsetDrift = Math.abs(requireNotNull(stored.bootReference) - (wallNow - elapsedNow))
        assertTrue("the restated offset must describe this boot (drift ${offsetDrift}ms)", offsetDrift < 1_000)
        val delayDrift = Duration.ofHours(2).minus(armedCapDelay()).abs()
        assertTrue(
            "the fallback must pull the alarm in to the restated remaining (drift ${delayDrift.toMillis()}ms)",
            delayDrift < Duration.ofSeconds(1),
        )
    }

    @Test
    @org.robolectric.annotation.Config(shadows = [TogglableAlarmManager::class])
    fun `the no-service fallback also ends when its re-arm is refused`() {
        // The receiver twin of the refused-re-arm test: service start refused,
        // then the alarm refused too. What is left is the direct release —
        // driven here through the real zen controller, whose missing policy
        // access reads as nothing-left-to-release, which completes the end and
        // clears the record exactly as a live refusal's escalation would
        // eventually have to.
        val wallNow = System.currentTimeMillis()
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        val record = snoozeFixture(now, capIn = cap).copy(
            capExpiresAt = Instant.ofEpochMilli(wallNow + Duration.ofHours(2).toMillis()),
            bootReference = (wallNow - elapsedNow) - Duration.ofHours(2).toMillis(),
        )
        ActiveSnoozeStore(appContext).arm(record)
        TogglableAlarmManager.refuse = true
        val refusing = object : android.content.ContextWrapper(appContext) {
            override fun startService(service: Intent?): android.content.ComponentName? =
                throw IllegalStateException("the platform refuses the start")
        }

        TimeChangedReceiver().onReceive(refusing, Intent(Intent.ACTION_TIME_CHANGED))

        assertNull(
            "a cap that cannot be scheduled where it is promised ends the snooze here too",
            ActiveSnoozeStore(appContext).load(),
        )
    }

    @Test
    fun `an offsetless record does not survive a clock change`() {
        // A record written before the offset existed has only the wall clock to
        // be read against, and the wall clock is what just moved — by an amount
        // nothing here can recover. Restating it would stamp the moved reading
        // as though it were measured, so the snooze ends instead (D7).
        TestSnoozeService.testReading = woundBack()
        val legacy = snoozeFixture(now, capIn = cap).copy(bootReference = null)

        startService(SnoozeService.ACTION_CLOCK_CHANGED, legacy)

        assertNull("the record must not outlive a bound nothing can read", ActiveSnoozeStore(appContext).load())
        assertTrue(
            "the rule must be driven off, not just forgotten",
            TestSnoozeService.zen.calls.contains(false to ZenTrigger.CONTEXT),
        )
    }

    @Test
    fun `a clock change past the cap ends the snooze`() {
        // The forward jump nothing else notices: the alarm counts in elapsed
        // realtime, so it does not move with the clock, and the countdown would
        // sit at zero over a phone that is still silent.
        TestSnoozeService.testReading = ClockReading(
            wallMillis = now.plus(Duration.ofHours(9)).toEpochMilli(),
            uptimeMillis = TestSnoozeService.FIXTURE_UPTIME_MILLIS + Duration.ofHours(2).toMillis(),
        )

        startService(SnoozeService.ACTION_CLOCK_CHANGED, snoozeFixture(now, capIn = cap))

        assertNull(ActiveSnoozeStore(appContext).load())
        assertTrue(TestSnoozeService.zen.calls.contains(false to ZenTrigger.CONTEXT))
    }
}

/**
 * An `AlarmManager` whose refusal a test can switch on mid-flight, unlike
 * `RefusingAlarmManager`, which refuses from the first call — the refused
 * re-arm test needs a snooze to arm normally first and only then meet a
 * platform that says no.
 */
@org.robolectric.annotation.Implements(android.app.AlarmManager::class)
class TogglableAlarmManager : org.robolectric.shadows.ShadowAlarmManager() {
    @org.robolectric.annotation.Implementation
    override fun setAndAllowWhileIdle(
        type: Int,
        triggerAtMillis: Long,
        operation: android.app.PendingIntent,
    ) {
        if (refuse) throw SecurityException("The test platform refuses this alarm.")
        super.setAndAllowWhileIdle(type, triggerAtMillis, operation)
    }

    companion object {
        /** Reset in `setUp`; static, so a refusal must not leak between tests. */
        var refuse: Boolean = false
    }
}
