package app.snoozemo.tile

import android.content.Context
import android.content.SharedPreferences

/**
 * Whether Snoozemo's tile is currently in the user's Quick Settings.
 *
 * The platform offers no way to ask. `StatusBarManager` can *request* that the
 * tile be added and reports the outcome of that one request, and `TileService`
 * is told when it is added or removed — but nothing answers "is it there now?",
 * so the only way to know is to keep the answer as it goes past.
 *
 * It matters because the tile is the product (SPEC.md §4.2) and the app screen
 * has to decide whether to offer to add it. Offering when it is already there
 * is clutter on the one screen this app has; not offering when it is missing
 * leaves the user with an app whose entire interaction they cannot reach.
 *
 * **A false negative is the safe direction and the one this errs toward.** The
 * flag starts false on a fresh install, so the row appears; if the tile is
 * somehow already there, the request reports it and the flag corrects itself on
 * the spot. A false *positive* would hide the only route to the tile.
 *
 * `SharedPreferences` like the other small stores here, and read only by the
 * app screen — never on the arm path. It holds one boolean about the app's own
 * placement; nothing about the user, the place, or the time is written here.
 */
class TilePresenceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Whether the tile is known to be in Quick Settings. */
    fun isAdded(): Boolean = prefs.getBoolean(KEY_ADDED, false)

    /**
     * Records what the platform just told us.
     *
     * Called from `onTileAdded` and `onTileRemoved`, and from the result of an
     * add request — the three places the platform volunteers the answer.
     */
    fun setAdded(added: Boolean) {
        prefs.edit().putBoolean(KEY_ADDED, added).apply()
    }

    /**
     * Calls [onChange] whenever the answer moves, until the returned handle is
     * closed.
     *
     * The screen cannot rely on reading this once. Three different things write
     * it, none of them on the screen's own thread of control: the tile service
     * when the tile is added or removed from the shade, and the add-request's
     * callback — which fires after a system dialog that can outlive the
     * activity that opened it. A configuration change while that dialog is up
     * replaces the activity, and the replacement has already done its read by
     * the time the answer lands, so without this it would go on offering a tile
     * the user has just added (flagged by Codex on PR #20).
     *
     * Same shape as `ActiveSnoozeStore.observe`, including why the handle holds
     * the listener: `SharedPreferences` keeps only a weak reference, so a
     * lambda with no other owner is collected mid-screen.
     */
    fun observe(onChange: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val FILE_NAME = "tile_presence"
        const val KEY_ADDED = "added"
    }
}
