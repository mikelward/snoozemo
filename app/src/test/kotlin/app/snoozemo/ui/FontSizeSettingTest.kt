package app.snoozemo.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.DEFAULT_FONT_SCALE
import app.snoozemo.core.FontSizeSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the app is drawn at while writes are in flight (`SPEC.md` §4.8).
 *
 * The queue is driven by hand rather than by the real worker: the cases here
 * are about the *order* two outstanding writes settle in, and a race would make
 * them pass or fail by luck.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontSizeSettingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * A store whose writes fail on demand, which no real `SharedPreferences`
     * will do.
     *
     * Refusal is set per field rather than by one flag: these cases queue two
     * writes and drain them afterwards, so a single flag would be read at drain
     * time and decide both of them together — the interleaving they exist to
     * test.
     */
    private class FakeStore : FontSizeWriter {
        var stored = FontSizeSettings()
        var refuseScale = false
        var refusePinch = false

        override fun read(): FontSizeSettings = stored

        override fun setScale(scale: Float): Boolean {
            // A refused write leaves the stored value alone, as the real store's
            // restore does.
            if (refuseScale) return false
            stored = stored.copy(scale = scale)
            return true
        }

        override fun setPinchEnabled(enabled: Boolean): Boolean {
            if (refusePinch) return false
            stored = stored.copy(pinchEnabled = enabled)
            return true
        }
    }

    private val store = FakeStore()
    private val queue = ArrayDeque<Runnable>()

    @Before
    fun setUp() {
        FontSizeSetting.resetForTest()
        FontSizeSetting.writerFor = { store }
        FontSizeSetting.dispatch = { queue += it }
    }

    @After
    fun tearDown() {
        FontSizeSetting.resetForTest()
    }

    private fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    @Test
    fun `a chosen size is shown at once, before it reaches the store`() {
        FontSizeSetting.setScale(context, 1.3f)

        assertEquals(1.3f, FontSizeSetting.current.scale, 0.0001f)
        assertEquals(DEFAULT_STORED, store.stored.scale, 0.0001f)

        drain()

        assertEquals(1.3f, store.stored.scale, 0.0001f)
        assertEquals(1.3f, FontSizeSetting.current.scale, 0.0001f)
        assertFalse(FontSizeSetting.scaleSaveRefused)
    }

    @Test
    fun `a refused write leaves the screen showing what is stored, and says so`() {
        store.refuseScale = true

        FontSizeSetting.setScale(context, 1.3f)
        drain()

        assertEquals(DEFAULT_STORED, FontSizeSetting.current.scale, 0.0001f)
        assertTrue(FontSizeSetting.scaleSaveRefused)
    }

    @Test
    fun `an earlier failure does not undo a later choice that stuck`() {
        // Codex, PR #217. The switch is tapped, the slider is released, and only
        // then does the queue drain — with the first write refused. Rolling back
        // to the value captured before it would show the old size over a stored
        // new one until the next launch.
        store.refusePinch = true
        FontSizeSetting.setPinchEnabled(context, false)
        FontSizeSetting.setScale(context, 1.45f)

        drain()

        // The size the user last asked for, which is also what is stored.
        assertEquals(1.45f, FontSizeSetting.current.scale, 0.0001f)
        assertEquals(1.45f, store.stored.scale, 0.0001f)
        // The pinch switch never landed, so it reads as it is stored — on.
        assertTrue(FontSizeSetting.current.pinchEnabled)
        // And the switch still says its own save was refused (Codex, PR #217):
        // one flag for both fields let the later success report for the earlier
        // failure, so the switch sprang back with nothing saying why.
        assertTrue(FontSizeSetting.pinchSaveRefused)
        // The size did save, and says so.
        assertFalse(FontSizeSetting.scaleSaveRefused)
    }

    @Test
    fun `a field that saves clears only its own failure`() {
        store.refuseScale = true
        FontSizeSetting.setScale(context, 1.3f)
        drain()
        assertTrue(FontSizeSetting.scaleSaveRefused)

        store.refuseScale = false
        FontSizeSetting.setScale(context, 1.35f)
        drain()

        assertFalse(FontSizeSetting.scaleSaveRefused)
        assertEquals(1.35f, FontSizeSetting.current.scale, 0.0001f)
    }

    @Test
    fun `two failures land on what is stored, not on a value in between`() {
        store.refuseScale = true
        FontSizeSetting.setScale(context, 1.3f)
        FontSizeSetting.setScale(context, 1.45f)

        drain()

        assertEquals(DEFAULT_STORED, FontSizeSetting.current.scale, 0.0001f)
        assertTrue(FontSizeSetting.scaleSaveRefused)
    }

    @Test
    fun `the warm read never overwrites a size chosen before it ran`() {
        store.stored = FontSizeSettings(scale = 1.5f)
        FontSizeSetting.warm(context)
        // The user moves the slider while the warm is still queued.
        FontSizeSetting.setScale(context, 0.9f)

        drain()

        assertEquals(0.9f, FontSizeSetting.current.scale, 0.0001f)
        assertEquals(0.9f, store.stored.scale, 0.0001f)
    }

    private companion object {
        /** Whatever the app starts at, so a changed default is not a test edit. */
        val DEFAULT_STORED = DEFAULT_FONT_SCALE
    }
}
