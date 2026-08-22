package app.snoozemo.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.snoozemo.core.AnchorCapture
import app.snoozemo.core.PresenceSignal
import app.snoozemo.core.SnoozeDebugLog

/**
 * The platform half of D4's Wi-Fi watch: a `NetworkCallback` on the Wi-Fi
 * transport feeding [AnchorWifiTracker], which decides what each report
 * means. Event-driven and free — no polling, no location request — which is
 * why SPEC.md §6.10 calls Wi-Fi loss the highest-value wake-up source.
 *
 * `FLAG_INCLUDE_LOCATION_INFO` is what puts the SSID into the capabilities
 * callback; it requires the fine-location grant the snooze already holds,
 * and when the platform redacts anyway the tracker reads it as
 * *not associated* — the fail-open direction.
 *
 * Signals are marshaled onto the main thread, the same confinement every
 * other source of the monitor's `deliver` uses. Timestamps are delivery
 * times, which the engine documents as the never-stale, fail-safe choice for
 * a transition the platform cannot date.
 */
internal class PlatformWifiWatch(
    context: Context,
    private val anchorSsid: String,
    private val readElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val onSignal: (PresenceSignal) -> Unit,
) : AutoCloseable {

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val tracker = AnchorWifiTracker(anchorSsid)

    /**
     * Every Wi-Fi network the callback currently knows about, keyed by
     * network, valued by its last-reported raw SSID. A device with
     * concurrent Wi-Fi connections (dual-STA, a secondary local-only or
     * internet-capable network beside the primary) can report more than one
     * network matching the broad `TRANSPORT_WIFI` request, and the anchor is
     * associated as long as *any one* of them carries its SSID — a single
     * "last reported network" field let a secondary network's report
     * overwrite the anchor's own and falsely report a loss (Codex, PR #77).
     */
    private val ssidByNetwork = mutableMapOf<Network, String?>()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO,
    ) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            ssidByNetwork[network] = (caps.transportInfo as? WifiInfo)?.ssid
            deliverCurrent()
        }

        override fun onLost(network: Network) {
            ssidByNetwork.remove(network)
            deliverCurrent()
        }

        private fun deliverCurrent() {
            tracker.onWifiSsid(currentAssociationSsid(), readElapsedRealtimeMs())?.let(onSignal)
        }
    }

    init {
        // Contained, but never quiet, and the failure resolves toward ending
        // (AGENTS.md, principles 1 and 2): a Wi-Fi watch that silently failed
        // to register would leave a WIFI_ONLY snooze looking watched while
        // nothing watches — the snooze that never ends. Reported as a loss,
        // it escalates instead, and the ordinary machinery settles it — a
        // fix for a fenced anchor, the grace period for a Wi-Fi-only one.
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback,
                Handler(Looper.getMainLooper()),
            )
            registered = true
        }.onFailure {
            SnoozeDebugLog.warning("Wi-Fi watch registration refused; treating as loss", it)
            onSignal(PresenceSignal.AnchorWifiLost(readElapsedRealtimeMs()))
        }
        if (registered) {
            // A registration with no matching network dispatches nothing —
            // not `onCapabilitiesChanged`, not `onLost` — because there was
            // never a network to report on (Codex, PR #77). The tracker,
            // seeded from the arm-time anchor as *associated*, would then
            // hold that belief forever: a restore after the user already
            // left, off Wi-Fi from the start, suppressed until the cap
            // rather than watched. This reads the state the callback would
            // have reported and delivers it once, explicitly; the async
            // callback still owns every transition after. A separate
            // `runCatching` from the registration above, so a failure here —
            // registered fine, but the read itself refused — is diagnosed
            // for what it is rather than reported as a registration failure;
            // either way the outcome is the same fail-open loss.
            runCatching { seedFromCurrentNetworks() }
                .onFailure { SnoozeDebugLog.warning("Wi-Fi initial state read refused; treating as loss", it) }
                // A refused read joins the same fail-open path as a genuine
                // disconnect — through the tracker, not straight to
                // `onSignal` — so its state stays the source of truth for
                // the async callback still to come; two `AnchorWifiLost`s in
                // a row from a stale tracker belief would be a spurious
                // repeat the tracker exists to suppress.
                .let { tracker.onWifiSsid(currentAssociationSsid(), readElapsedRealtimeMs())?.let(onSignal) }
        }
    }

    /**
     * Populates [ssidByNetwork] from whatever Wi-Fi networks are already
     * connected — the aggregation the async callback builds up over time,
     * read once up front (Codex, PR #77) rather than waited for, since a
     * registration with nothing to transition from dispatches nothing.
     */
    private fun seedFromCurrentNetworks() {
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            ssidByNetwork[network] = (caps.transportInfo as? WifiInfo)?.ssid
        }
    }

    /**
     * The raw SSID of whichever known network currently matches the anchor,
     * or null if none does — association is a property of the *set* of
     * known networks, not of any single one, so this is read fresh on every
     * report rather than cached on one network's identity.
     */
    private fun currentAssociationSsid(): String? =
        ssidByNetwork.values.firstOrNull { AnchorCapture.sanitizeSsid(it) == anchorSsid }

    override fun close() {
        if (!registered) return
        registered = false
        runCatching {
            connectivity.unregisterNetworkCallback(callback)
        }.onFailure {
            // Unregistering an already-gone callback is a lifecycle wrinkle,
            // not a leak the process can act on; logged, never swallowed.
            SnoozeDebugLog.warning("Wi-Fi watch unregister failed", it)
        }
    }
}
