package app.snoozemo.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.DEFAULT_FONT_SCALE
import app.snoozemo.core.MAX_FONT_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the chosen text size is kept (`SPEC.md` §4.8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontSizeStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = FontSizeStore(context, FILE)

    @Test
    fun `an install that has never chosen reads the system size, with pinch on`() {
        val read = store().read()
        assertEquals(DEFAULT_FONT_SCALE, read.scale, 0.0001f)
        assertTrue("pinch is how the setting is found at all", read.pinchEnabled)
    }

    @Test
    fun `a chosen size survives to a later reader`() {
        assertTrue(store().setScale(1.23f))
        // A separate instance, as a cold process would be.
        assertEquals(1.23f, store().read().scale, 0.0001f)
    }

    @Test
    fun `a size beyond this build's range is clamped on read`() {
        // Written by a build with a wider range, or by hand: it must not size a
        // screen past what this one lays out.
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat("scale", 4f).commit()
        assertEquals(MAX_FONT_SCALE, store().read().scale, 0.0001f)
    }

    @Test
    fun `turning the pinch off is remembered on its own`() {
        assertTrue(store().setScale(1.4f))
        assertTrue(store().setPinchEnabled(false))
        val read = store().read()
        assertEquals(1.4f, read.scale, 0.0001f)
        assertEquals(false, read.pinchEnabled)
    }

    private companion object {
        // Its own file, so these assertions aren't racing the running app's.
        const val FILE = "font_size_test"
    }
}
