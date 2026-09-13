package app.snoozemo.tile

import android.content.Context
import android.os.SystemClock
import app.snoozemo.core.ClockReading
import app.snoozemo.core.TrackingMode
import java.time.Duration

/**
 * What the tile needs to render, read straight from the persisted snooze record.
 *
 * A file read rather than a bound service on purpose: `onStartListening` runs
 * every time the shade opens, and binding for two fields would put IPC on the
 * path that has to feel instant. The file is small and already in the page cache.
 */
internal data class TileSnapshot(
    val snoozing: Boolean,
    val capExpiresAtMillis: Long,
    /**
     * Whether to qualify the countdown as timer-only — false both when the
     * snooze *is* tracked and when the tile has no business claiming either
     * way yet. See [claimsTimerOnly].
     */
    val timerOnly: Boolean,
    /**
     * The clock offset the record was written under, or null if it carries
     * none — see `ActiveSnooze.bootReference`.
     *
     * The tile reads the record directly rather than through the store, so it
     * has to carry this too: without it the countdown is pure wall-clock
     * arithmetic, and after a backwards clock change the shade would show hours
     * left on a snooze whose cap is about to fire.
     */
    val bootReference: Long? = null,
) {

    /**
     * Whether the shade shows the cap countdown at all: only when a snooze is
     * running *and* [timerOnly] — a settled timer with no armed event exit. On a
     * watched (departure or motion) snooze the cap is a passive eight-hour
     * failsafe, so the shade carries no time rather than fronting a deadline the
     * snooze does not expect to reach (SPEC.md §4.2).
     *
     * This is the tile's read of `ActiveSnooze.capIsEffectiveEnd`, the same
     * question the status line and the ongoing notification gate their countdown
     * on — they agree for a settled record, with [claimsTimerOnly] resolving the
     * transient settling window here from the persisted record, since the tile
     * has no live controller to ask.
     *
     * A pure val so the decision is unit-tested without a `Context`; [subtitle]
     * and [stateDescription] map it to strings.
     */
    internal val showsCountdown: Boolean get() = snoozing && timerOnly

    fun subtitle(context: Context): String? =
        if (showsCountdown) context.getString(R.string.tile_timer_only, remaining(context)) else null

    fun stateDescription(context: Context): String = when {
        !snoozing -> context.getString(R.string.tile_state_off)
        showsCountdown -> context.getString(R.string.tile_state_on, remaining(context))
        // A watched snooze drops the countdown here too; TalkBack gets the plain
        // on-state, the same rendering the optimistic paint already uses when it
        // has no countdown to voice.
        else -> context.getString(R.string.tile_snoozing)
    }

    /**
     * The countdown, formatted from resources rather than built here.
     *
     * The units, the word order and the `left` suffix are all copy, and copy
     * assembled in Kotlin is copy the translation PR cannot reach — it would be
     * English inside every locale.
     */
    private fun remaining(context: Context): String {
        // The `tile_remaining_hours` resource always, passing the hours field
        // even at zero — so it renders "0h 45m left" rather than "45m left"
        // (maintainer, 2026-09-13), the same always-hours form the main screen
        // uses, so the shade and the app never state the remaining time two ways.
        val minutes = Duration.ofMillis(remainingMillis()).toMinutes().coerceAtLeast(1)
        return context.getString(R.string.tile_remaining_hours, minutes / 60, minutes % 60)
    }

    /**
     * The smaller of what the two clocks say is left, floored at zero — the same
     * rule and the same reasoning as `ActiveSnooze.remaining`, which is what the
     * rest of the app judges the cap by. The tile has no record object to call
     * it on, so the arithmetic is repeated rather than the answer diverging.
     */
    private fun remainingMillis(): Long {
        val reading = ClockReading(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        val byWallClock = capExpiresAtMillis - reading.wallMillis
        val reference = bootReference ?: return byWallClock.coerceAtLeast(0L)
        val byUptime = (capExpiresAtMillis - reference) - reading.uptimeMillis
        return minOf(byWallClock, byUptime).coerceAtLeast(0L)
    }

    companion object {

        /**
         * Whether the record is a settled claim that nothing is watching.
         *
         * **Any event exit at all disqualifies the claim.** `Timer only` says
         * nothing but the clock can end this snooze, so the movement exit
         * counts as much as departure does — and a time chosen *then*
         * `Until I move` is a state this change made newly reachable, which
         * the tile alone rendered as `Timer only` while the notification and
         * the screen both named the movement exit (Codex, PR #267).
         *
         * **The user's choice first, then the mode.** `ends_on_departure` is
         * the tile's half of [app.snoozemo.core.ActiveSnooze.effectiveMode]:
         * a snooze narrowed to its timer keeps the capability `mode` it was
         * tracking with, deliberately, so `Until I leave` can put the exit
         * back — and reading the mode alone therefore left the shade showing
         * an unqualified countdown, as though a departure were still watched,
         * while the screen and the notification said `Timer only` (Codex,
         * PR #267). Missing reads as `true`, matching the record's own
         * default: a record written before the flag existed comes from a build
         * where leaving always ended a snooze.
         *
         * Parsed to the enum and decided by an exhaustive `when` rather than
         * compared to the string `"FULL"`, which is how the tile came to
         * report `Timer only` for a mode that means the opposite. The mode is
         * persisted as text, so the compiler cannot see this reader from
         * [TrackingMode]'s declaration: a member added there used to reach the
         * shade as a silent misreading, while the screen and the notification
         * — which switch on the enum — failed to compile until they were
         * taught. Parsing first puts this reader under the same rule, so the
         * next member is a build error here too (Codex, PR #221).
         *
         * A missing or unrecognized value degrades to timer-only, matching
         * `ActiveSnoozeStore`'s own fallback for the same input — a record
         * written before the mode was stored, or by a newer build than this
         * tile. Reading it as "no claim" was wrong twice over: the store
         * already answers `DURATION_ONLY`, so the app and the shade would
         * contradict each other over one record, and the tile has only two
         * renderings — dropping the qualifier is not silence, it is the
         * tracked-looking one (Codex, PR #221).
         *
         * A settling mode is the one case where dropping it is right, because
         * something genuinely is pending. Nothing is pending for a value nobody
         * can read.
         */
        internal fun claimsTimerOnly(
            stored: String?,
            startedAtMillis: Long,
            nowMillis: Long,
            endsOnDeparture: Boolean = true,
            endsOnMotion: Boolean = false,
        ): Boolean =
            if (endsOnMotion) {
                // An armed movement exit ends this snooze on something other
                // than the clock, whatever the mode says and whatever the
                // departure choice was. Asked first, because it is the one
                // answer no other input can override.
                false
            } else if (!endsOnDeparture) {
                // Nothing pending and nothing to wait for: the user answered
                // this question themselves, so no mode — a settling one
                // included — can soften it.
                true
            } else when (TrackingMode.entries.firstOrNull { it.name == stored }) {
                TrackingMode.DURATION_ONLY -> true
                // Watched, by something, so the countdown stands unqualified.
                TrackingMode.FULL, TrackingMode.WIFI_ONLY, TrackingMode.WIFI_GRACE -> false
                // The anchor has not landed *yet*, so this is not a settled
                // `Timer only` claim: `false`, which drops the tile countdown
                // entirely (`showsCountdown = snoozing && timerOnly`). That is
                // the same as every other surface while the cap is the failsafe
                // rather than the plan — during settling the cap is
                // `startedAt + DEFAULT_CAP`, the backstop, not a chosen deadline,
                // so fronting a countdown to it would front a plan the snooze has
                // not made (SPEC.md §4.2, §491-494). The ongoing notification
                // beside the tile names what the arm is waiting on. (This reader
                // used to return an unqualified countdown here; the
                // drop-the-failsafe decision made the three surfaces agree, and a
                // tile countdown during settling was the one that no longer did —
                // Codex, PR #278.)
                //
                // Past the window the claim is settled: a capture dies with its
                // process while the record does not, and this reader — reading the
                // preferences file directly so the shade stays instant — cannot
                // tell a live capture from a dead one except by the window. Past
                // it, nothing is watching, which is exactly `Timer only` (Codex,
                // PR #221).
                TrackingMode.SETTLING ->
                    !TrackingMode.settlingStillStands(startedAtMillis, nowMillis)
                null -> true
            }

        fun read(context: Context): TileSnapshot {
            val prefs = context.getSharedPreferences("active_snooze", Context.MODE_PRIVATE)
            val capExpiresAt = prefs.getLong("cap_expires_at", 0L)
            // A record whose snooze ended but whose erase failed is still on
            // disk, marked. The tile must read that as "not snoozing" like
            // everything else, or it offers `End now` for a snooze that is over.
            val released = prefs.getBoolean("released", false)
            return TileSnapshot(
                snoozing = capExpiresAt != 0L && !released,
                capExpiresAtMillis = capExpiresAt,
                timerOnly = claimsTimerOnly(
                    stored = prefs.getString("mode", null),
                    startedAtMillis = prefs.getLong("started_at", 0L),
                    nowMillis = System.currentTimeMillis(),
                    endsOnDeparture = prefs.getBoolean("ends_on_departure", true),
                    endsOnMotion = prefs.getBoolean("ends_on_motion", false),
                ),
                bootReference = if (prefs.contains("boot_reference")) {
                    prefs.getLong("boot_reference", 0L)
                } else {
                    null
                },
            )
        }
    }
}
