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
        // The rows themselves live in `PermissionRows`, shared with the
        // welcome flow's cards (SPEC.md §4.2). Both screens offer the same
        // grants, and every judgment in these rows — unread is not denied, a
        // grant is necessary and not sufficient, no row offers an action the
        // platform will ignore — has been got wrong once already; two copies
        // would be two places to fix the next one and one place to forget.
        PermissionRows.Access(
            access = access,
            ruleState = ruleState,
            settingsFailure = settingsFailure,
            onAction = onAccessRow,
            onRuleRow = onRuleRow,
        )
        PermissionRows.Notifications(
            notifications = notifications,
            reachTheUser = notificationsReachTheUser,
            settingsFailure = settingsFailure,
            onAction = onNotificationsRow,
        )
        PermissionRows.Location(
            location = location,
            settingsFailure = settingsFailure,
            onAction = onLocationRow,
            tracksDeparture = tracksDeparture,
        )
        PermissionRows.Calendar(
            calendar = calendar,
            settingsFailure = settingsFailure,
            onAction = onCalendarRow,
        )
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
