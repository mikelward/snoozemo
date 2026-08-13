package app.snoozemo.snooze

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

/**
 * This device's stamp: an opaque value that is stable across reboots and app
 * restarts, and different on a different phone (SPEC.md §12).
 *
 * Exists so a snooze record that arrives by device-to-device transfer can be
 * recognized and refused rather than restored into a live snooze on a phone
 * nobody armed it on. See [app.snoozemo.core.RecordOrigin].
 *
 * Derived from **two independent values**, either of which is enough on its own
 * to tell a new handset from the one that armed the snooze:
 *
 * - `Settings.Secure.ANDROID_ID`, scoped per app-signing key, per user and per
 *   device, and reset by a factory reset.
 * - This install's `firstInstallTime` from `PackageManager`. A device-to-device
 *   transfer copies app *data*; the app itself is installed fresh on the new
 *   phone, so its first-install time is the new phone's, while the transferred
 *   record still carries the old one. It survives an app update, which changes
 *   only `lastUpdateTime`.
 *
 * Neither needs a permission. **Two rather than one because they fail
 * independently** (Codex, PR #26): `ANDROID_ID` can read back null on modified
 * builds, and a device where it does would otherwise have no identity at all —
 * every record on it unattributable, and a transferred record indistinguishable
 * from its own. `firstInstallTime` is answered by `PackageManager` on any
 * device, so the "no identity" state stops being reachable in practice rather
 * than being argued about.
 *
 * **The raw identifier is never stored.** Only a salted SHA-256 of it reaches
 * disk, because equality is the only question ever asked of this value and a
 * hash answers that just as well. What the salt buys is that the stored stamp
 * is not the same value any other app deriving from `ANDROID_ID` would hold, so
 * a prefs file lifted off a device correlates with nothing. There is no
 * `INTERNET` permission for it to leave through in any case (SPEC.md §12) —
 * this is the second lock on a door that has no road to it.
 */
internal object DeviceStamp {

    /**
     * A constant, app-specific salt. Not a secret and not trying to be: it is
     * on the wrong side of the trust boundary to ever be one, since it ships in
     * the APK. Its whole job is domain separation — making this hash differ
     * from any other app's hash of the same identifier — which a public
     * constant does perfectly well.
     */
    private const val SALT = "snoozemo/device-stamp/v1"

    private const val TAG = "DeviceStamp"

    @Volatile
    private var cached: String? = null

    @Volatile
    private var computed = false

    /**
     * Guards *computing and publishing together*, which is the whole point.
     *
     * Two threads reach [current] in the ordinary run of things: the warm-up
     * thread started from `SnoozemoApplication.onCreate`, and whichever thread
     * saves the first record. With the read, the compute and the publish left
     * unsynchronized, a slow compute could finish after another thread had
     * already published — or after [forget] had deliberately dropped the value —
     * and quietly overwrite it with the older answer. On a device both threads
     * compute the same thing so nothing is visibly wrong; the moment the inputs
     * can differ, the losing thread's answer is one that says a record written
     * here belongs to another phone.
     */
    private val lock = Any()

    /**
     * Computes the stamp ahead of the arm path and holds it (SPEC.md §4.1).
     *
     * Called from `ActiveSnoozeStore.warm` at startup, on its thread. The value
     * cannot change while the process lives — `ANDROID_ID` is fixed for the
     * life of an install on a device — so caching it is not a staleness risk,
     * and it turns the read on the `ARMING` write into a field access.
     *
     * A failed read is cached too, deliberately: it means this device cannot
     * stamp, which is as stable a fact as a successful read and should not be
     * retried once per save. [cachedOrNull] is what the arm path uses, and it
     * never falls back to a lookup — so warming is what decides whether the
     * first snooze of a cold start is stamped by its async write or by the
     * blocking one just after.
     */
    fun warm(context: Context) {
        current(context)
    }

    /**
     * The stamp for this device, or null if it could not be determined.
     *
     * Null is a real answer rather than a failure to report: `ANDROID_ID` is
     * documented to be available, but a null or blank read is reported on
     * modified builds. (An earlier version of this comment also claimed it
     * during early boot — that was unverified, and it was quietly load-bearing
     * in `RecordOrigin`'s design, so it is stated no more strongly than it can
     * be supported.)
     *
     * Inventing a value would be worse than admitting none: a fabricated stamp
     * either matches nothing, ending every snooze across a reboot, or matches
     * everything, defeating the check. Reaching null now takes *both* inputs
     * failing, which is why [app.snoozemo.core.RecordOrigin.UNVERIFIABLE] —
     * the one origin that restores without a check — is close to unreachable.
     *
     * Cached after the first call, which [warm] makes at startup so this is a
     * field read by the time the arm path reaches it.
     */
    fun current(context: Context): String? {
        if (computed) return cached
        // Double-checked, over volatile fields: the fast path stays a field read
        // for every call after the first, and the slow path computes and
        // publishes inside one critical section so no result can be published
        // out of order with a later one.
        synchronized(lock) {
            if (computed) return cached
            val value = compute(context)
            cached = value
            computed = true
            return value
        }
    }

    /**
     * The stamp **only if it is already computed**, never computing it here.
     *
     * This is what the `ARMING` write uses (Codex, PR #26). Caching alone was
     * not enough: a tile tap on a cold start can overtake the thread [warm]
     * runs on, and [current] would then do its `Settings.Secure` and
     * `PackageManager` reads on the arm path — narrowing the forbidden IPC
     * window rather than closing it. Returning null instead means the async
     * write simply leaves the stamp alone, and the blocking `save` that
     * follows once the rule is on — already off the arm path — writes it.
     *
     * The gap between those two writes is a record that is momentarily
     * unstamped. Process death inside it leaves a record that later reads as
     * `UNATTRIBUTED` and ends the snooze, which is the fail-open direction and
     * the right one to land in.
     */
    fun cachedOrNull(): String? = if (computed) cached else null

    private fun compute(context: Context): String? {
        val app = context.applicationContext
        val androidId = try {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: SecurityException) {
            // Not expected — this read needs no permission — but a restricted
            // profile or a modified build can still refuse it. Degrade to the
            // install time alone rather than crashing the boot receiver.
            Log.w(TAG, "The device identifier could not be read; falling back to the install time.", e)
            null
        }
        val installedAt = try {
            app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
        } catch (e: PackageManager.NameNotFoundException) {
            // The app asking about itself, so this is unreachable short of a
            // broken package manager. Handled rather than declared away: an
            // escape here would take the boot receiver's restore down with it.
            Log.w(TAG, "This install's first-install time is unavailable.", e)
            null
        }

        if (androidId.isNullOrBlank() && installedAt == null) {
            Log.w(TAG, "No device identity is available; snooze records cannot be attributed.")
            return null
        }
        if (androidId.isNullOrBlank()) {
            Log.w(TAG, "The device identifier is absent; attributing records by install time alone.")
        }
        return hash("${androidId.orEmpty()}|$installedAt")
    }

    /** Test seam: forgets the cached value so a test can change identity. */
    internal fun forget() = synchronized(lock) {
        cached = null
        computed = false
    }

    /**
     * Salted SHA-256, hex-encoded.
     *
     * [MessageDigest] with SHA-256 is required to be present on every Android
     * implementation, so the `NoSuchAlgorithmException` this can technically
     * throw is unreachable in practice. It is still handled rather than
     * declared away: an unreachable throw that escapes a broadcast receiver on
     * the boot path would take the restore down with it, and "unknown" is a
     * defined outcome here with an already-correct behavior behind it.
     */
    private fun hash(raw: String): String? = try {
        MessageDigest.getInstance("SHA-256")
            .digest((SALT + raw).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (e: java.security.NoSuchAlgorithmException) {
        Log.e(TAG, "SHA-256 is unavailable; snooze records cannot be attributed to this device.", e)
        null
    }
}
