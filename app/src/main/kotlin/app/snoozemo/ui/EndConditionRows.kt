package app.snoozemo.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
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
 *   shortened minutes ago, so choosing it has to put the cap back — all the
 *   way to the ceiling, and bounded by the same one `+30 min` is (§4.3).
 *   Not the only choice that lengthens a cap any more: a chosen time moves it
 *   either way now, so `−` has a way back that is not this row.
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
    /**
     * Whether the chosen time applied but left an exit armed. A different line
     * from a refusal: the time *is* set, so [failureText] would describe the
     * opposite failure (SPEC.md §4.4).
     */
    partial: Boolean = false,
    tracksDeparture: Boolean = true,
    /**
     * Whether `Until I move` and `Until I leave` can be tapped right now,
     * apart from a commit being out — false while an offer to start is
     * drawn ahead of the location reading a tap on either rides
     * ([EndChoiceUiState.locationArmable]). The time row, the meeting rows
     * and the steppers need no reading and are never held by this.
     */
    locationRowsEnabled: Boolean = true,
    /**
     * Whether to offer `Until I move` (SPEC.md §4.4) — false on a build with
     * no foreground service or a phone with no significant-motion sensor,
     * where the row would promise an exit that cannot fire.
     */
    offersMotionEnd: Boolean = false,
    /** Commits "ends when you move" — an added exit; the cap stays. */
    onChooseMotionEnd: () -> Unit = {},
    /**
     * Whether this snooze's departure is watching the anchor's Wi-Fi, and
     * whether it is watching the area, as `Until I leave`'s card names them
     * (SPEC.md §4.4). Both default true: that is the pre-arm case, where
     * there is no anchor to ask.
     */
    departureUsesWifi: Boolean = true,
    departureUsesArea: Boolean = true,
    /**
     * What a refused tap says beside the rows. A refinement that was declined
     * could not set the end time; a row that offered to *start* a snooze and
     * was refused has nothing running behind it, and says so.
     */
    failureText: String = stringResource(R.string.failure_could_not_set_end),
    modifier: Modifier = Modifier,
) {
    // Which help card is open, if any. Local rather than hoisted: it is one
    // dialog's visibility and nothing outside this composable acts on it, so
    // both hosts — the sheet and the main screen — get the behavior without
    // threading a fourth piece of state through either.
    //
    // Saved rather than a plain `remember`, the way the licenses dialog saves
    // its selection: this activity handles no configuration change itself, so
    // a rotation with the card open would close it mid-read (Codex, PR #261).
    // The name rather than the value, since that is what a `Bundle` holds —
    // and read back by lookup rather than `valueOf`, so a name that no longer
    // names anything opens no card instead of throwing.
    var openHelp by rememberSaveable { mutableStateOf<String?>(null) }
    // Gated on the row still being offered, not only on what was tapped. A
    // snooze can lose departure tracking while its card is open — location
    // switched off, the grant revoked — and `tracksDeparture` then drops the
    // row from under a card still explaining what leaving will do (Codex, PR
    // #261). The card goes with its row, both ways.
    val help = EndHelp.entries
        .firstOrNull { it.name == openHelp }
        ?.takeIf { if (it == EndHelp.LEAVE) tracksDeparture else offersMotionEnd }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // **Clock times first, then the two events** (maintainer, 2026-09-10:
        // "Until time / Until I move / Until I leave"). Reverses 2026-09-08's
        // "`Until I leave` first, above every refinement of it": with a second
        // event-shaped row beside it the list reads as *when* — the times a
        // user can adjust, then the things that can happen — and the row the
        // product is for still closes the list rather than opening it.
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

        // **A choice, not a switch** (maintainer, 2026-09-10: "When I move
        // means just that, same as when I leave means just that"). It used to
        // be a toggle with its own card shape, on the argument that it carried
        // a state the user had to be able to revoke. The maintainer's reading
        // is the simpler one: it is an end condition like the row below it,
        // chosen by tapping and no more revocable than `Until I leave` is —
        // you pick a different row. So it takes the same card, the same
        // wording, and the same place in the group, and goes with the group
        // when the cap comes inside `MIN_CAP`.
        if (offersMotionEnd) {
            EndChoiceRowWithHelp(
                label = stringResource(R.string.main_until_i_move),
                onClick = onChooseMotionEnd,
                enabled = locationRowsEnabled && !committing,
                onHelp = { openHelp = EndHelp.MOVE.name },
            )
        }
        if (tracksDeparture) {
            EndChoiceRowWithHelp(
                label = stringResource(R.string.main_until_i_leave),
                onClick = onChooseDeparture,
                enabled = locationRowsEnabled && !committing,
                onHelp = { openHelp = EndHelp.LEAVE.name },
            )
        }

        // Above nothing in particular here — unlike the sheet, there is no
        // bottom-most control for a growing message to push off screen — but
        // still beside the rows that produced it, which is where the tap was.
        if (failed || partial) {
            Text(
                text = if (partial) stringResource(R.string.failure_exit_stayed_armed) else failureText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        help?.let { open ->
            EndHelpCard(
                title = stringResource(open.title),
                body = stringResource(open.body(departureUsesWifi, departureUsesArea)),
                onClose = { openHelp = null },
            )
        }
    }
}

/**
 * The two end conditions that need a word of explanation, and the card each
 * opens.
 *
 * `Until time` takes none: a clock time explains itself. These two are named
 * after what the user does, not what the phone measures, so the card is where
 * the scale difference gets stated — another room against out for the day.
 */
private enum class EndHelp(@StringRes val title: Int) {
    MOVE(R.string.main_until_i_move),
    LEAVE(R.string.main_until_i_leave),
}

/**
 * The card's wording, which for `Until I leave` depends on what this snooze
 * is actually watching.
 *
 * Both signals absent is the pre-arm and still-settling case, not a third
 * kind of departure, so it reads as the full sentence rather than naming
 * neither — a snooze with neither does not offer the row at all
 * ([EndChoiceUiState.tracksDeparture]).
 */
@StringRes
private fun EndHelp.body(usesWifi: Boolean, usesArea: Boolean): Int = when (this) {
    EndHelp.MOVE -> R.string.end_help_move_body
    EndHelp.LEAVE -> when {
        usesWifi && !usesArea -> R.string.end_help_leave_body_wifi
        usesArea && !usesWifi -> R.string.end_help_leave_body_area
        else -> R.string.end_help_leave_body
    }
}

/**
 * One help card, as its own window.
 *
 * A thin wrapper over [EndHelpCardContent], which is the whole of what the
 * card draws. The split is so a screenshot test can record the wording: a
 * dialog is its own window and this suite's capture draws the activity's
 * `decorView`, so a snapshot taken with an `AlertDialog` open records the
 * screen behind it — a picture that looks fine and shows none of the copy it
 * exists to show, which is this suite's own false-pass failure mode.
 *
 * **[BasicAlertDialog], not `AlertDialog` and not a bare `Dialog`.**
 * `AlertDialog` takes its title, text and button as separate slots, so there is
 * no single composable to hand a test — which is the whole reason this split
 * exists. A bare `Dialog` gives that, and silently drops three things
 * `AlertDialog` was doing: the 280–560dp width range, the dialog pane
 * semantics TalkBack announces the modal with, and a text slot measured after
 * the buttons so a long body cannot push the action off a short screen. Codex
 * found all three on PR #265, one per round, which is what a mechanism rather
 * than three bugs looks like. [BasicAlertDialog] is the component for exactly
 * this case: arbitrary content, with the width range and the pane semantics
 * kept. The third is layout of this card's own making and is handled below.
 *
 * **It narrows the class rather than deleting it.** A fourth round asked for
 * heading semantics on the title, which no wrapper can supply: what
 * [BasicAlertDialog] restores is the *container* — the window's width and the
 * pane it announces — while everything a slot used to carry is now this card's
 * to state. The title below does; anything added beside it has to as well.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EndHelpCard(title: String, body: String, onClose: () -> Unit) {
    // The card hosts its own pinch, and its content is wrapped: a dialog is
    // its own window, so the theme's scaled density does not reach it and the
    // text would come out at the system size whatever the user chose (Codex,
    // PR #217, on the rationale dialog).
    BasicAlertDialog(onDismissRequest = onClose) {
        Box(modifier = Modifier.pinchFontSizeHost()) {
            FontSizeWindow { EndHelpCardContent(title = title, body = body, onClose = onClose) }
        }
    }
}

/**
 * What a help card says, drawn in whatever window the caller gives it.
 *
 * `internal` so a screenshot test can record it directly; [EndHelpCard] is
 * the only production caller and puts it in a dialog window.
 *
 * **Carries the width range itself rather than leaning on the window's.**
 * [BasicAlertDialog] applies the same one, so in production this is redundant
 * — but the recorded image is taken without that wrapper, and a constraint
 * only the wrapper held would leave the snapshot showing a width the product
 * never draws. (At the recorded screen width the cap does not bind either way;
 * what this buys is that it cannot silently stop being there.)
 */
@Composable
internal fun EndHelpCardContent(title: String, body: String, onClose: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.sizeIn(minWidth = DIALOG_MIN_WIDTH, maxWidth = DIALOG_MAX_WIDTH),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // **Weighted and scrolling, so the way out is measured first.**
            // An unweighted body is measured before the row below it and can
            // take the whole window: at a large system font compounded with
            // the app's own 160% setting, or in short landscape, `Close` was
            // left clipped or measured to zero — a modal with no visible way
            // out (Codex, PR #265). `fill = false` keeps a short card short;
            // the scroll is what a long one does with the space it is given.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // **A heading, so TalkBack's heading navigation finds it.**
                // Not restoring something `AlertDialog` was doing — Material's
                // title slot styles and pads, it does not mark a heading — so
                // this is a small improvement on what the card had before,
                // taken because a modal's one title is exactly what heading
                // navigation is for (Codex, PR #265).
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

/**
 * Material's own dialog width range, which [BasicAlertDialog] applies and
 * [EndHelpCardContent] repeats so the recorded image carries it too.
 *
 * Named here because Material does not export them.
 */
private val DIALOG_MIN_WIDTH = 280.dp
private val DIALOG_MAX_WIDTH = 560.dp

/**
 * A committing row with a `?` beside it.
 *
 * Beside, not inside: the card answers the tap over its whole surface, and a
 * second target carved out of it would be a mis-tap on the screen where a
 * snooze is being started. This is the shape the time row already uses for its
 * steppers — one card weighted against small buttons in the same row — so
 * TalkBack names each, and the two event rows line up with the `-` and `+`
 * above them.
 */
@Composable
private fun EndChoiceRowWithHelp(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    onHelp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EndChoiceRow(
            label = label,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        EndHelpButton(
            description = stringResource(R.string.end_help_description, label),
            onClick = onHelp,
        )
    }
}

/**
 * The `?` beside an end-condition row.
 *
 * **Enabled even when the row is not.** A row held by a missing location grant
 * is exactly the one a user wants explained, and the card explains what the
 * row does rather than committing anything, so nothing about a refused tap
 * applies to it.
 *
 * The mark is drawn, but the name announced is the whole phrase: "question
 * mark" tells a screen-reader user nothing about which row it belongs to.
 */
@Composable
private fun EndHelpButton(description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_help),
            contentDescription = null,
            tint = LocalContentColor.current,
            modifier = Modifier.size(20.dp),
        )
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
        // A row that will not take a tap looks like one — the steppers beside
        // it already do, through `OutlinedButton`, and a card that ignores a
        // tap while looking live reads as the app having missed it (Codex,
        // PR #257). Material's disabled-content alpha, over the whole card
        // so the outlined exit dims the same way as a filled row.
        modifier = modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
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
    /** [EndChoiceController.commitPartial], for the line beside the rows. */
    val partial: Boolean = false,
    /**
     * Whether this snooze can end on a departure at all. False on a
     * duration-only snooze, where `Until I leave` would name something nothing
     * is watching for (`TrackingMode.DURATION_ONLY`) — the row is dropped
     * rather than disabled, since a grayed control invites a tap that can
     * never work.
     */
    val tracksDeparture: Boolean = true,
    /**
     * What this snooze's departure is actually watching — the anchor's Wi-Fi,
     * the area around it, or both — which is what `Until I leave`'s card
     * names (SPEC.md §4.4). An anchor can have one without the other: a fix
     * too vague to test against leaves the SSID doing the work, and a capture
     * with no network leaves the radius doing it.
     *
     * Both true where there is no anchor to ask — an offer to start, or a
     * capture still settling — which is the ordinary case and the sentence
     * the card has always read.
     */
    val departureUsesWifi: Boolean = true,
    val departureUsesArea: Boolean = true,
    /**
     * Whether a tap on these rows starts a snooze rather than refining one —
     * the idle screen's offer (SPEC.md §4.4). The rows are drawn the same and
     * so are the steppers, which move the time row on both screens and arm
     * nothing; what changes is only where a tap on a *row* goes.
     */
    val startsASnooze: Boolean = false,
    /**
     * Whether a tap on the offer to start's `Until I move` or `Until I leave`
     * can go out now. A tap on either arms on the location reading warmed
     * before it, never a lookup made during it (SPEC.md §6.9), so until that
     * reading exists those two rows are drawn inert rather than letting a
     * tap fall through to the lookup — a frame or so after a start, before
     * the post-first-frame refresh lands. The time row, the meeting rows and
     * the steppers need no reading and are never held (Codex, PR #257) —
     * the steppers because they arm nothing at all now, only moving the time
     * the row then commits. Always true for a refinement, which arms nothing.
     */
    val locationArmable: Boolean = true,
    /**
     * Which snooze this offer was drawn for — its `startedAt` — or null for
     * an offer to start. Carried so a tap can be matched against the offer
     * the controller holds *now*: the record observer can move it onto a
     * snooze the tile just armed before the frame that drew these rows is
     * replaced, and a tap in that gap must not send the drawn time as a
     * refinement of a snooze it was never offered over (Codex, PR #256).
     */
    val offerFor: Instant? = null,
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
    partial: Boolean = false,
    format: (Instant) -> String,
    /**
     * Whether this build can track a departure at all — the flavor constant,
     * injectable so a test can ask both answers. Only the offer to start
     * reads it; a running snooze's own mode answers for its rows.
     */
    buildTracksDeparture: Boolean = app.snoozemo.presence.PRESENCE_TRACKS_DEPARTURE,
): EndChoiceUiState? {
    if (condition == null) return null
    // **An offer to start, named by having no snooze to name** (SPEC.md §4.4).
    // It fails closed the other way round: a record under it means a snooze
    // arrived while the offer stood — the tile, say — and drawing it would
    // solicit a tap the service answers `GONE`. The host's next record read
    // replaces it with the running snooze's own rows.
    if (offerFor == null) {
        if (record != null) return null
        return EndChoiceUiState(
            condition = condition,
            formattedTime = format(condition.endsAt),
            // Bounded by the offer's own ceiling — the cap a snooze started
            // now would carry — rather than a record's, on the same rules
            // the running rows use, so the same meeting qualifies on both.
            meetings = MeetingEnd.offersBefore(condition.ceiling, meetingEnds, now, limit = MEETING_ROWS)
                .map { MeetingChoice(at = it, label = format(it)) },
            committing = committing,
            failed = failed,
            partial = partial,
            // `Until I leave` too (maintainer, 2026-09-11): every row the
            // running screen has is a way to start, and this one starts the
            // plain arm the pinned `Snooze` makes. Withheld only where this
            // build cannot track a departure at all, as the sheet withholds
            // it — a row naming an end nothing will watch for.
            tracksDeparture = buildTracksDeparture,
            startsASnooze = true,
            offerFor = null,
        )
    }
    val offerRecord = record?.takeIf { it.startedAt == offerFor }
    val signals = departureSignals(offerRecord?.anchor, offerRecord?.mode)
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
        partial = partial,
        // The same predicate the service honors the tap with, so the row is
        // never offered where the restore would be declined — and never
        // withheld where it would be taken.
        //
        // **`mode`, deliberately, not `effectiveMode`.** This asks whether the
        // machinery *can* watch for a departure, not whether this snooze
        // currently ends on one — and those part company exactly when the row
        // matters most. A snooze the user narrowed to its timer has
        // `endsOnDeparture` false and is precisely the one whose `Until I
        // leave` row is the way back; asking `effectiveMode` would withhold the
        // row from every snooze that needs it and leave the choice one-way
        // again. The same distinction holds for `departureSignals` above: the
        // help card describes what leaving *would* do if chosen.
        tracksDeparture = offerRecord?.mode?.tracksDeparture == true,
        // The live mode and the captured anchor together — neither answers it
        // alone, and the anchor alone goes stale the moment a degradation
        // lowers the mode under it (Codex, PR #261). See [departureSignals].
        departureUsesWifi = signals.usesWifi,
        departureUsesArea = signals.usesArea,
        offerFor = offerFor,
    )
}

/** What a departure is watching, for `Until I leave`'s card (SPEC.md §4.4). */
internal data class DepartureSignals(val usesWifi: Boolean, val usesArea: Boolean)

/**
 * Which of the two signals a departure is watching **right now**.
 *
 * Neither source answers this alone, which is the whole reason this is one
 * function rather than a field on either (Codex, PR #261, twice in the same
 * mechanism):
 *
 * - **[TrackingMode] does not say which signals.** `supportedModes` enables
 *   `FULL` from a usable fix alone and `WIFI_ONLY` from an SSID alone, so the
 *   mode names a capability tier rather than a pair.
 * - **[Anchor] does not say what is still working.** It is captured once and
 *   never rewritten, so an anchor that had both still reads as both after
 *   location stops producing fixes and [SnoozeController.modeFor] has moved
 *   the snooze to `WIFI_ONLY`.
 *
 * So each answers the half it is entitled to: the live mode says whether the
 * area is still being tested — `FULL` is the only mode that tests it, and
 * `FULL` already implies a usable fix — and the anchor says whether there is
 * a network to leave at all.
 *
 * `SETTLING`, a missing record and an anchor with neither all read as both:
 * nothing is determined yet, and a card that narrowed during the ~10 s
 * capture would flip its sentence as the fix landed. A snooze that really
 * has neither is `DURATION_ONLY`, and its row is dropped before this card
 * exists.
 */
internal fun departureSignals(anchor: Anchor?, mode: TrackingMode?): DepartureSignals {
    val undetermined = DepartureSignals(usesWifi = true, usesArea = true)
    if (anchor == null || mode == null || mode == TrackingMode.SETTLING) return undetermined
    val wifi = anchor.ssid != null
    val area = mode == TrackingMode.FULL
    return if (!wifi && !area) undetermined else DepartureSignals(usesWifi = wifi, usesArea = area)
}

/**
 * Whether the screen offers `Until I move` at all (SPEC.md §4.4).
 *
 * A yes/no rather than a state: the row is a choice like `Until I leave`, not
 * a switch, so there is nothing about the running snooze for it to draw
 * (maintainer, 2026-09-10).
 *
 * Kept apart from [EndChoiceUiState] because it answers a different question.
 * That state asks "is there a *time* left to choose", and is rebuilt as the
 * clock moves; this asks only about the build and the phone — can the
 * hardware answer, can this build hold the service it needs — which does
 * not. It no longer asks whether a snooze is running either: the idle screen
 * offers the row as a way to start one (maintainer, 2026-09-10), so whether
 * there is a group to draw it in is the offer's question, not this one's.
 * The screen draws the row inside the group, so it is withheld with the rest
 * once the cap comes inside `MIN_CAP`.
 *
 * @param deviceHasMotionSensor whether the phone has one at all; false offers
 *   nothing rather than a row the service would roll straight back.
 *   **A lambda, and asked last**, because answering it was once a
 *   `SensorManager` lookup made during composition on every first frame,
 *   including where the build could never offer the row anyway (Codex, PR #252). The
 *   host warms the answer at startup now, but the order still keeps a build
 *   with no foreground service from asking.
 */
internal fun offersMotionEnd(
    deviceHasMotionSensor: () -> Boolean,
): Boolean = motionEndUnavailability(deviceHasMotionSensor) == null

/**
 * Why this build or this phone cannot offer `When I move` at all, or null
 * when it can (SPEC.md §4.4).
 *
 * **Separate from [offersMotionEnd] so the reason can be said, not only
 * acted on** (maintainer, 2026-09-10). A row that is simply absent tells the
 * user nothing: "this build does not have the feature" and "this phone cannot
 * do it" look identical on screen, and neither reached the log that exists to
 * explain a snooze after the fact. This is the one place either answer is
 * decided, so the screen and the log cannot drift apart about it.
 *
 * The log's caller is `DebugLogging`, which says it with the run context. It
 * lives here, beside the row it governs and beside the flavor constant it
 * reads, rather than moving down to the layer that logs it: both callers are
 * in this module, and `buildHoldsForegroundService` is already a
 * flavor-scoped `ui` file.
 *
 * A build with no foreground service cannot keep the process alive to hear a
 * one-shot sensor, so the promotion is refused and the exit never fires. The
 * sensor question is asked second and through a lambda, so a build that can
 * never offer the row never asks it.
 *
 * The strings are fixed and name no device, so they are safe for a log the
 * user shares (AGENTS.md, *Privacy*).
 */
internal fun motionEndUnavailability(deviceHasMotionSensor: () -> Boolean): String? = when {
    !buildHoldsForegroundService -> "this build holds no foreground service"
    !deviceHasMotionSensor() -> "this device has no significant-motion sensor"
    else -> null
}

/** Material 3's disabled-content alpha, which `OutlinedButton` applies to the steppers on its own. */
private const val DISABLED_ALPHA = 0.38f

/**
 * How many meeting ends the screen offers.
 *
 * Two rather than the notification's one: a screen has room for the choice a
 * card has to pick between, and "the meeting after this one" is the common
 * answer when the current one is nearly over.
 */
internal const val MEETING_ROWS = 2
