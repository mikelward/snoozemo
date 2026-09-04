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
    fun `a withheld Wi-Fi name always names a cause`() {
        // The property that matters more than any single label: this is
        // total. The detection is the redacted read itself, so every route
        // to one has to come back with something the card can say — a
        // redaction that fell through used to latch nothing at all, and the
        // Wi-Fi loss behind it then armed a five-minute grace deadline
        // against a phone that had not moved.
        val causes = listOf(true, false).flatMap { fine ->
            listOf(true, false).flatMap { grants ->
                listOf(true, false).map { services ->
                    GeofencePresenceMonitor.redactionCause(
                        hasFineLocation = fine,
                        grantsHeld = grants,
                        servicesOn = services,
                    )
                }
            }
        }
        assertEquals(8, causes.size)
    }

    @Test
    fun `an unexplained redaction labels a cause the probes can refute`() {
        // The shape behind PR #165's third finding, pinned as a value so the
        // hazard is visible without reproducing the whole callback chain.
        //
        // The fallback fires exactly when all three probes read healthy, and
        // it must still name something (see the totality test above). But the
        // cause it names is one `grantRecheck` will restore on those same
        // healthy probes — so latching it arms the recovery watch, whose
        // "already on" sample refutes the latch immediately. That is only
        // safe because the watch reports the redaction *after* the tracker's
        // loss: `PlatformWifiWatch.deliverCurrent` orders it that way, and
        // `LocationAccessLost` withdraws a deadline rather than declining to
        // arm one. Reversing either half ends a snooze whose user has not
        // moved.
        val unexplained = GeofencePresenceMonitor.redactionCause(
            hasFineLocation = true,
            grantsHeld = true,
            servicesOn = true,
        )

        assertEquals(
            "the fallback is restorable by the probes, so ordering carries the safety",
            GrantRecheck.Restore,
            GeofencePresenceMonitor.grantRecheck(
                latched = unexplained,
                grantsHeld = true,
                hasFineLocation = true,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a registration accepted during a services outage does not lift the latch`() {
        // PR #165's fifth finding. `addGeofences` accepts a fence the platform
        // still cannot monitor, so a periodic repair succeeding mid-outage
        // used to clear the refusal and withdraw `locationAccessLost` — and
        // because the non-null slot is what arms the recovery watch, that
        // teardown took away the only thing left able to lift the latch. A
        // fix-only fenced anchor has no Wi-Fi callback to re-latch it, so the
        // card returned to `FULL` over a fence nothing could monitor.
        assertEquals(
            "a services outage is not refuted by a registration the platform accepted",
            RegistrationOutcome.Nothing,
            GeofencePresenceMonitor.registrationRefutes(
                DegradationCause.LOCATION_SERVICES_OFF,
                servicesOn = false,
            ),
        )
        assertEquals(
            "and it is refuted once the switch answers as well",
            RegistrationOutcome.Refuted,
            GeofencePresenceMonitor.registrationRefutes(
                DegradationCause.LOCATION_SERVICES_OFF,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a grant restored mid-outage renames the cause rather than holding it`() {
        // PR #165's seventh finding, and the cost of the fifth's fix. A
        // registration the platform accepts proves the grants are back; if the
        // switch is still off the outage is real, but the *label* is not — the
        // card would keep asking the user to fix a permission they have just
        // fixed, for the rest of the outage.
        //
        // The latch and the recovery watch stay; only the name moves to the
        // blocker actually left.
        DegradationCause.entries.filter { it.isGrantLoss }.forEach { cause ->
            assertEquals(
                "$cause is stale once a registration proves the grants back",
                RegistrationOutcome.Reclassify(
                    DegradationCause.LOCATION_SERVICES_OFF,
                ),
                GeofencePresenceMonitor.registrationRefutes(cause, servicesOn = false),
            )
        }
    }

    @Test
    fun `a registration refutes every withholding cause only with the switch on`() {
        // Stated over the enum rather than per cause: acceptance proves the
        // grants and nothing else, so any cause that withholds location reads
        // needs the switch to answer too. A cause added later cannot quietly
        // inherit the grant-shaped proof.
        //
        // The converse half matters as much — this must not become a gate on
        // *which* cause is latched, which is the regression PR #150 fixed. A
        // cause that does not withhold reads is refuted outright, switch or
        // no switch.
        val withholding = DegradationCause.entries.filter { it.blocksLocationReads }
        assertTrue("or this asserts nothing", withholding.isNotEmpty())

        withholding.forEach { cause ->
            assertTrue(
                "$cause must not be refuted while location is unreadable",
                GeofencePresenceMonitor.registrationRefutes(cause, servicesOn = false) !=
                    RegistrationOutcome.Refuted,
            )
            assertEquals(
                "$cause must be refuted once the switch reads on",
                RegistrationOutcome.Refuted,
                GeofencePresenceMonitor.registrationRefutes(cause, servicesOn = true),
            )
        }

        val other = DegradationCause.entries.filter { !it.blocksLocationReads }
        assertTrue("or the converse asserts nothing", other.isNotEmpty())
        other.forEach { cause ->
            assertEquals(
                "$cause is refuted by the registration alone",
                RegistrationOutcome.Refuted,
                GeofencePresenceMonitor.registrationRefutes(cause, servicesOn = false),
            )
        }
    }

    @Test
    fun `an empty registration slot is refuted by nothing`() {
        // The caller reads this as "there is something to clear", so a null
        // slot must answer `Nothing` rather than sending the handler on to
        // clear a level that was never set.
        assertEquals(
            RegistrationOutcome.Nothing,
            GeofencePresenceMonitor.registrationRefutes(null, servicesOn = true),
        )
    }

    @Test
    fun `every cause that withholds location reads is one the engine is told about`() {
        // The invariant behind PR #165's fourth finding, as a property rather
        // than a scenario. Two paths record these causes — a refused geofence
        // registration and a redacted Wi-Fi read — and the read's path skips
        // its delivery when the slot already holds the same cause. That skip
        // is sound only if the *other* writer always delivered too.
        //
        // It did not: `reportRegistration` gated on `isGrantLoss`, so a
        // registration refused for `LOCATION_SERVICES_OFF` recorded the cause
        // silently, and the redacted read behind it then found its own cause
        // already latched and returned early — after the tracker had armed
        // grace. The card said `Timer only` while the alarm ended the snooze
        // at the anchor.
        //
        // Asserted over the whole enum so a cause added later cannot land on
        // the recording side without the delivering side.
        val withholding = DegradationCause.entries.filter { it.blocksLocationReads }

        assertEquals(
            "every withholding cause must be one both writers deliver a suppressor for",
            withholding.toSet(),
            DegradationCause.entries.filter { it.isGrantLoss || it == DegradationCause.LOCATION_SERVICES_OFF }
                .toSet(),
        )
        assertTrue(
            "and the set is not empty, or this asserts nothing",
            withholding.isNotEmpty(),
        )
    }

    @Test
    fun `a withheld Wi-Fi name with no fine grant is a revoked permission`() {
        assertEquals(
            DegradationCause.LOCATION_PERMISSION_GONE,
            GeofencePresenceMonitor.redactionCause(
                hasFineLocation = false,
                grantsHeld = false,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a withheld Wi-Fi name under a while-in-use grant names the background gap`() {
        // The case the maintainer called out as first-class rather than an
        // edge: a fully-granted install where the user simply did not pick
        // "all the time". Nothing was revoked, so a permission-change story
        // would be wrong — and this flavor holds no foreground service, so
        // every read runs from the background and comes back withheld.
        assertEquals(
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
            GeofencePresenceMonitor.redactionCause(
                hasFineLocation = true,
                grantsHeld = false,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a withheld Wi-Fi name with both grants held is the services switch`() {
        // The deferred bug this closes: with both grants held the permission
        // probe finds nothing wrong and stays silent, so location switched
        // off system-wide reached the engine as a plain Wi-Fi loss and ended
        // a Wi-Fi-only snooze on grace (`TODO.md`, from PR #157).
        assertEquals(
            DegradationCause.LOCATION_SERVICES_OFF,
            GeofencePresenceMonitor.redactionCause(
                hasFineLocation = true,
                grantsHeld = true,
                servicesOn = false,
            ),
        )
    }

    @Test
    fun `a withheld Wi-Fi name every gate calls healthy still degrades`() {
        // The branch that keeps this total. Nothing known produces it today,
        // and that is exactly why it is here: the next platform gate Android
        // adds arrives as a redaction with three healthy answers behind it,
        // and the failure to avoid is latching nothing and letting the loss
        // through as a departure.
        assertEquals(
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
            GeofencePresenceMonitor.redactionCause(
                hasFineLocation = true,
                grantsHeld = true,
                servicesOn = true,
            ),
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
    fun `a services outage is lifted once the switch is back on`() {
        // Reversed from what this test used to assert (Codex, PR #165). It
        // read `Nothing`, on the reasoning that a permission read proves only
        // that two grants are held — but this decision reads the switch too,
        // so a restoration here rests on all three facts an SSID read needs.
        // Under the old behavior a `LOCATION_SERVICES_OFF` latch could never
        // be lifted on a fenceless anchor, which has no registration success
        // to lift it by: the engine's `locationAccessLost` stayed set for the
        // life of the snooze, no grace could arm, and a real departure ran
        // silently to the cap.
        assertEquals(
            GrantRecheck.Restore,
            GeofencePresenceMonitor.grantRecheck(
                latched = DegradationCause.LOCATION_SERVICES_OFF,
                grantsHeld = true,
                hasFineLocation = true,
                servicesOn = true,
            ),
        )
    }

    @Test
    fun `a services outage stays latched while the switch is still off`() {
        // The half that has to keep holding, and the reason widening the
        // predicate is safe: `servicesOn` is already a condition of the
        // restore branch, and it reads false for *unreadable* as well as
        // off — so an outage is only ever declared over once it is over.
        assertEquals(
            GrantRecheck.Nothing,
            GeofencePresenceMonitor.grantRecheck(
                latched = DegradationCause.LOCATION_SERVICES_OFF,
                grantsHeld = true,
                hasFineLocation = true,
                servicesOn = false,
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
    fun `a newer transition publishes whole`() {
        assertEquals(
            Publication.Publish,
            GeofencePresenceMonitor.publication(sequence = 2, publishedSequence = 1, event = null),
        )
        assertEquals(
            Publication.Publish,
            GeofencePresenceMonitor.publication(sequence = 2, publishedSequence = 1, event = PresenceEvent.Departed),
        )
    }

    @Test
    fun `a superseded transition keeps its event and loses its levels`() {
        // Two callbacks leave `feedLock` in one order and reach publication
        // in the other (Codex, PR #165). The older one's levels would rewind
        // what the collector already holds — a restored snooze reading
        // `Timer only` until the next signal — but its departure is still a
        // departure, and dropping it with the levels would end no snooze.
        assertEquals(
            Publication.EventOnly,
            GeofencePresenceMonitor.publication(sequence = 1, publishedSequence = 2, event = PresenceEvent.Departed),
        )
    }

    @Test
    fun `a superseded transition with nothing to say is dropped`() {
        assertEquals(
            Publication.Drop,
            GeofencePresenceMonitor.publication(sequence = 1, publishedSequence = 2, event = null),
        )
        // A repeat of the published sequence is stale too, so it can never
        // rewind the levels by being handed in twice.
        assertEquals(
            Publication.Drop,
            GeofencePresenceMonitor.publication(sequence = 2, publishedSequence = 2, event = null),
        )
    }

    @Test
    fun `a fenced anchor with an SSID rebuilds its watch on restoration too`() {
        // The watch is D4's suppressor on this shape, and a revocation poisons
        // it exactly as it does a Wi-Fi-only anchor's: redacted callbacks
        // leave the tracker at *not associated*, and the registration that
        // proves the grant back dispatches no callback. Without the rebuild a
        // real Wi-Fi departure after the re-grant read as a repeat and said
        // nothing (Codex, PR #185). The registration-success path now drives
        // this same list, so the shape has to be in it.
        val steps = GeofencePresenceMonitor.restoreSteps(
            Anchor(capturedAt = Instant.EPOCH, lat = 0.0, lon = 0.0, fixAccuracyM = 20f, ssid = "ExampleWifi"),
        )

        assertTrue(steps.contains(RestoreStep.RebuildWifiWatch))
        assertTrue(
            "and only after the restoration is declared",
            steps.indexOf(RestoreStep.RebuildWifiWatch) > steps.indexOf(RestoreStep.DeclareRestored),
        )
    }

    @Test
    fun `a grant landing re-asks a Wi-Fi-only anchor's grant directly`() {
        // No registration will ever answer for this shape, so the permission
        // read is the whole of what a grant landing can prompt — and it runs
        // whether or not anything is latched, since a grant loss on this
        // shape may have been latched by a redacted read the slot already
        // names, or not yet noticed at all.
        val anchor = Anchor(capturedAt = Instant.EPOCH, ssid = "ExampleWifi")

        assertEquals(
            listOf(GrantPokeStep.ReconcileGrants),
            GeofencePresenceMonitor.grantPokeSteps(anchor, latched = null),
        )
        assertEquals(
            "the read comes first, as it does when location comes back on",
            GrantPokeStep.ReconcileGrants,
            GeofencePresenceMonitor.grantPokeSteps(
                anchor,
                latched = DegradationCause.NO_LOCATION_IN_BACKGROUND,
            ).first(),
        )
    }

    @Test
    fun `a grant landing repairs a fenced anchor only through a latched refusal`() {
        // The fence learns its grant is back the way it learned it was gone —
        // from `addGeofences` — and keeps the repair poke's gate: a slot with
        // nothing latched is a monitor that already holds the grant as
        // present, and re-registering a healthy fence is IPC for nothing that
        // risks a mis-mapped refusal (Codex, PR #75).
        val anchor = Anchor(capturedAt = Instant.EPOCH, lat = 0.0, lon = 0.0, fixAccuracyM = 20f)

        assertTrue(GeofencePresenceMonitor.grantPokeSteps(anchor, latched = null).isEmpty())
        assertEquals(
            listOf(GrantPokeStep.RepairFence),
            GeofencePresenceMonitor.grantPokeSteps(
                anchor,
                latched = DegradationCause.LOCATION_PERMISSION_GONE,
            ),
        )
    }

    @Test
    fun `a duration-only anchor has nothing for a grant landing to re-ask`() {
        // No fence and no network name: nothing was ever read under the
        // grant, so nothing is waiting on it.
        val anchor = Anchor(capturedAt = Instant.EPOCH)

        assertTrue(
            GeofencePresenceMonitor.grantPokeSteps(
                anchor,
                latched = DegradationCause.LOCATION_PERMISSION_GONE,
            ).isEmpty(),
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
        // cause would outlive its own refutation and name the wrong remedy
        // for the rest of the snooze.
        //
        // The mode half of that reasoning is gone (Codex, PR #165):
        // `LOCATION_SERVICES_OFF` is a duration-only cause now, so an SSID
        // anchor no longer claims `WIFI_ONLY` under either of these. What
        // survives is the remedy — "turn location back on" and "grant the
        // permission" are different things to tell the user, and only one of
        // them is true here.
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
