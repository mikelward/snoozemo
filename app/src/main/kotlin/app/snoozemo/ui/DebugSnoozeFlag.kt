package app.snoozemo.ui

import android.content.Context

/**
 * Remembers, across process death, that a snooze may be running.
 *
 * The zen rule lives in the platform and outlives this app's process, so
 * in-memory state is not enough: an activity recreated after a kill would say
 * "not snoozing" while the phone was still silent, and — worse — would offer no
 * way to end it. That is principle 1's failure reached through a UI bug rather
 * than a sensor one.
 *
 * Deliberately a single boolean and deliberately temporary. Phase 2 replaces it
 * with the real `ActiveSnooze` record persisted on every state transition
 * (`SPEC.md` §11), which carries the anchor, the cap, and the tracking mode as
 * well. This exists so the Phase 1 debug screen cannot lie during the device
 * check it was built for.
 */
class DebugSnoozeFlag(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isSnoozing(): Boolean = prefs.getBoolean(KEY_SNOOZING, false)

    fun set(snoozing: Boolean) {
        prefs.edit().putBoolean(KEY_SNOOZING, snoozing).apply()
    }

    private companion object {
        const val FILE_NAME = "debug_snooze"
        const val KEY_SNOOZING = "snoozing"
    }
}
