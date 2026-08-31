package app.snoozemo.presence

import app.snoozemo.core.PresenceSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decisions of D4's Wi-Fi watch, as plain values: what a platform report
 * means, and which reports are transitions. Fixture SSIDs are stock
 * stand-ins, never a device capture (AGENTS.md, *Privacy*).
 */
class AnchorWifiTrackerTest {

    private val tracker = AnchorWifiTracker("ExampleWifi")

    @Test
    fun `joining the anchor's network is the association`() {
        // The platform quotes SSIDs; the anchor stores them bare.
        assertEquals(
            PresenceSignal.AnchorWifiAssociated(1_000),
            tracker.onWifiSsid("\"ExampleWifi\"", 1_000),
        )
    }

    @Test
    fun `a watch started away from the anchor says so immediately`() {
        // The fail-open first report: a restore hours after a departure must
        // not sit resting on the arm-time association.
        assertEquals(
            PresenceSignal.AnchorWifiLost(2_000),
            tracker.onWifiSsid(null, 2_000),
        )
    }

    @Test
    fun `repeats are not transitions`() {
        tracker.onWifiSsid("\"ExampleWifi\"", 1_000)

        assertNull(tracker.onWifiSsid("\"ExampleWifi\"", 2_000))
        assertNull(tracker.onWifiSsid("\"ExampleWifi\"", 3_000))
    }

    @Test
    fun `another network is a loss, not an association`() {
        tracker.onWifiSsid("\"ExampleWifi\"", 1_000)

        assertEquals(
            PresenceSignal.AnchorWifiLost(2_000),
            tracker.onWifiSsid("\"OtherWifi\"", 2_000),
        )
    }

    @Test
    fun `a redacted SSID reads as not associated`() {
        // Redaction means location access is gone, so nothing can vouch for
        // the association — and an unvouched suppressor holding a snooze
        // quiet is the direction D7 forbids. The escalation this causes is
        // settled like any other: a fix, or the grace period.
        tracker.onWifiSsid("\"ExampleWifi\"", 1_000)

        assertEquals(
            PresenceSignal.AnchorWifiLost(2_000),
            tracker.onWifiSsid("<unknown ssid>", 2_000),
        )
    }

    @Test
    fun `a departure after a redacted read is swallowed by the stale state`() {
        // Why a restored grant rebuilds the watch rather than merely clearing
        // the latch (Codex, PR #157). Redaction leaves the tracker holding
        // *not associated*, and a grant coming back dispatches no callback of
        // its own — so this instance survives it. A real departure then reads
        // as a repeat of the loss already reported and says nothing, and a
        // Wi-Fi-only snooze stays quiet to its cap: the direction principle 1
        // refuses.
        //
        // The tracker is right to behave this way — a repeat is not a
        // transition (D7) — which is exactly why the fix belongs in the watch
        // that owns the instance, not here.
        tracker.onWifiSsid("\"ExampleWifi\"", 1_000)
        assertEquals(
            "the redacted read reports the loss",
            PresenceSignal.AnchorWifiLost(2_000),
            tracker.onWifiSsid("<unknown ssid>", 2_000),
        )

        assertNull(
            "and the real departure that follows is silent",
            tracker.onWifiSsid(null, 3_000),
        )

        // A watch rebuilt on restoration starts one of these, whose first
        // report is a transition by definition — so the same departure speaks.
        assertEquals(
            PresenceSignal.AnchorWifiLost(4_000),
            AnchorWifiTracker("ExampleWifi").onWifiSsid(null, 4_000),
        )
    }

    @Test
    fun `a seed read finding Wi-Fi present is unconfirmed, not a loss`() {
        // The regression this pins: the seed read cannot name the network it
        // found, because a direct capabilities read hands back a redacted
        // SSID. Consulting it anyway reported a loss on every arm of a
        // Wi-Fi-only snooze — a five-minute grace deadline against a phone
        // sitting on its own anchor. Wi-Fi present is `Unconfirmed`: not a
        // loss (which would spuriously escalate) and not an association (it
        // cannot claim the anchor), only a note to a due grace deadline to
        // wait for the callback that owns every transition.
        assertEquals(
            PresenceSignal.AnchorWifiPresentUnconfirmed(1_000),
            tracker.onSeedRead(readSucceeded = true, anyWifiConnected = true, 1_000),
        )
    }

    @Test
    fun `a seed read finding no Wi-Fi is a loss`() {
        // The half the seed read *can* settle without naming anything: with
        // no Wi-Fi at all there is no anchor association, and a registration
        // with no matching network never dispatches, so nothing else would
        // ever say this.
        assertEquals(
            PresenceSignal.AnchorWifiLost(1_000),
            tracker.onSeedRead(readSucceeded = true, anyWifiConnected = false, 1_000),
        )
    }

    @Test
    fun `a refused seed read fails open to a loss`() {
        assertEquals(
            PresenceSignal.AnchorWifiLost(1_000),
            tracker.onSeedRead(readSucceeded = false, anyWifiConnected = false, 1_000),
        )
    }

    @Test
    fun `an unconfirmed seed read leaves the callback free to associate`() {
        // The unconfirmed seed must not touch the tracker's association
        // state: it makes no claim about the network, so the callback's first
        // report is still the first real transition the tracker has been told.
        assertEquals(
            PresenceSignal.AnchorWifiPresentUnconfirmed(1_000),
            tracker.onSeedRead(readSucceeded = true, anyWifiConnected = true, 1_000),
        )

        assertEquals(
            PresenceSignal.AnchorWifiAssociated(2_000),
            tracker.onWifiSsid("\"ExampleWifi\"", 2_000),
        )
    }

    @Test
    fun `an unconfirmed seed read leaves the callback free to report a different network`() {
        // The other branch of the same guarantee: an unconfirmed seed on a
        // phone that is on some *other* Wi-Fi must let the callback deliver
        // the loss that names it, so a due grace deadline resolves.
        assertEquals(
            PresenceSignal.AnchorWifiPresentUnconfirmed(1_000),
            tracker.onSeedRead(readSucceeded = true, anyWifiConnected = true, 1_000),
        )

        assertEquals(
            PresenceSignal.AnchorWifiLost(2_000),
            tracker.onWifiSsid("\"OtherWifi\"", 2_000),
        )
    }

    @Test
    fun `a seed read loss is not repeated by the callback that confirms it`() {
        assertEquals(
            PresenceSignal.AnchorWifiLost(1_000),
            tracker.onSeedRead(readSucceeded = true, anyWifiConnected = false, 1_000),
        )

        assertNull(tracker.onWifiSsid(null, 2_000))
    }

    @Test
    fun `coming back is a fresh association`() {
        tracker.onWifiSsid("\"ExampleWifi\"", 1_000)
        tracker.onWifiSsid(null, 2_000)

        assertEquals(
            PresenceSignal.AnchorWifiAssociated(3_000),
            tracker.onWifiSsid("\"ExampleWifi\"", 3_000),
        )
    }
}
