package app.snoozemo.snooze

import android.content.Context
import android.os.Build
import android.util.Log
import com.mikelward.androidlog.safe
import com.mikelward.androidlog.DebugLog
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
) : DebugLog.Sink {

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
     * Whether files written before the move to the shared logger may still be
     * on disk — set the moment a purge is asked for, cleared only when one is
     * proven to have emptied the directory.
     *
     * While it is set, [hasPinnedCrash] and [readPreviousOrCrash] report
     * nothing to hand over. Retrying at the next process start is not enough
     * on its own: a refused delete leaves `previous.log` or a pinned
     * `crash.log` holding the old **full** rendering, and this process would
     * go on serving it to a share for as long as it lives (Codex, PR #151).
     *
     * Set on the calling thread, ahead of the worker task that does the
     * deleting, so a read racing in first is refused too — the same reason the
     * mark has to be durable before the content it describes.
     *
     * Volatile rather than confined to the worker: it is written from
     * [start]'s caller and read inside worker tasks.
     */
    @Volatile
    private var legacyPurgePending: Boolean = false

    /**
     * Rotates the just-ended run and installs the chained crash handler. Call
     * once per process, off the main thread ([DebugLogging.install]); register
     * as a sink afterward, so this run's first writes queue behind the
     * rotation on the FIFO worker and can never clobber the prior run.
     *
     * [onDisabled] mirrors [setEnabled]'s own — fired only for a start under
     * an already-Off setting, with whether the retried delete actually
     * finished the job. A process restarted under a setting that was already
     * Off is exactly when a leftover from a previous refused delete gets
     * retried (the durable retry [deleteEverything] promises), so a refusal
     * here must reach the same place a refusal from the toggle does, not
     * read as success just because nothing was tapped this time (Codex,
     * PR #89).
     *
     * [purgeLegacy] deletes everything already on disk before rotating, for
     * the one start that follows the move to the shared logger — those files
     * hold the old full rendering and the reduced floor cannot be applied to
     * them after the fact. [onLegacyPurged] fires with whether they are
     * actually gone; it firing at all is the proof, so a caller that never
     * hears must retry rather than assume.
     */
    fun start(
        enabled: Boolean,
        purgeLegacy: Boolean = false,
        onLegacyPurged: (purged: Boolean) -> Unit = {},
        onDisabled: (allDeleted: Boolean) -> Unit = {},
    ) {
        this.enabled = enabled
        // Before the worker task, not inside it: until a purge is proven done
        // this sink has nothing it may hand to a share, and a read that beats
        // the delete to the worker must be refused as surely as one after a
        // refusal.
        if (purgeLegacy) legacyPurgePending = true
        runCatching {
            worker.execute {
                runCatching {
                    // Before the rotation, not after, and before anything this
                    // run records: files left by the build before the shared
                    // logger carry the *old* full rendering, and the reduced
                    // floor is not retroactive. Rotating them would move an
                    // unreduced run into `previous`, where a shared report
                    // reads it (Codex, PR #151). Deleting is the only thing
                    // that applies the new floor to them — re-rendering is
                    // impossible, the arguments are long gone.
                    //
                    // Same worker task as the rotation it precedes, so there
                    // is no ordering to get wrong. The outcome is reported
                    // rather than assumed, and the caller records the purge as
                    // done only on proof: a refused delete leaves a full-
                    // rendered run on disk, so it has to be retried at the
                    // next start rather than marked off.
                    if (purgeLegacy) {
                        // Caught here rather than left to the task's outer
                        // handler, which only reaches logcat: a throw and a
                        // returned `false` mean the same thing — not proven
                        // gone — so they take the same path, and the one that
                        // records the reason where the user's own report will
                        // carry it must not be the one that gets skipped
                        // (Codex, PR #151). `dir` resolves lazily on first
                        // touch and `File.delete()` can refuse by throwing, so
                        // this is a live path, not a defensive one.
                        val purged = runCatching { deleteEverything() }
                            .onFailure {
                                runCatching {
                                    Log.w(TAG, "Purging pre-migration debug logs threw.", it)
                                }
                            }
                            .getOrDefault(false)
                        // Cleared only on proof. A refusal leaves the gate
                        // shut for the rest of this process, so nothing that
                        // survived can reach a share before the next start
                        // gets to try again.
                        if (purged) {
                            legacyPurgePending = false
                        } else {
                            runCatching {
                                Log.w(
                                    TAG,
                                    "Pre-migration debug logs survived; withheld from sharing and retried at every start.",
                                )
                            }
                            // And into the log itself, not only logcat, so the
                            // report the user does share explains why it
                            // carries no prior run. Safe to fan back through
                            // the log here where a *write* failure would not
                            // be: this fires once at start, so it cannot
                            // recurse the way a failing write logging its own
                            // failure would.
                            runCatching {
                                SnoozeDebugLog.warning(
                                    "a log from before this version could not be deleted, so no prior run is shared",
                                )
                            }
                        }
                        runCatching { onLegacyPurged(purged) }
                    }
                    // The captured parameter, not the field: this task decides
                    // what a *start under the stored setting* does, and a
                    // re-enable racing in before it runs must not turn a
                    // pending delete into a rotation that keeps the files the
                    // previous run's disable promised away (flagged by Codex
                    // on PR #68). The re-enable's own task runs after this one
                    // on the FIFO worker and finds a clean directory.
                    if (!enabled) {
                        // Off means nothing is kept — including whatever a
                        // run under the old setting left behind (SPEC.md §4.6).
                        val allDeleted = deleteEverything()
                        runCatching { onDisabled(allDeleted) }
                        return@execute
                    }
                    rotate()
                }.onFailure {
                    // runCatching, because this also runs under plain JUnit
                    // where android.util.Log throws — and a logging failure
                    // must never take the worker task with it.
                    runCatching { Log.w(TAG, "Rotating the debug log failed; recording continues.", it) }
                    if (!enabled) runCatching { onDisabled(false) }
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
     *
     * [onDisabled] fires once the delete has actually run — never for an
     * enable, which deletes nothing — so a caller can resync anything that
     * was reading a now-gone crash pin, and so it can learn whether storage
     * actually let every file go: passing `allDeleted = false` through
     * lets a caller tell the user their delete request left something
     * behind, rather than reporting Off as if it had fully succeeded
     * (Codex, PR #89). The delete itself is queued onto this sink's own
     * worker asynchronously, so a caller cannot infer "done" from this call
     * merely returning.
     */
    fun setEnabled(enabled: Boolean, onDisabled: (allDeleted: Boolean) -> Unit = {}) {
        this.enabled = enabled
        if (enabled) return
        runCatching {
            worker.execute {
                val allDeleted = runCatching { deleteEverything() }
                    .onFailure {
                        runCatching { Log.w(TAG, "Deleting the debug log failed; files may remain until eviction.", it) }
                    }
                    .getOrDefault(false)
                runCatching { onDisabled(allDeleted) }
            }
        }.onFailure { runCatching { onDisabled(false) } }
    }

    /** Whether every file was actually gone afterward — see [setEnabled]'s [onDisabled]. */
    private fun deleteEverything(): Boolean {
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
        return leftovers.isEmpty()
    }

    private fun writeSnapshot() {
        if (!enabled) return
        runCatching {
            dir.mkdirs()
            // Trimmed by the log, not by this file, because the trim has to
            // know where the zone-offset anchors are. `snapshot()` synthesizes
            // one ahead of its oldest line; taking a character tail of the
            // joined text cuts that anchor off and leaves every local timestamp
            // beneath it unreadable — the exact failure PR #128 reverted the
            // per-change marker for, reintroduced here by the truncation rather
            // than by the marker (Codex, PR #151). `boundedSnapshot` trims
            // first and anchors after, and charges each anchor against the
            // budget so the file still honors the ceiling it documents.
            // Nothing is pinned in this app yet, so the reserve is zero; a
            // later `pinnedEvent` caller has to raise it or its lines are the
            // first thing the tail drops.
            val text = SnoozeDebugLog
                .boundedSnapshot(pinnedBudgetChars = 0, recentBudgetChars = PERSIST_BUDGET_CHARS)
                .joinToString("\n")
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
                    SnoozeDebugLog.failure(throwable, "uncaught exception on thread %s", safe(thread.name))
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

    /**
     * Whether a crashed run is currently pinned, holding the `previous` slot
     * (SPEC.md §4.6) — what the post-crash banner shows on.
     *
     * [checkSucceeded] is false only when the metadata check itself threw —
     * never when there was simply nothing pinned. Collapsing that failure
     * into a plain `false` read exactly like an honestly-absent pin (Codex,
     * PR #89): the banner would then silently hide a crash that is still
     * genuinely sitting there, unread, with nothing telling the user it
     * could not be checked. A caller that gets `checkSucceeded = false`
     * must leave whatever it already believed alone, not downgrade it.
     */
    fun hasPinnedCrash(onResult: (pinned: Boolean, checkSucceeded: Boolean) -> Unit) {
        runCatching {
            worker.execute {
                // A pin that outlived a refused purge is a pre-migration file:
                // offering its banner leads the user straight to sharing the
                // full rendering the purge exists to remove. Reported as a
                // successful "no pin" rather than a failed check, because it
                // is a real answer — there is nothing this process may show.
                if (legacyPurgePending) {
                    onResult(false, true)
                    return@execute
                }
                val exists = runCatching { crash.exists() }
                    .onFailure { runCatching { Log.w(TAG, "Checking for a pinned crash failed.", it) } }
                    .getOrNull()
                if (exists == null) {
                    onResult(false, false)
                } else {
                    onResult(exists, true)
                }
            }
        }.onFailure { onResult(false, false) }
    }

    /**
     * The run holding the `previous` slot right now, and whether it got there
     * by crashing. Only one of [crash] / [previous] is ever present at once
     * (SPEC.md §4.6, "the pin holds the previous slot"), so reading whichever
     * exists is exactly the file the sharing surface should offer.
     *
     * [readSucceeded] is false only when the file existed a moment ago but
     * `readText()` genuinely couldn't read it for some retry-worthy reason
     * — never when there was simply nothing to read, and never when the
     * file has vanished entirely (checked missing, or gone by the time
     * `readText()` runs — confirmed by re-checking existence after the
     * failure, not by the exception's type, since the JVM throws the same
     * `FileNotFoundException` for a path that exists but isn't an ordinary
     * readable file): a cache file reclaimed under storage pressure
     * between two checks is gone for good, so `SPEC.md` §4.6 says that
     * must read as confirmed absence (`wasCrash = false`,
     * `readSucceeded = true`), not a failure a retry could ever fix
     * (Codex, PR #89, several rounds). A caller deciding whether it is
     * safe to consume a crash pin needs the surviving distinction: a
     * `null` text because there was nothing there (ever, or not anymore)
     * means there was nothing to lose, but a `null` text because a real,
     * still-pinned crash file couldn't be read for some other reason means
     * the shared report silently omitted it.
     */
    fun readPreviousOrCrash(onResult: (text: String?, wasCrash: Boolean, readSucceeded: Boolean) -> Unit) {
        runCatching {
            worker.execute {
                // Same gate as [hasPinnedCrash], and the one that actually
                // matters: this is the call that puts a prior run's text into
                // a share. While a purge is unproven, whatever is in those
                // slots may be the old full rendering, so there is nothing to
                // give. A successful empty read, not a failed one — the share
                // still goes out carrying this run's own reduced log.
                if (legacyPurgePending) {
                    onResult(null, false, true)
                    return@execute
                }
                val pinned = runCatching { crash.exists() }
                    .onFailure { runCatching { Log.w(TAG, "Checking for a pinned crash failed.", it) } }
                    .getOrNull()
                if (pinned == null) {
                    // An exception here would otherwise escape this
                    // Runnable entirely, on the worker thread, never
                    // reaching onResult — every caller would then wait out
                    // its own timeout and log a misleading "timed out"
                    // instead of the real storage failure (Codex, PR #89).
                    onResult(null, false, false)
                    return@execute
                }
                val (file, wasCrash) = if (pinned) crash to true else previous to false
                val fileExists = runCatching { file.exists() }
                    .onFailure { runCatching { Log.w(TAG, "Checking for the previous run's debug log failed.", it) } }
                    .getOrNull()
                if (fileExists == null) {
                    // Same escape the crash-pin check above already guards
                    // against: an unlogged getOrDefault(false) here would
                    // read identically to a genuinely absent file, and
                    // readSucceeded would then compute true — reporting a
                    // failed check as a clean empty read (Codex, PR #89).
                    onResult(null, wasCrash, false)
                    return@execute
                }
                if (!fileExists) {
                    // The file was pinned a moment ago (the check above)
                    // but is gone now — reclaimed from cacheDir under
                    // storage pressure in the gap between the two checks
                    // (SPEC.md §4.6). wasCrash reports false here, not the
                    // stale true from the check that no longer holds: a
                    // vanished cache file is confirmed absence, not a
                    // pinned crash whose report should say so (Codex,
                    // PR #89, fresh evidence).
                    onResult(null, false, true)
                    return@execute
                }
                val readResult = runCatching { file.readText() }
                // The same race can land here instead — the file existed
                // at the check just above but is gone by the time this
                // read actually runs, the same permanent, non-retry-worthy
                // eviction as the !fileExists branch above. Confirmed by
                // re-checking existence rather than trusting the
                // exception's type: a `FileNotFoundException` isn't a
                // reliable signal for "gone" on its own — the JVM throws
                // the identical exception for a path that exists but isn't
                // an ordinary readable file (a directory sitting where a
                // file is expected, covered by the sibling test just
                // above), which is a genuinely different, retry-irrelevant
                // failure that must keep the loud "try again" this file's
                // other read failures still get (Codex, PR #89, fresh
                // evidence, and the test regression that first version of
                // this fix introduced).
                val vanished = readResult.isFailure && runCatching { !file.exists() }
                    .onFailure { runCatching { Log.w(TAG, "Re-checking the previous run's debug log after a failed read also failed.", it) } }
                    .getOrDefault(false)
                if (readResult.isFailure && !vanished) {
                    runCatching { Log.w(TAG, "Reading the previous run's debug log failed.", readResult.exceptionOrNull()) }
                }
                onResult(readResult.getOrNull(), wasCrash && !vanished, vanished || readResult.isSuccess)
            }
            // The task itself couldn't even be submitted (worker rejected
            // it) — unlike a plain empty read, this means we truly don't
            // know whether a crash is pinned, so it must not read as safe.
        }.onFailure { onResult(null, false, false) }
    }

    /**
     * Consumes the crash pin — the rename both a landed Share and Dismiss
     * perform (SPEC.md §4.6): `crash.log` becomes an ordinary `previous.log`,
     * shareable and rotated away like any other run from here on. A no-op,
     * reported as success, when there is nothing pinned to consume — the
     * caller does not have to know which case it is in.
     */
    fun consumeCrashPin(onResult: (Boolean) -> Unit) {
        runCatching {
            worker.execute {
                val consumed = runCatching {
                    if (!crash.exists()) return@runCatching true
                    // A rename onto an existing file replaces it, so nothing
                    // needs pre-deleting — and nothing should exist there
                    // anyway while a pin holds the slot. Same fallback shape
                    // as `rotate()`'s own renames, for a filesystem that
                    // refuses cross-name renames.
                    if (crash.renameTo(previous)) {
                        true
                    } else {
                        crash.copyTo(previous, overwrite = true)
                        // The copy landing is necessary but not sufficient:
                        // `delete()` reports refusal by returning false, not
                        // by throwing, so `.isSuccess` alone would call a
                        // refused delete "consumed" while `crash.log` is
                        // still sitting there — reappearing as pinned again
                        // after a restart with nothing saying why (Codex,
                        // PR #89). This reads the delete's own result.
                        crash.delete()
                    }
                }
                    // The three ops above can each throw (a full disk, a
                    // permission change mid-write), and an unguarded
                    // `getOrDefault` discarded that exception the same way
                    // it discards an honest `false` from the delete's own
                    // result above — losing exactly the diagnostic a
                    // storage failure needs (Codex, PR #89).
                    .onFailure { runCatching { Log.w(TAG, "Consuming the crash pin threw.", it) } }
                    .getOrDefault(false)
                if (!consumed) {
                    runCatching { Log.w(TAG, "Could not consume the crash pin; it stays pinned for a retry.") }
                }
                onResult(consumed)
            }
        }.onFailure { onResult(false) }
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
     * that finds [sink] still null can retry the installation rather than
     * only reporting that it couldn't check.
     *
     * `install()` failing leaves [sink] null for the process's whole life,
     * and the crash-pin reads below then report a failed check forever — a
     * genuinely pinned crash stays invisible with nothing able to heal it
     * (Codex, PR #89, the fourth finding on this pattern). Since `install()`
     * is already idempotent (it returns early when [sink] is set), retrying
     * it from those reads is enough: a transient failure heals on the next
     * read, and a permanent one still reports honestly.
     *
     * Set synchronously by [install] rather than on the worker, so the retry
     * is available even if the installation task itself never ran. Null only
     * before [install] has ever been called, which is when there is nothing
     * to retry.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * Whether the most recently completed toggle write was refused by storage.
     *
     * Process-level rather than screen-level, deliberately: the completion
     * callback closes over the activity that made the tap, and a configuration
     * change mid-write hands the screen to a replacement that callback cannot
     * reach — the failure explanation would vanish with the dead instance
     * (flagged by Codex on PR #68). Any instance reads this instead. Written
     * only on the FIFO worker, so after the queue drains it is the *latest*
     * write's outcome — a failure superseded by a later success reads false.
     */
    @Volatile
    var lastSaveRefused: Boolean = false
        private set

    /**
     * Whether the most recent Off toggle left one or more files undeleted.
     *
     * Off is supposed to mean "nothing is kept" (SPEC.md §4.6); a refused
     * delete leaves real files on disk while the switch and every other
     * signal read as if it fully succeeded, with nothing telling the user
     * their delete request only partly landed (Codex, PR #89). Read
     * alongside [watchCrashPinOutcome] — it fires at the same point, once
     * the delete this reports on has actually finished — never on its own,
     * since nothing else notifies a change to this field.
     */
    @Volatile
    var lastDisableCleanupFailed: Boolean = false
        private set

    /**
     * Guards which sink-disable callback (from [setEnabled]'s own disable
     * branch, or [install]'s startup retry) may still apply its own outcome
     * to [lastDisableCleanupFailed].
     *
     * [setEnabled]'s Off/On calls are ordered on [worker], but the delete
     * they trigger runs on the *sink's own separate* worker, asynchronously
     * — so a quick Off-then-On could have the re-enable clear
     * [lastDisableCleanupFailed] first, only for the Off call's own
     * still-in-flight delete to complete afterward and overwrite it with
     * `true`, showing a cleanup failure under a switch the user has already
     * turned back on (Codex, PR #89, second round on this field). Every
     * toggle bumps this before dispatching to the sink; a callback whose
     * captured generation no longer matches was superseded by a later
     * toggle and discards its own outcome instead of applying it.
     */
    @Volatile
    private var disableGeneration = 0

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
     * This exists because a `SharedPreferences` listener cannot carry the
     * outcome: `commit()` dispatches its change notification *before* this
     * object learns whether the disk write succeeded, so a listener woken by
     * the value change can read the new value paired with the previous
     * write's outcome (flagged by Codex on PR #68). This callback fires after
     * both are final, and it is also the only notification needed — nothing
     * but these writes changes the setting.
     *
     * Closing compares identity so an old instance's deferred close cannot
     * evict the replacement that registered before it (`onStop` runs after
     * the new activity's `onStart` on a configuration change).
     */
    fun watchSaveOutcome(onChange: () -> Unit): AutoCloseable {
        onSaveOutcome = onChange
        return AutoCloseable { if (onSaveOutcome === onChange) onSaveOutcome = null }
    }

    // One FIFO daemon worker for installing and for applying the toggle,
    // replacing a bare install thread: the setting now applies in the order
    // the calls were made, so a toggle can never be overwritten by an install
    // still holding the older stored value it read before the tap (deferred
    // from Codex's PR #62 review). Same shape as the sink's own worker.
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "snoozemo-debug-log-init").apply { isDaemon = true } }

    /**
     * Call once from `Application.onCreate`. Runs off the caller's thread, like
     * [SnoozeNotifications.warm] and for the same reason: the store read is a
     * preferences file load and the version read is a `PackageManager` call,
     * and `onCreate` sits ahead of a possible cold tile tap. Entries recorded
     * before the sinks register land in the buffer and reach the file with the
     * next entry after, so nothing is lost to the deferral.
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
     * This exists because `install()` only enqueues its work: on the calling
     * thread it returns before the gate is set, and [SnoozeDebugLog] starts
     * with recording on. Anything that collects and records on its own thread
     * can therefore win that race and record while the user's setting says Off
     * (Codex, PR #125). FIFO ordering on this worker is what rules that out —
     * cheaper and harder to get wrong than re-reading the store.
     *
     * FIFO alone proves only that installation was *attempted*. [installNow]
     * contains its body in a `runCatching`, so a preferences read that throws
     * leaves it returning normally with recording still permissive — so this
     * also declines to run [task] unless [gateApplied] says the stored setting
     * actually took effect. Failing closed is the right way round: not knowing
     * whether the user's choice is in force is not a reason to collect anyway.
     *
     * And when that setting turned out to be **Off**, [task] is skipped rather
     * than run with its writes discarded. `gateApplied` means "the choice was
     * read", not "recording is on"; running the work regardless would leave the
     * app gathering what the user asked it not to, which is the opt-out failing
     * in substance while succeeding on paper.
     *
     * Call only after [install]. A task queued before it would run first and
     * inherit exactly the race this avoids.
     */
    fun afterRecordingGateApplied(task: () -> Unit) {
        try {
            worker.execute {
                if (!gateApplied) {
                    // Fail closed. Not knowing whether the user's choice is in
                    // force is not a reason to collect anyway, and the reason
                    // this ran at all is worth a line — a silently absent
                    // section otherwise reads like a feature never wired up.
                    runCatching {
                        SnoozeDebugLog.warning("deferred task skipped: recording gate never applied")
                    }
                    return@execute
                }
                if (!SnoozeDebugLog.isRecording) {
                    // The gate applied and said Off. Skipping the task, not just
                    // discarding what it writes: [gateApplied] means the stored
                    // choice was read, and running the work anyway would have the
                    // app still gathering what the user asked it not to, with only
                    // the writes dropped (Codex, PR #125). No line here — the log
                    // is off, and one would be the very thing being declined.
                    return@execute
                }
                task()
            }
        } catch (e: RuntimeException) {
            // The queue refusing work is the one case where the task simply
            // never runs; say so rather than leaving a silently absent section
            // that reads like a feature that was never wired up.
            runCatching { SnoozeDebugLog.failure(e, "debug-log worker refused a deferred task") }
        } catch (e: Error) {
            // Submission can fail fatally too — an OutOfMemoryError creating the
            // thread. Reporting that and returning normally would hide it from
            // the uncaught-exception handler and leave the process running
            // compromised, and the task body's own Error handling never gets to
            // run because the body never started (Codex, PR #125).
            runCatching { SnoozeDebugLog.failure(e, "debug-log worker submission hit a fatal error") }
            throw e
        }
    }

    /**
     * [install]'s body, on the worker thread. Separate so the crash-pin reads
     * below can retry it inline when they find [sink] null — they already run
     * on this same single-threaded worker, so they must call this directly
     * rather than submitting another task and waiting on it.
     */
    private fun installNow(app: Context) {
        if (sink != null) return
        runCatching {
            val store = DebugLogStore(app)
            val enabled = store.isEnabled()
            // The recording gate first, so a disabled install stops
            // collecting — and drops whatever was buffered before this
            // read — rather than only not persisting (Codex, PR #62).
            SnoozeDebugLog.setRecording(enabled)
            // Only now is the user's stored choice actually in force. Anything
            // that defers itself behind installation needs to know that this
            // line was reached, not merely that installNow ran: the read above
            // can throw, and the runCatching around this block would then
            // return normally with recording still at its permissive default
            // (Codex, PR #125).
            gateApplied = true
            val fileSink = DebugFileSink(app)
            // Captured for the same reason [setEnabled]'s own
            // dispatch does: a manual toggle racing this startup
            // retry must be able to supersede it too.
            val generation = disableGeneration
            fileSink.start(
                enabled = enabled,
                // One start after the move to the shared logger, and every
                // start after that until it is proven done. Reading the flag
                // here rather than inside the sink keeps the sink free of
                // SharedPreferences, which is what lets its tests build one
                // from a bare directory.
                purgeLegacy = !store.hasPurgedLegacyLogs(),
                onLegacyPurged = { purged ->
                    // Only a proven purge is recorded, and only a recorded one
                    // is trusted. A refused write leaves the flag clear, so the
                    // next start purges again over this version's own logs —
                    // said out loud here rather than discovered as a missing
                    // run, since nothing downstream can tell that loss from an
                    // ordinary empty history.
                    //
                    // Every fallible step in this migration maps a throw onto
                    // the same outcome as a negative return — not proven — and
                    // reports it. `commit()` can throw as well as return false,
                    // and an escaping exception here would be swallowed by the
                    // `runCatching` around this very callback, losing both
                    // warnings on the one path that most needs them (Codex,
                    // PR #151).
                    val recorded = purged && runCatching { store.markLegacyLogsPurged() }
                        .onFailure {
                            runCatching {
                                Log.w(TAG, "Recording the debug-log migration threw.", it)
                            }
                        }
                        .getOrDefault(false)
                    if (purged && !recorded) {
                        runCatching {
                            Log.w(TAG, "Could not record the debug-log migration; the next start may re-purge.")
                        }
                        runCatching {
                            SnoozeDebugLog.warning(
                                "could not record that old logs were cleared, so this run's log may be cleared again",
                            )
                        }
                    }
                },
            ) { allDeleted ->
                // Same assign-then-notify ordering as the toggle path
                // below, and the same reason: a reader woken by the
                // watch must see this attempt's own outcome. Nothing
                // is registered to hear it yet this early at process
                // start — MainActivity's own onStart/first-frame reads
                // (Codex, PR #89) are what actually pick this up,
                // exactly as they already do for a pin left over from
                // before this process started. Only the
                // cleanup-failure verdict is guarded by the
                // generation, same reason as the toggle path below —
                // whether crash.log still exists is always this
                // callback's own real answer regardless of which
                // generation asked for the delete.
                if (generation == disableGeneration) {
                    lastDisableCleanupFailed = !allDeleted
                }
                runCatching { onCrashPinOutcome?.invoke() }
            }
            SnoozeDebugLog.addSink(fileSink)
            SnoozeDebugLog.addSink { line -> runCatching { Log.d(TAG, line) } }
            sink = fileSink
            // Build, device, and Android version (SPEC.md §4.6) —
            // first, so every later line reads against the software it
            // ran on.
            logRunContext(app)
        }.onFailure { runCatching { Log.w(TAG, "Installing the debug log failed; logcat still records.", it) } }
    }

    /**
     * Retries [installNow] when [sink] is null, so a failed or not-yet-run
     * installation can heal instead of reporting a failed check forever
     * (Codex, PR #89). Already on the worker thread; a no-op when [install]
     * has never been called, which is when there is nothing to retry.
     */
    private fun reinstallIfNeeded() {
        if (sink != null) return
        appContext?.let { installNow(it) }
    }

    /**
     * The settings switch's entry point: persists the choice, applies what
     * persisted — off deletes what was kept — and answers [onApplied] with
     * whether the write reached disk, on the worker's thread.
     *
     * Only a *persisted* choice is applied, deliberately: a change the storage
     * refused would otherwise look applied and silently revert at the next
     * process start (deferred from Codex's PR #62 review). Refusing to apply
     * it keeps the row, the recording gate, and the disk telling one story,
     * and the caller puts the switch back where the truth is.
     */
    fun setEnabled(context: Context, enabled: Boolean, onApplied: (persisted: Boolean) -> Unit) {
        val app = context.applicationContext
        runCatching {
            worker.execute {
                // Logged, not just reported as false: the switch reverting
                // tells the user, but only this line tells the next reader
                // *why* the save failed. The exception is a preferences write
                // failure and carries no user data.
                val persisted = runCatching { DebugLogStore(app).setEnabled(enabled) }
                    .onFailure { runCatching { Log.w(TAG, "Saving the debug-log setting failed; the switch reverts.", it) } }
                    .getOrDefault(false)
                if (persisted) {
                    // Off gates recording itself, not just persistence: the
                    // buffer stops collecting and is emptied, so nothing
                    // captured while off can be written to disk by a later
                    // re-enable (Codex, PR #62).
                    SnoozeDebugLog.setRecording(enabled)
                    // Disabling deletes any pinned crash along with the rest
                    // (SPEC.md §4.6), but that delete runs asynchronously on
                    // the sink's own worker — without this, a crash banner
                    // already on screen kept offering to share a crash file
                    // that no longer existed, with nothing to notice the
                    // pin was gone until the activity was recreated (Codex,
                    // PR #89). The watch's own observer re-reads
                    // hasPinnedCrash, so firing it after every disable is
                    // safe even when nothing was actually pinned.
                    // Every toggle invalidates whatever earlier disable's
                    // sink-callback might still be in flight on the sink's
                    // own separate worker — see [disableGeneration]'s own
                    // comment.
                    val generation = ++disableGeneration
                    if (enabled) {
                        // A leftover from an earlier disable is retried at
                        // every start and every subsequent disable
                        // (deleteEverything's own durable-retry contract);
                        // a re-enable is what actually resolves it going
                        // forward, so this is the point that must clear it
                        // — otherwise it never resets, and any later
                        // restart's unconditional read would show "some
                        // files couldn't be deleted" under a switch that is
                        // now On (Codex, PR #89).
                        lastDisableCleanupFailed = false
                        sink?.setEnabled(true)
                    } else {
                        sink?.setEnabled(false) { allDeleted ->
                            // A superseded generation means a later toggle
                            // (of either direction) already ran while this
                            // delete was still in flight — most often a
                            // quick re-enable, whose own clear this must not
                            // overwrite (Codex, PR #89, second round). Only
                            // the cleanup-failure verdict is guarded by it:
                            // whether crash.log itself still exists is a
                            // fact this callback always actually knows,
                            // independent of which toggle asked for the
                            // delete, and gating the watch fire on the same
                            // check too suppressed it whenever a quick
                            // Off-then-On raced this delete — leaving a
                            // crash banner offering to share a file this
                            // delete had already removed, with no later
                            // check left to notice in the same activity
                            // (Codex, PR #89, third round on this field).
                            if (generation == disableGeneration) {
                                // Assigned before the watch fires, same
                                // ordering discipline as lastSaveRefused
                                // just below — a reader woken by the watch
                                // must see this attempt's own outcome, not
                                // a stale one from before it.
                                lastDisableCleanupFailed = !allDeleted
                            }
                            runCatching { onCrashPinOutcome?.invoke() }
                        }
                    }
                }
                // A re-enable's log starts from an emptied buffer — disabling
                // dropped everything, the run-context line included — so the
                // context is restated, or nothing after this point says what
                // software it ran on (SPEC.md §4.6; flagged by Codex on
                // PR #68).
                if (persisted && enabled) logRunContext(app)
                // Assign first, then tell the screen: the watch fires only
                // once the outcome it will read is this write's.
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
     * Whether a crashed run is currently pinned (SPEC.md §4.6). See
     * [DebugFileSink.hasPinnedCrash] for [checkSucceeded] — no [sink] yet
     * reports a failed check too, the same as that check throwing: a
     * crash.log from a previous run exists independently of whether
     * [install] has finished in *this* process, so a missing sink is never
     * confirmed absence, only "not checked yet" — indistinguishable, until
     * now, from a genuine installation failure that leaves [sink] null
     * forever (Codex, PR #89). The ordinary startup race (a read landing
     * before [install]'s own worker task has finished) self-heals through
     * the same retry [MainActivity] already applies to any other failed
     * check.
     */
    fun hasPinnedCrash(onResult: (pinned: Boolean, checkSucceeded: Boolean) -> Unit) {
        runCatching {
            worker.execute {
                reinstallIfNeeded()
                sink?.hasPinnedCrash(onResult) ?: onResult(false, false)
            }
        }.onFailure { onResult(false, false) }
    }

    /**
     * See [DebugFileSink.readPreviousOrCrash] and [hasPinnedCrash]'s own
     * comment on why a missing [sink] reports a failed read, not a clean
     * empty one (Codex, PR #89): a pinned crash from a previous run cannot
     * be told apart from "genuinely nothing to report" if this process
     * never got as far as checking.
     */
    fun readPreviousOrCrash(onResult: (text: String?, wasCrash: Boolean, readSucceeded: Boolean) -> Unit) {
        runCatching {
            worker.execute {
                reinstallIfNeeded()
                sink?.readPreviousOrCrash(onResult) ?: onResult(null, false, false)
            }
        }.onFailure { onResult(null, false, false) }
    }

    /**
     * The current screen's ear for a completed [consumeCrashPin] — a direct
     * Dismiss, or the one a landed `DebugReport.share` performs. Mirrors
     * [watchSaveOutcome] and exists for the identical reason (flagged by
     * Codex on PR #89): a configuration change can recreate the activity
     * while the consuming call is still on this worker, and the completion
     * callback that started it closes over the now-dead instance — updating
     * it is invisible to the user. The replacement registers here before the
     * old instance unregisters, so it is always the one that hears this.
     *
     * Deliberately carries no value: the observer re-reads [hasPinnedCrash]
     * itself, so it always answers with the current truth regardless of
     * which instance's tap triggered the completion, the same way
     * [watchSaveOutcome]'s observer re-reads [DebugLogStore] rather than
     * trusting a captured one.
     */
    fun watchCrashPinOutcome(onChange: () -> Unit): AutoCloseable {
        onCrashPinOutcome = onChange
        return AutoCloseable { if (onCrashPinOutcome === onChange) onCrashPinOutcome = null }
    }

    @Volatile
    private var onCrashPinOutcome: (() -> Unit)? = null

    /**
     * See [DebugFileSink.consumeCrashPin]. Reported as success before
     * [install] has run — there is nothing pinned to consume.
     */
    fun consumeCrashPin(onResult: (Boolean) -> Unit) {
        runCatching {
            worker.execute {
                val notifyAndReport: (Boolean) -> Unit = { consumed ->
                    runCatching { onCrashPinOutcome?.invoke() }
                    onResult(consumed)
                }
                sink?.consumeCrashPin(notifyAndReport) ?: notifyAndReport(true)
            }
        }.onFailure { onResult(false) }
    }

    /**
     * Whether the most recently completed [dismissCrashPin] was refused by
     * the file layer — distinct from a Share's own [consumeCrashPin] call,
     * whose refusal is deliberately not surfaced (`DebugReportShareTest`,
     * "a share that fails to consume the pin still reports what it
     * delivered"): a Share that reached the user already told them
     * something happened, but a Dismiss tap that silently does nothing
     * leaves no explanation at all (Codex, PR #89). Process-level, for the
     * same dead-instance reason as [lastSaveRefused] and
     * `DebugReport.lastShareFailed`.
     */
    @Volatile
    var lastDismissFailed: Boolean = false
        private set

    @Volatile
    private var onDismissOutcome: (() -> Unit)? = null

    /** Mirrors [watchCrashPinOutcome] / `DebugReport.watchShareOutcome`; see [lastDismissFailed]. */
    fun watchDismissOutcome(onChange: () -> Unit): AutoCloseable {
        onDismissOutcome = onChange
        return AutoCloseable { if (onDismissOutcome === onChange) onDismissOutcome = null }
    }

    /**
     * Dismisses the crash banner directly (SPEC.md §4.6): consumes the pin
     * and records whether the file layer actually let it happen, for
     * [watchDismissOutcome] to surface. [watchCrashPinOutcome] still fires
     * too, from the [consumeCrashPin] call this makes — that resync of
     * [hasPinnedCrash] is unconditional and correct either way, since a
     * refused consume really does leave the crash still pinned.
     *
     * Clears [lastDismissFailed] up front, before the consume even starts:
     * a caller only clears its own local failure flag when it starts a
     * retry, and a configuration change or restart mid-retry would
     * otherwise have the replacement instance's `onStart` reload the
     * *previous* attempt's still-true process-level outcome and show
     * "Couldn't dismiss" again for a tap that already superseded it (Codex,
     * PR #89).
     */
    fun dismissCrashPin() {
        lastDismissFailed = false
        consumeCrashPin { consumed ->
            lastDismissFailed = !consumed
            runCatching { onDismissOutcome?.invoke() }
        }
    }

    /**
     * Test-only: blocks until the worker drains, install and toggles
     * included — and, once a sink exists, until *its* worker drains too, so a
     * test that just asked for a file read or a toggle can trust the result
     * is in before this returns. The read runs on this worker itself, which
     * is where [sink] may safely be read.
     */
    internal fun awaitIdleForTest() {
        worker.submit { sink?.awaitIdleForTest() }.get()
    }

    /** Test-only: forgets the installed sink so the next install runs fresh. */
    internal fun resetForTest() {
        onSaveOutcome = null
        onCrashPinOutcome = null
        onDismissOutcome = null
        lastDismissFailed = false
        worker.submit {
            // Drain the outgoing sink's *own* worker before orphaning it.
            // Nulling the field below stops nothing that is already queued on
            // that private thread, and the queue is not always empty: an
            // install that read the stored setting as **off** has a whole
            // `deleteEverything()` pending (see `DebugFileSink.start`). Left
            // running, that delete lands after the *next* test has written its
            // fixture files and wipes them, so its own `install` finds an empty
            // directory — the failure reads "install left [current.log] with
            // the log on", with nothing in the test body to explain it, because
            // by then `@Before` has already set the setting back to on.
            //
            // `SnoozemoApplication.onCreate` is what makes this reachable
            // rather than theoretical: it installs the log for every
            // Robolectric test, so each test inherits a sink started under
            // whatever setting the previous test left behind — and this class
            // deliberately writes the setting off partway through.
            //
            // Draining here removes the race by construction instead of
            // narrowing the window.
            sink?.awaitIdleForTest()
            sink = null
            // Cleared alongside the sink: leaving it set would let a read in
            // the next test retry the installation and quietly succeed,
            // which is exactly the pre-install state those tests exist to
            // pin. Together these restore "install() has never been called".
            appContext = null
            lastSaveRefused = false
            lastDisableCleanupFailed = false
            disableGeneration = 0
        }.get()
    }
}
