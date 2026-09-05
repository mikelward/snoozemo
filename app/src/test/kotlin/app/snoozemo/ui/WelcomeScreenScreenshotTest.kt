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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.snoozemo.core.CalendarPermission
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.core.ZenRuleState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome flow's five cards (`SPEC.md` §4.2, wording in `TUTORIAL.md`).
 *
 * Each card is one idea and one picture, so each gets its own capture — the
 * states are the point rather than the pixels. The two that differ by flavor
 * are captured both ways: cards 1 and 2 promise departure, and on a build that
 * cannot deliver it they must promise something else instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val allCards = welcomeCards(collectsTelemetry = true)

    @Test
    fun `card one says what the app is`() {
        capture("welcome-what.png") { Flow(WelcomeCard.WHAT) }

        composeRule.onNodeWithText("Silence your phone until you leave.").assertExists()
        composeRule.onNodeWithText("One tap.").assertExists()
        // No Skip here (maintainer, 2026-09-05): offering to leave beside the
        // one line that says what the app is invites skipping before there is
        // anything to skip. D7 is untouched — back still exits card 1, so the
        // way out exists; it just is not advertised before that line is read.
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
        composeRule.onNodeWithText("Next").assertExists()
    }

    @Test
    fun `card one promises no departure on a build that cannot track it`() {
        capture("welcome-what-timer-only.png") {
            Flow(WelcomeCard.WHAT, tracksDeparture = false)
        }

        // The promise the `direct` build can actually keep. Promising departure
        // there sets up exactly the silence-until-the-cap this app exists to
        // prevent (SPEC.md §3).
        composeRule.onNodeWithText("Silence your phone.").assertExists()
        composeRule.onNodeWithText("Silence your phone until you leave.").assertDoesNotExist()
    }

    @Test
    fun `card two reads the endings off the notification render`() {
        capture("welcome-ends.png") { Flow(WelcomeCard.ENDS) }

        // One line now (maintainer, 2026-09-05): the title says the endings are
        // automatic, the body lists them.
        composeRule.onNodeWithText("Ends automatically").assertExists()
        composeRule
            .onNodeWithText("When you leave, when your meeting ends, or at the time you choose.")
            .assertExists()
        // The render is one node, not three tappable-looking buttons: it is a
        // picture of a snooze that is not running.
        composeRule
            .onNodeWithContentDescription("Example of Snoozemo's notification while a snooze is running")
            .assertExists()
    }

    @Test
    fun `card two drops departure and the location row on a timer-only build`() {
        capture("welcome-ends-timer-only.png") {
            Flow(WelcomeCard.ENDS, tracksDeparture = false)
        }

        // The title is true on both builds, so it does not change; the body is
        // where departure drops out of the list.
        composeRule.onNodeWithText("Ends automatically").assertExists()
        composeRule
            .onNodeWithText("When your meeting ends, or at the time you choose.")
            .assertExists()
        composeRule
            .onNodeWithText("When you leave, when your meeting ends, or at the time you choose.")
            .assertDoesNotExist()
        // Nothing is asserted about the render's own body here, and that is not
        // an omission: it is one semantics node on purpose, so its inner text
        // is unreachable and an `assertDoesNotExist` on it would pass whatever
        // it said. What the render carries — the string this build actually
        // posts rather than invented copy — is held by construction and by the
        // captured image, which is what the snapshot is for.
        //
        // No location row, though: a grant that buys the user nothing must not
        // be invited, exactly as on that flavor's permissions screen.
        composeRule.onNodeWithText("Location").assertDoesNotExist()
    }

    @Test
    fun `card three offers the tile`() {
        capture("welcome-tile.png") { Flow(WelcomeCard.TILE) }

        composeRule.onNodeWithText("Swipe down and tap the Zzz tile.").assertExists()
        composeRule.onNodeWithText("Works with the phone locked.").assertExists()
        // `Add`, the same verb the banner and the Settings row use — the row is
        // the real one, not a copy of it.
        composeRule.onNodeWithText("Add").assertExists()
    }

    @Test
    fun `card four carries the rule and the ringer choice`() {
        capture("welcome-rule.png") { Flow(WelcomeCard.RULE) }

        composeRule.onNodeWithText("One rule, yours").assertExists()
        // The ringer ceiling is a live control here, not a description of one.
        composeRule.onNodeWithText("Vibrate").assertExists()
        // Do Not Disturb access comes last of the grants: it is the one without
        // which nothing here can snooze at all.
        composeRule.onNodeWithText("Do Not Disturb access").assertExists()
    }

    @Test
    fun `card five asks the consent with the affirmative trailing`() {
        var answered: Boolean? = null

        capture("welcome-telemetry.png") {
            Flow(WelcomeCard.TELEMETRY, onAnswerTelemetry = { answered = it })
        }

        composeRule.onNodeWithText("No thanks").assertExists()
        composeRule.onNodeWithText("Yes please").performClick()
        assertEquals(true, answered)
        // The last card says what it does rather than promising a card that
        // isn't there.
        composeRule.onNodeWithText("Done").assertExists()
        composeRule.onNodeWithText("Next").assertDoesNotExist()
    }

    @Test
    fun `a build that collects nothing has no consent card`() {
        // With the debug-log sentence gone (maintainer, 2026-09-05) there is
        // nothing else on that card, so it would be a blank screen and a fifth
        // dot promising one.
        assertEquals(
            listOf(WelcomeCard.WHAT, WelcomeCard.ENDS, WelcomeCard.TILE, WelcomeCard.RULE),
            welcomeCards(collectsTelemetry = false),
        )
    }

    @Test
    fun `next advances and skip leaves from any card`() {
        var advanced = 0
        var skipped = 0

        capture { Flow(WelcomeCard.TILE, onNext = { advanced++ }, onSkip = { skipped++ }) }

        composeRule.onNodeWithText("Next").performClick()
        assertEquals(1, advanced)
        composeRule.onNodeWithText("Skip").performClick()
        assertEquals(1, skipped)
    }

    @Test
    fun `a crashed run is surfaced on the flow too`() {
        // The flow is a cold-start landing screen now, so it owes the banner
        // the other two carry: a crash from before that same cold start would
        // otherwise stay silent until the user finished onboarding, and
        // onboarding is exactly when they are least likely to finish quickly
        // (SPEC.md §4.6; Codex, PR #204).
        capture("welcome-crash-banner.png") { Flow(WelcomeCard.WHAT, crashPending = true) }

        composeRule.onNodeWithText("Snoozemo crashed").assertExists()
    }

    @Test
    fun `card four shows a disabled rule and its repair`() {
        // Access granted with the rule switched off in Settings. Without the
        // verified state threaded through, `PermissionRows.Access` reads
        // granted-and-unread and renders nothing — hiding both the failure and
        // the one action that fixes it (Codex, PR #204).
        capture("welcome-rule-disabled.png") {
            Flow(
                WelcomeCard.RULE,
                access = PolicyAccess.GRANTED,
                ruleState = ZenRuleState.DISABLED,
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access").assertExists()
        composeRule.onNodeWithText("Allow").assertExists()
    }

    @Test
    fun `card one in dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        capture("welcome-what-dark.png") { Flow(WelcomeCard.WHAT) }
    }

    @Test
    fun `card two in dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        capture("welcome-ends-dark.png") { Flow(WelcomeCard.ENDS) }
    }

    /**
     * The flow as `MainActivity` composes it, with every capability read and
     * ungranted — which is what a fresh install looks like, and the state in
     * which every card has an offer to make.
     */
    @Composable
    private fun Flow(
        card: WelcomeCard,
        tracksDeparture: Boolean = true,
        access: PolicyAccess? = PolicyAccess.DENIED,
        ruleState: ZenRuleState? = null,
        crashPending: Boolean = false,
        onAnswerTelemetry: (Boolean) -> Unit = {},
        onNext: () -> Unit = {},
        onSkip: () -> Unit = {},
    ) {
        WelcomeScreen(
            card = card,
            cards = allCards,
            access = access,
            ruleState = ruleState,
            crashPending = crashPending,
            notifications = NotificationPermission.ASKABLE,
            notificationsReachTheUser = false,
            location = LocationPermission.ASKABLE,
            calendar = CalendarPermission.ASKABLE,
            tracksDeparture = tracksDeparture,
            tileAdded = false,
            snoozeRinger = SnoozeRinger.VIBRATE,
            onAnswerTelemetry = onAnswerTelemetry,
            onNext = onNext,
            onSkip = onSkip,
        )
    }

    /** Same shape as the sibling screenshot tests' — see `MainScreenScreenshotTest`. */
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
