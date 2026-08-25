package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sheet's arithmetic (SPEC.md §4.4). Every case here is a time a user could
 * plausibly tap the tile at, because the rounding is the part that reads as a
 * bug when it is a minute out.
 */
class EndConditionTest {

    /** A cap eight hours out, which is what an unmodified snooze arms with. */
    private fun ceilingFrom(now: Instant): Instant = now.plus(ActiveSnooze.DEFAULT_CAP)

    /**
     * Every case below reads its clock in UTC, so a `13:12Z` fixture is a
     * 13:12 wall clock. The zone-sensitive cases name their own zone.
     */
    private fun seededAt(now: Instant, zone: ZoneId = ZoneId.of("UTC")) =
        EndCondition.seededAt(now, ceilingFrom(now), zone)

    @Test
    fun `seeds an hour out, rounded up to the next half hour`() {
        // 13:12 + 1 h is 14:12, whose nearest half hour is 14:00 — §4.4's own
        // worked example.
        assertEquals(
            Instant.parse("2026-08-25T14:00:00Z"),
            seededAt(Instant.parse("2026-08-25T13:12:00Z")).endsAt,
        )
    }

    @Test
    fun `rounds down when the next half hour is further away`() {
        // 13:50 + 1 h is 14:50, nearer 15:00 than 14:30.
        assertEquals(
            Instant.parse("2026-08-25T15:00:00Z"),
            seededAt(Instant.parse("2026-08-25T13:50:00Z")).endsAt,
        )
    }

    @Test
    fun `rounds an exact halfway point up`() {
        // 13:45 + 1 h is 14:45 — equidistant, and rounding up is what keeps the
        // seed at least the promised hour out more often than not.
        assertEquals(
            Instant.parse("2026-08-25T15:00:00Z"),
            seededAt(Instant.parse("2026-08-25T13:45:00Z")).endsAt,
        )
    }

    @Test
    fun `rounds from the whole reading, not a truncated one`() {
        // 13:11:59.999 + 1 h is 14:11:59.999. Rounded on the minute that is
        // 14:11 → 14:00; an implementation that dropped the sub-minute part
        // before adding the half-step lands a whole step away.
        assertEquals(
            Instant.parse("2026-08-25T14:00:00Z"),
            seededAt(Instant.parse("2026-08-25T13:11:59.999Z")).endsAt,
        )
    }

    @Test
    fun `steps down in half hours and stops at thirty minutes from now`() {
        val now = Instant.parse("2026-08-25T13:00:00Z")
        var condition = seededAt(now)
        assertEquals(Instant.parse("2026-08-25T14:00:00Z"), condition.endsAt)

        condition = condition.stepDown()
        assertEquals(Instant.parse("2026-08-25T13:30:00Z"), condition.endsAt)

        // 13:30 is exactly the floor, so the next step down has nowhere to go.
        assertFalse(condition.canStepDown)
        assertEquals(condition, condition.stepDown())
    }

    @Test
    fun `steps up in half hours and stops at the backstop`() {
        val now = Instant.parse("2026-08-25T13:00:00Z")
        // A ceiling only two steps above the seed, standing in for a snooze
        // already close to its eight-hour backstop.
        val ceiling = Instant.parse("2026-08-25T15:00:00Z")
        var condition = EndCondition.seededAt(now, ceiling, ZoneId.of("UTC"))
        assertEquals(Instant.parse("2026-08-25T14:00:00Z"), condition.endsAt)

        condition = condition.stepUp()
        assertEquals(Instant.parse("2026-08-25T14:30:00Z"), condition.endsAt)
        condition = condition.stepUp()
        assertEquals(ceiling, condition.endsAt)

        assertFalse(condition.canStepUp)
        assertEquals(condition, condition.stepUp())
    }

    @Test
    fun `both buttons are live when a step down clears the floor`() {
        // 13:00 seeds 14:00, and the step below it is 13:30 — exactly the
        // thirty-minute floor, so both directions are offered.
        val condition = seededAt(Instant.parse("2026-08-25T13:00:00Z"))
        assertTrue(condition.canStepDown)
        assertTrue(condition.canStepUp)
    }

    @Test
    fun `minus is dead when the step below it would be inside the floor`() {
        // §4.4's own example: 13:12 seeds 14:00. The step below that is 13:30,
        // which is eighteen minutes out — inside the thirty-minute floor, so
        // there is no half-hour value left to offer. Rounding the seed onto the
        // grid is what produces this: it can leave as little as forty-five
        // minutes of headroom, and a step is thirty. The button reports itself
        // dead rather than clamping onto a ragged 13:42.
        val condition = seededAt(Instant.parse("2026-08-25T13:12:00Z"))
        assertFalse(condition.canStepDown)
        assertTrue(condition.canStepUp)
    }

    @Test
    fun `never opens above the backstop`() {
        val now = Instant.parse("2026-08-25T13:00:00Z")
        // Forty minutes of headroom: less than the hour the seed wants, so the
        // sheet must open on the backstop itself rather than offering a time
        // the service would refuse.
        val ceiling = now.plus(Duration.ofMinutes(40))
        val condition = EndCondition.seededAt(now, ceiling, ZoneId.of("UTC"))

        assertEquals(ceiling, condition.endsAt)
        assertFalse(condition.canStepUp)
        // `−` has nowhere to go either: a half-hour step off 13:40 lands at
        // 13:10, inside the floor. Steps stay on the half hour rather than
        // clamping onto it, so the button reports itself dead instead of
        // offering a ragged time.
        assertFalse(condition.canStepDown)
    }

    @Test
    fun `offers nothing when the backstop is already inside the floor`() {
        val now = Instant.parse("2026-08-25T13:00:00Z")
        // Ten minutes left before the backstop — closer than the thirty-minute
        // floor, so there is no value in range at all. The sheet still renders;
        // it just has no step to offer, which is what the two flags say.
        val ceiling = now.plus(Duration.ofMinutes(10))
        val condition = EndCondition.seededAt(now, ceiling, ZoneId.of("UTC"))

        // Never later than the ceiling, even though that is below the floor.
        // Clamping up to the floor afterward put the shown time *past the cap*,
        // which the service honors by doing nothing and reports as applied —
        // a row reading 13:30 over a snooze ending at 13:10 (Codex, PR #118).
        assertEquals(ceiling, condition.endsAt)
        assertFalse(condition.canStepDown)
        assertFalse(condition.canStepUp)
    }

    @Test
    fun `rounds onto the half hours the user sees, not UTC's`() {
        // Nepal is UTC+05:45, so a UTC-aligned 30-minute grid lands a
        // quarter-hour off every local half hour. 13:12 local is 07:27Z; the
        // seed an hour out is 14:12 local, whose nearest local half hour is
        // 14:00 — 08:15Z. Rounded against UTC it would be 08:30Z, which the
        // user reads as 14:15.
        val zone = ZoneId.of("Asia/Kathmandu")
        assertEquals(
            Instant.parse("2026-08-25T08:15:00Z"),
            seededAt(Instant.parse("2026-08-25T07:27:00Z"), zone).endsAt,
        )
    }

    @Test
    fun `seeds forward through a daylight-saving fall-back`() {
        // New York runs 01:00–02:00 twice on 2026-11-01: first at EDT (UTC-4),
        // then at EST (UTC-5). A tap at 01:40 EDT is 05:40Z; an hour out is
        // 06:40Z, which reads as 01:40 EST — the *second* pass. Rounded to
        // 01:30 and resolved by `LocalDateTime.atZone`, that comes back as
        // 01:30 EDT (05:30Z), half an hour *before* the tap. The floor then
        // drags it to 06:10Z: a ragged 01:10, thirty minutes out rather than
        // the promised hour (Codex, PR #118).
        val zone = ZoneId.of("America/New_York")
        val tap = Instant.parse("2026-11-01T05:40:00Z")

        assertEquals(
            Instant.parse("2026-11-01T06:30:00Z"),
            seededAt(tap, zone).endsAt,
        )
    }

    @Test
    fun `takes the nearer side of a fall-back rather than the later wall clock`() {
        // A tap at 00:50 EDT, ten minutes before New York runs 01:00–02:00 for
        // the second time. An hour out is 01:50 EDT; the wall clock rounds up
        // to 02:00, which exists only in EST — 2h10m past the tap — while
        // 01:30 EDT is twenty minutes from the target. Resolving one nominal
        // wall clock picked the far side; comparing the instants each
        // neighboring half hour can name picks the near one (Codex, PR #118).
        val zone = ZoneId.of("America/New_York")

        assertEquals(
            Instant.parse("2026-11-01T05:30:00Z"),
            seededAt(Instant.parse("2026-11-01T04:50:00Z"), zone).endsAt,
        )
    }

    @Test
    fun `keeps stepping on the half hour in a three-quarter-hour zone`() {
        // The seed being on a local half hour is what makes every later step
        // land on one too, since a step is exactly half an hour.
        val zone = ZoneId.of("Pacific/Chatham") // UTC+12:45
        val now = Instant.parse("2026-08-25T00:27:00Z")
        val condition = seededAt(now, zone).stepUp()

        assertEquals(
            0,
            condition.endsAt.atZone(zone).minute % 30,
        )
    }
}
