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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.snoozemo.R

/**
 * The prominent in-app disclosure `SPEC.md` §3.2 requires precede the location
 * runtime dialogs: what Snoozemo uses location for, that it stays on the
 * phone, and that Android will ask for it in two steps. Play's background-
 * location declaration asks for a demonstration video showing this screen and
 * then the system prompt it leads to, so its copy has to actually say why
 * before the "why" is a permission dialog the user has three seconds to read.
 *
 * A full screen, not a dialog over the settings screen — the disclosure has
 * to be read, not glanced past and dismissed with the same reflex a system
 * dialog gets. [onContinue] is the only thing that launches a runtime
 * request; nothing here holds a permission API of its own.
 */
@Composable
fun LocationDisclosureScreen(
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.location_disclosure_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.location_disclosure_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.location_disclosure_background_note),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.location_disclosure_continue))
        }
        TextButton(
            onClick = onNotNow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.location_disclosure_not_now))
        }
    }
}

@Preview
@Composable
private fun LocationDisclosureScreenPreview() {
    SnoozemoTheme {
        LocationDisclosureScreen(onContinue = {}, onNotNow = {})
    }
}
