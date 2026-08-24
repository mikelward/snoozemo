package app.snoozemo.presence

import app.snoozemo.core.AnchorCapture
import app.snoozemo.core.PresenceSignal

/**
 * Turns the platform's chatty network callbacks into the two transitions the
 * engine consumes (D4): joined the anchor's network, left it. Pure, so the
 * decisions — normalization, comparison, and transition detection — are
 * JVM-tested; [PlatformWifiWatch] is the thin registration around it.
 *
 * The engine dedups repeats itself (the transition is the evidence, not the
 * callback), but this class reports only transitions anyway: every suppressed
 * repeat is one less main-thread hop and one less line of state the engine
 * has to consider.
 *
 * The first report is a transition by definition — `associated` starts
 * unknown — so a watch started away from the anchor's network says so
 * immediately. That is deliberate and fail-open: a restore hours after a
 * departure must not sit resting on the arm-time association until something
 * else notices (SPEC.md D7).
 *
 * The SSID comparison never leaves this class and is never logged
 * (AGENTS.md, *Privacy*): the signals carry only a timestamp.
 */
internal class AnchorWifiTracker(private val anchorSsid: String) {

    private var associated: Boolean? = null

    /**
     * Feeds the currently visible Wi-Fi SSID as the platform reports it —
     * quoted, or the redaction placeholder, or null when there is no Wi-Fi —
     * and returns the signal this transition means, or null for a repeat.
     *
     * A redacted SSID reads as *not associated*, deliberately: it means
     * location access is gone, so nothing can vouch for the association, and
     * an unvouched suppressor holding a snooze quiet is the direction D7
     * forbids. The escalation it causes is settled by the same machinery as
     * any other — a fix, or the grace period.
     */
    /**
     * Feeds the one-shot read of the platform's *currently connected*
     * networks that [PlatformWifiWatch] takes at registration, as opposed to
     * the callback's reports.
     *
     * That read is a weaker instrument than a callback and this is where the
     * difference is stated: it cannot name a network, because
     * `getNetworkCapabilities` strips the SSID from a direct read and hands
     * back the redaction placeholder however firmly the phone is associated.
     * So it may answer only the question it can actually answer — is there
     * any Wi-Fi at all — and the two answers are not symmetric. *No* Wi-Fi
     * settles the anchor's association on its own: nothing is associated to
     * anything. Wi-Fi *present* settles nothing, because which network it is
     * decides everything and this read cannot see it; the callback that owns
     * every transition is along momentarily, and waiting for it is free.
     *
     * Reading the redacted SSID anyway is what put a five-minute grace
     * deadline on every arm and every restore of a Wi-Fi-only snooze — a
     * loss reported against a phone sitting on its own anchor, cleared only
     * if the callback won the race to correct it, and ending the snooze if
     * it did not.
     *
     * [readSucceeded] false is a refused read, which fails open to a loss
     * like every other unanswerable question here (D7).
     */
    fun onSeedRead(
        readSucceeded: Boolean,
        anyWifiConnected: Boolean,
        atElapsedRealtimeMs: Long,
    ): PresenceSignal? =
        if (readSucceeded && anyWifiConnected) null else onWifiSsid(null, atElapsedRealtimeMs)

    fun onWifiSsid(raw: String?, atElapsedRealtimeMs: Long): PresenceSignal? {
        val now = AnchorCapture.sanitizeSsid(raw) == anchorSsid
        val was = associated
        associated = now
        return when {
            now && was != true -> PresenceSignal.AnchorWifiAssociated(atElapsedRealtimeMs)
            !now && was != false -> PresenceSignal.AnchorWifiLost(atElapsedRealtimeMs)
            else -> null
        }
    }
}
