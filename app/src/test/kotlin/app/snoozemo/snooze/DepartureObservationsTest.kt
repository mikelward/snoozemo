package app.snoozemo.snooze

import app.snoozemo.core.DepartureObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The in-memory hand-off between the service and whatever is on screen
 * (`SPEC.md` §4.6).
 *
 * Its whole job is to be raced safely: the service publishes from a presence
 * callback off the main thread, and the activity that reads it can be replaced
 * mid-snooze by a configuration change.
 */
class DepartureObservationsTest {

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
    fun `registering delivers what is already there`() {
        // Subscribe-then-read had a window between the two, and a publish
        // landing in it reached neither — leaving a screen on the older number
        // until the next fix, which the resting duty cycle can put ten minutes
        // away (Codex, PR #210). Delivering on registration removes the window
        // rather than narrowing it.
        DepartureObservations.publish(observation(200.0))
        var seen: Double? = null

        DepartureObservations.watch { seen = DepartureObservations.latest()?.distanceM }

        assertEquals(200.0, seen)
    }

    @Test
    fun `registering with nothing there delivers nothing`() {
        var calls = 0
        var seen: DepartureObservation? = observation(1.0)

        DepartureObservations.watch {
            calls++
            seen = DepartureObservations.latest()
        }

        assertEquals(1, calls)
        assertNull(seen)
    }

    @Test
    fun `a later publish reaches the registered listener`() {
        var seen: Double? = null
        DepartureObservations.watch { seen = DepartureObservations.latest()?.distanceM }

        DepartureObservations.publish(observation(320.0))

        assertEquals(320.0, seen)
    }

    @Test
    fun `an earlier handle does not unregister a later listener`() {
        // What a configuration change produces: the replacement registers, then
        // the outgoing instance's `onStop` closes its own handle.
        var stale = 0
        val first = DepartureObservations.watch { stale++ }
        var fresh: Double? = null
        DepartureObservations.watch { fresh = DepartureObservations.latest()?.distanceM }
        val staleAfterRegistration = stale

        first.close()
        DepartureObservations.publish(observation(410.0))

        assertEquals(410.0, fresh)
        assertEquals("the replaced listener is not called again", staleAfterRegistration, stale)
    }

    @Test
    fun `clearing notifies, so a screen showing a distance stops`() {
        DepartureObservations.publish(observation(200.0))
        var seen: DepartureObservation? = null
        DepartureObservations.watch { seen = DepartureObservations.latest() }

        DepartureObservations.clear()

        assertNull(seen)
    }
}
