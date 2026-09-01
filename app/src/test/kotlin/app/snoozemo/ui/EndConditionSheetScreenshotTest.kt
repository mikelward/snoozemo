package app.snoozemo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.EndCondition
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The end-condition sheet (`SPEC.md` §4.4) — the two rows and the confirm the
 * trampoline offers once a snooze is already armed.
 *
 * Composed as its own content rather than through `TileTrampolineActivity`,
 * which is the reason `EndConditionSheetContent` is split out from the window
 * that hosts it: the sheet arrives over a transparent activity, and a popup
 * window is what makes a Compose tree fail to settle under Robolectric.
 *
 * The time is passed in already formatted, so these snapshots don't move with
 * the host's locale or its 12/24-hour setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EndConditionSheetScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A tap on the hour, so the seed lands on a clean half hour. */
    private val armedAt: Instant = Instant.parse("2026-08-25T13:00:00Z")

    private fun seeded() =
        EndCondition.seededAt(armedAt, armedAt.plus(ActiveSnooze.DEFAULT_CAP), ZoneId.of("UTC"))

    @Test
    fun `offers a time and a departure, and says they are not exclusive`() {
        capture("end-condition-sheet.png") {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = {},
                onChooseDeparture = {},
                onStepDown = {},
                onStepUp = {},
            )
        }

        composeRule.onNodeWithText("until 2:00 PM").assertExists()
        composeRule.onNodeWithText("until I leave").assertExists()
        // The helper line is load-bearing, not decoration: choosing a time
        // lowers the cap rather than replacing departure tracking.
        composeRule.onNodeWithText("Ends when you leave, either way.").assertExists()
        composeRule.onNodeWithText("OK").assertExists()
    }

    @Test
    fun `each row commits its own end condition`() {
        var time = 0
        var departure = 0

        capture {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = { time++ },
                onChooseDeparture = { departure++ },
                onStepDown = {},
                onStepUp = {},
            )
        }

        composeRule.onNodeWithText("until 2:00 PM").performClick()
        assertEquals(1, time)
        assertEquals(0, departure)

        composeRule.onNodeWithText("until I leave").performClick()
        assertEquals(1, departure)
    }

    @Test
    fun `OK accepts the time as shown`() {
        // The rows read as labels, so after stepping there was nothing on screen
        // that said "done" — the only exits kept no time at all. `OK` is that
        // exit, and it commits exactly what the time row would.
        var time = 0
        var departure = 0

        capture {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = { time++ },
                onChooseDeparture = { departure++ },
                onStepDown = {},
                onStepUp = {},
            )
        }

        composeRule.onNodeWithText("OK").performClick()
        assertEquals(1, time)
        assertEquals(0, departure)
    }

    @Test
    fun `the sheet scrolls, so OK stays reachable`() {
        // The sheet is bottom-aligned in both hosts, so content taller than the
        // window clips from the bottom — and the confirm is the bottom-most
        // control. Landscape gets there, and so does a large system font.
        // Clipped, this change's whole point is gone and only the exits that
        // discard the time are left (Codex, PR #173).
        //
        // This host never measures the composition against the window — the
        // scroll range reads zero under every device qualifier — so the clipping
        // itself cannot be reproduced here. What is pinned instead is the thing
        // that prevents it: `performScrollTo` throws without a scrollable
        // ancestor, so this fails on a sheet whose content cannot scroll.
        var time = 0

        capture {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = { time++ },
                onChooseDeparture = {},
                onStepDown = {},
                onStepUp = {},
            )
        }

        composeRule.onNodeWithText("OK").performScrollTo().performClick()
        assertEquals(1, time)
    }

    @Test
    fun `a refusal is placed above the confirm, not under it`() {
        // Scrolling is what makes `OK` reachable on a short screen, and it is
        // also what can hide the answer to pressing it: a failure line added
        // *below* the bottom-most control lands outside the viewport, and the
        // refused commit reads as an inert tap — principle 2's silent failure
        // (Codex, PR #173). Above the button, it arrives in the space the user
        // is already looking at.
        capture {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = {},
                onChooseDeparture = {},
                onStepDown = {},
                onStepUp = {},
                failed = true,
            )
        }

        val error = composeRule.onNodeWithText("Couldn\'t set the end time").fetchSemanticsNode()
        val ok = composeRule.onNodeWithText("OK").fetchSemanticsNode()
        assertTrue(
            "the failure line belongs above the confirm, not under it",
            error.positionInRoot.y < ok.positionInRoot.y,
        )
    }

    @Test
    fun `the steppers report the step they take, not the symbol they draw`() {
        var down = 0
        var up = 0

        capture {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = {},
                onChooseDeparture = {},
                onStepDown = { down++ },
                onStepUp = { up++ },
            )
        }

        composeRule.onNodeWithContentDescription("Half an hour earlier").performClick()
        composeRule.onNodeWithContentDescription("Half an hour later").performClick()
        assertEquals(1, down)
        assertEquals(1, up)
    }

    @Test
    fun `a stepper with nowhere to go is disabled rather than absent`() {
        // Two steps of headroom above the seed, and the floor immediately below
        // it — the shape a snooze close to its backstop produces.
        val atTheFloor = EndCondition(
            endsAt = armedAt.plus(ActiveSnooze.MIN_CAP),
            floor = armedAt.plus(ActiveSnooze.MIN_CAP),
            ceiling = armedAt.plus(ActiveSnooze.DEFAULT_CAP),
        )

        capture("end-condition-sheet-at-floor.png") {
            EndConditionSheetContent(
                condition = atTheFloor,
                formattedTime = "1:30 PM",
                onChooseTime = {},
                onChooseDeparture = {},
                onStepDown = {},
                onStepUp = {},
            )
        }

        // Still on screen, so the control doesn't move under the user's finger
        // between taps.
        composeRule.onNodeWithContentDescription("Half an hour earlier").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Half an hour later").assertIsEnabled()
    }

    @Test
    fun `a refused choice is reported in the sheet, not swallowed by a dismissal`() {
        capture("end-condition-sheet-refused.png") {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = {},
                onChooseDeparture = {},
                onStepDown = {},
                onStepUp = {},
                failed = true,
            )
        }

        composeRule.onNodeWithText("Couldn't set the end time").assertExists()
    }

    @Test
    fun `a commit in flight makes the rows inert rather than removing them`() {
        // The sheet no longer dismisses the instant a row is tapped — it waits
        // for the service to say whether the change actually took (Codex,
        // PR #118) — so a second tap has to be refused while the first is out.
        // The rows stay drawn: a control that vanished mid-tap would move the
        // other one under the user's finger.
        var time = 0
        var departure = 0

        capture("end-condition-sheet-committing.png") {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = { time++ },
                onChooseDeparture = { departure++ },
                onStepDown = {},
                onStepUp = {},
                committing = true,
            )
        }

        composeRule.onNodeWithText("until 2:00 PM").assertExists()
        composeRule.onNodeWithText("until 2:00 PM").performClick()
        composeRule.onNodeWithText("until I leave").performClick()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(0, time)
        assertEquals(0, departure)

        composeRule.onNodeWithText("OK").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Half an hour later").assertIsNotEnabled()
    }

    @Test
    fun `a build that cannot track departure offers no departure row`() {
        // `direct` has no presence monitor at all, so "until I leave" would be a
        // promise nothing behind it can keep — selecting it would dismiss and
        // leave the duration cap running while the sheet claimed otherwise
        // (Codex, PR #118). The footer goes with it: it exists only to say the
        // two rows are not exclusive, and there is now one row.
        capture("end-condition-sheet-duration-only.png") {
            EndConditionSheetContent(
                condition = seeded(),
                formattedTime = "2:00 PM",
                onChooseTime = {},
                onChooseDeparture = {},
                onStepDown = {},
                onStepUp = {},
                tracksDeparture = false,
            )
        }

        composeRule.onNodeWithText("until 2:00 PM").assertExists()
        composeRule.onNodeWithText("until I leave").assertDoesNotExist()
        composeRule.onNodeWithText("Ends when you leave, either way.").assertDoesNotExist()
        // The confirm is the sheet's way out on every build, not something the
        // departure row was carrying.
        composeRule.onNodeWithText("OK").assertExists()
    }

    @Test
    fun `the sheet swallows taps that land on its own content`() {
        // The scrim used to be the *parent* holding the sheet, so every tap the
        // sheet's own content didn't consume — the title, the footer, the
        // padding — bubbled up and dismissed it (Codex, PR #118). The scrim is a
        // sibling behind it now, and this pins the half that makes that work:
        // the sheet's Surface consumes what lands on it, so a scrim behind never
        // sees those taps.
        var dismissed = 0

        composeRule.setContent {
            SnoozemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("scrim")
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { dismissed++ },
                                ),
                        )
                        EndConditionSheetContent(
                            condition = seeded(),
                            formattedTime = "2:00 PM",
                            onChooseTime = {},
                            onChooseDeparture = {},
                            onStepDown = {},
                            onStepUp = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // The footer is plain text inside the sheet and clickable by nothing.
        composeRule.onNodeWithText("Ends when you leave, either way.").performClick()
        assertEquals("a tap inside the sheet is not a dismissal", 0, dismissed)

        // And the scrim still works, so the assertion above can't pass because
        // dismissal is simply broken.
        composeRule.onNodeWithTag("scrim").performClick()
        assertEquals(1, dismissed)
    }

    private fun capture(name: String? = null, content: @Composable () -> Unit) {
        composeRule.setContent {
            SnoozemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.waitForIdle()
        name?.let { captureSnapshot(it) }
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 2400) {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        if (!recording && !verifying) return

        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
