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
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
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
 * The app screen in each state it can actually be in, light and dark.
 *
 * The states are the point rather than the pixels. This screen is a *repair*
 * surface as much as an onboarding one — a tile-first user may only ever reach
 * it after an arm that didn't take (`TODO.md` Phase 2) — and what it must never
 * do is answer a question it hasn't read yet: `access`, `notifications` and
 * `snoozing` are all null until the platform and the record have been asked,
 * and guessing any of them is a visible lie (offering to arm over a running
 * snooze, or telling someone who granted access months ago that they haven't).
 * Each null state is recorded here so a refactor that quietly picks a default
 * shows up as a diff.
 *
 * The other invariant these tests hold is that **every row that states a
 * problem is the thing you tap**. The status used to be inert text beside a
 * separate button, so tapping the sentence naming the problem did nothing.
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
    fun `nothing read shows nothing to act on`() {
        capture("debug-screen-reading.png") {
            DebugScreen(
                access = null,
                notifications = null,
                notificationsReachTheUser = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        // The title is all there is: no rows, no buttons. A screen that
        // rendered either before the readings landed would be stating something
        // it does not know.
        composeRule.onNodeWithText("Snoozemo").assertExists()
        composeRule.onNodeWithText("Do Not Disturb access").assertDoesNotExist()
        composeRule.onNodeWithText("Notifications").assertDoesNotExist()
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
    }

    @Test
    fun `access denied says so on a row you can tap`() {
        var opened = 0

        // The genuine first-run state: neither capability granted yet, and the
        // two rows sitting next to each other is exactly where they must not
        // look alike.
        capture("debug-screen-access-denied.png") {
            DebugScreen(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = { opened++ },
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        // The defect this flow was reworked for: the sentence that names the
        // problem is the target, not a label beside one.
        composeRule.onNodeWithText("Snoozemo can't snooze without it").performClick()
        assertEquals(1, opened)
        // And the two rows say different things about where the tap goes: Do
        // Not Disturb access is a Settings toggle with no in-app dialog
        // (SPEC.md §5.2), while notifications really is a runtime prompt.
        composeRule.onNodeWithText("Opens Settings").assertExists()
        composeRule.onNodeWithText("Tap to allow").assertExists()
        // Nothing to arm with: the controls only appear once access is granted.
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
    }

    @Test
    fun `access denied in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("debug-screen-access-denied-dark.png") {
            DebugScreen(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access").assertExists()
    }

    @Test
    fun `notifications can be allowed without leaving the app`() {
        var tapped = 0

        capture("debug-screen-notifications-askable.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = { tapped++ },
                onArm = {},
                onRelease = {},
            )
        }

        // The distinction the two rows exist to make legible: this one is a
        // runtime prompt that appears in place, the row above leaves for
        // Settings. Both are stated, side by side, in the same position.
        composeRule.onNodeWithText("Tap to allow").performClick()
        assertEquals(1, tapped)
        composeRule.onNodeWithText("Opens Settings").assertExists()
    }

    @Test
    fun `notifications the system will not prompt for point at Settings`() {
        capture("debug-screen-notifications-blocked.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.BLOCKED,
                notificationsReachTheUser = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        // Still stated as a problem, but no longer offering a prompt: the
        // system silently ignores the request by now, so `Tap to allow` would
        // be the dead tap this screen was fixed to remove.
        composeRule.onNodeWithText("Snoozemo can't show what a snooze is doing").assertExists()
        composeRule.onNodeWithText("Tap to allow").assertDoesNotExist()
    }

    @Test
    fun `notifications in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("debug-screen-notifications-askable-dark.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Tap to allow").assertExists()
    }

    @Test
    fun `granted and idle offers to arm`() {
        capture("debug-screen-idle.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsEnabled()
        // Both rows stay live once everything is granted. Neither is a dead
        // tap: one is where access gets turned back off, the other is where
        // notifications do.
        composeRule.onNodeWithText("Granted").assertHasClickAction()
        composeRule.onNodeWithText("Allowed").assertHasClickAction()
    }

    @Test
    fun `granted and idle in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("debug-screen-idle-dark.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
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
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                snoozing = true,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
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
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsNotEnabled()
        composeRule.onNodeWithText("End snooze").assertIsEnabled()
    }

    @Test
    fun `a short window still reaches the way out`() {
        // Landscape, which is the constrained case: title, two three-line rows
        // and two buttons do not fit, and an unscrolled column clips whatever
        // is last — which is `End snooze`. Manual exit is "always available,
        // always instant" (SPEC.md §7), so losing it to a window shape is the
        // one failure this screen may not have.
        RuntimeEnvironment.setQualifiers("w914dp-h411dp-420dpi")

        capture("debug-screen-short-window.png", widthPx = 2400, heightPx = 1080) {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                snoozing = true,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("End snooze").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `a refused Settings trip is said on the row that was tapped`() {
        capture("debug-screen-settings-refused.png") {
            DebugScreen(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = SetupRowId.DND,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        // Beside the row, not at the foot of a scrolling column where the user
        // is not looking. Without this the tap reads as doing nothing — the
        // defect this screen exists to remove, reintroduced by its own error
        // path.
        composeRule.onNodeWithText("Do Not Disturb access")
            .assertTextContains("Couldn't open Settings")
    }

    @Test
    fun `a blocked channel is not reported as allowed`() {
        capture("debug-screen-notifications-muted.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = false,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onArm = {},
                onRelease = {},
            )
        }

        // The permission is held, so the old reading said `Allowed` — over a
        // snooze whose countdown and end reason were both being dropped.
        composeRule.onNodeWithText("Notifications")
            .assertTextContains("Snoozemo can't show what a snooze is doing")
        composeRule.onNodeWithText("Allowed").assertDoesNotExist()
        // And it points at the one place that can fix a blocked channel: there
        // is no runtime prompt for this, the permission is already held.
        composeRule.onNodeWithText("Notifications").assertTextContains("Opens Settings")
    }

    @Test
    fun `a failure is said, not swallowed`() {
        capture("debug-screen-outcome.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                snoozing = false,
                lastOutcome = "Couldn't snooze",
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
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
