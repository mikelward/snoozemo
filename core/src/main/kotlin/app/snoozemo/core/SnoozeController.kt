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
         * A release is about to be attempted, and this is why (Codex, PR #36).
         *
         * Called **before** the zen write, because the point is to survive a
         * process death between that write and the record being cleared: the
         * next wake-up would otherwise find a live record over an already-off
         * rule and, having no other explanation, blame the user for an ending
         * the app itself decided on — losing the reason §4.5 promises.
         *
         * Deliberately *not* the same thing as marking the record released.
         * That marker makes [ActiveSnoozeStore.load] refuse the record, and
         * writing it before a zen write that can still fail would strand a live
         * snooze with no record and Do Not Disturb stuck on — nothing left to
         * turn the phone back on, which is principle 1's failure. This one only
         * records attribution, so a stale marker is harmless.
         *
         * Best-effort by contract: an implementation must never let a failure
         * here stop the release.
         */
        fun onReleasing(reason: EndReason)

        /**
         * How well tracking is working has changed, with no transition to carry
         * the news — [snooze] holds the new mode and [degradation] the reason,
         * or null now that there isn't one.
         *
         * One callback for both directions, because there is one fact: the
         * caller does the same three things either way (record, notification,
         * tile), and a pair of opposite callbacks was an invitation to handle
         * the recovery half less carefully than the degradation half — which is
         * exactly what happened (SPEC.md §8.1).
         */
        fun onTrackingChanged(snooze: ActiveSnooze, degradation: DegradationCause?)

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
     * The modes the running presence machinery can honestly claim —
     * [PresenceMonitor.supportedModes] for this snooze's anchor, handed in at
     * arm and restore. Every mode this class computes passes through
     * [honest], because every derivation overstates otherwise: at arm,
     * [TrackingMode.from] would claim Wi-Fi tracking for an SSID nothing
     * watches; on an update, a null degradation reads as the anchor's full
     * capability; and a degradation *fallback* claims `WIFI_ONLY` whether or
     * not anything watches Wi-Fi — a set, not a ceiling, is what can answer
     * that last one (flagged by Codex on PR #73).
     */
    private var supportedModes: Set<TrackingMode> = setOf(TrackingMode.DURATION_ONLY)

    /**
     * [mode], or the nearest less capable mode the machinery actually runs.
     * [TrackingMode.DURATION_ONLY] is always honest: the cap needs no sensor.
     *
     * [TrackingMode.WIFI_GRACE] is never itself a rung — no monitor's
     * [PresenceMonitor.supportedModes] ever names it, and it must not be
     * walked past on that account. It stands or falls with [TrackingMode.WIFI_ONLY]:
     * it is the same Wi-Fi watch reporting a worse answer, not a different
     * capability, so treating it as unsupported here would degrade it all
     * the way to [TrackingMode.DURATION_ONLY] — exactly the overstated-honesty
     * bug this method exists to prevent, just relocated to a mode that names
     * the failure this file was written to stop hiding.
     */
    private fun honest(mode: TrackingMode): TrackingMode {
        var candidate = mode
        while (candidate != TrackingMode.DURATION_ONLY && !isSupported(candidate)) {
            candidate = TrackingMode.entries[candidate.ordinal + 1]
        }
        return candidate
    }

    private fun isSupported(mode: TrackingMode): Boolean =
        mode in supportedModes ||
            (mode == TrackingMode.WIFI_GRACE && TrackingMode.WIFI_ONLY in supportedModes)

    /**
     * Starts arming: turns the zen rule on **now**, before any anchor exists.
     *
     * This split is the whole point of the `ARMING` state (SPEC.md §4.1). Anchor
     * capture takes up to 10 s, and the phone must be quiet from the tap, not
     * from the fix — a controller that required a finished anchor would either
     * leave DND off for those 10 s or arm with an anchor it could never replace.
     * So the rule goes on first and the anchor lands afterward, via
     * [onAnchorCaptured].
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
        // Nothing is watching yet, so nothing may claim more until the anchor
        // lands and brings the machinery's real answer with it.
        supportedModes = setOf(TrackingMode.DURATION_ONLY)
        active = snooze
        state = SnoozeState.ARMING
        listener.onStateChanged(state, snooze, null)

        return when (val outcome = zen.setSnoozed(true, ZenTrigger.USER_ACTION, placeName, snooze.identity)) {
            is ZenOutcome.Applied -> {
                // The moment the rule is on, not when the anchor arrives
                // (Codex, PR #36). Anchor capture can be up to the 10 s ceiling
                // away, and for that whole window the record would claim the arm
                // never completed — so a process death in it, followed by the
                // user turning Do Not Disturb off, would have the next wake-up
                // "finish" an arm that was already finished and silence the
                // phone again. No write of its own: the caller saves the record
                // as soon as the rule is on, and now carries this with it.
                // The rule that went on is recorded with it, so the status
                // broadcast and the read-back are answered against *this*
                // snooze's rule and not whatever the app holds later (SPEC.md
                // §5.8). Taken from the outcome, not read back: a second read
                // could name a replacement minted in between (Codex, PR #195).
                active = snooze.copy(armed = true, ruleId = outcome.ruleId)
                true
            }
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
     * The anchor arrived — complete, partial, or empty, per whatever capture
     * managed within its ceiling (SPEC.md §4.1). Arms in the most capable
     * mode both the anchor's fields and the caller's machinery can honestly
     * back, and reports the degradation where that is short of full — because
     * arming must never feel slow or refuse, and a degraded snooze must never
     * look healthy.
     *
     * [supported] is stated by the caller rather than derived here, because
     * the anchor alone cannot answer it: [TrackingMode.from] is what the
     * captured *fields* allow, and only the machinery knows which of them
     * anything is actually watching ([PresenceMonitor.supportedModes]). Every
     * mode this class ever computes — here, and on every later update — is
     * lowered through the same set, so neither an arm nor a degradation
     * fallback can claim a watch that does not exist.
     *
     * Ignored if nothing is arming — a late fix for a snooze that has already
     * ended must not resurrect it.
     */
    fun onAnchorCaptured(
        anchor: Anchor,
        supported: Set<TrackingMode> = TrackingMode.entries.toSet(),
    ) {
        val snooze = active ?: return
        supportedModes = supported
        val fieldsAllow = TrackingMode.from(anchor)
        val armed = snooze.copy(anchor = anchor, mode = honest(fieldsAllow))
        active = armed
        state = SnoozeState.ARMED
        listener.onStateChanged(state, armed, null)
        // Armed, but say so if it armed degraded: a snooze that is really only a
        // timer must not look like a tracked one (SPEC.md §4.1, §8.1). The
        // cause names which limit bit — the anchor's missing fix, or the
        // machinery watching less than the fields allow — because the debug log
        // records it, and a reason that misstates which is which is exactly
        // what it exists to rule out (SPEC.md §4.6; flagged by Codex on
        // PR #71 when this said NO_LOCATION_FIX over a fix just captured).
        //
        // **Deliberately not onto the record** (Codex, PR #141, which asked for
        // exactly that). These two causes are structural facts the mode already
        // expresses — the anchor never had a fix, or nothing is watching — not
        // runtime failures the notification needs to explain. Recording them
        // would also flap: the engine's first update carries its own
        // degradation, `null` on a healthy watch, which does not refute an
        // anchor that still has no coordinates but *would* clear the stored
        // cause and drop the reason off a card seconds after arming. The
        // notification's reason is for the engine's runtime causes; here the
        // mode is the whole story, and the log is what wants the distinction.
        if (armed.mode != TrackingMode.FULL) {
            val cause = if (armed.mode != fieldsAllow) {
                DegradationCause.NOTHING_WATCHING
            } else {
                DegradationCause.NO_LOCATION_FIX
            }
            listener.onTrackingChanged(armed, cause)
        }
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
        // Before the zen write, so the reason outlives a crash in the window
        // between turning the rule off and clearing the record.
        //
        // **Only for the endings the phone decided on**, which is not a
        // compromise for speed — it is that the marker buys nothing otherwise
        // (Codex, PR #36). If a `MANUAL` ending is lost to a crash, the
        // fallback inference is `DND_TURNED_OFF`, which is *also* silent and
        // *also* attributed to the user: same notification, same source, same
        // everything the user can see. So the one path where somebody is
        // waiting — a tile tap, phone in hand, asking for sound back — does no
        // disk work at all.
        // Exhaustive on purpose (Codex, PR #36). §5.4's source argument is what
        // the Modes UI shows the user to tell "I did this" from "my phone did
        // this", so every reason has to state which it was — and a default
        // branch would keep answering for reasons added later, which is exactly
        // how `DND_TURNED_OFF` was reported as automation on its first outing.
        val trigger = reason.zenTrigger()

        if (ending != null && trigger == ZenTrigger.CONTEXT) listener.onReleasing(reason)

        val outcome = zen.setSnoozed(false, trigger, ending?.placeName ?: ActiveSnooze.DEFAULT_PLACE_NAME, ending?.identity)
        if (outcome is ZenOutcome.NotApplied) {
            // Reports the failure *and* retires the marker above: the release
            // did not happen, so the reason it recorded is now a lie waiting
            // for a crash to be believed (Codex, PR #36). A snooze that later
            // ends because the user switched Do Not Disturb off would otherwise
            // be explained by a departure that never completed.
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
     * Brings the cap in to [newCapExpiresAt] — a time chosen in the
     * end-condition sheet (SPEC.md §4.4). Returns the shortened snooze, or null
     * when there is nothing running or the new cap is not actually earlier.
     *
     * The mirror of [extendTo] and refuses the opposite direction, but the
     * ordering rule it depends on is the same one and not the mirror of it: the
     * caller re-arms the alarm **before** calling this, both ways round.
     * Extending needs it because an alarm still set for the old time would end
     * a snooze the countdown had promised more of; shortening needs it because
     * an alarm left at the *later* time is a phone that stays quiet past the
     * moment the user just picked, which is principle 1's failure rather than a
     * cosmetic disagreement.
     *
     * §4.4 is explicit that this is not a fourth exit. It moves the one
     * deadline the cap alarm already watches, so departure tracking is
     * untouched and whichever comes first still wins (§7) — there is nothing
     * here to tell the presence engine about.
     *
     * Clamping is the caller's: this refuses a value it cannot honor rather
     * than quietly substituting a different one, so a sheet that computed its
     * offer against a stale reading is a tap that reports failure instead of a
     * snooze silently ending at a time nobody chose.
     */
    fun lowerCapTo(newCapExpiresAt: Instant): ActiveSnooze? {
        val snooze = active ?: return null
        if (!newCapExpiresAt.isBefore(snooze.capExpiresAt)) return null

        val shortened = snooze.copy(capExpiresAt = newCapExpiresAt)
        active = shortened
        listener.onStateChanged(state, shortened, null)
        return shortened
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

    /**
     * One report from the presence engine: how well tracking is working, and
     * what (if anything) just happened (SPEC.md §6.1).
     *
     * The tracking level is applied **first, and silently**, so that when the
     * event also produces a transition the notification is posted once, already
     * correct, rather than as a stale line followed by a correction.
     */
    fun onPresenceUpdate(update: PresenceUpdate) {
        val snooze = active ?: return

        val mode = modeFor(
            update.degradation,
            update.graceActive,
            update.locationAccessLost,
            snooze.anchor,
        )
        // The *cause* moving counts as news too, not only the mode (TODO.md;
        // Codex, PR #31). `NO_LOCATION_FIX` and `FIXES_TOO_VAGUE` map to one
        // mode, so a mode-only test would let the reason change underneath a
        // notification that never reposts — which is the whole failure this
        // plumbing exists to end, just moved one layer down from where it
        // started.
        val moved = mode != snooze.mode || update.degradation != snooze.degradation
        if (moved) active = snooze.copy(mode = mode, degradation = update.degradation)

        val before = state
        update.event?.let { report(it) }

        // Only when nothing else has already said it. A transition carries the
        // record — and therefore the mode — to the same three places this would
        // (SPEC.md §8.1), and `end` has made the question moot.
        val current = active
        if (moved && current != null && state == before) {
            listener.onTrackingChanged(current, update.degradation)
        }
    }

    private fun report(event: PresenceEvent) {
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
     *
     * [supported] is [PresenceMonitor.supportedModes] for this snooze's
     * anchor, from whoever is restarting the machinery; it defaults to
     * everything for callers with nothing better to say.
     */
    fun restore(
        snooze: ActiveSnooze,
        supported: Set<TrackingMode> = TrackingMode.entries.toSet(),
    ) {
        supportedModes = supported
        // The record's own claim is lowered too: it was written under some
        // machinery, but not provably this one — an app update can change
        // what is watched between the write and this read.
        val restored = snooze.copy(mode = honest(snooze.mode))
        active = restored

        // The clock first, before the rule. A record whose cap passed while the
        // process was dead is already over, and re-asserting it would silence
        // the phone again — briefly in the good case, and until some later retry
        // succeeded in the bad one. Ending is the same call either way, so the
        // only thing the old order bought was a flap.
        if (restored.isExpired(readClock())) {
            state = SnoozeState.ARMED
            end(EndReason.DURATION_CAP)
            return
        }

        // Re-assert the rule rather than assume it survived (SPEC.md §8.1). The
        // record surviving does not mean the rule's condition did — a reboot, an
        // app update, or the platform dropping it would otherwise leave the app
        // showing a snooze over a phone that rings.
        val outcome = zen.setSnoozed(true, ZenTrigger.CONTEXT, restored.placeName, restored.identity)
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
                listener.onStateChanged(state, restored, null)
                onCapCheck()
                return
            }

            // Nothing is silencing the phone — no access, no rule, or the rule
            // switched off — so the snooze really is over. Ends without calling
            // the zen controller again: a second call would fail the same way.
            active = null
            state = SnoozeState.RELEASED
            listener.onStateChanged(state, restored, EndReason.LOST_CAPABILITY)
            state = SnoozeState.IDLE
            return
        }

        // The rule is confirmed on, so the record says so from here (Codex, PR
        // #36). It matters for a record whose *arm* never got this far: the
        // §5.8 read treats a record over an off rule as an unfinished arm and
        // re-asserts, which is right once — but left unmarked, a later process
        // death and a user switching Do Not Disturb off would land in that same
        // branch and silently re-silence a phone they had just un-silenced.
        // Only on the success path: the refusal above deliberately stays armed
        // without the rule being confirmed, and must not claim otherwise.
        // The enforcing rule is whichever one this re-assertion went to — as
        // the write reports it, the same way the arm takes it — so a record
        // written before it named its rule picks the name up here.
        // Not a smart cast: `NotApplied` returned above, and the remaining
        // case of a sealed type is not inferred.
        val applied = outcome as ZenOutcome.Applied
        val confirmed = restored.copy(armed = true, ruleId = applied.ruleId)
        active = confirmed
        state = SnoozeState.ARMED
        listener.onStateChanged(state, confirmed, null)
        onCapCheck()
    }

    /**
     * The tracking mode a given level of health adds up to.
     *
     * Three inputs and no memory, which is the whole point of reporting health
     * as a level: there is nothing to restore, nothing owed, and no ordering to
     * get right — the mode is a function of what the anchor could ever support,
     * whether location is currently answering, and whether the §6.6 grace
     * period is running.
     *
     * The rule that used to need its own ceiling falls out of that. Rejoining
     * the anchor's network does not clear the engine's degradation, because
     * every cause is a *location* cause and Wi-Fi says nothing about location —
     * so the mode stays `WIFI_ONLY` without anyone having to remember not to
     * claim `FULL`.
     *
     * [graceActive] is checked ahead of most of [degradation] (Codex, PR #31,
     * flagged as the *mode's* half of the same bug the missing signal was
     * the cause's half of): grace can start the instant Wi-Fi is lost, for an
     * anchor with no usable fix to confirm anything with, which is before
     * enough failed observations have accumulated for [degradation] to have
     * moved off null. Waiting for [degradation] there would report `WIFI_ONLY`
     * — Wi-Fi is what's tracking this — for the first moments of a grace
     * period that exists precisely because nothing is.
     *
     * A missing grant is the exception and sits above it, because it is the
     * one cause that invalidates the Wi-Fi signal itself rather than merely
     * failing alongside it — see the body.
     */
    private fun modeFor(
        degradation: DegradationCause?,
        graceActive: Boolean,
        locationAccessLost: Boolean,
        anchor: Anchor,
    ): TrackingMode {
        val computed = when {
            // A missing *grant* outranks even a running grace period, and is
            // the one cause that does (Codex, PR #149, deferred there; fixed
            // now that the engine clears the deadline to match). Under a dead
            // grant the SSID reads as absent because the *permission* is,
            // which is plausibly what started the grace period in the first
            // place — so `WIFI_GRACE` would say Wi-Fi is bounding a departure
            // that may never have happened.
            //
            // Ordering this above `graceActive` was unsafe until
            // `PresenceSignal.LocationAccessLost` existed: the card would
            // have read `Timer only`, promising the cap, while the grace
            // alarm still ended the snooze minutes later. Both halves land
            // together — the monitor delivers that signal on the same
            // classification, so by the time this line is reached the
            // deadline is cleared, persisted and the alarm canceled.
            //
            // Nor is there any falling back to Wi-Fi below (maintainer,
            // 2026-08-30; Codex raised the same point on PR #146). Reading an
            // SSID needs `ACCESS_FINE_LOCATION` and location services on —
            // there is no separate Wi-Fi permission — so `WIFI_ONLY` here
            // would not merely overstate the mode: it would report a
            // departure on every wake with the phone sitting on its own
            // network.
            // **Asked of the suppressor, not of the cause** (Codex, PR
            // #165). This branch first read `isGrantLoss`, which missed the
            // system location switch: it withholds the SSID exactly as a dead
            // grant does, and the monitor now says so the moment a read comes
            // back redacted, so the card claimed `Wi-Fi only` while the engine
            // had already shut every grace path — a snooze running to the cap
            // under a line saying something else was tracking it.
            //
            // Widening it to `blocksLocationReads` fixed that and bought the
            // mirror-image error, because a cause can reach the level without
            // the suppressor: a stale `GEOFENCE_NOT_AVAILABLE` observation
            // delivered after the switch is back on records
            // `LOCATION_SERVICES_OFF` while Wi-Fi is working and a grace
            // period can still arm and end the snooze. Reading the cause
            // promised the cap there instead.
            //
            // Both readings were proxies for one fact the engine already
            // knows, so this asks for it directly. It stays above
            // `graceActive` for the original reason: under a withheld SSID
            // the absence that started a grace period may be the withholding
            // itself, so `WIFI_GRACE` would say Wi-Fi is bounding a departure
            // that may never have happened — and by the time this line is
            // reached that deadline is cleared, persisted and its alarm
            // canceled.
            locationAccessLost -> TrackingMode.DURATION_ONLY
            graceActive && anchor.ssid != null -> TrackingMode.WIFI_GRACE
            degradation == null -> TrackingMode.from(anchor)
            // Every other degradation leaves Wi-Fi *only if there was an SSID*;
            // claiming `WIFI_ONLY` for an anchor with no network would tell the
            // user tracking is better than it is.
            anchor.ssid != null -> TrackingMode.WIFI_ONLY
            else -> TrackingMode.DURATION_ONLY
        }
        // Lowered to what the machinery actually runs (see [supportedModes]):
        // the anchor's fields set the best case, and a computed mode — the
        // healthy claim and the degradation fallback alike — is honest only
        // if something watches it.
        return honest(computed)
    }
}
