package app.snoozemo.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.EndChoiceResult
import app.snoozemo.snooze.WelcomeStore
import java.time.Duration
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
 * The close rides the service's **commit outcome** ([EndChoiceController]'s
 * `onArmOutcome`), not the record write. An arm publishes a provisional
 * non-partial `ARMING` record before it is confirmed and before a timer-only
 * arm can report `PARTIAL`, so the record transition names neither reliably;
 * only `APPLIED`/`GONE` from the service mean "armed, nothing left to choose"
 * (Codex, PR #284). These tests drive `rows.onOutcome` — the exact seam the
 * service's report lands on — so one covers the plain arm and every
 * end-condition row alike, rather than one per button.
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

    /**
     * The refresh the outcome kicks runs inline so its transition settles here
     * rather than on a thread — the ordering is explicit, not waited on
     * (`AGENTS.md`). The chooser's up-front notification ask is stubbed out so
     * driving a frame doesn't fire the real permission launcher.
     */
    private fun screen(fromTile: Boolean): MainActivity {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TILE_CHOOSER, fromTile)
        return Robolectric.buildActivity(MainActivity::class.java, intent).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = {}
            it.setup()
        }.get()
    }

    // Advances a frame so the post-first-frame callbacks (§6.9) run, then drains
    // the looper — the chooser's ask and its outcome refresh are both deferred
    // that way.
    private fun settle() = shadowOf(getMainLooper()).idleFor(Duration.ofMillis(50))

    @Test
    fun `a tile-chooser launch finishes when a row arms`() {
        val activity = screen(fromTile = true)
        assertFalse("precondition: idle chooser stays open", activity.isFinishing)

        // The service reports the arm it accepted; that outcome, not the record
        // write, is what closes the chooser.
        activity.rows.onOutcome(EndChoiceResult.APPLIED)
        settle()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a tile-chooser launch stays open when the arm outcome is GONE`() {
        // For these idle-start rows GONE means the chosen end was NOT applied —
        // a snooze is already running, or the arm count moved under the offer
        // (SnoozeService.armAsAsked). Finishing on it would collapse the chooser
        // over a default-cap snooze as if the picked deadline took, so only
        // APPLIED closes; GONE re-reads the record and stays (Codex, PR #284).
        val activity = screen(fromTile = true)

        activity.rows.onOutcome(EndChoiceResult.GONE)
        settle()

        assertFalse("GONE did not apply the chosen end, so the chooser stays", activity.isFinishing)
    }

    @Test
    fun `a tile-chooser launch stays open when the arm lands partial`() {
        // A chosen time the service couldn't take the departure/motion exit off
        // reports PARTIAL; the rows stay up to say the snooze can still end
        // early, so the chooser must not close on it (Codex, PR #284).
        val activity = screen(fromTile = true)

        activity.rows.onOutcome(EndChoiceResult.PARTIAL)
        settle()

        assertFalse("a partial timer keeps the chooser up to explain it", activity.isFinishing)
    }

    @Test
    fun `a tile-chooser launch stays open when the service refuses`() {
        // A refusal shows why in place (SPEC.md §4.2); closing on it would drop
        // the message for a user who denied notifications.
        val activity = screen(fromTile = true)

        activity.rows.onOutcome(EndChoiceResult.REFUSED)
        settle()

        assertFalse("a refusal keeps the chooser up to show why", activity.isFinishing)
    }

    @Test
    fun `a launcher launch stays open when a row arms`() {
        val activity = screen(fromTile = false)

        activity.rows.onOutcome(EndChoiceResult.APPLIED)
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
            it.get().requestNotifications = {}
        }.setup()
        controller.get().screen = Screen.SETTINGS

        controller.newIntent(
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TILE_CHOOSER, true),
        )

        assertEquals(Screen.MAIN, controller.get().screen)
    }

    @Test
    fun `a kill-restore with a fresh tile-chooser intent routes to the chooser`() {
        // A system-kill restore hands onCreate a non-null bundle even for a
        // brand-new tile tap. Without letting the intent win, the killed
        // instance's saved screen (Settings) and flag (off) would strand the
        // tile — neither arming nor showing choices, and a row unable to close
        // (Codex, PR #284).
        val saved = android.os.Bundle()
        Robolectric.buildActivity(MainActivity::class.java, Intent(context, MainActivity::class.java))
            .also {
                it.get().runOffMainThread = { work -> work() }
                it.get().requestNotifications = {}
            }
            .setup()
            .also { it.get().screen = Screen.SETTINGS }
            .saveInstanceState(saved)

        // A fresh instance — new ViewModelStore, so the RecreationMarker is
        // absent and this is not a configuration recreation — restored from that
        // bundle and launched by a new tile-chooser intent.
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TILE_CHOOSER, true),
        ).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = {}
        }
        controller.create(saved).start().resume()
        val activity = controller.get()

        assertEquals("the new tile-chooser intent routes to Main", Screen.MAIN, activity.screen)
        // And the flag is set, so a row arm finishes.
        activity.rows.onOutcome(EndChoiceResult.APPLIED)
        settle()
        assertTrue("a row arm closes the chooser", activity.isFinishing)
    }

    @Test
    fun `a launcher relaunch of a killed tile-chooser task is not tile-owned`() {
        // Tile-chooser session, process killed, then reopened from the launcher
        // (a plain intent, no EXTRA_TILE_CHOOSER). The restored activity must be
        // launcher-owned — a row arm flips it to running in place, never finishes
        // an app the user opened from the launcher. Regression: a saved copy of
        // the flag let the bundle's `true` override the launcher intent's `false`,
        // so the launch is now derived from the intent alone (Codex, PR #284).
        val saved = android.os.Bundle()
        Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TILE_CHOOSER, true),
        ).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = {}
        }.setup().saveInstanceState(saved)

        // A fresh instance (new ViewModelStore → not a configuration recreation),
        // restored from that tile-chooser bundle but launched by a plain intent.
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java),
        ).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = {}
        }
        controller.create(saved).start().resume()
        val activity = controller.get()

        activity.rows.onOutcome(EndChoiceResult.APPLIED)
        settle()

        assertFalse(
            "a launcher relaunch is not tile-owned, so a row arm stays open",
            activity.isFinishing,
        )
    }

    @Test
    fun `a kill-restore with a fresh tile-chooser intent asks for notifications`() {
        // The process-death restore path arms and closes just like a fresh
        // launch, so it must still ask up front — the banner is never reached.
        // Gating the ask on savedInstanceState presence skipped it here, dropping
        // the ongoing card for a tile-first user (Codex, PR #284).
        val saved = android.os.Bundle()
        Robolectric.buildActivity(MainActivity::class.java, Intent(context, MainActivity::class.java))
            .also {
                it.get().runOffMainThread = { work -> work() }
                it.get().requestNotifications = {}
            }
            .setup()
            .also { it.get().screen = Screen.SETTINGS }
            .saveInstanceState(saved)

        val asked = booleanArrayOf(false)
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TILE_CHOOSER, true),
        ).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = { asked[0] = true }
        }
        // `visible()` attaches the decor so the post-first-frame Choreographer
        // callback the ask rides actually dispatches (as `setup()` does).
        controller.create(saved).start().resume().visible()
        settle()

        assertTrue("a process-death restore still asks up front", asked[0])
    }

    @Test
    fun `a plain intent reusing the activity leaves the screen alone`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = {}
        }.setup()
        controller.get().screen = Screen.SETTINGS

        controller.newIntent(Intent(context, MainActivity::class.java))

        assertEquals(Screen.SETTINGS, controller.get().screen)
    }

    @Test
    fun `a tile-chooser launch asks for notifications up front`() {
        // The chooser arms-and-closes, so the notifications banner is never
        // tapped; without this the ongoing card is silently dropped for a
        // tile-first user who has not granted it (Codex, PR #284).
        val asked = booleanArrayOf(false)
        Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TILE_CHOOSER, true),
        ).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = { asked[0] = true }
        }.setup()
        // The ask is posted past the first frame (§6.9), so advance a frame.
        settle()

        assertTrue(asked[0])
    }

    @Test
    fun `a launcher launch does not ask for notifications`() {
        val asked = booleanArrayOf(false)
        Robolectric.buildActivity(MainActivity::class.java).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = { asked[0] = true }
        }.setup()
        settle()

        assertFalse("a launcher-opened app keeps the banner instead", asked[0])
    }

    @Test
    fun `a tile-chooser launch does not ask when notifications are granted`() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val asked = booleanArrayOf(false)
        Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TILE_CHOOSER, true),
        ).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = { asked[0] = true }
        }.setup()
        settle()

        assertFalse("nothing to ask for when it is already granted", asked[0])
    }
}
