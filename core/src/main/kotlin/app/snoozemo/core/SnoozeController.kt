package app.snoozemo.core

import java.time.Duration
import java.time.Instant

/**
 * The state machine of SPEC.md §4.1, and the place most of the app's real
 * complexity lives.
 *
 * Plain Kotlin over a clock reading and two injected interfaces, deliberately: it
 * is reachable by a JVM test with no Robolectric and no emulator, which is what
 * lets the rules that matter — the cap always fires, ending is idempotent,
 * ambiguity ends the snooze — be tested rather than hoped for.
 *
 * Not thread-safe on purpose. Callers drive it from one place (the service),
 * and adding a lock here would invite doing slow work while holding it.
 */
class SnoozeController(
    private val zen: ZenController,
    /**
     * Both clocks, read together at the moment they are needed.
     *
     * A [ClockReading] rather than a `java.time.Clock` because the cap has to
     * survive the wall clock moving, and one frame cannot tell you that it has
     * (see [ActiveSnooze.remaining]). Taking both from a single reading is also
     * what stops the record and the alarm being stamped from two readings taken
     * moments apart.
     */
    private val readClock: () -> ClockReading,
    private val listener: Listener,
) {

    /** Wall time from the same reading the cap is judged against. */
    private fun nowInstant(): Instant = Instant.ofEpochMilli(readClock().wallMillis)

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
        capExpiresAt: Instant,
        at: ClockReading,
        placeName: String = ActiveSnooze.DEFAULT_PLACE_NAME,
    ): Boolean {
        // The caller's reading, not a fresh one. The deadline and the alarm were
        // both derived from it, and the record's offset has to describe the same
        // frame they do: a wall-clock change landing between two readings pairs
        // a pre-change deadline with a post-change offset, and the cap then
        // reads as hours further off than the alarm is set for — so the alarm
        // fires, finds the snooze "not expired", and is spent for nothing. The
        // window is microseconds wide and the cost of losing it is a snooze with
        // no duration exit, which is the one state this app must never reach.
        val now = Instant.ofEpochMilli(at.wallMillis)
        val snooze = ActiveSnooze(
            anchor = Anchor(capturedAt = now),
            startedAt = now,
            // Stamped from the same reading the record's times come from, so
            // the offset really does describe the frame `capExpiresAt` was
            // written in. Taken from a second reading it would be off by
            // whatever the wall clock did in between — which is precisely the
            // quantity this exists to detect.
            bootReference = at.bootReference,
            // Taken verbatim, and an absolute instant rather than a duration, so
            // that this record and the alarm the caller has already scheduled
            // name the *same* moment. Deriving it here from a second clock
            // reading put the record a few milliseconds later than the alarm —
            // enough that the alarm could fire, find the snooze "not yet
            // expired", and be spent, leaving no duration exit at all. Clamping
            // belongs where the duration is chosen ([ActiveSnooze.capExpiryFor]),
            // not here, precisely so it cannot move the two apart.
            capExpiresAt = capExpiresAt,
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
     * **A release the platform refuses does not clear the snooze — unless there
     * is nothing left to release.** Where the rule still exists and the platform
     * merely refused, the record is the only thing that can turn it off later:
     * it keeps the cap alarm armed, the notification on screen, and the tile
     * showing `Snoozing`, so the next cap check or tap retries. Clearing it
     * would leave the rule active with nothing in the app that knows to try
     * again — a phone silent indefinitely, which is principle 1's failure.
     *
     * But where the failure means the rule is gone
     * ([ZenFailure.nothingLeftToRelease] — policy access revoked being the case
     * that bites), retrying can never succeed and keeping the record is the
     * opposite failure: the app strands itself claiming `Snoozing` over a phone
     * that is already ringing, and SPEC.md §8.2's promise that revocation *ends
     * the snooze* is never kept. So that case completes the end.
     *
     * The failure is reported either way, so neither outcome is silent.
     */
    fun end(reason: EndReason) {
        val ending = active
        val trigger = if (reason == EndReason.MANUAL) ZenTrigger.USER_ACTION else ZenTrigger.CONTEXT

        val outcome = zen.setSnoozed(false, trigger, ending?.placeName ?: ActiveSnooze.DEFAULT_PLACE_NAME)
        if (outcome is ZenOutcome.NotApplied) {
            listener.onZenFailure(outcome.reason, whileArming = false)
            // Keeps `active` and the current state so a retry is still possible.
            // Not a stuck state machine: onCapCheck, the tile, and the
            // notification action all call end() again.
            if (!outcome.reason.nothingLeftToRelease) return
        }

        active = null
        state = SnoozeState.RELEASED
        listener.onStateChanged(state, ending, reason)
        state = SnoozeState.IDLE
    }

    /**
     * Moves the cap out to [newCapExpiresAt] — the notification's `+30 min`
     * (SPEC.md §4.3). Returns the extended snooze, or null when there is nothing
     * running or the new cap is not actually later.
     *
     * Takes an instant rather than a duration, and refuses to move the cap
     * *earlier*, because the caller must re-arm the alarm **before** calling
     * this. The alarm is what actually ends the snooze; a controller that
     * believed in a later cap than the alarm was set for would show a countdown
     * the platform had no intention of honoring, and one that moved the cap out
     * after a failed re-arm would extend the snooze past its only backstop.
     */
    fun extendTo(newCapExpiresAt: Instant): ActiveSnooze? {
        val snooze = active ?: return null
        if (!newCapExpiresAt.isAfter(snooze.capExpiresAt)) return null

        val extended = snooze.copy(capExpiresAt = newCapExpiresAt)
        active = extended
        listener.onStateChanged(state, extended, null)
        return extended
    }

    /**
     * Takes [restated] as the running snooze — the same snooze with its clock
     * frames rewritten onto the clock the user has just set (SPEC.md §7).
     * Returns it, or null when there is nothing running or it describes a
     * different snooze.
     *
     * Called **after** the record is on disk, exactly as [extendTo] is called
     * after the alarm is re-armed, and for the mirror-image reason. Here the
     * record is the durable half: the deadline it carries is what a later boot
     * reads, so believing in a restated frame that never reached disk would put
     * memory and disk into the disagreement this repair exists to remove.
     *
     * Whoever holds the controller must call it. The record and `active` are
     * two copies of the same snooze, and until this runs the second one still
     * carries the pre-change deadline — which the next thing to write from it,
     * `+30 min` being the one that exists today, would put straight back on
     * disk over the repair.
     *
     * Deliberately emits no transition. Nothing about the snooze has changed
     * from the user's point of view — the same moment is still the cap, said in
     * a frame that survives — and the two surfaces that *are* stale after a
     * clock change (the notification's chronometer, which the platform ticks
     * against wall time, and the tile's cached subtitle) are refreshed by the
     * caller that just wrote the record, without a state change that would save
     * it a second time.
     */
    fun reconciledTo(restated: ActiveSnooze): ActiveSnooze? {
        val snooze = active ?: return null
        // Identity, not equality: a restated record differs from the running one
        // by design, and `startedAt` is the field that is fixed for a snooze's
        // whole life (see [ActiveSnooze.retryStillApplies]). A record for some
        // other snooze reaching here would replace the live one's deadline with
        // a stranger's.
        if (restated.startedAt != snooze.startedAt) return null
        active = restated
        return restated
    }

    /**
     * The duration cap — the backstop that holds when every sensor has failed
     * (SPEC.md §7, D6). Called from the alarm and from the in-service timer,
     * which is why it re-checks rather than trusting the caller: an alarm can
     * fire late, early, or twice.
     */
    fun onCapCheck() {
        val snooze = active ?: return
        if (snooze.isExpired(readClock())) end(EndReason.DURATION_CAP)
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
     * Takes over a persisted [snooze] **without touching the zen rule**, for a
     * wake-up whose whole purpose is to end it.
     *
     * [restore] re-asserts the rule, which is right when the process died
     * mid-snooze and wrong when the user has just tapped `End now`: it would
     * turn DND back on for the moment before [end] turns it off, and if that
     * release were then refused, the flap is what leaves the phone quiet after
     * an explicit exit — principle 1's failure produced by the exit meant to
     * prevent it. Ending needs the record, not the rule, so this hands over the
     * first without the second. Nothing is assumed about the platform either
     * way: [end] drives the rule off from here regardless of what state it was
     * actually in.
     *
     * Emits no transition. The snooze is about to end, and announcing `ARMED`
     * on the way would post an ongoing notification for it first.
     */
    fun adopt(snooze: ActiveSnooze) {
        if (active != null) return
        active = snooze
        state = SnoozeState.ARMED
    }

    /**
     * Restores a snooze that outlived the process (SPEC.md §8.1). The cap
     * continues from its original start — a reboot does not extend a snooze
     * (§8.3) — so an already-expired one ends immediately rather than being
     * resurrected.
     */
    fun restore(snooze: ActiveSnooze) {
        active = snooze

        // The clock first, before the rule. A record whose cap passed while the
        // process was dead is already over, and re-asserting it would silence
        // the phone again — briefly in the good case, and until some later retry
        // succeeded in the bad one. Ending is the same call either way, so the
        // only thing the old order bought was a flap.
        if (snooze.isExpired(readClock())) {
            state = SnoozeState.ARMED
            end(EndReason.DURATION_CAP)
            return
        }

        // Re-assert the rule rather than assume it survived (SPEC.md §8.1). The
        // record surviving does not mean the rule's condition did — a reboot, an
        // app update, or the platform dropping it would otherwise leave the app
        // showing a snooze over a phone that rings.
        val outcome = zen.setSnoozed(true, ZenTrigger.CONTEXT, snooze.placeName)
        if (outcome is ZenOutcome.NotApplied) {
            listener.onZenFailure(outcome.reason, whileArming = true)

            if (!outcome.reason.nothingLeftToRelease) {
                // The same distinction [end] draws, and for the same reason. A
                // platform refusal is not evidence the rule is off — the rule
                // still exists and may still be holding the phone quiet, since
                // this call was only *re-asserting* what was already running.
                // Treating it as "ended" would erase the record and cancel the
                // cap, which are the only two things that could ever turn it
                // back off. So keep them, stay armed, and let the cap retry.
                state = SnoozeState.ARMED
                listener.onStateChanged(state, snooze, null)
                onCapCheck()
                return
            }

            // Nothing is silencing the phone — no access, no rule, or the rule
            // switched off — so the snooze really is over. Ends without calling
            // the zen controller again: a second call would fail the same way.
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
        DegradationCause.FIXES_TOO_VAGUE,
        DegradationCause.LOCATION_SERVICES_OFF,
        DegradationCause.NO_LOCATION_IN_BACKGROUND,
        -> if (anchor.ssid != null) TrackingMode.WIFI_ONLY else TrackingMode.DURATION_ONLY
    }
}
