package app.snoozemo.presence.geofence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.snoozemo.core.Anchor
import app.snoozemo.core.DegradationCause
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
 * This is the first slice of the monitor — registration, the exit callback,
 * and the recoverable/fatal split at the platform boundary. The location
 * request loop driven by [PresenceFeed.duty], the Wi-Fi suppressor callback,
 * significant motion, the grace alarm, and `PresenceState` persistence are
 * their own `TODO.md` items and land behind the same interface. Until they
 * do, an exit escalates the engine but nothing takes the confirming fixes, so
 * this monitor observes and reports rather than ends — which is safe in the
 * fail-open direction: the duration cap still bounds every snooze.
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
                    degradation = update.degradation ?: platformDegradation.get(),
                ),
            )
        }

        fun deliver(signal: PresenceSignal) {
            send(feed.accept(signal))
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
