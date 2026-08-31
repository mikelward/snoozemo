package app.snoozemo.snooze

import android.content.Context
import android.os.Build
import android.util.Log
import app.snoozemo.core.SnoozeDebugLog
import com.mikelward.androidlog.android.DebugFileSink
import com.mikelward.androidlog.android.PreviousRun
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
    fun hasPurgedLegacyLogs(): Boolean = prefs.getBoolean(KEY_LEGACY_PURGED, false)

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
        prefs.edit().putBoolean(KEY_LEGACY_PURGED, true).commit()

    private companion object {
        const val FILE_NAME = "debug_log"
        const val KEY_ENABLED = "enabled"
        const val KEY_LEGACY_PURGED = "legacy_logs_purged"
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
    ) { runnable -> Thread(runnable, "snoozemo-debug-log-init").apply { isDaemon = true } }

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
                lastDismissFailed = outcomes.crashDismissalFailed
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
            .onFailure { runCatching { Log.w(TAG, "Removing the old debug-log directory threw.", it) } }
            .getOrDefault(false)
        val recorded = gone && runCatching { store.markLegacyLogsPurged() }
            .onFailure { runCatching { Log.w(TAG, "Recording the debug-log migration threw.", it) } }
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
                    runCatching { installed.requestCrashRecompute() }
                    onResult(pinnedCrash, true)
                }
            }
        }.onFailure { onResult(false, false) }
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
                    runCatching { installed.readPreviousRun() }
                        .onSuccess { onResult(it, pinnedCrash, true) }
                        .onFailure { onResult(null, pinnedCrash, false) }
                }
            }
        }.onFailure { onResult(null, false, false) }
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
                    if (run != null) runCatching { installed.clearPreviousRun(run) }
                    runCatching { installed.acknowledgeCrashBanner() }
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
        }.onFailure { onResult(false) }
    }

    /**
     * Whether the most recently completed [dismissCrashPin] was refused.
     *
     * Mirrored from the sink's storage listener rather than cleared on each
     * tap and set on completion: the sink already clears it when a dismissal
     * succeeds, and a local eager reset would race that publication for the
     * screen's next read.
     *
     * A refused dismissal leaves the banner up by construction — the sink
     * lowers nothing eagerly — so the user is never told it worked. What this
     * carries is the *why*, which the banner alone cannot say (Codex, PR #153).
     */
    @Volatile
    var lastDismissFailed: Boolean = false
        private set

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
        runCatching {
            worker.execute {
                reinstallIfNeeded()
                runCatching { sink?.acknowledgeCrashBanner() }
                runCatching { onDismissOutcome?.invoke() }
            }
        }
    }

    /** Test seam: drains the worker so assertions see settled state. */
    internal fun awaitIdleForTest() {
        val done = java.util.concurrent.CountDownLatch(1)
        runCatching { worker.execute { done.countDown() } }.onFailure { done.countDown() }
        done.await(5, TimeUnit.SECONDS)
        sink?.let { runCatching { it.awaitIdle() } }
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
        lastDismissFailed = false
        onSaveOutcome = null
        onCrashPinOutcome = null
        onDismissOutcome = null
    }
}
