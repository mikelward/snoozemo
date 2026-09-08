package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import android.media.AudioManager
import android.os.SystemClock
import app.snoozemo.R
import app.snoozemo.core.DepartureObservation
import app.snoozemo.core.TrackingMode
import app.snoozemo.dnd.PrefsRingerLoanStore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * The distance on the ongoing card's top row (`SPEC.md` §4.6), asserted where
 * the user reads it.
 *
 * Its own file rather than more cases in the degradation one: that file is
 * about `contentText` and sets a fixture up to keep the ringer clause out of
 * it, where this is about `subText` and about *when* there is nothing to say.
 * Both post the same card, so a test that reads the wrong field would pass
 * against a blank one.
 *
 * The same `Application` note applies as there — a plain one, so
 * `SnoozemoApplication.onCreate`'s background ringer reconcile does not race
 * these for the process-wide lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SnoozeNotificationsDistanceTest {

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
    @Config(qualifiers = "en-rGB")
    fun `the top row says how much further this snooze needs`() {
        // Remaining, not distance away: it sits beside a countdown, and the
        // two only read as one answer if both count toward zero.
        //
        // 60 m inside a 100 m anchor, with 15 m and 10 m accuracies: the
        // combined uncertainty is 18.0 m, so the margin is -58.0 m and the
        // ground still to cover is that plus the 50 m hysteresis — 108.0 m,
        // ceiled to 109. Spelled out because the number is the point: it is a
        // property of *this* reading, not of the anchor, so a vaguer fix would
        // legitimately print a bigger one.
        publish(distanceM = 60.0)

        assertEquals(
            appContext.getString(R.string.distance_meters, 109),
            subText(TrackingMode.FULL),
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `a US phone reads the same distance in feet`() {
        // The unit rule moved out of `MainScreen` to be shared with this card,
        // so it is asserted on this side too — a card that quietly answered it
        // differently from the screen would be worse than either being wrong,
        // since the user can see both at once.
        //
        // 355, not the 358 that converting a rounded 109 m would give: the
        // conversion runs before the ceiling, so the foot figure is ceiled from
        // 354.4 rather than derived from the meter one.
        publish(distanceM = 60.0)

        assertEquals(
            appContext.getString(R.string.distance_feet, 355),
            subText(TrackingMode.FULL),
        )
    }

    @Test
    fun `far enough already reports the wait, not a distance`() {
        // A departure still needs a second qualifying fix thirty seconds later
        // (SPEC.md §6.6), so "0 m to go" would be an ending that has not
        // happened. `DistanceUnit.toGo` floors at 1 rather than 0, so without
        // this branch the row would read "1 m to go" for the whole
        // confirmation window — a number, and the wrong one.
        publish(distanceM = 400.0)

        assertEquals(stringOf(R.string.ongoing_distance_confirming), subText(TrackingMode.FULL))
    }

    @Test
    fun `a mode that measures no distance shows none`() {
        // The same rule the main screen applies to the same reading: a number
        // from the last fix before tracking degraded explains a threshold that
        // is no longer what ends this snooze.
        publish(distanceM = 60.0)

        assertNull(subText(TrackingMode.WIFI_ONLY))
        assertNull(subText(TrackingMode.DURATION_ONLY))
        assertNull(subText(TrackingMode.WIFI_GRACE))
    }

    @Test
    fun `no reading yet shows nothing rather than a placeholder`() {
        assertNull(subText(TrackingMode.FULL))
    }

    @Test
    fun `a stale reading is dropped, not shown as current`() {
        // A distance from ten minutes ago is worse than no distance: the
        // resting duty cycle (SPEC.md §6.7) can leave a gap that long on a
        // phone that has not moved, and the row would keep claiming the
        // number was now.
        publish(distanceM = 60.0)
        ShadowSystemClock.advanceBy(
            java.time.Duration.ofMillis(DepartureObservation.FRESH_FOR_MS + 1),
        )

        assertNull(subText(TrackingMode.FULL))
    }

    private fun publish(distanceM: Double) = DepartureObservations.publish(
        DepartureObservation(
            distanceM = distanceM,
            accuracyM = 15f,
            anchorAccuracyM = 10f,
            radiusM = 100,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        ),
    )

    /** The top row of the ongoing card as posted for [mode]. */
    private fun subText(mode: TrackingMode): String? {
        SnoozeNotifications(appContext).showOngoing(snoozeFixture(now).copy(mode = mode))
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.ongoing_title) }
        return posted.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()
    }
}
