package app.snoozemo.snooze

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Attempt
import app.snoozemo.core.EndReason
import app.snoozemo.core.ReleaseEscalation
import app.snoozemo.core.ReleaseProgress
import app.snoozemo.core.ReleaseStep
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenTrigger
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.dnd.PrefsZenRuleIdStore
import app.snoozemo.ui.MainActivity
import java.time.Instant

/**
 * The duration cap's last line of defense (SPEC.md §7).
 *
 * `setAndAllowWhileIdle`, inexact **on purpose**: exact alarms need
 * `SCHEDULE_EXACT_ALARM`, which is no longer auto-granted on Android 14+ and
 * carries its own Play scrutiny, and a cap that fires at 8h04m instead of 8h00m
 * is indistinguishable to the user. What matters is that it fires at all — and
 * that it survives the service dying, which an in-process timer does not.
 */
object CapAlarm {

    /**
     * Arms the alarm for [capExpiresAtMillis], returning false if it could not
     * be scheduled.
     *
     * Called **before** anything that can throw on the arm path, because a
     * snooze whose cap was never scheduled is a snooze with one fewer exit — and
     * the cap is the exit that holds when every sensor has failed.
     *
     * The return value is not decoration. There is no in-process timer behind
     * this: the alarm *is* the cap, so a caller that armed a snooze anyway after
     * a failure here would have created a snooze with no time bound at all,
     * which after process death is a phone left silent indefinitely. Callers
     * treat false as a refusal to arm (SPEC.md §7).
     */
    fun arm(context: Context, capExpiresAtMillis: Long): Boolean =
        schedule(
            context,
            SystemClock.elapsedRealtime() + remainingRealMillis(capExpiresAtMillis),
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SnoozeService.ACTION_CHECK_CAP,
        )

    /**
     * Arms a cap check [delayMillis] from now, for a caller that has a *delay*
     * rather than a deadline.
     *
     * Separate from [arm] on purpose. Both end at `ACTION_CHECK_CAP`, so the
     * action cannot tell them apart — and inferring the time frame from it was a
     * real bug: the recordless release retries expressed "in 30 seconds" as
     * `System.currentTimeMillis() + RETRY_MS`, which [arm] then measured back
     * against the elapsed-realtime clock. A wall clock moved *forward* mid-life
     * made those disagree, so a half-minute retry became up to the 24 h ceiling
     * — on the one path where that retry may be the only thing left to turn a
     * stuck rule off.
     *
     * A delay never needed converting to wall time and back. This takes it as
     * what it is.
     */
    fun armCheckIn(context: Context, delayMillis: Long): Boolean =
        schedule(
            context,
            SystemClock.elapsedRealtime() + delayMillis.coerceAtLeast(0L),
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SnoozeService.ACTION_CHECK_CAP,
        )

    /**
     * How long from now [capExpiresAtMillis] is, as real time the user cannot
     * move, floored at zero and bounded by the absolute backstop.
     *
     * The bound is the defense against a backwards clock change made while no
     * process was alive to notice: the record's expiry is absolute wall time, so
     * winding the date back makes it look arbitrarily far away. `MAX_CAP` is
     * already the ceiling on any snooze (SPEC.md §7), so a remaining duration
     * beyond it is not a long snooze — it is a clock that moved, and the cap
     * fires at the ceiling instead of never.
     */
    private fun remainingRealMillis(capExpiresAtMillis: Long): Long =
        (capExpiresAtMillis - SnoozeClock.millis())
            .coerceIn(0L, ActiveSnooze.MAX_CAP.toMillis())

    /**
     * Arms a wake-up whose only job is to retry erasing a released record.
     *
     * A **separate** alarm, carrying its own action, because the two mean
     * opposite things to a service that has just been recreated. A cap wake-up
     * restores the record and re-asserts the rule; an erase retry must do the
     * reverse. Re-using the cap alarm for both meant a retry could restart the
     * process, be read as a cap, and re-enable DND over a snooze the user had
     * already ended (SPEC.md §8.1).
     *
     * [recordStartedAtMillis] names *which* record it may delete. This alarm is
     * durable and the process is not, so it can outlive the snooze it was armed
     * for and fire once the user has armed a new one; without an identity it
     * would erase that snooze's record and cancel its cap while its rule stayed
     * on (`ActiveSnooze.retryStillApplies`).
     */
    fun armEraseRetry(context: Context, atMillis: Long, recordStartedAtMillis: Long): Boolean =
        schedule(
            context,
            atMillis,
            AlarmManager.RTC_WAKEUP,
            SnoozeService.ACTION_ERASE_RETRY,
            recordStartedAtMillis,
        )

    /**
     * Arms a wake-up that retries **ending** a snooze which has no cap.
     *
     * A third distinct alarm, for the same reason the erase retry is a second
     * one: what a wake-up means has to travel with it. A cap wake-up asks
     * whether the record has expired yet, and the snooze this retries for is
     * one whose original cap could not be rescheduled after a reboot — its
     * deadline is still in the future, so a cap check would find nothing to do
     * and quietly spend the only retry left. This says "end it" instead, which
     * is what [SnoozeService.ACTION_CAP_LOST] already means.
     *
     * [recordStartedAtMillis] names *which* snooze it may end, for the same
     * reason the erase retry carries one: this alarm is durable and the process
     * is not, so a successful end whose `cancelAll` was refused — or which died
     * between clearing the record and canceling — leaves it armed to fire over
     * whatever the user snoozes next. Ending the wrong snooze is the fail-open
     * direction, but it is still an end the user didn't ask for, explained as a
     * reboot that couldn't resume, which never happened.
     *
     * [reason] travels with it for the same reason the identity does: this alarm
     * is the *successor* to a release that was already refused, so it finishes
     * an end that something else started. Without it every wake-up here reported
     * `LOST_CAPABILITY`, turning a cap expiry into a capability failure — the
     * user is told the app lost something rather than that their time limit was
     * reached, and the platform's Modes UI is told `CONTEXT` rather than the
     * trigger that actually applied.
     */
    fun armReleaseRetry(
        context: Context,
        atMillis: Long,
        recordStartedAtMillis: Long,
        reason: EndReason,
    ): Boolean =
        schedule(
            context,
            atMillis,
            AlarmManager.RTC_WAKEUP,
            SnoozeService.ACTION_CAP_LOST,
            recordStartedAtMillis,
            reason,
        )

    /**
     * [triggerAtMillis] is read in whichever frame [type] names, so the caller
     * states both rather than this inferring one from the action — two callers
     * share `ACTION_CHECK_CAP` and mean different clocks by it.
     */
    private fun schedule(
        context: Context,
        triggerAtMillis: Long,
        type: Int,
        action: String,
        recordStartedAtMillis: Long? = null,
        reason: EndReason? = null,
    ): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return runCatching {
            alarmManager.setAndAllowWhileIdle(
                type,
                triggerAtMillis,
                pendingIntent(context, action, recordStartedAtMillis, reason),
            )
            true
        }.getOrElse {
            // Never silent, and never assumed away: the caller aborts the arm on
            // this, and the user's phone is the thing at risk if it doesn't.
            Log.e(TAG, "Arming the $action alarm failed; refusing to snooze without it.", it)
            false
        }
    }

    /** Drops the cap wake-up, leaving any pending retry alone. */
    fun cancel(context: Context) = cancel(context, everything = false)

    /**
     * Drops every wake-up, for a snooze that is over and whose record is gone.
     *
     * Only for that case. An erase retry outstanding means a record is still on
     * disk, and that record is what a later cold start would restore into a
     * live snooze — so canceling its retry alongside the cap is how the app
     * ends up with a stale record and nothing left to delete it. All of them
     * are dropped only once the erase has actually succeeded, where each would
     * otherwise wake the phone for work that is already done.
     */
    fun cancelAll(context: Context) = cancel(context, everything = true)

    private fun cancel(context: Context, everything: Boolean) {
        runCatching {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            existing(context, SnoozeService.ACTION_CHECK_CAP)?.let(alarmManager::cancel)
            if (everything) {
                existing(context, SnoozeService.ACTION_ERASE_RETRY)?.let(alarmManager::cancel)
                existing(context, SnoozeService.ACTION_CAP_LOST)?.let(alarmManager::cancel)
            }
        }.onFailure {
            // An alarm that outlives its snooze is harmless — the service
            // re-checks the clock and the record on arrival and finds nothing to
            // do — so this is logged rather than escalated.
            Log.w(TAG, "Canceling the snooze's alarms failed; a stale alarm is harmless.", it)
        }
    }

    // Distinct request codes as well as distinct actions: PendingIntent
    // equality ignores the action, so one code would have made the two alarms
    // the same pending intent and each would have replaced the other.
    //
    // FLAG_UPDATE_CURRENT is what makes the identity extra trustworthy:
    // PendingIntent equality ignores extras too, so re-arming an erase retry
    // for a different record reuses the same pending intent — the flag is what
    // replaces the stale identity inside it with the current one.
    private fun pendingIntent(
        context: Context,
        action: String,
        recordStartedAtMillis: Long?,
        reason: EndReason?,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(action),
            intentFor(context, action).also { intent ->
                recordStartedAtMillis?.let {
                    intent.putExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, it)
                }
                reason?.let { intent.putExtra(SnoozeService.EXTRA_END_REASON, it.name) }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The already-scheduled alarm for [action], or null if there isn't one.
     *
     * `FLAG_NO_CREATE` rather than `FLAG_UPDATE_CURRENT`, and the difference is
     * load-bearing: fetching a token to *cancel* with must not modify the token
     * on the way past. `FLAG_UPDATE_CURRENT` would rewrite the pending intent's
     * extras first — stripping the erase retry's record identity — and then
     * cancel it. Where the cancel throws, or the process dies between the two,
     * what survives is an erase alarm that no longer names the record it was
     * armed for, which is exactly the unidentified retry the identity check
     * exists to reject (`ActiveSnooze.retryStillApplies`).
     */
    private fun existing(context: Context, action: String): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode(action),
            intentFor(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )

    private fun intentFor(context: Context, action: String): Intent =
        Intent(context, CapAlarmReceiver::class.java).setAction(action)

    // One per action. PendingIntent equality ignores the action, so sharing a
    // code would make two alarms the same pending intent and each would silently
    // replace the other.
    private fun requestCode(action: String): Int = when (action) {
        SnoozeService.ACTION_ERASE_RETRY -> REQUEST_ERASE_RETRY
        SnoozeService.ACTION_CAP_LOST -> REQUEST_RELEASE_RETRY
        else -> REQUEST_CAP
    }

    private const val TAG = "CapAlarm"
    private const val REQUEST_CAP = 1
    private const val REQUEST_ERASE_RETRY = 2
    private const val REQUEST_RELEASE_RETRY = 3
}

/**
 * Wakes the service so it can re-check the cap against the clock — and releases
 * the snooze itself if the service cannot be woken.
 *
 * This alarm is one-shot and has already fired by the time this runs, so there
 * is no cap left to retry: if starting the service is refused here, nothing else
 * in the app is scheduled to end this snooze. That is a phone left quiet
 * indefinitely, which is principle 1's failure, so the fallback is not a log
 * line — it is the release, done here.
 */
class CapAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == SnoozeService.ACTION_ERASE_RETRY) {
            // Not a cap. Nothing to release here — the release already
            // succeeded; only the record is left over. The service holds the
            // retry, but this alarm is one-shot and already spent, so a refused
            // start has to be handled here: otherwise nothing at all is left
            // scheduled to clear the record.
            //
            // The identity travels with it, because this alarm can outlive the
            // snooze it was armed for: erasing whatever record happens to be on
            // disk would take a *newer* snooze's cap with it (SPEC.md §8.1).
            val recordStartedAt = intent.getLongExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, 0L)
            if (!SnoozeService.retryErase(context, recordStartedAt)) {
                eraseDirectly(context, recordStartedAt)
            }
            return
        }
        if (intent?.action == SnoozeService.ACTION_CAP_LOST) {
            // A snooze whose cap could not be rescheduled after a reboot, whose
            // forced release was then refused. Same instruction as last time —
            // end it, there is no cap to wait for — and the same fallback if
            // the service still won't start, since this alarm is spent too.
            //
            // Identified for the same reason the erase retry is: this alarm
            // outliving its snooze would end whichever one the user armed next.
            // The service checks it against the record it adopts; the fallback
            // below has to check for itself.
            val endStartedAt = intent.getLongExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, 0L)
            // The reason the release this is retrying was started for. Absent on
            // an alarm armed before this extra existed, where `LOST_CAPABILITY`
            // is both the historical behavior and what the action means on its
            // own: a snooze whose cap is gone.
            val reason = SnoozeService.endReasonFrom(intent, default = EndReason.LOST_CAPABILITY)
            if (!SnoozeService.endWithoutCap(context, endStartedAt, reason)) {
                releaseDirectlyIfStillOurs(context, endStartedAt, reason)
            }
            return
        }
        if (SnoozeService.checkCap(context)) return
        releaseDirectly(context, EndReason.DURATION_CAP)
    }
}

/**
 * Ends the snooze the release retry was armed for — and only that one.
 *
 * The service does this check against the record it adopts, but this path is
 * reached precisely when the service would not start, so it has to make the
 * same judgment itself. Without it a retry that outlived its snooze would drive
 * the rule off under a *newer* snooze, erase that snooze's record and tell the
 * user their phone couldn't resume after a reboot that never happened.
 *
 * The record is read twice on the way through — once here and once inside
 * [releaseDirectly]. Both are memory hits by this point (the file is loaded),
 * and the alternative is threading a loaded record through a function whose
 * other callers have none.
 */
private fun releaseDirectlyIfStillOurs(
    context: Context,
    recordStartedAtMillis: Long,
    reason: EndReason,
) {
    val queuedFor = recordStartedAtMillis.takeIf { it != 0L }?.let(Instant::ofEpochMilli)
    if (!ActiveSnooze.retryStillApplies(ActiveSnoozeStore(context).load(), queuedFor)) {
        Log.w(RELEASE_TAG, "Dropping a stale release retry; a newer snooze owns the record now.")
        return
    }
    releaseDirectly(context, reason)
}

/**
 * The last line of defense: turn the rule off from a receiver, with no service.
 *
 * Used wherever the release cannot depend on the service starting — the cap
 * alarm, whose one-shot alarm is spent by the time it runs, and the boot path
 * that could not reschedule a cap. Deliberately duplicates a little of what the
 * service would have done rather than routing through it, since the whole reason
 * this exists is that the service could not be started. It is a handful of
 * binder calls, well inside a receiver's budget, and it drives Snoozemo's own
 * rule off exactly as `SnoozeController.end` would (SPEC.md §5.6 — never anyone
 * else's).
 *
 * [reason] is what the user is told the snooze ended for, since this path has no
 * controller to derive it from.
 *
 * Returns whether the rule is **confirmed off**. False means the zen write was
 * refused and something else — a fresh alarm, or the user, via the message this
 * posts — has to finish the job. Callers that report an outcome to the user must
 * not read a refusal as an ending: saying "Snooze ended" over a phone that may
 * still be silent is the quiet-wrong-answer this app's second principle is
 * about. Callers with nothing to report can ignore it.
 */
internal fun releaseDirectly(context: Context, reason: EndReason): Boolean {
    val store = ActiveSnoozeStore(context)
    val snooze = store.load()
    val zen = AndroidZenController(
        context = context.applicationContext,
        store = PrefsZenRuleIdStore(context.applicationContext),
        configurationActivity = ComponentName(context.applicationContext, MainActivity::class.java),
    )

    val outcome = zen.setSnoozed(
        snoozed = false,
        trigger = ZenTrigger.CONTEXT,
        placeName = snooze?.placeName ?: ActiveSnooze.DEFAULT_PLACE_NAME,
    )
    val released = outcome is ZenOutcome.Applied ||
        (outcome is ZenOutcome.NotApplied && outcome.reason.nothingLeftToRelease)
    if (released) {
        // The marker first — before the notification, the tile, and the erase.
        // Everything after this line is IPC that can throw or be killed, and
        // until the record says "released" a later restore reads it as live and
        // turns DND back on after the user's snooze is over (SPEC.md §8.1).
        // Ordering it after the zen write and before everything else is what
        // makes that window as small as the platform allows.
        val marked = store.markReleased()

        // The snooze is over, so everything still claiming otherwise has to be
        // taken down. Without this the ongoing notification keeps saying
        // `Snoozing` — with an `End now` for a snooze that already ended and a
        // `+30 min` that would extend nothing — and the tile stays lit, which
        // is principle 2's failure produced by the fallback that just did the
        // right thing. `showEnded` cancels the ongoing one on its way past.
        val notifications = SnoozeNotifications(context.applicationContext)
        // Only when a snooze actually existed. With no record this is either a
        // stale alarm after a clean end or the recordless safety release, and
        // in both cases `Snooze ended — time limit reached` describes a snooze
        // the user does not have: it names a cap that never fired for them.
        // Nothing to report either — if a stuck rule really was just turned
        // off, what they notice is their phone working again.
        //
        // The ongoing notification still comes down, since a record-less
        // release can still be cleaning up after one that was posted.
        if (snooze != null) notifications.showEnded(reason) else notifications.cancelOngoing()

        // Same for the stuck-rule card and the obligation behind it, which are
        // both claims that the rule *might* still be on — and the write above
        // has just confirmed it isn't. This is the no-service twin of the
        // clean-up `SnoozeService.releaseRecordlessRule` does, and it is not
        // hypothetical: the trampoline routes `Unsnooze` here whenever the
        // service refuses to start, so without it the user's tap turns Do Not
        // Disturb off and leaves `Do Not Disturb may still be on` on screen
        // over a phone that is already fine, with a later start dutifully
        // retrying a release that has nothing left to do.
        //
        // Unconditional, because what makes them false is the rule being off,
        // not which path turned it off.
        notifications.cancelStuckRule()
        PendingFailureStore(context).run { if (ruleMayBeStuck()) clearRuleStuck() }

        val erased = store.clear()
        if (!marked && !erased) {
            // Neither write landed, so the record on disk is released and
            // doesn't say so — a later restore reads it as live and turns DND
            // back on until the old cap. Nothing else here can fix that; the
            // retry below is the only thing that will, and the cap bounds it.
            Log.e(RELEASE_TAG, "The record could not be marked released; a restore may resurrect it.")
        }
        SnoozeTileBridge.refresh(context.applicationContext)
        if (erased) return true
        // Nothing was loaded, so the failed clear was an empty commit and there
        // is no record for a retry to come back for. Scheduling one anyway
        // would arm an *unidentified* erase — the one thing that could later
        // delete a snooze the user has since armed.
        val recordStartedAt = snooze?.startedAt?.toEpochMilli() ?: run {
            Log.w(RELEASE_TAG, "Clearing an already-absent record failed; nothing to retry.")
            return true
        }
        // A record that outlives its release gets restored later and re-asserts
        // the rule — the phone going quiet again with no user action behind it.
        // There is no service here to hold the retry, so the retry has to be an
        // alarm: this path is reached precisely because the service wouldn't
        // start, and by the next firing it may well start fine.
        Log.e(RELEASE_TAG, "Released without the service but could not clear the record; retrying.")
        if (CapAlarm.armEraseRetry(
                context,
                System.currentTimeMillis() + RELEASE_RETRY_MS,
                recordStartedAt,
            )
        ) {
            return true
        }

        // Alarm refused too. One more mechanism left to try: the service, which
        // holds an in-process erase retry of its own. It may well have refused
        // to start a moment ago — that is how this function was reached — but
        // that and this are different refusals and it costs one call to find
        // out. Asked for the erase specifically, not a cap check: the release
        // has already happened here, and a cap check on a record whose cap is
        // still in the future would find nothing to do and leave it in place.
        Log.e(RELEASE_TAG, "Scheduling the record-erase retry failed; asking the service to retry.")
        if (!SnoozeService.retryErase(context, recordStartedAt)) reportRecordStuck(context)
        // The rule is off either way; only the leftover record is unresolved.
        return true
    }

    // The release itself was refused, and the alarm that would have retried it
    // is spent — so the ladder decides what is left (SPEC.md §7.1). This site
    // used to write its own version of that sequence, in the wrong order: the
    // alarm went first and the durable obligation only if the alarm was
    // refused, so an alarm that armed and was then canceled by an unrelated
    // `cancelAll` left nothing written down about a rule that may still be on.
    Log.e(RELEASE_TAG, "Releasing without the service was refused; escalating.")
    escalateWithoutService(context, snooze, reason)
    return false
}

/**
 * Walks the release ladder from a receiver, where there is no service to hold a
 * retry.
 *
 * The same decision as the service's, with the performers a receiver actually
 * has. The in-process rung is *starting the service*, which holds a real
 * delayed retry of its own — and it is one attempt rather than ten, because a
 * broadcast receiver has neither the budget nor the lifetime for more, so a
 * refused start spends that rung outright and the ladder moves on.
 *
 * [snooze] is the record this is about, or null where nothing on disk describes
 * the rule. It decides which alarm carries the obligation onward: a plain cap
 * check asks "has the record expired yet?", so on an early refused end — a
 * manual exit hours before the cap — it would restore the snooze, find it
 * unexpired, and decline to act, having spent the retry.
 */
private fun escalateWithoutService(context: Context, snooze: ActiveSnooze?, reason: EndReason) {
    val pendingFailure = PendingFailureStore(context)
    val notifications = SnoozeNotifications(context.applicationContext)

    // Seeded from what is already on disk rather than re-written: a previous
    // escalation may have stored this same obligation, which carries no
    // identity precisely so it can be shared.
    var progress = ReleaseProgress(
        snoozeIdentified = snooze != null,
        obligationStored = if (pendingFailure.ruleMayBeStuck()) Attempt.TOOK else Attempt.NOT_TRIED,
    )

    while (true) {
        when (val step = ReleaseEscalation.next(progress)) {
            ReleaseStep.StoreObligation -> progress = progress.copy(
                obligationStored = Attempt.of(pendingFailure.rememberRuleMayBeStuck()),
            )

            // `Do Not Disturb may still be on`, carrying `Unsnooze`. The last
            // thing that outlives this receiver, and the only one that hands the
            // user an exit rather than describing one.
            ReleaseStep.TellUser -> progress = progress.copy(
                userTold = Attempt.of(notifications.showStuckRule()),
            )

            is ReleaseStep.ArmAlarm -> progress = progress.afterArmingAlarm(
                armReleaseAlarm(context, step, snooze, reason),
            )

            ReleaseStep.RetryInProcess -> {
                // The service's refusal is how this function was reached, but
                // that refusal and this one are a moment apart and a start costs
                // one call. If it takes, the service runs the rest of this same
                // ladder with a retry that lives as long as the process does.
                if (SnoozeService.releaseStuck(context, reason)) {
                    Log.w(RELEASE_TAG, "The service took the release over; handing it on.")
                    return
                }
                progress = progress.afterFinalInProcessAttempt()
            }

            ReleaseStep.HandOff -> {
                Log.w(RELEASE_TAG, "An alarm took the release over; handing it on.")
                return
            }

            ReleaseStep.Exhausted -> {
                // Named rather than fallen off the end. What is left is the
                // persisted obligation, which every later non-arm start reads
                // and acts on, and — where a record survives — its own cap.
                Log.e(RELEASE_TAG, "Every release rung is spent; the user must turn Do Not Disturb off.")
                return
            }

            // Unreachable: this walk starts from a refused release and nothing
            // in it re-tests the rule, so `ruleOff` stays false. Handled rather
            // than thrown, because a stuck rule is not worth crashing a receiver
            // over.
            ReleaseStep.Settled -> return
        }
    }
}

/** Which alarm carries the obligation onward, per the ladder's answer. */
private fun armReleaseAlarm(
    context: Context,
    step: ReleaseStep.ArmAlarm,
    snooze: ActiveSnooze?,
    reason: EndReason,
): Boolean {
    val at = System.currentTimeMillis() + RELEASE_RETRY_MS
    return if (step.forIdentifiedSnooze && snooze != null) {
        CapAlarm.armReleaseRetry(context, at, snooze.startedAt.toEpochMilli(), reason)
    } else {
        CapAlarm.armCheckIn(context, RELEASE_RETRY_MS)
    }
}

/**
 * Erases a released snooze's record with no service to do it.
 *
 * Reached when the erase-retry alarm fires and the service refuses to start.
 * That alarm is one-shot and spent by the time this runs, so leaving it there
 * would leave *nothing* scheduled to come back for the record — and a record
 * that outlives its release is one a later cold start restores, which
 * re-asserts the zen rule and takes the phone quiet again with nothing the user
 * did behind it. Clearing it is a single `SharedPreferences` commit, well
 * inside a receiver's budget, so no service is no reason to leave it.
 *
 * [recordStartedAtMillis] is the record this retry was armed for, and only that
 * record may be deleted here: the alarm is durable and the process is not, so
 * by now the user may have armed a new snooze whose cap this would cancel.
 */
private fun eraseDirectly(context: Context, recordStartedAtMillis: Long) {
    val store = ActiveSnoozeStore(context)
    val queuedFor = recordStartedAtMillis.takeIf { it != 0L }?.let(Instant::ofEpochMilli)
    if (!ActiveSnooze.retryStillApplies(store.load(), queuedFor)) {
        Log.w(RELEASE_TAG, "Dropping a stale erase retry; a newer snooze owns the record now.")
        return
    }
    // Idempotent, and cheap insurance: this retry may be the first thing that
    // reaches the record if whatever released it died before marking it.
    val marked = store.markReleased()
    if (store.clear()) return
    if (!marked) {
        Log.e(RELEASE_TAG, "The record could not be marked released; a restore may resurrect it.")
    }

    // The disk refused as well. Another alarm, since this one is spent.
    Log.e(RELEASE_TAG, "Erasing the record without the service failed; retrying via a fresh alarm.")
    if (CapAlarm.armEraseRetry(
            context,
            System.currentTimeMillis() + RELEASE_RETRY_MS,
            recordStartedAtMillis,
        )
    ) {
        return
    }
    reportRecordStuck(context)
}

/**
 * Puts a snooze back on its feet with no service, after a reboot.
 *
 * The mirror of [releaseDirectly], and reached the same way: the cap alarm was
 * re-armed successfully but the service refused to start, so the snooze is
 * bounded but nothing has re-asserted the rule or replaced the ongoing
 * notification the reboot wiped. Left alone, the user has a phone that may or
 * may not be quiet, no countdown, and no `End now` — principle 2's failure with
 * the deadline still ticking.
 *
 * Re-asserting rather than assuming, exactly as `SnoozeController.restore`
 * does: the record surviving a reboot does not mean the platform's rule
 * condition did. And, also exactly as `restore` does, checking the clock
 * *first* — a record whose cap passed while the phone was off is already over,
 * and re-asserting it would silence the phone past the deadline it promised.
 */
private fun restoreDirectly(context: Context, snooze: ActiveSnooze) {
    Log.w(RELEASE_TAG, "The post-reboot service start was refused; restoring without it.")

    // The clock before the rule. The boot receiver arms the cap from the
    // record's original expiry, so an already-passed cap becomes an overdue
    // inexact alarm — which the platform delivers when it feels like it. Turning
    // DND on and waiting for that is a phone silenced *after* its cap, for an
    // interval nothing in the app controls. The snooze is simply over.
    if (snooze.isExpired(Instant.now())) {
        Log.w(RELEASE_TAG, "The record's cap passed while the phone was off; ending instead.")
        releaseDirectly(context, EndReason.DURATION_CAP)
        return
    }

    val zen = AndroidZenController(
        context = context.applicationContext,
        store = PrefsZenRuleIdStore(context.applicationContext),
        configurationActivity = ComponentName(context.applicationContext, MainActivity::class.java),
    )
    val notifications = SnoozeNotifications(context.applicationContext)

    val outcome = zen.setSnoozed(true, ZenTrigger.CONTEXT, snooze.placeName)
    if (outcome is ZenOutcome.NotApplied) {
        // Said either way — the user is not left guessing why their phone is
        // or isn't quiet after a restart.
        Log.e(RELEASE_TAG, "Re-asserting the rule after a reboot was refused.")
        notifications.showFailure(outcome.reason, whileArming = true)

        // Then the same distinction `SnoozeController.end` draws (SPEC.md §7),
        // which this fallback was missing. A refusal of a rule that still
        // exists is retryable, and the record plus its armed cap are what will
        // retry — so they stay. But access revoked, no rule, or a rule switched
        // off means nothing is silencing the phone and no retry can ever
        // succeed; keeping the record there would strand the app showing
        // `Snoozing` on the tile over a phone that is already ringing, right
        // up to the cap (§8.2).
        if (outcome.reason.nothingLeftToRelease) {
            releaseDirectly(context, EndReason.LOST_CAPABILITY)
            return
        }
        // Retryable, so the snooze is still running as far as the app is
        // concerned — and it therefore still needs its status surface. The
        // reboot took the old ongoing notification with it, so returning here
        // left a live record and an armed cap with nothing on screen: no
        // countdown, no `End now`, and only `Couldn't snooze` to read, which
        // says the opposite of what the record now claims. Both are posted, on
        // separate ids, so the failure explains itself while the snooze stays
        // legible and endable.
    }
    notifications.showOngoing(snooze)
    SnoozeTileBridge.refresh(context.applicationContext)
}

/**
 * Says that a released snooze's record could not be erased, when every
 * mechanism for erasing it has been tried.
 *
 * The record is still bounded — its cap is unchanged, so whatever restores it
 * later re-checks that cap and ends on the original schedule rather than
 * running on — but a bound is not the intended outcome, and a snooze that comes
 * back on its own is exactly the thing a user cannot reconstruct a reason for.
 * So it is said where they are looking rather than left in a log.
 */
private fun reportRecordStuck(context: Context) {
    Log.e(RELEASE_TAG, "No mechanism left to erase the record; its own cap still bounds it.")
    SnoozeNotifications(context.applicationContext).showNotForgotten()
}

private const val RELEASE_TAG = "SnoozeRelease"

/** Short, because the cap has already passed and the phone is still quiet. */
private const val RELEASE_RETRY_MS = 5 * 60 * 1000L

/**
 * Puts a snooze back together after a reboot (SPEC.md §8.1, §8.3).
 *
 * A reboot clears every scheduled alarm and does not restart a stopped service,
 * so without this a snooze survives in the record and in the platform's zen rule
 * while nothing in the app is left to end it — the phone stays quiet past its
 * cap, which is principle 1's failure.
 *
 * The cap alarm is re-armed here rather than left to the service, and first: it
 * needs nothing but the persisted record and an `AlarmManager` handle, so it
 * still lands if starting the service is refused. The cap continues from its
 * original expiry — a reboot does not extend a snooze — so a cap that passed
 * while the phone was off is already in the past and fires immediately.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val snooze = ActiveSnoozeStore(context).load() ?: return

        if (CapAlarm.arm(context, snooze.capExpiresAt.toEpochMilli())) {
            // Restoring re-asserts the rule and re-checks the cap; the service
            // does both in onCreate from the same record.
            //
            // A refused start is not the dead end the no-cap branch below is —
            // the cap is armed, so the snooze still ends on time — but it does
            // leave the rule unverified and the notification gone, wiped by the
            // reboot. Both of those are done here instead.
            if (!SnoozeService.restore(context)) restoreDirectly(context, snooze)
        } else {
            // No cap means no guaranteed exit, and the rule may well still be on
            // from before the reboot — so this ends the snooze rather than
            // restoring one that nothing is left to stop. Fail open (SPEC.md D7):
            // the rule is driven off explicitly rather than assumed cleared by
            // the reboot. And not via the service alone: there is no alarm left
            // here either, so a refused start would leave the same dead end the
            // cap receiver guards against.
            // LOST_CAPABILITY, not the cap: this reboot could not reschedule
            // the cap, which is why the snooze is ending early.
            if (!SnoozeService.endWithoutCap(context, snooze.startedAt.toEpochMilli())) {
                releaseDirectly(context, EndReason.LOST_CAPABILITY)
            }
        }
    }
}
