package app.snoozemo.presence

import app.snoozemo.core.Anchor
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.Presence
import app.snoozemo.core.PresenceSignal
import app.snoozemo.core.PresenceState
import app.snoozemo.core.PresenceUpdate

/**
 * The one stateful step every monitor shares: feed a signal to the engine,
 * carry the state, and shape the result into the [PresenceUpdate] the
 * controller consumes.
 *
 * Deliberately tiny and deliberately in the shared source set — the geofence
 * monitor and Phase 7's foreground monitor differ in which sensors they
 * register, never in what a signal means, and a second copy of this loop is
 * where the two flavors would start to drift. Pure over an injected clock
 * seed, so a JVM test can drive it without Android.
 *
 * Not thread-safe on its own: a monitor calls [accept] from one confined
 * context (the flow's callback scope), which is also what keeps the engine's
 * state a plain value.
 */
internal class PresenceFeed(
    private val anchor: Anchor,
    seedElapsedRealtimeMs: Long,
    /**
     * A grace deadline restored from disk (TODO.md, "the grace deadline has
     * to survive process death"), or null for a fresh arm.
     *
     * Without this, a monitor restarted after the process died — the common
     * case with no foreground service — always constructs a feed with
     * [PresenceState.graceDeadlineMs] null, indistinguishable from "no grace
     * period is running." That is wrong two ways: a `GraceElapsed` the
     * mailbox held while the process was dead is read as a stale alarm and
     * dropped instead of ending the snooze, and a live signal that would
     * otherwise recompute a *fresh* five-minute window
     * (`Presence.graceFrom`'s `state.graceDeadlineMs ?: graceFrom(...)`)
     * silently discards the original deadline instead of resuming it. Seeding
     * this before anything else runs is what lets both cases fall through to
     * already-tested engine behavior with no new branching.
     */
    seedGraceDeadlineMs: Long? = null,
    /**
     * Whether the restored deadline has already spent its confirmation
     * deferral (SPEC.md §6.6; Codex, PR #106). Restored alongside the
     * deadline so the "defer at most once" bound survives a process death
     * inside the confirmation window — without it the seed re-grants a
     * deferral on every restart and the deadline extends indefinitely.
     * Meaningless without a deadline, so it is ignored when
     * [seedGraceDeadlineMs] is null.
     */
    seedConfirmationDeferralUsed: Boolean = false,
    /**
     * The engine-inferred degradation this snooze already carries, restored
     * from its record (Codex, PR #141). The monitor's own state dies with the
     * process, and on `play` that happens on every wake — so without this a
     * restart begins at "healthy", and its first update carries a synthesized
     * null that the controller reads as a recovery nothing actually observed.
     * The card then drops from `Timer only — no location` to a plain
     * `Snoozing` moments after being reposted, and because [Presence] infers
     * that level by *counting* misses, re-deriving the truth costs a whole
     * fresh run of failures rather than one probe.
     *
     * Only the engine's own inferences belong here. A platform-layer cause
     * (services off, the fence unavailable) is refuted by different evidence
     * and lives in the monitor's own slots — seeding it here would put it
     * behind the wrong refutation (PR #75's two-slot rule).
     *
     * Ignored without [seedDegradationAtMs]: a level with no floor is worse
     * than no level, so the pair is all-or-nothing rather than silently
     * half-applied.
     */
    seedDegradation: app.snoozemo.core.DegradationCause? = null,
    /**
     * **When this restart happened**, in the same elapsed-realtime frame as
     * the signals — the staleness floor a seeded [seedDegradation] gets, and
     * required for one to take effect at all (Codex, PR #142).
     *
     * `Presence.staleFix` clears a degradation on the first reading newer
     * than `lastUnusableAtMs`, which a rebuilt feed leaves null — so without
     * a floor any cached reading clears a restored level trivially. The arm
     * moment is **not** that floor, and the difference is the ordinary case:
     * a snooze that arms healthy, banks a good fix ten minutes in, and only
     * then starts failing leaves a cached reading that post-dates the arm and
     * pre-dates the trouble. Believing it restores `FULL` on evidence older
     * than the problem — PR #33's case, which is precisely what seeding the
     * level was meant to stop.
     *
     * The restart moment is the conservative floor and needs nothing
     * persisted: this process cannot vouch for anything it did not see
     * arrive, so only a reading it actually received clears the level. It
     * errs toward keeping a degradation, which is the safe direction
     * (SPEC.md §6.1 — overstating health is the dangerous one).
     */
    seedDegradationAtMs: Long? = null,
) {

    /**
     * Seeded with the arm moment per `PresenceState.latestEvidenceMs`'s
     * contract: a cached last-known location from before the tile was tapped
     * must not be the first thing the engine believes.
     *
     * The suppressor is seeded from what arming saw, per `Presence.advance`'s
     * contract: an anchor carrying an SSID was captured *while associated*,
     * and a snooze armed on the anchor's Wi-Fi should not spend a location
     * fix discovering that. A restore where the association has since ended
     * is corrected within moments — the Wi-Fi watch's first report is a
     * transition by definition.
     */
    private var state = PresenceState(
        // A restored grace deadline can only exist because Wi-Fi was lost —
        // `Presence.wifiLost` is the only place that ever sets it — so
        // seeding it alongside the anchor's own default "associated" belief
        // built an internally impossible state (Codex, PR #91, fifth pass).
        // Worse than merely wrong: `Presence.associated`'s duplicate guard
        // (`if (state.atAnchorWifi) return step(state, null, anchor)`)
        // means a *genuine* later report that Wi-Fi is back would have been
        // read as a repeat of what the seed already claimed and silently
        // dropped — the deadline would then never clear even though the
        // user plainly returned, ending the snooze five minutes after a
        // presence the engine had already been told about and ignored.
        atAnchorWifi = seedGraceDeadlineMs == null && anchor.ssid != null,
        latestEvidenceMs = seedElapsedRealtimeMs,
        graceDeadlineMs = seedGraceDeadlineMs,
        // Only meaningful with a deadline to bound; a fresh arm has neither.
        confirmationDeferralUsed = seedGraceDeadlineMs != null && seedConfirmationDeferralUsed,
        // Both or neither, so a level can never stand without the floor that
        // makes it survivable (see [seedDegradationAtMs]).
        degradation = seedDegradation.takeIf { seedDegradationAtMs != null },
        lastUnusableAtMs = seedDegradationAtMs.takeIf { seedDegradation != null },
        // The run of failures continues; only the process ended (Codex, PR
        // #142). `Presence.useless` updates an already-standing cause only
        // once the count reaches the threshold, so a restored level with a
        // counter starting at zero would keep asserting the *old* flavor —
        // and on `play`, where a wake takes one resting probe and the process
        // is then reclaimed, the count can reset before it ever gets there.
        // The card would say `weak location signal` for the rest of a snooze
        // during which nothing arrived at all. Restoring the level means the
        // threshold was already crossed, so it is seeded as crossed and the
        // next failure names the flavor that actually just failed.
        uselessObservations = if (seedDegradation != null && seedDegradationAtMs != null) {
            Presence.DEGRADED_AFTER_USELESS_OBSERVATIONS
        } else {
            0
        },
    )

    /** What the engine currently wants from location (SPEC.md §6.7). */
    val duty: LocationDuty
        get() = Presence.duty(state, anchor)

    /**
     * The engine's own degradation level, for a restate that arrives outside
     * any signal — a repair clearing the *platform* level must not speak for
     * the feed's, or a synthesized `null` promotes a snooze whose fixes are
     * still failing (Codex, PR #75). Only a usable fix clears this one.
     */
    val degradation: app.snoozemo.core.DegradationCause?
        get() = state.degradation

    /**
     * When the §6.6 grace period runs out, or null while none runs. The
     * monitor arms a real alarm for this and feeds
     * [PresenceSignal.GraceElapsed] back — the engine cannot wake a sleeping
     * phone, so an unarmed deadline is a Wi-Fi-only snooze that never ends.
     */
    val graceDeadlineMs: Long?
        get() = state.graceDeadlineMs

    /**
     * Whether the current grace deadline has already spent its one
     * confirmation deferral (SPEC.md §6.6). The monitor persists this beside
     * the deadline so the bound survives process death (Codex, PR #106).
     */
    val confirmationDeferralUsed: Boolean
        get() = state.confirmationDeferralUsed

    /**
     * Whether the engine is suppressing §6.6 grace because location data is
     * being withheld. The monitor mirrors this onto every [PresenceUpdate] so
     * the controller can classify the tracking mode from what is actually
     * shut rather than from the cause recorded beside it — the two come apart
     * in both directions (Codex, PR #165).
     */
    val locationAccessLost: Boolean
        get() = state.locationAccessLost

    /**
     * Feeds one signal through and returns what to report: the event (usually
     * null) and the degradation level restated, exactly the two halves
     * [PresenceUpdate] documents.
     */
    fun accept(signal: PresenceSignal): PresenceUpdate {
        val step = Presence.advance(state, signal, anchor)
        state = step.state
        return PresenceUpdate(
            event = step.event,
            degradation = step.state.degradation,
            graceActive = step.state.graceDeadlineMs != null,
        )
    }
}
