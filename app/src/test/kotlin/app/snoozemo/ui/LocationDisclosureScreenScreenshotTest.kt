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
 * The prominent disclosure Play requires before the location runtime dialogs
 * (`SPEC.md` §3.2) — a screen, not a dialog, because it has to be read rather
 * than dismissed with the reflex a system prompt gets. What is worth pinning
 * here: the copy states the *why* before either system dialog appears, and
 * `Continue` is the only thing that ever launches a request — this screen
 * never calls a permission API itself, which is what keeps
 * [MainActivity.beginLocationRequest] the one place that does.
 *
 * Capture is skipped unless `-Proborazzi.test.record` / `-Proborazzi.test.verify`
 * is passed, same as the app screen's own screenshot tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationDisclosureScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `continue launches the request, not the screen appearing`() {
        var continued = 0

        capture("location-disclosure.png") {
            LocationDisclosureScreen(onContinue = { continued++ }, onNotNow = {})
        }

        composeRule.onNodeWithText("Know when you've left").assertExists()
        // Says why before either system dialog does, and names both — the
        // in-app half of what Play's demonstration video has to show.
        composeRule.onNodeWithText(
            "Location is how Snoozemo tells you've left the place you snoozed at, " +
                "so Do Not Disturb turns off on its own. It stays on your phone — " +
                "nothing is collected or shared.",
        ).assertExists()
        composeRule.onNodeWithText(
            "Android will also ask to allow location \"All the time,\" so Snoozemo " +
                "can still notice while the app is closed.",
        ).assertExists()
        assertEquals(0, continued)
        composeRule.onNodeWithText("Continue").performClick()
        assertEquals(1, continued)
    }

    @Test
    fun `not now leaves without requesting anything`() {
        var continued = 0
        var declined = 0

        capture("location-disclosure.png") {
            LocationDisclosureScreen(onContinue = { continued++ }, onNotNow = { declined++ })
        }

        composeRule.onNodeWithText("Not now").performClick()
        assertEquals(1, declined)
        assertEquals(0, continued)
    }

    @Test
    fun `dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("location-disclosure-dark.png") {
            LocationDisclosureScreen(onContinue = {}, onNotNow = {})
        }

        composeRule.onNodeWithText("Continue").assertExists()
    }

    /** Same wrapper as [DebugScreenScreenshotTest.capture] — see that KDoc. */
    private fun capture(
        name: String,
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
        captureSnapshot(name, widthPx, heightPx)
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
