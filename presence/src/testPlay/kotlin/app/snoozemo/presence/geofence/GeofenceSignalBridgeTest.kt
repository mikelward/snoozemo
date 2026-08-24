package app.snoozemo.presence.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceSignalBridgeTest {

    @org.junit.Before
    fun drainSharedState() {
        // The bridge is process-wide on purpose; tests share it, so each one
        // starts from an empty mailbox and a quiet wake-up.
        GeofenceSignalBridge.installWakeup { }
        GeofenceSignalBridge.resetForTest()
    }

    @Test
    fun `a due grace with no monitor is held until its check settles`() {
        // The alarm is spent — nothing will say this again — so a dead
        // process's firing must survive to the restored monitor's attach,
        // and survive a detach immediately after too: the collector might
        // not settle the check before the very next teardown, the same
        // shape as the exit's retention below (Codex, PR #77 — an earlier
        // version of this test cleared the slot right after one replay,
        // which is exactly the gap the finding closed).
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }
        GeofenceSignalBridge.deliver(GeofenceObservation.GraceElapsed(atElapsedRealtimeMs = 20_000))
        assertEquals(1, woken)

        val first = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { first += it }.close()
        val second = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { second += it }.close()

        assertTrue(first.single() is GeofenceObservation.GraceElapsed)
        assertTrue("still held until settled", second.single() is GeofenceObservation.GraceElapsed)

        GeofenceSignalBridge.settleExit()
        val third = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { third += it }.close()
        assertTrue("settled, so no longer replayed", third.isEmpty())
    }

    @Test
    fun `a held exit outranks a due grace`() {
        // Both end silence; the exit carries the evidence, so it wins the
        // one slot, and the grace re-arms off the restored engine's state.
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 21_000))
        GeofenceSignalBridge.deliver(GeofenceObservation.GraceElapsed(atElapsedRealtimeMs = 22_000))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(21_000)), seen)
    }

    @Test
    fun `a due grace outranks an availability report`() {
        GeofenceSignalBridge.deliver(GeofenceObservation.GraceElapsed(atElapsedRealtimeMs = 23_000))
        GeofenceSignalBridge.deliver(GeofenceObservation.Unavailable(atElapsedRealtimeMs = 24_000))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertEquals(
            listOf<GeofenceObservation>(GeofenceObservation.GraceElapsed(23_000)),
            seen,
        )
    }

    @Test
    fun `delivers to the attached monitor and stops after close`() {
        val seen = mutableListOf<GeofenceObservation>()
        val handle = GeofenceSignalBridge.attach { seen += it }

        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 1_000))
        handle.close()
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 2_000))

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(1_000)), seen)
    }

    @Test
    fun `a superseded monitor's late close cannot evict its replacement`() {
        // On a restart the replacement attaches before the old instance's
        // teardown runs — the same overlap the screen's watches deal with.
        val old = GeofenceSignalBridge.attach { }
        val seen = mutableListOf<GeofenceObservation>()
        val replacement = GeofenceSignalBridge.attach { seen += it }

        old.close()
        GeofenceSignalBridge.deliver(GeofenceObservation.Unavailable(atElapsedRealtimeMs = 3_000))
        replacement.close()

        assertTrue(seen.single() is GeofenceObservation.Unavailable)
    }

    @Test
    fun `an observation with no monitor waits for the next attach`() {
        // The field's common case: the geofence broadcast restarted a dead
        // process, and the restored monitor attaches moments later. The exit
        // must survive that gap — a geofence fires on crossing, so nothing
        // will ever say it again (SPEC.md §8.1; Codex, PR #73).
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 4_000))
        assertEquals(1, woken)

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()
        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(4_000)), seen)

        // And it keeps waiting: a confirmation takes at least the two-fix
        // window, so a process death inside it must find the exit still held
        // — the next restore re-escalates, and the engine's arm-time seed is
        // what retires the slot for every later snooze (Codex, PR #73).
        val again = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { again += it }.close()
        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(4_000)), again)
    }

    @Test
    fun `a held exit outranks a later availability report`() {
        // The two are not interchangeable: an exit starts the confirmation,
        // an availability report only lowers the mode — erasing the first
        // with the second would leave a departure unchecked (Codex, PR #73).
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 5_000))
        GeofenceSignalBridge.deliver(GeofenceObservation.Unavailable(atElapsedRealtimeMs = 6_000))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(5_000)), seen)
    }

    @Test
    fun `a held availability report is delivered once, never replayed`() {
        // Unlike a signal, it bypasses the engine's evidence gate: replayed,
        // it would mark every later snooze in this process degraded off a
        // report from before that snooze existed (Codex, PR #73). Only the
        // exit — the observation nothing will ever say again — is retained.
        GeofenceSignalBridge.deliver(GeofenceObservation.Unavailable(atElapsedRealtimeMs = 8_000))

        val first = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { first += it }.close()
        val second = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { second += it }.close()

        assertTrue(first.single() is GeofenceObservation.Unavailable)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `a live-delivered exit is retained until its check settles`() {
        // Live dispatch is not consumption: the confirmation it starts takes
        // at least the two-fix window, and Android routinely destroys the
        // background service inside it. A live exit that lived only in the
        // dispatch was lost with the collector — the fence never fires twice
        // for one crossing (Codex, PR #73).
        val live = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { live += it }.also {
            GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 9_000))
            it.close()
        }
        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(9_000)), live)

        val restored = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { restored += it }.close()
        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(9_000)), restored)
    }

    @Test
    fun `a live grace expiry never displaces an already-pending exit`() {
        // The exit carries the evidence a departure needs; a grace firing
        // that arrives after it — possibly stale, e.g. an alarm that fired
        // moments before its cancellation reached the platform — must not
        // unconditionally win the one slot the same way it would if nothing
        // else were pending (Codex, PR #77: the live path let this happen
        // even though the coalescing path, below, never did).
        val live = mutableListOf<GeofenceObservation>()
        val handle = GeofenceSignalBridge.attach { live += it }
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 9_600))
        GeofenceSignalBridge.deliver(GeofenceObservation.GraceElapsed(atElapsedRealtimeMs = 9_700))
        handle.close()

        val restored = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { restored += it }.close()
        assertEquals(
            "the exit must still be the one replayed",
            listOf<GeofenceObservation>(GeofenceObservation.Exit(9_600)),
            restored,
        )
    }

    @Test
    fun `a live-delivered grace expiry is retained until its check settles`() {
        // The same shape as the exit above, and for the same reason: the
        // grace alarm is one-shot and already spent, so a live-dispatched
        // expiry that lived only in the dispatch was lost with the collector
        // (Codex, PR #77).
        val live = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { live += it }.also {
            GeofenceSignalBridge.deliver(GeofenceObservation.GraceElapsed(atElapsedRealtimeMs = 9_500))
            it.close()
        }
        assertEquals(
            listOf<GeofenceObservation>(GeofenceObservation.GraceElapsed(9_500)),
            live,
        )

        val restored = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { restored += it }.close()
        assertEquals(
            listOf<GeofenceObservation>(GeofenceObservation.GraceElapsed(9_500)),
            restored,
        )
    }

    @Test
    fun `detaching mid-check wakes a successor for a held grace expiry`() {
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }
        val handle = GeofenceSignalBridge.attach { }
        GeofenceSignalBridge.deliver(GeofenceObservation.GraceElapsed(atElapsedRealtimeMs = 10_500))
        assertEquals("a live delivery needs no wake", 0, woken)

        handle.close()

        assertEquals("the detach must arrange the successor", 1, woken)
    }

    @Test
    fun `a settled grace expiry detaches quietly and is not replayed`() {
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }
        val handle = GeofenceSignalBridge.attach { }
        GeofenceSignalBridge.deliver(GeofenceObservation.GraceElapsed(atElapsedRealtimeMs = 11_500))
        GeofenceSignalBridge.settleExit()
        handle.close()

        assertEquals(0, woken)
        val next = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { next += it }.close()
        assertTrue(next.isEmpty())
    }

    @Test
    fun `detaching mid-check wakes a successor for the held exit`() {
        // The graceful-destroy twin of the dead-process wake: awaitClose runs,
        // no further broadcast will come, so the close is the last hand that
        // can arrange the restore (Codex, PR #73).
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }
        val handle = GeofenceSignalBridge.attach { }
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 10_000))
        assertEquals("a live delivery needs no wake", 0, woken)

        handle.close()

        assertEquals("the detach must arrange the successor", 1, woken)
    }

    @Test
    fun `a settled exit detaches quietly and is not replayed`() {
        // The convergence half: without it every detach after the check
        // settled would wake a service with nothing to do, restart the check,
        // and ping-pong until the snooze ended.
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }
        val handle = GeofenceSignalBridge.attach { }
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 11_000))
        GeofenceSignalBridge.settleExit()
        handle.close()

        assertEquals(0, woken)
        val next = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { next += it }.close()
        assertTrue(next.isEmpty())
    }

    @Test
    fun `settling leaves a held availability report alone`() {
        // Settle answers the exit's question only; a pending Unavailable is
        // still unsaid news for the next attach.
        GeofenceSignalBridge.deliver(GeofenceObservation.Unavailable(atElapsedRealtimeMs = 12_000))
        GeofenceSignalBridge.settleExit()

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()
        assertTrue(seen.single() is GeofenceObservation.Unavailable)
    }

    @Test
    fun `a sanity poke reaches a live monitor and is never held`() {
        // A poke is a question, not news: with no monitor it is dropped
        // without a wake — the backstop that poked also restored, and the
        // restored monitor takes its own starting probe (SPEC.md §6.10).
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }
        GeofenceSignalBridge.deliver(GeofenceObservation.SanityPoke(atElapsedRealtimeMs = 13_000))
        assertEquals(0, woken)
        val restored = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { restored += it }.close()
        assertTrue(restored.isEmpty())

        val live = mutableListOf<GeofenceObservation>()
        val handle = GeofenceSignalBridge.attach { live += it }
        GeofenceSignalBridge.deliver(GeofenceObservation.SanityPoke(atElapsedRealtimeMs = 14_000))
        handle.close()
        assertTrue(live.single() is GeofenceObservation.SanityPoke)
    }

    @Test
    fun `a newer exit replaces an older one`() {
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 5_000))
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 6_000))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(6_000)), seen)
    }

    @Test
    fun `a held exit outranks a capability loss`() {
        // The exit carries evidence that exists nowhere else until a
        // monitor consumes it; a capability loss's actual payload is
        // separately durable in `CapabilityLossStore` and re-checked
        // unconditionally on every restore, so it costs nothing to lose
        // this slot to genuine departure evidence (Codex, PR #95, second
        // pass — an earlier version let an unvalidated, possibly-stale
        // capability-loss prompt discard a real exit here).
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 30_000))
        GeofenceSignalBridge.deliver(GeofenceObservation.CapabilityLoss(atElapsedRealtimeMs = 30_100))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(30_000)), seen)
    }

    @Test
    fun `an exit displaces an already-pending capability loss`() {
        GeofenceSignalBridge.deliver(GeofenceObservation.CapabilityLoss(atElapsedRealtimeMs = 30_200))
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 30_300))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(30_300)), seen)
    }

    @Test
    fun `a capability loss with no monitor wakes the service and is held until settled`() {
        // The alarm behind it is one-shot, like the grace alarm — a dead
        // process's firing must survive to the restored monitor's attach.
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }
        GeofenceSignalBridge.deliver(GeofenceObservation.CapabilityLoss(atElapsedRealtimeMs = 31_000))
        assertEquals(1, woken)

        val first = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { first += it }.close()
        assertTrue(first.single() is GeofenceObservation.CapabilityLoss)

        GeofenceSignalBridge.settleCapabilityLoss()
        val second = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { second += it }.close()
        assertTrue("settled, so no longer replayed", second.isEmpty())
    }

    @Test
    fun `settling a stale capability loss never clears a genuinely retained exit`() {
        // `rank()` already keeps the exit over a stale capability-loss
        // prompt delivered afterward; settling the prompt as stale must not
        // then discard that preserved, still-unconfirmed evidence — the
        // exact bug a `settleExit()` call here would have reintroduced
        // (Codex, PR #95, third pass).
        val live = mutableListOf<GeofenceObservation>()
        val handle = GeofenceSignalBridge.attach { live += it }
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 32_000))
        GeofenceSignalBridge.deliver(GeofenceObservation.CapabilityLoss(atElapsedRealtimeMs = 32_100))
        GeofenceSignalBridge.settleCapabilityLoss()
        handle.close()

        val restored = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { restored += it }.close()
        assertEquals(
            "the exit must still be there to replay",
            listOf<GeofenceObservation>(GeofenceObservation.Exit(32_000)),
            restored,
        )
    }

    @Test
    fun `a Wi-Fi recheck with no monitor wakes the service`() {
        // The whole point of the alarm: no monitor is the state it was armed
        // against. A Wi-Fi-only snooze whose service Android stopped has
        // nothing listening for its network going away, so this firing has
        // to bring a reader back — dropping it the way a poke is dropped
        // would leave the snooze running to the cap after the user left.
        var woken = 0
        GeofenceSignalBridge.installWakeup { woken++ }

        GeofenceSignalBridge.deliver(GeofenceObservation.WifiRecheck(atElapsedRealtimeMs = 7_000))

        assertEquals(1, woken)
    }

    @Test
    fun `a Wi-Fi recheck is never replayed to a later attach`() {
        // It is a wake, not evidence: the restored monitor's own watch reads
        // the association fresh, so there is nothing here worth holding.
        GeofenceSignalBridge.installWakeup { }
        GeofenceSignalBridge.deliver(GeofenceObservation.WifiRecheck(atElapsedRealtimeMs = 8_000))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertTrue("a recheck is a wake, not news to replay", seen.isEmpty())
    }

    @Test
    fun `a Wi-Fi recheck never displaces a held exit`() {
        // An exit's evidence exists nowhere but this slot until a monitor
        // consumes it, and a recheck's "evidence" is nothing at all — so a
        // recheck arriving on top of one must leave it exactly where it is.
        GeofenceSignalBridge.installWakeup { }
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 9_000))
        GeofenceSignalBridge.deliver(GeofenceObservation.WifiRecheck(atElapsedRealtimeMs = 9_100))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(9_000)), seen)
    }
}
