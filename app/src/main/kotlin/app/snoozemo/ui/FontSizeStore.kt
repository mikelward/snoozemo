package app.snoozemo.ui

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.snoozemo.core.DEFAULT_FONT_SCALE
import app.snoozemo.core.FontSizeSettings
import app.snoozemo.core.clampFontScale
import java.util.concurrent.Executors

/**
 * Where the chosen size is kept, as [FontSizeSetting] sees it.
 *
 * A seam rather than the class itself so a test can hand [FontSizeSetting] a
 * store whose writes fail on demand: storage refusing a `commit()` is the one
 * thing the reconciliation below exists for, and `SharedPreferences` offers no
 * way to make a real one fail.
 */
internal interface FontSizeWriter {
    fun read(): FontSizeSettings
    fun setScale(scale: Float): Boolean
    fun setPinchEnabled(enabled: Boolean): Boolean
}

/**
 * Remembers how big Snoozemo's own text is and whether a pinch may change it
 * (`SPEC.md` §4.8).
 *
 * Its own one-key-per-field `SharedPreferences` file, like the other settings
 * stores in this app, and clamped on read so a value written by a build with a
 * wider range can never size a screen past what this one lays out.
 *
 * Nothing about the user, the place, or the time is written here — one number
 * and one boolean about how the app draws itself.
 */
internal class FontSizeStore(
    context: Context,
    /**
     * Overridable so a test writes its own file rather than racing the running
     * app's collector for the real one.
     */
    @VisibleForTesting
    fileName: String = FILE_NAME,
) : FontSizeWriter {
    private val prefs = context.applicationContext
        .getSharedPreferences(fileName, Context.MODE_PRIVATE)

    /** The stored settings, or the defaults where nothing has been chosen. */
    override fun read(): FontSizeSettings = FontSizeSettings(
        scale = clampFontScale(prefs.getFloat(KEY_SCALE, DEFAULT_FONT_SCALE)),
        pinchEnabled = prefs.getBoolean(KEY_PINCH, true),
    )

    /**
     * Persists [scale], returning whether the write reached disk.
     *
     * The same restore-on-refusal the switches beside it do, and for the same
     * reason: `commit()` applies the change to the process-local map *before*
     * the disk write it reports on, so without putting the old value back every
     * later read would return a size that was neither applied nor stored — text
     * at one size until a process restart put it back at another.
     */
    override fun setScale(scale: Float): Boolean = write(KEY_SCALE) {
        putFloat(KEY_SCALE, clampFontScale(scale))
    }

    /** Persists the pinch switch, returning whether the write reached disk. */
    override fun setPinchEnabled(enabled: Boolean): Boolean = write(KEY_PINCH) {
        putBoolean(KEY_PINCH, enabled)
    }

    private fun write(key: String, edit: android.content.SharedPreferences.Editor.() -> Unit): Boolean {
        val before = read()
        val persisted = prefs.edit().apply(edit).commit()
        if (!persisted) {
            prefs.edit().apply {
                when (key) {
                    KEY_SCALE -> putFloat(KEY_SCALE, before.scale)
                    else -> putBoolean(KEY_PINCH, before.pinchEnabled)
                }
            }.commit()
        }
        return persisted
    }

    /**
     * Pulls the file into memory off the main thread, like the stores beside it.
     *
     * Never on the arm path — nothing between a tile tap and the zen rule reads
     * this — but every screen's first frame is sized from it, and a cold read in
     * front of that frame is the wait `SPEC.md` §4.1 keeps off the UI.
     */
    fun warm() {
        read()
    }

    private companion object {
        const val FILE_NAME = "font_size"
        const val KEY_SCALE = "scale"
        const val KEY_PINCH = "pinch"
    }
}

/**
 * The text size in force right now, for every screen in the process, and the
 * write side of [FontSizeStore].
 *
 * Held here rather than on a screen because a pinch resizes the *app*, not the
 * page it happened on: the app screens and the tile trampoline's sheet each
 * compose their own tree, and all of them read this. Compose state, so a change
 * — a pinch, a slider, or the warm read landing — recomposes whichever of them
 * is on screen without anyone polling.
 *
 * Writes go to a FIFO daemon worker and report their outcome, the shape
 * `EndSheetSetting` established: `commit()` is a synchronous file write and this
 * is a drag on a screen held to a scroll budget, and a setting that cannot tell
 * the user its choice didn't land is principle 2's failure.
 */
internal object FontSizeSetting {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "snoozemo-font-size").apply { isDaemon = true }
    }

    /**
     * What the app is drawn at: updated the moment a gesture or a drag ends, and
     * reconciled against the store once the writes behind it have drained.
     *
     * Starts at the defaults rather than null: unlike a switch, there is no
     * honest "not read yet" way to draw text, so the first frame is drawn at the
     * system's own size and [warm] corrects it if the user chose another. That
     * correction is a resize of text already on screen — which is why it is
     * warmed from `Application.onCreate` rather than read when a screen opens.
     */
    var current: FontSizeSettings by mutableStateOf(FontSizeSettings())
        private set

    /**
     * Whether the size's own last write was refused by storage, so the slider
     * can say so rather than letting the size springing back read as a missed
     * drag.
     */
    var scaleSaveRefused: Boolean by mutableStateOf(false)
        private set

    /**
     * The same for the pinch switch, kept apart from [scaleSaveRefused] rather
     * than as one flag for both (Codex, PR #217).
     *
     * Two changes can be outstanding at once, and one flag meant the later
     * write's outcome erased the earlier one's: a refused switch followed by a
     * saved size reported success, so the switch sprang back with nothing
     * saying why — principle 2's failure, and the reconciliation that restores
     * the *value* cannot report it either. A field's flag is written only by
     * that field's own writes, so neither can speak for the other.
     */
    var pinchSaveRefused: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether anything has been chosen in this process yet, so a [warm] that has
     * not run yet cannot overwrite it.
     *
     * Both halves run on the FIFO worker, so a write enqueued after the warm is
     * already ordered behind it; this covers the other order — the optimistic
     * update the caller makes on its own thread the moment the finger lifts,
     * which a still-queued warm would otherwise undo. Guarded by [lock] like
     * the rest of this state, rather than volatile on its own.
     */
    private var chosen = false

    /**
     * Reads the stored size into [current], off the main thread. Called from
     * `Application.onCreate`.
     *
     * **Narrows the window rather than closing it** (Codex, PR #217), the same
     * caveat `EndSheetStore.warm` carries: a screen composed in the first
     * instant of a cold process can still draw at the default and resize when
     * this lands. Closing it would mean either blocking startup — a cold tile
     * tap runs through `Application.onCreate` on its way to arming, and nothing
     * may sit in front of that (`SPEC.md` §6.9) — or holding the first frame
     * back on a disk read, which principle 5 forbids. A rare one-frame resize
     * is the cheaper of the three.
     */
    fun warm(context: Context) {
        val appContext = context.applicationContext
        dispatch {
            val stored = writerFor(appContext).read()
            synchronized(lock) { if (!chosen) current = stored }
        }
    }

    /**
     * Where a write goes, and what runs it. Replaced in tests so the ordering
     * between two queued writes can be made explicit rather than raced.
     */
    @VisibleForTesting
    internal var writerFor: (Context) -> FontSizeWriter = { FontSizeStore(it) }

    @VisibleForTesting
    internal var dispatch: (Runnable) -> Unit = { worker.execute(it) }

    /**
     * Guards [current], [outstanding], [chosen] and the two refusal flags
     * **together** (Codex, PR #217).
     *
     * Successive rounds of review found three interleavings between the caller
     * thread and the worker — a reconcile landing between publishing a value
     * and registering its write, and a stale copy of `current` taken before
     * either — each a different gap in the same shape: pieces of one state
     * moved one at a time. A lock over all of them makes that class of bug
     * unavailable rather than fixing its instances. The disk write and read
     * stay outside it, so nothing holds a lock across file I/O.
     */
    private val lock = Any()

    /**
     * How many writes are still queued, so a completed one knows whether it is
     * the last word; see [apply]. Guarded by [lock].
     */
    private var outstanding = 0

    /** The size a drag or a pinch settled on. */
    fun setScale(context: Context, scale: Float) {
        val settled = clampFontScale(scale)
        apply(
            context = context,
            wanted = { it.copy(scale = settled) },
            write = { it.setScale(settled) },
            report = { refused -> scaleSaveRefused = refused },
        )
    }

    /** The "Pinch to resize text" switch was set to [enabled]. */
    fun setPinchEnabled(context: Context, enabled: Boolean) {
        apply(
            context = context,
            wanted = { it.copy(pinchEnabled = enabled) },
            write = { it.setPinchEnabled(enabled) },
            report = { refused -> pinchSaveRefused = refused },
        )
    }

    /**
     * Shows the wanted value immediately, persists it on the worker, and
     * reconciles what is shown against what is stored once the queue has
     * drained.
     *
     * Optimistic because the alternative is text that lags a finger by a file
     * write — the drag and the pinch are both previewed continuously, so the
     * displayed size is already ahead of the store by the time this is called.
     *
     * **Reconciled from the store rather than rolled back to a remembered
     * value** (Codex, PR #217). Two changes can be outstanding at once — the
     * switch tapped, then the slider released — and an earlier write failing
     * used to restore the value captured before it, which is neither what the
     * user last asked for nor what is on disk. Reading the store instead is true
     * in every one of those orders, and it is the store — not this — that
     * decides what a refused write leaves behind.
     *
     * Only the last write reconciles: while another change is still queued, the
     * newest optimistic value is the one to show, and correcting to the store in
     * between would flash a size the user has already moved past.
     *
     * [wanted] is applied to `current` **inside [lock]**, along with the
     * registration, so the value published is derived from what is displayed at
     * that instant rather than from a copy taken before a worker reconciled
     * (Codex, PR #217). [report] takes the outcome to the flag for *this* field
     * alone, so a later write cannot clear an earlier one's refusal.
     */
    private fun apply(
        context: Context,
        wanted: (FontSizeSettings) -> FontSizeSettings,
        write: (FontSizeWriter) -> Boolean,
        report: (Boolean) -> Unit,
    ) {
        val appContext = context.applicationContext
        synchronized(lock) {
            chosen = true
            current = wanted(current)
            outstanding++
        }
        dispatch {
            val store = writerFor(appContext)
            // The write and the read are the only slow parts, and neither holds
            // the lock: the worker is single-threaded, so nothing else can be
            // writing while these run.
            val refused = !write(store)
            val stored = store.read()
            synchronized(lock) {
                report(refused)
                if (--outstanding == 0) current = stored
            }
        }
    }

    /** Drops what this process chose, for a test that must not inherit another's state. */
    @VisibleForTesting
    internal fun resetForTest() {
        current = FontSizeSettings()
        scaleSaveRefused = false
        pinchSaveRefused = false
        chosen = false
        synchronized(lock) { outstanding = 0 }
        writerFor = { FontSizeStore(it) }
        dispatch = { worker.execute(it) }
    }
}
