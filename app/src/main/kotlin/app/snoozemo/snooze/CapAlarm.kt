package app.snoozemo.snooze

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.mikelward.androidlog.safe
import app.snoozemo.core.endReason
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.identity
import app.snoozemo.core.Attempt
import app.snoozemo.core.ClockChange
import app.snoozemo.core.ClockChangeAction
import app.snoozemo.core.ClockReading
import app.snoozemo.core.EndReason
import app.snoozemo.core.RecordOrigin
import app.snoozemo.core.ReleaseEscalation
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.ReleaseProgress
import app.snoozemo.core.ReleaseStep
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenTrigger
import app.snoozemo.core.zenTrigger
import app.snoozemo.dnd.AndroidZenController
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
     * Arms the alarm for [snooze]'s cap, returning false if it could not be
     * scheduled.
     *
     * Takes the record rather than a deadline so the remaining time is worked
     * out from the record's own stored frame (`ActiveSnooze.remaining`) — a bare
     * deadline would have to be read against some clock chosen here, and picking
     * the wrong one is how a cap ends up measured in a frame it was never
     * written in.
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
    fun arm(
        context: Context,
        snooze: ActiveSnooze,
        now: ClockReading = SnoozeClock.read(),
    ): Boolean = armCheckIn(context, snooze.remaining(now).toMillis())

    /**
     * Arms a cap check [delayMillis] from now.
     *
     * Elapsed realtime, not wall time, and that is the whole point: this is the
     * cap's last line of defense, and an `RTC_WAKEUP` alarm slides with the
     * clock. Winding the date back moved this alarm out with it, so Do Not
     * Disturb stayed on past the backstop — reachable from Settings in two taps.
     * Elapsed realtime counts from boot across sleep and nothing can move it.
     *
     * Taking a *delay* rather than a deadline is also deliberate. Both callers
     * here already know how long they want to wait — the cap knows its remaining
     * time, the release retries know their interval — so nothing needs
     * converting into wall time and back, which is exactly where a frame gets
     * lost. `ACTION_CHECK_CAP` cannot tell the two apart, so it must not be what
     * decides which clock a number was written in.
     */
    fun armCheckIn(context: Context, delayMillis: Long): Boolean =
        schedule(
            context,
            SystemClock.elapsedRealtime() + delayMillis.coerceAtLeast(0L),
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SnoozeService.ACTION_CHECK_CAP,
        )

    /**
     * Arms a wake-up that retries **discarding** a record this device cannot
     * vouch for (SPEC.md §12).
     *
     * A fourth distinct action, and for the same reason as the other three:
     * what a wake-up means has to travel with it. This one cannot borrow the
     * release retries, because every one of them resolves its record through
     * `ActiveSnoozeStore.load()` — which refuses precisely the record this is
     * about. A retry that cannot see its own subject silently does nothing.
     *
     * No identity extra, unlike the others. Those carry one because they can
     * outlive their snooze and would otherwise act on whatever record replaced
     * it; this cannot, because the discard path re-reads the origin and does
     * nothing at all unless what it finds is still unvouchable. A snooze the
     * user armed in the meantime is stamped by this device and simply does not
     * match.
     *
     * **Elapsed realtime, unlike the other two retries** (Codex, PR #26). They
     * use `RTC_WAKEUP` and can afford to: the cap still stands behind them. This
     * one is different in exactly the way that matters — the record it is about
     * is refused by `load()`, so the cap check cannot adopt it and
     * `TimeChangedReceiver` cannot see it either. That makes this alarm the only
     * remaining exit from the silence, and an `RTC_WAKEUP` exit slides with the
     * wall clock: winding the clock back postpones it by the shift, with
     * nothing else scheduled to notice. Elapsed realtime counts from boot and
     * nothing can move it — the same reasoning as [armCheckIn], for the same
     * reason.
     */
    /**
     * Arms a wake-up that retries handing the ringer back (SPEC.md §5.9).
     *
     * The durable rung under a hand-back that failed every immediate attempt.
     * It exists because a completed release leaves nothing else watching: the
     * record is erased and every other alarm canceled, so the loan on its own
     * schedules nothing, and a phone left silent after a snooze it was told had
     * ended is principle 1's failure (Codex, PR #176).
     *
     * **Elapsed realtime**, for [armDiscardRetry]'s reason and more strongly:
     * this alarm is the only scheduled exit from that silence, and an
     * `RTC_WAKEUP` one slides with the wall clock — winding the clock back
     * would postpone it by the shift with nothing else left to notice.
     *
     * Carries no identity extra and needs none. It names no record and resolves
     * none; the loan is the whole subject, and a hand-back with no loan
     * outstanding does nothing at all.
     */
    fun armRingerRetry(context: Context, delayMillis: Long): Boolean =
        schedule(
            context,
            SystemClock.elapsedRealtime() + delayMillis.coerceAtLeast(0L),
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SnoozeService.ACTION_RINGER_RETRY,
        )

    fun armDiscardRetry(context: Context, delayMillis: Long): Boolean =
        schedule(
            context,
            SystemClock.elapsedRealtime() + delayMillis.coerceAtLeast(0L),
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SnoozeService.ACTION_DISCARD_RETRY,
        )

    /**
     * Arms a wake-up that retries **restoring** the snooze for a stranded
     * presence observation, after a background `startService` was refused.
     *
     * A fifth distinct action, and here the distinction has teeth: this retry
     * briefly borrowed `ACTION_CHECK_CAP`, whose receiver's no-service
     * fallback is `releaseDirectly(DURATION_CAP)` — the right last resort for
     * a spent cap alarm, and an end up to the whole cap early, under a reason
     * that never happened, for a retry armed a minute ago (Codex, PR #73).
     * Borrowing the cap's pending intent also *displaced* the real cap alarm
     * until a successful restore re-armed it. Its own action leaves the cap
     * alarm standing, so even a retry refused again on firing stays bounded.
     *
     * Elapsed realtime for the same reason as [armCheckIn]: the caller knows
     * the delay it wants, and a wall-clock alarm slides with the clock.
     *
     * [attemptsLeft] travels on the alarm so a firing whose service start is
     * *also* refused can re-arm itself, bounded: without it the "durable"
     * retry was spent in one attempt while its caller's budget still stood
     * (Codex, PR #75). Exhaustion rests on the cap, as ever.
     */
    fun armPresenceRetry(
        context: Context,
        delayMillis: Long,
        attemptsLeft: Int = PRESENCE_RETRIES,
    ): Boolean =
        schedule(
            context,
            SystemClock.elapsedRealtime() + delayMillis.coerceAtLeast(0L),
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SnoozeService.ACTION_RESTORE,
            retriesLeft = attemptsLeft,
        )

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
     * states both rather than this inferring one from the action — the cap and
     * the release retries share `ACTION_CHECK_CAP` and mean different clocks.
     */
    private fun schedule(
        context: Context,
        triggerAtMillis: Long,
        type: Int,
        action: String,
        recordStartedAtMillis: Long? = null,
        reason: EndReason? = null,
        retriesLeft: Int? = null,
    ): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return runCatching {
            alarmManager.setAndAllowWhileIdle(
                type,
                triggerAtMillis,
                pendingIntent(context, action, recordStartedAtMillis, reason, retriesLeft),
            )
            // That the cap was armed is one of §4.6's records — the alarm is
            // the exit that holds when everything else has failed, so "was one
            // ever scheduled" is the first question a stuck snooze raises.
            // Cheap enough for the arm path: an in-memory append, no IPC.
            SnoozeDebugLog.event("alarm armed: %s", safe(action))
            true
        }.getOrElse {
            // Never silent, and never assumed away: the caller aborts the arm on
            // this, and the user's phone is the thing at risk if it doesn't.
            Log.e(TAG, "Arming the $action alarm failed; refusing to snooze without it.", it)
            SnoozeDebugLog.failure(it, "alarm refused: %s", safe(action))
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
                // Dropped with the rest once a discard has actually finished.
                // Harmless if it were left — the retry re-reads the origin and
                // does nothing unless it finds another unvouchable record — but
                // a wake-up scheduled for work that is done is still a wake-up
                // the user's battery pays for (SPEC.md §9).
                existing(context, SnoozeService.ACTION_DISCARD_RETRY)?.let(alarmManager::cancel)
                // The presence retry likewise: a restore with no record to
                // restore does nothing, so leaving it armed only costs a wake.
                existing(context, SnoozeService.ACTION_RESTORE)?.let(alarmManager::cancel)
                // The ringer retry is deliberately **not** cancelled here, and
                // this is the one exception worth stating: every alarm above is
                // about a snooze, and `cancelAll` runs precisely because that
                // snooze is over — which is the very moment the ringer may still
                // be owed back (SPEC.md §5.9). Cancelling it would drop the
                // durable rung exactly when it is the only thing left.
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
        retriesLeft: Int? = null,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(action),
            intentFor(context, action).also { intent ->
                recordStartedAtMillis?.let {
                    intent.putExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, it)
                }
                reason?.let { intent.putExtra(SnoozeService.EXTRA_END_REASON, it.name) }
                retriesLeft?.let { intent.putExtra(SnoozeService.EXTRA_RETRIES_LEFT, it) }
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
        SnoozeService.ACTION_RESTORE -> REQUEST_PRESENCE_RETRY
        SnoozeService.ACTION_RINGER_RETRY -> REQUEST_RINGER_RETRY
        else -> REQUEST_CAP
    }

    private const val TAG = "CapAlarm"

    /** How many times a fired presence retry may re-arm itself when refused. */
    internal const val PRESENCE_RETRIES = 3

    /** The pause between a refused presence-retry firing and its re-arm. */
    internal const val PRESENCE_RETRY_MS = 60_000L

    private const val REQUEST_CAP = 1
    private const val REQUEST_ERASE_RETRY = 2
    private const val REQUEST_RELEASE_RETRY = 3
    private const val REQUEST_PRESENCE_RETRY = 4
    private const val REQUEST_RINGER_RETRY = 5
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
        // Which wake-up fired, and when — the alarm half of "did the cap fire,
        // and did the alarm or the in-service check get there first" (§4.6).
        SnoozeDebugLog.event("alarm fired: %s", safe(intent?.action))
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
        if (intent?.action == SnoozeService.ACTION_RINGER_RETRY) {
            // No service and no thread, unlike every branch above. The
            // hand-back is a preferences read and one `AudioManager` call, and
            // it re-checks the record itself under the ringer lock — so it needs
            // neither a snooze to resolve nor a foreground start window. A
            // no-op if any of the other checks has handed the loan back since.
            //
            // Inline rather than handed to a thread, which is the *more*
            // reliable of the two here: a broadcast keeps its process alive only
            // for `onReceive`, so an unowned thread can be killed halfway, and
            // this alarm is the last scheduled exit from a silent phone —
            // half-finished is the one outcome it must not have. `goAsync` would
            // hold the process instead, but it has no pending result to hand
            // back when a receiver is invoked directly, so the safe form of it
            // is a null check around the very thing that guarantees completion.
            // A few milliseconds on the receiver's thread, against ~10 seconds
            // of budget, buys the guarantee outright.
            handBackRingerNow(context)
            return
        }
        if (intent?.action == SnoozeService.ACTION_DISCARD_RETRY) {
            // A record this device cannot vouch for whose rule would not release
            // last time. Re-enters the discard rather than any of the ordinary
            // release paths, which cannot see this record at all — see
            // `armDiscardRetry`. Re-reads the origin first, so a snooze the user
            // has armed since is left entirely alone.
            val store = ActiveSnoozeStore(context)
            val origin = store.originOfStored()
            if (origin != null && !origin.mayRestore) {
                discardForeignRecord(context, store, origin)
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
        if (intent?.action == SnoozeService.ACTION_RESTORE) {
            // A presence retry, not a cap: it never displaces the cap alarm,
            // which is still armed, so a refusal here must never end anything
            // early (Codex, PR #73). It re-arms itself instead, bounded by
            // the attempts the arm gave it — one spent firing burned the
            // whole "durable" retry while its caller's budget still stood
            // (Codex, PR #75) — and exhaustion rests on the cap, as ever.
            val left = intent.getIntExtra(SnoozeService.EXTRA_RETRIES_LEFT, 0)
            // Adopted *before* the restore: the backstop's enqueue-retry
            // counter dies with the process, so a restore whose schedule is
            // rejected again in a fresh process would otherwise spend a
            // refilled budget every time — a persistently broken WorkManager
            // plus a death per alarm made the bounded ladder a wake per
            // minute for the whole snooze (Codex, PR #75).
            SnoozeBackstop.adoptRetryBudget(left)
            if (!SnoozeService.restore(context)) {
                if (left > 0) {
                    SnoozeDebugLog.warning("presence retry refused again; re-arming (%s left)", left)
                    rearmPresenceRetry(context, attemptsLeft = left - 1)
                } else {
                    SnoozeDebugLog.warning("presence retries exhausted; the cap bounds the snooze")
                }
            }
            return
        }
        if (SnoozeService.checkCap(context)) return
        releaseDirectly(context, EndReason.DURATION_CAP)
    }
}

/**
 * Re-arms the presence retry alarm with [attemptsLeft] on it, falling to an
 * in-process rung when even the alarm is refused — the same ladder the app's
 * wake path and the backstop's scheduler use, because a dropped refusal here
 * discarded the remaining budget with no successor (Codex, PR #75). The
 * in-process attempt retries the restore itself and, refused again, re-enters
 * this ladder one attempt down, so the same per-arm budget bounds every rung.
 * A process death before it fires leaves the cap alarm, still armed, as the
 * floor.
 */
private fun rearmPresenceRetry(context: Context, attemptsLeft: Int) {
    if (CapAlarm.armPresenceRetry(context, CapAlarm.PRESENCE_RETRY_MS, attemptsLeft = attemptsLeft)) {
        return
    }
    SnoozeDebugLog.warning("presence retry alarm refused too; retrying in process")
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        {
            if (!SnoozeService.restore(context)) {
                if (attemptsLeft > 0) {
                    SnoozeDebugLog.warning(
                        "presence retry refused again; re-arming ($attemptsLeft left)",
                    )
                    rearmPresenceRetry(context, attemptsLeft - 1)
                } else {
                    SnoozeDebugLog.warning("presence retries exhausted; the cap bounds the snooze")
                }
            }
        },
        IN_PROCESS_PRESENCE_RETRY_MS,
    )
}

/** The in-process rung's pause, mirroring the wake ladder's. */
private const val IN_PROCESS_PRESENCE_RETRY_MS = 30_000L

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
 * The real zen controller, built the same way for every receiver path here.
 *
 * A function rather than four copies of the constructor call, and a defaulted
 * parameter on each of those paths rather than a field, for one reason: every
 * one of them has a branch that only runs when the platform *refuses* to change
 * the rule, and those branches are where this file's worst bugs have lived
 * (PR #26 found three in a single new one). A refusal cannot be provoked
 * through a real `AndroidZenController`, so before this they could only be
 * checked by reading them.
 *
 * Defaulted, so no caller passes anything and no production behavior moves;
 * the parameter exists for `RefusingZen` and nothing else.
 */
private fun androidZen(context: Context): ZenController = AndroidZenController.default(context)

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
internal fun releaseDirectly(
    context: Context,
    reason: EndReason,
    zen: ZenController = androidZen(context),
): Boolean {
    val store = ActiveSnoozeStore(context)
    val snooze = store.load()

    // Why this release is being attempted, recorded before attempting it — the
    // same marker the service's own release path writes (SPEC.md §5.8). This
    // path used to write none (Codex, PR #36): a cap or capability-driven
    // release that turned the rule off here and died before the erase left a
    // live record over an off rule with no durable cause, read back as the
    // user's own doing, silently. Best-effort, like the service's: a missing
    // marker costs attribution, never the release.
    // Only for the endings the app decided on, as `SnoozeController.end`
    // gates it (Codex, PR #194): a manual ending needs no marker — losing one
    // to a crash falls back to "the user turned Do Not Disturb off", which is
    // equally silent and equally the user's — and this path is where the
    // trampoline sends the user's own `End now` when the service refuses to
    // start, so a disk write here would sit between their tap and their
    // phone making noise again.
    if (snooze != null && reason.zenTrigger() == ZenTrigger.CONTEXT) {
        val marked = runCatching { store.markReleasing(reason) }
            .onFailure { Log.w(RELEASE_TAG, "Recording the release reason failed; continuing.", it) }
            .getOrDefault(false)
        if (!marked) {
            SnoozeDebugLog.warning("release reason not recorded; a crash before cleanup would misread this ending")
        }
    } else if (snooze != null) {
        // The other half, mirroring `SnoozeController.end` (Codex, PR #197):
        // the user's ending supersedes a reason a refused contextual release
        // left standing, which would otherwise explain this ending instead of
        // them. Read first, so the tap pays a map lookup and not a write
        // unless there is really something to retire.
        val pending = runCatching { store.releasingReason() }
            .onFailure { Log.w(RELEASE_TAG, "Reading the release reason failed; leaving it.", it) }
            .getOrNull()
        if (pending != null) {
            runCatching { store.retireReleasing() }
                .onFailure { Log.w(RELEASE_TAG, "Retiring the superseded release reason failed.", it) }
            SnoozeDebugLog.event("user ending supersedes a pending release ($pending); reason retired")
        }
    }

    val outcome = zen.setSnoozed(
        snoozed = false,
        // The one mapping, shared with `SnoozeController.end` rather than
        // restated here (Codex, PR #36): a second copy of the rule went stale
        // exactly as a second copy does — it kept crediting a user's own Do Not
        // Disturb toggle to the app once a later fix made this path reach it.
        // The rest of the reasoning stands: this path is the
        // no-service stand-in for it, and the trampoline routes the user's own
        // `End now` here whenever the service refuses to start — so a
        // hard-coded `CONTEXT` credited that tap to the app deciding by
        // itself in the platform's Modes UI, contrary to SPEC.md §5.4.
        trigger = reason.zenTrigger(),
        placeName = snooze?.placeName ?: ActiveSnooze.DEFAULT_PLACE_NAME,
        // A refused release re-quiets, and the re-quiet must reuse this
        // snooze's own ceiling record rather than trust whatever is there
        // (Codex, PR #192).
        snooze = snooze?.identity,
    )
    val released = outcome is ZenOutcome.Applied ||
        (outcome is ZenOutcome.NotApplied && outcome.reason.nothingLeftToRelease)
    // The no-service exits are exactly the ones that leave no other trace — the
    // service never ran, so no transition was recorded (§4.6).
    SnoozeDebugLog.event("no-service release (%s): released=%s", reason, released)
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
        // not which path turned it off. The `trying again` card comes down with
        // them — this release is the retry it promised.
        notifications.cancelStuckRule()
        notifications.cancelEndFailure()
        PendingFailureStore(context).run { if (ruleMayBeStuck()) clearRuleStuck() }

        val erased = store.clear()
        if (!marked && !erased) {
            // Neither write landed, so the record on disk is released and
            // doesn't say so — a later restore reads it as live and turns DND
            // back on until the old cap. Nothing else here can fix that; the
            // retry below is the only thing that will, and the cap bounds it.
            Log.e(RELEASE_TAG, "The record could not be marked released; a restore may resurrect it.")
        }
        SnoozeTileBridge.refresh()
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
    // The marker stays, as it does on the service's own refusal (SPEC.md §5.8;
    // maintainer, 2026-09-05): the escalation below pursues this same reason,
    // so the marker names the ending still being executed rather than one
    // that never happened.
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
internal fun restoreDirectly(
    context: Context,
    snooze: ActiveSnooze,
    zen: ZenController = androidZen(context),
) {
    Log.w(RELEASE_TAG, "The post-reboot service start was refused; restoring without it.")

    // The clock before the rule. The boot receiver arms the cap from the
    // record's original expiry, so an already-passed cap becomes an overdue
    // inexact alarm — which the platform delivers when it feels like it. Turning
    // DND on and waiting for that is a phone silenced *after* its cap, for an
    // interval nothing in the app controls. The snooze is simply over.
    if (snooze.isExpired(SnoozeClock.read())) {
        Log.w(RELEASE_TAG, "The record's cap passed while the phone was off; ending instead.")
        // Forwarded, not rebuilt: one controller per receiver invocation. Two
        // would mean this path's release ran against a different object than
        // the one that had just been asked about the same rule — harmless in
        // production, and the reason the branch was untestable.
        releaseDirectly(context, EndReason.DURATION_CAP, zen)
        return
    }

    // Then what the rule is actually doing, before re-asserting it and
    // destroying the evidence — the same order `SnoozeService` takes, and
    // missing here (Codex, PR #36). This fallback runs precisely when no
    // process was alive to hear the status broadcast, so it is the *likeliest*
    // path to meet a user who turned Do Not Disturb off while the app was
    // gone. Re-asserting first would silence their phone again, which is the
    // one thing an explicit instruction from the user must never get.
    // The record's own rule, for the reason the service reads it (SPEC.md
    // §5.8): a replacement minted since the arm must not stand in for it.
    val inferred = runCatching { zen.ruleActivation(snooze.ruleId).endReason() }.getOrElse {
        // Unreadable ends nothing: a failed read must not be the reason a
        // snooze is dropped, and the cap still bounds it either way.
        Log.w(RELEASE_TAG, "Reading the rule state after a reboot failed; restoring anyway.")
        null
    }
    // An off rule with a live record has two explanations, and the read-back
    // cannot tell them apart: the user turned Do Not Disturb off, or an
    // app-decided release turned it off and died before erasing the record.
    // The marker that release wrote is the only thing that knows, and it
    // overrides the *user* inference alone — a missing rule is a lost
    // capability whoever was releasing it. The same preference the service's
    // read-back gives it (SPEC.md §5.8); this fallback used to skip it, so a
    // cap or a departure that had already ended the snooze was reported as
    // the user's doing, the one ending kept silent (Codex, PR #197).
    val reason = when (inferred) {
        EndReason.DND_TURNED_OFF ->
            runCatching { ActiveSnoozeStore(context).releasingReason() }
                .onFailure { Log.w(RELEASE_TAG, "Reading the release reason failed; inferring the ending.", it) }
                .getOrNull() ?: inferred
        else -> inferred
    }
    // Gated on the arm having completed, the same way the service path gates its
    // own version (Codex, PR #36). A record written *before* its rule ever went
    // on is an interrupted arm, not a user switching Do Not Disturb off — and an
    // off rule looks identical from here. Ending on that inference would erase a
    // snooze that only needed finishing, so an unfinished arm falls through and
    // is re-asserted below instead.
    if (reason != null && snooze.armed) {
        Log.w(RELEASE_TAG, "The rule is no longer on; ending instead of re-asserting.")
        releaseDirectly(context, reason, zen)
        return
    }

    val notifications = SnoozeNotifications(context.applicationContext)

    val outcome = zen.setSnoozed(true, ZenTrigger.CONTEXT, snooze.placeName, snooze.identity)
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
            releaseDirectly(context, EndReason.LOST_CAPABILITY, zen)
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
    // Deliberately NOT recording that the arm completed here (Codex, PR #36).
    // Writing it looked like the obvious mirror of `SnoozeController.restore`,
    // and three review rounds found three different ways for it to be wrong in
    // this path: marked on a refusal that never activated anything, and — with
    // that fixed — a write that fails leaves the rule on with disk still saying
    // the arm never finished, which a later wake re-asserts over a user who has
    // since switched Do Not Disturb off. Getting it right needs a durable retry
    // or a rollback of the activation, which is more machinery than this
    // no-service fallback should carry. The gap it would have closed is
    // recorded in TODO.md with the rest of the record's ownership story, and it
    // is the state this path was already in.
    notifications.showOngoing(snooze)
    SnoozeTileBridge.refresh()
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
 * Re-checks the cap when the user sets the clock (SPEC.md §7).
 *
 * The cap alarm counts in elapsed realtime so that winding the clock *back*
 * cannot push it out. The cost of that is the other direction: a clock moved
 * forward past the deadline does not move the alarm either, so the snooze would
 * run to its original real duration while the ongoing notification — which
 * ticks against wall time — sat at zero. The user reads a countdown that
 * finished and a phone that is still silent, with nothing explaining the gap.
 * That is principle 2's failure, so the clock change itself is the wake-up.
 *
 * Cheap by construction: this fires only when something actually sets the
 * clock, so it costs nothing on an undisturbed phone and adds no polling to
 * SPEC.md §9's battery budget.
 *
 * **This receiver re-arms only after a successful restate, and never from the
 * record as found.** Re-arming recomputes the delay from the record, which is
 * only trustworthy while the record's stored offset describes this boot — and
 * as found it may not: the boot receiver can fail to write it, and a phone
 * rebooted and left locked never runs the boot receiver at all (`TODO.md`).
 * Against a stale offset a *backwards* change makes the recomputed delay longer
 * than the one already armed, so a re-arm there would replace a correct alarm
 * with an overlong one and hand the user back the exact overrun this change
 * exists to remove.
 *
 * Immediately after a restate has reached disk the trap cannot apply: the
 * frame was written this instant, so the recomputed delay is the true
 * remaining, and re-arming is what pulls the alarm in after a *forward* jump —
 * closing the window where the countdown read zero over a phone still silent
 * until the original elapsed delay ran out (SPEC.md §7). The end-only rule
 * still governs every other path through here.
 *
 * What it decides is in [ClockChange], and deliberately not here: the running
 * service performs the same decision when it can be started, and this performs
 * it when it cannot. Two hand-written copies would drift, and the half that
 * drifted would be the no-service one, which no device reaches on purpose.
 */
class TimeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // A **timezone** change moves no deadline — every alarm here counts in
        // elapsed realtime and every stored instant is absolute — so none of
        // the reconciliation below applies. What it does move is the one piece
        // of text the app formats into a local time and then leaves on screen
        // for hours: the ongoing card's `Until 17:00` action (SPEC.md §4.3).
        // Flying with a snooze armed would otherwise leave that button naming
        // a wall-clock time the phone no longer agrees with, while the end it
        // actually sets is correct — the quietly-wrong outcome principle 2
        // ranks second worst (Codex, PR #156).
        //
        // A repost is the whole fix: the offer is cached as an `Instant`, which
        // no timezone touches, so rebuilding the card re-formats it in the zone
        // now in force. Nothing to do when the start is refused — the label is
        // cosmetic, and the next state change rebuilds it anyway.
        if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
            if (ActiveSnoozeStore(context).load() != null) SnoozeService.refresh(context)
            return
        }
        if (intent?.action != Intent.ACTION_TIME_CHANGED) return
        val store = ActiveSnoozeStore(context)
        val snooze = store.load() ?: return

        // Resolved here rather than handed straight to the service, so a clock
        // that was set to the time it already read costs one preferences read
        // and nothing else — no service start, no zen call, no notification.
        if (ClockChange.resolve(snooze, SnoozeClock.read()) == ClockChangeAction.None) return

        // Then the service, wherever it will start, because it owns the second
        // copy of this snooze. The controller's copy is what `+30 min` derives
        // its update from, so a record repaired behind a live service's back is
        // one the next extension writes the pre-change deadline back over — the
        // repair undone by the button beside it. The service re-resolves from
        // its own record and its own reading; this call only decides *who*
        // performs it.
        if (SnoozeService.clockChanged(context)) return

        // Refused, so there is no controller to keep in step and this broadcast
        // is the only thing that will act on the change — the same fallback
        // every other cap path keeps for the same reason. Resolved again rather
        // than reusing the answer above: the service start is an IPC, and the
        // whole subject here is a clock that moves between readings.
        performClockChange(context, store, ClockChange.resolve(snooze, SnoozeClock.read()))
    }
}

/**
 * Performs a clock change with no service, the direct twin of
 * `SnoozeService.reconcileClock`.
 */
private fun performClockChange(
    context: Context,
    store: ActiveSnoozeStore,
    action: ClockChangeAction,
) {
    when (action) {
        // The cap is due as of this reading. Ending is all that is owed — a
        // record about to be erased does not need its frames restated.
        ClockChangeAction.EndAtCap -> releaseDirectly(context, EndReason.DURATION_CAP)

        // Nothing left to read the deadline against but the clock that just
        // moved, so how long is really left is unknowable and the snooze ends
        // (SPEC.md §7, D7). `LOST_CAPABILITY` rather than the cap: this is not
        // a snooze that ran its time, it is one whose bound stopped being
        // legible.
        ClockChangeAction.EndUnframed -> {
            Log.w(RELEASE_TAG, "The clock moved under a record with no offset; ending rather than guessing.")
            releaseDirectly(context, EndReason.LOST_CAPABILITY)
        }

        is ClockChangeAction.Restate -> restateDirectly(context, store, action.snooze)

        ClockChangeAction.None -> Unit
    }
}

/**
 * Writes a restated record back with no service, and ends the snooze if that
 * write does not land.
 *
 * The write is the whole repair: after a backwards change, uptime is the only
 * frame that knows how long is really left, and it lives in memory as the gap
 * between the stored offset and the current uptime — which the next boot
 * destroys. The boot would then find an untouched, now-inflated wall deadline,
 * adopt it, and hold Do Not Disturb open by the size of the shift. Folding the
 * remaining time back into wall time, while wall time can still be read, is
 * what carries it across (SPEC.md §7).
 *
 * A refused write gets the same answer as a reboot that cannot restate its
 * offset, and for the same reason: the record on disk asserts a deadline that
 * is wrong in the dangerous direction and every later reader believes it. The
 * armed alarm is still correct, so this only bites after a reboot — but nothing
 * here can make it not bite, and a bound that depends on the phone never
 * restarting is not a bound. Fail open (SPEC.md D7).
 */
private fun restateDirectly(
    context: Context,
    store: ActiveSnoozeStore,
    restated: ActiveSnooze,
) {
    if (!store.update(restated)) {
        Log.e(RELEASE_TAG, "The clock change could not be recorded; ending rather than trusting the old deadline.")
        releaseDirectly(context, EndReason.LOST_CAPABILITY)
        return
    }

    // The service performer's twin: re-arm from the record whose frame the
    // write above just made fresh, so a forward change pulls the alarm in to
    // where the countdown now points (SPEC.md §7). The stale-offset trap the
    // receiver's never-re-arm rule guards against cannot reach this line — an
    // armed alarm implies an offset restated for this boot (every armer wrote
    // the frame it used, and a boot that cannot restate ends the snooze), and
    // where the offset really is stale no alarm survived the reboot at all,
    // so even an overlong re-arm adds a bound where none exists. A refusal
    // ends the snooze, the same answer as the refused save above: the record
    // now promises a deadline no scheduled alarm honors, and after a forward
    // jump the countdown would sit at zero over a silent phone with only a
    // log saying why (flagged by Codex on PR #63).
    if (!CapAlarm.arm(context, restated)) {
        Log.e(RELEASE_TAG, "Re-arming the cap after the clock change was refused; ending rather than letting the countdown lie.")
        releaseDirectly(context, EndReason.LOST_CAPABILITY)
        return
    }

    // The countdown, which the clock change has just falsified. The platform
    // ticks a chronometer against the *wall* clock from the instant the
    // notification was posted in, so after a backwards change it reads the
    // shift too long — a snooze that appears to have hours left over a phone
    // that is about to come back on, which is the reverse of the failure this
    // receiver was written for and just as unexplainable. Re-posting anchors it
    // in the clock the user is now on. The tile's subtitle is stale in exactly
    // the same way.
    SnoozeNotifications(context.applicationContext).showOngoing(restated)
    SnoozeTileBridge.refresh()
}

/**
 * Throws away a snooze record this device cannot vouch for, and makes sure Do
 * Not Disturb is not on because of it (SPEC.md §12).
 *
 * Two arrivals, and the difference between them decides what the user is told.
 * [RecordOrigin.ANOTHER_DEVICE] is a record carried across by a device-to-device
 * transfer despite `res/xml/data_extraction_rules.xml` — a snooze this phone's
 * owner never started. [RecordOrigin.UNATTRIBUTED] is most likely *their own*
 * snooze, written by a build from before stamping and met by an update.
 *
 * **The rule is driven off before anything is thrown away, and the record only
 * goes if that worked** (Codex, PR #26). An earlier version cleared the record
 * and canceled the cap regardless of the outcome, which on a refused
 * `setSnoozed(false)` left a phone silent with every durable obligation to
 * un-silence it deleted — principle 1's worst case, produced by the cleanup
 * meant to prevent it. On a refusal this keeps the record *and* its cap and
 * hands the obligation to the release ladder (§7.1), which is the machinery
 * that already knows how to carry one.
 */
internal fun discardForeignRecord(
    context: Context,
    store: ActiveSnoozeStore,
    origin: RecordOrigin,
    // Injected so the refused-release branch below is reachable from a test
    // (Codex, PR #26). It owns the record, the stuck-rule obligation, the
    // notification and the only retry, so "covered by inspection" was not good
    // enough for it. Defaulted rather than threaded through every caller: the
    // receivers should not have to know how a zen controller is built.
    zen: ZenController = androidZen(context),
) {
    Log.w(
        RELEASE_TAG,
        "A snooze record this device cannot vouch for ($origin) is on disk; discarding it.",
    )

    // Read past the refusal deliberately: `load()` hides this record from every
    // ordinary caller, and this is the one path that has to see it — to name it
    // in a retry, and to know whether a notification was ever posted for it.
    val stranded = store.readUnverified()
    val outcome = zen.setSnoozed(
        snoozed = false,
        trigger = ZenTrigger.CONTEXT,
        placeName = stranded?.placeName ?: ActiveSnooze.DEFAULT_PLACE_NAME,
        snooze = stranded?.identity,
    )
    val released = outcome is ZenOutcome.Applied ||
        (outcome is ZenOutcome.NotApplied && outcome.reason.nothingLeftToRelease)

    if (!released) {
        // Nothing is cleared and nothing is canceled: the record and its cap are
        // the only things left that can end this silence.
        //
        // **The ordinary release ladder cannot carry this one** (Codex, PR #26).
        // An earlier version handed it to `escalateWithoutService`, whose
        // successors all resolve the record through `load()` — and `load()` is
        // exactly what refuses this record. The `ACTION_CAP_LOST` retry would
        // reach a service that reads null, treats the release as recordless,
        // and never clears the record, cap, or stale notification;
        // `releaseDirectlyIfStillOurs` would read null, conclude the retry was
        // stale, and drop it. Arming either also suppresses the stuck-rule
        // card, so the phone could stay silent to its original cap with nothing
        // on screen saying so. The obligation has to stay on a path that can
        // still see the record, which means this one.
        Log.e(RELEASE_TAG, "The stranded snooze's rule would not release; retrying the discard itself.")

        // The durable half first, before the alarm: if this process dies here,
        // the next start still knows the rule may be on (SPEC.md §7.1).
        PendingFailureStore(context).rememberRuleMayBeStuck()
        // And say so, rather than leaving a silent phone with nothing to act on.
        // The ordinary ladder suppresses this card while a retry is pending,
        // which is right when the retry can actually finish the job; here it
        // cannot be relied on to, so the user gets the card *and* the retry.
        SnoozeNotifications(context.applicationContext).showStuckRule()

        if (!CapAlarm.armDiscardRetry(context, RELEASE_RETRY_MS)) {
            Log.e(RELEASE_TAG, "The discard retry could not be armed; the stuck-rule card is what is left.")
        }
        return
    }

    val notifications = SnoozeNotifications(context.applicationContext)
    // An update does not take the ongoing notification down, so without this it
    // keeps saying `Snoozing` over a record that is gone, offering an `End now`
    // with nothing to end (Codex, PR #26).
    //
    // Which one the user gets turns on whose snooze it was. `UNATTRIBUTED` is
    // most likely theirs, started on this phone before the update — they saw it
    // begin, so they are owed the ending. `ANOTHER_DEVICE` is a snooze from a
    // handset they may not even still own: `Snooze ended` there describes
    // something that never happened here and sends them looking for a fault
    // that isn't one, so the stale card comes down without a replacement.
    if (origin == RecordOrigin.UNATTRIBUTED && stranded != null) {
        notifications.showEnded(EndReason.LOST_CAPABILITY)
    } else {
        notifications.cancelOngoing()
    }
    notifications.cancelStuckRule()
    notifications.cancelEndFailure()
    PendingFailureStore(context).run { if (ruleMayBeStuck()) clearRuleStuck() }

    CapAlarm.cancelAll(context)
    if (!store.clear()) {
        // Not a dead end: `load()` refuses this record on every future read, so
        // the snooze cannot come back. It just means the same discard runs again
        // at the next boot or update, which is harmless and self-healing.
        Log.w(RELEASE_TAG, "The stranded snooze record could not be erased; it stays refused either way.")
    }
    SnoozeTileBridge.refresh()
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in HANDLED_ACTIONS) return
        SnoozeDebugLog.event("boot/update wake-up: %s", safe(intent?.action))

        // Inline, and before the early returns below, because this receiver's
        // process may be all there is (Codex, PR #176). `Application.onCreate`
        // starts the same check on its own thread, but a process spun up only
        // to deliver this broadcast can be killed the moment `onReceive`
        // returns — and a reboot has already cleared the retry alarm, so for a
        // loan left by a refused hand-back this is the recovery, not a backup
        // for it. Cheap enough to own: a preferences read and at most one
        // `AudioManager` call, and a no-op when a snooze is running or nothing
        // is outstanding.
        handBackRingerNow(context)

        val store = ActiveSnoozeStore(context)

        // Before anything else, because these are the two moments it matters:
        // the first boot after a device-to-device transfer, and the first run
        // after an update that introduced stamping over a snooze already
        // running.
        //
        // `load()` already refuses a record this device cannot vouch for, so
        // nothing below would restore it — but a refusal on its own is not a
        // fix. It leaves the record on disk, the zen rule possibly still on,
        // and the snooze invisible to the UI and to `End now`, which is a
        // silence the user cannot see or stop (Codex, PR #26). Only this path
        // knows the record is *unusable* rather than merely absent, which is
        // what makes it the one place that can drive the rule off and clear it.
        val origin = store.originOfStored()
        if (origin != null && !origin.mayRestore) {
            discardForeignRecord(context, store, origin)
            return
        }

        val stored = store.load() ?: return

        // The offset first, before the alarm and before the rule. A reboot has
        // just reset uptime, so the record's stored offset describes a boot that
        // no longer exists and the deadline has only the wall clock left to be
        // read against — which is fine until the wall clock moves, and then
        // nothing catches it. Restating it here is what gives this boot a frame
        // the user cannot wind backwards (SPEC.md §7).
        val now = SnoozeClock.read()
        val snooze = stored.rebasedOnto(now)
        if (!store.update(snooze)) {
            // The same answer as a cap that cannot be scheduled below, and for
            // the same reason: what is left is a record whose offset describes a
            // boot that no longer exists, and every later reader believes it.
            // The cap is computed from the smaller of the two clocks, so a stale
            // offset plus a backwards change leaves *both* answers too high —
            // and a re-arm off that record then schedules the cap past its own
            // deadline rather than at it.
            //
            // An earlier version logged this and carried on, reasoning that a
            // stale offset merely degrades to wall-clock-only. That was wrong:
            // it degrades to something worse than wall-clock-only, because the
            // stale offset is still trusted. Nothing here can restore a
            // trustworthy frame — the write is the only way to keep one — so
            // this ends the snooze rather than running one whose bound cannot be
            // relied on. Fail open (SPEC.md D7).
            Log.e(RELEASE_TAG, "The reboot could not restate the clock offset; ending rather than trusting it.")
            if (!SnoozeService.endWithoutCap(context, stored.startedAt.toEpochMilli())) {
                releaseDirectly(context, EndReason.LOST_CAPABILITY)
            }
            return
        }

        if (CapAlarm.arm(context, snooze, now)) {
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

    private companion object {
        /**
         * A reboot and an app update, handled identically.
         *
         * Both leave a record on disk with no process alive to act on it, and
         * the repair is the same in each case — restate the clock frame, re-arm
         * the cap, re-assert the rule — so sharing the path is what keeps the
         * update case from being the one nobody exercises.
         *
         * `ACTION_MY_PACKAGE_REPLACED` matters here beyond the general tidiness
         * (Codex, PR #26): an update is the one moment a snooze can outlive the
         * build that armed it, so it is exactly when a record written before
         * stamping meets a build that expects one. Without this, such a record
         * reads as absent to every caller while the zen rule stays on — a
         * silence with no way to see or end it — until the cap eventually
         * fires. It is a protected broadcast, delivered only to the app that
         * was replaced, and is exempt from the implicit-broadcast restrictions,
         * so a plain manifest receiver is all it needs.
         *
         * `rebasedOnto` is a no-op on this path rather than a hazard: uptime has
         * not reset, so the reading it stamps in is the one already stored.
         */
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
