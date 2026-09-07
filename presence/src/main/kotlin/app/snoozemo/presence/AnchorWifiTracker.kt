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
     * anything, so it reports a loss. Wi-Fi *present* settles nothing about
     * *which* network, so it reports [PresenceSignal.AnchorWifiPresentUnconfirmed]
     * — not an association (it cannot claim the anchor) and not a loss (it
     * would spuriously escalate a phone sitting on its own network). That
     * signal only asks a due grace deadline to wait for the callback that
     * owns every real transition, which is along momentarily.
     *
     * Reading the redacted SSID anyway is what put a five-minute grace
     * deadline on every arm and every restore of a Wi-Fi-only snooze — a
     * loss reported against a phone sitting on its own anchor, cleared only
     * if the callback won the race to correct it, and ending the snooze if
     * it did not. Reporting *nothing* instead left the grace-restore replay
     * to resolve `Departed` before the callback could speak; the unconfirmed
     * signal is what makes that replay defer.
     *
     * Deliberately does not touch the tracker's own association state: it
     * makes no claim about the network, so the async callback that follows
     * is still the first real transition.
     *
     * [readSucceeded] false is a refused read, which fails open to a loss
     * like every other unanswerable question here (D7) — and carries
     * `observed = false`, since failing open is not the same claim as having
     * looked and found nothing.
     */
    fun onSeedRead(
        readSucceeded: Boolean,
        anyWifiConnected: Boolean,
        atElapsedRealtimeMs: Long,
    ): PresenceSignal? =
        if (readSucceeded && anyWifiConnected) {
            PresenceSignal.AnchorWifiPresentUnconfirmed(atElapsedRealtimeMs)
        } else {
            // A read that succeeded and found *no Wi-Fi at all* has settled the
            // anchor's association on its own, and it is the only path here
            // that can: it reads the whole connected set in one go, so there
            // is no second network whose report could still be pending. A
            // refused read has established nothing and only fails open, so it
            // may not claim an observation however identical the signal looks.
            report(
                null,
                atElapsedRealtimeMs,
                evidence = if (readSucceeded) LossEvidence.COMPLETE_VIEW else LossEvidence.NONE,
            )
        }

    fun onWifiSsid(raw: String?, atElapsedRealtimeMs: Long): PresenceSignal? =
        report(
            raw,
            atElapsedRealtimeMs,
            // A redacted name means the location grant has gone, so the
            // callback saw nothing it is allowed to report on.
            evidence = if (AnchorCapture.isRedactedSsid(raw)) {
                LossEvidence.NONE
            } else {
                LossEvidence.TRANSITION_ONLY
            },
        )

    /**
     * How much a loss reported through [report] is entitled to claim.
     *
     * Fail-open behavior does not vary with this — every value still reports
     * the loss (D7). What varies is whether the signal may say the network was
     * *seen* to go, which is what a future shorter departure bar would gate on
     * (`TODO.md`).
     */
    private enum class LossEvidence {
        /**
         * Settles the anchor's association on its own, whatever was believed
         * before: the seed read finding no Wi-Fi at all.
         */
        COMPLETE_VIEW,

        /**
         * Settles it only as a *transition* away from a known association
         * (Codex, PR #222).
         *
         * The callback's picture of the connected set is built one network at
         * a time, and their initial reports arrive in no guaranteed order. On
         * a device with concurrent Wi-Fi connections a non-anchor network can
         * report first, leaving the anchor's own network not yet in
         * [PlatformWifiWatch]'s map — so an absence read at that instant is an
         * incomplete snapshot, not a disappearance. The same shape as PR #77's
         * finding, one level up: there it produced a false *loss*, here it
         * would produce a false claim to have *watched* one.
         *
         * Requiring a known prior association is what tells them apart, and it
         * errs the safe way: a watch that starts away from the anchor reports
         * its loss exactly as before, and only declines to call it observed.
         */
        TRANSITION_ONLY,

        /** Settles nothing. The loss is the fail-open answer, and says so. */
        NONE,
    }

    /**
     * The one place a transition — and its provenance — is decided.
     */
    private fun report(
        raw: String?,
        atElapsedRealtimeMs: Long,
        evidence: LossEvidence,
    ): PresenceSignal? {
        val now = AnchorCapture.sanitizeSsid(raw) == anchorSsid
        val was = associated
        associated = now
        return when {
            now && was != true -> PresenceSignal.AnchorWifiAssociated(atElapsedRealtimeMs)
            !now && was != false -> PresenceSignal.AnchorWifiLost(
                atElapsedRealtimeMs,
                observed = when (evidence) {
                    LossEvidence.COMPLETE_VIEW -> true
                    LossEvidence.TRANSITION_ONLY -> was == true
                    LossEvidence.NONE -> false
                },
            )
            else -> null
        }
    }
}
