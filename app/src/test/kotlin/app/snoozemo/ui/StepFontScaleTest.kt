package app.snoozemo.ui

import com.mikelward.androidzoom.DEFAULT_FONT_SCALE
import com.mikelward.androidzoom.MAX_FONT_SCALE
import com.mikelward.androidzoom.MIN_FONT_SCALE
import com.mikelward.androidzoom.fontScalePercent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Settings row's `−` / `+`: one shown percent per tap (`SPEC.md` §4.8). */
class StepFontScaleTest {
    @Test
    fun `a whole percent steps by exactly one either way`() {
        assertEquals(1.26f, stepFontScale(1.25f, 1), 0.00001f)
        assertEquals(1.24f, stepFontScale(1.25f, -1), 0.00001f)
        assertEquals(126, fontScalePercent(stepFontScale(1.25f, 1)))
    }

    @Test
    fun `a size between two percents steps from the one the row shows`() {
        // 115.3% reads as 115%, so + lands on 116% rather than 116.3%.
        assertEquals(1.16f, stepFontScale(1.153f, 1), 0.00001f)
        assertEquals(1.14f, stepFontScale(1.153f, -1), 0.00001f)
        // 115.6% reads as 116%, so − lands on 115%.
        assertEquals(1.15f, stepFontScale(1.156f, -1), 0.00001f)
    }

    @Test
    fun `both ends clamp to the offered range`() {
        assertEquals(MAX_FONT_SCALE, stepFontScale(MAX_FONT_SCALE, 1))
        assertEquals(MIN_FONT_SCALE, stepFontScale(MIN_FONT_SCALE, -1))
        assertEquals(MAX_FONT_SCALE, stepFontScale(1.595f, 1))
        assertEquals(MIN_FONT_SCALE, stepFontScale(0.804f, -1))
        // And leaving an end works: the buttons are only off at the end itself.
        assertEquals(1.59f, stepFontScale(MAX_FONT_SCALE, -1), 0.00001f)
        assertEquals(0.81f, stepFontScale(MIN_FONT_SCALE, 1), 0.00001f)
    }

    @Test
    fun `a non-finite size steps from the default, like the clamp`() {
        val fromDefault = stepFontScale(DEFAULT_FONT_SCALE, 1)
        assertEquals(fromDefault, stepFontScale(Float.NaN, 1))
        assertEquals(fromDefault, stepFontScale(Float.POSITIVE_INFINITY, 1))
    }
}
