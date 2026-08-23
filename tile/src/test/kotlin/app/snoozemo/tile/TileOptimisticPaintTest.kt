package app.snoozemo.tile

import org.junit.Assert.assertEquals
import org.junit.Test

class TileOptimisticPaintTest {

    @Test
    fun `arms from a tile currently reading not snoozing`() {
        val paint = TileOptimisticPaint.forTap(currentlySnoozing = false)

        assertEquals(TileOptimisticPaint.ACTION_ARM, paint.action)
        assertEquals(true, paint.active)
        assertEquals(R.string.tile_snoozing, paint.labelRes)
    }

    @Test
    fun `ends from a tile currently reading snoozing`() {
        val paint = TileOptimisticPaint.forTap(currentlySnoozing = true)

        assertEquals(TileOptimisticPaint.ACTION_END, paint.action)
        assertEquals(false, paint.active)
        assertEquals(R.string.tile_snooze_here, paint.labelRes)
    }

    @Test
    fun `arm and end use distinct request codes`() {
        val arm = TileOptimisticPaint.forTap(currentlySnoozing = false)
        val end = TileOptimisticPaint.forTap(currentlySnoozing = true)

        assertEquals(false, arm.requestCode == end.requestCode)
    }
}
