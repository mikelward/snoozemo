package app.snoozemo.presence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.snoozemo.core.Anchor
import app.snoozemo.core.AnchorCapture
import app.snoozemo.core.SnoozeDebugLog
import java.time.Instant

/**
 * The platform half of arm-time anchor capture (SPEC.md §4.1, §6.2): one
 * location request and one Wi-Fi read, bounded by the 10 s ceiling, feeding
 * the pure [AnchorCapture] that decides what counts.
 *
 * Shared by both flavors: a one-shot fix needs no Play Services — the
 * platform's fused provider serves it on every device this app supports
 * (minSdk 34) — so the flavor seam stays where the *ongoing* monitoring
 * differs (SPEC.md §3.4), not here.
 *
 * Started strictly after the zen rule is on: everything here is IPC, and none
 * of it may sit between the tile tap and `STATE_TRUE` (`AGENTS.md`, the arm
 * path). Every callback is delivered on the main thread — the network
 * callback through a main-looper handler, the location listener through the
 * main executor, the ceiling through the same handler — so the capture is
 * main-confined and needs no locks.
 *
 * A capture that can do nothing still answers: a missing permission or
 * switched-off location settles the fix half immediately, a Wi-Fi read that
 * arrives redacted is rejected by the pure half, and the ceiling bounds
 * whatever remains. The caller always gets exactly one anchor, with however
 * much could honestly be captured.
 */
class AnchorCaptureRunner(context: Context) {

    private val context = context.applicationContext

    /**
     * Starts capturing, delivering the finished anchor to [onCaptured] on the
     * main thread exactly once — possibly synchronously, when nothing needs
     * waiting for. Closing the returned handle cancels the capture and
     * releases everything it registered; after delivery it is a no-op.
     */
    fun begin(capturedAt: Instant, onCaptured: (Anchor) -> Unit): AutoCloseable =
        Session(capturedAt, onCaptured).also { it.start() }

    private inner class Session(
        capturedAt: Instant,
        private val onCaptured: (Anchor) -> Unit,
    ) : AutoCloseable {

        private val handler = Handler(Looper.getMainLooper())
        private val capture = AnchorCapture(capturedAt)
        private var done = false
        private var wifiRegistered = false
        private var locationRequested = false

        private val connectivity: ConnectivityManager? =
            context.getSystemService(ConnectivityManager::class.java)
        private val locationManager: LocationManager? =
            context.getSystemService(LocationManager::class.java)

        // FLAG_INCLUDE_LOCATION_INFO is load-bearing, not optional: without it
        // the WifiInfo arrives redacted regardless of the permissions held
        // (SPEC.md §6.4). The pure half still rejects the placeholders, so a
        // regression here degrades honestly instead of anchoring on
        // "<unknown ssid>".
        private val networkCallback = object :
            ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiInfo ?: return
                settle(capture.onWifi(info.ssid, info.bssid))
            }

            override fun onLost(network: Network) {
                settle(capture.onNoWifi())
            }
        }

        private val locationListener = LocationListener { location ->
            settle(
                capture.onFix(
                    location.latitude,
                    location.longitude,
                    if (location.hasAccuracy()) location.accuracy else null,
                ),
            )
        }

        fun start() {
            // The ceiling first, before anything that can throw or settle: it
            // is what guarantees the arm always completes (SPEC.md §4.1).
            handler.postDelayed({ settle(capture.onCeiling()) }, CEILING_MS)
            startWifi()
            startLocation()
        }

        private fun startWifi() {
            val cm = connectivity
            if (cm == null) {
                settle(capture.onNoWifi())
                return
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            try {
                // Delivered on [handler], so every event shares the main thread.
                cm.registerNetworkCallback(request, networkCallback, handler)
                wifiRegistered = true
            } catch (e: RuntimeException) {
                // Registration refused (the per-app callback cap being the
                // documented case). Settled as no Wi-Fi rather than left to the
                // ceiling: nothing will answer, and waiting only delays the
                // armed record.
                SnoozeDebugLog.failure(e, "anchor capture: wifi callback refused; no ssid")
                settle(capture.onNoWifi())
            }
        }

        private fun startLocation() {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // The in-app prompt is a later phase, so this is the normal
                // first-run state, not an error — said at event level so a
                // duration-only snooze is explicable after the fact.
                SnoozeDebugLog.event("anchor capture: no location permission; no fix")
                settle(capture.onNoFix())
                return
            }
            val lm = locationManager
            if (lm == null || !lm.isLocationEnabled) {
                SnoozeDebugLog.event("anchor capture: location services off; no fix")
                settle(capture.onNoFix())
                return
            }
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { lm.hasProvider(it) }
            if (provider == null) {
                SnoozeDebugLog.warning("anchor capture: no usable location provider; no fix")
                settle(capture.onNoFix())
                return
            }
            // Seed with the last known fix, synchronously, before any request:
            // this runs inside the tile tap's start, while the trampoline
            // still holds the app foreground, and a tap from the shade drops
            // to the background within a frame of it finishing — after which a
            // while-in-use grant delivers fresh fixes throttled or not at all
            // (flagged by Codex on PR #71). A cached fix read now is the one
            // answer that cannot lose that race. The same accuracy gate
            // applies, plus a freshness one: the anchor is where the user is
            // *now*, and a minute at walking pace stays well inside the 150 m
            // radius where ten minutes does not.
            try {
                lm.getLastKnownLocation(provider)?.let { last ->
                    val ageMs = (SystemClock.elapsedRealtimeNanos() -
                        last.elapsedRealtimeNanos) / 1_000_000
                    if (ageMs in 0..SEED_MAX_AGE_MS) {
                        settle(
                            capture.onFix(
                                last.latitude,
                                last.longitude,
                                if (last.hasAccuracy()) last.accuracy else null,
                            ),
                        )
                    }
                }
            } catch (e: SecurityException) {
                SnoozeDebugLog.failure(e, "anchor capture: last known fix refused")
            }
            // Settled by the seed (or by everything else already having
            // answered): ten seconds of updates the capture would ignore is
            // battery spent on nothing (SPEC.md §9).
            if (done || !capture.awaitingFix) return
            // Updates rather than one shot, so a first fix too vague for the
            // gate does not spend the only answer — the pure half keeps
            // waiting for a better one until the ceiling. Balanced power is
            // right for a 150 m decision boundary (SPEC.md §6.5), and the
            // duration bound has the platform stop the request even if this
            // process dies before cleanup.
            val request = LocationRequest.Builder(FIX_INTERVAL_MS)
                .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(CEILING_MS)
                .build()
            try {
                lm.requestLocationUpdates(provider, request, context.mainExecutor, locationListener)
                locationRequested = true
            } catch (e: SecurityException) {
                // Permission raced away between the check and the request.
                SnoozeDebugLog.failure(e, "anchor capture: location request refused; no fix")
                settle(capture.onNoFix())
            } catch (e: RuntimeException) {
                SnoozeDebugLog.failure(e, "anchor capture: location request failed; no fix")
                settle(capture.onNoFix())
            }
        }

        /** Finishes the capture if [anchor] is the completed one. Main thread. */
        private fun settle(anchor: Anchor?) {
            if (anchor == null || done) return
            done = true
            cleanup()
            onCaptured(anchor)
        }

        override fun close() {
            if (done) return
            done = true
            cleanup()
        }

        /**
         * Releases everything [start] acquired. Never throws: teardown runs on
         * paths that still owe the caller an anchor or a close, and a listener
         * the platform will not take back is a defect to log, not to escalate.
         */
        private fun cleanup() {
            handler.removeCallbacksAndMessages(null)
            if (wifiRegistered) {
                wifiRegistered = false
                runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
                    .onFailure {
                        SnoozeDebugLog.failure(it, "anchor capture: wifi callback would not unregister")
                    }
            }
            if (locationRequested) {
                locationRequested = false
                runCatching { locationManager?.removeUpdates(locationListener) }
                    .onFailure {
                        SnoozeDebugLog.failure(it, "anchor capture: location updates would not stop")
                    }
            }
        }
    }

    private companion object {
        /** The arming ceiling of SPEC.md §4.1: degrade rather than block. */
        const val CEILING_MS = 10_000L

        /** How often to retry a fix the accuracy gate rejected. */
        const val FIX_INTERVAL_MS = 1_000L

        /**
         * How old a cached fix may be and still seed the anchor. A minute at
         * walking pace is under 100 m — inside the radius the anchor decides
         * with — and even a wrong seed errs open: an anchor the user is not
         * at reads as an immediate departure, never a snooze that won't end.
         */
        const val SEED_MAX_AGE_MS = 60_000L
    }
}
