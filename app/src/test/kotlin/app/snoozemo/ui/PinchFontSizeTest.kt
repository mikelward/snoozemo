package app.snoozemo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.snoozemo.core.MAX_FONT_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pinch-to-resize gesture (`SPEC.md` §4.8) on a bare host.
 *
 * The qualifiers matter: 420dpi makes the 24dp slop 63px, which is what every
 * separation below is measured against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class PinchFontSizeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var scale = 1f
    private var started = 0
    private val previews = mutableListOf<Float>()
    private var settled: Float? = null
    private var enabled = true
    private var scrolled = 0f

    /**
     * The gesture over a scrolling child, which is what every real screen is:
     * a real pinch must win against the scroll, and everything else must still
     * reach it.
     */
    private fun setContent() {
        composeRule.setContent {
            val scrollState = rememberScrollableState { delta ->
                scrolled += delta
                delta
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pinchFontSize(
                        enabled = { enabled },
                        scale = { scale },
                        onStart = { started++ },
                        onPreview = {
                            scale = it
                            previews += it
                        },
                        onSettled = { settled = it },
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollable(scrollState, Orientation.Vertical)
                        .testTag(TAG),
                )
            }
        }
    }

    /** Puts two fingers [spread] pixels apart, centered where they started. */
    private fun androidx.compose.ui.test.TouchInjectionScope.spreadTo(spread: Float) {
        updatePointerTo(0, Offset(CENTER_X - spread / 2f, ROW_Y))
        updatePointerTo(1, Offset(CENTER_X + spread / 2f, ROW_Y))
        move()
    }

    @Test
    fun `spreading two fingers grows the text and persists where it stopped`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            // 100px apart to 180px: 80px of separation, which clears the 63px
            // slop. That movement is what the slop spends, so nothing resizes
            // on this frame and the size starts moving from here.
            spreadTo(180f)
            // 180px to 200px: a zoom of 1.111, which the gain squares.
            spreadTo(200f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertEquals(1.2346f, scale, 0.001f)
            // One write, on release, at exactly the size the last frame showed —
            // not a write per frame of the gesture.
            assertEquals(1.2346f, settled!!, 0.001f)
            // The list underneath must not have scrolled with the pinch.
            assertEquals(0f, scrolled, 0.0001f)
        }
    }

    @Test
    fun `the size follows the fingers continuously rather than in steps`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            spreadTo(180f)
            // Small movements past the slop, each of which has to move the text
            // (maintainer, 2026-09-06): stepping would leave several of these
            // reading identically.
            listOf(185f, 190f, 195f, 200f).forEach { spread -> spreadTo(spread) }
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertEquals(previews.toString(), 4, previews.size)
            assertEquals(previews.toString(), previews.size, previews.distinct().size)
            assertTrue(previews.toString(), previews.zipWithNext().all { (a, b) -> b > a })
        }
    }

    @Test
    fun `the gesture announces itself on the frame it becomes a pinch`() {
        // Codex, PR #217: the slop-crossing frame resizes nothing, so the state
        // would not have known a gesture was under way — and a size landing
        // from disk in that window was published and then overwritten by the
        // release.
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            spreadTo(180f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            // Announced on the crossing frame, which showed no size change.
            assertEquals(1, started)
            assertTrue(previews.isEmpty())
        }
    }

    @Test
    fun `a gesture that never becomes a pinch announces nothing`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            spreadTo(140f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle { assertEquals(0, started) }
    }

    @Test
    fun `pinching two fingers together shrinks the text`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 150f, ROW_Y))
            down(1, Offset(CENTER_X + 150f, ROW_Y))
            // 300px apart down to 200px, so the slop is crossed on the way.
            spreadTo(220f)
            spreadTo(200f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertTrue("expected a smaller size, got $scale", scale < 1f)
            assertEquals(scale, settled!!, 0.0001f)
        }
    }

    @Test
    fun `a small pinch is ignored, and the page still scrolls under it`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            // 40px of separation — an accidental spread, well under the 63px
            // slop, so nothing resizes and nothing is claimed from the page.
            spreadTo(140f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertEquals(1f, scale, 0.0001f)
            assertNull(settled)
        }
    }

    @Test
    fun `a two-finger scroll scrolls, however far it travels`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            // Both fingers together, a long way: the separation never changes,
            // so this is a scroll and not a pinch at any distance.
            repeat(3) { step ->
                val y = ROW_Y - 100f * (step + 1)
                updatePointerTo(0, Offset(CENTER_X - 50f, y))
                updatePointerTo(1, Offset(CENTER_X + 50f, y))
                move()
            }
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertEquals(1f, scale, 0.0001f)
            assertNull(settled)
            assertTrue("expected the two-finger drag to scroll", scrolled != 0f)
        }
    }

    @Test
    fun `a spread past the largest size stops there`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            spreadTo(180f)
            spreadTo(900f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertEquals(MAX_FONT_SCALE, scale, 0.0001f)
            assertEquals(MAX_FONT_SCALE, settled!!, 0.0001f)
        }
    }

    @Test
    fun `a spread past the end and back lands where the fingers started`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            // Crossing the slop is where the size starts from, so this is the
            // separation the round trip has to return to.
            spreadTo(180f)
            spreadTo(300f)
            spreadTo(180f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            // Not the clamped ceiling times the reverse zoom, which is what
            // clamping the running size inside the gesture would give.
            assertEquals(1f, scale, 0.001f)
            assertEquals(1f, settled!!, 0.001f)
        }
    }

    @Test
    fun `a one-finger drag scrolls and never resizes`() {
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X, ROW_Y))
            updatePointerTo(0, Offset(CENTER_X, ROW_Y - 300f))
            move()
            up(0)
        }
        composeRule.runOnIdle {
            assertEquals(1f, scale, 0.0001f)
            assertNull(settled)
            assertTrue("expected the drag to scroll", scrolled != 0f)
        }
    }

    @Test
    fun `with the setting off a pinch changes nothing`() {
        enabled = false
        setContent()
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            spreadTo(180f)
            spreadTo(300f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertEquals(1f, scale, 0.0001f)
            assertNull(settled)
        }
    }

    private companion object {
        const val TAG = "pinch-host"
        const val CENTER_X = 500f
        const val ROW_Y = 500f
    }
}
