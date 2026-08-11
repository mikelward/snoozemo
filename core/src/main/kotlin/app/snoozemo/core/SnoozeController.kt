package app.snoozemo.core

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The state machine of SPEC.md §4.1, and the place most of the app's real
 * complexity lives.
 *
 * Plain Kotlin over a clock and two injected interfaces, deliberately: it is
 * reachable by a JVM test with no Robolectric and no emulator, which is what
 * lets the rules that matter — the cap always fires, ending is idempotent,
 * ambiguity ends the snooze — be tested rather than hoped for.
 *
 * Not thread-safe on purpose. Callers drive it from one place (the service),
 * and adding a lock here would invite doing slow work while holding it.
 */
class SnoozeController(
    private val zen: ZenController,
    private val clock: Clock,
    private val listener: Listener,
) {

    /** Where the caller learns what happened, so nothing has to be inferred. */
    interface Listener {
        /** A transition happened. [reason] is null for arming. */
        fun onStateChanged(state: SnoozeState, snooze: ActiveSnooze?, reason: EndReason?)

        /**
         * Tracking degraded but the snooze continues — the notification must say
         * so rather than quietly becoming a timer (SPEC.md §8.1).
         */
        fun onDegraded(snooze: ActiveSnooze, cause: DegradationCause)

        /**
         * Arm or release didn't take. The caller surfaces this; it is never
         * assumed away.
         */
        fun onZenFailure(failure: ZenFailure, whileArming: Boolean)
    }

    var state: SnoozeState = SnoozeState.IDLE
        private set

    var active: ActiveSnooze? = null
        private set

    /**
     * Starts arming: turns the zen rule on **now**, before any anchor exists.
     *
     * This split is the whole point of the `ARMING` state (SPEC.md §4.1). Anchor
     * capture takes up to 10 s, and the phone must be quiet from the tap, not
     * from the fix — a controller that required a finished anchor would either
     * leave DND off for those 10 s or arm with an anchor it could never replace.
     * So the rule goes on first and the anchor lands afterward, via
     * [onAnchorCaptured] or [onAnchorUnavailable].
     *
     * Until then the snooze is honestly [TrackingMode.DURATION_ONLY]: nothing has
     * been captured yet, so nothing can detect a departure yet, and the cap is
     * the only exit that could fire.
     *
     * Returns false when the rule could not be turned on — there is no "armed
     * anyway" state, because a snooze the platform doesn't know about is a lie
     * to the user rather than a degraded mode.
     */
    fun beginArming(
        cap: Duration = ActiveSnooze.DEFAULT_CAP,
        placeName: String = ActiveSnooze.DEFAULT_PLACE_NAME,
    ): Boolean {
        val now = clock.instant()
        val snooze = ActiveSnooze(
            anchor = Anchor(capturedAt = now),
            startedAt = now,
            capExpiresAt = now.plus(cap.coerceIn(ActiveSnooze.MIN_CAP, ActiveSnooze.MAX_CAP)),
            mode = TrackingMode.DURATION_ONLY,
            placeName = placeName,
        )

        // Recorded before the rule is turned on, so a process death in between
        // leaves evidence that a snooze may be running. Believing we are snoozed
        // when we aren't is recoverable; the reverse leaves a silent phone.
        active = snooze
        state = SnoozeState.ARMING
        listener.onStateChanged(state, snooze, null)

        return when (val outcome = zen.setSnoozed(true, ZenTrigger.USER_ACTION, placeName)) {
            is ZenOutcome.Applied -> true
            is ZenOutcome.NotApplied -> {
                active = null
                state = SnoozeState.IDLE
                listener.onZenFailure(outcome.reason, whileArming = true)
                listener.onStateChanged(state, null, null)
                false
            }
        }
    }

    /**
     * The anchor arrived: the snooze is now fully armed and departure can be
     * detected. Ignored if nothing is arming — a late fix for a snooze that has
     * already ended must not resurrect it.
     */
    fun onAnchorCaptured(anchor: Anchor) {
        val snooze = active ?: return
        val armed = snooze.copy(anchor = anchor, mode = TrackingMode.from(anchor))
        active = armed
        state = SnoozeState.ARMED
        listener.onStateChanged(state, armed, null)
        // Armed, but say so if it armed degraded: a snooze that is really only a
        // timer must not look like a tracked one (SPEC.md §4.1, §8.1).
        if (armed.mode != TrackingMode.FULL) {
            listener.onDegraded(armed, DegradationCause.NO_LOCATION_FIX)
        }
    }

    /**
     * The 10 s ceiling passed with no usable fix (SPEC.md §4.1). Arms anyway in
     * whatever mode [ssid] supports — Wi-Fi-only if we are on a network,
     * duration-only if not — and reports the degradation, because arming must
     * never feel slow or refuse, and a degraded snooze must never look healthy.
     */
    fun onAnchorUnavailable(ssid: String?) {
        val snooze = active ?: return
        onAnchorCaptured(snooze.anchor.copy(ssid = ssid))
    }

    /**
     * Ends the snooze. **Idempotent** (SPEC.md §7): calling it twice, or while
     * idle, is safe and still drives the rule off — the three exits can race,
     * and every one of them must be allowed to fire without checking first.
     *
     * **A release the platform refuses does not clear the snooze.** The record is
     * the only thing that can turn the rule off later: it keeps the cap alarm
     * armed, the notification on screen, and the tile showing `Snoozing`, so the
     * next cap check or tap retries. Clearing it would leave the rule active with
     * nothing left in the app that knows to try again — a phone silent
     * indefinitely, which is principle 1's failure. The failure is reported
     * either way, so a stuck release is visible rather than silent.
     */
    fun end(reason: EndReason) {
        val ending = active
        val trigger = if (reason == EndReason.MANUAL) ZenTrigger.USER_ACTION else ZenTrigger.CONTEXT

        val outcome = zen.setSnoozed(false, trigger, ending?.placeName ?: ActiveSnooze.DEFAULT_PLACE_NAME)
        if (outcome is ZenOutcome.NotApplied) {
            listener.onZenFailure(outcome.reason, whileArming = false)
            // Deliberately keeps `active` and the current state so a retry is
            // still possible. Not a stuck state machine: onCapCheck, the tile,
            // and the notification action all call end() again.
            return
        }

        active = null
        state = SnoozeState.RELEASED
        listener.onStateChanged(state, ending, reason)
        state = SnoozeState.IDLE
    }

    /**
     * The duration cap — the backstop that holds when every sensor has failed
     * (SPEC.md §7, D6). Called from the alarm and from the in-service timer,
     * which is why it re-checks rather than trusting the caller: an alarm can
     * fire late, early, or twice.
     */
    fun onCapCheck() {
        val snooze = active ?: return
        if (snooze.isExpired(clock.instant())) end(EndReason.DURATION_CAP)
    }

    /** What the presence engine concluded (SPEC.md §6.1). */
    fun onPresenceEvent(event: PresenceEvent) {
        val snooze = active ?: return
        when (event) {
            PresenceEvent.StillHere -> if (state == SnoozeState.CHECKING) {
                state = SnoozeState.ARMED
                listener.onStateChanged(state, snooze, null)
            }

            PresenceEvent.ProbablyLeft -> if (state == SnoozeState.ARMED) {
                // Escalate only. No single source ends a snooze on its own
                // evidence (SPEC.md §6.10).
                state = SnoozeState.CHECKING
                listener.onStateChanged(state, snooze, null)
            }

            PresenceEvent.Departed -> end(EndReason.DEPARTURE)

            is PresenceEvent.Degraded -> {
                val degraded = snooze.copy(mode = event.cause.modeFor(snooze.anchor))
                active = degraded
                listener.onDegraded(degraded, event.cause)
            }

            // Fail open: tracking cannot be done at all, so the snooze ends
            // rather than staying armed on state nothing can verify.
            is PresenceEvent.CapabilityLost -> end(EndReason.LOST_CAPABILITY)
        }
    }

    /**
     * Restores a snooze that outlived the process (SPEC.md §8.1). The cap
     * continues from its original start — a reboot does not extend a snooze
     * (§8.3) — so an already-expired one ends immediately rather than being
     * resurrected.
     */
    fun restore(snooze: ActiveSnooze) {
        active = snooze

        // Re-assert the rule rather than assume it survived (SPEC.md §8.1). The
        // record surviving does not mean the rule's condition did — a reboot, an
        // app update, or the platform dropping it would otherwise leave the app
        // showing a snooze over a phone that rings.
        val outcome = zen.setSnoozed(true, ZenTrigger.CONTEXT, snooze.placeName)
        if (outcome is ZenOutcome.NotApplied) {
            // Cannot honor the snooze, so don't claim to. Ends without calling
            // the zen controller again: the rule is already not on — that is why
            // this failed — and a second call would only fail the same way.
            listener.onZenFailure(outcome.reason, whileArming = true)
            active = null
            state = SnoozeState.RELEASED
            listener.onStateChanged(state, snooze, EndReason.LOST_CAPABILITY)
            state = SnoozeState.IDLE
            return
        }

        state = SnoozeState.ARMED
        listener.onStateChanged(state, snooze, null)
        onCapCheck()
    }

    /**
     * How far a degradation actually knocks tracking down, which depends on what
     * the anchor had to begin with: losing location leaves Wi-Fi *only if there
     * was an SSID*, and claiming `WIFI_ONLY` for an anchor with no network would
     * tell the user tracking is better than it is.
     */
    private fun DegradationCause.modeFor(anchor: Anchor): TrackingMode = when (this) {
        // Location is gone but may come back; Wi-Fi still suppresses if we have it.
        DegradationCause.NO_LOCATION_FIX,
        DegradationCause.LOCATION_SERVICES_OFF,
        DegradationCause.NO_LOCATION_IN_BACKGROUND,
        -> if (anchor.ssid != null) TrackingMode.WIFI_ONLY else TrackingMode.DURATION_ONLY
    }
}
