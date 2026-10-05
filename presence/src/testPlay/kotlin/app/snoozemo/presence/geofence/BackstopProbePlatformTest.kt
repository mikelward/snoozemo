package app.snoozemo.presence.geofence

import app.snoozemo.core.PresenceSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `only a stated reason becomes a degradation`() {
        val cause = BackstopProbePlatform.Companion::unavailableCause
        assertEquals(
            "fine location still held: only the background half went",
            app.snoozemo.core.DegradationCause.NO_LOCATION_IN_BACKGROUND,
            cause(true, false, true),
        )
        assertEquals(app.snoozemo.core.DegradationCause.LOCATION_PERMISSION_GONE, cause(true, false, false))
        assertEquals(app.snoozemo.core.DegradationCause.LOCATION_SERVICES_OFF, cause(false, true, true))
        assertNull("a plain miss says nothing", cause(false, false, true))
    }

    @Test
    fun `an inconclusive probe can still name an outage`() {
        // Codex, PR #312: a vague fix, then `location off`, is an outage.
        val names = BackstopProbePlatform.Companion::mayNameAnOutage
        assertTrue(names(app.snoozemo.core.BackstopProbe.Outcome.NoFix))
        assertTrue(names(app.snoozemo.core.BackstopProbe.Outcome.Inconclusive))
        assertFalse(names(app.snoozemo.core.BackstopProbe.Outcome.StillHere))
        assertTrue("Wi-Fi back mid-probe leaves an earlier outage standing", names(app.snoozemo.core.BackstopProbe.Outcome.AtAnchorWifi))
    }
}
