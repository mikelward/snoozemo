package app.snoozemo.tile

import app.snoozemo.core.AnchorCapture
import app.snoozemo.core.TrackingMode
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the tile reads the persisted tracking mode.
 *
 * The mode crosses to the tile as text, so this reader is invisible to the
 * compiler from `TrackingMode`'s declaration — which is how the shade came to
 * say `Timer only` over a snooze that was still capturing its anchor, while
 * the ongoing notification beside it said the opposite (Codex, PR #221).
 * Every member is asserted by name here, so a new one has to arrive with a
 * decision about the shade rather than falling into whichever branch the old
 * string comparison happened to pick.
 */
class TileSnapshotModeTest {

    /** An arbitrary fixed wall-clock instant; nothing here depends on real time. */
    private val STARTED_AT = 1_700_000_000_000L

    /** Armed a moment ago, so a capture written on the record is still live. */
    private fun justArmed(mode: String?) =
        TileSnapshot.claimsTimerOnly(mode, startedAtMillis = STARTED_AT, nowMillis = STARTED_AT + 500)

    @Test
    fun `only a settled duration-only snooze is qualified as a timer`() {
        assertTrue(justArmed(TrackingMode.DURATION_ONLY.name))
    }

    /**
     * The tile's half of `ActiveSnooze.effectiveMode`. A snooze narrowed to
     * its timer keeps the capability mode it was tracking with — deliberately,
     * so `Until I leave` can put the exit back — so the mode alone still reads
     * `FULL` and left the shade showing an unqualified countdown while the
     * screen and the notification both said `Timer only` (Codex, PR #267).
     *
     * Every mode, not just the tracked ones: a settling record is the case
     * that softens the qualifier on its own, and the user's answer has to
     * outrank that too — nothing is pending once they have decided it.
     */
    @Test
    fun `a snooze the user gave a time is a timer whatever it could watch`() {
        for (mode in TrackingMode.entries) {
            assertTrue(
                "mode=$mode",
                TileSnapshot.claimsTimerOnly(
                    mode.name,
                    startedAtMillis = STARTED_AT,
                    nowMillis = STARTED_AT + 500,
                    endsOnDeparture = false,
                ),
            )
        }
    }

    /**
     * An armed movement exit outranks every other input, including the
     * departure choice: `Timer only` claims nothing but the clock ends this
     * snooze, and the movement exit is not the clock.
     *
     * Newly reachable because of this change — choose a time, then tap
     * `Until I move` — and the tile alone rendered it as `Timer only` while
     * the notification and the screen both named the movement exit (Codex,
     * PR #267).
     */
    @Test
    fun `an armed movement exit is never a timer`() {
        assertFalse(
            "a chosen time plus a movement exit still ends on movement",
            TileSnapshot.claimsTimerOnly(
                TrackingMode.DURATION_ONLY.name,
                startedAtMillis = STARTED_AT,
                nowMillis = STARTED_AT + 500,
                endsOnDeparture = false,
                endsOnMotion = true,
            ),
        )
        assertTrue(
            "and without it the same record is a timer",
            TileSnapshot.claimsTimerOnly(
                TrackingMode.DURATION_ONLY.name,
                startedAtMillis = STARTED_AT,
                nowMillis = STARTED_AT + 500,
                endsOnDeparture = false,
                endsOnMotion = false,
            ),
        )
    }

    /**
     * The default a record written before the flag existed reads as, matching
     * the record's own: departure tracking was not something a user could
     * switch off then, so an old record still ends on leaving and its mode is
     * the whole answer.
     */
    @Test
    fun `a record with no flag is read as still watching`() {
        assertFalse(
            TileSnapshot.claimsTimerOnly(
                TrackingMode.FULL.name,
                startedAtMillis = STARTED_AT,
                nowMillis = STARTED_AT + 500,
                endsOnDeparture = true,
            ),
        )
    }

    @Test
    fun `a watched snooze shows its countdown unqualified`() {
        assertFalse(justArmed(TrackingMode.FULL.name))
        assertFalse(justArmed(TrackingMode.WIFI_ONLY.name))
        assertFalse(justArmed(TrackingMode.WIFI_GRACE.name))
    }

    @Test
    fun `an arm still capturing claims nothing in the shade`() {
        // The failure this pins: `mode != "FULL"` read SETTLING as
        // duration-only, so a tile in an open shade contradicted the
        // notification directly below it for the whole ~10 s capture.
        assertFalse(justArmed(TrackingMode.SETTLING.name))
    }

    @Test
    fun `a capture claim that outlived its process is not believed`() {
        // The record survives a process death; the capture does not. Past the
        // window the shade goes back to qualifying the countdown, because by
        // then nothing is watching — `startPresence` only runs once an anchor
        // lands, so a capture killed mid-window registered nothing.
        assertTrue(
            TileSnapshot.claimsTimerOnly(
                TrackingMode.SETTLING.name,
                startedAtMillis = STARTED_AT,
                nowMillis = STARTED_AT + Duration.ofMinutes(5).toMillis(),
            ),
        )
    }

    @Test
    fun `a clock moved backwards under a capture claim does not extend it`() {
        // A negative age is outside the window, deliberately: the safe
        // direction is to stop claiming a capture is running, not to keep
        // claiming it for the length of the shift.
        assertTrue(
            TileSnapshot.claimsTimerOnly(
                TrackingMode.SETTLING.name,
                startedAtMillis = STARTED_AT,
                nowMillis = STARTED_AT - Duration.ofMinutes(5).toMillis(),
            ),
        )
    }

    @Test
    fun `a capture settling on the ceiling itself is still believed`() {
        // `startedAt` is stamped before the record is saved, the card posted
        // and the backstop scheduled — the runner's ceiling starts ticking
        // after all of that — so a window tight to the ceiling would call a
        // running capture dead on a slow arm (Codex, PR #221).
        assertFalse(
            TileSnapshot.claimsTimerOnly(
                TrackingMode.SETTLING.name,
                startedAtMillis = STARTED_AT,
                nowMillis = STARTED_AT + AnchorCapture.CEILING.toMillis(),
            ),
        )
    }

    @Test
    fun `a slow arm does not have its running capture called dead`() {
        // Seconds of arm-path latency before capture even starts, then the
        // full ceiling. Erring long here costs a dead record believed a little
        // longer; erring short contradicts a live capture, which is the
        // failure this change exists to remove.
        assertFalse(
            TileSnapshot.claimsTimerOnly(
                TrackingMode.SETTLING.name,
                startedAtMillis = STARTED_AT,
                nowMillis = STARTED_AT + AnchorCapture.CEILING.toMillis() +
                    Duration.ofSeconds(15).toMillis(),
            ),
        )
    }

    @Test
    fun `a mode this build cannot read degrades the way the store does`() {
        // A record written before the mode was stored, or by a newer build.
        // `ActiveSnoozeStore` answers DURATION_ONLY for all three, and the
        // shade has to agree: reading them as "no claim" left the app and the
        // tile contradicting each other over one record, and dropping the
        // qualifier is not silence here — it is the tracked-looking rendering
        // (Codex, PR #221).
        assertTrue(justArmed("SOMETHING_NEWER"))
        assertTrue(justArmed(null))
        assertTrue(justArmed(""))
    }

    @Test
    fun `every mode is covered by name, so a new one lands here`() {
        // Guards the tests above against a member added without a decision:
        // the list is spelled out, so adding to the enum fails this until
        // someone says what the shade should do.
        val decided = setOf(
            TrackingMode.FULL,
            TrackingMode.WIFI_ONLY,
            TrackingMode.WIFI_GRACE,
            TrackingMode.DURATION_ONLY,
            TrackingMode.SETTLING,
        )
        assertTrue(
            "a TrackingMode member has no decision about the tile's subtitle: " +
                (TrackingMode.entries.toSet() - decided).toString(),
            TrackingMode.entries.toSet() == decided,
        )
    }
}
