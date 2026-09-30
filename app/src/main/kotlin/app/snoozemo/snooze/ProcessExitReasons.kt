package app.snoozemo.snooze

import android.content.Context
import app.snoozemo.core.SnoozeDebugLog
import com.mikelward.androidlog.android.ProcessExits
import kotlinx.coroutines.CancellationException

/**
 * Records why this app's recent processes ended, through the shared
 * [ProcessExits] in androidlog.
 *
 * An uncaught exception is the only process death the app observes from the
 * inside, and the crash pin already records it. Every other way a process ends
 * leaves no in-process trace at all — an ANR, a native crash, an out-of-memory
 * reclaim, the installer stopping the app to swap the APK, or an OEM's
 * app-standby killing it. From the next run's point of view those are
 * indistinguishable from each other and from a clean exit.
 *
 * That gap is sharper here than in most apps. **A snooze that never ended is
 * principle 1's failure**, and "the process was killed and nothing restored the
 * watch" is one of the ways it happens — but today it leaves nothing behind, so
 * a user reporting a phone that stayed silent hands over a log that simply
 * restarts. The duration cap is what saves them; this is what explains it
 * afterwards. On a Samsung, distinguishing an OEM standby kill from a crash of
 * ours is exactly the question `TODO.md`'s hardware-verification list keeps
 * running into.
 *
 * The debug log is **on by default** (`SPEC.md` §4.6, `docs/PRIVACY.md`),
 * precisely because the failures worth diagnosing happen once and without
 * warning — so these records exist for the run that actually went wrong rather
 * than only after someone thinks to turn logging on. Turning the log off stops
 * them and deletes what it kept, like everything else it holds. Because they
 * are collected by default, the fields are disclosed in `docs/PRIVACY.md`
 * alongside the rest of the log's contents.
 *
 * The platform's description is included (`SPEC.md` §4.6 lists it): it is
 * system-composed and stays in the device's own copy of the log.
 */
internal fun logRecentProcessExits(context: Context) {
    ProcessExits.logRecent(context, SnoozeDebugLog, includeDescription = true)
}

/**
 * Reads the exit records on the debug log's own installation worker.
 *
 * Two things have to hold, and this ordering is what gives both. The query is
 * an `ActivityManager` binder call and `Application.onCreate` is the arm path's
 * immediate neighbour — a cold tile tap reaches the zen rule id within
 * milliseconds of it returning (`SPEC.md` §4.1) — so it must not run on the
 * calling thread. And it must not run before [DebugLogging.install] has applied
 * the stored setting to the recording gate: `install()` only *enqueues* that
 * work, and [SnoozeDebugLog] starts with recording on, so a collector on its
 * own thread can win the race and record while the user's setting says Off
 * (Codex, PR #125).
 *
 * Queuing on the same single-threaded worker satisfies both at once: it is off
 * the caller's thread, and FIFO puts it after installation. FIFO alone only
 * proves installation was *attempted*, though — its body is contained in a
 * `runCatching`, so a failed preferences read returns normally with recording
 * still permissive — so [DebugLogging.afterRecordingGateApplied] additionally
 * declines to run this at all unless the stored setting was actually applied
 * (Codex, PR #125). Must still be called after [DebugLogging.install], never
 * before.
 */
internal fun logRecentProcessExitsInBackground(context: Context) {
    val appContext = context.applicationContext
    DebugLogging.afterRecordingGateApplied {
        try {
            logRecentProcessExits(appContext)
        } catch (e: CancellationException) {
            // Structured concurrency: never swallowed, never reported as a
            // failure of ours.
            throw e
        } catch (e: Error) {
            // An allocation or linkage failure is not this diagnostic's to
            // absorb. Reporting it and returning normally would hide a fatal
            // condition from the uncaught-exception handler and from Android's
            // own exit accounting — the very accounting this file reads — while
            // leaving the process running compromised (Codex, PR #125).
            runCatching { SnoozeDebugLog.failure(e, "processExits worker hit a fatal error") }
            throw e
        } catch (e: Exception) {
            // Nothing above this on the worker will report it, and a silently
            // missing section reads exactly like a query that was never wired
            // up — which is the state this whole file exists to end.
            runCatching { SnoozeDebugLog.failure(e, "processExits worker failed") }
        }
    }
}
