package app.snoozemo.tile

/**
 * What a tile tap paints and does, decided purely from the tile's last known
 * snoozing state.
 *
 * Split out of `SnoozeTileService.onClick` so the action/render decision is a
 * plain JVM unit — no `TileService`, no `Tile`, no Robolectric — that a test
 * can drive for both starting states (Codex, PR #93: an untested transition
 * risking a wrong action or an omitted field). [action] and [requestCode] are
 * the single source both the paint and the trampoline intent read, so the two
 * cannot disagree about which way the tap is going.
 *
 * The countdown subtitle is deliberately not part of this: a tap has no
 * duration or record to compute one from, so `onClick` leaves it null itself
 * rather than this type guessing one.
 */
internal data class TileOptimisticPaint(
    val action: String,
    val requestCode: Int,
    val active: Boolean,
    val labelRes: Int,
) {
    companion object {
        const val ACTION_ARM = "app.snoozemo.action.ARM"
        const val ACTION_END = "app.snoozemo.action.END"

        private const val REQUEST_ARM = 1
        private const val REQUEST_END = 2

        /** [currentlySnoozing] is the tile's last known state — see `SnoozeTileService.listening`. */
        fun forTap(currentlySnoozing: Boolean): TileOptimisticPaint {
            val snoozingNow = !currentlySnoozing
            return TileOptimisticPaint(
                action = if (currentlySnoozing) ACTION_END else ACTION_ARM,
                requestCode = if (currentlySnoozing) REQUEST_END else REQUEST_ARM,
                active = snoozingNow,
                labelRes = if (snoozingNow) R.string.tile_snoozing else R.string.tile_snooze_here,
            )
        }
    }
}
