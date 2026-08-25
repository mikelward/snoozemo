package app.snoozemo.presence

import app.snoozemo.core.PresenceSignal
import app.snoozemo.core.SnoozeDebugLog

/**
 * Arms one `TYPE_SIGNIFICANT_MOTION` firing, calling [onFired] when it comes.
 * Closing the returned handle cancels it, after which no callback may arrive.
 * Null means the device has no such sensor at all — a permanent answer, not a
 * failure to retry. [PlatformMotionTrigger] is the real one; tests substitute
 * a manual registrar, which is what makes the lifecycle below JVM-testable.
 */
internal fun interface TriggerRegistrar {
    fun arm(onFired: () -> Unit): AutoCloseable?
}

/**
 * The §6.7 duty cycle's escalator: while the phone is resting away from the
 * anchor's Wi-Fi, a hardware-backed one-shot says *the phone moved*, and the
 * engine turns that into the checking burst that can actually confirm a
 * departure.
 *
 * Motion is not departure and this class does not pretend otherwise — the
 * engine treats [PresenceSignal.SignificantMotion] as a reason to *look*,
 * never as evidence of leaving (standing up for coffee must not end a
 * snooze), and suppresses it outright while the anchor's Wi-Fi is
 * associated, where D4 already has better evidence for free.
 *
 * Why it earns its keep: without it, a resting snooze whose geofence exit is
 * late or dropped waits for the §6.10 backstop's periodic wake instead of
 * escalating the moment the phone is picked up. `TYPE_SIGNIFICANT_MOTION`
 * needs no permission, is batched in hardware, and wakes nothing while the
 * phone sits still, so the escalation costs approximately nothing against
 * SPEC.md §9's budget — which is the whole reason §6.7 pairs it with an
 * otherwise very slow resting cadence.
 *
 * **A trigger sensor disarms itself when it fires**, which is the one
 * platform detail the lifecycle here exists to handle: every firing re-arms,
 * or the second movement of a snooze would never be heard.
 *
 * Confined to one thread — the main thread in production, where the real
 * registrar marshals its callback — so the fields below need no lock.
 */
internal class MotionTrigger(
    private val registrar: TriggerRegistrar,
    private val readElapsedRealtimeMs: () -> Long,
    private val onSignal: (PresenceSignal) -> Unit,
) : AutoCloseable {

    private var armed: AutoCloseable? = null

    /**
     * Which registration the next firing is allowed to belong to.
     *
     * The registrar marshals its callback, so a firing can still be in flight
     * when the duty leaves and re-enters the resting state — and without an
     * identity check that stale callback would clear the *newer* registration's
     * handle, leaving it live with nothing able to cancel it and two sensors
     * reporting into one snooze (Codex, PR #119). Belt to the registrar's own
     * braces: the platform one suppresses the callback as well, and this holds
     * even for a registrar that does not.
     */
    private var generation = 0L

    /** Whether the caller currently wants a firing, independent of [armed]. */
    private var wanted = false

    /**
     * Set once the device has answered that it has no significant-motion
     * sensor. A permanent property of the hardware, so it is asked once and
     * never retried — and said once, because a snooze escalating only on the
     * backstop's cadence is a real difference in behavior that a stuck-snooze
     * report needs to explain.
     */
    private var unavailable = false

    private var dead = false

    /**
     * Matches the platform to [needed] — armed while the engine is resting
     * away from the anchor's Wi-Fi, canceled otherwise. Idempotent in both
     * directions: re-asserting an arm that is already live must not spend a
     * second registration, since the duty is restated on every update the
     * engine produces, not only on the ones that change it.
     */
    fun reconcile(needed: Boolean) {
        if (dead) return
        wanted = needed
        if (!needed) {
            disarm()
            return
        }
        if (armed != null || unavailable) return
        arm()
    }

    private fun arm() {
        val armIdentity = ++generation
        val handle = runCatching { registrar.arm { onFired(armIdentity) } }
            .onFailure {
                // Recoverable in principle, but nothing here retries: the
                // backstop and the cap still bound the snooze, and a refused
                // sensor that keeps being re-asked on every engine update
                // would spend more than the escalation it is buying. Said,
                // not swallowed — what the user loses is departure latency,
                // which is exactly what a stuck snooze's report must show.
                SnoozeDebugLog.warning("motion trigger refused; the backstop and the cap still bound it", it)
                unavailable = true
            }
            .getOrNull()
        if (handle == null) {
            if (!unavailable) {
                SnoozeDebugLog.event("no significant-motion sensor; resting on the backstop's cadence")
                unavailable = true
            }
            return
        }
        armed = handle
    }

    private fun onFired(armIdentity: Long) {
        // A firing from a registration this trigger has already moved past
        // says nothing about the current one, and must not touch its handle.
        if (armIdentity != generation) return
        // The platform disarmed itself in firing, so the handle is spent
        // whatever happens next — dropped before anything can re-arm, or a
        // later cancel would close a registration that no longer exists.
        armed = null
        if (dead || !wanted) return
        SnoozeDebugLog.event("significant motion: escalating to a checking fix")
        onSignal(PresenceSignal.SignificantMotion(readElapsedRealtimeMs()))
        // Re-armed after the signal, not before: the engine's own answer to
        // it may take the duty out of resting, and `reconcile` would then
        // immediately cancel a registration this call had just spent.
        if (wanted && armed == null && !unavailable) arm()
    }

    private fun disarm() {
        armed?.close()
        armed = null
    }

    /** Ends this trigger for good; no later [reconcile] can re-arm it. */
    override fun close() {
        dead = true
        wanted = false
        disarm()
    }
}
