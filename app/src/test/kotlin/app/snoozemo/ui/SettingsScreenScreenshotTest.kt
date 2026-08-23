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
import app.snoozemo.PlayUpdateState
import app.snoozemo.UpdateProgress
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
 * and the debug-log switch. The post-crash banner lives on `MainScreen`
 * instead (`SPEC.md` §4.6, maintainer, 2026-08-23) — see
 * `MainScreenScreenshotTest` for its coverage.
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = { opened++ },
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = { tapped++ },
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = { opened++ },
                onDebugLog = {},
                onShareDebugLog = {},
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = { changed = it },
                onShareDebugLog = {},
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Couldn't save this setting").assertExists()
    }

    @Test
    fun `an available update is offered, above the permanent rows`() {
        var started = 0

        capture("settings-screen-update-available.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                playUpdate = PlayUpdateState.Available(versionCode = 5),
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onStartPlayUpdate = { started++ },
                onShareDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Update available").assertExists()
        composeRule.onNodeWithText("Update").performClick()
        assertEquals(1, started)
    }

    @Test
    fun `a dismissed update shows nothing`() {
        capture {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                playUpdate = PlayUpdateState.Available(versionCode = 5, isDismissed = true),
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Update available").assertDoesNotExist()
    }

    @Test
    fun `an in-flight update shows progress, not the offer`() {
        capture("settings-screen-update-downloading.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                playUpdate = PlayUpdateState.Available(versionCode = 5, progress = UpdateProgress.Downloading),
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Updating…").assertExists()
        composeRule.onNodeWithText("Update").assertDoesNotExist()
        composeRule.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    @Test
    fun `a downloaded update offers Restart, not Dismiss`() {
        var restarted = 0

        capture("settings-screen-update-downloaded.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                playUpdate = PlayUpdateState.Available(versionCode = 5, progress = UpdateProgress.Downloaded),
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onCompletePlayUpdate = { restarted++ },
                onShareDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Update ready").assertExists()
        // No Dismiss once downloaded: it's the only in-app way to finish an
        // update already fetched, so nothing may hide it.
        composeRule.onNodeWithText("Dismiss").assertDoesNotExist()
        composeRule.onNodeWithText("Restart").performClick()
        assertEquals(1, restarted)
    }

    @Test
    fun `a failed Restart says so under the banner, not just by doing nothing`() {
        capture("settings-screen-update-restart-failed.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                playUpdate = PlayUpdateState.Available(versionCode = 5, progress = UpdateProgress.Downloaded),
                playUpdateRestartFailed = true,
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Couldn't restart to install").assertExists()
        // Restart stays reachable — the failure is retryable, not terminal.
        composeRule.onNodeWithText("Restart").assertExists()
    }

    @Test
    fun `dismissing the update banner`() {
        var dismissed = 0

        capture {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                playUpdate = PlayUpdateState.Available(versionCode = 5),
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onDismissPlayUpdate = { dismissed++ },
                onShareDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a refused cleanup delete says so under the switch, distinct from a save failure`() {
        capture("settings-screen-debug-log-cleanup-failed.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = false,
                debugLogSaveFailed = false,
                debugLogCleanupFailed = true,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
            )
        }

        composeRule.onNodeWithText("Off, but some saved files couldn't be deleted").assertExists()
    }

    @Test
    fun `the share row is always offered, and a failure says so under it`() {
        var shared = 0

        capture("settings-screen-share-failed.png") {
            SettingsScreen(
                tileAdded = true,
                filtersRuleId = null,
                settingsFailure = null,
                debugLogEnabled = true,
                debugLogSaveFailed = false,
                debugLogCleanupFailed = false,
                shareFailed = true,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = { shared++ },
            )
        }

        composeRule.onNodeWithText("Share debug logs").assertExists()
        composeRule.onNodeWithText("Couldn't share the debug log").assertExists()
        composeRule.onNodeWithText("Share").performClick()
        assertEquals(1, shared)
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
                debugLogCleanupFailed = false,
                shareFailed = false,
                onOpenPermissions = {},
                onTileRow = {},
                onFiltersRow = {},
                onDebugLog = {},
                onShareDebugLog = {},
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
