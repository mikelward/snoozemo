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
import app.snoozemo.R
import com.github.takahirom.roborazzi.captureRoboImage
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The open-source attribution page, reached from the Settings foot.
 *
 * The library list is built synchronously from the committed
 * `res/raw/aboutlibraries.json` so the snapshot is deterministic — production
 * loads the same JSON asynchronously through `rememberLibraries`. CI records
 * under `testPlayDebugUnitTest`, so the recorded baseline is the `play`
 * flavor's list; `direct`'s is a strict subset (no Play libraries), and the
 * assertions below name only components both flavors bundle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicensesScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun libraries(): Libs =
        Libs.Builder().withJson(composeRule.activity, R.raw.aboutlibraries).build()

    @Test
    fun `every bundled component is listed, by name alone`() {
        capture("licenses-screen.png") {
            LicensesContent(libraries = libraries())
        }

        composeRule.onNodeWithText("Open source licenses").assertExists()
        composeRule.onNodeWithText("Activity").assertExists()
        // Name only: the version rides in the details dialog, so a
        // 90-row list stays scannable.
        composeRule.onNodeWithText("Version 1.13.0").assertDoesNotExist()
    }

    @Test
    fun `tapping a component shows its version and links out to its license`() {
        var openedUrl: String? = null

        capture {
            LicensesContent(
                libraries = libraries(),
                onOpenLicenseUrl = { openedUrl = it; true },
            )
        }

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertExists()
        // Apache-2.0 §4 asks for attribution, and the license name alone does
        // not carry it — the dialog names who wrote the component too.
        composeRule.onNodeWithText("By The Android Open Source Project").assertExists()

        // The bundled export carries no license text, so the license name is
        // a link to the full text rather than the text itself.
        composeRule.onNodeWithText("Apache License 2.0").performClick()
        composeRule.runOnIdle {
            assertEquals("https://spdx.org/licenses/Apache-2.0.html", openedUrl)
        }

        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertDoesNotExist()
    }

    /**
     * A device with nothing able to open a web link is a real case, and the
     * dialog has to say so — an absorbed tap is indistinguishable from a
     * broken app (principle 2).
     */
    @Test
    fun `a link that cannot open says so, rather than doing nothing`() {
        capture("licenses-screen-link-failed.png") {
            LicensesContent(
                libraries = libraries(),
                onOpenLicenseUrl = { false },
            )
        }

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Couldn't open the license").assertDoesNotExist()

        composeRule.onNodeWithText("Apache License 2.0").performClick()
        composeRule.onNodeWithText("Couldn't open the license").assertExists()
    }

    @Test
    fun `Done leaves the page`() {
        var back = 0

        capture {
            LicensesContent(libraries = libraries(), onBack = { back++ })
        }

        composeRule.onNodeWithText("Done").performClick()
        assertEquals(1, back)
    }

    /**
     * Nothing is listed until the JSON has been parsed, and the page still
     * paints its title and its way out — principle 5: the screen appears at
     * once and fills in, rather than holding the frame on the parse.
     */
    @Test
    fun `the page renders before the list has loaded`() {
        capture("licenses-screen-loading.png") {
            LicensesContent(libraries = null)
        }

        composeRule.onNodeWithText("Open source licenses").assertExists()
        composeRule.onNodeWithText("Done").assertExists()
    }

    @Test
    fun `listed in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("licenses-screen-dark.png") {
            LicensesContent(libraries = libraries())
        }

        composeRule.onNodeWithText("Open source licenses").assertExists()
    }

    /**
     * The POM's declared developers are the usual source of an authors line,
     * but plenty of components name only the organization that published
     * them, and a few name nobody at all. The fallback and the omission are
     * both driven from a fixture rather than the bundled export, which today
     * happens to carry a developer for every component that names anyone.
     */
    @Test
    fun `authors fall back to the publishing organization, and are omitted when nobody is named`() {
        capture {
            LicensesContent(libraries = Libs.Builder().withJson(ATTRIBUTION_FIXTURE).build())
        }

        composeRule.onNodeWithText("Organization only").performClick()
        composeRule.onNodeWithText("By Example Organization").assertExists()
        composeRule.onNodeWithText("Close").performClick()

        composeRule.onNodeWithText("Nobody named").performClick()
        composeRule.onNodeWithText("Version 3.0.0").assertExists()
        composeRule.onNodeWithText("By ", substring = true).assertDoesNotExist()
    }

    /**
     * Two developers on one component, so the line reads as a list rather
     * than as whichever name happened to come first.
     */
    @Test
    fun `several authors are listed together`() {
        capture {
            LicensesContent(libraries = Libs.Builder().withJson(ATTRIBUTION_FIXTURE).build())
        }

        composeRule.onNodeWithText("Two developers").performClick()
        composeRule.onNodeWithText("By Ada Example, Grace Example").assertExists()
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

/**
 * A hand-written export covering the attribution shapes the bundled one does
 * not: an organization with no developer, no attribution at all, and more
 * than one developer. Stock stand-in names throughout — nothing here is
 * anybody's.
 */
private const val ATTRIBUTION_FIXTURE = """
{
  "libraries": [
    {
      "uniqueId": "com.example:organization-only",
      "artifactVersion": "1.0.0",
      "name": "Organization only",
      "developers": [],
      "organization": { "name": "Example Organization" },
      "licenses": ["Apache-2.0"]
    },
    {
      "uniqueId": "com.example:two-developers",
      "artifactVersion": "2.0.0",
      "name": "Two developers",
      "developers": [{ "name": "Ada Example" }, { "name": "Grace Example" }],
      "licenses": ["Apache-2.0"]
    },
    {
      "uniqueId": "com.example:nobody-named",
      "artifactVersion": "3.0.0",
      "name": "Nobody named",
      "developers": [],
      "licenses": ["Apache-2.0"]
    }
  ],
  "licenses": {
    "Apache-2.0": {
      "name": "Apache License 2.0",
      "url": "https://spdx.org/licenses/Apache-2.0.html",
      "hash": "Apache-2.0",
      "spdxId": "Apache-2.0"
    }
  }
}
"""
