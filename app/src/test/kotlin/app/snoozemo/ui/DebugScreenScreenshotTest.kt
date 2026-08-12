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
import app.snoozemo.core.PolicyAccess
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app screen in each state it can actually be in, light and dark.
 *
 * The states are the point rather than the pixels. This screen is a *repair*
 * surface as much as an onboarding one — a tile-first user may only ever reach
 * it after an arm that didn't take (`TODO.md` Phase 2) — and what it must never
 * do is answer a question it hasn't read yet: both `access` and `snoozing` are
 * null until the platform and the record have been asked, and guessing either
 * way is a visible lie (offering to arm over a running snooze, or telling
 * someone who granted access months ago that they haven't). Each null state is
 * recorded here so a refactor that quietly picks a default shows up as a diff.
 *
 * Capture is skipped unless `-Proborazzi.test.record` / `-Proborazzi.test.verify`
 * is passed, so `./gradlew test` still runs these as ordinary render-and-assert
 * tests without rewriting the committed PNGs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebugScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `access unknown shows nothing to act on`() {
        capture("debug-screen-reading.png") {
            DebugScreen(
                access = null,
                snoozing = null,
                lastOutcome = null,
                onGrantAccess = {},
                onArm = {},
                onRelease = {},
            )
        }

        // The title is all there is: no status line, no buttons. A screen that
        // rendered either before the readings landed would be stating something
        // it does not know.
        composeRule.onNodeWithText("Snoozemo").assertExists()
        composeRule.onNodeWithText("Grant access").assertDoesNotExist()
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
    }

    @Test
    fun `access denied offers the grant button`() {
        capture("debug-screen-access-denied.png") {
            DebugScreen(
                access = PolicyAccess.DENIED,
                snoozing = null,
                lastOutcome = null,
                onGrantAccess = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Grant access").assertExists()
    }

    @Test
    fun `access denied in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("debug-screen-access-denied-dark.png") {
            DebugScreen(
                access = PolicyAccess.DENIED,
                snoozing = null,
                lastOutcome = null,
                onGrantAccess = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Grant access").assertExists()
    }

    @Test
    fun `granted and idle offers to arm`() {
        capture("debug-screen-idle.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                snoozing = false,
                lastOutcome = null,
                onGrantAccess = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsEnabled()
    }

    @Test
    fun `granted and idle in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("debug-screen-idle-dark.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                snoozing = false,
                lastOutcome = null,
                onGrantAccess = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsEnabled()
    }

    @Test
    fun `a running snooze cannot be armed over`() {
        capture("debug-screen-snoozing.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                snoozing = true,
                lastOutcome = null,
                onGrantAccess = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsNotEnabled()
        // Ending stays live whatever else is true: manual exit is "always
        // available, always instant" (SPEC.md §7).
        composeRule.onNodeWithText("End snooze").assertIsEnabled()
    }

    @Test
    fun `an unread record cannot be armed over either`() {
        // Unknown is not "nothing is running". Arming here would take the
        // deadline the user was already promised and start it again.
        capture("debug-screen-record-unread.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                snoozing = null,
                lastOutcome = null,
                onGrantAccess = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsNotEnabled()
        composeRule.onNodeWithText("End snooze").assertIsEnabled()
    }

    @Test
    fun `a failure is said, not swallowed`() {
        capture("debug-screen-outcome.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                snoozing = false,
                lastOutcome = "Couldn't snooze",
                onGrantAccess = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Couldn't snooze").assertExists()
    }

    /**
     * Renders [content] the way `MainActivity` does and records it under [name].
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
    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            SnoozemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.waitForIdle()
        captureSnapshot(name)
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
