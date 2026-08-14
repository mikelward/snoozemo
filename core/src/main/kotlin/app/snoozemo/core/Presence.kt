package app.snoozemo.core

import java.time.Duration

/**
 * Something the world told us about, as the presence engine takes it in.
 *
 * Every signal carries the moment it happened in **elapsed realtime** — the same
 * frame [Fix.elapsedRealtimeMs] uses, and for the same reason: the only
 * arithmetic done on these times decides whether a phone stays silent, and a
 * wall clock can be moved under it (SPEC.md §7).
 *
 * The list is deliberately what a *sensor* can say, never what it means. A
 * geofence exit is not a departure and Wi-Fi loss is not a departure; turning
 * signals into conclusions is this engine's whole job, and a signal type that
 * pre-judged would move that decision back out to the Android layer where no
 * JVM test can reach it.
 */
sealed interface PresenceSignal {

    val atElapsedRealtimeMs: Long

    /** Associated with the anchor's SSID: strong evidence of presence (D4). */
    data class AnchorWifiAssociated(override val atElapsedRealtimeMs: Long) : PresenceSignal

    /** The anchor's SSID went away. Weak evidence of leaving — escalates only (D4). */
    data class AnchorWifiLost(override val atElapsedRealtimeMs: Long) : PresenceSignal

    /** The registered geofence reported an exit (SPEC.md §6.10, source 1). */
    data class GeofenceExit(override val atElapsedRealtimeMs: Long) : PresenceSignal

    /** `TYPE_SIGNIFICANT_MOTION` fired: the phone moved, which is not "left" (SPEC.md §6.7). */
    data class SignificantMotion(override val atElapsedRealtimeMs: Long) : PresenceSignal

    /**
     * A location fix arrived, however it was asked for.
     *
     * There is deliberately no separate signal for the periodic backstop
     * (SPEC.md §6.10, source 3): a `WorkManager` check exists to *take* a fix
     * that nothing else asked for, and once taken it is the same evidence as any
     * other. Which source woke the phone matters to the debug log, not to the
     * test — and giving it its own signal would invite a rule that treats the
     * same reading differently depending on who asked for it.
     */
    data class FixArrived(val fix: Fix) : PresenceSignal {
        override val atElapsedRealtimeMs: Long get() = fix.elapsedRealtimeMs
    }

    /** A fix was asked for and none came back within the caller's ceiling. */
    data class FixUnavailable(override val atElapsedRealtimeMs: Long) : PresenceSignal

    /**
     * The Wi-Fi grace period the caller armed has come due
     * ([PresenceState.graceDeadlineMs]).
     *
     * Delivered rather than computed here because only the caller can wake a
     * sleeping phone; the engine re-checks the deadline against this signal's
     * own time, so an alarm the state has since moved on from is harmless.
     */
    data class GraceElapsed(override val atElapsedRealtimeMs: Long) : PresenceSignal
}

/**
 * How hard the caller should currently be asking for location (SPEC.md §6.7).
 *
 * This is where the battery budget of §9 is actually spent, so it is an output
 * of the state machine rather than a setting: the engine knows when location can
 * say something useful, and a phone on a desk inside its anchor's Wi-Fi should
 * be doing no location work at all.
 */
enum class LocationDuty {
    /**
     * None. Either Wi-Fi is answering the question for free, or location cannot
     * answer it at all — an anchor with no usable fix has nothing to measure
     * against, and asking anyway would spend battery to produce
     * [DepartureVerdict.INCONCLUSIVE] forever.
     */
    NONE,

    /** The slow sanity poll of §6.7 — on the order of 10 minutes, while nothing suggests movement. */
    SANITY,

    /** The 90-second request of §6.5, while a departure is being tested. */
    ACTIVE,
}

/** Which half of the escalation the engine is in (SPEC.md §4.1). */
enum class PresencePhase {
    /**
     * Nothing suggests the user has moved. Maps to [SnoozeState.ARMED].
     *
     * A separate two-value type rather than reusing [SnoozeState] because that
     * enum also carries `IDLE`, `ARMING` and `RELEASED`, and a state machine
     * whose type admits values it must never hold ends up asserting about them
     * instead of being unable to express them.
     */
    RESTING,

    /** A departure is being tested. Maps to [SnoozeState.CHECKING]. */
    CHECKING,
}

/**
 * Everything the presence engine remembers between signals.
 *
 * A value, like [DepartureProgress] and for the same reason: the engine is then
 * a pure function of (state, signal, anchor), so a whole afternoon of sensor
 * noise can be replayed through it in a JVM test with no Android, no clock, and
 * no waiting.
 *
 * **This is durable state, and the monitor that owns it must persist it**
 * (Codex, PR #31). A service killed while [graceDeadlineMs] is set comes back
 * with a default state, and a default state ignores the alarm that fires — so
 * the snooze the grace period was bounding runs to the duration cap in silence
 * instead. Two frames, and they behave differently: everything here is elapsed
 * realtime, which survives process death but resets at reboot, where the alarm
 * is gone as well and the restore has to re-derive the deadline rather than
 * reload it (`ActiveSnooze.bootReference` solves the same problem for the cap).
 */
data class PresenceState(
    val phase: PresencePhase = PresencePhase.RESTING,
    /** Whether the phone is currently associated with the anchor's SSID (D4's suppressor). */
    val atAnchorWifi: Boolean = false,
    /** The departure test's confirmation window, carried across fixes. */
    val progress: DepartureProgress = DepartureProgress.NONE,
    /**
     * How many observations in a row have said nothing usable — a vague fix, or
     * no fix at all. What [Presence.DEGRADED_AFTER_USELESS_OBSERVATIONS]
     * compares against.
     */
    val uselessObservations: Int = 0,
    /**
     * The degradation already reported, so it is reported **once** rather than
     * per failed fix. A notification rewritten every 90 seconds to say the same
     * thing is noise, and noise is how a user learns to ignore the line that
     * matters.
     */
    val reportedDegradation: DegradationCause? = null,
    /**
     * When location last failed to say anything usable, or null if it never has.
     *
     * The boundary a recovery has to beat (Codex, PR #33). A reading captured
     * *before* the failures proves nothing about whether they are over, and
     * cached and batched delivery means one can arrive long afterwards —
     * promoting the snooze to [TrackingMode.FULL] on evidence older than the
     * problem, which hides a degradation while departure tracking is still
     * broken. That is the overstating direction, and the one this whole change
     * exists to prevent.
     *
     * Only the stale path needs it: a reading new enough to be accepted for
     * presence is already newer than every observation, since a failed one
     * moves the evidence bar too.
     */
    val lastUnusableAtMs: Long? = null,
    /** When the anchor's Wi-Fi went away, or null if it is present or never was. */
    val wifiLostAtMs: Long? = null,
    /**
     * When the current check started, or null while resting.
     *
     * A fix taken *before* this says nothing about the question the check is
     * asking. Android hands out cached and queued readings freely — a
     * last-known location, an update that sat in a queue while the phone slept —
     * and one of those placing the user at the anchor would call off a check
     * started by a signal that came after it (Codex, PR #31).
     *
     * Kept at its original value while the check runs, so a stream of geofence
     * exits cannot keep invalidating the fixes that would settle them.
     */
    val checkingSinceMs: Long? = null,
    /**
     * The newest thing the engine has already accepted as evidence about where
     * the user is — a fix it used, or the moment it joined the anchor's network
     * — or null before the first.
     *
     * The other half of the stale-reading problem, and the half that has teeth
     * while *resting* (Codex, PR #31): a batched delivery can hand over an old
     * reading after a newer one, and an unambiguous stale fix would end the
     * snooze with no check running to reject it. Evidence only moves forward.
     *
     * **Association counts, not only fixes**, because it is evidence of the same
     * kind: a queued reading from before the phone rejoined the anchor's network
     * would otherwise arrive afterwards and end the snooze, overriding the newer
     * and stronger signal that it is still here (D4).
     *
     * **Seed this at arm time** with the elapsed realtime the snooze started,
     * so a last-known location cached from before the tile was tapped — which
     * could be from anywhere the phone has been — cannot be the first thing the
     * engine believes.
     */
    val latestEvidenceMs: Long? = null,
    /**
     * When the Wi-Fi grace period of SPEC.md §6.6 runs out, or null if none is
     * running. The caller arms an alarm for this and sends
     * [PresenceSignal.GraceElapsed]; a null means there is nothing to arm.
     */
    val graceDeadlineMs: Long? = null,
    /**
     * A recovery the engine has established but has not been able to report
     * yet, or null when there is nothing owed.
     *
     * One signal can prove location works *and* demand an escalation — a
     * qualifying fix taken while resting — and a step carries one event, which
     * the escalation has to win. Dropping the recovery there is how a snooze
     * ends up reading `Wi-Fi only` for good: the phone rejoins the anchor's
     * network, Wi-Fi suppresses location entirely (§6.7), and no later fix ever
     * arrives to correct it (Codex, PR #33). Held here instead, and attached to
     * the next event that goes out.
     */
    val pendingRecovery: PresenceEvidence? = null,
    /**
     * Whether this engine has already concluded the snooze is over.
     *
     * Terminal by construction: every later signal is ignored and re-reports
     * nothing. `endSnooze` is required to be idempotent (SPEC.md §7), and the
     * cheapest way to hold that is for the thing calling it to stop calling.
     */
    val resolved: Boolean = false,
)

/** The result of one signal: the state to carry, what to report, and what to ask of location. */
data class PresenceStep(
    val state: PresenceState,
    /**
     * What to report, or null for "nothing new".
     *
     * Null is the common answer and the important one. An event per signal would
     * have the controller re-entering `CHECKING` it is already in and the
     * notification rewriting itself while nothing changed — flapping produced by
     * the *reporting*, not by the sensors.
     */
    val event: PresenceEvent?,
    val duty: LocationDuty,
)

/**
 * Turns a stream of sensor signals into the handful of conclusions the
 * controller acts on (SPEC.md §6.1, §6.3, §6.7, §6.10).
 *
 * [Departure] decides what a single fix means. This decides everything around
 * it: which signals are worth escalating for, when location should be running at
 * all, when to admit tracking has degraded, and — with nothing left that can
 * confirm — when to fail open rather than keep a phone silent on a guess.
 *
 * Three properties are the point of the whole file, and each has tests:
 *
 * - **Only positive evidence de-escalates.** A reading that locates nobody
 *   leaves the engine exactly where it was. Treating "I could not tell" as "you
 *   are here" is what would have the duty cycle back off precisely when tracking
 *   has stopped working.
 * - **Nothing is reported twice.** Escalation, de-escalation and degradation
 *   each report on the transition, so a router that reboots or a provider that
 *   emits junk for an hour produces a handful of events rather than hundreds.
 * - **Ambiguity ends the snooze rather than outlasting it.** When Wi-Fi is gone
 *   *and* location cannot answer, a grace period runs and then the snooze ends
 *   (D7). The alternative is a phone that stays quiet because nothing could
 *   prove it should not.
 */
object Presence {

    /**
     * How many useless observations in a row before tracking is called degraded.
     *
     * Three rather than one, because a single vague fix is ordinary — walking
     * past a lift shaft produces one. Three in a row at the 90-second checking
     * rate is about four and a half minutes of location telling the user
     * nothing, which is worth a line in the notification.
     */
    const val DEGRADED_AFTER_USELESS_OBSERVATIONS: Int = 3

    /**
     * How long the anchor's Wi-Fi may stay away before an unverifiable snooze
     * ends (SPEC.md §6.6).
     *
     * Long enough to ride out a router reboot or a walk to the far end of a
     * building on 5 GHz; short enough that a user who actually left is not
     * silenced for the rest of the cap. It only ever runs when location cannot
     * answer, so it is the last thing standing between an unverifiable state and
     * a phone that never comes back.
     */
    val WIFI_GRACE: Duration = Duration.ofMinutes(5)

    /** What the caller should currently be asking of location (SPEC.md §6.7). */
    fun duty(state: PresenceState, anchor: Anchor): LocationDuty = when {
        state.resolved -> LocationDuty.NONE
        // Nothing to measure against, so every fix would come back inconclusive.
        !anchor.hasUsableFix -> LocationDuty.NONE
        // Checking outranks the Wi-Fi suppressor, and that ordering is the whole
        // point of escalating a geofence exit that contradicts an association
        // (Codex, PR #31). With the suppressor first, the disagreement changed
        // the phase and then asked for nothing — the fix that was supposed to
        // settle it never taken, and a real departure behind a stale
        // association invisible until the duration cap.
        state.phase == PresencePhase.CHECKING -> LocationDuty.ACTIVE
        // D4's asymmetry, and the whole battery budget: being associated with the
        // anchor's network is better evidence of presence than any fix, and free.
        state.atAnchorWifi -> LocationDuty.NONE
        else -> LocationDuty.SANITY
    }

    /**
     * Feeds one signal to the engine.
     *
     * [state] is whatever the previous call returned, or a fresh [PresenceState]
     * — with [PresenceState.atAnchorWifi] set from what arming saw, since a
     * snooze armed on the anchor's Wi-Fi should not spend a location fix
     * discovering that.
     */
    fun advance(
        state: PresenceState,
        signal: PresenceSignal,
        anchor: Anchor,
    ): PresenceStep {
        if (state.resolved) return step(state, null, anchor)
        return when (signal) {
            is PresenceSignal.AnchorWifiAssociated ->
                associated(state, signal.atElapsedRealtimeMs, anchor)
            is PresenceSignal.AnchorWifiLost ->
                // Checked for staleness like everything else, even though a loss
                // never *advances* the boundary (Codex, PR #31). A queued loss
                // older than evidence already accepted is old news by
                // definition: something later said the phone was here. Acting on
                // it would escalate — and, for a Wi-Fi-only anchor, arm a grace
                // period that ends the snooze five minutes on.
                if (isStale(state, signal.atElapsedRealtimeMs)) {
                    step(state, null, anchor)
                } else {
                    wifiLost(state, signal.atElapsedRealtimeMs, anchor)
                }
            is PresenceSignal.GeofenceExit -> escalate(state, signal.atElapsedRealtimeMs, anchor)
            is PresenceSignal.SignificantMotion ->
                // Suppressed while associated: the phone moving inside the place
                // it is anchored to is someone standing up, and D4 already has
                // better evidence than the accelerometer can offer.
                if (state.atAnchorWifi) {
                    step(state, null, anchor)
                } else {
                    escalate(state, signal.atElapsedRealtimeMs, anchor)
                }

            is PresenceSignal.FixArrived -> fixArrived(state, signal.fix, anchor)
            is PresenceSignal.FixUnavailable ->
                // A timeout is stale for the same reason a reading is, and it was
                // the one signal not checked (Codex, PR #31): a request whose
                // ceiling expires after Wi-Fi came back reports the failure of a
                // question already answered. Counting it moves a snooze one
                // observation closer to being called degraded, and — after a
                // later Wi-Fi loss — arms the grace period a cycle early, which
                // is a real shortening of someone's snooze.
                if (isStale(state, signal.atElapsedRealtimeMs)) {
                    step(state, null, anchor)
                } else {
                    useless(
                        state,
                        DegradationCause.NO_LOCATION_FIX,
                        signal.atElapsedRealtimeMs,
                        anchor,
                    )
                }

            is PresenceSignal.GraceElapsed -> graceElapsed(state, signal.atElapsedRealtimeMs, anchor)
        }
    }

    private fun associated(state: PresenceState, atMs: Long, anchor: Anchor): PresenceStep {
        // The *transition* is the evidence, not the callback (Codex, PR #31).
        // Network callbacks repeat — a capabilities change, a re-association,
        // the same network reported twice — and treating each one as fresh
        // proof of presence would let a repeat cancel a check that the
        // association itself did not settle. Concretely: a geofence exit
        // arriving while still associated escalates and asks for a fix, and the
        // next repeat would have called that off without anything having
        // answered, which is the stale-association case the escalation exists
        // for.
        if (state.atAnchorWifi) return step(state, null, anchor)

        // And the same staleness rule as every other observation, which this one
        // was the last to skip (Codex, PR #31). A queued association delivered
        // after the check that outdates it would otherwise cancel a real
        // departure check with evidence from before it started — the one
        // direction that leaves a phone silent rather than merely noisy.
        if (isStale(state, atMs)) return step(state, null, anchor)

        val next = state.copy(
            phase = PresencePhase.RESTING,
            atAnchorWifi = true,
            progress = DepartureProgress.NONE,
            uselessObservations = 0,
            // The degradation deliberately survives this (Codex, PR #33). Every
            // cause is a *location* cause, and rejoining the anchor's network
            // says nothing about whether location started working — so clearing
            // it here would drop the engine's only memory that location is
            // broken. What that cost: a fix arriving afterward finds neither a
            // check nor a degradation outstanding, reports nothing, and the
            // controller keeps a `WIFI_ONLY` claim that has stopped being true —
            // the stale line this event exists to withdraw. A usable fix is the
            // only thing that clears it, in `evaluate`, because a usable fix is
            // the only evidence that says location is back.
            // Reported below if there was one, so it stops being owed.
            pendingRecovery = null,
            wifiLostAtMs = null,
            graceDeadlineMs = null,
            checkingSinceMs = null,
            // Joining the anchor's network is evidence, so it moves the bar with
            // it: a reading captured before this moment answers an older
            // question and must not override it (Codex, PR #31).
            latestEvidenceMs = maxOfNullable(state.latestEvidenceMs, atMs),
        )
        // Reported only when it resolves something: rejoining the anchor's
        // network after a check or a degraded run is news, and arriving at a
        // state the controller is already in is not.
        val changed = state.phase == PresencePhase.CHECKING ||
            state.reportedDegradation != null ||
            state.pendingRecovery != null
        // The evidence is the best tracking this event can vouch for, which is
        // not always the signal that produced it. An owed recovery rides out
        // here (Codex, PR #33): location proved itself before the phone rejoined
        // the network, and this is the last event that will go out before Wi-Fi
        // suppresses location and takes the chance to say so with it.
        val proven = state.pendingRecovery ?: PresenceEvidence.ANCHOR_WIFI
        return step(next, if (changed) PresenceEvent.StillHere(proven) else null, anchor)
    }

    private fun wifiLost(state: PresenceState, atMs: Long, anchor: Anchor): PresenceStep {
        val next = state.copy(
            phase = PresencePhase.CHECKING,
            atAnchorWifi = false,
            wifiLostAtMs = atMs,
            graceDeadlineMs = state.graceDeadlineMs ?: graceFrom(atMs, state, anchor),
            checkingSinceMs = state.checkingSinceMs ?: atMs,
        )
        return step(next, escalationEvent(state), anchor)
    }

    private fun escalate(state: PresenceState, atMs: Long, anchor: Anchor): PresenceStep {
        // Departure *hints* are checked against the boundary too (Codex,
        // PR #31). A geofence exit that happened before evidence the engine has
        // since accepted is old news — something later placed the phone here —
        // and acting on it starts active location work for a question already
        // answered.
        //
        // This puts a requirement on the monitor: give the moment the transition
        // *happened* when the platform reports one it can stand behind, and the
        // delivery time when it cannot. A delivery time is never stale, which is
        // the fail-safe direction — the check runs, and a fix settles it.
        if (isStale(state, atMs)) return step(state, null, anchor)

        // A geofence exit escalates even while the anchor's Wi-Fi is associated,
        // and deliberately: the two subsystems disagreeing is exactly when a
        // fix is worth taking, and the cost of being wrong here is one location
        // request against a snooze that would otherwise run on unexamined
        // evidence. The fix settles it — inside the radius de-escalates again.
        val next = state.copy(
            phase = PresencePhase.CHECKING,
            checkingSinceMs = state.checkingSinceMs ?: atMs,
        )
        return step(next, escalationEvent(state), anchor)
    }

    private fun fixArrived(state: PresenceState, fix: Fix, anchor: Anchor): PresenceStep {
        // An anchor with nothing usable to measure against is Wi-Fi-only mode
        // (SPEC.md §8.4), not a tracking failure — so a fix here is ignored
        // rather than counted as a useless observation. Counting it would report
        // degradation the user can do nothing about, every time, for a snooze
        // whose notification already says it is Wi-Fi-only.
        if (!anchor.hasUsableFix) return step(state, null, anchor)

        if (isStale(state, fix.elapsedRealtimeMs)) return staleFix(state, fix, anchor)

        val outcome = Departure.consider(fix, anchor, state.progress)
        val accepted = state.copy(latestEvidenceMs = fix.elapsedRealtimeMs)
        return when (outcome.verdict) {
            DepartureVerdict.DEPARTED -> departed(accepted, anchor)

            DepartureVerdict.AWAITING_CONFIRMATION -> {
                // This reading says location works while saying nothing settled
                // about presence, which is the one combination `StillHere`
                // cannot express — so the recovery travels on its own event
                // (Codex, PR #33). Without it the degradation was cleared here
                // and never reported: the engine is already `CHECKING`, so
                // `escalationEvent` returns null, and after this the only
                // evidence left is Wi-Fi, which by design cannot restore
                // `FULL`. The notification then reads `Wi-Fi only` for the rest
                // of a snooze whose location came back.
                val escalation = escalationEvent(state)
                val recovery = if (state.reportedDegradation != null) {
                    PresenceEvent.TrackingRecovered(PresenceEvidence.LOCATION_FIX)
                } else {
                    null
                }
                val emitted = escalation ?: recovery
                val next = accepted.copy(
                    phase = PresencePhase.CHECKING,
                    progress = outcome.progress,
                    uselessObservations = 0,
                    // Location answered, so the engine no longer believes it is
                    // broken whatever it manages to report.
                    reportedDegradation = null,
                    pendingRecovery = when {
                        // Delivered by this step, so nothing is left owed.
                        recovery != null && emitted === recovery -> null
                        // Established now, but the escalation took the one event
                        // this step carries. Owed rather than dropped.
                        recovery != null -> recovery.confirmedBy
                        // Nothing new established, so a debt from an earlier fix
                        // stays a debt (Codex, PR #33). The second qualifying fix
                        // of a confirmation window arrives here with no
                        // degradation left to notice and no escalation to make,
                        // and overwriting would discharge a recovery that was
                        // never reported — leaving the association to report
                        // Wi-Fi alone, after which nothing asks for location
                        // again.
                        else -> state.pendingRecovery
                    },
                    // Location is answering again, so the grace period's premise
                    // — that nothing can confirm a departure — no longer holds,
                    // and an alarm left armed would end the snooze on a timer
                    // while the two-fix confirmation it exists to substitute for
                    // was actually running (Codex, PR #31). A later run of
                    // unusable readings arms a fresh one.
                    graceDeadlineMs = null,
                    checkingSinceMs = state.checkingSinceMs ?: fix.elapsedRealtimeMs,
                )
                step(next, emitted, anchor)
            }

            DepartureVerdict.STILL_HERE -> {
                // Positive evidence of presence, so it also calls off the grace
                // period: that timer exists for the case where nothing can
                // confirm, and something just did.
                val next = accepted.copy(
                    phase = PresencePhase.RESTING,
                    progress = DepartureProgress.NONE,
                    uselessObservations = 0,
                    reportedDegradation = null,
                    // This event carries the recovery itself, so nothing is owed.
                    pendingRecovery = null,
                    graceDeadlineMs = null,
                    checkingSinceMs = null,
                )
                val changed = state.phase == PresencePhase.CHECKING ||
                    state.reportedDegradation != null ||
                    state.pendingRecovery != null
                step(
                    next,
                    if (changed) PresenceEvent.StillHere(PresenceEvidence.LOCATION_FIX) else null,
                    anchor,
                )
            }

            DepartureVerdict.INCONCLUSIVE -> useless(
                accepted.copy(progress = outcome.progress),
                DegradationCause.FIXES_TOO_VAGUE,
                fix.elapsedRealtimeMs,
                anchor,
            )
        }
    }

    /**
     * A reading whose question has already been answered — but which may still
     * be the only proof that location started working again (Codex, PR #33).
     *
     * The two halves of a fix go stale at different rates. *Where the user was*
     * expires the moment newer evidence lands, which is what [isStale] is for.
     * *That location produced a reading good enough to measure with* does not
     * expire at all: the subsystem either managed it or it didn't.
     *
     * Dropping the whole reading strands a snooze whenever a queued fix is
     * delivered just after the phone rejoins the anchor's network — the
     * association bumps the evidence bar past the fix, and then suppresses
     * location entirely (§6.7), so nothing else will ever arrive to say
     * tracking recovered. The notification sits on `Wi-Fi only` until the next
     * time Wi-Fi drops.
     *
     * So the presence half is discarded exactly as before — no phase change, no
     * progress, no advance of the evidence bar — and only the capability is
     * reported.
     */
    private fun staleFix(state: PresenceState, fix: Fix, anchor: Anchor): PresenceStep {
        if (state.reportedDegradation == null) return step(state, null, anchor)

        // A reading captured *before* the failures says nothing about whether
        // they are over (Codex, PR #33). Cached and batched delivery makes this
        // reachable — a last-known location from before the trouble started,
        // handed over long after — and accepting it would promote the snooze to
        // FULL on evidence older than the problem, hiding a degradation while
        // departure tracking is still broken.
        val newerThanTheFailure = fix.elapsedRealtimeMs > (state.lastUnusableAtMs ?: Long.MIN_VALUE)
        if (!newerThanTheFailure) return step(state, null, anchor)

        // Only this reading's precision is being read. The verdict is computed
        // against a fresh [DepartureProgress] and then thrown away, so a stale
        // fix cannot advance the confirmation window it was never part of.
        val couldPlaceAnyone = Departure.consider(fix, anchor, DepartureProgress.NONE).verdict !=
            DepartureVerdict.INCONCLUSIVE
        if (!couldPlaceAnyone) return step(state, null, anchor)

        val next = state.copy(
            // The run of failures is over: location managed a usable reading.
            // Left standing, the count would re-report a degradation on the
            // very next vague fix rather than after a fresh run of them.
            uselessObservations = 0,
            reportedDegradation = null,
            pendingRecovery = null,
        )
        return step(next, PresenceEvent.TrackingRecovered(PresenceEvidence.LOCATION_FIX), anchor)
    }

    /**
     * An observation that said nothing: a fix too vague to place anyone, or no
     * fix at all.
     *
     * The phase is left exactly where it was. This is the rule the rest of the
     * file is built around — a reading that locates nobody is not evidence of
     * presence, so it neither de-escalates nor re-opens anything.
     */
    private fun useless(
        state: PresenceState,
        cause: DegradationCause,
        atMs: Long,
        anchor: Anchor,
    ): PresenceStep {
        if (!anchor.hasUsableFix) return step(state, null, anchor)

        val count = state.uselessObservations + 1
        // An accepted failure moves the boundary too, so a re-delivered timeout
        // counts once (Codex, PR #31) — the same duplicate-counting bug the fix
        // path had, in the path that reports it. The cost is that a reading
        // captured before a declared timeout and delivered after it is dropped;
        // that is a reading the request had already given up on, and the next
        // one is along at the checking rate.
        // Once per run of failures, whatever flavor they are (Codex, PR #31).
        // Reporting again on a *changed* cause reads as more honest and is the
        // opposite: fixes that alternate between arriving-useless and not
        // arriving would emit an event per sample, each rewriting the persisted
        // record and the ongoing notification — the flapping this file claims to
        // prevent, produced by its own reporting. Both causes lower tracking the
        // same way and say the same thing to the user; which one the last sample
        // was belongs in the debug log (SPEC.md §4.6), not in a notification
        // rewritten every 90 seconds. A usable fix clears the report, so the
        // next run says whatever is true then.
        val nowDegraded = count >= DEGRADED_AFTER_USELESS_OBSERVATIONS &&
            state.reportedDegradation == null
        val next = state.copy(
            uselessObservations = count,
            latestEvidenceMs = maxOfNullable(state.latestEvidenceMs, atMs),
            // Moves forward only, so a re-delivered failure cannot pull the
            // boundary back and let an older reading through.
            lastUnusableAtMs = maxOfNullable(state.lastUnusableAtMs, atMs),
            reportedDegradation = if (nowDegraded) cause else state.reportedDegradation,
            // Location has broken again, so a recovery still owed from before is
            // no longer true and must not be delivered by a later event.
            pendingRecovery = if (nowDegraded) null else state.pendingRecovery,
            // Location has just stopped being able to answer, so if Wi-Fi is
            // also gone the snooze is now unverifiable and the §6.6 grace period
            // starts here rather than never. Without this, a snooze that armed
            // healthy and then lost both signals would run to the cap in silence
            // — the failure the grace period exists to bound, reachable by the
            // ordinary route of walking out of a building.
            graceDeadlineMs = state.graceDeadlineMs
                ?: if (nowDegraded && state.wifiLostAtMs != null && anchor.ssid != null) {
                    atMs + WIFI_GRACE.toMillis()
                } else {
                    null
                },
        )
        return step(next, if (nowDegraded) PresenceEvent.Degraded(cause) else null, anchor)
    }

    private fun graceElapsed(state: PresenceState, atMs: Long, anchor: Anchor): PresenceStep {
        val deadline = state.graceDeadlineMs
        // Stale alarm: Wi-Fi came back, or a fix confirmed presence, and the
        // deadline was cleared underneath it. Nothing to do — an alarm that
        // outlives its reason must not end a snooze.
        if (deadline == null || atMs < deadline) return step(state, null, anchor)
        return departed(state, anchor)
    }

    private fun departed(state: PresenceState, anchor: Anchor): PresenceStep {
        val next = state.copy(
            phase = PresencePhase.CHECKING,
            progress = DepartureProgress.NONE,
            graceDeadlineMs = null,
            resolved = true,
        )
        return step(next, PresenceEvent.Departed, anchor)
    }

    /**
     * Whether something that happened at [atMs] is already answered.
     *
     * A reading — or the failure to get one — taken before the current check
     * started, or before evidence the engine has already accepted, answers an
     * older question (Codex, PR #31). Cached and queued work is ordinary on
     * Android: a last-known location, an update that sat in a queue while the
     * phone slept, a request whose ceiling expires after the thing it was asking
     * about has resolved. Applied to *every* observation rather than per caller,
     * because the one signal that skipped this check is exactly where the next
     * bug appeared.
     *
     * `<=` rather than `<`, so a **re-delivery counts once** (Codex, PR #31).
     * The same cached reading handed over three times is one observation, not
     * three, and counting it three times would reach the degradation threshold —
     * arming the grace period and shortening a snooze — on the strength of a
     * single thing location said. The cost of the stricter comparison is that a
     * genuinely new reading stamped at the same millisecond as the last one is
     * dropped; at millisecond resolution that is a coincidence, and the next
     * reading is along shortly.
     */
    private fun isStale(state: PresenceState, atMs: Long): Boolean {
        val notBeforeMs = maxOfNullable(state.checkingSinceMs, state.latestEvidenceMs) ?: return false
        return atMs <= notBeforeMs
    }

    /** [PresenceEvent.ProbablyLeft], but only if this is a change (SPEC.md §6.10). */
    private fun escalationEvent(state: PresenceState): PresenceEvent? =
        if (state.phase == PresencePhase.RESTING) PresenceEvent.ProbablyLeft else null

    /**
     * The grace deadline to start when Wi-Fi goes, or null if location can still
     * answer the question.
     *
     * Only armed for an anchor that has an SSID at all: with no Wi-Fi anchor
     * there was never a signal to lose, and a deadline armed off a network the
     * snooze does not depend on would end it for no reason.
     */
    private fun graceFrom(atMs: Long, state: PresenceState, anchor: Anchor): Long? = when {
        anchor.ssid == null -> null
        anchor.hasUsableFix && state.reportedDegradation == null -> null
        else -> atMs + WIFI_GRACE.toMillis()
    }

    private fun step(state: PresenceState, event: PresenceEvent?, anchor: Anchor) =
        PresenceStep(state, event, duty(state, anchor))

    /** The later of two moments, either of which may be absent. */
    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}
