package app.snoozemo.storage

import android.content.Context
import app.snoozemo.core.PreferenceWriteLock

/**
 * A preferences file whose writes never overlap, process-wide.
 *
 * The mechanism, and why overlapping commits wedge a worker under
 * Robolectric, is on [PreferenceWriteLock], which this holds the `:app` side
 * of.
 *
 * **It owns the preferences rather than exposing a lock**, which is the whole
 * point of the type. The first version of this was a `writeLock` in one
 * store's companion object, correct for that store and invisible to the next
 * three written in the same shape — `CrashReportingStore`, `EndSheetStore`
 * and `FontSizeStore`, each with its own worker writing its own file while
 * another thread writes it too. A store that never holds a raw
 * `SharedPreferences` cannot commit around the lock by forgetting it exists.
 *
 * Reads are deliberately **not** serialized: a getter takes no part in the
 * `QueuedWork` hand-off, and `SharedPreferences` is already thread-safe for
 * them. Locking reads would put every draw decision behind whatever write is
 * in flight for no benefit.
 */
internal class SerializedPreferences(context: Context, private val fileName: String) {

    private val prefs = context.applicationContext
        .getSharedPreferences(fileName, Context.MODE_PRIVATE)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    fun getFloat(key: String, defaultValue: Float): Float =
        prefs.getFloat(key, defaultValue)

    /**
     * Runs [block] as one write to this file, start to finish.
     *
     * **A restore-on-refusal write is one operation, not two.** Every store
     * here reads the old value, commits the new one, and puts the old one back
     * when that commit is refused — and serializing only the commits leaves
     * the rollback in its own critical section. Two threads then interleave as
     * *read false, write true (refused), [another thread writes true and is
     * told it stuck], roll back to false*: the second caller's setting is lost
     * the instant it is made, and it was told otherwise (Codex, PR #246). The
     * lock this replaced covered the read, the write and the rollback together
     * on the one store that had it; this is where that scope lives now.
     *
     * The put methods take the same lock, so calling them inside [block] is
     * free — a JVM monitor is reentrant on the thread already holding it — and
     * they stay usable on their own for a write with nothing to roll back.
     * Reads are not locked at all, so reading inside [block] is likewise fine.
     */
    fun <T> write(block: () -> T): T = PreferenceWriteLock.write(fileName, block)

    /** Writes [value] synchronously, returning whether it reached disk. */
    fun putBoolean(key: String, value: Boolean): Boolean =
        PreferenceWriteLock.write(fileName) {
            prefs.edit().putBoolean(key, value).commit()
        }

    /** Writes [value] synchronously, returning whether it reached disk. */
    fun putFloat(key: String, value: Float): Boolean =
        PreferenceWriteLock.write(fileName) {
            prefs.edit().putFloat(key, value).commit()
        }

    internal companion object {

        /**
         * Test seam: runs [block] holding [fileName]'s write lock, so a write
         * from another thread can be seen to wait for it.
         */
        internal fun holdWritesForTest(fileName: String, block: () -> Unit) =
            PreferenceWriteLock.write(fileName, block)
    }
}
