package app.snoozemo.presence.geofence

import app.snoozemo.core.Fix
import app.snoozemo.core.PresenceSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The burst's lifecycle over its seams — the part two review rounds found
 * real bugs in, driven here as plain values: a manual scheduler stands in
 * for the main thread, a scripted requester for the platform.
 */
class CheckingFixesTest {

    /** Runs immediates inline; delayed work waits for [advance]. */
    private class ManualScheduler : BurstScheduler {
        val delayed = mutableListOf<Pair<Long, () -> Unit>>()

        override fun post(block: () -> Unit) = block()

        override fun postDelayed(delayMs: Long, block: () -> Unit): AutoCloseable {
            val entry = delayMs to block
            delayed += entry
            return AutoCloseable { delayed.remove(entry) }
        }

        /** Fires the oldest pending task whose delay matches [delayMs]. */
        fun fire(delayMs: Long) {
            val entry = delayed.first { it.first == delayMs }
            delayed.remove(entry)
            entry.second()
        }
    }

    /** Answers each request from a script; unanswered ones stay in flight. */
    private class ScriptedRequester : FixRequester {
        val pending = mutableListOf<(FixOutcome) -> Unit>()
        var canceled = 0

        override fun request(onOutcome: (FixOutcome) -> Unit): AutoCloseable {
            pending += onOutcome
            return AutoCloseable { canceled++ }
        }

        fun answer(outcome: FixOutcome) {
            pending.removeAt(0).invoke(outcome)
        }
    }

    private val scheduler = ManualScheduler()
    private val requester = ScriptedRequester()
    private val signals = mutableListOf<PresenceSignal>()
    private var permissionLost = 0
    private var servicesOff = 0

    private var elapsedMs = 100_000L

    private val fixes = CheckingFixes(
        scheduler,
        requester,
        readElapsedRealtimeMs = { elapsedMs },
        onSignal = { signals += it },
        onPermissionLost = { permissionLost++ },
        onServicesOff = { servicesOff++ },
    )

    private fun fix(atMs: Long) = Fix(lat = 0.0, lon = 0.0, accuracyM = 20f, elapsedRealtimeMs = atMs)

    @Test
    fun `a delivered fix reaches the engine and the next request is paced at the confirmation gap`() {
        fixes.start()

        requester.answer(FixOutcome.Delivered(fix(atMs = 101_000)))

        assertEquals(listOf<PresenceSignal>(PresenceSignal.FixArrived(fix(101_000))), signals)
        assertTrue(scheduler.delayed.any { it.first == CheckingCadence.CONFIRM_SPACING_MS })
    }

    @Test
    fun `unanswered requests degrade honestly and back off`() {
        fixes.start()

        repeat(CheckingCadence.BACKOFF_AFTER) { round ->
            requester.answer(FixOutcome.NothingRecoverable)
            if (round < CheckingCadence.BACKOFF_AFTER - 1) {
                scheduler.fire(CheckingCadence.CONFIRM_SPACING_MS)
            }
        }

        // Every miss reached the engine — its counter is what arms the grace
        // period — and the next request now waits at the backoff rate.
        assertEquals(
            CheckingCadence.BACKOFF_AFTER,
            signals.count { it is PresenceSignal.FixUnavailable },
        )
        assertTrue(scheduler.delayed.any { it.first == CheckingCadence.BACKOFF_SPACING_MS })
    }

    @Test
    fun `a platform recovery asks again instead of serving out the backoff`() {
        // The gap the mode-change watch alone could not close (Codex, PR
        // #139): an outage that starts mid-check leaves the duty ACTIVE, so
        // the resting probe is a no-op and the burst is the only thing that
        // can ask.
        fixes.start()
        repeat(CheckingCadence.BACKOFF_AFTER) { round ->
            requester.answer(FixOutcome.ServicesOff)
            if (round < CheckingCadence.BACKOFF_AFTER - 1) {
                scheduler.fire(CheckingCadence.CONFIRM_SPACING_MS)
            }
        }
        assertTrue(scheduler.delayed.any { it.first == CheckingCadence.BACKOFF_SPACING_MS })
        val asked = requester.pending.size

        fixes.retryNow()

        // Asked immediately, and the backoff that was pending is gone rather
        // than left to fire a duplicate request later.
        assertEquals(asked + 1, requester.pending.size)
        assertTrue(scheduler.delayed.none { it.first == CheckingCadence.BACKOFF_SPACING_MS })
    }

    @Test
    fun `a recovery paces the next request at the confirmation gap again`() {
        fixes.start()
        repeat(CheckingCadence.BACKOFF_AFTER) { round ->
            requester.answer(FixOutcome.ServicesOff)
            if (round < CheckingCadence.BACKOFF_AFTER - 1) {
                scheduler.fire(CheckingCadence.CONFIRM_SPACING_MS)
            }
        }

        fixes.retryNow()
        requester.answer(FixOutcome.NothingRecoverable)

        assertTrue(scheduler.delayed.any { it.first == CheckingCadence.CONFIRM_SPACING_MS })
    }

    @Test
    fun `a recovery while a request is in flight forgives the backoff without asking twice`() {
        // Cutting a live request short to ask again would spend two requests
        // on one moment; its own answer is already arriving.
        fixes.start()
        repeat(CheckingCadence.BACKOFF_AFTER - 1) {
            requester.answer(FixOutcome.ServicesOff)
            scheduler.fire(CheckingCadence.CONFIRM_SPACING_MS)
        }
        val inFlight = requester.pending.size

        fixes.retryNow()

        assertEquals(inFlight, requester.pending.size)

        // And that answer's follow-up is paced at the confirmation gap, not
        // the backoff it would otherwise have crossed into.
        requester.answer(FixOutcome.ServicesOff)
        assertTrue(scheduler.delayed.any { it.first == CheckingCadence.CONFIRM_SPACING_MS })
    }

    @Test
    fun `a recovery while resting is the probe's business, not the burst's`() {
        fixes.retryNow()

        assertEquals(0, requester.pending.size)
    }

    @Test
    fun `a recovery after close asks nothing`() {
        fixes.start()
        fixes.close()
        // The start's own request is still in the script's list — cancelling
        // it does not un-ask it — so what this asserts is that no *further*
        // request is made.
        val asked = requester.pending.size

        fixes.retryNow()

        assertEquals(asked, requester.pending.size)
    }

    @Test
    fun `the ceiling settles a request that never answers`() {
        fixes.start()

        scheduler.fire(CheckingFixes.REQUEST_CEILING_MS)

        assertEquals(1, requester.canceled)
        assertTrue(signals.single() is PresenceSignal.FixUnavailable)
    }

    @Test
    fun `an answer after the ceiling is dropped, not double-counted`() {
        fixes.start()
        scheduler.fire(CheckingFixes.REQUEST_CEILING_MS)

        requester.answer(FixOutcome.Delivered(fix(atMs = 101_000)))

        assertTrue(signals.single() is PresenceSignal.FixUnavailable)
    }

    @Test
    fun `a lost grant stops the burst and reports once`() {
        fixes.start()

        requester.answer(FixOutcome.PermissionLost)
        fixes.start()

        assertEquals(1, permissionLost)
        assertTrue(signals.isEmpty())
        // Suspended: the restart queued nothing while the grant is gone.
        assertTrue(requester.pending.isEmpty())
        assertTrue(scheduler.delayed.isEmpty())
    }

    @Test
    fun `the burst comes back when the grant does`() {
        // The regression this guards (Codex, PR #149): permission loss used to
        // set the same permanent flag teardown uses, which was fine only while
        // it *ended* the snooze. Now that the snooze survives, a burst that
        // stayed dead would leave an anchor with no SSID unable to confirm a
        // geofence exit for the life of the process — snoozed to the cap while
        // a repaired registration reported `FULL`.
        fixes.start()
        requester.answer(FixOutcome.PermissionLost)

        fixes.resume()
        // `resume` unblocks the guard; it does not restart the burst, because
        // `settle` already stopped it. Asserted rather than assumed: the first
        // version of this test went straight to `start()`, which hid that the
        // monitor's recovery path called only `resume()` and so left an active
        // departure check with nothing asking (Codex, PR #149, second pass).
        // The caller owes the restart, and this is where that contract lives.
        assertTrue(requester.pending.isEmpty())

        fixes.start()

        assertTrue(requester.pending.isNotEmpty())
    }

    @Test
    fun `resume cannot resurrect a closed burst`() {
        // Teardown stays final, which is the half PR #72 needed: a resume
        // racing a close must not leave background location running for as
        // long as the process.
        fixes.start()
        fixes.close()
        // The pre-close request is still on the script's list; what this
        // asserts is that no *new* one is queued after the resume.
        requester.pending.clear()

        fixes.resume()
        fixes.start()

        assertTrue(requester.pending.isEmpty())
    }

    @Test
    fun `the resting probe comes back with the grant too`() {
        // `sanityCheck` guards on the same flag, so the backstop's own probe
        // would stay suspended alongside the burst.
        requester.answer(FixOutcome.PermissionLost.also { fixes.start() })
        requester.pending.clear()

        fixes.resume()
        fixes.sanityCheck()

        assertTrue(requester.pending.isNotEmpty())
    }

    @Test
    fun `services off is said immediately and the burst keeps asking`() {
        fixes.start()

        requester.answer(FixOutcome.ServicesOff)

        assertEquals(1, servicesOff)
        // The engine still hears an unanswered fix — its counter is what
        // arms the grace period — and the next request stays scheduled.
        assertTrue(signals.single() is PresenceSignal.FixUnavailable)
        assertTrue(scheduler.delayed.isNotEmpty())
    }

    @Test
    fun `pause cancels pending work and a later start resumes`() {
        fixes.start()
        fixes.pause()

        assertEquals(1, requester.canceled)
        assertTrue(scheduler.delayed.isEmpty())

        fixes.start()
        // A fresh request with its own ceiling: the pause was a pause.
        assertEquals(2, requester.pending.size)
        assertTrue(scheduler.delayed.any { it.first == CheckingFixes.REQUEST_CEILING_MS })
    }

    @Test
    fun `close cannot be undone by a start already behind it`() {
        // The revival race (Codex, PR #72): teardown posts its stop, a racing
        // callback posts a start behind it. The flag is synchronous, so the
        // start finds it whatever the queue order was.
        fixes.start()
        fixes.close()
        fixes.start()

        assertEquals(1, requester.canceled)
        assertTrue(scheduler.delayed.isEmpty())
        assertTrue(requester.pending.size == 1)
    }

    @Test
    fun `a resting probe delivers one fix and chains nothing`() {
        // The §6.10 backstop's probe: one reading for the engine to test, no
        // burst started, no follow-up — the next probe is the next wake's.
        fixes.sanityCheck()

        requester.answer(FixOutcome.Delivered(fix(atMs = 101_000)))

        assertEquals(listOf<PresenceSignal>(PresenceSignal.FixArrived(fix(101_000))), signals)
        assertTrue(scheduler.delayed.isEmpty())
    }

    @Test
    fun `a resting probe is skipped while a burst is asking`() {
        fixes.start()

        fixes.sanityCheck()

        // Only the burst's own request stands — the burst already asks
        // faster than the probe would.
        assertEquals(1, requester.pending.size)
    }

    @Test
    fun `a burst starting mid-probe supersedes it rather than running beside it`() {
        // The reverse of the skip above: an exit arriving inside the probe's
        // request window must not leave two requests answering for the same
        // moment — one failure counted twice against the engine's three-fix
        // bar, and a second request spent (Codex, PR #75).
        fixes.sanityCheck()
        assertEquals(1, requester.pending.size)

        fixes.start()

        // The probe's platform request was canceled, its late answer is
        // dropped, and only the burst's outcome reaches the engine.
        assertEquals(1, requester.canceled)
        requester.answer(FixOutcome.NothingRecoverable)
        requester.answer(FixOutcome.NothingRecoverable)
        assertEquals(1, signals.count { it is PresenceSignal.FixUnavailable })
    }

    @Test
    fun `an unanswered probe still reaches the engine's counter`() {
        // Delivered, never swallowed, same as the burst's rule: a place where
        // nothing can get a fix is exactly what the counting notices.
        fixes.sanityCheck()

        requester.answer(FixOutcome.NothingRecoverable)

        assertTrue(signals.single() is PresenceSignal.FixUnavailable)
        assertTrue(scheduler.delayed.isEmpty())
    }

    @Test
    fun `a lost grant found by the probe is fatal, not a quiet miss`() {
        // The probe re-checks the grants each wake — this is what makes a
        // mid-snooze revocation detectable at the backstop's cadence.
        fixes.sanityCheck()

        requester.answer(FixOutcome.PermissionLost)
        fixes.start()

        assertEquals(1, permissionLost)
        assertTrue("dead means dead for the burst too", requester.pending.isEmpty())
    }

    @Test
    fun `a synchronously answered request still settles exactly once`() {
        // The permission checks answer before any platform call exists; the
        // token identity is what keeps that from being dropped or doubled.
        val sync = FixRequester { onOutcome ->
            onOutcome(FixOutcome.NothingRecoverable)
            AutoCloseable { }
        }
        val burst = CheckingFixes(
            scheduler,
            sync,
            readElapsedRealtimeMs = { elapsedMs },
            onSignal = { signals += it },
            onPermissionLost = { permissionLost++ },
            onServicesOff = { servicesOff++ },
        )

        burst.start()

        assertTrue(signals.single() is PresenceSignal.FixUnavailable)
        assertTrue(scheduler.delayed.any { it.first == CheckingCadence.CONFIRM_SPACING_MS })
    }
}
