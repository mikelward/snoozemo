package app.snoozemo.snooze

import android.app.NotificationManager
import app.snoozemo.R
import app.snoozemo.core.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The channel-level half of bypassing Snoozemo's own Do Not Disturb (SPEC.md
 * §5.7): which channels claim the exemption, and which alert lands on which.
 *
 * What this can and cannot prove: Robolectric's shadow stores whatever channel
 * configuration this code asks for, faithfully — it does not simulate the
 * platform's `ACCESS_NOTIFICATION_POLICY` gating that silently drops a
 * requested `bypassDnd` when the caller lacks that access (`SPEC.md` §5.7,
 * `TODO.md` hardware item 12). So these tests are the "did we ask for the
 * right policy" layer — which channels request the exemption and which alert
 * is routed to which — not "does the platform honor it", which stays a
 * device check.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeNotificationsChannelTest {

    private val manager get() = appContext.getSystemService(NotificationManager::class.java)

    @Before
    fun reset() {
        SnoozeNotifications.resetForTest()
    }

    @Test
    fun `the ongoing and urgent channels ask to bypass Do Not Disturb`() {
        SnoozeNotifications(appContext)

        assertTrue(manager.getNotificationChannel(SnoozeNotifications.CHANNEL_ACTIVE).canBypassDnd())
        assertTrue(manager.getNotificationChannel(SnoozeNotifications.CHANNEL_URGENT).canBypassDnd())
    }

    @Test
    fun `the ended channel deliberately does not bypass Do Not Disturb`() {
        SnoozeNotifications(appContext)

        assertFalse(manager.getNotificationChannel(SnoozeNotifications.CHANNEL_ENDED).canBypassDnd())
    }

    @Test
    fun `the stuck-rule alert posts on the bypassing urgent channel`() {
        val notifications = SnoozeNotifications(appContext)

        notifications.showStuckRule()

        val posted = shadowOf(manager).allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.failure_rule_stuck) }
        assertEquals(SnoozeNotifications.CHANNEL_URGENT, posted.channelId)
    }

    @Test
    fun `a routine ended notification stays on the non-bypassing ended channel`() {
        val notifications = SnoozeNotifications(appContext)

        notifications.showEnded(EndReason.DEPARTURE)

        val posted = shadowOf(manager).allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.ended_departure) }
        assertEquals(SnoozeNotifications.CHANNEL_ENDED, posted.channelId)
    }

    @Test
    fun `reapplying the bypass is safe to repeat and leaves every channel correctly configured`() {
        val notifications = SnoozeNotifications(appContext)

        notifications.reapplyDndBypass()
        notifications.reapplyDndBypassOnce()

        assertTrue(manager.getNotificationChannel(SnoozeNotifications.CHANNEL_ACTIVE).canBypassDnd())
        assertTrue(manager.getNotificationChannel(SnoozeNotifications.CHANNEL_URGENT).canBypassDnd())
        assertFalse(manager.getNotificationChannel(SnoozeNotifications.CHANNEL_ENDED).canBypassDnd())
    }

    /**
     * `createNotificationChannel` not throwing is not the same as the
     * platform having actually honored `bypassDnd` — a caller lacking
     * `ACCESS_NOTIFICATION_POLICY` gets a normal return with the flag
     * silently kept false, which an arm attempted before the user ever
     * granted access reaches just as surely as a granted one does (Codex,
     * PR #92). Robolectric's shadow does not simulate that gating (see the
     * class doc), so what this asserts is the guard itself: a no-access
     * attempt must not consume it, or a later, access-holding attempt would
     * never run.
     */
    @Test
    fun `reapplyDndBypassOnce does not consume its guard while access is denied`() {
        val notifications = SnoozeNotifications(appContext)
        shadowOf(manager).setNotificationPolicyAccessGranted(false)

        notifications.reapplyDndBypassOnce()
        assertFalse(SnoozeNotifications.bypassReapplyAttemptedForTest())

        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        notifications.reapplyDndBypassOnce()
        assertTrue(SnoozeNotifications.bypassReapplyAttemptedForTest())
    }
}
