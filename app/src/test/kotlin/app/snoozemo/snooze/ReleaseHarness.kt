package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleState
import app.snoozemo.core.ZenTrigger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The harness for [SnoozeService]'s half of the release escalation.
 *
 * SPEC.md §7.1's ladder became a pure function in `:core` and is covered there.
 * What it does *not* cover is the performing half — which alarm the service
 * arms, which end reason a retry carries, which message the user is left
 * looking at — and five real bugs shipped to review in exactly that half, every
 * one caught by reading rather than by a test.
 *
 * The reason it was untestable is narrow: every branch is reached only when the
 * platform **refuses** a zen write, which no device and no emulator will do. So
 * the only fake here is [RefusingZen]; everything else is real and observed
 * through Robolectric's shadows — the notifications the user would see, and the
 * alarms with the extras actually packed into their `PendingIntent`s. That
 * matters, because two of the five bugs were *in* an extra and a notification
 * id, which a fake of either would have hidden rather than caught.
 */
internal class RefusingZen : ZenController {

    /** Every state change this was asked for, in order, as (snoozed, trigger). */
    val calls = mutableListOf<Pair<Boolean, ZenTrigger>>()

    /**
     * What to answer. [ZenFailure.PLATFORM_REFUSED] is the one that drives the
     * escalation: the others mean there is nothing left holding the phone quiet
     * (`ZenFailure.nothingLeftToRelease`), so the ladder settles instead.
     */
    var outcome: ZenOutcome = ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)

    override fun policyAccess(): PolicyAccess = PolicyAccess.GRANTED

    override fun ensureRule(): ZenRuleState = ZenRuleState.READY

    override fun setSnoozed(
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
    ): ZenOutcome {
        calls += snoozed to trigger
        return outcome
    }
}

/**
 * [SnoozeService] with its two seams filled in.
 *
 * The statics are how a test configures an instance Android would otherwise
 * construct for itself; [reset] clears them so nothing leaks between tests.
 */
internal class TestSnoozeService : SnoozeService() {

    override fun createZenController(): ZenController = zen

    override val clock: Clock get() = testClock

    companion object {
        var zen: RefusingZen = RefusingZen()
        var testClock: Clock = Clock.systemUTC()

        fun reset(now: Instant) {
            zen = RefusingZen()
            testClock = Clock.fixed(now, ZoneOffset.UTC)
        }
    }
}

/** A snooze that started [startedAgo] ago and caps [capIn] from now. */
internal fun snoozeFixture(
    now: Instant,
    startedAgo: Duration = Duration.ofHours(1),
    capIn: Duration = Duration.ofHours(7),
): ActiveSnooze = ActiveSnooze(
    // Stock stand-ins, never a device capture: these ship to the repo, and the
    // privacy rule covers fixtures as much as logs (AGENTS.md, *Privacy*).
    anchor = Anchor(lat = 0.0, lon = 0.0, fixAccuracyM = 10f, capturedAt = now, ssid = "ExampleWifi"),
    startedAt = now.minus(startedAgo),
    capExpiresAt = now.plus(capIn),
    mode = TrackingMode.FULL,
    placeName = "Home",
)

internal val appContext: Application get() = ApplicationProvider.getApplicationContext()

/** Starts the service for [action], with the record already on disk. */
internal fun startService(
    action: String,
    record: ActiveSnooze? = null,
    extras: Intent.() -> Unit = {},
): ServiceController<TestSnoozeService> {
    record?.let { ActiveSnoozeStore(appContext).save(it) }
    val intent = Intent(appContext, TestSnoozeService::class.java).setAction(action).apply(extras)
    return Robolectric.buildService(TestSnoozeService::class.java, intent).create().startCommand(0, 1)
}

/** The one-shot failure notification currently in the shade, or null. */
internal fun postedOneShot(): String? =
    shadowOf(appContext.getSystemService(NotificationManager::class.java))
        .allNotifications
        .lastOrNull { shadowOf(it).contentTitle != null && it.extras != null }
        ?.let { shadowOf(it).contentTitle?.toString() }

/** Whether any notification carrying [title] is in the shade. */
internal fun shadeShows(title: String): Boolean =
    shadowOf(appContext.getSystemService(NotificationManager::class.java))
        .allNotifications
        .any { shadowOf(it).contentTitle?.toString() == title }

/** The scheduled alarms, newest last, as the intents their senders carry. */
internal fun scheduledAlarmIntents(): List<Intent> =
    shadowOf(appContext.getSystemService(android.app.AlarmManager::class.java))
        .scheduledAlarms
        .mapNotNull { shadowOf(it.operation).savedIntent }

internal fun stringOf(id: Int): String = appContext.getString(id)
