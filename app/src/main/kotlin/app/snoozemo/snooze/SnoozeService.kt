package app.snoozemo.snooze

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.Attempt
import app.snoozemo.core.ClockChange
import app.snoozemo.core.ClockChangeAction
import app.snoozemo.core.ClockReading
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.EndReason
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.PolicyAccessAction
import app.snoozemo.core.PolicyAccessChange
import app.snoozemo.core.PresenceEvent
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.core.ReleaseEscalation
import app.snoozemo.core.ReleaseProgress
import app.snoozemo.core.ReleaseStep
import app.snoozemo.core.SnoozeController
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.SnoozeState
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenTrigger
import app.snoozemo.core.logSummary
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.presence.AnchorCaptureRunner
import app.snoozemo.presence.defaultPresenceMonitor
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "SnoozeService"

/**
 * Owns the running snooze: the state machine, the record, the cap alarm, the
 * ongoing notification, and the policy-access watch.
 *
 * A service rather than the activity, because every one of those has to outlive
 * whatever screen the user was looking at. Phase 1 kept the policy-access
 * receiver on the activity, so a revocation while backgrounded went unnoticed
 * until the app was reopened; that lives here now, for as long as a snooze is
 * running (`TODO.md`, flagged by Codex on PR #5).
 *
 * Not a foreground service in the `play` flavor: SPEC.md §3 puts departure
 * detection on the Geofencing API precisely so there is no long-running service
 * to justify. The `direct` flavor's foreground service arrives in Phase 7 behind
 * `PresenceMonitor`.
 */
open class SnoozeService : Service(), SnoozeController.Listener {

    private lateinit var store: ActiveSnoozeStore
    /**
     * Built on first use, not in `onCreate`, because constructing it creates
     * the two notification channels — two synchronous binder calls.
     *
     * `SnoozeNotifications.warm` normally pays for those at process start, but
     * a cold tile tap is exactly the case where it may not have finished: the
     * tap is what started the process. Constructing here would then have put
     * both calls between the tap and `STATE_TRUE`, which is the one stretch of
     * this app nothing may sit in (`AGENTS.md`, the arm path).
     *
     * Safe because no path touches this before the zen write on a *successful*
     * arm: `ARMING` writes the record and nothing else, and the ongoing
     * notification goes up on `ARMED`. The paths that do touch it earlier are
     * the failure ones — a cap that wouldn't schedule, an arm that was refused
     * — where there is no snooze left to be quick for.
     */
    private val notifications: SnoozeNotifications by lazy {
        SnoozeNotifications(applicationContext)
    }
    private lateinit var pendingFailure: PendingFailureStore
    private lateinit var controller: SnoozeController

    /**
     * Shared with the controller, so the cap is read from one place only.
     *
     * Overridable for tests, which is the only reason it is not private. The
     * decisions that read it — whether a re-armed cap is imminent, whether a
     * record has expired — are exactly the ones that must not be driven by real
     * elapsed time (AGENTS.md, *Testing expectations*).
     */
    internal open val readClock: () -> ClockReading = SnoozeClock::read

    /**
     * Whether the last attempt to erase the record actually reached disk. False
     * keeps this service alive, because it is the only thing that will retry.
     */
    private var recordErased: Boolean = true

    /** Bounds the in-process erase retry of [forget]; reset by a success. */
    private var eraseRetries: Int = 0

    /**
     * How far the running escalation has got, or null when nothing is owed.
     *
     * The state half of [ReleaseEscalation]: what has actually been put behind a
     * rule that would not come off. [advanceRelease] is the only thing that
     * moves it, and it moves it with outcomes rather than intentions.
     */
    private var releaseProgress: ReleaseProgress? = null

    /**
     * Which snooze the running escalation is for, or null when nothing on disk
     * describes the rule at all.
     *
     * The one thing that differs between the two escalations this used to have:
     * a snooze with a record can be ended through the controller and needs the
     * identified alarm, while a recordless rule can only be driven off directly.
     */
    private var releasingSnooze: Instant? = null

    /**
     * Why the running escalation is ending its snooze.
     *
     * Carried for the whole escalation rather than decided per attempt, because
     * every attempt is finishing the *same* end. A retry that picked its own
     * would tell the user their manual exit was a duration cap, and hand the
     * platform's Modes UI `ZenTrigger.CONTEXT` where `USER_ACTION` is what
     * happened — the distinction SPEC.md §5.4 keeps the trigger for.
     *
     * `LOST_CAPABILITY` is the resting value because it is what the paths with
     * nothing better to say already mean: something the snooze depended on has
     * gone. [beginRelease] overwrites it before any attempt runs.
     */
    private var releasingReason: EndReason = EndReason.LOST_CAPABILITY

    /**
     * Whether a record on disk actually describes the snooze being released.
     *
     * Distinct from the ladder's `snoozeIdentified`, and they part company on
     * exactly one path: an arm whose zen write landed but whose `save` failed is
     * unwound with a live in-memory snooze and nothing written down. That
     * release is identified — there is a snooze to end, and the alarm needs to
     * name it — but the user is looking at `Couldn't snooze`, so nothing here
     * may go on to announce one.
     */
    private var releasingRecordOnDisk: Boolean = false

    /**
     * Whether a release is still owed — and therefore whether this service may
     * stop.
     *
     * The counterpart of [recordErased], for the opposite leftover. A record
     * with no release is bounded by its own cap; a *release* with no record has
     * nothing bounding it at all, so the process holding the retry is the only
     * thing left. It stays up until the rule is off or the ladder runs out.
     */
    private val owedRelease: Boolean
        get() = releaseProgress != null

    /**
     * Set while [arm] is between asking to arm and acting on the answer.
     *
     * `IDLE` is delivered from inside `beginArming`, so its callback runs while
     * [arm] still has cleanup to decide — including, on the worst refusal, an
     * in-process release retry. A `stopSelf` from that callback would be
     * queued before the retry is posted and would cancel it at `onDestroy`.
     */
    private var decidingArm: Boolean = false

    /**
     * Holds the in-process release retry, so it can be taken back the same way
     * the erase retry can — and for the same reason: a snooze armed in the
     * meantime owns the rule, and this would turn it off underneath them.
     */
    private val releaseRetryHandler = Handler(Looper.getMainLooper())

    /**
     * Which record [forget] is entitled to erase — the `startedAt` of the
     * snooze whose release it is finishing.
     *
     * Kept because erasing outlives its snooze. A retry can be durable (an
     * alarm) or merely delayed (the handler below), and either can still be
     * outstanding once the user has armed a *new* snooze; the record on disk is
     * then the new one, and erasing it cancels its cap while its zen rule stays
     * on. Tracked from the last transition that wrote a record, or handed in by
     * the alarm, so `forget` can tell the two apart
     * (`ActiveSnooze.retryStillApplies`).
     */
    private var erasing: Instant? = null

    /**
     * Holds the in-process erase retry so it can be taken back.
     *
     * A pending retry outlives what it was queued for: the record can be
     * cleared by something else, the service can stop, and the user can arm a
     * *new* snooze — all within the retry's delay. Firing then would erase the
     * new snooze's record and cancel its cap while its rule stayed on, which is
     * the failure the retry exists to prevent, aimed at the wrong snooze.
     */
    private val eraseRetryHandler = Handler(Looper.getMainLooper())

    /**
     * The running anchor capture, or null when none is.
     *
     * Held so an exit can take it back: a capture's ceiling outlives an `End
     * now` tapped inside it, and its callbacks and location request must not.
     * Closed on every transition that ends the snooze, and by a new arm before
     * it starts its own.
     */
    private var anchorCapture: AutoCloseable? = null

    /**
     * Collects the presence monitor while a snooze runs; this service's
     * main thread, because the controller is confined to it.
     */
    private val presenceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The running collection, or null. See [startPresence]. */
    private var presenceJob: Job? = null

    /**
     * The one monitor this service drives — the flavor seam of SPEC.md §3.4.
     * Lazy so building the service costs nothing extra; constructing a
     * monitor does no work until `start`.
     */
    private val presenceMonitor: PresenceMonitor by lazy { createPresenceMonitor() }

    /**
     * The monitor seam, like [createZenController] and for the same reason:
     * a real monitor registers geofences and takes location requests, which
     * no JVM test can drive, while which updates do what to the snooze is
     * exactly what a test must be able to replay.
     */
    internal open fun createPresenceMonitor(): PresenceMonitor =
        defaultPresenceMonitor(applicationContext)

    /**
     * Starts watching [snooze]'s anchor and feeds every report to the
     * controller (SPEC.md §6.1) — the step that makes "until you leave" a
     * watched promise rather than a recorded intention.
     *
     * Guarded by identity, like the capture's delivery and for the same
     * reason: a monitor started for one snooze must not steer a different
     * one, and liveness alone cannot tell an end-and-re-arm apart.
     */
    private fun startPresence(snooze: ActiveSnooze) {
        // Replaces only the collection — never through [stopPresence], whose
        // `monitor.stop()` means *the snooze is over*: it settles the bridge's
        // held exit and takes down the durable fence, and on the restore path
        // that cleared the very exit the wake was delivering before the new
        // flow could attach and collect it (Codex, PR #73). Canceling the job
        // runs the old flow's own in-process teardown; the monitor's start
        // re-registers the fence under the same id, which replaces in place.
        presenceJob?.cancel()
        presenceJob = null
        val startedAt = snooze.startedAt
        val seed = presenceSeedFor(snooze)
        presenceJob = presenceScope.launch {
            presenceMonitor.start(snooze.anchor, seed).collect { update ->
                val running = controller.active
                if (running == null || running.startedAt != startedAt) return@collect
                controller.onPresenceUpdate(update)
                // An ending event whose zen write was refused leaves the
                // snooze active with the engine's question answered — no
                // further presence event is coming, so this collector is the
                // only caller left to escalate. Same pairing as `End now` and
                // the clock-change ends; the helper no-ops when the end
                // actually landed (Codex, PR #73).
                when (update.event) {
                    PresenceEvent.Departed ->
                        ensureCapAfterRefusedEnd(EndReason.DEPARTURE)
                    is PresenceEvent.CapabilityLost ->
                        ensureCapAfterRefusedEnd(EndReason.LOST_CAPABILITY)
                    else -> Unit
                }
            }
        }
    }

    /**
     * The snooze's arm moment in elapsed realtime — the monitor's evidence
     * seed. Never "now": on a restore, now post-dates the very observation
     * the restart was woken to collect, and seeding with it would drop that
     * exit as stale (Codex, PR #73). The frame arithmetic lives on the
     * record ([ActiveSnooze.armedAtElapsedRealtimeMs]), beside the
     * restatements that make it survive a clock change. A record with no
     * frame falls back to now, which errs toward dropping — the direction
     * the cap bounds.
     */
    private fun presenceSeedFor(snooze: ActiveSnooze): Long =
        snooze.armedAtElapsedRealtimeMs() ?: readClock().uptimeMillis

    /**
     * Stops watching. Idempotent, so every exit path may call it — and
     * deliberately *not* keyed on whether this instance started a collection:
     * a snooze ended from a cold process (an `End now` or cap wake that
     * adopts the record without restoring) never collected, but the durable
     * geofence its arm registered is still out there, and this is the only
     * path that removes it (flagged by Codex on PR #73). Constructing the
     * lazy monitor just to stop it costs an object, which is cheaper than a
     * fence waking the app for a snooze that ended.
     */
    private fun stopPresence() {
        presenceJob?.cancel()
        presenceJob = null
        presenceMonitor.stop()
    }

    /** Restoring is attempted once per service instance, from onStartCommand. */
    private var restored: Boolean = false

    /**
     * The id of the most recent start, so shutdowns outside `onStartCommand`
     * can name one.
     *
     * A bare `stopSelf()` stops regardless of what is queued behind it, and
     * transitions reach this class from places that have no `startId` to hand —
     * the access broadcast, an in-process retry, the controller settling after
     * a release. Stopping on one of those can discard a start already queued
     * behind it, and the one that matters is `ACTION_ARM`: a tile tap that
     * races a cap-driven end would be dropped, which is the whole product's
     * one interaction failing silently. Passing the latest id makes the
     * platform decline the stop when a newer start has arrived.
     */
    private var latestStartId: Int = 0

    /**
     * Why the last arm was refused, so [arm]'s unwind can tell the two kinds of
     * refusal apart.
     *
     * `beginArming` only returns a boolean, and the difference matters here:
     * a refusal that means *nothing is silencing the phone* leaves nothing to
     * clean up, while `PLATFORM_REFUSED` means the platform declined a change
     * to a rule that still exists — and, from the disabled-rule confirmation,
     * can mean `STATE_TRUE` landed and the reset back to `STATE_FALSE` did not.
     */
    private var lastArmFailure: ZenFailure? = null

    /**
     * Set while [arm] is unwinding a snooze that armed the rule but couldn't be
     * recorded, so the release doesn't post "Snooze ended" on top of the
     * "Couldn't start the snooze" the user is already reading.
     */
    private var unwindingFailedArm: Boolean = false

    private val accessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = reconcilePolicyAccess()
    }

    /**
     * Whether [accessReceiver] actually registered, so teardown only
     * unregisters something that exists — and so a failure to register reads as
     * the known degradation it is rather than a surprise at `onDestroy`.
     */
    private var accessReceiverRegistered: Boolean = false

    /**
     * Acts on the current policy-access state, whether a broadcast or a wake-up
     * brought us here.
     *
     * The broadcast says only that access *changed*, for grants and revocations
     * alike, so this reads the current state rather than inferring a direction
     * (SPEC.md §5.2) — which is also what makes it safe to call from anywhere.
     */
    private fun reconcilePolicyAccess() {
        // Contained because of where it runs: on every wake-up, including the
        // cap check. An escaping exception here would unwind `onStartCommand`
        // before the branch that actually ends the snooze, and the alarm that
        // woke us is spent by then — a phone left quiet by a *diagnostic*.
        //
        // Nothing is the right outcome for a failed read: acting on a guess
        // would either end a healthy snooze or create a rule on a hunch, and
        // the record and its cap are both untouched, so the next wake-up asks
        // again with nothing lost.
        val access = runCatching { zen.policyAccess() }.getOrElse {
            Log.e(TAG, "Reading policy access failed; leaving the snooze as it is.", it)
            SnoozeDebugLog.warning("policy access unreadable; leaving the snooze as it is", it)
            return
        }
        when (PolicyAccessChange.resolve(access, controller.active != null)) {
            // The promise of SPEC.md §8.2, kept when it happens rather than when
            // someone next opens the app.
            PolicyAccessAction.EndSnooze -> {
                // A denied permission is often the whole answer to "why didn't
                // it end" — and to "why did it" (SPEC.md §4.6).
                SnoozeDebugLog.event("policy access revoked mid-snooze; ending")
                controller.end(EndReason.LOST_CAPABILITY)
            }
            PolicyAccessAction.EnsureRule -> zen.ensureRule()
            PolicyAccessAction.None -> Unit
        }
    }

    private val zen by lazy { createZenController() }

    /**
     * The zen controller this service drives its rule through.
     *
     * A factory rather than a constructor parameter because Android builds
     * services, so there is no constructor to inject into — and overridable
     * because *every* branch of the release escalation is reached only when the
     * platform **refuses** a zen write. Nothing else can produce that: a device
     * grants the write, and an emulator has no DND to refuse it. Without this
     * seam the whole of SPEC.md §7.1 is unreachable by any test, which is how
     * five real bugs in it reached review rather than a red build.
     *
     * Production overrides nothing; this is the only implementation that ships.
     */
    internal open fun createZenController(): ZenController =
        AndroidZenController.default(applicationContext)

    override fun onCreate() {
        super.onCreate()
        store = ActiveSnoozeStore(applicationContext)
        pendingFailure = PendingFailureStore(applicationContext)
        controller = SnoozeController(zen, readClock, this)

        // Contained, like the notification channels are at their own source,
        // and for the same reason: this is `onCreate`, so a throw here takes
        // the service down before `onStartCommand` runs at all. On a cap
        // wake-up that is the snooze's last exit — the alarm is spent, and its
        // receiver has already read the accepted `startService` as sufficient
        // and skipped its own release.
        //
        // Losing the receiver is a real degradation, not a shrug: a revocation
        // while the app is backgrounded goes unnoticed until something else
        // wakes the service. But every wake-up calls `reconcilePolicyAccess`
        // anyway, so the promise of SPEC.md §8.2 is late rather than lost —
        // and a snooze that ends late still beats a phone left silent because
        // a broadcast registration failed.
        accessReceiverRegistered = runCatching {
            registerReceiver(
                accessReceiver,
                IntentFilter(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED),
                RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            Log.e(TAG, "Registering the policy-access receiver failed; wake-ups still reconcile.", it)
        }.isSuccess

        // Restoring is deliberately NOT here. onCreate cannot see which action
        // started the service, and one of them — the erase retry — exists to
        // clean up a record whose snooze already ended. Restoring first would
        // read that leftover as a live snooze and re-assert the rule, silencing
        // the phone again with no user action behind it. onStartCommand knows
        // the action, so the decision belongs there.
    }

    /**
     * Picks a snooze back up after process death (SPEC.md §8.1, §8.3): the rule
     * is re-asserted rather than assumed to have survived, and a record whose
     * cap passed while we were dead ends immediately.
     */
    private fun restoreIfNeeded() {
        if (restored || controller.active != null) return
        restored = true
        store.load()?.let { record ->
            erasing = record.startedAt
            // The alarm first, and unconditionally. Whatever killed the process
            // may also have consumed the cap alarm — the alarm firing is itself
            // one of the things that starts this service — so a restore that
            // trusted the old alarm could re-assert the rule with nothing left
            // to end it. Re-arming from the record is idempotent when the alarm
            // is still pending, and it cannot extend the snooze because the
            // delay comes from the record's own stored frame — which every
            // reboot restates before anything reads it, and which a snooze
            // whose restate could not be written does not survive to reach
            // (SPEC.md §7). Without that guarantee at the source, a stale
            // offset plus a backwards clock change would make this re-arm
            // schedule the cap *past* its own deadline instead of at it.
            if (!CapAlarm.arm(applicationContext, record, readClock())) {
                SnoozeDebugLog.warning("restore could not re-arm the cap; ending instead")
                // No cap means no guaranteed exit, so don't restore into one.
                // Released directly rather than through the controller: it has
                // not been handed the record yet, so `end()` here would run with
                // no active snooze and — on a refusal — return without clearing
                // the record or leaving anything to retry. The direct path
                // carries the full escalation instead (§8.3).
                notifications.showCouldNotResume()
                releaseDirectly(applicationContext, EndReason.LOST_CAPABILITY)
                return@let
            }
            controller.restore(record, supported = presenceMonitor.supportedModes(record.anchor))
            // A restore that survived is a snooze to watch again — the
            // presence analog of re-asserting the rule (SPEC.md §8.1): the
            // record surviving does not mean the geofence did.
            controller.active?.let(::startPresence)
        }
    }

    /**
     * Takes over the persisted record for a wake-up that is going to end it.
     *
     * Restoring would re-assert the zen rule on the way past — right after
     * process death, wrong here: an `End now` tap that recreates this service
     * would turn DND on for the moment before turning it off, and a release
     * refused after that leaves the phone quiet behind an explicit exit. The
     * cap alarm isn't re-armed either, since the release is about to cancel it;
     * the caller re-arms if the release turns out to have been refused.
     */
    private fun adoptIfNeeded() {
        if (restored || controller.active != null) return
        restored = true
        store.load()?.let {
            erasing = it.startedAt
            controller.adopt(it)
        }
    }

    /**
     * Makes sure a snooze that survived a refused release still has its cap.
     *
     * Ending is what normally cancels the cap alarm, so a refusal has to leave
     * one behind — otherwise a release the platform declined has quietly spent
     * the app's last exit. Re-arming at the record's own expiry is idempotent
     * while the original is still pending and cannot extend the snooze.
     *
     * When even that is refused, the ladder takes over rather than this stopping
     * at a message. The cap is the *scheduled* exit; with neither it nor the
     * release in place, the only things left are a tile and a notification the
     * user has to notice — and this service is an ordinary started one, so it
     * can be gone moments later.
     *
     * A re-armed cap only *finishes* the job where it is about to fire. On an
     * expired record it is: the wake-up is already due, so it re-runs the
     * release within moments and is a real retry. On a live snooze it is not —
     * an `End now` refused an hour into an eight-hour snooze would leave the
     * phone quiet for the remaining seven, with the app having stopped trying
     * and said nothing, which is principles 1 and 2 together. So the cap is
     * restored either way and the ladder continues unless the cap is the thing
     * that ends this.
     */
    private fun ensureCapAfterRefusedEnd(reason: EndReason) {
        val running = controller.active ?: return
        // Restoring the bound this snooze already had, not a rung: idempotent
        // while the original is still pending, and it cannot extend the snooze.
        val capArmed = CapAlarm.arm(applicationContext, running, readClock())
        if (capArmed && running.isExpired(readClock())) return

        if (capArmed) {
            Log.e(TAG, "The release was refused and its cap is not due yet; escalating rather than waiting.")
        } else {
            Log.e(TAG, "The release was refused and its cap could not be re-armed; escalating.")
        }
        beginRelease(running.startedAt, reason)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Recorded before anything can transition, since a transition inside
        // this call is exactly when a shutdown gets decided without an id.
        latestStartId = startId

        // Pick the record back up first — but how depends on what this wake-up
        // is for. The arm is starting a new snooze and the erase retry is
        // disposing of an old one's leftovers, so neither loads anything; the
        // three release actions adopt the record without re-asserting the rule;
        // everything else is a genuine restore.
        //
        // `ACTION_RELEASE_STUCK` belongs with the ends, not the restores, and
        // that is not bookkeeping. Restoring asserts `STATE_TRUE` on the way
        // past — right for a process that died mid-snooze, wrong for a tap
        // whose entire purpose is to turn Do Not Disturb *off*. On a cold
        // process with a live record behind a stale card, restoring first would
        // silence the phone and then try to un-silence it, and a refusal in
        // between leaves the user's own exit having caused the silence.
        when (intent?.action) {
            ACTION_ARM, ACTION_ERASE_RETRY -> Unit
            ACTION_END, ACTION_CAP_LOST, ACTION_RELEASE_STUCK -> adoptIfNeeded()
            else -> restoreIfNeeded()
        }

        // Then reconcile with the platform, on every wake-up except the arm.
        // After the restore, not before: this reads whether a snooze is running,
        // and before the record is picked back up the answer is always "no".
        //
        // The access receiver is registered dynamically because this broadcast
        // is not on the manifest-receiver exemption list, so it only exists
        // while this service does — and an ordinary started service can be
        // stopped when the app goes to the background. A revocation during that
        // gap goes unseen, leaving the record, tile and notification claiming a
        // snooze the platform has already dropped. Every later wake-up is a
        // chance to notice, and they are all off the arm path.
        if (intent?.action != ACTION_ARM) reconcilePolicyAccess()

        // An obligation that outlived its process gets discharged by whatever
        // starts us next, whatever that start was for. The flag is the only
        // thing that survives here — no record, no alarm, and the in-process
        // retry died with the service — so without this it would sit on disk
        // until something happened to route through one of the two branches
        // that read it, and the rule could stay on meanwhile.
        //
        // Off the arm path deliberately. `ACTION_ARM` is excluded because a
        // preferences read and a possible zen write between the tap and
        // `STATE_TRUE` is exactly what §4.1 forbids — and an arm that succeeds
        // clears the flag anyway, since the rule becomes legitimately ours. An
        // arm that *fails* without taking ownership of the rule runs the same
        // discharge from its failure branch instead (see [arm]).
        if (intent?.action != ACTION_ARM) dischargeStuckRuleIfOrphaned()

        when (intent?.action) {
            // A sticky recreation: the system killed us and brought us back with
            // no intent to say why. If a record survived, `restoreIfNeeded` above
            // has already picked the snooze back up and there is nothing more to
            // do — but with *no* record this is how a recordless release retry
            // survives its process being killed, and it is the only way it can.
            // The flag holding that retry is in memory and died with the
            // process; the rule it was trying to turn off did not.
            //
            // Safe on a stray recreation for the same reason the cap path's
            // version is: driving Snoozemo's own already-off rule off again
            // costs one binder call and changes nothing.
            null -> if (controller.active == null) releaseRecordlessRule()
            // The user tapped `Unsnooze` on the stuck-rule notification. They
            // are asking for Do Not Disturb off, so that is what happens.
            //
            // The notification is taken down first, and unconditionally: it
            // exists to offer this exit, the user has now taken it, and leaving
            // it in the shade would invite a second tap at a rule that is
            // already off. `releaseRecordlessRule` escalates by itself if the
            // platform refuses again, and that escalation re-posts this same
            // notification when it gives up.
            //
            // Which release depends on what the restore above found, and this
            // is not the formality it looks like. The card is normally
            // canceled by whatever made it false — a confirmed release, a
            // later successful arm — but a cancel that throws, or a process
            // death between saving a new record and taking the card down,
            // leaves it in the shade over a *live* snooze. Driving the rule
            // off recordlessly there would un-silence the phone while the
            // record, the cap alarm, the tile and the ongoing notification all
            // went on claiming a snooze — and the next restore would re-assert
            // the rule, silencing the phone again after the user had
            // explicitly asked for it off. So a snooze that exists is ended
            // through the controller, exactly as `End now` would.
            ACTION_RELEASE_STUCK -> {
                notifications.cancelStuckRule()
                if (controller.active != null) {
                    // The reason the release was *originally* for, not the
                    // action's own default. The no-service ladder hands work
                    // here when its alarm was refused, and that work can have
                    // started as a cap expiry or a lost capability — ending it
                    // as `MANUAL` would tell the user they did it themselves
                    // and tell the platform's Modes UI `USER_ACTION`, which is
                    // exactly the distinction SPEC.md §5.4 keeps the trigger
                    // for. Absent, it really is the user's tap.
                    val reason = endReasonFrom(intent)
                    controller.end(reason)
                    ensureCapAfterRefusedEnd(reason)
                } else {
                    releaseRecordlessRule()
                }
            }
            ACTION_ARM -> arm()
            ACTION_END -> {
                controller.end(EndReason.MANUAL)
                ensureCapAfterRefusedEnd(EndReason.MANUAL)
            }
            ACTION_CHECK_CAP -> {
                controller.onCapCheck()
                // The alarm that woke us is spent, so anything still unfinished
                // needs a new one or it will never be revisited.
                rescheduleIfUnfinished()
            }
            ACTION_EXTEND -> extend()
            ACTION_CLOCK_CHANGED -> reconcileClock()
            // onCreate already restored from the record; starting the service is
            // the whole of what a reboot needs.
            ACTION_RESTORE -> Unit
            // Something that suppressed notifications has changed — today, a
            // late POST_NOTIFICATIONS grant on a tile-first install. Whatever
            // the user should be able to see right now gets posted again, and
            // that is not only a running snooze: a tile-first tap with no DND
            // access *fails*, and its explanation was dropped by the same
            // suppression. Reconstructed from the current state rather than
            // remembered, since the service that saw the failure has already
            // stopped by the time the grant arrives.
            ACTION_REFRESH -> {
                val running = controller.active
                // Peeked, not taken. This whole mechanism exists because the
                // message was dropped once already, and the replay can fail the
                // same way — a refused `notify`, channels that wouldn't create.
                // Taking it here would spend the last copy of an explanation
                // the user has still never seen, and a failed arm won't come
                // round again to re-record it. Cleared once it has landed.
                val owed = if (running == null) pendingFailure.peek() else null
                // Posted *alongside* whatever else is owed rather than instead
                // of it, now that the stuck-rule card has its own id: both can
                // genuinely be outstanding at once — a refused arm that left a
                // rule behind owes "Couldn't snooze" *and* a way to undo it —
                // and on a shared id the explanation would have displaced the
                // only `Unsnooze` the user had.
                //
                // This is what repairs the tile-first case. The give-up
                // notification is posted while `POST_NOTIFICATIONS` may still
                // be denied, and the platform drops it — leaving a
                // possibly-silenced phone with no affordance anywhere. A later
                // grant sends `ACTION_REFRESH`, and without this the replay
                // covered only `ArmFailure` and the exit was gone for good.
                //
                // A post, not a re-attempt, because the re-attempt has already
                // happened: every non-arm start drives the rule off first, and
                // clears this flag and cancels the card if that succeeded. So
                // reaching here with the flag still set means the rule would
                // not come off just now, which is exactly when the user needs
                // the card.
                if (running == null && pendingFailure.ruleMayBeStuck()) {
                    notifications.showStuckRule()
                }
                when {
                    running != null -> notifications.showOngoing(running)
                    // The actual failure the last arm hit, not a guess at it.
                    // Inferring only the access-denied case left every other
                    // reason — no cap, no record, rule disabled, platform
                    // refusal — permanently unexplained.
                    owed is ArmFailure.Zen ->
                        if (notifications.showFailure(owed.failure, whileArming = true)) {
                            pendingFailure.clear()
                        }
                    owed is ArmFailure.BelowZen ->
                        if (notifications.showCouldNotArm()) pendingFailure.clear()
                    // Contained like the other reads: this one only decides
                    // whether to explain a past failure, and a throw would
                    // take the service down over it.
                    runCatching { zen.policyAccess() }.getOrDefault(PolicyAccess.GRANTED) ==
                        PolicyAccess.DENIED ->
                        notifications.showFailure(ZenFailure.NO_POLICY_ACCESS, whileArming = true)
                    else -> Unit
                }
            }
            // The record of an already-ended snooze wouldn't erase. Nothing to
            // restore and nothing to release — just finish the cleanup, and
            // only for the record this retry was actually queued for: the alarm
            // behind it is durable and this process is not, so it can arrive
            // after the user has armed a new snooze.
            ACTION_ERASE_RETRY -> {
                erasing = intent?.getLongExtra(EXTRA_RECORD_STARTED_AT, 0L)
                    ?.takeIf { it != 0L }
                    ?.let(Instant::ofEpochMilli)
                forget()
            }
            // A reboot that couldn't reschedule the cap. The record has been
            // adopted rather than restored, so the rule is driven off from
            // whatever state it is actually in rather than being switched on
            // first — the reboot may well have left it on, and `end` does not
            // assume either way.
            ACTION_CAP_LOST -> {
                val adopted = controller.active
                // Which snooze this wake-up is about. The retry alarm behind it
                // is durable and this process is not, so it can arrive after
                // the snooze it was armed for has ended and the user has armed
                // another — and unlike the cap, this action ends a snooze
                // outright rather than asking whether its deadline has passed.
                val queuedFor = intent?.getLongExtra(EXTRA_RECORD_STARTED_AT, 0L)
                    ?.takeIf { it != 0L }
                    ?.let(Instant::ofEpochMilli)
                when {
                    // No record — but that does *not* mean the release
                    // succeeded, and reading it that way was a way to leave the
                    // phone quiet. A release is one thing that erases the
                    // record; an arm that never managed to *write* one is
                    // another. That second case is exactly what this retry can
                    // be carrying: the record write failed after `STATE_TRUE`,
                    // the immediate release was refused, this alarm was armed
                    // from the controller's snooze, and the process then died
                    // with nothing on disk. Treating it as finished abandons a
                    // possibly-active rule until the original cap.
                    //
                    // So drive Snoozemo's own rule off instead of assuming.
                    // Costless on the reading where the release really did
                    // succeed — turning off a rule that is already off is what
                    // idempotent means — and it escalates by itself if the
                    // platform refuses again. No user-visible message either
                    // way: on the succeeded reading there is nothing to report,
                    // and `showCouldNotResume` would describe a reboot that
                    // never happened.
                    adopted == null -> {
                        Log.w(TAG, "A release retry found no record; driving the rule off rather than assuming.")
                        releaseRecordlessRule()
                    }
                    !ActiveSnooze.retryStillApplies(adopted, queuedFor) ->
                        Log.w(TAG, "Dropping a stale release retry; a newer snooze owns the record now.")
                    else -> {
                        // The reason the refused release was started for, not
                        // this action's own name for itself: an alarm armed by
                        // a cap expiry is still finishing that cap.
                        val capLostReason = endReasonFrom(intent, EndReason.LOST_CAPABILITY)
                        // `Couldn't resume snoozing` describes one situation —
                        // a reboot whose cap could not be rescheduled — and
                        // this action is no longer reached only from that
                        // situation. A duration cap or a manual exit whose
                        // release was refused arms the same alarm now, and on
                        // those the message is simply false: nothing failed to
                        // resume. It is also a *separate* notification id from
                        // the ended one, so it would sit in the shade next to
                        // `Snooze ended · time limit reached` contradicting it
                        // rather than being replaced. The branch above already
                        // refuses to post it for exactly this reason.
                        if (capLostReason == EndReason.LOST_CAPABILITY) {
                            notifications.showCouldNotResume()
                        }
                        controller.end(capLostReason)
                        // No cap could be scheduled — that is why this action
                        // exists — so a refused release here has nothing at all
                        // behind it. A short retry alarm is the one thing left.
                        //
                        // A *release* retry, not a cap check. A cap wake-up asks
                        // "is the record expired yet?", and this record's
                        // original cap is still hours away — that is the whole
                        // situation — so the check would find nothing to do,
                        // decline to reschedule, and spend the one retry this
                        // branch has. The retry has to carry the same
                        // instruction that got us here: end it, there is no cap.
                        if (controller.active != null &&
                            !CapAlarm.armReleaseRetry(
                                applicationContext,
                                System.currentTimeMillis() + RETRY_MS,
                                adopted.startedAt.toEpochMilli(),
                                capLostReason,
                            )
                        ) {
                            // No alarm and no cap, so this process is the only
                            // successor left — the same position the recordless
                            // path reaches, with a record to identify by.
                            // Saying "trying again" without arranging one would
                            // be a promise nothing keeps.
                            Log.e(TAG, "The post-reboot release was refused and no alarm took; retrying in process.")
                            beginRelease(adopted.startedAt, capLostReason)
                        }
                    }
                }
            }
        }
        // Some actions finish without any controller transition — an erase retry
        // that worked, a refresh with nothing running, an arm refused before the
        // state machine was ever entered — so the shutdown check in
        // onStateChanged never runs for them and an idle service (and its
        // registered receiver) would stay up asking to be recreated after a
        // kill. `stopSelf(startId)`, not the bare form: another start may have
        // arrived while this one was being handled, and only the id-carrying
        // form declines to stop in that case.
        //
        // `owedRelease` holds it up for the same reason `recordErased` does:
        // this process is the only thing left that will turn a possibly-stuck
        // rule off, and stopping would take that retry with it.
        if (controller.active == null && recordErased && !owedRelease) stopSelf(startId)

        // START_STICKY: a snooze is a promise to turn the phone back on, and the
        // system recreating us is how that promise survives being killed.
        //
        // Except when the only thing outstanding is erasing a record whose
        // snooze already ended. A sticky recreation arrives with a **null**
        // intent — no action to key on — so it takes the restore branch, reads
        // the leftover record as a live snooze, and turns DND back on with
        // nothing the user did behind it. The erase-retry alarm is the durable
        // mechanism for that work and it carries its own action, so declining
        // the recreation loses nothing and closes the one path that can't
        // distinguish a released record from a running one.
        return if (controller.active == null && !recordErased) START_NOT_STICKY else START_STICKY
    }

    private fun arm() {
        // Never arm over a snooze that is already running. Doing so replaces its
        // record and its cap with a fresh eight hours, so the deadline the user
        // was promised is simply gone and the phone stays quiet past it — the
        // arm path producing principle 1's failure. A duplicate arm re-states
        // what is running instead.
        //
        // The record, not just the controller: this is the one action that skips
        // the restore, because a cold process armed straight from the tile has
        // nothing in memory yet — so the in-memory answer alone would miss
        // exactly the case that matters. The file is warmed at process start and
        // this path writes to it a few lines below, so reading it here adds no
        // wait that arming didn't already have.
        if (controller.active != null || store.load() != null) {
            restoreIfNeeded()
            controller.active?.let(notifications::showOngoing)
            return
        }

        // The cap alarm is armed first, before anything that can throw: a snooze
        // whose cap was never scheduled has lost the exit that holds when every
        // sensor has failed (SPEC.md §7). And if it can't be scheduled, nothing
        // is armed at all — the alarm *is* the cap, so arming without it would
        // create a snooze with no time bound, which after process death is a
        // phone silent indefinitely.
        //
        // Settled once, as an absolute instant, and handed to both the alarm and
        // the controller: two derivations from two clock readings put them
        // milliseconds apart, and an alarm that fires just before its own record
        // counts as expired is spent for nothing.
        val reading = readClock()
        val capExpiresAt = ActiveSnooze.capExpiryFor(reading)
        // As a delay, from the same reading the deadline came from: there is no
        // record to measure against yet, and the delay *is* the cap's duration.
        if (!CapAlarm.armCheckIn(applicationContext, capExpiresAt.toEpochMilli() - reading.wallMillis)) {
            notifications.showCouldNotArm()
            // Kept for the same reason a zen failure is: on a tile-first
            // install POST_NOTIFICATIONS is still denied at the first tap, so
            // the message above goes nowhere and a later grant is the only
            // chance to tell the user why their tap didn't snooze the phone.
            pendingFailure.remember(ArmFailure.BelowZen)
            // The discharge every non-arm start runs, which this start skipped
            // on the way in to keep the tap-to-`STATE_TRUE` stretch clean. That
            // was justified by "a successful arm clears the flag anyway" — true
            // of a successful arm, not of this one, which failed before
            // reaching zen. Without it the service stops with nothing scheduled
            // while an older rule may still be silencing the phone (flagged by
            // Codex on PR #8). The arm is over, so the reads are affordable now.
            dischargeStuckRuleIfOrphaned()
            return
        }

        lastArmFailure = null
        // Held across everything below, because `IDLE` and `ARMING` are both
        // delivered from inside `beginArming` — their callbacks run while this
        // is still mid-decision, and the shutdown check in `onStateChanged`
        // must not tear the service down before that decision is made.
        // `onStartCommand` runs the same check on its way out.
        decidingArm = true
        try {
            armWithCap(capExpiresAt, reading)
        } finally {
            decidingArm = false
        }
    }

    /**
     * The arm proper, once a cap alarm is scheduled for [capExpiresAt].
     *
     * [at] is the one reading the deadline and the alarm were both derived
     * from, threaded through rather than re-read so all three name the same
     * frame (see `SnoozeController.beginArming`).
     *
     * Split from [arm] only so the flag above has an unmissable scope: every
     * exit from here — refusal, unrecorded snooze, success — passes through the
     * `finally` rather than each `return` remembering to clear it.
     */
    private fun armWithCap(capExpiresAt: Instant, at: ClockReading) {
        if (!controller.beginArming(capExpiresAt, at)) {
            // Arming was refused. The IDLE transition has already dealt with the
            // record and the notification; the cap alarm is ours to take back.
            //
            // The cap only. If that clear failed, IDLE left an erase retry armed
            // and a record on disk, and canceling the retry too would leave the
            // record with nothing scheduled to remove it — a later cold start
            // would then restore it as a live snooze.
            //
            // And a refusal that leaves the rule's state *unknown* gets one
            // put back. `PLATFORM_REFUSED` is that refusal: the platform
            // declined a change to a rule that still exists, and one way to
            // reach it is a `STATE_TRUE` that landed on a switched-off rule and
            // would not come back off. Nothing on disk describes that — IDLE
            // erased the record — so a wake-up is the only thing that will ever
            // revisit it. It finds no record and takes the recordless safety
            // release, which drives Snoozemo's own rule off once. On the
            // harmless reading it costs a single wake-up: turning off a rule
            // that is already off is what idempotent means.
            //
            // Re-armed rather than merely left alone, because IDLE has already
            // been through `forget()` by now and a successful erase there
            // cancels every alarm this snooze had — the cap included. "Keeping"
            // it was keeping something that no longer existed.
            //
            // Soon, not at the original cap: if the rule really is stuck on,
            // the phone is silent *now*, and waiting out a cap the user never
            // got a snooze from is principle 1's failure with a schedule.
            if (lastArmFailure?.nothingLeftToRelease != false) {
                CapAlarm.cancel(applicationContext)
                // Same discharge as the below-zen failure above, and here it
                // can settle the obligation for good: the refusal means
                // nothing is silencing the phone, so the recordless release
                // this runs comes back `nothingLeftToRelease` and retires the
                // flag and its card rather than chasing a rule that is off.
                dischargeStuckRuleIfOrphaned()
                return
            }
            Log.w(TAG, "The arm was refused with the rule's state unknown; scheduling a release.")
            // Handed to the ladder rather than spelled out here, which is the
            // point of SPEC.md §7.1: this site used to write the first two rungs
            // by hand and then fall into the escalation for the rest, and the
            // ordering it had to remember — the durable obligation *before* the
            // alarm, because a later `forget()` calls `cancelAll` and takes the
            // alarm with it while the flag survives — is now stated once.
            //
            // Recordless deliberately: IDLE erased the record on its way
            // through, so there is nothing on disk to identify a retry by, and
            // the plain wake-up is the one that can drive off a rule nothing
            // describes.
            beginRelease(startedAt = null, reason = EndReason.LOST_CAPABILITY)
            return
        }

        // Now that the rule is on and the hot path is over, force the record
        // through to disk and find out whether it landed. It is the only thing
        // that can turn the rule off after process death, so a snooze that
        // couldn't be written is one we can't promise to end — release it and
        // say so rather than leaving DND on with nothing to release it.
        val snooze = controller.active
        if (snooze == null || !store.save(snooze)) {
            notifications.showCouldNotArm()
            pendingFailure.remember(ArmFailure.BelowZen)
            // The user is being told it didn't start; following that with
            // "Snooze ended" would contradict it. The snooze existed for a few
            // milliseconds and they never saw a confirmed one.
            unwindingFailedArm = true
            controller.end(EndReason.LOST_CAPABILITY)
            unwindingFailedArm = false
            // That end can be refused too, and returning here would leave the
            // rule on with only the cap behind it — the *default* cap, hours
            // away, because this snooze never got as far as being recorded.
            // The user would have `Couldn't snooze` on screen and a phone that
            // stays quiet until the backstop fires, which is principle 1's
            // failure produced by the unwind of a failed arm.
            //
            // The alarm first, this process second, as everywhere else: an
            // alarm outlives the process and the in-process retry does not.
            // Identified by the controller's snooze rather than the record's,
            // since the record is precisely what could not be written.
            controller.active?.let { stillOn ->
                Log.e(TAG, "An unrecorded arm could not be released; retrying rather than waiting for the cap.")
                if (!CapAlarm.armReleaseRetry(
                        applicationContext,
                        System.currentTimeMillis() + RETRY_MS,
                        stillOn.startedAt.toEpochMilli(),
                        EndReason.LOST_CAPABILITY,
                    )
                ) {
                    beginRelease(stillOn.startedAt, EndReason.LOST_CAPABILITY, recordOnDisk = false)
                }
            }
            return
        }

        // Nothing left to explain: this arm worked, so an older failure must
        // not surface later as if it described this snooze.
        //
        // Both copies of it. Clearing the store only stops it being *replayed*
        // later; a failure notification posted by an earlier arm is already in
        // the shade, under its own id, and `setAutoCancel` dismisses it on a
        // tap rather than by itself. Left alone it sits beside the `Snoozing`
        // card this arm just posted — two notifications disagreeing about
        // whether the phone is snoozed, which is principle 2's failure caused
        // by an explanation that outlived what it explained.
        pendingFailure.clear()
        notifications.cancelFailure()
        // And nothing is stuck any more. If a previous arm left the rule
        // possibly-on with no record, this arm has just turned it on
        // deliberately and given it a record and a cap — so the obligation is
        // discharged by the thing that replaced it, not left to chase a rule
        // that is now legitimately ours.
        pendingFailure.clearRuleStuck()
        notifications.cancelStuckRule()
        // A leftover `Couldn't end the snooze — trying again` is stale for the
        // same reason: whatever release it was promising, this arm now owns
        // the rule, and the retry machinery drops stale releases on its own.
        notifications.cancelEndFailure()

        // And nothing left to erase. A previous release that marked its record
        // but couldn't clear it leaves `recordErased` false — while the marker
        // is exactly what let this arm proceed, and the save above has just
        // overwritten that record with this one. Left standing, the flag says
        // "cleanup pending" about a record that no longer exists, and the next
        // refused release reads it and calls `forget()` on the *live* snooze:
        // record deleted, every alarm canceled, rule possibly still on. The
        // identity guard inside `forget` cannot catch it either, because
        // `ARMING` has already pointed `erasing` at this snooze.
        recordErased = true
        eraseRetries = 0
        eraseRetryHandler.removeCallbacksAndMessages(null)
        // The erase-retry *alarm* is left where it is on purpose: it carries
        // the old record's `startedAt`, so if it fires it fails the identity
        // check and drops itself, and canceling alarms is the one thing that
        // has repeatedly turned out to remove an exit something still needed.

        // Say `Snoozing` now rather than when the anchor lands: capture takes
        // up to 10 s (SPEC.md §4.1), and ARMED — which posts this — no longer
        // follows within milliseconds. Off the arm path: `STATE_TRUE` landed
        // above, so these binder calls delay nothing the user is waiting on.
        notifications.showOngoing(snooze)
        SnoozeTileBridge.refresh(applicationContext)

        beginAnchorCaptureFor(snooze)
    }

    /**
     * Starts anchor capture for [snooze], replacing any capture still running —
     * there is at most one snooze, so at most one capture (SPEC.md §7).
     *
     * The delivery is guarded by identity, not just liveness: the runner's
     * ceiling means a capture can outlive its snooze, and an `End now` plus a
     * fresh tap inside ten seconds would otherwise land the *old* place on the
     * *new* snooze. The controller's own null-check cannot see that case.
     */
    private fun beginAnchorCaptureFor(snooze: ActiveSnooze) {
        anchorCapture?.close()
        val startedAt = snooze.startedAt
        anchorCapture = beginAnchorCapture(snooze.anchor.capturedAt) { anchor ->
            anchorCapture = null
            val running = controller.active
            if (running == null || running.startedAt != startedAt) {
                SnoozeDebugLog.event("anchor arrived for an ended snooze; dropped")
                return@beginAnchorCapture
            }
            // The mode is the machinery's claim, not the anchor's:
            // `supportedModes` says which modes anything actually watches for
            // these fields (SPEC.md §6.1, §8.1), and the controller lowers
            // every mode it ever computes — the arm's and every later
            // report's — through the same set.
            controller.onAnchorCaptured(anchor, presenceMonitor.supportedModes(anchor))
            // And now watch it. After the controller, so the first update
            // finds the armed snooze; identity-guarded either way.
            controller.active?.let(::startPresence)
        }
    }

    /**
     * The capture seam, like [createZenController] and for the same reason:
     * the platform half needs real callbacks a JVM test cannot drive, so tests
     * substitute the delivery and the decision logic stays covered — the
     * assembly rules in `AnchorCapture`'s own tests, the wiring here through
     * this seam.
     */
    internal open fun beginAnchorCapture(
        capturedAt: Instant,
        onCaptured: (Anchor) -> Unit,
    ): AutoCloseable = AnchorCaptureRunner(applicationContext).begin(capturedAt, onCaptured)

    /**
     * The user set the clock, and this service is the one holding the snooze
     * (SPEC.md §7).
     *
     * `TimeChangedReceiver` could write the repaired record on its own — it does
     * exactly that when this service will not start — but a record written while
     * a controller is alive is only half the state. The controller's copy still
     * carries the pre-change deadline, and `+30 min` derives its update from
     * *that*: one tap and the repair is on the floor, with the pre-change
     * deadline back on disk and a reboot away from holding Do Not Disturb open
     * by the size of the shift. So the change is performed where both copies
     * are, and the record is written from the same snooze the controller then
     * adopts.
     *
     * The decision itself is [ClockChange]'s, shared with the no-service path so
     * the two cannot disagree about what a clock change means.
     */
    private fun reconcileClock() {
        val snooze = controller.active ?: return
        when (val action = ClockChange.resolve(snooze, readClock())) {
            // Nothing moved that this record could not already read correctly.
            ClockChangeAction.None -> Unit

            // Due as of this reading — including the forward jump that landed
            // past the deadline, which is the case nothing else would notice:
            // the alarm counts in elapsed realtime and did not move with the
            // clock. `ensureCapAfterRefusedEnd` covers a platform that refuses,
            // exactly as `End now` does; the cap alarm is still armed here, so
            // unlike the cap-check path there is no spent alarm to replace.
            ClockChangeAction.EndAtCap -> {
                SnoozeDebugLog.event("clock changed; the cap is due as of the new reading")
                controller.onCapCheck()
                ensureCapAfterRefusedEnd(EndReason.DURATION_CAP)
            }

            // No offset to read the deadline against but the clock that just
            // moved, so how long is really left is unknowable. Fail open
            // (SPEC.md §7, D7) — and `LOST_CAPABILITY` rather than the cap,
            // because this snooze is not ending on its time, it is ending
            // because its bound stopped being legible.
            ClockChangeAction.EndUnframed -> {
                Log.w(TAG, "The clock moved under a record with no offset; ending rather than guessing.")
                SnoozeDebugLog.warning("clock changed under an unframed record; ending rather than guessing")
                controller.end(EndReason.LOST_CAPABILITY)
                ensureCapAfterRefusedEnd(EndReason.LOST_CAPABILITY)
            }

            is ClockChangeAction.Restate -> {
                SnoozeDebugLog.event("clock changed; restating the record's frames")
                restate(action.snooze)
            }
        }
    }

    /**
     * Writes the restated record, then hands the same snooze to the controller.
     *
     * That order is the one [extend] uses and for the same reason: the durable
     * half goes first, and nothing in memory claims a frame that did not reach
     * disk. A refused write ends the snooze rather than leaving a record whose
     * deadline is wrong in the dangerous direction — the armed alarm is still
     * correct, so it only bites after a reboot, but a bound that depends on the
     * phone never restarting is not a bound (D7).
     */
    private fun restate(restated: ActiveSnooze) {
        if (!store.save(restated)) {
            Log.e(TAG, "The clock change could not be recorded; ending rather than trusting the old deadline.")
            controller.end(EndReason.LOST_CAPABILITY)
            ensureCapAfterRefusedEnd(EndReason.LOST_CAPABILITY)
            return
        }
        // The controller adopts the restated snooze the moment it is durable,
        // *before* anything below can fail. The record and the controller are
        // two copies of the same snooze, and every fallback under this line —
        // the refused-end ladder included — reads the controller's copy; left
        // stale, a refused release would keep the snooze running with `+30
        // min` deriving from pre-change frames and writing them back over the
        // repair (flagged by Codex on PR #63).
        controller.reconciledTo(restated)

        // Re-arm the cap from the restated record — the one place the
        // never-re-arm rule (SPEC.md §7) does not apply, because the record's
        // frame is provably fresh: the restate above just wrote it. This is
        // what closes the forward-change gap where the countdown reads the
        // shortened wall answer while the armed alarm still counts the
        // original elapsed delay, leaving the phone quiet past a countdown
        // that has already hit zero. On a backwards change the recomputed
        // delay equals the one already armed, so the re-arm changes nothing.
        //
        // A refusal gets the same answer as the refused save above, and for
        // the same reason: the record now promises a deadline the scheduled
        // alarm will not honor, and after a forward jump the gap is the whole
        // shift — a countdown at zero over a phone still silent, with only a
        // log saying why (flagged by Codex on PR #63). A cap that cannot be
        // scheduled where it is promised ends the snooze (SPEC.md §7), and
        // the ended notification is the user-visible account of it.
        if (!CapAlarm.arm(applicationContext, restated, readClock())) {
            Log.e(TAG, "Re-arming the cap after the clock change was refused; ending rather than letting the countdown lie.")
            SnoozeDebugLog.warning("clock-change re-arm refused; ending the snooze")
            controller.end(EndReason.LOST_CAPABILITY)
            ensureCapAfterRefusedEnd(EndReason.LOST_CAPABILITY)
            return
        }

        // The countdown, which the clock change has just falsified: the platform
        // ticks the chronometer against the *wall* clock from the instant it was
        // posted in, so after a backwards change it reads the shift too long — a
        // snooze that appears to have hours left over a phone about to come back
        // on. Re-posted rather than emitted as a transition, because nothing
        // about the snooze changed: the same moment is still the cap, said in a
        // frame that survives, and a transition would commit the record a second
        // time to say so. The tile's subtitle is stale in exactly the same way.
        notifications.showOngoing(restated)
        SnoozeTileBridge.refresh(applicationContext)
    }

    /** The notification's `+30 min` (SPEC.md §4.3). */
    private fun extend() {
        val snooze = controller.active ?: return
        val extendedCap = snooze.extendedCap(EXTENSION)
        if (!extendedCap.isAfter(snooze.capExpiresAt)) {
            notifications.showAtMaxDuration()
            return
        }

        // The alarm moves first, and the controller only hears about it if that
        // worked: the alarm is the cap, so a countdown extended past an alarm
        // still set for the old time would be a promise the platform never made.
        val extended = snooze.copy(capExpiresAt = extendedCap)
        if (!CapAlarm.arm(applicationContext, extended, readClock())) {
            notifications.showCouldNotExtend()
            return
        }

        // Then the record, and only then the controller. All three have to agree
        // about when this snooze ends, so nothing claims the extension until the
        // record that survives process death carries it: a countdown and an
        // alarm at the new time over a record at the old one would restore to a
        // snooze that ends earlier than the user was told.
        if (!store.save(extended)) {
            notifications.showCouldNotExtend()

            // The record first, then the alarm. A failed `commit` still leaves
            // the extended cap in the preferences' in-memory map — only the
            // disk write failed — so anything that reads the record in this
            // process, including a service recreation woken by the old alarm,
            // would find the extension we just told the user didn't happen and
            // re-arm the cap for the later deadline. Writing the original back
            // is what actually undoes it.
            val recordRolledBack = store.save(snooze)
            if (recordRolledBack && CapAlarm.arm(applicationContext, snooze, readClock())) {
                return
            }

            // The roll-back failed too, so what the record and the alarm now say
            // is whatever survived — at best disagreeing with each other, at
            // worst both sitting at the extended deadline the user was told did
            // not happen, with no way left to move either. This does not fail
            // early and safe; it leaves the phone quiet past the deadline
            // everything else is promising. So end the snooze instead: a cap we
            // can no longer state is not one to keep (SPEC.md §7).
            Log.e(TAG, "Rolling back a failed extension failed too; ending the snooze.")
            controller.end(EndReason.LOST_CAPABILITY)
            // And if even that release is refused, the snooze is still running
            // with its two deadlines in disagreement — so make sure one of them
            // is actually scheduled. Re-armed at the controller's cap, which is
            // still the *original* one: `extendTo` was never reached, so this
            // both guarantees an alarm exists and drags back one left sitting at
            // the extended deadline.
            ensureCapAfterRefusedEnd(EndReason.LOST_CAPABILITY)
            return
        }
        controller.extendTo(extendedCap)
    }

    override fun onStateChanged(state: SnoozeState, snooze: ActiveSnooze?, reason: EndReason?) {
        // Every transition and its reason (SPEC.md §4.6) — the debug log's
        // whole point is reconstructing a snooze's life after the fact. Cheap
        // by construction: a string build and an in-memory append, with the
        // file write coalesced on its own thread, so even the ARMING delivery
        // on the arm path pays no disk and no IPC. The record's summary rides
        // along where one exists; it is the floor-safe rendering (never the
        // SSID, coordinates, or place name).
        SnoozeDebugLog.event(
            "state → $state" + (reason?.let { " ($it)" } ?: "") +
                (snooze?.let { "; ${it.logSummary()}" } ?: ""),
        )
        when (state) {
            // The one transition on the arm path, between the tap and the rule
            // going on, so the write is handed to a background thread rather
            // than waited on (`AGENTS.md`, "the arm path"). arm() forces it
            // down and checks it as soon as the rule is on.
            SnoozeState.ARMING -> snooze?.let {
                // This snooze owns the record from here on, so an erase queued
                // for an earlier one can be told apart from a clean-up of this.
                erasing = it.startedAt
                store.saveAsync(it)
                // Deliberately *not* the notification. This transition is
                // delivered from inside `beginArming`, before
                // `setAutomaticZenRuleState(STATE_TRUE)`, so a `notify` here is
                // a system-server round trip sitting between the tile tap and
                // the phone going quiet — the one thing the arm path may not
                // have (`AGENTS.md`, the arm path). Nothing needs it this
                // early: `armWithCap` posts it the moment the rule is on —
                // capture can hold `ARMED` off for ten seconds, so it no
                // longer waits for that — and an arm that *fails* never
                // flashes `Snoozing` at all.
                //
                // The record is different and stays: it is what turns the rule
                // back off after process death, so it has to exist before the
                // rule does. `saveAsync` keeps it off this thread.
            }
            SnoozeState.ARMED, SnoozeState.CHECKING -> snooze?.let {
                erasing = it.startedAt
                // A failed update here is a stale record, not a missing one: the
                // cap time in it is unchanged, so the snooze still ends on time
                // and still has something to release it with. Logged, not fatal
                // — unlike the arm-path write, which arm() treats as a refusal.
                if (!store.save(it)) {
                    Log.w(TAG, "Updating the snooze record failed; the stored cap still stands.")
                }
                notifications.showOngoing(it)
            }
            SnoozeState.RELEASED -> {
                // A capture still running is for a snooze that no longer
                // exists; the identity guard would drop its anchor anyway, but
                // its callbacks and location request are resources to release
                // now, not at the ceiling. The monitor likewise: nothing is
                // left to watch for, and a geofence outliving its snooze is a
                // wake-up nobody wants.
                anchorCapture?.close()
                anchorCapture = null
                stopPresence()
                snooze?.let { erasing = it.startedAt }
                forget()
                if (unwindingFailedArm) notifications.cancelOngoing() else notifications.showEnded(reason)
                // The rule is confirmed off, so a stuck-rule card left over from
                // an earlier refusal is now saying something false and offering
                // an exit nobody needs. `ensureCapAfterRefusedEnd` can post that
                // card and then be followed by a release that works — a second
                // `End now`, the cap, a retry alarm — and this is the transition
                // every one of those arrives at. The direct no-service path
                // already does the same; this is its twin.
                //
                // The persisted obligation goes with it, for the same reason:
                // both are claims that the rule *might* be on, and this
                // transition is the one that proves otherwise. Left set, it
                // would send the next start off to drive an already-off rule
                // off again — harmless, but a promise outliving its cause.
                notifications.cancelStuckRule()
                if (pendingFailure.ruleMayBeStuck()) pendingFailure.clearRuleStuck()
                // So does `Couldn't end the snooze — trying again`: the retry
                // it promised is the release that just confirmed, and leaving
                // it beside `Snooze ended` has the two contradicting each
                // other (flagged by Codex on PR #8).
                notifications.cancelEndFailure()
            }
            // Reached when arming was refused, and only then. The record and the
            // ongoing notification went up before the rule was asked for, so
            // both are now claiming "Snoozing" over a phone that will ring —
            // and a later cold start would restore that record as a real snooze.
            SnoozeState.IDLE -> {
                forget()
                notifications.cancelOngoing()
                stopPresence()
            }
        }
        // Every transition except `ARMING`, which is the one that sits between
        // the tile tap and the rule going on. Nothing is lost by skipping it:
        // a refused arm reaches `IDLE`, which refreshes here, and a successful
        // one refreshes from `armWithCap` the moment the rule is on — capture
        // can hold `ARMED` off for ten seconds now, so that refresh no longer
        // waits for it. What is gained is one fewer binder call on the path
        // whose whole purpose is to be immediate.
        if (state != SnoozeState.ARMING) SnoozeTileBridge.refresh(applicationContext)
        // Nothing running, nothing to own. Keyed on the record rather than on
        // the state: a successful release reports RELEASED and settles into
        // IDLE without a second callback, so a state test here would never fire
        // and would leave an empty START_STICKY service and its receiver up.
        //
        // Not while a record we failed to erase is still on disk, though: this
        // service is the only thing that will try again.
        //
        // Nor while `arm` is still deciding. `IDLE` arrives from inside
        // `beginArming`, before the caller has looked at *why* the arm was
        // refused — and one of those reasons leaves a rule that may be
        // silencing the phone with no record and no alarm, whose only remaining
        // successor is an in-process retry this service has not been asked for
        // yet. Stopping here queues a teardown that `onDestroy` then uses to
        // cancel that retry a few lines after it is posted. `onStartCommand`
        // stops the service on its way out instead, once the decision is in.
        if (decidingArm || owedRelease) return
        // Named, not bare: a start queued behind this transition — a tile arm
        // racing a cap-driven end is the case that matters — must not be
        // thrown away by the stop.
        if (controller.active == null && recordErased) stopSelf(latestStartId)
    }

    /**
     * Drives off a possibly-stuck rule that nothing else will revisit.
     *
     * Guarded on there being no record and no snooze, because that is what the
     * stored flag means: *our* rule may be on with nothing behind it. If a
     * snooze exists, it owns the rule and this is not ours to touch.
     */
    private fun dischargeStuckRuleIfOrphaned() {
        if (pendingFailure.ruleMayBeStuck() &&
            controller.active == null &&
            store.load() == null
        ) {
            Log.w(TAG, "A start found a possibly-stuck rule outstanding; driving it off.")
            releaseRecordlessRule()
        }
    }

    /**
     * Drives Snoozemo's own rule off when nothing on disk describes a snooze.
     *
     * Either a stray wake-up after a clean end, or a rule left on with nothing
     * tracking it — an arm whose record never landed and whose release was then
     * refused leaves exactly that, and from here the two are indistinguishable.
     * The second is the one that matters, so this drives our own rule off once.
     * On the harmless reading it changes nothing: turning off a rule that is
     * already off is what idempotent means (SPEC.md §5.6 — never anyone else's).
     */
    private fun releaseRecordlessRule() {
        val outcome = zen.setSnoozed(false, ZenTrigger.CONTEXT, ActiveSnooze.DEFAULT_PLACE_NAME)
        if (outcome !is ZenOutcome.NotApplied || outcome.reason.nothingLeftToRelease) {
            // Confirmed off — including the `nothingLeftToRelease` readings,
            // which mean there was nothing silencing the phone in the first
            // place. Either way nothing is owed, so stop carrying it and take
            // down a stuck-rule notification that is now describing a rule that
            // is off — and the `trying again` card, whose retry this was.
            if (pendingFailure.ruleMayBeStuck()) {
                pendingFailure.clearRuleStuck()
                notifications.cancelStuckRule()
            }
            notifications.cancelEndFailure()
            return
        }

        // Refused rather than unnecessary, so the rule may genuinely be on with
        // no record behind it — and whatever alarm brought us here is spent.
        //
        // The ladder takes it from here, which also corrects the order this site
        // had: it armed the alarm first and only stored the obligation once the
        // in-process escalation was reached, so a refused alarm followed by a
        // service teardown left nothing written down at all.
        Log.e(TAG, "Recordless safety release was refused; escalating.")
        beginRelease(startedAt = null, reason = EndReason.LOST_CAPABILITY)
    }

    /**
     * Puts a fresh alarm behind anything the cap check left undone.
     *
     * The cap alarm is one-shot, so by the time this runs it is spent. Two
     * things can survive it, and both would otherwise be revisited by nothing:
     * a snooze whose release the platform refused (still active, still past its
     * cap, still silencing the phone), and a record that wouldn't erase. One
     * short retry alarm covers both.
     */
    private fun rescheduleIfUnfinished() {
        if (!recordErased) {
            forget()
            return
        }

        if (controller.active == null) {
            releaseRecordlessRule()
            return
        }
        val running = controller.active ?: return
        if (!running.isExpired(readClock())) return

        // The cap that woke us is spent, so this snooze has nothing durable
        // behind it until something is put there. Handed to the ladder whole
        // rather than arming an alarm here and escalating only if that failed —
        // same rungs, stated once.
        //
        // The alarm it asks for is the identified release retry rather than the
        // plain cap check this site used to arm. Both work here, since the
        // record really is expired and a cap check would act on it; the
        // identified one is preferred because it carries the instruction rather
        // than re-deriving it, and it cannot act on a snooze the user arms in
        // the meantime.
        Log.e(TAG, "The cap fired but the release was refused; escalating.")
        beginRelease(running.startedAt, EndReason.DURATION_CAP)
    }

    /**
     * Starts a **fresh** escalation for a rule that would not come off, retiring
     * whatever the last one left behind.
     *
     * [startedAt] names the snooze this is about, or is null when nothing on
     * disk describes the rule at all. That is the only difference between what
     * used to be two near-identical escalations: it decides which alarm carries
     * the obligation onward, and what an in-process retry actually does.
     *
     * The ladder itself is [ReleaseEscalation], in `:core` and under test. This
     * function and [advanceRelease] are the performing half — ask, do, record
     * what actually happened, ask again.
     */
    private fun beginRelease(
        startedAt: Instant?,
        reason: EndReason,
        recordOnDisk: Boolean = startedAt != null,
    ) {
        // Retire the previous escalation's queued retry before starting this
        // one, so there is only ever one callback outstanding and it belongs to
        // the newest obligation. A leftover retry can otherwise fire *during*
        // this escalation, find its own snooze gone, and stop a service the new
        // retry is living in — whose `onDestroy` then cancels it, leaving the
        // newer rule with no successor at all.
        releaseRetryHandler.removeCallbacksAndMessages(null)
        releasingSnooze = startedAt
        // Why this release was asked for in the first place, carried so every
        // later attempt ends for the same reason the first one did. A retry
        // that invented its own would tell the user a cap expired — and tell
        // the platform's Modes UI `CONTEXT` rather than `USER_ACTION` — for a
        // snooze they ended themselves.
        releasingReason = reason
        releasingRecordOnDisk = recordOnDisk
        // Read rather than assumed: a previous escalation may already have
        // stored the obligation, and re-writing it every pass would be a
        // preferences commit per retry for nothing.
        releaseProgress = ReleaseProgress(
            snoozeIdentified = startedAt != null,
            obligationStored = if (pendingFailure.ruleMayBeStuck()) Attempt.TOOK else Attempt.NOT_TRIED,
        )
        advanceRelease()
    }

    /**
     * Walks the ladder until something durable takes it over, or nothing is
     * left.
     *
     * Every rung's result is fed back as *what actually happened* — a refused
     * `commit`, an alarm the platform declined and a `notify` that threw are all
     * "not done", and each moves the ladder on rather than being assumed away.
     */
    private fun advanceRelease() {
        while (true) {
            val progress = releaseProgress ?: return
            when (val step = ReleaseEscalation.next(progress)) {
                // Confirmed off, so the obligation and the cards that say it
                // might not be are now false and come down together.
                ReleaseStep.Settled -> {
                    if (pendingFailure.ruleMayBeStuck()) pendingFailure.clearRuleStuck()
                    notifications.cancelStuckRule()
                    notifications.cancelEndFailure()
                    finishRelease()
                    return
                }

                ReleaseStep.StoreObligation -> releaseProgress = progress.copy(
                    obligationStored = Attempt.of(pendingFailure.rememberRuleMayBeStuck()),
                )

                ReleaseStep.TellUser -> releaseProgress = progress.copy(
                    userTold = Attempt.of(notifications.showStuckRule()),
                )

                is ReleaseStep.ArmAlarm ->
                    releaseProgress = progress.afterArmingAlarm(armReleaseAlarm(step))

                ReleaseStep.RetryInProcess -> {
                    scheduleReleaseRetry(progress)
                    return
                }

                // Something durable will act. Stop here rather than paying for a
                // retry as well — and in particular without the stuck-rule card,
                // which says the rule *may* be on with nothing coming: untrue
                // while an alarm is scheduled.
                //
                // `Couldn't end the snooze — trying again` is the message that
                // is true here, and only where a record exists: it names a
                // snooze, so a rule with nothing on disk behind it has none to
                // name and gets the log line alone.
                //
                // Gated on the record, *not* on the ladder's `snoozeIdentified`,
                // and the two part company on exactly one path. An arm whose
                // zen write landed but whose `save` failed is unwound with a
                // live in-memory snooze and nothing on disk: identified enough
                // for the alarm, but the user has `Couldn't snooze` on screen,
                // and replacing it with `Couldn't end the snooze` would
                // announce a snooze the app had just told them didn't start.
                ReleaseStep.HandOff -> {
                    Log.w(TAG, "The release is still refused but an alarm took; handing it over.")
                    if (releasingRecordOnDisk) {
                        notifications.showFailure(ZenFailure.PLATFORM_REFUSED, whileArming = false)
                    }
                    finishRelease()
                    return
                }

                ReleaseStep.Exhausted -> {
                    // Named rather than fallen off the end, so nothing here can
                    // go on claiming a retry is underway. What is left is the
                    // persisted obligation, which any later start discharges,
                    // and — where a record survives — that record's own cap.
                    Log.e(TAG, "Every release rung is spent; the rule may still be on.")
                    finishRelease()
                    return
                }
            }
        }
    }

    /** Which alarm carries this obligation onward, per the ladder's answer. */
    private fun armReleaseAlarm(step: ReleaseStep.ArmAlarm): Boolean {
        val at = System.currentTimeMillis() + RETRY_MS
        val startedAt = releasingSnooze
        // A snooze with a record needs the instruction that actually applies —
        // end it, whatever the clock says — because its original cap can be
        // hours away and a plain cap check would restore it, find it unexpired,
        // and spend the only retry doing nothing. Identified, so it cannot end a
        // snooze the user armed in the meantime.
        return if (step.forIdentifiedSnooze && startedAt != null) {
            CapAlarm.armReleaseRetry(applicationContext, at, startedAt.toEpochMilli(), releasingReason)
        } else {
            CapAlarm.armCheckIn(applicationContext, RETRY_MS)
        }
    }

    /**
     * Queues the weakest successor there is: another go in this process.
     *
     * It dies with the service, which is why the ladder reaches it only once
     * nothing durable would take — and why the service is held up for as long as
     * one is outstanding.
     */
    private fun scheduleReleaseRetry(progress: ReleaseProgress) {
        releaseProgress = progress
        releaseRetryHandler.postDelayed({
            // A fresh pass: the alarm the platform refused a moment ago is worth
            // asking for again, and this attempt is what eventually ends the
            // escalation.
            releaseProgress = progress.afterInProcessAttempt().copy(ruleOff = retryRelease())
            advanceRelease()
        }, IN_PROCESS_RETRY_MS)
    }

    /**
     * One more go at driving the rule off, and whether it is now confirmed off.
     *
     * Both readings of "nothing to do here" return true, because both mean
     * nothing is owed: the rule really did come off, or a snooze armed since
     * owns it and this escalation is chasing something that no longer exists.
     * Ending *that* snooze is not this retry's business — it is the same mistake
     * the identified erase and release retries guard against, from the one path
     * that has no record to identify itself by.
     */
    private fun retryRelease(): Boolean {
        val startedAt = releasingSnooze
        if (startedAt == null) {
            if (controller.active != null || store.load() != null) {
                Log.w(TAG, "Dropping the recordless release; a new snooze owns the rule now.")
                return true
            }
            val outcome = zen.setSnoozed(false, ZenTrigger.CONTEXT, ActiveSnooze.DEFAULT_PLACE_NAME)
            return outcome !is ZenOutcome.NotApplied || outcome.reason.nothingLeftToRelease
        }

        val running = controller.active
        if (running == null || running.startedAt != startedAt) {
            Log.w(TAG, "Dropping the release retry; the snooze it was queued for is gone.")
            return true
        }
        controller.end(releasingReason)
        return controller.active == null
    }

    /**
     * Marks the escalation over and lets an idle service go.
     *
     * Stops against the latest start rather than the one that queued this: the
     * delay means a newer start may have arrived, and the bare form would stop
     * regardless and discard it. Guarded on the same conditions
     * `onStartCommand` uses, so a snooze armed in the meantime keeps the
     * service up.
     */
    private fun finishRelease() {
        releaseProgress = null
        releasingSnooze = null
        releasingReason = EndReason.LOST_CAPABILITY
        releasingRecordOnDisk = false
        if (controller.active == null && recordErased) stopSelf(latestStartId)
    }


    /**
     * Erases the record and the cap alarm together — and, if the erase failed,
     * deliberately leaves the alarm alone.
     *
     * A record that outlives its snooze is one a later cold start will restore,
     * and restoring re-asserts the zen rule: the phone would go quiet again with
     * no user action behind it. The erase can't be forced, so the fallback is to
     * keep the exit that bounds it. The stale record's cap is unchanged, so the
     * alarm still fires at the original time and ends the thing it restored —
     * an unwanted snooze that expires on schedule, rather than an open-ended one.
     */
    private fun forget() {
        // Only the record this erase was queued for. A retry — durable alarm or
        // delayed handler — can arrive after the user has armed a new snooze,
        // and erasing *that* record cancels its cap while its zen rule stays
        // on: a phone left quiet with nothing scheduled to bring it back, done
        // by the mechanism that exists to prevent it.
        val onDisk = store.load()
        if (!ActiveSnooze.retryStillApplies(onDisk, erasing)) {
            Log.w(TAG, "Dropping a stale erase retry; a newer snooze owns the record now.")
            // Nothing of ours is outstanding any more, so this service has no
            // reason to stay up holding a retry for a record that is gone.
            recordErased = true
            eraseRetries = 0
            eraseRetryHandler.removeCallbacksAndMessages(null)
            return
        }

        // Settle the identity *before* the clear. A failed `commit` still
        // empties the in-memory map, so reading the record back afterwards to
        // label a retry would find nothing and arm an unidentified one — the
        // very thing the guard above exists to reject.
        val queuedFor = erasing ?: onDisk?.startedAt
        erasing = queuedFor

        // Marked released before the erase is attempted, so a record that
        // outlives a failed erase is at least never read back as a live snooze.
        // The erase is what fails here, so a marker written after it would
        // never be reached — and every wake-up that isn't an erase retry
        // restores from this record, which would re-assert the rule.
        val marked = store.markReleased()
        recordErased = store.clear()
        if (recordErased) {
            eraseRetries = 0
            erasing = null
            // Nothing left to retry, so drop any pending one before it can fire
            // against whatever record comes next.
            eraseRetryHandler.removeCallbacksAndMessages(null)
            // Both alarms, and only here: the record is gone, so neither the cap
            // nor an erase retry has anything left to act on.
            CapAlarm.cancelAll(applicationContext)
            return
        }
        Log.e(TAG, "Erasing the snooze record failed; retrying, with the cap as its bound.")
        if (!marked) {
            // Both writes to this file were refused, so what is on disk is a
            // released record that doesn't say so. Worse than a failed erase
            // alone: until a retry lands, any wake-up that restores — a cap
            // alarm, a boot, a notification grant — reads it as live and turns
            // DND back on. Recorded separately from the erase failure because
            // it is a different, larger exposure, and the two are worth telling
            // apart in a bug report.
            //
            // There is no third mechanism to reach for: both the marker and the
            // erase are commits to the store that just refused one, and the
            // in-memory map is already correct, so this process is safe and
            // only a *later* one is at risk. What bounds it is the record's own
            // cap, unchanged — whatever restores it ends on the original
            // schedule rather than running on.
            Log.e(TAG, "The record could not be marked released either; a restore may resurrect it.")
        }
        notifications.showNotForgotten()
        // An actual retry, not just a flag: nothing else calls this again, and
        // the cap alarm on its own would only wake a service that found no
        // active snooze and did nothing.
        //
        // The **erase-retry** alarm specifically, never the cap. A cap wake-up
        // restores the record before its action is even examined — that is what
        // a cap means — so pointing one at a record we are trying to delete
        // would re-assert the rule on arrival, and a clear that then succeeded
        // would cancel the cap while the resurrected snooze stayed active. It
        // also leaves the real cap alarm where it is, so the stale record keeps
        // the bound this log line claims for it.
        //
        // Skipped entirely when there is no record to name: an erase alarm
        // without an identity is one that would delete whatever record it finds
        // on arrival, which after a new arm is that snooze's cap.
        if (queuedFor != null &&
            CapAlarm.armEraseRetry(
                applicationContext,
                System.currentTimeMillis() + RETRY_MS,
                queuedFor.toEpochMilli(),
            )
        ) {
            return
        }

        // Neither the disk nor the alarm is cooperating. All that is left is
        // this process, so use it: an in-process retry is bounded by the
        // process's own lifetime, which is worse than an alarm but is not
        // nothing — and this service stays up for exactly this reason. Bounded
        // by a count as well, so a permanently broken filesystem doesn't leave a
        // timer ticking forever; the next service wake-up retries after that.
        if (eraseRetries++ >= MAX_IN_PROCESS_ERASE_RETRIES) {
            Log.e(TAG, "Giving up the in-process erase retry; the next wake-up will try again.")
            return
        }
        Log.e(TAG, "Scheduling the record-erase retry failed; falling back to an in-process retry.")
        eraseRetryHandler.postDelayed({
            // Guarded as well as cancelable, because the cancellation only
            // covers this service instance: a snooze armed in the meantime owns
            // the record now, and erasing it would take its cap with it.
            if (controller.active != null) {
                Log.w(TAG, "Dropping a stale erase retry; a new snooze owns the record now.")
                return@postDelayed
            }
            forget()
        }, IN_PROCESS_RETRY_MS)
    }

    override fun onTrackingChanged(snooze: ActiveSnooze, degradation: DegradationCause?) {
        // Said where the user is already looking, rather than left to be
        // discovered when the snooze doesn't end (SPEC.md §8.1) — and the same
        // three writes whichever way it moved, which is why there is one
        // callback rather than a pair (PR #34).
        //
        // A refused record write is not fatal and not silent. The notification
        // this process posts is right either way, and the stored cap is
        // untouched, so the snooze still ends on time — what a cold start would
        // lose is the *reason*, coming back to a record that misstates tracking.
        // Logged rather than retried: the retry machinery here exists for the
        // release path, where the cost of losing it is a phone that stays quiet,
        // and this is not that.
        // A snooze that quietly degraded to duration-only is visible after the
        // fact (SPEC.md §4.6) — the cause enum, never what was lost.
        SnoozeDebugLog.event(
            "tracking → ${snooze.mode}" + (degradation?.let { " ($it)" } ?: " (recovered)"),
        )
        if (!store.save(snooze)) {
            Log.w(TAG, "Recording the tracking mode failed; a restart would misstate tracking.")
        }
        notifications.showOngoing(snooze)
        // The tile renders the mode too — its subtitle drops to `timer only`
        // (Codex, PR #33). It reads the record in `onStartListening` and nothing
        // else, so without this the shade keeps the old subtitle until the
        // system happens to restart listening. Every state transition already
        // refreshes; a mode change is the one that doesn't have one.
        SnoozeTileBridge.refresh(applicationContext)
    }

    override fun onZenFailure(failure: ZenFailure, whileArming: Boolean) {
        SnoozeDebugLog.warning("zen write refused: $failure (whileArming=$whileArming)")
        notifications.showFailure(failure, whileArming)
        // Kept in case the system dropped it: on a tile-first install
        // POST_NOTIFICATIONS is still denied at the first tap, so the message
        // explaining a failed arm goes nowhere and the user is left with a tap
        // that did nothing and no reason for it. A later grant re-posts this.
        if (whileArming) {
            pendingFailure.remember(ArmFailure.Zen(failure))
            // Also kept in memory, for the unwind a few frames later: `arm` sees
            // only that `beginArming` returned false, and what it should do with
            // the cap alarm depends on *which* refusal this was.
            lastArmFailure = failure
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The retry cannot outlive the instance that queued it — the next one
        // re-reads the record and picks the work back up if it is still there.
        eraseRetryHandler.removeCallbacksAndMessages(null)
        releaseRetryHandler.removeCallbacksAndMessages(null)
        // The capture dies with the controller it feeds. The snooze survives —
        // its record is on disk with whatever mode ARMING wrote — and a
        // capture lost this way is the arm ceiling's degraded path arriving
        // early, not a new failure mode.
        anchorCapture?.close()
        anchorCapture = null
        // The *collection* dies with this instance — the next one restores
        // the record and re-collects — but deliberately not through
        // [stopPresence]: `stop()` removes the geofence, and Android destroys
        // an ordinary background service routinely, so stopping here would
        // take down the one durable thing that can wake the app for a
        // departure — DND until the cap, silently (flagged by Codex on
        // PR #73). The fence outlives the process by design (SPEC.md §6.10);
        // only a snooze actually ending stops the monitor.
        presenceJob?.cancel()
        presenceJob = null
        presenceScope.cancel()
        // Only if it registered. A registration the platform refused is a state
        // this class now knows about, so unregistering it is not something to
        // attempt and then explain away.
        if (!accessReceiverRegistered) return
        accessReceiverRegistered = false
        runCatching { unregisterReceiver(accessReceiver) }.onFailure {
            // Registered but not unregisterable — a lifecycle defect, not an
            // expected state. Harmless at teardown (the process is releasing it
            // anyway), so logged rather than escalated, but not swallowed.
            Log.w(TAG, "Unregistering the policy-access receiver failed.", it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ARM = "app.snoozemo.action.ARM"
        const val ACTION_END = "app.snoozemo.action.END"
        const val ACTION_CHECK_CAP = "app.snoozemo.action.CHECK_CAP"
        const val ACTION_EXTEND = "app.snoozemo.action.EXTEND"
        const val ACTION_RESTORE = "app.snoozemo.action.RESTORE"
        const val ACTION_CAP_LOST = "app.snoozemo.action.CAP_LOST"
        const val ACTION_REFRESH = "app.snoozemo.action.REFRESH"

        /**
         * The wall clock was set while a snooze is running (SPEC.md §7).
         *
         * Distinct from [ACTION_CHECK_CAP] because the cap being due is only one
         * of the answers: the record's two clock frames have to be restated
         * whether or not it is, and doing that through the service is what keeps
         * the controller's copy in step with the one on disk.
         */
        const val ACTION_CLOCK_CHANGED = "app.snoozemo.action.CLOCK_CHANGED"
        const val ACTION_ERASE_RETRY = "app.snoozemo.action.ERASE_RETRY"

        /**
         * Retries discarding a record this device cannot vouch for, when the
         * rule would not release (SPEC.md §12). Distinct from the release
         * retries because those resolve their record through
         * `ActiveSnoozeStore.load()`, which refuses this one.
         */
        const val ACTION_DISCARD_RETRY = "app.snoozemo.action.DISCARD_RETRY"

        /**
         * The user tapping `Unsnooze` on the stuck-rule notification — the last
         * exit, and the only one driven entirely by them.
         *
         * Distinct from [ACTION_END] because there is no snooze to end: no
         * record, nothing for the controller to adopt. This drives Snoozemo's
         * own rule off directly and is safe whenever it arrives, since a user
         * asking for Do Not Disturb off always wants exactly that.
         */
        const val ACTION_RELEASE_STUCK = "app.snoozemo.action.RELEASE_STUCK"

        /**
         * Which record an [ACTION_ERASE_RETRY] may delete, as epoch millis of
         * its `startedAt`.
         *
         * On-device only, and never logged: a snooze's start time is the user's
         * (`AGENTS.md`, *Privacy*), so it travels between the app's own
         * components and goes nowhere else.
         */
        const val EXTRA_RECORD_STARTED_AT = "app.snoozemo.extra.RECORD_STARTED_AT"

        /**
         * Why an [ACTION_RELEASE_STUCK] start is ending a snooze, as an
         * [EndReason] name.
         *
         * Carried because that action has two senders with different answers:
         * the notification's `Unsnooze`, which really is the user, and the
         * no-service ladder handing work over, which may be finishing a
         * duration cap or a lost capability. Without it every hand-off was
         * reported to the user — and to the platform's Modes UI — as something
         * they did themselves.
         */
        const val EXTRA_END_REASON = "app.snoozemo.extra.END_REASON"

        /** The notification's `+30 min` step (SPEC.md §4.3). */
        val EXTENSION: Duration = Duration.ofMinutes(30)

        /** How soon to come back at anything a spent cap alarm left undone. */
        private const val RETRY_MS = 5 * 60 * 1000L

        /** The last-resort retry when even scheduling an alarm fails. */
        private const val IN_PROCESS_RETRY_MS = 30 * 1000L
        private const val MAX_IN_PROCESS_ERASE_RETRIES = 10

        /**
         * Arms from inside the app. The tile goes through the trampoline
         * instead (SPEC.md §6.9); an activity is already a documented exemption
         * for starting a service, so this needs no trampoline of its own.
         */
        fun arm(context: Context) = start(context, ACTION_ARM)

        fun end(context: Context) = start(context, ACTION_END)

        fun checkCap(context: Context) = start(context, ACTION_CHECK_CAP)

        fun restore(context: Context) = start(context, ACTION_RESTORE)

        /** Re-post the ongoing notification, after something unblocked it. */
        fun refresh(context: Context) = start(context, ACTION_REFRESH)

        /**
         * Restate the running snooze's clock frames after the wall clock was
         * set, returning false if the service would not start — in which case
         * the caller performs the same change itself.
         */
        fun clockChanged(context: Context) = start(context, ACTION_CLOCK_CHANGED)

        /**
         * Finish erasing the released record that started at
         * [recordStartedAtMillis], and only that one — by the time this runs the
         * user may have armed a new snooze whose record must not be touched.
         */
        fun retryErase(context: Context, recordStartedAtMillis: Long) =
            start(context, ACTION_ERASE_RETRY) {
                it.putExtra(EXTRA_RECORD_STARTED_AT, recordStartedAtMillis)
            }

        /**
         * Drive Snoozemo's own rule off when nothing else is left to do it.
         *
         * The same action the stuck-rule notification's `Unsnooze` sends, used
         * as a last successor by the no-service fallback: if that path can
         * neither persist the obligation nor post the card, this service's
         * in-process retry is the only thing that could still run. It may well
         * refuse to start — that is how the fallback was reached — but this and
         * that are different refusals a moment apart, and it costs one call.
         */
        fun releaseStuck(context: Context, reason: EndReason = EndReason.MANUAL) =
            start(context, ACTION_RELEASE_STUCK) { it.putExtra(EXTRA_END_REASON, reason.name) }

        /**
         * The [EndReason] a release start or alarm was made for, or [default]
         * when it carries none.
         *
         * The default is the caller's, because what "no reason attached" means
         * depends on who is asking. On [ACTION_RELEASE_STUCK] it is `MANUAL`:
         * the extra is absent exactly when the notification's own `Unsnooze`
         * sent it, and that is the user acting. On [ACTION_CAP_LOST] it is
         * `LOST_CAPABILITY`, which is what that action means on its own and
         * what an alarm armed before this extra existed would have done.
         *
         * An unrecognized name takes the same default and logs — a reason we
         * cannot parse is not worth failing a release over, and ending is what
         * matters here.
         */
        fun endReasonFrom(intent: Intent?, default: EndReason = EndReason.MANUAL): EndReason {
            val name = intent?.getStringExtra(EXTRA_END_REASON) ?: return default
            return EndReason.entries.firstOrNull { it.name == name } ?: run {
                Log.w(TAG, "A release start named an unknown end reason; falling back to the default.")
                default
            }
        }

        /**
         * A reboot that could not reschedule the cap: end rather than restore.
         *
         * [recordStartedAtMillis] names the snooze this is about, so a retry
         * that outlived it cannot end the one the user armed since.
         */
        fun endWithoutCap(
            context: Context,
            recordStartedAtMillis: Long,
            reason: EndReason = EndReason.LOST_CAPABILITY,
        ) =
            start(context, ACTION_CAP_LOST) {
                it.putExtra(EXTRA_RECORD_STARTED_AT, recordStartedAtMillis)
                it.putExtra(EXTRA_END_REASON, reason.name)
            }

        /**
         * Starts the service for [action], returning false if the platform
         * refused.
         *
         * Every caller is a broadcast receiver or a notification action, so the
         * app should be on the temporary allowlist that permits this — but
         * "should be" is documentation, and the caller has to know. It matters
         * most for the cap alarm: that alarm is one-shot and has already fired
         * by the time this runs, so a refusal there is not a lost wake-up, it is
         * the loss of the snooze's last exit. [CapAlarmReceiver] handles it.
         */
        private fun start(
            context: Context,
            action: String,
            extras: (Intent) -> Unit = {},
        ): Boolean =
            runCatching {
                val intent = Intent(context, SnoozeService::class.java).setAction(action)
                extras(intent)
                context.startService(intent)
                true
            }.getOrElse {
                Log.e(TAG, "Starting the snooze service for $action was refused.", it)
                false
            }
    }
}
