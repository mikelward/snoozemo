package app.snoozemo.snooze

import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.EndCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
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

    /** The request a restored sheet says it was waiting on. */
    private val RESUMED_REQUEST = 7L

    private val now: Instant = Instant.parse("2026-01-01T13:12:00Z")
    private val zone: ZoneId = ZoneId.of("UTC")

    private class Seams(var now: Instant) {
        var accepted = true
        var sent: Instant? = null
        var watches = 0
        var closes = 0
        var dismissals = 0
        var onOutcome: ((EndChoiceResult) -> Unit)? = null
        var watchedRequestId: Long = 0L
        var sentRequestId: Long = 0L
        var sentForSnooze: Instant? = null
        /** Whether the departure restore was the thing dispatched. */
        var restored = false
        /** Whether the motion exit was the thing dispatched. */
        var motion = false
        /** What the host would answer if asked what is running right now. */
        var live: ActiveSnooze? = null
    }

    @org.junit.Before
    fun clearChannel() = EndChoiceOutcome.reset()

    private fun controller(seams: Seams, offersToStart: Boolean = false) = EndChoiceController(
        surface = "a test host",
        offersToStart = offersToStart,
        currentRecord = { seams.live },
        chooseEnd = { at, requestId, forSnooze ->
            seams.sent = at
            seams.sentRequestId = requestId
            seams.sentForSnooze = forSnooze
            seams.accepted
        },
        restoreDeparture = { requestId, forSnooze ->
            seams.restored = true
            seams.sentRequestId = requestId
            seams.sentForSnooze = forSnooze
            seams.accepted
        },
        chooseMotionEnd = { requestId, forSnooze ->
            seams.motion = true
            seams.sentRequestId = requestId
            seams.sentForSnooze = forSnooze
            seams.accepted
        },
        watchOutcome = { requestId, handler ->
            seams.watches++
            seams.watchedRequestId = requestId
            seams.onOutcome = handler
            AutoCloseable { seams.closes++ }
        },
        onDismiss = { seams.dismissals++ },
        clock = { seams.now },
        zone = { zone },
    )

    /**
     * A snooze to offer times against; its `startedAt` is the offer's identity.
     *
     * Its cap is [capIn] from [startedAt] and its §7 backstop [ceilingIn] from
     * it.
     *
     * The backstop is the sheet's ceiling and what decides whether a choice is
     * offered at all, because a chosen time moves the cap either way. Defaults
     * to the cap, which is what an unshortened snooze carries.
     */
    private fun snoozeAt(
        startedAt: Instant,
        capIn: Duration = ActiveSnooze.DEFAULT_CAP,
        ceilingIn: Duration = capIn,
    ) =
        ActiveSnooze(
            anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
            startedAt = startedAt,
            capExpiresAt = startedAt.plus(capIn),
            mode = TrackingMode.DURATION_ONLY,
            capCeilingAt = startedAt.plus(ceilingIn),
        )

    private fun seeded(seams: Seams): EndChoiceController =
        controller(seams).also { it.seed(snoozeAt(seams.now)) }

    /** The idle screen's offer: a host that offers to start, seeded from nothing. */
    private fun offerToStart(seams: Seams): EndChoiceController =
        controller(seams, offersToStart = true).also { it.seed(null, seams.now) }

    @Test
    fun `an offer to start names no snooze and caps at the default`() {
        // The idle screen's rows (SPEC.md §4.4, maintainer, 2026-09-10): there
        // is no record to name or to read a cap from, so the offer is the
        // clock's — bounded by the cap a snooze started now would carry.
        val seams = Seams(now)
        val controller = offerToStart(seams)

        assertTrue(controller.startsASnooze)
        assertNull(controller.offerFor)
        assertEquals(now.plus(ActiveSnooze.DEFAULT_CAP), controller.endCondition!!.ceiling)
        assertEquals(now.plus(ActiveSnooze.MIN_CAP), controller.endCondition!!.floor)
    }

    @Test
    fun `a host that does not offer to start never holds one`() {
        // The sheets are offered over a snooze that exists; for them a null
        // record still means the offer is over, exactly as before.
        val seams = Seams(now)
        val controller = controller(seams).also { it.seed(null, seams.now) }

        assertFalse(controller.startsASnooze)
        controller.reconcile(null, seams.now)

        assertNull("dropped: nothing to refine", controller.endCondition)
        assertEquals(1, seams.dismissals)
    }

    @Test
    fun `an offer to start stands while nothing runs and goes when something does`() {
        val seams = Seams(now)
        val controller = offerToStart(seams)

        controller.reconcile(null, seams.now)
        assertNotNull("nothing running is exactly what it is offered for", controller.endCondition)
        assertEquals(0, seams.dismissals)

        controller.reconcile(snoozeAt(seams.now), seams.now)
        assertNull("a snooze arrived — the tile, say — so the host seeds that one's rows instead", controller.endCondition)
        assertEquals(1, seams.dismissals)
    }

    @Test
    fun `an offer to start that has gone stale is reseeded from the clock`() {
        // Seeded from the clock alone, so the clock is the only thing that
        // stales it — and a screen left open on a desk is where it does.
        val seams = Seams(now)
        val controller = offerToStart(seams)
        val first = controller.endCondition!!.endsAt

        seams.now = now.plus(Duration.ofMinutes(35))
        controller.reconcile(null, seams.now)

        assertTrue("moved later, against the clock as it is now", controller.endCondition!!.endsAt.isAfter(first))
        assertTrue(controller.startsASnooze)
        assertEquals(0, seams.dismissals)
    }

    @Test
    fun `an offer to start is rebuilt whole against the clock, keeping a refusal`() {
        // Its ceiling has to equal the cap the service would set, and that
        // moves with the clock — forward a minute a minute, back across a
        // wall-clock change — so the rebuild is unconditional rather than
        // waiting for the time to fall inside the floor (Codex, PR #256).
        // The refusal is the one thing the user has not yet acted on.
        val seams = Seams(now)
        val controller = offerToStart(seams)
        controller.commit(controller.endCondition!!.endsAt)
        seams.onOutcome!!(EndChoiceResult.REFUSED)
        seams.now = now.plus(Duration.ofMinutes(5))

        controller.refreshStart(seams.now)

        assertEquals(seams.now.plus(ActiveSnooze.DEFAULT_CAP), controller.endCondition!!.ceiling)
        assertTrue("the refusal is still showing", controller.commitFailed)
        assertTrue(controller.startsASnooze)
    }

    @Test
    fun `a rebuild leaves a refinement, and a commit in flight, alone`() {
        val seams = Seams(now)
        val refining = seeded(seams)
        val standing = refining.endCondition
        refining.refreshStart(now.plus(Duration.ofMinutes(5)))
        assertEquals("a refinement is not clock-derived", standing, refining.endCondition)

        val starting = offerToStart(seams)
        starting.commit(starting.endCondition!!.endsAt)
        val out = starting.endCondition
        starting.refreshStart(now.plus(Duration.ofMinutes(5)))
        assertEquals("its answer is coming and settles it", out, starting.endCondition)
    }

    @Test
    fun `a start goes out with no identity and waits for the answer`() {
        val seams = Seams(now)
        val controller = offerToStart(seams)

        controller.commit(controller.endCondition!!.endsAt)

        assertNull("no snooze to claim", seams.sentForSnooze)
        assertEquals(controller.endCondition!!.endsAt, seams.sent)
        assertTrue(controller.committing)
    }

    @Test
    fun `a start that took, or found a snooze already running, ends the offer`() {
        // `APPLIED` and `GONE` mean the same simpler thing here: a snooze is
        // running now, so the offer is over and the host reads the record
        // that replaces it.
        for (answer in listOf(EndChoiceResult.APPLIED, EndChoiceResult.GONE)) {
            val seams = Seams(now)
            val controller = offerToStart(seams)
            controller.commit(controller.endCondition!!.endsAt)

            seams.onOutcome!!(answer)

            assertNull("$answer", controller.endCondition)
            assertEquals("$answer", 1, seams.dismissals)
        }
    }

    @Test
    fun `a refused start leaves the offer standing with the failure showing`() {
        // Refused means nothing is running, so there is still something to
        // offer; the rows stay for a retry and say what happened.
        val seams = Seams(now)
        val controller = offerToStart(seams)
        controller.commit(controller.endCondition!!.endsAt)

        seams.onOutcome!!(EndChoiceResult.REFUSED)

        assertTrue(controller.commitFailed)
        assertTrue(controller.startsASnooze)
        assertEquals(0, seams.dismissals)
    }

    @Test
    fun `a refused start reseeds a stale offer without a record to rebuild from`() {
        // The running rows rebuild a stale offer from the live record; this
        // one has none and needs none — the clock seeded it and the clock
        // reseeds it, so the retry is not the same tap failing forever.
        val seams = Seams(now)
        val controller = offerToStart(seams)
        val first = controller.endCondition!!.endsAt
        controller.commit(first)
        seams.now = now.plus(Duration.ofMinutes(35))

        seams.onOutcome!!(EndChoiceResult.REFUSED)

        assertTrue(controller.commitFailed)
        assertTrue(controller.endCondition!!.endsAt.isAfter(first))
        assertTrue(controller.startsASnooze)
    }

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
    fun `choosing a departure restores rather than naming a time`() {
        // The target is the record's own ceiling, which only the service can
        // read: a time computed here would be a guess about a backstop a clock
        // change may already have moved.
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commitDeparture()

        assertTrue(seams.restored)
        assertNull("no time was sent with it", seams.sent)
        assertTrue(controller.committing)
        assertEquals("and it carries the offer's identity", seams.now, seams.sentForSnooze)
    }

    @Test
    fun `a departure choice waits for the answer like any other`() {
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commitDeparture()
        assertEquals("nothing dismissed until the service answers", 0, seams.dismissals)
        seams.onOutcome!!(EndChoiceResult.APPLIED)

        assertEquals(1, seams.dismissals)
        assertFalse(controller.committing)
    }

    @Test
    fun `choosing until I move goes out like a departure and waits for the answer`() {
        // A plain choice has no state of its own to fall back on, so a
        // refusal has to come back here like a declined time's does (Codex,
        // PR #255): the same request id, the same identity, the same inert
        // rows until the service answers.
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commitMotionEnd()

        assertTrue(seams.motion)
        assertNull("no time was sent with it", seams.sent)
        assertFalse("and it is not the departure restore", seams.restored)
        assertEquals("it carries the offer's identity", seams.now, seams.sentForSnooze)
        assertEquals("and the request the watch is on", seams.watchedRequestId, seams.sentRequestId)
        assertTrue(controller.committing)
        assertEquals("nothing dismissed until the service answers", 0, seams.dismissals)

        seams.onOutcome!!(EndChoiceResult.REFUSED)

        assertFalse(controller.committing)
        assertTrue("a refusal is shown where the tap happened", controller.commitFailed)
        assertEquals("and the rows stay up for a retry", 0, seams.dismissals)
    }

    @Test
    fun `a second choice cannot stack on an unanswered departure`() {
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commitDeparture()
        seams.restored = false
        controller.commit(controller.endCondition!!.endsAt)

        assertNull("the time never went out", seams.sent)
        assertFalse(seams.restored)
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

        val later = now.plus(Duration.ofMinutes(40))
        controller.seed(snoozeAt(later), later)

        assertTrue("the offer moved with the arm", controller.endCondition!!.endsAt.isAfter(first))
    }

    @Test
    fun `re-seeding clears a standing failure`() {
        val seams = Seams(now)
        seams.accepted = false
        val controller = seeded(seams)
        controller.commit(controller.endCondition!!.endsAt)
        assertTrue(controller.commitFailed)

        controller.seed(snoozeAt(now))

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
        EndChoiceOutcome.report(RESUMED_REQUEST, EndChoiceResult.APPLIED)

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            offeredFor = now,
            configurationChange = true,
            requestId = RESUMED_REQUEST,
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
        assertNull("nothing held over from another test", EndChoiceOutcome.takePending(RESUMED_REQUEST))

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            offeredFor = now,
            configurationChange = true,
            requestId = RESUMED_REQUEST,
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
            offeredFor = now,
            configurationChange = true,
            requestId = RESUMED_REQUEST,
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
        assertNull("nothing held over from another test", EndChoiceOutcome.takePending(RESUMED_REQUEST))

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            offeredFor = now,
            configurationChange = false,
            requestId = RESUMED_REQUEST,
        )

        assertFalse("usable again, not stuck waiting on an answer that cannot come", controller.committing)
        assertNotNull("and still offering what it was", controller.endCondition)
        assertEquals("and watching nothing, since nothing can answer", 0, seams.watches)
    }

    @Test
    fun `an answer to somebody else's commit is not taken`() {
        // Both hosts can have a commit outstanding: the app screen's sheet in
        // the main task, the tile's alive in its own `singleInstance` task
        // behind it. A restore that took whatever the channel held would settle
        // on the other sheet's answer (Codex, PR #152).
        val seams = Seams(now)
        val controller = controller(seams)
        EndChoiceOutcome.report(RESUMED_REQUEST + 1, EndChoiceResult.APPLIED)

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            offeredFor = now,
            configurationChange = true,
            requestId = RESUMED_REQUEST,
        )

        assertEquals("not settled by an answer addressed elsewhere", 0, seams.dismissals)
        assertTrue("still waiting on its own request", controller.committing)
        assertEquals("and watching for that one", RESUMED_REQUEST, seams.watchedRequestId)
        assertEquals(
            "the other host's answer is left for it",
            EndChoiceResult.APPLIED,
            EndChoiceOutcome.takePending(RESUMED_REQUEST + 1),
        )
    }

    @Test
    fun `each commit sends and watches its own request`() {
        // The identity is what the service echoes back, so it has to be the
        // one this commit is listening for.
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.commit(controller.endCondition!!.endsAt)

        assertTrue("a real identity, not the absent-request sentinel", seams.sentRequestId != 0L)
        assertEquals(seams.sentRequestId, seams.watchedRequestId)
        assertEquals(seams.sentRequestId, controller.committingRequestId)
        assertEquals(
            "and names the snooze it was made for, so the service can refuse a mismatch",
            controller.offerFor,
            seams.sentForSnooze,
        )
    }

    @Test
    fun `a saved commit naming no request comes back retryable`() {
        // A bundle written before this identity existed, or one where the
        // commit never got as far as minting one. Nothing can be addressed to
        // it, so waiting would be waiting on an answer that cannot arrive.
        val seams = Seams(now)
        val controller = controller(seams)

        controller.restore(
            EndCondition.seededAt(now, now.plus(ActiveSnooze.DEFAULT_CAP), zone),
            wasCommitting = true,
            failed = false,
            offeredFor = now,
            configurationChange = true,
            requestId = 0L,
        )

        assertFalse(controller.committing)
        assertEquals("and watching nothing", 0, seams.watches)
    }

    @Test
    fun `a refusal rebuilds the offer from the backstop as it is now`() {
        // A wall-clock change reconciles `capExpiresAt` and `capCeilingAt` onto
        // the new frame while `startedAt` stays put, so the ceiling this offer
        // was built with can name an earlier instant than the snooze actually
        // allows.
        // Reseeding against that cached value could put the replacement below
        // the floor as well, refusing every retry (Codex, PR #155).
        val seams = Seams(now)
        val controller = controller(seams)
        val tight = snoozeAt(now, capIn = ActiveSnooze.MIN_CAP.plusMinutes(20))
        controller.seed(tight, now)
        // The clock moves on past the offer, and the cap moves with it.
        val later = controller.endCondition!!.endsAt.plus(Duration.ofMinutes(1))
        // Both move, as `ActiveSnooze.reconciledOnto` moves them: the backstop
        // is carried in the record precisely so it stays in the deadline's
        // frame rather than drifting off `startedAt`.
        val reconciled = tight.copy(
            capExpiresAt = later.plus(Duration.ofHours(4)),
            capCeilingAt = later.plus(Duration.ofHours(4)),
        )
        seams.live = reconciled
        seams.now = later
        seams.accepted = true
        controller.commit(controller.endCondition!!.endsAt)

        requireNotNull(seams.onOutcome)(EndChoiceResult.REFUSED)

        assertTrue("the refusal is still shown", controller.commitFailed)
        assertEquals(
            "and the rebuilt offer uses the reconciled backstop",
            reconciled.capCeilingAt,
            controller.endCondition!!.ceiling,
        )
        assertTrue(
            "so the retry is one the service would accept",
            controller.endCondition!!.endsAt.isAfter(later.plus(ActiveSnooze.MIN_CAP)),
        )
    }

    @Test
    fun `a refusal with no live record leaves the offer standing`() {
        // The host has not read a record back yet. Rebuilding from something
        // that is not this snooze would be worse than showing the failure and
        // letting `reconcile` settle it on the next record read.
        val seams = Seams(now)
        val controller = controller(seams)
        controller.seed(snoozeAt(now, capIn = ActiveSnooze.MIN_CAP.plusMinutes(20)), now)
        val offered = controller.endCondition!!.endsAt
        seams.now = offered.plus(Duration.ofMinutes(1))
        seams.live = null
        controller.commit(offered)

        requireNotNull(seams.onOutcome)(EndChoiceResult.REFUSED)

        assertTrue(controller.commitFailed)
        assertEquals("unchanged, not rebuilt from a guess", offered, controller.endCondition!!.endsAt)
    }

    @Test
    fun `a different snooze running is not the one the offer was made for`() {
        // The screen can be stopped across one snooze ending and another
        // arming. Asking only whether *some* snooze offers a choice lets the
        // replacement pass for the original, and the time chosen for the first
        // is then applied to the second (Codex, PR #152).
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.reconcile(snoozeAt(now.plus(Duration.ofMinutes(20))), now)

        assertNull(controller.endCondition)
        assertEquals(1, seams.dismissals)
    }

    @Test
    fun `the snooze ending takes its offer with it`() {
        val seams = Seams(now)
        val controller = seeded(seams)

        controller.reconcile(null, now)

        assertNull(controller.endCondition)
    }

    @Test
    fun `the same snooze still running keeps its offer untouched`() {
        val seams = Seams(now)
        val controller = seeded(seams)
        val offered = controller.endCondition!!.endsAt

        controller.reconcile(snoozeAt(now), now.plus(Duration.ofMinutes(5)))

        assertEquals("nothing to correct", offered, controller.endCondition!!.endsAt)
        assertEquals(0, seams.dismissals)
    }

    @Test
    fun `an offer that went stale while away is reseeded, not dropped`() {
        // The worker finished while the screen was stopped, or it simply sat
        // there: the time on it has fallen inside the floor. The snooze is
        // still running and still refinable, so the question is worth asking —
        // just not that one (Codex, PR #152).
        val seams = Seams(now)
        val controller = seeded(seams)
        val stale = controller.endCondition!!.endsAt

        val muchLater = stale.plus(Duration.ofMinutes(1))
        controller.reconcile(snoozeAt(now, capIn = Duration.ofHours(8)), muchLater)

        assertEquals("still asking", 0, seams.dismissals)
        assertTrue(
            "against a time the service would accept",
            controller.endCondition!!.endsAt.isAfter(muchLater.plus(ActiveSnooze.MIN_CAP)),
        )
    }

    @Test
    fun `a commit in flight is never reconciled away`() {
        val seams = Seams(now)
        val controller = seeded(seams)
        controller.commit(controller.endCondition!!.endsAt)

        controller.reconcile(null, now)

        assertNotNull("its answer is coming and settles it", controller.endCondition)
        assertEquals(0, seams.dismissals)
    }

    @Test
    fun `the offer takes its ceiling from the record it was seeded against`() {
        // Not from whatever the host had to hand: a stale or absent warm copy
        // gives `now + DEFAULT_CAP`, and the offer can then walk past what the
        // running snooze would actually accept (Codex, PR #152).
        //
        // The ceiling is the record's `capCeilingAt`, which for this fixture is
        // also its cap — a snooze nobody has shortened carries the two at the
        // same instant. Named as the backstop rather than the cap so the
        // assertion says what it is checking.
        val seams = Seams(now)
        val controller = controller(seams)
        val nearCap = snoozeAt(now, capIn = Duration.ofHours(2))

        controller.seed(nearCap, now)

        assertEquals(nearCap.capCeilingAt, controller.endCondition!!.ceiling)
        assertNotEquals(now.plus(ActiveSnooze.DEFAULT_CAP), controller.endCondition!!.ceiling)
        assertEquals(nearCap.startedAt, controller.offerFor)
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

    /**
     * A running snooze whose cap is [capIn] from now and whose §7 backstop is
     * [ceilingIn] from now.
     *
     * The two are separate parameters because separating them is the whole
     * subject here: a chosen time lowers the cap and leaves the backstop where
     * it is, and every case below turns on which of the two is being asked.
     */
    private fun snooze(
        capIn: java.time.Duration,
        ceilingIn: java.time.Duration = ActiveSnooze.DEFAULT_CAP,
    ) = ActiveSnooze(
        anchor = app.snoozemo.core.Anchor(capturedAt = now),
        startedAt = now,
        capExpiresAt = now.plus(capIn),
        mode = app.snoozemo.core.TrackingMode.DURATION_ONLY,
        capCeilingAt = now.plus(ceilingIn),
    )

    @Test
    fun `no record means no sheet`() {
        // Fails closed: no sheet over a correctly armed snooze is exactly what
        // the setting being off would have given.
        assertFalse(EndCondition.offersAChoice(null, now))
    }

    @Test
    fun `a backstop inside the floor leaves nothing to choose`() {
        // The service declines anything inside `MIN_CAP` and clamps anything
        // above the ceiling; with the two crossed there is no value left in
        // between, so the sheet would be a screen the user cannot answer.
        val nearlyOver = snooze(
            capIn = ActiveSnooze.MIN_CAP.minusMinutes(2),
            ceilingIn = ActiveSnooze.MIN_CAP.minusMinutes(1),
        )

        assertFalse(EndCondition.offersAChoice(nearlyOver, now))
    }

    @Test
    fun `a backstop above the floor does offer a choice`() {
        assertTrue(
            EndCondition.offersAChoice(
                snooze(
                    capIn = ActiveSnooze.MIN_CAP.plusMinutes(1),
                    ceilingIn = ActiveSnooze.MIN_CAP.plusMinutes(1),
                ),
                now,
            ),
        )
    }

    @Test
    fun `a cap stepped down inside the floor still offers the way back out`() {
        // The case the ceiling-based test exists for: the user stepped down to
        // half an hour and time passed. Asked of the cap, the rows vanish —
        // and they vanish exactly where the way back out is the thing the user
        // wants. The backstop is hours away and every one of those hours is
        // still choosable.
        val steppedDown = snooze(
            capIn = ActiveSnooze.MIN_CAP.minusMinutes(10),
            ceilingIn = java.time.Duration.ofHours(6),
        )

        assertTrue(EndCondition.offersAChoice(steppedDown, now))
    }

    @Test
    fun `the ceiling is the running snooze's own backstop, not its current cap`() {
        // A chosen time lowers the cap, so a ceiling read from the cap came
        // down with it and `+` could never climb back — one tap of `−` was
        // permanent. The backstop is where this snooze was always going to end,
        // so nothing becomes reachable that was not reachable at the arm.
        val steppedDown = snooze(
            capIn = java.time.Duration.ofMinutes(45),
            ceilingIn = java.time.Duration.ofHours(6),
        )

        assertEquals(steppedDown.capCeilingAt, EndCondition.ceilingFor(steppedDown, now))
        assertNotEquals(steppedDown.capExpiresAt, EndCondition.ceilingFor(steppedDown, now))
    }

    @Test
    fun `the ceiling is still the record's own, not a fresh full cap`() {
        // A duplicate arm keeps the snooze already running (SPEC.md §4.2), so
        // the record can have started long ago. Seeded against a constant the
        // sheet would offer eight hours over a snooze with one left.
        val old = snooze(
            capIn = java.time.Duration.ofHours(1),
            ceilingIn = java.time.Duration.ofHours(1),
        )

        assertEquals(old.capCeilingAt, EndCondition.ceilingFor(old, now))
        assertNotEquals(now.plus(ActiveSnooze.DEFAULT_CAP), EndCondition.ceilingFor(old, now))
    }

    @Test
    fun `with no record the ceiling falls back to a full cap`() {
        assertEquals(now.plus(ActiveSnooze.DEFAULT_CAP), EndCondition.ceilingFor(null, now))
    }
}
