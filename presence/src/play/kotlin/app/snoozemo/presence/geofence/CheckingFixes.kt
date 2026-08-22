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
     * Begins the burst; a burst already running, or an instance already
     * closed for good, is left alone.
     */
    fun start() {
        scheduler.post {
            if (dead || running) return@post
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
     * Ends the burst for good: no queued or future [start] can revive it.
     * The flag is set synchronously — before the marshaled stop — so a start
     * already sitting in the queue behind this call still finds it.
     */
    override fun close() {
        dead = true
        scheduler.post { stopWork() }
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
                // Permanent, not a pause: the snooze this callback ends must
                // not find a queued start reviving its burst. The stop comes
                // first so the callback finds nothing still running behind it.
                dead = true
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
