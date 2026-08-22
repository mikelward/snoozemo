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
    fun `a newer exit replaces an older one`() {
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 5_000))
        GeofenceSignalBridge.deliver(GeofenceObservation.Exit(atElapsedRealtimeMs = 6_000))

        val seen = mutableListOf<GeofenceObservation>()
        GeofenceSignalBridge.attach { seen += it }.close()

        assertEquals(listOf<GeofenceObservation>(GeofenceObservation.Exit(6_000)), seen)
    }

}
