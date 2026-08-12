package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockChangeTest {

    private val start: Instant = Instant.parse("2026-08-11T09:00:00Z")
    private val uptimeAtStart = Duration.ofHours(30).toMillis()

    private fun snooze(cap: Duration = ActiveSnooze.DEFAULT_CAP) = ActiveSnooze(
        anchor = Anchor(capturedAt = start),
        startedAt = start,
        capExpiresAt = start.plus(cap),
        mode = TrackingMode.DURATION_ONLY,
        bootReference = start.toEpochMilli() - uptimeAtStart,
    )

    /** Both clocks after [elapsed] of real time and a wall clock moved [by]. */
    private fun reading(elapsed: Duration, by: Duration = Duration.ZERO) = ClockReading(
        wallMillis = start.plus(elapsed).plus(by).toEpochMilli(),
        uptimeMillis = uptimeAtStart + elapsed.toMillis(),
    )

    @Test
    fun `a backwards shift is restated into wall time`() {
        // The repair itself: two real hours into an eight-hour snooze, the clock
        // goes back three. Only uptime knows six hours are left, and only until
        // the next boot resets it — so the deadline is rewritten to say six
        // hours in the frame that survives.
        val snooze = snooze()
        val now = reading(elapsed = Duration.ofHours(2), by = Duration.ofHours(-3))

        val action = ClockChange.resolve(snooze, now)

        val restated = (action as ClockChangeAction.Restate).snooze
        assertEquals(Duration.ofHours(6), restated.remaining(now))
        assertEquals(Instant.ofEpochMilli(now.wallMillis).plus(Duration.ofHours(6)), restated.capExpiresAt)
        // And it survives the reboot that would otherwise have destroyed it.
        val atBoot = ClockReading(wallMillis = now.wallMillis + 60_000, uptimeMillis = 60_000)
        assertEquals(
            Duration.ofHours(6).minusMinutes(1),
            restated.rebasedOnto(atBoot).remaining(atBoot),
        )
    }

    @Test
    fun `a forward shift past the deadline ends at the cap`() {
        // The case nothing else notices: the alarm counts in elapsed realtime,
        // so it does not move with the clock, and the countdown would sit at
        // zero over a phone that is still silent.
        val snooze = snooze()
        val now = reading(elapsed = Duration.ofHours(2), by = Duration.ofHours(9))

        assertEquals(ClockChangeAction.EndAtCap, ClockChange.resolve(snooze, now))
    }

    @Test
    fun `an offsetless record ends rather than being stamped with a frame`() {
        // A record written before the offset existed has only wall time to be
        // read against, and wall time is exactly what just moved. Restating it
        // would hand the next reader a deadline that looks framed and is not —
        // and the shift here, three hours, is far too small for the 24 h ceiling
        // to catch. So the snooze ends instead (D7).
        val legacy = snooze().copy(bootReference = null)
        val now = reading(elapsed = Duration.ofHours(2), by = Duration.ofHours(-3))

        assertEquals(ClockChangeAction.EndUnframed, ClockChange.resolve(legacy, now))
    }

    @Test
    fun `an offsetless record whose cap is due still ends at the cap`() {
        // Ordering, not a detail: an expired record needs ending, and reporting
        // it as an unreadable frame would tell the user their snooze broke when
        // in fact it finished.
        val legacy = snooze().copy(bootReference = null)
        val now = reading(elapsed = Duration.ofHours(9))

        assertEquals(ClockChangeAction.EndAtCap, ClockChange.resolve(legacy, now))
    }

    @Test
    fun `a clock set to the time it already read asks for nothing`() {
        // The common case — an automatic correction of a few milliseconds' worth
        // of drift, or none at all — costs a preferences read and stops there.
        val snooze = snooze()
        val now = reading(elapsed = Duration.ofHours(2))

        assertEquals(ClockChangeAction.None, ClockChange.resolve(snooze, now))
    }

    @Test
    fun `restating never moves the cap further out`() {
        // The invariant the whole receiver is arranged around: a clock change
        // may end a snooze early, and may never extend one. Walked across the
        // range of shifts a user can produce in Settings.
        val snooze = snooze()
        val elapsed = Duration.ofHours(2)
        val trueRemaining = ActiveSnooze.DEFAULT_CAP.minus(elapsed)

        for (hours in -12L..12L) {
            val now = reading(elapsed, by = Duration.ofHours(hours))
            val restated = when (val action = ClockChange.resolve(snooze, now)) {
                is ClockChangeAction.Restate -> action.snooze
                else -> continue
            }
            assertTrue(
                "a $hours h shift must not lengthen the snooze",
                restated.remaining(now) <= trueRemaining,
            )
        }
    }
}
