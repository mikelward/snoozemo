package app.snoozemo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
// `assertIsEnabled` / `assertIsNotEnabled` are extensions and need importing;
// `assertExists` / `assertDoesNotExist` are members of the same type and must
// not be, which is a compile error that reads like a missing dependency.
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.TrackingMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The home screen in each state it can actually be in, light and dark.
 *
 * Leaner than the old `DebugScreenScreenshotTest` by design (`TODO.md` Phase
 * 4): this screen no longer owns the permission-setup rows, so what is left to
 * cover is what stayed — the required-permission banner, the tile banner, and
 * the Arm/Release/Settings controls. The states are still the point rather
 * than the pixels: `access` and `snoozing` stay null until the platform and
 * the record have answered, and guessing either is a visible lie.
 *
 * **Snooze/End snooze visibility is unchanged from the old `DebugScreen`** —
 * both render only once `access == PolicyAccess.GRANTED`, same gating as
 * before. `TODO.md` Phase 4 still tracks "how and when to show the buttons"
 * as an open question (maintainer, 2026-08-23); this split doesn't answer it,
 * deliberately, to keep this change scoped to moving the screens apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `nothing read still offers the way out`() {
        capture("main-screen-reading.png") {
            MainScreen(
                access = null,
                tileAdded = null,
                tileBannerDismissed = true,
                snoozing = null,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        // No banner yet — unread is not "missing" — and, same as before the
        // split, neither button renders until access reads granted.
        composeRule.onNodeWithText("Snoozemo").assertExists()
        composeRule.onNodeWithText("Do Not Disturb access needed").assertDoesNotExist()
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
        composeRule.onNodeWithText("End snooze").assertDoesNotExist()
    }

    @Test
    fun `missing access shows the banner and hides the arm controls`() {
        var opened = 0

        capture("main-screen-access-missing.png") {
            MainScreen(
                access = PolicyAccess.DENIED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = { opened++ },
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access needed").assertExists()
        composeRule.onNodeWithText("Snoozemo can't snooze without it").assertExists()
        // Same gating the old DebugScreen had — neither button is a stray
        // affordance while access is missing.
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
        composeRule.onNodeWithText("End snooze").assertDoesNotExist()
        // The banner's only job is routing to the interstitial — it does not
        // allow anything itself.
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `missing access shows the banner in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("main-screen-access-missing-dark.png") {
            MainScreen(
                access = PolicyAccess.DENIED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access needed").assertExists()
    }

    @Test
    fun `granted and idle offers to arm`() {
        capture("main-screen-idle.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access needed").assertDoesNotExist()
        composeRule.onNodeWithText("Snooze").assertIsEnabled()
        composeRule.onNodeWithText("End snooze").assertIsEnabled()
        composeRule.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun `granted and idle in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("main-screen-idle-dark.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsEnabled()
    }

    @Test
    fun `a running snooze cannot be armed over`() {
        capture("main-screen-snoozing.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsNotEnabled()
        composeRule.onNodeWithText("End snooze").assertIsEnabled()
        composeRule.onNodeWithText("Ends when you leave").assertExists()
        composeRule.onNodeWithText("3h 40m left").assertExists()
    }

    @Test
    fun `a Wi-Fi-only snooze says so`() {
        capture("main-screen-snoozing-wifi-only.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.WIFI_ONLY,
                remaining = Duration.ofMinutes(45),
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Wi-Fi only").assertExists()
        // Under an hour left, so the minutes-only form — no "0h" leaking in.
        composeRule.onNodeWithText("45m left").assertExists()
    }

    @Test
    fun `a duration-only snooze says so`() {
        capture("main-screen-snoozing-timer-only.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.DURATION_ONLY,
                remaining = Duration.ofHours(8),
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Timer only").assertExists()
        composeRule.onNodeWithText("8h 0m left").assertExists()
    }

    @Test
    fun `a first run leads with the tile`() {
        var added = 0
        var dismissed = 0

        capture("main-screen-tile-banner.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = false,
                tileBannerDismissed = false,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = { added++ },
                onDismissTileBanner = { dismissed++ },
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        // The screen pushes toward the tile rather than listing it as one
        // option among equals (SPEC.md §4.2) — the banner is the only element
        // here that says *why*. The permanent row it used to sit above now
        // lives on SettingsScreen instead.
        composeRule.onNodeWithText("Add tile").performClick()
        assertEquals(1, added)
        composeRule.onNodeWithText("Don't ask again").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a refused add-tile request is said on this banner too`() {
        // Not only on SettingsScreen's permanent tile row (Codex, PR #82) —
        // the tap that failed happened here, on the banner, and a failure
        // that only shows up on a screen the user hasn't opened reads as
        // this tap having done nothing.
        capture("main-screen-tile-banner-refused.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = false,
                tileBannerDismissed = false,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                settingsFailure = SetupRowId.TILE,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Couldn't add the tile").assertExists()
    }

    @Test
    fun `a dismissed tile banner does not come back`() {
        capture("main-screen-idle.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = false,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Snooze from the shade").assertDoesNotExist()
    }

    @Test
    fun `a failure is said, not swallowed`() {
        capture("main-screen-outcome.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = "Couldn't snooze",
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Couldn't snooze").assertExists()
    }

    @Test
    fun `a pinned crash raises the banner here, above even the access banner`() {
        var shared = 0
        var dismissed = 0

        capture("main-screen-crash-banner.png") {
            MainScreen(
                access = PolicyAccess.DENIED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = true,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = { shared++ },
                onDismissCrash = { dismissed++ },
            )
        }

        composeRule.onNodeWithText("Snoozemo crashed").assertExists()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, shared)
    }

    @Test
    fun `the crash banner disables its own Share button while a share is running`() {
        // Same gate as the Settings row's (`DebugReport.shareInFlight`) —
        // this banner's Share button is a second route to the same call, so
        // it has to be gated too or the tap it prevents could still be made
        // from here.
        var shared = 0

        capture("main-screen-crash-banner-sharing.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = true,
                shareFailed = false,
                dismissFailed = false,
                sharing = true,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = { shared++ },
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Sharing…").assertIsNotEnabled()
        composeRule.onNodeWithText("Sharing…").performClick()
        assertEquals("a disabled button must not fire its action", 0, shared)
    }

    @Test
    fun `a share that fails from the crash banner says so on the banner itself`() {
        // The banner's own Share button reaches the same DebugReport.share
        // call the permanent Settings row uses — a failure from this one
        // must render here too, not only on a screen the user has not
        // navigated to (Codex, PR #89).
        capture("main-screen-crash-banner-share-failed.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = true,
                shareFailed = true,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Snoozemo crashed").assertExists()
        composeRule.onNodeWithText("Couldn't share the debug log").assertExists()
    }

    @Test
    fun `a dismiss that fails from the crash banner says so on the banner itself`() {
        // A refused consume leaves crashPending correctly true (the pin
        // really is still there) — but silently, which reads as the tap
        // having done nothing at all (Codex, PR #89).
        capture("main-screen-crash-banner-dismiss-failed.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = true,
                shareFailed = false,
                dismissFailed = true,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Snoozemo crashed").assertExists()
        composeRule.onNodeWithText("Couldn't dismiss — try again").assertExists()
    }

    @Test
    fun `no crash pinned, no banner`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Snoozemo crashed").assertDoesNotExist()
    }

    @Test
    fun `the Settings button opens SettingsScreen`() {
        var opened = 0

        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = { opened++ },
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a short window still reaches the way out`() {
        // Landscape, which is the constrained case: title, banner, the status
        // line and three controls do not fit, and an unscrolled column clips
        // whatever is last — which is `End snooze`. Manual exit is "always
        // available, always instant" (SPEC.md §7), so losing it to a window
        // shape is the one failure this screen may not have. The status line
        // is included (not null) so this covers the worst case honestly —
        // real content, not an empty stand-in that scrolls easier than the
        // real screen ever will.
        RuntimeEnvironment.setQualifiers("w914dp-h411dp-420dpi")

        capture("main-screen-short-window.png", widthPx = 2400, heightPx = 1080) {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("End snooze").performScrollTo().assertIsEnabled()
    }

    /**
     * Renders [content] the way `MainActivity` does and records it under
     * [name] when a name is given.
     *
     * Theme **and** `Surface`, because both are load-bearing and only one of
     * them is obvious. `SnoozemoTheme` reads `isSystemInDarkTheme()`, so a
     * `+night` qualifier set before this runs is the whole of the difference
     * between the light and dark variants. The `Surface` is what paints the
     * themed background and sets the content color the `Text`s inherit —
     * without it Compose falls back to black text, which renders identically in
     * both variants and would have made the dark snapshots look like a theming
     * bug in the app rather than a missing wrapper in the test.
     */
    private fun capture(
        name: String? = null,
        widthPx: Int = 1080,
        heightPx: Int = 2400,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            SnoozemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.waitForIdle()
        name?.let { captureSnapshot(it, widthPx, heightPx) }
    }

    /**
     * Draws the activity's window into a PNG.
     *
     * Measured and laid out explicitly at the device size: Robolectric's window
     * has no real surface, so an unmeasured decor view captures as an empty
     * bitmap. Same helper shape as the sibling Simmo repo's.
     */
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
