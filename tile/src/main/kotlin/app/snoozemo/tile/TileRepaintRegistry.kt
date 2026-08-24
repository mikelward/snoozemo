package app.snoozemo.tile

import java.util.concurrent.CopyOnWriteArrayList

/**
 * How a snooze that started or ended somewhere else reaches a tile that is on
 * screen right now.
 *
 * The tile used to be told by `TileService.requestListeningState()`, and that
 * call cannot work here: the platform documents it as applying "only to tiles
 * that have `META_DATA_ACTIVE_TILE` defined as true on their `TileService`
 * Manifest declaration, and will do nothing otherwise". Snoozemo's tile is
 * deliberately *not* an active tile — active tiles are bound only when they ask
 * to be or when a tap needs delivering, and this tile relies on the passive
 * "listening whenever the panel is open" contract for two things it cannot give
 * up: recomputing the countdown subtitle from the record every time the shade
 * opens, and warming the zen rule while a tap may be a moment away
 * (`SPEC.md` §4.1). So every refresh request was a documented no-op, and a
 * snooze ending under an open shade — the cap firing, `End now` on the ongoing
 * notification sitting inches below the tile, `+30 min`, a release from the app
 * screen — left the tile reading `Snoozing` until the shade was closed and
 * reopened.
 *
 * Nothing needed to cross a process boundary for that: `:tile` and `:app` are
 * one process, and a listening `TileService` is a live object in it. So the
 * state change is delivered straight to that object instead, and the tile
 * repaints from the record it already reads. A tile that is not listening is
 * not on screen, and `onStartListening` re-reads the record when it next is —
 * which is why there is nothing to queue for later.
 *
 * Deliberately a plain Kotlin object with no Android types, so the
 * register/notify contract is unit-testable; `TileService` and `Tile` are not.
 */
object TileRepaintRegistry {

    /**
     * Implemented by the tile itself. Callbacks arrive on whichever thread
     * changed the state, so an implementation that touches the tile is
     * responsible for getting itself onto the main thread — and for containing
     * its own failures, since a repaint that throws must not cost another
     * listener its repaint.
     */
    fun interface Repaint {
        fun onSnoozeRecordChanged()
    }

    private val listeners = CopyOnWriteArrayList<Repaint>()

    /**
     * Registered from `onStartListening` and dropped again in
     * `onStopListening`, so the set is empty — and [repaintNow] free — whenever
     * no tile is on screen. Idempotent: the platform may start listening twice
     * without an intervening stop, and a double registration would repaint
     * twice.
     */
    fun register(listener: Repaint) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: Repaint) {
        listeners.remove(listener)
    }

    /** Called after the snooze record has been written, never before. */
    fun repaintNow() {
        listeners.forEach { it.onSnoozeRecordChanged() }
    }
}
