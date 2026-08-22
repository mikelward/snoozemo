package app.snoozemo.core

import java.time.Instant

/**
 * Assembles the [Anchor] from whatever arm-time capture manages to deliver
 * before the ceiling (SPEC.md §4.1, §6.2).
 *
 * Pure by design, like the rest of the decision logic (SPEC.md §11): the
 * platform half feeds in what its callbacks said — a Wi-Fi read, a fix, the
 * ceiling elapsing — and this decides what counts and when the anchor is done.
 * The rules worth testing live here, where a JVM test can replay them:
 *
 * - **Only a usable answer settles a half early.** A fix vaguer than
 *   [Anchor.MAX_ANCHOR_ACCURACY_M] is discarded, not stored — it says
 *   "somewhere in the neighborhood", which cannot distinguish leaving from
 *   standing still (SPEC.md §8.4) — and capture keeps waiting for a better one.
 *   The same goes for a redacted Wi-Fi read: a later callback may carry the
 *   real values, so a placeholder does not close the question.
 * - **The placeholders are rejected, never stored** (SPEC.md §6.4). A callback
 *   registered without location access hands back real-looking objects whose
 *   SSID is `<unknown ssid>` and whose BSSID is `02:00:00:00:00:00` — plausible
 *   strings that would anchor nothing and match nothing. An anchor built from
 *   them fails quietly, so they are treated as no answer at all.
 * - **A definitive "no" settles immediately.** Location permission missing,
 *   location services off, Wi-Fi lost — nothing better is coming, so waiting
 *   for the ceiling would only delay the armed record.
 * - **The ceiling settles everything else** with whatever arrived. Arming must
 *   never block on capture (SPEC.md §4.1); the caller owns the timer and this
 *   just answers it.
 *
 * Each event returns the finished [Anchor] exactly once — the first time the
 * capture completes — and null while it is still waiting or after it is done.
 * Not thread-safe; the caller confines events to one thread.
 */
class AnchorCapture(private val capturedAt: Instant) {

    private var finished = false

    private var wifiAnswered = false
    private var ssid: String? = null
    private var bssid: String? = null

    private var fixAnswered = false
    private var lat: Double? = null
    private var lon: Double? = null
    private var accuracyM: Float? = null

    /**
     * Whether the location half is still open — what tells the platform half
     * it needs a live location request at all. A seeded fix (the last known
     * one, read synchronously while the tap still holds the app foreground)
     * can settle the half before any request exists, and asking for ten
     * seconds of updates a settled half will ignore is battery spent on
     * nothing (SPEC.md §9).
     */
    val awaitingFix: Boolean
        get() = !finished && !fixAnswered

    /**
     * A Wi-Fi read arrived, raw from the platform: [rawSsid] possibly quoted,
     * either field possibly a redaction placeholder. Settles the Wi-Fi half
     * only when the SSID is real — the SSID is the anchor (SPEC.md §6.2), so a
     * BSSID without one is recorded as nothing.
     */
    fun onWifi(rawSsid: String?, rawBssid: String?): Anchor? {
        if (finished || wifiAnswered) return null
        val usableSsid = sanitizeSsid(rawSsid) ?: return null
        wifiAnswered = true
        ssid = usableSsid
        bssid = sanitizeBssid(rawBssid)
        return completeIfReady()
    }

    /** Wi-Fi is definitively absent — never connected, or lost mid-capture. */
    fun onNoWifi(): Anchor? {
        if (finished || wifiAnswered) return null
        wifiAnswered = true
        return completeIfReady()
    }

    /**
     * A fix arrived. Settles the location half only when it clears the
     * accuracy gate; a vaguer one is discarded entirely rather than stored for
     * a mode derivation to reject later — coordinates that cannot be tested
     * against are not worth keeping (SPEC.md §8.4).
     */
    fun onFix(lat: Double, lon: Double, accuracyM: Float?): Anchor? {
        if (finished || fixAnswered) return null
        if (accuracyM == null || accuracyM > Anchor.MAX_ANCHOR_ACCURACY_M) return null
        fixAnswered = true
        this.lat = lat
        this.lon = lon
        this.accuracyM = accuracyM
        return completeIfReady()
    }

    /**
     * No fix is coming at all — permission missing, location services off, no
     * provider. Distinct from a vague fix, which keeps the question open.
     */
    fun onNoFix(): Anchor? {
        if (finished || fixAnswered) return null
        fixAnswered = true
        return completeIfReady()
    }

    /** The ceiling elapsed: finish with whatever arrived (SPEC.md §4.1). */
    fun onCeiling(): Anchor? {
        if (finished) return null
        return finish()
    }

    private fun completeIfReady(): Anchor? =
        if (wifiAnswered && fixAnswered) finish() else null

    private fun finish(): Anchor {
        finished = true
        return Anchor(
            lat = lat,
            lon = lon,
            fixAccuracyM = accuracyM,
            capturedAt = capturedAt,
            ssid = ssid,
            bssid = bssid,
        )
    }

    companion object {
        /**
         * `WifiManager.UNKNOWN_SSID`, by value: what `WifiInfo.getSSID()`
         * returns when location access is not allowed (SPEC.md §6.4). The
         * literal rather than the platform constant so this class stays pure.
         */
        const val REDACTED_SSID = "<unknown ssid>"

        /** What `WifiInfo.getBSSID()` returns under the same redaction. */
        const val REDACTED_BSSID = "02:00:00:00:00:00"

        /**
         * The SSID as an anchor value, or null when [raw] is no answer:
         * `WifiInfo.getSSID()` wraps a UTF-8 SSID in double quotes, and hands
         * back [REDACTED_SSID] — unquoted — when access is not allowed.
         */
        fun sanitizeSsid(raw: String?): String? {
            val unquoted = raw?.removeSurrounding("\"") ?: return null
            return unquoted.takeUnless { it.isBlank() || it == REDACTED_SSID }
        }

        fun sanitizeBssid(raw: String?): String? =
            raw?.takeUnless { it.isBlank() || it == REDACTED_BSSID }
    }
}
