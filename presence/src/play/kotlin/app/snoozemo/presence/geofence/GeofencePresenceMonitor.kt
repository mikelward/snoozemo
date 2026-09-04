package app.snoozemo.presence.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import app.snoozemo.core.Anchor
import app.snoozemo.core.CapabilityLossCause
import app.snoozemo.core.ClockReading
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.PresenceEvent
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.core.PresenceSignal
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.TrackingMode
import app.snoozemo.presence.LocationModeWatch
import app.snoozemo.presence.MotionTrigger
import app.snoozemo.presence.PlatformLocationModeWatch
import app.snoozemo.presence.PlatformMotionTrigger
import app.snoozemo.presence.PlatformWifiWatch
import app.snoozemo.presence.PresenceFeed
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The `play` flavor's [PresenceMonitor] (SPEC.md §3.4, option B): the
 * Geofencing API watches for the departure, and no foreground service exists.
 *
 * Built in slices. Landed here: registration, the exit callback, the
 * recoverable/fatal split at the platform boundary, the confirming fixes —
 * [CheckingFixes] one-shots started and stopped by the duty the engine
 * reports, so an exit escalates and the §6.6 test can actually settle it —
 * and the restart mailbox: an exit that lands in a dead process waits in
 * [GeofenceSignalBridge] while the woken service restores, and this
 * monitor's own attach collects it. Still their own `TODO.md` items behind
 * the same interface: the Wi-Fi suppressor callback, significant motion,
 * the grace alarm, the periodic backstop, and `PresenceState` persistence.
 *
 * Everything the platform can refuse is classified through
 * [GeofenceRegistrationFailure], because the one distinction this class must
 * never get wrong is degradation versus capability loss (SPEC.md §6.1): the
 * first keeps the snooze armed in a lesser mode, the second ends it, and the
 * cost of confusing them in the wrong direction is a phone that never comes
 * back.
 *
 * Real geofence delivery — latency, batching, behavior under Doze — is
 * hardware-verified, not emulator-verified (`TODO.md`, hardware item 2); the
 * JVM tests cover the decisions, not the platform.
 */
class GeofencePresenceMonitor(
    context: Context,
    private val readElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : PresenceMonitor {

    private val appContext = context.applicationContext

    /**
     * Whether the fine-location grant is still held, read live at the moment a
     * refusal is classified.
     *
     * Both permission-shaped refusals arrive as the same answer from the
     * platform — "insufficient location permission" — and only the live grant
     * separates *background missing* from *location gone*, which are different
     * things for the user to fix. Read at classification time rather than
     * cached from arm, because a mid-snooze revocation is precisely the case
     * this has to name (SPEC.md §8.2).
     */
    private fun hasFineLocation(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Both grants geofencing needs, read live. `minSdk` is 35, so
     * `ACCESS_BACKGROUND_LOCATION` is always a real, separately-grantable
     * permission here — there is no pre-29 case where holding fine implies
     * it.
     */
    private fun hasGeofencingGrants(): Boolean =
        hasFineLocation() &&
            appContext.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Closes the running flow; null while none runs. Atomic for the same
     * reason the bridge's slot is: a replacement's start races the old flow's
     * teardown, and a check-then-null could evict the replacement's handle —
     * leaving [stop] unable to end the new flow (flagged by Codex on PR #70).
     */
    private val active = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(null)

    /**
     * Bumped by every [stop]. A flow captures the value at [start] and refuses
     * to publish or set up if it has moved: a cold producer — dispatched but
     * not yet running when the stop landed — has no handle to close, so the
     * generation is what says its snooze already ended (flagged by Codex on
     * PR #70).
     */
    private val stopGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * The grace deadline's durable side effects — the store, the alarm — kept
     * on the instance, not inside one `start()`'s `callbackFlow` (Codex, PR
     * #91, eighth pass). A `deliver` call's own producer-local sequence
     * ordered writes *within* one generation, but a rapid end-and-rearm can
     * leave an old generation's `deliver` still in flight when a new one
     * starts; the two shared no lock, so a stale write landing after a fresh
     * one clobbers the current snooze's identity in the single
     * [GraceDeadlineStore] file every generation writes to, and can rearm or
     * cancel the wrong platform alarm. [persistenceGeneration] is the gate:
     * only a call whose own captured `startGeneration` still matches may
     * write, checked and claimed under the same [persistenceLock] so a claim
     * and a stale write can never interleave.
     */
    private val persistenceLock = Any()
    private var persistenceGeneration = -1L
    private var persistedSequence = -1L
    private var lastPersistedGraceDeadlineMs: Long? = null

    /**
     * Latched, not just checked per call (Codex, PR #91, eighth pass):
     * `send` only queues a `Departed` update, so a signal arriving before the
     * collector consumes it and calls [stop] would see the engine's own
     * already-resolved, grace-cleared state and clear the durable deadline
     * itself — stranding the bridge's retained `GraceElapsed` exactly as the
     * second pass's fix was meant to prevent, just reachable from a *later*
     * call instead of the one that actually produced `Departed`. Reset with
     * [persistenceGeneration] on every claim, so a fresh generation starts
     * unlatched.
     */
    private var departedObserved = false

    /** Both clocks, read together — [GraceDeadlineStore] converts between them. */
    private fun clockNow() = ClockReading(
        wallMillis = System.currentTimeMillis(),
        uptimeMillis = readElapsedRealtimeMs(),
    )

    override fun start(
        anchor: Anchor,
        sinceElapsedRealtimeMs: Long,
        armedAtEpochMs: Long,
        restoredDegradation: DegradationCause?,
    ): Flow<PresenceUpdate> {
        // Captured synchronously at the call, not inside the producer: the
        // whole point is telling a stop that happened after start() from one
        // that happened before the producer got to run.
        val startGeneration = stopGeneration.get()
        return callbackFlow {
        // Published before any setup, so a stop() that lands mid-registration
        // finds a handle to close rather than a null to shrug at (flagged by
        // Codex on PR #70). Closing the channel early is safe against
        // everything below: sends onto a closed channel report failure rather
        // than throw, and awaitClose still runs the release when the body
        // reaches it, so resources acquired after the close are still freed.
        //
        // Under the lifecycle lock, and only while no stop has intervened
        // since start(): a producer running after its stop must neither
        // displace a fresh flow's handle nor go on to set up — closing itself
        // here is what makes every guarded section below skip.
        val producer = this
        val handle = AutoCloseable { producer.close() }
        synchronized(registrationLock) {
            if (stopGeneration.get() == startGeneration) {
                active.set(handle)
            } else {
                producer.close()
            }
        }

        // A persisted grace deadline, restated into this process's — and,
        // after a reboot, this boot's — elapsed-realtime frame (TODO.md,
        // "the grace deadline has to survive process death"). Read before
        // the feed exists so it can seed the state the bridge's mailbox
        // replay and the Wi-Fi watch's restore-time redelivery both land
        // against; either one landing on a fresh, un-seeded state is exactly
        // what silently discarded the original deadline before.
        val restoredGrace = GraceDeadlineStore.load(appContext, armedAtEpochMs, clockNow())
        val restoredGraceDeadlineMs = restoredGrace?.deadlineElapsedMs

        // Claims persistence ownership for this generation before anything
        // can be delivered through the fresh flow (Codex, PR #91, eighth
        // pass) — see `persistenceGeneration`'s own comment. Ordered against
        // any still-in-flight old generation's write by sharing its lock:
        // either this claim lands first, and the stale write's own generation
        // check then rejects it, or the stale write lands first as the last
        // word from a generation that had nothing newer yet, and this claim
        // then correctly starts the new generation unlatched and seeded from
        // what was actually just read above.
        synchronized(persistenceLock) {
            persistenceGeneration = startGeneration
            persistedSequence = -1L
            lastPersistedGraceDeadlineMs = restoredGraceDeadlineMs
            departedObserved = false
        }

        // The caller's arm-moment seed, never this monitor's own "now": a
        // restored monitor's now post-dates the held exit the restart was
        // woken to deliver, and seeding with it dropped that exit as stale —
        // the confirmation never ran and the phone stayed quiet until the
        // cap (flagged by Codex on PR #73).
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = sinceElapsedRealtimeMs,
            seedGraceDeadlineMs = restoredGraceDeadlineMs,
            // Only the engine's own inferences (Codex, PR #141). A restored
            // platform cause goes to the platform slot below instead, because
            // the two are refuted by different evidence and PR #75 split them
            // for exactly that reason.
            seedDegradation = restoredDegradation.takeIf {
                it == DegradationCause.NO_LOCATION_FIX || it == DegradationCause.FIXES_TOO_VAGUE
            },
            // This restart, not the arm: a cached fix banked after arming but
            // before the failures began post-dates the arm moment and would
            // clear the restored level on evidence older than the problem
            // (Codex, PR #142).
            seedDegradationAtMs = readElapsedRealtimeMs(),
            // The "defer at most once" bound has to survive the restart that
            // this restore *is* (Codex, PR #106): without it, a process death
            // inside the confirmation window would let the seed re-grant the
            // deferral and extend the deadline again.
            seedConfirmationDeferralUsed = restoredGrace?.confirmationDeferralUsed ?: false,
        )

        // Re-armed explicitly rather than left to whatever signal happens to
        // flow through `deliver` first: a reboot clears the platform alarm
        // (only wall time survives one), and a restore that seeded the
        // deadline into the engine but left no alarm behind it would silently
        // wait out the rest of the cap instead of the five minutes it
        // promised.
        if (restoredGraceDeadlineMs != null) {
            GraceAlarm.reconcile(appContext, restoredGraceDeadlineMs)
        }

        // A durably-recorded capability loss from before this restart
        // (TODO.md; maintainer decision following Codex's ninth pass on PR
        // #91). Unlike the grace deadline, this ending needs no further
        // presence evidence to confirm — it was already decided — so it is
        // applied immediately rather than waiting on whatever else this
        // restore was woken for; the rest of setup below still runs (fence,
        // Wi-Fi watch), same as any other fail-open ending in this file, and
        // is torn down once `stop()` follows the queued event.
        val restoredCapabilityLoss = CapabilityLossStore.load(appContext, armedAtEpochMs)
        if (restoredCapabilityLoss != null) {
            SnoozeDebugLog.event("restored a durable capability-loss decision; ending")
            // Armed *before* the `trySend`, not after (Codex, PR #95, fifth
            // pass — the live-delivery branch below already had this order
            // right; this restore-time replay didn't). Whatever alarm
            // prompted this restore, if any, is already spent by the time
            // this code runs, so a process death between the old order's
            // `trySend` and its `arm` left the record on disk with no
            // wake-up left pending — the same gap the fourth pass closed on
            // the other replay path.
            CapabilityLossAlarm.arm(appContext)
            trySend(PresenceUpdate(event = PresenceEvent.CapabilityLost(restoredCapabilityLoss), degradation = null))
        }

        // The platform-health level, held beside the feed because it is not
        // the engine's to know: the engine reasons about evidence, and "the
        // sensor stopped watching" is a statement about the sensor. Merged
        // into every update rather than sent once — a later engine update
        // carrying the feed's null level would otherwise read as recovery and
        // restore FULL tracking while no fence is registered, which is the
        // overstating direction the level design exists to prevent (flagged
        // by Codex on PR #70). A level is restated, never delivered (SPEC.md
        // §6.1); this holds that rule at the monitor boundary too.
        //
        // **Two slots, by what clears each** (Codex, PR #75). A refused
        // registration is refuted by a registration that succeeds; but
        // "location services are off" is not — `addGeofences` can *accept* a
        // fence the platform still cannot monitor, so a repair's success
        // clearing that level would promote the snooze on no evidence at
        // all. Services-off clears only on a delivered platform fix, the
        // definitive proof the subsystem it indicts is working.
        val registrationDegradation =
            java.util.concurrent.atomic.AtomicReference<DegradationCause?>(null)
        val servicesDegradation =
            java.util.concurrent.atomic.AtomicReference<DegradationCause?>(null)

        // A missing *grant* outranks a services outage, and only that one
        // exception to services-wins holds (Codex, PR #149, fourth pass).
        // The services slot is refuted only by a delivered fix — and once the
        // grant is gone no fix can ever arrive, so a latched services cause
        // would outlive its own refutation and keep naming the wrong remedy
        // ("turn location on" over "grant the permission") for the rest of the
        // snooze. Worse than the label: `LOCATION_SERVICES_OFF` is not one of
        // the causes the controller drops to duration-only for, so an anchor
        // with an SSID would sit in `WIFI_ONLY` with no grant to read that
        // SSID with — the exact claim this PR's `modeFor` guard exists to stop.
        //
        // The services slot is kept rather than cleared: if the grant returns
        // while services are still off, the re-registration answers
        // `GEOFENCE_NOT_AVAILABLE` and re-latches it anyway, so overriding is
        // as self-healing as clearing and loses nothing meanwhile.
        fun platformLevel(): DegradationCause? =
            platformLevelOf(registrationDegradation.get(), servicesDegradation.get())

        // **Only the engine's own inferences are resumed across a restart**
        // (maintainer, 2026-08-30, narrowing this change to the half that
        // held up). A platform cause is not, and `LOCATION_SERVICES_OFF` is
        // the one that would be: the record carries the cause without its
        // origin, and that cause reaches *both* slots — a recoverable
        // `addGeofences` refusal carries it, as does the "fence is now
        // suspect" marker set when a delivered fix clears the services slot.
        // Three attempts at resuming it anyway each ended in the overstating
        // direction (Codex, PR #142): the registration slot and a
        // clear-on-registration both let `addGeofences` — which can accept a
        // fence the platform cannot monitor — promote the snooze to healthy,
        // and the conservative services slot is cleared by the very first
        // `FixArrived` in `deliver`, cached or not, ahead of any staleness
        // test. Resuming it safely needs the origin persisted, which is a
        // durable field with the grace deadline's epoch-frame problem, so it
        // is its own change (TODO.md).
        //
        // The cost of not resuming it is what `main` already does: a services
        // outage loses its *reason* across a process death, and the engine
        // re-derives a level from its own next observations. That is the
        // understating direction, and no worse than before this change.
        // `NO_LOCATION_IN_BACKGROUND` and `NOTHING_WATCHING` are likewise not
        // resumed — neither is a runtime failure this monitor can refute, and
        // a durable capability loss already has its own restore path above.
        // Assigned once `repairFence` exists below; deliver() runs only after
        // setup, so the placeholder is never the one invoked.
        var repairOnRecovery: () -> Unit = {}

        // What a restored grant has to do, assigned once the Wi-Fi watch's
        // rebuild exists below — the same placeholder shape, for the same
        // reason: the registration-success listener that needs it is declared
        // before the watch it rebuilds, and only ever runs after setup.
        var restoreGrant: () -> Unit = {}

        // What a location-services outage ending should poke, assigned once
        // the repair and the probe below exist — the same placeholder shape
        // `repairOnRecovery` uses, and for the same reason: the watch has to
        // be built above `send`, which reconciles it, while what it pokes is
        // defined further down. Nothing can invoke it before the assignment,
        // since only a registered receiver fires it and only a reported
        // degradation registers one.
        var onLocationRecovered: () -> Unit = {}

        // SPEC.md §8.4's recovery watch. Built here rather than beside the
        // burst and the trigger because `send`, just below, is what
        // reconciles it.
        // Held as its own reference, not just handed to the watch: the grant
        // recheck below reads the live setting through it. That read needs no
        // permission — it says whether the *device's* location switch is on,
        // never where anything is.
        val locationMode = PlatformLocationModeWatch(appContext)
        val locationModeWatch = LocationModeWatch(locationMode) { onLocationRecovered() }

        // A level like `registrationDegradation`/`servicesDegradation` above,
        // for the same reason: `send` is called from restates that never
        // touch the feed at all (a registration refusal, a recovery, a
        // `GeofenceObservation.Unavailable`), and those always pass a bare
        // `PresenceUpdate(event = null, degradation = ...)` with no opinion
        // on grace — trusting that would report grace as over on the next
        // unrelated restate while the deadline the feed still holds keeps
        // counting down underneath it. Mirrored from `feed.graceDeadlineMs`
        // inside `deliver`'s own `feedLock` section below, so a plain atomic
        // read here needs no lock of its own; `false` until the first
        // `deliver` call is always correct, since nothing before that can
        // have started a grace period.
        val graceActiveMirror = java.util.concurrent.atomic.AtomicBoolean(false)

        // The suppressor, mirrored the same way and for the same reason. The
        // controller classifies the mode from whether grace is actually shut,
        // not from which cause is in a slot: a stale `GEOFENCE_NOT_AVAILABLE`
        // arriving after the switch is back on records `LOCATION_SERVICES_OFF`
        // with Wi-Fi working and grace live, and reading the cause there
        // reported `Timer only` over a snooze the grace alarm could still end
        // (Codex, PR #165, sixth pass).
        val locationAccessLostMirror = java.util.concurrent.atomic.AtomicBoolean(false)

        fun send(
            update: PresenceUpdate,
            // Defaulted to the mirrors, which is right for every caller that
            // is *not* a feed transition: a registration refusal, a services
            // outage, a repair. Those carry no levels of their own and mean
            // "restate whatever is current". A `deliver` passes its own
            // snapshot instead, taken under `feedLock` with the transition
            // that produced it.
            graceActive: Boolean = graceActiveMirror.get(),
            locationAccessLost: Boolean = locationAccessLostMirror.get(),
        ) {
            // Every path that sets or clears a platform level ends here — a
            // refused registration, a services outage, a delivered fix, a
            // successful repair — so this is the one place the recovery
            // watch can be matched to the truth without a second call site
            // to forget. Armed exactly while there is an outage to recover
            // from, and restated on every send, so [LocationModeWatch
            // .reconcile] is idempotent rather than this call conditional.
            locationModeWatch.reconcile(platformLevel() != null)
            trySend(
                PresenceUpdate(
                    event = update.event,
                    // The platform's level outranks the engine's when both
                    // are set: the engine *infers* a generic NO_LOCATION_FIX
                    // by counting misses, while a set platform level names
                    // the sensor-layer fact behind those misses — services
                    // off, the fence unavailable — which is the more specific
                    // truth and the one the debug log should carry (flagged
                    // by Codex on PR #72). Both lower the mode identically,
                    // so only the recorded cause differs.
                    degradation = platformLevel() ?: update.degradation,
                    graceActive = graceActive,
                    locationAccessLost = locationAccessLost,
                ),
            )
        }

        // The confirming fixes of SPEC.md §6.10: one-shots while the engine is
        // checking, started and stopped by the duty the engine itself reports
        // (§6.7). Declared before `deliver` because delivery is what drives it.
        var checkingFixes: CheckingFixes? = null
        var motionTrigger: MotionTrigger? = null

        // The feed is a plain value and its callers arrive from more than one
        // thread — the bridge and the fixes on main, the setup body on the
        // collector's — so one lock serializes the accept-and-read-duty step.
        // Never held across anything slow: `accept` is pure arithmetic.
        val feedLock = Any()

        // Orders the grace deadline's durable side effects (the store, the
        // alarm) against each other, without widening `feedLock` to cover
        // them (Codex, PR #91, second pass): those are a binder call and a
        // disk write, not the pure arithmetic `feedLock` is scoped to. Two
        // `deliver` calls on different platform callback threads can finish
        // their feed transitions in one order — serialized correctly by
        // `feedLock` — and their side effects in the other, since nothing
        // orders *those* once the lock is released. Tagging each call with a
        // sequence taken inside `feedLock` and only ever applying the
        // highest one seen is what keeps a stale deadline from overwriting a
        // fresher one that happened to finish first. `persistenceLock`,
        // `persistedSequence` and `lastPersistedGraceDeadlineMs` itself now
        // live on the instance, generation-gated — see their own comments.
        val deliverySequence = java.util.concurrent.atomic.AtomicLong(0)

        // The one path every fail-open ending in this class now goes
        // through (TODO.md; maintainer decision following Codex's ninth
        // pass on PR #91): a bare `trySend` is only an in-process `Flow`
        // send, lost if the process dies before `SnoozeService`'s collector
        // actually consumes it. Durable first, same shape as the grace
        // deadline this file already hardens: persisted, then a near-
        // immediate alarm armed as the prompt wake — a persisted decision
        // with nothing scheduled to act on it just waits for whatever
        // *next* restarts the process, worst case the multi-hour cap.
        fun failCapability(cause: CapabilityLossCause) {
            // The generation check and the write itself share one lock
            // (Codex, PR #95): checking, then writing outside the lock, left
            // a window where `stop()` could retire the generation and clear
            // the store — or a replacement monitor could claim the
            // generation and persist its own loss — between this call's
            // check and its write, which would then land after either and
            // overwrite it with a decision that belongs to a snooze already
            // over. Held for the store write and the alarm arm both, the
            // same shape the grace deadline's own persistence already uses.
            synchronized(persistenceLock) {
                if (persistenceGeneration == startGeneration) {
                    if (CapabilityLossStore.save(appContext, cause, armedAtEpochMs)) {
                        CapabilityLossAlarm.arm(appContext)
                    } else {
                        SnoozeDebugLog.warning(
                            "capability-loss write failed; ending anyway, undurably — the cap still bounds this snooze",
                        )
                    }
                }
            }
            // Sent regardless: harmless on a closed channel (a superseded
            // generation's own flow is already closed by the time it could
            // reach here), and it is still this call's own best shot at
            // ending the snooze promptly if the process survives.
            trySend(PresenceUpdate(event = PresenceEvent.CapabilityLost(cause), degradation = null))
        }

        fun deliver(signal: PresenceSignal) {
            // A delivered platform fix is the recovery proof the services-off
            // level waits for: the subsystem it indicts just answered. The
            // fence, though, may not have survived the outage — the platform
            // can drop registrations while services are off — so recovery
            // marks the registration suspect and re-attempts it now; success
            // is what clears that and makes the fence provably fresh (Codex,
            // PR #75).
            if (signal is PresenceSignal.FixArrived &&
                servicesDegradation.getAndSet(null) != null
            ) {
                SnoozeDebugLog.event("a fix arrived; services-off level cleared")
                registrationDegradation.set(DegradationCause.LOCATION_SERVICES_OFF)
                repairOnRecovery()
            }
            val update: PresenceUpdate
            val duty: LocationDuty
            val graceDeadlineMs: Long?
            val confirmationDeferralUsed: Boolean
            val locationAccessLost: Boolean
            val sequence: Long
            synchronized(feedLock) {
                update = feed.accept(signal)
                duty = feed.duty
                graceDeadlineMs = feed.graceDeadlineMs
                confirmationDeferralUsed = feed.confirmationDeferralUsed
                locationAccessLost = feed.locationAccessLost
                sequence = deliverySequence.incrementAndGet()
            }
            graceActiveMirror.set(graceDeadlineMs != null)
            locationAccessLostMirror.set(locationAccessLost)
            // Both read back below rather than re-read from the mirrors, so
            // this update carries the levels its *own* transition produced
            // (Codex, PR #165, eighth pass). Two `deliver` calls on different
            // callback threads leave `feedLock` in one order and reach `send`
            // in either, so reading a shared mirror at publication time could
            // pair one transition's event with another's levels.
            send(
                update,
                graceActive = graceDeadlineMs != null,
                locationAccessLost = locationAccessLost,
            )
            // Always entered, `Departed` included, so the generation check
            // and the latch below are read and written under the same lock
            // (Codex, PR #91, eighth pass) — see `persistenceGeneration` and
            // `departedObserved`'s own comments.
            synchronized(persistenceLock) {
                if (persistenceGeneration != startGeneration) {
                    // A newer `start()` has claimed persistence ownership
                    // since this flow began — this generation is retired,
                    // and writing now could clobber the current generation's
                    // own state in the single store file both would share.
                    return@synchronized
                }
                if (update.event == PresenceEvent.Departed) {
                    // `send` above only *queued* the event — the collector
                    // that actually ends the snooze and erases the record
                    // hasn't necessarily run yet. The bridge already knows
                    // this and keeps a `GraceElapsed` observation retained
                    // rather than settling it here, for the same reason
                    // (`settlesHeldExit`, below). Clearing the durable
                    // deadline anyway, now or on any later signal this same
                    // generation delivers before `stop()` runs, would strand
                    // that retained observation: a process death before the
                    // collector consumes the departure finds no deadline to
                    // make sense of the eventual replay, reads it as stale,
                    // and the snooze runs to the cap instead. `stop()` is
                    // what actually clears this, once the end is confirmed.
                    if (sequence > persistedSequence) persistedSequence = sequence
                    departedObserved = true
                    return@synchronized
                }
                if (departedObserved) {
                    // Already resolved to `Departed` by an earlier call in
                    // this generation. The engine ignores every signal after
                    // that too (`PresenceState.resolved`), and this must
                    // match — a later call finding a null event here is not
                    // proof grace is genuinely still off, just that
                    // `PresenceFeed` has nothing left to say.
                    return@synchronized
                }
                // Ordered against every other `deliver` call's own attempt to
                // persist, not just against this call's own alarm reconcile:
                // only the highest sequence number seen may write, so a
                // stale transition that finishes its side effects after a
                // fresher one cannot overwrite it.
                if (sequence > persistedSequence) {
                    persistedSequence = sequence
                    // Skipped when the deadline is exactly what the store
                    // already confirmed holding (Codex, PR #91, seventh
                    // pass): most signals through a multi-hour snooze —
                    // every fix in a check burst included — leave grace
                    // untouched, and a `commit()` on each of them anyway
                    // was the real main-thread cost Codex's finding named,
                    // not the synchronous write itself. Real transitions —
                    // Wi-Fi lost, Wi-Fi back, a restore — are rare, and
                    // those still write synchronously below.
                    if (graceDeadlineMs != lastPersistedGraceDeadlineMs) {
                        // Synchronous, on whichever thread delivered this
                        // signal — several of those are the main thread
                        // (`PlatformWifiWatch`'s callback is posted to it)
                        // (Codex, PR #91, sixth pass, reverting the fifth
                        // pass's off-thread dispatch). `producer.launch`
                        // does not block its caller: the calling callback
                        // could return, and the process could be
                        // reclaimed, before the launched write ever
                        // actually ran — reopening the exact gap the
                        // third pass's switch to `commit()` closed.
                        // `AGENTS.md`'s own principle order puts
                        // never-fail-silently above don't-make-the-user-
                        // wait when the two genuinely conflict, and
                        // `ActiveSnoozeStore.save` already makes this
                        // same trade on the arm and release paths for the
                        // same reason. This write is two `Long`s, run only
                        // on a real transition, not the kind of frequent,
                        // unconditional cost principle 5 is guarding
                        // against.
                        //
                        // Persisted *before* the alarm is armed (Codex, PR
                        // #91), checking the result rather than trusting
                        // `apply()` to have landed by the time this returns
                        // (Codex, third pass): a `commit` that doesn't
                        // actually reach disk before a kill is the same gap
                        // as arming the alarm first, just moved — a process
                        // death right after leaves a real armed alarm with
                        // nothing durable behind it, and a restore reads the
                        // eventual `GraceElapsed` as stale. Saved first and
                        // confirmed, a death before the alarm is armed is
                        // harmless the other way — restore already re-arms
                        // the alarm itself from whatever it loads (above).
                        val saved =
                            GraceDeadlineStore.save(
                                appContext,
                                graceDeadlineMs,
                                confirmationDeferralUsed,
                                armedAtEpochMs,
                                clockNow(),
                            )
                        if (saved) {
                            // Confirmed only now, never optimistically —
                            // see this var's own comment on why a failed
                            // save must not update it.
                            lastPersistedGraceDeadlineMs = graceDeadlineMs
                            // The engine can set a grace deadline but cannot
                            // wake a phone; the alarm is what makes the §6.6
                            // promise real. Reconciled per signal like the
                            // burst below — armed while a deadline stands,
                            // canceled the moment presence evidence clears
                            // it. Only once the store agrees, or the two
                            // could disagree about which snooze — or
                            // whether one — is actually being watched.
                            GraceAlarm.reconcile(appContext, graceDeadlineMs)
                        } else if (graceDeadlineMs != null) {
                            // A failed *setting* write, not a failed clear
                            // (Codex, PR #91, sixth pass): leaving this
                            // signal merely "ungraced" relies on some later
                            // signal's own save succeeding, but an anchor
                            // already off its Wi-Fi with no usable fix
                            // (location duty NONE) may never produce another
                            // one — nothing would ever retry, and the snooze
                            // would silently run to the multi-hour cap
                            // instead of the five minutes it promised.
                            // Ending it here is principle 1's fail-open, not
                            // principle 3's data loss: nothing the user
                            // configured is at risk, only whether this one
                            // snooze ends on time.
                            SnoozeDebugLog.warning(
                                "grace deadline write failed; ending the snooze rather than risk it never ending",
                            )
                            failCapability(CapabilityLossCause.MONITORING_UNAVAILABLE)
                        } else {
                            // A failed *clearing* write follows good news —
                            // presence evidence already told the engine
                            // grace is off — so ending the snooze over an
                            // unrelated write failure would be a punitive
                            // overreaction. The store can carry a stale
                            // entry until the next successful write or
                            // `stop()`'s own clear; worst case a future
                            // restore over-trusts a deadline that no longer
                            // applies, which the duration cap still bounds.
                            SnoozeDebugLog.warning(
                                "clearing the grace deadline failed; a stale entry may remain on disk",
                            )
                        }
                    }
                }
            }
            // Pause, not close: the duty leaving ACTIVE is a state the engine
            // can re-enter, and only teardown (awaitClose) may end the burst
            // for good.
            // §6.7's escalator, armed exactly where the duty cycle wants
            // it: SANITY is the resting state — away from the anchor's
            // Wi-Fi, with something to measure against, nothing being
            // checked — and it is the only state where being told the phone
            // moved changes what happens next. ACTIVE is already asking
            // faster than a trigger would help; NONE means either D4 has
            // better evidence for free or there is nothing a fix could
            // settle. Restated on every update, so [MotionTrigger.reconcile]
            // has to be idempotent rather than this call being conditional.
            motionTrigger?.reconcile(duty == LocationDuty.SANITY)
            if (duty == LocationDuty.ACTIVE) {
                checkingFixes?.start()
            } else {
                checkingFixes?.pause()
                // Not checking means any held exit reached its terminal
                // answer — refuted, degraded past, or dropped as stale — so
                // the bridge's slot is retired here, which is what keeps a
                // detach after the answer from waking a service with nothing
                // left to check (Codex, PR #73). Except an *ending* answer:
                // `send` above only queued it, and a teardown before the
                // collector consumes it would find the slot already settled —
                // a departure confirmed and then lost (Codex, PR #73). An end
                // that is acted on settles the slot through stop().
                if (settlesHeldExit(duty, update.event)) GeofenceSignalBridge.settleExit()
            }
        }

        // D4's suppressor extends to the backstop's probe: associated with
        // the anchor's network, presence is already proven for free, and a
        // resting fix would spend §9's budget re-answering it. `NONE` also
        // covers the anchor nothing can measure against; `ACTIVE` means a
        // burst is already asking faster than a probe would.
        // Recovery has to *re-drive* the duty, not merely unblock it (Codex,
        // PR #149, second pass on the same mechanism). The burst is started by
        // the duty reconciliation in `deliver`, which a registration success
        // does not go through, and `settle`'s `stopWork` already left
        // `running` false — so clearing the suspension alone leaves an active
        // departure check with nothing asking for the confirming fix it needs,
        // and an SSID-less anchor waits for an unrelated signal or the cap.
        // Mirrors `deliver`'s own branch so the two cannot disagree about what
        // a duty means.
        fun resumeChecking() {
            checkingFixes?.resume()
            when (synchronized(feedLock) { feed.duty }) {
                LocationDuty.ACTIVE -> checkingFixes?.start()
                LocationDuty.SANITY -> checkingFixes?.sanityCheck()
                LocationDuty.NONE -> Unit
            }
        }

        fun sanityProbe() {
            val duty = synchronized(feedLock) { feed.duty }
            if (duty == LocationDuty.SANITY) checkingFixes?.sanityCheck()
        }

        // Recoverable refusals set the platform level (and so keep being
        // restated on every later update); fatal ones end the snooze and need
        // no level at all.
        fun reportRegistration(failure: GeofenceRegistrationFailure) {
            when (failure) {
                is GeofenceRegistrationFailure.Recoverable -> {
                    // The registration slot, whatever the cause names: the
                    // origin decides what refutes it, and a later successful
                    // registration refutes a refused one. If services really
                    // are off, the burst's own callback and the platform's
                    // Unavailable events keep the services slot said
                    // independently (Codex, PR #75).
                    registrationDegradation.set(failure.cause)
                    // `blocksLocationReads`, not `isGrantLoss` (Codex, PR
                    // #165, fourth pass). A registration refused because
                    // location services are off withholds the SSID exactly as
                    // a dead grant does, so the engine needs the same
                    // suppressor — and without it there was a path where
                    // nothing delivered one at all: this branch recorded
                    // `LOCATION_SERVICES_OFF` in the slot and stayed silent,
                    // and the redacted Wi-Fi read that followed found its own
                    // cause already latched and returned early, having already
                    // armed grace through the tracker. The card read
                    // `Timer only` while the alarm still ended the snooze at
                    // the anchor.
                    if (failure.cause.blocksLocationReads) {
                        // Through `deliver`, not `send`, and the slot is set
                        // first so this update already carries the cause.
                        // The engine has to hear about a dead grant because
                        // a §6.6 grace period is engine state the platform
                        // slots do not touch: without this the mode line
                        // would say `Timer only` while the deadline armed by
                        // a redacted SSID read kept counting, and the alarm
                        // ended the snooze minutes later (Codex, PR #149).
                        // `deliver` is also what makes the cancellation
                        // durable — the cleared deadline is persisted and
                        // `GraceAlarm.reconcile` cancels the real alarm on
                        // the same pass — which a bare `trySend` could not.
                        deliver(PresenceSignal.LocationAccessLost(readElapsedRealtimeMs()))
                    } else {
                        send(PresenceUpdate(event = null, degradation = null))
                    }
                }
                is GeofenceRegistrationFailure.Fatal -> failCapability(failure.cause)
            }
        }
        motionTrigger = MotionTrigger(
            PlatformMotionTrigger(appContext),
            readElapsedRealtimeMs,
            ::deliver,
        )
        checkingFixes = CheckingFixes(
            AndroidBurstScheduler(),
            PlatformFixRequester(appContext),
            readElapsedRealtimeMs,
            ::deliver,
            onPermissionLost = {
                // Classified through the same tested mapping registration
                // uses, so the two paths cannot disagree about one grant.
                //
                // This used to end the snooze (Codex, PR #72, against a first
                // version that degraded). The maintainer reversed it on
                // 2026-08-30: the duration cap is mandatory, so duration-only
                // is bounded by construction and ending early discards the
                // user's snooze without buying safety the cap does not already
                // give. PR #72's objection — that degrading leaves the snooze
                // armed until its cap with nothing watching — is exactly what
                // is now accepted, deliberately and with the card saying so.
                reportRegistration(
                    GeofenceRegistrationFailure.fromSecurityException(hasFineLocation()),
                )
            },
            onServicesOff = {
                // The recoverable side of the same split, said the moment it
                // is known rather than after three generic unanswered fixes.
                // The *services* slot, not the registration one: what this
                // asserts is refuted by a delivered fix, never by a
                // registration the platform accepts while still unable to
                // monitor it (Codex, PR #75).
                servicesDegradation.set(DegradationCause.LOCATION_SERVICES_OFF)
                send(PresenceUpdate(event = null, degradation = null))
            },
        )

        var bridge: AutoCloseable? = null
        // Atomic for the reason [active] and the bridge slot are: the
        // restoration below replaces this from whichever thread delivered the
        // recheck, and the teardown closes it from the flow's own — a plain
        // `var` there leaks the replacement or closes a watch twice.
        val wifiWatch = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(null)

        // Registration, callable twice: once from setup, and again as the
        // repair a warm backstop wake asks for through the bridge — a fence
        // whose first registration failed transiently must be re-attempted
        // without tearing down the running collection, because a replacement
        // feed forgets its accumulated failure history and its first
        // unanswered probe would promote a broken snooze back to FULL
        // (Codex, PR #75). Re-registering under the same id replaces in
        // place, so a healthy fence is unharmed.
        //
        // Attempts are tokened because repairs can overlap: one wake can
        // queue a repair from the poke and a second from its own probe's
        // recovery, and the two async registrations then race — a
        // superseded attempt's late failure would indict the fence a later
        // attempt just registered, up to a fatal loss over a healthy watch
        // (Codex, PR #75). Only the latest attempt's outcome speaks; a
        // superseded one is dropped, its truth restated by the attempt that
        // replaced it.
        val registrationAttempt = java.util.concurrent.atomic.AtomicLong(0L)

        fun registerFence() {
            if (!anchor.hasUsableFix) return
            val attempt = registrationAttempt.incrementAndGet()
            val client: GeofencingClient = LocationServices.getGeofencingClient(appContext)
            val request = GeofencingRequest.Builder()
                .addGeofence(
                    Geofence.Builder()
                        .setRequestId(GEOFENCE_ID)
                        .setCircularRegion(anchor.lat!!, anchor.lon!!, anchor.radiusM.toFloat())
                        // The fence outlives nothing: stop() removes
                        // it, and the duration cap bounds the snooze
                        // it serves. An expiry here would add a second
                        // clock that could silently stop watching
                        // before the snooze ends.
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                        .build(),
                )
                // No initial trigger: the anchor was captured here
                // moments ago, so an exit at registration would be a
                // stale platform belief, and no single source may end
                // a snooze anyway (SPEC.md §6.10).
                .setInitialTrigger(0)
                .build()
            try {
                // `addGeofences` only enqueues — the lock never waits
                // on Play Services.
                client.addGeofences(request, transitionIntent(appContext))
                    .addOnSuccessListener {
                        if (registrationAttempt.get() != attempt) return@addOnSuccessListener
                        SnoozeDebugLog.event("geofence registered; radius=%sm", anchor.radiusM)
                        // Actual recovery is the one thing that clears the
                        // platform level: a registration that just succeeded
                        // is the fence provably watching again, and a level
                        // left standing would hold the mode down over a
                        // repaired watch. The engine's own inferred
                        // degradation is untouched — only a usable fix
                        // clears that, which is exactly the asymmetry that
                        // keeps a fresh registration from overstating what
                        // location can do.
                        // Only the registration slot: `addGeofences` can
                        // accept a fence the platform still cannot monitor,
                        // so success here proves nothing about services —
                        // that slot waits for a delivered fix (Codex, PR
                        // #75).
                        val latched = registrationDegradation.get()
                        if (latched == null) return@addOnSuccessListener
                        // The switch has to answer too (Codex, PR #165,
                        // fifth pass). This handler has always said a
                        // registration proves nothing about services, and
                        // that was harmless while only a grant loss reached
                        // the engine. It stopped being harmless when this PR
                        // let `LOCATION_SERVICES_OFF` reach it: a repair
                        // accepted during an outage would clear the refusal,
                        // withdraw the suppressor, and tear down the mode
                        // watch that the non-null slot was arming — the only
                        // thing left able to lift the latch — leaving a
                        // fix-only anchor reporting `FULL` over a fence the
                        // platform still cannot monitor.
                        when (val outcome = registrationRefutes(latched, locationMode.isEnabled() == true)) {
                            is RegistrationOutcome.Nothing -> {
                                SnoozeDebugLog.event(
                                    "geofence re-registered, but location is not readable as on;" +
                                        " the refusal stands",
                                )
                                return@addOnSuccessListener
                            }
                            is RegistrationOutcome.Reclassify -> {
                                // The grants came back mid-outage. Keep the
                                // latch and the watch — reads are still
                                // blocked — and rename the cause so the card
                                // stops asking for a permission the user has
                                // already restored.
                                if (registrationDegradation.compareAndSet(latched, outcome.cause)) {
                                    SnoozeDebugLog.event(
                                        "geofence re-registered under a services outage;" +
                                            " the grant cause is stale and was replaced",
                                    )
                                    send(PresenceUpdate(event = null, degradation = null))
                                }
                                return@addOnSuccessListener
                            }
                            is RegistrationOutcome.Refuted -> Unit
                        }
                        // Compare-and-set, not `getAndSet`: the guard above
                        // decided against a value, so clearing a different
                        // one would clear a refusal nothing has refuted.
                        if (registrationDegradation.compareAndSet(latched, null)) {
                            SnoozeDebugLog.event("geofence re-registered; registration level cleared")
                            // The engine's grace suppressor is refuted by
                            // exactly this and nothing else (Codex, PR #150).
                            // Geofencing needs `ACCESS_BACKGROUND_LOCATION`
                            // outright on API 29+, so a registration the
                            // platform accepted is proof both grants are
                            // actually held — which neither a delivered fix
                            // (it can be cached from before the revocation)
                            // nor a nameable SSID (readable in the foreground
                            // under a while-in-use grant) can show.
                            //
                            // Emitted from the *proof*, never gated on which
                            // cause happens to be sitting in the slot (Codex,
                            // PR #150, and a regression the gated version
                            // introduced). A repair while location services
                            // are off overwrites this slot with
                            // `LOCATION_SERVICES_OFF` — see `deliver` — so a
                            // grant restored during an outage would find a
                            // non-grant cause here and skip the restoration,
                            // stranding the latch for the life of the snooze.
                            // Nothing could then arm grace, and a user who
                            // really left would be silenced to the cap: the
                            // too-quiet direction, which is the one principle
                            // 1 refuses. The engine no-ops when the latch is
                            // already clear, so emitting always is free.
                            // A registration that just succeeded is proof the
                            // grant is back, and it is the only such proof
                            // that arrives on its own — the burst cannot
                            // demonstrate recovery while it is the thing
                            // suspended. Without this the fix requester stays
                            // suspended for the life of the process, so an
                            // anchor with no SSID could report `FULL` over a
                            // repaired fence it can never confirm an exit
                            // from, and sit snoozed to the cap (Codex, PR
                            // #149). Safe against a teardown racing it:
                            // `resume` refuses on a closed instance.
                            //
                            // The same steps the Wi-Fi-only recheck runs,
                            // from the one list (Codex, PR #185): this path
                            // used to declare, resume and restate inline
                            // and never rebuilt the Wi-Fi watch, so a fenced
                            // anchor that also carries an SSID kept a watch
                            // the revocation had poisoned — the tracker at
                            // *not associated* — and a real Wi-Fi departure
                            // after the re-grant read as a repeat and said
                            // nothing. The rebuild is what SPEC.md §8.2
                            // promises of a restored grant, whichever path
                            // proves it.
                            restoreGrant()
                        }
                    }
                    .addOnFailureListener { e ->
                        if (registrationAttempt.get() != attempt) return@addOnFailureListener
                        val code = (e as? ApiException)?.statusCode
                        val failure = if (code != null) {
                            GeofenceRegistrationFailure.fromStatusCode(code, hasFineLocation())
                        } else {
                            GeofenceRegistrationFailure.Fatal(
                                app.snoozemo.core.CapabilityLossCause.MONITORING_UNAVAILABLE,
                            )
                        }
                        SnoozeDebugLog.warning(
                            "geofence registration refused (status=${code ?: "none"}); " +
                                "classified ${failure.javaClass.simpleName}",
                        )
                        reportRegistration(failure)
                    }
            } catch (e: SecurityException) {
                // The grant went between the permission check and the
                // call — fail open with the reason rather than watch
                // nothing quietly.
                SnoozeDebugLog.warning("geofence registration refused: permission gone")
                reportRegistration(
                    GeofenceRegistrationFailure.fromSecurityException(hasFineLocation()),
                )
            }
        }

        // The repair, marshaled off the bridge's lock before taking the
        // registration lock — the two are taken in the opposite order on the
        // stop() path, and holding one while asking for the other is the
        // deadlock shape. Re-guarded on arrival: a repair queued behind this
        // flow's own stop must find the generation moved and do nothing.
        /**
         * Re-asks the location grant directly, and moves the registration slot
         * to match — the Wi-Fi-only anchor's stand-in for what `addGeofences`
         * being refused or accepted tells every other snooze.
         *
         * Only that anchor shape, because only it lacks the registration whose
         * outcome would answer this. An anchor with a fix is served by the real
         * thing, and asking here as well would let a permission read that
         * happens to pass clear a cause the fence itself is still refusing.
         *
         * **Clears only a grant cause, unlike the success path.** There, a
         * registration the platform accepted is proof the whole subsystem
         * works, so it clears the slot whatever is in it. A permission read
         * proves something much narrower: that these two grants are held. It
         * says nothing about location services, so a latched
         * `LOCATION_SERVICES_OFF` — which the slot can hold — has to survive
         * it, or an outage would read as repaired every fifteen minutes.
         *
         * Acts on transitions only. Re-delivering `LocationAccessLost` on
         * every firing would rewrite persisted grace state four times an hour
         * for a snooze in a steady, already-reported state; the cause is
         * recomputed rather than merely presence-checked, so a grant
         * downgraded from one shape of loss to another still moves.
         */
        /**
         * Asks the grant when Wi-Fi reports a *loss*, before that loss is spent.
         *
         * The recheck below owns the periodic case and the restore owns the
         * cold one, but neither covers the commonest: a snooze running while
         * the user walks into Settings and revokes location. The capabilities
         * callback fires within moments, redacted, and D7 reads that as a
         * loss — which arms a five-minute grace deadline and ends the snooze
         * roughly ten minutes before the next 15-minute recheck could have
         * suppressed it. The premature departure this whole change exists to
         * stop, surviving in the one path a live monitor actually takes
         * (Codex, PR #157).
         *
         * Only the latch half of [grantRecheck] is acted on here. A loss is
         * not the moment to declare a restoration — and declaring one would
         * rebuild the watch from inside its own callback.
         *
         * The decision itself is [latchGrantLoss], which is also where the
         * compare-and-set guarding it against a concurrent restore lives.
         *
         * Costs two `checkSelfPermission` lookups, against the package
         * manager's cache rather than a binder, and only on a real transition:
         * the tracker reports losses, not repeats.
         */
        fun latchIfGrantGone() {
            if (!needsWifiRecheck(anchor)) return
            latchGrantLoss(
                registrationDegradation,
                ::hasGeofencingGrants,
                ::hasFineLocation,
                servicesOn = { locationMode.isEnabled() == true },
            ) {
                SnoozeDebugLog.warning("a location grant is missing when Wi-Fi reported a loss")
                reportRegistration(GeofenceRegistrationFailure.Recoverable(it))
            }
        }

        /**
         * A Wi-Fi read came back with the network's name withheld.
         *
         * The one report on this path that needs no probe to justify it. A
         * redacted name *is* the outage — the platform refusing to answer,
         * at the moment the answer was needed — so this latches and declares
         * unconditionally, and [redactionCause] only decides what the card
         * calls it. That is the difference from [latchIfGrantGone], which
         * asks three permission questions and stays silent when none of them
         * explains what just happened.
         *
         * `deliver`, not `send`, and for the reason `reportRegistration`
         * gives: a §6.6 grace deadline is engine state no platform slot
         * touches, and only a delivered [PresenceSignal.LocationAccessLost]
         * shuts the arming paths, cancels a deadline already armed, and
         * makes the cancellation durable.
         *
         * **Cancelling one already armed is the case that matters**, not a
         * fallback (Codex, PR #165). The watch reports a redaction *after*
         * the loss it produced, deliberately, because reporting first runs
         * everything below — including the recovery this latch arms — ahead
         * of that loss.
         *
         * The compare-and-set is what keeps a steady outage quiet: every
         * capabilities callback during it reads redacted, and re-delivering
         * on each would restate a state nothing has changed about.
         */
        fun reportRedactedRead() {
            // Safe to skip the delivery when the cause is unchanged *only*
            // because every path that records a `blocksLocationReads` cause
            // now delivers the suppressor with it — `reportRegistration`
            // above included. The slot standing for "the engine has been
            // told" is what broke before (Codex, PR #165, fourth pass): they
            // are different facts, and the two are kept in step by having one
            // rule for both writers rather than by this read guessing.
            val latched = registrationDegradation.get()
            val cause = redactionCause(
                hasFineLocation = hasFineLocation(),
                grantsHeld = hasGeofencingGrants(),
                servicesOn = locationMode.isEnabled() == true,
            )
            if (latched == cause) return
            if (!registrationDegradation.compareAndSet(latched, cause)) return
            SnoozeDebugLog.warning("the Wi-Fi name came back withheld; location access is gone")
            deliver(PresenceSignal.LocationAccessLost(readElapsedRealtimeMs()))
        }

        fun buildWifiWatch(ssid: String): AutoCloseable =
            PlatformWifiWatch(
                appContext,
                ssid,
                readElapsedRealtimeMs,
                onRedactedRead = ::reportRedactedRead,
            ) { signal ->
                // Before the loss, not after: `reportRegistration` delivers
                // `LocationAccessLost`, which is what shuts both grace-arming
                // paths, so asking first means the deadline is never armed
                // rather than armed and then canceled.
                //
                // Still asked for a loss the watch did not call redacted: the
                // network genuinely changed, and a grant may have gone at the
                // same time — the redaction path only covers the reads the
                // platform refused to answer.
                if (signal is PresenceSignal.AnchorWifiLost) latchIfGrantGone()
                deliver(signal)
            }

        /**
         * Installs a fresh Wi-Fi watch, and does not leave one behind if the
         * flow closed while it was being built.
         *
         * `buildWifiWatch` registers its `NetworkCallback` as it constructs,
         * and only the `getAndSet` below publishes it — so a teardown
         * completing in that gap closes the *old* watch, clears the slot, and
         * never sees the new one, leaving a callback registered with nothing
         * holding a reference to unregister it (Codex, PR #157, eighth pass).
         * The rebuild runs on whichever thread delivered the recheck while
         * `awaitClose` runs on the flow's own, which is what makes the gap
         * reachable at all.
         *
         * So the lifecycle is re-read *after* publishing, and the candidate is
         * taken back and closed if the flow has since closed. The
         * compare-and-set is what keeps that from double-closing: a teardown
         * racing this line takes the watch out of the slot itself, and only
         * one of the two can win it.
         *
         * Published first and re-checked second, rather than gated before the
         * build: closing a watch that is genuinely live would stop a running
         * snooze watching Wi-Fi, which is the worse of the two failures. This
         * order can only leak in the window it then closes, never blind a
         * snooze that is still going.
         */
        fun publishWifiWatch(ssid: String) {
            if (!publishWatch(wifiWatch, buildWifiWatch(ssid)) { isClosedForSend }) {
                SnoozeDebugLog.event("the Wi-Fi watch was built into a closed flow; closed it")
            }
        }

        /**
         * Runs [restoreSteps] for this anchor — the one restoration, whichever
         * path proved the grant back: a registration the platform accepted
         * (`registerFence`) or a permission read at the Wi-Fi recheck
         * ([reconcileGrants]). Driven from the list rather than written out
         * at either site, so which steps run and in what order is a value a
         * test can assert. Deleting the rebuild or moving it ahead of the
         * restoration are both real regressions that every other test on
         * this path stays green through (Codex, PR #157), and the `when`
         * below is exhaustive, so a step cannot be dropped silently.
         */
        fun runRestoreSteps() {
            restoreSteps(anchor).forEach { step ->
                when (step) {
                    // The engine's own latch, which is what actually re-opens
                    // grace — the slot alone only decides the mode line.
                    RestoreStep.DeclareRestored -> deliver(
                        PresenceSignal.LocationAccessRestored(readElapsedRealtimeMs()),
                    )
                    // Nothing here requests fixes, but the requester is
                    // suspended process-wide and `resume` refuses on a closed
                    // instance, so leaving it suspended is the only outcome
                    // with a cost.
                    RestoreStep.ResumeChecking -> resumeChecking()
                    RestoreStep.RebuildWifiWatch ->
                        anchor.ssid?.let { publishWifiWatch(it) }
                    // The restate carries the *feed's* level, not a
                    // synthesized null: this update arrives outside any
                    // signal, and a null here would promote a snooze whose
                    // fixes are still failing (Codex, PR #75) — the feed's
                    // degradation clears only on a usable fix, as ever.
                    RestoreStep.RestateLevel -> send(
                        PresenceUpdate(
                            event = null,
                            degradation = synchronized(feedLock) { feed.degradation },
                        ),
                    )
                }
            }
        }
        restoreGrant = ::runRestoreSteps

        fun reconcileGrants() {
            if (!needsWifiRecheck(anchor)) return
            // Captured, not re-read. The decision below is made against this
            // exact value, so the compare-and-set has to name it: re-reading
            // the slot inside the branch and comparing it against itself would
            // clear a *newer* loss that a registration answer had latched in
            // between, and announce a restoration nothing had refuted (Codex,
            // PR #157). Losing the race now means doing nothing, which is the
            // half that keeps a real loss latched.
            val latched = registrationDegradation.get()
            when (
                val next = grantRecheck(
                    latched,
                    hasGeofencingGrants(),
                    hasFineLocation(),
                    servicesOn = locationMode.isEnabled() == true,
                )
            ) {
                GrantRecheck.Nothing -> Unit
                GrantRecheck.Restore ->
                    if (registrationDegradation.compareAndSet(latched, null)) {
                        SnoozeDebugLog.event(
                            "the location grant is back; lifting the Wi-Fi-only latch",
                        )
                        runRestoreSteps()
                    }
                // Named in a compare-and-set for the same reason the restore
                // above is, and against the same captured value: a restore
                // landing while the two permission lookups ran would otherwise
                // be overwritten by a loss it had already refuted, shutting
                // grace with nothing to reopen it for fifteen minutes. See
                // [latchGrantLoss], which is this decision on the callback
                // path — the reads are already in hand here, so the
                // compare-and-set is written out rather than re-asked.
                is GrantRecheck.Latch ->
                    if (registrationDegradation.compareAndSet(latched, next.cause)) {
                        SnoozeDebugLog.warning("a location grant is missing at the Wi-Fi recheck")
                        reportRegistration(GeofenceRegistrationFailure.Recoverable(next.cause))
                    }
            }
        }

        fun repairFence() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                synchronized(registrationLock) {
                    if (stopGeneration.get() == startGeneration && !isClosedForSend) {
                        SnoozeDebugLog.event("repair: re-registering the fence")
                        registerFence()
                    }
                }
            }
        }
        repairOnRecovery = ::repairFence

        // The prompt half of an outage's recovery (TODO.md, Phase 3; Codex,
        // PR #70): `GEOFENCE_NOT_AVAILABLE` and a services outage both report
        // themselves correctly, and until now nothing watched for the user
        // switching location back on — the repair waited for the §6.10
        // backstop's own cadence. This pokes exactly what a warm backstop
        // wake pokes, the moment the platform says the outage is over.
        //
        // The fence first, and **unconditionally** — not gated on the
        // registration level the way `RepairPoke` is (Codex, PR #139, second
        // pass). That gate exists because re-registering *into* a live
        // outage is IPC for nothing and risks a mis-mapped refusal ending the
        // snooze mid-outage (PR #75); here the outage has provably ended, so
        // its premise is gone. And the case it was hiding is real: a
        // `GEOFENCE_NOT_AVAILABLE` broadcast sets only the *services* level,
        // so a fence the platform had stopped monitoring was left unregistered
        // until a backstop restore happened to rebuild it — the substantive
        // half of this outage, waiting on the very cadence this watch exists
        // to beat. `registerFence` is a no-op for an anchor with no fence, and
        // re-registering a healthy one replaces it in place.
        //
        // Then a fix, by whichever route is already open. `sanityProbe` covers
        // the resting snooze and is a declared no-op mid-check; `retryNow`
        // covers exactly that gap — an outage that began *during* a departure
        // check leaves the duty `ACTIVE`, where the probe does nothing and the
        // burst's cadence has by then backed off to five minutes. They are
        // mutually exclusive by their own guards, so calling both asks once,
        // never twice.
        //
        // **Neither forces a fix past D4's suppressor**, and that is the one
        // thing this callback deliberately does not do. On the anchor's Wi-Fi
        // the duty is `NONE` and both routes decline, so the services level
        // stands until real evidence arrives — over-reporting degradation,
        // which is the safe direction, rather than clearing it on the
        // broadcast alone (a fence the platform accepts is not a subsystem
        // proven to work, PR #75). Asking anyway would not be a battery
        // quibble: `Presence.fixArrived` runs the §6.6 test on every fix
        // whatever the association says, and §6.6's unambiguous shortcut ends
        // a snooze on a *single* reading beyond radius + 500 m — so on a
        // network that spans more ground than the anchor's radius, a fix taken
        // only to tidy a notification label could end the snooze outright,
        // with no departure evidence prompting it. That is the trade D4 exists
        // to refuse.
        //
        // Driven from [recoverySteps] rather than written out here, for the
        // reason [restoreSteps] is: a step that only *some* anchor shapes
        // need is one a later edit can drop without any other test noticing
        // (Codex, PR #157).
        onLocationRecovered = {
            recoverySteps(anchor).forEach { step ->
                when (step) {
                    // A fenced anchor learns its grant is back from a
                    // registration the platform accepts. A Wi-Fi-only
                    // anchor never calls `addGeofences` at all, so nothing
                    // on this callback would have re-asked — and since the services
                    // gate holds the latch shut while location is off, this
                    // recovery is the moment it becomes liftable. Without it
                    // the latch waits out the 15-minute recheck, and an
                    // association cannot clear it (Codex, PR #157).
                    RecoveryStep.ReconcileGrants -> reconcileGrants()
                    RecoveryStep.RepairFence -> repairFence()
                    RecoveryStep.RetryFixes -> checkingFixes?.retryNow()
                    RecoveryStep.SanityProbe -> sanityProbe()
                }
            }
        }

        // The whole setup — bridge attachment included — runs as one section
        // under the registration lock, guarded by whether this flow has
        // already been stopped. A stop() racing the setup window closes only
        // the channel and the body deliberately continues to awaitClose; left
        // unguarded, that stopped body could attach and register *after* a
        // replacement did, displacing the replacement's listener and fence
        // and then tearing them down — a snooze left believing it is watched
        // by nothing (flagged by Codex on PR #70). Guarded and serialized, a
        // stopped flow abandons setup with nothing to tear down, and every
        // interleaving with a replacement's setup or this flow's own teardown
        // resolves through the identity checks.
        synchronized(registrationLock) {
            if (!isClosedForSend) {
                // Constructed *before* the bridge attaches (Codex, PR #91,
                // fifth pass), and it matters specifically for a restore
                // carrying a seeded grace deadline. The subtlety the earlier
                // form of this comment got wrong (Codex, PR #105): the watch's
                // synchronous seed *cannot* confirm the anchor is back —
                // `getNetworkCapabilities` redacts the SSID, so only the async
                // callback can name the network. What the seed can do is
                // deliver `AnchorWifiPresentUnconfirmed` when any Wi-Fi is up,
                // and constructing before the attach is what gets that signal
                // into the engine before the bridge replays a `GraceElapsed`
                // held from the outage. On that flag `Presence.graceElapsed`
                // defers the due deadline for one short confirmation window
                // (`Presence.WIFI_CONFIRM`) instead of resolving `Departed`, so
                // the async callback that follows can clear it if the user
                // genuinely returned. Without the pre-attach order the replay
                // would resolve `Departed` and shut the engine down
                // (`PresenceState.resolved` ignores every later signal) before
                // the seed — let alone the callback — was ever heard.
                anchor.ssid?.let { publishWifiWatch(it) }
                // Asked *before* the bridge attaches, for the same reason
                // the Wi-Fi watch is built before it (Codex, PR #150). A
                // grant revoked while the process was dead is not known to
                // this monitor until `addGeofences` is refused, which is
                // asynchronous and lands well after the attach — and the
                // attach can replay a `GraceElapsed` held from the outage,
                // which resolves `Departed` and shuts the engine down
                // (`PresenceState.resolved` ignores every later signal). The
                // refusal would then arrive at a snooze that had already
                // ended on the deadline this whole change exists to suppress.
                // A live permission read costs two binder-free lookups and
                // gets the fact in ahead of the replay.
                //
                // Asked for a Wi-Fi-only anchor too, which it was not until
                // the recheck below became its refutation. The objection was
                // never that the latch is wrong there — it is the same
                // revoked grant with the same consequence, and D7 makes the
                // redacted read that follows report a departure the user
                // never made — but that nothing on that path could ever lift
                // it, and a permanent latch silences a user who really did
                // leave: the too-quiet direction principle 1 refuses. The
                // 15-minute `WifiRecheck` re-asks this same question and
                // clears it, so the latch is now bounded by that rather than
                // by the snooze.
                //
                // Restricted to anchors with an SSID (`needsWifiRecheck`),
                // because that is both what makes a grant loss *matter* here
                // — no SSID, nothing to misread — and what guarantees the
                // recheck alarm that refutes it is actually armed.
                if (watchesGrants(anchor) && !hasGeofencingGrants()) {
                    SnoozeDebugLog.warning(
                        "a location grant is missing at restore; suppressing grace before the replay",
                    )
                    reportRegistration(
                        GeofenceRegistrationFailure.fromSecurityException(hasFineLocation()),
                    )
                }
                bridge = GeofenceSignalBridge.attach { observation ->
                    when (observation) {
                        is GeofenceObservation.Exit -> {
                            SnoozeDebugLog.event("geofence exit observed")
                            deliver(PresenceSignal.GeofenceExit(observation.atElapsedRealtimeMs))
                        }
                        is GeofenceObservation.Unavailable -> {
                            SnoozeDebugLog.warning("geofencing became unavailable mid-snooze")
                            servicesDegradation.set(DegradationCause.LOCATION_SERVICES_OFF)
                            send(PresenceUpdate(event = null, degradation = null))
                        }
                        // The backstop asking a live monitor for one resting
                        // fix — the warm half of §6.10's probe; the cold half
                        // is the starting probe below, taken by the monitor a
                        // backstop restore creates.
                        // The recheck found a monitor after all, so the
                        // Wi-Fi watch is already running and already knows
                        // the association — nothing to read there. What this
                        // firing does own is the grant: a Wi-Fi-only anchor
                        // has no `addGeofences` to be refused by a revoked
                        // one or accepted by a restored one, so this is the
                        // only place either becomes known. Then keep the
                        // chain alive: the alarm is one-shot, and only a
                        // monitor arms the next one.
                        is GeofenceObservation.WifiRecheck -> {
                            reconcileGrants()
                            WifiRecheckAlarm.reconcile(appContext, needsWifiRecheck(anchor))
                        }
                        is GeofenceObservation.SanityPoke -> sanityProbe()
                        // A warm wake asking for the fence to be re-attempted
                        // — repair without replacing the collection, so the
                        // engine's failure memory survives (Codex, PR #75).
                        // Gated on the *registration* slot alone: that is the
                        // one set of failures re-registration can refute. A
                        // services outage repairs nothing until it ends —
                        // re-registering into it is IPC for nothing and risks
                        // a mis-mapped refusal ending the snooze mid-outage
                        // (Codex, PR #75) — and the outage's own recovery
                        // marks the registration suspect, which re-opens this
                        // gate.
                        is GeofenceObservation.RepairPoke ->
                            if (registrationDegradation.get() != null) repairFence()
                        // The app saw a location grant land (SPEC.md §8.2):
                        // the permission dialog's result, or Settings and
                        // back. Android broadcasts no permission change, so
                        // this is the only prompt that reaches a monitor
                        // holding a grant-shaped latch — otherwise the
                        // repair waited for the backstop or the 15-minute
                        // recheck, with §6.6 grace shut the whole way, and a
                        // user who left inside that window stayed quiet to
                        // the cap (Codex, PR #150). Each anchor shape learns
                        // its grant is back the way it learned it was gone:
                        // a fence from a registration the platform accepts,
                        // a Wi-Fi-only anchor from the permission read. The
                        // fence half keeps `RepairPoke`'s gate and reason.
                        // Driven from [grantPokeSteps] so which shape asks
                        // what is a value a test can pin, as the other two
                        // step lists are.
                        is GeofenceObservation.GrantPoke ->
                            grantPokeSteps(anchor, registrationDegradation.get()).forEach { step ->
                                when (step) {
                                    GrantPokeStep.ReconcileGrants -> reconcileGrants()
                                    GrantPokeStep.RepairFence -> repairFence()
                                }
                            }
                        // The grace alarm's firing, as a signal like any
                        // other: the engine re-checks the deadline against
                        // its own state, so a stale firing is a no-op.
                        //
                        // Stamped with the *handling* time, not the receiver's
                        // fire time (Codex, PR #106). A firing held in the
                        // bridge's mailbox while a dead process restores can be
                        // delivered here tens of seconds after it fired, and
                        // the confirmation deferral extends the deadline from
                        // this signal's own timestamp — off the stale fire
                        // time, the "30-second" window could already be in the
                        // past, giving the async association callback no room
                        // to confirm a return before the re-armed alarm ends
                        // the snooze. The due check is unaffected: handling
                        // time is never earlier than the fire time, so a
                        // genuinely due deadline still resolves.
                        // The grant is asked here too, and **before** the
                        // deadline is spent — the same order, and for the
                        // same reason, as the Wi-Fi loss handler. A grant
                        // revoked *after* grace was already armed reaches
                        // neither of the other two paths: the tracker only
                        // reports transitions, so a redacted read arriving
                        // while it already holds *not associated* is a repeat
                        // and says nothing, and the fifteen-minute recheck
                        // sits well past a five-minute deadline. The case
                        // that leaves is narrow and real — the user is at the
                        // anchor, their Wi-Fi flaps so a loss arms grace, and
                        // they revoke location inside those five minutes, so
                        // the re-association that would have canceled grace
                        // can only be read redacted and is heard as a repeat.
                        // The snooze then ends on a user who never left
                        // (Codex, PR #157, ninth pass).
                        //
                        // Asking first is what makes it work: a latch
                        // delivers `LocationAccessLost`, which clears the
                        // deadline and cancels the alarm on the same pass, so
                        // the elapsed signal below finds nothing due. Asking
                        // after would end the snooze and then say the grant
                        // was gone.
                        is GeofenceObservation.GraceElapsed -> {
                            latchIfGrantGone()
                            deliver(PresenceSignal.GraceElapsed(readElapsedRealtimeMs()))
                        }
                        // The alarm's own prompt, not its payload — the
                        // decision lives in `CapabilityLossStore`, keyed to
                        // *this* monitor's own `armedAtEpochMs`, so a firing
                        // left over from an already-superseded snooze finds
                        // nothing and is a no-op instead of misapplying an
                        // old ending to a new snooze.
                        is GeofenceObservation.CapabilityLoss -> {
                            val cause = CapabilityLossStore.load(appContext, armedAtEpochMs)
                            if (cause != null) {
                                SnoozeDebugLog.event("capability-loss alarm delivered; ending per durable record")
                                // Re-armed, not just replayed (Codex, PR #95,
                                // fourth pass): the alarm that just fired is
                                // now spent, and this `trySend` is only an
                                // in-process send — the exact kind of
                                // unacknowledged handoff this whole item
                                // exists to close. A process death before the
                                // collector consumes it would otherwise leave
                                // the record still on disk with nothing left
                                // scheduled to prompt another restore, same as
                                // the restore-time replay above.
                                CapabilityLossAlarm.arm(appContext)
                                trySend(PresenceUpdate(event = PresenceEvent.CapabilityLost(cause), degradation = null))
                            } else {
                                // A stale firing — the record it named is
                                // already gone, whether from a confirmed
                                // `stop()` or a superseded generation (Codex,
                                // PR #95) — retired explicitly: this
                                // observation is retained like an exit or a
                                // due grace deadline, but unlike either it
                                // never reaches `deliver`, so `settlesHeldExit`
                                // never runs for it. Left alone, the slot
                                // would keep replaying to every later attach
                                // and waking every future teardown for a
                                // decision that no longer exists.
                                //
                                // `settleCapabilityLoss`, not `settleExit`
                                // (Codex, PR #95, third pass): `rank()` can
                                // already have kept a genuine, still-
                                // unconfirmed exit or due grace in the slot
                                // over this stale prompt, and `settleExit`
                                // clears whatever is retained regardless of
                                // what it is — discarding that real evidence
                                // for a prompt that turned out to mean
                                // nothing.
                                SnoozeDebugLog.event("capability-loss alarm fired with nothing recorded; ignored")
                                GeofenceSignalBridge.settleCapabilityLoss()
                            }
                        }
                    }
                }
                if (anchor.hasUsableFix) {
                    registerFence()
                    // One resting probe per start (SPEC.md §6.10): a monitor
                    // the backstop restores hands the engine one reading, so
                    // a departure the geofence never reported gets tested
                    // rather than waited out — and the probe re-checks the
                    // location grants on the way, so a mid-snooze revocation
                    // fails open here instead of at the cap — and it defers
                    // to D4's suppressor: on the anchor's Wi-Fi the answer is
                    // already free, and registration still re-checks the
                    // grants on every restore either way.
                    sanityProbe()
                } else {
                    // A Wi-Fi-only anchor has nothing to fence: the Wi-Fi
                    // watch (constructed above, before the bridge attached)
                    // is its whole coverage, and the engine's grace period is
                    // what resolves a loss it cannot confirm. Order between
                    // this and the watch's own initial report is safe either
                    // way: `Presence.useless` — what this signal reaches — is
                    // a declared no-op for an anchor with no usable fix
                    // (Codex raised a same-millisecond staleness race between
                    // the two on PR #77; disproved by a `PresenceTest` case
                    // added for the claim — the guard means this can never
                    // advance `latestEvidenceMs` and so can never make
                    // anything else look stale).
                    SnoozeDebugLog.event("no usable fix on the anchor; geofence not registered")
                    // And so nothing durable is watching a *Wi-Fi-only*
                    // snooze: the Wi-Fi watch above dies with the service,
                    // which Android stops routinely, and there is no fence to
                    // take over. The recheck alarm is what the fence would
                    // have been — registered with the platform, outliving the
                    // process, and restoring a reader on its own schedule.
                    //
                    // Gated on an actual Wi-Fi anchor, not merely the
                    // missing fix (Codex, PR #105; see `needsWifiRecheck`):
                    // this same no-fix branch also covers a duration-only
                    // anchor with no SSID, where no `PlatformWifiWatch` was
                    // built and nothing could ever be re-read.
                    WifiRecheckAlarm.reconcile(appContext, needsWifiRecheck(anchor))
                    deliver(PresenceSignal.FixUnavailable(readElapsedRealtimeMs()))
                }
            }
        }

        // What stop() closes is the *flow*, not the resources directly:
        // completing the channel is what runs awaitClose, so the collector
        // ends and the release below happens exactly once whichever side —
        // stop() or the collector's own cancellation — got there first
        // (flagged by Codex on PR #70; the first version released resources
        // but left the collector suspended past the snooze).
        awaitClose {
            // The teardown that ends this snooze's live watching, said out
            // loud (AGENTS.md, principle 2). Without it the debug log's
            // account of a Wi-Fi-only snooze simply stopped — the last entry
            // was the arm, minutes of silence followed, and nothing recorded
            // that the Wi-Fi watch had closed with the service or when. That
            // silence is the log failing to explain the gap it was there to
            // explain.
            SnoozeDebugLog.event("presence watch closed; a wake-up restores it")
            active.compareAndSet(handle, null)
            checkingFixes?.close()
            // In-process like the burst, and cheap to lose: a restored
            // monitor re-arms from the duty it recomputes, and a resting
            // phone with no live trigger is exactly the case the §6.10
            // backstop already covers.
            motionTrigger?.close()
            // In-process like the burst and the trigger, and cheap to lose
            // for the same reason: a restored monitor re-arms it from the
            // level it recomputes, and an outage nobody is watching still
            // heals at the backstop's cadence.
            locationModeWatch.close()
            bridge?.close()
            // In-process like the burst: a restarted service re-registers its
            // own watch. The grace *alarm* is deliberately not canceled here
            // — like the fence, its deadline must outlive a routine service
            // destroy, or a Wi-Fi-only snooze whose grace was running would
            // never end; a stale firing is a no-op by the engine's check.
            wifiWatch.getAndSet(null)?.close()
            // Deliberately NOT the fence. The registration is the one durable
            // thing this monitor owns, and durability is its entire point:
            // Android stops an ordinary background service routinely, the
            // collector dies with it, and a fence removed here would mean no
            // departure could ever wake the app again — DND until the cap,
            // silently (flagged by Codex on PR #73). The fence outlives the
            // process by design (SPEC.md §3, §6.10); only [stop] — the snooze
            // actually ending — takes it down. A restarted service re-collects
            // and re-registers under the same id, which replaces in place.
        }
        }
    }

    override fun stop() {
        // Idempotent by construction: closing an already-completed channel is
        // a no-op. The generation bump and the close share the lifecycle lock
        // so a cold producer cannot observe the old generation after this
        // stop has decided (see [stopGeneration]).
        //
        // The fence comes down here, and only here: stop() means the snooze
        // is over, and a fence outliving its snooze is a wake-up nobody
        // wants. Unconditional — no in-process ownership gate — because the
        // registration outlives the process while any token would not: a
        // snooze ended from a cold process must still take down the fence a
        // dead process registered (flagged by Codex on PR #73), and removing
        // an id with no registration behind it is a no-op.
        synchronized(registrationLock) {
            stopGeneration.incrementAndGet()
            // Retired before the close: the snooze is over, so whatever exit
            // the bridge still holds is settled by definition, and clearing it
            // first is what keeps the detach below from waking a service to
            // check a departure that no longer has a snooze to end.
            GeofenceSignalBridge.settleExit()
            // The grace alarm goes the same way and for the same reason: a
            // deadline outliving its snooze would fire over whatever the user
            // arms next. Only here — a routine service destroy must leave it
            // standing, or a running grace would never come due.
            GraceAlarm.reconcile(appContext, null)
            // Retired before the clear, under the same lock every write
            // checks (Codex, PR #91, eighth pass): a `deliver` call from
            // this generation still in flight — dispatched before this stop
            // but not yet at its own persistence check — must find no
            // generation it can match and write nothing here, or its stale
            // write could land right after this clear and leave a leftover
            // entry the identity check alone would otherwise have to catch.
            // `-1L` is never a real `startGeneration` (generations begin at
            // `stopGeneration`'s initial `0`), so this unconditionally
            // rejects every in-flight call regardless of which generation.
            synchronized(persistenceLock) { persistenceGeneration = -1L }
            // A failed clear is a leftover entry, not a lost one — logged
            // rather than escalated, because the identity check `load`
            // already does (Codex, PR #91) is exactly what makes a stale
            // survivor harmless: the next arm's own `armedAtEpochMs` will not
            // match it.
            if (!GraceDeadlineStore.clear(appContext)) {
                SnoozeDebugLog.warning("clearing the grace deadline failed; a stale entry may remain on disk")
            }
            // The capability-loss record and its alarm go the same way, for
            // the same reason: a decision or a wake-up outliving the snooze
            // it belonged to would misfire over whatever the user arms next
            // — the `persistenceGeneration` retirement above already stops
            // any further write, this is what erases what already landed.
            // The recheck goes the same way as the fence and the grace
            // alarm, and only here: a routine service destroy must leave it
            // standing — it is the one thing that will bring the watch back
            // — while a snooze that has actually ended must not keep waking
            // the phone every quarter hour.
            WifiRecheckAlarm.reconcile(appContext, needed = false)
            CapabilityLossAlarm.cancel(appContext)
            if (!CapabilityLossStore.clear(appContext)) {
                SnoozeDebugLog.warning("clearing the capability-loss record failed; a stale entry may remain on disk")
            }
            active.getAndSet(null)?.close()
            // Contained: removal is cleanup, and the fence dies with the
            // app's registrations anyway if this fails. A leftover fence is
            // inert either way — a stale crossing wakes a restore that finds
            // no record — so the response to a failure is the diagnostic,
            // not a retry chain.
            runCatching {
                LocationServices.getGeofencingClient(appContext)
                    .removeGeofences(listOf(GEOFENCE_ID))
                    // The call only enqueues; a rejection arrives on the task,
                    // which the catch below never sees (Codex, PR #73).
                    .addOnFailureListener {
                        SnoozeDebugLog.warning(
                            "geofence removal rejected; it is inert without a snooze",
                            it,
                        )
                    }
            }.onFailure {
                SnoozeDebugLog.failure(it, "geofence removal failed; it is inert without a snooze")
            }
        }
    }

    /**
     * A fenced anchor is fully watched: the geofence detects the departure
     * and the checking burst confirms it through §6.6. `WIFI_ONLY` is in the
     * set exactly when the anchor has an SSID, because the Wi-Fi watch now
     * backs it (D4): association suppresses location work, loss escalates,
     * and — when location cannot confirm — the grace alarm is what ends an
     * unverifiable snooze. A fenced anchor that loses location therefore
     * degrades to a *watched* Wi-Fi mode rather than to a timer, and an
     * SSID-only anchor is a real watch at last, not a labeled timer.
     */
    override fun supportedModes(anchor: Anchor): Set<TrackingMode> = buildSet {
        if (anchor.hasUsableFix) add(TrackingMode.FULL)
        if (anchor.ssid != null) add(TrackingMode.WIFI_ONLY)
        add(TrackingMode.DURATION_ONLY)
    }

    companion object {
        /** The one fence this app ever registers (one snooze, one place). */
        internal const val GEOFENCE_ID = "snoozemo-anchor"

        /**
         * Which of the two platform slots the controller is told about.
         *
         * Services normally wins: a refused registration says nothing about
         * whether the subsystem works, while a services outage indicts it
         * outright. **A missing grant is the one exception** (Codex, PR #149).
         * The services slot is refuted only by a delivered fix, and once the
         * grant is gone no fix can ever arrive — so a latched services cause
         * would outlive its own refutation and keep naming the wrong remedy
         * ("turn location on" over "grant the permission") for the rest of the
         * snooze. Worse than the label: `LOCATION_SERVICES_OFF` is not a cause
         * the controller drops to duration-only for, so an anchor with an SSID
         * would sit in `WIFI_ONLY` with no grant to read that SSID with — the
         * exact claim this PR's `modeFor` guard exists to stop.
         *
         * The services slot is kept rather than cleared: if the grant returns
         * while services are still off, the re-registration answers
         * `GEOFENCE_NOT_AVAILABLE` and re-latches it, so overriding is as
         * self-healing as clearing and loses nothing meanwhile.
         *
         * Pulled out of [start]'s local scope purely so it can be asserted —
         * the same reason [settlesHeldExit] is up here.
         */
        internal fun platformLevelOf(
            registration: DegradationCause?,
            services: DegradationCause?,
        ): DegradationCause? = when (registration) {
            DegradationCause.LOCATION_PERMISSION_GONE,
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
            -> registration
            else -> services ?: registration
        }

        /**
         * Whether an update leaving the checking duty may retire the bridge's
         * held exit. A terminal *ending* event may not: the update is only
         * queued toward the collector at this point, so settling here would
         * leave a teardown racing the collector with no held exit to wake a
         * successor for — a departure confirmed and then lost (Codex, PR
         * #73). The end that is actually acted on settles the slot through
         * [stop]; a refusal keeps it held, and the next attach re-runs the
         * check.
         */
        internal fun settlesHeldExit(duty: LocationDuty, event: PresenceEvent?): Boolean =
            duty != LocationDuty.ACTIVE &&
                event != PresenceEvent.Departed &&
                event !is PresenceEvent.CapabilityLost

        /**
         * Whether this snooze needs the durable Wi-Fi recheck alarm
         * ([WifiRecheckAlarm]) — the platform-registered stand-in for the
         * geofence a no-fix anchor cannot have.
         *
         * Both conditions, not the missing fix alone (Codex, PR #105). The
         * no-fix branch that skips fence registration also covers a
         * duration-only anchor with no SSID, where no `PlatformWifiWatch` was
         * built and there is no association to re-read: arming there would
         * wake and restore the service every period for a snooze with nothing
         * to check, each firing re-arming the next — a standing drain with no
         * departure it could detect. The same `ssid != null` that gates the
         * watch (D4) and the grace period (`Presence.graceFrom`) gates its
         * alarm.
         */
        internal fun needsWifiRecheck(anchor: Anchor): Boolean =
            !anchor.hasUsableFix && anchor.ssid != null

        /**
         * Whether this snooze's tracking depends on a location grant that can
         * be lost mid-snooze — so the monitor watches the grant directly.
         *
         * Both anchor shapes, for the same reason and by different routes. An
         * anchor with a fix learns of a lost grant when `addGeofences` is
         * refused, and learns it is back when one succeeds. A Wi-Fi-only
         * anchor never calls either, so it asks the permission directly at
         * restore and again on every `WifiRecheck`; that is the whole of the
         * difference.
         *
         * An anchor with neither a fix nor an SSID is excluded, because
         * nothing about it reads location at all: it is duration-only already,
         * there is no signal a revoked grant could corrupt, and no recheck
         * alarm is armed to re-ask (see [needsWifiRecheck]).
         */
        /**
         * What re-asking the location grant should do to the registration slot,
         * as a pure decision so it can be asserted (the same reason
         * [platformLevelOf] is up here).
         *
         * [grantsHeld] is the live read of both grants; [hasFineLocation]
         * splits a loss into the two states the card names, exactly as a
         * refused registration is split.
         *
         * **A restoration needs the grant *and* the services switch.** Wi-Fi
         * identifiers are gated on both: with system location off, a
         * `NetworkCapabilities` read comes back redacted however healthy the
         * two permissions are. Declaring a restoration there would clear the
         * engine's suppressor and hand the rebuilt watch a redacted read,
         * which D7 makes a loss — grace arms and the snooze ends on a user
         * who never left, the exact failure this whole change exists to stop
         * (Codex, PR #157, seventh pass).
         *
         * [servicesOn] false includes *unreadable*, deliberately. Staying
         * latched costs a snooze that runs duration-only and says so on the
         * card, bounded by the mandatory cap and re-asked at the next
         * recheck; restoring on a guess costs the snooze. The recovery watch
         * pokes a repair the moment location comes back, so the latch is not
         * waiting on the fifteen-minute cadence alone.
         *
         * **Clears any cause that withheld location data, and only because
         * it reads the switch as well as the grants.** The registration-success
         * path clears the slot whatever is in it, since a registration the
         * platform accepted is proof the whole subsystem works. This proves
         * less, but not as little as it once did: it reads [servicesOn]
         * alongside the two grants, so a restoration declared here is backed
         * by the same three facts an SSID read needs. That is what makes
         * [DegradationCause.blocksLocationReads] the right predicate rather
         * than `isGrantLoss` (Codex, PR #165) — under the narrower one a
         * latched [DegradationCause.LOCATION_SERVICES_OFF] could never be
         * lifted at all on a fenceless anchor, which has no registration
         * success to lift it by. What still must not happen is a
         * restoration on a *guess*, and it cannot: [servicesOn] false
         * includes unreadable, so an unreadable switch stays latched.
         *
         * Answers [Nothing] for a loss already latched under the same cause,
         * so a steady reported state is not re-delivered four times an hour;
         * the cause is compared rather than mere presence, so a grant that
         * moves from one shape of loss to the other still updates.
         */
        /**
         * What to call a **redacted Wi-Fi read** on the card.
         *
         * The read itself is the detection, and it is cause-agnostic: the
         * platform withheld the network's name, which every route to that
         * outcome has in common and which no permission probe has to be
         * consulted to establish. This only picks the *label*, and being
         * total is the whole point — a redaction it could not explain used
         * to fall through the [grantRecheck] probe and latch nothing, which
         * is how location switched off system-wide still ended a Wi-Fi-only
         * snooze five minutes later (`TODO.md`, deferred from PR #157).
         *
         * Ordered by what the user would have to do about it, most specific
         * first, and the final branch is deliberately not a fourth question:
         * every gate reads healthy and the name came back redacted anyway,
         * so the honest label is the one that says location cannot be read
         * in the background — which is what just happened, whatever the
         * platform's reason for it.
         */
        internal fun redactionCause(
            hasFineLocation: Boolean,
            grantsHeld: Boolean,
            servicesOn: Boolean,
        ): DegradationCause = when {
            !hasFineLocation -> DegradationCause.LOCATION_PERMISSION_GONE
            !grantsHeld -> DegradationCause.NO_LOCATION_IN_BACKGROUND
            !servicesOn -> DegradationCause.LOCATION_SERVICES_OFF
            else -> DegradationCause.NO_LOCATION_IN_BACKGROUND
        }

        internal fun grantRecheck(
            latched: DegradationCause?,
            grantsHeld: Boolean,
            hasFineLocation: Boolean,
            servicesOn: Boolean,
        ): GrantRecheck = when {
            grantsHeld ->
                // `blocksLocationReads`, not `isGrantLoss` (Codex, PR #165).
                // A redacted read now latches `LOCATION_SERVICES_OFF` too,
                // and under the narrower predicate nothing could ever lift
                // it: a fenceless anchor has no registration success to
                // declare a restoration for it, so the engine's
                // `locationAccessLost` stayed set for the life of the
                // snooze, no §6.6 grace could arm, and a real departure ran
                // silently to the cap — principle 1's failure, introduced by
                // latching a non-grant cause into a grant-shaped restore.
                //
                // Safe to widen precisely because `servicesOn` is already a
                // condition of this branch: a restoration here needs the two
                // grants *and* the switch, which is exactly what an SSID
                // read needs, so a services outage is only ever declared
                // over once it is actually over.
                if (latched?.blocksLocationReads == true && servicesOn) {
                    GrantRecheck.Restore
                } else {
                    GrantRecheck.Nothing
                }
            else -> {
                val cause = if (hasFineLocation) {
                    DegradationCause.NO_LOCATION_IN_BACKGROUND
                } else {
                    DegradationCause.LOCATION_PERMISSION_GONE
                }
                if (latched == cause) GrantRecheck.Nothing else GrantRecheck.Latch(cause)
            }
        }

        /**
         * Whether a registration the platform has just accepted refutes what
         * is sitting in the registration slot.
         *
         * A value for the same reason [grantRecheck] is one: the answer turns
         * on which *kind* of outage was latched, and the two kinds have
         * different proofs.
         *
         * **Acceptance proves the grants, and only the grants.** Geofencing
         * needs `ACCESS_BACKGROUND_LOCATION` outright on API 29+, so a fence
         * the platform took is proof both grants are held — which is why this
         * path clears a grant loss at all. It says nothing about the system
         * location switch, as this file has noted since PR #75: `addGeofences`
         * will accept a fence the platform still cannot monitor.
         *
         * That gap was harmless while a grant loss was the only cause that
         * reached the engine. PR #165 let `LOCATION_SERVICES_OFF` reach it
         * too, and then a periodic repair accepted *during* an outage would
         * clear the refusal, withdraw `locationAccessLost`, and — because the
         * non-null slot is exactly what arms [LocationModeWatch] — tear down
         * the recovery watch that was the only thing left able to lift the
         * latch. A fix-only fenced anchor has no Wi-Fi callback to re-latch
         * it, so the card returned to `FULL` over a fence nothing could
         * monitor and a snooze that would run to its cap (Codex, PR #165).
         *
         * So a cause that withholds location reads needs the switch to answer
         * as well. Holding the refusal is the safe direction here: the slot
         * stays non-null, the mode watch stays armed, and its recovery runs
         * [grantRecheck], which restores on the two grants *and* the switch —
         * the same three facts a location read needs.
         *
         * **Not a gate on which cause is in the slot.** PR #150's regression
         * was skipping the restoration because a non-grant cause happened to
         * be latched; this asks for more proof, never for a different cause,
         * and a grant restored during an outage is still restored — by the
         * mode watch, the moment the outage ends.
         */
        internal fun registrationRefutes(
            latched: DegradationCause?,
            servicesOn: Boolean,
        ): RegistrationOutcome = when {
            latched == null -> RegistrationOutcome.Nothing
            !latched.blocksLocationReads || servicesOn -> RegistrationOutcome.Refuted
            // The grants are proven back and the switch is still off, so the
            // outage is real but the *label* is stale (Codex, PR #165, seventh
            // pass). Leaving it reads `Fix the location permission` at a user
            // who has just fixed it, for the rest of the outage — the card
            // asking for the one thing that is no longer wrong. The latch and
            // the watch both stay; only the name changes, to the blocker that
            // is actually left.
            latched.isGrantLoss -> RegistrationOutcome.Reclassify(DegradationCause.LOCATION_SERVICES_OFF)
            else -> RegistrationOutcome.Nothing
        }

        /**
         * Publishes [fresh] into [slot], and hands it straight back if the
         * flow closed while it was being built. Returns whether it is live.
         *
         * A watch registers its platform callback as it is constructed, so
         * there is always a gap between that registration and this
         * publication. A teardown completing inside it closes whatever the
         * slot held and clears it, never seeing [fresh] — which then stays
         * registered with nothing holding a reference to unregister it
         * (Codex, PR #157, eighth pass).
         *
         * The compare-and-set is what stops the repair from becoming a
         * double close: a teardown racing this line takes [fresh] out of the
         * slot itself, and only one of the two can win it. Losing means the
         * teardown already owns it and will close it.
         *
         * [flowClosed] is a lambda because the gap is the whole subject: a
         * test drives the interleaving by tearing the slot down from inside
         * it, which is where the real window is.
         */
        internal fun publishWatch(
            slot: java.util.concurrent.atomic.AtomicReference<AutoCloseable?>,
            fresh: AutoCloseable,
            flowClosed: () -> Boolean,
        ): Boolean {
            slot.getAndSet(fresh)?.close()
            if (flowClosed() && slot.compareAndSet(fresh, null)) {
                fresh.close()
                return false
            }
            return true
        }

        /**
         * Asks the grant and latches a loss **only if nothing moved the slot
         * while it was being asked**.
         *
         * The read is not free — two `checkSelfPermission` lookups — and a
         * restore can land inside it, on the recheck's own thread or the
         * registration callback's. It clears the slot and declares
         * [PresenceSignal.LocationAccessRestored]; an unconditional report
         * afterwards would re-latch a loss the grant no longer supports, and
         * nothing would refute it for fifteen minutes. That matters more than
         * a wrong mode line, because only `LocationAccessRestored` clears the
         * engine's own `locationAccessLost` — not the association the rebuilt
         * watch is about to report (`Presence.kt`) — so §6.6 grace would stay
         * shut and a real departure would run to the cap. A quiet phone: the
         * failure this whole path exists to prevent, one race deeper (Codex,
         * PR #157).
         *
         * So the decision names the value it was made against, exactly as the
         * restore branch does. **Losing means doing nothing**, and every way
         * of losing leaves grace *armed* rather than shut — the safe direction
         * (principle 1). If a restore won, the grant is genuinely held and a
         * Wi-Fi loss is a real departure. If another loss won, the slot
         * already holds one and the engine has already been told.
         *
         * The permission reads are lambdas because that gap is the whole
         * subject: a test drives the interleaving by mutating [slot] from
         * inside [grantsHeld], which is where the real window is.
         *
         * Not airtight, and deliberately not claimed to be: [report] sets the
         * slot again on its way to delivering, so a restore landing in *that*
         * span still loses. The slot and the engine's latch are two pieces of
         * state with no single atomic covering both; this closes the wide
         * window (two IPCs) and leaves an instruction-scale one, which is the
         * same residual the restore branch carries.
         */
        internal fun latchGrantLoss(
            slot: java.util.concurrent.atomic.AtomicReference<DegradationCause?>,
            grantsHeld: () -> Boolean,
            hasFineLocation: () -> Boolean,
            servicesOn: () -> Boolean,
            report: (DegradationCause) -> Unit,
        ): Boolean {
            val latched = slot.get()
            val next = grantRecheck(latched, grantsHeld(), hasFineLocation(), servicesOn())
            if (next !is GrantRecheck.Latch) return false
            if (!slot.compareAndSet(latched, next.cause)) return false
            report(next.cause)
            return true
        }

        /**
         * What restoring the grant has to do, in order, as a value so it can be
         * asserted (the same reason [platformLevelOf] and [grantRecheck] are up
         * here).
         *
         * **The rebuild is the step worth pinning.** A grant coming back does
         * not repair what its absence wrote: every redacted callback stored the
         * placeholder in the watch's per-network SSID map and left its tracker
         * holding *not associated*, and a restored grant dispatches no callback
         * of its own, so both survive it. The tracker would then read a real
         * departure as a repeat of the loss it already reported and emit
         * nothing, leaving a Wi-Fi-only snooze quiet to its cap — the direction
         * principle 1 refuses. Re-registering is what fixes it: a fresh
         * registration dispatches the current networks, now unredacted, into a
         * tracker whose first report is a transition by definition.
         *
         * **And it comes after the restoration, not before.** The new watch's
         * seed read can report a loss, and delivering that into an engine still
         * holding `locationAccessLost` would spend the signal with grace still
         * shut — a user who really had left, missed.
         *
         * Only for an anchor with an SSID: there is no watch to rebuild
         * otherwise, and `getAndSet` on an empty slot would install one for a
         * snooze that never had it. **An SSID beside a fix counts** (Codex,
         * PR #185): a fenced anchor carries the same watch as D4's suppressor,
         * a revocation poisons it the same way, and the registration that
         * proves its grant back dispatches no callback either — so the
         * registration-success path runs this list too, not only the recheck.
         */
        internal fun restoreSteps(anchor: Anchor): List<RestoreStep> = buildList {
            add(RestoreStep.DeclareRestored)
            add(RestoreStep.ResumeChecking)
            if (anchor.ssid != null) add(RestoreStep.RebuildWifiWatch)
            add(RestoreStep.RestateLevel)
        }

        /**
         * What switching system location back on should poke, in order.
         *
         * A value for the same reason [restoreSteps] is one: the grant re-ask
         * is needed by one anchor shape only, so deleting it leaves every
         * other test on this path green while a Wi-Fi-only snooze sits latched
         * until the next 15-minute recheck (Codex, PR #157).
         *
         * **The grant first.** The other three ask the platform for evidence,
         * and the engine's `locationAccessLost` latch is what decides whether
         * evidence can act — so it is lifted before anything that might arrive
         * is read against a suppressor this same callback is about to remove.
         * The same ordering, and the same reason, as `DeclareRestored`.
         *
         * Gated on [needsWifiRecheck], the same predicate [reconcileGrants]
         * returns on — one predicate asked twice, not two copies of a rule.
         * That call keeps its own guard because the recheck alarm calls it too.
         */
        internal fun recoverySteps(anchor: Anchor): List<RecoveryStep> = buildList {
            if (needsWifiRecheck(anchor)) add(RecoveryStep.ReconcileGrants)
            add(RecoveryStep.RepairFence)
            add(RecoveryStep.RetryFixes)
            add(RecoveryStep.SanityProbe)
        }

        /**
         * What a location grant landing in the app should re-ask, in order
         * (SPEC.md §8.2) — a value for the reason [recoverySteps] is one.
         *
         * **The grant read first**, as in [recoverySteps] and for its reason:
         * it lifts the engine's `locationAccessLost` latch directly, where the
         * fence repair only asks the platform for a registration whose answer
         * arrives later. Only the shape a registration cannot answer for
         * takes it — one predicate, [needsWifiRecheck], asked here and again
         * inside [reconcileGrants]'s own guard.
         *
         * **The fence repair keeps `RepairPoke`'s gate.** A latched slot is
         * the one set of failures a re-registration can refute; with nothing
         * latched the monitor already holds the grant as present, and
         * re-registering a healthy fence into a possible services outage is
         * IPC for nothing that risks a mis-mapped refusal (Codex, PR #75).
         * And only for an anchor with a fence to repair: `registerFence`
         * declines on its own without a fix, but the list says so rather
         * than relying on that.
         */
        internal fun grantPokeSteps(
            anchor: Anchor,
            latched: DegradationCause?,
        ): List<GrantPokeStep> = buildList {
            if (needsWifiRecheck(anchor)) add(GrantPokeStep.ReconcileGrants)
            if (latched != null && anchor.hasUsableFix) add(GrantPokeStep.RepairFence)
        }

        internal fun watchesGrants(anchor: Anchor): Boolean =
            anchor.hasUsableFix || needsWifiRecheck(anchor)

        /** Serializes claim-and-register with owner-checked removal. */
        private val registrationLock = Any()

        private const val REQUEST_TRANSITIONS = 1

        /**
         * Mutable, as the Geofencing API requires — Play Services fills the
         * transition data in — and explicit at our own receiver, so nothing
         * else can send it something that parses as an exit.
         */
        private fun transitionIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_TRANSITIONS,
                Intent(context, GeofenceTransitionReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/**
 * What re-asking the location grant means for the registration slot
 * ([GeofencePresenceMonitor.grantRecheck]).
 *
 * A type rather than a nullable cause because "do nothing" and "clear what is
 * there" are different instructions that a null would collapse into one — and
 * collapsing them is how a latch either outlives its refutation or is dropped
 * while it still holds.
 */
/**
 * What a registration the platform has just accepted does to the registration
 * slot. A value for the same reason [GrantRecheck] is one: three outcomes, and
 * the wrong one is invisible on the card.
 */
internal sealed interface RegistrationOutcome {
    /** The refusal is refuted — clear the slot and declare restored. */
    data object Refuted : RegistrationOutcome

    /**
     * The outage stands but under a different name: the grants are proven held
     * and the switch is still off. The latch and the recovery watch both stay;
     * the card stops naming a permission that is no longer missing.
     */
    data class Reclassify(val cause: DegradationCause) : RegistrationOutcome

    /** Nothing latched, or nothing this proof can say anything about. */
    data object Nothing : RegistrationOutcome
}

internal sealed interface GrantRecheck {
    /** The slot already says what the grant says. */
    data object Nothing : GrantRecheck

    /** Both grants are held again, and a grant cause is latched. */
    data object Restore : GrantRecheck

    /** A grant is missing, and this is the state to name on the card. */
    data class Latch(val cause: DegradationCause) : GrantRecheck
}

/**
 * One step of the restoration a returning location grant triggers
 * ([GeofencePresenceMonitor.restoreSteps]).
 *
 * An ordered list of these rather than four statements in a row, because two of
 * the properties that matter — that the watch is rebuilt at all, and that it
 * happens after the restoration is declared — are otherwise asserted by nothing
 * and regress silently.
 */
/**
 * What a location-services recovery pokes
 * ([GeofencePresenceMonitor.recoverySteps]).
 *
 * An enum so the `when` executing it is exhaustive, and so the sequence is a
 * value a test can assert rather than a shape read off the code.
 */
internal enum class RecoveryStep {
    /** Re-ask the location grant, for the anchor shape no registration answers. */
    ReconcileGrants,

    /** Re-register the fence the outage may have left unmonitored. */
    RepairFence,

    /** Ask again on a departure check the outage began during. */
    RetryFixes,

    /** Ask once for a resting snooze; a declared no-op mid-check. */
    SanityProbe,
}

internal enum class GrantPokeStep {
    /** Re-ask the location grant, for the anchor shape no registration answers. */
    ReconcileGrants,

    /** Re-register a fence whose registration slot holds a refusal. */
    RepairFence,
}

internal enum class RestoreStep {
    /** Lift the engine's `locationAccessLost` latch; this is what re-opens grace. */
    DeclareRestored,

    /** Un-suspend the process-wide fix requester. */
    ResumeChecking,

    /** Re-register the Wi-Fi watch, discarding what the revocation wrote into it. */
    RebuildWifiWatch,

    /** Restate the feed's level, so the card stops saying the mode is degraded. */
    RestateLevel,
}
