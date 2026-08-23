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
import app.snoozemo.R
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess

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
    settingsFailure: SetupRowId?,
    onAccessRow: () -> Unit,
    onNotificationsRow: () -> Unit,
    onLocationRow: () -> Unit,
    onDone: () -> Unit,
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
        Text(
            text = stringResource(R.string.permissions_screen_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        // Nothing at all until access has been read, rather than a guess in
        // either direction: the wrong guess either tells a user who granted
        // access that they haven't, or offers to arm something that can't.
        access?.let {
            val granted = it == PolicyAccess.GRANTED
            SetupRow(
                title = stringResource(R.string.setup_dnd_title),
                status = stringResource(
                    if (granted) R.string.setup_dnd_granted else R.string.setup_dnd_missing,
                ),
                // `Grant` rather than `Allow`: this one is a Settings toggle
                // with no in-app dialog and no result callback (SPEC.md §5.2),
                // and the pair of verbs is what is left of saying so now that
                // the row no longer spends a line on the mechanism.
                action = stringResource(R.string.setup_action_grant).takeUnless { granted },
                onAction = onAccessRow,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.DND },
            )
        }
        // Same discipline, same reason: unread is not "denied". Read after the
        // first frame like everything else here, so this is briefly absent
        // rather than briefly wrong.
        notifications?.let {
            // Granted is necessary and not sufficient. The permission can be
            // held while the app is switched off in Settings or either channel
            // is blocked, and the system then drops every post — so the row may
            // only say `Allowed`, and only drop its button, when a message
            // would actually arrive (flagged by Codex on PR #18).
            val working = it == NotificationPermission.GRANTED && notificationsReachTheUser
            SetupRow(
                title = stringResource(R.string.setup_notifications_title),
                status = stringResource(
                    if (working) {
                        R.string.setup_notifications_granted
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
                    if (granted) R.string.setup_location_granted else R.string.setup_location_missing,
                ),
                action = stringResource(R.string.setup_action_allow).takeUnless { granted },
                onAction = onLocationRow,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.LOCATION },
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
