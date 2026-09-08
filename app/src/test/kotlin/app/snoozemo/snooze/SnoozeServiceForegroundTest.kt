package app.snoozemo.snooze

import android.content.Intent
import android.os.Looper.getMainLooper
import app.snoozemo.core.Anchor
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenOutcome
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The foreground service that keeps a watched snooze's process alive
 * (SPEC.md §6.10).
 *
 * What it buys is the process, not the card — the ongoing notification is
 * posted either way — so these assert the *binding*: which snoozes take one,
 * which do not, and that ending gives it back rather than leaving a `Snoozing`
 * card in the shade for a snooze that is over.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceForegroundTest {

    private val now: Instant = Instant.parse("2026-08-22T10:00:00Z")

    // Stock stand-ins, never a device capture (AGENTS.md, *Privacy*).
    private val watchable = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = now,
        ssid = "ExampleWifi",
    )

    /** No fix and no network, so the monitor supports only the timer. */
    private val unwatchable = Anchor(capturedAt = now)

    /**
     * A fix but no network, so losing location leaves nothing to fall back on
     * and the snooze degrades all the way to duration-only while it runs.
     */
    private val fixOnly = Anchor(lat = 0.0, lon = 0.0, fixAccuracyM = 20f, capturedAt = now)

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("a-zen-rule-id")
        ActiveSnoozeStore(appContext).clear()
    }

    /** Arms and completes capture on one instance, so the watch is running. */
    private fun armWith(anchor: Anchor): ServiceController<TestSnoozeService> {
        val controller = startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single().invoke(anchor)
        shadowOf(getMainLooper()).idle()
        return controller
    }

    private fun emit(update: PresenceUpdate) {
        assertTrue(TestSnoozeService.presence.updates.tryEmit(update))
        shadowOf(getMainLooper()).idle()
    }

    @Test
    fun `a watched snooze runs the service in the foreground`() {
        val controller = armWith(watchable)

        assertEquals(TrackingMode.FULL, ActiveSnoozeStore(appContext).load()?.mode)
        val shadow = shadowOf(controller.get())
        assertNotNull(
            "the watch has something to keep alive, so the process is protected",
            shadow.lastForegroundNotification,
        )
        // The ongoing card itself, not a second one: two notifications for one
        // snooze is the shade telling the user two different things.
        assertEquals(SnoozeNotifications.ID_ONGOING, shadow.lastForegroundNotificationId)
    }

    @Test
    fun `a duration-only snooze takes no foreground service`() {
        // Nothing is watching — the cap alarm is this snooze's only exit and it
        // is durable on its own — so keeping the process alive buys nothing,
        // and claiming a *location* foreground service for a snooze doing no
        // location work would be a claim the app cannot back.
        val controller = armWith(unwatchable)

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertNull(shadowOf(controller.get()).lastForegroundNotification)
        // And the card is still up: the binding is what changes, not what the
        // user sees.
        assertTrue(shadeShows(stringOf(app.snoozemo.R.string.ongoing_title)))
    }

    @Test
    fun `a Wi-Fi-only watch keeps it`() {
        // Losing location is not losing the watch: Wi-Fi is still answering
        // the departure question (D4), so the process is still worth keeping.
        val controller = armWith(watchable)
        TestSnoozeService.presence.canTrackDeparture = false

        emit(PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX))

        assertEquals(TrackingMode.WIFI_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
    }

    @Test
    fun `a refused foreground service is said on the card, not only in the log`() {
        // Codex, PR #230. Without this the card goes on claiming a snooze that
        // ends when you leave while the process it needs can be reclaimed, and
        // only the debug log knows — a mode degraded in fact and not in what
        // the user is told, which is the second principle's failure.
        TestSnoozeService.refuseForeground = true

        val controller = armWith(watchable)

        assertNull(
            "nothing was taken",
            shadowOf(controller.get()).lastForegroundNotification,
        )
        assertEquals(TrackingMode.FULL, ActiveSnoozeStore(appContext).load()?.mode)
        assertTrue(
            "and the card says so",
            shadeText().contains(stringOf(app.snoozemo.R.string.ongoing_watch_unprotected)),
        )
    }

    @Test
    fun `the first card of a watched snooze does not cry refusal`() {
        // The card is built before it is posted and promotion happens on the
        // post, so "wants one and holds none" is true of every first card. The
        // clause is edge-triggered off an attempt that actually failed, which
        // is what keeps it off the arm.
        armWith(watchable)

        assertFalse(
            shadeText().contains(stringOf(app.snoozemo.R.string.ongoing_watch_unprotected)),
        )
    }

    @Test
    fun `ending gives the foreground service back and takes the card with it`() {
        val controller = armWith(watchable)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)

        controller
            .withIntent(
                Intent(appContext, TestSnoozeService::class.java)
                    .setAction(SnoozeService.ACTION_END),
            )
            .startCommand(0, 2)
        shadowOf(getMainLooper()).idle()

        val shadow = shadowOf(controller.get())
        assertTrue("the foreground service is released", shadow.isForegroundStopped)
        // With the notification, not detached from it: a `Snoozing` card left
        // behind for an ended snooze is the second principle's failure in its
        // most literal form.
        assertTrue("and its card goes with it", shadow.notificationShouldRemoved)
    }

    @Test
    fun `a refused refresh does not forget a service already held`() {
        // Codex, PR #230, the same flag from the other side. A refusal on a
        // *refresh* is not a demotion — the service stays foreground — so
        // forgetting it there meant the give-back later took its early return
        // and the location service was held for the rest of the snooze, its
        // card left in the shade after the end.
        val controller = armWith(watchable)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)

        // Still watched, so it still wants one, and this attempt fails.
        TestSnoozeService.refuseForeground = true
        TestSnoozeService.presence.canTrackDeparture = false
        emit(PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX))
        assertEquals(TrackingMode.WIFI_ONLY, ActiveSnoozeStore(appContext).load()?.mode)

        // And the card does not cry wolf while the service is still held
        // (Codex, PR #230): the refusal is real but the protection is not
        // lost, so telling the user tracking may pause would spend the
        // clause's credibility on a watch in no danger at all. Asserted here,
        // while the card is still up — after the end there is no card to read
        // and the assertion would pass whatever the predicate said.
        assertFalse(
            "the watch is still protected, so the card says nothing",
            shadeText().contains(stringOf(app.snoozemo.R.string.ongoing_watch_unprotected)),
        )

        controller
            .withIntent(
                Intent(appContext, TestSnoozeService::class.java)
                    .setAction(SnoozeService.ACTION_END),
            )
            .startCommand(0, 2)
        shadowOf(getMainLooper()).idle()

        assertEquals("the give-back still happens", 1, TestSnoozeService.foregroundExits)
        assertTrue(
            "so the service is released rather than held past the snooze",
            shadowOf(controller.get()).isForegroundStopped,
        )
    }

    @Test
    fun `a refused give-back is retried rather than stranding the service`() {
        // Codex, PR #230. The snooze degrades to duration-only while it runs,
        // so the service gives the foreground back mid-snooze — and if that
        // throws, the process may still be foreground. Marking it released
        // anyway meant every later attempt returned early: a location
        // foreground service held for a snooze doing no location work, and its
        // card left in the shade after the snooze ended.
        val controller = armWith(fixOnly)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        TestSnoozeService.presence.canTrackDeparture = false
        TestSnoozeService.refuseForegroundExit = true

        emit(PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX))

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertEquals("it tried to give it back", 1, TestSnoozeService.foregroundExits)
        assertFalse(
            "and the platform did not take it",
            shadowOf(controller.get()).isForegroundStopped,
        )

        TestSnoozeService.refuseForegroundExit = false
        controller
            .withIntent(
                Intent(appContext, TestSnoozeService::class.java)
                    .setAction(SnoozeService.ACTION_END),
            )
            .startCommand(0, 2)
        shadowOf(getMainLooper()).idle()

        assertEquals("so it tries again", 2, TestSnoozeService.foregroundExits)
        assertTrue(
            "and this time it is released",
            shadowOf(controller.get()).isForegroundStopped,
        )
    }
}
