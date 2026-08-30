package app.snoozemo.presence

import app.snoozemo.core.Anchor
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.Fix
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.PresenceEvent
import app.snoozemo.core.PresenceSignal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** A restart well after the arm — the gap is where the trap lives. */
    private val restartedAtMs = armedAtMs + 1_200_000L

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
    fun `a restore inside the confirmation window does not re-grant the deferral`() {
        // The persisted half of "defer at most once" (Codex, PR #106): a
        // process death inside the confirmation window restores the extended
        // deadline *and* the spent flag, and the cold Wi-Fi re-check reports
        // `PresentUnconfirmed`. The due deadline must resolve now, not defer a
        // second time — the failure this pins is a deadline that extends on
        // every reclamation and holds DND to the cap.
        val restoredDeadlineMs = armedAtMs + 30_000
        val feed = PresenceFeed(
            Anchor(ssid = "AnchorNet", capturedAt = Instant.EPOCH),
            seedElapsedRealtimeMs = armedAtMs,
            seedGraceDeadlineMs = restoredDeadlineMs,
            seedConfirmationDeferralUsed = true,
        )

        feed.accept(PresenceSignal.AnchorWifiPresentUnconfirmed(armedAtMs + 1_000))
        val update = feed.accept(PresenceSignal.GraceElapsed(restoredDeadlineMs))

        assertEquals(PresenceEvent.Departed, update.event)
    }

    @Test
    fun `a restore with an unspent deferral still defers once`() {
        // The complement: a restart *before* the window was ever entered
        // carries the flag false, so the first due firing after a
        // `PresentUnconfirmed` still gets its single deferral.
        val restoredDeadlineMs = armedAtMs + 60_000
        val feed = PresenceFeed(
            Anchor(ssid = "AnchorNet", capturedAt = Instant.EPOCH),
            seedElapsedRealtimeMs = armedAtMs,
            seedGraceDeadlineMs = restoredDeadlineMs,
            seedConfirmationDeferralUsed = false,
        )

        feed.accept(PresenceSignal.AnchorWifiPresentUnconfirmed(armedAtMs + 1_000))
        val update = feed.accept(PresenceSignal.GraceElapsed(restoredDeadlineMs))

        assertNull("the first firing defers rather than ending", update.event)
        assertTrue("grace still runs on the extended deadline", update.graceActive)
    }

    @Test
    fun `graceActive is true the instant a Wi-Fi-only anchor loses its network`() {
        // TrackingMode.WIFI_GRACE's whole reason to exist: an anchor with no
        // usable fix has grace start the moment Wi-Fi is lost, before enough
        // failed observations ever accumulate for `degradation` to move off
        // null — so a caller reading `degradation` alone would still see a
        // healthy update on this exact delivery.
        val feed = PresenceFeed(
            Anchor(ssid = "AnchorNet", capturedAt = Instant.EPOCH),
            seedElapsedRealtimeMs = armedAtMs,
        )

        val update = feed.accept(PresenceSignal.AnchorWifiLost(armedAtMs + 5_000))

        assertNull("no location to fail yet", update.degradation)
        assertTrue("grace started on this very delivery", update.graceActive)
    }

    @Test
    fun `graceActive is false once a genuine Wi-Fi return clears the deadline`() {
        val restoredDeadlineMs = armedAtMs + 60_000
        val feed = PresenceFeed(
            Anchor(ssid = "AnchorNet", capturedAt = Instant.EPOCH),
            seedElapsedRealtimeMs = armedAtMs,
            seedGraceDeadlineMs = restoredDeadlineMs,
        )

        val update = feed.accept(PresenceSignal.AnchorWifiAssociated(armedAtMs + 5_000))

        assertFalse("Wi-Fi is back; nothing left for the timer to bound", update.graceActive)
    }

    /**
     * The restart case (Codex, PR #141). On `play` the process dies between
     * wakes, so a monitor is rebuilt constantly; a feed that started every
     * time at "healthy" would have its first update read as a recovery
     * nothing observed, dropping the card from `Timer only — no location`
     * back to a plain `Snoozing` moments after it was reposted.
     */
    @Test
    fun `a restored degradation survives the restart that rebuilt the feed`() {
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = armedAtMs,
            seedDegradation = DegradationCause.NO_LOCATION_FIX,
            seedDegradationAtMs = restartedAtMs,
        )

        assertEquals(DegradationCause.NO_LOCATION_FIX, feed.degradation)

        // A signal that says nothing about location must not promote it.
        val update = feed.accept(PresenceSignal.AnchorWifiAssociated(armedAtMs + 5_000))

        assertEquals(
            "Wi-Fi is not evidence about location (SPEC.md §6.1)",
            DegradationCause.NO_LOCATION_FIX,
            update.degradation,
        )
    }

    /**
     * The half that would have been missed by seeding the level alone: a
     * *cached* reading captured before the restart says nothing about whether
     * the failures are over, and `Presence.staleFix` compares against
     * `lastUnusableAtMs`, which a fresh feed leaves null — so every reading
     * would clear a seeded level trivially. PR #33's case, reappearing at the
     * restart boundary.
     */
    @Test
    fun `a fix from before the restart does not clear a restored degradation`() {
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = armedAtMs,
            seedDegradation = DegradationCause.FIXES_TOO_VAGUE,
            seedDegradationAtMs = restartedAtMs,
        )

        val update = feed.accept(
            PresenceSignal.FixArrived(
                // Precise, and squarely at the anchor — it would clear the
                // level on its merits. What disqualifies it is its age.
                Fix(lat = 0.0, lon = 0.0, accuracyM = 10f, elapsedRealtimeMs = restartedAtMs - 5_000),
            ),
        )

        assertEquals(
            "evidence older than the restart cannot say the failures are over",
            DegradationCause.FIXES_TOO_VAGUE,
            update.degradation,
        )
    }

    /** And the seed is not a trap: a genuinely fresh reading still clears it. */
    @Test
    fun `a fix taken after the restart clears a restored degradation`() {
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = armedAtMs,
            seedDegradation = DegradationCause.FIXES_TOO_VAGUE,
            seedDegradationAtMs = restartedAtMs,
        )

        val update = feed.accept(
            PresenceSignal.FixArrived(
                Fix(lat = 0.0, lon = 0.0, accuracyM = 10f, elapsedRealtimeMs = restartedAtMs + 30_000),
            ),
        )

        assertNull("location answered; the level is withdrawn", update.degradation)
    }

    /** A fresh arm keeps the old shape — no seed, no staleness floor. */
    @Test
    fun `an unseeded feed starts healthy`() {
        val feed = PresenceFeed(anchor, seedElapsedRealtimeMs = armedAtMs)

        assertNull(feed.degradation)
    }

    /**
     * The case that made the first floor wrong (Codex, PR #142). A snooze
     * arms healthy, banks a good fix ten minutes in, and only *then* starts
     * failing. The cached reading post-dates the arm and pre-dates the
     * trouble, so an arm-moment floor believes it and restores `FULL` on
     * evidence older than the problem — PR #33's case, which is the exact
     * thing seeding the level was meant to stop.
     */
    @Test
    fun `a fix banked between the arm and the failures cannot clear a restored level`() {
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = armedAtMs,
            seedDegradation = DegradationCause.NO_LOCATION_FIX,
            seedDegradationAtMs = restartedAtMs,
        )

        val update = feed.accept(
            PresenceSignal.FixArrived(
                Fix(
                    lat = 0.0,
                    lon = 0.0,
                    accuracyM = 10f,
                    // Comfortably after the arm, comfortably before the restart.
                    elapsedRealtimeMs = armedAtMs + 600_000,
                ),
            ),
        )

        assertEquals(
            "this process never saw it arrive, so it cannot vouch for it",
            DegradationCause.NO_LOCATION_FIX,
            update.degradation,
        )
    }

    /**
     * A level with no floor is worse than no level — it would be cleared by
     * the first reading of any age — so the pair is all-or-nothing rather
     * than silently half-applied.
     */
    @Test
    fun `a seeded cause without a floor is refused rather than half-applied`() {
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = armedAtMs,
            seedDegradation = DegradationCause.NO_LOCATION_FIX,
        )

        assertNull(feed.degradation)
    }

    /**
     * The run of failures continues across a restart; only the process ended
     * (Codex, PR #142). `Presence.useless` updates a standing cause only once
     * the count reaches the threshold, so a restored level whose counter
     * started at zero would keep asserting the old flavor — and on `play`,
     * where a wake takes one resting probe before the process is reclaimed,
     * the counter can reset before it ever gets there. The card would say
     * `weak location signal` for the rest of a snooze in which nothing
     * arrived at all.
     */
    @Test
    fun `the next failure after a restart names the flavor that just failed`() {
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = armedAtMs,
            seedDegradation = DegradationCause.FIXES_TOO_VAGUE,
            seedDegradationAtMs = restartedAtMs,
        )

        // One failure, of the other kind — all a single wake gets to take.
        val update = feed.accept(PresenceSignal.FixUnavailable(restartedAtMs + 30_000))

        assertEquals(
            "the threshold was crossed before the restart, not reset by it",
            DegradationCause.NO_LOCATION_FIX,
            update.degradation,
        )
    }

    /** A fresh arm still has to earn its level the long way. */
    @Test
    fun `an unseeded feed still needs a full run of failures to degrade`() {
        val feed = PresenceFeed(anchor, seedElapsedRealtimeMs = armedAtMs)

        assertNull(feed.accept(PresenceSignal.FixUnavailable(armedAtMs + 10_000)).degradation)
        assertNull(feed.accept(PresenceSignal.FixUnavailable(armedAtMs + 20_000)).degradation)
        assertEquals(
            DegradationCause.NO_LOCATION_FIX,
            feed.accept(PresenceSignal.FixUnavailable(armedAtMs + 30_000)).degradation,
        )
    }

    /**
     * The other half of the resumed counter (Codex, PR #142). Seeding the
     * threshold as crossed lets the next failure name the current flavor —
     * but a *cached* inconclusive reading banked before the restart is not
     * the current flavor, and would otherwise relabel `no location` as `weak
     * location signal` while nothing had arrived at all, then survive further
     * restarts saying it.
     */
    @Test
    fun `a cached vague fix from before the restart cannot relabel the cause`() {
        val feed = PresenceFeed(
            anchor,
            seedElapsedRealtimeMs = armedAtMs,
            seedDegradation = DegradationCause.NO_LOCATION_FIX,
            seedDegradationAtMs = restartedAtMs,
        )

        val update = feed.accept(
            PresenceSignal.FixArrived(
                // Too vague to place anyone, and banked after the arm but
                // before the restart — fresh by the evidence test, silent
                // about what is failing now.
                Fix(
                    lat = 0.0,
                    lon = 0.0,
                    accuracyM = 500f,
                    elapsedRealtimeMs = armedAtMs + 600_000,
                ),
            ),
        )

        assertEquals(
            DegradationCause.NO_LOCATION_FIX,
            update.degradation,
        )
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
