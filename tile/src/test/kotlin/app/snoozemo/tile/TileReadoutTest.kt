package app.snoozemo.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileReadoutTest {

    private val started = 1_760_000_000_000L

    @Test
    fun `a readout for this snooze is read back whole`() {
        assertEquals(
            TileReadout(TileReadout.Kind.DISTANCE, "45 m"),
            TileReadout.decode(started, started, "DISTANCE", "45 m"),
        )
        assertEquals(
            TileReadout(TileReadout.Kind.ON_WIFI),
            TileReadout.decode(started, started, "ON_WIFI", null),
        )
        assertEquals(
            TileReadout(TileReadout.Kind.LEAVING),
            TileReadout.decode(started, started, "LEAVING", null),
        )
    }

    @Test
    fun `a readout left by an earlier snooze is ignored`() {
        // A process that died before clearing it must not describe the next
        // snooze, which would otherwise inherit its distance.
        assertNull(TileReadout.decode(started - 1, started, "DISTANCE", "45 m"))
    }

    @Test
    fun `nothing stored reads as nothing`() {
        assertNull(TileReadout.decode(0L, started, null, null))
        assertNull(TileReadout.decode(0L, 0L, "ON_WIFI", null))
    }

    @Test
    fun `an unreadable readout falls back to the plain exit`() {
        // A kind from a newer build, or a distance with no number, would
        // otherwise print a guess after `Until you leave`.
        assertNull(TileReadout.decode(started, started, "SOMETHING_NEW", "45 m"))
        assertNull(TileReadout.decode(started, started, "DISTANCE", null))
        assertNull(TileReadout.decode(started, started, "DISTANCE", ""))
    }

    @Test
    fun `only a distance carries one`() {
        assertEquals(
            TileReadout(TileReadout.Kind.ON_WIFI),
            TileReadout.decode(started, started, "ON_WIFI", "45 m"),
        )
    }

    private fun leaving(readout: TileReadout?) = TileSnapshot(
        snoozing = true,
        capExpiresAtMillis = started + 8 * 3_600_000L,
        timerOnly = false,
        readout = readout,
    )

    @Test
    fun `a departure snooze with a readout says it after the exit`() {
        // `Until you leave · %1$s` and its TalkBack sentence, not the plain
        // exit (Codex, PR #323: the rendering had no coverage of its own).
        val snapshot = leaving(TileReadout(TileReadout.Kind.DISTANCE, "45 m"))

        assertEquals(TileSnapshot.SubtitleKind.UNTIL_LEAVE, snapshot.subtitleKind)
        assertEquals(R.string.tile_until_leave_readout, snapshot.leaveSubtitleRes)
        assertEquals(R.string.tile_state_leave_readout, snapshot.leaveStateRes)
    }

    @Test
    fun `without a readout the exit stays plain`() {
        val snapshot = leaving(readout = null)

        assertEquals(R.string.tile_until_leave, snapshot.leaveSubtitleRes)
        assertEquals(R.string.tile_state_leave, snapshot.leaveStateRes)
    }

    @Test
    fun `each readout has its own words`() {
        // Asserted by name, so a new kind arrives with a decision about its
        // wording, and two swapped strings fail here rather than on a device.
        assertEquals(R.string.tile_readout_to_go, TileSnapshot.readoutRes(TileReadout.Kind.DISTANCE))
        assertEquals(R.string.tile_readout_on_wifi, TileSnapshot.readoutRes(TileReadout.Kind.ON_WIFI))
        assertEquals(R.string.tile_readout_leaving, TileSnapshot.readoutRes(TileReadout.Kind.LEAVING))
        assertEquals(
            TileReadout.Kind.entries.size,
            TileReadout.Kind.entries.map { TileSnapshot.readoutRes(it) }.toSet().size,
        )
    }

    @Test
    fun `a readout does not reach the other exits`() {
        // Motion has no distance, and a time or grace already names its end.
        val readout = TileReadout(TileReadout.Kind.DISTANCE, "45 m")

        assertEquals(
            TileSnapshot.SubtitleKind.UNTIL_MOVE,
            leaving(readout).copy(endsOnMotion = true).subtitleKind,
        )
        assertEquals(
            TileSnapshot.SubtitleKind.UNTIL_TIME,
            leaving(readout).copy(timerOnly = true).subtitleKind,
        )
    }
}
