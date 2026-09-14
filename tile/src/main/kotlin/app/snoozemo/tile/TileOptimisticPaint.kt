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

        /**
         * [currentlySnoozing] is the tile's last known state — see
         * `SnoozeTileService.listening`. [chooserModeOn] is whether "ask when to
         * unsnooze" is on ([app.snoozemo.core.ChooserMode]); when it is, an arm
         * tap opens the chooser and arms *nothing* (SPEC.md §4.4), so the tile
         * must not paint itself Snoozing over a snooze that has not started — and
         * may never, if the chooser is dismissed. It stays inactive; the arm the
         * user commits from a chooser row reconciles on the next `onStartListening`.
         * An end tap, and an arm tap with the chooser off, are unchanged.
         */
        fun forTap(currentlySnoozing: Boolean, chooserModeOn: Boolean): TileOptimisticPaint {
            if (!currentlySnoozing && chooserModeOn) {
                return TileOptimisticPaint(
                    action = ACTION_ARM,
                    requestCode = REQUEST_ARM,
                    active = false,
                    labelRes = R.string.tile_snooze_here,
                )
            }
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
