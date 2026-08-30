package app.snoozemo.presence.geofence

import app.snoozemo.core.Fix
import app.snoozemo.core.PresenceSignal
import app.snoozemo.core.SnoozeDebugLog

/**
 * One tick of the confirmation clock: run [block] after the delay, unless the
 * returned handle is closed first. The production implementation wraps the
 * main-thread handler ([AndroidBurstScheduler]); tests substitute a manual
 * queue, which is what makes the burst's lifecycle — the part two review
 * rounds found real bugs in — JVM-testable.
 */
internal interface BurstScheduler {
    fun post(block: () -> Unit)
    fun postDelayed(delayMs: Long, block: () -> Unit): AutoCloseable
}

/** What one platform fix request came back with. */
internal sealed interface FixOutcome {
    /** A reading §6.6 can gate: coordinates, accuracy, and its own timing. */
    data class Delivered(val fix: Fix) : FixOutcome

    /**
     * Nothing usable, recoverably: no reading, an accuracy-less one, no
     * provider. The engine's degradation counting runs on hearing about it.
     */
    data object NothingRecoverable : FixOutcome

    /** Location is off system-wide — known, recoverable, said immediately. */
    data object ServicesOff : FixOutcome

    /** The grant is gone. Fatal: the snooze ends on it (SPEC.md §6.1). */
    data object PermissionLost : FixOutcome
}

/**
 * Asks the platform for one fix, calling back **on the scheduler's thread**
 * with exactly one outcome — unless the returned handle is closed first, after
 * which no callback may arrive. [PlatformFixRequester] is the real one.
 */
internal fun interface FixRequester {
    fun request(onOutcome: (FixOutcome) -> Unit): AutoCloseable
}

/**
 * The `play` flavor's confirming fixes (SPEC.md §6.10): one-shot requests
 * taken while the engine is checking, so a geofence exit — or any later
 * wake-up source — can be confirmed or called off by the §6.6 test rather
 * than trusted on its own.
 *
 * One-shots, not a request loop, deliberately: with no foreground service, a
 * background 90-second request would be throttled to nothing (that loop is
 * the `direct` flavor's, behind Phase 7's foreground service, §6.5), while a
 * one-shot per confirmation step fits inside the background budget and is
 * all the two-fix rule needs. [CheckingCadence] owns the pacing and the
 * backoff; [FixRequester] owns the platform call; this class owns the
 * lifecycle, over seams, so every path below is JVM-tested.
 *
 * Confined to the scheduler's thread — the main thread in production — with
 * [start], [pause] and [close] marshaled onto it, so the fields need no lock.
 *
 * Every outcome is delivered, never swallowed, and classified by the one
 * distinction that matters (SPEC.md §6.1): recoverable nothings are
 * [PresenceSignal.FixUnavailable], which the engine's degradation counting
 * and the §6.6 grace period run on; a missing *grant* is [onPermissionLost],
 * because that is capability loss and ends the snooze.
 */
internal class CheckingFixes(
    private val scheduler: BurstScheduler,
    private val requester: FixRequester,
    private val readElapsedRealtimeMs: () -> Long,
    /** Delivered on the scheduler's thread, once per requested fix. */
    private val onSignal: (PresenceSignal) -> Unit,
    /**
     * The location grant is gone — revoked or downgraded mid-snooze. Fatal,
     * never a degradation (SPEC.md §6.1, §8.2): a snooze kept armed by
     * machinery that can no longer watch anything is the phone that never
     * comes back. The burst has already stopped itself, permanently, when
     * this fires.
     */
    private val onPermissionLost: () -> Unit,
    /**
     * Location is switched off system-wide — said the moment it is known
     * rather than left to be inferred from three generic unanswered fixes
     * (SPEC.md §8.4; flagged by Codex on PR #72). The request still counts
     * as unanswered through [onSignal], because the engine's own counting is
     * what arms the grace period.
     */
    private val onServicesOff: () -> Unit,
) : AutoCloseable {

    private val cadence = CheckingCadence()

    private var running = false
    private var inFlight: AutoCloseable? = null
    private var deadline: AutoCloseable? = null
    private var nextRequest: AutoCloseable? = null

    // The resting probe's own in-flight state, apart from the burst's: a
    // pause ends the burst but must not cut off a probe already asked, and a
    // probe must never revive or extend a burst (see [sanityCheck]).
    private var sanityToken: Any? = null
    private var sanityInFlight: AutoCloseable? = null
    private var sanityDeadline: AutoCloseable? = null

    /**
     * Identity of the request currently awaiting an answer, or null. A token
     * rather than the request handle, because a requester may answer
     * *synchronously* — a permission check fails before any platform call —
     * and the handle does not exist yet inside its own callback.
     */
    private var currentToken: Any? = null

    /**
     * Whether this instance is permanently over — [close] or a lost grant.
     * Volatile and checked inside every queued start, because that is the
     * one path a flag flipped on the scheduler's thread cannot police:
     * teardown can race an in-flight callback that has already *queued* a
     * start, and a pause-shaped stop would let that start revive the burst
     * with nobody left to close it — background location running for as long
     * as the process does (flagged by Codex on PR #72).
     */
    @Volatile
    private var dead = false

    /**
     * The *recoverable* stop: a lost location grant, which used to end the
     * snooze and no longer does (maintainer, 2026-08-30).
     *
     * Deliberately not [dead]. That flag is teardown — permanent by design, so
     * a start already queued behind [close] cannot revive a burst with nobody
     * left to close it (Codex, PR #72). Permission loss reused it while the
     * comment's premise held: "the snooze this callback ends". It does not end
     * the snooze any more, so reusing it would leave a *running* snooze unable
     * to request another confirming fix for the rest of the process — and an
     * anchor with no SSID cannot confirm a geofence exit without one, so it
     * would sit snoozed until the cap while a repaired registration reported
     * `FULL` (Codex, PR #149). Separate and clearable, so recovery can revive
     * it while teardown stays final: [start] and [sanityCheck] check both, and
     * [resume] can only ever clear this one.
     */
    @Volatile
    private var suspended = false

    /**
     * Revives fix checking after the grant that suspended it comes back.
     *
     * Cannot resurrect a closed instance — [dead] is checked alongside this
     * and nothing clears it — so a resume racing a teardown still finds the
     * burst shut for good.
     */
    fun resume() {
        if (dead) return
        suspended = false
    }

    /**
     * Begins the burst; a burst already running, or an instance already
     * closed for good, is left alone.
     */
    fun start() {
        scheduler.post {
            if (dead || suspended || running) return@post
            // A resting probe still in flight is superseded, not run beside:
            // the burst asks immediately anyway, and letting both answer
            // would count one moment's failure twice against the engine's
            // three-fix bar and spend a second request (Codex, PR #75). The
            // probe already defers to a running burst; this is the same rule
            // the other way around.
            stopSanity()
            running = true
            SnoozeDebugLog.event("checking: taking confirming fixes")
            requestOnce()
        }
    }

    /**
     * Ends the burst because the duty left ACTIVE; a later [start] may begin
     * a new one. Idempotent.
     */
    fun pause() {
        scheduler.post { stopWork() }
    }

    /**
     * The platform layer the burst was failing against is working again —
     * location switched back on (SPEC.md §8.4) — so ask now instead of
     * serving out a backoff the outage earned.
     *
     * This is the burst's half of that recovery, and it exists because the
     * resting probe cannot cover it (Codex, PR #139): an outage that starts
     * *during* a departure check leaves the duty `ACTIVE`, where
     * [sanityCheck] is a declared no-op, and three `ServicesOff` answers have
     * by then dropped the cadence to five minutes. Without this, the moment
     * the user switches location back on would do nothing at all for a
     * snooze mid-check — the one state where confirming a departure is most
     * urgent.
     *
     * A no-op while resting or closed: the resting case is the probe's, and
     * a closed instance is over for good. A request already in flight is
     * left alone — its own answer is arriving, and cutting it short to ask
     * again would spend two requests on one moment — but the cadence is
     * still forgiven, so that answer's follow-up is paced at the
     * confirmation gap rather than the backoff.
     */
    fun retryNow() {
        scheduler.post {
            if (dead || suspended || !running) return@post
            cadence.onPlatformRecovered()
            if (currentToken != null) return@post
            SnoozeDebugLog.event("checking: asking again now that the platform recovered")
            nextRequest?.close()
            nextRequest = null
            requestOnce()
        }
    }

    /**
     * Takes **one** resting fix outside any burst — the §6.10 backstop's
     * probe: a geofence that never fired says nothing, so each backstop wake
     * hands the engine one reading and lets the §6.6 test decide (SPEC.md
     * §6.10). Skipped while a burst runs (the burst is already asking faster
     * than this would) and while a probe is already in flight; no cadence and
     * no follow-up — the next probe is the next backstop wake's.
     *
     * Outcomes flow to the same sinks as the burst's, permission loss
     * included: the probe re-checks the grants on every wake, which is what
     * makes a mid-snooze revocation detectable at the backstop's cadence
     * rather than the cap's.
     */
    fun sanityCheck() {
        scheduler.post {
            if (dead || suspended || running || sanityToken != null) return@post
            SnoozeDebugLog.event("backstop: taking one resting fix")
            val token = Any()
            sanityToken = token
            sanityDeadline = scheduler.postDelayed(REQUEST_CEILING_MS) {
                if (sanityToken === token) {
                    SnoozeDebugLog.event("resting fix: no answer within the ceiling")
                    sanityToken = null
                    sanityInFlight?.close()
                    sanityInFlight = null
                    settleSanity(FixOutcome.NothingRecoverable)
                }
            }
            sanityInFlight = requester.request { outcome ->
                if (sanityToken !== token) return@request
                sanityToken = null
                sanityDeadline?.close()
                sanityDeadline = null
                sanityInFlight = null
                settleSanity(outcome)
            }
            // A synchronous answer has already settled the probe.
            if (sanityToken !== token) {
                sanityInFlight?.close()
                sanityInFlight = null
            }
        }
    }

    private fun settleSanity(outcome: FixOutcome) {
        if (dead) return
        when (outcome) {
            is FixOutcome.Delivered -> onSignal(PresenceSignal.FixArrived(outcome.fix))
            // Delivered, never swallowed, same as the burst's: the engine's
            // counting is what notices a place where nothing can get a fix.
            FixOutcome.NothingRecoverable ->
                onSignal(PresenceSignal.FixUnavailable(readElapsedRealtimeMs()))
            FixOutcome.ServicesOff -> {
                onServicesOff()
                onSignal(PresenceSignal.FixUnavailable(readElapsedRealtimeMs()))
            }
            FixOutcome.PermissionLost -> {
                // Suspended, not dead: the snooze survives this now, so the
                // burst has to be revivable when the grant returns.
                suspended = true
                stopWork()
                stopSanity()
                onPermissionLost()
            }
        }
    }

    private fun stopSanity() {
        sanityToken = null
        sanityDeadline?.close()
        sanityDeadline = null
        sanityInFlight?.close()
        sanityInFlight = null
    }

    /**
     * Ends the burst for good: no queued or future [start] can revive it.
     * The flag is set synchronously — before the marshaled stop — so a start
     * already sitting in the queue behind this call still finds it.
     */
    override fun close() {
        dead = true
        scheduler.post {
            stopWork()
            stopSanity()
        }
    }

    /** The stop itself; scheduler's thread only. */
    private fun stopWork() {
        if (!running) return
        running = false
        currentToken = null
        nextRequest?.close()
        nextRequest = null
        deadline?.close()
        deadline = null
        inFlight?.close()
        inFlight = null
    }

    private fun requestOnce() {
        if (!running) return
        val token = Any()
        currentToken = token
        // A belt on the platform's own timeout, armed before the request so
        // no ordering can lose it: a consumer that never runs would otherwise
        // leave the burst stalled with the engine hearing nothing at all —
        // the one outcome every path here must avoid.
        deadline = scheduler.postDelayed(REQUEST_CEILING_MS) {
            if (currentToken === token) {
                SnoozeDebugLog.event("checking fix: no answer within the ceiling")
                currentToken = null
                inFlight?.close()
                inFlight = null
                settle(FixOutcome.NothingRecoverable)
            }
        }
        inFlight = requester.request { outcome ->
            // Late or duplicate answers are dropped: the deadline above may
            // have already settled this request as unanswered, or the burst
            // may have stopped.
            if (currentToken !== token || !running) return@request
            currentToken = null
            deadline?.close()
            deadline = null
            inFlight = null
            settle(outcome)
        }
        // A synchronous answer has already settled this request; the handle
        // assigned above is then a finished one nothing needs to hold.
        if (currentToken !== token) {
            inFlight?.close()
            inFlight = null
        }
    }

    private fun settle(outcome: FixOutcome) {
        when (outcome) {
            is FixOutcome.Delivered -> {
                cadence.onFixDelivered()
                onSignal(PresenceSignal.FixArrived(outcome.fix))
                scheduleNext()
            }
            FixOutcome.NothingRecoverable -> nothing()
            FixOutcome.ServicesOff -> {
                // The cause is known right here, so it is said right here —
                // the engine's counter would take three requests to conclude
                // a generic "no fix", and would name it wrongly (SPEC.md
                // §8.4). Recoverable, so the burst keeps asking: the user can
                // switch location back on mid-snooze.
                onServicesOff()
                nothing()
            }
            FixOutcome.PermissionLost -> {
                // Suspended rather than dead — see [suspended]. The stop still
                // comes first so the callback finds nothing running behind it;
                // what changed is that a later [resume] can start it again,
                // because this no longer ends the snooze.
                suspended = true
                stopWork()
                onPermissionLost()
            }
        }
    }

    private fun nothing() {
        if (!running) return
        cadence.onNothing()
        onSignal(PresenceSignal.FixUnavailable(readElapsedRealtimeMs()))
        scheduleNext()
    }

    private fun scheduleNext() {
        if (!running) return
        nextRequest = scheduler.postDelayed(cadence.nextDelayMs) { requestOnce() }
    }

    internal companion object {
        /**
         * Longer than the platform's own ~30 s internal timeout, so it only
         * fires when the callback truly never came.
         */
        const val REQUEST_CEILING_MS: Long = 45_000L
    }
}
