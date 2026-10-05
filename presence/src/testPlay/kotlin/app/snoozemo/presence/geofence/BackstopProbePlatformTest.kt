package app.snoozemo.presence.geofence

import app.snoozemo.core.PresenceSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The backstop probe's reading of one Wi-Fi report (SPEC.md §6.10, D4). */
class BackstopProbePlatformTest {

    private val settles = BackstopProbePlatform.Companion::settlesAssociation

    @Test
    fun `an association settles it and suppresses the probe`() {
        assertEquals(true, settles(PresenceSignal.AnchorWifiAssociated(0L)))
    }

    @Test
    fun `an observed loss settles it and lets the probe run`() {
        assertEquals(false, settles(PresenceSignal.AnchorWifiLost(0L, observed = true)))
    }

    @Test
    fun `an unobserved loss waits, since the anchor's callback may still come`() {
        // Codex, PR #310: on a device with concurrent Wi-Fi connections a
        // non-anchor network can report first.
        assertNull(settles(PresenceSignal.AnchorWifiLost(0L, observed = false)))
    }

    @Test
    fun `Wi-Fi present but unnamed waits`() {
        assertNull(settles(PresenceSignal.AnchorWifiPresentUnconfirmed(0L)))
    }
}
