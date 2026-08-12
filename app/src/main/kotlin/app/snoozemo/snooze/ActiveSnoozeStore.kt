package app.snoozemo.snooze

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import java.time.Instant

/**
 * The persisted record of a running snooze (SPEC.md §11), written on every state
 * transition so process death is recoverable.
 *
 * `SharedPreferences` rather than DataStore, deliberately and narrowly: this is
 * read on the arm path and on every service start, and it must not cost a
 * coroutine or a disk wait at the moment of a tile tap. The file is loaded once
 * at construction — which happens at service start, not at tap time — and reads
 * after that are memory hits. Settings, which are neither hot nor on that path,
 * go to DataStore when they land.
 *
 * Only the anchor's *shape* is persisted, not a serialized object: the fields are
 * few, and a hand-written schema here is easier to migrate than a serializer's
 * output when `Anchor` grows saved-place fields.
 */
class ActiveSnoozeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The running snooze, or null when there isn't one.
     *
     * A record marked released reads as **absent**, which is the whole point of
     * the marker: erasing can fail, so a record that has already been released
     * can outlive its snooze on disk, and every caller here — restoring after
     * process death, adopting for a release, refusing a duplicate arm — would
     * otherwise read that leftover as a live snooze and turn DND back on with
     * nothing the user did behind it. Only [markReleased]'s own retry path
     * cares that the row still exists, and it works from the identity it was
     * given rather than from this.
     */
    fun load(): ActiveSnooze? {
        if (prefs.getBoolean(KEY_RELEASED, false)) return null
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        val capExpiresAt = prefs.getLong(KEY_CAP_EXPIRES_AT, 0L)
        if (startedAt == 0L || capExpiresAt == 0L) return null

        return ActiveSnooze(
            anchor = Anchor(
                lat = prefs.getDoubleOrNull(KEY_LAT),
                lon = prefs.getDoubleOrNull(KEY_LON),
                fixAccuracyM = prefs.getFloatOrNull(KEY_ACCURACY),
                capturedAt = Instant.ofEpochMilli(prefs.getLong(KEY_CAPTURED_AT, startedAt)),
                ssid = prefs.getString(KEY_SSID, null),
                radiusM = prefs.getInt(KEY_RADIUS, Anchor.DEFAULT_RADIUS_M),
            ),
            startedAt = Instant.ofEpochMilli(startedAt),
            capExpiresAt = Instant.ofEpochMilli(capExpiresAt),
            mode = loadMode(),
            placeName = prefs.getString(KEY_PLACE, ActiveSnooze.DEFAULT_PLACE_NAME)
                ?: ActiveSnooze.DEFAULT_PLACE_NAME,
        )
    }

    /**
     * Reads the tracking mode, degrading to the honest answer when it can't be
     * read.
     *
     * The fallback is `DURATION_ONLY` in every failure: claiming `FULL` tracking
     * we can't verify is the failure this app is arranged against, and a snooze
     * that thinks it is watching a place it isn't never ends when the user
     * leaves. Every path to it is logged, because a mode that silently reverts
     * is exactly the kind of degradation the user would otherwise discover as
     * "the snooze didn't end when I left" with nothing to explain it — and each
     * failure names a different defect: a schema change that moved this key's
     * type, a value written by a build that knew a mode this one doesn't, or a
     * record that reached disk without its mode.
     */
    private fun loadMode(): TrackingMode {
        val stored = try {
            prefs.getString(KEY_MODE, null)
        } catch (e: ClassCastException) {
            Log.w(TAG, "The stored tracking mode is not a string; degrading to duration-only.", e)
            null
        }
        if (stored == null) {
            Log.w(TAG, "The snooze record carries no tracking mode; degrading to duration-only.")
            return TrackingMode.DURATION_ONLY
        }
        return try {
            TrackingMode.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            // The value itself is app state, not the user's — but it is of no
            // diagnostic use either, since the defect is that nothing here can
            // read it. The name of the failure is the whole signal.
            Log.w(TAG, "The stored tracking mode is unknown; degrading to duration-only.", e)
            TrackingMode.DURATION_ONLY
        }
    }

    /**
     * Pulls the file into memory off the main thread, for the same reason the
     * zen rule id is warmed: `apply` makes only the *disk* write asynchronous,
     * while `edit()` itself still waits for the initial load to finish. On a
     * cold process that wait would land between the tile tap and the rule going
     * on. Same caveat as the rule id — this narrows the window, it does not
     * close it, since a fast enough tap can still overtake the warm-up.
     */
    fun warm() {
        Thread { load() }.start()
    }

    /**
     * Records [snooze] in memory without waiting for the disk, for the one write
     * that happens between the tile tap and the zen rule going on.
     *
     * The ordering matters both ways and this is how both are satisfied. The
     * record must exist *before* the rule, so a process death in between leaves
     * evidence that a snooze may be running — believing we are snoozed when we
     * aren't is recoverable; the reverse leaves a silent phone. But no blocking
     * disk write belongs on the arm path (`AGENTS.md`, "the arm path"). `apply`
     * updates the in-memory map synchronously and hands the file write to a
     * background thread, so a later read in this process already sees it, and
     * [save] forces it down the moment the rule is on.
     */
    fun saveAsync(snooze: ActiveSnooze) {
        prefs.edit().putAll(snooze).apply()
    }

    /**
     * Writes [snooze] through to disk, returning false if the write failed.
     *
     * `commit`, not `apply`: called once the rule is on and off the hot path,
     * because the caller has to know. This record is the only thing that can
     * turn the rule off after process death, so a snooze that isn't on disk is
     * one the app cannot promise to end.
     */
    fun save(snooze: ActiveSnooze): Boolean = prefs.edit().putAll(snooze).commit()

    private fun SharedPreferences.Editor.putAll(
        snooze: ActiveSnooze,
    ): SharedPreferences.Editor = this
        // A new snooze clears any marker left by one whose erase failed —
        // otherwise this record would be born already invisible to [load].
        .remove(KEY_RELEASED)
        .putLong(KEY_STARTED_AT, snooze.startedAt.toEpochMilli())
        .putLong(KEY_CAP_EXPIRES_AT, snooze.capExpiresAt.toEpochMilli())
        .putLong(KEY_CAPTURED_AT, snooze.anchor.capturedAt.toEpochMilli())
        .putString(KEY_MODE, snooze.mode.name)
        .putString(KEY_PLACE, snooze.placeName)
        .putString(KEY_SSID, snooze.anchor.ssid)
        .putInt(KEY_RADIUS, snooze.anchor.radiusM)
        .also { editor ->
            snooze.anchor.lat?.let { editor.putLong(KEY_LAT, it.toRawBits()) }
                ?: editor.remove(KEY_LAT)
            snooze.anchor.lon?.let { editor.putLong(KEY_LON, it.toRawBits()) }
                ?: editor.remove(KEY_LON)
            snooze.anchor.fixAccuracyM?.let { editor.putFloat(KEY_ACCURACY, it) }
                ?: editor.remove(KEY_ACCURACY)
        }

    /**
     * Marks the record released, so that even if [clear] then fails the leftover
     * is never read back as a live snooze.
     *
     * Written **before** the erase, not after, because the erase is the thing
     * that fails: a record survives precisely when clearing it didn't work, and
     * a marker written afterwards would never be reached. Both are
     * `SharedPreferences` commits, so a disk that refuses one will usually
     * refuse the other — this is not a guarantee, it is one more chance for the
     * app to end up in the safe state rather than the dangerous one.
     *
     * The dangerous one is specific: a released record that outlives its erase
     * gets restored by the next cold start — a cap wake-up, a boot, a late
     * notification grant — which re-asserts the zen rule and takes the phone
     * quiet again with nothing the user did behind it, until the old cap.
     */
    fun markReleased(): Boolean = prefs.edit().putBoolean(KEY_RELEASED, true).commit()

    /**
     * Forgets the snooze, returning false if the erase didn't reach disk.
     *
     * The result matters as much as [save]'s, in the opposite direction: a
     * record that survives a release is one a later cold start will restore, and
     * restoring re-asserts the zen rule — so a failed clear is how a phone goes
     * quiet again on its own, with no user action behind it. The caller keeps
     * the cap alarm armed on a false, so even a stale record is bounded by the
     * original cap rather than being open-ended.
     */
    fun clear(): Boolean = prefs.edit().clear().commit()

    /**
     * Calls [onChange] whenever the record changes, until the returned handle is
     * closed.
     *
     * So a screen can show the *one* truth about whether a snooze is running
     * rather than keeping its own. A second source that drifts out of step is
     * how a UI ends up offering to stop something that already stopped, or
     * claiming nothing is running over a silent phone.
     */
    fun observe(onChange: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Holds the listener itself: SharedPreferences keeps only a weak
        // reference, so a lambda with no other owner is collected mid-screen.
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun SharedPreferences.getDoubleOrNull(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)) else null

    private fun SharedPreferences.getFloatOrNull(key: String): Float? =
        if (contains(key)) getFloat(key, 0f) else null

    private companion object {
        const val TAG = "ActiveSnoozeStore"
        const val FILE_NAME = "active_snooze"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_RELEASED = "released"
        const val KEY_CAP_EXPIRES_AT = "cap_expires_at"
        const val KEY_CAPTURED_AT = "captured_at"
        const val KEY_MODE = "mode"
        const val KEY_PLACE = "place"
        const val KEY_SSID = "ssid"
        const val KEY_RADIUS = "radius"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_ACCURACY = "accuracy"
    }
}
