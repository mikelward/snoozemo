package app.snoozemo.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.SnoozeService
import app.snoozemo.snooze.snoozeFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A calendar grant that arrives from Settings rather than a runtime prompt
 * (SPEC.md §4.3).
 *
 * The `BLOCKED` row opens application settings, so there is no result callback
 * to notice the grant — only this screen's own next reading. Without a repost
 * from here, a snooze already running keeps a card built when the calendar was
 * unreadable, and on a duration-only snooze nothing rebuilds it again before
 * the cap (Codex, PR #156).
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityCalendarGrantTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearRecord() {
        // Every test below states its own snooze state; a record left by a
        // sibling would silently turn the first-read repost on or off.
        ActiveSnoozeStore(app).clear()
    }

    /** Runs the deferred first-frame reads the screen queues on every start. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The next `SnoozeService` start this screen asked for, if any. */
    private fun nextServiceAction(): String? {
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: return null
            if (intent.component?.className == SnoozeService::class.java.name) return intent.action
        }
    }

    private fun drainStartedServices() {
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @Test
    fun `a grant taken in Settings reposts the running card`() {
        // A card only needs rebuilding when there is one, so the transition
        // this is about only arises over a running snooze.
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        // Everything the screen did while starting is not what this is about.
        drainStartedServices()

        // The trip to Settings, and back.
        controller.pause().stop()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        controller.start().resume()
        settle()

        assertEquals(
            "returning with the grant has to repost, or the card keeps its two actions",
            SnoozeService.ACTION_REFRESH,
            nextServiceAction(),
        )
    }

    @Test
    fun `a revocation taken in Settings reposts too`() {
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        // The other direction, and it has no in-app route at all — a revocation
        // only ever happens in Settings, so this reading is the only thing that
        // can see it. Left unreposted, `Until <time>` stands over a calendar
        // Snoozemo can no longer read, which the offer cache's own key promises
        // it will not (Codex, PR #156).
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        drainStartedServices()

        controller.pause().stop()
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        controller.start().resume()
        settle()

        assertEquals(
            "a revoked calendar has to reach the card too",
            SnoozeService.ACTION_REFRESH,
            nextServiceAction(),
        )
    }

    @Test
    fun `a first read with a snooze already running reposts`() {
        // The tile arms without this screen ever existing (SPEC.md §4.2), so a
        // card can already be up — built and cached under whatever the
        // permission was then. A grant taken in system App info before the app
        // is first opened reaches this screen with no previous reading to
        // compare against, and would otherwise never reach the card (Codex,
        // PR #156).
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

        Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()

        assertEquals(
            "a first read over a running snooze has to rebuild the card it did not post",
            SnoozeService.ACTION_REFRESH,
            nextServiceAction(),
        )
    }

    @Test
    fun `a first read with no snooze running reposts nothing`() {
        // The other half: with nothing running there is no card to rebuild, and
        // starting the service on every app open would be work for nobody.
        ActiveSnoozeStore(app).clear()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

        Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()

        assertNull("nothing is running, so there is nothing to repost", nextServiceAction())
    }

    @Test
    fun `a grant with no snooze running reposts nothing`() {
        // `ACTION_REFRESH` with no record replays the last arm failure, and
        // failing that checks policy access and posts `Couldn't snooze`. On a
        // fresh setup that would raise a card about an arm nobody attempted,
        // over a tap that was only a permission grant (Codex, PR #156).
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        drainStartedServices()

        controller.pause().stop()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        controller.start().resume()
        settle()

        assertNull("there is no card to rebuild, so nothing should be started", nextServiceAction())
    }

    @Test
    fun `an ordinary resume with the permission long held reposts nothing`() {
        // The other half: a repost on every resume would rebuild the card for
        // no reason, and re-ask the calendar each time the user opens the app.
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        drainStartedServices()

        controller.pause().stop().start().resume()
        settle()

        assertNull("nothing changed, so nothing should be reposted", nextServiceAction())
    }
}
