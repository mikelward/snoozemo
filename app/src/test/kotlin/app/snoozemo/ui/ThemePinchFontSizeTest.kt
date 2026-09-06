package app.snoozemo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.snoozemo.core.FontSizeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The same pinch as `PinchFontSizeTest`, but through the real
 * [SnoozemoTheme] — where every preview changes the `LocalDensity` the gesture
 * itself is hosted under (Codex, PR #217).
 *
 * That is the difference worth a test of its own: `PinchFontSizeTest` drives
 * `pinchFontSize` on a bare host whose density never moves, so it could not
 * see a pointer-input handler restarted by the resize it just caused. If that
 * happened, the gesture would die on its first resizing frame — the fingers
 * would keep moving, nothing more would follow them, and the release would
 * never persist anything.
 *
 * 420dpi makes the 24dp slop 63px, as in the sibling test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class ThemePinchFontSizeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fontSize: FontSizeState
    private val settled = mutableListOf<Float>()

    /** The font scales the content was actually drawn at, in order. */
    private val drawnAt = mutableListOf<Float>()

    private fun setContent() {
        // Built here rather than through `rememberFontSizeState`, so the
        // settled size is captured without the real store being written to.
        fontSize = FontSizeState(
            initial = FontSizeSettings(),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        composeRule.setContent {
            SnoozemoTheme(fontSize) {
                // Reads the provided density, so the assertions below are about
                // what the user would see rather than about a field.
                val fontScale = LocalDensity.current.fontScale
                if (drawnAt.lastOrNull() != fontScale) drawnAt += fontScale
                Box(modifier = Modifier.fillMaxSize().testTag(TAG))
            }
        }
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.spreadTo(spread: Float) {
        updatePointerTo(0, Offset(CENTER_X - spread / 2f, ROW_Y))
        updatePointerTo(1, Offset(CENTER_X + spread / 2f, ROW_Y))
        move()
    }

    @Test
    fun `a pinch keeps following the fingers after the first resize`() {
        setContent()
        // Each stretch is injected separately, with the tree allowed to settle
        // in between: one `performTouchInput` block dispatches its moves
        // without recomposing, so the density would not actually change until
        // the gesture was already over and the case would prove nothing. The
        // pointers stay down across the blocks.
        touch {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            // Spends the slop; resizes nothing, so the density has not moved yet.
            spreadTo(180f)
        }
        // The first resizing frame — this is the one that changes the density
        // the gesture is hosted under.
        touch { spreadTo(200f) }
        val afterFirstResize = fontSize.scale
        assertTrue(drawnAt.toString(), drawnAt.size >= 2)

        // And the frames after it, which a restarted handler would drop.
        touch { spreadTo(210f) }
        touch { spreadTo(220f) }
        touch {
            up(0)
            up(1)
        }

        composeRule.runOnIdle {
            // Kept growing past the first resizing frame, so the later moves
            // reached the same live gesture rather than a restarted one.
            assertTrue(
                "$afterFirstResize -> ${fontSize.scale}",
                fontSize.scale > afterFirstResize,
            )
            // Exactly one write, on release, at the size the last frame showed.
            assertEquals(settled.toString(), 1, settled.size)
            assertEquals(fontSize.scale, settled.single(), 0.0001f)
            // And the text was redrawn as the fingers moved, not only at the end.
            assertTrue(drawnAt.toString(), drawnAt.size > 2)
            assertEquals(fontSize.scale, drawnAt.last(), 0.0001f)
        }
    }

    private fun touch(block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        composeRule.onNodeWithTag(TAG).performTouchInput(block)
        composeRule.waitForIdle()
    }

    private companion object {
        const val TAG = "theme-pinch-host"
        const val CENTER_X = 500f
        const val ROW_Y = 500f
    }
}
