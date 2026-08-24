package app.snoozemo.snooze

import android.util.Log
import app.snoozemo.tile.TileRepaintRegistry

/**
 * Tells the tile the snooze record changed, so the shade stops showing
 * `Snoozing` on a phone that isn't.
 *
 * Called **after** the record is written, never before: the tile renders from
 * the record, so a call ahead of the write would repaint the state it was
 * already showing.
 *
 * This used to be `TileService.requestListeningState`, which the platform
 * documents as applying "only to tiles that have `META_DATA_ACTIVE_TILE`
 * defined as true on their `TileService` Manifest declaration, and will do
 * nothing otherwise". Snoozemo's tile is not an active tile and cannot become
 * one without giving up the shade-open bind it relies on, so every one of these
 * calls was a documented no-op — see [TileRepaintRegistry] for the whole
 * argument and for what replaces it.
 */
object SnoozeTileBridge {
    fun refresh() {
        runCatching {
            TileRepaintRegistry.repaintNow()
        }.onFailure {
            // A stale tile is a cosmetic failure, not a silent phone — logged
            // rather than escalated, but never swallowed. It also must not
            // propagate: the callers are release and cap paths whose real job
            // is getting the phone's sound back.
            Log.w("SnoozeTileBridge", "Repainting the tile failed; the tile may be stale.", it)
        }
    }
}
