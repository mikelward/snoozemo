package app.snoozemo.ui

import android.os.Looper.getMainLooper
import android.os.SystemClock
import app.snoozemo.core.DepartureObservation
import app.snoozemo.snooze.DepartureObservations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The screen follows the departure readout rather than reading it once
 * (`SPEC.md` §4.6).
 *
 * The fixes that move this number arrive while the screen is up, from the
 * service's own presence callback — so a value read at `onStart` and left
 * there would show the first fix of a snooze forever. The other half is the
 * teardown: a watch left registered past `onStop` writes to a screen nobody
 * can see, and — after a configuration change — to a dead one.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityDepartureReadoutTest {

    @After
    fun tearDown() {
        // Process-wide by design, so one test's reading must not reach the next.
        DepartureObservations.clear()
    }

    private fun observation(distanceM: Double) = DepartureObservation(
        distanceM = distanceM,
        accuracyM = 10f,
        radiusM = 150,
        elapsedRealtimeMs = 0L,
    )

    @Test
    fun `a reading published before the screen opened is picked up`() {
        // Through the watch's own registration callback rather than a separate
        // read, so there is no window between subscribing and reading for a fix
        // to land in and be missed by both (Codex, PR #210).
        DepartureObservations.publish(observation(200.0))

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertEquals(200.0, activity.departure?.distanceM)
    }

    @Test
    fun `a reading published while the screen is up reaches it`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        DepartureObservations.publish(observation(320.0))
        shadowOf(getMainLooper()).idle()

        assertEquals(320.0, activity.departure?.distanceM)
    }

    @Test
    fun `a reading that just arrived is not read as coming from the future`() {
        // `now` ticks once a minute, which is right for the countdown beside
        // this and wrong for this: a fix stamped after the cached reading has
        // a negative age, which `isFresh` rejects — so every new reading was
        // withheld for up to a minute, and the line already on screen vanished
        // as a newer fix replaced it (Codex, PR #210).
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Far enough past the last tick to be stale, not far enough to trigger
        // the next one.
        shadowOf(getMainLooper()).idleFor(Duration.ofSeconds(30))
        val arrivedAt = SystemClock.elapsedRealtime()

        DepartureObservations.publish(observation(200.0).copy(elapsedRealtimeMs = arrivedAt))
        shadowOf(getMainLooper()).idle()

        assertEquals(200.0, activity.departure?.distanceM)
        assertTrue(
            "the reading must be fresh against the clock the screen tests it with",
            activity.departure!!.isFresh(activity.now.uptimeMillis),
        )
    }

    @Test
    fun `a snooze ending takes the reading off the screen`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        DepartureObservations.publish(observation(320.0))
        shadowOf(getMainLooper()).idle()

        DepartureObservations.clear()
        shadowOf(getMainLooper()).idle()

        assertNull(activity.departure)
    }

    @Test
    fun `a stopped screen is no longer written to`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        controller.stop()

        DepartureObservations.publish(observation(320.0))
        shadowOf(getMainLooper()).idle()

        assertNull(activity.departure)
    }
}
