package app.snoozemo.tile

import org.junit.Assert.assertEquals
import org.junit.Test

class TileOptimisticPaintTest {

    @Test
    fun `arms from a tile currently reading not snoozing`() {
        val paint = TileOptimisticPaint.forTap(currentlySnoozing = false, chooserModeOn = false)

        assertEquals(TileOptimisticPaint.ACTION_ARM, paint.action)
        assertEquals(true, paint.active)
        assertEquals(R.string.tile_snoozing, paint.labelRes)
    }

    @Test
    fun `ends from a tile currently reading snoozing`() {
        val paint = TileOptimisticPaint.forTap(currentlySnoozing = true, chooserModeOn = false)

        assertEquals(TileOptimisticPaint.ACTION_END, paint.action)
        assertEquals(false, paint.active)
        assertEquals(R.string.tile_snooze_here, paint.labelRes)
    }

    @Test
    fun `arm and end use distinct request codes`() {
        val arm = TileOptimisticPaint.forTap(currentlySnoozing = false, chooserModeOn = false)
        val end = TileOptimisticPaint.forTap(currentlySnoozing = true, chooserModeOn = false)

        assertEquals(false, arm.requestCode == end.requestCode)
    }

    @Test
    fun `an arm with the chooser on paints no change, since it arms nothing`() {
        // Ask-on: the tap opens the chooser and arms nothing (SPEC.md §4.4), so
        // the tile must not flip to Snoozing — the chooser may be dismissed
        // without an arm, and a row's arm reconciles on the next listen.
        val paint = TileOptimisticPaint.forTap(currentlySnoozing = false, chooserModeOn = true)

        assertEquals(TileOptimisticPaint.ACTION_ARM, paint.action)
        assertEquals(false, paint.active)
        assertEquals(R.string.tile_snooze_here, paint.labelRes)
    }

    @Test
    fun `an end with the chooser on still ends`() {
        // The chooser is the way in; ending is on the way out and is unaffected.
        val paint = TileOptimisticPaint.forTap(currentlySnoozing = true, chooserModeOn = true)

        assertEquals(TileOptimisticPaint.ACTION_END, paint.action)
        assertEquals(false, paint.active)
        assertEquals(R.string.tile_snooze_here, paint.labelRes)
    }
}
