package app.snoozemo.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.snoozemo.PlayUpdateState
import app.snoozemo.R

/**
 * Everything touched rarely — usually never — moved off [MainScreen] so the
 * arm/release control isn't sharing a screen with it (`TODO.md` Phase 4).
 *
 * The tile row lives here rather than on [MainScreen]: it is permanent by
 * design (`SPEC.md` §4.2 — its permanence is what makes the tile banner's
 * forever-dismissal on `MainScreen` safe), and a permanent row is exactly the
 * kind of always-there-but-rarely-touched item this screen exists to hold.
 */
@Composable
internal fun SettingsScreen(
    tileAdded: Boolean?,
    filtersRuleId: String?,
    settingsFailure: SetupRowId?,
    debugLogEnabled: Boolean?,
    debugLogSaveFailed: Boolean,
    /**
     * Whether crash reporting is on, or **null when this build has no reporter
     * to offer** — `direct` always, and a `play` build made without a Firebase
     * config (`docs/crashlytics.md`). Null draws no row at all rather than a
     * disabled one: a switch over a reporter that does not exist would tell
     * the user they had turned something off that was never on. It is also
     * null until the store has answered, the same
     * null-until-read discipline as [debugLogEnabled] and for the same reason.
     */
    crashReportingEnabled: Boolean? = null,
    /** Whether the last crash-reporting save was refused by storage. */
    crashReportingSaveFailed: Boolean = false,
    /**
     * Whether the tile asks when to unsnooze (`SPEC.md` §4.4), or null until
     * [MainActivity] has read it. Off by default, so the row is an offer.
     */
    askWhenToUnsnooze: Boolean? = null,
    /** Whether the last tap on that switch failed to reach disk. */
    askWhenToUnsnoozeSaveFailed: Boolean = false,
    // `PlayUpdateState.NotAvailable` on `direct` (no flavor branch needed
    // here — that flavor's checker never reports anything else) and while
    // `MainActivity` hasn't finished its own first resume check yet.
    playUpdate: PlayUpdateState = PlayUpdateState.NotAvailable,
    // Whether the last Restart tap failed to hand off to Play. Only ever
    // meaningful alongside a `Downloaded` [playUpdate] — see that field's
    // own comment on `MainActivity`.
    playUpdateRestartFailed: Boolean = false,
    debugLogCleanupFailed: Boolean,
    shareFailed: Boolean,
    /** Whether a share is already running, disabling the share row's button. */
    sharing: Boolean = false,
    /** `BuildConfig.VERSION_NAME`, shown at the foot of the page. */
    versionName: String = "",
    /**
     * Whether a crashed run is currently pinned (`SPEC.md` §4.6). Every
     * screen renders the banner while one is, this one included: `onCreate`
     * restores [Screen] from saved state, so a process killed while the user
     * was on Settings comes back *here*, not on [MainScreen] (Codex, PR #89).
     */
    crashPending: Boolean = false,
    /** Whether the last Dismiss tap on the crash banner was refused by the file layer. */
    dismissFailed: Boolean = false,
    onOpenPermissions: () -> Unit,
    onTileRow: () -> Unit,
    onFiltersRow: () -> Unit,
    onDebugLog: (Boolean) -> Unit,
    onCrashReporting: (Boolean) -> Unit = {},
    onAskWhenToUnsnooze: (Boolean) -> Unit = {},
    onStartPlayUpdate: () -> Unit = {},
    onCompletePlayUpdate: () -> Unit = {},
    onDismissPlayUpdate: () -> Unit = {},
    onShareDebugLog: () -> Unit,
    onDismissCrash: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SnoozemoTitleRow(title = stringResource(R.string.settings_title))
        // Above everything else, exactly as on the other two screens: which
        // screen the user lands on is not something this feature should have
        // to reason about (SPEC.md §4.6).
        if (crashPending) {
            CrashBanner(
                onShare = onShareDebugLog,
                onDismiss = onDismissCrash,
                shareFailed = shareFailed,
                dismissFailed = dismissFailed,
                sharing = sharing,
            )
        }
        // Above the permanent rows, the same "most urgent thing first" order
        // the sibling Simmo repo's own banner follows: there is something to
        // act on here, where the rows below mostly just state what already
        // holds.
        (playUpdate as? PlayUpdateState.Available)?.takeIf { it.shouldPrompt }?.let { update ->
            PlayUpdateBanner(
                progress = update.progress,
                restartFailed = playUpdateRestartFailed,
                onUpdate = onStartPlayUpdate,
                onRestart = onCompletePlayUpdate,
                onDismiss = onDismissPlayUpdate,
            )
        }
        Button(
            onClick = onOpenPermissions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.permissions_title))
        }
        // Permanent, and null until the store has answered. Permanent because
        // it is what makes the tile banner's forever-dismissal on MainScreen
        // safe — the banner can be sent away for good precisely because this
        // outlives it — and null-until-read because a permanent row with a
        // default would assert `Added` on the first frame of a cold launch and
        // then correct itself, which is the one lie this screen is least
        // entitled to tell.
        tileAdded?.let { added ->
            SetupRow(
                title = stringResource(R.string.setup_tile_title),
                status = stringResource(
                    if (added) R.string.setup_tile_added else R.string.setup_tile_missing,
                ),
                // Absent once the tile is there: nothing is left to create,
                // and the platform's own answer to a redundant request is a
                // dialog saying it is already added. The row becomes a
                // statement.
                action = stringResource(R.string.setup_action_add).takeUnless { added },
                onAction = onTileRow,
                failure = stringResource(R.string.failure_could_not_add_tile)
                    .takeIf { settingsFailure == SetupRowId.TILE },
            )
        }
        // Directly below the tile row, because it changes what a tile tap does:
        // off (the default) arms and gets out of the way, on puts the
        // end-condition sheet in front of the user first (`SPEC.md` §4.4). Same
        // null-until-read discipline as the switch below — a row that asserted
        // the default and corrected itself a frame later would flash `off` on
        // the one launch where the user had turned it on.
        askWhenToUnsnooze?.let {
            AskWhenToUnsnoozeRow(
                enabled = it,
                saveFailed = askWhenToUnsnoozeSaveFailed,
                onChange = onAskWhenToUnsnooze,
            )
        }
        // Absent rather than disabled while there is nothing yet to edit: no
        // Do Not Disturb access, or access allowed but the rule not created
        // yet (TODO.md's "SettingsScreen button to the system zen rule's own
        // interruption-filter screen" — MainActivity clears `filtersRuleId`
        // in both cases). A button with nothing behind it is the dead tap
        // AGENTS.md's error-handling rules exist to keep off this screen.
        filtersRuleId?.let {
            SetupRow(
                title = stringResource(R.string.setup_filters_title),
                status = stringResource(R.string.setup_filters_status),
                action = stringResource(R.string.setup_action_edit),
                onAction = onFiltersRow,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.FILTERS },
            )
        }
        // Below the tile row, deliberately: touched rarely — usually never.
        // Same null-until-read discipline as the row above: a switch that
        // asserted the default and corrected itself a frame later would flash
        // on the one launch where the user had turned it off.
        debugLogEnabled?.let {
            DebugLogRow(
                enabled = it,
                saveFailed = debugLogSaveFailed,
                cleanupFailed = debugLogCleanupFailed,
                onChange = onDebugLog,
            )
        }
        // Directly below the debug-log switch: the two are the app's whole
        // diagnostics story and belong read together, and this is the one of
        // the pair that sends something off the phone — so it sits second,
        // where the row above has already established what "diagnostics"
        // means here.
        crashReportingEnabled?.let {
            CrashReportingRow(
                enabled = it,
                saveFailed = crashReportingSaveFailed,
                onChange = onCrashReporting,
            )
        }
        // Always offered, unlike the rows above: there is no "done" state to
        // reach — sharing is repeatable, not a capability to fix once — so
        // SetupRow's action is always present rather than dropping away.
        SetupRow(
            title = stringResource(R.string.setup_debug_log_share_title),
            status = stringResource(R.string.setup_debug_log_share_status),
            action = stringResource(
                if (sharing) R.string.share_in_progress else R.string.setup_debug_log_share_action,
            ),
            onAction = onShareDebugLog,
            failure = stringResource(R.string.setup_debug_log_share_failed).takeIf { shareFailed },
            actionRunning = sharing,
        )
        // Same row shape and position as the sibling Simmo repo's Settings foot.
        // Padded, unlike a bare Text row, so the tap target clears Android's
        // 48dp minimum rather than sitting at the titleMedium text's own
        // ~24dp height.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPrivacyPolicy)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_privacy_policy),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        // Below the privacy policy, above the version — the same row, shape,
        // and position as the sibling Simmo repo's own Licenses row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenLicenses)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_licenses),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        // At the very foot of the page, same format as the sibling Simmo repo.
        Text(
            text = stringResource(R.string.settings_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
