package app.snoozemo.snooze

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper.getMainLooper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContextImpl

/**
 * What a tap does when the platform refuses to start the service
 * (`TileTrampolineActivity.recoverFromRefusedStart`, `TODO.md`'s coverage gap
 * from PR #238).
 *
 * The branch that matters most is `End now`: the user has spent their exit, and
 * with no service behind it the activity releases the snooze itself. A mistake
 * there leaves the phone quiet after the user asked for it not to be
 * (principle 1). The other branches owe the user a reason the tap did nothing
 * (principle 2).
 *
 * Robolectric accepts every `startService`, so the refusal comes from
 * [RefusingStartContextImpl], a test-only shadow that throws the way a refused
 * background start does on a device. No production seam was needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [RefusingStartContextImpl::class])
class TileTrampolineRefusedStartTest {

    private val appContext: Application = ApplicationProvider.getApplicationContext()

    private val manager get() = appContext.getSystemService(NotificationManager::class.java)

    private var controller: ActivityController<TileTrampolineActivity>? = null

    @Before
    fun setUp() {
        // Instant arm: with the chooser on, an arm tap opens the main screen
        // and never reaches `startService` at all.
        EndSheetStore.resetCacheForTest()
        EndSheetStore(appContext).setEnabled(false)
        DeviceStamp.forget()
        ActiveSnoozeStore(appContext).clear()
        SnoozeNotifications.resetForTest()
        // Unlocked, notifications granted and Do Not Disturb access held, so the
        // tap takes the plain start-the-service route rather than a setup or
        // permission branch.
        shadowOf(appContext.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(appContext).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        RefusingStartContextImpl.refuse = true
    }

    @After
    fun tearDown() {
        RefusingStartContextImpl.refuse = false
        controller?.destroy()
        controller = null
    }

    private fun tap(intent: Intent) {
        Robolectric.buildActivity(TileTrampolineActivity::class.java, intent)
            .also { controller = it }
            // Past onboarding, so an arm tap is not routed back to the welcome flow.
            .also { WelcomeGate.publish(false) }
            .setup()
        shadowOf(getMainLooper()).idle()
    }

    private fun tap(action: String, extras: Intent.() -> Unit = {}) =
        tap(Intent(appContext, TileTrampolineActivity::class.java).setAction(action).apply(extras))

    /** A snooze running on a timer, with a stock stand-in for its place. */
    private fun running(): ActiveSnooze {
        // Whole milliseconds, which is what the record stores.
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        return ActiveSnooze(
            anchor = Anchor(capturedAt = now, ssid = "ExampleWifi"),
            startedAt = now.minus(Duration.ofMinutes(10)),
            capExpiresAt = now.plus(Duration.ofHours(1)),
            mode = TrackingMode.DURATION_ONLY,
        ).also { ActiveSnoozeStore(appContext).arm(it) }
    }

    /** The title of the one-shot failure card, or null if none was posted. */
    private fun failureCard(): String? =
        shadowOf(manager).getNotification(SnoozeNotifications.ID_FAILURE)
            ?.let { shadowOf(it).contentTitle?.toString() }

    @Test
    fun `a refused end now ends the snooze anyway`() {
        // The user's exit, with nothing behind it: the activity has to release
        // the snooze itself, or the phone stays quiet after they asked.
        running()

        tap(SnoozeService.ACTION_END)

        assertNull("the snooze must be over", ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a refused stuck-rule release still releases`() {
        running()

        tap(SnoozeService.ACTION_RELEASE_STUCK)

        assertNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a refused plus thirty says it could not extend and leaves the snooze alone`() {
        val snooze = running()

        tap(SnoozeService.ACTION_EXTEND)

        assertEquals(stringOf(R.string.failure_could_not_extend), failureCard())
        assertEquals(
            "nothing was extended, and nothing was ended either",
            snooze.capExpiresAt,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
    }

    @Test
    fun `a refused end time says it could not set the end`() {
        val snooze = running()

        tap(SnoozeService.ACTION_SET_CAP) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, snooze.capExpiresAt.plus(Duration.ofHours(1)).toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, snooze.startedAt.toEpochMilli())
        }

        assertEquals(stringOf(R.string.failure_could_not_set_end), failureCard())
        assertNotNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a refused still here says the place could not be updated`() {
        // It names no end time, so "Couldn't set the end time" would describe a
        // request the user never made (Codex, PR #330).
        val snooze = running()

        tap(SnoozeService.ACTION_SET_CAP) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, snooze.startedAt.toEpochMilli())
        }

        assertEquals(stringOf(R.string.failure_could_not_update_place), failureCard())
        assertNotNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a refused arm says it could not snooze and remembers why`() {
        tap(SnoozeService.ACTION_ARM)

        assertEquals(stringOf(R.string.failure_could_not_start), failureCard())
        // Kept for a later notification grant to explain a tap that seemed to
        // do nothing, if this card was never seen.
        assertEquals(ArmFailure.BelowZen, PendingFailureStore(appContext).peek())
        assertNull("nothing was armed", ActiveSnoozeStore(appContext).load())
    }
}

/**
 * A `ContextImpl` whose `startService` throws while [refuse] is set, the way a
 * background-start refusal does on a device. Everything else is Robolectric's
 * own shadow.
 */
@Implements(className = ShadowContextImpl.CLASS_NAME, isInAndroidSdk = false)
class RefusingStartContextImpl : ShadowContextImpl() {

    @Implementation
    override fun startService(service: Intent): ComponentName? {
        if (refuse) throw IllegalStateException("Not allowed to start service (refused by the test)")
        return super.startService(service)
    }

    companion object {
        /** Read from the shadow, so it is a static the test flips. */
        @JvmStatic
        var refuse = false
    }
}
