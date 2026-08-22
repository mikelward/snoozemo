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
    fun `coming back is a fresh association`() {
        tracker.onWifiSsid("\"ExampleWifi\"", 1_000)
        tracker.onWifiSsid(null, 2_000)

        assertEquals(
            PresenceSignal.AnchorWifiAssociated(3_000),
            tracker.onWifiSsid("\"ExampleWifi\"", 3_000),
        )
    }
}
