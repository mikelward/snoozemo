package app.snoozemo.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
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
 * The Settings slider dragged inside the real theme (Codex, PR #217).
 *
 * The pinch had this exact bug: a drag that changes the text size changes the
 * `LocalDensity` its own pointer handler is hosted under, and Compose resets a
 * pointer handler when its density changes — so the gesture dies on the frame
 * it first resizes anything. `SettingsScreenScreenshotTest` renders the row and
 * `ThemePinchFontSizeTest` drives the pinch; neither drags this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class FontSizeSliderDragTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fontSize: FontSizeState
    private val settled = mutableListOf<Float>()

    @Test
    fun `dragging the slider keeps tracking after the first resize and persists`() {
        fontSize = FontSizeState(
            initial = FontSizeSettings(),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        composeRule.setContent {
            SnoozemoTheme(fontSize) {
                Box(Modifier.fillMaxSize().testTag(HOST)) {
                    FontSizeRow(
                        scale = fontSize.scale,
                        saveFailed = false,
                        onPreview = fontSize::preview,
                        onSettled = fontSize::commit,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Selected by *having* a progress range rather than by its current
        // value: the value changes as the drag resizes, and a value-matching
        // selector then stops finding the node it is dragging.
        val node = composeRule.onNode(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            ),
        )

        // Each step in its own block, with the tree allowed to settle between:
        // one `performTouchInput` block dispatches without recomposing, so the
        // density would not move until the drag was already over.
        node.performTouchInput { down(Offset(centerX, centerY)) }
        composeRule.waitForIdle()
        // 80px clears the platform touch slop, so this is the first movement
        // that actually resizes anything — and therefore the first that changes
        // the density this slider is hosted under.
        node.performTouchInput { moveTo(Offset(centerX + 80f, centerY)) }
        composeRule.waitForIdle()
        val afterFirstResize = fontSize.scale

        node.performTouchInput { moveTo(Offset(centerX + 140f, centerY)) }
        composeRule.waitForIdle()
        node.performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(
                "$afterFirstResize -> ${fontSize.scale}",
                fontSize.scale > afterFirstResize,
            )
            assertEquals(settled.toString(), 1, settled.size)
            assertEquals(fontSize.scale, settled.single(), 0.0001f)
        }
    }

    @Test
    fun `a row removed mid-drag settles rather than leaving the state moving`() {
        // Codex, PR #217: leaving Settings with a finger still on the slider
        // disposes it without `onValueChangeFinished`, and the state lives above
        // the screen switch — so it stayed `moving` for the life of the
        // activity and deferred every later stored value.
        fontSize = FontSizeState(
            initial = FontSizeSettings(),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        var shown by androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            SnoozemoTheme(fontSize) {
                Box(Modifier.fillMaxSize().testTag(HOST)) {
                    if (shown) {
                        FontSizeRow(
                            scale = fontSize.scale,
                            saveFailed = false,
                            onPreview = fontSize::preview,
                            onSettled = fontSize::commit,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val node = composeRule.onNode(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            ),
        )
        node.performTouchInput { down(Offset(centerX, centerY)) }
        composeRule.waitForIdle()
        node.performTouchInput { moveTo(Offset(centerX + 80f, centerY)) }
        composeRule.waitForIdle()
        val dragged = fontSize.scale
        assertTrue(dragged.toString(), dragged > 1f)

        // The screen goes away with the finger still down.
        composeRule.runOnIdle { shown = false }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            // Written once, at the size the drag reached — not lost.
            assertEquals(settled.toString(), 1, settled.size)
            assertEquals(dragged, settled.single(), 0.0001f)
            // And the gesture is over, so a stored value lands rather than
            // being held for a drag that has ended.
            fontSize.onPersisted(FontSizeSettings(scale = 1.3f, pinchEnabled = false))
            assertEquals(1.3f, fontSize.scale, 0.0001f)
            assertEquals(false, fontSize.pinchEnabled)
        }
    }

    @Test
    fun `the slider says the percentage the row shows`() {
        // Codex, PR #217: a `Slider` describes itself as its position within
        // its own range, so at the default it announced roughly 25% beside a
        // row reading 100% — on the one setting whose users are most likely to
        // be listening rather than looking.
        fontSize = FontSizeState(
            initial = FontSizeSettings(scale = 1.25f),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        composeRule.setContent {
            SnoozemoTheme(fontSize) {
                Box(Modifier.fillMaxSize().testTag(HOST)) {
                    FontSizeRow(
                        scale = fontSize.scale,
                        saveFailed = false,
                        onPreview = fontSize::preview,
                        onSettled = fontSize::commit,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            ),
        ).assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                "125%",
            ),
        )
    }

    @Test
    fun `a press on the thumb after a pinch does not restore the pre-pinch size`() {
        // Codex, PR #217: the row caches the value the slider last reported so
        // a release in the same frame as the last drag step persists where the
        // finger left it. A pinch changes the size without going through the
        // slider, so that cache goes stale against it — a pre-pinch size
        // waiting for the next thing that reports a finish.
        //
        // Measured, a press does report a value before its finish, so the stale
        // cache is not reachable that way today and this passed before the sync
        // was added. What it pins is the outcome rather than that path: a touch
        // on the slider after a pinch lands near where the pinch left the size,
        // never back at what it was before.
        fontSize = FontSizeState(
            initial = FontSizeSettings(),
            onScaleSettled = { settled += it },
            onPinchEnabledChange = {},
        )
        composeRule.setContent {
            SnoozemoTheme(fontSize) {
                Box(Modifier.fillMaxSize().testTag(HOST)) {
                    FontSizeRow(
                        scale = fontSize.scale,
                        saveFailed = false,
                        onPreview = fontSize::preview,
                        onSettled = fontSize::commit,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // A pinch elsewhere on the screen resizes and settles.
        composeRule.runOnIdle {
            fontSize.preview(1.4f)
            fontSize.commit(1.4f)
        }
        composeRule.waitForIdle()
        settled.clear()

        val node = composeRule.onNode(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            ),
        )
        // On the thumb itself, where 1.4 sits in a 0.8..1.6 range, so the press
        // reports no value change at all — pressing anywhere else would legitimately
        // move the slider to that position and prove nothing.
        node.performTouchInput { down(Offset(width * 0.75f, centerY)) }
        composeRule.waitForIdle()
        node.performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            // Near the pinched size — the press itself may nudge it — and
            // nowhere near the 1.0 it started at.
            assertEquals(1.4f, fontSize.scale, 0.05f)
            assertTrue(settled.toString(), settled.all { it > 1.3f })
        }
    }

    private companion object {
        const val HOST = "font-size-row-host"
    }
}
