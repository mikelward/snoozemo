package app.snoozemo.tile

/**
 * Whether a tile tap is a new intent or a quick second tap on the last one
 * (SPEC.md §7; maintainer, 2026-10-13).
 *
 * The tile flips meaning under the finger: one tap starts a snooze, the next
 * ends it, and the tile paints the flip before the service has answered. So a
 * fast double tap, meant as one, would arm a snooze and end it at once — one
 * candidate for the snoozes a device capture (2026-09-09) showed ending
 * `MANUAL` seconds after they armed (`TODO.md`). A tap within [SETTLE_MS] of
 * the last one the tile acted on is ignored, so it lands as one intent.
 *
 * **The tile only.** `End now` on the notification and in the app mean only
 * "end", so a tap on either is deliberate, and they stay instant (§7). A tile
 * tap ignored here leaves the snooze the first tap asked for, with the tile
 * already painted to say so; the next tap after the window acts as usual.
 *
 * Measured from the last *accepted* tap, so tapping repeatedly cannot hold
 * the tile inert. Process-wide and in memory: a tap is never slowed by a
 * read, and a process that died since forgets, which accepts the next tap —
 * the side that acts. Elapsed realtime, so a wall-clock change cannot hold the
 * window open either.
 */
internal class TileTapGate(private val settleMs: Long = SETTLE_MS) {

    private var lastAcceptedAtMs: Long? = null

    /** Whether a tap at [nowMs] (elapsed realtime) acts; records it if so. */
    @Synchronized
    fun accept(nowMs: Long): Boolean {
        val last = lastAcceptedAtMs
        if (last != null && nowMs >= last && nowMs - last < settleMs) return false
        lastAcceptedAtMs = nowMs
        return true
    }

    companion object {
        /**
         * How long after a tile tap a second one is read as part of it
         * (maintainer, 2026-10-13): two taps meant as one land about 300 ms
         * apart, and anything longer only ignores taps the user meant.
         */
        const val SETTLE_MS = 500L
    }
}
