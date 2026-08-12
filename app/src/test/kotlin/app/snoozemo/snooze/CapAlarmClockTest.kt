package app.snoozemo.snooze

import android.app.AlarmManager
import android.os.SystemClock
import app.snoozemo.core.ActiveSnooze
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The duration cap against a wall clock the user can move (SPEC.md §7).
 *
 * Setting the date back used to keep Do Not Disturb on past the 24 h backstop:
 * the alarm was `RTC_WAKEUP` and the expiry test read the wall clock, so both
 * moved together. Principle 1's failure, reachable from Settings.
 */
@RunWith(RobolectricTestRunner::class)
class CapAlarmClockTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
    }

    @Test
    fun `the cap rides elapsed realtime rather than the wall clock`() {
        CapAlarm.arm(appContext, SnoozeClock.millis() + Duration.ofHours(8).toMillis())

        val capAlarm = scheduledAlarms().last()
        assertEquals(
            "a wall-clock cap moves when the clock does",
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            capAlarm.type,
        )
    }

    @Test
    fun `an eight-hour cap is armed eight hours out`() {
        val eightHours = Duration.ofHours(8).toMillis()
        CapAlarm.arm(appContext, SnoozeClock.millis() + eightHours)

        val fromNow = scheduledAlarms().last().triggerAtTime - SystemClock.elapsedRealtime()
        // Inexact by design (`setAndAllowWhileIdle`), so this is a sanity band
        // rather than an equality: what matters is that it is not the wall-clock
        // epoch value, which would be billions of milliseconds out.
        assertTrue(
            "expected roughly eight hours out, got ${fromNow}ms",
            fromNow in (eightHours - 5_000)..(eightHours + 5_000),
        )
    }

    @Test
    fun `a cap that a backwards clock change pushed out is capped at the backstop`() {
        // What a record looks like after the date is wound back a month while no
        // process was alive to notice: its absolute expiry is now far away. The
        // ceiling is what stops that becoming an unbounded snooze — a remaining
        // duration past `MAX_CAP` is not a long snooze, it is a moved clock.
        CapAlarm.arm(appContext, SnoozeClock.millis() + Duration.ofDays(30).toMillis())

        val fromNow = scheduledAlarms().last().triggerAtTime - SystemClock.elapsedRealtime()
        assertTrue(
            "expected the 24 h backstop, got ${fromNow}ms",
            fromNow <= ActiveSnooze.MAX_CAP.toMillis() + 5_000,
        )
    }

    @Test
    fun `an already-expired cap fires immediately rather than in the past`() {
        CapAlarm.arm(appContext, SnoozeClock.millis() - Duration.ofHours(1).toMillis())

        val fromNow = scheduledAlarms().last().triggerAtTime - SystemClock.elapsedRealtime()
        assertTrue("an overdue cap must be due now, not overdue by an hour", fromNow in 0..5_000)
    }

    @Test
    fun `the retry alarms keep the wall clock they were measured against`() {
        // Only the cap changes frame. These are short relative delays computed
        // from the wall clock at the call site, so converting one and not the
        // other is how an alarm lands in 1970.
        CapAlarm.armEraseRetry(appContext, System.currentTimeMillis() + 30_000, now.toEpochMilli())

        assertEquals(AlarmManager.RTC_WAKEUP, scheduledAlarms().last().type)
    }

    @Test
    fun `a retry expressed as a delay is armed at that delay`() {
        // The recordless release retry says "in 30 seconds". Expressing that as
        // a wall-clock instant and converting it back is what turned a
        // half-minute retry into hours once the two clocks disagreed.
        CapAlarm.armCheckIn(appContext, 30_000)

        val fromNow = scheduledAlarms().last().triggerAtTime - SystemClock.elapsedRealtime()
        assertTrue("expected ~30s, got ${fromNow}ms", fromNow in 25_000..35_000)
    }

    @Test
    fun `a delay is never read against the wall clock`() {
        // The regression guard: `armCheckIn` must not go anywhere near
        // `SnoozeClock`, so an epoch-sized value can never leak into the
        // trigger the way it could when the frame was inferred from the action.
        CapAlarm.armCheckIn(appContext, 30_000)

        assertTrue(
            "a delay must not be measured against wall time",
            scheduledAlarms().last().triggerAtTime < System.currentTimeMillis() / 2,
        )
    }
}
