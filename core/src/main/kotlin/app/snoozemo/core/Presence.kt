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

    /**
     * Wi-Fi is connected, but the watch cannot yet say *which* network
     * (SPEC.md §6.6). The synchronous seed read that runs when the watch is
     * built can only see whether any Wi-Fi is present — a direct
     * capabilities read has the SSID redacted, and only the async callback
     * (with `FLAG_INCLUDE_LOCATION_INFO`) can name it. So this is the seed's
     * way of saying "hold on: a real association result is coming."
     *
     * It settles nothing about presence on its own — it is neither
     * association nor loss — and its whole job is one narrow ordering case:
     * a snooze restored *by the grace alarm* with a due deadline replays a
     * held [GraceElapsed] the instant the bridge attaches, before the async
     * callback can report that the user is back on the anchor's network. On
     * that path this signal makes the due deadline defer *once* for a short
     * confirmation window ([Presence.WIFI_CONFIRM]) instead of resolving
     * [PresenceEvent.Departed], so the callback that follows can clear it. If
     * the callback says the user really left (a different network, or none),
     * the window elapses and the snooze ends then — fail-open, delayed by
     * the window, never lost (D7).
     */
    data class AnchorWifiPresentUnconfirmed(override val atElapsedRealtimeMs: Long) : PresenceSignal

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

    /**
     * The location grant these sensors run on is gone — revoked mid-snooze,
     * downgraded to coarse, or never granted in the background
     * ([DegradationCause.isGrantLoss]).
     *
     * A sensor-layer fact like every other signal here, not a conclusion:
     * what it says is that the platform has stopped answering, and the
     * engine decides what follows. What follows is that **Wi-Fi stops being
     * evidence**. An SSID read needs the same grant, and a background read
     * without it comes back as the redaction placeholder, which the Wi-Fi
     * watch reports as [AnchorWifiLost] — so under a dead grant the engine
     * is told the anchor's network went away whether or not it did, and a
     * §6.6 grace period armed on that would end the snooze five minutes
     * after the *permission* changed. That is the ending the maintainer's
     * 2026-08-30 decision exists to prevent, arriving by a different door.
     *
     * So this clears any running deadline and refuses to arm another until
     * something proves the grant is back — a fix arriving, or the anchor's
     * SSID being nameable again. The snooze keeps running on the duration
     * cap, which is mandatory, so the fallback is bounded by construction.
     *
     * Nothing else moves: this is not evidence about *where* the user is, so
     * it must not escalate, de-escalate, or advance the staleness bar.
     */
    data class LocationAccessLost(override val atElapsedRealtimeMs: Long) : PresenceSignal

    /**
     * The location grant is back, proven rather than inferred
     * ([LocationAccessLost]'s only refutation).
     *
     * The monitor says this, and nothing in the engine works it out for
     * itself, because nothing in the engine can (Codex, PR #150). Two
     * plausible engine-side proofs were tried and both were wrong:
     *
     * - **A fix arriving.** Location hands out cached and queued readings, so
     *   one captured *before* the revocation can be delivered after it — the
     *   same trap `lastUnusableAtMs` exists for, one layer down. It would
     *   clear the latch on evidence older than the problem.
     * - **The anchor's SSID being nameable.** True proof for a revoked grant,
     *   false for a missing *background* one: opening the app makes the SSID
     *   readable under the while-in-use grant it still holds, and the moment
     *   it goes back to the background the read is redacted again. The latch
     *   would clear on a foreground glance and the next loss would arm a
     *   grace period the card is still promising the cap for.
     *
     * A successful geofence registration is neither: it requires
     * `ACCESS_BACKGROUND_LOCATION` outright on API 29+, so it can only
     * succeed with both grants actually held, and it is the same evidence
     * that already refutes the monitor's own registration level. One proof,
     * one refutation, no second judge.
     */
    data class LocationAccessRestored(override val atElapsedRealtimeMs: Long) : PresenceSignal
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
    val degradation: DegradationCause? = null,
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
     * **Both fix paths consult it, and the reason the stale one used to be
     * enough is worth keeping** (Codex, PR #142). A failed observation
     * advances `latestEvidenceMs` and this boundary together, so within one
     * process a reading fresh enough to be accepted for presence is newer
     * than every failure by construction, and checking again on that path
     * would be a no-op.
     *
     * A **restarted** monitor separates the two deliberately, which is what
     * makes the check real. `latestEvidenceMs` is seeded to the snooze's arm
     * moment — raising it to the restart would discard a held geofence exit
     * the restart was woken to deliver (Codex, PR #73) — while a resumed
     * degradation's boundary is the restart itself, since a process cannot
     * vouch for a reading it never saw arrive. A fix banked after the arm and
     * before the failures then lands in the gap: fresh by the evidence test,
     * and still silent about whether the trouble is over.
     *
     * So `fixArrived` applies this boundary as well, and the check is not
     * redundant however it reads in a single-process trace. Removing it
     * restores the promotion described above, across exactly the restart this
     * boundary is now seeded for.
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
     * Whether a Wi-Fi association result is still pending and a due grace
     * deadline should defer once rather than resolve
     * ([PresenceSignal.AnchorWifiPresentUnconfirmed]).
     *
     * Set by the seed read finding Wi-Fi connected but unnameable; cleared by
     * the first definitive association signal (associated or lost), by a fix
     * that confirms presence, and by the one deferral it grants — so a
     * `GraceElapsed` can be held back at most once, and a callback that never
     * comes still ends the snooze after the window (fail-open, D7).
     */
    val awaitingAssociationConfirmation: Boolean = false,
    /**
     * Whether *this* grace deadline has already spent its one confirmation
     * deferral (SPEC.md §6.6).
     *
     * The bound has to be a property of the deadline, not of the process,
     * because [awaitingAssociationConfirmation] is transient — a restart
     * loses it and the seed re-sets it — so without a durable record a
     * process death inside the confirmation window would let the restored
     * seed grant a *fresh* deferral, extending the deadline again and again
     * under repeated background reclamation until the duration cap (Codex,
     * PR #106). So the monitor persists this alongside the deadline, and it
     * resets whenever the deadline itself is cleared — a genuine recovery
     * ends the episode, and any later outage is a new one that earns its own
     * single deferral.
     */
    val confirmationDeferralUsed: Boolean = false,
    /**
     * Whether the location grant is currently known to be gone
     * ([PresenceSignal.LocationAccessLost]), which suppresses the §6.6 grace
     * period until something proves otherwise.
     *
     * Deliberately **not** persisted, unlike [graceDeadlineMs]. It mirrors a
     * platform fact the monitor re-derives on every restart — its
     * registration attempt is refused again and re-reports the cause — so
     * persisting it would be a second copy of the same truth, kept behind a
     * different refutation, which is exactly the two-slot mistake PR #75
     * exists to prevent. Losing it across a process death errs toward arming
     * a grace period that should not be armed, which the re-derivation
     * corrects within one registration attempt.
     */
    val locationAccessLost: Boolean = false,
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

    /**
     * How long a due grace deadline defers when a Wi-Fi association result is
     * still pending ([PresenceSignal.AnchorWifiPresentUnconfirmed], SPEC.md
     * §6.6).
     *
     * The one case it covers: a snooze restored *by the grace alarm* replays
     * a held [PresenceSignal.GraceElapsed] the instant the bridge attaches,
     * before the watch's async callback can report that the user is back on
     * the anchor's network — so a due deadline would end a snooze the user
     * returned to. This is the margin the callback runs in. Short, because
     * the callback for an already-connected network arrives in well under a
     * second; long enough to ride out the restore. Spent at most once per
     * pending result, and only on the grace-restore path — a snooze whose
     * user genuinely left has no Wi-Fi, so its seed reports a loss and never
     * reaches here.
     */
    val WIFI_CONFIRM: Duration = Duration.ofSeconds(30)

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
            is PresenceSignal.AnchorWifiPresentUnconfirmed ->
                anchorWifiPresentUnconfirmed(state, anchor)
            is PresenceSignal.LocationAccessLost -> locationAccessLost(state, anchor)
            is PresenceSignal.LocationAccessRestored ->
                // Nothing else moves, for the same reason the loss moves
                // nothing else: it is a fact about the platform, not about
                // where the user is. The degradation it accompanies is the
                // monitor's own level and is withdrawn by the monitor.
                step(state.copy(locationAccessLost = false), null, anchor)
        }
    }

    /**
     * Records that the location grant is gone and calls off any grace period
     * running on it (see [PresenceSignal.LocationAccessLost]).
     *
     * Not staleness-checked, and that is the one place this signal
     * deliberately differs from the sensor readings around it. [isStale]
     * exists to stop an old *observation of where the user was* overriding a
     * newer one; this observes the platform's own configuration, which has no
     * position to be out of date about. Applying the bar would also drop the
     * signal precisely when it matters most — the queued delivery after a
     * newer Wi-Fi loss is the case where the deadline is already armed.
     */
    private fun locationAccessLost(state: PresenceState, anchor: Anchor): PresenceStep {
        val next = state.copy(
            locationAccessLost = true,
            // The premise is withdrawn, not merely paused: the deadline this
            // clears was armed by a Wi-Fi loss that may only ever have been
            // the redaction placeholder. Cleared through the same fields a
            // genuine recovery clears, so the monitor's `deliver` persists
            // the null and `GraceAlarm.reconcile` cancels the real alarm —
            // a mode line that said `Timer only` while an alarm still ended
            // the snooze would be worse than saying nothing.
            graceDeadlineMs = null,
            confirmationDeferralUsed = false,
            awaitingAssociationConfirmation = false,
        )
        // No event: nothing about presence changed, and the degradation this
        // reflects is reported by the monitor's own platform level.
        return step(next, null, anchor)
    }

    /**
     * Records that a real association result is still coming, so the next due
     * [PresenceSignal.GraceElapsed] defers once (see
     * [PresenceState.awaitingAssociationConfirmation]).
     *
     * Deliberately touches nothing else — not the phase, not the evidence
     * bar, not the deadline. It is not evidence of presence (it cannot name
     * the network) and not evidence of absence, so it must not de-escalate,
     * advance staleness, or arm anything. Off the grace-restore path it is
     * inert: with no deadline pending, no `GraceElapsed` will come to read the
     * flag, and the first association callback clears it regardless.
     */
    private fun anchorWifiPresentUnconfirmed(state: PresenceState, anchor: Anchor): PresenceStep {
        if (state.awaitingAssociationConfirmation) return step(state, null, anchor)
        return step(state.copy(awaitingAssociationConfirmation = true), null, anchor)
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
        if (state.atAnchorWifi) {
            // Still clear a pending confirmation even on a repeat: this *is*
            // the definitive association result the seed's
            // `AnchorWifiPresentUnconfirmed` was waiting on, so a due grace
            // deadline must resolve normally from here, not defer again.
            return if (state.awaitingAssociationConfirmation) {
                step(state.copy(awaitingAssociationConfirmation = false), null, anchor)
            } else {
                step(state, null, anchor)
            }
        }

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
            wifiLostAtMs = null,
            graceDeadlineMs = null,
            checkingSinceMs = null,
            // `locationAccessLost` deliberately survives this (Codex, PR
            // #150). A nameable SSID proves a *revoked* grant is back and
            // proves nothing about a missing background one: the app in the
            // foreground reads the SSID fine under a while-in-use grant, and
            // redacted again the moment it isn't. Clearing here would lift
            // the suppressor on a glance at the app, and the next background
            // read would arm a grace period while the card still promised
            // the cap. Only [PresenceSignal.LocationAccessRestored] clears
            // it.
            // The definitive result the pending-confirmation flag was waiting
            // on: the user is back on the anchor's network, and the deadline
            // just cleared, so there is nothing left to defer — and the
            // deferral bound resets with the deadline, so a later outage is a
            // new episode that earns its own single deferral.
            awaitingAssociationConfirmation = false,
            confirmationDeferralUsed = false,
            // Joining the anchor's network is evidence, so it moves the bar with
            // it: a reading captured before this moment answers an older
            // question and must not override it (Codex, PR #31).
            latestEvidenceMs = maxOfNullable(state.latestEvidenceMs, atMs),
        )
        // Purely the phase transition now: the degradation travels as a level on
        // every update, so this event has only one job — telling the controller
        // a check is over. Arriving at a state it is already in is not news.
        val settlesACheck = state.phase == PresencePhase.CHECKING
        return step(next, if (settlesACheck) PresenceEvent.StillHere else null, anchor)
    }

    private fun wifiLost(state: PresenceState, atMs: Long, anchor: Anchor): PresenceStep {
        val next = state.copy(
            phase = PresencePhase.CHECKING,
            atAnchorWifi = false,
            wifiLostAtMs = atMs,
            graceDeadlineMs = state.graceDeadlineMs ?: graceFrom(atMs, state, anchor),
            checkingSinceMs = state.checkingSinceMs ?: atMs,
            // A definitive Wi-Fi result — the network is a different one, or
            // gone — so the pending confirmation is answered: the user did not
            // return to the anchor. The existing deadline stands and its next
            // firing resolves normally rather than deferring again.
            awaitingAssociationConfirmation = false,
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
        // Deliberately does **not** clear `locationAccessLost` (Codex, PR
        // #150). A reading can be cached or queued from before the
        // revocation, so treating its arrival as proof the grant returned
        // repeats `lastUnusableAtMs`'s own lesson one layer down — see
        // [PresenceSignal.LocationAccessRestored], which is the only thing
        // that clears it.

        // An anchor with nothing usable to measure against is Wi-Fi-only mode
        // (SPEC.md §8.4), not a tracking failure — so a fix here is ignored
        // rather than counted as a useless observation. Counting it would report
        // degradation the user can do nothing about, every time, for a snooze
        // whose notification already says it is Wi-Fi-only.
        if (!anchor.hasUsableFix) return step(state, null, anchor)

        if (isStale(state, fix.elapsedRealtimeMs)) return staleFix(state, fix, anchor)

        val outcome = Departure.consider(fix, anchor, state.progress)
        val accepted = state.copy(latestEvidenceMs = fix.elapsedRealtimeMs)
        // SPEC.md §6.1's "evidence of health must be newer than the failure it
        // claims is over", applied on this path too. It used to live only in
        // [staleFix], which was enough while both boundaries moved together:
        // a useless observation advances `latestEvidenceMs` and
        // `lastUnusableAtMs` alike, so in one process a reading fresh enough
        // to reach here is newer than the failure by construction and this is
        // a no-op.
        //
        // A restarted monitor breaks that pairing deliberately (Codex, PR
        // #142). `latestEvidenceMs` is seeded to the *arm* moment, because
        // raising it to the restart would drop the held geofence exit the
        // restart was woken to deliver (Codex, PR #73) — while a restored
        // degradation's floor is the *restart*, since this process cannot
        // vouch for a reading it never saw arrive. So a fix banked after the
        // arm but before the failures began is fresh by the evidence test and
        // still says nothing about whether the trouble is over; without this
        // it would clear the level and restore FULL on evidence older than
        // the problem.
        val provesHealth = fix.elapsedRealtimeMs > (state.lastUnusableAtMs ?: Long.MIN_VALUE)
        // Only the health half is withheld. The reading's *presence* half is
        // legitimately fresh, so the phase, the departure progress and the
        // grace period all still act on it.
        val healthAfter = if (provesHealth) null else state.degradation
        val uselessAfter = if (provesHealth) 0 else state.uselessObservations
        return when (outcome.verdict) {
            DepartureVerdict.DEPARTED -> departed(accepted, anchor)

            DepartureVerdict.AWAITING_CONFIRMATION -> {
                val next = accepted.copy(
                    phase = PresencePhase.CHECKING,
                    progress = outcome.progress,
                    uselessObservations = uselessAfter,
                    // Location answered, so the engine no longer believes it is
                    // broken — and that travels as a level, so nothing has to be
                    // said about it here. This reading proves the capability
                    // while settling nothing about presence, which used to be the
                    // one combination no event could express (Codex, PR #33).
                    // Withheld when the reading pre-dates the failure it would
                    // be claiming is over — see `provesHealth` above.
                    degradation = healthAfter,
                    // Location is answering again, so the grace period's premise
                    // — that nothing can confirm a departure — no longer holds,
                    // and an alarm left armed would end the snooze on a timer
                    // while the two-fix confirmation it exists to substitute for
                    // was actually running (Codex, PR #31). A later run of
                    // unusable readings arms a fresh one.
                    graceDeadlineMs = null,
                    // The deadline is gone, so its one-shot deferral bound
                    // resets with it (Codex, PR #106): a fresh grace episode
                    // earns its own deferral.
                    confirmationDeferralUsed = false,
                    checkingSinceMs = state.checkingSinceMs ?: fix.elapsedRealtimeMs,
                )
                step(next, escalationEvent(state), anchor)
            }

            DepartureVerdict.STILL_HERE -> {
                // Positive evidence of presence, so it also calls off the grace
                // period: that timer exists for the case where nothing can
                // confirm, and something just did.
                val next = accepted.copy(
                    phase = PresencePhase.RESTING,
                    progress = DepartureProgress.NONE,
                    uselessObservations = uselessAfter,
                    // Same withholding as above: presence is confirmed, but a
                    // reading older than the failures does not retract them.
                    degradation = healthAfter,
                    graceDeadlineMs = null,
                    checkingSinceMs = null,
                    // Presence is confirmed and the deadline cleared, so a
                    // pending Wi-Fi confirmation has nothing left to defer, and
                    // the deferral bound resets with the deadline.
                    awaitingAssociationConfirmation = false,
                    confirmationDeferralUsed = false,
                )
                val settlesACheck = state.phase == PresencePhase.CHECKING
                step(next, if (settlesACheck) PresenceEvent.StillHere else null, anchor)
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
        if (state.degradation == null) return step(state, null, anchor)

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
            degradation = null,
        )
        // No event: the recovery is the level, and the level is on every update.
        return step(next, null, anchor)
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
        // **The cause now follows the failures; it used to freeze** (Codex,
        // PR #141, reversing PR #31's call here). The old rule kept whichever
        // flavor first crossed the threshold until a usable fix cleared it,
        // and its stated reason was that "both causes lower tracking the same
        // way and say the same thing to the user" — so the distinction was
        // debug-log detail (SPEC.md §4.6) and re-reporting it bought a
        // notification rewrite for nothing.
        //
        // That premise is what changed. The two causes no longer say the same
        // thing: the ongoing card renders `no location` for one and `weak
        // location signal` for the other (SPEC.md §4.3), because they mean
        // opposite things to a reader — something is broken, versus location
        // works and cannot place you here. Frozen, the card asserts whichever
        // was true first for the rest of a run, so walking from a weak-signal
        // spot into one with no fixes at all leaves it saying `weak location
        // signal` indefinitely. That is the stale reason §8.1 exists to
        // prevent, and it also made the controller's own cause comparison
        // unreachable through this path.
        //
        // The flapping cost is real and is accepted rather than dismissed:
        // alternating samples now restate an alternating level, so the card
        // can be rewritten at the checking cadence. It stays a *level*, never
        // an event (see the `step` below), so nothing alerts — the repost is
        // silent and costs a preferences write and a tile refresh, which is
        // the price of the line not lying.
        //
        // `nowDegraded` deliberately keeps its old meaning — "the level just
        // moved off null" — because the §6.6 grace deadline below arms on it,
        // and that must fire once when tracking gives up, not again every
        // time the flavor of the failure changes.
        val nowDegraded = count >= DEGRADED_AFTER_USELESS_OBSERVATIONS &&
            state.degradation == null
        // Whether this observation is part of the run the level describes,
        // rather than one from before it that arrived late (see the
        // `degradation` assignment below). Null means nothing has failed yet,
        // so the first run is never held back.
        val namesThisRun = atMs > (state.lastUnusableAtMs ?: Long.MIN_VALUE)
        val next = state.copy(
            uselessObservations = count,
            latestEvidenceMs = maxOfNullable(state.latestEvidenceMs, atMs),
            // Moves forward only, so a re-delivered failure cannot pull the
            // boundary back and let an older reading through.
            lastUnusableAtMs = maxOfNullable(state.lastUnusableAtMs, atMs),
            // Not `nowDegraded`: that one is false once a level already
            // stands, which is exactly the case this has to update.
            //
            // `namesThisRun` is the same boundary `fixArrived` applies before
            // letting a reading retract a degradation, on the one path that
            // did not have it (Codex, PR #142). An observation older than
            // the standing failure cannot say what is failing *now* any more
            // than it can say the failures are over. In one process it never
            // fires — a useless observation advances this boundary as it
            // sets it, so the next is always later — but a resumed level
            // starts with the boundary at the restart and the counter
            // already at the threshold, so a cached inconclusive reading
            // banked before the restart would otherwise relabel
            // `no location` as `weak location signal` while nothing had
            // arrived at all, and survive further restarts saying it.
            degradation = if (count >= DEGRADED_AFTER_USELESS_OBSERVATIONS && namesThisRun) {
                cause
            } else {
                state.degradation
            },
            // Location has just stopped being able to answer, so if Wi-Fi is
            // also gone the snooze is now unverifiable and the §6.6 grace period
            // starts here rather than never. Without this, a snooze that armed
            // healthy and then lost both signals would run to the cap in silence
            // — the failure the grace period exists to bound, reachable by the
            // ordinary route of walking out of a building.
            graceDeadlineMs = state.graceDeadlineMs
                // `graceSuppressed` asked here too, not only in `graceFrom`:
                // this branch arms a deadline of its own, so the guard has to
                // be on both or it is on neither (Codex, PR #150).
                ?: if (
                    nowDegraded && state.wifiLostAtMs != null && anchor.ssid != null &&
                    !graceSuppressed(state)
                ) {
                    atMs + WIFI_GRACE.toMillis()
                } else {
                    null
                },
        )
        // No event: `nowDegraded` only decides whether the *level* moves, and
        // the level is reported on every update either way.
        return step(next, null, anchor)
    }

    private fun graceElapsed(state: PresenceState, atMs: Long, anchor: Anchor): PresenceStep {
        val deadline = state.graceDeadlineMs
        // Stale alarm: Wi-Fi came back, or a fix confirmed presence, and the
        // deadline was cleared underneath it. Nothing to do — an alarm that
        // outlives its reason must not end a snooze.
        if (deadline == null || atMs < deadline) return step(state, null, anchor)
        // A restore woke this deadline before the watch's async callback could
        // report an association that is still pending (SPEC.md §6.6): defer
        // once for the confirmation window rather than end a snooze the user
        // may have returned to. `confirmationDeferralUsed` is the *durable*
        // half of the bound — the transient flag alone would re-grant a
        // deferral on every restart inside the window (Codex, PR #106) — so a
        // deadline that has already deferred once resolves now, and a callback
        // that never comes lets this firing end the snooze after the window,
        // never never (D7). The window extends from this firing, not the
        // original deadline, so the callback gets the full margin.
        if (state.awaitingAssociationConfirmation && !state.confirmationDeferralUsed) {
            val next = state.copy(
                awaitingAssociationConfirmation = false,
                confirmationDeferralUsed = true,
                graceDeadlineMs = atMs + WIFI_CONFIRM.toMillis(),
            )
            return step(next, null, anchor)
        }
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
    /**
     * Whether anything may arm a §6.6 deadline at all right now.
     *
     * **There are two arming sites, and a guard on one of them is not a
     * guard** (Codex, PR #150). The first version of the grant suppressor
     * checked only [graceFrom], leaving `useless`'s own
     * degradation-triggered branch to arm a countdown under a dead grant by
     * a different route — the same premature ending, reached three failed
     * fixes later instead of on the Wi-Fi loss. So the question is asked in
     * one named place that both sites call, and a third arming site that
     * forgets to ask is a grep away from being found.
     *
     * Wi-Fi is not evidence while the grant is gone: the SSID reads as
     * absent *because* the permission does, and the phone may never have
     * moved (maintainer, 2026-08-30). Only
     * [PresenceSignal.LocationAccessRestored] lifts this.
     */
    private fun graceSuppressed(state: PresenceState): Boolean = state.locationAccessLost

    private fun graceFrom(atMs: Long, state: PresenceState, anchor: Anchor): Long? = when {
        anchor.ssid == null -> null
        graceSuppressed(state) -> null
        anchor.hasUsableFix && state.degradation == null -> null
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
