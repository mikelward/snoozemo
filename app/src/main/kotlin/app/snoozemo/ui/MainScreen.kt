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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.snoozemo.PlayUpdateState
import app.snoozemo.R
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.PolicyAccess
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
    tileAdded: Boolean?,
    tileBannerDismissed: Boolean,
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
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * Replays the welcome flow from its first card (`SPEC.md` §4.2). Defaulted
     * so a screenshot test pinning something else need not state an opinion.
     */
    onOpenWelcome: () -> Unit = {},
    onAddTile: () -> Unit,
    onDismissTileBanner: () -> Unit,
    onArm: () -> Unit,
    onRelease: () -> Unit,
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
    Column(
        modifier = modifier
            .fillMaxSize()
            // Outside the scroll, so the whole column — not just its resting
            // position — stays clear of the status bar, the navigation bar and
            // any display cutout. Inside the scroll it would only pad the
            // content, leaving a row to slide under the status bar as soon as
            // the user scrolled.
            .safeDrawingPadding()
            // Scrolls, and this is not cosmetic. Manual exit is "always
            // available, always instant" (SPEC.md §7), and a user who cannot
            // reach it because their font is large or their window is short
            // has lost the exit from this screen entirely.
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
        // Last of the banners. Everything above either blocks the product
        // (Do Not Disturb access, the tile) or offers to repair something the
        // user is missing; this asks for a favor, so it yields to all of them.
        if (telemetryUnanswered) {
            TelemetryInviteCard(onAnswer = onAnswerTelemetry)
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
                SnoozeStatus(trackingMode, remaining, degradation)
            snoozing == false -> NotSnoozingStatus()
            // Nothing yet: either the record is still being read, or it read
            // as running but without the mode and cap the line reports. Same
            // "unread is not zero" discipline as the banners above — an idle
            // claim over a snooze this screen hasn't finished reading is the
            // one wrong thing this line could say, and it is exactly the
            // wrong direction to be wrong in.
            else -> Unit
        }
        // Gated behind access being allowed, same as the old DebugScreen.
        //
        // **Exactly one of the two shows** (maintainer, 2026-08-22). The split
        // is on `snoozing == false` rather than on `snoozing == true`, and the
        // asymmetry is the whole design: `End snooze` is the one guaranteed
        // way to un-silence the phone, so it may only disappear where the
        // screen is *confident* nothing is running. Unknown — the record not
        // read yet — keeps it, because a stale or unread belief must never be
        // what stops someone turning their phone back on (SPEC.md §7: manual
        // exit is always available, always instant, and `endSnooze` is
        // idempotent, so offering it when nothing is running costs nothing).
        //
        // `Snooze` takes the opposite treatment for the same reason: it needs
        // a confident "nothing is running" to appear at all, since offering to
        // arm over a snooze the screen has not read yet is how a user loses
        // the deadline they were promised. It used to render disabled in that
        // state; showing the safety net instead says more with one button.
        if (access == PolicyAccess.GRANTED) {
            if (snoozing == false) {
                Button(
                    onClick = onArm,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.arm))
                }
            } else {
                OutlinedButton(
                    onClick = onRelease,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.release))
                }
            }
        }
        lastOutcome?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
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
 * read as another paragraph rather than the answer — so it is the largest text
 * on the screen and it sits in the middle, where a glance lands.
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
 */
@Composable
private fun StatusBlock(
    headline: String,
    oneRow: String? = null,
    condition: String? = null,
    detail: String? = null,
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
 */
@Composable
private fun SnoozeStatus(mode: TrackingMode, remaining: Duration, degradation: DegradationCause?) {
    val body = when (mode) {
        TrackingMode.FULL -> stringResource(R.string.ongoing_ends_when_you_leave)
        TrackingMode.WIFI_ONLY -> stringResource(R.string.ongoing_wifi_only)
        TrackingMode.WIFI_GRACE -> stringResource(R.string.ongoing_wifi_grace)
        TrackingMode.DURATION_ONLY -> stringResource(R.string.ongoing_timer_only)
    }
    // Same two modes the notification appends to, for the same reasons: FULL
    // carries no cause by construction, and WIFI_GRACE already names the thing
    // that matters and resolves in minutes.
    val reason = when (mode) {
        TrackingMode.WIFI_ONLY, TrackingMode.DURATION_ONLY ->
            degradationReasonRes(degradation)?.let { stringResource(it) }
        TrackingMode.FULL, TrackingMode.WIFI_GRACE -> null
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
    )
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
                Button(onClick = onFix) {
                    Text(stringResource(R.string.setup_action_allow))
                }
            }
        }
    }
}
