package app.snoozemo.snooze

import android.content.Context

/**
 * Remembers that a location permission request has actually been **denied** —
 * foreground (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`) and
 * background (`ACCESS_BACKGROUND_LOCATION`) tracked separately, the same
 * history [LocationPermission.of] takes to tell "never denied" from
 * "permanently denied" apart, and for the same reason
 * [NotificationPromptStore] exists: both readings report
 * `shouldShowRequestPermissionRationale() == false`, so without this the
 * settings row cannot tell a fresh install from a permission the system has
 * stopped prompting for.
 *
 * A **denial**, not a request: a dialog the user swipes away is not counted
 * by the system either, so remembering the launch would strand a user who
 * pressed back once — sent to Settings, never asked again, for a dialog that
 * was still available. `shouldShowRequestPermissionRationale` going true is
 * the only signal the platform gives that a denial landed, and that is what
 * gets recorded, read when the permission is *read* rather than when
 * anything is launched.
 *
 * `SharedPreferences` for the same reason the notification prompt history
 * uses it: read while deciding what to draw, so it must not cost a disk wait
 * on that path, and it holds two booleans about the app's own history —
 * nothing about the user, the place, or the time.
 */
class LocationPromptStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Folds a fresh foreground reading into the remembered history. */
    fun recordForeground(granted: Boolean, rationale: Boolean) =
        record(KEY_EVER_DENIED_FOREGROUND, granted, rationale)

    /** Folds a fresh background reading into the remembered history. */
    fun recordBackground(granted: Boolean, rationale: Boolean) =
        record(KEY_EVER_DENIED_BACKGROUND, granted, rationale)

    /** Whether a foreground denial has ever been observed since the last grant. */
    fun foregroundEverDenied(): Boolean = prefs.getBoolean(KEY_EVER_DENIED_FOREGROUND, false)

    /** Whether a background denial has ever been observed since the last grant. */
    fun backgroundEverDenied(): Boolean = prefs.getBoolean(KEY_EVER_DENIED_BACKGROUND, false)

    /**
     * Whether the user has dismissed the banner asking for background
     * location.
     *
     * The same shape as the tile banner's own dismissal, and for the same
     * reason: someone who has said "not now" once has been told, and the
     * permanent location row on `PermissionsScreen` outlives the banner, so
     * the route stays open without asking again.
     *
     * **Remembered forever, and deliberately not cleared by a later grant.**
     * The denial history above resets on a grant because the *platform's*
     * prompt availability resets with it; this records what the user asked
     * of Snoozemo, which nothing about a permission moving undoes. A grant
     * hides the banner anyway — there is nothing left to ask for — so
     * clearing the flag would only bring it back if the grant were revoked
     * again, which is the one case the user has already answered.
     */
    fun isBackgroundBannerDismissed(): Boolean =
        prefs.getBoolean(KEY_BACKGROUND_BANNER_DISMISSED, false)

    /** The user has dismissed the background-location banner. Not undone. */
    fun dismissBackgroundBanner() {
        prefs.edit().putBoolean(KEY_BACKGROUND_BANNER_DISMISSED, true).apply()
    }

    /**
     * A grant **clears** [key] rather than leaving it set — granting resets the
     * denial history the system counts, so a permission later revoked from
     * Settings starts again with its prompts available.
     */
    private fun record(key: String, granted: Boolean, rationale: Boolean) {
        when {
            granted -> prefs.edit().remove(key).apply()
            rationale -> prefs.edit().putBoolean(key, true).apply()
            // Neither: nothing new was learned. Writing `false` here is the bug
            // — it is what a never-denied permission and a permanently denied
            // one both look like.
            else -> Unit
        }
    }

    private companion object {
        const val FILE_NAME = "location_prompt"
        const val KEY_EVER_DENIED_FOREGROUND = "ever_denied_foreground"
        const val KEY_EVER_DENIED_BACKGROUND = "ever_denied_background"
        const val KEY_BACKGROUND_BANNER_DISMISSED = "background_banner_dismissed"
    }
}
