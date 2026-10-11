package app.snoozemo.tile

import android.content.Context

/**
 * What the tile says after `Until you leave`: the same answer the ongoing
 * card's top row gives (SPEC.md §4.2, §4.6), in the tile's own words.
 *
 * `:tile` cannot format a distance itself — the unit, the rounding and the
 * reading all live in `:app` — so `:app` hands over the finished distance
 * ([distance], already `45 m` or `< 5 m`) and the tile only places it.
 */
data class TileReadout(val kind: Kind, val distance: String? = null) {

    enum class Kind {
        /** [distance] still to go before the snooze ends. */
        DISTANCE,

        /** The anchor's own network is associated, so no distance is measured. */
        ON_WIFI,

        /** One qualifying fix is waiting on its confirmation (SPEC.md §6.6). */
        LEAVING,
    }

    companion object {

        /**
         * The stored readout, if it belongs to the snooze the tile is showing.
         *
         * Stamped with the snooze's start rather than trusted as the current
         * one: the store is written by `:app` and read here at shade-open, and
         * a readout left behind by an earlier snooze — a process that died
         * before it could clear it — must not describe this one. An
         * unrecognized kind, or a distance with no number, reads as nothing,
         * so the tile falls back to plain `Until you leave` rather than
         * guessing.
         */
        internal fun decode(
            storedStartedAtMillis: Long,
            snoozeStartedAtMillis: Long,
            kindName: String?,
            distance: String?,
        ): TileReadout? {
            if (storedStartedAtMillis == 0L || storedStartedAtMillis != snoozeStartedAtMillis) return null
            val kind = Kind.entries.firstOrNull { it.name == kindName } ?: return null
            if (kind == Kind.DISTANCE && distance.isNullOrEmpty()) return null
            return TileReadout(kind, if (kind == Kind.DISTANCE) distance else null)
        }
    }
}

/**
 * Where `:app` leaves the readout for the tile to pick up at shade-open.
 *
 * A file rather than anything live, like the rest of the tile (see
 * [TileSnapshot]): the tile is painted when the shade opens and does not
 * repaint on presence updates, so the newest card's answer is all it needs.
 * It holds a formatted distance and which of three states applies, and nothing
 * about where the anchor or the phone is.
 */
class TileReadoutStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The readout for the snooze that started at [snoozeStartedAtMillis], if any. */
    fun read(snoozeStartedAtMillis: Long): TileReadout? = TileReadout.decode(
        storedStartedAtMillis = prefs.getLong(KEY_STARTED_AT, 0L),
        snoozeStartedAtMillis = snoozeStartedAtMillis,
        kindName = prefs.getString(KEY_KIND, null),
        distance = prefs.getString(KEY_DISTANCE, null),
    )

    /**
     * Records [readout] for the snooze that started at [snoozeStartedAtMillis];
     * `null` clears it.
     *
     * Skips the write when nothing changed, since the card it follows is
     * rebuilt on every reading. Does disk work on first use, so callers run it
     * off the arm path.
     *
     * @return whether the stored readout changed, so the caller can repaint a
     *   tile that is on screen now: it may have already repainted from the
     *   record before this write landed (Codex, PR #323).
     */
    fun write(snoozeStartedAtMillis: Long, readout: TileReadout?): Boolean {
        if (readout == null) return clear()
        val distance = if (readout.kind == TileReadout.Kind.DISTANCE) readout.distance else null
        if (prefs.getLong(KEY_STARTED_AT, 0L) == snoozeStartedAtMillis &&
            prefs.getString(KEY_KIND, null) == readout.kind.name &&
            prefs.getString(KEY_DISTANCE, null) == distance
        ) {
            return false
        }
        prefs.edit()
            .putLong(KEY_STARTED_AT, snoozeStartedAtMillis)
            .putString(KEY_KIND, readout.kind.name)
            .putString(KEY_DISTANCE, distance)
            .apply()
        return true
    }

    /**
     * Forgets any readout, for the card coming down.
     *
     * @return whether there was one to forget.
     */
    fun clear(): Boolean {
        if (!prefs.contains(KEY_KIND)) return false
        prefs.edit().clear().apply()
        return true
    }

    private companion object {
        const val FILE_NAME = "tile_readout"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_KIND = "kind"
        const val KEY_DISTANCE = "distance"
    }
}
