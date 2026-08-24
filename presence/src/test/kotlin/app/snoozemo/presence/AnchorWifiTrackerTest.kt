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
    fun `a seed read finding Wi-Fi present concludes nothing`() {
        // The regression this pins: the seed read cannot name the network it
        // found, because a direct capabilities read hands back a redacted
        // SSID. Consulting it anyway reported a loss on every arm of a
        // Wi-Fi-only snooze — a five-minute grace deadline against a phone
        // sitting on its own anchor. Wi-Fi present means "wait for the
        // callback", which owns every transition and is along momentarily.
        assertNull(tracker.onSeedRead(readSucceeded = true, anyWifiConnected = true, 1_000))
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
    fun `a seed read that concluded nothing leaves the callback free to associate`() {
        // The seed staying silent must not cost the association its
        // transition: the callback's first report is still the first thing
        // the tracker has been told.
        assertNull(tracker.onSeedRead(readSucceeded = true, anyWifiConnected = true, 1_000))

        assertEquals(
            PresenceSignal.AnchorWifiAssociated(2_000),
            tracker.onWifiSsid("\"ExampleWifi\"", 2_000),
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
