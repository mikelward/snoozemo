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
     * The finest step this fix has earned, as a rung of [rungs].
     *
     * **One rule: never show a step finer than your uncertainty.** A readout
     * quoting whole units off a fix whose combined confidence radius is ±18 m
     * claims a sharpness no location provider has, and the app's own numbers
     * then invite a precision the departure test does not act on.
     *
     * The rung is the smallest one **at least** as coarse as the uncertainty,
     * not the largest one below it (maintainer, 2026-09-09). The rule says
     * "not finer than", and `10` *is* finer than `±18` — so rounding the step
     * down would break the rule the ladder exists to keep. Erring coarse costs
     * the reader detail; erring fine costs them the truth.
     *
     * **The ladder has no top**, and an earlier draft of this that clamped to
     * one was wrong in the exact way this class exists to prevent (Codex, PR
     * #244). The `±` is printed *as* the step, so a clamp does not merely
     * round coarsely — it states an uncertainty smaller than the real one and
     * then snaps the separation at a resolution the fix has not got. It was
     * reachable, too: [Anchor.MAX_ANCHOR_ACCURACY_M] caps the anchor at 200 m
     * but `Departure.observe` passes the *fix's* accuracy through uncapped, so
     * an ordinary cell-tower fix combines past 500 m without anything
     * refusing it.
     *
     * **A ceiling is the one place this could still understate**, so nothing
     * reportable is allowed to reach it. The ladder stops climbing at
     * [CEILING_M] because an `Int` cannot go further, and above that the
     * fallback saturates instead of refusing — which is exactly the shape of
     * the three findings this class collected in review (Codex, PR #244: a
     * clamped top rung, a per-cycle terminator that discarded valid rungs, and
     * a ceiling that meant two different distances in the two units).
     *
     * Rather than patch a fourth boundary, the reading is refused before it
     * gets here: [DepartureObservation.isReportable] requires an uncertainty
     * the ladder can actually describe, so the saturation is unreachable from
     * anything a surface renders. It survives only as the guard that keeps
     * this function total for a caller who has not asked that question.
     */
    fun step(uncertaintyM: Double): Int {
        val uncertain = fromMeters(uncertaintyM)
        return rungs.firstOrNull { it >= uncertain } ?: rungs.last()
    }

    /**
     * [meters] snapped to a multiple of [step], or `null` when it does not
     * reach one — which the caller renders as a bound (`< 5 m`) rather than as
     * a number.
     *
     * The bound is what a floor would otherwise have to fake. `1 m` under a
     * ±25 m fix is not a small distance honestly reported, it is a rounding
     * artifact wearing a number, and `0 m` contradicts a snooze that has not
     * ended. `< 25 m` says the one true thing available: closer than this
     * reading can resolve.
     */
    fun snap(meters: Double, step: Int): Int? =
        ((fromMeters(meters) / step).roundToInt() * step).takeIf { it > 0 }
    // No non-finite guard here on purpose, and the reason is worth stating
    // because the obvious fix is wrong twice over. `roundToInt` throws on NaN,
    // and catching it to return `null` would collide with what `null` already
    // means — "closer than this can resolve" — so a NaN distance would render
    // `< 25 m`, a claim where the truth is an absence.
    //
    // Refusing the observation in `Departure.observe` instead is also wrong,
    // and its tests say so: PR #233 settled that a fix with a NaN coordinate
    // must still fall out inconclusive, still count toward degradation, and
    // still write its `distance=unknown` line, because a throw on the fix path
    // leaves the snooze armed with nothing running to end it. Deleting the
    // observation deletes all three.
    //
    // So the guard belongs where the *rendering* happens, which is where the
    // debug log already put its own (`SnoozeDebugLog`'s `isFinite` check), and
    // where an absence is a shape both surfaces already have.

    /**
     * How much further to report — snapped to [step] like everything else, and
     * **rounded down**.
     *
     * An earlier version of this left the deficit at whole units, on the
     * argument that `remainingM` already has the combined uncertainty
     * subtracted so snapping it to a rung drawn from that uncertainty would
     * subtract the vagueness twice. **That argument does not hold** (Codex, PR
     * #244): subtracting uncertainty and choosing a display resolution are
     * different operations, and quantizing a number for display does neither
     * to the other. `109 m to go` beside a `±25 m` showed a one-meter step off
     * a reading that cannot resolve one — the exact thing this class exists to
     * stop, left standing in the number that motivated it.
     *
     * **Down rather than to nearest**, which is the one way this differs from
     * [snap]. The reader's question here is "am I nearly there?", and rounding
     * up would answer it by over-stating what is left. That direction is the
     * wrong one for an app whose ambiguous states resolve toward *ending* the
     * snooze, so where a rung has to be chosen, the one that says "closer" is
     * the safe one.
     *
     * Below one whole step this returns `null` and the caller renders the
     * bound. That is also what keeps `0 m to go` off the screen beside a
     * snooze that has not ended — the reason the old `ceil` and its floor of
     * one existed (Codex, PR #210) — without printing a meter the fix has not
     * earned.
     */
    fun toGo(meters: Double, step: Int): Int? =
        ((fromMeters(meters) / step).toInt() * step).takeIf { it > 0 }

    /**
     * The rungs this unit rounds to, finest first.
     *
     * **Two ladders, not one converted into the other.** Snapping in meters and
     * then converting would produce step sizes no reader recognizes — 25 m is
     * 82 ft — so each system rounds to numbers it actually uses, and above the
     * floor the two happen to agree on 25/50/100, which are the roundest
     * numbers either has.
     *
     * The floors differ because a floor is a claim about resolution, not a
     * number to match. 5 m is about where a good fix stops being able to tell
     * one place from another; the foot equivalent is 15 ft (4.6 m), not 5 ft
     * (1.5 m), which would claim a sharpness the metric ladder explicitly
     * refuses (maintainer, 2026-09-09).
     */
    private val rungs: Sequence<Int> get() {
        // Each unit repeats one cycle of shapes per decade — `5 · 10 · 25`
        // then `50 · 100 · 250` then `500 · 1000 · 2500`, and `25 · 50 · 100`
        // then `250 · 500 · 1000` for feet. Generating it rather than listing
        // it is what makes the ladder unbounded, which is what makes the
        // guarantee absolute rather than true up to some number.
        val cycle = when (this) {
            METER -> listOf(5L, 10L, 25L)
            FOOT -> listOf(25L, 50L, 100L)
        }
        // Terminated **per rung**, not per cycle, and in `Long` so the
        // terminator is the only thing that stops it. Rejecting a whole cycle
        // because one member overflowed threw away the valid rungs in front of
        // it — twice, at two different boundaries (Codex, PR #244) — and an
        // `Int` multiply would reintroduce exactly that, since the cycle whose
        // last member overflows still holds smaller members that do not.
        //
        // [MAX_REPRESENTABLE_RUNG] is about the return type and nothing else:
        // it is not a distance, so it cannot mean two different distances in
        // the two units, which is what the ceiling it replaced did. What is
        // *reportable* is a separate question with a separate constant, asked
        // by `DepartureObservation.isReportable` — conflating the two is how a
        // shared number came to be compared against already-converted rungs.
        val decades = generateSequence(cycle) { previous -> previous.map { it * 10 } }
            .flatten()
            .takeWhile { it <= MAX_REPRESENTABLE_RUNG }
            .map { it.toInt() }
        // The metric floor is the first rung of its own cycle; the imperial one
        // is not, so it is prepended.
        return when (this) {
            METER -> decades
            FOOT -> sequenceOf(15) + decades
        }
    }

    companion object {
        /** The international foot, exactly. */
        const val METERS_PER_FOOT: Double = 0.3048

        /**
         * The largest rung that fits the `Int` this returns.
         *
         * **A statement about the return type, not about distance**, which is
         * the distinction the ladder kept failing on. The constant it replaced
         * was compared against rungs that had already been converted, so one
         * integer meant 10,000 km in meters and 3,048 km in feet: a US phone
         * saturated at a third of the documented ceiling while a metric phone
         * rounded the same fix correctly (Codex, PR #244). A number that
         * cannot be interpreted as a distance cannot be the wrong distance.
         *
         * Both units reach far past anything reportable before this bites —
         * a billion feet is 304,800 km — so it decides nothing a reader sees.
         */
        const val MAX_REPRESENTABLE_RUNG: Long = 1_000_000_000L

        /**
         * The widest uncertainty a readout will describe: ten thousand
         * kilometers, a quarter of the Earth's circumference.
         *
         * **Reportability, which is a different question from
         * representability** — and keeping them apart is the point. A ladder
         * has to stop somewhere, and above its last rung the only answers are
         * to saturate, which understates, or to decline. This is where
         * `DepartureObservation.isReportable` declines, chosen well inside
         * every unit's ladder so a rung always exists for anything it admits.
         * That is what makes the saturation unreachable from a reading rather
         * than merely unlikely.
         */
        const val CEILING_M: Double = 10_000_000.0
    }
}
