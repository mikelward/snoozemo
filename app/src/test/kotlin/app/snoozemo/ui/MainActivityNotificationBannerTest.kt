package app.snoozemo.ui

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import app.snoozemo.snooze.SnoozeNotifications
import app.snoozemo.snooze.WelcomeStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the main screen's required-notifications banner is actually fed
 * (`SPEC.md` §4.2).
 *
 * `MainScreen`'s own cases live in `MainScreenScreenshotTest`, which takes the
 * reading as a parameter. What is asserted here is the *mapping* — the step
 * that turns a manager into that reading, and the one place two definite
 * failures could be mistaken for "not read yet".
 *
 * Both were already known to `canReachTheUser` (Codex, PR #18) and were dropped
 * by the narrower read this screen uses; they are back (Codex, PR #216). The
 * distinction that makes them failures here and not on the tile's path is
 * ordering: this runs after a `SnoozeNotifications` has been constructed, so
 * `ensureChannels()` has already had its chance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityNotificationBannerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        context.getSharedPreferences("welcome", Context.MODE_PRIVATE).edit().clear().commit()
        // Spent, so these land on Main rather than the welcome flow.
        WelcomeStore(context).markSeen()
        // `channelsCreated` is process-wide, so a class that ran earlier in
        // this JVM leaves it set — and the next construction then skips
        // creation against a shadow manager that has no channels, which reads
        // as "the creation was refused". Resetting it is what makes each case
        // here measure the reason it names.
        SnoozeNotifications.resetForTest()
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        // Granted, so the permission is never what these cases are measuring —
        // each one isolates a single reason the ongoing card could not post.
        shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launch(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Test
    fun `the app-wide switch counts as missing even while the permission is held`() {
        // The permission can read granted while every post is dropped. Treating
        // this as unread would leave the screen silent about a snooze that will
        // run with no countdown and no End now.
        shadowOf(manager).setNotificationsEnabled(false)

        assertEquals(false, launch().activeChannelEnabled)
    }

    @Test
    fun `a channel that could not be created counts as missing, not unread`() {
        // Absent on the tile's path means "the service has not started yet".
        // Here the channels have already been ensured, so absent means the
        // creation was refused — and posting to a channel that does not exist
        // throws.
        shadowOf(manager).setNotificationsEnabled(true)
        val activity = launch()
        manager.deleteNotificationChannel(SnoozeNotifications.CHANNEL_ACTIVE)
        activity.refreshNotificationsForTest()

        assertEquals(false, activity.activeChannelEnabled)
    }

    @Test
    fun `a working setup reads as working`() {
        // The other direction. Without this the two above would pass on a
        // mapping that reported everything as missing.
        shadowOf(manager).setNotificationsEnabled(true)

        assertEquals(true, launch().activeChannelEnabled)
    }
}
