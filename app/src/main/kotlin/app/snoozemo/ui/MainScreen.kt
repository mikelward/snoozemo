package app.snoozemo.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.TrackingMode
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
@Composable
fun MainScreen(
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
    lastOutcome: String?,
    /** Whether a crashed run is currently pinned (SPEC.md §4.6) — the crash banner's own state. */
    crashPending: Boolean,
    /** Whether the last debug-log share reached neither the clipboard nor the chooser. */
    shareFailed: Boolean,
    /** Whether the last Dismiss tap on the crash banner was refused by the file layer. */
    dismissFailed: Boolean,
    /** Whether a share is already running, disabling the banner's Share button. */
    sharing: Boolean = false,
    // Only SetupRowId.TILE is ever relevant here — this banner has no other
    // capability to fail — but the type is shared with the other screens'
    // failure-routing rather than narrowed to a Boolean, so a caller reading
    // this signature can see at a glance it's the same failure state as
    // everywhere else, not a screen-local one.
    settingsFailure: SetupRowId? = null,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddTile: () -> Unit,
    onDismissTileBanner: () -> Unit,
    onArm: () -> Unit,
    onRelease: () -> Unit,
    onShareDebugLog: () -> Unit,
    onDismissCrash: () -> Unit,
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
        SnoozemoTitleRow(title = stringResource(R.string.app_name))
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
                SnoozeStatus(trackingMode, remaining)
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
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_title))
        }
        lastOutcome?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The idle half of the status slot: no snooze is running, said plainly.
 *
 * Same `titleMedium` and the same position as [SnoozeStatus]'s mode line, so
 * the two read as one line changing rather than content appearing and
 * disappearing — and so a user who glances at the screen gets the answer from
 * the words rather than from which button is grayed.
 *
 * No second line beneath it. The running state's second line carries the
 * remaining time; idle has no equivalent fact, and inventing one ("Tap Snooze
 * to start") would only restate the button directly below it.
 */
@Composable
private fun NotSnoozingStatus() {
    Text(
        text = stringResource(R.string.main_not_snoozing),
        style = MaterialTheme.typography.titleMedium,
    )
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
 * `ongoing_timer_only`)
 * and the remaining-time line reuses the tile's (`tile_remaining_hours` /
 * `tile_remaining_minutes`, `:tile` module) — the same two facts stated the
 * same way everywhere they already appear, rather than a third phrasing.
 */
@Composable
private fun SnoozeStatus(mode: TrackingMode, remaining: Duration) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = when (mode) {
                TrackingMode.FULL -> stringResource(R.string.ongoing_ends_when_you_leave)
                TrackingMode.WIFI_ONLY -> stringResource(R.string.ongoing_wifi_only)
                TrackingMode.WIFI_GRACE -> stringResource(R.string.ongoing_wifi_grace)
                TrackingMode.DURATION_ONLY -> stringResource(R.string.ongoing_timer_only)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = remainingText(remaining), style = MaterialTheme.typography.bodyMedium)
    }
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
