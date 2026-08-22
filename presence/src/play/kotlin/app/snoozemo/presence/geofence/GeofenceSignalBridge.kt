package app.snoozemo.presence.geofence

import app.snoozemo.core.SnoozeDebugLog

/**
 * The in-process handoff from [GeofenceTransitionReceiver] to the running
 * monitor.
 *
 * A receiver cannot hold a reference to the monitor the service started, so
 * this is the meeting point: the monitor attaches while its flow runs, the
 * receiver delivers whatever the platform hands it. One slot, not a list —
 * there is at most one running snooze and therefore one monitor (SPEC.md §7).
 *
 * An observation arriving with no monitor attached is **dropped and said**:
 * the process was restarted by the geofence broadcast itself and nothing has
 * restored the monitor yet. Restoring tracking from exactly that wake-up is
 * its own Phase 3 item; until it lands, the debug-log line is what makes the
 * gap diagnosable rather than silent (SPEC.md §4.6).
 *
 * Closing compares identity so a superseded monitor's late close cannot evict
 * the one that replaced it — the same rule `DebugLogging.watchSaveOutcome`
 * follows for the same overlapping-lifecycle reason.
 */
internal object GeofenceSignalBridge {

    // One lock serializes attachment, closure, and delivery, because no
    // atomic reference can: delivery is a read *and then* an invocation, and
    // between the two the old flow could close — sending the one exit signal
    // into a dead channel while the replacement hears nothing (flagged by
    // Codex on PR #70, the last of this race class). Held across the
    // listener call, which is safe here: the listener only steps the engine
    // and does a non-blocking send, and nothing in attach, close, or deliver
    // takes another lock.
    private val lock = Any()

    private var listener: ((GeofenceObservation) -> Unit)? = null

    fun attach(onObservation: (GeofenceObservation) -> Unit): AutoCloseable {
        synchronized(lock) { listener = onObservation }
        return AutoCloseable {
            synchronized(lock) {
                // Only its own: a replacement that attached before this close
                // ran must not be evicted by it.
                if (listener === onObservation) listener = null
            }
        }
    }

    fun deliver(observation: GeofenceObservation) {
        synchronized(lock) {
            val current = listener
            if (current == null) {
                SnoozeDebugLog.warning(
                    "geofence observation arrived with no monitor running; dropped " +
                        "(restore-from-wake is not built yet)",
                )
                return
            }
            current(observation)
        }
    }
}
