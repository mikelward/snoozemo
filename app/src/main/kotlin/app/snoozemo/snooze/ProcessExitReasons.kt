package app.snoozemo.snooze

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import app.snoozemo.core.SnoozeDebugLog
import kotlinx.coroutines.CancellationException

/**
 * How many prior process exits to read. Enough to cover "what happened around
 * the time the snooze misbehaved" without turning startup into a log dump.
 */
private const val MAX_EXIT_RECORDS = 5

/**
 * Records why this app's recent processes ended.
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
 * Ported from the sibling Type Launcher repo deliberately unchanged in shape —
 * same names, same line format — so the logs read alike. See `TODO.md` on
 * aligning the repos' loggers properly.
 */
internal fun logRecentProcessExits(context: Context) {
    val activityManager = context.getSystemService<ActivityManager>() ?: run {
        SnoozeDebugLog.event("processExits unavailable reason=noActivityManager")
        return
    }
    val exits = try {
        // pid 0 means "any process of this package" — asking by pid would miss
        // exactly the abrupt deaths this is here for.
        activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
    } catch (e: RuntimeException) {
        // A denial or a dead system_server leaves us no worse off than before
        // this existed, so report and return rather than letting the failure
        // escape into startup — which on this app is the arm path's neighbour.
        SnoozeDebugLog.warning("processExits query failed", e)
        return
    }
    if (exits.isEmpty()) {
        SnoozeDebugLog.event("processExits none")
    } else {
        logExitRecords(exits)
    }
    // Last, deliberately. The exit records are what this exists to capture and
    // are already in hand by now, so anything that can fail runs only after
    // they are safely in the log, never ahead of them.
    logOwnPackageTimestamps(context)
}

/** See [logRecentProcessExits]; split out so a later failure cannot preempt it. */
private fun logExitRecords(exits: List<ApplicationExitInfo>) {
    // Newest first, which is how the platform returns them and the order a
    // reader wants: the most recent exit is the one that explains this start.
    exits.forEach { info ->
        SnoozeDebugLog.event(
            "processExit reason=${exitReasonName(info.reason)} " +
                "importance=${processImportanceName(info.importance)} " +
                "status=${info.status} timestamp=${info.timestamp} " +
                "description=${info.description}",
        )
    }
}

/**
 * Records when this package was last updated, next to the exit records above.
 *
 * An exit whose timestamp sits alongside the package's own update time means
 * the installer replaced the APK rather than anything going wrong — worth
 * ruling out before treating a snooze that ended early as a bug.
 */
private fun logOwnPackageTimestamps(context: Context) {
    try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        SnoozeDebugLog.event(
            "ownPackage lastUpdateTime=${info.lastUpdateTime} " +
                "firstInstallTime=${info.firstInstallTime}",
        )
    } catch (e: PackageManager.NameNotFoundException) {
        SnoozeDebugLog.warning("ownPackage query failed", e)
    } catch (e: RuntimeException) {
        // The lookup is a binder call, so it can also fail as a RuntimeException
        // — a dead system_server mid-restart being the realistic case, which is
        // exactly the sort of moment this diagnostic is read about. Caught for
        // the same reason as above: this is the optional half and must not take
        // the exit records down with it.
        SnoozeDebugLog.warning("ownPackage query failed", e)
    }
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
            runCatching { SnoozeDebugLog.warning("processExits worker hit a fatal error", e) }
            throw e
        } catch (e: Exception) {
            // Nothing above this on the worker will report it, and a silently
            // missing section reads exactly like a query that was never wired
            // up — which is the state this whole file exists to end.
            runCatching { SnoozeDebugLog.warning("processExits worker failed", e) }
        }
    }
}

/**
 * Maps an [ApplicationExitInfo] reason to a stable, readable name.
 *
 * Named rather than numeric because a shared debug log is read by whoever it
 * reaches, not only by someone with the SDK constants to hand. An unrecognized
 * reason keeps its number so a future platform addition degrades to something
 * still diagnosable instead of collapsing into "unknown".
 */
internal fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashNative"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependencyDied"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessiveResourceUsage"
    ApplicationExitInfo.REASON_EXIT_SELF -> "exitSelf"
    ApplicationExitInfo.REASON_FREEZER -> "freezer"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initializationFailure"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "lowMemory"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "packageStateChange"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "packageUpdated"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permissionChange"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "userRequested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "userStopped"
    else -> "unrecognized($reason)"
}

/**
 * Maps an [ApplicationExitInfo.importance] to a stable, readable name.
 *
 * Importance is the priority Android had assigned the process when it died. It
 * separates a routine background reclaim from a death the system counted as
 * user-aware work — for a snooze, the difference between the ordinary state
 * this app lives in and one where something was actually running.
 *
 * It is **not** proof an Activity was on screen: a broadcast receiver handling
 * a geofence exit, or the foreground service that holds a snooze, both reach
 * foreground importance with nothing visible (Codex, PR #125). This app has
 * several such receivers, so reading `foreground` as "the user was looking at
 * it" would misread most of its records.
 */
internal fun processImportanceName(importance: Int): String = when (importance) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foregroundService"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "topSleeping"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
    else -> "unrecognized($importance)"
}
