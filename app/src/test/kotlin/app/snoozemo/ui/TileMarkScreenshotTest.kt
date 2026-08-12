package app.snoozemo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.DrawableCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tile mark, drawn the two ways Quick Settings will draw it.
 *
 * The active tile inverts — light ground when off, dark when on, matching the
 * system Do Not Disturb tile — and the platform tints the glyph to contrast
 * with whichever ground it drew. So one asset has to work as dark-on-light
 * *and* light-on-dark, and the failure is one-directional: light-on-dark
 * blooms, thickening strokes and closing counters. A mark checked only in the
 * forgiving direction can ship looking like a solid block in the other, which
 * on a 1x1 tile is the whole armed-vs-inactive signal gone (SPEC.md §4.2).
 *
 * Recorded at the tile's real size rather than blown up. A glyph that only
 * survives at 4x is not one this app can use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileMarkScreenshotTest {

    @Test
    fun `the mark reads dark on light, as an inactive tile draws it`() {
        capture("tile-mark-inactive.png", glyph = Color.parseColor("#1B1B1B"), ground = Color.parseColor("#F1F1F1"))
    }

    @Test
    fun `the mark reads light on dark, as an active tile draws it`() {
        // The dangerous direction. Anything that fills in does it here.
        capture("tile-mark-active.png", glyph = Color.WHITE, ground = Color.parseColor("#101010"))
    }

    private fun capture(name: String, glyph: Int, ground: Int) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mark: Drawable = requireNotNull(
            context.getDrawable(app.snoozemo.tile.R.drawable.ic_tile_snooze),
        ) { "The tile mark is missing from the merged resources." }
        assertNotNull(mark)

        // Tinted here rather than trusting the drawable's own `?attr` tint: the
        // platform overrides that per tile state, so the asset's declared tint
        // is not what a user ever sees.
        val tinted = DrawableCompat.wrap(mark.mutate()).also { DrawableCompat.setTint(it, glyph) }
        val size = tinted.intrinsicWidth
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ground)
        tinted.setBounds(0, 0, size, size)
        tinted.draw(canvas)

        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        if (!recording && !verifying) return
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
