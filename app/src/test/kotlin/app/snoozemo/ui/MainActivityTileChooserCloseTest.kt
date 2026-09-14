package app.snoozemo.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.EndChoiceResult
import app.snoozemo.snooze.WelcomeStore
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper.getMainLooper

/**
 * An end-condition **row** choice finishes the activity, collapsing back to
 * where the user was — the tile chooser and the launcher-opened app alike
 * (SPEC.md §4.4; maintainer, 2026-09-13: close after choosing an end
 * condition). `openedAsTileChooser` no longer gates the close; it still gates
 * the up-front notification ask, which the ask tests below cover.
 *
 * The close rides the service's **commit outcome** ([EndChoiceController]'s
 * `onArmOutcome`), not the record write. An arm publishes a provisional
 * non-partial `ARMING` record before it is confirmed and before a timer-only
 * arm can report `PARTIAL`, so the record transition names neither reliably;
 * only `APPLIED` from the service means "armed, nothing left to choose", while
 * `GONE`/`PARTIAL`/`REFUSED` keep the screen up (Codex, PR #284). These tests
 * drive `rows.onOutcome` — the exact seam the service's report lands on — so one
 * covers every end-condition row.
 *
 * The plain `Snooze` and `End now` buttons are **not** end-condition rows: they
 * carry no `EndChoiceOutcome`, so they have no confirmed signal to close on and
 * must stay on the screen rather than close on the bare service start (Codex,
 * PR #286). Their tests assert exactly that.
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

    @After
    fun tearDown() {
        // Process-static, so a value left here would decide the next test's ask.
        MainActivity.testNotificationRequest = null
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

    // A plain running snooze on the default cap — enough for `End now` to have
    // something to end. The duration-only mode and the stock anchor keep it off
    // any real place (`AGENTS.md` privacy floor).
    private fun runningSnooze(): ActiveSnooze {
        val now = Instant.now()
        return ActiveSnooze(
            anchor = Anchor(capturedAt = now, ssid = "ExampleWifi"),
            startedAt = now,
            capExpiresAt = now.plus(ActiveSnooze.DEFAULT_CAP),
            mode = TrackingMode.DURATION_ONLY,
        )
    }

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
    fun `the plain Snooze button does not finish the activity`() {
        // The plain `Snooze` carries no `EndChoiceOutcome`, so it has no
        // confirmed-arm signal to close on: an arm the platform accepts can
        // still be refused later, and closing on the bare service start would
        // drop the only surface saying so where notifications are denied. It
        // arms and leaves the screen up; the store observer flips it to running
        // when the record lands (Codex, PR #286).
        val activity = screen(fromTile = false)

        activity.armFromScreen()
        settle()

        assertFalse("the plain arm has no confirmed close signal, so it stays", activity.isFinishing)
    }

    @Test
    fun `End now does not finish the activity`() {
        // `End now` carries no `EndChoiceOutcome`, and the release is async — the
        // helper returns once the service start is away, not once the zen rule is
        // off — so there is no confirmed-end signal to close on. It stays on the
        // screen as the surface that shows a release the platform later rolls
        // back, even where notifications are denied (Codex, PR #286).
        ActiveSnoozeStore(context).arm(runningSnooze())
        val activity = screen(fromTile = false)

        activity.endFromScreen()
        settle()

        assertFalse("End now has no confirmed close signal, so it stays", activity.isFinishing)
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
    fun `a launcher launch finishes when a row arms`() {
        // The close is no longer gated on the launch surface: a committed choice
        // collapses the app whether it was opened from the tile or the launcher
        // (maintainer, 2026-09-13).
        val activity = screen(fromTile = false)

        activity.rows.onOutcome(EndChoiceResult.APPLIED)
        settle()

        assertTrue("an applied choice closes the app, launcher-opened or not", activity.isFinishing)
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
        // launcher-owned. Since a committed choice now closes the app whatever
        // the launch surface, tile-ownership is observable only in the up-front
        // notification ask — a tile chooser asks, a launcher launch does not.
        // So this proves the launch is derived from the intent alone: a saved
        // copy of the flag must not let the bundle's `true` override the
        // launcher intent's `false` (Codex, PR #284).
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
        val asked = booleanArrayOf(false)
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java),
        ).also {
            it.get().runOffMainThread = { work -> work() }
            it.get().requestNotifications = { asked[0] = true }
        }
        controller.create(saved).start().resume().visible()
        settle()

        assertFalse(
            "a launcher relaunch is not tile-owned, so it does not ask up front",
            asked[0],
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
    fun `a configuration recreation before the first-frame ask still fires it`() {
        // The up-front ask rides a post-first-frame callback. A rotation between
        // onCreate and that callback recomputes freshTileChooserLaunch as false;
        // without carrying the obligation across the recreation the ask is dropped
        // and a tile-first user silently loses the ongoing card (Codex, PR #284).
        //
        // Observed through the companion seam, not a per-instance one: `recreate()`
        // dispatches the recreated instance's first-frame callback before a test
        // can set a field on it, so the default request routes through the hook.
        val asked = booleanArrayOf(false)
        MainActivity.testNotificationRequest = { asked[0] = true }
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TILE_CHOOSER, true),
        ).also { it.get().runOffMainThread = { work -> work() } }
        // Resume without `visible()`/`settle()`, so the original's first-frame ask
        // has not fired — its obligation is still owed and saved on the recreation.
        controller.create().start().resume()
        assertFalse("precondition: the original has not asked yet", asked[0])

        // A configuration recreation: the retained ViewModelStore makes
        // wasRecreatedByConfiguration true, so freshTileChooserLaunch is false and
        // only the restored owed flag can re-schedule the ask.
        controller.recreate()
        settle()

        assertTrue("the ask survives a recreation before its first frame", asked[0])
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
