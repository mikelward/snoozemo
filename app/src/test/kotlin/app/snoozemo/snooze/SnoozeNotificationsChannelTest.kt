package app.snoozemo.snooze

import android.app.Notification
import android.app.NotificationManager
import app.snoozemo.R
import app.snoozemo.core.EndReason
import java.time.Instant
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
    fun `the ringer notice names the state and where to fix it`() {
        val notifications = SnoozeNotifications(appContext)

        notifications.showRingerStuck()

        val posted = shadowOf(manager).allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.failure_ringer_stuck) }
        // The snooze has ended, so this belongs beside the other after-the-fact
        // explanations rather than on the Do-Not-Disturb-bypassing channel.
        assertEquals(SnoozeNotifications.CHANNEL_ENDED, posted.channelId)
        assertEquals(stringOf(R.string.failure_ringer_stuck_body), shadowOf(posted).contentText?.toString())

        // And it comes down when the ringer is back, since nothing else would
        // take it down and it would otherwise outlive the problem.
        notifications.dropRingerStuck()
        assertTrue(
            shadowOf(manager).allNotifications.none {
                shadowOf(it).contentTitle?.toString() == stringOf(R.string.failure_ringer_stuck)
            },
        )
    }

    /**
     * `showOngoing()` reposts on every ARMED/CHECKING transition — including
     * presence evidence flip-flopping while a snooze runs, not just the
     * initial arm. Without `setOnlyAlertOnce`, each repost would re-sound or
     * re-vibrate on a channel that now bypasses Snoozemo's own DND — a snooze
     * that used to be silent by accident (the platform's own filter caught
     * the repeat alert) would otherwise become audibly noisy by design
     * (Codex, PR #92).
     */
    @Test
    fun `the ongoing card only alerts once, not on every repost`() {
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(snoozeFixture(Instant.parse("2026-08-22T09:00:00Z")))

        val posted = shadowOf(manager).allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.ongoing_title) }
        assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
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

    /**
     * `showStuckRule()`'s own attempt is the *only* one on the failed-arm
     * path — `armWithCap()` deliberately makes none, having twice been found
     * to sit ahead of a durable write it couldn't afford to risk (Codex, PR
     * #92). Without this, nothing would retry the bypass before the one
     * alert meant to survive a stuck rule finally posts, however many
     * alarm-scheduled release-escalation rungs later that turns out to be.
     */
    @Test
    fun `showStuckRule makes its own reapply attempt before posting`() {
        val notifications = SnoozeNotifications(appContext)
        // Explicit, not assumed: the guard only ever advances while access is
        // actually held, so this test states its precondition rather than
        // relying on whatever a fresh shadow's default happens to be.
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        assertFalse(SnoozeNotifications.bypassReapplyAttemptedForTest())

        notifications.showStuckRule()

        assertTrue(SnoozeNotifications.bypassReapplyAttemptedForTest())
    }
}
