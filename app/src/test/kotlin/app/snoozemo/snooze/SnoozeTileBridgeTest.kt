package app.snoozemo.snooze

import app.snoozemo.tile.TileRepaintRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bug this pins: a snooze that ended anywhere other than the tile — the cap
 * firing, `End now` or `+30 min` on the ongoing notification, a release from
 * the app screen — left the tile reading `Snoozing` for as long as the shade
 * stayed open. The refresh went out as `TileService.requestListeningState`,
 * which the platform documents as doing nothing for a tile that is not declared
 * `META_DATA_ACTIVE_TILE`, and Snoozemo's tile deliberately isn't one.
 *
 * A no-op is invisible from inside the app, so nothing could have caught it.
 * This asserts the thing that was missing: a refresh reaches a tile that is
 * listening right now.
 */
class SnoozeTileBridgeTest {

    private val listeners = mutableListOf<TileRepaintRegistry.Repaint>()

    @After
    fun tearDown() {
        listeners.forEach { TileRepaintRegistry.unregister(it) }
    }

    private fun listening(): IntArray {
        val counted = IntArray(1)
        val listener = TileRepaintRegistry.Repaint { counted[0]++ }
        listeners += listener
        TileRepaintRegistry.register(listener)
        return counted
    }

    @Test
    fun `a refresh reaches a tile that is listening`() {
        val counted = listening()

        SnoozeTileBridge.refresh()

        assertEquals(1, counted[0])
    }

    /**
     * The shade is usually closed when a snooze ends, and the release path must
     * not throw on its way to getting the phone's sound back.
     */
    @Test
    fun `a refresh with no tile listening is harmless`() {
        SnoozeTileBridge.refresh()
    }
}
