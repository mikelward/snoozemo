package app.snoozemo.presence

import app.snoozemo.core.Anchor
import app.snoozemo.core.Fix
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.PresenceEvent
import app.snoozemo.core.PresenceSignal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stateful step both flavors' monitors share. The engine's own rules have
 * their trace tests in `:core`; these pin what the *feed* adds — the state is
 * carried between signals, the seed guards against cached readings, and the
 * update carries the level beside the event.
 */
class PresenceFeedTest {

    private val anchor = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = Instant.EPOCH,
        radiusM = 150,
    )

    private val armedAtMs = 1_000_000L

    @Test
    fun `a geofence exit escalates and turns location on`() {
        val feed = PresenceFeed(anchor, seedElapsedRealtimeMs = armedAtMs)

        val update = feed.accept(PresenceSignal.GeofenceExit(armedAtMs + 60_000))

        assertEquals(PresenceEvent.ProbablyLeft, update.event)
        assertEquals(
            "a check outranks everything in the duty cycle",
            LocationDuty.ACTIVE,
            feed.duty,
        )
    }

    @Test
    fun `a cached fix from before the arm cannot be the first evidence`() {
        // The seed is the arm moment: a last-known location Android hands out
        // from before the tile was tapped could be from anywhere the phone has
        // been, and an unambiguous stale one would end the snooze.
        val feed = PresenceFeed(anchor, seedElapsedRealtimeMs = armedAtMs)

        val update = feed.accept(
            PresenceSignal.FixArrived(
                Fix(lat = 10.0, lon = 10.0, accuracyM = 10f, elapsedRealtimeMs = armedAtMs - 5_000),
            ),
        )

        assertNull("a stale reading is old news, not a departure", update.event)
    }

    @Test
    fun `a restored grace deadline is not read as stale`() {
        // TODO.md: "the grace deadline has to survive process death". A
        // deadline seeded from disk must be believed, not treated as a fresh
        // feed's default null — otherwise a `GraceElapsed` the platform alarm
        // fired while the process was dead is read as a stale alarm and
        // dropped instead of ending the snooze.
        val restoredDeadlineMs = armedAtMs + 60_000
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = armedAtMs,
            seedGraceDeadlineMs = restoredDeadlineMs,
        )

        val update = feed.accept(PresenceSignal.GraceElapsed(atElapsedRealtimeMs = restoredDeadlineMs + 1))

        assertEquals(PresenceEvent.Departed, update.event)
    }

    @Test
    fun `a restored grace deadline still pending is preserved, not restarted`() {
        // The other half of the same bug: a live signal that would otherwise
        // compute a *fresh* five-minute window (`Presence.graceFrom`'s
        // `state.graceDeadlineMs ?: graceFrom(...)`) must find the seeded
        // deadline already occupying that slot, or a restore silently resets
        // the countdown instead of resuming it.
        val restoredDeadlineMs = armedAtMs + 60_000
        val feed = PresenceFeed(
            Anchor(ssid = "AnchorNet", capturedAt = Instant.EPOCH),
            seedElapsedRealtimeMs = armedAtMs,
            seedGraceDeadlineMs = restoredDeadlineMs,
        )

        // Wi-Fi still away is exactly what the restore-time re-observation
        // (`PlatformWifiWatch`'s cold re-check) redelivers.
        feed.accept(PresenceSignal.AnchorWifiLost(armedAtMs + 5_000))

        assertEquals(
            "the original deadline stands, not a new one from the redelivery",
            restoredDeadlineMs,
            feed.graceDeadlineMs,
        )
    }

    @Test
    fun `a genuine Wi-Fi return clears a restored deadline instead of being swallowed as a duplicate`() {
        // The seed must not also claim `atAnchorWifi = true` alongside a
        // restored deadline (Codex, PR #91, fifth pass): `Presence.associated`
        // treats an already-true state as a repeat and no-ops
        // (`if (state.atAnchorWifi) return step(state, null, anchor)`), so a
        // wrongly "already associated" seed would have silently dropped the
        // one signal that should have cleared this deadline — the user
        // actually returned, and the engine would never have found out.
        val restoredDeadlineMs = armedAtMs + 60_000
        val feed = PresenceFeed(
            Anchor(ssid = "AnchorNet", capturedAt = Instant.EPOCH),
            seedElapsedRealtimeMs = armedAtMs,
            seedGraceDeadlineMs = restoredDeadlineMs,
        )

        feed.accept(PresenceSignal.AnchorWifiAssociated(armedAtMs + 5_000))

        assertNull("a real return to the anchor's Wi-Fi calls grace off", feed.graceDeadlineMs)
    }

    @Test
    fun `state carries across signals`() {
        val feed = PresenceFeed(anchor, seedElapsedRealtimeMs = armedAtMs)
        feed.accept(PresenceSignal.GeofenceExit(armedAtMs + 60_000))

        // A second exit while already checking is not news: the engine reports
        // transitions once, and the feed carrying its state is what makes that
        // hold across calls.
        val second = feed.accept(PresenceSignal.GeofenceExit(armedAtMs + 65_000))

        assertNull(second.event)
        assertEquals(LocationDuty.ACTIVE, feed.duty)
    }
}
