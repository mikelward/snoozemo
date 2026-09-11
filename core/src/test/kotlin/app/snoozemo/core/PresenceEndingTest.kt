package app.snoozemo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which presence reports may end a given snooze (SPEC.md §4.4, D7).
 *
 * One function answers this because two sites act on these reports — the
 * controller, which ends, and the service, which escalates an end the
 * platform refused. Both directions are asserted for every event: a gate that
 * withheld an ending from a snooze still watching for a departure would leave
 * the phone quiet with nothing left to release it, which is the failure D7
 * exists to prevent, and is the opposite of the one this gate is for.
 */
class PresenceEndingTest {

    private val start: Instant = Instant.parse("2026-09-11T09:00:00Z")

    // Stock stand-ins, never a device capture (AGENTS.md, *Privacy*).
    private val anchor = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 25f,
        capturedAt = start,
        ssid = "ExampleWifi",
    )

    private fun snooze(endsOnDeparture: Boolean) = ActiveSnooze(
        anchor = anchor,
        startedAt = start,
        capExpiresAt = start.plus(ActiveSnooze.DEFAULT_CAP),
        mode = TrackingMode.from(anchor),
        endsOnDeparture = endsOnDeparture,
    )

    private val lost = PresenceEvent.CapabilityLost(CapabilityLossCause.MONITORING_UNAVAILABLE)

    @Test
    fun `a snooze that still ends on leaving ends on both confirmations`() {
        val watching = snooze(endsOnDeparture = true)

        assertEquals(EndReason.DEPARTURE, PresenceEvent.Departed.endReasonFor(watching))
        assertEquals(EndReason.LOST_CAPABILITY, lost.endReasonFor(watching))
    }

    @Test
    fun `a snooze narrowed to its timer ends on neither`() {
        // The watch is down by then, so ordinarily nothing arrives — but a
        // geofence already in flight, or an exit held across a restore, can
        // still land after the choice. Ending on one would end the phone's
        // silence early on a condition the user had just replaced, and the cap
        // is still armed and is still what guarantees the snooze ends.
        val timerOnly = snooze(endsOnDeparture = false)

        assertNull(PresenceEvent.Departed.endReasonFor(timerOnly))
        assertNull(lost.endReasonFor(timerOnly))
    }

    @Test
    fun `nothing below a confirmation ends anything, whatever the snooze ends on`() {
        // Escalation and de-escalation move the state and never end a snooze
        // (SPEC.md §6.10) — so neither may reach the service's escalation
        // either, which is the reason this question is asked in one place.
        for (watching in listOf(true, false)) {
            val subject = snooze(endsOnDeparture = watching)
            assertNull(PresenceEvent.StillHere.endReasonFor(subject))
            assertNull(PresenceEvent.ProbablyLeft.endReasonFor(subject))
        }
    }
}
