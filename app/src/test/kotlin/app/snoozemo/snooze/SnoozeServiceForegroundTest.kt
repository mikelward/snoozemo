package app.snoozemo.snooze

import android.content.Intent
import android.content.pm.ServiceInfo
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
 * posted either way — so these assert the *binding*: every snooze takes one
 * (`specialUse`, so the ringer can be handed back at the end — SPEC.md §5.9
 * rule 5), the watched ones add `location`, and ending gives it back rather
 * than leaving a `Snoozing` card in the shade for a snooze that is over.
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

    private val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    private val locationAndSpecialUse =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("a-zen-rule-id")
        ActiveSnoozeStore(appContext).clear()
        UnprotectedWatches.clear()
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
        assertEquals("location for the watch, specialUse for the ringer", locationAndSpecialUse, TestSnoozeService.foregroundType)
    }

    @Test
    fun `a duration-only snooze holds a specialUse foreground service and no location`() {
        // Field log, 2026-10-12: a timer-only snooze held no foreground
        // service, so its cap ended it from the background and Android 17
        // dropped the ringer hand-back — the phone stayed on vibrate. The
        // process is now held for the ringer, but under `specialUse` only:
        // claiming a *location* foreground service for a snooze doing no
        // location work would be a claim the app cannot back.
        val controller = armWith(unwatchable)

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(specialUse, TestSnoozeService.foregroundType)
        // And the card is still up: the binding is what changes, not what the
        // user sees. Located by its stable id, since a plain timer-only card
        // now folds its title to "Snoozing until <time>" (PR #276) and the
        // constant title no longer identifies it.
        assertNotNull(ongoingTitle())
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
    fun `a fenced snooze degraded by its fixes keeps the foreground service`() {
        // Field log, 2026-10-05: a walk past the fence edge gave three
        // inconclusive fixes, the snooze fell to duration-only, the service
        // gave the foreground back, Android reclaimed the watch, and the
        // recovery's re-promotion was refused from the background — the phone
        // stayed silent 300 m away. The fence and the engine were still
        // running, so the process they report to has to stay.
        val controller = armWith(fixOnly)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        TestSnoozeService.presence.canTrackDeparture = false

        emit(PresenceUpdate(event = null, degradation = DegradationCause.FIXES_TOO_VAGUE))

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertEquals("nothing given back", 0, TestSnoozeService.foregroundExits)
        assertFalse(shadowOf(controller.get()).isForegroundStopped)
    }

    @Test
    fun `a stale services-off report keeps the foreground service`() {
        // Codex, PR #309: a late `GEOFENCE_NOT_AVAILABLE` can record services
        // off after the switch is back on, with the fence still live. And a
        // real outage is repaired in process, by the location-mode watch.
        val controller = armWith(fixOnly)
        TestSnoozeService.presence.canTrackDeparture = false

        emit(PresenceUpdate(event = null, degradation = DegradationCause.LOCATION_SERVICES_OFF))

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertFalse(shadowOf(controller.get()).isForegroundStopped)
    }

    @Test
    fun `a fenced snooze whose location grant is gone drops the location type and keeps the service`() {
        // The other side: the grant is the platform's own prerequisite for a
        // location service, and nothing in process can bring it back — so the
        // type goes. The service stays, under `specialUse`, because the ringer
        // still has to be handed back from inside one when the snooze ends.
        val controller = armWith(fixOnly)
        TestSnoozeService.presence.canTrackDeparture = false

        emit(PresenceUpdate(event = null, degradation = DegradationCause.LOCATION_PERMISSION_GONE))

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertEquals("narrowed, not given back", specialUse, TestSnoozeService.foregroundType)
        assertEquals(0, TestSnoozeService.foregroundExits)
        assertFalse(shadowOf(controller.get()).isForegroundStopped)
    }

    @Test
    fun `a refused location type still holds the service for the ringer`() {
        // A withdrawn grant or location services off refuses a location
        // `startForeground` outright. That must not also cost the ringer
        // hand-back: the service falls back to `specialUse` alone, and the card
        // still says the watch is unprotected, because it is.
        TestSnoozeService.refuseLocationForeground = true

        val controller = armWith(watchable)

        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertEquals(specialUse, TestSnoozeService.foregroundType)
        assertTrue(
            "the watch is still unprotected, and the card says so",
            shadeText().contains(stringOf(app.snoozemo.R.string.ongoing_watch_unprotected)),
        )
        assertTrue(UnprotectedWatches.latest())
    }

    @Test
    fun `a refused location refresh does not narrow a service that holds it`() {
        // The fallback is only for a service that has no location to lose: a
        // *refresh* refused while location is already held leaves it held, and
        // narrowing it to `specialUse` would take away a watch that is fine.
        val controller = armWith(watchable)
        assertEquals(locationAndSpecialUse, TestSnoozeService.foregroundType)

        TestSnoozeService.refuseLocationForeground = true
        TestSnoozeService.presence.canTrackDeparture = false
        emit(PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX))

        assertEquals(TrackingMode.WIFI_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertEquals("still location", locationAndSpecialUse, TestSnoozeService.foregroundType)
        assertFalse(shadowOf(controller.get()).isForegroundStopped)
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

    /**
     * The warning has to survive a time chosen *during* the anchor capture,
     * which is the ordinary tile-sheet flow.
     *
     * Two predicates answer "does this snooze need the process?" and only one
     * of them had learned about a pending capture: `wantsForeground` holds the
     * process while the capture is out, because its anchor is what
     * `Until I leave` goes back to — while this card's gate read
     * `effectiveMode`, saw the timer, and suppressed the warning. So a refused
     * promotion went unsaid on exactly the snooze whose route home depends on
     * surviving long enough to capture (Codex, PR #267). The raw `mode` is
     * what reports it: still `SETTLING` while `effectiveMode` reads
     * `DURATION_ONLY`.
     */
    @Test
    fun `a time chosen mid-capture still says the watch is unprotected`() {
        TestSnoozeService.refuseForeground = true

        // Armed, capture deliberately undelivered, so the mode is `SETTLING`.
        val controller = startService(SnoozeService.ACTION_ARM)
        controller.get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java)
                .setAction(SnoozeService.ACTION_SET_CAP)
                .putExtra(
                    SnoozeService.EXTRA_CAP_EXPIRES_AT,
                    now.plusSeconds(3600).toEpochMilli(),
                ),
            0,
            2,
        )
        shadowOf(getMainLooper()).idle()

        assertTrue(
            "the capture still has to land, so a refused promotion matters",
            shadeText().contains(stringOf(app.snoozemo.R.string.ongoing_watch_unprotected)),
        )
    }

    @Test
    fun `a capture that finished inside begin holds no location foreground service`() {
        // The real runner starts inside `begin`, and both halves can settle
        // there — no location permission plus a refused Wi-Fi callback. The
        // callback then clears the handle field *before* the assignment that
        // would set it, so the finished handle is what lands and a predicate
        // reading the field never goes false again: a timer-only snooze kept
        // the location foreground service to its cap (Codex, PR #267).
        TestSnoozeService.captureSettlesInBegin = unwatchable

        val controller = startService(SnoozeService.ACTION_ARM)
        shadowOf(getMainLooper()).idle()
        controller.get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java)
                .setAction(SnoozeService.ACTION_SET_CAP)
                .putExtra(
                    SnoozeService.EXTRA_CAP_EXPIRES_AT,
                    now.plusSeconds(3600).toEpochMilli(),
                ),
            0,
            2,
        )
        shadowOf(getMainLooper()).idle()

        assertEquals(
            "nothing is being watched for, so only the ringer holds the process",
            specialUse,
            TestSnoozeService.foregroundType,
        )
    }

    @Test
    fun `a refused foreground service is published for the main screen`() {
        // The screen cannot reach this instance, so the card's reading is
        // published for it (TODO.md, deferred from PR #262).
        TestSnoozeService.refuseForeground = true

        armWith(watchable)

        assertTrue(UnprotectedWatches.latest())
    }

    @Test
    fun `ending withdraws the protection warning`() {
        TestSnoozeService.refuseForeground = true
        val controller = armWith(watchable)
        assertTrue(UnprotectedWatches.latest())

        controller
            .withIntent(
                Intent(appContext, TestSnoozeService::class.java)
                    .setAction(SnoozeService.ACTION_END),
            )
            .startCommand(0, 2)
        shadowOf(getMainLooper()).idle()

        assertFalse(UnprotectedWatches.latest())
    }

    @Test
    fun `a destroyed service withdraws the protection warning`() {
        // Nothing on a destroyed instance can retract it, so the screen would
        // otherwise warn about a watch no service is running any more.
        TestSnoozeService.refuseForeground = true
        val controller = armWith(watchable)
        assertTrue(UnprotectedWatches.latest())

        controller.destroy()

        assertFalse(UnprotectedWatches.latest())
        assertNotNull("the snooze outlives its service", ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a held watch publishes no warning`() {
        armWith(watchable)

        assertFalse(UnprotectedWatches.latest())
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
    fun `a destroyed service stops answering for the card it posted`() {
        // Codex, PR #231. `SnoozeNotifications.readCalendar` is a process-wide
        // executor, so a calendar read queued by this service still holds this
        // notifications instance after Android destroys it — and Android
        // destroys an ordinary background service routinely. A replacement
        // then restores the snooze and posts its own card, and that orphaned
        // worker can pass the generation check and repost through *this*
        // instance, whose host would answer from a controller nothing updates
        // any more: a confident `Wi-Fi` row, or a refusal clause, from a
        // service that stopped watching.
        //
        // Detaching is what ties the host's lifetime to the service's, so both
        // readings degrade to the same "cannot say" any other instance gets.
        val controller = armWith(watchable)
        val notifications = controller.get().notifications
        assertNotNull("installed while it was running", notifications.ongoingForegroundHost)

        controller.destroy()

        assertNull(
            "and gone with it, so nothing answers for a service that stopped watching",
            notifications.ongoingForegroundHost,
        )
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
    fun `a refused give-back at the end leaves the service to retry it`() {
        // Codex, PR #230. If `stopForeground` throws, the process may still be
        // foreground, so the held flag stays set and the next give-back tries
        // again rather than taking the early return — a service held past the
        // snooze, its card left in the shade, is the failure. The second
        // give-back here is the card's own takedown after the ended notice.
        val controller = armWith(unwatchable)
        TestSnoozeService.refuseForegroundExit = true

        controller
            .withIntent(
                Intent(appContext, TestSnoozeService::class.java)
                    .setAction(SnoozeService.ACTION_END),
            )
            .startCommand(0, 2)
        shadowOf(getMainLooper()).idle()

        val tries = TestSnoozeService.foregroundExits
        assertTrue("it tried to give it back", tries >= 1)
        assertFalse("and the platform did not take it", shadowOf(controller.get()).isForegroundStopped)

        TestSnoozeService.refuseForegroundExit = false
        controller.get().notifications.cancelOngoing()

        assertEquals("so it tries again", tries + 1, TestSnoozeService.foregroundExits)
        assertTrue("and this time it is released", shadowOf(controller.get()).isForegroundStopped)
    }

    @Test
    fun `a restored expired snooze takes the foreground service before it releases`() {
        // Codex, PR #328. The process died before the cap; the alarm starts a
        // fresh instance, and `restore` ends the expired record before its
        // first transition would have posted the card. The ringer is handed
        // back inside that release, and Android 17 drops it from a process
        // with no foreground service — so the service has to hold one first.
        val expired = snoozeFixture(now, startedAgo = java.time.Duration.ofHours(2), capIn = java.time.Duration.ofMinutes(-1))
        ActiveSnoozeStore(appContext).arm(expired)
        ActiveSnoozeStore(appContext).setState(
            app.snoozemo.core.SnoozeRecordState(app.snoozemo.core.SnoozeLifecycle.ARMED),
        )

        startService(SnoozeService.ACTION_CHECK_CAP)
        shadowOf(getMainLooper()).idle()

        val zen = TestSnoozeService.zen
        // The *first* release: it is the one that ends the snooze and hands
        // the ringer back. The cap check's own reconcile can drive the
        // already-off rule off once more afterwards, recordlessly, after the
        // card and its service are gone — that call has no snooze behind it.
        val release = zen.calls.indexOfFirst { (snoozed, _) -> !snoozed }
        assertTrue("the expired snooze was released: ${zen.calls}", release >= 0)
        assertEquals(
            "and the release ran from inside a specialUse foreground service",
            specialUse,
            zen.foregroundTypeAtCalls[release],
        )
        assertNull("and it is over", ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `an End now on a fresh instance takes the foreground service before it releases`() {
        // The adopt path: the user taps `End now` after the process died, and
        // the release that hands the ringer back runs in the same start.
        val running = snoozeFixture(now)
        ActiveSnoozeStore(appContext).arm(running)
        ActiveSnoozeStore(appContext).setState(
            app.snoozemo.core.SnoozeRecordState(app.snoozemo.core.SnoozeLifecycle.ARMED),
        )

        startService(SnoozeService.ACTION_END)
        shadowOf(getMainLooper()).idle()

        val zen = TestSnoozeService.zen
        val release = zen.calls.indexOfFirst { (snoozed, _) -> !snoozed }
        assertTrue("the snooze was released: ${zen.calls}", release >= 0)
        assertEquals(specialUse, zen.foregroundTypeAtCalls[release])
    }

    @Test
    @org.robolectric.annotation.Config(shadows = [TogglableAlarmManager::class])
    fun `a restore whose cap re-arm is refused takes the foreground service before its direct release`() {
        // Codex, PR #328, the same gap one branch earlier: a cold restore that
        // cannot re-arm the cap ends the snooze straight away through the
        // direct release, which hands the ringer back too. Nothing on that
        // branch posts the card afterwards, so a `specialUse` start here can
        // only have come from the restore taking it first.
        TogglableAlarmManager.refuse = true
        try {
            ActiveSnoozeStore(appContext).arm(snoozeFixture(now))
            ActiveSnoozeStore(appContext).setState(
                app.snoozemo.core.SnoozeRecordState(app.snoozemo.core.SnoozeLifecycle.ARMED),
            )

            startService(SnoozeService.ACTION_CHECK_CAP)
            shadowOf(getMainLooper()).idle()

            assertTrue(
                "the restore took a specialUse foreground service: ${TestSnoozeService.foregroundTypesEntered}",
                specialUse in TestSnoozeService.foregroundTypesEntered,
            )
            assertTrue(
                "and could not resume, so it ended directly",
                shadeShows(stringOf(app.snoozemo.R.string.failure_could_not_resume)),
            )
        } finally {
            TogglableAlarmManager.refuse = false
        }
    }
}
