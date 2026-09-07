package app.snoozemo.core

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Which unit a distance is shown in (`SPEC.md` §4.2).
 *
 * Pure and in `:core` so the arithmetic is testable without Android, while
 * *choosing* between them needs the platform's own measurement system and stays
 * in `:app`. The label is a string resource rather than a constant here for the
 * ordinary reason: `m` and `ft` are translatable text, not code.
 */
enum class DistanceUnit {
    METER,
    FOOT,
    ;

    /** [meters] in this unit. */
    fun fromMeters(meters: Double): Double = when (this) {
        METER -> meters
        FOOT -> meters / METERS_PER_FOOT
    }

    /**
     * How far away to report — nearest whole unit.
     *
     * Whole units in both systems, which is deliberately the same precision the
     * meters-only version shipped with rather than a second decision made here.
     * Neither is meaningful below a fix's own accuracy (±10 m is a *good*
     * reading), and a foot is finer than a meter, so the imperial form is
     * nominally the noisier of the two. It does not read that way in practice:
     * the readout is replaced when a fix arrives, ninety seconds apart at the
     * fastest, and successive fixes differ by far more than one unit. Whether
     * both should round to something coarser is a separate question, left open
     * in `TODO.md`.
     */
    fun away(meters: Double): Int = fromMeters(meters).roundToInt()

    /**
     * How much further to report — rounded **up**, never below one.
     *
     * `Departure.qualifies` is a strict comparison, so a reading exactly on the
     * band has zero meters remaining while the engine still wants more; rounding
     * down would print `0 to go` beside a snooze that has not ended, which
     * contradicts the verdict this line exists to quote (Codex, PR #210).
     */
    fun toGo(meters: Double): Int = ceil(fromMeters(meters)).toInt().coerceAtLeast(1)

    /**
     * How uncertain to report the separation as — nearest whole unit, never
     * below one.
     *
     * Nearest rather than rounded up, because this is not a bound being
     * defended like [toGo]; it is a description of how sharp the reading is,
     * and inflating it would misdescribe a good fix. The floor exists because
     * `±0 m` claims a precision no location provider has, and a sub-meter
     * value at the foot scale would otherwise print exactly that.
     */
    fun uncertainty(meters: Double): Int =
        fromMeters(meters).roundToInt().coerceAtLeast(1)

    companion object {
        /** The international foot, exactly. */
        const val METERS_PER_FOOT: Double = 0.3048
    }
}
