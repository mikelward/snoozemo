package app.snoozemo.ui

import app.snoozemo.core.FontSizeSettings
import app.snoozemo.core.MAX_FONT_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the screen is drawn at while a gesture is in flight (`SPEC.md` §4.8).
 *
 * Robolectric only because the app module's tests run there; nothing here
 * touches Android — the state is a plain holder over Compose snapshot state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontSizeStateTest {

    private val settled = mutableListOf<Float>()
    private val pinchChanges = mutableListOf<Boolean>()

    private fun state(initial: FontSizeSettings = FontSizeSettings()) = FontSizeState(
        initial = initial,
        onScaleSettled = { settled += it },
        onPinchEnabledChange = { pinchChanges += it },
    )

    @Test
    fun `a preview shows the size without persisting it`() {
        val state = state()

        state.preview(1.3f)

        assertEquals(1.3f, state.scale, 0.0001f)
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `a stored value arriving mid-gesture does not move the text`() {
        // Codex, PR #217: the startup warm read and a rolled-back save both
        // arrive asynchronously, so applying one mid-drag snapped the text and
        // the slider thumb away from the fingers dragging them.
        val state = state()
        state.preview(1.3f)

        state.onPersisted(FontSizeSettings(scale = 0.9f, pinchEnabled = false))

        assertEquals(1.3f, state.scale, 0.0001f)
        // The switch is left alone too: flipping it mid-pinch would stop the
        // gesture the user is still making.
        assertTrue(state.pinchEnabled)
    }

    @Test
    fun `the gesture's own value wins once it ends`() {
        val state = state()
        state.preview(1.3f)
        state.onPersisted(FontSizeSettings(scale = 0.9f))

        state.commit(1.35f)

        assertEquals(1.35f, state.scale, 0.0001f)
        assertEquals(listOf(1.35f), settled)
    }

    @Test
    fun `a gesture that has begun holds a stored value even before it moves`() {
        // Codex, PR #217: a pinch owns the size from the frame it crosses the
        // slop, which resizes nothing — so a value landing from disk in that
        // window used to be applied and then overwritten by the release.
        val state = state()
        state.startGesture()

        state.onPersisted(FontSizeSettings(scale = 0.9f, pinchEnabled = false))

        assertEquals(1f, state.scale, 0.0001f)
        assertTrue(state.pinchEnabled)
    }

    @Test
    fun `a gesture that moved nothing keeps what arrived and writes nothing`() {
        // Codex, PR #217: the slop-crossing frame resizes nothing, so a pinch
        // can begin and end having changed nothing at all. `commit` used to
        // write the size captured at that crossing and drop what had arrived in
        // between — a two-finger gesture over a cold start would overwrite the
        // user's saved size with the default it had been drawn at.
        val state = state()
        state.startGesture()
        state.onPersisted(FontSizeSettings(scale = 1.4f, pinchEnabled = false))

        // The size the crossing captured, unchanged since.
        state.commit(1f)

        assertEquals(1.4f, state.scale, 0.0001f)
        assertFalse(state.pinchEnabled)
        // Nothing to persist: the store already holds this.
        assertTrue(settled.toString(), settled.isEmpty())
    }

    @Test
    fun `a pinch taking over a slider drag still persists what is on screen`() {
        // Codex, PR #217: a second finger on a slider drag hands the gesture to
        // the pinch, which owns the size from the frame it crosses the slop —
        // and that frame moves nothing. Clearing the flag there let the pinch
        // disown the drag's work, leaving the size the user could see unwritten
        // until the process restarted. It is cleared by a write, not by a
        // gesture beginning.
        val state = state()
        state.preview(1.2f)

        state.startGesture()
        state.commit(1.2f)

        assertEquals(1.2f, state.scale, 0.0001f)
        assertEquals(listOf(1.2f), settled)
    }

    @Test
    fun `a gesture that moved keeps its own size over one that arrived`() {
        // The other side of the case above, so it cannot be satisfied by never
        // writing: once the fingers have moved the size, that is the value.
        val state = state()
        state.startGesture()
        state.preview(1.2f)
        state.onPersisted(FontSizeSettings(scale = 1.4f, pinchEnabled = false))

        state.commit(1.2f)

        assertEquals(1.2f, state.scale, 0.0001f)
        assertFalse(state.pinchEnabled)
        assertEquals(listOf(1.2f), settled)
    }

    @Test
    fun `the switch that arrived mid-gesture lands once the gesture ends`() {
        // Codex, PR #217: dropping what arrived mid-drag stranded the switch.
        // A drag released back at the stored size leaves the persisted value
        // structurally equal to the one before it, so the effect that feeds
        // `onPersisted` never runs again and the switch stayed on for the life
        // of the activity.
        val state = state()
        state.preview(1.3f)
        state.onPersisted(FontSizeSettings(scale = 1f, pinchEnabled = false))

        state.commit(1f)

        // The user's own size stands — the gesture is what they were doing.
        assertEquals(1f, state.scale, 0.0001f)
        assertEquals(listOf(1f), settled)
        // And the switch they never touched reads as it is stored.
        assertFalse(state.pinchEnabled)
    }

    @Test
    fun `a stored value arriving after the gesture is applied`() {
        val state = state()
        state.preview(1.3f)
        state.commit(1.3f)

        // What the store actually held — a refused save, restored.
        state.onPersisted(FontSizeSettings(scale = 1f, pinchEnabled = false))

        assertEquals(1f, state.scale, 0.0001f)
        assertFalse(state.pinchEnabled)
    }

    @Test
    fun `a size outside the offered range is clamped wherever it comes from`() {
        val state = state()

        state.preview(9f)
        assertEquals(MAX_FONT_SCALE, state.scale, 0.0001f)

        state.commit(9f)
        assertEquals(MAX_FONT_SCALE, state.scale, 0.0001f)
        assertEquals(listOf(MAX_FONT_SCALE), settled)

        state.onPersisted(FontSizeSettings(scale = 9f))
        assertEquals(MAX_FONT_SCALE, state.scale, 0.0001f)
    }

    @Test
    fun `the switch reports its change and shows it at once`() {
        val state = state()

        state.choosePinch(false)

        assertFalse(state.pinchEnabled)
        assertEquals(listOf(false), pinchChanges)
    }
}
