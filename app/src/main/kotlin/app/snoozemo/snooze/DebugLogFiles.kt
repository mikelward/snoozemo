package app.snoozemo.snooze

import android.content.Context
import android.os.Build
import android.util.Log
import app.snoozemo.core.SnoozeDebugLog
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SnoozemoDebugLog"

/** The subdirectory of `cacheDir` everything here lives in. */
private const val DIR_NAME = "debuglog"

/** The running process's log; rotated aside at the next start. */
private const val CURRENT_FILE = "current.log"

/** The previous run's log — exactly one, per SPEC.md §4.6's two-run bound. */
private const val PREVIOUS_FILE = "previous.log"

/**
 * A crashed run, pinned: ordinary rotation never overwrites it, and while it
 * exists it *holds* the previous slot — an ordinary run that would have
 * rotated into [PREVIOUS_FILE] is discarded instead of displacing it, so the
 * two-run privacy bound holds unchanged (SPEC.md §4.6).
 */
private const val CRASH_FILE = "crash.log"

/**
 * Left beside [CURRENT_FILE] by the uncaught-exception handler and consumed at
 * the next start. Its presence is the one in-process signal that the run
 * *crashed* rather than exited or was killed — an ordinary process death,
 * force-stop, or app update never writes it, which is what keeps the crash pin
 * (and, in Phase 5, the post-crash banner) from mislabeling a routine kill.
 */
private const val CRASH_MARKER_FILE = "$CURRENT_FILE.crash"

/** Atomic-write staging for [CURRENT_FILE]; never meaningful across a start. */
private const val TEMP_FILE = "$CURRENT_FILE.tmp"

/** Bound on one persisted run. The buffer is entry-bounded; this backstops it. */
private const val PERSIST_BUDGET_CHARS = 150_000

/**
 * How long the crash handler waits for its flush before chaining on. Long
 * enough to land the snapshot on healthy storage, short enough that a stalled
 * disk never delays process termination.
 */
private const val CRASH_WRITE_TIMEOUT_MS = 250L

/**
 * Persists the debug log (SPEC.md §4.6): mirrors [SnoozeDebugLog] to
 * `cacheDir/debuglog/current.log`, rotates the just-ended run aside at start —
 * the current run plus the previous one, and a pinned crash that holds the
 * previous slot — and deletes everything when the user turns the log off.
 *
 * Ported from the sibling Simmo repo's `DebugFileSink`, with §4.6's two
 * deliberate differences: exactly one previous run rather than several, and
 * the crash pin holding the `previous` slot rather than being a third file.
 *
 * **Everything touching the filesystem runs on one FIFO daemon worker** — the
 * rotation, the mirroring, the crash flush, and the delete-on-disable — so
 * nothing blocks the caller on disk. That matters here twice: `log` is fanned
 * out from recording sites that include the arm path, and the rotation is
 * enqueued from `Application.onCreate`, which a cold tile tap races.
 */
internal class DebugFileSink internal constructor(
    dirProvider: () -> File,
) : SnoozeDebugLog.Sink {

    constructor(context: Context) : this({ File(context.applicationContext.cacheDir, DIR_NAME) })

    // Resolved on first access, which is always inside a worker task, so
    // cacheDir resolution never touches the calling thread.
    private val dir: File by lazy(dirProvider)

    private val current get() = File(dir, CURRENT_FILE)
    private val previous get() = File(dir, PREVIOUS_FILE)
    private val crash get() = File(dir, CRASH_FILE)
    private val crashMarker get() = File(dir, CRASH_MARKER_FILE)
    private val temp get() = File(dir, TEMP_FILE)

    // Single-threaded and FIFO, which is what serializes every file operation
    // without a lock; daemon, so it never keeps the process alive; prestarted,
    // so the first write never pays thread creation.
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "snoozemo-debug-log").apply { isDaemon = true } }
        .apply { prestartCoreThread() }

    private val writePending = AtomicBoolean(false)

    /**
     * Whether writes are wanted at all — the user's setting, told to this sink
     * by [DebugLogging]. Volatile because [log] reads it on whatever thread
     * recorded the entry while the setting flips on the main thread.
     */
    @Volatile
    private var enabled: Boolean = true

    /**
     * Rotates the just-ended run and installs the chained crash handler. Call
     * once per process, off the main thread ([DebugLogging.install]); register
     * as a sink afterward, so this run's first writes queue behind the
     * rotation on the FIFO worker and can never clobber the prior run.
     */
    fun start(enabled: Boolean) {
        this.enabled = enabled
        runCatching {
            worker.execute {
                runCatching {
                    if (!this.enabled) {
                        // Off means nothing is kept — including whatever a
                        // run under the old setting left behind (SPEC.md §4.6).
                        deleteEverything()
                        return@execute
                    }
                    rotate()
                }.onFailure {
                    // runCatching, because this also runs under plain JUnit
                    // where android.util.Log throws — and a logging failure
                    // must never take the worker task with it.
                    runCatching { Log.w(TAG, "Rotating the debug log failed; recording continues.", it) }
                }
            }
        }
        installCrashHandler()
    }

    /**
     * The §4.6 rotation. Reads left to right: a crashed run pins, replacing
     * any older pin and the plain previous run together (the pin *holds* the
     * previous slot); an ordinary run rotates into `previous` — unless a pin
     * is holding that slot, in which case it is discarded, because an unread
     * crash explains a failure and the uneventful run after it explains
     * nothing. The marker and temp file never survive a start either way.
     */
    private fun rotate() {
        if (current.exists()) {
            val crashed = crashMarker.exists()
            when {
                crashed -> {
                    // The old pin goes first — a later crash pins again — but
                    // `previous` and the marker only go once the pin actually
                    // took: deleting them ahead of a refused rename destroyed
                    // the retained run and left the crashed one sitting
                    // unmarked in `current`, where this run's first snapshot
                    // would overwrite it (flagged by Codex on PR #62). The
                    // copy is the fallback for a filesystem that refuses
                    // cross-name renames.
                    //
                    // On a failed pin the marker is **retained**, and that is
                    // the second Codex round on this branch getting it right
                    // over the first fix: a refusal of both operations is
                    // usually persistent, and under it the retained marker is
                    // what makes the next start retry the pin against the
                    // still-intact crashed run. The residual is the transient
                    // case — a later snapshot that succeeds overwrites
                    // `current` and the next start pins this run's log — and
                    // it self-describes, because that log leads with the
                    // warning below.
                    crash.delete()
                    val pinned = current.renameTo(crash) || runCatching {
                        current.copyTo(crash, overwrite = true)
                        current.delete()
                    }.isSuccess
                    if (pinned) {
                        previous.delete()
                        // Checked, per the error-handling rule: a marker that
                        // will not delete would mislabel the *next* ordinary
                        // run as a crash and displace this genuine pin. Not
                        // acted on beyond the log — a same-directory delete
                        // failing right after a rename succeeded is a storage
                        // already past reasoning about, the harm is bounded to
                        // a mislabeled diagnostic, and it self-heals the run
                        // after.
                        if (!crashMarker.delete() && crashMarker.exists()) {
                            runCatching {
                                Log.w(TAG, "The consumed crash marker would not delete; the next run may be mislabeled.")
                            }
                        }
                    } else {
                        runCatching {
                            Log.w(TAG, "Could not pin the crashed run's log; retrying at the next start.")
                        }
                    }
                }
                // The marker is handled inside the crashed branch above and is
                // absent by definition in these two, so nothing below touches
                // it: an unconditional delete here is what un-did the pin's
                // retry (flagged by Codex on PR #62, third round).
                crash.exists() -> current.delete()
                else -> {
                    // The same checked shape as the pin above, and for the
                    // same reason (Codex, PR #62): deleting `previous` ahead
                    // of an unchecked rename lost both retained runs to one
                    // filesystem refusal — the next snapshot overwrites
                    // `current`. A rename onto an existing file replaces it,
                    // so nothing needs pre-deleting; on refusal the copy
                    // fallback overwrites instead, and a rotation that fails
                    // outright leaves both files where they were and says so.
                    val rotated = current.renameTo(previous) || runCatching {
                        current.copyTo(previous, overwrite = true)
                        current.delete()
                    }.isSuccess
                    if (!rotated) {
                        runCatching {
                            Log.w(TAG, "Could not rotate the debug log; the ended run may be overwritten.")
                        }
                    }
                }
            }
        } else {
            // A marker with no run behind it is stale — a crash that landed
            // before anything was written — and would mislabel this run.
            crashMarker.delete()
        }
        // Never renamed into place, so by the atomic-write contract it is
        // uncommitted and possibly partial.
        temp.delete()
    }

    override fun log(line: String) {
        if (!enabled) return
        // Coalesce: while a write is queued, later lines are already covered by
        // that write's snapshot read. Enqueue-only, so this returns at once —
        // the fan-out can be on the arm path.
        if (writePending.compareAndSet(false, true)) {
            runCatching {
                worker.execute {
                    writePending.set(false)
                    writeSnapshot()
                }
            }.onFailure { writePending.set(false) }
        }
    }

    /**
     * Applies the user's setting. Turning the log **off deletes what was
     * kept** — the current run, the previous run, and any pinned crash,
     * immediately (SPEC.md §4.6): stopping writes while leaving the files
     * would be a privacy control that doesn't do what its name promises, and
     * the leftover files would no longer rotate, outliving every log the
     * feature normally keeps.
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (enabled) return
        runCatching {
            worker.execute {
                runCatching { deleteEverything() }.onFailure {
                    runCatching { Log.w(TAG, "Deleting the debug log failed; files may remain until eviction.", it) }
                }
            }
        }
    }

    private fun deleteEverything() {
        // `File.delete()` reports refusal by returning false, so an unchecked
        // walk read "off deletes what was kept" as done when it wasn't
        // (Codex, PR #62). Every file is still attempted — one refusal must
        // not shield the rest — and a leftover is said out loud rather than
        // treated as success. The durable retry already exists: a disabled
        // sink re-runs this at every process start, and so does the toggle.
        val leftovers = listOf(current, previous, crash, crashMarker, temp)
            .filter { !it.delete() && it.exists() }
        if (leftovers.isNotEmpty()) {
            runCatching {
                Log.w(
                    TAG,
                    "Turning the debug log off left ${leftovers.size} file(s) undeleted; " +
                        "retried at every start until they go.",
                )
            }
        }
    }

    private fun writeSnapshot() {
        if (!enabled) return
        runCatching {
            dir.mkdirs()
            var text = SnoozeDebugLog.snapshot().joinToString("\n")
            if (text.length > PERSIST_BUDGET_CHARS) text = text.takeLast(PERSIST_BUDGET_CHARS)
            // Atomic replace: a kill mid-write leaves the prior complete
            // snapshot rather than a truncated file — surviving exactly that
            // kill is the point. Fall back to a direct write only if the
            // rename is refused.
            temp.writeText(text)
            if (!temp.renameTo(current)) {
                current.writeText(text)
                temp.delete()
            }
        }.onFailure {
            // Straight to logcat, never back through SnoozeDebugLog — this
            // sink would just fan the failure back to itself. Cache storage
            // refusing writes is exactly when diagnostics stop persisting, and
            // that must not look identical to everything working (flagged by
            // Codex on PR #62); the buffer still holds the entries, so a later
            // write that succeeds catches the file up.
            runCatching { Log.w(TAG, "Writing the debug log failed; entries stay in memory.", it) }
        }
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                if (enabled) {
                    // Claim writePending first: the warning below fans out to
                    // this sink too, and its ordinary coalesced write would
                    // otherwise queue ahead of the marker task on the FIFO
                    // worker — on slow storage that redundant write eats the
                    // whole deadline before the marker lands, and the next
                    // start reads the crash as a routine kill.
                    writePending.set(true)
                    SnoozeDebugLog.warning("uncaught exception on thread ${thread.name}", throwable)
                    // Marker first, so a flush killed at the deadline still
                    // leaves the pin signal; the snapshot rides behind it.
                    val flush = worker.submit {
                        writePending.set(false)
                        runCatching {
                            dir.mkdirs()
                            crashMarker.writeText("1")
                        }.onFailure {
                            // Logged, not retried: the process is dying and
                            // there is nothing left to retry from. The cost is
                            // bounded — this run's log survives as an ordinary
                            // `previous` rather than a pinned crash.
                            runCatching { Log.w(TAG, "Writing the crash marker failed; this crash won't be pinned.", it) }
                        }
                        writeSnapshot()
                    }
                    runCatching { flush.get(CRASH_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Test-only: blocks until the worker drains, rotation included. */
    internal fun awaitIdleForTest() {
        worker.submit {}.get()
    }
}

/**
 * Whether the debug log is on — the user's setting behind SPEC.md §4.6's "on by
 * default". Its own one-key file, like `notification_prompt`: nothing else is
 * about the log, and the stores it might have shared are about a snooze.
 */
internal class DebugLogStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    /** Persists the choice, returning whether the write reached disk. */
    fun setEnabled(enabled: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()

    private companion object {
        const val FILE_NAME = "debug_log"
        const val KEY_ENABLED = "enabled"
    }
}

/**
 * Wires the debug log up once per process: rotation, the persisted-file sink,
 * a logcat mirror, and the run's opening line.
 */
internal object DebugLogging {

    @Volatile
    private var sink: DebugFileSink? = null

    /**
     * Call once from `Application.onCreate`. Spawns its own thread, like
     * [SnoozeNotifications.warm] and for the same reason: the store read is a
     * preferences file load and the version read is a `PackageManager` call,
     * and `onCreate` sits ahead of a possible cold tile tap. Entries recorded
     * before the sinks register land in the buffer and reach the file with the
     * next entry after, so nothing is lost to the deferral.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        Thread {
            runCatching {
                val opening = synchronized(this) {
                    if (sink != null) return@runCatching
                    val enabled = DebugLogStore(app).isEnabled()
                    // The recording gate first, so a disabled install stops
                    // collecting — and drops whatever was buffered before this
                    // read — rather than only not persisting (Codex, PR #62).
                    SnoozeDebugLog.setRecording(enabled)
                    val fileSink = DebugFileSink(app)
                    fileSink.start(enabled = enabled)
                    SnoozeDebugLog.addSink(fileSink)
                    SnoozeDebugLog.addSink { line -> runCatching { Log.d(TAG, line) } }
                    sink = fileSink
                    // Build, device, and Android version (SPEC.md §4.6) — once
                    // per run, first, so every later line reads against the
                    // software it ran on.
                    "run start; app=${appVersion(app)} android=${Build.VERSION.RELEASE} " +
                        "(api ${Build.VERSION.SDK_INT}) device=${Build.MANUFACTURER} ${Build.MODEL}"
                }
                SnoozeDebugLog.event(opening)
            }.onFailure { runCatching { Log.w(TAG, "Installing the debug log failed; logcat still records.", it) } }
        }.apply {
            isDaemon = true
            name = "snoozemo-debug-log-init"
        }.start()
    }

    /**
     * The settings toggle's entry point (the row itself lands with the copy
     * pass): persists the choice and applies it — off deletes what was kept.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        DebugLogStore(context).setEnabled(enabled)
        // Off gates recording itself, not just persistence: the buffer stops
        // collecting and is emptied, so nothing captured while off can be
        // written to disk by a later re-enable (Codex, PR #62).
        SnoozeDebugLog.setRecording(enabled)
        sink?.setEnabled(enabled)
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")
}
