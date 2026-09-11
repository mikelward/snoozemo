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
import app.snoozemo.core.EndCondition
import app.snoozemo.core.DepartureObservation
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.TrackingMode
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
import java.time.Duration
import java.time.Instant

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
        composeRule.onNodeWithText("End now").assertDoesNotExist()
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
        composeRule.onNodeWithText("End now").assertDoesNotExist()
        // Missing access is why nothing *can* snooze; it does not make the
        // state itself unknown, so the line still reports it.
        composeRule.onNodeWithText("Not snoozing").assertExists()
        // The banner's only job is routing to the interstitial — it does not
        // allow anything itself.
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `blocked notifications get a banner of their own`() {
        var opened = 0

        capture("main-screen-notifications-missing.png") {
            MainScreen(
                // Granted, so the access banner is not what this captures. The
                // two are separate required capabilities with separate
                // remedies, and a test that let them overlap would pass on
                // either one.
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.BLOCKED,
                activeChannelEnabled = true,
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

        composeRule.onNodeWithText("Notifications needed").assertExists()
        composeRule.onNodeWithText("Snoozes can't show status and quick actions").assertExists()
        composeRule.onNodeWithText("Do Not Disturb access needed").assertDoesNotExist()
        // Unlike missing access, this does not stop a snooze arming — it stops
        // the app reporting on one. Hiding the button would be the gate firing
        // where it shouldn't.
        composeRule.onNodeWithText("Snooze").assertExists()
        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a revoked-then-askable permission still gets the banner`() {
        // The state a user reaches by granting notifications and later revoking
        // them in system settings: granting cleared the denial history, so the
        // next reading is ASKABLE rather than BLOCKED. The tile skips that
        // state because its tap shows the prompt; this screen shows none and
        // Snooze arms immediately, so hiding the banner here would let the app
        // arm with its ongoing card silently dropped (Codex, PR #216).
        capture("main-screen-notifications-askable.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.ASKABLE,
                activeChannelEnabled = true,
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

        composeRule.onNodeWithText("Notifications needed").assertExists()
    }

    @Test
    fun `two banners at once name their own capabilities to a screen reader`() {
        // Both buttons read "Allow", so the visible label cannot tell them
        // apart — the same ambiguity SetupRow already solved for the rows
        // (Codex, PR #103, and again here on PR #216).
        capture("main-screen-both-required-missing.png") {
            MainScreen(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.BLOCKED,
                activeChannelEnabled = true,
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
        composeRule.onNodeWithText("Notifications needed").assertExists()
        composeRule.onNodeWithContentDescription("Allow Do Not Disturb access").assertExists()
        composeRule.onNodeWithContentDescription("Allow Notifications").assertExists()
    }

    @Test
    fun `a switched-off ongoing channel gets the same banner`() {
        // The permission can be held while the user silences the channel in
        // system settings, and the platform then drops the post — the same
        // outcome by a different route, and the same remedy.
        capture("main-screen-ongoing-channel-off.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                activeChannelEnabled = false,
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

        composeRule.onNodeWithText("Notifications needed").assertExists()
    }

    @Test
    fun `an unread notification reading shows no banner`() {
        // The other direction, and the one that decides whether this is a
        // banner or a nag: null is a reading that has not landed, and every
        // launch passes through it before the first refresh. A banner here
        // would flash on every cold start.
        capture("main-screen-notifications-unread.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                notifications = null,
                activeChannelEnabled = null,
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

        composeRule.onNodeWithText("Notifications needed").assertDoesNotExist()
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
        var chosenTime = 0
        var chosenMeeting = -1
        var stepped = 0
        var chosenMotion = 0
        var chosenDeparture = 0

        capture("main-screen-idle.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                // The idle screen's own offer (maintainer, 2026-09-10): the
                // same rows, every one of them, as a way to start —
                // `Until I leave` included (maintainer, 2026-09-11).
                endChoice = idleOffer(),
                offersMotionEnd = true,
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
                onChooseEndTime = { chosenTime++ },
                onChooseEndMeeting = { chosenMeeting = it },
                onStepEndDown = { stepped-- },
                onStepEndUp = { stepped++ },
                onChooseDeparture = { chosenDeparture++ },
                onChooseMotionEnd = { chosenMotion++ },
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access needed").assertDoesNotExist()
        // The point of this test: idle is stated, not left to be inferred
        // from which button happens to be enabled.
        composeRule.onNodeWithText("Not snoozing").assertExists()
        // Pinned beside where `End now` sits when running (maintainer,
        // 2026-09-10): displayed without a scroll, whatever the calendar
        // contributed above it.
        composeRule.onNodeWithText("Snooze").assertIsEnabled()
        composeRule.onNodeWithText("Snooze").assertIsDisplayed()
        // The mirror of the running case (maintainer, 2026-08-22): confidently
        // idle is the one state where the way out is hidden, because there is
        // provably nothing to get out of.
        composeRule.onNodeWithText("End now").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Settings").assertExists()
        // The rows are wired the same as over a running snooze; what a tap
        // does with them is the activity's business.
        composeRule.onNodeWithText("Until 1:00 PM").performScrollTo().performClick()
        assertEquals(1, chosenTime)
        composeRule.onNodeWithText("Until 1:30 PM").performScrollTo().performClick()
        assertEquals(0, chosenMeeting)
        composeRule.onNodeWithContentDescription("Half an hour later").performScrollTo().performClick()
        assertEquals(1, stepped)
        composeRule.onNodeWithText("Until I move").performScrollTo().performClick()
        assertEquals(1, chosenMotion)
        composeRule.onNodeWithText("Until I leave").performScrollTo().performClick()
        assertEquals(1, chosenDeparture)
    }

    @Test
    fun `the offer to start's location rows are inert until the reading a tap rides exists`() {
        // A tap that arms on location reads the warmed permission state and
        // looks nothing up (SPEC.md §6.9); before that reading lands — a
        // frame after a start — those two rows are drawn rather than hidden,
        // and cannot be tapped. The rows that arm without a reading are not
        // held (Codex, PR #257).
        var chosenDeparture = 0
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                endChoice = idleOffer().copy(locationArmable = false),
                offersMotionEnd = true,
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
                onChooseDeparture = { chosenDeparture++ },
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Until I leave").assertIsNotEnabled()
        composeRule.onNodeWithText("Until I move").assertIsNotEnabled()
        composeRule.onNodeWithText("Until I leave").performScrollTo().performClick()
        assertEquals(0, chosenDeparture)
        // A time arms with no grant at all, steppers included, and is not
        // held back — nor is the plain arm beside them, which reads nothing.
        composeRule.onNodeWithText("Until 1:00 PM").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Half an hour later").assertIsEnabled()
        composeRule.onNodeWithText("Snooze").assertIsEnabled()
    }

    @Test
    fun `the offer to start waits for a confident idle, like the button beside it`() {
        // The record has not been read: arming over a snooze the screen has
        // not seen is how a user loses the deadline they were promised, so
        // the rows wait exactly as `Snooze` does — and the way out stands.
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = null,
                trackingMode = null,
                remaining = null,
                degradation = null,
                endChoice = idleOffer(),
                offersMotionEnd = true,
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

        composeRule.onNodeWithText("Until 1:00 PM").assertDoesNotExist()
        composeRule.onNodeWithText("Until I move").assertDoesNotExist()
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
        composeRule.onNodeWithText("End now").assertIsDisplayed()
    }

    @Test
    fun `the offer to start needs access, like the button beside it`() {
        capture {
            MainScreen(
                access = PolicyAccess.DENIED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                endChoice = idleOffer(),
                offersMotionEnd = true,
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

        composeRule.onNodeWithText("Until 1:00 PM").assertDoesNotExist()
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
    }

    @Test
    fun `a refused start says nothing is running, not that the end was not set`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                endChoice = idleOffer().copy(failed = true),
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

        composeRule.onNodeWithText("Couldn't snooze").assertExists()
        composeRule.onNodeWithText("Couldn't set the end time").assertDoesNotExist()
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

        composeRule.onNodeWithText("End now").assertIsEnabled()
        // And `Snooze` is absent, not merely disabled: arming over a snooze
        // this screen has not read is how the user loses their cap.
        composeRule.onNodeWithText("Snooze").assertDoesNotExist()
        // No idle claim either — the record has not been read, so "Not
        // snoozing" would be a guess over a snooze that may well be running.
        composeRule.onNodeWithText("Not snoozing").assertDoesNotExist()
    }

    @Test
    fun `a running snooze can be refined without being restarted`() {
        var chosenTime = 0
        var chosenMeeting = -1
        var chosenDeparture = 0
        var stepped = 0
        var chosenMotion = 0

        capture("main-screen-end-condition.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                endChoice = EndChoiceUiState(
                    condition = EndCondition(
                        endsAt = NOON.plus(Duration.ofHours(1)),
                        floor = NOON.plus(Duration.ofMinutes(30)),
                        ceiling = NOON.plus(Duration.ofHours(8)),
                    ),
                    formattedTime = "1:00 PM",
                    meetings = listOf(
                        MeetingChoice(NOON.plus(Duration.ofMinutes(90)), "1:30 PM"),
                        MeetingChoice(NOON.plus(Duration.ofMinutes(165)), "2:45 PM"),
                    ),
                ),
                offersMotionEnd = true,
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
                onChooseEndTime = { chosenTime++ },
                onChooseEndMeeting = { chosenMeeting = it },
                onChooseDeparture = { chosenDeparture++ },
                onStepEndDown = { stepped-- },
                onStepEndUp = { stepped++ },
                onChooseMotionEnd = { chosenMotion++ },
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        // The choices the arm-time sheet offers, on a screen the user can open
        // at any point during the snooze (maintainer, 2026-09-08).
        composeRule.onNodeWithText("Until 1:00 PM").performScrollTo().performClick()
        assertEquals(1, chosenTime)
        // Times only for the meetings — never a title (`AGENTS.md`, Privacy).
        composeRule.onNodeWithText("Until 2:45 PM").performScrollTo().performClick()
        assertEquals(1, chosenMeeting)
        composeRule.onNodeWithText("Until I leave").performScrollTo().performClick()
        assertEquals(1, chosenDeparture)
        composeRule.onNodeWithContentDescription("Half an hour later").performScrollTo().performClick()
        assertEquals(1, stepped)
        // And the exit is displayed without a scroll, because it is pinned
        // below the scrolling half rather than sitting at the end of it: no
        // number of refinements can push the one guaranteed way out of a
        // snooze off the screen (SPEC.md §7).
        composeRule.onNodeWithText("End now").assertIsDisplayed()
        // A choice like the rest, worded like `Until I leave` (maintainer,
        // 2026-09-10) — not the switch it started as.
        composeRule.onNodeWithText("Until I move").performScrollTo().performClick()
        assertEquals(1, chosenMotion)
    }

    @Test
    fun `a duration-only snooze is not offered a departure it cannot make`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.DURATION_ONLY,
                remaining = Duration.ofHours(3),
                degradation = null,
                endChoice = EndChoiceUiState(
                    condition = EndCondition(
                        endsAt = NOON.plus(Duration.ofHours(1)),
                        floor = NOON.plus(Duration.ofMinutes(30)),
                        ceiling = NOON.plus(Duration.ofHours(8)),
                    ),
                    formattedTime = "1:00 PM",
                    tracksDeparture = false,
                ),
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

        composeRule.onNodeWithText("Until 1:00 PM").assertExists()
        // Dropped rather than disabled: nothing is watching for a departure on
        // this snooze, so the row would name an end that cannot arrive.
        composeRule.onNodeWithText("Until I leave").assertDoesNotExist()
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
        composeRule.onNodeWithText("End now").assertIsEnabled()
        // One row at the default width, not the title-over-condition split:
        // `Snoozing until you leave` says the whole thing in a sentence and is
        // preferred wherever it fits (maintainer, 2026-09-05).
        composeRule.onNodeWithText("Snoozing until you leave").assertExists()
        composeRule.onNodeWithText("Ends when you leave").assertDoesNotExist()
        composeRule.onNodeWithText("3h 40m left").assertExists()
        // The same slot, not a second line — a screen showing both at once
        // would contradict itself.
        composeRule.onNodeWithText("Not snoozing").assertDoesNotExist()
    }

    // Pinned to a metric locale: the readout follows the phone's own
    // measurement system now, and Robolectric's default is en-US, which
    // takes feet. The UK's system is `UK` rather than `SI` — it keeps miles
    // for road distance — but short distances there read in meters.
    @Config(qualifiers = "+en-rGB")
    @Test
    fun `a tracked snooze shows how far there is left to go`() {
        capture("main-screen-snoozing-distance.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                // 200 m out against a 150 m radius, with 10 m of fix accuracy
                // and a 20 m anchor: ~22 m of combined uncertainty, ~28 m of
                // margin against a 50 m band, so ~23 m still to go.
                departure = DepartureObservation(
                    distanceM = 200.0,
                    accuracyM = 10f,
                    anchorAccuracyM = 20f,
                    radiusM = 150,
                    elapsedRealtimeMs = 0L,
                ),
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

        composeRule.onNodeWithText("Snoozing until you leave").assertExists()
        // A 10 m fix against a 20 m anchor combines to ±22.36 m, so the step is
        // 25 m — the smallest rung not finer than that. The `±` is printed as
        // the step, the separation is snapped to it, and the 23 m still to go
        // does not reach one step, so it reads as the bound it is. The old line
        // said `200 m away ±22 m · 23 m to go`: three numbers to the meter off a
        // reading whose own second number says it cannot resolve one.
        composeRule.onNodeWithText("200 m away ±25 m · < 25 m to go").assertExists()
    }

    // Pinned to a metric locale: the readout follows the phone's own
    // measurement system now, and Robolectric's default is en-US, which
    // takes feet. The UK's system is `UK` rather than `SI` — it keeps miles
    // for road distance — but short distances there read in meters.
    @Config(qualifiers = "+en-rGB")
    @Test
    fun `a fix far enough to end the snooze says it is confirming`() {
        capture("main-screen-snoozing-confirming.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                // Past the band on this fix, which is not yet a departure: a
                // second qualifying fix thirty seconds later is what ends it,
                // so the readout reports the wait rather than promising the end.
                departure = DepartureObservation(
                    distanceM = 400.0,
                    accuracyM = 15f,
                    anchorAccuracyM = 20f,
                    radiusM = 150,
                    elapsedRealtimeMs = 0L,
                ),
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

        composeRule.onNodeWithText("400 m away ±25 m · confirming").assertExists()
        composeRule.onNodeWithText("400 m away ±25 m · 0 m to go").assertDoesNotExist()
    }

    // Pinned to a metric locale: the readout follows the phone's own
    // measurement system now, and Robolectric's default is en-US, which
    // takes feet. The UK's system is `UK` rather than `SI` — it keeps miles
    // for road distance — but short distances there read in meters.
    @Config(qualifiers = "+en-rGB")
    @Test
    fun `a reading exactly on the band still says there is a meter to go`() {
        capture("main-screen-snoozing-on-the-band.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                // Exactly 50 m of margin against a 50 m band. `qualifies` is a
                // strict comparison, so this is *not* a departure — and a
                // rounded `0 m to go` beside a snooze that has not ended would
                // contradict the verdict the line is quoting.
                //
                // 15 m and 20 m combine to exactly 25 m, which is what keeps the
                // margin exactly on the band rather than a floating-point hair
                // either side of it.
                departure = DepartureObservation(
                    distanceM = 225.0,
                    accuracyM = 15f,
                    anchorAccuracyM = 20f,
                    radiusM = 150,
                    elapsedRealtimeMs = 0L,
                ),
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

        // Still never zero — that is what this case has always been for — but
        // the way it avoids zero changed. `1 m to go` was a fake meter standing
        // in for a floor; the bound says the true thing instead, and says it
        // about a reading whose ±25 m could not have resolved a meter anyway.
        composeRule.onNodeWithText("225 m away ±25 m · < 25 m to go").assertExists()
        composeRule.onNodeWithText("225 m away ±25 m · 0 m to go").assertDoesNotExist()
        composeRule.onNodeWithText("225 m away ±25 m · 1 m to go").assertDoesNotExist()
        composeRule.onNodeWithText("225 m away ±25 m · confirming").assertDoesNotExist()
    }

    @Config(qualifiers = "+en-rUS")
    @Test
    fun `a US phone reads the same distance in feet`() {
        capture("main-screen-snoozing-distance-feet.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                // The same reading as the metric case above: 200 m out with 10 m
                // of fix accuracy and a 20 m anchor against a 150 m radius, so
                // ~23 m still to go.
                departure = DepartureObservation(
                    distanceM = 200.0,
                    accuracyM = 10f,
                    anchorAccuracyM = 20f,
                    radiusM = 150,
                    elapsedRealtimeMs = 0L,
                ),
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

        // The same reading as the metric case, on the imperial ladder: ±22.36 m
        // is 73.4 ft, whose smallest not-finer rung is 100 ft, and 656.17 ft
        // snaps to 700 on it.
        //
        // Worth seeing rather than only reading: the two ladders do not track
        // each other exactly here. 25 m is 82 ft, so the same fix is described
        // as ±25 m on one phone and ±100 ft on another — the imperial form a
        // fifth coarser. That is the price of rungs a US reader recognizes, and
        // whether a 75 ft rung should close it is open in `TODO.md`.
        composeRule.onNodeWithText("700 ft away ±100 ft · < 100 ft to go").assertExists()
        composeRule.onNodeWithText("200 m away ±25 m · < 25 m to go").assertDoesNotExist()
    }

    @Test
    fun `a degraded snooze shows no distance, because it is measuring none`() {
        capture("main-screen-wifi-only-no-distance.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.WIFI_ONLY,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = DegradationCause.NO_LOCATION_FIX,
                // A reading left over from before tracking degraded. Showing it
                // would explain a threshold that is no longer what ends this
                // snooze.
                departure = DepartureObservation(
                    distanceM = 200.0,
                    accuracyM = 10f,
                    anchorAccuracyM = 20f,
                    radiusM = 150,
                    elapsedRealtimeMs = 0L,
                ),
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

        composeRule.onNodeWithText("200 m away ±25 m · < 25 m to go").assertDoesNotExist()
    }

    @Test
    fun `a snooze started with Until I move says until you move`() {
        capture("main-screen-snoozing-ends-on-motion.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                endsOnMotion = true,
                // The same reportable observation the departure cases use, so
                // this asserts the readout is *withheld* rather than merely
                // absent — a null here would pass on nothing being measured.
                departure = DepartureObservation(
                    distanceM = 200.0,
                    accuracyM = 10f,
                    anchorAccuracyM = 20f,
                    radiusM = 150,
                    elapsedRealtimeMs = 0L,
                ),
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

        // The report this fixes: tapping `Until I move` left this screen saying
        // exactly what it said before, so the tap read as having done nothing.
        // The sentence names the exit that was tapped, and names only that one —
        // exact matches, so neither can be satisfied by the other still being
        // there, and nothing enumerates two endings.
        composeRule.onNodeWithText("Snoozing until you move").assertExists()
        composeRule.onNodeWithText("Snoozing until you leave").assertDoesNotExist()
        composeRule.onNodeWithText("Ends when you leave, or when you move").assertDoesNotExist()
        composeRule.onNodeWithText("3h 40m left").assertExists()
        // Departure information, on a snooze that does not report departure:
        // naming one exit and then putting a number on a different one is the
        // same inconsistency from a third direction (Codex, PR #263).
        composeRule.onNodeWithText("200 m away ±25 m · 23 m to go").assertDoesNotExist()
    }

    @Test
    fun `a motion snooze reports its own exit, not degraded departure tracking`() {
        capture("main-screen-snoozing-ends-on-motion-degraded.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.WIFI_ONLY,
                remaining = Duration.ofMinutes(45),
                degradation = DegradationCause.NO_LOCATION_FIX,
                endsOnMotion = true,
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

        // The maintainer's call (2026-09-11): the mode and its cause describe
        // how *departure* is being watched, and a fix has no bearing on whether
        // the sensor fires — so a snooze started on movement says what ends it
        // and stops there. Exact matches, so the degraded line cannot survive
        // as a second row.
        composeRule.onNodeWithText("Snoozing until you move").assertExists()
        composeRule.onNodeWithText("Wi-Fi only — no location").assertDoesNotExist()
        composeRule.onNodeWithText("Wi-Fi only").assertDoesNotExist()
        composeRule.onNodeWithText("Wi-Fi only — no location, or when you move")
            .assertDoesNotExist()
        composeRule.onNodeWithText("45m left").assertExists()
    }

    /**
     * The fallback the one-sentence form falls back *to*, which is where a
     * large accessibility font lands (Codex, PR #263).
     *
     * No snapshot: the point is the words, and a 200dp-wide PNG of a layout
     * nobody's device renders would be a recorded artifact with nothing to
     * compare against.
     */
    @Test
    @Config(qualifiers = "w200dp-h914dp-420dpi")
    fun `the narrow fallback still names the exit that was tapped`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                endsOnMotion = true,
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

        // Too narrow for the sentence, so the block splits into the title over
        // its condition — and the condition names the same exit the sentence
        // would have. Going back to `Ends when you leave` here would put the
        // reported bug back exactly where the screen most needs to be legible.
        composeRule.onNodeWithText("Ends when you move").assertExists()
        composeRule.onNodeWithText("Ends when you leave").assertDoesNotExist()
        composeRule.onNodeWithText("Snoozing until you move").assertDoesNotExist()
    }

    /**
     * The one mode the motion sentence does not take over, because it is a
     * deadline rather than a description (Codex, PR #263).
     *
     * No snapshot: this is about which of two lines is rendered, and the shape
     * is already recorded by the sibling cases.
     */
    @Test
    fun `the Wi-Fi grace warning survives a motion exit`() {
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.WIFI_GRACE,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                endsOnMotion = true,
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

        // The grace period ends the snooze on expiry whether or not the phone
        // moves, so `3h 40m left` beside `Snoozing until you move` would be a
        // countdown to the wrong thing on a snooze minutes from ending.
        composeRule.onNodeWithText("Snoozing").assertExists()
        composeRule.onNodeWithText("Wi-Fi lost — ending soon").assertExists()
        composeRule.onNodeWithText("Snoozing until you move").assertDoesNotExist()
        composeRule.onNodeWithText("Ends when you move").assertDoesNotExist()
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

        // The two-row shape: "Snoozing, Wi-Fi only" does not compose, so a
        // degraded snooze states the title over its condition rather than
        // trying for the one-row sentence. An exact match, so this cannot be
        // satisfied by `Snoozing until you leave`.
        composeRule.onNodeWithText("Snoozing").assertExists()
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

        composeRule.onNodeWithText("Snoozing until you leave").assertExists()
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
    fun `the replay hint points at the help icon`() {
        // Shown just after the flow is left; the icon it names is in the title
        // row above it, so the two read as one instruction.
        capture("main-screen-replay-hint.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                showReplayHint = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                sharing = false,
                onDismissTileBanner = {},
                onAddTile = {},
                onArm = {},
                onRelease = {},
                onOpenSettings = {},
                onOpenPermissions = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        composeRule.onNodeWithText("Tap (?) to see the tutorial again").assertExists()
        composeRule.onNodeWithText("Dismiss").assertExists()
    }

    @Test
    fun `the replay hint yields to the tile banner`() {
        // The hint blocks nothing, repairs nothing and asks nothing, so
        // anything with something at stake outranks it — and the tile most of
        // all, since `SPEC.md` §4.2 has the screen lead with it and it is the
        // one action that makes arming from a locked phone work. Written above
        // it first, where on a short window or at a large font it pushed that
        // action down the scroll (Codex, PR #206).
        capture {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = false,
                tileBannerDismissed = false,
                showReplayHint = true,
                snoozing = false,
                trackingMode = null,
                remaining = null,
                degradation = null,
                lastOutcome = null,
                crashPending = false,
                shareFailed = false,
                dismissFailed = false,
                sharing = false,
                onDismissTileBanner = {},
                onAddTile = {},
                onArm = {},
                onRelease = {},
                onOpenSettings = {},
                onOpenPermissions = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        // Both up at once, which is the only arrangement that can get this
        // wrong — and the case no capture covers, since the recorded hint
        // shows it alone.
        val tile = composeRule.onNodeWithText("Snooze from Quick Settings")
            .fetchSemanticsNode().positionInRoot.y
        val hint = composeRule.onNodeWithText("Tap (?) to see the tutorial again")
            .fetchSemanticsNode().positionInRoot.y
        assertTrue("the tile banner must sit above the replay hint", tile < hint)
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

        composeRule.onNodeWithText("Snooze from Quick Settings").assertDoesNotExist()
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
        // line and three controls do not fit in the height available. Manual
        // exit is "always available, always instant" (SPEC.md §7), so losing
        // it to a window shape is the one failure this screen may not have —
        // and it no longer can, since the exit sits outside the scroll. What
        // this case still proves is that the content above it stays reachable
        // when the pinned row has taken its height. The status line is
        // included (not null) so this covers the worst case honestly — real
        // content, not an empty stand-in that scrolls easier than the real
        // screen ever will.
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

        composeRule.onNodeWithText("End now").assertIsDisplayed()
        // And the gear is up here without scrolling, which is the claim that
        // move actually makes: it is where the screen opens, not pinned
        // against the scroll (SPEC.md §4.2). Asserted in the constrained case
        // because that is the one where its old position at the foot — below
        // the exit — cost the most to reach.
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `the fullest offer on the shortest window still reaches the way out`() {
        // The worst case the refinements can make, in the window shape that
        // has least room for it: every row a snooze can offer — departure,
        // the adjustable time with its steppers, and both meeting ends — over
        // a landscape window. Four rows plus the exit is more than any other
        // case here puts on screen, and it is the arrangement that decides
        // whether the exit keeps a fixed position.
        //
        // This case is why the exit is pinned. Recorded when it was merely
        // *last*, the same image showed it below the fold and a scroll away;
        // it is now displayed on the first frame with the rows scrolling
        // behind it, which is what the maintainer asked for on 2026-09-09.
        RuntimeEnvironment.setQualifiers("w914dp-h411dp-420dpi")

        capture("main-screen-end-condition-short-window.png", widthPx = 2400, heightPx = 1080) {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.FULL,
                remaining = Duration.ofHours(3).plusMinutes(40),
                degradation = null,
                endChoice = EndChoiceUiState(
                    condition = EndCondition(
                        endsAt = NOON.plus(Duration.ofHours(1)),
                        floor = NOON.plus(Duration.ofMinutes(30)),
                        ceiling = NOON.plus(Duration.ofHours(8)),
                    ),
                    formattedTime = "1:00 PM",
                    meetings = listOf(
                        MeetingChoice(NOON.plus(Duration.ofMinutes(90)), "1:30 PM"),
                        MeetingChoice(NOON.plus(Duration.ofMinutes(165)), "2:45 PM"),
                    ),
                ),
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
                onChooseEndTime = {},
                onChooseEndMeeting = {},
                onChooseDeparture = {},
                onStepEndDown = {},
                onStepEndUp = {},
                onShareDebugLog = {},
                onDismissCrash = {},
            )
        }

        // Both ends of the screen at once, with no scrolling between them:
        // the gear where the screen opens (SPEC.md §4.2) and the exit fixed
        // below the rows (§7). Asserting them together is the point — before
        // the pin, reaching one carried the other off the opposite edge, so a
        // single frame holding both is exactly what changed.
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("End now").assertIsDisplayed()
        // Enabled as well as drawn: a pinned control that cannot be tapped
        // would satisfy the layout claim and none of the promise behind it.
        composeRule.onNodeWithText("End now").assertIsEnabled()
        // And the rows above it are still reachable — pinning took height
        // from the content, so the content has to still scroll to all of it.
        composeRule.onNodeWithText("Until 2:45 PM").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `an arming snooze says it is waiting, not that it is a timer`() {
        // The case from a device log (maintainer, 2026-09-07). The mode the
        // record actually carries while the arm is in flight — asserted here on
        // the rendering only; that the arm really produces it is
        // `SnoozeControllerTest`'s job, because the first version of this fix
        // tested the rendering alone and shipped a no-op (Codex, PR #221).
        capture("main-screen-arming-waiting.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.SETTLING,
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

        composeRule.onNodeWithText("Waiting for location").assertExists()
        composeRule.onNodeWithText("Timer only").assertDoesNotExist()
    }

    @Test
    fun `a settled timer-only snooze still says so`() {
        // The direction that stops the fix swallowing a real degraded mode:
        // once the arm has settled, a timer is a timer and says why.
        capture("main-screen-settled-timer-only.png") {
            MainScreen(
                access = PolicyAccess.GRANTED,
                tileAdded = true,
                tileBannerDismissed = true,
                snoozing = true,
                trackingMode = TrackingMode.DURATION_ONLY,
                remaining = Duration.ofHours(8),
                degradation = DegradationCause.LOCATION_PERMISSION_GONE,
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

        composeRule.onNodeWithText("Waiting for location").assertDoesNotExist()
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
    private companion object {
        /**
         * A fixed instant for the end-condition bounds. Nothing renders it —
         * the row's label arrives already formatted — so it only has to sit
         * far enough inside the bounds for the steppers to be enabled.
         */
        val NOON: Instant = Instant.parse("2026-09-08T12:00:00Z")
    }

    /**
     * The idle screen's offer as the activity would build it: seeded from
     * the clock, bounded by the default cap, one meeting inside the window.
     */
    private fun idleOffer() = EndChoiceUiState(
        condition = EndCondition(
            endsAt = NOON.plus(Duration.ofHours(1)),
            floor = NOON.plus(Duration.ofMinutes(30)),
            ceiling = NOON.plus(Duration.ofHours(8)),
        ),
        formattedTime = "1:00 PM",
        meetings = listOf(MeetingChoice(NOON.plus(Duration.ofMinutes(90)), "1:30 PM")),
        tracksDeparture = true,
        startsASnooze = true,
    )

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
