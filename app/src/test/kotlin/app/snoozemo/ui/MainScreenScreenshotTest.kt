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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.snoozemo.PlayUpdateState
import app.snoozemo.core.DegradationCause
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

        // No banner yet — unread is not "missing" — and, same as before the
        // split, neither button renders until access reads granted.
        composeRule.onNodeWithText("Snoozemo").assertExists()
        composeRule.onNodeWithText("Do Not Disturb access needed").assertDoesNotExist()
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
        composeRule.onNodeWithText("End snooze").assertDoesNotExist()
        // And no idle claim either: the record has not been read, so "Not
        // snoozing" would be a guess — over a snooze that may well be running.
        composeRule.onNodeWithText("Not snoozing").assertDoesNotExist()
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
                degradation = null,
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
        composeRule.onNodeWithText("Snoozes can't silence your phone").assertExists()
        // Same gating the old DebugScreen had — neither button is a stray
        // affordance while access is missing.
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
        composeRule.onNodeWithText("End snooze").assertDoesNotExist()
        // Missing access is why nothing *can* snooze; it does not make the
        // state itself unknown, so the line still reports it.
        composeRule.onNodeWithText("Not snoozing").assertExists()
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

        composeRule.onNodeWithText("Do Not Disturb access needed").assertDoesNotExist()
        // The point of this test: idle is stated, not left to be inferred
        // from which button happens to be enabled.
        composeRule.onNodeWithText("Not snoozing").assertExists()
        composeRule.onNodeWithText("Snooze").assertIsEnabled()
        // The mirror of the running case (maintainer, 2026-08-22): confidently
        // idle is the one state where the way out is hidden, because there is
        // provably nothing to get out of.
        composeRule.onNodeWithText("End snooze").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Settings").assertExists()
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

        composeRule.onNodeWithText("Snooze").assertIsEnabled()
    }

    /**
     * The state the whole asymmetry exists for, and the one no test covered
     * (Codex, PR #143): access has finished loading but the snooze record has
     * not, so `snoozing` is still `null`. `End snooze` has to be here — it is
     * the guaranteed way back to a ringing phone (SPEC.md §7, and `endSnooze`
     * is idempotent, so offering it over nothing costs nothing), and a stale
     * or unread belief must never be what withholds it.
     *
     * Asserted rather than captured: the pixels are the idle screen's minus
     * the status line, and what needs pinning is which control is reachable.
     * Without this, flipping the split to `snoozing == true` would leave the
     * suite green while deleting the manual exit for the length of a disk
     * read — the failure this design was chosen to avoid, passing its own
     * tests.
     */
    @Test
    fun `granted but not yet read still offers the way out`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = null,
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

        composeRule.onNodeWithText("End snooze").assertIsEnabled()
        // And `Snooze` is absent, not merely disabled: arming over a snooze
        // this screen has not read is how the user loses their cap.
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
        // No idle claim either — the record has not been read, so "Not
        // snoozing" would be a guess over a snooze that may well be running.
        composeRule.onNodeWithText("Not snoozing").assertDoesNotExist()
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

        // Exactly one button, and on a running snooze it is the way out
        // (maintainer, 2026-08-22). `Snooze` used to render here disabled;
        // the state it would have communicated is already on the card above.
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
        composeRule.onNodeWithText("End snooze").assertIsEnabled()
        composeRule.onNodeWithText("Ends when you leave").assertExists()
        composeRule.onNodeWithText("3h 40m left").assertExists()
        // The same slot, not a second line — a screen showing both at once
        // would contradict itself.
        composeRule.onNodeWithText("Not snoozing").assertDoesNotExist()
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

        composeRule.onNodeWithText("Timer only").assertExists()
        composeRule.onNodeWithText("8h 0m left").assertExists()
    }

    /**
     * The reason travels with the mode, not just to the notification.
     *
     * `Timer only` alone reads like a setting someone chose. Joined to its
     * cause it reads as the thing that went wrong, which is the whole point of
     * saying it (principle 2) — and the user may well have arrived here
     * *because* the notification was swiped away or silenced.
     */
    @Test
    fun `a degraded snooze says why`() {
        capture("main-screen-snoozing-timer-only-degraded.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.DURATION_ONLY,
                remaining = Duration.ofHours(8),
                degradation = DegradationCause.NO_LOCATION_FIX,
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

        // Verbatim, and one node rather than two: the same joined line the
        // ongoing notification renders (`ongoing_degraded_reason`), so the two
        // surfaces cannot drift into phrasing the same snooze differently.
        composeRule.onNodeWithText("Timer only \u2014 no location").assertExists()
        composeRule.onNodeWithText("Timer only").assertDoesNotExist()
        composeRule.onNodeWithText("8h 0m left").assertExists()
    }

    /** Wi-Fi-only degrades for its own reasons and names them the same way. */
    @Test
    fun `a Wi-Fi-only snooze names its cause too`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.WIFI_ONLY,
                remaining = Duration.ofMinutes(45),
                degradation = DegradationCause.FIXES_TOO_VAGUE,
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

        composeRule.onNodeWithText("Wi-Fi only \u2014 weak location signal").assertExists()
    }

    /**
     * The update banner rides on this screen too.
     *
     * Same reasoning `CrashBanner` already follows: which screen the user
     * happens to land on is not something the feature should have to reason
     * about, and this is the one they land on by default — an update offered
     * only behind Settings is offered to whoever was already going there.
     */
    @Test
    fun `a waiting update is offered here as well`() {
        var started = 0

        capture("main-screen-update-available.png") {
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
                playUpdate = PlayUpdateState.Available(versionCode = 5),
                onOpenPermissions = {},
                onOpenSettings = {},
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
                onStartPlayUpdate = { started++ },
            )
        }

        composeRule.onNodeWithText("Update available").assertExists()
        composeRule.onNodeWithText("Update").performClick()
        assertEquals(1, started)
    }

    /** A dismissed update stays dismissed here, exactly as on Settings. */
    @Test
    fun `a dismissed update is not offered again`() {
        capture {
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
                playUpdate = PlayUpdateState.Available(versionCode = 5, isDismissed = true),
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

        composeRule.onNodeWithText("Update available").assertDoesNotExist()
    }

    /**
     * The screen picks up a newly-speaking cause for free.
     *
     * `degradationReasonRes` is shared with the notification, so adding
     * `NO_LOCATION_IN_BACKGROUND` there reached this screen with no change
     * here — which is the point of sharing it, and worth an assertion so a
     * later split of the two mappings fails loudly rather than silently.
     */
    @Test
    fun `the background-location cause reaches this screen too`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.DURATION_ONLY,
                remaining = Duration.ofHours(8),
                degradation = DegradationCause.NO_LOCATION_IN_BACKGROUND,
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

        composeRule.onNodeWithText("Timer only \u2014 background location off").assertExists()
    }

    /**
     * A cause that earns no line leaves the mode exactly as it was.
     *
     * `NOTHING_WATCHING` is the app's own wiring rather than anything the user
     * did or can act on, so `Timer only` already says everything true about it
     * — appending a clause here would spend the user's attention on a fact
     * they cannot use.
     */
    @Test
    fun `a cause with no line of its own leaves the mode alone`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.DURATION_ONLY,
                remaining = Duration.ofHours(8),
                degradation = DegradationCause.NOTHING_WATCHING,
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
    }

    /**
     * A tracked snooze never grows a reason, whatever the record carries.
     *
     * `SnoozeController.modeFor` maps a null degradation straight to the
     * anchor's capability, so `FULL` with a cause should not arise — but the
     * mode gate is what makes that unrepresentable on screen rather than
     * merely unlikely, and this is the test that holds it there.
     */
    @Test
    fun `a tracked snooze appends nothing`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3),
                degradation = DegradationCause.NO_LOCATION_FIX,
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

        composeRule.onNodeWithText("Ends when you leave").assertExists()
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
                degradation = null,
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
                degradation = null,
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

        composeRule.onNodeWithText("Snooze from the shade").assertDoesNotExist()
    }

    @Test
    fun `missing background location says what it costs`() {
        capture("main-screen-background-location.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                backgroundLocationMissing = true,
                backgroundLocationBannerDismissed = false,
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

        // An offer, not a warning (maintainer, 2026-08-31): nothing has
        // been lost, there is a capability the user can switch on, and the
        // banner's whole job is to say so in one line.
        composeRule.onNodeWithText("Enable location support?").assertExists()
    }

    @Test
    fun `a dismissed background-location banner does not come back`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                backgroundLocationMissing = true,
                backgroundLocationBannerDismissed = true,
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

        composeRule.onNodeWithText("Enable location support?").assertDoesNotExist()
    }

    @Test
    fun `a held background grant raises no banner`() {
        // The direction that matters more than the banner appearing: this is
        // the default state of a correctly-set-up install, and a banner that
        // showed here would be permanent noise on the one screen this app
        // has.
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                backgroundLocationMissing = false,
                backgroundLocationBannerDismissed = false,
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

        composeRule.onNodeWithText("Enable location support?").assertDoesNotExist()
    }

    @Test
    fun `the banner's own buttons are wired`() {
        var allowed = 0
        var dismissed = 0
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                backgroundLocationMissing = true,
                backgroundLocationBannerDismissed = false,
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
                onAllowBackgroundLocation = { allowed++ },
                onDismissBackgroundLocationBanner = { dismissed++ },
            )
        }

        composeRule.onNodeWithText("Yes please").performScrollTo().performClick()
        composeRule.onNodeWithText("No thanks").performScrollTo().performClick()

        assertEquals(1, allowed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `an unanswered telemetry question is asked`() {
        capture("main-screen-telemetry-invite.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                telemetryUnanswered = true,
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

        composeRule.onNodeWithText("Help make Snoozemo better?").assertExists()
    }

    @Test
    fun `an answered telemetry question is not asked again`() {
        // Either answer retires the card, which is why the screen reads a
        // single "unanswered" flag rather than the enabled setting: a
        // recorded "no" and a never-asked install both leave reporting off,
        // and only one of them should see this.
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                telemetryUnanswered = false,
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

        composeRule.onNodeWithText("Help make Snoozemo better?").assertDoesNotExist()
    }

    @Test
    fun `both telemetry answers are reported, and they differ`() {
        // The half a "did the button fire" test would miss: declining has to
        // reach the same handler as accepting, carrying `false`. A decline
        // wired to nothing would look identical on screen and would leave the
        // question unanswered forever.
        val answers = mutableListOf<Boolean>()
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                telemetryUnanswered = true,
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
                onAnswerTelemetry = { answers += it },
            )
        }

        composeRule.onNodeWithText("Yes please").performScrollTo().performClick()
        composeRule.onNodeWithText("No thanks").performScrollTo().performClick()

        assertEquals(listOf(true, false), answers)
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
                degradation = null,
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
                degradation = null,
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
                degradation = null,
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
                degradation = null,
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
                degradation = null,
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

        composeRule.onNodeWithText("Snoozemo crashed").assertDoesNotExist()
    }

    @Test
    fun `the settings gear opens SettingsScreen`() {
        var opened = 0

        capture {
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
                onOpenSettings = { opened++ },
                onAddTile = {},
                onDismissTileBanner = {},
                onArm = {},
                onRelease = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        // Found by its label, which is the accessible name an icon-only
        // control does not otherwise have — and clicked without a
        // `performScrollTo`, which is half the point of the move: as a
        // full-width button under the exit, a long screen could push it below
        // the fold.
        composeRule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, opened)
        // The word is gone from the body: the gear replaced the button rather
        // than joining it, so there is one way there rather than two.
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
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

        composeRule.onNodeWithText("End snooze").performScrollTo().assertIsEnabled()
        // And the gear is up here without scrolling back, which is the claim
        // this move actually makes: it is where the screen opens, not pinned
        // against the scroll (SPEC.md §4.2). Asserted in the constrained case
        // because that is the one where its old position at the foot — below
        // the exit — cost the most to reach.
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
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
