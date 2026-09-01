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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.core.CalendarPermission
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.ZenRuleState

/**
 * The interstitial: what each capability Snoozemo can use needs, and the one
 * tap that fixes it while something is still missing.
 *
 * Reached two ways (`SPEC.md` §4.2's screen split) — automatically the first
 * time Do Not Disturb access is read as missing, since nothing on [MainScreen]
 * can arm without it, and any time after that from [SettingsScreen]'s
 * Permissions row. [onDone] returns to whichever of those brought the user
 * here; there is no requirement that every row be resolved first (D7,
 * "fail open" — a screen that cannot be left without granting everything is a
 * trap, not an onboarding flow).
 */
@Composable
fun PermissionsScreen(
    access: PolicyAccess?,
    notifications: NotificationPermission?,
    notificationsReachTheUser: Boolean,
    location: LocationPermission?,
    /**
     * Defaulted to unread, unlike the three above it. Every caller that has a
     * reading passes one; a caller that doesn't get one — a screenshot test
     * pinning a different row — renders the screen exactly as it looked before
     * this row existed, rather than having to state an absence it has no
     * opinion about.
     */
    calendar: CalendarPermission? = null,
    /**
     * Whether this build can ever end a snooze because the user left
     * (`PRESENCE_TRACKS_DEPARTURE`). The location row's status names that
     * capability, so on a build that does not have it the same sentence would
     * promise something the user cannot get — and, worse, invite a location
     * grant that buys them nothing (Codex, PR #171).
     *
     * A parameter rather than the constant read here, for the same reason
     * [EndConditionSheet] takes one: a screenshot test has to be able to render
     * both builds, and the flavor seam belongs at the call site.
     *
     * Defaults to true, so the only caller that has to think about it is the
     * one on a build where departure is unavailable.
     */
    tracksDeparture: Boolean = true,
    /**
     * The verified state of Snoozemo's own zen rule, or null while unread.
     *
     * Policy access is necessary for silencing the phone and not sufficient:
     * the rule can exist and be **switched off by the user in Settings**, which
     * Snoozemo deliberately does not undo (SPEC.md §5.1), or the platform can
     * have refused to create it. In both cases the grant is held and a snooze
     * still cannot silence anything — so a row that claimed the capability from
     * `access` alone would state a success the app cannot deliver, on the one
     * screen that does not also render `lastOutcome` to contradict it (Codex,
     * PR #171).
     *
     * Null leaves the capability pair to speak for the grant, which is the
     * right reading before any check has run.
     */
    ruleState: ZenRuleState? = null,
    settingsFailure: SetupRowId?,
    /**
     * Whether a crashed run is currently pinned (`SPEC.md` §4.6). On a cold
     * start with Do Not Disturb access missing, `MainActivity.applyAccess`
     * routes straight here — the actual first-landed screen in that case,
     * not [MainScreen] — so the banner has to be reachable from here too or
     * a crash from before that same cold start goes unseen until the user
     * finishes this screen and navigates back on their own (Codex, PR #89).
     */
    crashPending: Boolean = false,
    /** Whether the last debug-log share reached neither the clipboard nor the chooser. */
    shareFailed: Boolean = false,
    /** Whether the last Dismiss tap on the crash banner was refused by the file layer. */
    dismissFailed: Boolean = false,
    /** Whether a share is already running, disabling the banner's Share button. */
    sharing: Boolean = false,
    onAccessRow: () -> Unit,
    /**
     * Opens this app's own mode in system settings, where its off switch is —
     * the repair for [ZenRuleState.DISABLED], which the access row reports but
     * could not act on (Codex, PR #171). Defaulted so a screenshot test pinning
     * another row need not state an opinion; `MainActivity` passes
     * `openFilters`, the same route the Settings screen's Filters row uses.
     */
    onRuleRow: () -> Unit = {},
    onNotificationsRow: () -> Unit,
    onLocationRow: () -> Unit,
    onCalendarRow: () -> Unit = {},
    onDone: () -> Unit,
    onShareDebugLog: () -> Unit = {},
    onDismissCrash: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Outside the scroll, like every screen here — see MainScreen's
            // note on why safeDrawingPadding sits around the scroll rather
            // than inside it.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SnoozemoTitleRow(title = stringResource(R.string.permissions_title))
        // Same placement and reasoning as MainScreen's own — above
        // everything else, since this can be the actual first-landed screen
        // (Codex, PR #89, fresh evidence).
        if (crashPending) {
            CrashBanner(
                onShare = onShareDebugLog,
                onDismiss = onDismissCrash,
                shareFailed = shareFailed,
                dismissFailed = dismissFailed,
                sharing = sharing,
            )
        }
        // Nothing at all until access has been read, rather than a guess in
        // either direction: the wrong guess either tells a user who allowed
        // access that they haven't, or offers to arm something that can't.
        // A granted row needs the rule state as well as the grant. `access`
        // publishes the moment it is read, while the rule check answers on a
        // background thread after it — so rendering on the grant alone would
        // claim "Snoozes can silence your phone" for that window and then take
        // it back if the rule turns out DISABLED or FAILED. Same discipline as
        // every other row here: briefly absent rather than briefly wrong
        // (Codex, PR #171). A missing grant needs no rule state — there is
        // nothing for a rule to be in the way of — so that row is immediate.
        access?.takeUnless { it == PolicyAccess.GRANTED && ruleState == null }?.let {
            val granted = it == PolicyAccess.GRANTED
            SetupRow(
                title = stringResource(R.string.setup_dnd_title),
                status = stringResource(
                    when {
                        // The grant is held but the rule cannot silence
                        // anything, so the capability claim would be false.
                        // These two lines are the ones MainScreen already
                        // shows for the same states, rather than new copy
                        // saying the same thing differently.
                        granted && ruleState == ZenRuleState.DISABLED -> R.string.rule_disabled
                        granted && ruleState == ZenRuleState.FAILED -> R.string.rule_failed
                        // The rule check found access gone after the read that
                        // said it was there — revoked in between. Access is
                        // what is missing, whatever the older read said, so
                        // this reads as the missing-grant state rather than
                        // claiming a capability nothing can deliver (Codex,
                        // PR #171).
                        granted && ruleState == ZenRuleState.MISSING_ACCESS -> R.string.setup_dnd_missing
                        granted -> R.string.setup_dnd_allowed
                        else -> R.string.setup_dnd_missing
                    },
                ),
                // Same `Allow`/`Allowed` wording as every other row, even
                // though this one is a Settings toggle with no in-app dialog
                // and no result callback (SPEC.md §5.2) — the mechanism
                // differs but what the user is doing doesn't need its own verb.
                //
                // A disabled rule keeps the button rather than losing it with
                // the grant: the row reports work the user can still do, and
                // §5.2 says such a row carries a button that does it. Same
                // verb again, and it reads correctly on the screen it reaches
                // — the mode's own switch (maintainer, 2026-09-01). `FAILED`
                // gets no button: nothing there is the user's to fix.
                action = stringResource(R.string.setup_action_allow)
                    .takeUnless {
                        granted &&
                            ruleState != ZenRuleState.DISABLED &&
                            ruleState != ZenRuleState.MISSING_ACCESS
                    },
                onAction = if (granted && ruleState == ZenRuleState.DISABLED) onRuleRow else onAccessRow,
                // MISSING_ACCESS keeps the access route, not the rule one:
                // what the user needs is the grant back.
                // FILTERS as well as DND: in the disabled state this button
                // opens the mode screen, and `openFilters` reports a refusal
                // under its own row id — which belongs here when this row is
                // the one that sent the user there, not on a Settings row they
                // never touched.
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf {
                        settingsFailure == SetupRowId.DND ||
                            (granted && ruleState == ZenRuleState.DISABLED && settingsFailure == SetupRowId.FILTERS)
                    },
            )
        }
        // Same discipline, same reason: unread is not "denied". Read after the
        // first frame like everything else here, so this is briefly absent
        // rather than briefly wrong.
        notifications?.let {
            // Allowed is necessary and not sufficient. The permission can be
            // held while the app is switched off in Settings or either channel
            // is blocked, and the system then drops every post — so the row may
            // only say `Allowed`, and only drop its button, when a message
            // would actually arrive (flagged by Codex on PR #18).
            val working = it == NotificationPermission.GRANTED && notificationsReachTheUser
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
                onAction = onNotificationsRow,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.NOTIFICATIONS },
            )
        }
        // Same null-until-read discipline again. Missing this permission never
        // blocks a snooze (SPEC.md §3.6's fallback ladder), so the row states a
        // gap in what is tracked, not a broken product.
        location?.let { state ->
            val granted = state == LocationPermission.GRANTED
            SetupRow(
                title = stringResource(R.string.setup_location_title),
                status = stringResource(
                    when {
                        // Says the same thing whether or not the permission is
                        // held, because the permission is not what is missing.
                        !tracksDeparture -> R.string.setup_location_no_departure
                        granted -> R.string.setup_location_allowed
                        else -> R.string.setup_location_missing
                    },
                ),
                // No offer either: granting would change nothing here, and a
                // button is a promise that it would.
                action = stringResource(R.string.setup_action_allow)
                    .takeUnless { granted || !tracksDeparture },
                onAction = onLocationRow,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.LOCATION },
            )
        }
        // Last of the four, and deliberately: it is the only one whose absence
        // costs a single notification action rather than a whole capability
        // (SPEC.md §4.3), so it reads as the smallest thing on the screen.
        // Same null-until-read discipline as the rows above it.
        calendar?.let { state ->
            val granted = state == CalendarPermission.GRANTED
            SetupRow(
                title = stringResource(R.string.setup_calendar_title),
                status = stringResource(
                    if (granted) R.string.setup_calendar_allowed else R.string.setup_calendar_missing,
                ),
                action = stringResource(R.string.setup_action_allow).takeUnless { granted },
                onAction = onCalendarRow,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.CALENDAR },
            )
        }
        // Always present and always enabled: nothing here is mandatory, so the
        // way out can never depend on a row above being resolved first.
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.permissions_done))
        }
    }
}
