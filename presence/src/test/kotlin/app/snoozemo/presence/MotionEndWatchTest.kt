package app.snoozemo.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `When I move` (SPEC.md §4.4), over a manual registrar.
 *
 * The lifecycle itself belongs to `MotionTrigger` and is covered there. What
 * is this class's own is the part that differs: a firing is a *verdict* here,
 * it may only be reported once however many times the sensor re-arms, and the
 * watch has to be inert until the snooze asks for it.
 */
class MotionEndWatchTest {

    private class Registration(val onFired: () -> Unit) {
        var canceled = false
    }

    private class FakeRegistrar : TriggerRegistrar {
        val registrations = mutableListOf<Registration>()

        /** Null makes every arm answer "this device has no such sensor". */
        var available = true

        val live: Registration?
            get() = registrations.lastOrNull()?.takeUnless { it.canceled }

        override fun arm(onFired: () -> Unit): AutoCloseable? {
            if (!available) return null
            val registration = Registration(onFired)
            registrations += registration
            return AutoCloseable { registration.canceled = true }
        }
    }

    private val registrar = FakeRegistrar()
    private var moves = 0
    private var now = 1_000L

    private val watch = MotionEndWatch(registrar, { now }) { moves++ }

    @Test
    fun `nothing is armed until the snooze asks`() {
        assertTrue(registrar.registrations.isEmpty())
        assertEquals(0, moves)
    }

    @Test
    fun `a firing ends the snooze`() {
        watch.reconcile(needed = true)
        assertNotNull(registrar.live)

        registrar.live!!.onFired()

        assertEquals(1, moves)
    }

    @Test
    fun `a second firing does not end a second time`() {
        // The trigger re-arms itself after every firing — the duty cycle needs
        // that — and the ending this starts is asynchronous, so a user who
        // takes another step while the snooze is winding down really can fire
        // it twice. The second one would retry an ending from a reason the
        // first had already begun.
        watch.reconcile(needed = true)
        registrar.live!!.onFired()
        assertNotNull("the trigger re-arms after firing", registrar.live)

        registrar.live!!.onFired()

        assertEquals(1, moves)
    }

    @Test
    fun `restating the choice does not arm a second sensor`() {
        // The service restates this on every transition rather than tracking
        // the edges, so this is the ordinary path, not an edge case.
        watch.reconcile(needed = true)
        watch.reconcile(needed = true)
        watch.reconcile(needed = true)

        assertEquals(1, registrar.registrations.size)
    }

    @Test
    fun `turning the choice off cancels the registration`() {
        watch.reconcile(needed = true)

        watch.reconcile(needed = false)

        assertNull(registrar.live)
    }

    @Test
    fun `closing the watch ends it for good`() {
        watch.reconcile(needed = true)
        val registration = registrar.live!!

        watch.close()

        assertTrue(registration.canceled)
        watch.reconcile(needed = true)
        assertNull(registrar.live)
    }

    @Test
    fun `a device with no such sensor never fires, and says so`() {
        // Silence alone is survivable — the cap still bounds the snooze — but
        // the caller has to be able to tell, or the record keeps a choice and
        // the card promises an exit that can never happen (Codex, PR #252).
        registrar.available = false

        watch.reconcile(needed = true)

        assertEquals(0, moves)
        assertFalse("nothing is registered", watch.listening)
    }

    @Test
    fun `an armed watch reports that it is listening`() {
        // The other direction, so the check above cannot pass by always
        // answering no.
        watch.reconcile(needed = true)

        assertTrue(watch.listening)
    }

    @Test
    fun `a disarmed watch stops reporting that it is listening`() {
        watch.reconcile(needed = true)

        watch.reconcile(needed = false)

        assertFalse(watch.listening)
    }
}
