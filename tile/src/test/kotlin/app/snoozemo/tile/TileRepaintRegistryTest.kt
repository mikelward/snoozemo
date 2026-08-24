package app.snoozemo.tile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The contract a live tile depends on to hear about a snooze that started or
 * ended somewhere else — the cap firing, the ongoing notification's actions, a
 * release from the app screen.
 *
 * `TileService` and `Tile` are not practically unit-testable, which is exactly
 * why the register/notify half lives in a plain object: the part that used to
 * be a documented no-op is now the part a test can pin.
 */
class TileRepaintRegistryTest {

    private val registered = mutableListOf<TileRepaintRegistry.Repaint>()

    @After
    fun tearDown() {
        // The registry is a singleton, so a listener left behind would leak
        // into the next test in the class.
        registered.forEach { TileRepaintRegistry.unregister(it) }
    }

    private fun counter(): IntArray {
        val counted = IntArray(1)
        val listener = TileRepaintRegistry.Repaint { counted[0]++ }
        registered += listener
        TileRepaintRegistry.register(listener)
        return counted
    }

    @Test
    fun `a listening tile is repainted when the record changes`() {
        val counted = counter()

        TileRepaintRegistry.repaintNow()

        assertEquals(1, counted[0])
    }

    @Test
    fun `a tile that stopped listening is not repainted`() {
        val counted = IntArray(1)
        val listener = TileRepaintRegistry.Repaint { counted[0]++ }
        TileRepaintRegistry.register(listener)
        TileRepaintRegistry.unregister(listener)

        TileRepaintRegistry.repaintNow()

        assertEquals(0, counted[0])
    }

    /**
     * The platform may start listening twice without an intervening stop, and
     * two registrations would paint the tile twice for one change.
     */
    @Test
    fun `registering twice still repaints once`() {
        val counted = IntArray(1)
        val listener = TileRepaintRegistry.Repaint { counted[0]++ }
        registered += listener
        TileRepaintRegistry.register(listener)
        TileRepaintRegistry.register(listener)

        TileRepaintRegistry.repaintNow()

        assertEquals(1, counted[0])
    }

    /**
     * The shade being closed is the common case, and a state change then must
     * not be an error — the next `onStartListening` re-reads the record.
     */
    @Test
    fun `a change with no tile listening is harmless`() {
        TileRepaintRegistry.repaintNow()
    }

    @Test
    fun `every listening tile is repainted`() {
        val first = counter()
        val second = counter()

        TileRepaintRegistry.repaintNow()

        assertEquals(1, first[0])
        assertEquals(1, second[0])
    }
}
