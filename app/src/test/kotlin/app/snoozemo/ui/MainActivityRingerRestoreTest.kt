package app.snoozemo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * When the stuck-ringer notice's tap is acted on (SPEC.md §5.9 rule 5).
 *
 * Android 17 drops a `setRingerMode` from an app that is not visible, so the
 * hand-back has to start once the activity is resumed — never from `onCreate`
 * or `onNewIntent` — and a request must survive the activity being recreated
 * before it gets there (Codex, PR #327, two rounds). Observed through the
 * companion seam rather than the ringer itself, because the test application
 * switches the real hand-back off for every activity test.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityRingerRestoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var restores = 0

    private fun restoreIntent() =
        Intent(context, MainActivity::class.java).setAction(ACTION_RESTORE_RINGER)

    @Before
    fun countRestores() {
        restores = 0
        MainActivity.testRingerRestore = { restores++ }
    }

    @After
    fun resetSeam() {
        MainActivity.testRingerRestore = null
    }

    @Test
    fun `a cold tap restores once the screen is resumed, not before`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, restoreIntent()).create()
        assertEquals("not from onCreate, before the screen is visible", 0, restores)

        controller.start().resume()

        assertEquals(1, restores)
    }

    @Test
    fun `a launcher open does not restore`() {
        Robolectric.buildActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).setup()

        assertEquals(0, restores)
    }

    @Test
    fun `a tap reaching an open screen restores on the next resume`() {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java),
        ).setup()

        controller.pause()
        controller.newIntent(restoreIntent())
        assertEquals("not from onNewIntent", 0, restores)
        controller.resume()

        assertEquals(1, restores)
    }

    @Test
    fun `a request not yet acted on survives the screen being recreated`() {
        val first = Robolectric.buildActivity(MainActivity::class.java, restoreIntent()).create()
        val saved = Bundle()
        first.saveInstanceState(saved)

        // Recreated from saved state with an intent that is not the tap's —
        // the pending request has to come from the bundle.
        Robolectric.buildActivity(MainActivity::class.java, Intent(context, MainActivity::class.java))
            .create(saved).start().resume()

        assertEquals(1, restores)
    }
}
