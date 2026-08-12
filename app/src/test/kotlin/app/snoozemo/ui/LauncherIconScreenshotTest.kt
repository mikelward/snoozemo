package app.snoozemo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.DrawableCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The launcher icon, drawn the way a launcher draws it.
 *
 * The mark exists twice on purpose — once at 24dp for the tile, once scaled
 * into the adaptive icon's safe zone — because the two are different assets
 * with different viewports, not one asset reused. That duplication is the
 * reason this test exists: edit one vector and forget the other and the shade
 * and the home screen quietly stop showing the same app, which no compile step
 * and no other test would notice.
 *
 * Two things are captured that the tile snapshots cannot show. `AdaptiveIconDrawable`
 * applies its own mask when it draws, so the round capture is the actual answer
 * to "does the mark survive a circular launcher" rather than an eyeballed
 * guess. And the `monochrome` layer is the same vector flattened to a
 * silhouette — the themed-icon path — which is where a launcher-only flourish
 * would disappear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconScreenshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the adaptive icon keeps the mark inside its mask`() {
        val icon = requireNotNull(context.getDrawable(app.snoozemo.R.mipmap.ic_launcher)) {
            "The launcher icon is missing from the merged resources."
        }
        assertTrue(
            "The launcher icon must stay adaptive — a plain drawable gets no mask and no themed variant.",
            icon is AdaptiveIconDrawable,
        )

        capture("launcher-icon.png", icon, size = 288)
    }

    @Test
    fun `the monochrome layer is the same mark as a silhouette`() {
        // Tinted the way a themed launcher tints it, which is the same
        // flattening Quick Settings does to the tile: only the alpha survives.
        val monochrome = requireNotNull(
            (context.getDrawable(app.snoozemo.R.mipmap.ic_launcher) as AdaptiveIconDrawable).monochrome,
        ) { "The adaptive icon carries no monochrome layer, so themed icons fall back to a generic mark." }

        val tinted = DrawableCompat.wrap(monochrome.mutate())
            .also { DrawableCompat.setTint(it, Color.parseColor("#1B1B1B")) }
        capture("launcher-icon-monochrome.png", tinted, size = 288, ground = Color.parseColor("#DCD6E4"))
    }

    private fun capture(name: String, drawable: Drawable, size: Int, ground: Int = Color.TRANSPARENT) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (ground != Color.TRANSPARENT) canvas.drawColor(ground)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)

        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        if (!recording && !verifying) return
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
