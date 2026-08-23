package app.snoozemo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Everything touched rarely — usually never — that moved off `MainScreen`
 * (`TODO.md` Phase 4): the Permissions entry point, the permanent tile row,
 * and the debug-log switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the Permissions row opens the interstitial`() {
        var opened = 0

        capture("settings-screen-idle.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = { opened++ },
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Permissions").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a missing tile is offered, and only while it is missing`() {
        var tapped = 0

        capture("settings-screen-tile-missing.png") {
            SettingsScreen(
                tileAdded = false,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = {},
                onTileRow = { tapped++ },
                onFiltersRow = {},
                onDebugLog = {},
            )
        }

        // The tile is the product (SPEC.md §4.2) — a user without it has an
        // app whose whole interaction is out of reach, so the row offers it.
        composeRule.onNodeWithText("The one-tap way to snooze").assertExists()
        composeRule.onNodeWithText("Add").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun `a tile already in the shade is stated, not offered`() {
        capture("settings-screen-idle.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
            )
        }

        // The row stays — its permanence is what makes MainScreen's tile
        // banner safe to dismiss for good — but it stops offering: nothing is
        // left to create.
        composeRule.onNodeWithText("Quick Settings tile").assertExists()
        composeRule.onNodeWithText("Added").assertExists()
        composeRule.onNodeWithText("Add").assertDoesNotExist()
    }

    @Test
    fun `a refused add-tile request is said on the row`() {
        capture("settings-screen-tile-refused.png") {
            SettingsScreen(
                tileAdded = false,
                filtersRuleId = null,
                settingsFailure = SetupRowId.TILE,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Couldn't add the tile").assertExists()
    }

    @Test
    fun `the Filters row is hidden until there is a rule to edit`() {
        capture {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
            )
        }

        // Absent, not disabled — a button with nothing yet behind it is the
        // dead tap this screen's rows are built to avoid (TODO.md).
        composeRule.onNodeWithText("Filters").assertDoesNotExist()
    }

    @Test
    fun `the Filters row opens the rule's own interruption-filter screen`() {
        var opened = 0

        capture("settings-screen-filters.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = "example-rule-id",
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = { opened++ },
                onDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Filters").assertExists()
        composeRule.onNodeWithText("Edit").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a refused Filters trip is said on the row`() {
        capture("settings-screen-filters-refused.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = "example-rule-id",
                settingsFailure = SetupRowId.FILTERS,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Couldn't open Settings").assertExists()
    }

    @Test
    fun `the debug log switch is here, not on the home screen`() {
        var changed: Boolean? = null

        capture {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = { changed = it },
            )
        }

        composeRule.onNodeWithText("Debug log").assertExists()
        composeRule.onNodeWithText("Save snooze details to help fix issues").assertExists()
    }

    @Test
    fun `a refused debug log save says so under the switch`() {
        capture("settings-screen-debug-log-save-failed.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = true,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Couldn't save this setting").assertExists()
    }

    @Test
    fun `idle in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("settings-screen-idle-dark.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Settings").assertExists()
    }

    /**
     * Renders [content] the way `MainActivity` does and records it under
     * [name] when a name is given. See the sibling suites for why both the
     * theme and the `Surface` matter.
     */
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
