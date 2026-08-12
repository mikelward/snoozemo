package app.snoozemo.tile

import android.content.Context
import android.os.SystemClock
import app.snoozemo.core.ClockReading
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
    val tracked: Boolean,
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
        !tracked -> context.getString(R.string.tile_timer_only, remaining(context))
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
                tracked = prefs.getString("mode", null) == "FULL",
                bootReference = if (prefs.contains("boot_reference")) {
                    prefs.getLong("boot_reference", 0L)
                } else {
                    null
                },
            )
        }
    }
}
