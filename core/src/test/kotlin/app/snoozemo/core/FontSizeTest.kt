package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text-size math (`SPEC.md` §4.8), on the JVM with no Compose host. */
class FontSizeTest {

    @Test
    fun `sizes outside the offered range clamp to its ends`() {
        assertEquals(MIN_FONT_SCALE, clampFontScale(0.1f), DELTA)
        assertEquals(MAX_FONT_SCALE, clampFontScale(9f), DELTA)
        assertEquals(1.2f, clampFontScale(1.2f), DELTA)
    }

    @Test
    fun `a non-finite size falls back to the system size`() {
        // A corrupt or hand-edited preference must not size text to infinity,
        // nor to NaN, which lays out as nothing at all.
        assertEquals(DEFAULT_FONT_SCALE, clampFontScale(Float.NaN), DELTA)
        assertEquals(DEFAULT_FONT_SCALE, clampFontScale(Float.POSITIVE_INFINITY), DELTA)
        assertEquals(DEFAULT_FONT_SCALE, clampFontScale(Float.NEGATIVE_INFINITY), DELTA)
    }

    @Test
    fun `the size is continuous, not stepped`() {
        // What the maintainer asked for over the sibling Simmo repo's 5% steps
        // (2026-09-06): a small movement of the fingers moves the text by a
        // small amount rather than by a notch or not at all, in both
        // directions. Two nearby zooms have to land on two different sizes.
        val grown = fontScaleAfterZoom(1f, 1.02f)
        val grownMore = fontScaleAfterZoom(1f, 1.03f)
        assertTrue("$grown was not below $grownMore", grown < grownMore)
        assertTrue("a 2% spread moved nothing", grown > 1f)
        val shrunk = fontScaleAfterZoom(1f, 1f / 1.02f)
        assertTrue("a 2% pinch moved nothing", shrunk < 1f)
        // And the value is kept as it lands, not rounded to anything.
        assertEquals(1.0404f, grown, DELTA)
    }

    @Test
    fun `a pinch moves the size in force, amplified by the gain`() {
        // The gain is an exponent on what the fingers did, so a 1.1x spread
        // moves the size by 1.1 squared.
        assertEquals(1.21f, fontScaleAfterZoom(1f, 1.1f), DELTA)
        assertEquals(0.675f, fontScaleAfterZoom(1.2f, 0.75f), DELTA)
        // Amplified, not re-scaled: fingers that stand still leave the size
        // exactly where it was, whatever the gain.
        assertEquals(1.2f, fontScaleAfterZoom(1.2f, 1f), DELTA)
    }

    @Test
    fun `the gain makes an ordinary spread cover most of the range`() {
        // What the gain is for. Tracking the fingers 1:1, a 1.4x spread — a
        // comfortable one-hand pinch — moved the size 40% of the way up an
        // 80%-160% range, so an ordinary gesture read as the pinch not working.
        val span = MAX_FONT_SCALE - MIN_FONT_SCALE
        val covered = clampFontScale(fontScaleAfterZoom(MIN_FONT_SCALE, 1.4f)) - MIN_FONT_SCALE
        assertTrue("a 1.4x spread covered only $covered of $span", covered > span * 0.9f)
        // And symmetrically the other way.
        val shrunk = MAX_FONT_SCALE - clampFontScale(fontScaleAfterZoom(MAX_FONT_SCALE, 1f / 1.4f))
        assertTrue("a 1.4x pinch covered only $shrunk of $span", shrunk > span * 0.9f)
    }

    @Test
    fun `a pinch past an end keeps its overshoot, so reversing it comes back`() {
        // Clamping the running size would make the clamped value the origin of
        // the next step: spread past the ceiling, bring the fingers back, and
        // the size would land at 80% instead of 100%.
        val spread = fontScaleAfterZoom(1f, 1.3f)
        assertEquals(1.69f, spread, DELTA)
        assertEquals(1f, fontScaleAfterZoom(spread, 1f / 1.3f), DELTA)
        // What the user sees is still clamped — the overshoot lives only in the
        // gesture.
        assertEquals(MAX_FONT_SCALE, clampFontScale(spread), DELTA)
    }

    @Test
    fun `the overshoot is bounded, so a wild spread has a short way back`() {
        assertEquals(MAX_PINCH_SCALE, fontScaleAfterZoom(1f, 100f), DELTA)
        assertEquals(MIN_PINCH_SCALE, fontScaleAfterZoom(1f, 0.001f), DELTA)
    }

    @Test
    fun `the gain does not change how far past an end the fingers may stray`() {
        // The bound is finger travel, not font size, so making the gesture more
        // aggressive doesn't also make it less forgiving.
        val forgiven = fontScaleAfterZoom(MAX_FONT_SCALE, FONT_SCALE_OVERSHOOT)
        assertEquals(MAX_PINCH_SCALE, forgiven, DELTA)
        assertEquals(MAX_FONT_SCALE, fontScaleAfterZoom(forgiven, 1f / FONT_SCALE_OVERSHOOT), DELTA)
    }

    @Test
    fun `a degenerate pinch leaves the size alone`() {
        // Two fingers landing on the same point report a zoom of zero (or NaN
        // where the centroid has no size at all); either would collapse the text.
        assertEquals(1.2f, fontScaleAfterZoom(1.2f, 0f), DELTA)
        assertEquals(1.2f, fontScaleAfterZoom(1.2f, -1f), DELTA)
        assertEquals(1.2f, fontScaleAfterZoom(1.2f, Float.NaN), DELTA)
    }

    @Test
    fun `a small gesture is not a pinch`() {
        // The accidental-gesture guard (maintainer, 2026-09-06). Fingers 300px
        // apart that drift to 310px have not asked for anything.
        val slop = 60f
        assertFalse(pinchPassedSlop(startSpreadPx = 300f, spreadPx = 310f, slopPx = slop))
        assertFalse(pinchPassedSlop(startSpreadPx = 300f, spreadPx = 290f, slopPx = slop))
        // Two fingers moving together — a two-finger scroll — never change
        // their separation, so no amount of travel makes one.
        assertFalse(pinchPassedSlop(startSpreadPx = 300f, spreadPx = 300f, slopPx = slop))
    }

    @Test
    fun `a deliberate one is, in both directions`() {
        val slop = 60f
        assertTrue(pinchPassedSlop(startSpreadPx = 300f, spreadPx = 360f, slopPx = slop))
        assertTrue(pinchPassedSlop(startSpreadPx = 300f, spreadPx = 240f, slopPx = slop))
        // Exactly the slop counts: the threshold is where it starts working,
        // not where it starts being ignored.
        assertTrue(pinchPassedSlop(startSpreadPx = 100f, spreadPx = 160f, slopPx = slop))
    }

    @Test
    fun `a spread that cannot be measured is not a pinch`() {
        // A centroid with no size reports NaN; that is not permission to resize.
        assertFalse(pinchPassedSlop(Float.NaN, 300f, 60f))
        assertFalse(pinchPassedSlop(300f, Float.NaN, 60f))
    }

    @Test
    fun `the percentage beside the slider reads against the system size`() {
        assertEquals(100, fontScalePercent(1f))
        assertEquals(115, fontScalePercent(1.15f))
        assertEquals(80, fontScalePercent(MIN_FONT_SCALE))
        assertEquals(160, fontScalePercent(MAX_FONT_SCALE))
    }

    private companion object {
        const val DELTA = 0.0001f
    }
}
