package app.snoozemo.snooze

import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.EndCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The sheet's commit lifecycle, which both ways of arming now share
 * (SPEC.md §4.4).
 *
 * Driven through injected seams rather than the service, so the states that
 * matter — a commit in flight, a refusal, a snooze that ended underneath —
 * are reachable without a device or a Robolectric frame.
 */
class EndChoiceControllerTest {

    private val now: Instant = Instant.parse("2026-01-01T13:12:00Z")
    private val zone: ZoneId = ZoneId.of("UTC")

    private class Seams(var now: Instant) {
        var accepted = true
        var sent: Instant? = null
        var watches = 0
        var closes = 0
        var dismissals = 0
        var onOutcome: ((EndChoiceResult) -> Unit)? = null
    }

    private fun controller(seams: Seams) = EndChoiceController(
        ceilingAt = { at -> at.plus(ActiveSnooze.DEFAULT_CAP) },
        chooseEnd = { at -> seams.sent = at; seams.accepted },
        watchOutcome = { handler ->
            seams.watches++
            seams.onOutcome = handler
            AutoCloseable { seams.closes++ }
        },
        onDismiss = { seams.dismissals++ },
        clock = { seams.now },
        zone = { zone },
    )

    private fun seeded(seams: Seams): EndChoiceController =
        controller(seams).also { it.seed() }

    @Test
    fun `an accepted choice dismisses the sheet`() {
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commit(controller.endCondition!!.endsAt)
        assertTrue("the rows are inert while it is out", controller.committing)
        seams.onOutcome!!(EndChoiceResult.APPLIED)

        assertFalse(controller.committing)
        assertEquals(1, seams.dismissals)
        assertNull(controller.endCondition)
    }

    @Test
    fun `a snooze that ended underneath dismisses too`() {
        // Not retryable and not a failure of the choice: whatever ended the
        // snooze has posted its own card, and standing there offering a retry
        // over a snooze that is already over is a dead end.
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commit(controller.endCondition!!.endsAt)
        seams.onOutcome!!(EndChoiceResult.GONE)

        assertEquals(1, seams.dismissals)
        assertFalse(controller.commitFailed)
    }

    @Test
    fun `a refusal keeps the sheet up and says so`() {
        // A dismissal on a refused tap is indistinguishable from one on an
        // accepted tap, so the sheet stays and reports instead.
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commit(controller.endCondition!!.endsAt)
        seams.onOutcome!!(EndChoiceResult.REFUSED)

        assertEquals("nothing dismissed", 0, seams.dismissals)
        assertTrue(controller.commitFailed)
        assertFalse(controller.committing)
        assertEquals("still offering something", controller.endCondition!!.endsAt, seams.sent)
    }

    @Test
    fun `a service that never dispatched settles the commit itself`() {
        // No outcome is coming, so waiting for one would leave the rows inert
        // forever with nothing able to free them.
        val seams = Seams(now)
        seams.accepted = false
        val controller = seeded(seams)

        controller.commit(controller.endCondition!!.endsAt)

        assertFalse("not left waiting on an answer that cannot arrive", controller.committing)
        assertTrue(controller.commitFailed)
    }

    @Test
    fun `a second tap cannot stack a second commit`() {
        val seams = Seams(now)
        val controller = seeded(seams)
        val at = controller.endCondition!!.endsAt

        controller.commit(at)
        controller.commit(at)

        assertEquals("one commit in flight, one watch", 1, seams.watches)
    }

    @Test
    fun `re-seeding moves an open sheet onto the new arm`() {
        // A second arm while the sheet is up armed a *new* snooze, so an hour
        // from the first tap is no longer the offer being made.
        val seams = Seams(now)
        val controller = seeded(seams)
        val first = controller.endCondition!!.endsAt

        val later = now.plus(java.time.Duration.ofMinutes(40))
        controller.seed(later)

        assertTrue("the offer moved with the arm", controller.endCondition!!.endsAt.isAfter(first))
    }

    @Test
    fun `re-seeding clears a standing failure`() {
        val seams = Seams(now)
        seams.accepted = false
        val controller = seeded(seams)
        controller.commit(controller.endCondition!!.endsAt)
        assertTrue(controller.commitFailed)

        controller.seed()

        assertFalse("a fresh offer is not a failed one", controller.commitFailed)
    }

    @Test
    fun `the outcome watch is closed on every settled commit`() {
        // It is the one thing here that leaks if a path forgets it.
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commit(controller.endCondition!!.endsAt)
        seams.onOutcome!!(EndChoiceResult.REFUSED)
        controller.commit(controller.endCondition!!.endsAt)
        seams.onOutcome!!(EndChoiceResult.APPLIED)

        assertEquals(2, seams.watches)
        assertEquals(2, seams.closes)
    }

    @Test
    fun `a restored commit with an answer waiting settles on it`() {
        // The rotation case: the service answered in the gap where no watch
        // existed, so the replacement takes the held result and acts on it.
        val seams = Seams(now)
        val controller = controller(seams)
        EndChoiceOutcome.report(EndChoiceResult.APPLIED)

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            configurationChange = true,
        )

        assertEquals("an applied change dismisses, whenever it arrived", 1, seams.dismissals)
        assertFalse(controller.committing)
    }

    @Test
    fun `a configuration change keeps a live commit single-flight`() {
        // The process never went away, so the request is still out and the
        // outcome channel names no request. Coming back retryable would let a
        // second tap dispatch a second one, and the first answer would then
        // arrive at the retry's watch and be read as its own — an old
        // `APPLIED` dismissing over a newer choice the service refused
        // (Codex, PR #152).
        val seams = Seams(now)
        val controller = controller(seams)
        assertNull("nothing held over from another test", EndChoiceOutcome.takePending())

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            configurationChange = true,
        )

        assertTrue("still committing, so the rows stay inert", controller.committing)
        controller.commit(controller.endCondition!!.endsAt)
        assertNull("and a retry cannot dispatch a second request", seams.sent)
        assertEquals("nor open a second watch", 1, seams.watches)
    }

    @Test
    fun `a restored commit still hears a late answer`() {
        // The watch kept above is what hears it: the service answers a moment
        // after the replacement is up, and the sheet must not sit open over a
        // snooze already refined.
        val seams = Seams(now)
        val controller = controller(seams)

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            configurationChange = true,
        )
        requireNotNull(seams.onOutcome)(EndChoiceResult.APPLIED)

        assertEquals(1, seams.dismissals)
    }

    @Test
    fun `a commit restored after process death comes back retryable`() {
        // Nothing is left to hear from: `EndChoiceOutcome` is process-scoped,
        // so nothing is held, and the request died with the process. Restoring
        // as committing would leave every row inert and — since the swipe veto
        // keys off the same flag — the sheet undismissable too.
        val seams = Seams(now)
        val controller = controller(seams)
        assertNull("nothing held over from another test", EndChoiceOutcome.takePending())

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            configurationChange = false,
        )

        assertFalse("usable again, not stuck waiting on an answer that cannot come", controller.committing)
        assertNotNull("and still offering what it was", controller.endCondition)
        assertEquals("and watching nothing, since nothing can answer", 0, seams.watches)
    }

    @Test
    fun `stepping moves the offer and stops at its own edges`() {
        val seams = Seams(now)
        val controller = seeded(seams)
        val start = controller.endCondition!!

        controller.stepUp()
        assertTrue(controller.endCondition!!.endsAt.isAfter(start.endsAt))
        controller.stepDown()
        assertEquals(start.endsAt, controller.endCondition!!.endsAt)
    }

    @Test
    fun `stepping with no sheet up is inert`() {
        // Reachable from a host whose composition outlives the offer.
        val seams = Seams(now)
        val controller = controller(seams)

        controller.stepUp()
        controller.stepDown()

        assertNull(controller.endCondition)
    }
}

/** The two pure decisions the sheet is gated on, which both hosts now share. */
class EndConditionChoiceTest {

    private val now: Instant = Instant.parse("2026-01-01T13:12:00Z")

    private fun snooze(capIn: java.time.Duration) = ActiveSnooze(
        anchor = app.snoozemo.core.Anchor(capturedAt = now),
        startedAt = now,
        capExpiresAt = now.plus(capIn),
        mode = app.snoozemo.core.TrackingMode.DURATION_ONLY,
    )

    @Test
    fun `no record means no sheet`() {
        // Fails closed: no sheet over a correctly armed snooze is exactly what
        // the setting being off would have given.
        assertFalse(EndCondition.offersAChoice(null, now))
    }

    @Test
    fun `a cap inside the floor leaves nothing to choose`() {
        // The service declines anything inside `MIN_CAP`, and the only value
        // above it is past the cap — a screen the user cannot answer.
        val nearlyOver = snooze(ActiveSnooze.MIN_CAP.minusMinutes(1))

        assertFalse(EndCondition.offersAChoice(nearlyOver, now))
    }

    @Test
    fun `a cap above the floor does offer a choice`() {
        assertTrue(EndCondition.offersAChoice(snooze(ActiveSnooze.MIN_CAP.plusMinutes(1)), now))
    }

    @Test
    fun `the ceiling is the running snooze's own cap, not a fresh one`() {
        // A duplicate arm keeps the snooze already running, so the record can
        // have started long ago; seeded against a constant the sheet would
        // offer an hour over a snooze with ten minutes left.
        val old = snooze(java.time.Duration.ofMinutes(45))

        assertEquals(old.capExpiresAt, EndCondition.ceilingFor(old, now))
    }

    @Test
    fun `with no record the ceiling falls back to a full cap`() {
        assertEquals(now.plus(ActiveSnooze.DEFAULT_CAP), EndCondition.ceilingFor(null, now))
    }
}
