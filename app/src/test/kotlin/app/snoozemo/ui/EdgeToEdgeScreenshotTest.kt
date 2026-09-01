package app.snoozemo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.R
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.ZenRuleState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen under system bars and a display cutout, light and dark.
 *
 * Every window in this app is drawn edge to edge whether it asks to be or not —
 * Android 15 made that the behavior for anything targeting SDK 35 and up, and
 * Android 16 removed the opt-out — so "handles insets" is not a polish item but
 * the difference between a readable screen and a title sitting under the status
 * bar, which is how this was reported.
 *
 * Robolectric reports no insets of its own, so each test dispatches a set: a
 * status bar and cutout at the top, a gesture bar at the bottom. That makes the
 * absence of inset handling *visible* to a test, which is the only way this
 * regresses quietly — with zero insets, a screen that ignores them and one that
 * respects them render identically.
 *
 * Most of the coverage runs against `PermissionsScreen` — the richest column,
 * since `TODO.md` Phase 4 split what was one screen into three — with one test
 * confirming `MainScreen` applies the same `safeDrawingPadding` placement,
 * since that regression (a title under the status bar) is the one this screen
 * would actually show first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeToEdgeScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the screen starts below the status bar and cutout`() {
        capture("edge-to-edge-insets.png") { GrantedPermissionsScreen() }

        // The reported defect: the title drew under the status bar, so the one
        // word identifying the screen was behind the clock.
        val top = composeRule.onNodeWithText("Permissions").getUnclippedBoundsInRoot().top
        assertTrue(
            "The title drew at $top, inside the ${statusBarInset()} status bar",
            top >= statusBarInset(),
        )
    }

    @Test
    fun `every row clears the status bar and cutout`() {
        capture { GrantedPermissionsScreen() }

        // Not just the first child: `safeDrawingPadding` sits outside the
        // scroll for this reason, and moving it inside would pad the content
        // while letting each row slide under the bar as the user scrolls.
        for (text in listOf("Permissions", "Do Not Disturb access", "Notifications", "Location")) {
            val top = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot().top
            assertTrue("`$text` drew at $top, inside the status bar", top >= statusBarInset())
        }
    }

    @Test
    fun `the way out clears the navigation bar`() {
        // A window short enough that the column overflows it with or without
        // the insets applied, so the last control only arrives by scrolling.
        // That is the case that catches an inset applied *inside* the scroll
        // instead of around it: the resting screen looks right either way,
        // and it is the scrolled-to-bottom position that slides under the
        // gesture bar.
        RuntimeEnvironment.setQualifiers("w411dp-h320dp-420dpi")

        capture { GrantedPermissionsScreen() }
        composeRule.onNodeWithText("Done").performScrollTo()

        // Done is never conditional on every row being resolved (D7, "fail
        // open"), so it has to be reachable whatever the window shape — a
        // gesture bar over the button is the same loss as a window too short
        // to show it — the tap goes to the system, not to Snoozemo.
        val bottom = composeRule.onNodeWithText("Done").getUnclippedBoundsInRoot().bottom
        val safeBottom = composeRule.onRoot().getUnclippedBoundsInRoot().bottom - navigationBarInset()
        assertTrue(
            "`Done` drew down to $bottom, under the navigation bar at $safeBottom",
            bottom <= safeBottom,
        )
    }

    @Test
    fun `the screen clears the bars in dark too`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("edge-to-edge-insets-dark.png") { GrantedPermissionsScreen() }

        val top = composeRule.onNodeWithText("Permissions").getUnclippedBoundsInRoot().top
        assertTrue("The title drew at $top, inside the status bar", top >= statusBarInset())
    }

    @Test
    fun `MainScreen starts below the status bar and cutout too`() {
        // The regression that matters most on this screen specifically: it is
        // the one the user actually lands on, so a title behind the status bar
        // here is the first thing anyone would notice.
        capture { GrantedMainScreen() }

        val top = composeRule.onNodeWithText("Snoozemo").getUnclippedBoundsInRoot().top
        assertTrue(
            "The title drew at $top, inside the ${statusBarInset()} status bar",
            top >= statusBarInset(),
        )
    }

    @Test
    fun `dark mode paints a dark screen`() {
        RuntimeEnvironment.setQualifiers("+night")

        // The window is transparent behind the system bars now, so the app's
        // own background is what shows through them. A theme that resolved
        // light in dark mode would put white behind the shade's white icons.
        assertTrue(
            "The dark scheme's background is not dark",
            resolvedScheme().background.luminance() < 0.5f,
        )
    }

    @Test
    fun `light mode paints a light screen`() {
        assertTrue(
            "The light scheme's background is not light",
            resolvedScheme().background.luminance() > 0.5f,
        )
    }

    @Test
    fun `the tile's transparent window follows dark mode`() {
        RuntimeEnvironment.setQualifiers("+night")

        // The trampoline's theme (SPEC.md §6.9) used to be pinned to the light
        // framework theme in both configurations, because it named that theme
        // as its parent directly. It is parented on the app's own theme now, so
        // it inherits the values-night override — and the transparent window it
        // needs survives that, which is the half a re-parenting can quietly
        // break.
        assertTrue(
            "The transparent theme's background is not dark in night mode",
            transparentThemeAttribute(android.R.attr.colorBackground).luminanceOfArgb() < 0.5f,
        )
        assertEquals(
            "The trampoline's window is no longer transparent",
            0,
            transparentThemeAttribute(android.R.attr.windowBackground),
        )
    }

    @Test
    fun `the tile's transparent window is light by day`() {
        assertTrue(
            "The transparent theme's background is not light in day mode",
            transparentThemeAttribute(android.R.attr.colorBackground).luminanceOfArgb() > 0.5f,
        )
        assertEquals(
            "The trampoline's window is no longer transparent",
            0,
            transparentThemeAttribute(android.R.attr.windowBackground),
        )
    }

    /** [attribute] as the trampoline's theme resolves it right now. */
    private fun transparentThemeAttribute(attribute: Int): Int {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val theme = context.resources.newTheme()
        theme.applyStyle(R.style.Theme_Snoozemo_Transparent, true)
        val values = theme.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            values.getColor(0, -1)
        } finally {
            values.recycle()
        }
    }

    private fun Int.luminanceOfArgb(): Float = Color(this).luminance()

    /** `PermissionsScreen` with everything granted — the longest column it can show. */
    @Composable
    private fun GrantedPermissionsScreen() {
        PermissionsScreen(
            access = PolicyAccess.GRANTED,
            notifications = NotificationPermission.GRANTED,
            notificationsReachTheUser = true,
            location = LocationPermission.GRANTED,
            // A settled screen: the access row waits for the rule check as well
            // as the grant, so without a state it would not render at all and
            // this test's edge-to-edge assertions would have no row to measure.
            ruleState = ZenRuleState.READY,
            settingsFailure = null,
            onAccessRow = {},
            onNotificationsRow = {},
            onLocationRow = {},
            onDone = {},
        )
    }

    /** `MainScreen` idle, with nothing missing and nothing to say. */
    @Composable
    private fun GrantedMainScreen() {
        MainScreen(
            access = PolicyAccess.GRANTED,
            tileAdded = true,
            tileBannerDismissed = true,
            snoozing = false,
            trackingMode = null,
            remaining = null,
            degradation = null,
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

    /** What [SnoozemoTheme] resolves to under the qualifiers currently set. */
    private fun resolvedScheme(): ColorScheme {
        lateinit var scheme: ColorScheme
        composeRule.setContent { SnoozemoTheme { scheme = MaterialTheme.colorScheme } }
        composeRule.waitForIdle()
        return scheme
    }

    private fun statusBarInset(): Dp = with(composeRule.density) { STATUS_BAR_PX.toDp() }

    private fun navigationBarInset(): Dp = with(composeRule.density) { NAVIGATION_BAR_PX.toDp() }

    /**
     * Renders [content] the way `MainActivity` does, under system bars and a
     * cutout, and records it under [name] when a name is given.
     *
     * Theme **and** `Surface`, for the reason the sibling suite spells out: the
     * `Surface` is what paints the themed background and sets the content color
     * the `Text`s inherit, so wrapping in the theme alone renders a dark
     * snapshot over a light window and reads as a theming bug.
     */
    private fun capture(name: String? = null, content: @Composable () -> Unit) {
        composeRule.setContent {
            SnoozemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.waitForIdle()
        applyInsets()
        name?.let { captureSnapshot(it) }
    }

    /**
     * Hands the window a phone's worth of insets: a status bar with a cutout
     * behind it, and a gesture navigation bar.
     *
     * Dispatched onto the content view rather than mocked, so what the test
     * exercises is the same path a device uses — the framework walks the
     * hierarchy and Compose's own listener turns the result into the padding
     * `safeDrawingPadding` reads. Sent after the content is set because that is
     * when there is a listener to receive it.
     */
    private fun applyInsets() {
        val content = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                Insets.of(0, STATUS_BAR_PX, 0, NAVIGATION_BAR_PX),
            )
            // Same edge as the status bar, which is the common case (a punch
            // hole or a notch sits in it) and still exercises the difference
            // between `safeDrawing` and the bars alone.
            .setInsets(
                WindowInsetsCompat.Type.displayCutout(),
                Insets.of(0, STATUS_BAR_PX, 0, 0),
            )
            .build()
            .toWindowInsets()
        composeRule.runOnUiThread { content.dispatchApplyWindowInsets(insets) }
        composeRule.waitForIdle()
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

    private companion object {
        /** A phone's status bar at 420dpi, with a cutout sharing the edge. */
        const val STATUS_BAR_PX = 126

        /** A gesture-navigation bar at the same density. */
        const val NAVIGATION_BAR_PX = 63
    }
}
