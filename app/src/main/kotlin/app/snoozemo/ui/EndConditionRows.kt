package app.snoozemo.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
     * What a refused tap says beside the rows. A refinement that was declined
     * could not set the end time; a row that offered to *start* a snooze and
     * was refused has nothing running behind it, and says so.
     */
    failureText: String = stringResource(R.string.failure_could_not_set_end),
    modifier: Modifier = Modifier,
) {
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
            EndChoiceRow(
                label = stringResource(R.string.main_until_i_move),
                onClick = onChooseMotionEnd,
                enabled = locationRowsEnabled && !committing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (tracksDeparture) {
            EndChoiceRow(
                label = stringResource(R.string.main_until_i_leave),
                onClick = onChooseDeparture,
                enabled = locationRowsEnabled && !committing,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Above nothing in particular here — unlike the sheet, there is no
        // bottom-most control for a growing message to push off screen — but
        // still beside the rows that produced it, which is where the tap was.
        if (failed) {
            Text(
                text = failureText,
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
    /**
     * Whether this snooze can end on a departure at all. False on a
     * duration-only snooze, where `Until I leave` would name something nothing
     * is watching for (`TrackingMode.DURATION_ONLY`) — the row is dropped
     * rather than disabled, since a grayed control invites a tap that can
     * never work.
     */
    val tracksDeparture: Boolean = true,
    /**
     * Whether a tap on these rows starts a snooze rather than refining one —
     * the idle screen's offer (SPEC.md §4.4). The rows are drawn the same;
     * what changes is where a tap goes, and that `−`/`+` arm at the stepped
     * time rather than stepping the row.
     */
    val startsASnooze: Boolean = false,
    /**
     * Whether a tap on the offer to start's `Until I move` or `Until I leave`
     * can go out now. A tap on either arms on the location reading warmed
     * before it, never a lookup made during it (SPEC.md §6.9), so until that
     * reading exists those two rows are drawn inert rather than letting a
     * tap fall through to the lookup — a frame or so after a start, before
     * the post-first-frame refresh lands. The time row, the meeting rows and
     * the steppers arm without any reading and are never held (Codex, PR
     * #257). Always true for a refinement, which arms nothing.
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
        offerFor = offerFor,
    )
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
 *   `direct` included, where the answer cannot matter (Codex, PR #252). The
 *   host warms the answer at startup now, but the order still keeps the
 *   flavor that can never offer the row from asking.
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
 * in this module, and `buildHoldsForegroundService` is already a `ui` file
 * per flavor.
 *
 * A build with no foreground service cannot keep the process alive to hear a
 * one-shot sensor, so the promotion is refused and the exit never fires;
 * `direct` was excluded by accident until the tracking-mode gate came off, and
 * is excluded on purpose now. The sensor question is asked second and through
 * a lambda, so the flavor that can never offer the row never asks it.
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
