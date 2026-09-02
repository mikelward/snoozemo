package app.snoozemo.snooze

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.dnd.SnoozeRingerStore
import java.util.concurrent.Executors

/**
 * Writes [SnoozeRingerStore] off the main thread and reports what happened
 * (SPEC.md §5.9).
 *
 * The shape `EndSheetSetting` established, for the same reasons: `commit()` is a
 * synchronous file write and this is a tap on a screen held to a first-frame and
 * scroll budget, so the write goes to a worker and the row is reconciled from
 * the callback. `apply()` would avoid the thread hop but reports nothing at all,
 * and a setting that cannot tell the user its choice didn't land is principle
 * 2's failure.
 *
 * The store itself lives in `:dnd`, where the arm path can reach it without
 * seeing `:app`; this is only the write side.
 *
 * One FIFO daemon thread, so writes land in tap order and nothing here keeps the
 * process alive.
 */
internal object SnoozeRingerSetting {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "snoozemo-snooze-ringer").apply { isDaemon = true }
    }

    /**
     * Whether the last write reached disk, kept **here** rather than on the
     * screen that asked for it — see `EndSheetSetting.lastSaveRefused` for why:
     * a configuration change destroys that screen while the worker is still
     * running, and the replacement then has nothing to reconcile against.
     *
     * Written only on the FIFO worker, so once the queue drains it is the
     * *latest* write's outcome.
     */
    @Volatile
    var lastSaveRefused: Boolean = false
        private set

    // Volatile because it is written on the main thread and read on the worker,
    // for the same reason `EndSheetSetting.onSaveOutcome` carries it.
    @Volatile
    private var onSaveOutcome: (() -> Unit)? = null

    /**
     * Watches for a completed write, so a screen recreated mid-write can
     * reconcile. Closing the handle stops it.
     *
     * Identity-checked on close, so an outgoing activity's `onStop` cannot evict
     * the replacement that registered first — `onStop` runs *after* the new
     * activity's `onStart` on a configuration change.
     */
    fun watchSaveOutcome(onChange: () -> Unit): AutoCloseable {
        onSaveOutcome = onChange
        return AutoCloseable { if (onSaveOutcome === onChange) onSaveOutcome = null }
    }

    /**
     * Persists [ceiling], calling [onDone] on the worker with whether it stuck.
     *
     * Nothing here reaches for the ringer. The choice governs the **next**
     * snooze: a running one already holds a loan naming the mode it borrowed,
     * and retargeting that mid-snooze has no ordering that survives a process
     * death in the middle — the record can name the old mode over a phone in the
     * new one, or the reverse, and each leaves a later release unable to tell a
     * stale loan from the user's own change (`TODO.md` Phase 4 tracks it).
     */
    fun setChosen(context: Context, ceiling: SnoozeRinger, onDone: (Boolean) -> Unit) {
        val store = SnoozeRingerStore(context)
        worker.execute {
            val persisted = store.setChosen(ceiling)
            lastSaveRefused = !persisted
            onDone(persisted)
            // After the caller's own callback, so a screen that is still alive
            // has already reconciled and this is a no-op for it; it is the
            // *replacement* screen, whose own callback died with its activity,
            // that this exists for.
            onSaveOutcome?.invoke()
        }
    }

    /** Drops the recorded outcome, for a test that must not inherit another's state. */
    @VisibleForTesting
    internal fun resetForTest() {
        lastSaveRefused = false
        onSaveOutcome = null
    }
}
