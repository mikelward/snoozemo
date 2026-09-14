package app.snoozemo.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.WelcomeStore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper.getMainLooper

/**
 * Opened from the tile as the chooser, a row that arms finishes the activity;
 * opened from the launcher, the same arm leaves it open (SPEC.md §4.4;
 * [EXTRA_TILE_CHOOSER], maintainer's tile-vs-launcher UX).
 *
 * The close rides [MainActivity.refreshSnoozing]'s idle→running transition,
 * which every start path reaches by writing the record this observes — so one
 * test covers the plain arm and every end-condition row alike, rather than one
 * per button. The arm itself is stood in for by writing the record directly,
 * which is exactly what the service does on a successful arm; a *refused* arm
 * never writes one, and the idle-stays-open case below stands for it.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTileChooserCloseTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        // Past the welcome flow, so the chooser is the screen rather than a card.
        WelcomeStore(context).markSeen()
        ActiveSnoozeStore(context).clear()
    }

    private fun running(): ActiveSnooze {
        val now = Instant.now()
        return ActiveSnooze(
            anchor = Anchor(capturedAt = now, ssid = "ExampleWifi"),
            startedAt = now,
            capExpiresAt = now.plus(ActiveSnooze.DEFAULT_CAP),
            mode = TrackingMode.DURATION_ONLY,
            capCeilingAt = now.plus(ActiveSnooze.DEFAULT_CAP),
        )
    }

    /**
     * The record read runs inline so the transition settles here rather than on
     * a thread — the ordering is explicit, not waited on (`AGENTS.md`).
     */
    private fun screen(fromTile: Boolean): MainActivity {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TILE_CHOOSER, fromTile)
        return Robolectric.buildActivity(MainActivity::class.java, intent).also {
            it.get().runOffMainThread = { work -> work() }
            it.setup()
        }.get()
    }

    private fun settle() = shadowOf(getMainLooper()).idle()

    @Test
    fun `a tile-chooser launch finishes when a row arms`() {
        val activity = screen(fromTile = true)
        assertFalse("precondition: idle chooser stays open", activity.isFinishing)

        // Every arm path ends in this record write; the observer would fire it
        // in production, so drive the same transition directly.
        ActiveSnoozeStore(context).arm(running())
        activity.refreshSnoozingForTest()
        settle()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a launcher launch stays open when a row arms`() {
        val activity = screen(fromTile = false)

        ActiveSnoozeStore(context).arm(running())
        activity.refreshSnoozingForTest()
        settle()

        assertFalse("a launcher-opened app flips to running in place", activity.isFinishing)
    }

    @Test
    fun `a tile-chooser intent reusing the activity routes to the main chooser`() {
        // singleTask reuse: an app sitting on Settings that a tile-chooser tap
        // reaches via onNewIntent has to land on Main's chooser rows, not stay
        // on Settings with nothing to choose (Codex, PR #284).
        val controller = Robolectric.buildActivity(MainActivity::class.java).also {
            it.get().runOffMainThread = { work -> work() }
        }.setup()
        controller.get().screen = Screen.SETTINGS

        controller.newIntent(
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TILE_CHOOSER, true),
        )

        assertEquals(Screen.MAIN, controller.get().screen)
    }

    @Test
    fun `a plain intent reusing the activity leaves the screen alone`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).also {
            it.get().runOffMainThread = { work -> work() }
        }.setup()
        controller.get().screen = Screen.SETTINGS

        controller.newIntent(Intent(context, MainActivity::class.java))

        assertEquals(Screen.SETTINGS, controller.get().screen)
    }

    @Test
    fun `a tile-chooser launch stays open while nothing has armed`() {
        // A refused arm writes no record, so the transition never fires and the
        // screen stays to show why (SPEC.md §4.2). The idle refresh stands for
        // it: no record, no close.
        val activity = screen(fromTile = true)

        activity.refreshSnoozingForTest()
        settle()

        assertFalse(activity.isFinishing)
    }
}
