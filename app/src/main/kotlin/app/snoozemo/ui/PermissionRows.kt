package app.snoozemo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.snoozemo.R
import app.snoozemo.core.CalendarPermission
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.ZenRuleState

/**
 * The capability rows themselves, so the two screens that offer a grant draw
 * the *same* row rather than each deciding what it says.
 *
 * [PermissionsScreen] is the recap; the welcome flow's cards each carry the row
 * for the thing that card just introduced (`SPEC.md` §4.2). Both need the same
 * three judgments, and every one of them has already been got wrong once:
 * unread is not denied and the row is briefly absent rather than briefly wrong;
 * a grant is necessary and not sufficient, so `Allowed` waits on the capability
 * actually working; and no row offers an action the platform will ignore. Two
 * copies of those would be two chances to fix a finding in only one of them —
 * which is the shape of bug this file exists to make impossible.
 *
 * Each row is null-safe on its own reading and renders nothing until it has
 * one, so a caller can hand over whatever it has read so far.
 */
object PermissionRows {

    /**
     * Do Not Disturb access, plus the state of the rule the grant is for.
     *
     * Policy access is necessary and not sufficient: the rule can exist and be
     * switched off by the user in Settings, which Snoozemo deliberately does
     * not undo (§5.1), or the platform can have refused to create it. Either
     * way the grant is held and a snooze still silences nothing, so the row
     * says so rather than claiming the capability from the grant alone.
     */
    @Composable
    fun Access(
        access: PolicyAccess?,
        settingsFailure: SetupRowId?,
        onAction: () -> Unit,
        ruleState: ZenRuleState? = null,
        onRuleRow: () -> Unit = onAction,
    ) {
        // A granted row waits for the rule check, which answers after the grant
        // reads: rendering on the grant alone would claim "Snoozes can silence
        // your phone" for that window and take it back if the rule turns out
        // DISABLED. A missing grant needs no rule state — there is nothing for
        // a rule to be in the way of — so that row is immediate.
        access?.takeUnless { it == PolicyAccess.GRANTED && ruleState == null }?.let {
            val granted = it == PolicyAccess.GRANTED
            SetupRow(
                title = stringResource(R.string.setup_dnd_title),
                status = stringResource(
                    when {
                        granted && ruleState == ZenRuleState.DISABLED -> R.string.rule_disabled
                        granted && ruleState == ZenRuleState.FAILED -> R.string.rule_failed
                        // The rule check found access gone after the read that
                        // said it was there. Access is what is missing,
                        // whatever the older read said.
                        granted && ruleState == ZenRuleState.MISSING_ACCESS -> R.string.setup_dnd_missing
                        granted -> R.string.setup_dnd_allowed
                        else -> R.string.setup_dnd_missing
                    },
                ),
                // A disabled rule keeps the button rather than losing it with
                // the grant: the row reports work the user can still do.
                // `FAILED` gets none — nothing there is theirs to fix.
                action = stringResource(R.string.setup_action_allow)
                    .takeUnless {
                        granted &&
                            ruleState != ZenRuleState.DISABLED &&
                            ruleState != ZenRuleState.MISSING_ACCESS
                    },
                onAction = if (granted && ruleState == ZenRuleState.DISABLED) onRuleRow else onAction,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf {
                        settingsFailure == SetupRowId.DND ||
                            (granted && ruleState == ZenRuleState.DISABLED && settingsFailure == SetupRowId.FILTERS)
                    },
            )
        }
    }

    /**
     * Notifications.
     *
     * Allowed is necessary and not sufficient here too: the permission can be
     * held while the app is switched off in Settings or either channel is
     * blocked, and the system then drops every post — so the row may only say
     * `Allowed`, and only drop its button, when a message would actually
     * arrive.
     */
    @Composable
    fun Notifications(
        notifications: NotificationPermission?,
        reachTheUser: Boolean,
        settingsFailure: SetupRowId?,
        onAction: () -> Unit,
    ) {
        notifications?.let {
            val working = it == NotificationPermission.GRANTED && reachTheUser
            SetupRow(
                title = stringResource(R.string.setup_notifications_title),
                status = stringResource(
                    if (working) {
                        R.string.setup_notifications_allowed
                    } else {
                        R.string.setup_notifications_missing
                    },
                ),
                action = stringResource(R.string.setup_action_allow).takeUnless { working },
                onAction = onAction,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.NOTIFICATIONS },
            )
        }
    }

    /**
     * Location.
     *
     * Missing this never blocks a snooze (§3.6's fallback ladder), so the row
     * states a gap in what is tracked rather than a broken product. Where the
     * build cannot track departure at all it says the same thing whether or not
     * the permission is held, and offers nothing — granting would change
     * nothing, and a button is a promise that it would.
     */
    @Composable
    fun Location(
        location: LocationPermission?,
        settingsFailure: SetupRowId?,
        onAction: () -> Unit,
        tracksDeparture: Boolean = true,
    ) {
        location?.let { state ->
            val granted = state == LocationPermission.GRANTED
            SetupRow(
                title = stringResource(R.string.setup_location_title),
                status = stringResource(
                    when {
                        !tracksDeparture -> R.string.setup_location_no_departure
                        granted -> R.string.setup_location_allowed
                        else -> R.string.setup_location_missing
                    },
                ),
                action = stringResource(R.string.setup_action_allow)
                    .takeUnless { granted || !tracksDeparture },
                onAction = onAction,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.LOCATION },
            )
        }
    }

    /**
     * The calendar, whose absence costs a single notification action rather
     * than a whole capability (§4.3) — so it reads as the smallest row.
     */
    @Composable
    fun Calendar(
        calendar: CalendarPermission?,
        settingsFailure: SetupRowId?,
        onAction: () -> Unit,
    ) {
        calendar?.let { state ->
            val granted = state == CalendarPermission.GRANTED
            SetupRow(
                title = stringResource(R.string.setup_calendar_title),
                status = stringResource(
                    if (granted) R.string.setup_calendar_allowed else R.string.setup_calendar_missing,
                ),
                action = stringResource(R.string.setup_action_allow).takeUnless { granted },
                onAction = onAction,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.CALENDAR },
            )
        }
    }

    /**
     * The Quick Settings tile.
     *
     * Not a permission, and deliberately not on [PermissionsScreen] — the
     * request is a button the user tapped rather than a launch-time prompt
     * (§10), and its standing home is `SettingsScreen`'s permanent row. The
     * welcome flow's tile card carries it because that card is where the tile
     * is introduced.
     */
    @Composable
    fun Tile(
        tileAdded: Boolean?,
        settingsFailure: SetupRowId?,
        onAction: () -> Unit,
    ) {
        tileAdded?.let { added ->
            SetupRow(
                title = stringResource(R.string.setup_tile_title),
                status = stringResource(
                    if (added) R.string.setup_tile_added else R.string.setup_tile_missing,
                ),
                action = stringResource(R.string.setup_action_add).takeUnless { added },
                onAction = onAction,
                failure = stringResource(R.string.failure_could_not_add_tile)
                    .takeIf { settingsFailure == SetupRowId.TILE },
            )
        }
    }
}
