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

    fun subtitle(context: Context): String? = when {
        !snoozing -> null
        timerOnly -> context.getString(R.string.tile_timer_only, remaining(context))
        else -> remaining(context)
    }

    fun stateDescription(context: Context): String = when {
        !snoozing -> context.getString(R.string.tile_state_off)
        else -> context.getString(R.string.tile_state_on, remaining(context))
    }

    /**
     * The countdown, formatted from resources rather than built here.
     *
     * The units, the word order and the `left` suffix are all copy, and copy
     * assembled in Kotlin is copy the translation PR cannot reach — it would be
     * English inside every locale.
     */
    private fun remaining(context: Context): String {
        val minutes = Duration.ofMillis(remainingMillis()).toMinutes().coerceAtLeast(1)
        val hours = minutes / 60
        return if (hours > 0) {
            context.getString(R.string.tile_remaining_hours, hours, minutes % 60)
        } else {
            context.getString(R.string.tile_remaining_minutes, minutes)
        }
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
         * Whether the stored mode is a settled claim that nothing is watching.
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
         * [TrackingMode.SETTLING] is the one case where dropping it is right,
         * because something genuinely is pending. Nothing is pending for a
         * value nobody can read.
         */
        internal fun claimsTimerOnly(
            stored: String?,
            startedAtMillis: Long,
            nowMillis: Long,
        ): Boolean =
            when (TrackingMode.entries.firstOrNull { it.name == stored }) {
                TrackingMode.DURATION_ONLY -> true
                // Watched, by something, so the countdown stands unqualified.
                TrackingMode.FULL, TrackingMode.WIFI_ONLY, TrackingMode.WIFI_GRACE -> false
                // The anchor has not landed *yet*. Not "nothing is watching" —
                // the absence of an answer, so the shade shows the countdown
                // without a qualifier rather than guessing at one. The ongoing
                // notification beside it says `Checking where you are`; saying
                // `Timer only` here would be the contradictory pair this change
                // exists to remove.
                //
                // Unless the claim has outlived the capture that wrote it. A
                // capture dies with its process and the record does not, and
                // this reader is the one that cannot tell: it reads the
                // preferences file directly, deliberately, because binding a
                // service to paint the shade would put IPC on the path that has
                // to feel instant. So the window does the telling instead —
                // and past it nothing is watching, which is exactly what the
                // qualifier says (Codex, PR #221).
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
