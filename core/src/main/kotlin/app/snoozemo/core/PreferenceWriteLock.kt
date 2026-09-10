package app.snoozemo.core

/**
 * One write at a time per preferences file, process-wide.
 *
 * `SharedPreferencesImpl.EditorImpl.commit()` writes to disk on the calling
 * thread only while it is that file's **sole** write in flight. A second
 * commit overlapping it from another thread is handed to the platform's
 * `QueuedWork`, and the first caller parks on a latch that the queued write
 * counts down. On a device that write always runs. Under Robolectric it does
 * not survive the test that queued it — `ShadowQueuedWork`'s per-test reset
 * clears the handler and the work list — so the latch never opens and whatever
 * thread was inside `commit()` is parked for the rest of the JVM. When that
 * thread is one of this app's long-lived FIFO workers, everything queued
 * behind it for every later test class never runs; `TODO.md` has the four
 * diagnoses that took.
 *
 * **Here rather than beside the wrapper that uses it**, because the stores
 * that need it are spread across modules — `:app` has four,
 * `SnoozeRingerStore` is in `:dnd` so `:tile` can reach it — and `:core` is
 * the one module all of them already depend on. It holds no Android type for
 * exactly that reason: the file name is a string, and the platform work
 * happens inside [write]'s block.
 *
 * `:app` stores reach this through `SerializedPreferences`, which owns the
 * preferences so a store cannot commit around the lock by not knowing it
 * exists. Anything outside `:app` calls it directly.
 */
object PreferenceWriteLock {

    private val locks = HashMap<String, Any>()

    /**
     * Runs [block] as one write to [fileName], start to finish.
     *
     * **A restore-on-refusal write is one operation, not two.** Every store
     * here reads the old value, commits the new one, and puts the old one back
     * when that commit is refused — and locking only the commits leaves the
     * rollback in its own critical section. Two threads then interleave as
     * *read, write (refused), [another thread writes and is told it stuck],
     * roll back*: the second caller's setting is lost the instant it is made,
     * and it was told otherwise (Codex, PR #246). So the scope is the whole
     * operation, and nesting is free — a JVM monitor is reentrant on the
     * thread already holding it.
     */
    fun <T> write(fileName: String, block: () -> T): T =
        synchronized(lockFor(fileName)) { block() }

    /**
     * One lock per file, shared by every caller naming it.
     *
     * Per *file*, so an unrelated store's slow write cannot hold up a draw
     * decision reading another — and per file rather than per store instance,
     * since the stores are constructed freely (`CrashReporting` builds a fresh
     * `CrashReportingStore` inside each worker task), so an instance-scoped
     * lock would serialize nothing at all.
     */
    private fun lockFor(fileName: String): Any = synchronized(locks) {
        locks.getOrPut(fileName) { Any() }
    }
}
