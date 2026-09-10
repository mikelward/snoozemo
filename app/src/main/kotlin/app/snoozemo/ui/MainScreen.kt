package app.snoozemo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.snoozemo.PlayUpdateState
import app.snoozemo.distanceText
import app.snoozemo.distanceUnitFor
import app.snoozemo.R
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.DepartureObservation
import app.snoozemo.core.DistanceUnit
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.notificationsMissing
import app.snoozemo.core.TrackingMode
import app.snoozemo.degradationReasonRes
import app.snoozemo.tile.R as TileR
import java.time.Duration

/**
 * The home screen: the Arm/Release control the tile mirrors, plus whatever
 * stands between the user and a working snooze right now.
 *
 * Leaner than the old `DebugScreen` on purpose (`TODO.md` Phase 4, "Split the
 * permission-setup rows from the Arm/Release view") — the setup rows
 * themselves live on [PermissionsScreen], reached from the banner below or
 * from [SettingsScreen]. This screen states only what is *missing*, not how to
 * fix every capability in place.
 */
// `internal`, like `SettingsScreen` and `LicensesScreen`: nothing outside
// this module composes it, and taking `PlayUpdateState` — itself internal —
// means a public signature would not compile.
@Composable
internal fun MainScreen(
    access: PolicyAccess?,
    /**
     * The notification permission, or null until a reading lands. Paired with
     * [activeChannelEnabled] and judged by `notificationsMissing` — the tile
     * tap sends the user to the setup screen for a reason this screen has to be
     * able to state too, and it asks the *wider* of the two questions, since
     * this screen shows no prompt of its own (Codex, PR #216).
     */
    notifications: NotificationPermission? = null,
    /**
     * Whether the channel a running snooze reports on is switched on, null
     * until read. Deliberately the ongoing channel alone rather than the
     * three-channel aggregate: a silenced `snooze_ended` is not a reason to put
     * a red banner on this screen.
     */
    activeChannelEnabled: Boolean? = null,
    tileAdded: Boolean?,
    tileBannerDismissed: Boolean,
    /**
     * Whether to point at the help icon. True only just after the welcome flow
     * has been left and until the user dismisses it (`SPEC.md` §4.2).
     */
    showReplayHint: Boolean = false,
    snoozing: Boolean?,
    // Both null unless a snooze is actually running and its record has been
    // read — the same "unread is not zero" discipline every other field on
    // this screen follows. Passed as the raw values rather than the whole
    // `ActiveSnooze`, so a screenshot test can supply them without a store or
    // a clock reading behind them.
    trackingMode: TrackingMode?,
    remaining: Duration?,
    // Why the mode degraded, where there is a reason worth naming. Null on a
    // healthy snooze by construction, and also null for the causes that earn
    // no line of their own ([degradationReasonRes]).
    degradation: DegradationCause?,
    /**
     * The most recent departure reading, where one has arrived and is still
     * fresh ([DepartureObservation.isFresh]). Null until the first fix of a
     * snooze lands, and null again once a reading has gone stale — a distance
     * from ten minutes ago is worse than no distance at all.
     */
    departure: DepartureObservation? = null,
    lastOutcome: String?,
    /** Whether a crashed run is currently pinned (SPEC.md §4.6) — the crash banner's own state. */
    crashPending: Boolean,
    /** Whether the last debug-log share reached neither the clipboard nor the chooser. */
    shareFailed: Boolean,
    /** Whether the last Dismiss tap on the crash banner was refused by the file layer. */
    dismissFailed: Boolean,
    /** Whether a share is already running, disabling the banner's Share button. */
    sharing: Boolean = false,
    /**
     * What Play last said about a waiting update, dismissal already folded in.
     * `NotAvailable` on `direct`, where the checker is a no-op.
     */
    playUpdate: PlayUpdateState = PlayUpdateState.NotAvailable,
    /** Whether the last Restart tap on the update banner was refused. */
    playUpdateRestartFailed: Boolean = false,
    /**
     * Whether background location is missing *and* this flavor's tracking
     * needs it. False on `direct`, which declares no such permission, and
     * false while the reading is unknown — unread is not "missing".
     */
    backgroundLocationMissing: Boolean = false,
    /** Whether the user has dismissed the background-location banner for good. */
    backgroundLocationBannerDismissed: Boolean = true,
    /**
     * Whether the telemetry question is still unanswered *and* this build has
     * something to turn on. False on `direct`, which has no reporter, and
     * false until the store has been read — unasked is not "unanswered" as
     * far as this screen is concerned.
     */
    telemetryUnanswered: Boolean = false,
    // Only SetupRowId.TILE is ever relevant here — this banner has no other
    // capability to fail — but the type is shared with the other screens'
    // failure-routing rather than narrowed to a Boolean, so a caller reading
    // this signature can see at a glance it's the same failure state as
    // everywhere else, not a screen-local one.
    settingsFailure: SetupRowId? = null,
    /**
     * The end-condition choice for the snooze running right now, or null when
     * there is nothing to refine — no snooze, a cap already inside the floor,
     * or the record not read yet.
     *
     * The same choices the arm-time sheet offers, on a surface the user can
     * open at any point during a snooze rather than only in the seconds after
     * arming it (maintainer, 2026-09-08). Defaulted, so a screenshot test
     * pinning any other state need not state an opinion about this one.
     */
    endChoice: EndChoiceUiState? = null,
    /** Whether `Until I move` is offered among the end-condition rows (SPEC.md §4.4). */
    offersMotionEnd: Boolean = false,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * Replays the welcome flow from its first card (`SPEC.md` §4.2). Defaulted
     * so a screenshot test pinning something else need not state an opinion.
     */
    onOpenWelcome: () -> Unit = {},
    onAddTile: () -> Unit,
    onDismissTileBanner: () -> Unit,
    onDismissReplayHint: () -> Unit = {},
    onArm: () -> Unit,
    onRelease: () -> Unit,
    /** Commits [EndChoiceUiState.condition] as the snooze's new end. */
    onChooseEndTime: () -> Unit = {},
    /** Commits the meeting end at that index of [EndChoiceUiState.meetings]. */
    onChooseEndMeeting: (Int) -> Unit = {},
    /** Puts the cap back to its ceiling, so the snooze runs until departure again. */
    onChooseDeparture: () -> Unit = {},
    /** Commits "ends when you move" — an added exit; the cap stays. */
    onChooseMotionEnd: () -> Unit = {},
    onStepEndDown: () -> Unit = {},
    onStepEndUp: () -> Unit = {},
    onShareDebugLog: () -> Unit,
    onDismissCrash: () -> Unit,
    onStartPlayUpdate: () -> Unit = {},
    onCompletePlayUpdate: () -> Unit = {},
    onDismissPlayUpdate: () -> Unit = {},
    onAllowBackgroundLocation: () -> Unit = {},
    onDismissBackgroundLocationBanner: () -> Unit = {},
    onAnswerTelemetry: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // **The exit is pinned; everything else scrolls** (maintainer,
    // 2026-09-09). Two containers rather than one, because those are two
    // different promises: the refinements are content and may run past the
    // bottom of a short window, while manual exit is "always available, always
    // instant" (SPEC.md §7) and a position that depends on how many meetings
    // the calendar happened to contribute is not that.
    Column(
        modifier = modifier
            .fillMaxSize()
            // Outside both, so the whole screen — not just a resting scroll
            // position — stays clear of the status bar, the navigation bar and
            // any display cutout. Inside the scroll it would only pad the
            // content, leaving a row to slide under the status bar as soon as
            // the user scrolled; and the pinned row below would sit on the
            // navigation bar.
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                // Takes the height the pinned row leaves. A short window
                // therefore shrinks the *content*, which scrolls, rather than
                // the exit, which cannot.
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // Bottom is 12 rather than 16 because it is no longer an outer
                // margin: it is the gap to the pinned row, so it matches the
                // 12dp these rows already keep between each other rather than
                // adding a second margin on top of the footer's own.
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SnoozemoTitleRow(
                title = stringResource(R.string.app_name),
                // Settings rides the title row rather than a button at the foot of
                // the screen. Down there it sat *below* the exit, so a long screen
                // put it behind a scroll past everything else — and it competed for
                // width with the one control that has to be unmissable (SPEC.md
                // §7's "always available, always instant"). Up here it is where the
                // screen opens, and the arm/end button gets the width to itself.
                //
                // Still inside the scroll, deliberately: pinning the row would take
                // its height off the viewport in exactly the short-window and
                // large-font cases where `End snooze` is already tight, and the
                // exit outranks Settings (SPEC.md §4.2).
                actions = {
                    // Help before settings, in logical order rather than physical:
                    // the app supports RTL, where the row mirrors, so "left" would
                    // pin them against the direction the layout is meant to flip.
                    SnoozemoTitleAction(
                        icon = R.drawable.ic_help,
                        label = R.string.welcome_replay,
                        onClick = onOpenWelcome,
                    )
                    SnoozemoTitleAction(
                        icon = R.drawable.ic_settings_gear,
                        label = R.string.settings_title,
                        onClick = onOpenSettings,
                    )
                },
            )
            // First, above even the access banner: this is the screen the user
            // actually lands on, so a crashed run is surfaced where it will be
            // seen rather than tucked away on SettingsScreen (SPEC.md §4.6,
            // maintainer, 2026-08-23).
            if (crashPending) {
                CrashBanner(
                    onShare = onShareDebugLog,
                    onDismiss = onDismissCrash,
                    shareFailed = shareFailed,
                    dismissFailed = dismissFailed,
                    sharing = sharing,
                )
            }
            // The one required capability, stated as a problem rather than listed
            // as a row: nothing on this screen can arm without it, so it is a
            // banner, not a setup row waiting its turn beside the others on
            // PermissionsScreen. Null-guarded like every other reading here —
            // unread is not "missing".
            if (access != null && access != PolicyAccess.GRANTED) {
                RequiredPermissionBanner(onFix = onOpenPermissions)
            }
            // The second required capability, and it had no banner at all until
            // the tile tap started routing people here for it (Codex, PR #215):
            // `MainScreen` has stated missing Do Not Disturb access since it
            // existed, so a user sent to the setup screen by a dead tap and then
            // backing out to Main found nothing here saying why. Below the access
            // banner, because without access nothing arms at all — this only
            // silences the reports about it.
            //
            // Separate from the banner above rather than merged with it: two
            // required capabilities with two different remedies, and one banner
            // would have to say both things at once.
            //
            // The *wider* of the two questions, not the tile's (Codex, PR #216).
            // The tile skips an askable permission because the tap itself shows the
            // prompt; nothing here does, and Snooze arms immediately — so sharing
            // that predicate hid this banner in exactly the state a user reaches by
            // granting the permission and later revoking it in system settings, and
            // let the app arm with its ongoing card silently dropped.
            if (notificationsMissing(notifications, activeChannelEnabled)) {
                RequiredNotificationsBanner(onFix = onOpenPermissions)
            }
            // Above the buttons and louder than a row, because the screen leads
            // with the tile rather than offering a symmetrical choice (SPEC.md
            // §4.2): the tile is easier and is where people already go to silence
            // a phone. Dismissible for good, which only works because the
            // permanent tile row on SettingsScreen outlives it.
            if (tileAdded == false && !tileBannerDismissed) {
                TileBanner(
                    onAdd = onAddTile,
                    onDismiss = onDismissTileBanner,
                    failure = stringResource(R.string.failure_could_not_add_tile)
                        .takeIf { settingsFailure == SetupRowId.TILE },
                )
            }
            // Below both of the above, and above the update banner. A missing
            // tile or missing Do Not Disturb access stops the product working;
            // this only degrades it, so it must not push either of those down
            // the screen — but it outranks an available update, which costs the
            // user nothing to ignore.
            if (backgroundLocationMissing && !backgroundLocationBannerDismissed) {
                BackgroundLocationBanner(
                    onAllow = onAllowBackgroundLocation,
                    onDismiss = onDismissBackgroundLocationBanner,
                )
            }
            // Same banner `SettingsScreen` shows, for the same reason `CrashBanner`
            // is on every screen: which screen the user happens to land on is not
            // something this feature should have to reason about, and this is the
            // one they land on by default. Below the tile banner rather than above
            // it — a missing tile blocks the product's whole first impression,
            // where an update is worth acting on but nothing is broken without it.
            (playUpdate as? PlayUpdateState.Available)?.takeIf { it.shouldPrompt }?.let { update ->
                PlayUpdateBanner(
                    progress = update.progress,
                    restartFailed = playUpdateRestartFailed,
                    onUpdate = onStartPlayUpdate,
                    onRestart = onCompletePlayUpdate,
                    onDismiss = onDismissPlayUpdate,
                )
            }
            // Everything above either blocks the product (Do Not Disturb access,
            // the tile) or offers to repair something the user is missing; this
            // asks for a favor, so it yields to all of them.
            if (telemetryUnanswered) {
                TelemetryInviteCard(onAnswer = onAnswerTelemetry)
            }
            // Last of the banners, below even the one that asks a favor. It blocks
            // nothing, repairs nothing and asks nothing — it points at an icon
            // already on this screen — so anything with something at stake outranks
            // it. Written first and moved here (Codex, PR #206): above the tile it
            // pushed the one action that makes the product work down the scroll,
            // which is the opposite of `SPEC.md` §4.2's lead.
            if (showReplayHint) {
                ReplayHintBanner(onDismiss = onDismissReplayHint)
            }
            // One slot, always saying which of the two states the screen is in
            // once the record has been read — a running snooze reports what would
            // end it and when, and an idle one says so outright. Leaving idle
            // blank made "not snoozing" and "hasn't been read yet" render
            // identically, so the only thing distinguishing them was whether the
            // Snooze button happened to be enabled, which is principle 2's
            // failure: the safe state, stated nowhere.
            //
            // The record's own place name is left out on purpose: it is always
            // literally "Here" today (`ActiveSnooze.DEFAULT_PLACE_NAME`) since
            // saved/named places are unbuilt (`TODO.md`, "Saved places"), and the
            // ongoing notification doesn't show it either, so surfacing it here
            // first would only read as filler.
            when {
                snoozing == true && trackingMode != null && remaining != null ->
                    SnoozeStatus(trackingMode, remaining, degradation, departure)
                snoozing == false -> NotSnoozingStatus()
                // Nothing yet: either the record is still being read, or it read
                // as running but without the mode and cap the line reports. Same
                // "unread is not zero" discipline as the banners above — an idle
                // claim over a snooze this screen hasn't finished reading is the
                // one wrong thing this line could say, and it is exactly the
                // wrong direction to be wrong in.
                else -> Unit
            }
            // The last thing in the scrolling half, so the screen reads status,
            // then how to change it — and then, below the scroll and fixed
            // there, how to end it. These rows are what made pinning the exit
            // necessary: there can be four of them, and while the exit sat
            // underneath, its position moved with whatever the calendar
            // contributed.
            endChoice?.let { choice ->
                EndConditionRows(
                    condition = choice.condition,
                    formattedTime = choice.formattedTime,
                    meetingLabels = choice.meetings.map { it.label },
                    onChooseTime = onChooseEndTime,
                    onChooseMeeting = onChooseEndMeeting,
                    onChooseDeparture = onChooseDeparture,
                    onStepDown = onStepEndDown,
                    onStepUp = onStepEndUp,
                    committing = choice.committing,
                    failed = choice.failed,
                    tracksDeparture = choice.tracksDeparture,
                    // Inside the group, as one of the *whens* (maintainer,
                    // 2026-09-10). It sat outside as a switch that had to
                    // outlive the time offers; as a choice it has nothing to
                    // outlive them for.
                    offersMotionEnd = offersMotionEnd,
                    onChooseMotionEnd = onChooseMotionEnd,
                )
            }
            // Gated behind access being allowed, same as the old DebugScreen.
            //
            // **`Snooze` scrolls; the exit does not.** They are split across
            // the two containers rather than kept as one if/else because only
            // one of them carries §7's guarantee. Arming is something the user
            // came here to do and can hunt for; ending is something they may
            // need in a hurry with the phone already silent. Pinning `Snooze`
            // too would also push it to the foot of an otherwise empty idle
            // screen, which is a worse reach on a tall phone, not a better one.
            //
            // It needs a confident "nothing is running" to appear at all,
            // since offering to arm over a snooze the screen has not read yet
            // is how a user loses the deadline they were promised. It used to
            // render disabled in that state; showing the safety net instead
            // says more with one button.
            if (access == PolicyAccess.GRANTED && snoozing == false) {
                Button(
                    onClick = onArm,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.arm))
                }
            }
            // Above the exit rather than below it, which is where it used to
            // sit: the exit is the bottom-most thing on the screen now, and a
            // result line under a pinned row would either be pinned itself —
            // spending height that belongs to the content — or float free of
            // the button whose tap it reports.
            lastOutcome?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }

        // **Pinned, outside the scroll** (maintainer, 2026-09-09: "let's try
        // pinned outside the scroll, i.e. pinned at the bottom"). Refinements
        // are variable in number — a snooze can offer a departure row, a time
        // row and two meeting ends — and while the exit sat below them its
        // position moved with whatever the calendar contributed, on a short
        // window off the bottom of the screen entirely.
        //
        // **The split is on `snoozing == false`, not on `snoozing == true`**,
        // and the asymmetry is the whole design (maintainer, 2026-08-22): this
        // is the one guaranteed way to un-silence the phone, so it may only
        // disappear where the screen is *confident* nothing is running.
        // Unknown — the record not read yet — keeps it, because a stale or
        // unread belief must never be what stops someone turning their phone
        // back on (SPEC.md §7: manual exit is always available, always
        // instant, and `endSnooze` is idempotent, so offering it when nothing
        // is running costs nothing).
        //
        // The whole footer is absent rather than empty when it has nothing to
        // draw, so its padding does not reserve a strip of blank screen on the
        // idle and access-missing states.
        if (access == PolicyAccess.GRANTED && snoozing != false) {
            // **The same size as the choices above it, outlined rather than
            // filled** (maintainer, 2026-09-08). Same size because it is the
            // guaranteed way back to a ringing phone and must stay the easiest
            // thing on the screen to hit; outlined because it is the one row
            // that acts rather than schedules, and a stack of identical cards
            // ending in the irreversible one invites the wrong tap.
            EndChoiceRow(
                label = stringResource(R.string.action_end_now),
                onClick = onRelease,
                outlined = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

/**
 * The idle half of the status slot: no snooze is running, said plainly.
 *
 * The same [StatusBlock] the running state uses, so the two read as one thing
 * changing rather than content appearing and disappearing — and so a user who
 * glances at the screen gets the answer from the words rather than from which
 * button is grayed.
 *
 * No second line beneath it. The running state's carries the remaining time;
 * idle has no equivalent fact, and inventing one ("Tap Snooze to start") would
 * only restate the button directly below it.
 */
@Composable
private fun NotSnoozingStatus() {
    StatusBlock(headline = stringResource(R.string.main_not_snoozing))
}

/**
 * The status slot's shape: one headline, optionally over what qualifies it,
 * optionally over a detail.
 *
 * **Centered and at `headlineSmall`** (maintainer, 2026-09-05). This one line
 * is what the screen exists to say, and at body size across the full column it
 * read as another paragraph rather than the answer — so it steps up two type
 * roles, above every other line of content, and sits in the middle where a
 * glance lands. The title row's app name stays larger still (`headlineMedium`):
 * this is the loudest thing the screen *says*, not the loudest thing on it.
 *
 * **[oneRow] is preferred and measured, not assumed.** `Snoozing until you
 * leave` says the whole thing in a sentence and is the better reading where it
 * fits; where it does not, the split is a deliberate one — the title over its
 * condition — rather than whatever a wrap happens to produce ("Snoozing until
 * you" / "leave"). So the width is measured before composing rather than
 * discovered by an overflow callback afterwards, which would draw the clipped
 * one-line version for a frame first.
 *
 * Only the full tracking mode has a one-row form. "Snoozing, Wi-Fi only" does
 * not compose, so a degraded snooze always takes the two-row shape and states
 * its mode — and its reason — on the second line.
 *
 * **[readout] is last and quietest, and that ordering is the point.** Above it
 * sit things that are true of the snooze — what ends it, and by when at the
 * latest. It is true of one *reading*, replaced ninety seconds later, so it
 * takes the smallest role and the muted color rather than competing with the
 * guarantee above it.
 */
@Composable
private fun StatusBlock(
    headline: String,
    oneRow: String? = null,
    condition: String? = null,
    detail: String? = null,
    readout: String? = null,
) {
    val headlineStyle = MaterialTheme.typography.headlineSmall
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val oneRowFits = oneRow != null && !measurer.measure(
            text = oneRow,
            style = headlineStyle,
            softWrap = false,
            constraints = Constraints(maxWidth = constraints.maxWidth),
        ).hasVisualOverflow
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                // `oneRow` already contains the condition, so it replaces both
                // rows rather than sitting above one that repeats it.
                text = if (oneRowFits) oneRow else headline,
                style = headlineStyle,
                textAlign = TextAlign.Center,
            )
            if (!oneRowFits) {
                condition?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            // Last and quietest. The cap above it is the guarantee — the snooze
            // ends by then whatever every sensor does — where this is one
            // reading of one fix, true now and replaced in ninety seconds.
            readout?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * What the running snooze is doing right now: what would end it, and how
 * long until the cap does regardless.
 *
 * Not a live per-second countdown — [remaining] is recomputed once a minute
 * while `MainActivity` is visible ([MainActivity.now], Codex, PR #87), which
 * matches this line's own display granularity (`Xh Ym left`) rather than a
 * timer that would repaint faster than the text can change. The ongoing
 * notification already owns the true live countdown
 * (`SnoozeNotifications.showOngoing`'s chronometer); a per-second clock here
 * too would just be a second one to keep in sync with it for no benefit the
 * user doesn't already have.
 *
 * The mode line reuses the ongoing notification's own copy
 * (`ongoing_ends_when_you_leave` / `ongoing_wifi_only` / `ongoing_wifi_grace` /
 * `ongoing_timer_only`), the degraded reason joined to it reuses that
 * notification's join too (`ongoing_degraded_reason`, and the same
 * [degradationReasonRes] mapping behind it), and the remaining-time line
 * reuses the tile's (`tile_remaining_hours` / `tile_remaining_minutes`,
 * `:tile` module) — the same facts stated the same way everywhere they
 * already appear, rather than a third phrasing.
 *
 * Why a degraded snooze says why here and not only in the notification: the
 * notification can be swiped away, silenced by the user's own channel
 * settings, or simply not the surface they opened. `Timer only` on its own
 * reads as a choice someone made; `Timer only — no location` reads as the
 * thing that went wrong, which is the difference principle 2 is about.
 *
 * And why a tracked snooze shows a distance ([departure], `SPEC.md` §4.2):
 * `Ends when you leave` never says how far leaving *is*, so a user standing in
 * the garden watching a snooze survive has no way to tell a working app from a
 * broken one. Only under `FULL` — the other modes are measuring no distance,
 * and a leftover reading from before tracking degraded would explain a
 * threshold that is no longer what ends this snooze.
 */
@Composable
private fun SnoozeStatus(
    mode: TrackingMode,
    remaining: Duration,
    degradation: DegradationCause?,
    departure: DepartureObservation?,
) {
    val body = when (mode) {
        TrackingMode.FULL -> stringResource(R.string.ongoing_ends_when_you_leave)
        TrackingMode.WIFI_ONLY -> stringResource(R.string.ongoing_wifi_only)
        TrackingMode.WIFI_GRACE -> stringResource(R.string.ongoing_wifi_grace)
        TrackingMode.DURATION_ONLY -> stringResource(R.string.ongoing_timer_only)
        // The anchor has not landed yet, so there is no mode to report — only
        // what is still being waited on. Same strings the ongoing notification
        // uses, since both read this one field.
        TrackingMode.SETTLING -> stringResource(R.string.ongoing_settling)
    }
    // Same two modes the notification appends to, for the same reasons: FULL
    // carries no cause by construction, and WIFI_GRACE already names the thing
    // that matters and resolves in minutes.
    val reason = when (mode) {
        TrackingMode.WIFI_ONLY, TrackingMode.DURATION_ONLY ->
            degradationReasonRes(degradation)?.let { stringResource(it) }
        // SETTLING carries no cause for the same reason FULL doesn't: nothing
        // has degraded, and an arming record can still hold the previous
        // snooze's cause.
        TrackingMode.FULL, TrackingMode.WIFI_GRACE,
        TrackingMode.SETTLING -> null
    }
    StatusBlock(
        headline = stringResource(R.string.ongoing_title),
        // FULL carries no degraded reason by construction, so its one-row form
        // can never be missing one.
        oneRow = stringResource(R.string.main_snoozing_until_you_leave)
            .takeIf { mode == TrackingMode.FULL },
        condition = reason?.let { stringResource(R.string.ongoing_degraded_reason, body, it) }
            ?: body,
        detail = remainingText(remaining),
        // Only under `FULL`. The other modes are not measuring a distance —
        // showing one from the last fix before tracking degraded would explain
        // a threshold that is no longer what ends this snooze.
        readout = departure?.takeIf { mode == TrackingMode.FULL && it.isReportable }
            ?.let { departureText(it) },
    )
}

/**
 * The departure test's own arithmetic, in a sentence (`SPEC.md` §4.6).
 *
 * **Distance, how sure of it, and how much further — not distance and a fixed
 * edge.** The test subtracts the combined uncertainty before comparing, so the
 * meters still to go are a property of *this* reading rather than of the anchor
 * — a vague fix genuinely needs more distance than a sharp one, and naming the
 * nominal edge would promise a departure the current fix could not deliver. The
 * `±` is that same combined figure: both endpoints' accuracies in quadrature,
 * since the anchor is a reported point too ([Departure.uncertaintyM]).
 *
 * **In whichever units the phone is set to.** The distance and its unit are
 * formatted together and interpolated as one placeholder, so there is one pair
 * of sentences rather than a metric and an imperial copy of each — which is
 * also the shape a translator wants, since where the unit sits in a sentence is
 * not the same in every language. The rounding rules and the conversion are
 * [DistanceUnit]'s, and pure.
 */
@Composable
private fun departureText(observation: DepartureObservation): String {
    // Callers gate on `isReportable` first: a non-finite reading has no
    // sentence, and the screen already knows how to show no distance.
    val unit = rememberDistanceUnit()
    // The two confidence radii combined, which is what the test thresholds —
    // so the readout quotes the decision rather than describing one term of
    // it. A single figure on purpose: the components are diagnostic, and the
    // card is the tightest copy surface in the app.
    //
    // It is also the ruler for everything beside it: the step is the finest
    // rung this fix has earned, so the separation is rounded to it and the
    // figure itself is printed *as* that rung. `150 m away ±25 m` is one
    // reading described consistently; `142 m away ±18 m` was the same reading
    // claiming two digits of sharpness the second number denies.
    val step = unit.step(observation.uncertaintyM)
    val away = distanceText(unit, unit.snap(observation.distanceM, step), step)
    val uncertain = distanceText(unit, step)
    return if (observation.qualifies) {
        // Far enough on this fix, but a departure still needs a second one
        // thirty seconds later, so this reports the wait rather than the end.
        stringResource(R.string.main_distance_confirming, away, uncertain)
    } else {
        stringResource(
            R.string.main_distance_to_go,
            away,
            uncertain,
            // On the same step as the rest of the line, rounded down — see
            // `DistanceUnit.toGo` for why down, and why leaving this at whole
            // units was wrong.
            distanceText(unit, unit.toGo(observation.remainingM, step), step),
        )
    }
}

/** A whole number of [unit] — the shared [distanceText], for the reason above. */
@Composable
private fun distanceText(unit: DistanceUnit, value: Int): String =
    distanceText(LocalContext.current, unit, value)

/** The same, for a value that may not reach one whole [step] (`< 5 m`). */
@Composable
private fun distanceText(unit: DistanceUnit, value: Int?, step: Int): String =
    distanceText(LocalContext.current, unit, value, step)

/**
 * The unit this phone measures distance in — [distanceUnitFor], which the
 * ongoing notification asks too, so the two surfaces cannot disagree.
 *
 * Remembered against the configuration rather than read per recomposition: it
 * changes when the locale does, and a configuration change recreates the
 * activity anyway.
 */
@Composable
private fun rememberDistanceUnit(): DistanceUnit {
    val configuration = LocalConfiguration.current
    return remember(configuration) { distanceUnitFor(configuration) }
}

/** The same hours/minutes split and copy [app.snoozemo.tile.TileSnapshot] formats the tile's countdown from. */
@Composable
private fun remainingText(remaining: Duration): String {
    val minutes = remaining.toMinutes().coerceAtLeast(1)
    val hours = minutes / 60
    return if (hours > 0) {
        stringResource(TileR.string.tile_remaining_hours, hours, minutes % 60)
    } else {
        stringResource(TileR.string.tile_remaining_minutes, minutes)
    }
}

/**
 * The telemetry question, put to an install that has never answered it
 * (`SPEC.md` §12).
 *
 * Nothing is collected until the answer is yes, so an install that is never
 * asked never reports — which is why the question needs a home outside
 * Settings: a user who never opens Settings would otherwise have decided by
 * default, and the default is the one that loses every crash.
 *
 * **Both buttons record an answer**, because a question you can only walk
 * away from is not one that was asked. Declining is a recorded "no", not an
 * absence, so the card does not come back. simmo's `AnalyticsInviteCard` is
 * the prior art and this follows it — same question, same two answers, so
 * the apps read alike.
 *
 * Last of the banners, below even the update prompt: nothing is broken, and
 * everything above it is either blocking the product or offering to fix
 * something the user is missing.
 */
@Composable
private fun TelemetryInviteCard(onAnswer: (Boolean) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.telemetry_invite_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.telemetry_invite_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Pushed to opposite ends rather than clustered at the trailing
            // edge (maintainer, 2026-09-05): a yes/no pair sitting side by side
            // is two taps a thumb can confuse, and the separation is what makes
            // the affirmative one deliberate. `Yes please` is the trailing,
            // filled button — the affirmative answer is always the trailing one.
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
    }
}

/**
 * Background location is missing, so presence tracking cannot run at all and
 * every snooze will sit until its timer (SPEC.md §8.1).
 *
 * **Not error-toned, and that is the point.** Nothing is broken: the snooze
 * still arms, still silences the phone, and still ends — on the duration cap
 * the user set rather than on their walking away. So it takes
 * [TileBanner]'s tone, not [RequiredPermissionBanner]'s, and sits below both
 * of those on the screen: a missing tile or missing Do Not Disturb access
 * stops the product working, where this degrades it.
 *
 * Dismissible for good, like the tile banner and for the same reason:
 * someone who has declined once has been asked, and the permanent location
 * row on [PermissionsScreen] outlives the banner, so the route to granting
 * it stays open without asking twice.
 *
 * **Worded as an offer, not a warning** (maintainer, 2026-08-31). Nothing
 * has been lost and nothing is broken — there is a capability the user can
 * switch on — so the copy asks a question and names the benefit in one
 * line, and the buttons answer that question rather than reading as generic
 * actions.
 */
@Composable
private fun BackgroundLocationBanner(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.background_location_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.background_location_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.background_location_banner_dismiss))
                }
                Button(onClick = onAllow) {
                    Text(stringResource(R.string.background_location_banner_allow))
                }
            }
        }
    }
}

/**
 * The one thing this screen insists on: Do Not Disturb access, without which
 * nothing here can arm.
 *
 * An error-toned banner rather than a [SetupRow] — unlike the rows on
 * [PermissionsScreen], which state a capability and wait, this is the reason
 * the primary button on the screen the user is looking at is disabled right
 * now, so it reads as a problem rather than a checklist item.
 */
/**
 * The `Allow` button both required-capability banners use.
 *
 * The visible label is the same word on each, so a screen reader announcing it
 * alone cannot tell a user which capability a given button opens once both
 * banners are up at once — reachable on any install missing both (Codex, PR
 * #216). `SetupRow` solved exactly this for the rows on `PermissionsScreen`
 * (Codex, PR #103) and this reuses its string, so the two surfaces stay
 * consistent and a translation can reorder the parts independently of English
 * word order.
 *
 * Nothing drawn changes: the label stays `Allow` on both.
 */
@Composable
private fun AllowButton(capability: String, onClick: () -> Unit) {
    val label = stringResource(R.string.setup_action_allow)
    val description = stringResource(R.string.setup_row_action_description, label, capability)
    Button(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) { Text(label) }
}

@Composable
private fun RequiredPermissionBanner(onFix: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.dnd_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.setup_dnd_missing),
                style = MaterialTheme.typography.bodyMedium,
            )
            // End-aligned, matching TileBanner's and CrashBanner's own action
            // row — a bare Button left in the Column sits flush left instead,
            // which is inconsistent with every other banner on this screen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                AllowButton(capability = stringResource(R.string.setup_dnd_title), onClick = onFix)
            }
        }
    }
}

/**
 * The notifications half of what a tile tap needs, stated the same way its
 * sibling states Do Not Disturb access.
 *
 * Same `errorContainer` treatment as [RequiredPermissionBanner], because it is
 * the same class of problem: the app cannot do its job, and no row further down
 * a settings screen is going to be found by someone who does not already know
 * to look. Body and button are the notifications row's own strings, so the two
 * surfaces cannot describe the same missing capability differently.
 */
@Composable
private fun RequiredNotificationsBanner(onFix: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.notifications_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.setup_notifications_missing),
                style = MaterialTheme.typography.bodyMedium,
            )
            // End-aligned, matching every other banner's action row here.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                AllowButton(capability = stringResource(R.string.setup_notifications_title), onClick = onFix)
            }
        }
    }
}
