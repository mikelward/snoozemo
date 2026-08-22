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
 * recoverable/fatal split at the platform boundary, and the confirming fixes
 * — [CheckingFixes] one-shots started and stopped by the duty the engine
 * reports, so an exit escalates and the §6.6 test can actually settle it.
 * Still their own `TODO.md` items behind the same interface: the Wi-Fi
 * suppressor callback, significant motion, the grace alarm, the periodic
 * backstop, and `PresenceState` persistence. And nothing collects this flow
 * until the service wiring slice lands, so every conclusion here is still
 * unconsumed — safe in the fail-open direction: the duration cap bounds
 * every snooze.
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

    override fun start(anchor: Anchor): Flow<PresenceUpdate> {
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

        val feed = PresenceFeed(anchor, seedElapsedRealtimeMs = readElapsedRealtimeMs())

        // The platform-health level, held beside the feed because it is not
        // the engine's to know: the engine reasons about evidence, and "the
        // sensor stopped watching" is a statement about the sensor. Merged
        // into every update rather than sent once — a later engine update
        // carrying the feed's null level would otherwise read as recovery and
        // restore FULL tracking while no fence is registered, which is the
        // overstating direction the level design exists to prevent (flagged
        // by Codex on PR #70). A level is restated, never delivered (SPEC.md
        // §6.1); this holds that rule at the monitor boundary too. Cleared
        // only by actual recovery, which is the re-registration slice's job —
        // until that lands, an impaired platform stays said.
        val platformDegradation =
            java.util.concurrent.atomic.AtomicReference<DegradationCause?>(null)

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
                    degradation = platformDegradation.get() ?: update.degradation,
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
            val update: PresenceUpdate
            val duty: LocationDuty
            synchronized(feedLock) {
                update = feed.accept(signal)
                duty = feed.duty
            }
            send(update)
            // Reconciled on every signal rather than on transitions, because
            // both calls are idempotent and "did the transition get noticed"
            // is exactly the kind of question idempotence deletes.
            // Pause, not close: the duty leaving ACTIVE is a state the engine
            // can re-enter, and only teardown (awaitClose) may end the burst
            // for good.
            if (duty == LocationDuty.ACTIVE) checkingFixes?.start() else checkingFixes?.pause()
        }

        // Recoverable refusals set the platform level (and so keep being
        // restated on every later update); fatal ones end the snooze and need
        // no level at all.
        fun reportRegistration(failure: GeofenceRegistrationFailure) {
            when (failure) {
                is GeofenceRegistrationFailure.Recoverable -> {
                    platformDegradation.set(failure.cause)
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
                // is known rather than after three generic unanswered fixes:
                // the platform level carries it on every later update until
                // recovery re-registration clears it.
                reportRegistration(
                    GeofenceRegistrationFailure.Recoverable(DegradationCause.LOCATION_SERVICES_OFF),
                )
            },
        )

        // This flow's claim on the one fence. Registration takes the process-
        // level ownership below; only the owner removes the fence on teardown.
        val ownership = Any()
        var bridge: AutoCloseable? = null
        var registered = false

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
                            platformDegradation.set(DegradationCause.LOCATION_SERVICES_OFF)
                            send(PresenceUpdate(event = null, degradation = null))
                        }
                    }
                }
                if (anchor.hasUsableFix) {
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
                        // The claim sits before the call, not on its success
                        // callback: an in-flight registration is already this
                        // flow's to clean up. Both under the section's lock,
                        // so the last claimant is also the last registrant
                        // (flagged by Codex on PR #70). `addGeofences` only
                        // enqueues — the lock never waits on Play Services.
                        fenceOwner.set(ownership)
                        client.addGeofences(request, transitionIntent(appContext))
                            .addOnSuccessListener {
                                SnoozeDebugLog.event("geofence registered; radius=${anchor.radiusM}m")
                            }
                            .addOnFailureListener { e ->
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
                        registered = true
                    } catch (e: SecurityException) {
                        // The grant went between the permission check and the
                        // call — fail open with the reason rather than watch
                        // nothing quietly.
                        SnoozeDebugLog.warning("geofence registration refused: permission gone")
                        reportRegistration(GeofenceRegistrationFailure.fromSecurityException())
                    }
                } else {
                    // A Wi-Fi-only anchor has nothing to fence. The Wi-Fi
                    // callback is its own slice; until it lands this monitor
                    // reports the truth — no location fix — rather than
                    // implying a watch it isn't keeping.
                    SnoozeDebugLog.event("no usable fix on the anchor; geofence not registered")
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
            active.compareAndSet(handle, null)
            checkingFixes?.close()
            bridge?.close()
            // Only while this flow still owns the fence: a replacement monitor
            // registering under the shared id has taken ownership, and the old
            // teardown removing it would strip the new snooze's tracking with
            // no degradation to say so (flagged by Codex on PR #70) — the
            // identity rule the bridge already follows, applied to the fence.
            // Under the same lock as registration, so a removal can never
            // slot between a replacement's claim and its register call.
            synchronized(registrationLock) {
                if (registered && fenceOwner.compareAndSet(ownership, null)) {
                    // Contained: removal is cleanup, and the fence dies with
                    // the app's registrations anyway if this fails.
                    runCatching {
                        LocationServices.getGeofencingClient(appContext)
                            .removeGeofences(listOf(GEOFENCE_ID))
                    }.onFailure {
                        SnoozeDebugLog.warning("geofence removal failed; it is inert without a snooze", it)
                    }
                }
            }
        }
        }
    }

    override fun stop() {
        // Idempotent by construction: closing an already-completed channel is
        // a no-op, and the release itself runs once, inside awaitClose. The
        // generation bump and the close share the lifecycle lock so a cold
        // producer cannot observe the old generation after this stop has
        // decided (see [stopGeneration]).
        synchronized(registrationLock) {
            stopGeneration.incrementAndGet()
            active.getAndSet(null)?.close()
        }
    }

    companion object {
        /** The one fence this app ever registers (one snooze, one place). */
        internal const val GEOFENCE_ID = "snoozemo-anchor"

        /**
         * Which flow currently owns the fence. Process-level because the fence
         * is: registrations under the shared id replace each other in Play
         * Services, so removal is only the owner's to do.
         */
        private val fenceOwner = java.util.concurrent.atomic.AtomicReference<Any?>(null)

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
