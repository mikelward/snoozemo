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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * The other invariant these tests hold is about the rows' one control:
 * **a row offers a button exactly while something is left to do**, carrying the
 * verb for it, and a capability that is already in place offers nothing. Both
 * halves are regressions waiting to happen — a button that survives the grant
 * is a tap with nothing behind it, and an action named after its route
 * (`Opens Settings`) reads as a description rather than an offer.
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
                tileAdded = null,
                tileBannerDismissed = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
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
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = { opened++ },
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        // The row states the problem and offers the fix beside it, and the
        // button is what carries the tap.
        composeRule.onNodeWithText("Snoozemo can't snooze without it").assertExists()
        composeRule.onNodeWithText("Grant").performClick()
        assertEquals(1, opened)
        // And the two verbs keep the one distinction worth carrying: Do Not
        // Disturb access is a Settings toggle with no in-app dialog (SPEC.md
        // §5.2), while notifications really is a runtime prompt.
        composeRule.onNodeWithText("Allow").assertExists()
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
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
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
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = { tapped++ },
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, tapped)
        // Access is granted in this state, so its row has nothing left to
        // offer and the only button on screen belongs to notifications.
        composeRule.onNodeWithText("Grant").assertDoesNotExist()
    }

    @Test
    fun `notifications the system will not prompt for point at Settings`() {
        capture("debug-screen-notifications-blocked.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.BLOCKED,
                notificationsReachTheUser = true,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        // Still stated as a problem, and still offered — the button is the
        // same `Allow` whichever route the tap takes, because what the user
        // wants is the same and the system has merely stopped showing its own
        // prompt (the row sends them to Settings instead).
        composeRule.onNodeWithText("Snoozemo can't show what a snooze is doing").assertExists()
        composeRule.onNodeWithText("Allow").assertExists()
    }

    @Test
    fun `notifications in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("debug-screen-notifications-askable-dark.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Allow").assertExists()
    }

    @Test
    fun `granted and idle offers to arm`() {
        capture("debug-screen-idle.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Snooze").assertIsEnabled()
        // Nothing left to do, so neither row offers anything: they are
        // statements now. A button that only re-opens a screen the user has
        // already finished with is a tap with nothing behind it, and keeps
        // first-run urgency on a screen where everything is fine.
        composeRule.onNodeWithText("Granted").assertExists()
        composeRule.onNodeWithText("Allowed").assertExists()
        composeRule.onNodeWithText("Grant").assertDoesNotExist()
        composeRule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun `granted and idle in dark`() {
        RuntimeEnvironment.setQualifiers("+night")

        capture("debug-screen-idle-dark.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
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
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
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
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
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
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
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
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = null,
                lastOutcome = null,
                settingsFailure = SetupRowId.DND,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        // Beside the row, not at the foot of a scrolling column where the user
        // is not looking. Without this the tap reads as doing nothing — the
        // defect this screen exists to remove, reintroduced by its own error
        // path.
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
        // another route into Settings, or an administrator, while this screen
        // is up. The row loses its button at that moment, so a message about a
        // tap that could not happen would sit under `Granted` with nothing left
        // to clear it (flagged by Codex on PR #21).
        // Recorded over the idle snapshot deliberately: with the message
        // suppressed this state is pixel-identical to having no failure at all,
        // which is the claim being made.
        capture("debug-screen-idle.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = SetupRowId.DND,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        composeRule.onNodeWithText("Granted").assertExists()
        composeRule.onNodeWithText("Couldn't open Settings").assertDoesNotExist()
    }

    @Test
    fun `a blocked channel is not reported as allowed`() {
        capture("debug-screen-notifications-muted.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = false,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        // The permission is held, so the old reading said `Allowed` — over a
        // snooze whose countdown and end reason were both being dropped.
        composeRule.onNodeWithText("Snoozemo can't show what a snooze is doing").assertExists()
        composeRule.onNodeWithText("Allowed").assertDoesNotExist()
        // And the row still offers the fix, which is the half a status line
        // alone would lose: held-but-blocked is repairable, and the button is
        // what says so.
        composeRule.onNodeWithText("Allow").assertHasClickAction()
    }

    @Test
    fun `a missing tile is offered, and only while it is missing`() {
        var tapped = 0

        capture("debug-screen-tile-missing.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                tileAdded = false,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = { tapped++ },
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        // The tile is the product (SPEC.md §4.2) — a user without it has an app
        // whose whole interaction is out of reach, so the screen offers it.
        composeRule.onNodeWithText("The one-tap way to snooze").assertExists()
        composeRule.onNodeWithText("Add").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun `a tile already in the shade is stated, not offered`() {
        capture("debug-screen-idle.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
            )
        }

        // The row stays — "always have it in settings" (maintainer, 2026-08-13),
        // and its permanence is what makes the banner's forever-dismissal safe.
        // But it stops offering: nothing is left to create, and the platform's
        // own answer to a redundant request is a dialog saying it is already
        // there.
        composeRule.onNodeWithText("Quick Settings tile").assertExists()
        composeRule.onNodeWithText("Added").assertExists()
        composeRule.onNodeWithText("Add").assertDoesNotExist()
    }

    @Test
    fun `a first run leads with the tile`() {
        var added = 0
        var dismissed = 0

        capture("debug-screen-tile-banner.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                tileAdded = false,
                tileBannerDismissed = false,
                snoozing = false,
                lastOutcome = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = { added++ },
                onDismissTileBanner = { dismissed++ },
                onArm = {},
                onRelease = {},
            )
        }

        // The screen pushes toward the tile rather than listing it as one
        // option among equals (SPEC.md §4.2) — the banner is the only element
        // here that says *why*.
        composeRule.onNodeWithText("Add tile").performClick()
        assertEquals(1, added)
        // And the label is honest about what dismissing costs: the dismissal is
        // permanent, so "Not now" would promise a return that never comes.
        composeRule.onNodeWithText("Don't ask again").performClick()
        assertEquals(1, dismissed)
        // The row is underneath it, which is what makes that permanence safe.
        composeRule.onNodeWithText("Quick Settings tile").assertExists()
    }

    @Test
    fun `a failure is said, not swallowed`() {
        capture("debug-screen-outcome.png") {
            DebugScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                lastOutcome = "Couldn't snooze",
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onTileRow = {},
                onDismissTileBanner = {},
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
