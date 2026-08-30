package app.snoozemo.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The §8.4 recovery watch's lifecycle, over a manual registrar. What is worth
 * testing here is not "does it register a receiver" but the three things a
 * level restated on every engine update makes easy to get wrong: never
 * registering twice for the same outage, letting go the moment the outage
 * clears, and never poking a repair on behalf of a registration the watch has
 * already moved past.
 */
class LocationModeWatchTest {

    /** One live registration, and whether it has been let go. */
    private class Registration(val onEnabled: () -> Unit) {
        var closed = false
    }

    private class FakeRegistrar : LocationModeRegistrar {
        val registrations = mutableListOf<Registration>()

        /** Null makes every watch answer "this cannot be watched at all". */
        var available = true

        /** Non-null makes every watch throw, as a refusing platform would. */
        var failure: RuntimeException? = null

        /** What the post-registration sample sees; null = unreadable. */
        var enabledNow: Boolean? = false

        /** Non-null makes the sample throw, as a refusing platform would. */
        var readFailure: RuntimeException? = null

        /** Runs inside [isEnabled], to drive the ordering hazards. */
        var duringRead: (() -> Unit)? = null

        var reads = 0

        override fun isEnabled(): Boolean? {
            reads++
            duringRead?.invoke()
            readFailure?.let { throw it }
            return enabledNow
        }

        val live: Registration?
            get() = registrations.lastOrNull()?.takeUnless { it.closed }

        override fun watch(onEnabled: () -> Unit): AutoCloseable? {
            failure?.let { throw it }
            if (!available) return null
            val registration = Registration(onEnabled)
            registrations += registration
            return AutoCloseable { registration.closed = true }
        }
    }

    private val registrar = FakeRegistrar()
    private var recoveries = 0

    private val watch = LocationModeWatch(registrar) { recoveries++ }

    @Test
    fun `a platform degradation starts the watch`() {
        watch.reconcile(needed = true)

        assertEquals(1, registrar.registrations.size)
        assertNotNull(registrar.live)
    }

    @Test
    fun `restating the same degradation does not register a second time`() {
        // The monitor restates its level on every update it sends, not only
        // when it changes, so this is the common path rather than an edge
        // case: without idempotence a degraded snooze would register a
        // receiver per fix.
        watch.reconcile(needed = true)
        watch.reconcile(needed = true)
        watch.reconcile(needed = true)

        assertEquals(1, registrar.registrations.size)
    }

    @Test
    fun `a recovered snooze lets the watch go`() {
        watch.reconcile(needed = true)
        val registration = registrar.live

        watch.reconcile(needed = false)

        assertEquals(true, registration?.closed)
        assertNull(registrar.live)
    }

    @Test
    fun `location coming back on pokes the recovery`() {
        watch.reconcile(needed = true)

        registrar.live!!.onEnabled()

        assertEquals(1, recoveries)
    }

    @Test
    fun `the watch stays live across a firing`() {
        // Unlike the one-shot motion trigger, a broadcast watch is not spent
        // by delivering: the outage is not over until a fix actually
        // arrives, and a user toggling location twice must be heard twice.
        watch.reconcile(needed = true)
        registrar.live!!.onEnabled()
        registrar.live!!.onEnabled()

        assertEquals(2, recoveries)
        assertEquals(1, registrar.registrations.size)
    }

    @Test
    fun `a firing from a superseded registration is ignored`() {
        // A broadcast can already be in flight when the level clears, and a
        // stale delivery must not poke a repair for an outage that is over.
        watch.reconcile(needed = true)
        val stale = registrar.registrations.first()
        watch.reconcile(needed = false)
        watch.reconcile(needed = true)

        stale.onEnabled()

        assertEquals(0, recoveries)
    }

    @Test
    fun `re-arming after a recovery registers again`() {
        watch.reconcile(needed = true)
        watch.reconcile(needed = false)
        watch.reconcile(needed = true)

        assertEquals(2, registrar.registrations.size)
        assertNotNull(registrar.live)
    }

    @Test
    fun `close ends the watch and no later reconcile restarts it`() {
        watch.reconcile(needed = true)
        val registration = registrar.live

        watch.close()
        watch.reconcile(needed = true)

        assertEquals(true, registration?.closed)
        assertEquals(1, registrar.registrations.size)
    }

    @Test
    fun `a firing after close pokes nothing`() {
        watch.reconcile(needed = true)
        val registration = registrar.live!!

        watch.close()
        registration.onEnabled()

        assertEquals(0, recoveries)
    }

    @Test
    fun `location already back on when the watch starts is acted on at once`() {
        // The broadcast is not sticky: an outage reported late — a
        // GEOFENCE_NOT_AVAILABLE observation arriving after the user already
        // fixed the setting — leaves nothing to hear, so the sample is the
        // only thing that closes that window.
        registrar.enabledNow = true

        watch.reconcile(needed = true)

        assertEquals(1, recoveries)
        // And it is still watching, since the level clears on evidence, not
        // on this.
        assertNotNull(registrar.live)
    }

    @Test
    fun `location still off when the watch starts pokes nothing`() {
        registrar.enabledNow = false

        watch.reconcile(needed = true)

        assertEquals(0, recoveries)
        assertEquals(1, registrar.reads)
    }

    @Test
    fun `an unreadable setting waits for a broadcast rather than guessing`() {
        registrar.enabledNow = null

        watch.reconcile(needed = true)

        assertEquals(0, recoveries)
        assertNotNull(registrar.live)
    }

    @Test
    fun `a refusing read does not throw out of reconcile`() {
        registrar.readFailure = RuntimeException("refused")

        watch.reconcile(needed = true)

        assertEquals(0, recoveries)
        assertNotNull(registrar.live)
    }

    @Test
    fun `the sample is taken once per registration, not on every update`() {
        registrar.enabledNow = false

        watch.reconcile(needed = true)
        watch.reconcile(needed = true)
        watch.reconcile(needed = true)

        assertEquals(1, registrar.reads)
    }

    @Test
    fun `re-arming after a recovery samples again`() {
        registrar.enabledNow = false
        watch.reconcile(needed = true)
        watch.reconcile(needed = false)

        registrar.enabledNow = true
        watch.reconcile(needed = true)

        assertEquals(1, recoveries)
        assertEquals(2, registrar.reads)
    }

    @Test
    fun `a sample overtaken by a teardown pokes nothing`() {
        // The sample runs outside the lock, so the level can clear — or the
        // whole watch end — between installing the handle and reading the
        // setting. The generation guard is what makes that a no-op rather
        // than a repair poked for a snooze already past it.
        registrar.enabledNow = true
        registrar.duringRead = { watch.reconcile(needed = false) }

        watch.reconcile(needed = true)

        assertEquals(0, recoveries)
    }

    @Test
    fun `an unwatchable platform is not sampled at all`() {
        registrar.available = false
        registrar.enabledNow = true

        watch.reconcile(needed = true)

        // Nothing is listening, so acting on "already on" once and then never
        // hearing another change would be worse than leaving it to the
        // backstop.
        assertEquals(0, recoveries)
        assertEquals(0, registrar.reads)
    }

    @Test
    fun `an unwatchable platform is asked once, not on every update`() {
        registrar.available = false

        watch.reconcile(needed = true)
        watch.reconcile(needed = true)
        watch.reconcile(needed = true)

        assertEquals(0, registrar.registrations.size)
    }

    @Test
    fun `a refusing platform is recorded rather than thrown out of reconcile`() {
        // The caller is the monitor's `send`, on the path that reports a
        // degradation — a throw escaping here would lose the very update
        // saying the snooze is in trouble.
        registrar.failure = RuntimeException("refused")

        watch.reconcile(needed = true)
        registrar.failure = null
        watch.reconcile(needed = true)

        assertEquals(0, registrar.registrations.size)
    }
}
