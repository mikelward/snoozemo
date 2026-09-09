package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distance a user reads, in whichever units their phone is set to
 * (`SPEC.md` §4.2).
 *
 * The conversion and the rounding are pure and live here; *choosing* the unit
 * needs the platform's measurement system and is covered on the Android side.
 */
class DistanceUnitTest {

    @Test
    fun `the step is never finer than the uncertainty`() {
        // The rule, in both directions. A rung below the uncertainty would be
        // a step the fix has not earned; the next one up is the smallest that
        // keeps the promise.
        assertEquals(25, DistanceUnit.METER.step(18.0))
        assertEquals(10, DistanceUnit.METER.step(10.0))
        assertEquals(25, DistanceUnit.METER.step(10.1))
        // A very sharp fix still stops at the floor: below it is finer than a
        // location provider resolves, whatever it claims.
        assertEquals(5, DistanceUnit.METER.step(0.0))
        assertEquals(5, DistanceUnit.METER.step(4.9))
    }

    @Test
    fun `the imperial floor is a claim about resolution, not a converted number`() {
        // 15 ft is 4.6 m — the foot value nearest the 5 m floor. 5 ft would be
        // 1.5 m, sharper than the metric ladder explicitly refuses to claim.
        assertEquals(15, DistanceUnit.FOOT.step(0.0))
        assertTrue(
            "15 ft must be about as coarse as the 5 m floor",
            DistanceUnit.FOOT.fromMeters(5.0) in 15.0..17.0,
        )
        // Above the floor the two ladders agree, so one rule serves both.
        assertEquals(25, DistanceUnit.FOOT.step(6.0))
        assertEquals(50, DistanceUnit.FOOT.step(10.0))
    }

    @Test
    fun `the ladder keeps climbing rather than clamping`() {
        // The regression this pins: clamping to a top rung would print `±500 m`
        // for a fix that is nothing of the sort, and then snap the separation
        // at 500 m — a resolution it has not got. It is reachable, because the
        // anchor is capped at 200 m but the fix's own accuracy is not, so an
        // ordinary cell-tower fix combines past 500 m (Codex, PR #244).
        assertEquals(500, DistanceUnit.METER.step(400.0))
        assertEquals(1_000, DistanceUnit.METER.step(538.0))
        assertEquals(1_000, DistanceUnit.METER.step(800.0))
        assertEquals(2_500, DistanceUnit.METER.step(1_200.0))
        assertEquals(5_000, DistanceUnit.METER.step(5_000.0))
        // And in feet, where 305 m is 1000.6 ft — just past a rung.
        assertEquals(2_500, DistanceUnit.FOOT.step(305.0))
    }

    @Test
    fun `the ladder reaches its documented ceiling rather than stopping a cycle early`() {
        // The regression: terminating per *cycle* rather than per rung threw
        // away the valid rungs ahead of the one that overflowed, so metric
        // stopped at 2,500 km and everything above it understated — the same
        // failure as the clamp, at a new boundary (Codex, PR #244).
        assertEquals(5_000_000, DistanceUnit.METER.step(3_000_000.0))
        assertEquals(10_000_000, DistanceUnit.METER.step(6_000_000.0))
        assertEquals(10_000_000, DistanceUnit.METER.step(9_999_999.0))
    }

    @Test
    fun `the ceiling is the same distance in both units, not the same number`() {
        // It used to be one integer compared against rungs already converted,
        // so it meant 10,000 km in meters but 3,048 km in feet — a US phone
        // saturated at a third of the documented ceiling and understated
        // everything above it while a metric phone rounded the same fix
        // correctly (Codex, PR #244). A ceiling two scales share has to be
        // expressed in the thing they share.
        // Every unit must have a real rung for anything `isReportable` admits,
        // right up to the ceiling — otherwise the saturation is reachable in
        // one unit and not the other, which is the bug.
        for (unit in DistanceUnit.entries) {
            for (fraction in listOf(0.3, 0.5, 0.9, 1.0)) {
                val uncertain = DistanceUnit.CEILING_M * fraction
                assertTrue(
                    "$unit must describe ±$uncertain m rather than saturate",
                    unit.step(uncertain) >= unit.fromMeters(uncertain),
                )
            }
        }
        // 3,048 km is where the old, unconverted ceiling put feet, so this is
        // the case that used to understate on a US phone and not a metric one.
        assertTrue(
            DistanceUnit.FOOT.step(5_000_000.0) >= DistanceUnit.FOOT.fromMeters(5_000_000.0),
        )
    }

    @Test
    fun `the ladder terminates on a value that is not a fix at all`() {
        // Far past any reading about a place. What matters is that it answers
        // rather than multiplying an Int until it wraps: a negative rung or a
        // hang would be worse than the understatement this replaced.
        val absurd = DistanceUnit.METER.step(1e12)

        assertTrue("a rung must stay positive", absurd > 0)
        assertTrue(
            "and must not exceed what the return type can hold",
            absurd.toLong() <= DistanceUnit.MAX_REPRESENTABLE_RUNG,
        )
    }

    @Test
    fun `every rung is at least as coarse as the uncertainty that chose it`() {
        // The guarantee itself, swept rather than sampled — including across
        // the decade boundaries the generated ladder introduced.
        var uncertain = 0.5
        while (uncertain < 8_000_000.0) {
            for (unit in DistanceUnit.entries) {
                val step = unit.step(uncertain)
                assertTrue(
                    "$unit step $step is finer than ±$uncertain m",
                    step >= unit.fromMeters(uncertain),
                )
            }
            uncertain *= 1.37
        }
    }

    @Test
    fun `a separation is snapped to the step`() {
        assertEquals(150, DistanceUnit.METER.snap(142.0, step = 25))
        assertEquals(150, DistanceUnit.METER.snap(150.0, step = 25))
        assertEquals(200, DistanceUnit.METER.snap(190.0, step = 25))
        // And in feet, off the same meters — the snapping happens after the
        // conversion, so the step is a number a US reader recognizes rather
        // than 82 ft, which is what snapping in meters first would produce.
        assertEquals(500, DistanceUnit.FOOT.snap(150.0, step = 25))
    }

    @Test
    fun `a separation below one step is a bound, not a number`() {
        // `0 m away` reads as a bug and `1 m away` claims what a ±25 m fix
        // cannot; null is what the caller renders as `< 25 m`.
        assertNull(DistanceUnit.METER.snap(8.0, step = 25))
        assertNull(DistanceUnit.METER.snap(0.0, step = 5))
        // Just far enough to round to one whole step is a number again.
        assertEquals(25, DistanceUnit.METER.snap(13.0, step = 25))
    }

    @Test
    fun `how much further rides the same step, rounded down`() {
        // It used to be left at whole units, on the argument that `remainingM`
        // already has the uncertainty subtracted. That argument confused
        // subtracting uncertainty with choosing a display resolution, and left
        // `109 m to go` beside a `±25 m` — a one-meter step off a reading that
        // cannot resolve one (Codex, PR #244).
        assertEquals(100, DistanceUnit.METER.toGo(109.0, step = 25))
        assertEquals(100, DistanceUnit.METER.toGo(124.9, step = 25))
        assertEquals(125, DistanceUnit.METER.toGo(125.0, step = 25))
        // Down, not to nearest — the one place this differs from `snap`. The
        // reader is asking "am I nearly there?", and rounding up answers by
        // over-stating what is left, which is the wrong direction for an app
        // whose ambiguity resolves toward ending the snooze.
        assertEquals(25, DistanceUnit.METER.toGo(44.0, step = 25))
    }

    @Test
    fun `a deficit below one step is a bound, never zero`() {
        // The engine's comparison is strict, so a reading exactly on the band
        // has zero meters remaining while the engine still wants more; `0 to
        // go` would contradict the verdict the line exists to quote (Codex, PR
        // #210). The bound is what keeps that off the screen now, in place of
        // the old floor of one — which printed a meter the fix had not earned.
        assertNull(DistanceUnit.METER.toGo(0.0, step = 25))
        assertNull(DistanceUnit.METER.toGo(24.9, step = 25))
        assertNull(DistanceUnit.FOOT.toGo(0.0, step = 15))
    }

    @Test
    fun `feet are the international foot, not an approximation of it`() {
        // 0.3048 m exactly, so 200 m is 656.17 ft.
        assertEquals(656.17, DistanceUnit.FOOT.fromMeters(200.0), 0.01)
        assertEquals(328.08, DistanceUnit.FOOT.fromMeters(100.0), 0.01)
    }

    @Test
    fun `the two units describe the same distance`() {
        // The readout must not disagree with itself across a locale change: the
        // same reading is the same place, said twice.
        val meters = 161.0

        assertEquals(
            DistanceUnit.METER.fromMeters(meters) / DistanceUnit.METERS_PER_FOOT,
            DistanceUnit.FOOT.fromMeters(meters),
            0.000_001,
        )
    }
}
