package app.snoozemo.snooze

import android.content.Context

/**
 * Remembers that a `READ_CALENDAR` request has actually been **denied**.
 *
 * Exists for exactly the reason [NotificationPromptStore] does, and its own
 * doc is where that reasoning lives: the platform offers no way to ask about
 * the past, and a permission never denied reads identically to one the system
 * has stopped prompting for. Separate from that store rather than sharing its
 * file because the two histories are independent — denying notifications says
 * nothing about the calendar, and a grant clears only its own.
 *
 * One boolean about the app's own history. Nothing about the user, their
 * meetings, or their calendar is written here — this file never sees an event.
 */
class CalendarPromptStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Folds a fresh reading into the history; a grant clears it. */
    fun record(granted: Boolean, rationale: Boolean) {
        when {
            granted -> prefs.edit().remove(KEY_EVER_DENIED).apply()
            rationale -> prefs.edit().putBoolean(KEY_EVER_DENIED, true).apply()
            // Neither reading says anything new — and writing `false` here is
            // the bug, since it is what both ends of the history look like.
            else -> Unit
        }
    }

    /** Whether a denial has ever been observed since the last grant. */
    fun everDenied(): Boolean = prefs.getBoolean(KEY_EVER_DENIED, false)

    private companion object {
        const val FILE_NAME = "calendar_prompt"
        const val KEY_EVER_DENIED = "ever_denied"
    }
}
