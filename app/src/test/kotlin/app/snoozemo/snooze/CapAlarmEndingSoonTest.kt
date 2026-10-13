package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import java.time.Duration
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
import org.robolectric.annotation.Config

/**
 * The timed "ending soon" warning's alarm (SPEC.md §4.3): armed with the cap,
 * moved with it, and posting only for the snooze and the deadline it was armed
 * for. A courtesy ahead of the cap, never a substitute for it.
 */
@RunWith(RobolectricTestRunner::class)
// A plain `Application`, for the reason the sibling notification tests give.
@Config(application = Application::class)
class CapAlarmEndingSoonTest {

    private val manager get() = appContext.getSystemService(NotificationManager::class.java)

    @Before
    fun reset() {
        ActiveSnoozeStore(appContext).clear()
        SnoozeNotifications.resetForTest()
    }

    /**
     * A snooze with [left] to run, against the clock the receiver reads — so
     * the record carries no boot reference and is read in wall time alone.
     */
    private fun endingIn(left: Duration): ActiveSnooze {
        val wallNow = Instant.ofEpochMilli(SnoozeClock.read().wallMillis)
        return ActiveSnooze(
            // Stock stand-ins, never a device capture (AGENTS.md, *Privacy*).
            anchor = Anchor(lat = 0.0, lon = 0.0, fixAccuracyM = 10f, capturedAt = wallNow, ssid = "ExampleWifi"),
            startedAt = wallNow.minus(Duration.ofHours(1)),
            capExpiresAt = wallNow.plus(left),
            mode = TrackingMode.DURATION_ONLY,
            endsOnDeparture = false,
            timerOnlyRequested = true,
        )
    }

    private fun fire(armedFor: ActiveSnooze) {
        CapAlarmReceiver().onReceive(
            appContext,
            Intent(SnoozeService.ACTION_ENDING_SOON)
                .putExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, armedFor.startedAt.toEpochMilli()),
        )
    }

    private fun timedWarning() = shadowOf(manager).getNotification(SnoozeNotifications.ID_ENDING_TIME)

    @Test
    fun `arming the cap arms the warning five minutes ahead of it`() {
        val snooze = endingIn(Duration.ofHours(1))

        assertTrue(CapAlarm.arm(appContext, snooze))

        val warning = shadowOf(appContext.getSystemService(android.app.AlarmManager::class.java))
            .scheduledAlarms
            .last { shadowOf(it.operation).savedIntent.action == SnoozeService.ACTION_ENDING_SOON }
        assertEquals(
            Duration.ofMinutes(55).toMillis().toDouble(),
            (warning.triggerAtMs - android.os.SystemClock.elapsedRealtime()).toDouble(),
            1_000.0,
        )
        assertEquals(
            "it names the snooze it is for",
            snooze.startedAt.toEpochMilli(),
            shadowOf(warning.operation).savedIntent.getLongExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, 0L),
        )
    }

    @Test
    fun `the warning never spends the cap's allow-while-idle budget`() {
        // Android rate-limits allow-while-idle alarms per app, "such as 15
        // minutes" in idle; a courtesy five minutes ahead must not be able to
        // hold the cap back (Codex, PR #330).
        CapAlarm.arm(appContext, endingIn(Duration.ofHours(1)))

        val alarms = shadowOf(appContext.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
        fun alarmFor(action: String) = alarms.last { shadowOf(it.operation).savedIntent.action == action }
        assertFalse(alarmFor(SnoozeService.ACTION_ENDING_SOON).isAllowWhileIdle)
        assertTrue("the cap itself still is", alarmFor(SnoozeService.ACTION_CHECK_CAP).isAllowWhileIdle)
    }

    @Test
    fun `a snooze already in its last five minutes arms no warning`() {
        // A restore there would buzz again for a warning already seen.
        CapAlarm.arm(appContext, endingIn(Duration.ofMinutes(3)))

        assertFalse(scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_ENDING_SOON })
    }

    @Test
    fun `canceling the cap cancels the warning with it`() {
        CapAlarm.arm(appContext, endingIn(Duration.ofHours(1)))

        CapAlarm.cancel(appContext)

        assertFalse(scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_ENDING_SOON })
    }

    @Test
    fun `a fired warning posts for the running snooze inside its window`() {
        val snooze = endingIn(Duration.ofMinutes(5))
        ActiveSnoozeStore(appContext).arm(snooze)

        fire(armedFor = snooze)

        val card = timedWarning()
        assertNotNull(card)
        assertEquals(SnoozeNotifications.CHANNEL_ENDING, card!!.channelId)
        assertEquals(stringOf(R.string.ending_soon_title), shadowOf(card).contentTitle?.toString())
    }

    @Test
    fun `a warning that outlived its snooze says nothing`() {
        val replaced = endingIn(Duration.ofMinutes(5))
        ActiveSnoozeStore(appContext).arm(replaced)

        fire(armedFor = replaced.copy(startedAt = replaced.startedAt.minus(Duration.ofHours(2))))

        assertNull(timedWarning())
    }

    @Test
    fun `a warning whose deadline has moved out says nothing`() {
        // `+30 min` landed after the alarm was armed and before it fired.
        val extended = endingIn(Duration.ofMinutes(35))
        ActiveSnoozeStore(appContext).arm(extended)

        fire(armedFor = extended)

        assertNull(timedWarning())
    }

    @Test
    fun `a clock change inside the window rebuilds a standing warning`() {
        // The re-arm finds the snooze inside its last five minutes and arms
        // nothing — but the card already up named the old deadline (Codex,
        // PR #330).
        val snooze = endingIn(Duration.ofMinutes(4))
        ActiveSnoozeStore(appContext).arm(snooze)
        fire(armedFor = snooze)
        val restated = snooze.copy(capExpiresAt = snooze.capExpiresAt.minus(Duration.ofMinutes(2)))

        CapAlarm.arm(appContext, restated)

        assertEquals(
            appContext.getString(
                R.string.ending_soon_time,
                app.snoozemo.ui.formatSheetTime(appContext, restated.capExpiresAt),
            ),
            shadowOf(timedWarning()!!).contentText?.toString(),
        )
    }

    @Test
    fun `a warning the user swiped away is not brought back by a re-arm`() {
        val snooze = endingIn(Duration.ofMinutes(4))
        ActiveSnoozeStore(appContext).arm(snooze)

        CapAlarm.arm(appContext, snooze)

        assertNull(timedWarning())
    }

    @Test
    fun `a time-zone change rebuilds a standing warning in place`() {
        val snooze = endingIn(Duration.ofMinutes(4))
        ActiveSnoozeStore(appContext).arm(snooze)
        fire(armedFor = snooze)
        val before = shadowOf(timedWarning()!!).contentText?.toString()
        val zone = java.util.TimeZone.getDefault()
        try {
            // Any zone at a different offset from the test's own.
            java.util.TimeZone.setDefault(
                java.util.TimeZone.getTimeZone(if (zone.rawOffset == 0) "Asia/Tokyo" else "UTC"),
            )

            TimeChangedReceiver().onReceive(appContext, Intent(Intent.ACTION_TIMEZONE_CHANGED))

            val after = shadowOf(timedWarning()!!).contentText?.toString()
            assertEquals(
                appContext.getString(
                    R.string.ending_soon_time,
                    app.snoozemo.ui.formatSheetTime(appContext, snooze.capExpiresAt),
                ),
                after,
            )
            assertTrue("the zone moved, so the time it names should too", before != after)
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `moving the cap out takes a standing warning down`() {
        val snooze = endingIn(Duration.ofMinutes(4))
        ActiveSnoozeStore(appContext).arm(snooze)
        fire(armedFor = snooze)
        assertNotNull(timedWarning())

        CapAlarm.arm(appContext, snooze.copy(capExpiresAt = snooze.capExpiresAt.plus(Duration.ofMinutes(30))))

        assertNull("the time it named is no longer the end", timedWarning())
    }
}
