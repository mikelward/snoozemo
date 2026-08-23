package app.snoozemo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.UpdateProgress

/**
 * The three screens (`MainScreen`, `PermissionsScreen`, `SettingsScreen`) share
 * this theme and these building blocks — `SetupRow` in particular is what a
 * capability row looks like wherever one appears, so it lives here rather than
 * inside any one screen's file.
 */
@Composable
fun SnoozemoTheme(content: @Composable () -> Unit) {
    // Built once per dark-mode reading rather than on every recomposition:
    // `lightColorScheme()`/`darkColorScheme()` allocate a whole new
    // `ColorScheme` (dozens of `Color` fields), and this composable sits
    // above every screen this app has — every one of them recomposing
    // through a scope that rebuilds the palette from scratch would be waste
    // for no visual difference.
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = remember(darkTheme) { if (darkTheme) darkColorScheme() else lightColorScheme() }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/** Which setup row a failure belongs beside. */
enum class SetupRowId {
    DND,
    NOTIFICATIONS,
    LOCATION,
    TILE,
    FILTERS,
}

/**
 * One capability: what it is, how it stands, and — only while something is
 * actually left to do — the button that fixes it.
 *
 * The trailing-button shape is the sibling Simmo repo's `GrantRow`, and the two
 * halves of it are load-bearing separately.
 *
 * **A verb, not a route.** The action line used to describe the mechanism —
 * `Opens Settings`, `Tap to add` — which reads as a note about what will happen
 * rather than an offer to do it, and made a granted row look like it still
 * wanted something. `Grant`, `Allow` and `Add` say what the user gets.
 *
 * **Nothing to tap once it is done.** [action] is null when the capability is
 * in place, and the row is then a statement: title and status, no control. A
 * button that only re-opens a screen the user has already finished with is a
 * tap with nothing behind it, and it keeps first-run urgency on a screen where
 * everything is already fine.
 *
 * That is a deliberate trade against the shape this row had before, where the
 * whole surface was clickable so the sentence naming the problem was itself the
 * target (`TODO.md` Phase 2). One target per row is what TalkBack can announce
 * unambiguously — a button nested inside a clickable row gives the same tap two
 * different names — so the row keeps the single target and moves it onto the
 * control that says what it does.
 */
@Composable
internal fun SetupRow(
    title: String,
    status: String,
    action: String?,
    onAction: () -> Unit,
    failure: String? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            // The same gap the sibling repo keeps between a label block and its
            // trailing control: the text takes whatever width the button
            // leaves, so a status long enough to wrap would otherwise end flush
            // against the button and read as one object with it.
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = status, style = MaterialTheme.typography.bodyMedium)
                // Inside the row, not at the foot of the screen. A tap that
                // could not open Settings has to say so where the tap was: the
                // column scrolls, so a message appended below the buttons is
                // off screen in landscape or at a large font scale, and the row
                // would read as the dead tap this screen exists to remove
                // (flagged by Codex on PR #18).
                //
                // And only beside an offer. The message is about a tap, so a
                // row with nothing left to tap has nothing to report — showing
                // it there would put `Couldn't open Settings` under `Granted`,
                // which is the screen contradicting itself about the user's own
                // phone. The state is cleared as well, in the two places that
                // learn the capability recovered; this is what keeps the two
                // from disagreeing in the frame between.
                failure?.takeIf { action != null }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            action?.let {
                Button(onClick = onAction) { Text(it) }
            }
        }
    }
}

/**
 * The screen's one piece of advocacy: add the tile.
 *
 * Deliberately not a [SetupRow]. The rows state a fact about a capability and
 * offer the repair; this makes a case, and looking different is the point —
 * `SPEC.md` §4.2 asks the screen to push toward the tile rather than list it as
 * one option among equals. It is the only element here that says *why*.
 *
 * Two actions, weighted: adding is the filled button, dismissing is a text one.
 */
@Composable
internal fun TileBanner(
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    failure: String? = null,
) {
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
                text = stringResource(R.string.tile_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.tile_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            // A tap that could not add the tile has to say so here too, not
            // only on the permanent row on SettingsScreen (Codex, PR #82) —
            // the banner is where the tap actually happened, and a failure
            // that only shows up on a screen the user hasn't opened reads as
            // the tap having done nothing.
            failure?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.tile_banner_dismiss))
                }
                Button(onClick = onAdd) {
                    Text(stringResource(R.string.tile_banner_add))
                }
            }
        }
    }
}

/**
 * The Play update banner (`SettingsScreen`, `play` flavor only —
 * [app.snoozemo.PlayUpdateChecker]): "Update available" with Dismiss /
 * Update, tracking a flexible update through downloading to "Update ready"
 * with Restart.
 *
 * Dismiss is offered before the download and not after: while it is in
 * flight there is nothing useful to tap (Update would re-open a sheet for a
 * download already running), and once it is downloaded, dismissing would
 * hide the only in-app way to install what has already been fetched.
 */
@Composable
internal fun PlayUpdateBanner(
    progress: UpdateProgress,
    // Only ever meaningful alongside `Downloaded` — see the caller's own
    // comment on why this is carried apart from [progress] rather than a
    // fifth [UpdateProgress] state.
    restartFailed: Boolean = false,
    onUpdate: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val inFlight = progress == UpdateProgress.Starting || progress == UpdateProgress.Downloading
    val downloaded = progress == UpdateProgress.Downloaded
    val titleRes = when {
        inFlight -> R.string.play_update_banner_updating_title
        downloaded -> R.string.play_update_banner_downloaded_title
        else -> R.string.play_update_banner_title
    }
    val bodyRes = when {
        inFlight -> R.string.play_update_banner_updating_body
        downloaded -> R.string.play_update_banner_downloaded_body
        else -> R.string.play_update_banner_body
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Inside the card, like every other row's failure line on these
            // screens (`SetupRow`, `TileBanner`) — the message is about the
            // tap, and the column scrolls. Only reachable in `Downloaded`:
            // that is the only state Restart is offered in.
            if (downloaded && restartFailed) {
                Text(
                    text = stringResource(R.string.play_update_banner_restart_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    inFlight -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    // No Dismiss once the update is downloaded: dismissing is
                    // remembered against that build, and Restart is the only
                    // in-app way to finish it — so the pair would strand a
                    // download that has already been fetched, with nothing
                    // left to offer until Play publishes a *newer* build. The
                    // banner costs a card at the top of the screen until the
                    // restart happens, which is the cheaper end of that
                    // trade.
                    downloaded -> Button(onClick = onRestart) {
                        Text(stringResource(R.string.play_update_banner_restart_button))
                    }
                    else -> {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.play_update_banner_dismiss))
                        }
                        Button(onClick = onUpdate) {
                            Text(stringResource(R.string.play_update_banner_update_button))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The debug log's on/off switch (SPEC.md §4.6): on by default, and turning it
 * off deletes what was kept.
 *
 * Not a [SetupRow]: those state a capability and offer its one repair, while
 * this is a choice with two valid states, so it carries a switch. The whole
 * card is the target and the switch is its indicator — one TalkBack target per
 * row, the same rule the rows above keep — which is why the switch itself
 * takes no `onCheckedChange` of its own.
 */
@Composable
internal fun DebugLogRow(
    enabled: Boolean,
    saveFailed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // `toggleable` before the padding, so the whole card answers the
            // tap; the Surface's shape clips the ripple to the card.
            modifier = Modifier
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onChange,
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.setup_debug_log_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setup_debug_log_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Inside the row, like SetupRow's failure line and for the
                // same reason: the message is about the tap, and the column
                // scrolls. The switch has already snapped back to the stored
                // truth by the time this shows; the line is what stops the
                // snap-back reading as a missed tap.
                if (saveFailed) {
                    Text(
                        text = stringResource(R.string.setup_debug_log_save_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}
