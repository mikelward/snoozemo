package app.snoozemo.snooze

import android.app.NotificationManager
import android.content.Intent
import android.os.Looper.getMainLooper
import app.snoozemo.R
import app.snoozemo.core.Anchor
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.PresenceEvent
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.ZenOutcome
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.RobolectricTestRunner

/**
 * The "ending soon" warning's life on a running snooze (SPEC.md §4.3): raised
 * by the presence levels, taken down when they clear, when the snooze ends or
 * is narrowed to a time, and when the user answers it with `Still here`.
 *
 * Driven through the monitor seam, like the sibling presence tests.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceEndingSoonTest {

    private val now: Instant = Instant.parse("2026-08-22T10:00:00Z")

    // Stock stand-ins, never a device capture (AGENTS.md, *Privacy*).
    private val captured = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = now,
        ssid = "ExampleWifi",
    )

    private val manager get() = appContext.getSystemService(NotificationManager::class.java)

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
        ActiveSnoozeStore(appContext).clear()
        TogglableAlarmManager.refuse = false
        GraceDeadlines.clear()
        SnoozeNotifications.resetForTest()
        CancelRecordingNotificationManager.refuse = null
        CancelRecordingNotificationManager.refuseCancel = null
    }

    /** Arms and completes capture, so the watch is running; the instance is kept for later starts. */
    private fun armWatched(): ServiceController<TestSnoozeService> {
        val intent = Intent(appContext, TestSnoozeService::class.java).setAction(SnoozeService.ACTION_ARM)
        val service = Robolectric.buildService(TestSnoozeService::class.java, intent).create().startCommand(0, 1)
        TestSnoozeService.captureRequests.single().invoke(captured)
        shadowOf(getMainLooper()).idle()
        return service
    }

    private fun ServiceController<TestSnoozeService>.send(action: String, startId: Int, extras: Intent.() -> Unit = {}) {
        get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java).setAction(action).apply(extras),
            0,
            startId,
        )
        shadowOf(getMainLooper()).idle()
    }

    private fun emit(update: PresenceUpdate) {
        assertTrue(TestSnoozeService.presence.updates.tryEmit(update))
        shadowOf(getMainLooper()).idle()
    }

    private fun presenceWarning(): String? =
        shadowOf(manager).getNotification(SnoozeNotifications.ID_ENDING_PRESENCE)
            ?.let { shadowOf(it).contentText?.toString() }

    private fun confirming(value: Boolean, event: PresenceEvent? = null) =
        PresenceUpdate(event = event, degradation = null, confirmingDeparture = value)

    @Test
    fun `a departure confirming raises the warning and a fix back home takes it down`() {
        armWatched()

        emit(confirming(true, PresenceEvent.ProbablyLeft))
        assertEquals(stringOf(R.string.ending_soon_leaving), presenceWarning())

        emit(confirming(false, PresenceEvent.StillHere))
        assertNull("a GPS jump settled is not news worth leaving up", presenceWarning())
    }

    @Test
    fun `a check that has not confirmed anything raises no warning`() {
        // A geofence exit or a Wi-Fi drop starts the test, and buzzing on it
        // would buzz on every router blip.
        armWatched()

        emit(confirming(false, PresenceEvent.ProbablyLeft))

        assertNull(presenceWarning())
    }

    @Test
    fun `a grace period raises the Wi-Fi warning`() {
        armWatched()

        emit(
            PresenceUpdate(
                event = null,
                degradation = DegradationCause.NO_LOCATION_FIX,
                graceActive = true,
                graceDeadlineMs = TestSnoozeService.FIXTURE_UPTIME_MILLIS + Duration.ofMinutes(5).toMillis(),
            ),
        )

        assertEquals(stringOf(R.string.ending_soon_wifi_lost), presenceWarning())
    }

    @Test
    fun `the departure ending takes the warning down with the snooze`() {
        armWatched()
        emit(confirming(true, PresenceEvent.ProbablyLeft))

        emit(confirming(false, PresenceEvent.Departed))

        assertNull(ActiveSnoozeStore(appContext).load())
        assertNull(presenceWarning())
    }

    @Test
    fun `a time chosen over the departure takes the warning down`() {
        // The watch stops, so no presence update will ever say the window
        // closed; the transition has to.
        val service = armWatched()
        emit(confirming(true, PresenceEvent.ProbablyLeft))

        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, now.plus(Duration.ofHours(1)).toEpochMilli())
        }

        assertNull(presenceWarning())
    }

    @Test
    fun `still here retakes the place and the stale window cannot buzz again`() {
        val service = armWatched()
        emit(confirming(true, PresenceEvent.ProbablyLeft))
        val armed = ActiveSnoozeStore(appContext).load()!!

        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, armed.startedAt.toEpochMilli())
        }

        assertEquals("the Until I leave retake started", 2, TestSnoozeService.captureRequests.size)
        assertNull("answered, so it comes down", presenceWarning())

        // The fresh place lands and the transitions that follow run while the
        // controller still holds the old watch's open window — nothing has
        // restated it since. It must not read as live.
        TestSnoozeService.captureRequests.last().invoke(captured)
        shadowOf(getMainLooper()).idle()
        assertNull("the old window is stale, not news", presenceWarning())

        // The new watch speaking is what makes the levels live again.
        emit(confirming(true, PresenceEvent.ProbablyLeft))
        assertEquals(stringOf(R.string.ending_soon_leaving), presenceWarning())
    }

    @Test
    fun `a cold still here takes down a warning an earlier process left up`() {
        // The new process's bookkeeping has never seen the card, and the
        // restored watch is stopped for the retake before it says anything —
        // so nothing would ever clear it but its own timeout (Codex, PR #330).
        val record = snoozeFixture(now)
        SnoozeNotifications(appContext).showEndingSoon(record, app.snoozemo.core.EndingSoonCause.LEAVING)
        assertEquals(stringOf(R.string.ending_soon_leaving), presenceWarning())

        startService(SnoozeService.ACTION_SET_CAP, record) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, record.startedAt.toEpochMilli())
        }
        shadowOf(getMainLooper()).idle()

        assertTrue("the retake started", TestSnoozeService.captureRequests.isNotEmpty())
        assertNull(presenceWarning())
    }

    @Test
    fun `a still here the service declines says the place did not update`() {
        // `Still here` names no time, so "Couldn't set the end time" would
        // describe a tap the user never made (Codex, PR #330). Declined here
        // because nothing watches a departure on this snooze any more — the
        // card that offered it outlived the degradation.
        val record = snoozeFixture(now).copy(mode = app.snoozemo.core.TrackingMode.DURATION_ONLY)

        startService(SnoozeService.ACTION_SET_CAP, record) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, record.startedAt.toEpochMilli())
        }
        shadowOf(getMainLooper()).idle()

        assertTrue(shadeShows(stringOf(R.string.failure_could_not_update_place)))
        assertFalse(shadeShows(stringOf(R.string.failure_could_not_set_end)))
    }

    @Test
    @org.robolectric.annotation.Config(shadows = [CancelRecordingNotificationManager::class])
    fun `a cold restore keeps a still-true inherited warning up rather than reposting it`() {
        // An earlier process left `Looks like you're leaving` up, and the
        // restored watch's first update says the window is still open. A
        // cancel followed by a repost is a *new* post, which buzzes again;
        // only an update in place stays quiet (`setOnlyAlertOnce`). Reconciling
        // on each level as it landed read the window before it was installed
        // and canceled the card (Codex, PR #330).
        val record = snoozeFixture(now)
        SnoozeNotifications(appContext).showEndingSoon(record, app.snoozemo.core.EndingSoonCause.LEAVING)
        startService(SnoozeService.ACTION_RESTORE, record)
        shadowOf(getMainLooper()).idle()
        CancelRecordingNotificationManager.canceled.clear()

        emit(confirming(true))

        assertEquals(stringOf(R.string.ending_soon_leaving), presenceWarning())
        assertTrue(
            "the card still standing was canceled and reposted",
            SnoozeNotifications.ID_ENDING_PRESENCE !in CancelRecordingNotificationManager.canceled,
        )
    }

    @Test
    @org.robolectric.annotation.Config(shadows = [CancelRecordingNotificationManager::class])
    fun `a warning whose post was refused is retried on the next update`() {
        // A refused `notify` must not count as warned, or the equality guard
        // swallows every restated level after it and the warning never lands
        // (Codex, PR #330).
        armWatched()
        CancelRecordingNotificationManager.refuse = SnoozeNotifications.ID_ENDING_PRESENCE
        emit(confirming(true, PresenceEvent.ProbablyLeft))
        assertNull("the platform refused it", presenceWarning())

        CancelRecordingNotificationManager.refuse = null
        emit(confirming(true))

        assertEquals(stringOf(R.string.ending_soon_leaving), presenceWarning())
    }

    @Test
    @org.robolectric.annotation.Config(shadows = [CancelRecordingNotificationManager::class])
    fun `a warning whose cancel was refused is taken down on the next update`() {
        // The mirror of the refused post: counting a refused cancel as done
        // leaves a false `Looks like you're leaving` up until its timeout
        // (Codex, PR #330).
        armWatched()
        emit(confirming(true, PresenceEvent.ProbablyLeft))
        CancelRecordingNotificationManager.refuseCancel = SnoozeNotifications.ID_ENDING_PRESENCE
        emit(confirming(false, PresenceEvent.StillHere))
        assertEquals("the platform refused the cancel", stringOf(R.string.ending_soon_leaving), presenceWarning())

        CancelRecordingNotificationManager.refuseCancel = null
        emit(confirming(false))

        assertNull(presenceWarning())
    }

    @Test
    fun `an arm schedules the timed warning once the rule is on`() {
        armWatched()

        assertTrue(scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_ENDING_SOON })
    }
}

/**
 * Records every notification id canceled, so a test can tell an update in
 * place from a cancel-and-repost — and refuses posts to [refuse] and cancels
 * of [refuseCancel], which Robolectric's own shadow never does.
 */
@org.robolectric.annotation.Implements(NotificationManager::class)
class CancelRecordingNotificationManager : org.robolectric.shadows.ShadowNotificationManager() {
    @org.robolectric.annotation.Implementation
    override fun notify(tag: String?, id: Int, notification: android.app.Notification) {
        if (id == refuse) throw SecurityException("The test platform refuses this post.")
        super.notify(tag, id, notification)
    }

    @org.robolectric.annotation.Implementation
    override fun notify(id: Int, notification: android.app.Notification) {
        if (id == refuse) throw SecurityException("The test platform refuses this post.")
        super.notify(id, notification)
    }

    @org.robolectric.annotation.Implementation
    override fun cancel(tag: String?, id: Int) {
        if (id == refuseCancel) throw SecurityException("The test platform refuses this cancel.")
        canceled += id
        super.cancel(tag, id)
    }

    @org.robolectric.annotation.Implementation
    override fun cancel(id: Int) {
        if (id == refuseCancel) throw SecurityException("The test platform refuses this cancel.")
        canceled += id
        super.cancel(id)
    }

    companion object {
        /** Cleared by the test that reads it; static, like every shadow's state. */
        val canceled = mutableListOf<Int>()

        /** A notification id whose posts throw, or null; reset by the test that sets it. */
        var refuse: Int? = null

        /** A notification id whose cancels throw, or null; reset by the test that sets it. */
        var refuseCancel: Int? = null
    }
}
