package app.snoozemo.ui

import android.os.Looper.getMainLooper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * `MainScreen`'s remaining-time display must not go stale while the screen
 * stays open with nothing else changing (Codex, PR #87): [MainActivity.now]
 * is what a `remaining` computed for the composable is read against, and
 * this is what proves it actually advances rather than being read once at
 * `onStart` and left there.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityStatusTickTest {

    @Test
    fun `now advances once a minute while the screen is visible`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val before = activity.now

        shadowOf(getMainLooper()).idleFor(Duration.ofMinutes(1))

        assertTrue(activity.now.wallMillis > before.wallMillis)
    }

    @Test
    fun `the tick stops once the screen is no longer visible`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        controller.stop()
        val before = activity.now

        // Long enough that a still-running tick would have fired several
        // times over; a stopped screen ticking on is the wasted-wakeup half
        // of the bug this field exists to avoid the other half of.
        shadowOf(getMainLooper()).idleFor(Duration.ofMinutes(5))

        assertEquals(before, activity.now)
    }
}
