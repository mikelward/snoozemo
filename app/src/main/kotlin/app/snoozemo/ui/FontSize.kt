package app.snoozemo.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.snoozemo.core.FONT_SCALE_PINCH_SLOP_DP
import app.snoozemo.core.FontSizeSettings
import app.snoozemo.core.clampFontScale
import app.snoozemo.core.fontScaleAfterZoom
import app.snoozemo.core.pinchPassedSlop

/**
 * The text size on screen right now, and how to change it (`SPEC.md` §4.8).
 *
 * Held for the life of a composition so a drag or a pinch resizes the whole app
 * as it happens, with exactly one write when it ends — the same
 * move-it-live, persist-on-release shape the end-condition sheet's stepper uses.
 */
@Stable
class FontSizeState internal constructor(
    initial: FontSizeSettings,
    private val onScaleSettled: (Float) -> Unit,
    private val onPinchEnabledChange: (Boolean) -> Unit,
) {
    /** What every sp text is multiplied by, including mid-gesture. */
    var scale: Float by mutableFloatStateOf(clampFontScale(initial.scale))
        private set

    /** Whether a pinch may change [scale] — the Settings switch. */
    var pinchEnabled: Boolean by mutableStateOf(initial.pinchEnabled)
        private set

    /**
     * Whether a drag or a pinch is in flight, so nothing arriving from disk can
     * move the text out from under the user's fingers; see [onPersisted].
     */
    private var moving = false

    /**
     * What arrived from disk while the fingers were down, held rather than
     * dropped (Codex, PR #217).
     *
     * Dropping it stranded the pinch switch: a warm read landing mid-drag
     * carries both fields, and a drag released back at the size already stored
     * leaves `FontSizeSetting.current` structurally equal to what it was — so
     * the effect that feeds this never runs again, and a stored `pinchEnabled =
     * false` stayed displayed and enforced as `true` for the life of the
     * activity.
     */
    private var deferred: FontSizeSettings? = null

    /**
     * A gesture has begun, before it has moved anything (Codex, PR #217).
     *
     * A pinch is live from the frame it crosses the slop, and that frame
     * deliberately resizes nothing — so waiting for the first [preview] left a
     * window where a value arriving from disk was applied, and the release then
     * wrote the size the gesture had captured *before* it, overwriting what had
     * just been read. The slider needs no equivalent: its first movement is a
     * preview.
     */
    fun startGesture() {
        moving = true
    }

    /**
     * Whether the size has been moved since the last write (Codex, PR #217).
     *
     * A pinch owns the size from the frame it crosses the slop, and that frame
     * resizes nothing — so a gesture can begin and end having changed nothing at
     * all. Without knowing that, [commit] wrote the size captured at the
     * crossing and threw away a stored one that had arrived in between: a
     * two-finger gesture over a cold start could overwrite the user's saved size
     * with the default it had been drawn at.
     *
     * **Since the last write, not since the last gesture began.** Clearing it in
     * [startGesture] made one gesture able to disown another's work: a slider
     * drag that a second finger turns into a pinch hands over with the size
     * already moved, and the pinch — which may cross the slop and end without
     * moving anything further — would then leave that visible size unwritten
     * until the process restarted. Only a write clears it, so whoever is holding
     * the gesture at the end persists what is on screen (Codex, PR #217).
     */
    private var resized = false

    /** Show [scale] without persisting it: a slider drag, or a pinch in flight. */
    fun preview(scale: Float) {
        moving = true
        resized = true
        this.scale = clampFontScale(scale)
    }

    /** The drag or the pinch ended here: show it and persist where it landed. */
    fun commit(scale: Float) {
        val settled = clampFontScale(scale)
        val held = deferred
        moving = false
        deferred = null
        if (!resized) {
            // Nothing has moved the size since the last write, so this gesture
            // has no size of its own to persist: anything held lands whole and
            // the store keeps what it holds.
            held?.let {
                this.scale = clampFontScale(it.scale)
                pinchEnabled = it.pinchEnabled
            }
            return
        }
        resized = false
        // Anything that arrived while the fingers were down lands now — except
        // the size, which is what the gesture was about: the user's own value
        // wins over one the store reported mid-drag, and the write below is
        // what makes it true.
        held?.let { pinchEnabled = it.pinchEnabled }
        this.scale = settled
        onScaleSettled(settled)
    }

    /**
     * The switch was tapped.
     *
     * Named for the choice rather than for the field, like `chooseSnoozeRinger`
     * beside it — and `setPinchEnabled` would collide on the JVM with the
     * property's own setter.
     */
    fun choosePinch(enabled: Boolean) {
        pinchEnabled = enabled
        onPinchEnabledChange(enabled)
    }

    /**
     * The stored settings changed under us — the startup warm read landing, the
     * write a [commit] just made, or that write being rolled back after storage
     * refused it.
     *
     * **Held, not applied, while a gesture is in flight** (Codex, PR #217). The
     * first and third of those arrive asynchronously and owe nothing to the
     * user's fingers, so applying one mid-drag snapped the text and the slider
     * thumb away from where they were being dragged. Held rather than dropped
     * because the value carries the pinch switch as well as the size, and the
     * effect that feeds this does not run again for a value equal to the one
     * before it — see [deferred].
     */
    fun onPersisted(settings: FontSizeSettings) {
        if (moving) {
            deferred = settings
            return
        }
        scale = clampFontScale(settings.scale)
        pinchEnabled = settings.pinchEnabled
    }
}

/**
 * The font size for this composition, wired to the stored settings.
 *
 * Seeded from [FontSizeSetting.current] — memory only, never disk — so the first
 * frame is already the user's size on every screen, including the tile
 * trampoline's sheet, which opens over whatever they were doing and must not
 * resize a beat later under a finger already on its way down.
 */
@Composable
internal fun rememberFontSizeState(): FontSizeState {
    val context = LocalContext.current.applicationContext
    val persisted = FontSizeSetting.current
    val state = remember(context) {
        FontSizeState(
            initial = persisted,
            onScaleSettled = { FontSizeSetting.setScale(context, it) },
            onPinchEnabledChange = { FontSizeSetting.setPinchEnabled(context, it) },
        )
    }
    LaunchedEffect(state, persisted) { state.onPersisted(persisted) }
    return state
}

/**
 * A two-finger pinch anywhere in Snoozemo resizes its text (`SPEC.md` §4.8),
 * tracking the fingers as they move and persisting where they stopped once they
 * lift.
 *
 * Handled in the **Initial** pointer pass, and only once a second finger is
 * down: single-finger taps, drags, and scrolls reach the screen untouched,
 * while a pinch that starts inside a scrolling list resizes text instead of
 * being eaten by the scroll — every screen here scrolls, so a Main-pass gesture
 * would almost never win. Events are consumed only from the frame the gesture
 * becomes a pinch, so a two-finger scroll that never spreads still scrolls the
 * page and only a real pinch takes the list's events away from it.
 */
internal fun Modifier.pinchFontSize(
    enabled: () -> Boolean,
    scale: () -> Float,
    onStart: () -> Unit,
    onPreview: (Float) -> Unit,
    onSettled: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    val slopPx = FONT_SCALE_PINCH_SLOP_DP.dp.toPx()
    awaitEachGesture {
        // Unconsumed isn't required: this handler runs ahead of the screen, and
        // a gesture a child will claim is one we simply never join.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Whether the fingers have moved far enough apart to mean it; until they
        // have, nothing resizes and nothing is consumed.
        var pinching = false
        // The gesture's running size, kept unclamped (see [fontScaleAfterZoom]):
        // what is shown and stored is clamped, but the gesture itself has to
        // remember an overshoot so pinching back returns where the fingers say.
        var pinchScale = 0f
        // The separation the slop is measured against, re-taken whenever the set
        // of fingers changes.
        var startSpread = 0f
        var pointerCount = 1
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                // The switch is read per event, not per gesture, so turning pinch
                // off applies to the next pinch without re-arming this handler.
                if (pressed >= 2 && enabled()) {
                    val spread = event.pressedSpread()
                    if (pressed != pointerCount || spread <= 0f) {
                        // A frame where a finger arrived or left compares this
                        // centroid against one measured from a different set of
                        // pointers — a meaningless "zoom" that would jump the size —
                        // and two fingers on the same point have no separation to
                        // measure at all. Let that frame only re-baseline the
                        // gesture.
                        startSpread = spread
                    } else if (!pinching) {
                        if (pinchPassedSlop(startSpread, spread, slopPx)) {
                            pinching = true
                            pinchScale = scale()
                            // Nothing resizes on this frame: the movement that
                            // crossed the slop is what the slop spent, so the size
                            // starts moving from here rather than jumping by it.
                            // The gesture is still announced now rather than at the
                            // first resize, because it owns the size from here — a
                            // value landing from disk in between would be published
                            // and then overwritten by the release (Codex, PR #217).
                            onStart()
                        }
                    } else {
                        // Continuous — no steps to snap to, so the text lands
                        // wherever the fingers put it and the slider can return to
                        // exactly the same size (maintainer, 2026-09-06).
                        pinchScale = fontScaleAfterZoom(pinchScale, event.calculateZoom())
                        onPreview(clampFontScale(pinchScale))
                    }
                    // Only once it is a pinch, so a two-finger scroll below the slop
                    // still reaches the list underneath.
                    if (pinching) event.changes.forEach { it.consume() }
                }
                pointerCount = pressed
            }
        } finally {
            // Clamped like every preview before it, so releasing the fingers
            // leaves the size exactly where the last frame showed it rather than
            // storing an overshoot the screen never drew.
            //
            // **In a `finally`, so a host that goes away mid-pinch still ends
            // the gesture** (Codex, PR #217). The end-condition sheet is removed
            // when its snooze ends, with the fingers possibly still down. That
            // path already ends here — a detached pointer-input node is handed a
            // cancellation whose pointers read released, which leaves by the
            // loop's own exit — so this is not a bug being fixed; it is the
            // outcome stopping depending on Compose's teardown order. Leave the
            // gesture unsettled and the state stays `moving` in the activity
            // that outlived the sheet, deferring every later stored value to
            // fingers that are no longer anywhere. Settling is the right end for
            // a canceled pinch either way: the user did resize the text, and
            // they watched it happen.
            if (pinching) onSettled(clampFontScale(pinchScale))
        }
    }
}

/**
 * How far apart the fingers currently are, in pixels — twice the mean distance
 * from their centroid, which for the two-finger case is the distance between
 * them and stays meaningful for a third that joins them.
 *
 * Measured over the pointers pressed **now**, unlike `calculateCentroidSize`,
 * which counts only pointers that were also pressed on the previous frame and
 * therefore reports zero on the very frame a second finger lands. That frame is
 * exactly where the gesture takes its baseline, and a zero there made a
 * deliberate-looking spread out of the next ordinary movement — the accidental
 * pinches the slop exists to reject.
 */
private fun PointerEvent.pressedSpread(): Float {
    var count = 0
    var sum = Offset.Zero
    changes.forEach { if (it.pressed) { sum += it.position; count++ } }
    if (count < 2) return 0f
    val centroid = sum / count.toFloat()
    var distance = 0f
    changes.forEach { if (it.pressed) distance += (it.position - centroid).getDistance() }
    val spread = distance / count * 2f
    return if (spread.isFinite()) spread else 0f
}
