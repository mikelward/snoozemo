package app.snoozemo.presence.geofence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.snoozemo.core.Anchor
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.PresenceEvent
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.core.PresenceSignal
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.TrackingMode
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

    override fun start(anchor: Anchor, sinceElapsedRealtimeMs: Long): Flow<PresenceUpdate> {
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

        // The caller's arm-moment seed, never this monitor's own "now": a
        // restored monitor's now post-dates the held exit the restart was
        // woken to deliver, and seeding with it dropped that exit as stale —
        // the confirmation never ran and the phone stayed quiet until the
        // cap (flagged by Codex on PR #73).
        val feed = PresenceFeed(anchor, seedElapsedRealtimeMs = sinceElapsedRealtimeMs)

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

        fun platformLevel(): DegradationCause? =
            servicesDegradation.get() ?: registrationDegradation.get()

        // Assigned once `repairFence` exists below; deliver() runs only after
        // setup, so the placeholder is never the one invoked.
        var repairOnRecovery: () -> Unit = {}

        fun send(update: PresenceUpdate) {
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
                ),
            )
        }

        // The confirming fixes of SPEC.md §6.10: one-shots while the engine is
        // checking, started and stopped by the duty the engine itself reports
        // (§6.7). Declared before `deliver` because delivery is what drives it.
        var checkingFixes: CheckingFixes? = null

        // The feed is a plain value and its callers arrive from more than one
        // thread — the bridge and the fixes on main, the setup body on the
        // collector's — so one lock serializes the accept-and-read-duty step.
        // Never held across anything slow: `accept` is pure arithmetic.
        val feedLock = Any()

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
            synchronized(feedLock) {
                update = feed.accept(signal)
                duty = feed.duty
                graceDeadlineMs = feed.graceDeadlineMs
            }
            send(update)
            // The engine can set a grace deadline but cannot wake a phone;
            // the alarm is what makes the §6.6 promise real. Reconciled per
            // signal like the burst below — armed while a deadline stands,
            // canceled the moment presence evidence clears it.
            GraceAlarm.reconcile(appContext, graceDeadlineMs)
            // Reconciled on every signal rather than on transitions, because
            // both calls are idempotent and "did the transition get noticed"
            // is exactly the kind of question idempotence deletes.
            // Pause, not close: the duty leaving ACTIVE is a state the engine
            // can re-enter, and only teardown (awaitClose) may end the burst
            // for good.
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
                    send(PresenceUpdate(event = null, degradation = null))
                }
                is GeofenceRegistrationFailure.Fatal -> trySend(
                    PresenceUpdate(
                        event = PresenceEvent.CapabilityLost(failure.cause),
                        degradation = null,
                    ),
                )
            }
        }
        checkingFixes = CheckingFixes(
            AndroidBurstScheduler(),
            PlatformFixRequester(appContext),
            readElapsedRealtimeMs,
            ::deliver,
            onPermissionLost = {
                // The recoverable/fatal split again, at the burst's boundary:
                // a revoked grant mid-check ends the snooze, classified
                // through the same tested mapping registration uses (flagged
                // by Codex on PR #72 when it reported mere degradation).
                reportRegistration(GeofenceRegistrationFailure.fromSecurityException())
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
        var wifiWatch: AutoCloseable? = null

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
                        SnoozeDebugLog.event("geofence registered; radius=${anchor.radiusM}m")
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
                        if (registrationDegradation.getAndSet(null) != null) {
                            SnoozeDebugLog.event("geofence re-registered; registration level cleared")
                            // The restate carries the *feed's* level, not a
                            // synthesized null: this update arrives outside
                            // any signal, and a null here would promote a
                            // snooze whose fixes are still failing (Codex,
                            // PR #75) — the feed's degradation clears only
                            // on a usable fix, as ever.
                            send(
                                PresenceUpdate(
                                    event = null,
                                    degradation = synchronized(feedLock) { feed.degradation },
                                ),
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        if (registrationAttempt.get() != attempt) return@addOnFailureListener
                        val code = (e as? ApiException)?.statusCode
                        val failure = if (code != null) {
                            GeofenceRegistrationFailure.fromStatusCode(code)
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
                reportRegistration(GeofenceRegistrationFailure.fromSecurityException())
            }
        }

        // The repair, marshaled off the bridge's lock before taking the
        // registration lock — the two are taken in the opposite order on the
        // stop() path, and holding one while asking for the other is the
        // deadlock shape. Re-guarded on arrival: a repair queued behind this
        // flow's own stop must find the generation moved and do nothing.
        fun repairFence() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                synchronized(registrationLock) {
                    if (stopGeneration.get() == startGeneration && !isClosedForSend) {
                        SnoozeDebugLog.event("backstop repair: re-registering the fence")
                        registerFence()
                    }
                }
            }
        }
        repairOnRecovery = ::repairFence

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
                        // The grace alarm's firing, as a signal like any
                        // other: the engine re-checks the deadline against
                        // its own state, so a stale firing is a no-op.
                        is GeofenceObservation.GraceElapsed ->
                            deliver(PresenceSignal.GraceElapsed(observation.atElapsedRealtimeMs))
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
                    // watch below is its whole coverage, and the engine's
                    // grace period is what resolves a loss it cannot confirm.
                    // Order with the watch below is safe either way: `Presence
                    // .useless` — what this signal reaches — is a declared
                    // no-op for an anchor with no usable fix (Codex raised a
                    // same-millisecond staleness race between this and the
                    // watch's initial report on PR #77; disproved by a
                    // `PresenceTest` case added for the claim — the guard
                    // means this can never advance `latestEvidenceMs` and so
                    // can never make anything else look stale).
                    SnoozeDebugLog.event("no usable fix on the anchor; geofence not registered")
                    deliver(PresenceSignal.FixUnavailable(readElapsedRealtimeMs()))
                }
                // D4's suppressor and §6.10's second wake-up source in one
                // registration: association suppresses location work, loss
                // escalates into the same confirmation everything else feeds.
                // Event-driven and free, so it runs for every anchor that has
                // an SSID, fenced or not.
                anchor.ssid?.let { ssid ->
                    wifiWatch = PlatformWifiWatch(
                        appContext,
                        ssid,
                        readElapsedRealtimeMs,
                    ) { deliver(it) }
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
            active.compareAndSet(handle, null)
            checkingFixes?.close()
            bridge?.close()
            // In-process like the burst: a restarted service re-registers its
            // own watch. The grace *alarm* is deliberately not canceled here
            // — like the fence, its deadline must outlive a routine service
            // destroy, or a Wi-Fi-only snooze whose grace was running would
            // never end; a stale firing is a no-op by the engine's check.
            wifiWatch?.close()
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
                SnoozeDebugLog.warning("geofence removal failed; it is inert without a snooze", it)
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
