package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The distance a user reads, in whichever units their phone is set to
 * (`SPEC.md` §4.2).
 *
 * The conversion is pure and lives here; *choosing* the unit needs the
 * platform's measurement system and is covered on the Android side.
 */
class DistanceUnitTest {

    @Test
    fun `meters are reported as they are measured`() {
        assertEquals(200, DistanceUnit.METER.away(200.0))
        assertEquals(200, DistanceUnit.METER.away(200.4))
        assertEquals(201, DistanceUnit.METER.away(200.5))
    }

    @Test
    fun `feet are the international foot, not an approximation of it`() {
        // 0.3048 m exactly, so 200 m is 656.17 ft.
        assertEquals(656, DistanceUnit.FOOT.away(200.0))
        assertEquals(328, DistanceUnit.FOOT.away(100.0))
        assertEquals(3, DistanceUnit.FOOT.away(1.0))
    }

    @Test
    fun `how much further is always rounded up`() {
        // The engine's comparison is strict, so a reading that has *just* not
        // qualified must not print zero — it would contradict the verdict the
        // line exists to quote (Codex, PR #210).
        assertEquals(1, DistanceUnit.METER.toGo(0.0))
        assertEquals(1, DistanceUnit.METER.toGo(0.4))
        assertEquals(11, DistanceUnit.METER.toGo(10.1))
        assertEquals(1, DistanceUnit.FOOT.toGo(0.0))
        assertEquals(34, DistanceUnit.FOOT.toGo(10.1))
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
