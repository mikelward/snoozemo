package app.snoozemo.snooze

import android.content.Intent
import app.snoozemo.core.SnoozeLifecycle
import app.snoozemo.core.ZenOutcome
import app.snoozemo.tile.TileRepaintRegistry
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `ACTION_REFRESH` repaints the tile, not only the ongoing notification.
 *
 * The tile subtitle now renders an absolute end time in the device's own
 * zone/format, and a timezone change reaches the warm service as
 * `ACTION_REFRESH` (`TimeChangedReceiver`). Before this, that path reposted the
 * card but never called [SnoozeTileBridge.refresh], so a shade left open across
 * a timezone change kept claiming the old-zone time until reopened (Codex,
 * PR #281). Repainting is gated on a running snooze — the only time the tile
 * shows a time.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceTileRefreshTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val listeners = mutableListOf<TileRepaintRegistry.Repaint>()

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
    }

    @After
    fun tearDown() {
        listeners.forEach { TileRepaintRegistry.unregister(it) }
    }

    private fun listening(): IntArray {
        val counted = IntArray(1)
        val listener = TileRepaintRegistry.Repaint { counted[0]++ }
        listeners += listener
        TileRepaintRegistry.register(listener)
        return counted
    }

    @Test
    fun `a warm refresh repaints the tile while a snooze is running`() {
        val counted = listening()

        // The first start restores the record, whose ARMED transition already
        // refreshes the tile once — a cold-start artifact. The timezone-change
        // scenario is a *warm* service (restore runs once per instance), so a
        // second ACTION_REFRESH on the same instance isolates this path's own
        // refresh: it must add exactly one.
        val service = startService(
            SnoozeService.ACTION_REFRESH,
            record = snoozeFixture(now).copy(lifecycle = SnoozeLifecycle.ARMED),
        )
        val afterCold = counted[0]

        service.startCommand(0, 2)

        assertEquals(afterCold + 1, counted[0])
    }

    @Test
    fun `a refresh with no running snooze does not repaint the tile`() {
        // No record: ACTION_REFRESH is the arm-failure replay, the tile is
        // inactive, and repainting it would be needless churn — the `running`
        // guard is what keeps it off.
        val counted = listening()

        startService(SnoozeService.ACTION_REFRESH)

        assertEquals(0, counted[0])
    }

    @Test
    fun `a timezone change repaints the tile in-process, not via the service`() {
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now).copy(lifecycle = SnoozeLifecycle.ARMED))
        val counted = listening()

        TimeChangedReceiver().onReceive(appContext, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        // The receiver refreshes the tile directly; the service start it also
        // requests is shadowed here and never runs its handler, so this count
        // is the receiver's own in-process repaint — the one that survives a
        // refused background service start (Codex, PR #281).
        assertEquals(1, counted[0])
    }

    @Test
    fun `a timezone change with no snooze does not repaint the tile`() {
        val counted = listening()

        TimeChangedReceiver().onReceive(appContext, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        assertEquals(0, counted[0])
    }
}
