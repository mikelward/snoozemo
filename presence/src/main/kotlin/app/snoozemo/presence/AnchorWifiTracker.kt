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
