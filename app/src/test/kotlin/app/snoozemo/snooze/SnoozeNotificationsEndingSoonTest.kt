package app.snoozemo.snooze

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import app.snoozemo.R
import app.snoozemo.core.EndReason
import app.snoozemo.core.EndingSoonCause
import app.snoozemo.core.TrackingMode
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The "ending soon" warning's card and channel, and the way to keep snoozing
 * that both cards offer (SPEC.md §4.3).
 *
 * Robolectric stores the channel this code asks for without simulating the
 * platform's DND filtering or the vibrator, so this is the "did we ask for the
 * right thing" layer: whether the buzz actually reaches through Snoozemo's own
 * Do Not Disturb is a device check (`TODO.md`).
 */
@RunWith(RobolectricTestRunner::class)
// A plain `Application`, for the reason the sibling notification tests give.
@Config(application = Application::class)
class SnoozeNotificationsEndingSoonTest {

    private val now: Instant = Instant.parse("2026-08-22T09:00:00Z")

    private val manager get() = appContext.getSystemService(NotificationManager::class.java)

    @Before
    fun reset() {
        SnoozeNotifications.resetForTest()
    }

    /** A snooze ending on leaving, its failsafe at the ceiling — the fixture's shape. */
    private fun leaving(mode: TrackingMode = TrackingMode.FULL) = snoozeFixture(now).copy(mode = mode)

    /** A snooze the user narrowed to a time an hour out, with room to extend. */
    private fun timer() = snoozeFixture(now, capIn = Duration.ofHours(1)).copy(
        endsOnDeparture = false,
        timerOnlyRequested = true,
    )

    private fun posted(id: Int): Notification? = shadowOf(manager).getNotification(id)

    private fun actionTitles(notification: Notification?): List<String> =
        notification?.actions.orEmpty().map { it.title.toString() }

    @Test
    fun `the ending channel buzzes without a sound and reaches through Do Not Disturb`() {
        SnoozeNotifications(appContext)

        val channel = manager.getNotificationChannel(SnoozeNotifications.CHANNEL_ENDING)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertTrue("felt, not heard", channel.shouldVibrate())
        assertNull("no sound in the meeting it is warning about", channel.sound)
        // Snoozemo's own rule would otherwise silence the warning about it.
        assertTrue(channel.canBypassDnd())
    }

    @Test
    fun `a departure warning offers still here, as the Until I leave retake`() {
        val snooze = leaving()

        SnoozeNotifications(appContext).showEndingSoon(snooze, EndingSoonCause.LEAVING)

        val card = posted(SnoozeNotifications.ID_ENDING_PRESENCE)!!
        assertEquals(SnoozeNotifications.CHANNEL_ENDING, card.channelId)
        assertEquals(stringOf(R.string.ending_soon_title), shadowOf(card).contentTitle?.toString())
        assertEquals(stringOf(R.string.ending_soon_leaving), shadowOf(card).contentText?.toString())
        assertTrue("a repost after a restart must not buzz again", card.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(
            listOf(stringOf(R.string.action_end_now), stringOf(R.string.action_still_here)),
            actionTitles(card),
        )
        val stillHere = shadowOf(card.actions[1].actionIntent).savedIntent
        assertEquals(SnoozeService.ACTION_SET_CAP, stillHere.action)
        assertTrue(stillHere.getBooleanExtra(SnoozeService.EXTRA_RESTORE_END, false))
        assertEquals(
            "a card outliving its snooze must retake nobody's place",
            snooze.startedAt.toEpochMilli(),
            stillHere.getLongExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, 0L),
        )
    }

    @Test
    fun `a lost Wi-Fi warning counts down and offers still here`() {
        SnoozeNotifications(appContext).showEndingSoon(
            leaving(TrackingMode.WIFI_GRACE),
            EndingSoonCause.WIFI_LOST,
            graceDeadlineMs = android.os.SystemClock.elapsedRealtime() + Duration.ofMinutes(5).toMillis(),
        )

        val card = posted(SnoozeNotifications.ID_ENDING_PRESENCE)!!
        assertEquals(stringOf(R.string.ending_soon_wifi_lost), shadowOf(card).contentText?.toString())
        assertTrue(shadowOf(card).isWhenShown)
        assertEquals(stringOf(R.string.action_still_here), actionTitles(card).last())
    }

    @Test
    fun `a timed warning offers thirty more minutes`() {
        SnoozeNotifications(appContext).showEndingSoon(timer(), EndingSoonCause.TIME)

        val card = posted(SnoozeNotifications.ID_ENDING_TIME)!!
        assertEquals(
            listOf(stringOf(R.string.action_end_now), stringOf(R.string.action_extend)),
            actionTitles(card),
        )
        assertEquals(
            SnoozeService.ACTION_EXTEND,
            shadowOf(card.actions[1].actionIntent).savedIntent.action,
        )
    }

    @Test
    fun `the failsafe at its ceiling is warned about with nothing to push it back`() {
        // The backstop is absolute (SPEC.md §7); `+30 min` there could only
        // ever say "at max".
        SnoozeNotifications(appContext).showEndingSoon(leaving(), EndingSoonCause.TIME)

        assertEquals(listOf(stringOf(R.string.action_end_now)), actionTitles(posted(SnoozeNotifications.ID_ENDING_TIME)))
    }

    @Test
    fun `every warning action says it came from the notification`() {
        val notifications = SnoozeNotifications(appContext)
        notifications.showEndingSoon(leaving(), EndingSoonCause.LEAVING)
        notifications.showEndingSoon(timer(), EndingSoonCause.TIME)

        listOf(SnoozeNotifications.ID_ENDING_PRESENCE, SnoozeNotifications.ID_ENDING_TIME).forEach { id ->
            posted(id)!!.actions.forEach { action ->
                assertEquals(
                    SnoozeService.REQUESTED_FROM_NOTIFICATION,
                    shadowOf(action.actionIntent).savedIntent.getStringExtra(SnoozeService.EXTRA_REQUESTED_FROM),
                )
            }
        }
    }

    @Test
    fun `an ending takes every warning down with the ongoing card`() {
        val notifications = SnoozeNotifications(appContext)
        notifications.showOngoing(leaving())
        notifications.showEndingSoon(leaving(), EndingSoonCause.LEAVING)
        notifications.showEndingSoon(leaving(), EndingSoonCause.TIME)

        notifications.showEnded(EndReason.DEPARTURE)

        assertNull(posted(SnoozeNotifications.ID_ENDING_PRESENCE))
        assertNull(posted(SnoozeNotifications.ID_ENDING_TIME))
    }

    @Test
    fun `the ongoing card of a snooze ending on leaving offers still here`() {
        // Its cap sits at the ceiling from the arm, so `+30 min` only ever
        // answered "at max" (maintainer, 2026-10-13).
        SnoozeNotifications(appContext).showOngoing(leaving())

        assertEquals(
            listOf(stringOf(R.string.action_end_now), stringOf(R.string.action_still_here)),
            actionTitles(posted(SnoozeNotifications.ID_ONGOING)),
        )
    }

    @Test
    fun `the ongoing card of a timer keeps thirty more minutes`() {
        SnoozeNotifications(appContext).showOngoing(timer())

        assertEquals(
            listOf(stringOf(R.string.action_end_now), stringOf(R.string.action_extend)),
            actionTitles(posted(SnoozeNotifications.ID_ONGOING)),
        )
    }

    @Test
    fun `a card with no way to keep going offers only End now`() {
        // Degraded to its timer at the ceiling: nothing watches a departure,
        // and the cap cannot move — absent, never disabled.
        val stuck = leaving(TrackingMode.DURATION_ONLY)
        assertFalse(stuck.canExtend)

        SnoozeNotifications(appContext).showOngoing(stuck)

        assertEquals(listOf(stringOf(R.string.action_end_now)), actionTitles(posted(SnoozeNotifications.ID_ONGOING)))
    }

    @Test
    fun `the timed warning's takedown reaches the card from outside any instance`() {
        SnoozeNotifications(appContext).showEndingSoon(timer(), EndingSoonCause.TIME)

        SnoozeNotifications.cancelTimedWarning(appContext)

        assertNull(posted(SnoozeNotifications.ID_ENDING_TIME))
    }
}
