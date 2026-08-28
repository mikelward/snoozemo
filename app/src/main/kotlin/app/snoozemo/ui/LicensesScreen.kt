package app.snoozemo.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.snoozemo.R
import app.snoozemo.core.SnoozeDebugLog
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries

/**
 * Open-source attribution, reached from the Settings foot: every third-party
 * component bundled in this build, and the license each ships under.
 *
 * The list is read from the committed `res/raw/aboutlibraries.json` —
 * regenerated with `./gradlew :app:exportBundledLicenses`, which writes one per
 * flavor, since `play` bundles Play's update library and `direct` does not
 * (`SPEC.md` §3.4). Committed rather than generated because the AboutLibraries
 * plugin can't wire the resource in under AGP 9; see app/build.gradle.kts.
 */
@Composable
internal fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // rememberLibraries parses the bundled JSON off the composition thread and
    // swaps it in when ready, so the screen appears at once with its title and
    // fills in — principle 5, rather than holding the frame on the parse.
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    LicensesContent(
        libraries = libraries,
        onBack = onBack,
        // Reports whether the link actually opened. A device with no browser
        // is a real case, and swallowing the failure would leave the tap
        // looking like it did nothing — the silent-wrong outcome principle 2
        // exists to rule out. The dialog says so instead.
        onOpenLicenseUrl = { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                true
            } catch (exception: ActivityNotFoundException) {
                // The URL is a public SPDX address, not user data, but it is
                // also not worth logging: the failure is fully described by
                // "nothing here can open a web link", and that is what the
                // reader needs.
                SnoozeDebugLog.failure(exception, "license link: no activity to open it")
                false
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun LicensesContent(
    libraries: Libs?,
    onBack: () -> Unit = {},
    /** Opens [url], returning whether anything on the device could. */
    onOpenLicenseUrl: (String) -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    // The tapped component's stable id, if any — its details fill the dialog
    // below. Saved (not a plain remember) so an open dialog survives rotation
    // and process death; resolved back to the library once the list is loaded.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = remember(libraries, selectedId) {
        selectedId?.let { id -> libraries?.libraries?.firstOrNull { it.uniqueId == id } }
    }
    // The export lists components in dependency-coordinate order, which reads
    // as no order at all once the coordinates themselves are hidden —
    // "Experimental annotation" lands nowhere near "Annotation". The displayed
    // name is the only thing a reader can scan by here, and there is no search,
    // so sort on exactly that. Case-insensitive so a lowercase coordinate
    // fallback name doesn't sort into its own block after the Z's.
    val sortedLibraries = remember(libraries) {
        libraries?.libraries.orEmpty()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Library::name))
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            // Outside the list, like every screen here — see MainScreen's note
            // on why safeDrawingPadding sits around the scroll, not inside it.
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SnoozemoTitleRow(title = stringResource(R.string.settings_licenses_title))
        // Just the component names, one compact row each; the version and
        // license live behind a tap so a 90-row list stays scannable. Lazy,
        // unlike the other screens' verticalScroll Columns, because that many
        // rows is more than one screen's worth by an order of magnitude.
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(
                items = sortedLibraries,
                key = { it.uniqueId },
            ) { library ->
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.bodyLarge,
                    // Vertical only: the page's own 16dp already sets the left
                    // margin, so the name lines up with the title above it.
                    // 12dp clears Android's 48dp minimum tap target against
                    // bodyLarge's own height.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedId = library.uniqueId }
                        .padding(vertical = 12.dp),
                )
            }
        }
        // Same shape and position as the Permissions screen's own Done: system
        // Back works too, but a leaf screen with no visible way out reads as
        // stuck.
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.permissions_done))
        }
    }
    selected?.let { library ->
        LibraryDetailsDialog(
            library = library,
            onOpenLicenseUrl = onOpenLicenseUrl,
            onDismiss = { selectedId = null },
        )
    }
}

/**
 * Version and license(s) for a tapped [library]. The bundled export carries no
 * license text (it's excluded to keep CI's regenerate-and-diff deterministic —
 * see app/build.gradle.kts), so each license with a URL is a link to the full
 * text rather than inline body copy.
 */
@Composable
internal fun LibraryDetailsDialog(
    library: Library,
    onOpenLicenseUrl: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    // Set when a tap found nothing on the device able to open a web link. The
    // dialog says so rather than absorbing the tap: a link that silently does
    // nothing is indistinguishable from a broken app (`SPEC.md`, principle 2).
    // Cleared on the next tap, so a retry that works clears the message too.
    var linkFailed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        title = { Text(library.name) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                library.artifactVersion?.let { version ->
                    Text(
                        text = stringResource(R.string.settings_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                library.licenses.forEach { license ->
                    val url = license.url
                    if (!url.isNullOrEmpty()) {
                        // A link to the full license text — primary color and a
                        // tap target signal it opens in the browser.
                        //
                        // It is a control, so it owes Android's 48dp minimum
                        // touch target: bodyMedium's own line box is about 20dp
                        // and the 8dp padding alone left it at roughly 36dp.
                        // The min height wins for a one-line name; a name long
                        // enough to wrap grows past it and keeps the padding.
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { linkFailed = !onOpenLicenseUrl(url) }
                                .heightIn(min = 48.dp)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (linkFailed) {
                    Text(
                        text = stringResource(R.string.settings_licenses_link_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
    )
}
