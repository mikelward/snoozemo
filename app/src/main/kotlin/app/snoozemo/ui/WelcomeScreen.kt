package app.snoozemo.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
 * An enum rather than an index so the filtering below has something to
 * name: [TELEMETRY] is absent on a build that ships no crash-reporting SDK, and
 * a bare index would be fragile if the card set ever changed.
 *
 * The order here is the order the flow shows and the dots count (maintainer,
 * 2026-09-15): what the app is and how to add its tile, the rule it silences
 * with, ending it by hand, ending it by itself, then the one consent question.
 */
enum class WelcomeCard {
    /** What the app is, and the tile that arms it. */
    WHAT,

    /** The one Do Not Disturb rule, its filters and the ringer ceiling. */
    RULE,

    /** How a snooze ends by hand, on a render of the ongoing notification. */
    ENDS_MANUAL,

    /** How a snooze ends by itself, on a render of the end-time chooser. */
    ENDS_AUTO,

    /** The crash-report and analytics consent (§12). */
    TELEMETRY,
}

/**
 * Where a card name is stored, and what an older one means.
 *
 * **Two things persist the card, and both outlive an app update**: the
 * `WelcomeStore` breadcrumb, and `MainActivity`'s saved instance state, which
 * the system holds outside the process. Both migrate through this one object so
 * a card-set change cannot fix one path and miss the other (Codex, PR #226).
 *
 * **The key is the version.** [KEY] is new in each build that changes the card
 * set, so a name found under an older key was written by an older set by
 * definition. The 2026-09-15 overhaul reshaped the cards entirely — cards were
 * merged, split and reordered — so no old position maps cleanly onto a new one;
 * a legacy name therefore resumes the *new* flow from its first card rather than
 * guessing a correspondence that does not exist. Writing only ever touches
 * [KEY], so the rewind is spent the first time the flow moves.
 *
 * All keys but [KEY] can go once no install can still hold a pre-overhaul name.
 */
object WelcomeCardMemory {

    /** What this build writes. */
    const val KEY = "welcomeCard3"

    /** What the 2026-09-08 reorder wrote. Read, never written. */
    const val LEGACY_KEY = "welcomeCard2"

    /** What builds before the 2026-09-08 reorder wrote. Read, never written. */
    const val OLDEST_KEY = "welcomeCard"

    /**
     * The stored name, preferring [KEY] and restarting a legacy one at card 1.
     *
     * A flow paused by a pre-overhaul build was paused on a card that no longer
     * exists in the same shape, so resuming it in place would land the user on
     * the wrong step — past a grant they have not seen, or on a card whose name
     * this build cannot even place. Restarting the new flow is the honest answer
     * and costs at most the cards they had already read once.
     */
    fun resolve(current: String?, legacy: String?): String? =
        current ?: legacy?.let { WelcomeCard.WHAT.name }
}

/**
 * The cards this build actually shows, in order.
 *
 * [TELEMETRY][WelcomeCard.TELEMETRY] is dropped where nothing collects — a
 * build with no Firebase config ships neither SDK (§12), and with the debug-log
 * sentence gone (maintainer, 2026-09-05) there is nothing else on that card, so it would be a
 * blank screen and an extra dot. The list is what the dots count, so dropping it
 * here is what keeps them honest.
 */
fun welcomeCards(collectsTelemetry: Boolean): List<WelcomeCard> =
    WelcomeCard.entries.filter { collectsTelemetry || it != WelcomeCard.TELEMETRY }

/**
 * The card a remembered name resolves to, or null for no usable memory
 * (Codex, PR #220).
 *
 * Resolved against **the cards this build shows**, not against every enum
 * entry: a build with no crash reporter drops the
 * telemetry card, and a breadcrumb naming it came back as a card the flow does
 * not contain — `cards.indexOf` returned -1, so `Next` did nothing and the dots
 * read card 1 while a fifth card was on screen. A name this build cannot place
 * is no different from one a later build removed, and both mean the same thing:
 * start the flow over.
 *
 * One function for both the gate and the seed, so a name that cannot be shown
 * can never be the thing that opens the flow either.
 *
 * The name arrives already migrated for the card set it was written under —
 * `WelcomeStore.lastCard` does that, since which set wrote it is a question
 * about storage rather than about cards.
 */
fun rememberedWelcomeCard(name: String?, cards: List<WelcomeCard>): WelcomeCard? =
    name?.let { remembered -> cards.firstOrNull { it.name == remembered } }

fun shouldOpenWelcome(
    seen: Boolean,
    freshInstall: () -> Boolean,
    /**
     * Whether a run of the flow was left part-way and can be picked up
     * (Codex, PR #220) — the card `WelcomeStore` remembers, which is forgotten
     * the moment the flow is left.
     *
     * It outranks both of the others because a **replay** is neither: `seen` is
     * true by then and the install is not fresh, so a blocked tile tap arriving
     * after a replay's process had gone opened the recap instead of the cards
     * the user was part-way through — the resume this change promises, not kept
     * in the one case where the flow is entered deliberately.
     */
    inProgress: Boolean = false,
): Boolean = inProgress || (!seen && freshInstall())

/**
 * Whether leaving the flow should land on the permissions recap rather than the
 * main screen (`SPEC.md` §4.2) — true when any capability this flavor offers is
 * still ungranted.
 *
 * **Every offered row, not just Do Not Disturb access.** Access alone was the
 * wrong test: a user who allowed it and skipped the rest reached the
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
 * left without finishing it is a trap, not onboarding. `Skip`, the last card's
 * `Done` and back off card 1 all land in the same place, so no route through
 * this misses a missing permission and none gets stuck.
 *
 * **The card's title is the screen's title** (maintainer, 2026-09-06), drawn by
 * the same [SnoozemoTitleRow] every other screen uses, with `Skip` as its
 * trailing action. Along the bottom: `Back`, the progress dots, `Next` — the two
 * controls that step through the flow either side of the thing that says where
 * in it you are.
 *
 * **`Skip` is absent on card 1**, the one place the row's action slot is empty:
 * offering to leave beside the one line that says what the app is invites
 * skipping before there is anything to skip. D7 is untouched — back still exits
 * card 1, so the way out is there, just not advertised before that line has
 * been read.
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
     * Whether this build can end a snooze because the user left. Card 4
     * promises departure, and on a build that cannot deliver it that promise
     * sets up exactly the silence-until-the-cap the app exists to prevent
     * (§3) — so the seam is at the call site, as it is for [PermissionsScreen].
     */
    tracksDeparture: Boolean = true,
    tileAdded: Boolean? = null,
    snoozeRinger: SnoozeRinger? = null,
    snoozeRingerSaveFailed: Boolean = false,
    /**
     * The verified state of Snoozemo's own rule, or null while unread. Card 2's
     * access row needs it for the same reason `PermissionsScreen`'s does: with
     * access granted and this null the row reads as unread and renders nothing,
     * which would hide both a known failure and, for a disabled rule, the
     * repair (Codex, PR #204).
     */
    ruleState: ZenRuleState? = null,
    /**
     * The rule's id, or null while there is nothing to edit — no access, or
     * access granted and the rule not created yet. Card 2 offers Filters only
     * when it is non-null, exactly as `SettingsScreen` does.
     */
    filtersRuleId: String? = null,
    settingsFailure: SetupRowId? = null,
    /**
     * Whether a crashed run is pinned (`SPEC.md` §4.6). The flow is a cold-start
     * landing screen now, so it owes the banner the other two landing screens
     * carry — a crash from before that same cold start would otherwise stay
     * silent until the user finished onboarding (Codex, PR #204).
     */
    crashPending: Boolean = false,
    /**
     * Whether a tile tap arrived here during the flow (maintainer, 2026-09-07,
     * broadened 2026-09-15). A tap before the flow is finished resumes it rather
     * than snoozing (§4.2), so the flow looks unchanged and saying nothing reads
     * as the tile being broken (principle 2).
     */
    tapBlocked: Boolean = false,
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
    /** Previous card, or out of the flow from the first — the back gesture's twin. */
    onBack: () -> Unit = onSkip,
    modifier: Modifier = Modifier,
) {
    val position = cards.indexOf(card).takeIf { it >= 0 } ?: 0
    val first = position == 0
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
        // what gives, so `Back`, `Next` and the dots stay reachable and no
        // `Allow` is ever clipped off the bottom. Clipping a grant is the worse
        // of the two failures the no-scroll rule was trying to avoid.
        //
        // The title row is *inside* the scroll, as it is on every other screen
        // (`MainScreen`'s note has the reasoning). It was pinned above the body
        // first, and that could not hold: a wrapped heading at the largest font
        // on a short window, plus the pinned row below, left the weighted body
        // nothing to measure into — text and `Allow` buttons that were not just
        // clipped but absent, with no viewport to scroll them into (Codex,
        // PR #209). Letting the title give with the body is what deletes that
        // case; the exit is never lost with it, since `Back` stays pinned and
        // leaves the flow from card 1, and `Skip` is one scroll up on the rest.
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
            // The card's title is the screen's title, in the same row every
            // other screen puts one (maintainer, 2026-09-06) — so the mark sits
            // where a user already finds it, and the title reads as a page
            // heading rather than the first line of the body.
            //
            // `Skip` is that row's trailing action, which is where a flow's
            // exit is looked for.
            SnoozemoTitleRow(
                title = stringResource(cardTitle(card)),
                actions = {
                    // Not on card 1 (maintainer, 2026-09-06): offering to
                    // leave beside the one line that says what the app is
                    // invites skipping before there is anything to skip. D7 is
                    // untouched — back still exits card 1, so the way out is
                    // there, just not advertised before that line has been
                    // read.
                    if (!first) {
                        TextButton(onClick = onSkip) {
                            Text(stringResource(R.string.welcome_skip))
                        }
                    }
                },
            )
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
            // Above the card and below the crash banner, so it sits with the
            // other things that are true of the whole flow rather than of one
            // card. It names no permission: the card the user is on offers the
            // grant for the thing it introduces, and the recap on the way out
            // carries whatever is still missing.
            if (tapBlocked) {
                Text(
                    text = stringResource(R.string.welcome_tile_tap_blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            when (card) {
                WelcomeCard.WHAT -> WhatCard(
                    tileAdded = tileAdded,
                    settingsFailure = settingsFailure,
                    onAddTile = onAddTile,
                )
                WelcomeCard.RULE -> RuleCard(
                    access = access,
                    ruleState = ruleState,
                    filtersRuleId = filtersRuleId,
                    snoozeRinger = snoozeRinger,
                    snoozeRingerSaveFailed = snoozeRingerSaveFailed,
                    settingsFailure = settingsFailure,
                    onAccessRow = onAccessRow,
                    onRuleRow = onRuleRow,
                    onSnoozeRinger = onSnoozeRinger,
                )
                WelcomeCard.ENDS_MANUAL -> EndsManualCard(
                    tracksDeparture = tracksDeparture,
                    notifications = notifications,
                    notificationsReachTheUser = notificationsReachTheUser,
                    settingsFailure = settingsFailure,
                    onNotificationsRow = onNotificationsRow,
                )
                WelcomeCard.ENDS_AUTO -> EndsAutoCard(
                    tracksDeparture = tracksDeparture,
                    location = location,
                    calendar = calendar,
                    settingsFailure = settingsFailure,
                    onLocationRow = onLocationRow,
                    onCalendarRow = onCalendarRow,
                )
                WelcomeCard.TELEMETRY -> TelemetryCard(onAnswerTelemetry)
            }
        }
        Spacer(Modifier.size(16.dp))
        // Back, the dots, Next (maintainer, 2026-09-06): the row is the flow's
        // own controls plus the place in it they move through, so where the
        // user is reads between the two things that change it. `Skip` left this
        // row for the top-right corner — it goes somewhere else entirely, and
        // sitting between back and forward made it a third tap in the band the
        // thumb rests on.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `Back` on card 1 leaves the flow, exactly as the system gesture
            // does, so the two never disagree.
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.welcome_back))
            }
            // Weighted rather than centered in the row: the two buttons are
            // different widths and grow at different rates with type size, so
            // true centering would need the dots to overlap them at the sizes
            // where the row is tightest. Centered in what the buttons leave is
            // the arrangement that cannot collide.
            WelcomeDots(
                position = position,
                count = cards.size,
                modifier = Modifier.weight(1f),
            )
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

/**
 * Card 1: the product in two lines under a mock Quick Settings panel with
 * Snoozemo's tile ringed among the others, and the button that adds it.
 *
 * Build-neutral copy: neither line names departure, so both hold on a
 * duration-only build too, where "automatically" is the cap rather than a walk
 * away. The shade rather than the app icon: what the app *is* is a tile you tap,
 * and a user who has never seen it has no idea what a Quick Settings tile is
 * called or where it lives; the panel is drawn rather than screenshotted, so it
 * follows the app's theme and text size, and the ring marks Snoozemo's tile
 * rather than a different tile style the shade will never show.
 *
 * **The tile row leads the flow now** (maintainer, 2026-09-15): the tile is the
 * whole product, so the first card both shows it and offers to add it, through
 * the same [PermissionRows.Tile] the banner and `SettingsScreen` draw. Adding it
 * before Do Not Disturb access is granted (card 2) is safe here in a way it was
 * not before: a tile tapped during the unfinished flow resumes the flow rather
 * than failing to snooze (§4.2), so the "tile without access" tap the old order
 * guarded against cannot happen while onboarding is still open.
 */
@Composable
private fun WhatCard(
    tileAdded: Boolean?,
    settingsFailure: SetupRowId?,
    onAddTile: () -> Unit,
) {
    QuickSettingsMock()
    CardBody(stringResource(R.string.welcome_what_body))
    CardBody(stringResource(R.string.welcome_what_promise))
    // The Add-tile row leads card 1, so it is on the very first frame — but the
    // tile-presence store is read only *after* the first frame, like every
    // other permission state here, never off disk in front of it (`SPEC.md`
    // §6.9). So while the state is unknown the row is laid out as an invisible
    // skeleton that reserves its height, and it fades in when the read lands
    // (maintainer, 2026-09-15): the affordance pops in without the card
    // reflowing to make room, and no stale `Add tile` is shown for a tile that
    // turns out to be already there. `null` renders the not-added row purely
    // for its height, at alpha 0 and cleared from the semantics tree so a
    // screen reader is not told of a row it cannot see or reach; once known,
    // the real row is drawn (and drops out entirely when the tile is present,
    // exactly as before).
    when (tileAdded) {
        null -> Box(modifier = Modifier.alpha(0f).clearAndSetSemantics {}) {
            PermissionRows.Tile(
                tileAdded = false,
                settingsFailure = null,
                onAction = {},
                hideWhenSatisfied = true,
            )
        }
        else -> PermissionRows.Tile(
            tileAdded = tileAdded,
            settingsFailure = settingsFailure,
            onAction = onAddTile,
            hideWhenSatisfied = true,
        )
    }
}

/**
 * Card 2: one rule, its filters, and the ringer ceiling.
 *
 * Titled *One rule, yours* — Snoozemo touches nothing else of the user's. The
 * ringer choice sits above the grants (maintainer, 2026-09-15), a live control
 * the user can set at once. Do Not Disturb access is the one grant without which
 * nothing here can snooze at all, so it is asked once the user has seen the tile
 * that will arm it (card 1) and before the two cards that describe how a snooze
 * ends.
 *
 * Filters is offered rather than only named, through the same row
 * `SettingsScreen` draws. The row is absent until there is a rule to edit —
 * access granted and the rule created — and appears in place the moment there
 * is, exactly as [PermissionRows.Filters]'s null check arranges. So the card
 * names the rule as the user's and hands them the way to edit it in the same
 * breath.
 */
@Composable
private fun RuleCard(
    access: PolicyAccess?,
    ruleState: ZenRuleState?,
    filtersRuleId: String?,
    snoozeRinger: SnoozeRinger?,
    snoozeRingerSaveFailed: Boolean,
    settingsFailure: SetupRowId?,
    onAccessRow: () -> Unit,
    onRuleRow: () -> Unit,
    onSnoozeRinger: (SnoozeRinger) -> Unit,
) {
    CardBody(stringResource(R.string.welcome_rule_body))
    // The ringer ceiling (§5.9) above the grants (maintainer, 2026-09-15) — a
    // live control the user can set straight away, ahead of the Do Not Disturb
    // access it will apply under; the same setting `SettingsScreen`'s
    // Ring/vibrate row edits.
    snoozeRinger?.let {
        SnoozeRingerRow(chosen = it, saveFailed = snoozeRingerSaveFailed, onChange = onSnoozeRinger)
    }
    PermissionRows.Access(
        access = access,
        ruleState = ruleState,
        settingsFailure = settingsFailure,
        onAction = onAccessRow,
        onRuleRow = onRuleRow,
        hideWhenSatisfied = true,
        // This card is the only place both rows appear, so it is the only one
        // that has to say which of them reports a refused filters launch.
        filtersRowPresent = filtersRuleId != null,
    )
    // Below the access row, because it only exists once that grant has landed
    // and the rule has been created — the order the user meets them in.
    PermissionRows.Filters(
        filtersRuleId = filtersRuleId,
        settingsFailure = settingsFailure,
        onAction = onRuleRow,
    )
}

/**
 * Card 3: how a snooze ends when the user ends it.
 *
 * The render is the picture because the notification is where every manual exit
 * lives at once (§4.3): `End now` and `+30 min` are its buttons, and its body
 * opens the sheet with more options. The tile is the other manual exit and is
 * named as a quieter note, since it does what the buttons already do. Split from
 * card 4's automatic endings (maintainer, 2026-09-15) so each card carries one
 * idea and the notification grant sits with the card that depicts the
 * notification.
 */
@Composable
private fun EndsManualCard(
    tracksDeparture: Boolean,
    notifications: NotificationPermission?,
    notificationsReachTheUser: Boolean,
    settingsFailure: SetupRowId?,
    onNotificationsRow: () -> Unit,
) {
    NotificationRender(tracksDeparture)
    // Three short lines, one per way to end a snooze by hand: the notification's
    // buttons, its body, and the tile. Same font size so they read as one set of
    // instructions; the tile line stays a shade quieter in color because it is a
    // second way to do what the two above already cover (SPEC.md §4.2, D6).
    CardBody(stringResource(R.string.welcome_ends_manual_body))
    CardBody(stringResource(R.string.welcome_ends_manual_options))
    Text(
        text = stringResource(R.string.welcome_ends_manual_tile_note),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PermissionRows.Notifications(
        notifications = notifications,
        reachTheUser = notificationsReachTheUser,
        settingsFailure = settingsFailure,
        onAction = onNotificationsRow,
        hideWhenSatisfied = true,
    )
}

/**
 * Card 4: how a snooze ends by itself.
 *
 * The render is an illustration of the end-time chooser (§4.4) — a fixed
 * picture of its rows: a clock time with its steppers, the meeting, and the
 * move and leave exits. The body lists them in words, and the grants below are
 * for the two the app cannot offer without permission: the calendar seeds the
 * meeting end, and location the departures.
 *
 * The location row is absent on a build that cannot track departure, as it is on
 * the `PermissionsScreen`: a grant that buys the user nothing must not be
 * invited. The calendar is offered on every build. The render is a static
 * illustration, not the live chooser (see [EndConditionRender]).
 */
@Composable
private fun EndsAutoCard(
    tracksDeparture: Boolean,
    location: LocationPermission?,
    calendar: CalendarPermission?,
    settingsFailure: SetupRowId?,
    onLocationRow: () -> Unit,
    onCalendarRow: () -> Unit,
) {
    EndConditionRender(tracksDeparture)
    CardBody(
        stringResource(
            if (tracksDeparture) R.string.welcome_ends_body else R.string.welcome_ends_body_timer_only,
        ),
    )
    // Calendar first, then location: the order the render reads top to bottom
    // once the clock row is passed — the meeting, then the departures.
    PermissionRows.Calendar(
        calendar = calendar,
        settingsFailure = settingsFailure,
        onAction = onCalendarRow,
        hideWhenSatisfied = true,
    )
    if (tracksDeparture) {
        PermissionRows.Location(
            location = location,
            settingsFailure = settingsFailure,
            onAction = onLocationRow,
            hideWhenSatisfied = true,
        )
    }
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
                // the real notification — so it is here too, even where the
                // body reads `Timer only`.
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

/**
 * An inert picture of the end-time chooser (§4.4), in its fullest shape.
 *
 * Drawn flat and read as one node, exactly like [NotificationRender] and for the
 * same reason: the real chooser's rows commit an end condition, and a picture of
 * one must never take a tap. The clock row shows its `−`/`+` steppers, the
 * meeting row its calendar mark, and the two departures follow — dropped
 * together on a build that cannot track departure, where they would name endings
 * nothing watches for.
 *
 * Its labels are fixed and fictional: a real meeting time has no place in a
 * screenshot test's baseline (`AGENTS.md`, *Privacy*), and the clock time is a
 * plainly-illustrative one rather than anything read off the device.
 */
@Composable
private fun EndConditionRender(tracksDeparture: Boolean) {
    val clock = stringResource(R.string.action_end_at, SAMPLE_CHOOSER_TIME)
    val meeting = stringResource(R.string.welcome_end_until_meeting)
    // A fixed, illustrative list of the chooser's rows — a picture, like the
    // Quick Settings mock on card 1, not the live chooser (maintainer,
    // 2026-09-15). So it depicts the automatic endings this *build* offers,
    // gated only on the compile-time `tracksDeparture` flavor flag: whether a
    // given phone has a significant-motion sensor is the live chooser's check,
    // not the tutorial's, and the illustration carries no per-device or
    // asynchronous state that could shift the card after its first frame.
    val move = stringResource(R.string.main_until_i_move).takeIf { tracksDeparture }
    val leave = stringResource(R.string.main_until_i_leave).takeIf { tracksDeparture }
    // One node with one description, but the description names the rows it shows
    // rather than a bare "a chooser" (Codex, PR #291): the render is inert, so
    // without this a screen reader learns nothing of the endings it depicts, and
    // the card body names leaving, the meeting and a chosen time but never the
    // move exit. Built from the same labels the rows carry, in the order they
    // appear, so the two channels cannot drift.
    val rows = listOfNotNull(clock, meeting, move, leave)
    val description = stringResource(R.string.welcome_end_chooser_render) +
        ": " + rows.joinToString(", ")
    Surface(
        shape = RoundedCornerShape28,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RenderedChoice(label = clock, modifier = Modifier.weight(1f))
                RenderedStepper("−")
                RenderedStepper("+")
            }
            RenderedChoice(
                label = meeting,
                trailingIcon = R.drawable.ic_calendar,
                modifier = Modifier.fillMaxWidth(),
            )
            // The two automatic exits the departure builds add, shown together
            // on the same compile-time flag — a fixed list, so nothing here
            // shifts after the first frame.
            move?.let { RenderedChoice(label = it, modifier = Modifier.fillMaxWidth()) }
            leave?.let { RenderedChoice(label = it, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

/** One inert row of the chooser render: styled like a choice card, not clickable. */
@Composable
private fun RenderedChoice(
    label: String,
    modifier: Modifier = Modifier,
    trailingIcon: Int? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            trailingIcon?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** One inert `−`/`+` stepper of the chooser render, drawn as an outline. */
@Composable
private fun RenderedStepper(symbol: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
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

/** The progress dots. */
@Composable
private fun WelcomeDots(position: Int, count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.welcome_progress, position + 1, count)
    Row(
        modifier = modifier.semantics { contentDescription = description },
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

/**
 * The card's title, which the title row draws rather than the card itself — one
 * per card, so the row always has one and no card can forget to supply it.
 */
private fun cardTitle(card: WelcomeCard): Int = when (card) {
    WelcomeCard.WHAT -> R.string.welcome_what_title
    WelcomeCard.RULE -> R.string.welcome_rule_title
    WelcomeCard.ENDS_MANUAL -> R.string.welcome_ends_manual_title
    WelcomeCard.ENDS_AUTO -> R.string.welcome_ends_title
    WelcomeCard.TELEMETRY -> R.string.telemetry_invite_title
}

@Composable
private fun CardBody(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

/** The chooser render's container corner, matching [QuickSettingsMock]'s panel. */
private val RoundedCornerShape28 = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)

/**
 * A plainly-fictional countdown, meeting time and chosen time for the renders.
 *
 * Fixed rather than live: the pictures are of a snooze that is not running, so a
 * ticking clock in them would be a lie that moves. Fictional rather than derived
 * from anything on the device — a render seeded from the user's own next
 * meeting would put their calendar in a screenshot test's baseline.
 */
private const val SAMPLE_REMAINING = "3:40:12"
private const val SAMPLE_UNTIL = "Until 17:00"
private const val SAMPLE_CHOOSER_TIME = "12:00"
