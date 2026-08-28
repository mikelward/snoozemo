package app.snoozemo.crash

import android.content.Context

/**
 * Remembers whether the user has left crash reporting on (`SPEC.md` §12).
 *
 * **Defaults to off** (maintainer, 2026-08-28), reversing §12's original
 * on-by-default decision: reporting leaves the device, so it waits for the
 * user's explicit agreement. Nothing here decides whether a reporter exists to
 * be turned on; that is the flavor's answer (`CrashReporter`), and `direct`
 * has none.
 *
 * `SharedPreferences`, like the other one-key stores in this app — read while
 * deciding what to draw, so it must not cost a coroutine or a disk wait — and
 * it holds one boolean about the app's own diagnostics. Nothing about the
 * user, the place, or the time is written here.
 */
internal class CrashReportingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /**
     * Persists the choice, returning whether the write reached disk.
     *
     * On a refused write the old value is put back first, exactly as
     * `DebugLogStore` does and for the same reason: `commit()` applies the
     * change to the process-local map *before* the disk write it reports on,
     * so without the restore every later read would return a value that was
     * neither applied nor stored — a switch reading `off` over a reporter
     * still collecting, until a process restart flipped it back. The
     * restore's own disk write may fail too; the map is restored regardless,
     * which is the part every reader sees.
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
     * Loads the preferences file so the first real read is a memory hit.
     * Called from `Application.onCreate` off the main thread, like the other
     * warmed stores on the arm path (`SPEC.md` §4.1).
     */
    fun warm() {
        isEnabled()
    }

    private companion object {
        const val FILE_NAME = "crash_reporting"
        const val KEY_ENABLED = "enabled"
    }
}
