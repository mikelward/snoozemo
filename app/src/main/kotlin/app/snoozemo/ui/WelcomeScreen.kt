package app.snoozemo.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.core.CalendarPermission
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.core.ZenRuleState

/**
 * Which card of the welcome flow is showing (`SPEC.md` §4.2, wording and
 * reasoning in `TUTORIAL.md`).
 *
 * An enum rather than an index so the flavor filtering below has something to
 * name: [TELEMETRY] is absent on a build that ships no crash-reporting SDK, and
 * a bare `4` would then mean a different card depending on the flavor.
 */
enum class WelcomeCard {
    /** What the app is. */
    WHAT,

    /** How a snooze ends, on a render of the ongoing notification. */
    ENDS,

    /** How to start one: the tile. */
    TILE,

    /** The one Do Not Disturb rule, and the ringer ceiling. */
    RULE,

    /** The crash-report and analytics consent (§12). */
    TELEMETRY,
}

/**
 * The cards this build actually shows, in order.
 *
 * [TELEMETRY][WelcomeCard.TELEMETRY] is dropped where nothing collects — the
 * `direct` flavor ships neither SDK (§12), and with the debug-log sentence gone
 * (maintainer, 2026-09-05) there is nothing else on that card, so it would be a
 * blank screen and a sixth dot. The list is what the dots count, so dropping it
 * here is what keeps them honest.
 */
fun welcomeCards(collectsTelemetry: Boolean): List<WelcomeCard> =
    WelcomeCard.entries.filter { collectsTelemetry || it != WelcomeCard.TELEMETRY }

/**
 * Whether a launch should open the welcome flow (`SPEC.md` §4.2).
 *
 * **Fresh installs only, and [seen] alone does not say that.** The flag is
 * absent on an install that predates the flow exactly as it is on a new one, so
 * reading it by itself would march every existing user through onboarding on
 * the update that shipped this — people who have been snoozing for months
 * (Codex, PR #204). [freshInstall] is the platform's own answer, and it needs
 * no migration write: nothing has to be seeded, because nothing about an
 * upgraded install is being read wrong.
 *
 * The help icon is unaffected — a replay is deliberate, so it never consults
 * this.
 *
 * **[freshInstall] is a lambda so that it is only asked when it matters.** It
 * is a `PackageManager` binder call, and this runs in front of the first frame;
 * passing it by value made every launch pay for it, [seen] installs included,
 * which is every launch after the first (Codex, PR #204). Short-circuiting is
 * the answer, and a lambda is what makes forgetting it impossible.
 */
fun shouldOpenWelcome(seen: Boolean, freshInstall: () -> Boolean): Boolean = !seen && freshInstall()

/**
 * Whether leaving the flow should land on the permissions recap rather than the
 * main screen (`SPEC.md` §4.2) — true when any capability this flavor offers is
 * still ungranted.
 *
 * **Every offered row, not just Do Not Disturb access.** Access alone was the
 * wrong test: a user who allowed it on card 4 and skipped the rest reached the
 * main screen able to arm with no notification to show status on, which is the
 * recap's whole job to catch, since each of its rows carries the consequence of
 * the no the user just gave (Codex, PR #204).
 *
 * "Missing" means a permission the recap would show an **action** for, so the
 * two exclusions match the rows themselves: an *unread* capability is not a
 * missing one — the readings land after the first frame, and routing on one
 * would send the user to a recap of things nothing has checked — and on a build
 * that cannot track departure the location row offers nothing, so an ungranted
 * location permission there counts for nothing.
 *
 * A pure function rather than a method on the activity: this is the decision
 * that either strands a user past the recap or shows them one with nothing on
 * it, and it belongs where a JVM test can enumerate its cases.
 */
fun welcomeExitNeedsRecap(
    access: PolicyAccess?,
    notifications: NotificationPermission?,
    notificationsReachTheUser: Boolean,
    location: LocationPermission?,
    calendar: CalendarPermission?,
    tracksDeparture: Boolean,
): Boolean {
    val accessMissing = access?.let { it != PolicyAccess.GRANTED } == true
    // Granted is necessary and not sufficient here, exactly as the row says:
    // the permission can be held while the app or a channel is switched off,
    // and the system then drops every post.
    val notificationsMissing = notifications?.let {
        it != NotificationPermission.GRANTED || !notificationsReachTheUser
    } == true
    val locationMissing = tracksDeparture &&
        location?.let { it != LocationPermission.GRANTED } == true
    val calendarMissing = calendar?.let { it != CalendarPermission.GRANTED } == true
    return accessMissing || notificationsMissing || locationMissing || calendarMissing
}

/**
 * The first-run flow: a short run of fixed cards, each one idea, before the
 * permissions screen (`SPEC.md` §4.2).
 *
 * **Every card's button is optional.** `Next` never waits on a grant — the same
 * fail-open rule the permission rows follow (D7): a setup flow that cannot be
 * left without finishing it is a trap, not onboarding. `Skip` and the last
 * card's `Done` land in the same place, so no route through this misses a
 * missing permission and none gets stuck.
 *
 * **The grants are the real rows, not a copy of them.** Each card embeds the
 * same [SetupRow] `PermissionsScreen` draws, so the observed-denial handling,
 * the location disclosure sequence (§3.2) and the rule that no row offers an
 * action the platform will ignore all come for free rather than being
 * re-implemented per card and drifting.
 */
@Composable
fun WelcomeScreen(
    card: WelcomeCard,
    cards: List<WelcomeCard>,
    access: PolicyAccess?,
    notifications: NotificationPermission?,
    notificationsReachTheUser: Boolean,
    location: LocationPermission?,
    calendar: CalendarPermission? = null,
    /**
     * Whether this build can end a snooze because the user left. Card 1 and
     * card 2 both promise departure, and on a build that cannot deliver it
     * that promise sets up exactly the silence-until-the-cap the app exists to
     * prevent (§3) — so the seam is at the call site, as it is for
     * [PermissionsScreen] and [EndConditionSheet].
     */
    tracksDeparture: Boolean = true,
    tileAdded: Boolean? = null,
    snoozeRinger: SnoozeRinger? = null,
    snoozeRingerSaveFailed: Boolean = false,
    /**
     * The verified state of Snoozemo's own rule, or null while unread. Card 4's
     * access row needs it for the same reason `PermissionsScreen`'s does: with
     * access granted and this null the row reads as unread and renders nothing,
     * which would hide both a known failure and, for a disabled rule, the
     * repair (Codex, PR #204).
     */
    ruleState: ZenRuleState? = null,
    settingsFailure: SetupRowId? = null,
    /**
     * Whether a crashed run is pinned (`SPEC.md` §4.6). The flow is a cold-start
     * landing screen now, so it owes the banner the other two landing screens
     * carry — a crash from before that same cold start would otherwise stay
     * silent until the user finished onboarding (Codex, PR #204).
     */
    crashPending: Boolean = false,
    shareFailed: Boolean = false,
    dismissFailed: Boolean = false,
    sharing: Boolean = false,
    onAccessRow: () -> Unit = {},
    /** Opens the rule's own screen, the repair for a disabled rule. */
    onRuleRow: () -> Unit = onAccessRow,
    onShareDebugLog: () -> Unit = {},
    onDismissCrash: () -> Unit = {},
    onNotificationsRow: () -> Unit = {},
    onLocationRow: () -> Unit = {},
    onCalendarRow: () -> Unit = {},
    onAddTile: () -> Unit = {},
    onSnoozeRinger: (SnoozeRinger) -> Unit = {},
    onAnswerTelemetry: (Boolean) -> Unit = {},
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = cards.indexOf(card).takeIf { it >= 0 } ?: 0
    val last = position == cards.lastIndex
    Column(
        modifier = modifier
            .fillMaxSize()
            // Outside the scroll, like every screen here — see MainScreen's
            // note on why safeDrawingPadding sits around the scroll.
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        // The body scrolls and the controls below do not. At the default font
        // and display size nothing scrolls at all; as those grow the body is
        // what gives, so `Next`, `Skip` and the dots stay reachable and no
        // `Allow` is ever clipped off the bottom. Clipping a grant is the worse
        // of the two failures the no-scroll rule was trying to avoid.
        Column(
            modifier = Modifier
                .weight(1f)
                // Keyed on the card, so each one opens at its top. A single
                // state shared across the run meant scrolling one card and
                // tapping `Next` landed the next one partway down, with its
                // title scrolled off — and at the font sizes where anything
                // scrolls at all, that is the reader who can least afford to
                // start in the middle (Codex, PR #204).
                .verticalScroll(remember(card) { ScrollState(0) }),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Above the card, on whichever card is showing: the same placement
            // and reasoning as the other two landing screens'.
            if (crashPending) {
                CrashBanner(
                    onShare = onShareDebugLog,
                    onDismiss = onDismissCrash,
                    shareFailed = shareFailed,
                    dismissFailed = dismissFailed,
                    sharing = sharing,
                )
            }
            when (card) {
                WelcomeCard.WHAT -> WhatCard(tracksDeparture)
                WelcomeCard.ENDS -> EndsCard(
                    tracksDeparture = tracksDeparture,
                    notifications = notifications,
                    notificationsReachTheUser = notificationsReachTheUser,
                    location = location,
                    calendar = calendar,
                    settingsFailure = settingsFailure,
                    onNotificationsRow = onNotificationsRow,
                    onLocationRow = onLocationRow,
                    onCalendarRow = onCalendarRow,
                )
                WelcomeCard.TILE -> TileCard(
                    tileAdded = tileAdded,
                    settingsFailure = settingsFailure,
                    onAddTile = onAddTile,
                )
                WelcomeCard.RULE -> RuleCard(
                    access = access,
                    ruleState = ruleState,
                    snoozeRinger = snoozeRinger,
                    snoozeRingerSaveFailed = snoozeRingerSaveFailed,
                    settingsFailure = settingsFailure,
                    onAccessRow = onAccessRow,
                    onRuleRow = onRuleRow,
                    onSnoozeRinger = onSnoozeRinger,
                )
                WelcomeCard.TELEMETRY -> TelemetryCard(onAnswerTelemetry)
            }
        }
        Spacer(Modifier.size(16.dp))
        WelcomeDots(position = position, count = cards.size)
        Spacer(Modifier.size(16.dp))
        // Opposite ends rather than clustered (maintainer, 2026-09-05): leaving
        // and continuing are different enough that they should not be two taps
        // a thumb can confuse, and the forward action is the trailing one.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `Skip` stays on every card including the first. It is the fail-open
            // rule again: a flow whose exit appears only once you are inside it
            // is the trap D7 forbids.
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.welcome_skip))
            }
            Button(onClick = if (last) onSkip else onNext) {
                Text(
                    stringResource(
                        // The last card advances to the same place `Skip` does,
                        // so it says what it does rather than promising a card
                        // that isn't there.
                        if (last) R.string.welcome_done else R.string.welcome_next,
                    ),
                )
            }
        }
    }
}

/** Card 1: the product in one line, and the promise the rest of the app keeps. */
@Composable
private fun WhatCard(tracksDeparture: Boolean) {
    CardTitle(stringResource(R.string.welcome_what_title))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SnoozemoMark(size = 96.dp)
    }
    CardBody(
        stringResource(
            if (tracksDeparture) R.string.welcome_what_body else R.string.welcome_what_body_timer_only,
        ),
    )
    CardBody(stringResource(R.string.welcome_what_promise))
}

/**
 * Card 2: how a snooze ends, read off a render of the ongoing notification.
 *
 * The render is the picture because it is the one surface that shows every way
 * a snooze ends at once (§4.3). The two body lines are the card's own division
 * and it is load-bearing: the first is what happens with nobody touching the
 * phone, the second is the taps. The calendar is never a trigger — it only
 * seeds an `Until <time>` action the user still has to press — so writing them
 * as one list would promise an automatic ending the app never delivers.
 */
@Composable
private fun EndsCard(
    tracksDeparture: Boolean,
    notifications: NotificationPermission?,
    notificationsReachTheUser: Boolean,
    location: LocationPermission?,
    calendar: CalendarPermission?,
    settingsFailure: SetupRowId?,
    onNotificationsRow: () -> Unit,
    onLocationRow: () -> Unit,
    onCalendarRow: () -> Unit,
) {
    CardTitle(stringResource(R.string.welcome_ends_title))
    NotificationRender(tracksDeparture)
    CardBody(
        stringResource(
            if (tracksDeparture) R.string.welcome_ends_body else R.string.welcome_ends_body_timer_only,
        ),
    )
    CardBody(stringResource(R.string.welcome_ends_taps))
    // The location row is absent on a build that cannot track departure, as it
    // is on that flavor's PermissionsScreen: a grant that buys the user nothing
    // must not be invited.
    if (tracksDeparture) {
        PermissionRows.Location(
            location = location,
            settingsFailure = settingsFailure,
            onAction = onLocationRow,
        )
    }
    PermissionRows.Calendar(
        calendar = calendar,
        settingsFailure = settingsFailure,
        onAction = onCalendarRow,
    )
    PermissionRows.Notifications(
        notifications = notifications,
        reachTheUser = notificationsReachTheUser,
        settingsFailure = settingsFailure,
        onAction = onNotificationsRow,
    )
}

/** Card 3: the tile, which is the arm affordance and the one locked-phone path. */
@Composable
private fun TileCard(
    tileAdded: Boolean?,
    settingsFailure: SetupRowId?,
    onAddTile: () -> Unit,
) {
    CardTitle(stringResource(R.string.welcome_tile_title))
    CardBody(stringResource(R.string.welcome_tile_body))
    CardBody(stringResource(R.string.welcome_tile_locked))
    PermissionRows.Tile(
        tileAdded = tileAdded,
        settingsFailure = settingsFailure,
        onAction = onAddTile,
    )
}

/**
 * Card 4: one rule, and the ringer ceiling.
 *
 * Do Not Disturb access comes last of the grants because it is the one without
 * which nothing here can snooze at all — asked after the user has seen what it
 * is for. Filters is named rather than offered: it deep-links to the system's
 * editor for the rule's policy, and the rule does not exist until access is
 * granted, so a button here would open to nothing.
 */
@Composable
private fun RuleCard(
    access: PolicyAccess?,
    ruleState: ZenRuleState?,
    snoozeRinger: SnoozeRinger?,
    snoozeRingerSaveFailed: Boolean,
    settingsFailure: SetupRowId?,
    onAccessRow: () -> Unit,
    onRuleRow: () -> Unit,
    onSnoozeRinger: (SnoozeRinger) -> Unit,
) {
    CardTitle(stringResource(R.string.welcome_rule_title))
    CardBody(stringResource(R.string.welcome_rule_body))
    snoozeRinger?.let {
        SnoozeRingerRow(chosen = it, saveFailed = snoozeRingerSaveFailed, onChange = onSnoozeRinger)
    }
    PermissionRows.Access(
        access = access,
        ruleState = ruleState,
        settingsFailure = settingsFailure,
        onAction = onAccessRow,
        onRuleRow = onRuleRow,
    )
}

/**
 * Card 5: the one question on the cards about data leaving the phone.
 *
 * Last on purpose — after the run of `Allow` buttons rather than among them, so
 * it does not read as one more of the same, and by then the user has seen what
 * "help fix bugs" refers to. Nothing is collected until the answer is yes;
 * passing with `Next` is not a "no", so `MainScreen`'s invite still appears.
 */
@Composable
private fun TelemetryCard(onAnswer: (Boolean) -> Unit) {
    CardTitle(stringResource(R.string.telemetry_invite_title))
    CardBody(stringResource(R.string.telemetry_invite_body))
    // The same shape as MainScreen's invite card, which is the same question:
    // opposite ends, affirmative trailing and filled.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = { onAnswer(false) }) {
            Text(stringResource(R.string.telemetry_invite_decline))
        }
        Button(onClick = { onAnswer(true) }) {
            Text(stringResource(R.string.telemetry_invite_accept))
        }
    }
}

/**
 * An inert picture of the ongoing notification (§4.3), in its fullest shape.
 *
 * Drawn rather than posted, and drawn *flat* — no ripple, no clickable — so
 * nobody taps `End now` on a snooze that is not running. The whole thing is one
 * node to a screen reader for the same reason: read as a list of controls it
 * would announce three buttons that do nothing.
 *
 * Its body carries the string this build actually posts, never invented copy: a
 * render teaching a screen nobody will see is worse than no render.
 */
@Composable
private fun NotificationRender(tracksDeparture: Boolean) {
    val description = stringResource(R.string.welcome_notification_render)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SnoozemoMark(size = 16.dp)
                    Text(
                        text = stringResource(R.string.ongoing_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                // The countdown lives in the chronometer beside the title on
                // the real notification, on both flavors — so it is here on
                // both, even where the body reads `Timer only`.
                Text(
                    text = SAMPLE_REMAINING,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(
                    if (tracksDeparture) {
                        R.string.ongoing_ends_when_you_leave
                    } else {
                        R.string.ongoing_timer_only
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RenderedAction(stringResource(R.string.action_end_now))
                RenderedAction(stringResource(R.string.action_extend))
                RenderedAction(SAMPLE_UNTIL)
            }
        }
    }
}

/** One of the render's notification actions: styled like a button, inert. */
@Composable
private fun RenderedAction(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** The five progress dots. */
@Composable
private fun WelcomeDots(position: Int, count: Int) {
    val description = stringResource(R.string.welcome_progress, position + 1, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            Surface(
                shape = CircleShape,
                color = if (index == position) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(8.dp),
            ) {}
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun CardBody(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

/**
 * A plainly-fictional countdown and meeting time for the render.
 *
 * Fixed rather than live: the picture is of a snooze that is not running, so a
 * ticking clock in it would be a lie that moves. Fictional rather than derived
 * from anything on the device — a render seeded from the user's own next
 * meeting would put their calendar in a screenshot test's baseline.
 */
private const val SAMPLE_REMAINING = "3:40:12"
private const val SAMPLE_UNTIL = "Until 17:00"
