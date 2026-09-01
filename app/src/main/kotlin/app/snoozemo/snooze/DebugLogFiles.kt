package app.snoozemo.snooze

import android.content.Context
import android.os.Build
import android.util.Log
import app.snoozemo.core.SnoozeDebugLog
import com.mikelward.androidlog.android.DebugFileSink
import com.mikelward.androidlog.android.PreviousRun
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * How long [DebugLogging.readPreviousOrCrash] waits for a previous-run read
 * before reporting that it could not be read.
 *
 * A quarter of the library's own bound, deliberately. This one is in front of
 * a user who has asked to share their log, so the failure needs to arrive
 * while they are still watching; the library's has to be generous enough never
 * to fire on a slow device doing nothing wrong, since it answers a caller that
 * cannot tell a timeout from "there is nothing to send". Here that ambiguity
 * does not arise -- a timeout becomes `readSucceeded = false`.
 */
private const val PREVIOUS_RUN_READ_TIMEOUT_SECONDS = 5L

private const val TAG = "SnoozemoDebugLog"

/**
 * Where this app's own file sink used to write, before the move to the shared
 * library. Nothing reads it now — the library writes elsewhere, under its own
 * names — so it exists only to be deleted once. See
 * `DebugLogging.purgeLegacyDirectory`.
 */
private const val LEGACY_DIR_NAME = "debuglog"

internal class DebugLogStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    /**
     * Persists the choice, returning whether the write reached disk.
     *
     * On a refused write the old value is put back first: `commit()` applies
     * the change to the process-local map *before* the disk write it reports
     * on, so without the restore every later read would return a value that
     * was neither applied nor stored — a switch reading `off` over a log
     * still recording, until a process restart flipped it back (flagged by
     * Codex on PR #68). The restore's own disk write may fail too; the map is
     * restored regardless, which is the part every reader sees.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val before = isEnabled()
        val persisted = prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
        if (!persisted) {
            prefs.edit().putBoolean(KEY_ENABLED, before).commit()
        }
        return persisted
    }

    /**
     * Whether the one-time purge of pre-migration log files has been proven
     * done (see [DebugFileSink.start]'s `purgeLegacy`).
     *
     * Defaults to `false`, so an install that has never recorded the flag —
     * including every upgrade from a build before this one — purges. The cost
     * of a redundant purge is one run of history; the cost of skipping a
     * needed one is a full-rendered run in a shared report, so the default
     * resolves to the safe side.
     */
    fun hasPurgedLegacyLogs(): Boolean = prefs.getBoolean(KEY_DIRECTORY_PURGED, false)

    /**
     * Records the purge as done, returning whether the write reached disk.
     *
     * Called only once the sink has confirmed the files are actually gone: a
     * refused delete leaves the flag clear, and the next start tries again.
     *
     * `commit()` rather than `apply()`, and for a sharper reason than the
     * symmetry with [setEnabled]. `apply()` reports nothing and lands later, so
     * an unwritable store — or a process death before it lands — leaves the
     * flag clear with the files already gone, and the next start purges a
     * directory holding **this** version's own correctly-reduced logs, taking a
     * current run, a previous one and a pinned crash with it (Codex, PR #151).
     * That is a worse loss than the one the purge exists to prevent, so the
     * write is synchronous and its failure is reported rather than assumed away.
     * Safe to block here: every caller is already on the debug log's worker.
     */
    fun markLegacyLogsPurged(): Boolean =
        prefs.edit().putBoolean(KEY_DIRECTORY_PURGED, true).commit()

    private companion object {
        const val FILE_NAME = "debug_log"
        const val KEY_ENABLED = "enabled"

        /**
         * This migration's own marker, deliberately **not** the older
         * `legacy_logs_purged`.
         *
         * That key belongs to a different migration: the local sink's
         * full-to-reduced purge (PR #151), which emptied `cacheDir/debuglog`
         * and then went on writing to it. So on every upgrade from that
         * release the old key is already `true` while the directory holds
         * that release's logs — reusing it would skip this purge, orphan
         * those files, and have the Off toggle trust the same stale answer
         * and report a privacy control that succeeded (Codex, PR #153).
         *
         * The old key is left where it is. Nothing reads it now, and clearing
         * it would be a write with no reader.
         */
        const val KEY_DIRECTORY_PURGED = "shared_logger_directory_purged"
    }
}

/**
 * Wires the debug log up once per process: rotation, the persisted-file sink,
 * a logcat mirror, and the run's opening line.
 */

/**
 * Wires the debug log up once per process: the shared library's file sink, a
 * logcat mirror, and the run's opening line.
 *
 * The file sink itself now comes from `mikelward/androidlog` — the library this
 * app's 615-line copy was extracted into. What stays here is the wiring: the
 * stored on/off setting, the ordering that makes it take effect before anything
 * records, and the crash-banner state screens read.
 */
internal object DebugLogging {

    /** Confined to [worker]; nothing off that thread reads or writes it. */
    private var sink: DebugFileSink? = null

    /**
     * Whether [installNow] got as far as applying the stored setting to
     * [SnoozeDebugLog]'s recording gate.
     *
     * Distinct from "installation ran": [installNow] contains its whole body in
     * `runCatching`, so a preferences read that throws leaves it returning
     * normally with recording still at its permissive default. Work deferred
     * via [afterRecordingGateApplied] must not run in that window — the user's
     * Off setting would not be in force, and Settings still offers Share.
     */
    @Volatile
    private var gateApplied = false

    /**
     * The application context [install] was last called with, kept so a read
     * that finds [sink] still null can retry the installation rather than only
     * reporting that it couldn't check (Codex, PR #89). `install()` is
     * idempotent, so retrying from those reads is enough: a transient failure
     * heals on the next read and a permanent one still reports honestly.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * Whether the most recently completed toggle write was refused by storage.
     *
     * Process-level rather than screen-level, deliberately: the completion
     * callback closes over the activity that made the tap, and a configuration
     * change mid-write hands the screen to a replacement that callback cannot
     * reach (Codex, PR #68). This is the **preferences** write, not the log
     * file, so the sink swap left it exactly as it was.
     */
    @Volatile
    var lastSaveRefused: Boolean = false
        private set

    /**
     * Whether the most recent Off toggle left one or more files undeleted.
     *
     * Two independent directories can fail, and either one leaves files the
     * user was told were gone: the shared sink's own (this run's saved log,
     * reported by its storage listener) and `cacheDir/debuglog`, where the old
     * logger's full-rendered files sit until the migration finally removes
     * them. The sink cannot answer for the second — it has never read those
     * files — so the union is assembled here.
     *
     * Each half is cleared by its own next success, so a retry that works
     * retires the warning; neither is cleared by turning the log back on,
     * which removes no files. The legacy half is set at startup as well as on
     * the Off toggle, since the files outlive the process that failed to
     * remove them and the flag would otherwise read false at every launch.
     *
     * That outliving is why the row's copy is state-neutral rather than
     * naming Off: the switch can be back On, or never have been touched this
     * launch, while the warning is still true (Codex, PR #153).
     */
    val lastDisableCleanupFailed: Boolean get() = sinkPurgeFailed || legacyPurgeFailed

    /** See [lastDisableCleanupFailed]; mirrored from the sink's storage listener. */
    @Volatile
    private var sinkPurgeFailed = false

    /** See [lastDisableCleanupFailed]; set by the Off toggle's own retry. */
    @Volatile
    private var legacyPurgeFailed = false

    /**
     * Whether a crashed run is currently unshared and undismissed.
     *
     * Mirrored from the sink rather than asked for on demand: the library
     * *derives* this on its own worker from what is actually on disk, and
     * publishes changes to listeners. Mirroring turns that into the instant
     * answer [hasPinnedCrash] needs, with no query to race the derivation.
     */
    @Volatile
    private var pinnedCrash = false

    /**
     * The current screen's ear for completed writes; see [watchSaveOutcome].
     * One slot, not a list — there is at most one screen, and during a
     * recreation the replacement registers before the old instance unregisters.
     */
    @Volatile
    private var onSaveOutcome: (() -> Unit)? = null

    /**
     * Calls [onChange] on the worker after each write completes — value
     * applied, [lastSaveRefused] assigned — until the handle is closed.
     *
     * A `SharedPreferences` listener cannot carry the outcome: `commit()`
     * dispatches its change notification *before* this object learns whether
     * the disk write succeeded (Codex, PR #68). This fires after both are final.
     */
    fun watchSaveOutcome(onChange: () -> Unit): AutoCloseable {
        onSaveOutcome = onChange
        return AutoCloseable { if (onSaveOutcome === onChange) onSaveOutcome = null }
    }

    // One FIFO daemon worker for installing and for applying the toggle, so the
    // setting applies in the order the calls were made and an install can never
    // overwrite a toggle with the older stored value it read before the tap
    // (deferred from Codex's PR #62 review).
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { runnable ->
        Thread(runnable, "snoozemo-debug-log-init").apply {
            isDaemon = true
            // Held only so a stalled drain can say what the thread was doing
            // rather than only that it did not finish -- see [workerStall].
            workerThread = this
        }
    }

    /**
     * The worker's thread, for [workerStall] alone. Written by the factory on
     * the pool's own thread and read from a test thread, so it is volatile;
     * nothing else in this object touches it, and no behavior depends on it.
     */
    @Volatile
    private var workerThread: Thread? = null

    /**
     * Call once from `Application.onCreate`. Runs off the caller's thread, like
     * [SnoozeNotifications.warm] and for the same reason: the store read is a
     * preferences file load and the version read is a `PackageManager` call,
     * and `onCreate` sits ahead of a possible cold tile tap.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        runCatching {
            worker.execute { installNow(app) }
        }
    }

    /**
     * Queues [task] on the same single-threaded worker [install] uses, so it
     * runs *after* the stored setting has been applied to the recording gate.
     *
     * `install()` only enqueues its work, and [SnoozeDebugLog] starts with
     * recording on, so anything that collects on its own thread can otherwise
     * record while the user's setting says Off (Codex, PR #125). FIFO ordering
     * rules that out. It also declines to run [task] unless [gateApplied] says
     * the stored setting actually took effect, and skips it outright when that
     * setting was Off — running the work with its writes discarded would leave
     * the app gathering what the user asked it not to.
     */
    fun afterRecordingGateApplied(task: () -> Unit) {
        try {
            worker.execute {
                if (!gateApplied) {
                    runCatching {
                        SnoozeDebugLog.warning("deferred task skipped: recording gate never applied")
                    }
                    return@execute
                }
                if (!SnoozeDebugLog.isRecording) return@execute
                task()
            }
        } catch (e: RuntimeException) {
            runCatching { SnoozeDebugLog.failure(e, "debug-log worker refused a deferred task") }
        } catch (e: Error) {
            // Reporting and returning normally would hide a fatal from the
            // uncaught-exception handler and leave the process running
            // compromised; the task body never started, so its own handling
            // never gets to run (Codex, PR #125).
            runCatching { SnoozeDebugLog.failure(e, "debug-log worker submission hit a fatal error") }
            throw e
        }
    }

    /**
     * Reports a caught failure into the debug log, where the report the user
     * shares will carry it.
     *
     * Two kinds of caller. Calls into the shared sink, and the enqueues that
     * would have made one, do not throw today — each is contained whole on the
     * library's side, and the ones with an outcome the screen needs publish it
     * rather than propagating; those catches guard a contract that changes
     * under us, since the library is tracked at `@main` with nothing pinned.
     * The pre-migration purge is the live kind: `cacheDir` resolution and
     * `deleteRecursively` can genuinely throw. Either way a silent catch is
     * what would hide it (Codex, PR #153).
     *
     * Contained itself, because it runs inside catches whose whole job is to
     * stop a failure escaping — and it goes through the log rather than a bare
     * `Log.e` so the *Privacy* rule's floor applies to it too. The messages
     * name the operation and nothing else.
     */
    private fun logFailure(cause: Throwable, what: String) {
        runCatching { SnoozeDebugLog.failure(cause, what) }
    }

    /**
     * [install]'s body, on the worker thread. Separate so the crash reads below
     * can retry it inline when they find [sink] null — they already run on this
     * same single-threaded worker.
     */
    private fun installNow(app: Context) {
        if (sink != null) return
        runCatching {
            val store = DebugLogStore(app)
            val enabled = store.isEnabled()
            // The recording gate first, so a disabled install stops collecting
            // — and drops whatever was buffered before this read — rather than
            // only not persisting (Codex, PR #62).
            SnoozeDebugLog.setRecording(enabled)
            // Only now is the user's stored choice actually in force; the read
            // above can throw and the runCatching would return normally with
            // recording still permissive (Codex, PR #125).
            gateApplied = true

            // Files written before this app moved to the shared logger are in
            // `cacheDir/debuglog/`, under names the library never looks at, so
            // nothing here would ever read them again — but they hold the old
            // *full* rendering, and leaving them on disk keeps that content
            // around for nothing. Deleted once, and only recorded as done when
            // they are actually gone, so a refusal retries at the next start.
            // The answer is recorded, not just acted on. A refusal here reaches
            // the log only when recording is on -- and an install that starts
            // *disabled* is exactly when it is not, which is also exactly when
            // the user last asked for those files to go. Without this the flag
            // resets to false at every launch while the files the Off toggle
            // promised to delete are still on disk, and Settings reports a
            // privacy control that succeeded (Codex, PR #153).
            if (!store.hasPurgedLegacyLogs()) legacyPurgeFailed = !purgeLegacyDirectory(app, store)

            val fileSink = DebugFileSink(SnoozeDebugLog, app)
            // Registered before start() so the first derivation — which start()
            // publishes once the rotation has settled — is not missed.
            fileSink.addCrashListener { unacknowledged ->
                pinnedCrash = unacknowledged
                runCatching { onCrashPinOutcome?.invoke() }
            }
            // Registered here for the same reason as the crash listener: the
            // first value is delivered on registration, so a failure recorded
            // before this screen existed still reaches it.
            fileSink.addStorageListener { outcomes ->
                sinkPurgeFailed = outcomes.optOutPurgeFailed
                sinkDismissFailed = outcomes.crashDismissalFailed
                // Both ears, unconditionally. The sink's purge and rename run
                // on *its* worker, so either outcome can settle long after the
                // tap that started it has already told the screen it was done
                // — and a configuration change in between hands the screen to
                // an instance that captured neither. Each observer re-reads
                // rather than trusting a delivered value, so notifying the ear
                // whose field did not move costs a duplicate read.
                runCatching { onSaveOutcome?.invoke() }
                runCatching { onDismissOutcome?.invoke() }
            }
            // start() before addSink, so the rotation is queued ahead of this
            // run's first write and can never clobber the prior run.
            fileSink.start()
            SnoozeDebugLog.addSink(fileSink)
            SnoozeDebugLog.addSink { line -> runCatching { Log.d(TAG, line) } }
            sink = fileSink
            // Build, device, and Android version (SPEC.md §4.6) — first, so
            // every later line reads against the software it ran on.
            logRunContext(app)
        }.onFailure { runCatching { Log.w(TAG, "Installing the debug log failed; logcat still records.", it) } }
    }

    /**
     * Deletes what this app's own file sink left in `cacheDir/debuglog/`, once.
     *
     * Every fallible step maps a throw onto the same outcome as a negative
     * return — not proven — and reports it, so a refusal cannot be mistaken for
     * a completed migration (Codex, PR #151, three rounds on this shape).
     *
     * Answers whether the directory is **gone**, so an Off toggle can say on
     * screen that its cleanup did not finish. The warning below reaches the
     * debug log, which is not where a user who has just used a privacy control
     * is looking — and the shared sink's own outcome cannot cover this, since
     * it owns a different directory and has never read these files (Codex,
     * PR #153).
     */
    private fun purgeLegacyDirectory(app: Context, store: DebugLogStore): Boolean {
        val gone = runCatching {
            val dir = File(app.cacheDir, LEGACY_DIR_NAME)
            !dir.exists() || (dir.deleteRecursively() && !dir.exists())
        }
            .onFailure {
                // Both channels, deliberately. Logcat is the only one that
                // works when recording is off -- which is exactly when this
                // runs on an install that starts disabled -- but logcat is not
                // in the report the user shares, so the type and frames would
                // never reach whoever has to explain the failure (Codex,
                // PR #153). `failure` records the throwable's type, stack and
                // message; the message is Android's own text and SPEC.md §4.6
                // accepts it as the floor's single exception.
                runCatching { Log.w(TAG, "Removing the old debug-log directory threw.", it) }
                logFailure(it, "removing the pre-migration log directory threw")
            }
            .getOrDefault(false)
        val recorded = gone && runCatching { store.markLegacyLogsPurged() }
            .onFailure {
                runCatching { Log.w(TAG, "Recording the debug-log migration threw.", it) }
                logFailure(it, "recording the pre-migration log removal threw")
            }
            .getOrDefault(false)
        if (!gone) {
            runCatching {
                SnoozeDebugLog.warning("logs from before this version could not be removed; retried when the log is turned off, and at the next start")
            }
        } else if (!recorded) {
            runCatching {
                SnoozeDebugLog.warning("could not record that old logs were removed, so this will run again")
            }
        }
        return gone
    }

    /**
     * Retries [installNow] when [sink] is null, so a failed or not-yet-run
     * installation can heal instead of reporting a failed check forever
     * (Codex, PR #89). Already on the worker thread.
     */
    private fun reinstallIfNeeded() {
        if (sink != null) return
        appContext?.let { installNow(it) }
    }

    /**
     * The settings switch's entry point: persists the choice, applies what
     * persisted, and answers [onApplied] with whether the write reached disk.
     *
     * Only a *persisted* choice is applied: a change storage refused would
     * otherwise look applied and silently revert at the next process start
     * (deferred from Codex's PR #62 review).
     */
    fun setEnabled(context: Context, enabled: Boolean, onApplied: (persisted: Boolean) -> Unit) {
        val app = context.applicationContext
        runCatching {
            worker.execute {
                val persisted = runCatching { DebugLogStore(app).setEnabled(enabled) }
                    .onFailure { runCatching { Log.w(TAG, "Saving the debug-log setting failed; the switch reverts.", it) } }
                    .getOrDefault(false)
                if (persisted && !enabled) {
                    // The startup migration is retried here, not only at the
                    // next start. It runs once per process and records itself
                    // done only when the directory is actually gone, so a
                    // refusal at startup otherwise leaves the *old* logger's
                    // full-rendered files sitting in `cacheDir/debuglog` for
                    // the rest of the process — including across the very Off
                    // toggle whose whole promise is that what was kept is
                    // deleted immediately (SPEC.md §4.6; Codex, PR #153). The
                    // shared sink's own purge does not reach them: it owns a
                    // different directory and has never read these files.
                    val store = DebugLogStore(app)
                    // Nothing left to purge is a success, not a silence: it is
                    // what clears a warning an earlier refused toggle left up.
                    val purged = runCatching { store.hasPurgedLegacyLogs() }
                        .onFailure { runCatching { Log.w(TAG, "Reading the debug-log migration state threw.", it) } }
                        .getOrDefault(false)
                    legacyPurgeFailed = !(purged || purgeLegacyDirectory(app, store))
                }
                if (persisted) {
                    // Off gates recording itself, not just persistence: the
                    // buffer stops collecting and is emptied (Codex, PR #62) —
                    // and the library's sink answers the same call by deleting
                    // this run's saved copy, so the two stay in step without a
                    // second switch to keep aligned.
                    SnoozeDebugLog.setRecording(enabled)
                }
                // A re-enable's log starts from an emptied buffer — disabling
                // dropped everything, the run-context line included — so the
                // context is restated (SPEC.md §4.6; Codex, PR #68).
                if (persisted && enabled) logRunContext(app)
                // Assign first, then tell the screen: the watch fires only once
                // the outcome it will read is this write's.
                lastSaveRefused = !persisted
                runCatching { onSaveOutcome?.invoke() }
                runCatching { onApplied(persisted) }
            }
        }.onFailure { runCatching { onApplied(false) } }
    }

    /** The line every run's log leads with — and a re-enabled one restarts with. */
    private fun logRunContext(app: Context) {
        SnoozeDebugLog.event(
            "run start; app=${appVersion(app)} android=${Build.VERSION.RELEASE} " +
                "(api ${Build.VERSION.SDK_INT}) device=${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    /**
     * Whether a crashed run is currently unshared and undismissed (SPEC.md
     * §4.6), answered from [pinnedCrash].
     *
     * `checkSucceeded` is false only when there is still no sink after a retry:
     * a crash from a previous run exists independently of whether [install]
     * finished in *this* process, so a missing sink is never confirmed absence,
     * only "not checked yet" (Codex, PR #89). A recompute is requested at the
     * same time, so a screen opening re-derives from what is actually on disk;
     * its answer arrives through [watchCrashPinOutcome].
     */
    fun hasPinnedCrash(onResult: (pinned: Boolean, checkSucceeded: Boolean) -> Unit) {
        runCatching {
            worker.execute {
                reinstallIfNeeded()
                val installed = sink
                if (installed == null) {
                    onResult(false, false)
                } else {
                    // A recompute that never ran leaves the screen deriving
                    // from a stale value with nothing to say so.
                    runCatching { installed.requestCrashRecompute() }
                        .onFailure { logFailure(it, "a crash recompute could not be requested") }
                    onResult(pinnedCrash, true)
                }
            }
        }.onFailure {
            logFailure(it, "debug-log worker refused a crash check")
            onResult(false, false)
        }
    }

    /**
     * The unshared prior runs, with the handle that consumes exactly them.
     *
     * The handle is passed to the caller rather than held here: it is what lets
     * a report delete the files it was actually built from, so two overlapping
     * share flows cannot have the first destroy a run only the second had read.
     *
     * A null [PreviousRun] means there is nothing to send. It no longer
     * distinguishes that from a read that failed — the library reports a failed
     * read into the log itself rather than to the caller — so `readSucceeded`
     * now answers the narrower question the caller can still be told: whether
     * the read ran at all, as against no sink installed or a worker that
     * refused the task.
     *
     * That narrowing does not reopen what the wider answer protected against,
     * but only because the handle now says so itself. A crash arriving with no
     * text is caught by `DebugReport`'s own check; a crash *skipped* by a read
     * that could not open it is caught by `PreviousRun.complete`, which is the
     * library reporting that its handle does not cover every run still on
     * disk. Leaving the file in place is not enough on its own — the file
     * survives, but acknowledging the banner on a report that never carried it
     * retires the only offer to send it (Codex, PR #153).
     */
    fun readPreviousOrCrash(
        onResult: (run: PreviousRun?, wasCrash: Boolean, readSucceeded: Boolean) -> Unit,
    ) {
        runCatching {
            worker.execute {
                reinstallIfNeeded()
                val installed = sink
                if (installed == null) {
                    onResult(null, false, false)
                } else {
                    // A throw here would otherwise escape this Runnable on the
                    // worker thread, never reaching onResult, and every caller
                    // would wait out its own timeout and report a misleading
                    // "timed out" instead of the real failure (Codex, PR #89).
                    runCatching { readPreviousRunBounded(installed) }
                        .onSuccess { onResult(it, pinnedCrash, true) }
                        .onFailure {
                            logFailure(it, "the previous runs could not be read")
                            onResult(null, pinnedCrash, false)
                        }
                }
            }
        }.onFailure {
            logFailure(it, "debug-log worker refused a previous-run read")
            onResult(null, false, false)
        }
    }

    /**
     * [DebugFileSink.readPreviousRun] on a thread that is not [worker], waited
     * for with a bound.
     *
     * That call is synchronous and, until androidlog 2.0.50, waited on the
     * sink's own worker with no timeout at all — so a sink read that never
     * came back parked *this* worker for the life of the process, and with it
     * everything queued behind: the recording gate, and the user's opt-out.
     * A privacy control that never applies while Settings shows it off is
     * principle 2's failure, not a test problem (`TODO.md`).
     *
     * The library now bounds its own wait, and this is deliberately kept
     * anyway. It is the half that does not depend on which version of the
     * library an install resolved, it fails in a quarter of the time so a user
     * waiting on a share is not left for ten seconds, and — the part that
     * matters most — a timeout here becomes `readSucceeded = false`, so the
     * screen says the log could not be read rather than showing "nothing to
     * send" over runs that are still on disk.
     *
     * The reference is handed off, not the field: [sink] stays confined to
     * [worker], which reads it and passes the value. The pool is cached rather
     * than single-threaded on purpose — a thread still stuck on a previous
     * stalled read must not be what makes the next one time out.
     */
    private fun readPreviousRunBounded(installed: DebugFileSink): PreviousRun? =
        awaitBounded { installed.readPreviousRun() }

    /**
     * Runs [read] on [blockingReads] and waits [timeoutSeconds] for it.
     *
     * Takes the work rather than the sink so the bound can be tested at all:
     * `DebugFileSink` is a final class this module cannot stall — the seams
     * that would allow it are internal to the library — so a test that had to
     * go through one could only ever assert the happy path.
     */
    internal fun <T> awaitBounded(
        timeoutSeconds: Long = PREVIOUS_RUN_READ_TIMEOUT_SECONDS,
        read: () -> T,
    ): T {
        val pending = blockingReads.submit<T> { read() }
        return try {
            pending.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // Interrupts the read, which the library's own wait reports and
            // returns from; without that it would sit on the thread until the
            // sink's worker freed up, or forever.
            //
            // It releases *this* thread and nothing further: the task the read
            // submitted keeps its place in the sink's queue, as does everything
            // behind it, an opt-out's purge included (Codex, PR #169; `TODO.md`
            // carries what is still owed there). Propagating the cancellation
            // is not the answer — the sink's executor has one thread shared by
            // every file operation, so interrupting the task at its head could
            // land on an unrelated write, and dequeuing the read would not
            // unwedge a worker stuck on something else anyway.
            pending.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            // The read's own failure, already logged by the library. Unwrapped
            // so the caller's failure line names what actually went wrong
            // rather than the wrapper this hand-off added.
            throw e.cause ?: e
        }
    }

    /**
     * The pool [readPreviousRunBounded] hands the blocking read to. Cached and
     * daemon: threads are created only when a read is in flight, a stalled one
     * cannot hold the process open, and one left stuck does not delay the next.
     */
    private val blockingReads = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "snoozemo-debug-log-read").apply { isDaemon = true }
    }

    /** Mirrors [watchSaveOutcome]; fed by the sink's own crash listener. */
    fun watchCrashPinOutcome(onChange: () -> Unit): AutoCloseable {
        onCrashPinOutcome = onChange
        return AutoCloseable { if (onCrashPinOutcome === onChange) onCrashPinOutcome = null }
    }

    @Volatile
    private var onCrashPinOutcome: (() -> Unit)? = null

    /**
     * Consumes [run] — the files a delivered report was built from — and lowers
     * the crash banner. Reported as success before [install] has run: there is
     * nothing to consume.
     */
    fun consumeCrashPin(run: PreviousRun?, onResult: (Boolean) -> Unit) {
        runCatching {
            worker.execute {
                val installed = sink
                if (installed != null) {
                    // The banner is acknowledged whether or not the clear
                    // succeeded, deliberately: the report landed, so the user
                    // has the crash, and the library holds any file it could
                    // not discard for the next share to retry. What a failure
                    // here loses is the diagnostic, not the evidence.
                    if (run != null) {
                        runCatching { installed.clearPreviousRun(run) }
                            .onFailure { logFailure(it, "a shared run could not be cleared") }
                    }
                    runCatching { installed.acknowledgeCrashBanner() }
                        .onFailure { logFailure(it, "a crash banner could not be acknowledged after a share") }
                }
                // Unconditional, because "a consume completed" is its own event
                // and not the same fact as "the pin state changed". The sink's
                // crash listener reports the second; only this reports the
                // first, and with nothing pinned there is no state change to
                // report at all. It matters because a configuration change can
                // recreate the activity while this is still on the worker, and
                // the `onResult` that started it closes over the instance that
                // is now gone — without this the screen sits on stale state
                // until something else happens to refresh it.
                //
                // A consume that *does* lower the banner therefore notifies
                // twice, which costs nothing: the observer re-reads the state
                // rather than trusting a delivered value, so a duplicate is
                // the same answer a second time. Making this conditional to
                // avoid that is what left a test racing the install's own
                // first derivation.
                runCatching { onCrashPinOutcome?.invoke() }
                onResult(true)
            }
        }.onFailure {
            logFailure(it, "debug-log worker refused a crash-pin consume")
            onResult(false)
        }
    }

    /**
     * Whether the most recently completed [dismissCrashPin] was refused.
     *
     * A union, for the same reason [lastDisableCleanupFailed] is one: the sink
     * can only answer for the attempts that reached it. [sinkDismissFailed] is
     * mirrored from its storage listener rather than cleared on each tap and
     * set on completion — the sink already clears it when a dismissal succeeds,
     * and a local eager reset would race that publication for the screen's next
     * read. [localDismissFailed] covers the attempts that never got that far.
     *
     * A refused dismissal leaves the banner up by construction — the sink
     * lowers nothing eagerly — so the user is never told it worked. What this
     * carries is the *why*, which the banner alone cannot say (Codex, PR #153).
     */
    val lastDismissFailed: Boolean get() = sinkDismissFailed || localDismissFailed

    /** The half the sink publishes; see [lastDismissFailed]. */
    @Volatile
    private var sinkDismissFailed: Boolean = false

    /**
     * The half the sink cannot publish: a dismissal *this* worker refused, so
     * `acknowledgeCrashBanner()` was never called and nothing over there has an
     * outcome to report. Cleared at the start of each attempt, so a retry that
     * gets through retires it (Codex, PR #153).
     */
    @Volatile
    private var localDismissFailed: Boolean = false

    @Volatile
    private var onDismissOutcome: (() -> Unit)? = null

    /** Mirrors [watchCrashPinOutcome] / `DebugReport.watchShareOutcome`. */
    fun watchDismissOutcome(onChange: () -> Unit): AutoCloseable {
        onDismissOutcome = onChange
        return AutoCloseable { if (onDismissOutcome === onChange) onDismissOutcome = null }
    }

    /**
     * Dismisses the crash banner directly (SPEC.md §4.6) without sending
     * anything: the banner goes down and the runs behind it stay, since a
     * dismissal is "I don't want to look at this", not "delete the evidence".
     */
    fun dismissCrashPin() {
        try {
            worker.execute {
                localDismissFailed = false
                try {
                    reinstallIfNeeded()
                    sink?.acknowledgeCrashBanner()
                } catch (e: RuntimeException) {
                    // The sink does not throw here today — its body is
                    // contained whole, and the one synchronous failure it has,
                    // a refused enqueue, publishes `crashDismissalFailed`
                    // rather than propagating. So this is for a contract that
                    // changes under us: the library is tracked at `@main` with
                    // nothing pinned. It logs and records rather than
                    // swallowing, because a silent catch here would leave the
                    // banner up with nothing anywhere saying why (Codex,
                    // PR #153).
                    localDismissFailed = true
                    logFailure(e, "a crash-banner dismissal failed before it was queued")
                }
                runCatching { onDismissOutcome?.invoke() }
            }
        } catch (e: RuntimeException) {
            // This one the sink genuinely cannot cover: the task never reached
            // it, so nothing over there has an outcome to publish and the
            // screen would keep reading the previous answer.
            localDismissFailed = true
            logFailure(e, "debug-log worker refused a crash-banner dismissal")
            runCatching { onDismissOutcome?.invoke() }
        }
    }

    /**
     * Test seam: drains the worker so assertions see settled state, and
     * reports whether it actually drained.
     *
     * Unlike [afterRecordingGateApplied], the task queued here runs whatever
     * the recording gate says — that method is production API whose job is to
     * *skip* when the setting is Off, so a test latching on it waits on
     * something that is entitled never to happen. FIFO ordering still gives
     * the guarantee a drain needs: a task queued now runs only once every
     * earlier one has.
     *
     * Returns false when the worker did not reach the queued task in time, so
     * a caller can fail on that rather than proceeding against state it never
     * waited for. [timeoutSeconds] is a real-time bound in a JVM competing with
     * every other Gradle worker on the machine, so a caller that needs more
     * headroom than the default asks for it rather than being failed by load.
     */
    internal fun awaitIdleForTest(timeoutSeconds: Long = 5): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        runCatching { worker.execute { done.countDown() } }.onFailure { done.countDown() }
        if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
            // Snapshotted *here*, not read back by the caller afterwards
            // (Codex, PR #168). A task that finishes just after the timeout
            // leaves the worker idle — or running this drain's own task — by
            // the time a caller asks, so a live read would name anything but
            // the operation that blew the bound. The one failure this exists
            // to explain would arrive carrying evidence about something else.
            //
            // Then *validated*, because taking it is not instantaneous either
            // and the worker can advance while a stack is being walked (Codex,
            // PR #168). The latch settles it: a count still standing after the
            // snapshot means the worker had not reached this drain's own task
            // by then, and so had not reached it at the earlier moment the
            // snapshot was taken — the reading describes a worker genuinely
            // behind. A count of zero means it got there somewhere around the
            // bound, which no stack read can place either side of, so that is
            // reported as what it is rather than dressed up as a stall.
            val snapshot = describeWorker()
            lastStall = if (done.count > 0L) {
                snapshot
            } else {
                "the drain finished around the moment its bound expired, so this " +
                    "reading cannot be placed either side of it — treat it as a slow " +
                    "worker rather than a wedged one: $snapshot"
            }
            // Returns without touching the sink, and that ordering is the
            // whole point (Codex, PR #168). `awaitIdle()` drains the *sink's*
            // worker, and the leading explanation for a stalled worker here is
            // that it is itself parked in `readPreviousRun()` waiting on
            // exactly that worker. Draining would then queue behind the same
            // task and block, so the caller would hang instead of failing with
            // [workerStall] — leaving the one path this seam most needs to
            // report as the one it could not.
            return false
        }
        sink?.let { runCatching { it.awaitIdle() } }
        return true
    }

    /**
     * The worker as it was at the most recent [awaitIdleForTest] timeout.
     *
     * Deliberately **not** cleared by a later successful drain. Both callers
     * print it only when their own drain has just timed out — which overwrote
     * it a moment earlier — so clearing buys no accuracy, while keeping it
     * makes the capture observable: a test can free the worker, drain it to
     * completion, and still see the stall the timeout recorded, which is the
     * whole property worth pinning.
     */
    @Volatile
    private var lastStall: String? = null

    /**
     * Test seam: occupies the worker until [release] is counted down, so a
     * test can see what callers do against a worker that is not coming back.
     *
     * That state is otherwise unreachable from a test — it arises from a
     * *stalled* sink read — while being the state the drain's reporting has
     * to survive, so it is worth being able to stage deliberately.
     *
     * The wait is bounded even so. An unreleased latch here would wedge the
     * worker for the rest of the JVM and take every later test class in the
     * sandbox down with it, which is precisely the bug this seam exists to
     * study; a test that forgets its `finally` should fail alone.
     */
    internal fun blockWorkerForTest(release: java.util.concurrent.CountDownLatch) {
        runCatching { worker.execute { runCatching { release.await(30, TimeUnit.SECONDS) } } }
    }

    /**
     * Test seam: what the worker was doing when a drain did not finish.
     *
     * A failed [awaitIdleForTest] says only that a trivial task did not reach
     * the front of a FIFO queue, which leaves the actual question -- *what is
     * ahead of it* -- unanswered, and that has cost two wrong diagnoses of the
     * `ProcessExitReasonsTest` stall already (`TODO.md`). The worker is a
     * process-wide singleton shared by every test class in a Robolectric
     * sandbox, so whatever wedges it was very likely queued by an *earlier*
     * class and is invisible from the class that fails. Its stack names that
     * caller directly.
     *
     * Reports the queue depth alongside, since "blocked in a task" and "never
     * started one" are different faults with the same symptom.
     */
    internal fun workerStall(): String = lastStall?.let { "at the drain timeout: $it" }
        ?: "no drain has timed out; worker as of now: ${describeWorker()}"

    /** The worker's thread state and stack, as of this call. */
    private fun describeWorker(): String {
        val thread = workerThread
            ?: return "the debug-log worker thread was never created; queued=${worker.queue.size}"
        val frames = thread.stackTrace.joinToString("\n") { "    at $it" }
        return buildString {
            append("debug-log worker \"${thread.name}\" is ${thread.state}")
            append(", queued=${worker.queue.size}")
            append(", completed=${worker.completedTaskCount}")
            if (frames.isNotEmpty()) append("\n").append(frames)
        }
    }

    /** Test seam: forgets the installed sink so the next [install] rebuilds it. */
    internal fun resetForTest() {
        awaitIdleForTest()
        sink = null
        appContext = null
        gateApplied = false
        pinnedCrash = false
        lastSaveRefused = false
        sinkPurgeFailed = false
        legacyPurgeFailed = false
        sinkDismissFailed = false
        localDismissFailed = false
        onSaveOutcome = null
        onCrashPinOutcome = null
        onDismissOutcome = null
    }
}
