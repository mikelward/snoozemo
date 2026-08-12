package app.snoozemo.tile

import android.content.Context
import java.time.Duration
import java.time.Instant

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
        val minutes = Duration.between(Instant.now(), Instant.ofEpochMilli(capExpiresAtMillis))
            .toMinutes().coerceAtLeast(1)
        val hours = minutes / 60
        return if (hours > 0) {
            context.getString(R.string.tile_remaining_hours, hours, minutes % 60)
        } else {
            context.getString(R.string.tile_remaining_minutes, minutes)
        }
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
            )
        }
    }
}
