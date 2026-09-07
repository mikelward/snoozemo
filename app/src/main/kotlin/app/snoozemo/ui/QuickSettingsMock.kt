package app.snoozemo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import app.snoozemo.R
import app.snoozemo.tile.R as TileR

/**
 * The shade, drawn from memory: four Quick Settings tiles with Snoozemo's among
 * them, ringed (`SPEC.md` §4.2).
 *
 * Card 1 has one job — say what the app is — and the honest answer is "a tile
 * you tap". A user who has never seen the app has no idea what a Quick Settings
 * tile is called or where it lives, and the words for it differ by
 * manufacturer, so the picture does the work the copy cannot.
 *
 * **Drawn like the real thing rather than as a diagram** (maintainer,
 * 2026-09-07): the panel is a rounded surface of pill tiles, two per row, the
 * familiar ones lit the way a phone lights them, and Snoozemo's tile sits
 * inside it looking like every other tile. What marks it is a ring, not a
 * different style — the point is "this is the one to look for", and a tile
 * rendered specially would be teaching the user to look for something the shade
 * will never show them.
 *
 * Not a screenshot: a real one would be a particular phone's shade, would rot
 * with every platform release, and could not follow the app's own theme or the
 * chosen text size. Everything here is themed, so it lands right in dark mode
 * and at any size.
 */
@Composable
internal fun QuickSettingsMock(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.welcome_quick_settings_mock_description)
    Surface(
        // The shade's own container, a step away from the card behind it so the
        // panel reads as a separate surface rather than as part of the card.
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .fillMaxWidth()
            // One description for the whole picture: read tile by tile it is
            // four labels with no sense to them, and the thing a screen reader
            // user needs is what the picture is *for*.
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                QuickSettingsTile(
                    label = stringResource(R.string.welcome_quick_settings_wifi),
                    glyph = QuickSettingsGlyph.WIFI,
                    on = true,
                    modifier = Modifier.weight(1f),
                )
                QuickSettingsTile(
                    label = stringResource(R.string.welcome_quick_settings_bluetooth),
                    glyph = QuickSettingsGlyph.BLUETOOTH,
                    on = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                QuickSettingsTile(
                    label = stringResource(R.string.welcome_quick_settings_airplane),
                    glyph = QuickSettingsGlyph.AIRPLANE,
                    on = false,
                    modifier = Modifier.weight(1f),
                )
                QuickSettingsTile(
                    label = stringResource(TileR.string.tile_snooze_here),
                    glyph = QuickSettingsGlyph.SNOOZEMO,
                    // Off, like a tile you have not tapped yet — which is
                    // exactly what the user is about to go and find.
                    on = false,
                    ringed = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Which mark a mock tile draws. */
private enum class QuickSettingsGlyph { WIFI, BLUETOOTH, AIRPLANE, SNOOZEMO }

/**
 * One pill in [QuickSettingsMock].
 *
 * [ringed] is the whole point of the picture and is drawn outside the pill, so
 * the tile itself stays the same shape and color as its neighbors: a ring says
 * "this one", where a recolored tile would say "Snoozemo's tile looks different
 * from the others", which is false and would send the user looking for the
 * wrong thing.
 */
@Composable
private fun QuickSettingsTile(
    label: String,
    glyph: QuickSettingsGlyph,
    on: Boolean,
    modifier: Modifier = Modifier,
    ringed: Boolean = false,
) {
    val shape = RoundedCornerShape(24.dp)
    val container =
        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            // The ring goes outside, with 4dp of clear space inside it, so it
            // reads as something drawn *around* the tile rather than as its
            // border. **Every** tile carries that padding, ringed or not:
            // paying for it only on the marked one made it visibly smaller
            // than the tile beside it, which is the same lie a recolored tile
            // would tell.
            .then(
                if (ringed) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(28.dp),
                    )
                } else {
                    Modifier
                }
            )
            .padding(4.dp),
    ) {
        Surface(shape = shape, color = container, contentColor = content) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    // Fixed, so the four tiles line up their labels whatever
                    // the text size does around them — a Quick Settings icon is
                    // dp on a real phone too.
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QuickSettingsMark(glyph = glyph, tint = content)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    // A real tile truncates rather than wrapping, and at 160%
                    // text this is what keeps four tiles the same height.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

/**
 * The mark inside a mock tile.
 *
 * Drawn rather than imported: this app carries no icon library, and three
 * glyphs are not worth one. Snoozemo's own tile icon is the real asset, so the
 * picture shows the user exactly the mark they will be looking for.
 */
@Composable
private fun QuickSettingsMark(glyph: QuickSettingsGlyph, tint: Color) {
    if (glyph == QuickSettingsGlyph.SNOOZEMO) {
        Image(
            painter = painterResource(TileR.drawable.ic_tile_snooze),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(24.dp),
        )
        return
    }
    Canvas(modifier = Modifier.size(24.dp)) {
        when (glyph) {
            QuickSettingsGlyph.WIFI -> drawWifi(tint)
            QuickSettingsGlyph.BLUETOOTH -> drawBluetooth(tint)
            QuickSettingsGlyph.AIRPLANE -> drawAirplane(tint)
            QuickSettingsGlyph.SNOOZEMO -> Unit
        }
    }
}

/** Three arcs and a dot, the shape every phone draws for a signal. */
private fun DrawScope.drawWifi(tint: Color) {
    val stroke = Stroke(width = size.minDimension * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val center = Offset(size.width / 2f, size.height * 0.78f)
    listOf(0.72f, 0.48f, 0.24f).forEach { fraction ->
        val radius = size.minDimension * fraction
        drawArc(
            color = tint,
            startAngle = 215f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = stroke,
        )
    }
    drawCircle(color = tint, radius = size.minDimension * 0.055f, center = center)
}

/** The rune: a vertical stroke crossed by two chevrons meeting at the top and foot. */
private fun DrawScope.drawBluetooth(tint: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.30f, h * 0.32f)
        lineTo(w * 0.70f, h * 0.68f)
        lineTo(w * 0.50f, h * 0.86f)
        lineTo(w * 0.50f, h * 0.14f)
        lineTo(w * 0.70f, h * 0.32f)
        lineTo(w * 0.30f, h * 0.68f)
    }
    drawPath(
        path = path,
        color = tint,
        style = Stroke(
            width = size.minDimension * 0.09f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ),
    )
}

/** A plane seen from above, nose up. */
private fun DrawScope.drawAirplane(tint: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.50f, h * 0.08f)
        cubicTo(w * 0.58f, h * 0.08f, w * 0.58f, h * 0.22f, w * 0.56f, h * 0.36f)
        lineTo(w * 0.94f, h * 0.60f)
        lineTo(w * 0.94f, h * 0.70f)
        lineTo(w * 0.56f, h * 0.58f)
        lineTo(w * 0.55f, h * 0.80f)
        lineTo(w * 0.68f, h * 0.90f)
        lineTo(w * 0.68f, h * 0.96f)
        lineTo(w * 0.50f, h * 0.90f)
        lineTo(w * 0.32f, h * 0.96f)
        lineTo(w * 0.32f, h * 0.90f)
        lineTo(w * 0.45f, h * 0.80f)
        lineTo(w * 0.44f, h * 0.58f)
        lineTo(w * 0.06f, h * 0.70f)
        lineTo(w * 0.06f, h * 0.60f)
        lineTo(w * 0.44f, h * 0.36f)
        cubicTo(w * 0.42f, h * 0.22f, w * 0.42f, h * 0.08f, w * 0.50f, h * 0.08f)
        close()
    }
    drawPath(path = path, color = tint)
}
