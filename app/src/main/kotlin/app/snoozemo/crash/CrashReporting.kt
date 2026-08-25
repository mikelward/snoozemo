package app.snoozemo.crash

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.snoozemo.core.SnoozeDebugLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Keeps the crash reporter in step with the stored opt-out (`SPEC.md` §12).
 *
 * All the decidable logic lives here, in the flavor-agnostic half: the store,
 * the threading, and how a refused write is surfaced. [CrashReporter] — the
 * one piece that differs between flavors — is just "is there a reporter, and
 * turn it on or off".
 *
 * **Everything touching the store runs on one FIFO daemon worker**, for the
 * same reason `DebugLogging` does: `commit()` is a synchronous disk write, and
 * `Application.onCreate` sits directly ahead of a possible cold tile tap
 * (`SPEC.md` §4.1). [isAvailable] is the one call made on the caller's thread,
 * because it reads an in-memory list rather than disk and the first frame
 * needs its answer to decide whether to draw the row at all.
 */
internal object CrashReporting {

    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "snoozemo-crash-reporting").apply { isDaemon = true } }

    /**
     * Whether the most recently completed toggle write was refused by storage.
     *
     * Process-level rather than screen-level, exactly as
     * `DebugLogging.lastSaveRefused` is and for the same reason: the
     * completion callback closes over the activity that made the tap, and a
     * configuration change mid-write hands the screen to a replacement that
     * callback cannot reach — the failure explanation would vanish with the
     * dead instance. Any instance reads this instead. Written only on the FIFO
     * worker, so after the queue drains it is the *latest* write's outcome.
     */
    @Volatile
    var lastSaveRefused: Boolean = false
        private set

    /**
     * Notified once a toggle write has finished and [lastSaveRefused] holds
     * that write's own outcome.
     *
     * The screen that made the tap may be dead by then — a configuration
     * change mid-write hands off to a replacement whose own first-frame read
     * already ran, against a store the worker had not yet updated. Its
     * completion callback can only reach the dead instance, so without this
     * the replacement would show the pre-tap value for good and swallow a
     * refused save entirely (Codex, PR #113; the same failure `DebugLogging`
     * carries `watchSaveOutcome` for, found twice there already).
     */
    @Volatile
    private var onSaveOutcome: (() -> Unit)? = null

    /**
     * Registers [onChange], to be called on the worker once a write's outcome
     * is readable. The returned handle clears it, and clears **only** this
     * registration — a later registrant must not be unregistered by an earlier
     * one's handle closing.
     */
    fun watchSaveOutcome(onChange: () -> Unit): AutoCloseable {
        onSaveOutcome = onChange
        return AutoCloseable { if (onSaveOutcome === onChange) onSaveOutcome = null }
    }

    /**
     * How a choice reaches the SDK. Indirect solely so a test can pin the
     * *order* of the two durable writes [setEnabled] makes — the ordering is
     * the whole correctness argument there, and `CrashReporter` is a
     * flavor-split object with no other seam.
     *
     * Restore it after swapping: it is process-wide, like everything else on
     * this object.
     */
    @VisibleForTesting
    internal var applyToReporter: (Context, Boolean) -> ReporterOutcome =
        { context, enabled -> CrashReporter.apply(context, enabled) }

    /**
     * Whether this build has a reporter to offer at all — false on `direct`
     * always, and on a `play` build made without a `google-services.json`
     * (`docs/crashlytics.md`). Settings draws no row when this is false: a
     * switch over a reporter that does not exist would tell the user they had
     * turned something off that was never on.
     */
    fun isAvailable(context: Context): Boolean = CrashReporter.isAvailable(context)

    /**
     * Whether the user has left reporting on. A memory hit once [install] has
     * warmed the file; safe from the first frame.
     */
    fun isEnabled(context: Context): Boolean = CrashReportingStore(context).isEnabled()

    /**
     * Call once from `Application.onCreate`. Applies the stored choice to the
     * SDK, which is what makes the opt-out real: the manifest starts
     * Crashlytics with collection **off**, so nothing is collected on a fresh
     * install until this says otherwise, and an install where the user has
     * opted out never starts collecting at all.
     *
     * Off the caller's thread, like `DebugLogging.install` and
     * `SnoozeNotifications.warm`, and for the same reason.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        if (!CrashReporter.isAvailable(app)) return
        // A rejected submission must not take the caller's thread with it —
        // this runs in Application.onCreate, ahead of a possible cold tile
        // tap, and crash reporting is never worth failing an arm over. The
        // cost of losing it is one process with the manifest's `false` still
        // standing, i.e. no reports until the next launch, which is the safe
        // direction.
        runCatching {
            worker.execute {
                val enabled = CrashReportingStore(app).isEnabled()
                if (applyToReporter(app, enabled) == ReporterOutcome.NO_REPORTER) {
                    SnoozeDebugLog.warning("crash reporting unavailable; nothing applied")
                }
            }
        }.onFailure {
            SnoozeDebugLog.warning("crash reporting setup was refused a worker", it)
        }
    }

    /**
     * Persists [enabled] and applies it to the SDK, then reports back on the
     * caller's behalf whether the write reached disk.
     *
     * The SDK is given whatever the store *ended up* holding rather than the
     * requested value: a refused write restores the previous value
     * ([CrashReportingStore.setEnabled]), so applying the request anyway would
     * leave the reporter and the switch disagreeing until the next launch put
     * them back — the user would see the switch snap back to On with
     * collection actually off, which is the silent-wrong-thing this app's
     * second principle exists to stop. The failure line under the switch is
     * what tells them the tap did not take.
     *
     * **The two durable writes are ordered, and asymmetrically.** This method
     * writes to two independent stores that persist separately — this app's
     * preference, and Crashlytics' own collection override — so a process
     * death between them leaves them disagreeing, and *which* disagreement is
     * survivable is not symmetric. Crashlytics' override outranks the
     * manifest's `false` on the next launch, so an opt-out that reached only
     * this app's store would meet an SDK still holding `true` and could upload
     * a queued report before [install]'s worker reapplied the choice — the
     * user's opt-out broken by a crash they never saw (Codex, PR #113).
     *
     * So **off reaches the SDK first, on reaches the store first**: both
     * orderings leave a torn write pointing at "not collecting". The cost of
     * being wrong that way is one missed report, which is the side to be wrong
     * on.
     *
     * Ordering the *calls* is necessary but not sufficient, and it took a
     * second pass from Codex to see why: the SDK persists its override with
     * `Editor.apply()`, so it returns before that value is durable, while the
     * store below commits synchronously. `CrashReporter.apply` therefore
     * blocks on the SDK's write reaching disk before returning on the off
     * path — see `flushCollectionOverride` there for how, and for what is
     * left when the SDK's internals move under it.
     */
    fun setEnabled(context: Context, enabled: Boolean, onApplied: (persisted: Boolean) -> Unit) {
        val app = context.applicationContext
        runCatching {
            worker.execute {
                val store = CrashReportingStore(app)
                // The off half of the ordering above. Deliberately ahead of
                // the store write, and deliberately not conditional on it:
                // stopping collection is the part that must survive a process
                // death here.
                val offOutcome = if (!enabled) applyToReporter(app, false) else null
                if (offOutcome == ReporterOutcome.NOT_DURABLE) {
                    // The SDK is off in memory but its own override may still
                    // read on after a process death. Committing our `off` now
                    // would build exactly the split state the flush exists to
                    // rule out — our preference durably off, the SDK's durably
                    // on — so the preference is left alone and the tap is
                    // reported as refused, which is the failure line the switch
                    // already knows how to show (Codex, PR #113).
                    //
                    // The SDK is deliberately not put back to on: within this
                    // session not collecting is the safe direction, and the
                    // next launch reconciles it from the store either way.
                    lastSaveRefused = true
                    SnoozeDebugLog.warning("crash reporting: opt-out could not be made durable; setting unchanged")
                    onSaveOutcome?.invoke()
                    onApplied(false)
                    return@execute
                }
                val preApplied: Boolean? = if (!enabled) false else null
                val persisted = runCatching { store.setEnabled(enabled) }
                    .onFailure { SnoozeDebugLog.warning("crash reporting setting could not be saved", it) }
                    .getOrDefault(false)
                lastSaveRefused = !persisted
                val effective = store.isEnabled()
                // Skipped only when the pre-apply above already put the SDK
                // where the store ended up — which is the ordinary successful
                // opt-out. A refused write restores `true`, and that has to
                // reach the SDK so the reporter and the switch agree.
                if (preApplied != effective &&
                    applyToReporter(app, effective) == ReporterOutcome.NO_REPORTER
                ) {
                    SnoozeDebugLog.warning("crash reporting unavailable; setting saved but not applied")
                }
                SnoozeDebugLog.event(
                    "crash reporting ${if (effective) "on" else "off"}" +
                        if (persisted) "" else " (not saved)",
                )
                // Assign-then-notify: `lastSaveRefused` and the store are both
                // settled above, so a reader woken here sees this attempt's own
                // outcome rather than the previous one's. Ahead of [onApplied]
                // so the surviving screen is reconciled even when the callback
                // below belongs to an instance that is already gone.
                onSaveOutcome?.invoke()
                onApplied(persisted)
            }
        }.onFailure {
            // Never leave the tap unanswered: the screen is holding the user's
            // requested value and waiting for this callback to reconcile it.
            SnoozeDebugLog.warning("crash reporting toggle was refused a worker", it)
            lastSaveRefused = true
            onSaveOutcome?.invoke()
            onApplied(false)
        }
    }
}
