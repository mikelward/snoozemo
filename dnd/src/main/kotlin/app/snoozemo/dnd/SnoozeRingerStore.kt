package app.snoozemo.dnd

import android.content.Context
import app.snoozemo.core.SnoozeRinger

/**
 * How loud the user has said a snooze may be (SPEC.md §5.9).
 *
 * Its own one-key `SharedPreferences` file, read on the arm path — after the
 * rule is on, never before it — and warmed at startup like the rule id beside
 * it, so a tile tap finds it in memory.
 *
 * Nothing about the user, the place, or the time is written here: one enum name
 * about how the app behaves.
 *
 * In `:dnd` rather than `:app` because `AndroidZenController.default` builds one
 * and `:tile` constructs that controller too, so a store in `:app` would not be
 * reachable from every caller that needs it.
 */
class SnoozeRingerStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The stored choice, or [SnoozeRinger.DEFAULT] where there is none — which
     * is also the answer for a value this build cannot read, per
     * [SnoozeRinger.named].
     */
    fun chosen(): SnoozeRinger = SnoozeRinger.named(prefs.getString(KEY_CEILING, null))

    /**
     * Pulls the file into memory off the main thread, exactly as
     * [PrefsZenRuleIdStore.warm] does and with the same caveat: it narrows the
     * window rather than closing it, and what it costs when a tap overtakes it
     * is a blocking read *after* the rule is already on rather than before it.
     */
    fun warm() {
        Thread { chosen() }.start()
    }

    /**
     * Persists [ceiling], returning whether the write reached disk.
     *
     * The same restore-on-refusal `EndSheetStore` does, and for the same reason:
     * `commit()` applies the change to the process-local map *before* the disk
     * write it reports on, so without putting the old value back every later
     * read would return a value that was neither applied nor stored — a row
     * reading one way over behavior going the other, until a process restart
     * flipped it back.
     */
    fun setChosen(ceiling: SnoozeRinger): Boolean {
        val before = chosen()
        val persisted = prefs.edit().putString(KEY_CEILING, ceiling.name).commit()
        if (!persisted) {
            prefs.edit().putString(KEY_CEILING, before.name).commit()
        }
        return persisted
    }

    private companion object {
        const val FILE_NAME = "snooze_ringer"
        const val KEY_CEILING = "ceiling"
    }
}
