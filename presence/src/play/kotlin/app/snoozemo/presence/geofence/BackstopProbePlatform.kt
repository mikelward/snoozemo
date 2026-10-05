package app.snoozemo.presence.geofence

import android.content.Context
import android.os.SystemClock
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.BackstopProbe
import app.snoozemo.core.Fix
import app.snoozemo.core.PresenceSignal
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.logSummary
import app.snoozemo.presence.PlatformWifiWatch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The platform half of [BackstopProbe]: blocking fix requests and a one-off
 * anchor-Wi-Fi read, for a `WorkManager` worker thread with no service behind
 * it (SPEC.md §6.10).
 *
 * Blocking is right here and nowhere else: a worker runs off the main thread
 * with a ten-minute budget, and the longest probe — three fixes at their
 * timeout plus two confirmation gaps — is a few minutes of it. Callbacks still
 * land on the main looper, which a worker thread leaves free.
 */
internal class BackstopProbePlatform(context: Context) {

    private val appContext = context.applicationContext

    fun probe(snooze: ActiveSnooze): BackstopProbe.Outcome {
        val ssid = snooze.anchor.ssid
        if (!snooze.endsOnDeparture || !snooze.anchor.hasUsableFix || ssid == null) {
            return runTest(snooze, BackstopProbe.AnchorWifi.None)
        }
        // The watch stays open for the whole probe, read live around every fix
        // (`BackstopProbe.AnchorWifi`).
        val wifi = AnchorWifiCheck(ssid)
        try {
            if (wifi.awaitFirstAnswer()) {
                SnoozeDebugLog.event("backstop probe: on the anchor's Wi-Fi; no fix taken")
                return BackstopProbe.Outcome.AtAnchorWifi
            }
            val outcome = runTest(snooze, wifi)
            if (outcome == BackstopProbe.Outcome.AtAnchorWifi) {
                SnoozeDebugLog.event("backstop probe: the anchor's Wi-Fi came back mid-probe")
            }
            return outcome
        } finally {
            wifi.close()
        }
    }

    private fun runTest(snooze: ActiveSnooze, wifi: BackstopProbe.AnchorWifi): BackstopProbe.Outcome =
        BackstopProbe.run(
            snooze,
            takeFix = ::takeFix,
            sleepMs = { Thread.sleep(it) },
            nowElapsedMs = SystemClock::elapsedRealtime,
            wifi = wifi,
            onObservation = { observation, verdict, rule ->
                SnoozeDebugLog.event("%s", observation.logSummary(verdict, rule))
            },
        )

    /**
     * One fix, or null. The requester's own outcomes are logged where they
     * happen; a grant gone or services off is simply no fix here — the probe
     * ends nothing on a reading it never had, and the cap still holds.
     */
    private fun takeFix(): Fix? {
        val done = CountDownLatch(1)
        val result = AtomicReference<Fix?>(null)
        val handle = PlatformFixRequester(appContext).request { outcome ->
            if (outcome is FixOutcome.Delivered) result.set(outcome.fix)
            done.countDown()
        }
        try {
            if (!done.await(FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                SnoozeDebugLog.event("backstop probe: no fix within the timeout")
            }
        } finally {
            // Closed either way, so a late answer cannot outlive the wake.
            handle.close()
        }
        return result.get()
    }

    /**
     * The anchor's association, watched for as long as the probe runs. Only a
     * positive report suppresses the probe: an observed loss, no Wi-Fi, or no
     * answer in time all let it run, because the probe is still the §6.6 test
     * and an unanswered question resolves toward checking (principle 1).
     */
    private inner class AnchorWifiCheck(ssid: String) : BackstopProbe.AnchorWifi, AutoCloseable {
        private val decided = CountDownLatch(1)
        private val associated = AtomicBoolean(false)
        private val transitions = AtomicLong(0L)
        private val watch = PlatformWifiWatch(appContext, ssid) { signal ->
            settlesAssociation(signal)?.let {
                if (associated.getAndSet(it) != it) transitions.incrementAndGet()
                decided.countDown()
            }
        }

        /** Whether the first settled answer, or the timeout, finds it associated. */
        fun awaitFirstAnswer(): Boolean {
            if (!decided.await(WIFI_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                SnoozeDebugLog.event("backstop probe: Wi-Fi association unanswered; probing anyway")
            }
            return associated.get()
        }

        override fun associatedNow(): Boolean = associated.get()

        override fun transitions(): Long = transitions.get()

        override fun close() = watch.close()
    }

    internal companion object {
        /**
         * What one Wi-Fi report settles about the anchor's association: true
         * for associated, false for an *observed* loss, null for keep waiting.
         *
         * An unobserved loss settles nothing (Codex, PR #310). On a device with
         * concurrent Wi-Fi connections the first callback can be a non-anchor
         * network's, reporting a loss while the anchor's own callback is still
         * on its way; taking that as final would run the probe — and possibly
         * its single-fix shortcut — on a phone still on the anchor's network.
         * Only a loss the tracker actually watched happen, or no Wi-Fi at all,
         * is final; anything else waits for an association or the timeout.
         */
        fun settlesAssociation(signal: PresenceSignal): Boolean? = when (signal) {
            is PresenceSignal.AnchorWifiAssociated -> true
            is PresenceSignal.AnchorWifiLost -> false.takeIf { signal.observed }
            else -> null
        }

        /** A little over the platform's own one-shot timeout. */
        const val FIX_TIMEOUT_MS = 45_000L

        /**
         * The callback for an already-connected network arrives in well under
         * a second (SPEC.md §6.6's `WIFI_CONFIRM` reasoning); this is margin.
         */
        const val WIFI_TIMEOUT_MS = 5_000L
    }
}
