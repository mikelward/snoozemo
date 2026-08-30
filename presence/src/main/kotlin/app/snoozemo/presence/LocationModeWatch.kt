package app.snoozemo.presence

import app.snoozemo.core.SnoozeDebugLog

/**
 * Watches for location services being switched back **on**, calling
 * `onEnabled` when they are. Closing the returned handle ends the watch,
 * after which no callback may arrive. Null means the platform refused to be
 * watched at all — a failure to record once, not to retry.
 * [PlatformLocationModeWatch] is the real one; tests substitute a manual
 * registrar, which is what makes the lifecycle below JVM-testable.
 */
internal interface LocationModeRegistrar {
    fun watch(onEnabled: () -> Unit): AutoCloseable?

    /**
     * Whether location is switched on **right now**, or null if that cannot
     * be read. Sampled once after [watch] has installed its handle — see
     * [LocationModeWatch]'s note on the missed-broadcast race, which is the
     * only reason this exists.
     */
    fun isEnabled(): Boolean?
}

/**
 * The recovery half of SPEC.md §8.4's location-services outage: the user
 * turns location off mid-snooze, every fix stops arriving and the fence
 * either stops being monitorable or is refused outright — and then the user
 * turns it back on, and *nothing was listening for that*.
 *
 * Before this, recovery waited on the §6.10 backstop: every restore
 * re-registers the fence and takes one resting fix, so an outage healed in
 * roughly the backstop's 30-minute cadence rather than lasting to the cap.
 * Bounded, but a snooze whose watch is provably repairable should not spend
 * half an hour degraded to a timer when the platform will say so for free.
 * This makes the same repair prompt.
 *
 * **It repairs nothing itself, on purpose.** The callback pokes exactly what
 * a backstop wake pokes — re-register the fence, take one resting fix — and
 * the established asymmetry then decides what is believed: a successful
 * registration clears the registration level, and only a *delivered fix*
 * clears the services-off level, because a fence the platform accepts is not
 * a subsystem proven to work (Codex, PR #75). So a spurious firing costs one
 * registration and one fix request, and can never promote a snooze on
 * nothing.
 *
 * **Armed only while there is something to recover from**, which is what
 * keeps it off SPEC.md §9's budget for the snoozes that are working:
 * a healthy watch registers no receiver and is woken by no toggle.
 *
 * **What it does not cover, and cannot.** `MODE_CHANGED_ACTION` is an
 * implicit broadcast, and is not one of the exemptions that may still be
 * received by a manifest-declared receiver on API 26+ — so this is a
 * context-registered watch, and on the `play` flavor it lives exactly as
 * long as the service does, which Android stops within about a minute of
 * each wake (§3.4, §6.10). The window it genuinely covers is the one where
 * the app is *running* while the outage is discovered and ended: a snooze
 * armed with location off, or a user who reaches for the setting on the
 * strength of the notification. Outside that minute nothing changes and the
 * backstop remains the mechanism. That is the same shape — and the same
 * honest limit — as [MotionTrigger] on this flavor, and for the same
 * underlying reason: nothing in the platform delivers this to a dead
 * process. On `direct`, Phase 7's foreground service keeps the process
 * resident and this covers the whole snooze, which is why it lives in the
 * shared source set rather than the `play` flavor's.
 *
 * **The broadcast is not sticky, so registering is not enough.** An outage
 * can be *reported* well after location was actually switched back on — a
 * `GEOFENCE_NOT_AVAILABLE` observation arriving late is the ordinary case,
 * not a contrived one — and then the mode change this watch exists to hear
 * has already happened and will never be re-delivered. So the watch samples
 * the current setting once, immediately after the handle is installed, and
 * treats "already on" exactly as it treats a broadcast (Codex, PR #139).
 * Registering *first* is what makes that a closed window rather than a
 * smaller one: a change landing between the registration and the sample is
 * delivered to the receiver, and one landing before the registration is what
 * the sample sees. [PlatformWifiWatch] reads its own initial state in the
 * same order and for the same reason.
 *
 * Callers arrive from more than one thread — the monitor's `send` is reached
 * from platform callbacks on main and from its own setup body on the
 * collector's — so one lock serializes the reconcile. Never held across the
 * recovery callback or the sample, both of which take locks of their own.
 */
internal class LocationModeWatch(
    private val registrar: LocationModeRegistrar,
    private val onRecovered: () -> Unit,
) : AutoCloseable {

    private val lock = Any()

    private var watching: AutoCloseable? = null

    /**
     * Which registration the next firing is allowed to belong to.
     *
     * A broadcast can already be in flight when the watch is closed or
     * reconciled away, and without an identity check that stale delivery
     * would poke a repair for a monitor that has since been torn down — or,
     * worse, one belonging to a later snooze. Belt to the registrar's own
     * braces: the platform one drops its receiver first, and this holds even
     * for a registrar that does not.
     */
    private var generation = 0L

    /**
     * Set once the platform has refused to be watched. Recoverable in
     * principle, but nothing here retries: re-asking on every engine update
     * would spend more than the promptness it is buying, and the backstop
     * still heals the outage on its own cadence. Said once, because a snooze
     * recovering only at the backstop's pace is a real difference a
     * stuck-snooze report has to explain.
     */
    private var unavailable = false

    private var dead = false

    /**
     * Matches the platform to [needed] — watching while the monitor holds a
     * recoverable location-platform degradation, not watching otherwise.
     * Idempotent in both directions: the monitor restates its level on every
     * update it sends, not only on the ones that change it, so re-asserting
     * a live watch must not spend a second registration.
     */
    fun reconcile(needed: Boolean) {
        val toClose: AutoCloseable?
        var sample: Long? = null
        synchronized(lock) {
            if (dead) return
            if (!needed) {
                toClose = watching
                watching = null
            } else {
                toClose = null
                if (watching == null && !unavailable) {
                    start()
                    // Only a registration that actually took gets sampled:
                    // with nothing listening, "already on" would be acted on
                    // once and then never heard again.
                    if (watching != null) sample = generation
                }
            }
        }
        // Both outside the lock: the close is a platform call, and the sample
        // ends in the recovery callback, which takes the monitor's own locks.
        toClose?.close()
        sample?.let { sampleCurrentState(it) }
    }

    /**
     * Closes the missed-broadcast window: the change may already have
     * happened before there was anything registered to hear it.
     */
    private fun sampleCurrentState(identity: Long) {
        val enabled = runCatching { registrar.isEnabled() }
            .onFailure {
                // Contained, and it costs only promptness: the watch is
                // registered either way, so a later toggle is still heard,
                // and the backstop still heals an outage that already ended.
                SnoozeDebugLog.failure(it, "location-mode read refused; waiting for a broadcast instead")
            }
            .getOrNull()
        if (enabled == true) {
            SnoozeDebugLog.event("location was already back on when the watch started")
            onEnabled(identity)
        }
    }

    /** Lock held. */
    private fun start() {
        val identity = ++generation
        val handle = runCatching { registrar.watch { onEnabled(identity) } }
            .onFailure {
                SnoozeDebugLog.failure(
                    it,
                    "location-mode watch refused; recovery falls back to the backstop's cadence",
                )
                unavailable = true
            }
            .getOrNull()
        if (handle == null) {
            if (!unavailable) {
                SnoozeDebugLog.event("no location-mode watch; recovery waits for the backstop")
                unavailable = true
            }
            return
        }
        watching = handle
    }

    private fun onEnabled(identity: Long) {
        synchronized(lock) {
            // A delivery from a registration this watch has already moved
            // past says nothing about the current one.
            if (dead || identity != generation || watching == null) return
        }
        // Outside the lock: the repair takes the monitor's registration lock
        // and the probe takes its feed lock, and holding this one across
        // either is the deadlock shape.
        SnoozeDebugLog.event("location services back on; re-registering and re-checking")
        onRecovered()
    }

    /** Ends this watch for good; no later [reconcile] can restart it. */
    override fun close() {
        val toClose: AutoCloseable?
        synchronized(lock) {
            dead = true
            toClose = watching
            watching = null
        }
        toClose?.close()
    }
}
