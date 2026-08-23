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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.core.PolicyAccess

/**
 * The home screen: the Arm/Release control the tile mirrors, plus whatever
 * stands between the user and a working snooze right now.
 *
 * Leaner than the old `DebugScreen` on purpose (`TODO.md` Phase 4, "Split the
 * permission-setup rows from the Arm/Release view") — the setup rows
 * themselves live on [PermissionsScreen], reached from the banner below or
 * from [SettingsScreen]. This screen states only what is *missing*, not how to
 * fix every capability in place.
 */
@Composable
fun MainScreen(
    access: PolicyAccess?,
    tileAdded: Boolean?,
    tileBannerDismissed: Boolean,
    snoozing: Boolean?,
    lastOutcome: String?,
    // Only SetupRowId.TILE is ever relevant here — this banner has no other
    // capability to fail — but the type is shared with the other screens'
    // failure-routing rather than narrowed to a Boolean, so a caller reading
    // this signature can see at a glance it's the same failure state as
    // everywhere else, not a screen-local one.
    settingsFailure: SetupRowId? = null,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddTile: () -> Unit,
    onDismissTileBanner: () -> Unit,
    onArm: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Outside the scroll, so the whole column — not just its resting
            // position — stays clear of the status bar, the navigation bar and
            // any display cutout. Inside the scroll it would only pad the
            // content, leaving a row to slide under the status bar as soon as
            // the user scrolled.
            .safeDrawingPadding()
            // Scrolls, and this is not cosmetic. Manual exit is "always
            // available, always instant" (SPEC.md §7), and a user who cannot
            // reach it because their font is large or their window is short
            // has lost the exit from this screen entirely.
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        // The one required capability, stated as a problem rather than listed
        // as a row: nothing on this screen can arm without it, so it is a
        // banner, not a setup row waiting its turn beside the others on
        // PermissionsScreen. Null-guarded like every other reading here —
        // unread is not "missing".
        if (access != null && access != PolicyAccess.GRANTED) {
            RequiredPermissionBanner(onFix = onOpenPermissions)
        }
        // Above the buttons and louder than a row, because the screen leads
        // with the tile rather than offering a symmetrical choice (SPEC.md
        // §4.2): the tile is easier and is where people already go to silence
        // a phone. Dismissible for good, which only works because the
        // permanent tile row on SettingsScreen outlives it.
        if (tileAdded == false && !tileBannerDismissed) {
            TileBanner(
                onAdd = onAddTile,
                onDismiss = onDismissTileBanner,
                failure = stringResource(R.string.failure_could_not_add_tile)
                    .takeIf { settingsFailure == SetupRowId.TILE },
            )
        }
        // TODO(TODO.md Phase 3/4): show the current snooze's place and
        // countdown here once presence tracking lands — today the ongoing
        // notification is the only place that state renders.
        //
        // Gated behind access being granted, same as the old DebugScreen —
        // not a design call this PR makes. TODO.md still tracks "how and when
        // to show Snooze/End snooze" as open (maintainer, 2026-08-23): both
        // buttons rendering only once access is granted keeps this screen's
        // change scoped to the split itself.
        if (access == PolicyAccess.GRANTED) {
            Button(
                onClick = onArm,
                // Disabled until the record has actually been read. Unknown
                // is not "nothing is running": offering to arm over a snooze
                // the screen hasn't read yet is how the user loses the
                // deadline they were promised. A button that is briefly
                // inert costs a tap; the other direction costs the cap.
                enabled = snoozing == false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.debug_arm))
            }
            // Deliberately always enabled, even when we believe nothing is
            // running. Manual exit is "always available, always instant"
            // (SPEC.md §7), and endSnooze is idempotent — so a stale belief
            // must never be what stops someone turning their phone back on.
            OutlinedButton(
                onClick = onRelease,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.debug_release))
            }
        }
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_screen_title))
        }
        lastOutcome?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The one thing this screen insists on: Do Not Disturb access, without which
 * nothing here can arm.
 *
 * An error-toned banner rather than a [SetupRow] — unlike the rows on
 * [PermissionsScreen], which state a capability and wait, this is the reason
 * the primary button on the screen the user is looking at is disabled right
 * now, so it reads as a problem rather than a checklist item.
 */
@Composable
private fun RequiredPermissionBanner(onFix: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.main_dnd_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.setup_dnd_missing),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onFix) {
                Text(stringResource(R.string.setup_action_grant))
            }
        }
    }
}
