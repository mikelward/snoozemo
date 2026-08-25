package app.snoozemo.snooze

import android.content.Context
import java.util.concurrent.Executors

/**
 * Whether the tile should ask when to unsnooze, or just snooze (SPEC.md §4.4).
 *
 * **Off by default** (maintainer, 2026-08-25). One tap from the shade with
 * nothing in the way is goal 1, and the sheet — however cheap — is something
 * between the tap and getting on with what you were doing. A user who wants to
 * be asked can say so once; a user who doesn't should never have to.
 *
 * Its own one-key `SharedPreferences` file, read by the trampoline **after** the
 * service start and never before it (§6.9), so this never sits between a tile
 * tap and the zen rule going on.
 *
 * Nothing about the user, the place, or the time is written here — one boolean
 * about how the app behaves.
 */
internal class EndSheetStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /**
     * Pulls the file into memory off the main thread, like the record and the
     * zen rule id beside it.
     *
     * The trampoline reads this after the service start, so it is never between
     * a tap and the rule going on — but a `SharedPreferences` getter still waits
     * for the initial load, and on a cold process that wait would sit in front
     * of the sheet's first frame over a transparent window, which is the "flash
     * of blank" §6.9 warns about (Codex, PR #118). Warmed here it is a memory
     * hit by the time the decision is made. Narrows the window rather than
     * closing it — a fast enough tap can still overtake the warm-up — which is
     * the same caveat the two stores beside it carry, and the fallback is one
     * brief blank frame rather than a wrong answer.
     */
    fun warm() {
        Thread { isEnabled() }.start()
    }

    /**
     * Persists the choice, returning whether the write reached disk.
     *
     * The same restore-on-refusal `DebugLogStore` does, and for the same reason:
     * `commit()` applies the change to the process-local map *before* the disk
     * write it reports on, so without putting the old value back, every later
     * read would return a value that was neither applied nor stored — a switch
     * reading one way over behavior going the other, until a process restart
     * flipped it back. The restore's own write may fail too; the map is restored
     * regardless, which is the part every reader sees.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val before = isEnabled()
        val persisted = prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
        if (!persisted) {
            prefs.edit().putBoolean(KEY_ENABLED, before).commit()
        }
        return persisted
    }

    private companion object {
        const val FILE_NAME = "end_sheet"
        const val KEY_ENABLED = "enabled"
    }
}

/**
 * Writes [EndSheetStore] off the main thread and reports what happened.
 *
 * `commit()` is a synchronous file write, and this is a tap on a screen whose
 * first-frame and scroll budget the rest of the app is held to — so the write
 * goes to a worker and the switch is reconciled from the callback, the shape
 * `DebugLogging.setEnabled` already established for the debug-log switch.
 * `apply()` would avoid the thread hop but reports nothing at all, and a switch
 * that cannot tell the user its choice didn't land is principle 2's failure.
 *
 * One FIFO daemon thread, so writes land in tap order and nothing here keeps the
 * process alive.
 */
internal object EndSheetSetting {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "snoozemo-end-sheet").apply { isDaemon = true }
    }

    /**
     * Whether the last write reached disk, kept **here** rather than on the
     * screen that asked for it.
     *
     * A configuration change destroys that screen while the worker is still
     * running, and the replacement then has nothing to reconcile against: it
     * reads the stored value, the old write completes into a dead activity, and
     * the switch stays wrong until the next launch (Codex, PR #118). Process
     * state outlives the recreation; `watchSaveOutcome` is how the replacement
     * hears about it. Same shape as `DebugLogging.lastSaveRefused` beside it,
     * for the same reason.
     *
     * Written only on the FIFO worker, so once the queue drains it is the
     * *latest* write's outcome — a failure superseded by a later success reads
     * false.
     */
    @Volatile
    var lastSaveRefused: Boolean = false
        private set

    // Volatile because it is written on the main thread and read on the worker.
    // Without it the worker may keep the old listener — or none — through exactly
    // the configuration change this exists for, so the replacement screen misses
    // the one completion that would reconcile its switch (Codex, PR #118). The
    // same reason `DebugLogging.onSaveOutcome` beside it carries the annotation.
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

    /** Persists [enabled], calling [onDone] on the worker with whether it stuck. */
    fun setEnabled(context: Context, enabled: Boolean, onDone: (Boolean) -> Unit) {
        val store = EndSheetStore(context)
        worker.execute {
            val persisted = store.setEnabled(enabled)
            lastSaveRefused = !persisted
            onDone(persisted)
            // After the caller's own callback, so a screen that is still alive
            // has already reconciled and this is a no-op for it; it is the
            // *replacement* screen, whose own callback died with its activity,
            // that this exists for.
            onSaveOutcome?.invoke()
        }
    }
}

/**
 * How a chosen end time actually turned out, from the service back to the sheet
 * that offered it.
 *
 * `startService` returning a component only means the *start* was accepted, and
 * the sheet used to dismiss on that (Codex, PR #118). Everything that can go
 * wrong happens afterwards — the alarm refused, the record not written, the
 * snooze already gone — and the card those post is a notification, which is
 * invisible to exactly the tile-first user who has denied that permission and
 * for whom the tile is the only surface there is (`SPEC.md` §4.2). So the sheet
 * stays up until the service says what happened.
 *
 * Main thread only, both ends: `SnoozeService.setCap` runs on the service's main
 * thread and the trampoline reads it on its own, which is the same one — no
 * synchronization, and none needed. In-memory and process-scoped, because both
 * ends are one tap apart in one process; a durable channel would outlive the
 * sheet it is answering.
 *
 * Every exit from `setCap` reports, including the ones that change nothing on
 * purpose — a choice already satisfied by the cap in place is applied, not
 * failed. The one case that reports nothing is a process death between the
 * accepted start and the dispatch, where the sheet simply stays up and the user
 * dismisses it; the snooze is untouched either way.
 */
/** What became of a time chosen in the end-condition sheet (SPEC.md §4.4). */
internal enum class EndChoiceResult {
    /** The snooze now ends when the user asked it to — including where it already did. */
    APPLIED,

    /**
     * There is no snooze left to refine: a departure, the cap, or a capability
     * loss ended it while the sheet sat open.
     *
     * Distinct from [REFUSED] because it is not retryable and not a failure of
     * anything: the sheet dismisses rather than offering a message over a
     * snooze that is already over, and every later tap would fail identically
     * (Codex, PR #118). Whatever ended the snooze has posted its own card.
     */
    GONE,

    /** The change did not take, and trying again might work. */
    REFUSED,
}

internal object EndChoiceOutcome {

    private var listener: ((EndChoiceResult) -> Unit)? = null

    /**
     * An outcome that arrived with nobody listening, kept for whoever asks next.
     *
     * A configuration change destroys the sheet and builds another one, and the
     * service may answer in between — an outcome dropped there would leave the
     * replacement sheet waiting on a commit that has already finished (Codex,
     * PR #118). Held rather than delivered, because only a sheet that knows it
     * was mid-commit should act on it; see [takePending].
     */
    private var pending: EndChoiceResult? = null

    /** Watches for the next outcome. Closing the handle stops it. */
    fun watch(onOutcome: (EndChoiceResult) -> Unit): AutoCloseable {
        listener = onOutcome
        // Identity, so a handle closed after a later watch replaced it doesn't
        // silently unhook the newer one.
        return AutoCloseable { if (listener === onOutcome) listener = null }
    }

    /**
     * The outcome that landed while nothing was watching, cleared as it is read.
     *
     * Deliberately *not* delivered by [watch]: a sheet starting a fresh commit
     * registers a watch too, and handing it an answer to somebody else's tap is
     * how a new choice gets reported as already applied. So the restore path
     * asks for it and the commit path discards it, and each says which it is
     * doing.
     */
    fun takePending(): EndChoiceResult? = pending.also { pending = null }

    /** Reports what became of a chosen end time. */
    fun report(result: EndChoiceResult) {
        val waiting = listener
        if (waiting != null) waiting(result) else pending = result
    }
}
