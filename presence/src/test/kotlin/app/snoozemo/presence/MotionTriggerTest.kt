package app.snoozemo.presence

import app.snoozemo.core.PresenceSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §6.7 motion trigger's lifecycle, over a manual registrar. The part
 * worth testing is not "does it call the sensor" but the two things a
 * one-shot hardware trigger makes easy to get wrong: re-arming after every
 * firing, and never arming twice for a duty the engine restates on every
 * update.
 */
class MotionTriggerTest {

    /** One armed registration, and whether it is still live. */
    private class Registration(val onFired: () -> Unit) {
        var canceled = false
    }

    private class FakeRegistrar : TriggerRegistrar {
        val registrations = mutableListOf<Registration>()

        /** Null makes every arm answer "this device has no such sensor". */
        var available = true

        /** Non-null makes every arm throw, as a refusing platform would. */
        var failure: RuntimeException? = null

        val live: Registration?
            get() = registrations.lastOrNull()?.takeUnless { it.canceled }

        override fun arm(onFired: () -> Unit): AutoCloseable? {
            failure?.let { throw it }
            if (!available) return null
            val registration = Registration(onFired)
            registrations += registration
            return AutoCloseable { registration.canceled = true }
        }
    }

    private val registrar = FakeRegistrar()
    private val signals = mutableListOf<PresenceSignal>()
    private var now = 1_000L

    private val trigger = MotionTrigger(registrar, { now }, signals::add)

    @Test
    fun `resting arms the trigger`() {
        trigger.reconcile(needed = true)

        assertEquals(1, registrar.registrations.size)
        assertTrue(registrar.live != null)
    }

    @Test
    fun `restating the same duty does not arm a second time`() {
        // The engine restates the duty on every update, not only when it
        // changes, so this is the common path rather than an edge case.
        trigger.reconcile(needed = true)
        trigger.reconcile(needed = true)
        trigger.reconcile(needed = true)

        assertEquals(1, registrar.registrations.size)
    }

    @Test
    fun `leaving the resting state cancels the arm`() {
        trigger.reconcile(needed = true)
        val registration = registrar.registrations.single()

        trigger.reconcile(needed = false)

        assertTrue(registration.canceled)
    }

    @Test
    fun `a firing reports motion and re-arms`() {
        // A trigger sensor disarms itself when it fires; without the re-arm
        // the second movement of a snooze would never be heard.
        trigger.reconcile(needed = true)
        now = 5_000L

        registrar.registrations.single().onFired()

        assertEquals(listOf(PresenceSignal.SignificantMotion(5_000L)), signals)
        assertEquals(2, registrar.registrations.size)
        assertTrue(registrar.live != null)
    }

    @Test
    fun `a firing that the engine answers by leaving rest does not re-arm`() {
        // The signal is delivered synchronously, so the engine's own answer
        // can take the duty out of resting inside the callback. Re-arming
        // regardless would spend a registration the next reconcile cancels.
        lateinit var answering: MotionTrigger
        answering = MotionTrigger(registrar, { now }) { signal ->
            signals += signal
            answering.reconcile(needed = false)
        }
        answering.reconcile(needed = true)

        registrar.registrations.single().onFired()

        assertEquals(1, signals.size)
        assertEquals(1, registrar.registrations.size)
    }

    @Test
    fun `a firing after the cancel reports nothing`() {
        trigger.reconcile(needed = true)
        val registration = registrar.registrations.single()
        trigger.reconcile(needed = false)

        registration.onFired()

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `a device with no such sensor is asked once`() {
        registrar.available = false

        trigger.reconcile(needed = true)
        trigger.reconcile(needed = true)
        trigger.reconcile(needed = true)

        // Hardware cannot appear mid-snooze, so re-asking would be pure cost
        // on exactly the devices that can least afford the escalation.
        assertTrue(registrar.registrations.isEmpty())
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `a refusing platform is not retried either`() {
        registrar.failure = RuntimeException("refused")

        trigger.reconcile(needed = true)
        trigger.reconcile(needed = true)

        registrar.failure = null
        trigger.reconcile(needed = true)

        assertTrue(registrar.registrations.isEmpty())
    }

    @Test
    fun `a firing in flight across a re-arm cannot touch the new registration`() {
        // The registrar marshals its callback, so a firing can still be
        // queued when the duty leaves and re-enters the resting state. The
        // stale callback must not clear the newer handle — that would leave a
        // live registration nothing can cancel, and two sensors reporting into
        // one snooze (Codex, PR #119).
        trigger.reconcile(needed = true)
        val stale = registrar.registrations.single()

        trigger.reconcile(needed = false)
        trigger.reconcile(needed = true)
        val current = registrar.registrations.last()

        stale.onFired()

        assertTrue(signals.isEmpty())
        assertEquals(2, registrar.registrations.size)
        // Still cancelable, which is the property the leak would have cost.
        trigger.reconcile(needed = false)
        assertTrue(current.canceled)
    }

    @Test
    fun `close is permanent`() {
        trigger.reconcile(needed = true)
        val registration = registrar.registrations.single()

        trigger.close()
        trigger.reconcile(needed = true)

        assertTrue(registration.canceled)
        assertEquals(1, registrar.registrations.size)
    }
}
