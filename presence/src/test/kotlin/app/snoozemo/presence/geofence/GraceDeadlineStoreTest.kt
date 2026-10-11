package app.snoozemo.presence.geofence

import app.snoozemo.core.ClockReading
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame conversion behind [GraceDeadlineStore.save]/[load]'s deadline —
 * the only part worth a dedicated test, since the `SharedPreferences`
 * read/write either side of it is the same pattern every other store in
 * this app already exercises via its callers, and the arm-moment identity
 * `load` also checks is a direct `Long` equality with no arithmetic of its
 * own to pin (Codex, PR #91's second pass — an earlier version derived it
 * through this same clock arithmetic, which is exactly what made it
 * unstable across a clock change).
 */
class GraceDeadlineStoreTest {

    @Test
    fun `a deadline round-trips through wall time within the same boot`() {
        val now = ClockReading(wallMillis = 10_000_000L, uptimeMillis = 5_000_000L)
        val deadlineElapsedMs = now.uptimeMillis + 60_000L

        val wallMs = GraceDeadlineStore.wallMsFor(deadlineElapsedMs, now)
        val restated = GraceDeadlineStore.elapsedMsFor(wallMs, now)

        assertEquals(deadlineElapsedMs, restated)
    }

    @Test
    fun `a deadline restates correctly across a reboot`() {
        // Written five minutes before a reboot, restored a minute after it —
        // wall time is unaffected, uptime resets to (nearly) zero. This is the
        // exact case `ActiveSnooze.bootReference` exists for on the cap; here
        // the wall-only encoding survives it without a separate boot path.
        val writtenAt = ClockReading(wallMillis = 10_000_000L, uptimeMillis = 5_000_000L)
        val deadlineElapsedMs = writtenAt.uptimeMillis + 300_000L // 5 minutes out
        val wallMs = GraceDeadlineStore.wallMsFor(deadlineElapsedMs, writtenAt)

        // Two minutes of wall time passed (one to reboot, one since) and
        // uptime restarted near zero.
        val afterReboot = ClockReading(wallMillis = 10_120_000L, uptimeMillis = 60_000L)
        val restatedElapsedMs = GraceDeadlineStore.elapsedMsFor(wallMs, afterReboot)

        // Three minutes of real time were left when it was written (5 total
        // minus the 2 that passed); that's what should be left in the new
        // boot's frame too.
        assertEquals(afterReboot.uptimeMillis + 180_000L, restatedElapsedMs)
    }

    @Test
    fun `an already-overdue deadline restates in the past`() {
        val writtenAt = ClockReading(wallMillis = 10_000_000L, uptimeMillis = 5_000_000L)
        val deadlineElapsedMs = writtenAt.uptimeMillis + 60_000L
        val wallMs = GraceDeadlineStore.wallMsFor(deadlineElapsedMs, writtenAt)

        // Ten minutes later — long past the one-minute deadline.
        val now = ClockReading(wallMillis = 10_600_000L, uptimeMillis = 5_600_000L)
        val restated = GraceDeadlineStore.elapsedMsFor(wallMs, now)

        assertEquals(
            "the restated deadline is in the past, for the engine's own overdue check to catch",
            true,
            restated < now.uptimeMillis,
        )
    }

}
