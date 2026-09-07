package app.snoozemo.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.snoozemo.core.MAX_FONT_SCALE
import app.snoozemo.core.MIN_FONT_SCALE
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.core.fontScalePercent
import app.snoozemo.R
import app.snoozemo.UpdateProgress

/**
 * Every screen (`MainScreen`, `PermissionsScreen`, `SettingsScreen`,
 * `LicensesScreen`) shares
 * this theme and these building blocks — `SetupRow` in particular is what a
 * capability row looks like wherever one appears, so it lives here rather than
 * inside any one screen's file.
 */
/**
 * The text size in force, reachable from a composable Compose hosts in its own
 * **window** (Codex, PR #217).
 *
 * A `ModalBottomSheet`, an `AlertDialog` and a `DropdownMenu` each render in a
 * separate window whose own owner provides `LocalDensity` afresh — so the
 * scaled density [SnoozemoTheme] provides never reaches them, and their text
 * came out at the system size however the user had set it. The gesture is
 * lost the same way: a pointer handler on the activity's root never sees a
 * touch made in another window.
 *
 * Null outside the theme, so a composable used on its own still renders.
 */
internal val LocalFontSizeState = staticCompositionLocalOf<FontSizeState?> { null }

/**
 * The density before the text-size multiplier, for a control whose own gesture
 * would otherwise be hosted under a density its drag keeps changing (Codex,
 * PR #217).
 *
 * Compose resets a pointer-input handler when the density under it changes, so
 * the size slider — which changes exactly that on every frame of its drag —
 * died on its first resizing movement, the same way the pinch host did before
 * it was lifted out. Null outside the theme.
 */
internal val LocalBaseDensity = staticCompositionLocalOf<Density?> { null }

/**
 * Hosts [content] at the unscaled density, for a control that resizes text
 * while being dragged. See [LocalBaseDensity].
 *
 * Only `fontScale` differs between the two densities, so a control with no text
 * of its own — a slider's track and thumb are dp — looks identical either way.
 */
@Composable
internal fun StableInputDensity(content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current
    if (base == null) {
        content()
        return
    }
    CompositionLocalProvider(LocalDensity provides base, content = content)
}

/**
 * Re-provides the chosen text size inside one of those windows.
 *
 * For a slot that only shows text — a dialog's title, body or buttons. Reads
 * the window's own density as its base, which is the unscaled one, so this
 * applies the multiplier exactly once wherever it is used.
 */
@Composable
internal fun FontSizeWindow(content: @Composable () -> Unit) {
    val state = LocalFontSizeState.current
    val base = LocalDensity.current
    if (state == null) {
        content()
        return
    }
    val scaled = remember(base, state.scale) { Density(base.density, base.fontScale * state.scale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * The pinch, for a surface in one of those windows.
 *
 * "Two fingers anywhere in Snoozemo" is what the setting promises, so every
 * window the app opens takes the gesture — the sheet, both dialogs, the ringer
 * menu (maintainer, 2026-09-07). Each window needs its own host: a pointer
 * handler no more crosses the boundary than a density does.
 *
 * Apply it to the surface *containing* the text rather than inside it, so one
 * pinch spans a dialog's title, body and buttons instead of dying at the edge
 * of whichever slot it started in. It handles the Initial pass, so it reaches
 * the surface ahead of anything inside it and consumes nothing until the
 * fingers have actually spread — see [pinchFontSize].
 *
 * Never nest two of these: both would see the same events and apply the same
 * zoom twice. No-op outside [SnoozemoTheme].
 */
@Composable
internal fun Modifier.pinchFontSizeHost(): Modifier {
    val state = LocalFontSizeState.current ?: return this
    return pinchFontSize(
        enabled = { state.pinchEnabled },
        scale = { state.scale },
        onStart = state::startGesture,
        onPreview = state::preview,
        onSettled = state::commit,
    )
}

/**
 * [FontSizeWindow] plus [pinchFontSizeHost], for a whole surface in its own
 * window — the end-condition sheet, which is a screen in its own right.
 *
 * The gesture is hosted outside the scaled density for the reason
 * [SnoozemoTheme] is: a density change restarts a pointer handler, so a
 * gesture hosted under the size it is changing dies on its own first resize.
 */
@Composable
internal fun FontSizePinchWindow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.pinchFontSizeHost()) {
        FontSizeWindow(content)
    }
}

@Composable
fun SnoozemoTheme(
    /**
     * The text size in force, and whether a pinch may change it (`SPEC.md`
     * §4.8). Defaulted so every existing call site — the app screens, the tile
     * trampoline's sheet, and the screenshot tests — is sized by the user's
     * setting and answers a pinch without having to say so; a caller that also
     * *shows* the setting (Settings) passes its own handle in so the slider and
     * the gesture move one value.
     */
    fontSize: FontSizeState = rememberFontSizeState(),
    content: @Composable () -> Unit,
) {
    // Built once per dark-mode reading rather than on every recomposition:
    // `lightColorScheme()`/`darkColorScheme()` allocate a whole new
    // `ColorScheme` (dozens of `Color` fields), and this composable sits
    // above every screen this app has — every one of them recomposing
    // through a scope that rebuilds the palette from scratch would be waste
    // for no visual difference.
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = remember(darkTheme) { if (darkTheme) darkColorScheme() else lightColorScheme() }
    // Only `fontScale` is overridden, so text grows while paddings, icons, and
    // touch targets keep the layout the 4dp grid describes — and it multiplies
    // the system's own scale rather than replacing it, so an accessibility
    // setting made in Android is respected (`SPEC.md` §4.8).
    val density = LocalDensity.current
    val scaled = remember(density, fontSize.scale) {
        Density(density.density, density.fontScale * fontSize.scale)
    }
    MaterialTheme(colorScheme = colorScheme) {
        // The gesture sits above every screen rather than on any one of them: a
        // pinch resizes the app, so it must work wherever the user happens to
        // be — the welcome flow and the end-condition sheet included.
        //
        // **Outside the scaled density, not inside it** (Codex, PR #217).
        // Compose restarts a pointer-input handler when the density under it
        // changes, and every preview frame of a pinch changes exactly that —
        // so hosting the gesture under `scaled` killed it on its own first
        // resize: the fingers kept moving, nothing followed them, and the
        // release persisted nothing. The scaled density belongs to the content
        // being sized, not to the hand doing the sizing. Only `fontScale`
        // differs between the two, so the slop this reads is unchanged either
        // way — it is a distance in fingers, not in text.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchFontSize(
                    enabled = { fontSize.pinchEnabled },
                    scale = { fontSize.scale },
                    onStart = fontSize::startGesture,
                    onPreview = fontSize::preview,
                    onSettled = fontSize::commit,
                ),
        ) {
            CompositionLocalProvider(
                LocalDensity provides scaled,
                // So a sheet, dialog or menu in its own window can re-establish
                // both — neither the density nor the gesture crosses a window
                // boundary on its own. See [LocalFontSizeState].
                LocalFontSizeState provides fontSize,
                // And so a control that resizes text as it is dragged can host
                // its own input where the density does not move. See
                // [LocalBaseDensity].
                LocalBaseDensity provides density,
            ) {
                content()
            }
        }
    }
}

/**
 * The app's mark: the bare `ic_launcher_foreground` vector, same as the
 * sibling Simmo repo's own `SimmoMark` — no background shape behind it.
 *
 * `ic_launcher_foreground` is white strokes only (it doubles as the
 * `monochrome` themed-icon layer, which the system tints itself rather than
 * reading its own color), so it needs the same treatment in-app: tinted to
 * `onSurface` here, the same way the launcher tints the themed layer, rather
 * than trusting a color the vector doesn't actually carry. `onSurface`
 * rather than a fixed dark color so the mark stays legible against both
 * themes' page background, not just the light one. `TODO.md` still tracks
 * reconsidering the launcher icon itself.
 */
@Composable
internal fun SnoozemoMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        modifier = modifier.size(size),
    )
}

/**
 * The one page-title row, used by every screen (`MainScreen`,
 * `PermissionsScreen`, `SettingsScreen`): [SnoozemoMark] beside the title, so
 * a user finds the same mark in the same place everywhere in the app — the
 * sibling Simmo repo's `SimmoTopBar`.
 *
 * [actions] are icon buttons pinned to the trailing edge. The title takes the
 * space between, so a screen that passes none looks exactly as it did before
 * this slot existed — which is what lets the row stay the single title
 * treatment rather than forking into "with actions" and "without".
 */
@Composable
internal fun SnoozemoTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnoozemoMark(size = 32.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * One icon button in a [SnoozemoTitleRow]'s trailing slot.
 *
 * The label is the accessible name rather than a tooltip: an icon-only control
 * says nothing to a screen reader on its own, and the gear and the help mark
 * are exactly the pair a user cannot tell apart by shape alone if they cannot
 * see them.
 */
@Composable
internal fun SnoozemoTitleAction(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(label),
        )
    }
}

/** Which setup row a failure belongs beside. */
enum class SetupRowId {
    DND,
    NOTIFICATIONS,
    LOCATION,
    CALENDAR,
    TILE,
    FILTERS,
}

/**
 * One capability: what it is, how it stands, and — only while something is
 * actually left to do — the button that fixes it.
 *
 * The trailing-button shape is the sibling Simmo repo's `AllowRow`, and the two
 * halves of it are load-bearing separately.
 *
 * **A verb, not a route.** The action line used to describe the mechanism —
 * `Opens Settings`, `Tap to add` — which reads as a note about what will happen
 * rather than an offer to do it, and made an allowed row look like it still
 * wanted something. `Allow` and `Add` say what the user gets.
 *
 * **Nothing to tap once it is done.** [action] is null when the capability is
 * in place, and the row is then a statement: title and status, no control. A
 * button that only re-opens a screen the user has already finished with is a
 * tap with nothing behind it, and it keeps first-run urgency on a screen where
 * everything is already fine.
 *
 * That is a deliberate trade against the shape this row had before, where the
 * whole surface was clickable so the sentence naming the problem was itself the
 * target (`TODO.md` Phase 2). One target per row is what TalkBack can announce
 * unambiguously — a button nested inside a clickable row gives the same tap two
 * different names — so the row keeps the single target and moves it onto the
 * control that says what it does.
 */
@Composable
internal fun SetupRow(
    title: String,
    status: String,
    action: String?,
    onAction: () -> Unit,
    failure: String? = null,
    /**
     * Whether this row's own action is currently running, disabling its
     * button while it is. Only the debug-log share row uses it so far — that
     * action is repeatable and slow enough to tap twice, and gating it here
     * is what removes the concurrent second tap rather than reconciling one
     * afterwards (`DebugReport.shareInFlight`). Every other row's action
     * either completes instantly or leaves the screen.
     */
    actionRunning: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            // The same gap the sibling repo keeps between a label block and its
            // trailing control: the text takes whatever width the button
            // leaves, so a status long enough to wrap would otherwise end flush
            // against the button and read as one object with it.
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = status, style = MaterialTheme.typography.bodyMedium)
                // Inside the row, not at the foot of the screen. A tap that
                // could not open Settings has to say so where the tap was: the
                // column scrolls, so a message appended below the buttons is
                // off screen in landscape or at a large font scale, and the row
                // would read as the dead tap this screen exists to remove
                // (flagged by Codex on PR #18).
                //
                // And only beside an offer. The message is about a tap, so a
                // row with nothing left to tap has nothing to report — showing
                // it there would put `Couldn't open Settings` under `Allowed`,
                // which is the screen contradicting itself about the user's own
                // phone. The state is cleared as well, in the two places that
                // learn the capability recovered; this is what keeps the two
                // from disagreeing in the frame between.
                failure?.takeIf { action != null }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            action?.let {
                // The visible label is the same "Allow" on every row (SPEC.md
                // §5.2) — a screen reader announcing that word alone can't
                // tell a user which permission a given button opens once more
                // than one row is missing at once (flagged by Codex on PR
                // #103). The content description carries the capability's own
                // name instead, without changing what's drawn — from a formatted
                // string resource, not Kotlin concatenation, so a translation can
                // reorder the two parts independently of English word order.
                val actionDescription = stringResource(R.string.setup_row_action_description, it, title)
                Button(
                    onClick = onAction,
                    enabled = !actionRunning,
                    modifier = Modifier.semantics { contentDescription = actionDescription },
                ) { Text(it) }
            }
        }
    }
}

/**
 * Points at the help icon once the welcome flow has been left.
 *
 * The flow's replay lives behind an icon in the title row, which is discoverable
 * only if you already know it is there — so the one moment the user has just
 * seen the cards is the moment worth saying it (maintainer, 2026-09-05).
 *
 * Quieter than [TileBanner]: that one makes a case for an action, this only
 * says where something is, so it takes the surface variant rather than the
 * primary container and has no affirmative button — the only thing to do here
 * is stop being told.
 */
@Composable
internal fun ReplayHintBanner(onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // Asymmetric on purpose, and only numerically: `TextButton` carries
            // its own horizontal content padding, so a matching 16dp here would
            // set its label further in than the text it sits beside. The
            // vertical 8dp is likewise a floor rather than the height — the
            // button's own minimum touch target is what the row measures to.
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.replay_hint_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.replay_hint_dismiss))
            }
        }
    }
}

/**
 * The screen's one piece of advocacy: add the tile.
 *
 * Deliberately not a [SetupRow]. The rows state a fact about a capability and
 * offer the repair; this makes a case, and looking different is the point —
 * `SPEC.md` §4.2 asks the screen to push toward the tile rather than list it as
 * one option among equals. It is the only element here that says *why*.
 *
 * Two actions, weighted: adding is the filled button, dismissing is a text one.
 */
@Composable
internal fun TileBanner(
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    failure: String? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tile_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.tile_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            // A tap that could not add the tile has to say so here too, not
            // only on the permanent row on SettingsScreen (Codex, PR #82) —
            // the banner is where the tap actually happened, and a failure
            // that only shows up on a screen the user hasn't opened reads as
            // the tap having done nothing.
            failure?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.tile_banner_dismiss))
                }
                Button(onClick = onAdd) {
                    Text(stringResource(R.string.tile_banner_add))
                }
            }
        }
    }
}

/**
 * The Play update banner (`SettingsScreen`, `play` flavor only —
 * [app.snoozemo.PlayUpdateChecker]): "Update available" with Dismiss /
 * Update, tracking a flexible update through downloading to "Update ready"
 * with Restart.
 *
 * Dismiss is offered before the download and not after: while it is in
 * flight there is nothing useful to tap (Update would re-open a sheet for a
 * download already running), and once it is downloaded, dismissing would
 * hide the only in-app way to install what has already been fetched.
 */
@Composable
internal fun PlayUpdateBanner(
    progress: UpdateProgress,
    // Only ever meaningful alongside `Downloaded` — see the caller's own
    // comment on why this is carried apart from [progress] rather than a
    // fifth [UpdateProgress] state.
    restartFailed: Boolean = false,
    onUpdate: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val inFlight = progress == UpdateProgress.Starting || progress == UpdateProgress.Downloading
    val downloaded = progress == UpdateProgress.Downloaded
    val titleRes = when {
        inFlight -> R.string.play_update_banner_updating_title
        downloaded -> R.string.play_update_banner_downloaded_title
        else -> R.string.play_update_banner_title
    }
    val bodyRes = when {
        inFlight -> R.string.play_update_banner_updating_body
        downloaded -> R.string.play_update_banner_downloaded_body
        else -> R.string.play_update_banner_body
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Inside the card, like every other row's failure line on these
            // screens (`SetupRow`, `TileBanner`) — the message is about the
            // tap, and the column scrolls. Only reachable in `Downloaded`:
            // that is the only state Restart is offered in.
            if (downloaded && restartFailed) {
                Text(
                    text = stringResource(R.string.play_update_banner_restart_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    inFlight -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    // No Dismiss once the update is downloaded: dismissing is
                    // remembered against that build, and Restart is the only
                    // in-app way to finish it — so the pair would strand a
                    // download that has already been fetched, with nothing
                    // left to offer until Play publishes a *newer* build. The
                    // banner costs a card at the top of the screen until the
                    // restart happens, which is the cheaper end of that
                    // trade.
                    downloaded -> Button(onClick = onRestart) {
                        Text(stringResource(R.string.play_update_banner_restart_button))
                    }
                    else -> {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.play_update_banner_dismiss))
                        }
                        Button(onClick = onUpdate) {
                            Text(stringResource(R.string.play_update_banner_update_button))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The post-crash banner (SPEC.md §4.6, `docs/DEBUG.md`): shown on
 * [SettingsScreen] only while an uncaught-exception run is pinned. Modeled
 * on [TileBanner]'s shape — a card that makes a case, not a [SetupRow] that
 * states a fact — but colored for a problem rather than an opportunity.
 *
 * `onShare` hands off through the same share flow the permanent row below
 * uses; it is *this* banner's job only to know it should appear and to ask.
 */
@Composable
internal fun CrashBanner(
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    shareFailed: Boolean = false,
    dismissFailed: Boolean = false,
    /**
     * Whether a share is already running. The Share button is disabled while
     * it is, so a second tap can't be made at all — which is what lets
     * `DebugReport` stay free of the machinery that used to reconcile one
     * after the fact (`DebugReport.shareInFlight`).
     */
    sharing: Boolean = false,
) {
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
                text = stringResource(R.string.crash_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.crash_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            // A tap that reached neither the clipboard nor the chooser has to
            // say so here — this banner's own Share button is a second route
            // to the same DebugReport.share call the permanent Settings row
            // uses, and a failure from this one must not go unsaid just
            // because this screen has no other place it renders (Codex,
            // PR #89).
            if (shareFailed) {
                Text(
                    text = stringResource(R.string.setup_debug_log_share_failed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Same reasoning as shareFailed above, for a Dismiss tap the
            // file layer refused (Codex, PR #89): the pin really is still
            // there, so the banner staying up is correct — but silently,
            // which reads as the tap having done nothing at all.
            if (dismissFailed) {
                Text(
                    text = stringResource(R.string.crash_banner_dismiss_failed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.crash_banner_dismiss))
                }
                Button(onClick = onShare, enabled = !sharing) {
                    Text(
                        stringResource(
                            if (sharing) R.string.share_in_progress else R.string.crash_banner_share,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * A setting with two valid states, as opposed to a [SetupRow]'s "capability
 * missing, here is its one repair".
 *
 * The whole card is the target and the switch is its indicator — one TalkBack
 * target per row, the same rule the rows above keep — which is why the switch
 * itself takes no `onCheckedChange` of its own.
 *
 * [failures] are shown under the description, in order, and are what stops a
 * switch snapping back to the stored truth from reading as a missed tap. Each
 * one is a distinct failure with a distinct fix, so they stack rather than
 * replace one another; already-null entries are dropped by the caller's own
 * `listOfNotNull`.
 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    enabled: Boolean,
    failures: List<String>,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // `toggleable` before the padding, so the whole card answers the
            // tap; the Surface's shape clips the ripple to the card.
            modifier = Modifier
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onChange,
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Inside the row, like SetupRow's failure line and for the
                // same reason: the message is about the tap, and the column
                // scrolls. The switch has already snapped back to the stored
                // truth by the time this shows; the line is what stops the
                // snap-back reading as a missed tap.
                failures.forEach { failure ->
                    Text(
                        text = failure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

/**
 * The debug log's on/off switch (SPEC.md §4.6): on by default, and turning it
 * off deletes what was kept.
 */
@Composable
internal fun DebugLogRow(
    enabled: Boolean,
    saveFailed: Boolean,
    cleanupFailed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.setup_debug_log_title),
        description = stringResource(R.string.setup_debug_log_description),
        enabled = enabled,
        failures = listOfNotNull(
            stringResource(R.string.setup_debug_log_save_failed).takeIf { saveFailed },
            // Distinct from saveFailed: the setting itself did save as Off,
            // but the delete that's supposed to go with it left something
            // behind — a different failure than the switch not taking
            // (Codex, PR #89).
            stringResource(R.string.setup_debug_log_cleanup_failed).takeIf { cleanupFailed },
        ),
        onChange = onChange,
    )
}

/**
 * Crash reporting's on/off switch (SPEC.md §12): off until turned on, and the
 * one place in the app where the user agrees to something leaving the phone.
 *
 * Drawn only when there is a reporter behind it — the `play` flavor, built
 * with a Firebase config (`docs/crashlytics.md`). `SettingsScreen` decides
 * that by being handed a null, so this composable never has to know which
 * flavor it is in, and a screenshot test can render the row either way.
 *
 * No cleanup-failure line to match the debug log's: turning this off deletes
 * the reports Crashlytics had already captured, but that delete is the SDK's
 * own asynchronous call and reports no outcome back, so there is nothing
 * honest to say about it here. What the row can promise — and does — is that
 * nothing further is collected.
 */
@Composable
internal fun CrashReportingRow(
    enabled: Boolean,
    saveFailed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.setup_crash_reporting_title),
        description = stringResource(R.string.setup_crash_reporting_description),
        enabled = enabled,
        failures = listOfNotNull(
            stringResource(R.string.setup_crash_reporting_save_failed).takeIf { saveFailed },
        ),
        onChange = onChange,
    )
}

/**
 * How loud a snooze may be (SPEC.md §5.9): a ceiling, defaulting to vibrate.
 *
 * A dropdown rather than three rows (maintainer, 2026-09-02): the description
 * runs straight into it — `When snoozing set the phone to  [ Vibrate ]` — so
 * the sentence and the control read as one thing.
 *
 * Structurally identical to [SwitchRow] (maintainer, 2026-09-02): one card, one
 * 16dp padding, title and description in a weighted column, and the control
 * centered against **both** lines at the trailing edge rather than beside the
 * description alone. That is what keeps the row the same height and the same
 * shape as the switches and the Filters row around it — a settings screen where
 * one setting is built differently reads as two screens.
 */
@Composable
internal fun SnoozeRingerRow(
    chosen: SnoozeRinger,
    saveFailed: Boolean,
    onChange: (SnoozeRinger) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.setup_ringer_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setup_ringer_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Inside the column, like SwitchRow's failure line and for the
                // same reason: the selection has already snapped back to the
                // stored value by the time this shows, and the line is what
                // stops that reading as a missed tap.
                if (saveFailed) {
                    Text(
                        text = stringResource(R.string.setup_debug_log_save_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Box {
                OutlinedButton(
                    onClick = { open = true },
                    // 40dp is the button's own height; this clears Android's
                    // 48dp minimum target without a fixed height that would
                    // clip a scaled-up font.
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(chosen.labelRes()))
                    Text(
                        text = " ▾",
                        // Decorative: the value beside it is the label, and
                        // TalkBack reading the glyph's Unicode name after it is
                        // noise.
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    // A menu is a window of its own, so the chosen size has to
                    // be re-provided here too (Codex, PR #217) — otherwise the
                    // options read at the system size while the row that opened
                    // them does not — and the pinch has to be re-hosted here for
                    // the same reason (maintainer, 2026-09-07).
                    modifier = Modifier.pinchFontSizeHost(),
                ) {
                    FontSizeWindow {
                        // Loudest first, matching the volume panel's own order
                        // and `SnoozeRinger`'s declaration.
                        SnoozeRinger.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelRes())) },
                                onClick = {
                                    open = false
                                    onChange(option)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The user-facing name of a ceiling.
 *
 * Exhaustive rather than defaulted, so a fourth option cannot ship rendering
 * as one of the three that already exist.
 */
@StringRes
private fun SnoozeRinger.labelRes(): Int = when (this) {
    SnoozeRinger.RING -> R.string.setup_ringer_ring
    SnoozeRinger.VIBRATE -> R.string.setup_ringer_vibrate
    SnoozeRinger.SILENT -> R.string.setup_ringer_silent
}

/**
 * Whether the tile asks when to unsnooze (SPEC.md §4.4) — **off by default**, so
 * an ordinary tap arms and gets out of the way.
 *
 * The opposite default to [DebugLogRow]'s, and deliberately: the debug log is on
 * because an uncaptured failure is unrepeatable, while a sheet not shown costs
 * nothing that turning it on can't recover. So this row is an offer, not a way
 * out of something.
 *
 * No cleanup-failure line either — turning it off stops a sheet appearing and
 * deletes nothing, so a refused save is the only thing that can go wrong.
 */
@Composable
internal fun AskWhenToUnsnoozeRow(
    enabled: Boolean,
    saveFailed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.setup_ask_unsnooze_title),
        description = stringResource(R.string.setup_ask_unsnooze_description),
        enabled = enabled,
        failures = listOfNotNull(
            stringResource(R.string.setup_debug_log_save_failed).takeIf { saveFailed },
        ),
        onChange = onChange,
    )
}

/**
 * How big Snoozemo's own text is (`SPEC.md` §4.8): a multiplier over the system
 * font scale, 80%-160%, continuous.
 *
 * Structurally the same card as [SwitchRow] and the ringer row — one surface,
 * one 16dp padding, title and description in a column — with the slider under
 * them because it needs the row's whole width; a settings screen where one
 * setting is built differently reads as two screens.
 *
 * The page resizes as the slider moves and only the release is persisted, so
 * this screen is its own preview: the value shown beside the title is the size
 * the page is currently drawn at.
 */
@Composable
internal fun FontSizeRow(
    scale: Float,
    saveFailed: Boolean,
    onPreview: (Float) -> Unit,
    onSettled: (Float) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setup_font_size_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setup_font_size_value, fontScalePercent(scale)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = stringResource(R.string.setup_font_size_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Same place and reason as a switch's: the size has already sprung
            // back to the stored one by the time this shows, and the line is
            // what stops that reading as a missed drag.
            if (saveFailed) {
                Text(
                    text = stringResource(R.string.setup_debug_log_save_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // The value the slider last reported, so the release persists
            // exactly where the finger left it — [scale] comes back through
            // recomposition, which a release in the same frame as the last drag
            // step would beat.
            var dragged by remember { mutableFloatStateOf(scale) }
            // Whether this row currently holds the gesture, so its disposal can
            // end one that is still in flight (Codex, PR #217). Leaving Settings
            // with a finger still on the slider disposes it without ever calling
            // `onValueChangeFinished`, and the state lives above the screen
            // switch — so it stayed `moving` for the life of the activity,
            // deferring every later stored value to a drag that had ended.
            // The pinch closes the same window with a `finally`; a slider has
            // no equivalent, so this is it.
            var dragging by remember { mutableStateOf(false) }
            // Kept level with the size whenever this row is not the one moving
            // it (Codex, PR #217): a pinch changes the size without going
            // through the slider, and a cache that went stale against it would
            // be a pre-pinch size waiting to be written by the next thing that
            // reports a finish. Writing it here recomposes nothing — the cache
            // is read only by the callbacks below.
            if (!dragging) dragged = scale
            DisposableEffect(Unit) {
                onDispose { if (dragging) onSettled(dragged) }
            }
            // Hosted at the unscaled density (Codex, PR #217): dragging this
            // changes the text size, which changes the density around it, which
            // resets its own pointer handler — measured, the drag froze at
            // whatever the first resizing movement had reached and the release
            // never persisted. A slider has no text, so nothing about it looks
            // different for being sized here.
            // What the row shows, said out loud (Codex, PR #217). A `Slider`
            // describes itself as its position within its own range, so the
            // default read as "25%" beside a row saying 100% — on the one
            // setting whose users are most likely to be listening rather than
            // looking.
            val spoken = stringResource(R.string.setup_font_size_value, fontScalePercent(scale))
            StableInputDensity {
                Slider(
                    modifier = Modifier.semantics { stateDescription = spoken },
                    value = scale,
                    onValueChange = {
                        dragging = true
                        dragged = it
                        onPreview(it)
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onSettled(dragged)
                    },
                    // No steps: the pinch is continuous, and a stepped slider
                    // would round a size the user set with their fingers away
                    // the next time they touched this (maintainer, 2026-09-06).
                    valueRange = MIN_FONT_SCALE..MAX_FONT_SCALE,
                )
            }
        }
    }
}

/**
 * Whether a two-finger pinch resizes the text (`SPEC.md` §4.8) — **on by
 * default**, because the gesture is how most people will find [FontSizeRow] at
 * all. The switch is for whoever keeps triggering it by accident.
 *
 * No cleanup-failure line: turning it off stops a gesture and deletes nothing,
 * so a refused save is the only thing that can go wrong.
 */
@Composable
internal fun PinchFontSizeRow(
    enabled: Boolean,
    saveFailed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.setup_pinch_font_size_title),
        description = stringResource(R.string.setup_pinch_font_size_description),
        enabled = enabled,
        failures = listOfNotNull(
            stringResource(R.string.setup_debug_log_save_failed).takeIf { saveFailed },
        ),
        onChange = onChange,
    )
}
