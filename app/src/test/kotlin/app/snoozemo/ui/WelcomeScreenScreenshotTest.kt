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
 * The welcome flow's cards (`SPEC.md` §4.2, wording in `TUTORIAL.md`).
 *
 * Four cards on every build, plus the crash/analytics consent on a build that
 * collects it. Each card is one idea and one picture, so each gets its own
 * capture — the states are the point rather than the pixels. Card 1's two lines
 * are build-neutral (maintainer, 2026-09-14); card 4 still promises departure
 * and is captured both ways, since on a build that cannot deliver it it must
 * promise something else instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val allCards = welcomeCards(collectsTelemetry = true)

    @Test
    fun `card one says what the app is and adds the tile`() {
        capture("welcome-what.png") { Flow(WelcomeCard.WHAT) }

        // Card 1's two lines (maintainer, 2026-09-14), build-neutral.
        composeRule.onNodeWithText("Silence your phone with one tap.").assertExists()
        composeRule.onNodeWithText("Ends automatically, so you don't forget.").assertExists()
        // The picture card 1 leads with (maintainer, 2026-09-07). Asserted by
        // its description rather than by the tile labels, because that is the
        // whole of what a screen reader gets.
        composeRule
            .onNodeWithContentDescription(
                "Quick Settings, with Snoozemo's tile ringed among the others",
            )
            .assertExists()
        // The tile row leads the flow now (maintainer, 2026-09-15): the first
        // card both shows the tile and offers to add it. `Add`, the same verb
        // the banner and the Settings row use — the row is the real one.
        composeRule.onNodeWithText("Add").assertExists()
        // No `Skip` here (maintainer, 2026-09-06): offering to leave beside the
        // one line that says what the app is invites skipping before there is
        // anything to skip. D7 is untouched — back still exits card 1.
        composeRule.onNodeWithText("Back").assertExists()
        composeRule.onNodeWithText("Next").assertExists()
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
    }

    @Test
    fun `card one drops the tile row once the tile is added`() {
        // The one row whose satisfied state has its own copy — an inert
        // "Added" line the user cannot act on (Codex, PR #206).
        capture { Flow(WelcomeCard.WHAT, tileAdded = true) }

        composeRule.onNodeWithText("Quick Settings tile").assertDoesNotExist()
        composeRule.onNodeWithText("Add").assertDoesNotExist()
        // The card itself still stands: hiding the row must not hide the idea.
        composeRule.onNodeWithText("Silence your phone with one tap.").assertExists()
    }

    @Test
    fun `card one reserves the tile row's space while its state is unknown`() {
        // The Add-tile row leads card 1, so it is on the first frame — but the
        // tile-presence store is read only after it, like every other permission
        // state (`SPEC.md` §6.9). While it is unknown (`null`) the row is an
        // invisible skeleton that reserves its height and fades in when the read
        // lands (maintainer, 2026-09-15), so the affordance pops in without the
        // card reflowing to make room. Captured so the reserved-but-blank row
        // reads against the filled one in `welcome-what.png`.
        capture("welcome-what-loading.png") { Flow(WelcomeCard.WHAT, tileAdded = null) }

        // The idea still stands while the row is loading...
        composeRule.onNodeWithText("Silence your phone with one tap.").assertExists()
        // ...but the skeleton is invisible and unreachable: cleared from the
        // semantics tree, so a screen reader is not told of a row that is not
        // there yet, and no stale `Add` can be tapped before the read lands.
        composeRule.onNodeWithText("Add").assertDoesNotExist()
    }

    @Test
    fun `the title row carries the title and skip, over back-dots-next`() {
        // The layout the maintainer asked for on 2026-09-06: the card's title
        // in the same row every other screen puts one, `Skip` its trailing
        // action, and along the bottom `Back`, the progress dots and `Next`.
        //
        // Card 2, because card 1 is the one card with no `Skip`.
        //
        // Asserted rather than left to the snapshot: a recorded image goes red
        // for any pixel that moves, so it says nothing about which arrangement
        // was intended, and re-recording is what an agent does to a red one.
        capture { Flow(WelcomeCard.RULE) }

        val title = composeRule.onNodeWithText("One rule, yours")
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
    fun `a tile tap during the flow says so on the card it lands back on`() {
        // The flow resumes rather than restarting (maintainer, 2026-09-07,
        // broadened 2026-09-15), so the only thing that tells the user their tap
        // did nothing is this line — the card behind it is the one they were on.
        capture("welcome-tap-blocked.png") {
            Flow(WelcomeCard.ENDS_AUTO, tapBlocked = true)
        }

        composeRule.onNodeWithText("Finish setup first — then the tile snoozes.")
            .assertExists()
    }

    @Test
    fun `card two carries the rule, filters and the ringer choice`() {
        capture("welcome-rule.png") { Flow(WelcomeCard.RULE) }

        composeRule.onNodeWithText("One rule, yours").assertExists()
        composeRule
            .onNodeWithText("Creates a new Do Not Disturb mode that you configure.")
            .assertExists()
        // The ringer ceiling is a live control here, not a description of one.
        composeRule.onNodeWithText("Vibrate").assertExists()
        // Do Not Disturb access, the grant without which nothing here can snooze.
        composeRule.onNodeWithText("Do Not Disturb access").assertExists()
    }

    @Test
    fun `card two offers Filters once there is a rule to edit`() {
        // The card names the rule the user's; this is the button that makes that
        // true rather than a claim (maintainer, 2026-09-05).
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
    fun `card two offers no Filters button before the rule exists`() {
        // Absent rather than disabled: with no access, or access granted and
        // the rule not yet created, there is nothing behind the button.
        capture { Flow(WelcomeCard.RULE, filtersRuleId = null) }

        composeRule.onNodeWithText("Filters").assertDoesNotExist()
    }

    @Test
    fun `card two drops the access row once the rule is ready`() {
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
    fun `card two shows a disabled rule and its repair`() {
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
    fun `a refused filters launch is reported once`() {
        // Card 2 is the only screen that draws the access row and the Filters
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
    fun `card three explains the manual endings off the notification render`() {
        capture("welcome-ends-manual.png") { Flow(WelcomeCard.ENDS_MANUAL) }

        composeRule.onNodeWithText("End manually").assertExists()
        // Three separate paragraphs now, one per way to end a snooze by hand.
        composeRule
            .onNodeWithText("Tap the notification buttons to end or extend the snooze.")
            .assertExists()
        composeRule
            .onNodeWithText("Tap the notification body for more options.")
            .assertExists()
        composeRule
            .onNodeWithText("Tapping the tile again also turns it off.")
            .assertExists()
        // The render is one node, not three tappable-looking buttons: it is a
        // picture of a snooze that is not running.
        composeRule
            .onNodeWithContentDescription("Example of Snoozemo's notification while a snooze is running")
            .assertExists()
        // The notification grant sits with the card that depicts it.
        composeRule.onNodeWithText("Notifications").assertExists()
    }

    @Test
    fun `card four lists the automatic endings off the chooser render`() {
        capture("welcome-ends-auto.png") { Flow(WelcomeCard.ENDS_AUTO) }

        composeRule.onNodeWithText("Ends automatically").assertExists()
        composeRule
            .onNodeWithText("When you leave, when your meeting ends, or at the time you choose.")
            .assertExists()
        // The render is one node, not a set of tappable-looking rows — but its
        // one description names the endings it depicts, including the move exit
        // the body never mentions, so a screen reader is not left with a bare
        // "a chooser" (Codex, PR #291).
        composeRule
            .onNodeWithContentDescription("Example of Snoozemo's end-time chooser", substring = true)
            .assertExists()
        composeRule.onNodeWithContentDescription("Until I move", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Until I leave", substring = true).assertExists()
        // Both grants the automatic endings need.
        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithText("Location").assertExists()
    }

    @Test
    fun `card four drops departure and the location row on a timer-only build`() {
        // A timer-only build cannot track departure at all, so its illustration
        // drops both the move and leave exits and the location grant.
        capture("welcome-ends-auto-timer-only.png") {
            Flow(WelcomeCard.ENDS_AUTO, tracksDeparture = false)
        }

        composeRule.onNodeWithText("Ends automatically").assertExists()
        composeRule
            .onNodeWithText("When your meeting ends, or at the time you choose.")
            .assertExists()
        composeRule
            .onNodeWithText("When you leave, when your meeting ends, or at the time you choose.")
            .assertDoesNotExist()
        // No location row: a grant that buys the user nothing must not be
        // invited, exactly as on such a build's permissions screen. The calendar
        // stays, offered on every build.
        composeRule.onNodeWithText("Location").assertDoesNotExist()
        composeRule.onNodeWithText("Calendar").assertExists()
        // The illustration drops the departure exits too, in words and to a
        // screen reader.
        composeRule.onNodeWithContentDescription("Until I move", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Until I leave", substring = true).assertDoesNotExist()
    }

    @Test
    fun `card four drops a permission row once it is satisfied`() {
        // The cards ask for what is still missing; a row with no action left is
        // a line the user reads past (maintainer, 2026-09-05).
        capture {
            Flow(
                WelcomeCard.ENDS_AUTO,
                location = LocationPermission.GRANTED,
                calendar = CalendarPermission.GRANTED,
            )
        }

        composeRule.onNodeWithText("Location").assertDoesNotExist()
        composeRule.onNodeWithText("Calendar").assertDoesNotExist()
        // The card itself still stands: hiding the rows must not hide the idea.
        composeRule.onNodeWithText("Ends automatically").assertExists()
    }

    @Test
    fun `card three drops the notifications row once it is satisfied`() {
        capture {
            Flow(
                WelcomeCard.ENDS_MANUAL,
                notifications = NotificationPermission.GRANTED,
                notificationsReachTheUser = true,
            )
        }

        composeRule.onNodeWithText("Notifications").assertDoesNotExist()
        composeRule.onNodeWithText("End manually").assertExists()
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
    fun `the consent card reports both answers`() {
        // The card's half of the contract, and all of it: it reports which
        // button was pressed and decides nothing else. That leaving the flow
        // follows is the activity's (`MainActivityWelcomeRouteTest`).
        val answers = mutableListOf<Boolean>()

        capture { Flow(WelcomeCard.TELEMETRY, onAnswerTelemetry = { answers += it }) }

        composeRule.onNodeWithText("Yes please").performClick()
        composeRule.onNodeWithText("No thanks").performClick()
        assertEquals(listOf(true, false), answers)
    }

    @Test
    fun `a build that collects nothing has no consent card`() {
        // With the debug-log sentence gone (maintainer, 2026-09-05) there is
        // nothing else on that card, so it would be a blank screen and an extra
        // dot promising one.
        assertEquals(
            listOf(
                WelcomeCard.WHAT,
                WelcomeCard.RULE,
                WelcomeCard.ENDS_MANUAL,
                WelcomeCard.ENDS_AUTO,
            ),
            welcomeCards(collectsTelemetry = false),
        )
    }

    @Test
    fun `the cards run in their onboarding order`() {
        // What the app is and its tile, the rule it silences with, ending it by
        // hand, ending it by itself, then the one consent question (maintainer,
        // 2026-09-15).
        assertEquals(
            listOf(
                WelcomeCard.WHAT,
                WelcomeCard.RULE,
                WelcomeCard.ENDS_MANUAL,
                WelcomeCard.ENDS_AUTO,
                WelcomeCard.TELEMETRY,
            ),
            welcomeCards(collectsTelemetry = true),
        )
    }

    @Test
    fun `next advances and skip leaves from any card`() {
        var advanced = 0
        var skipped = 0

        capture { Flow(WelcomeCard.ENDS_MANUAL, onNext = { advanced++ }, onSkip = { skipped++ }) }

        composeRule.onNodeWithText("Next").performClick()
        assertEquals(1, advanced)
        composeRule.onNodeWithText("Skip").performClick()
        assertEquals(1, skipped)
    }

    @Test
    fun `a crashed run is surfaced on the flow too`() {
        // The flow is a cold-start landing screen now, so it owes the banner
        // the other two carry: a crash from before that same cold start would
        // otherwise stay silent until the user finished onboarding (SPEC.md
        // §4.6; Codex, PR #204).
        capture("welcome-crash-banner.png") { Flow(WelcomeCard.WHAT, crashPending = true) }

        composeRule.onNodeWithText("Snoozemo crashed").assertExists()
    }

    @Test
    fun `card one in dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        capture("welcome-what-dark.png") { Flow(WelcomeCard.WHAT) }
    }

    @Test
    fun `card four in dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        capture("welcome-ends-auto-dark.png") { Flow(WelcomeCard.ENDS_AUTO) }
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
    @Config(sdk = [36], qualifiers = "w411dp-h240dp-420dpi", fontScale = 2f)
    fun `the body keeps a viewport when the title wraps on a short window`() {
        // Android's largest font scale in a short multi-window pane, on card 1 —
        // which leads with the Quick Settings illustration, so its body and its
        // `Add` button sit well below the fold. With the title row pinned above
        // the body, the wrapped heading plus the pinned controls consumed the
        // column before the weighted body was measured, so its text and button
        // were not clipped but absent — no viewport to scroll them into (Codex,
        // PR #209). The title row now scrolls with the body, so the body is
        // always reachable.
        //
        // Plain body text and a button, not a `SetupRow` title: a row merges
        // title, status and button into one node too tall to ever fit a 240dp
        // window whole, which is a fact about that node's height rather than the
        // reachability this test is about.
        //
        // No capture: a snapshot of a wrapped heading at this size would say
        // nothing about whether what is below it can be reached.
        capture { Flow(WelcomeCard.WHAT) }

        // *Fully* visible after scrolling to it, not merely displayed.
        for (text in listOf("Silence your phone with one tap.", "Add")) {
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
