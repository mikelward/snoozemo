package app.snoozemo.snooze

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.TrackingMode
import app.snoozemo.dnd.PrefsRingerLoanStore
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When the ongoing card fronts a cap countdown, and when it drops the time
 * indicator entirely (`SPEC.md` §4.2, §4.3).
 *
 * The countdown is the platform's chronometer against the absolute cap, so the
 * decision reduces to whether `setUsesChronometer`/`setShowWhen` were called —
 * asserted through the notification's extras, the same posted card the other
 * `SnoozeNotifications*Test` files read. Its own file, because it is about
 * *whether there is a time* rather than about `contentText`, `subText`, or the
 * channel; a test reading the wrong field would pass against a card that showed
 * the opposite time.
 *
 * The same plain-`Application` note as the sibling files: it keeps
 * `SnoozemoApplication.onCreate`'s background ringer reconcile from racing these
 * for the process-wide lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SnoozeNotificationsCountdownTest {

    private val now = Instant.parse("2026-08-22T09:00:00Z")

    @Before
    fun reset() {
        SnoozeNotifications.resetForTest()
        PrefsRingerLoanStore(appContext).recordChoice(null)
        appContext.getSystemService(AudioManager::class.java).ringerMode =
            AudioManager.RINGER_MODE_NORMAL
        DepartureObservations.clear()
    }

    @Test
    fun `a departure snooze with no chosen time fronts no countdown`() {
        // The cap is a passive eight-hour failsafe, not the plan, so the whole
        // time indicator is dropped — no chronometer, and `setShowWhen(false)`
        // so no corner timestamp stands in for it.
        val posted = post(snoozeFixture(now)) // default: FULL, ends on departure, no time
        assertFalse("chronometer", posted.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse("show when", posted.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
    }

    @Test
    fun `a chosen timer fronts a counting-down chronometer`() {
        val posted = post(
            snoozeFixture(now).copy(
                mode = TrackingMode.DURATION_ONLY,
                endsOnDeparture = false,
                timerOnlyRequested = true,
            ),
        )
        assertTrue("chronometer", posted.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue("counts down", posted.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
    }

    @Test
    fun `a motion snooze at the failsafe fronts no countdown`() {
        // A movement exit whose cap sits at the failsafe ceiling: "Until I move"
        // restored the cap there rather than leaving a chosen time behind the
        // exit (SPEC.md §4.4), so there is no shortened deadline to front.
        val motion = snoozeFixture(now).copy(endsOnMotion = true)
        assertFalse("precondition: nothing to count down", motion.capCountdownShown)
        val posted = post(motion)
        assertFalse("chronometer", posted.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse("show when", posted.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
    }

    @Test
    fun `a shortened cap behind a motion exit still fronts its countdown`() {
        // The Finding-3 case: a chosen time whose motion-exit removal could not
        // persist, or an interrupted "Until I move," leaves a shortened cap the
        // user must still see — capIsEffectiveEnd hides it, capCountdownShown
        // does not (SPEC.md §4.4, Codex PR #278).
        val partial = snoozeFixture(now).copy(
            endsOnMotion = true,
            capExpiresAt = now.plus(Duration.ofHours(1)),
        )
        assertFalse("precondition: not the effective end", partial.capIsEffectiveEnd)
        assertTrue("precondition: a shortened cap", partial.capCountdownShown)
        val posted = post(partial)
        assertTrue("chronometer", posted.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue("counts down", posted.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
    }

    private fun post(snooze: ActiveSnooze): Notification {
        SnoozeNotifications(appContext).showOngoing(snooze)
        val manager = appContext.getSystemService(NotificationManager::class.java)
        return requireNotNull(shadowOf(manager).getNotification(SnoozeNotifications.ID_ONGOING))
    }
}
