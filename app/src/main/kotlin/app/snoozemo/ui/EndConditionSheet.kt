package app.snoozemo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.core.EndCondition

/**
 * The end-condition sheet's content (SPEC.md §4.4) — two rows that refine a
 * snooze which is **already running**.
 *
 * Split from the window that hosts it, the way the sibling repos split a dialog
 * body from its `Dialog` wrapper: this half renders inside an
 * activity-hosted Compose tree in a Robolectric screenshot test, with no popup
 * window to settle.
 *
 * Deliberately stateless. It is handed an [EndCondition] and reports taps; the
 * trampoline holds the state and does the committing, so nothing here can be
 * waiting on anything (§6.9).
 *
 * @param formattedTime the chosen time rendered in the user's own 12/24-hour
 *   setting. Passed in rather than formatted here so this stays free of
 *   platform calls and a screenshot test can pin one string.
 */
@Composable
internal fun EndConditionSheetContent(
    condition: EndCondition,
    formattedTime: String,
    onChooseTime: () -> Unit,
    onChooseDeparture: () -> Unit,
    onStepDown: () -> Unit,
    onStepUp: () -> Unit,
    failed: Boolean = false,
    /**
     * Whether a chosen time is with the service and unanswered. The rows and
     * steppers go inert while it is: the sheet no longer dismisses the instant a
     * row is tapped, so without this a second tap could stack a second commit on
     * the first.
     */
    committing: Boolean = false,
    /**
     * Whether this build can ever end a snooze because the user left
     * (`PRESENCE_TRACKS_DEPARTURE`). False drops the departure row *and* the
     * footer: on a build with no presence monitor, "until I leave" is a promise
     * nothing behind it can keep, and the footer exists only to say the two rows
     * are not exclusive — with one row there is nothing to disambiguate.
     */
    tracksDeparture: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The row commits; the steppers beside it only change what it
                // would commit. Two targets, not one nested inside the other, so
                // TalkBack has a name for each — the same rule `SetupRow` keeps.
                ChoiceRow(
                    label = stringResource(R.string.sheet_until_time, formattedTime),
                    onClick = onChooseTime,
                    enabled = !committing,
                    modifier = Modifier.weight(1f),
                )
                Stepper(
                    symbol = "−",
                    description = stringResource(R.string.sheet_earlier),
                    enabled = condition.canStepDown && !committing,
                    onClick = onStepDown,
                )
                Stepper(
                    symbol = "+",
                    description = stringResource(R.string.sheet_later),
                    enabled = condition.canStepUp && !committing,
                    onClick = onStepUp,
                )
            }

            if (tracksDeparture) {
                ChoiceRow(
                    label = stringResource(R.string.sheet_until_i_leave),
                    onClick = onChooseDeparture,
                    enabled = !committing,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.sheet_footer),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // A tap the service refused has to say so where the tap was — the
            // sheet is about to dismiss otherwise, and the snooze would keep a
            // deadline the user did not choose with nothing to show for it.
            // Same placement rule as `SetupRow`'s own failure line.
            if (failed) {
                Text(
                    text = stringResource(R.string.failure_could_not_set_end),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * One committing row: tapping it chooses that end condition.
 *
 * The time row dismisses only once the service has confirmed the change, so
 * [enabled] is what keeps a second tap off an in-flight first one. The row stays
 * drawn either way — a control that vanished mid-tap would move the other one
 * under the user's finger.
 */
@Composable
private fun ChoiceRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                // Before the padding, so the whole card answers the tap and the
                // Surface's shape clips the ripple to it.
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

/**
 * One of the `−` / `+` steppers.
 *
 * The symbol is drawn, but the name announced is the whole phrase: "minus" tells
 * a screen-reader user nothing about what it steps or by how much.
 */
@Composable
private fun Stepper(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(text = symbol, style = MaterialTheme.typography.titleMedium)
    }
}
