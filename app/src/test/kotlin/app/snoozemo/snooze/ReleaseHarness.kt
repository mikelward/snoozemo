package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.ClockReading
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.RuleOwnership
import app.snoozemo.core.SnoozeIdentity
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenRuleActivation
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
/** The rule id the fake zen controller holds, and the fixture record names. */
internal const val OWN_RULE_ID = "rule-under-test"

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

    /** What the platform will say the rule is doing. */
    var activation: ZenRuleActivation = ZenRuleActivation.ACTIVE

    /**
     * What the platform will say about a rule *by id*, ahead of [activation] —
     * so a test can give the rule a record was armed with a different answer
     * from the one the app holds now (SPEC.md §5.8).
     */
    val activationById = mutableMapOf<String, ZenRuleActivation>()

    /** Every rule the state read-back was asked about, in order. */
    val activationAskedFor = mutableListOf<String?>()

    override fun ruleActivation(ruleId: String?): ZenRuleActivation {
        activationAskedFor += ruleId
        return ruleId?.let(activationById::get) ?: activation
    }

    /** The rule id this fake believes it currently holds. */
    var ownRuleId: String? = OWN_RULE_ID

    /**
     * Delegates to the real rule-ownership decision instead of answering
     * `true`.
     *
     * It used to answer `true` unconditionally, which made this fake unable to
     * fail for the one bug ownership can have: the receiver reading the wrong
     * extra off the status broadcast, so `ruleId` arrives null and nothing is
     * ever ours. A fake that says yes to every id reports success for a
     * question the app never actually got to ask.
     */
    override fun ownsRule(ruleId: String?, enforcing: String?): Boolean =
        RuleOwnership.isOurs(ruleId, current = ownRuleId, enforcing = enforcing)
    override fun ensureRule(): ZenRuleState = ZenRuleState.READY

    override fun setSnoozed(
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
        snooze: SnoozeIdentity?,
    ): ZenOutcome {
        calls += snoozed to trigger
        // The platform remembers, so the fake does too. Without this a test
        // could not see the failure mode where a *reassertion* is what makes
        // the later state read answer `ACTIVE` (Codex, PR #36) — the fake would
        // keep reporting whatever the test set, and the bug would pass.
        if (outcome is ZenOutcome.Applied) {
            activation = if (snoozed) ZenRuleActivation.ACTIVE else ZenRuleActivation.INACTIVE
        }
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

    /** What the service handed the monitor to restore with (Codex, PR #141). */
    val startedDegradations = mutableListOf<DegradationCause?>()
    var stops: Int = 0

    /** Emit into this to stand in for the sensors. */
    val updates = MutableSharedFlow<PresenceUpdate>(extraBufferCapacity = 16)

    override fun start(
        anchor: Anchor,
        sinceElapsedRealtimeMs: Long,
        armedAtEpochMs: Long,
        restoredDegradation: DegradationCause?,
    ): Flow<PresenceUpdate> {
        startedWith += anchor
        startedSeeds += sinceElapsedRealtimeMs
        startedArmedAtEpochMs += armedAtEpochMs
        startedDegradations += restoredDegradation
        return updates
    }

    override fun stop() {
        stops++
    }

    /**
     * Whether this monitor may still act on what an anchor holds — the
     * location grants and the phone's location setting, which the real
     * geofence monitor reads live inside [supportedModes].
     *
     * Settable, so a test can revoke them mid-snooze without a second fake.
     * That is the case the live read exists for: nothing announces either
     * loss to a monitor that is not running, so a stopped watch's recorded
     * mode goes stale and only asking again finds out.
     */
    var canActOnAnchors: Boolean = true

    /** The geofence monitor's rule, so the fixtures read like the real build. */
    override fun supportedModes(anchor: Anchor): Set<TrackingMode> = buildSet {
        if (canActOnAnchors) {
            if (anchor.hasUsableFix) add(TrackingMode.FULL)
            if (anchor.ssid != null) add(TrackingMode.WIFI_ONLY)
        }
        add(TrackingMode.DURATION_ONLY)
    }

    /**
     * Settable, so a test can be a build that never tracks departure without a
     * second fake. True by default, matching the geofence monitor these
     * fixtures stand in for.
     */
    override var canTrackDeparture: Boolean = true
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
        // The real runner starts inside `begin`, and both halves can settle
        // there — no location permission plus a refused Wi-Fi callback, or no
        // `ConnectivityManager` at all. [captureSettlesInBegin] is how a test
        // reaches that ordering, which is the one the service has to survive.
        captureSettlesInBegin?.let(onCaptured)
        return AutoCloseable { captureClosed++ }
    }

    override fun createPresenceMonitor(): PresenceMonitor = presence

    /**
     * The record store, with [refuseRecordUpdates] able to make every write
     * fail — which Robolectric's SharedPreferences never does on its own.
     */
    override fun createRecordStore(): ActiveSnoozeStore =
        object : ActiveSnoozeStore(applicationContext) {
            // **Writes, then reports failure** — which is what a refused
            // `commit()` actually does: the new values are already in the
            // preferences' in-process map, and only the disk write failed. A
            // fake that skipped the write instead made every "the record is
            // unchanged" assertion pass without the code under test having put
            // anything back (Codex, PR #252).
            override fun update(snooze: ActiveSnooze): Boolean {
                val wrote = super.update(snooze)
                val refused = refuseRecordUpdates || refuseRecordUpdateWhen?.invoke(snooze) == true
                return if (refused) false else wrote
            }
        }

    /**
     * The motion seam. A trigger sensor cannot be fired from a JVM test — or
     * from an emulator — so the test *is* the registrar, and a firing is a
     * method call.
     */
    override fun createMotionEndWatch(onMoved: () -> Unit): app.snoozemo.presence.MotionEndWatch =
        app.snoozemo.presence.MotionEndWatch(
            motionRegistrar,
            { testReading.uptimeMillis },
            onMoved,
        ).also { motionWatchesBuilt++ }

    /**
     * Robolectric's shadow accepts every `startForeground`, so a test that
     * needs the platform's refusal has to inject it here — the one thing the
     * production path cannot be driven to do off a device.
     */
    override fun enterForeground(id: Int, notification: android.app.Notification, type: Int) {
        if (refuseForeground) {
            throw android.app.ForegroundServiceStartNotAllowedException("refused by the test")
        }
        super.enterForeground(id, notification, type)
    }

    override fun exitForeground() {
        foregroundExits++
        if (refuseForegroundExit) {
            throw IllegalStateException("giving it back refused by the test")
        }
        super.exitForeground()
    }

    /**
     * Refuses the rule-status receiver's registration, which Robolectric
     * otherwise always accepts.
     *
     * Injected here rather than behind a new production seam so the *real*
     * `runCatching` in `onCreate` is what fails and the *real*
     * `ruleStatusReceiverRegistered` is what records it. That flag is half the
     * condition guarding the restore read, and a fake of it would let the
     * guard pass a test while the service it stands for was never blind.
     */
    override fun registerReceiver(
        receiver: android.content.BroadcastReceiver?,
        filter: android.content.IntentFilter?,
        flags: Int,
    ): Intent? {
        if (refuseRuleStatusReceiver &&
            filter?.hasAction(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED) == true
        ) {
            throw SecurityException("rule-status registration refused by the test")
        }
        return super.registerReceiver(receiver, filter, flags)
    }

    override fun pokeWatchRepair() {
        repairPokes++
    }

    override fun pokeGrantRecheck() {
        grantPokes++
    }

    companion object {
        /**
         * Stands in for `TYPE_SIGNIFICANT_MOTION`. `fire()` is one movement;
         * `armed` says whether the service is currently listening, which is
         * what a test asserts about a snooze that did or did not ask for the
         * watch.
         */
        class FakeMotionRegistrar : app.snoozemo.presence.TriggerRegistrar {
            /** The live registration's callback, or null when nothing is armed. */
            private var pending: (() -> Unit)? = null

            /** Registrations this registrar has been asked for, ever. */
            var arms: Int = 0

            /**
             * False stands in for a device with no significant-motion sensor,
             * and for a platform that refuses the registration — the two cases
             * `PlatformMotionTrigger` collapses into a null handle.
             */
            var available: Boolean = true

            val armed: Boolean get() = pending != null

            override fun arm(onFired: () -> Unit): AutoCloseable? {
                arms++
                if (!available) return null
                pending = onFired
                // Identity-checked, so cancelling a spent registration cannot
                // disarm the one that replaced it — the same hazard the real
                // trigger's generation counter exists for.
                return AutoCloseable { if (pending === onFired) pending = null }
            }

            /** One movement, as the platform would deliver it. */
            fun fire() {
                // Cleared first: a real trigger sensor disarms itself in
                // firing, and a fake that stayed armed would let a test pass
                // against a lifecycle the platform does not have.
                val callback = pending ?: return
                pending = null
                callback()
            }
        }

        var motionRegistrar: FakeMotionRegistrar = FakeMotionRegistrar()

        /** How many motion watches the service built — one per snooze, at most. */
        var motionWatchesBuilt: Int = 0

        /**
         * Makes every `ActiveSnoozeStore.update` refuse, as a full disk would —
         * having already updated the in-process map, exactly as a refused
         * `commit()` leaves it.
         */
        var refuseRecordUpdates: Boolean = false

        /**
         * Refuses only the writes this matches, which [refuseRecordUpdates]
         * cannot express: an ordering defect shows up when one write of a pair
         * lands and the other does not, and a switch that fails *every* write
         * never reaches the second one. Matched on the record being written, so
         * a test names the write it means ("the one turning departure on")
         * rather than counting — the transitions commit records of their own,
         * so a positional count is not stable.
         */
        var refuseRecordUpdateWhen: ((ActiveSnooze) -> Boolean)? = null

        /** Fence-repair pokes the service sent through the presence seam. */
        var repairPokes: Int = 0

        /** Grant-recheck pokes the service sent through the presence seam. */
        var grantPokes: Int = 0

        /** Makes the rule-status receiver's registration throw. */
        var refuseRuleStatusReceiver: Boolean = false

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

        /**
         * The capture's other callback: what it calls when location cannot
         * answer at all, held separately so a test can fire it without also
         * landing an anchor and ending the arm.
         */

        /** How many captures were closed — by an exit, or by a replacement. */
        var captureClosed: Int = 0

        /**
         * An anchor the capture delivers **before `begin` returns**, or null
         * for the ordinary asynchronous delivery through [captureRequests].
         */
        var captureSettlesInBegin: Anchor? = null

        /**
         * Both clocks, frozen. The uptime is arbitrary but plausible and moves
         * with the wall reading, so a fixture reads as an undisturbed device
         * rather than as one whose clock has been tampered with.
         */
        var testReading: ClockReading = ClockReading(
            wallMillis = System.currentTimeMillis(),
            uptimeMillis = FIXTURE_UPTIME_MILLIS,
        )

        /**
         * Makes `startForeground` throw, standing in for every way the
         * platform declines one — a background start it refuses, location
         * services off, the runtime grant withdrawn. Robolectric's shadow
         * accepts them all, so the refusal has to be injected here.
         */
        var refuseForeground: Boolean = false

        /**
         * Refuses the *give-back*, which no shadow throws either — and which
         * is where clearing the held flag too early stranded the service.
         */
        var refuseForegroundExit: Boolean = false

        /** How many times the service tried to give the foreground back. */
        var foregroundExits: Int = 0

        fun reset(now: Instant) {
            refuseForeground = false
            refuseForegroundExit = false
            refuseRuleStatusReceiver = false
            foregroundExits = 0
            zen = RefusingZen()
            captureRequests = mutableListOf()
            captureClosed = 0
            captureSettlesInBegin = null
            repairPokes = 0
            grantPokes = 0
            presence = FakePresenceMonitor()
            motionRegistrar = FakeMotionRegistrar()
            motionWatchesBuilt = 0
            refuseRecordUpdates = false
            refuseRecordUpdateWhen = null
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
    // Named, so ownership is a real comparison rather than a fake's yes.
    ruleId = OWN_RULE_ID,
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
    record?.let { ActiveSnoozeStore(appContext).arm(it) }
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
/** The ongoing card's body text, or an empty string if there is none. */
internal fun shadeText(): String =
    shadowOf(appContext.getSystemService(NotificationManager::class.java))
        .allNotifications
        .lastOrNull { shadowOf(it).contentTitle?.toString() == appContext.getString(app.snoozemo.R.string.ongoing_title) }
        ?.let { shadowOf(it).contentText?.toString() }
        .orEmpty()

internal fun shadeShows(title: String): Boolean =
    shadowOf(appContext.getSystemService(NotificationManager::class.java))
        .allNotifications
        .any { shadowOf(it).contentTitle?.toString() == title }

/**
 * The ongoing card's body — the line that names what ends this snooze.
 *
 * The durable report of an exit a chosen time could not take off lives here
 * now, rather than on a card of its own (SPEC.md §4.4), so this is what the
 * tests about that assert against. `contentText`, not the title: the title is
 * the constant `Snoozing`.
 */
internal fun ongoingBody(): String? =
    shadowOf(appContext.getSystemService(NotificationManager::class.java))
        .allNotifications
        .lastOrNull {
            shadowOf(it).contentTitle?.toString() == stringOf(app.snoozemo.R.string.ongoing_title)
        }
        ?.let { shadowOf(it).contentText?.toString() }

/** The scheduled alarms, newest last, as the intents their senders carry. */
internal fun scheduledAlarmIntents(): List<Intent> =
    shadowOf(appContext.getSystemService(android.app.AlarmManager::class.java))
        .scheduledAlarms
        .mapNotNull { shadowOf(it.operation).savedIntent }

internal fun stringOf(id: Int): String = appContext.getString(id)
