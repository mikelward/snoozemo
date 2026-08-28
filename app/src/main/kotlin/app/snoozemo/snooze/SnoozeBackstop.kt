package app.snoozemo.snooze

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.presence.pokePresenceSanity
import java.util.concurrent.TimeUnit

/**
 * The §6.10 periodic backstop: a coarse `WorkManager` wake every half hour
 * while a snooze is armed, purely to catch what the event-driven sources
 * missed (SPEC.md §6.10). Deferrable and batched — the cheap kind of
 * periodic (SPEC.md §9) — and never load-bearing: the duration cap is the
 * floor, armed by its own alarm; this bounds *staleness*, not the snooze.
 *
 * Each wake does two things through machinery that already exists: it
 * restores the service — cold, that is every repair a wake performs (re-arm
 * the cap, re-assert the rule, reconcile policy access, re-register the
 * fence, collect any held exit); warm, it re-checks the cap, repairs a
 * failed fence registration, and re-enqueues this schedule, deliberately
 * leaving the zen rule to the running service that owns it — and it asks
 * the presence monitor for one resting fix, so a departure the geofence
 * never reported gets tested by the §6.6 rule rather than waited out until
 * the cap. The probe also re-checks the location grants, which is what
 * makes a mid-snooze revocation detectable at this cadence.
 *
 * Scheduled beside the watch and canceled with it; a wake that finds no
 * record retires the schedule itself, so a cancel lost to process death
 * costs one empty wake, not a standing drain.
 */
internal object SnoozeBackstop {

    fun schedule(context: Context) {
        // Contained: the backstop is an extra layer, and a snooze without it
        // is still bounded by the cap — but a layer that failed to arm is
        // said, never assumed (AGENTS.md, *Error handling*).
        runCatching {
            val result = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP: re-arming an armed backstop would reset its period
                // and push the next wake out; the existing schedule is
                // exactly what a re-arm wants.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackstopWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                    // Without this the first run fires immediately — an extra
                    // restore and a second probe moments after the arm's own
                    // starting probe, against the 16-wake budget (Codex, PR
                    // #75). The first wake anything needs is one period out.
                    .setInitialDelay(PERIOD_MINUTES, TimeUnit.MINUTES)
                    .build(),
            ).result
            // The enqueue only submits; the persistence answer arrives on the
            // operation, which the catch below never sees — success is said
            // when it is known, not at invocation (Codex, PR #75).
            result.addListener(
                {
                    runCatching { result.get() }
                        .onSuccess {
                            scheduleRetriesLeft.set(SCHEDULE_RETRIES)
                            SnoozeDebugLog.event(
                                "backstop scheduled: every $PERIOD_MINUTES min while armed",
                            )
                        }
                        .onFailure { rejectScheduling(context, it) }
                },
                { it.run() },
            )
        }.onFailure { rejectScheduling(context, it) }
    }

    /**
     * A rejected enqueue gets a durable successor, not only a log line
     * (Codex, PR #75): the retry alarm restores the service, whose watch
     * start re-attempts this schedule. Deliberately *not* a user-visible
     * degradation — the event-driven fence is intact, so the snooze's mode
     * is still the truth; what failed is a recovery layer, and the cap
     * alarm, armed independently of WorkManager, still bounds the snooze.
     * Bounded so a persistently broken WorkManager cannot turn the ladder
     * into a once-a-minute wake loop; the counter refills on the next
     * successful schedule. The remaining budget rides the retry alarm
     * itself, because the in-process counter dies with the process: a death
     * between the rejection and the retry would refill it on the cold
     * restore, unbounding the loop after all (Codex, PR #75) — the receiver
     * adopts it back (see [adoptRetryBudget]) before restoring.
     */
    private fun rejectScheduling(context: Context, cause: Throwable) {
        Log.e(TAG, "Scheduling the periodic backstop failed; the cap alarm still bounds the snooze.", cause)
        SnoozeDebugLog.failure(cause, "backstop schedule refused; the cap still bounds the snooze")
        val prev = scheduleRetriesLeft.getAndUpdate { if (it > 0) it - 1 else 0 }
        if (prev > 0) {
            if (!CapAlarm.armPresenceRetry(context, RETRY_MS, attemptsLeft = prev - 1)) {
                // WorkManager *and* the alarm refused, so the ordinary two
                // rungs are both out — but the process running this callback
                // is alive, so its own handler is a real last rung, exactly
                // as the app's wake ladder uses one (Codex, PR #75). Each
                // re-attempt passes back through this method, so the same
                // per-snooze budget bounds it; a process death before it
                // fires leaves the cap alarm, armed independently, as the
                // floor.
                SnoozeDebugLog.warning("backstop retry alarm refused too; retrying in process")
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ schedule(context) }, IN_PROCESS_RETRY_MS)
            }
        } else {
            SnoozeDebugLog.warning("backstop schedule retries exhausted; the cap bounds the snooze")
        }
    }

    private val scheduleRetriesLeft = java.util.concurrent.atomic.AtomicInteger(SCHEDULE_RETRIES)

    /**
     * Adopts the retry chain's remaining budget from a fired retry alarm.
     * Min, never a refill: memory can only have spent *more* than the alarm
     * knew, and a successful schedule or a snooze's end refills either way.
     * This is what keeps the rejection ladder bounded across process death —
     * without it, each cold restore started the count over (Codex, PR #75).
     */
    internal fun adoptRetryBudget(attemptsLeft: Int) {
        scheduleRetriesLeft.getAndUpdate { minOf(it, attemptsLeft.coerceAtLeast(0)) }
    }

    /** The counter, for tests pinning the adoption rule. */
    internal fun retryBudget(): Int = scheduleRetriesLeft.get()

    /** The in-process rung's pause, mirroring the wake ladder's. */
    private const val IN_PROCESS_RETRY_MS = 30_000L

    /**
     * Cancels the schedule and, once the cancellation has settled, looks
     * again: **every** cancel can race an arm, not only the worker's
     * retirement (Codex, PR #75). An end followed by an immediate re-arm has
     * the new watch's `schedule()` run while the old snooze's cancellation
     * is still pending — KEEP sees the doomed work, no-ops, and the
     * cancellation lands last, leaving the fresh snooze without its
     * recovery layer. The settled re-read closes it for every call site: a
     * record present after the cancel means a snooze that wants a schedule,
     * and re-enqueueing one that already has it is free. The rare inverse —
     * the settle racing an *ending* snooze's not-yet-erased record —
     * re-schedules a doomed snooze's backstop, whose own next wake finds no
     * record and retires it: one wasted wake, self-healing.
     */
    fun cancel(context: Context) {
        cancelThen(context) {
            if (ActiveSnoozeStore(context).load() != null) schedule(context)
        }
    }

    /**
     * The cancel itself, with [onSettled] run once the operation's answer is
     * in — success or failure, synchronous or asynchronous — because the
     * re-check must not race the cancellation it just submitted.
     */
    private fun cancelThen(context: Context, onSettled: () -> Unit) {
        // The retry budget is per snooze, not per process (Codex, PR #75): a
        // snooze that exhausted it must not leave the *next* snooze's first
        // transient failure with no retry at all. Every cancel means a snooze
        // ended (or a stale schedule retired), so the refill lands here — and
        // never in schedule(), where each retry's own re-attempt would refill
        // the bound it is spending.
        scheduleRetriesLeft.set(SCHEDULE_RETRIES)
        runCatching {
            val result = WorkManager.getInstance(context).cancelUniqueWork(NAME).result
            // The answer arrives on the operation, same as the enqueue's
            // (Codex, PR #75). A backstop outliving its snooze is harmless —
            // the next wake finds no record and retires itself — so both
            // failure paths log rather than escalate.
            result.addListener(
                {
                    runCatching { result.get() }.onFailure(::rejectCancellation)
                    onSettled()
                },
                { it.run() },
            )
        }.onFailure {
            rejectCancellation(it)
            onSettled()
        }
    }

    private fun rejectCancellation(cause: Throwable) {
        Log.w(TAG, "Canceling the periodic backstop failed; its next wake retires it.", cause)
    }

    /**
     * Retires the schedule for a wake that found no snooze. The same
     * cancel-then-reconcile as every cancel (see [cancel]): a snooze armed
     * between the wake's empty read and the settled cancellation keeps its
     * backstop, because the re-read runs only after the cancellation's
     * answer is in.
     */
    fun retireStale(context: Context) = cancel(context)

    internal const val NAME = "snooze-backstop"

    /**
     * The coarse end of §6.10's 15–30 minute range: 16 deferrable wakes over
     * an 8-hour snooze, matching §9's "handful of wakeups" budget line, at
     * the cost of a ~30-minute *typical* staleness bound. Best-effort, not a
     * ceiling — the period defers in Doze and under battery saver — and the
     * deferral lands where it costs least: Doze needs a stationary device,
     * so the late wake is late while the user is still there, and leaving is
     * the motion that flushes it (SPEC.md §6.10). Reversible — one constant
     * (TODO.md, Decisions needing review).
     */
    internal const val PERIOD_MINUTES = 30L

    /** How soon the alarm ladder retries a refused backstop restore. */
    internal const val RETRY_MS = 60_000L

    /** Per-snooze bound on schedule-rejection retries; refilled on success. */
    internal const val SCHEDULE_RETRIES = 3

    private const val TAG = "SnoozeBackstop"
}

/**
 * One backstop wake. Synchronous and short: load the record, start the
 * service, poke the probe — every slow or fallible repair lives behind the
 * service's own tested paths, not here.
 */
internal class BackstopWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        if (ActiveSnoozeStore(applicationContext).load() == null) {
            // The snooze this schedule served is gone — ended on a path whose
            // cancel was lost, or the record was erased cold. Retire rather
            // than keep waking for nothing (SPEC.md §9); the retire re-checks
            // for a snooze armed mid-retirement.
            SnoozeDebugLog.event("backstop wake: no snooze; retiring the schedule")
            SnoozeBackstop.retireStale(applicationContext)
            return Result.success()
        }
        SnoozeDebugLog.event("backstop wake: reconciling the snooze")
        if (!SnoozeService.checkCap(applicationContext)) {
            // A worker's process is not an alarm receiver's start window, so
            // the refusal falls to the same ladder the presence wake uses:
            // the retry alarm restores from its own better-privileged window.
            SnoozeDebugLog.warning("backstop restore refused; arming a check-in to retry")
            if (!CapAlarm.armPresenceRetry(applicationContext, SnoozeBackstop.RETRY_MS)) {
                // Even the alarm refused: WorkManager's own backoff is the
                // last rung left standing.
                return Result.retry()
            }
            return Result.success()
        }
        // The warm half of the probe: a monitor already attached takes one
        // resting fix now. If the restore above is still starting, the poke
        // is dropped and the restored monitor takes its own starting probe —
        // either way exactly one probe per wake.
        pokePresenceSanity()
        return Result.success()
    }
}
