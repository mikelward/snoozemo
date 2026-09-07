package app.snoozemo.snooze

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.AnchorCapture
import app.snoozemo.core.TrackingMode
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a cold reader makes of a record still claiming a capture is running.
 *
 * The main screen loads through this store and never through
 * `SnoozeController.restore`, so resolving `SETTLING` only inside the
 * controller left the screen saying it was still waiting, over a capture
 * that had died with its process (Codex, PR #221). Robolectric rather than a
 * fake, for the reason the sibling store tests give: an in-memory double would
 * pass while nothing reached disk, and surviving the write is the mechanism.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveSnoozeStoreSettlingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Stock stand-ins, never a device capture (AGENTS.md, *Privacy*). */
    private fun arming(
        startedAt: Instant,
        anchor: Anchor = Anchor(capturedAt = startedAt),
        mode: TrackingMode = TrackingMode.SETTLING,
    ) = ActiveSnooze(
        anchor = anchor,
        startedAt = startedAt,
        capExpiresAt = startedAt.plus(Duration.ofHours(4)),
        mode = mode,
    )

    @Test
    fun `a capture still inside its window is read back as still capturing`() {
        val startedAt = Instant.now().minusMillis(500)
        ActiveSnoozeStore(context).arm(arming(startedAt))

        assertEquals(TrackingMode.SETTLING, ActiveSnoozeStore(context).load()?.mode)
    }

    @Test
    fun `a capture that outlived its process is not read back as still capturing`() {
        // The screen's path. Nothing is watching by then — `startPresence`
        // only runs once an anchor lands — so duration-only is the truth, and
        // the backstop alarm is what re-arms it.
        val startedAt = Instant.now().minus(Duration.ofMinutes(5))
        ActiveSnoozeStore(context).arm(arming(startedAt))

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(context).load()?.mode)
    }

    @Test
    fun `a stale claim resolves to what the stored anchor supports, not to the cap`() {
        // A capture that delivered a usable anchor and then died before the
        // mode was rewritten still has a watchable anchor on disk. Reading it
        // as duration-only would understate the record and drop the reason
        // the user is shown.
        val startedAt = Instant.now().minus(Duration.ofMinutes(5))
        val captured = Anchor(
            lat = 0.0,
            lon = 0.0,
            fixAccuracyM = 20f,
            capturedAt = startedAt,
            ssid = "ExampleWifi",
        )
        ActiveSnoozeStore(context).arm(arming(startedAt, captured))

        assertEquals(TrackingMode.FULL, ActiveSnoozeStore(context).load()?.mode)
    }

    @Test
    fun `the no-location settling value survives the write like its sibling`() {
        // It round-trips as a mode because it *is* one — the reason the split
        // is a second enum value rather than a flag beside it. A flag would
        // have needed its own key here, and a reader that forgot it would show
        // the wrong half.
        val startedAt = Instant.now().minusMillis(500)
        ActiveSnoozeStore(context).arm(
            arming(startedAt, mode = TrackingMode.SETTLING_AWAITING_WIFI),
        )

        assertEquals(
            TrackingMode.SETTLING_AWAITING_WIFI,
            ActiveSnoozeStore(context).load()?.mode,
        )
    }

    @Test
    fun `it expires the same way, since it is the same window`() {
        // The half a check written against one value would miss: both settling
        // modes claim a capture is running, and both die with the process that
        // made the claim.
        val startedAt = Instant.now().minus(Duration.ofMinutes(5))
        ActiveSnoozeStore(context).arm(
            arming(startedAt, mode = TrackingMode.SETTLING_AWAITING_WIFI),
        )

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(context).load()?.mode)
    }

    @Test
    fun `a capture settling on the ceiling itself is still believed`() {
        val startedAt = Instant.now().minusMillis(AnchorCapture.CEILING.toMillis())
        ActiveSnoozeStore(context).arm(arming(startedAt))

        assertEquals(TrackingMode.SETTLING, ActiveSnoozeStore(context).load()?.mode)
    }
}
