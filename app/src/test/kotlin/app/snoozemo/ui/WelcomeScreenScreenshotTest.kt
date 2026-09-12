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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import app.snoozemo.core.CalendarPermission
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.core.ZenRuleState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * states are the point rather than the pixels. The two that differ by whether
 * the build tracks departure are captured both ways: cards 1 and 2 promise
 * departure, and on a build that cannot deliver it they must promise something
 * else instead.
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
        // The picture card 1 leads with (maintainer, 2026-09-07). Asserted by
        // its description rather than by the tile labels, because that is the
        // whole of what a screen reader gets: the panel is one image, and four
        // labels read out in a row say nothing about what it is for.
        composeRule
            .onNodeWithContentDescription(
                "Quick Settings, with Snoozemo's tile ringed among the others",
            )
            .assertExists()
        // No `Skip` here (maintainer, 2026-09-06): offering to leave beside the
        // one line that says what the app is invites skipping before there is
        // anything to skip. D7 is untouched — back still exits card 1, so the
        // way out is there, just not advertised yet.
        composeRule.onNodeWithText("Back").assertExists()
        composeRule.onNodeWithText("Next").assertExists()
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
    }

    @Test
    fun `the title row carries the title and skip, over back-dots-next`() {
        // The layout the maintainer asked for on 2026-09-06: the card's title
        // in the same row every other screen puts one, `Skip` its trailing
        // action, and along the bottom `Back`, the progress dots and `Next` —
        // the two controls that step through the flow either side of the thing
        // that says where in it you are.
        //
        // Card 2, because card 1 is the one card with no `Skip`.
        //
        // Asserted rather than left to the snapshot: a recorded image goes red
        // for any pixel that moves, so it says nothing about which arrangement
        // was intended, and re-recording is what an agent does to a red one.
        capture { Flow(WelcomeCard.ENDS) }

        val title = composeRule.onNodeWithText("Ends automatically")
            .fetchSemanticsNode().positionInRoot
        val skip = composeRule.onNodeWithText("Skip").fetchSemanticsNode().positionInRoot
        val back = composeRule.onNodeWithText("Back").fetchSemanticsNode().positionInRoot
        val dots = composeRule.onNodeWithContentDescription("Card 2 of 5")
            .fetchSemanticsNode().positionInRoot
        val next = composeRule.onNodeWithText("Next").fetchSemanticsNode().positionInRoot

        assertTrue("the title heads the screen", title.y < back.y)
        assertTrue("Skip shares the title's row", skip.y < back.y)
        assertTrue("Skip is that row's trailing action", skip.x > title.x)
        assertTrue("Back leads the bottom row", back.x < dots.x)
        assertTrue("Next trails it", dots.x < next.x)
        assertTrue("the dots share the bottom row", dots.y > title.y)
    }

    @Test
    fun `card one promises no departure on a build that cannot track it`() {
        capture("welcome-what-timer-only.png") {
            Flow(WelcomeCard.WHAT, tracksDeparture = false)
        }

        // The promise a build that cannot track departure can actually keep.
        // Promising departure there sets up exactly the silence-until-the-cap
        // this app exists to prevent (SPEC.md §3).
        composeRule.onNodeWithText("Silence your phone.").assertExists()
        composeRule.onNodeWithText("Silence your phone until you leave.").assertDoesNotExist()
    }

    @Test
    fun `a tile tap that could not snooze says so on the card it lands back on`() {
        // The flow resumes rather than restarting (maintainer, 2026-09-07), so
        // the only thing that tells the user their tap did nothing is this
        // line — the card behind it is exactly the one they were already on.
        capture("welcome-tap-blocked.png") {
            Flow(WelcomeCard.TILE, tapBlocked = true)
        }

        composeRule.onNodeWithText("That tap couldn't snooze yet — finish setup first.")
            .assertExists()
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
        // be invited, exactly as on such a build's permissions screen.
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
            listOf(WelcomeCard.WHAT, WelcomeCard.ENDS, WelcomeCard.RULE, WelcomeCard.TILE),
            welcomeCards(collectsTelemetry = false),
        )
    }

    @Test
    fun `the rule comes before the tile it will arm`() {
        // Do Not Disturb access before `Add tile` (maintainer, 2026-09-08). A
        // tile added first is one whose first tap fails with NO_POLICY_ACCESS;
        // the grant taken first leaves an app that already snoozes from its own
        // button, so this is the order that costs least when the user abandons
        // the flow part way.
        assertEquals(
            listOf(
                WelcomeCard.WHAT,
                WelcomeCard.ENDS,
                WelcomeCard.RULE,
                WelcomeCard.TILE,
                WelcomeCard.TELEMETRY,
            ),
            welcomeCards(collectsTelemetry = true),
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
    fun `card four offers Filters once there is a rule to edit`() {
        // The card's title calls the rule the user's; this is the button that
        // makes that true rather than a claim (maintainer, 2026-09-05).
        capture("welcome-rule-filters.png") {
            Flow(
                WelcomeCard.RULE,
                access = PolicyAccess.GRANTED,
                ruleState = ZenRuleState.READY,
                filtersRuleId = "rule-1",
            )
        }

        composeRule.onNodeWithText("Filters").assertExists()
        composeRule.onNodeWithText("Edit").assertExists()
    }

    @Test
    fun `card four offers no Filters button before the rule exists`() {
        // Absent rather than disabled: with no access, or access granted and
        // the rule not yet created, there is nothing behind the button, and a
        // dead tap is what the row's null check exists to prevent.
        capture { Flow(WelcomeCard.RULE, filtersRuleId = null) }

        composeRule.onNodeWithText("Filters").assertDoesNotExist()
    }

    @Test
    fun `the consent card reports both answers`() {
        // The card's half of the contract, and all of it: it reports which
        // button was pressed and decides nothing else. That leaving the flow
        // follows is the activity's, and `MainActivityWelcomeRouteTest` is
        // where it is asserted — naming the exit here would let dropping it
        // leave this green (Codex, PR #206).
        var answers = mutableListOf<Boolean>()

        capture { Flow(WelcomeCard.TELEMETRY, onAnswerTelemetry = { answers += it }) }

        composeRule.onNodeWithText("Yes please").performClick()
        composeRule.onNodeWithText("No thanks").performClick()
        assertEquals(listOf(true, false), answers)
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
        filtersRuleId: String? = null,
        crashPending: Boolean = false,
        tapBlocked: Boolean = false,
        notifications: NotificationPermission = NotificationPermission.ASKABLE,
        notificationsReachTheUser: Boolean = false,
        location: LocationPermission = LocationPermission.ASKABLE,
        calendar: CalendarPermission = CalendarPermission.ASKABLE,
        tileAdded: Boolean? = false,
        settingsFailure: SetupRowId? = null,
        onAnswerTelemetry: (Boolean) -> Unit = {},
        onNext: () -> Unit = {},
        onSkip: () -> Unit = {},
    ) {
        WelcomeScreen(
            card = card,
            cards = allCards,
            access = access,
            ruleState = ruleState,
            filtersRuleId = filtersRuleId,
            crashPending = crashPending,
            tapBlocked = tapBlocked,
            settingsFailure = settingsFailure,
            notifications = notifications,
            notificationsReachTheUser = notificationsReachTheUser,
            location = location,
            calendar = calendar,
            tracksDeparture = tracksDeparture,
            tileAdded = tileAdded,
            snoozeRinger = SnoozeRinger.VIBRATE,
            onAnswerTelemetry = onAnswerTelemetry,
            onNext = onNext,
            onSkip = onSkip,
        )
    }

    @Test
    fun `card two drops a permission row once it is satisfied`() {
        // The cards ask for what is still missing; a row with no action left is
        // a line the user reads past on a screen whose whole job is what still
        // needs them (maintainer, 2026-09-05). `PermissionsScreen` keeps its
        // granted rows — stating what is in place is that screen's job.
        //
        // Captured nowhere: this is the absence of three rows, which a snapshot
        // of an otherwise-unchanged card cannot distinguish from a card that
        // never drew them.
        capture {
            Flow(
                WelcomeCard.ENDS,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
                location = LocationPermission.GRANTED,
                calendar = CalendarPermission.GRANTED,
            )
        }

        composeRule.onNodeWithText("Location").assertDoesNotExist()
        composeRule.onNodeWithText("Calendar").assertDoesNotExist()
        composeRule.onNodeWithText("Notifications").assertDoesNotExist()
        // The card itself still stands: hiding the rows must not hide the idea.
        composeRule.onNodeWithText("Ends automatically").assertExists()
    }

    @Test
    fun `card three drops the tile row once the tile is added`() {
        // The one row whose satisfied state has its own copy — an inert
        // "Added" line the user cannot act on (Codex, PR #206).
        capture { Flow(WelcomeCard.TILE, tileAdded = true) }

        composeRule.onNodeWithText("Quick Settings tile").assertDoesNotExist()
        composeRule.onNodeWithText("Added").assertDoesNotExist()
        // The card itself still stands, and `card three offers the tile` holds
        // the other direction: with the tile missing, the row and its `Add` are
        // both there. Without that pair either assertion here would pass on a
        // string that had simply been renamed.
        composeRule.onNodeWithText("Snooze from Quick Settings").assertExists()
    }

    @Test
    fun `card four drops the access row once the rule is ready`() {
        // Granted *and* the rule created: `PermissionRows.Access` treats
        // granted-and-unread as not-yet-satisfied on purpose, so both halves
        // have to land before the row goes (Codex, PR #204).
        capture {
            Flow(
                WelcomeCard.RULE,
                access = PolicyAccess.GRANTED,
                ruleState = ZenRuleState.READY,
                filtersRuleId = "rule-id",
            )
        }

        composeRule.onNodeWithText("Do Not Disturb access").assertDoesNotExist()
        // Filters is not a permission and never hides: it is the card's offer.
        composeRule.onNodeWithText("Filters").assertExists()
    }

    @Test
    fun `a refused filters launch is reported once`() {
        // Card 3 is the only screen that draws the access row and the Filters
        // row together, and with the rule disabled both buttons open the same
        // settings screen. Reported on both, one refused tap printed the line
        // twice and made the untouched row look like it had failed too (Codex,
        // PR #206).
        capture {
            Flow(
                WelcomeCard.RULE,
                access = PolicyAccess.GRANTED,
                ruleState = ZenRuleState.DISABLED,
                filtersRuleId = "rule-id",
                settingsFailure = SetupRowId.FILTERS,
            )
        }

        assertEquals(
            1,
            composeRule.onAllNodesWithText("Couldn't open Settings")
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h240dp-420dpi", fontScale = 2f)
    fun `the body keeps a viewport when the title wraps on a short window`() {
        // Android's largest font scale in a short multi-window pane, on the
        // card with the longest title. With the title row pinned above the
        // body, the wrapped heading plus the pinned controls consumed the
        // column before the weighted body was measured, so its text and its
        // `Add` button were not clipped but absent — no viewport to scroll
        // them into (Codex, PR #209). The title row now scrolls with the body,
        // as on every other screen, so the body is always reachable.
        //
        // No capture: a snapshot of a wrapped heading at this size would say
        // nothing about whether what is below it can be reached.
        // Both set on the method's `@Config` rather than in the body: the
        // rule's activity is created before the body runs, and a font scale
        // set after that reached nothing this test measures.
        capture { Flow(WelcomeCard.TILE) }

        // *Fully* visible after scrolling to it, not merely displayed: with the
        // title pinned, the body was left a 26dp sliver — enough for
        // `assertIsDisplayed` to pass on a corner of the button, and nothing
        // like enough to ever show a 53dp button whole.
        for (text in listOf("Swipe down and tap the Zzz tile.", "Add")) {
            val node = composeRule.onNodeWithText(text).performScrollTo().fetchSemanticsNode()
            assertEquals(
                "'$text' must fit its viewport whole once scrolled to",
                node.size.height,
                node.boundsInRoot.height.toInt(),
            )
        }
        // The pinned row is still pinned: the exit never left the screen.
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
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
