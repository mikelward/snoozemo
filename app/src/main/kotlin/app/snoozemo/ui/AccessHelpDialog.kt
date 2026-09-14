package app.snoozemo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.snoozemo.R

/**
 * The "how to grant Do Not Disturb access" help dialog (maintainer,
 * 2026-09-14). Tapping `Allow` on the access row opens this before the system
 * access list, because that list is a page of apps the user has to find
 * Snoozemo in and toggle on — a step the button alone does not explain.
 *
 * **[BasicAlertDialog], not `AlertDialog`**, for the same reason the end-help
 * card is (`EndConditionRows`): `AlertDialog` takes its title, body and buttons
 * as separate slots, so there is no single composable to hand a screenshot
 * test, and a dialog is its own window that this suite's `decorView` capture
 * cannot reach. `BasicAlertDialog` keeps Material's width range and the pane
 * semantics TalkBack announces the modal with, while giving one content
 * composable — [AccessHelpDialogContent] — that the test records directly.
 *
 * The content is wrapped in the pinch host and a [FontSizeWindow], as the
 * rationale and end-help dialogs are: a dialog is its own window, so the
 * theme's scaled density does not reach it otherwise (Codex, PR #217).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AccessHelpDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.pinchFontSizeHost()) {
            FontSizeWindow {
                AccessHelpDialogContent(onConfirm = onConfirm, onDismiss = onDismiss)
            }
        }
    }
}

/**
 * What the access-help dialog says, drawn in whatever window the caller gives
 * it. `internal` so a screenshot test can record it directly; [AccessHelpDialog]
 * is the only production caller and puts it in a dialog window.
 *
 * Carries Material's dialog width range itself (as [EndHelpCardContent] does),
 * so the recorded image shows the width the product draws even though the test
 * renders it without the [BasicAlertDialog] wrapper that also applies it.
 */
@Composable
internal fun AccessHelpDialogContent(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.sizeIn(minWidth = ACCESS_DIALOG_MIN_WIDTH, maxWidth = ACCESS_DIALOG_MAX_WIDTH),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Weighted and scrolling so the buttons are measured first — a long
            // body at a large font must not push the way out off a short window
            // (the failure the end-help card hit on Codex, PR #265).
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // A heading, so TalkBack's heading navigation finds the modal's
                // one title (BasicAlertDialog restores the container, not a
                // title slot's semantics).
                Text(
                    text = stringResource(R.string.welcome_access_help_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.welcome_access_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Affirmative trailing, and a FlowRow rather than a plain Row so
            // the two actions wrap to stack instead of overflowing: at the
            // system font enlarged and the app's own scale near 160% on a
            // compact multi-window pane, a fixed row squeezes `Open settings`
            // — the only route on — to a sliver, as Material's own wrapping
            // action layout does not (Codex, PR #288).
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.welcome_access_help_dismiss))
                }
                Button(onClick = onConfirm) {
                    Text(stringResource(R.string.welcome_access_help_continue))
                }
            }
        }
    }
}

/** Material's dialog width range, repeated so the recorded image carries it. */
private val ACCESS_DIALOG_MIN_WIDTH = 280.dp
private val ACCESS_DIALOG_MAX_WIDTH = 560.dp
