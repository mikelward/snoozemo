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
import app.snoozemo.core.ZenRuleState
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

        composeRule.onNodeWithText("Snoozes can't silence your phone").assertExists()
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
                ruleState = ZenRuleState.READY,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozes can't show status and quick actions").assertExists()
        composeRule.onNodeWithText("Allow").assertExists()
    }

    @Test
    fun `a blocked channel is not reported as allowed`() {
        var tapped = 0

        capture("permissions-screen-notifications-muted.png") {
            PermissionsScreen(
                // Left unread: each row's status is its own sentence now
                // (SPEC.md §5.2), so nothing is shared between rows — but a
                // granted access row would still add a second granted status
                // and make `assertDoesNotExist()` ambiguous about which row it
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
        // snooze whose countdown and end reason were both being dropped. The
        // status now names the capability, which makes the wrong reading worse
        // rather than better: it would claim the thing the user is not getting.
        composeRule.onNodeWithText("Snoozes can't show status and quick actions").assertExists()
        composeRule.onNodeWithText("Snoozes can show status and quick actions").assertDoesNotExist()
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
                ruleState = ZenRuleState.READY,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = { tapped++ },
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozes can't end when you leave").assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun `a build without departure tracking promises nothing and offers nothing`() {
        // The `direct` flavor has no presence monitor (SPEC.md §3.4), but it
        // still declares ACCESS_FINE_LOCATION from the shared manifest, so the
        // row renders. Naming the capability there would promise something the
        // build cannot do and invite a grant that buys nothing (Codex, PR
        // #171) — the copy change this test guards is what introduced that.
        capture("permissions-screen-no-departure-askable.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.ASKABLE,
                tracksDeparture = false,
                ruleState = ZenRuleState.READY,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Ending when you leave isn't in this build yet").assertExists()
        composeRule.onNodeWithText("Snoozes can end when you leave").assertDoesNotExist()
        composeRule.onNodeWithText("Snoozes can't end when you leave").assertDoesNotExist()
        // And no offer: the permission is askable, so without this the row
        // would still show `Allow` over a status saying it would not help.
        composeRule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun `a build without departure tracking says the same thing once granted`() {
        capture("permissions-screen-no-departure-granted.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                tracksDeparture = false,
                ruleState = ZenRuleState.READY,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        // The grant is not what stands between the user and this, so holding it
        // changes nothing the row says.
        composeRule.onNodeWithText("Ending when you leave isn't in this build yet").assertExists()
        composeRule.onNodeWithText("Snoozes can end when you leave").assertDoesNotExist()
    }

    @Test
    fun `a granted access row does not claim a capability its rule cannot deliver`() {
        // Access held, rule switched off by the user in Settings — which
        // Snoozemo deliberately does not undo (SPEC.md §5.1). The capability
        // claim would be a false success on the one screen that does not also
        // render `lastOutcome` to contradict it (Codex, PR #171).
        capture("permissions-screen-rule-disabled.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                ruleState = ZenRuleState.DISABLED,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozemo's rule is switched off in Settings").assertExists()
        composeRule.onNodeWithText("Snoozes can silence your phone").assertDoesNotExist()
    }

    @Test
    fun `an unverified rule leaves the access row out rather than claiming it works`() {
        // `access` publishes as soon as it is read; the rule check answers
        // after it. Claiming the capability in that window would take it back
        // a moment later on a DISABLED or FAILED rule, so the row waits —
        // briefly absent rather than briefly wrong (Codex, PR #171).
        capture {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                ruleState = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozes can silence your phone").assertDoesNotExist()
        composeRule.onNodeWithText("Do Not Disturb access").assertDoesNotExist()
        // The rows that do not depend on the rule are unaffected.
        composeRule.onNodeWithText("Snoozes can end when you leave").assertExists()
    }

    @Test
    fun `a refused access row does not wait on a rule it has no use for`() {
        // Nothing for a rule to be in the way of, so this row is immediate —
        // the wait above must not delay the state a fresh install lands on.
        capture {
            PermissionsScreen(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                ruleState = null,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozes can't silence your phone").assertExists()
    }

    @Test
    fun `a disabled rule offers the button that reaches its off switch`() {
        // The row reports work the user can still do, so SPEC.md 5.2 says it
        // carries a button that does it — and the button routes to the mode's
        // own settings screen, not to the policy-access one the grant already
        // cleared (Codex, PR #171).
        var access = 0
        var rule = 0
        capture {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                ruleState = ZenRuleState.DISABLED,
                settingsFailure = null,
                onAccessRow = { access++ },
                onRuleRow = { rule++ },
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, rule)
        assertEquals(0, access)
    }

    @Test
    fun `a ready rule keeps its capability row button-less`() {
        // The other side of the discrimination above: a working rule has
        // nothing left to do, so the button stays absent as it always has.
        capture {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                ruleState = ZenRuleState.READY,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun `a refused rule says so rather than claiming the capability`() {
        capture("permissions-screen-rule-failed.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                ruleState = ZenRuleState.FAILED,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access is on, but Snoozemo could not create its rule")
            .assertExists()
        composeRule.onNodeWithText("Snoozes can silence your phone").assertDoesNotExist()
    }

    @Test
    fun `a ready rule lets the access row claim its capability`() {
        capture("permissions-screen-rule-ready.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                ruleState = ZenRuleState.READY,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        // The other direction, so the guard above is a discrimination rather
        // than a blanket suppression of the capability line.
        composeRule.onNodeWithText("Snoozes can silence your phone").assertExists()
        composeRule.onNodeWithText("Snoozemo's rule is switched off in Settings").assertDoesNotExist()
    }

    @Test
    fun `every row's status names the capability, in both states`() {
        capture("permissions-screen-capability-pair.png") {
            PermissionsScreen(
                access = PolicyAccess.GRANTED,
                // Not held, so this test carries rows in *both* states — the
                // pair is what is under test, not the granted half of it. A
                // `null` here would read as unread and render no status at
                // all, which pins neither side.
                notifications = NotificationPermission.ASKABLE,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                ruleState = ZenRuleState.READY,
                settingsFailure = null,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        // SPEC.md §5.2 (revised 2026-09-01): the status names the capability in
        // both states, differing by one word, so a user can tell what a grant
        // buys them before making it. `Allowed` is what this replaced — it
        // answered a question the user had just answered themselves. Earlier
        // still, the rows each invented their own word: "Tracking your place",
        // "Granted".
        composeRule.onNodeWithText("Snoozes can silence your phone").assertExists()
        composeRule.onNodeWithText("Snoozes can end when you leave").assertExists()
        composeRule.onNodeWithText("Snoozes can't show status and quick actions").assertExists()
        composeRule.onNodeWithText("Allowed").assertDoesNotExist()
        composeRule.onNodeWithText("Tracking your place").assertDoesNotExist()
        composeRule.onNodeWithText("Granted").assertDoesNotExist()
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
                ruleState = ZenRuleState.READY,
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
                ruleState = ZenRuleState.READY,
                settingsFailure = SetupRowId.DND,
                onAccessRow = {},
                onNotificationsRow = {},
                onLocationRow = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Snoozes can silence your phone").assertExists()
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
        composeRule.onNodeWithText("Snoozes can't end when your meeting does").assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun `a granted calendar names the capability and offers nothing`() {
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

        composeRule.onNodeWithText("Snoozes can end when your meeting does").assertExists()
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
