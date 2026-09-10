package app.snoozemo.presence

import app.snoozemo.core.PresenceSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the presence engine's own wake-ups say in the debug log (SPEC.md §4.6).
 *
 * The lines themselves are written from Android callbacks — a
 * `ConnectivityManager` watch and a `BroadcastReceiver` over a Play Services
 * payload — which is why the *choices* live in pure functions and are pinned
 * here. Each case below is a distinction that would read as its opposite if it
 * collapsed, which is the only reason it costs a line at all.
 */
class PresenceTraceLineTest {

    private fun at(ms: Long) = ms

    @Test
    fun `a missing crossing time is recorded as missing, not as a prompt delivery`() {
        // The regression this exists for: the receiver's fallback for an
        // absent crossing time is the delivery time itself, so a downstream
        // subtraction reports ≈0 lag for exactly the events that carried no
        // timing — clearing the "delivered late" case on the evidence it most
        // lacks (Codex, PR #232).
        val absent = geofenceExitDeliveryNote(crossedAtMs = null, deliveredAtMs = at(90_000))

        assertEquals("geofence exit delivered; platform attached no crossing time", absent)
        // And in particular it must not read as a lag of any size, least of all zero.
        assertTrue(absent, !absent.contains("ms after the crossing"))
    }

    @Test
    fun `a crossing time is recorded as the lag it implies`() {
        assertEquals(
            "geofence exit delivered 4200 ms after the crossing",
            geofenceExitDeliveryNote(crossedAtMs = at(85_800), deliveredAtMs = at(90_000)),
        )
        // A prompt delivery is a real reading and says so — which is the
        // reading the absent case must not be mistaken for.
        assertEquals(
            "geofence exit delivered 0 ms after the crossing",
            geofenceExitDeliveryNote(crossedAtMs = at(90_000), deliveredAtMs = at(90_000)),
        )
    }

    @Test
    fun `both kinds of anchor Wi-Fi loss are named, and differently`() {
        // A loss is fail-open, so "the network went away" and "nothing could
        // answer, so assume it did" arrive as the same signal; the log is the
        // only place a bug report can tell which one ended a snooze.
        val observed = anchorWifiTraceLine(PresenceSignal.AnchorWifiLost(at(1_000), observed = true))
        val assumed = anchorWifiTraceLine(PresenceSignal.AnchorWifiLost(at(1_000), observed = false))

        assertEquals("anchor Wi-Fi lost: absence confirmed", observed)
        assertEquals("anchor Wi-Fi lost: could not tell, failing open to gone", assumed)
        assertNotEquals(observed, assumed)
    }

    @Test
    fun `both kinds of anchor Wi-Fi presence are named, and differently`() {
        // Presence was silent until now, so a trace could see the suppressor
        // let go but never see it take hold. The two are not one: association
        // is the suppressor with evidence behind it, while the seed's
        // unconfirmed form settles nothing and only makes a due grace deadline
        // wait once for the callback that can name the network.
        val associated = anchorWifiTraceLine(PresenceSignal.AnchorWifiAssociated(at(1_000)))
        val unconfirmed =
            anchorWifiTraceLine(PresenceSignal.AnchorWifiPresentUnconfirmed(at(1_000)))

        assertEquals("anchor Wi-Fi present: association confirmed", associated)
        assertEquals("anchor Wi-Fi present: on some network, name still unread", unconfirmed)
        assertNotEquals(associated, unconfirmed)
    }

    @Test
    fun `a signal this watch does not own has no line`() {
        // The watch emits only its own signals, but the mapping is total, and
        // a line invented for someone else's signal would be a second, worse
        // record of an event that already logs where it happens.
        assertNull(anchorWifiTraceLine(PresenceSignal.GeofenceExit(at(1_000))))
        assertNull(anchorWifiTraceLine(PresenceSignal.SignificantMotion(at(1_000))))
    }

    @Test
    fun `no line names a network`() {
        // The floor: the SSID never leaves `AnchorWifiTracker` (AGENTS.md,
        // *Privacy*). Nothing here is built from one, and this is what keeps
        // that true as the lines are edited.
        val lines = listOf(
            PresenceSignal.AnchorWifiAssociated(at(1_000)),
            PresenceSignal.AnchorWifiPresentUnconfirmed(at(1_000)),
            PresenceSignal.AnchorWifiLost(at(1_000), observed = true),
            PresenceSignal.AnchorWifiLost(at(1_000), observed = false),
        ).mapNotNull(::anchorWifiTraceLine)

        assertEquals(4, lines.size)
        lines.forEach { assertTrue(it, it.startsWith("anchor Wi-Fi ")) }
    }
}
