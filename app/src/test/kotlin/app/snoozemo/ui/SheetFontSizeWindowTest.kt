package app.snoozemo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.snoozemo.core.FontSizeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chosen text size, and the pinch, inside a composable Compose hosts in its
 * own window (Codex, PR #217).
 *
 * A `ModalBottomSheet`, an `AlertDialog` and a `DropdownMenu` each render in a
 * separate window whose owner provides `LocalDensity` afresh, so neither the
 * scaled density nor a pointer handler installed on the activity's root
 * crosses into them. Measured before the fix, the end-condition sheet drew at
 * 1.0 with the app set to 1.5 — the chosen size ignored outright, on the one
 * surface `SPEC.md` §4.8 promises the gesture reaches.
 *
 * 420dpi makes the 24dp slop 63px, as in the sibling gesture tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class SheetFontSizeWindowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fontSize: FontSizeState
    private val settled = mutableListOf<Float>()
    private var inWindow = -1f

    private fun setContent(initial: Float) {
        fontSize = FontSizeState(
            initial = FontSizeSettings(scale = initial),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        composeRule.setContent {
            // The condition inside one of those windows, without the window:
            // every composition local the theme provided is still in scope
            // except `LocalDensity`, which the window's own owner re-provides
            // from the platform — and no ancestor pinch host, since the
            // activity root's is in a different window and never sees these
            // touches. Wrapping in a real `SnoozemoTheme` instead would put
            // its root host above this one and settle every gesture twice,
            // which is the fake being wrong rather than the code.
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val platform = androidx.compose.ui.unit.Density(
                androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.density,
                configuration.fontScale,
            )
            androidx.compose.runtime.CompositionLocalProvider(
                LocalFontSizeState provides fontSize,
                LocalDensity provides platform,
            ) {
                FontSizePinchWindow {
                    inWindow = LocalDensity.current.fontScale
                    Box(Modifier.fillMaxSize().testTag(TAG))
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a window of its own still draws at the chosen size`() {
        setContent(initial = 1.5f)

        assertEquals(1.5f, inWindow, 0.0001f)
    }

    @Test
    fun `a pinch inside that window resizes and persists`() {
        setContent(initial = 1f)

        touch {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            // Spends the slop.
            spreadTo(180f)
        }
        touch { spreadTo(200f) }
        val afterFirstResize = fontSize.scale
        touch { spreadTo(220f) }
        touch {
            up(0)
            up(1)
        }

        composeRule.runOnIdle {
            assertTrue(
                "$afterFirstResize -> ${fontSize.scale}",
                fontSize.scale > afterFirstResize,
            )
            assertEquals(settled.toString(), 1, settled.size)
            assertEquals(fontSize.scale, settled.single(), 0.0001f)
            // The window followed it, rather than staying at the size it opened at.
            assertEquals(fontSize.scale, inWindow, 0.0001f)
        }
    }

    @Test
    fun `the text-only host resizes with the app but takes no gesture`() {
        // `FontSizeWindow` is the size half alone, and stays that way now that
        // every window takes the gesture too (maintainer, 2026-09-07): the
        // pinch is hosted once per window, on the surface *above* these slots,
        // so a dialog's title, body and buttons are spanned by one gesture.
        // Nesting a second host inside a slot would apply the same zoom twice,
        // which is what this pins against.
        textOnly(initial = 1.5f)

        assertEquals(1.5f, inWindow, 0.0001f)
        touch {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            spreadTo(180f)
            spreadTo(260f)
            up(0)
            up(1)
        }
        composeRule.runOnIdle {
            assertEquals(1.5f, fontSize.scale, 0.0001f)
            assertTrue(settled.toString(), settled.isEmpty())
        }
    }

    @Test
    fun `one host spans a dialog's slots`() {
        // How every popup is wired since the gesture reached all of them
        // (maintainer, 2026-09-07): `Modifier.pinchFontSizeHost()` on the
        // surface, size-only slots inside it. The fingers go down in *different*
        // slots, which is the point — hosted per slot instead, this gesture
        // would not exist at all, and the rationale dialog's body is too small
        // to hold two fingers comfortably on its own.
        slottedSurface(initial = 1f)

        touch(TOP_SLOT) {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y + SLOT_HEIGHT))
            // Spends the slop. The fingers stay one slot apart throughout, so
            // the second one is hit-tested into the slot below and the gesture
            // only exists because the host above both of them sees it.
            slotSpreadTo(240f)
        }
        touch(TOP_SLOT) { slotSpreadTo(340f) }
        touch(TOP_SLOT) {
            up(0)
            up(1)
        }

        composeRule.runOnIdle {
            assertTrue(fontSize.scale.toString(), fontSize.scale > 1f)
            // Once, not once per slot: a second host inside one of them would
            // have taken the same events and written its own value too.
            assertEquals(settled.toString(), 1, settled.size)
            assertEquals(fontSize.scale, settled.single(), 0.0001f)
            assertEquals(fontSize.scale, inWindow, 0.0001f)
        }
    }

    @Test
    fun `a host removed mid-pinch settles rather than leaving the state moving`() {
        // The end-condition sheet goes away when its snooze ends, with the
        // fingers possibly still down (Codex, PR #217). Measured on the code
        // before the `finally`, this already settled — a detached node is handed
        // a cancellation whose pointers read released — so what this pins is the
        // outcome, not that path in particular: whichever way the host goes
        // away, the size the user watched happen is written and the state is no
        // longer `moving`, which would otherwise defer every later stored value
        // to fingers that are no longer anywhere.
        hostedWindow(initial = 1f)

        touch {
            down(0, Offset(CENTER_X - 50f, ROW_Y))
            down(1, Offset(CENTER_X + 50f, ROW_Y))
            spreadTo(180f)
        }
        touch { spreadTo(220f) }
        val resized = fontSize.scale
        assertTrue(resized.toString(), resized > 1f)

        // The window goes away with the fingers still down.
        composeRule.runOnIdle { hosted = false }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            // Written once, at the size the last frame showed — not lost.
            assertEquals(settled.toString(), 1, settled.size)
            assertEquals(resized, settled.single(), 0.0001f)
            // And the gesture is over, so a stored value lands rather than
            // being held for fingers that are no longer anywhere.
            fontSize.onPersisted(FontSizeSettings(scale = 1.2f, pinchEnabled = false))
            assertEquals(1.2f, fontSize.scale, 0.0001f)
            assertEquals(false, fontSize.pinchEnabled)
        }
    }

    private var hosted by androidx.compose.runtime.mutableStateOf(true)

    private fun hostedWindow(initial: Float) {
        fontSize = FontSizeState(
            initial = FontSizeSettings(scale = initial),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalFontSizeState provides fontSize,
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (hosted) {
                        FontSizePinchWindow {
                            Box(Modifier.fillMaxSize().testTag(TAG))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun textOnly(initial: Float) {
        fontSize = FontSizeState(
            initial = FontSizeSettings(scale = initial),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        composeRule.setContent {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val platform = androidx.compose.ui.unit.Density(
                androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.density,
                configuration.fontScale,
            )
            androidx.compose.runtime.CompositionLocalProvider(
                LocalFontSizeState provides fontSize,
                LocalDensity provides platform,
            ) {
                FontSizeWindow {
                    inWindow = LocalDensity.current.fontScale
                    Box(Modifier.fillMaxSize().testTag(TAG))
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * A dialog's shape: one pinch host on the surface, size-only slots inside.
     */
    private fun slottedSurface(initial: Float) {
        fontSize = FontSizeState(
            initial = FontSizeSettings(scale = initial),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        composeRule.setContent {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val platform = androidx.compose.ui.unit.Density(
                androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.density,
                configuration.fontScale,
            )
            androidx.compose.runtime.CompositionLocalProvider(
                LocalFontSizeState provides fontSize,
                LocalDensity provides platform,
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxSize().pinchFontSizeHost(),
                ) {
                    FontSizeWindow {
                        inWindow = LocalDensity.current.fontScale
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(SLOT_HEIGHT_DP.dp)
                                .testTag(TOP_SLOT),
                        )
                    }
                    FontSizeWindow {
                        Box(Modifier.fillMaxSize().testTag(BOTTOM_SLOT))
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun touch(
        tag: String = TAG,
        block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit,
    ) {
        composeRule.onNodeWithTag(tag).performTouchInput(block)
        composeRule.waitForIdle()
    }

    /** [spreadTo], but keeping the second finger in the slot below the first. */
    private fun androidx.compose.ui.test.TouchInjectionScope.slotSpreadTo(width: Float) {
        updatePointerTo(0, Offset(CENTER_X - width / 2f, ROW_Y))
        updatePointerTo(1, Offset(CENTER_X + width / 2f, ROW_Y + SLOT_HEIGHT))
        move()
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.spreadTo(spread: Float) {
        updatePointerTo(0, Offset(CENTER_X - spread / 2f, ROW_Y))
        updatePointerTo(1, Offset(CENTER_X + spread / 2f, ROW_Y))
        move()
    }

    private companion object {
        const val TAG = "window-pinch-host"
        const val TOP_SLOT = "window-slot-top"
        const val BOTTOM_SLOT = "window-slot-bottom"
        const val CENTER_X = 500f
        const val ROW_Y = 500f

        /** Tall enough that the second finger lands in the slot below. */
        const val SLOT_HEIGHT_DP = 200
        const val SLOT_HEIGHT = 100f
    }
}
