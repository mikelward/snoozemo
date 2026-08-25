package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.ClockReading
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleState
import app.snoozemo.core.ZenTrigger
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    /** Quiet, as a snooze this harness is releasing would be. */
    override fun audible(): Boolean = false

    override fun ensureRule(): ZenRuleState = ZenRuleState.READY

    override fun setSnoozed(
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
    ): ZenOutcome {
        calls += snoozed to trigger
        return outcome
    }

    override fun ruleId(): String? = "refusing-zen-rule-id"
}

/**
 * A [PresenceMonitor] a test can drive: it records what it was started with
 * and hands the test the emitting end of the flow the service collects.
 */
internal class FakePresenceMonitor : PresenceMonitor {

    val startedWith = mutableListOf<Anchor>()
    val startedSeeds = mutableListOf<Long>()
    val startedArmedAtEpochMs = mutableListOf<Long>()
    var stops: Int = 0

    /** Emit into this to stand in for the sensors. */
    val updates = MutableSharedFlow<PresenceUpdate>(extraBufferCapacity = 16)

    override fun start(anchor: Anchor, sinceElapsedRealtimeMs: Long, armedAtEpochMs: Long): Flow<PresenceUpdate> {
        startedWith += anchor
        startedSeeds += sinceElapsedRealtimeMs
        startedArmedAtEpochMs += armedAtEpochMs
        return updates
    }

    override fun stop() {
        stops++
    }

    /** The geofence monitor's rule, so the fixtures read like the real flavor. */
    override fun supportedModes(anchor: Anchor): Set<TrackingMode> = buildSet {
        if (anchor.hasUsableFix) add(TrackingMode.FULL)
        if (anchor.ssid != null) add(TrackingMode.WIFI_ONLY)
        add(TrackingMode.DURATION_ONLY)
    }
}

/**
 * [SnoozeService] with its seams filled in.
 *
 * The statics are how a test configures an instance Android would otherwise
 * construct for itself; [reset] clears them so nothing leaks between tests.
 */
internal class TestSnoozeService : SnoozeService() {

    override fun createZenController(): ZenController = zen

    override val readClock: () -> ClockReading get() = { testReading }

    override fun beginAnchorCapture(
        capturedAt: Instant,
        onCaptured: (Anchor) -> Unit,
    ): AutoCloseable {
        captureRequests += onCaptured
        return AutoCloseable { captureClosed++ }
    }

    override fun createPresenceMonitor(): PresenceMonitor = presence

    override fun pokeWatchRepair() {
        repairPokes++
    }

    companion object {
        /** Fence-repair pokes the service sent through the flavor seam. */
        var repairPokes: Int = 0

        /** Arbitrary but plausible: the fixture device booted 30 h ago. */
        const val FIXTURE_UPTIME_MILLIS: Long = 30L * 60 * 60 * 1000

        var zen: RefusingZen = RefusingZen()

        var presence: FakePresenceMonitor = FakePresenceMonitor()

        /**
         * Every capture the service started, as the callback each would hand
         * its anchor to — a test *is* the runner behind the seam, so it
         * delivers (or withholds) the anchor at the moment under test.
         */
        var captureRequests = mutableListOf<(Anchor) -> Unit>()

        /** How many captures were closed — by an exit, or by a replacement. */
        var captureClosed: Int = 0

        /**
         * Both clocks, frozen. The uptime is arbitrary but plausible and moves
         * with the wall reading, so a fixture reads as an undisturbed device
         * rather than as one whose clock has been tampered with.
         */
        var testReading: ClockReading = ClockReading(
            wallMillis = System.currentTimeMillis(),
            uptimeMillis = FIXTURE_UPTIME_MILLIS,
        )

        fun reset(now: Instant) {
            zen = RefusingZen()
            captureRequests = mutableListOf()
            captureClosed = 0
            repairPokes = 0
            presence = FakePresenceMonitor()
            testReading = ClockReading(
                wallMillis = now.toEpochMilli(),
                uptimeMillis = FIXTURE_UPTIME_MILLIS,
            )
            // The backstop schedules through WorkManager on every watch
            // start, and the production initializer is absent under
            // Robolectric — without this every startPresence would log a
            // refused schedule instead of exercising the real enqueue.
            androidx.work.testing.WorkManagerTestInitHelper
                .initializeTestWorkManager(appContext)
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
    // Stamped as the harness's frozen device would have stamped it, so the
    // fixture's two frames agree and the cap reads the same either way.
    bootReference = now.toEpochMilli() - TestSnoozeService.FIXTURE_UPTIME_MILLIS,
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
