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
 * An observation arriving with no monitor attached is the **common case in
 * the field**, not a corner: with no foreground service, Android reclaims the
 * process during a long snooze, and the geofence broadcast is what restarts
 * it — into a process where nothing has restored the monitor yet (SPEC.md
 * §8.1; sharpened by Codex on PR #73, since a dropped exit is a departure
 * never confirmed and a phone silent until the cap). So an undeliverable
 * observation is **held, and the service is woken**: it goes into a one-slot
 * mailbox, the wake-up hook installed by the app runs, and the restored
 * monitor's own attach flushes the slot into the engine. Re-registering the
 * fence could not replace this — geofences fire on *crossing*, and the user
 * already crossed.
 *
 * The engine makes the mailbox safe to flush blind: every observation carries
 * its elapsed-realtime moment, and `Presence` drops anything older than
 * evidence it has already accepted — including the arm-time seed — so a
 * leftover exit from a previous snooze cannot end the next one.
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

    /** The mailbox: the newest undeliverable observation, awaiting a monitor. */
    private var pending: GeofenceObservation? = null

    /**
     * Runs — outside the app's control flow, from the receiver's thread —
     * whenever an observation lands in the mailbox. Installed once at process
     * start by the app layer (`installPresenceWakeup`), because this module
     * cannot see the service that needs starting.
     */
    private var onPending: (() -> Unit)? = null

    fun installWakeup(onObservationPending: () -> Unit) {
        synchronized(lock) { onPending = onObservationPending }
    }

    /**
     * Empties the mailbox. Tests only: production never needs it, because the
     * engine's arm-time evidence seed makes a held observation inert for
     * every snooze that starts after it.
     */
    internal fun resetForTest() {
        synchronized(lock) {
            pending = null
            listener = null
        }
    }

    fun attach(onObservation: (GeofenceObservation) -> Unit): AutoCloseable {
        synchronized(lock) {
            listener = onObservation
            // Delivered but deliberately NOT cleared: a confirmation takes at
            // least the §6.6 two-fix window, and a process death inside it
            // would otherwise lose the check with nothing left to restart it
            // — the exit was consumed and a geofence never fires twice for
            // one crossing (flagged by Codex on PR #73). Held, the next
            // restore re-escalates and the check re-runs; the engine's
            // arm-time seed retires the slot for every later snooze, so the
            // cost is one spurious re-check per restart of the same snooze —
            // the fail-open direction, and a fix settles it.
            pending?.let {
                SnoozeDebugLog.event("geofence observation held across restart; delivering")
                // Under the lock, like live delivery and for the same reason:
                // nothing may close or replace this listener between the read
                // and the observation landing.
                onObservation(it)
            }
            // Only a one-shot that starts an unfinished confirmation earns
            // retention — an exit or a due grace deadline, whose alarm is
            // spent (Codex, PR #77, the same shape as the exit rule below).
            // An Unavailable is recoverable news other paths restate —
            // registration re-detects it on every start — and, unlike a
            // signal, it bypasses the engine's evidence gate: replayed to
            // every attach, it would mark each later snooze in this process
            // LOCATION_SERVICES_OFF off a report from before that snooze
            // existed (flagged by Codex on PR #73).
            if (!isRetained(pending)) pending = null
        }
        return AutoCloseable {
            synchronized(lock) {
                // Only its own: a replacement that attached before this close
                // ran must not be evicted by it.
                if (listener === onObservation) {
                    listener = null
                    // Detached mid-check: an exit or due grace still held
                    // here means its confirmation never settled — the
                    // routine background destroy tears the collector down
                    // inside the two-fix window — and no further broadcast
                    // or alarm firing will come, so this is the last hand
                    // that can arrange a successor (flagged by Codex on PR
                    // #73, generalized to grace on PR #77). The wake
                    // restores the service, whose attach re-collects the
                    // held observation; a settled check cleared the slot and
                    // detaches silently.
                    if (isRetained(pending)) {
                        val wake = onPending
                        if (wake == null) {
                            SnoozeDebugLog.warning(
                                "monitor detached mid-check with no wake-up installed; exit held",
                            )
                        } else {
                            SnoozeDebugLog.event("monitor detached mid-check; waking the service")
                            wake()
                        }
                    }
                }
            }
        }
    }

    fun deliver(observation: GeofenceObservation) {
        synchronized(lock) {
            val current = listener
            if (current == null) {
                // A poke is a question, not news: with nobody to ask, it is
                // dropped rather than held or woken for — the backstop that
                // poked also restored the service, and a restored monitor
                // takes its own starting probe and registers its own fence
                // (SPEC.md §6.10).
                if (observation is GeofenceObservation.SanityPoke) return
                if (observation is GeofenceObservation.RepairPoke) return
                // Coalesced by rank, not by recency alone: an Exit starts a
                // confirmation, an Unavailable only lowers the tracking mode,
                // and letting a later availability report erase a held exit
                // would leave the departure unchecked — a phone quiet until
                // the cap (flagged by Codex on PR #73). So an Exit replaces
                // anything (a newer exit asks the same question with the
                // fresher timestamp); an Unavailable never displaces one.
                pending = rank(observation, pending)
                val wake = onPending
                if (wake == null) {
                    SnoozeDebugLog.warning(
                        "geofence observation arrived with no monitor and no wake-up installed; held",
                    )
                } else {
                    SnoozeDebugLog.event("geofence observation arrived with no monitor; waking the service")
                    wake()
                }
                return
            }
            // A live exit — or a live grace expiry — is retained too, not
            // only an undeliverable one: its confirmation takes at least the
            // §6.6 two-fix window, Android routinely destroys the ordinary
            // background service inside it, and a live-dispatched one lived
            // nowhere else — the fence never fires twice for one crossing,
            // and the grace alarm is one-shot and already spent, so the
            // departure was lost until the cap (flagged by Codex on PR #73
            // for the exit; generalized to grace on PR #77, where a service
            // destroyed between this queue and the collector processing it
            // left neither the alarm nor the bridge able to replay the
            // expiry). Ranked the same as the coalescing path above — a live
            // grace must not unconditionally displace an exit still pending
            // from an earlier delivery, which drifted from the coalescing
            // rule on the first pass at this and could permanently lose a
            // real exit behind a stale grace firing (Codex, PR #77). Held
            // here, the detach below wakes a successor and its attach
            // re-runs the check; [settleExit] retires the slot once the
            // check actually settles.
            if (isRetained(observation)) pending = rank(observation, pending)
            current(observation)
        }
    }

    /**
     * Retires a held exit or grace expiry once its departure check reached a
     * terminal result — confirmed (the snooze is ending), refuted (still
     * present), or abandoned (the engine degraded past checking, or dropped
     * the observation as stale). The monitor calls this whenever the
     * engine's duty is not ACTIVE, so the slot holds a retained observation
     * exactly while its confirmation is unfinished: a detach in that window
     * wakes a successor, a detach after it stays quiet. Named for the exit
     * it originally covered (Codex, PR #73); grace joined it on PR #77.
     */
    fun settleExit() {
        synchronized(lock) {
            if (isRetained(pending)) pending = null
        }
    }

    /**
     * Retires a stale `CapabilityLoss` prompt specifically — never whatever
     * else might occupy the slot (Codex, PR #95, third pass). Unlike
     * [settleExit], which answers "the one confirmation this slot was
     * tracking is now settled" and so may clear any retained occupant, a
     * `CapabilityLoss` delivery that turns out to name nothing does not mean
     * *the slot's* confirmation settled — `rank()` can already have kept a
     * genuine, still-unconfirmed exit or due grace there instead, over the
     * stale prompt, and clearing unconditionally would discard that real,
     * otherwise-unrecoverable evidence for a prompt that meant nothing.
     * Harmless to skip when the occupant has already moved on to a fresher
     * `CapabilityLoss`: unlike an exit or a grace deadline, this one's own
     * payload lives in `CapabilityLossStore`, not in the observation itself,
     * so a redundant prompt left in the slot costs nothing a restore's own
     * unconditional store read wouldn't already have caught.
     */
    fun settleCapabilityLoss() {
        synchronized(lock) {
            if (pending is GeofenceObservation.CapabilityLoss) pending = null
        }
    }

    /**
     * Whether [observation] is a one-shot that starts or continues an
     * unfinished §6.6 confirmation and so must survive a detach: an exit or a
     * due grace deadline, neither of which the platform will report again on
     * its own. Everything else — an availability report, a poke — is
     * recoverable news some other path restates.
     */
    private fun isRetained(observation: GeofenceObservation?): Boolean =
        observation is GeofenceObservation.Exit ||
            observation is GeofenceObservation.GraceElapsed ||
            observation is GeofenceObservation.CapabilityLoss

    /**
     * Picks which of [candidate] and [existing] occupies the slot: an exit
     * outranks everything — it carries the evidence a departure needs — a
     * due grace deadline outranks an availability report — its alarm is
     * spent, so nothing else will say this again — and neither is ever
     * displaced by news that would not itself earn retention. The one rule
     * for both the coalescing path (no listener) and the live-retention
     * path, which had drifted apart: a live grace could unconditionally
     * replace an already-pending exit, permanently losing a real departure
     * behind a stale firing (Codex, PR #77).
     */
    private fun rank(candidate: GeofenceObservation, existing: GeofenceObservation?): GeofenceObservation =
        when {
            // An exit or a due grace deadline outranks a capability-loss
            // prompt, not the other way around (Codex, PR #95, second pass —
            // an earlier version of this ranking let an unvalidated
            // capability-loss prompt win the slot unconditionally, so a
            // canceled alarm firing late could discard a real, otherwise-
            // unrecoverable exit before any monitor ever saw it). An exit's
            // or a due grace's evidence exists nowhere but this slot until a
            // monitor consumes it; a capability loss's actual payload lives
            // in `CapabilityLossStore`, re-checked unconditionally on every
            // restore (`start()`), so losing this slot to genuine departure
            // evidence costs it nothing — whatever wakes the process for
            // that evidence runs the same restore-time check regardless.
            candidate is GeofenceObservation.Exit -> candidate
            existing is GeofenceObservation.Exit -> existing
            candidate is GeofenceObservation.GraceElapsed -> candidate
            existing is GeofenceObservation.GraceElapsed -> existing
            else -> candidate
        }
}
