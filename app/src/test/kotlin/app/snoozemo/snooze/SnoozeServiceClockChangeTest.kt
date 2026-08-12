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
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
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
