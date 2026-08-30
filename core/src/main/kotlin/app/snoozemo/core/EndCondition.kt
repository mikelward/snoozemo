package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The arithmetic behind the end-condition sheet (SPEC.md §4.4) — the seeded
 * time, what `−` and `+` do to it, and where it may not go.
 *
 * Pure, and deliberately knows nothing about a running snooze. The sheet is
 * rendered by the trampoline *while the arm is still in flight* (§6.9: nothing
 * may wait on the record), so the one input it can count on is what time it is.
 * The service clamps the committed value against the record it actually has —
 * see [SnoozeController.lowerCapTo] — and this is what the user is shown and
 * steps through in the meantime.
 *
 * The two are not redundant. This one keeps the sheet's own floor and ceiling
 * honest so `−` stops offering times the service would refuse; the service's
 * keeps a value chosen against a stale reading from outliving it.
 */
data class EndCondition(
    /** The absolute instant the chosen row would end the snooze at. */
    val endsAt: Instant,
    /** The earliest [endsAt] may be stepped down to. */
    val floor: Instant,
    /** The latest [endsAt] may be stepped up to — the §7 backstop. */
    val ceiling: Instant,
) {
    /** Whether `−` has anywhere left to go. */
    val canStepDown: Boolean get() = endsAt.minus(STEP) >= floor

    /** Whether `+` has anywhere left to go. */
    val canStepUp: Boolean get() = endsAt.plus(STEP) <= ceiling

    /**
     * One tap of `−`, or this unchanged when the floor is already reached.
     *
     * Deliberately does *not* clamp a step that would undershoot down onto the
     * floor: the floor is 30 minutes from now and the seed sits on a half hour,
     * so clamping would produce a ragged time — 13:42 from a 14:00 seed at
     * 13:12 — out of a control whose whole promise is half-hour steps. A button
     * with nowhere to go reports that through [canStepDown] instead.
     */
    fun stepDown(): EndCondition = if (canStepDown) copy(endsAt = endsAt.minus(STEP)) else this

    /** One tap of `+`, or this unchanged when the ceiling is already reached. */
    fun stepUp(): EndCondition = if (canStepUp) copy(endsAt = endsAt.plus(STEP)) else this

    companion object {
        /** The `−` / `+` step, and the unit `+30 min` already extends by (§4.3). */
        val STEP: Duration = Duration.ofMinutes(30)

        /** What the sheet opens on, before any tap: one hour out (SPEC.md §4.4). */
        val SEED_AHEAD: Duration = Duration.ofHours(1)

        /**
         * The sheet as it first appears at [now], read in [zone].
         *
         * The time is seeded an hour out and **rounded to the nearest half
         * hour** — a tap at 13:12 offers 14:00, not 14:12. §4.4 is explicit
         * about why: a ragged time looks like a bug and invites pointless
         * fiddling, and rounding is also what makes every later step land on a
         * half hour, which is where meetings end.
         *
         * The rounding happens in [zone] — the user's own — because the half
         * hours the user sees are their local ones. India (UTC+05:30) sits on
         * the same 30-minute grid as UTC and would survive rounding on either,
         * but Nepal (UTC+05:45), Eucla (+08:45) and the Chatham Islands
         * (+12:45) do not: rounded against UTC, a tap there offers 14:15 and
         * every step after it stays on :15 and :45, which is exactly the
         * ragged time §4.4 rules out.
         *
         * Rounding can pull the seed *below* the hour — 13:50 rounds 14:50 down
         * to 14:30 — but never below the floor, since the floor is only 30
         * minutes out and the seed is at least 45. The ceiling is the harder
         * edge: a snooze already near its backstop (a cap the user extended, or
         * a sheet somehow reached late) can have less than an hour above it, so
         * the seed is clamped down to [ceiling] rather than opening on a time
         * the service would refuse.
         */
        /**
         * The latest time the sheet may offer over [snooze]: the cap that
         * snooze actually carries, not a fresh [ActiveSnooze.DEFAULT_CAP] from
         * now.
         *
         * They coincide on a fresh arm and part company on a duplicate one —
         * a second arm onto a running snooze is answered by *keeping* the one
         * already running (SPEC.md §4.2), so the record the sheet is about can
         * have started long ago. Seeded against a constant, the sheet would
         * offer an hour over a snooze with ten minutes left.
         *
         * Takes the record rather than reading it, so each caller supplies it
         * from wherever it already has one — a load on the tile path, the copy
         * the app screen keeps warm — and neither puts a disk wait where this
         * is decided (Codex, PR #150 discussion; principle 3).
         */
        fun ceilingFor(snooze: ActiveSnooze?, now: Instant): Instant =
            snooze?.capExpiresAt ?: now.plus(ActiveSnooze.DEFAULT_CAP)

        /**
         * Whether there is a snooze **and** a time the sheet could set on it.
         *
         * The record alone is not enough. A cap already closer than
         * [ActiveSnooze.MIN_CAP] leaves nothing to choose — the service
         * declines anything inside that floor, and the only value above it is
         * later than the cap, which the service honors by doing nothing and
         * reports as applied. Either way the sheet would be a screen the user
         * cannot answer.
         *
         * Fails closed on a missing record: no sheet over a correctly armed
         * snooze is exactly what the setting being off would have given.
         */
        fun offersAChoice(snooze: ActiveSnooze?, now: Instant): Boolean {
            val cap = snooze?.capExpiresAt ?: return false
            return cap.isAfter(now.plus(ActiveSnooze.MIN_CAP))
        }

        fun seededAt(now: Instant, ceiling: Instant, zone: ZoneId): EndCondition {
            val floor = now.plus(ActiveSnooze.MIN_CAP)
            val seed = roundToHalfHour(now.plus(SEED_AHEAD), zone)
            return EndCondition(
                // **`coerceAtMost(ceiling)` last, and the order is the point.**
                // A ceiling below the floor is producible — a duplicate arm
                // onto a snooze with ten minutes left gives one — and clamping
                // up to the floor afterward put `endsAt` *past the cap*. The
                // service then answers `APPLIED`, correctly, because a chosen
                // time no earlier than the cap is honored by doing nothing: the
                // row read 12:30 and the snooze ended at 12:10 (Codex, PR #118).
                //
                // Clamped this way the sheet can show a time below its own
                // floor instead, which the service declines rather than wrongly
                // accepts. Neither is a good offer, and the trampoline's own
                // gate is what keeps the sheet away from this case at all — but
                // between a displayed time that lies and one that is refused,
                // the refusal is the one the user can see.
                endsAt = seed.coerceAtLeast(floor).coerceAtMost(ceiling),
                floor = floor,
                ceiling = ceiling,
            )
        }

        /**
         * [instant] moved to whichever :00 or :30 is closest **in [zone]**,
         * halves rounding up.
         *
         * The arithmetic is done on the local wall clock rather than on epoch
         * millis, because a 30-minute grid laid over the epoch only lines up
         * with the user's own :00 and :30 in zones whose offset is a whole
         * half hour. The three-quarter-hour zones are the ones that break, and
         * they are real places, not a theoretical case.
         *
         * Truncation is to the minute first: an [Instant] carries seconds and
         * nanos, and "13:59:59.9 is nearer 14:00 than 13:30" has to survive
         * them. Without that, a seed captured mid-second rounds by an
         * arithmetic that never sees the sub-minute part and lands a whole
         * step away.
         *
         * Rounding a local time back to an instant can land in a daylight-saving
         * *gap*, where [java.time.LocalDateTime.atZone] moves it forward past
         * the gap and off the grid. That is the one time the seed is ragged, it
         * is an hour a year, and [seededAt]'s floor and ceiling still hold. The
         * *overlap* — the same hour run twice — is handled rather than
         * tolerated; see the offset resolution below.
         */
        private fun roundToHalfHour(instant: Instant, zone: ZoneId): Instant {
            val local = instant.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
            val below = local.minusMinutes((local.minute % 30).toLong())
            val above = below.plusMinutes(30)
            // Every *instant* the two neighboring wall clocks can name here.
            // Usually one each. A time inside a daylight-saving fall-back names
            // two, since that hour runs twice; one inside a spring-forward gap
            // names none.
            val candidates = listOf(below, above).flatMap { candidate ->
                zone.rules.getValidOffsets(candidate).map { candidate.toInstant(it) }
            }
            // Nearest to the target, ties to the later one — which is "halves
            // round up" stated over instants rather than over the wall clock,
            // and reduces to exactly that on the 364 ordinary days.
            //
            // Choosing among instants rather than resolving one nominal wall
            // clock is what makes the transitions come out right, and it
            // replaces two special cases with one rule (Codex, PR #118). At a
            // fall-back, `LocalDateTime.atZone` always picks the earlier of the
            // two offsets: for a tap inside the repeated hour that is an offset
            // already gone by, and for a tap just before it the rounded-up wall
            // clock resolves past the transition — an hour further out than the
            // one asked for, when the other side was twenty minutes away.
            return candidates.minWithOrNull(
                compareBy(
                    { abs(it.toEpochMilli() - instant.toEpochMilli()) },
                    { -it.toEpochMilli() },
                ),
            // Both neighbors inside the gap, which a 30-minute step can manage
            // against a 60-minute one. `atZone` moves past the gap, which is
            // the same ragged-for-an-hour-a-year outcome the KDoc names.
            ) ?: above.atZone(zone).toInstant()
        }
    }
}
