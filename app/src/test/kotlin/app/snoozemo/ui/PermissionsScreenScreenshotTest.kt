package app.snoozemo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.snoozemo.core.CalendarPermission
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The interstitial in each state it can actually be in, light and dark.
 *
 * Split out of `DebugScreenScreenshotTest` (`TODO.md` Phase 4): this is now
 * the one screen that owns the DND, notification and location setup rows, so
 * what moved here is everything about *those three capabilities* — the
 * arm/release control and the tile stayed behind on `MainScreen` and
 * `SettingsScreen` respectively. Same discipline as before: `access`,
 * `notifications` and `location` stay null until the platform has actually
 * been asked, and a row offers a button exactly while something is left to do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissionsScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `nothing read shows nothing to act on`() {
        capture("permissions-screen-reading.png") {
            PermissionsScreen(
                access = null,
                notifications = null,
                notificationsReachTheUser = true,
                location = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Permissions").assertExists()
        composeRule.onNodeWithText("Do Not Disturb access").assertDoesNotExist()
        composeRule.onNodeWithText("Notifications").assertDoesNotExist()
        composeRule.onNodeWithText("Location").assertDoesNotExist()
        // Done is never conditional on a reading landing (D7, "fail open").
        composeRule.onNodeWithText("Done").assertExists()
    }

    @Test
    fun `access denied says so on a row you can tap`() {
        var opened = 0

        capture("permissions-screen-access-denied.png") {
            PermissionsScreen(
                access = PolicyAccess.DENIED,
                // Left unread: an askable notifications row would render its
                // own `Allow` button too, and make `onNodeWithText("Allow")`
                // ambiguous about which row this test is clicking.
                notifications = null,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                settingsFailure = null,
                onAccessRow = { opened++ },
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozemo can't snooze without it").assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `two rows both offering Allow still announce which permission each opens`() {
        // Same visible word on every row (SPEC.md §5.2), so a screen reader
        // needs a capability-specific accessible name to tell the buttons
        // apart — flagged by Codex on PR #103.
        var openedAccess = 0
        var openedLocation = 0

        capture {
            PermissionsScreen(
                access = PolicyAccess.DENIED,
                notifications = null,
                notificationsReachTheUser = true,
                location = LocationPermission.ASKABLE,
                settingsFailure = null,
                onAccessRow = { openedAccess++ },
                onNotificationsRow = {},
                onLocationRow = { openedLocation++ },
                onDone = {},
            )
        }

        composeRule.onAllNodesWithText("Allow").assertCountEquals(2)
        composeRule.onNodeWithContentDescription("Allow Do Not Disturb access").performClick()
        assertEquals(1, openedAccess)
        composeRule.onNodeWithContentDescription("Allow Location").performClick()
        assertEquals(1, openedLocation)
    }

    @Test
    fun `access denied in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("permissions-screen-access-denied-dark.png") {
            PermissionsScreen(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access").assertExists()
    }

    @Test
    fun `notifications the system will not prompt for point at Settings`() {
        capture("permissions-screen-notifications-blocked.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.BLOCKED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozemo can't show what a snooze is doing").assertExists()
        composeRule.onNodeWithText("Allow").assertExists()
    }

    @Test
    fun `a blocked channel is not reported as allowed`() {
        var tapped = 0

        capture("permissions-screen-notifications-muted.png") {
            PermissionsScreen(
                // Left unread: an allowed access row shares "Allowed" with
                // notifications now (AGENTS.md's standardized status text)
                // and would otherwise leave a second `Allowed` on screen and
                // make `assertDoesNotExist()` ambiguous about which row it
                // means — this test is about the notifications row
                // specifically. Same reason location stays unread below.
                access = null,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = false,
                location = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = { tapped++ },
                onLocationRow = {},
                onDone = {},
            )
        }

        // The permission is held, so the old reading said `Allowed` — over a
        // snooze whose countdown and end reason were both being dropped.
        composeRule.onNodeWithText("Snoozemo can't show what a snooze is doing").assertExists()
        composeRule.onNodeWithText("Allowed").assertDoesNotExist()
        // And the row still offers the fix, which is the half a status line
        // alone would lose: held-but-blocked is repairable, and the button is
        // what says so.
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun `location askable opens the disclosure, not a system dialog directly`() {
        var tapped = 0

        capture("permissions-screen-location-askable.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.ASKABLE,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = { tapped++ },
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozemo can't tell when you've left").assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun `granted rows read Allowed, the standardized status word`() {
        capture("permissions-screen-idle.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                // Left unread: a granted notifications row would leave a
                // third `Allowed` on screen and make the count below need
                // updating for reasons that have nothing to do with this test.
                notifications = null,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        // AGENTS.md: status text is either "Allowed" or the capability's own
        // missing-state copy — location used to say "Tracking your place",
        // and Do Not Disturb access used to say "Granted".
        composeRule.onAllNodesWithText("Allowed").assertCountEquals(2)
        composeRule.onNodeWithText("Tracking your place").assertDoesNotExist()
        composeRule.onNodeWithText("Granted").assertDoesNotExist()
        composeRule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun `everything granted in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("permissions-screen-idle-dark.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Done").assertExists()
    }

    @Test
    fun `Done returns without requiring every row be resolved`() {
        var done = 0

        capture {
            PermissionsScreen(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                location = LocationPermission.ASKABLE,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = { done++ },
            )
        }

        composeRule.onNodeWithText("Done").performClick()
        assertEquals(1, done)
    }

    @Test
    fun `a refused Settings trip is said on the row that was tapped`() {
        capture("permissions-screen-settings-refused.png") {
            PermissionsScreen(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                settingsFailure = SetupRowId.DND,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Couldn't open Settings").assertExists()
        // And under the row that was tapped, not the one below it: the two
        // rows are adjacent and identical in shape, so a message under the
        // wrong one sends the user to fix a capability that is not broken.
        val row = composeRule.onNodeWithText("Do Not Disturb access").getUnclippedBoundsInRoot()
        val message = composeRule.onNodeWithText("Couldn't open Settings").getUnclippedBoundsInRoot()
        val nextRow = composeRule.onNodeWithText("Notifications").getUnclippedBoundsInRoot()
        assertTrue(
            "The failure drew at ${message.top}, outside its own row",
            message.top > row.top && message.top < nextRow.top,
        )
    }

    @Test
    fun `a failure does not outlive the offer it belongs to`() {
        // A refused Settings trip, and then access arrives anyway — from
        // another route into Settings, or an administrator, while this
        // screen is up. The row loses its button at that moment, so a
        // message about a tap that could not happen would sit under
        // `Allowed` with nothing left to clear it (flagged by Codex on PR
        // #21, on the screen this split out of).
        capture("permissions-screen-idle.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                settingsFailure = SetupRowId.DND,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onAllNodesWithText("Allowed").assertCountEquals(3)
        composeRule.onNodeWithText("Couldn't open Settings").assertDoesNotExist()
    }

    @Test
    fun `the calendar row states what is lost, not that anything is broken`() {
        var tapped = 0

        capture("permissions-screen-calendar-askable.png") {
            PermissionsScreen(
                // The three rows above left unread, so the single `Allow` this
                // test clicks is unambiguously the calendar's.
                access = null,
                notifications = null,
                notificationsReachTheUser = true,
                location = null,
                calendar = CalendarPermission.ASKABLE,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onCalendarRow = { tapped++ },
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Snoozemo can't offer your next meeting's end time").assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun `a granted calendar reads Allowed and offers nothing`() {
        capture {
            PermissionsScreen(
                access = null,
                notifications = null,
                notificationsReachTheUser = true,
                location = null,
                calendar = CalendarPermission.GRANTED,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onCalendarRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Allowed").assertExists()
        composeRule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun `an unread calendar draws no row at all`() {
        // The default, and what every screenshot recorded before this row
        // existed still renders: unread is not "denied", the same discipline
        // the three rows above it keep.
        capture {
            PermissionsScreen(
                access = null,
                notifications = null,
                notificationsReachTheUser = true,
                location = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Calendar").assertDoesNotExist()
    }

    @Test
    fun `a pinned crash raises the banner here too, above everything else`() {
        // On a cold start with Do Not Disturb access missing,
        // MainActivity.applyAccess routes straight here — the actual
        // first-landed screen in that case, not MainScreen — so a crash from
        // before that same cold start has to be reachable from here or it
        // goes unseen until the user finishes this screen and navigates back
        // on their own (Codex, PR #89, fresh evidence).
        var shared = 0

        capture("permissions-screen-crash-banner.png") {
            PermissionsScreen(
                access = PolicyAccess.DENIED,
                notifications = null,
                notificationsReachTheUser = true,
                location = null,
                settingsFailure = null,
                crashPending = true,
                shareFailed = false,
                dismissFailed = false,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
                onShareDebugLog = { shared++ },
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Share").performClick()
        assertEquals(1, shared)
    }

    /**
     * Renders [content] the way `MainActivity` does and records it under
     * [name] when a name is given.
     *
     * Theme **and** `Surface`, for the reason the sibling suites all spell
     * out: without the `Surface`, Compose falls back to black text that
     * renders identically in both variants, so a dark snapshot would look
     * like a theming bug in the app rather than a missing wrapper in the test.
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
