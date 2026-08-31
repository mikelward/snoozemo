package app.snoozemo.presence.geofence

import app.snoozemo.core.Anchor
import app.snoozemo.core.CapabilityLossCause
import app.snoozemo.core.LocationDuty
import app.snoozemo.core.PresenceEvent
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.snoozemo.core.DegradationCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The settle decision at the duty reconcile — the pure half of the monitor,
 * pinned here because the timing bug it prevents lives in a race no JVM test
 * can schedule: `send` only queues the update, so an ending event must leave
 * the exit held for a teardown that beats the collector (Codex, PR #73).
 */
class GeofencePresenceMonitorTest {

    @Test
    fun `a refuted or degraded check settles the held exit`() {
        assertTrue(GeofencePresenceMonitor.settlesHeldExit(LocationDuty.SANITY, null))
        assertTrue(
            GeofencePresenceMonitor.settlesHeldExit(LocationDuty.SANITY, PresenceEvent.StillHere),
        )
    }

    @Test
    fun `an active check keeps the exit held`() {
        assertFalse(GeofencePresenceMonitor.settlesHeldExit(LocationDuty.ACTIVE, null))
    }

    @Test
    fun `a Wi-Fi-only anchor needs the durable recheck alarm`() {
        // No usable fix, but an SSID to re-read: the watch dies with the
        // service and there is no fence, so the alarm is the only durable
        // thing left.
        assertTrue(
            GeofencePresenceMonitor.needsWifiRecheck(
                Anchor(capturedAt = Instant.EPOCH, ssid = "ExampleWifi"),
            ),
        )
    }

    @Test
    fun `a duration-only anchor never arms the recheck alarm`() {
        // No fix and no SSID: nothing was ever watching Wi-Fi, so arming a
        // repeating restore would drain battery for a snooze with nothing to
        // check — the regression Codex caught on PR #105.
        assertFalse(GeofencePresenceMonitor.needsWifiRecheck(Anchor(capturedAt = Instant.EPOCH)))
    }

    @Test
    fun `an anchor with a usable fix never arms the recheck alarm`() {
        // A fix means a fence, and the fence is the durable watch — the
        // recheck alarm is only the no-fence stand-in.
        assertFalse(
            GeofencePresenceMonitor.needsWifiRecheck(
                Anchor(
                    capturedAt = Instant.EPOCH,
                    ssid = "ExampleWifi",
                    lat = 0.0,
                    lon = 0.0,
                    fixAccuracyM = 20f,
                ),
            ),
        )
    }

    @Test
    fun `both anchor shapes that read location watch the grant`() {
        // The bug this closes: the same revoked grant with the same
        // consequence reached only one of these. An anchor with a fix learns
        // of it when `addGeofences` is refused; a Wi-Fi-only anchor has no
        // registration to be refused, so the monitor asks the permission
        // directly — and until it did, a redacted SSID read reported a
        // departure the user never made and ended the snooze on grace.
        assertTrue(
            "an anchor with a fix registers a fence, so its grant can die under it",
            GeofencePresenceMonitor.watchesGrants(
                Anchor(
                    capturedAt = Instant.EPOCH,
                    ssid = "ExampleWifi",
                    lat = 0.0,
                    lon = 0.0,
                    fixAccuracyM = 20f,
                ),
            ),
        )
        assertTrue(
            "a Wi-Fi-only anchor needs the grant to read the SSID it depends on",
            GeofencePresenceMonitor.watchesGrants(
                Anchor(capturedAt = Instant.EPOCH, ssid = "ExampleWifi"),
            ),
        )
    }

    @Test
    fun `a duration-only anchor watches no grant`() {
        // Nothing about it reads location, so there is no signal a revoked
        // grant could corrupt — and no recheck alarm armed to ever re-ask,
        // which is what would strand a latch set against it.
        assertFalse(
            GeofencePresenceMonitor.watchesGrants(Anchor(capturedAt = Instant.EPOCH)),
        )
    }

    @Test
    fun `a restored grant lifts a grant latch`() {
        assertEquals(
            GrantRecheck.Restore,
            GeofencePresenceMonitor.grantRecheck(
                latched = DegradationCause.LOCATION_PERMISSION_GONE,
                grantsHeld = true,
                hasFineLocation = true,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a restored grant does not lift a services outage`() {
        // The asymmetry against the registration-success path, and the reason
        // this decision exists separately: a registration the platform
        // accepted proves the whole subsystem works, but a permission read
        // proves only that two grants are held. Clearing an outage on it would
        // read as repaired every fifteen minutes for as long as the outage
        // lasted.
        assertEquals(
            GrantRecheck.Nothing,
            GeofencePresenceMonitor.grantRecheck(
                latched = DegradationCause.LOCATION_SERVICES_OFF,
                grantsHeld = true,
                hasFineLocation = true,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a missing grant latches the state the card names`() {
        assertEquals(
            "fine held, background gone",
            GrantRecheck.Latch(DegradationCause.NO_LOCATION_IN_BACKGROUND),
            GeofencePresenceMonitor.grantRecheck(
                latched = null,
                grantsHeld = false,
                hasFineLocation = true,
                servicesOn = true,
            ),
        )
        assertEquals(
            "fine gone too",
            GrantRecheck.Latch(DegradationCause.LOCATION_PERMISSION_GONE),
            GeofencePresenceMonitor.grantRecheck(
                latched = null,
                grantsHeld = false,
                hasFineLocation = false,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a loss already latched under the same cause is not re-reported`() {
        // Delivered through `deliver`, which persists cleared grace state — so
        // re-reporting a steady state would rewrite it four times an hour for
        // a snooze where nothing changed.
        assertEquals(
            GrantRecheck.Nothing,
            GeofencePresenceMonitor.grantRecheck(
                latched = DegradationCause.LOCATION_PERMISSION_GONE,
                grantsHeld = false,
                hasFineLocation = false,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a loss that changes shape still moves`() {
        // Fine was granted back but background was not: the card has to stop
        // saying "grant location" and start saying "grant it in the
        // background", which a presence-only check would miss.
        assertEquals(
            GrantRecheck.Latch(DegradationCause.NO_LOCATION_IN_BACKGROUND),
            GeofencePresenceMonitor.grantRecheck(
                latched = DegradationCause.LOCATION_PERMISSION_GONE,
                grantsHeld = false,
                hasFineLocation = true,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a Wi-Fi loss under a missing grant latches rather than departs`() {
        // The live-monitor case, and the commonest one: a snooze running while
        // the user revokes location in Settings. The capabilities callback
        // fires within moments, redacted, and D7 reads that as a loss — which
        // would arm a five-minute grace deadline and end the snooze roughly
        // ten minutes before the 15-minute recheck could suppress it (Codex,
        // PR #157). So the grant is asked when a loss is reported, and the
        // same decision the recheck uses says to latch.
        assertEquals(
            GrantRecheck.Latch(DegradationCause.LOCATION_PERMISSION_GONE),
            GeofencePresenceMonitor.grantRecheck(
                latched = null,
                grantsHeld = false,
                hasFineLocation = false,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a Wi-Fi loss with the grant held is left alone`() {
        // The other half, and the one that must not regress: grants held means
        // the loss is a real departure, so nothing is latched and grace arms
        // as it always did. A check that suppressed here would silence a user
        // who genuinely left — principle 1's failure.
        assertEquals(
            GrantRecheck.Nothing,
            GeofencePresenceMonitor.grantRecheck(
                latched = null,
                grantsHeld = true,
                hasFineLocation = true,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a restored grant does not lift the latch while location services are off`() {
        // Round seven's P1. Wi-Fi identifiers are gated on the *services*
        // switch as well as the two permissions, so with system location off
        // the rebuilt watch reads a redacted SSID however healthy the grants
        // are — D7 makes that a loss, grace arms, and the snooze ends on a
        // user who never left. Staying latched costs duration-only with the
        // card saying so, bounded by the cap.
        assertEquals(
            GrantRecheck.Nothing,
            GeofencePresenceMonitor.grantRecheck(
                latched = DegradationCause.NO_LOCATION_IN_BACKGROUND,
                grantsHeld = true,
                hasFineLocation = true,
                servicesOn = false,
            ),
        )
    }

    @Test
    fun `a restored grant lifts the latch once services are back on`() {
        // The other half, and the one that must not regress: the whole point
        // of the latch is that it can be lifted, so gating it on services
        // must not make it permanent.
        assertEquals(
            GrantRecheck.Restore,
            GeofencePresenceMonitor.grantRecheck(
                latched = DegradationCause.NO_LOCATION_IN_BACKGROUND,
                grantsHeld = true,
                hasFineLocation = true,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a missing grant is still latched with services off`() {
        // Services gates the *restoration*, not the latch — a grant that is
        // genuinely gone is still worth reporting, and the card names which
        // permission is missing either way.
        assertEquals(
            GrantRecheck.Latch(DegradationCause.LOCATION_PERMISSION_GONE),
            GeofencePresenceMonitor.grantRecheck(
                latched = null,
                grantsHeld = false,
                hasFineLocation = false,
                servicesOn = false,
            ),
        )
    }

    /** Records whether it was closed, so a test can assert ownership. */
    private class FakeWatch : AutoCloseable {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `publishing a watch into a live flow installs it and closes the old one`() {
        // The baseline the guard below must not cost.
        val old = FakeWatch()
        val slot = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(old)
        val fresh = FakeWatch()

        val live = GeofencePresenceMonitor.publishWatch(slot, fresh) { false }

        assertTrue(live)
        assertTrue("the watch it replaced should be closed", old.closed)
        assertFalse("the new watch should be left running", fresh.closed)
        assertEquals(fresh, slot.get())
    }

    @Test
    fun `a watch built into a closed flow is taken back and closed`() {
        // Round eight's P2. The platform callback registers as the watch is
        // constructed, so a teardown completing before it is published closes
        // the old one and never sees this — leaving it registered with nothing
        // holding a reference to unregister it.
        val slot = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(null)
        val fresh = FakeWatch()

        val live = GeofencePresenceMonitor.publishWatch(slot, fresh) { true }

        assertFalse(live)
        assertTrue("the watch was left registered after teardown", fresh.closed)
        assertNull("and the slot must not keep pointing at it", slot.get())
    }

    @Test
    fun `a teardown that takes the watch first is left to close it`() {
        // The repair must not become a double close. The interleaving is
        // driven where the real one is — the teardown lands from inside the
        // lifecycle read, having already taken the watch out of the slot.
        val slot = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(null)
        val fresh = FakeWatch()

        val live = GeofencePresenceMonitor.publishWatch(slot, fresh) {
            // Teardown, mid-read: it won the slot and owns closing it.
            slot.set(null)
            true
        }

        assertTrue("losing the slot means the teardown owns it", live)
        assertFalse("so this path must not close it as well", fresh.closed)
    }

    @Test
    fun `a loss decided against an unmoved slot is latched and reported`() {
        // The baseline the guard below must not cost: with nothing racing, a
        // missing grant still latches and still reports, which is the whole
        // point of asking on the loss path.
        val slot = java.util.concurrent.atomic.AtomicReference<DegradationCause?>(null)
        val reported = mutableListOf<DegradationCause>()

        val acted = GeofencePresenceMonitor.latchGrantLoss(
            slot,
            grantsHeld = { false },
            hasFineLocation = { true },
            servicesOn = { true },
            report = { reported += it },
        )

        assertTrue(acted)
        assertEquals(listOf(DegradationCause.NO_LOCATION_IN_BACKGROUND), reported)
        assertEquals(DegradationCause.NO_LOCATION_IN_BACKGROUND, slot.get())
    }

    @Test
    fun `a restore landing while the grant is being read leaves the loss unreported`() {
        // The regression (Codex, PR #157, fifth pass). The permission lookups
        // are the window, so the test drives the interleaving where the real
        // one is: the restore lands from inside `grantsHeld`, clearing the slot
        // and declaring `LocationAccessRestored` on the engine.
        //
        // Reporting anyway would re-latch a loss the grant no longer supports,
        // and only `LocationAccessRestored` clears the engine's own
        // `locationAccessLost` — not the association the rebuilt watch is about
        // to report — so grace would stay shut and a real departure would run
        // to the cap. Fifteen minutes of a phone that cannot be let ring.
        val slot = java.util.concurrent.atomic.AtomicReference<DegradationCause?>(
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
        )
        val reported = mutableListOf<DegradationCause>()

        val acted = GeofencePresenceMonitor.latchGrantLoss(
            slot,
            grantsHeld = {
                // The restore, mid-read: it won the slot fair and square.
                slot.set(null)
                false
            },
            hasFineLocation = { false },
            servicesOn = { true },
            report = { reported += it },
        )

        assertFalse(acted)
        assertEquals(emptyList<DegradationCause>(), reported)
        // And the winner's value stands rather than being clobbered back.
        assertNull(slot.get())
    }

    @Test
    fun `a concurrent services outage is not overwritten by the loss it raced`() {
        // The other way to lose, and the same answer. Losing means doing
        // nothing at all: the slot keeps what the winner wrote, so an outage
        // latched in that window is not silently downgraded to a grant cause.
        val slot = java.util.concurrent.atomic.AtomicReference<DegradationCause?>(null)
        val reported = mutableListOf<DegradationCause>()

        val acted = GeofencePresenceMonitor.latchGrantLoss(
            slot,
            grantsHeld = {
                slot.set(DegradationCause.LOCATION_SERVICES_OFF)
                false
            },
            hasFineLocation = { true },
            servicesOn = { true },
            report = { reported += it },
        )

        assertFalse(acted)
        assertEquals(emptyList<DegradationCause>(), reported)
        assertEquals(DegradationCause.LOCATION_SERVICES_OFF, slot.get())
    }

    @Test
    fun `restoring the grant rebuilds the Wi-Fi watch, after declaring the restoration`() {
        // Both halves regress silently otherwise (Codex, PR #157). Dropping the
        // rebuild leaves the watch holding what the revocation wrote — a
        // tracker stuck at *not associated* and a per-network map full of the
        // redaction placeholder — so a real departure reads as a repeat and the
        // snooze stays quiet to its cap. Running it *before* the restoration is
        // the other failure: the new watch's seed read can report a loss, and
        // an engine still holding `locationAccessLost` would swallow it with
        // grace shut, missing a user who had genuinely left.
        val steps = GeofencePresenceMonitor.restoreSteps(
            Anchor(capturedAt = Instant.EPOCH, ssid = "ExampleWifi"),
        )

        assertTrue(
            "the watch must be rebuilt",
            steps.contains(RestoreStep.RebuildWifiWatch),
        )
        assertTrue(
            "and only after the restoration is declared",
            steps.indexOf(RestoreStep.RebuildWifiWatch) >
                steps.indexOf(RestoreStep.DeclareRestored),
        )
        assertEquals(
            "the restoration is what the engine hears first",
            RestoreStep.DeclareRestored,
            steps.first(),
        )
    }

    @Test
    fun `a Wi-Fi-only anchor re-asks the grant when location comes back`() {
        // The gap the services gate opened. Holding the latch until system
        // location is back on is right, but nothing then lifted it: the
        // recovery callback repaired the fence, and a Wi-Fi-only anchor has no
        // fence — so the only thing that could clear the latch was the
        // 15-minute recheck. An association cannot clear it, so a departure
        // inside that window leaves the phone quiet to its cap.
        val steps = GeofencePresenceMonitor.recoverySteps(
            Anchor(capturedAt = Instant.EPOCH, ssid = "ExampleWifi"),
        )

        assertTrue(
            "the grant must be re-asked when location comes back",
            steps.contains(RecoveryStep.ReconcileGrants),
        )
        assertEquals(
            "and before anything that asks the platform for evidence",
            RecoveryStep.ReconcileGrants,
            steps.first(),
        )
    }

    @Test
    fun `a fenced anchor's recovery does not re-ask the grant`() {
        // It learns its grant is back the way it learned it was gone — from
        // `addGeofences`, which `repairFence` is about to call. Re-asking here
        // would answer from a permission read what a registration answers
        // properly, and a registration the platform accepts proves the whole
        // subsystem rather than two grants.
        val steps = GeofencePresenceMonitor.recoverySteps(
            Anchor(capturedAt = Instant.EPOCH, lat = 0.0, lon = 0.0, fixAccuracyM = 20f),
        )

        assertFalse(steps.contains(RecoveryStep.ReconcileGrants))
        assertTrue(
            "the rest of the recovery still runs",
            steps.containsAll(
                listOf(
                    RecoveryStep.RepairFence,
                    RecoveryStep.RetryFixes,
                    RecoveryStep.SanityProbe,
                ),
            ),
        )
    }

    @Test
    fun `an anchor with no SSID has no watch to rebuild`() {
        // `getAndSet` on an empty slot would install a watch for a snooze that
        // never had one — and there is nothing a revocation could have written
        // into it to discard.
        val steps = GeofencePresenceMonitor.restoreSteps(Anchor(capturedAt = Instant.EPOCH))

        assertFalse(steps.contains(RestoreStep.RebuildWifiWatch))
        assertTrue(
            "the rest of the restoration still runs",
            steps.containsAll(
                listOf(
                    RestoreStep.DeclareRestored,
                    RestoreStep.ResumeChecking,
                    RestoreStep.RestateLevel,
                ),
            ),
        )
    }

    @Test
    fun `a services outage normally outranks a refused registration`() {
        // The rule this PR does not change: a refused registration says
        // nothing about whether the subsystem works, while a services outage
        // indicts it outright.
        assertEquals(
            DegradationCause.LOCATION_SERVICES_OFF,
            GeofencePresenceMonitor.platformLevelOf(
                registration = DegradationCause.NOTHING_WATCHING,
                services = DegradationCause.LOCATION_SERVICES_OFF,
            ),
        )
    }

    @Test
    fun `a missing grant outranks a latched services outage`() {
        // Codex, PR #149. The services slot clears only on a delivered fix,
        // and no fix can arrive once the grant is gone — so without this the
        // cause would outlive its own refutation, name the wrong remedy for
        // the rest of the snooze, and (because `LOCATION_SERVICES_OFF` is not
        // a duration-only cause) leave an SSID anchor claiming `WIFI_ONLY`
        // with no grant to read that SSID with.
        for (grant in listOf(
            DegradationCause.LOCATION_PERMISSION_GONE,
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
        )) {
            assertEquals(
                grant,
                GeofencePresenceMonitor.platformLevelOf(
                    registration = grant,
                    services = DegradationCause.LOCATION_SERVICES_OFF,
                ),
            )
        }
    }

    @Test
    fun `with neither slot set nothing is reported`() {
        assertNull(GeofencePresenceMonitor.platformLevelOf(registration = null, services = null))
    }

    @Test
    fun `an ending answer leaves the settling to the end itself`() {
        // The update is only queued at this point; settling now would lose a
        // confirmed departure to a teardown that beats the collector.
        assertFalse(
            GeofencePresenceMonitor.settlesHeldExit(LocationDuty.SANITY, PresenceEvent.Departed),
        )
        assertFalse(
            GeofencePresenceMonitor.settlesHeldExit(
                LocationDuty.SANITY,
                PresenceEvent.CapabilityLost(CapabilityLossCause.MONITORING_UNAVAILABLE),
            ),
        )
    }
}
