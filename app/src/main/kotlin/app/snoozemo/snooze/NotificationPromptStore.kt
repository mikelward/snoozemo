package app.snoozemo.snooze

import android.content.Context

/**
 * Remembers that a `POST_NOTIFICATIONS` request has actually been **denied**.
 *
 * The platform offers no way to ask this, and it is the difference between the
 * two states that look identical from outside the app: a permission never
 * denied and one denied as often as the system allows both report
 * `shouldShowRequestPermissionRationale() == false`. Reading the second as the
 * first leaves the app screen offering a prompt the system will silently ignore
 * — a tap that does nothing, which is what this flow was fixed for.
 *
 * A **denial**, not a request, and the distinction has teeth: a dialog the user
 * swipes away is not counted by the system either, so remembering the launch
 * would strand a user who pressed back once — sent to Settings by the screen,
 * never asked again by the tile, for a dialog that was still available.
 * `shouldShowRequestPermissionRationale` going true is the only signal the
 * platform gives that a denial landed, so that is what gets recorded, and it is
 * recorded when it is *read* rather than when anything is launched.
 *
 * Written by every surface that reads the permission. The tile can be added
 * straight from the Quick Settings editor, so the trampoline may see both
 * denials before the app screen is ever opened; a flag only the screen wrote
 * would be false on a permission that can no longer be asked for.
 *
 * `SharedPreferences` for the same reason the snooze record uses it — this is
 * read while deciding what to draw, so it must not cost a coroutine or a disk
 * wait — and it holds one boolean about the app's own history. Nothing about
 * the user, the place, or the time is written here.
 */
class NotificationPromptStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Folds a fresh reading of the platform into the remembered history.
     *
     * A grant **clears** it rather than leaving it set: granting resets the
     * denial history the system counts, so a permission later revoked from
     * Settings starts again with its prompts available, and a stale flag would
     * have the app send that user back to Settings instead of asking.
     */
    fun record(granted: Boolean, rationale: Boolean) {
        when {
            granted -> prefs.edit().remove(KEY_EVER_DENIED).apply()
            rationale -> prefs.edit().putBoolean(KEY_EVER_DENIED, true).apply()
            // Neither: nothing new was learned. A `false` here would be the bug
            // — it is what a never-denied permission and a permanently denied
            // one both look like.
            else -> Unit
        }
    }

    /** Whether a denial has ever been observed since the last grant. */
    fun everDenied(): Boolean = prefs.getBoolean(KEY_EVER_DENIED, false)

    private companion object {
        const val FILE_NAME = "notification_prompt"
        const val KEY_EVER_DENIED = "ever_denied"
    }
}
