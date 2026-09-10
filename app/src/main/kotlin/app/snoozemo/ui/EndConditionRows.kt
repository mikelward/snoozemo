package app.snoozemo.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.EndCondition
import app.snoozemo.core.MeetingEnd
import app.snoozemo.core.TrackingMode
import java.time.Instant

/**
 * The end-condition choices as the **main screen** shows them (SPEC.md §4.4).
 *
 * The same choices the sheet offers over a freshly-armed snooze, on a screen
 * the user can open at any point during one — which is the difference that
 * matters: the sheet appears once, at the arm, and a snooze refined an hour
 * later had nowhere to be refined from (maintainer, 2026-09-08).
 *
 * Two things follow from being a screen rather than a sheet, and both are
 * behavior rather than layout:
 *
 * - **The rows are capitalized and stand alone.** The sheet's read under its
 *   own "Snoozing" title — `until 14:30` — while these are buttons with
 *   nothing above them to complete the sentence, so they reuse the ongoing
 *   notification's `Until %s` and its approved wording.
 * - **`Until I leave` commits.** On the sheet it dismisses, because a snooze
 *   that has just been armed is already running to its ceiling and "until I
 *   leave" is what it is already doing. Here the snooze may have been
 *   shortened minutes ago, so choosing it has to put the cap back — the one
 *   choice in the app that lengthens one, bounded by the same ceiling `+30
 *   min` is (§4.3).
 *
 * Deliberately stateless, like the sheet's own content: it is handed what to
 * draw and reports taps. Every label arrives already formatted, so this stays
 * free of platform calls and a screenshot test can pin exact strings.
 *
 * @param meetingLabels each offered meeting end, already rendered in the user's
 *   own 12/24-hour setting, earliest first. Empty when the calendar cannot be
 *   read or has nothing that would change anything — the rows simply aren't
 *   there, since a disabled row for a meeting nobody has explains nothing.
 */
@Composable
internal fun EndConditionRows(
    condition: EndCondition,
    formattedTime: String,
    meetingLabels: List<String>,
    onChooseTime: () -> Unit,
    onChooseMeeting: (Int) -> Unit,
    onChooseDeparture: () -> Unit,
    onStepDown: () -> Unit,
    onStepUp: () -> Unit,
    committing: Boolean = false,
    failed: Boolean = false,
    tracksDeparture: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // **First, above every refinement of it** (maintainer, 2026-09-08).
        // This is what the product is for and what an arm already does, so it
        // reads wrong sitting underneath two ways of narrowing it.
        if (tracksDeparture) {
            EndChoiceRow(
                label = stringResource(R.string.main_until_i_leave),
                onClick = onChooseDeparture,
                enabled = !committing,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The row commits; the steppers beside it only change what it would
            // commit. Two targets rather than one nested in the other, so
            // TalkBack names each — the rule the sheet's own row keeps.
            EndChoiceRow(
                label = stringResource(R.string.action_end_at, formattedTime),
                onClick = onChooseTime,
                enabled = !committing,
                modifier = Modifier.weight(1f),
            )
            EndStepper(
                symbol = "−",
                description = stringResource(R.string.sheet_earlier),
                enabled = condition.canStepDown && !committing,
                onClick = onStepDown,
            )
            EndStepper(
                symbol = "+",
                description = stringResource(R.string.sheet_later),
                enabled = condition.canStepUp && !committing,
                onClick = onStepUp,
            )
        }

        // Times only, and the same string the notification's action uses: a
        // meeting's *name* would put the user's day on a screen that needs a
        // time and nothing else (`AGENTS.md`, *Privacy*), and nothing here ever
        // asked the provider for one.
        meetingLabels.forEachIndexed { index, label ->
            EndChoiceRow(
                label = stringResource(R.string.action_end_at, label),
                onClick = { onChooseMeeting(index) },
                enabled = !committing,
                trailingIcon = R.drawable.ic_calendar,
                trailingIconDescription = stringResource(R.string.main_from_your_calendar),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Above nothing in particular here — unlike the sheet, there is no
        // bottom-most control for a growing message to push off screen — but
        // still beside the rows that produced it, which is where the tap was.
        if (failed) {
            Text(
                text = stringResource(R.string.failure_could_not_set_end),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * One committing row: tapping it chooses that end condition.
 *
 * Shared by the sheet and the main screen so the two cannot drift into
 * different-looking versions of the same control. [enabled] is what keeps a
 * second tap off an in-flight first one; the row stays drawn either way, since
 * a control that vanished mid-tap would move its neighbor under the finger.
 */
@Composable
internal fun EndChoiceRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /**
     * A mark drawn after [label], or null for a row that needs none.
     *
     * **After the text, never before it** (maintainer, 2026-09-08). Every row
     * on this screen starts its label at the same x, and a leading icon on
     * some of them would break that column — the eye scans the times, so the
     * times are what has to line up. What the mark says is *where this time
     * came from*, which is a qualifier on the answer rather than a category
     * in front of it.
     */
    @DrawableRes trailingIcon: Int? = null,
    trailingIconDescription: String? = null,
    /**
     * Draw the row as an outline rather than a filled card.
     *
     * **`End now` takes this** (maintainer, 2026-09-08). It is the same size
     * and shape as the rows above it, because it answers the same question,
     * but it is not one of the *whens* — every filled row schedules an end,
     * this one performs it — and a wall of identical cards where the last is
     * the irreversible one is the arrangement a hurried tap gets wrong. The
     * outline is what says "different kind of answer" without saying
     * "smaller", which would have made the guaranteed way back to a ringing
     * phone the hardest thing on the screen to hit (SPEC.md §7).
     */
    outlined: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        // Transparent rather than `surface`: the screen's own background may
        // not be `surface` under every theme, and a near-match that is not a
        // match reads as a rendering bug rather than as a choice.
        color = if (outlined) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        contentColor =
            if (outlined) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                // Before the padding, so the whole card answers the tap and the
                // Surface's shape clips the ripple to it.
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
                    // Named for a screen reader, since the mark carries meaning
                    // the label does not: which of two identical-looking times
                    // came from the calendar.
                    contentDescription = trailingIconDescription,
                    // Tinted by the row rather than by the drawable, so it sits
                    // at the weight of the text it follows in either theme.
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The `When I move` row (SPEC.md §4.4) — the one control on this screen that
 * carries a *state* rather than performing a choice.
 *
 * **A switch, not another card.** Every other row here answers the question
 * once and is done: tapping `Until 14:30` sets a deadline and the row has
 * nothing further to say. This one is on or off for the life of the snooze and
 * the user has to be able to see which, so it takes the affordance that shows
 * a state and can be reversed — the same reason `End now` is outlined rather
 * than filled: the shape is what says which kind of answer this is.
 *
 * The whole card toggles and the switch itself takes no click of its own, so
 * there is one target rather than two overlapping ones — a switch is a small
 * target next to a full-width row, and a miss that lands on the card would
 * otherwise do nothing.
 */
@Composable
internal fun MotionEndRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateLabel = stringResource(R.string.main_when_i_move_on)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .fillMaxWidth()
                .padding(16.dp)
                // What the label alone cannot say: `When I move` names the
                // choice either way, and a screen reader announcing only that
                // leaves a user unable to tell an armed snooze from an
                // unarmed one without toggling it to find out.
                .semantics { if (checked) stateDescription = stateLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.main_when_i_move),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // Null, so the row above owns the gesture and the semantics; the
            // switch is the picture of the state, not a second control.
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/**
 * One of the `−` / `+` steppers.
 *
 * The symbol is drawn, but the name announced is the whole phrase: "minus"
 * tells a screen-reader user nothing about what it steps or by how much.
 */
@Composable
internal fun EndStepper(
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

/**
 * One offered meeting end: the instant to commit and the label that names it.
 *
 * One object rather than two parallel lists, so the row a user reads and the
 * time a tap sends cannot drift apart — an index into a labels list that was
 * built from a different filtering is exactly the shape that commits the wrong
 * time silently.
 */
@Immutable
internal data class MeetingChoice(
    val at: Instant,
    /** [at] in the user's own 12/24-hour setting. */
    val label: String,
)

/**
 * Everything [EndConditionRows] draws, as one value.
 *
 * Bundled rather than passed as seven more parameters on [MainScreen], which
 * already takes enough of them — and null here is the whole answer to "is
 * there anything to refine right now", so the screen has one thing to check
 * rather than a condition plus six fields that only mean something when it is
 * non-null.
 *
 * `@Immutable` because [meetings] is a `List`, which Compose otherwise treats
 * as unstable and re-reads on every recomposition of the screen.
 */
@Immutable
internal data class EndChoiceUiState(
    val condition: EndCondition,
    /** [EndCondition.endsAt], already in the user's own 12/24-hour setting. */
    val formattedTime: String,
    val meetings: List<MeetingChoice> = emptyList(),
    val committing: Boolean = false,
    val failed: Boolean = false,
    /**
     * Whether this snooze can end on a departure at all. False on a
     * duration-only snooze, where `Until I leave` would name something nothing
     * is watching for (`TrackingMode.DURATION_ONLY`) — the row is dropped
     * rather than disabled, since a grayed control invites a tap that can
     * never work.
     */
    val tracksDeparture: Boolean = true,
)

/**
 * The screen's end-condition offer, or null when there is nothing to refine.
 *
 * Pure, and separate from the composition that reads it, because all three of
 * its rules are the kind that go wrong quietly and are worth a JVM test rather
 * than a Robolectric one (AGENTS.md, *Testing expectations*).
 *
 * **[record] has to be the offer's own**, matched on [offerFor], and the
 * caller is not trusted to have done that: an offer restored from saved state
 * is on screen before the asynchronous record read lands, and a null record
 * read as "tracks departure" would offer to put a duration-only snooze's cap
 * back to its eight-hour ceiling with nothing watching for the departure that
 * names (Codex, PR #234). Both questions fail closed on a record that is
 * absent or is another snooze's.
 *
 * **Everything is decided against [now], not against the moment the record was
 * read.** The offer is built from a snapshot and rendered against a moving
 * clock, and that gap is a *class* of bug rather than one: a meeting end
 * sliding inside the floor and the cap itself crossing inside it are the same
 * shape, and both leave a row that the service declines on every tap — the
 * controller cannot even reseed past it, since the offer it would rebuild from
 * no longer offers a choice. So the whole offer is withheld the moment the
 * record stops being refinable, rather than each row being patched as its own
 * case (Codex, PR #234, twice in the same mechanism).
 *
 * That gate also answers the unread record: an offer this cannot confirm still
 * belongs to a running, refinable snooze is one it must not solicit taps on.
 */
internal fun endChoiceUiState(
    condition: EndCondition?,
    offerFor: Instant?,
    record: ActiveSnooze?,
    meetingEnds: List<Instant>,
    now: Instant,
    committing: Boolean,
    failed: Boolean,
    format: (Instant) -> String,
): EndChoiceUiState? {
    if (condition == null) return null
    val offerRecord = record?.takeIf { it.startedAt == offerFor }
    // Fails closed on all three at once: no record, another snooze's record,
    // and a cap that has come inside the floor while the screen sat open.
    if (!EndCondition.offersAChoice(offerRecord, now)) return null
    return EndChoiceUiState(
        condition = condition,
        formattedTime = format(condition.endsAt),
        meetings = MeetingEnd.offersFor(offerRecord, meetingEnds, now, limit = MEETING_ROWS)
            .map { MeetingChoice(at = it, label = format(it)) },
        committing = committing,
        failed = failed,
        // The same predicate the service honors the tap with, so the row is
        // never offered where the restore would be declined — and never
        // withheld where it would be taken.
        tracksDeparture = offerRecord?.mode?.tracksDeparture == true,
    )
}

/**
 * The `When I move` switch, as the screen draws it — or null when there is
 * nothing to draw.
 *
 * **Deliberately not part of [EndChoiceUiState]**, and that separation is the
 * whole point (Codex, PR #252). That state answers "is there a *time* left to
 * choose", and withholds the whole offer once the cap comes inside `MIN_CAP` —
 * correct for rows that set a deadline, and wrong for this one, which carries
 * a state the user must be able to revoke. Bundled in, the switch vanished for
 * the final thirty minutes of every snooze while the sensor stayed armed:
 * an exit the user had turned on and could no longer turn off.
 *
 * So this asks only its own questions — is a snooze running, can the hardware
 * answer, can this build hold the service it needs — and none of the
 * time-choice ones.
 *
 * @param record the running snooze, or null while the screen has not read one.
 * @param deviceHasMotionSensor whether the phone has one at all; false offers
 *   nothing rather than a switch the service would roll straight back.
 *   **A lambda, and asked last**, because answering it is a `SensorManager`
 *   lookup: taken eagerly at the call site it ran during composition on every
 *   first frame, including an idle screen with no snooze and including
 *   `direct`, where the answer cannot matter (Codex, PR #252). Deferred, the
 *   two free checks below settle the common cases and the platform is only
 *   asked when its answer actually decides something.
 */
internal fun motionEndUiState(
    record: ActiveSnooze?,
    deviceHasMotionSensor: () -> Boolean,
): MotionEndUiState? {
    if (record == null) return null
    // A build with no foreground service cannot keep the process alive to hear
    // a one-shot sensor, so the promotion is refused and the exit never fires.
    // `direct` was excluded by accident until the tracking-mode gate came off
    // — it runs duration-only snoozes, which that gate also caught.
    if (!buildHoldsForegroundService) return null
    if (!deviceHasMotionSensor()) return null
    return MotionEndUiState(enabled = record.endsOnMotion)
}

/** Everything [MotionEndRow] draws, as one value. */
@Immutable
internal data class MotionEndUiState(
    /** Whether the running snooze already ends on movement (SPEC.md §4.4). */
    val enabled: Boolean,
)

/**
 * How many meeting ends the screen offers.
 *
 * Two rather than the notification's one: a screen has room for the choice a
 * card has to pick between, and "the meeting after this one" is the common
 * answer when the current one is nearly over.
 */
internal const val MEETING_ROWS = 2
