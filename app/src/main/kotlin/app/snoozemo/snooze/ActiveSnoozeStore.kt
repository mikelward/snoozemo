package app.snoozemo.snooze

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.EndReason
import app.snoozemo.core.Anchor
import app.snoozemo.core.RecordOrigin
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

    private val context = context.applicationContext

    private val prefs = this.context
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
        val record = read() ?: return null
        // Refused here rather than at each call site, for exactly the reason a
        // released record is: there are a dozen callers and every one of them
        // would otherwise read a record this phone never wrote as a live snooze
        // and turn Do Not Disturb on. Filtering once, at the only door, is what
        // makes that unmissable. [originOfStored] is how the boot path asks the
        // question it needs the *answer* to rather than just the safe default.
        val origin = originOf(record)
        if (origin.isDegraded) {
            // Not a refusal — a capability this device does not have, which the
            // `<device-transfer>` exclude still covers. Said out loud because a
            // protection that quietly isn't running is principle 2's failure.
            Log.w(
                TAG,
                "This device cannot attribute snooze records; restoring without the transfer check.",
            )
        }
        if (origin.mayRestore) return record
        Log.w(
            TAG,
            "The snooze record was not written by this device ($origin); refusing to restore it.",
        )
        return null
    }

    /**
     * Which device wrote the record on disk, or null when there is no record to
     * attribute.
     *
     * Separate from [load] because the two questions differ: [load] answers "is
     * there a snooze to run", where a foreign record is simply absent, and this
     * answers "is there a record here that should not be", which is what the
     * boot path needs in order to clear it and say why (principle 2).
     */
    fun originOfStored(): RecordOrigin? = read()?.let { originOf(it) }

    /**
     * The record exactly as written, refusal and all.
     *
     * Only the discard path may use this, and only because it is the one caller
     * that needs to act *on* an unusable record rather than around it — naming
     * it in a retry, and knowing whether a notification was posted for it.
     * Everything else goes through [load], which is where the refusal lives.
     */
    internal fun readUnverified(): ActiveSnooze? = read()

    private fun originOf(record: ActiveSnooze): RecordOrigin =
        RecordOrigin.of(record.deviceStamp, DeviceStamp.current(context))

    /** The record as written, with no judgment about whether it may be used. */
    private fun read(): ActiveSnooze? {
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
                bssid = prefs.getString(KEY_BSSID, null),
                radiusM = prefs.getInt(KEY_RADIUS, Anchor.DEFAULT_RADIUS_M),
            ),
            startedAt = Instant.ofEpochMilli(startedAt),
            capExpiresAt = Instant.ofEpochMilli(capExpiresAt),
            mode = loadMode(),
            armed = prefs.getBoolean(KEY_ARMED, false),
            placeName = prefs.getString(KEY_PLACE, ActiveSnooze.DEFAULT_PLACE_NAME)
                ?: ActiveSnooze.DEFAULT_PLACE_NAME,
            // Absent for a record written before this key existed. Null is the
            // honest answer there and the safe one: the cap falls back to wall
            // time alone, which is what such a record was already relying on.
            bootReference = if (prefs.contains(KEY_BOOT_REFERENCE)) {
                prefs.getLong(KEY_BOOT_REFERENCE, 0L)
            } else {
                null
            },
            // Falls back to the derivation this replaced, which is both what a
            // record written before this key existed means and what it was
            // already being read as. Such a record has never been reconciled —
            // the field and the reconciliation landed together — so the
            // derivation is still correct for it.
            capCeilingAt = Instant.ofEpochMilli(
                prefs.getLong(
                    KEY_CAP_CEILING_AT,
                    startedAt + ActiveSnooze.DEFAULT_CAP.toMillis(),
                ),
            ),
            // Absent for a record written before stamping existed, and absent
            // for one written on a device that could not stamp. On a device that
            // *can* stamp, absent reads as `UNATTRIBUTED` and does not restore;
            // see `RecordOrigin.mayRestore` for why that asymmetry is the right
            // one.
            deviceStamp = prefs.getString(KEY_DEVICE_STAMP, null),
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
        Thread {
            load()
            // The stamp too, and for the same reason the record is: it is read
            // on the write that happens *during* `ARMING`, before the zen rule
            // goes on (Codex, PR #26). `DeviceStamp.current` is a
            // `Settings.Secure` provider read plus a hash — small, but it is
            // still IPC, and `AGENTS.md`'s arm-path rule admits none between
            // the tap and `STATE_TRUE`. Warmed here it is a field read by then.
            DeviceStamp.warm(this.context)
        }.start()
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
        // `allowLookup = false`: this is the one write that happens between the
        // tile tap and the zen rule going on, and nothing on that path may do
        // IPC (`AGENTS.md`). See `DeviceStamp.cachedOrNull`.
        prefs.edit().putAll(snooze, allowLookup = false).apply()
    }

    /**
     * Writes [snooze] through to disk, returning false if the write failed.
     *
     * `commit`, not `apply`: called once the rule is on and off the hot path,
     * because the caller has to know. This record is the only thing that can
     * turn the rule off after process death, so a snooze that isn't on disk is
     * one the app cannot promise to end.
     */
    fun save(snooze: ActiveSnooze): Boolean = prefs.edit().putAll(snooze, allowLookup = true).commit()

    private fun SharedPreferences.Editor.putAll(
        snooze: ActiveSnooze,
        allowLookup: Boolean,
    ): SharedPreferences.Editor = this
        // A new snooze clears any marker left by one whose erase failed —
        // otherwise this record would be born already invisible to [load].
        .remove(KEY_RELEASED)
        .remove(KEY_RELEASING_REASON)
        .putBoolean(KEY_ARMED, snooze.armed)
        .putLong(KEY_STARTED_AT, snooze.startedAt.toEpochMilli())
        .putLong(KEY_CAP_EXPIRES_AT, snooze.capExpiresAt.toEpochMilli())
        // Written with the deadline for the same reason the boot reference is:
        // a clock change moves both, and a ceiling left in the old frame is one
        // `+30 min` can walk the snooze past.
        .putLong(KEY_CAP_CEILING_AT, snooze.capCeilingAt.toEpochMilli())
        // Stamped from the device here rather than read off [snooze], and on
        // every save rather than once at arm.
        //
        // Taking it from the record would mean every construction site had to
        // remember to set it, and the one that forgot would write a record this
        // device could never afterwards vouch for — which fails closed (the
        // snooze ends at the next restore) but fails *silently and often*,
        // turning a safety net into a bug that looks like the safety net
        // working. Stamping at the single point where records reach disk is
        // what makes "every record written here carries this device's stamp"
        // true by construction instead of by convention.
        //
        // Null is written as an absent key rather than an empty string: a
        // device that cannot stamp should leave no stamp. `RecordOrigin` reads
        // blank and absent identically, but only one of the two is honest, and
        // the difference shows up in a `SharedPreferences` dump when someone is
        // working out why a snooze ended at boot.
        .also { editor ->
            val stamp =
                if (allowLookup) DeviceStamp.current(context) else DeviceStamp.cachedOrNull()
            when {
                !stamp.isNullOrBlank() -> editor.putString(KEY_DEVICE_STAMP, stamp)
                // Not yet warm, on the arm path. Leave whatever is there rather
                // than removing: the blocking `save` moments later writes the
                // real value, and clearing it here would only widen the window
                // in which the record reads as unattributed.
                !allowLookup -> Unit
                // A lookup was allowed and still produced nothing, so this
                // device genuinely has no identity. Absent, not empty — only
                // one of the two is honest, and the difference shows up in a
                // `SharedPreferences` dump when someone is working out why a
                // snooze ended at boot.
                else -> editor.remove(KEY_DEVICE_STAMP)
            }
        }
        // Written with the deadline, never separately: the two are only
        // meaningful as a pair, and a reference stamped from a later reading
        // would describe a different frame than the deadline beside it.
        .also { editor ->
            snooze.bootReference?.let { editor.putLong(KEY_BOOT_REFERENCE, it) }
                ?: editor.remove(KEY_BOOT_REFERENCE)
        }
        .putLong(KEY_CAPTURED_AT, snooze.anchor.capturedAt.toEpochMilli())
        .putString(KEY_MODE, snooze.mode.name)
        .putString(KEY_PLACE, snooze.placeName)
        .putString(KEY_SSID, snooze.anchor.ssid)
        // Recorded alongside the SSID and acted on by nothing (SPEC.md §6.2);
        // persisted so the record a restore reads matches the one that armed.
        .putString(KEY_BSSID, snooze.anchor.bssid)
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
     * Records *why* a release is being attempted, before it is attempted.
     *
     * Unlike [markReleased] this does not suppress the record — it only answers
     * the question a recovering process cannot otherwise answer: a live record
     * sitting over a rule that is already off means either the user turned Do
     * Not Disturb off while nothing of ours was running, or we did and died
     * before clearing up. `STATE_FALSE` alone cannot tell those apart (Codex,
     * PR #36), and guessing "the user" suppresses the ending notification,
     * because that ending is the one deliberately kept silent.
     *
     * Safe to be stale: it is read only in that specific recovery case, and it
     * is cleared by both [save] and [clear].
     */
    fun markReleasing(reason: EndReason): Boolean =
        prefs.edit().putString(KEY_RELEASING_REASON, reason.name).commit()

    /**
     * Whether the rule actually went on for the stored record (SPEC.md §5.8).
     *
     * The record is deliberately written *before* the rule (§4.1, and so that a
     * crash never leaves the rule on with nothing to turn it off), which means
     * a record over an off rule is ambiguous: either the arm never completed,
     * or it completed and the user has since turned Do Not Disturb off. Those
     * want opposite answers — finish the arm, or end the snooze — so the
     * difference has to be recorded rather than guessed.
     *
     * Written after `STATE_TRUE` lands, so it costs the arm path nothing.
     */
    fun wasArmed(): Boolean = prefs.getBoolean(KEY_ARMED, false)

    /**
     * Forgets a release reason whose release did not happen.
     *
     * Not merely tidy: a marker that outlives its failed attempt is read as the
     * explanation for whatever ends the snooze *next*, which may be the user
     * turning Do Not Disturb off — and they would be told they had left
     * somewhere instead.
     */
    fun clearReleasing(): Boolean = prefs.edit().remove(KEY_RELEASING_REASON).commit()

    /** The reason recorded by [markReleasing], if one survived. */
    fun releasingReason(): EndReason? =
        prefs.getString(KEY_RELEASING_REASON, null)?.let { name ->
            // An unrecognized name is a record from a build that knew a reason
            // this one doesn't. Null, so the caller falls back rather than
            // throwing on a downgrade.
            EndReason.entries.firstOrNull { it.name == name }
        }

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
        const val KEY_RELEASING_REASON = "releasing_reason"
        const val KEY_ARMED = "armed"
        const val KEY_CAP_EXPIRES_AT = "cap_expires_at"
        const val KEY_BOOT_REFERENCE = "boot_reference"
        const val KEY_CAP_CEILING_AT = "cap_ceiling_at"
        const val KEY_DEVICE_STAMP = "device_stamp"
        const val KEY_CAPTURED_AT = "captured_at"
        const val KEY_MODE = "mode"
        const val KEY_PLACE = "place"
        const val KEY_SSID = "ssid"
        const val KEY_BSSID = "bssid"
        const val KEY_RADIUS = "radius"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_ACCURACY = "accuracy"
    }
}
