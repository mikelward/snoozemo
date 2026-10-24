package app.snoozemo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.mikelward.androidzoom.DEFAULT_FONT_SCALE
import com.mikelward.androidzoom.clampFontScale
import com.mikelward.androidzoom.compose.FontSizeState
import com.mikelward.androidzoom.compose.rememberFontSizeState
import kotlin.math.roundToInt

/**
 * The text size for this composition, wired to the stored settings (`SPEC.md`
 * §4.8).
 *
 * The state, the pinch and the range are mikelward/androidzoom's, shared with
 * the sibling apps; what stays here is where the value is kept
 * ([FontSizeSetting]) and how the Settings rows word it.
 *
 * Seeded from [FontSizeSetting.current] — memory only, never disk — so the first
 * frame is already the user's size on every screen, including the tile
 * trampoline's sheet, which opens over whatever they were doing and must not
 * resize a beat later under a finger already on its way down.
 */
@Composable
internal fun rememberAppFontSizeState(): FontSizeState {
    val context = LocalContext.current.applicationContext
    return rememberFontSizeState(
        persisted = FontSizeSetting.current,
        onScaleSettled = { FontSizeSetting.setScale(context, it) },
        onPinchEnabledChange = { FontSizeSetting.setPinchEnabled(context, it) },
        defaultScale = DEFAULT_FONT_SCALE,
    )
}

/**
 * [scale] moved by [deltaPercent] whole points of the percentage the Settings
 * row shows, for its `−` / `+` buttons.
 *
 * Rounded to the shown percent first, so a size the slider or a pinch left
 * between two percents (115.3%) steps to the next whole one (116%) rather than
 * to another fraction the row would show as the same number. Clamped to the
 * offered range like everything else that writes a size, a non-finite one
 * included. App-side because the range and the percent are androidzoom's, and
 * the buttons are only this app's.
 */
internal fun stepFontScale(scale: Float, deltaPercent: Int): Float {
    val percent = (clampFontScale(scale) * 100).roundToInt()
    return clampFontScale((percent + deltaPercent) / 100f)
}
