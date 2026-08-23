package app.snoozemo.snooze

import android.content.Context

/**
 * Remembers which waiting build the user dismissed the update banner for, so
 * a dismissal silences that one and not the next. Its own tiny preferences
 * file rather than a field in the snooze record: it is device-local UI
 * nagging state, unrelated to anything the snooze lifecycle needs to survive
 * process death or a reboot.
 *
 * `SharedPreferences`, like the other prompt stores in this package — read
 * while deciding what to draw, so it must not cost a coroutine or a disk
 * wait — and it holds one integer about the app's own update history.
 * Nothing about the user, the place, or the time is written here.
 */
class PlayUpdateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 0 when nothing has been dismissed. */
    var dismissedVersionCode: Int
        get() = prefs.getInt(KEY_DISMISSED_VERSION_CODE, 0)
        set(value) {
            prefs.edit().putInt(KEY_DISMISSED_VERSION_CODE, value).apply()
        }

    private companion object {
        const val FILE_NAME = "play_update"
        const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"
    }
}
