package app.snoozemo.tile

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one fact this store keeps, and the direction it must fail in.
 *
 * It lives in `:app`'s test source set rather than `:tile`'s, which has no test
 * dependencies of its own — `:app` already has Robolectric, and adding a second
 * Android test harness to hold four assertions would cost more than it earns.
 *
 * Robolectric rather than a fake because surviving is the property under test:
 * the screen reads this on a later launch than the `TileService` callback that
 * wrote it, so an in-memory double would pass while nothing reached disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TilePresenceStoreTest {

    private fun newStore() = TilePresenceStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a fresh install assumes the tile is missing`() {
        // The safe direction, and the reason the default is `false`: the row
        // appears, and a request corrects it on the spot if the tile is already
        // there. The opposite default would hide the only route to the tile.
        assertFalse(newStore().isAdded())
    }

    @Test
    fun `an added tile is remembered across instances`() {
        newStore().setAdded(true)

        // A new instance, because the point is the disk: `onTileAdded` runs in
        // the tile service and the screen reads it in another process lifetime.
        assertTrue(newStore().isAdded())
    }

    @Test
    fun `a removed tile is offered again`() {
        val store = newStore()
        store.setAdded(true)

        store.setAdded(false)

        assertFalse(newStore().isAdded())
    }
}
