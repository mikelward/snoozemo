package app.snoozemo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    // `PlayUpdateState.NotAvailable` on `direct` (no flavor branch needed
    // here — that flavor's checker never reports anything else) and while
    // `MainActivity` hasn't finished its own first resume check yet.
    playUpdate: PlayUpdateState = PlayUpdateState.NotAvailable,
    // Whether the last Restart tap failed to hand off to Play. Only ever
    // meaningful alongside a `Downloaded` [playUpdate] — see that field's
    // own comment on `MainActivity`.
    playUpdateRestartFailed: Boolean = false,
    crashPending: Boolean,
    shareFailed: Boolean,
    onOpenPermissions: () -> Unit,
    onTileRow: () -> Unit,
    onFiltersRow: () -> Unit,
    onDebugLog: (Boolean) -> Unit,
    onStartPlayUpdate: () -> Unit = {},
    onCompletePlayUpdate: () -> Unit = {},
    onDismissPlayUpdate: () -> Unit = {},
    onShareDebugLog: () -> Unit,
    onDismissCrash: () -> Unit,
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
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        // First, above everything else touched rarely: a crashed run is the
        // one thing on this screen that needs acting on rather than merely
        // being available (SPEC.md §4.6).
        if (crashPending) {
            CrashBanner(onShare = onShareDebugLog, onDismiss = onDismissCrash)
        }
        // Next, the same "most urgent thing first" order the sibling Simmo
        // repo's own banner follows: there is something to act on here,
        // where the rows below mostly just state what already holds.
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
        // Absent rather than disabled while there is nothing yet to edit: no
        // Do Not Disturb access, or access granted but the rule not created
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
            DebugLogRow(enabled = it, saveFailed = debugLogSaveFailed, onChange = onDebugLog)
        }
        // Always offered, unlike the rows above: there is no "done" state to
        // reach — sharing is repeatable, not a capability to fix once — so
        // SetupRow's action is always present rather than dropping away.
        SetupRow(
            title = stringResource(R.string.setup_debug_log_share_title),
            status = stringResource(R.string.setup_debug_log_share_status),
            action = stringResource(R.string.setup_debug_log_share_action),
            onAction = onShareDebugLog,
            failure = stringResource(R.string.setup_debug_log_share_failed).takeIf { shareFailed },
        )
    }
}
