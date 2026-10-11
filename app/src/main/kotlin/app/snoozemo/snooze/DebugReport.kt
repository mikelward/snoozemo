package app.snoozemo.snooze

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.snoozemo.R
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.dnd.PrefsRingerLoanStore
import app.snoozemo.dnd.SnoozeRingerStore
import app.snoozemo.tile.TilePresenceStore
import app.snoozemo.ui.locationTrackingNeedsBackgroundPermission
import com.mikelward.androidlog.android.CollectedReport
import com.mikelward.androidlog.android.ShareOutcome
import com.mikelward.androidlog.android.DebugReport as SharedReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DebugReport"

/**
 * Builds the SPEC.md §4.6 share payload and hands it to the system share
 * sheet, with a copy-to-clipboard fallback (`TODO.md` Phase 5, `docs/DEBUG.md`).
 * The mechanism is androidlog's `DebugReport`, the one every app in the fleet
 * shares through: it appends the earlier runs, holds the report to a size that
 * pastes whole, and lowers the crash banner on a landed copy. What is here is
 * this app's section, and the tap-level bookkeeping the Share button needs.
 *
 * Ported from the sibling Simmo/ClothesCast repos' `DebugReport`/`BugReport`,
 * narrowed to what this app actually has to report: there is no settings or
 * rules dump here, because — unlike those two — Snoozemo has no equivalent
 * structured state worth one. The log *is* the report.
 *
 * [share] is synchronous and does binder and disk I/O; callers run it off the
 * main thread (`MainActivity.shareDebugLog`), the same discipline every other
 * slow read in this app already follows.
 */
internal object DebugReport {

    /**
     * Whether the most recently completed [share] reached neither delivery
     * route. Process-level, mirroring [DebugLogging.lastSaveRefused] and for
     * the identical reason: [share] runs on a caller-provided background
     * thread whose completion callback closes over the activity that
     * started it, and a configuration change mid-share hands the screen to
     * a replacement that closure cannot reach (Codex, PR #89). Any instance
     * reads this instead, via [watchShareOutcome].
     */
    @Volatile
    var lastShareFailed: Boolean = false
        private set

    @Volatile
    private var onShareOutcome: (() -> Unit)? = null

    /**
     * The latest share attempt ticket issued so far — see [nextAttempt]. A
     * second tap while an earlier attempt is still collecting its payload
     * starts a second, unsynchronized [share] call, and the two can finish
     * in either order. [share] compares its own `attempt` against this
     * field, not against the highest attempt whose outcome has *landed*:
     * comparing against completions alone let an older attempt that was
     * still in flight when a newer one was issued apply its own outcome the
     * moment it finished, even though the newer tap the user is actually
     * waiting on hadn't resolved yet — a stale failure (or success) shown,
     * or shown indefinitely if the newer attempt then stalled (Codex,
     * PR #89, third round on this mechanism). Comparing against the issued
     * ticket instead means only the single most-recently-issued attempt can
     * ever update the visible state, whenever it finishes; every superseded
     * attempt's own completion is discarded outright, not merely raced.
     *
     * Deliberately process-level, not caller-scoped: an activity-scoped
     * counter reset to zero by a configuration change would hand out
     * tickets *lower* than the process-level high-water mark already
     * reached before the recreation, so every share fired by the
     * replacement instance would read as stale and never surface (Codex,
     * PR #89, second round on this same mechanism). Guarded by [applyLock]
     * rather than left `@Volatile`, since applying an outcome is a
     * read-compare-write, not a single field write.
     */
    private var attemptTicket: Int = 0

    private val applyLock = Any()

    /**
     * Serializes the actual delivery (clipboard write, chooser launch, banner
     * acknowledgement) across concurrently-running [share] calls — separate from
     * [applyLock], which stays fast and non-blocking since [nextAttempt] is
     * called synchronously on the tap thread and must never wait on a
     * background attempt's binder/IPC work (Codex, PR #89, fourth round on
     * this mechanism: the ticket only ever guarded [lastShareFailed], never
     * these side effects, so two taps close together could still both write
     * the clipboard and open a chooser, unordered).
     */
    private val deliveryLock = Any()

    /**
     * Whether a share is currently in flight — set when a ticket is issued
     * ([nextAttempt]) and cleared when that attempt's own outcome lands.
     * Callers gate their Share affordance on this, so a second tap can't
     * happen while the first is still resolving.
     *
     * **This is the whole answer to "what happens when the user taps Share
     * twice quickly", and it replaces a much larger mechanism.** This class
     * previously answered that question *after the fact*, at the delivery
     * layer: a completed-delivery counter, the last delivery's own outcome
     * and its pin-safety, and a per-ticket map of counter snapshots, all so
     * a second attempt could retroactively work out that it was a duplicate
     * of a delivery already in progress and reuse that outcome instead of
     * opening a second chooser. That machinery accumulated a field or a
     * condition per review round and was, by itself, the single largest
     * source of defects in this file (PR #89, twelve findings). Coalescing
     * at the tap removes the question rather than answering it: with the
     * affordance gated there is no second concurrent tap to reconcile, so
     * the counter, the two cached-outcome fields and the snapshot map are
     * all gone, along with the branch that consumed them.
     *
     * Process-level rather than activity-scoped, for the same reason
     * [attemptTicket] is: a configuration change mid-share must not hand the
     * replacement instance a re-enabled button for a share that is still
     * running. Observers refresh through [watchShareOutcome] and re-read
     * this on `onStart`, exactly as they already do for [lastShareFailed].
     *
     * [deliveryLock] stays as the floor beneath this: gating is a UI
     * contract, and this function must still behave if some future caller
     * doesn't honor it. Serialized delivery plus the [attemptTicket] check
     * means a superseded attempt skips delivery entirely rather than
     * clobbering a newer one — the guarantee that never depended on the
     * reuse machinery.
     */
    @Volatile
    var shareInFlight: Boolean = false
        private set

    /**
     * Issues the next share attempt ticket, for a caller that can fire more
     * than one concurrently-running share (the repeatable Share button) and
     * wants the *visible* outcome to track the latest tap — see [share]'s
     * own `attempt` parameter and [attemptTicket]. Safe to call from any
     * thread; callers still call it synchronously at tap time, before
     * starting the background work, so ticket order is tap order.
     *
     * Also clears [lastShareFailed]: a caller only clears its own local
     * failure flag when it starts a retry, and a configuration change or
     * restart mid-retry would otherwise have the replacement instance's
     * `onStart` reload the *previous* attempt's still-true process-level
     * outcome and show a failure message the retry already superseded —
     * indefinitely, if the retry itself then stalled (Codex, PR #89). Since
     * only this ticket's own eventual completion may apply an outcome from
     * here on (the `attempt >= attemptTicket` guard in [share]), clearing
     * the shared flag the moment the new ticket is issued is safe: nothing
     * else can set it back to `true` before this attempt's own result does.
     *
     * Also raises [shareInFlight], which the caller's Share affordance is
     * gated on. Raised here rather than inside [share] for the same reason
     * the ticket is issued here: this runs synchronously on the tap thread,
     * so the button is disabled by the time the tap returns, with no window
     * in which a background thread's scheduling delay could let a second tap
     * through.
     */
    fun nextAttempt(): Int = synchronized(applyLock) {
        lastShareFailed = false
        shareInFlight = true
        ++attemptTicket
    }

    /** Test-only: resets the process-level outcome state so tests don't leak into each other. */
    internal fun resetForTest() {
        lastShareFailed = false
        shareInFlight = false
        onShareOutcome = null
        synchronized(applyLock) { attemptTicket = 0 }
    }

    /** Mirrors [DebugLogging.watchSaveOutcome]; see [lastShareFailed]. */
    fun watchShareOutcome(onChange: () -> Unit): AutoCloseable {
        onShareOutcome = onChange
        return AutoCloseable { if (onShareOutcome === onChange) onShareOutcome = null }
    }

    /**
     * Collects the report and delivers it: a clipboard copy and the share
     * chooser, both attempted, through androidlog's [SharedReport.deliver]
     * (SPEC.md §4.6, `docs/DEBUG.md`), the same path every app in the fleet
     * shares through. A landed copy lowers the crash banner for the crashes
     * the report carried; nothing is deleted, and the earlier runs age out
     * under the logger's own retention (maintainer, 2026-10-11).
     *
     * Returns what the delivery achieved, or null when a newer tap superseded
     * this attempt before it delivered. [collect] and [deliver] are test
     * seams; production uses the defaults.
     *
     * [attempt] identifies which tap this call answers, so a caller that can
     * fire more than one concurrently-running share (the repeatable Share
     * button) can make the *visible* outcome track the latest tap rather
     * than whichever attempt's binder/disk work happens to finish last — see
     * [attemptTicket]. Obtain it from [nextAttempt] at tap time, before
     * starting the background work — never from a caller-scoped counter,
     * which a configuration change would reset behind the process-level
     * high-water mark this compares against (Codex, PR #89). The default of
     * 0 is fine for a single, non-concurrent call (tests, and any future
     * caller that never fires two attempts at once): every such call's own
     * attempt is trivially `>=` the ticket counter, which nothing else ever
     * advances in that case.
     */
    fun share(
        context: Context,
        attempt: Int = 0,
        collect: (Context) -> CollectedReport = ::collectReport,
        deliver: (Context, CollectedReport) -> ShareOutcome = ::deliverOnMain,
        fallback: (Throwable) -> CollectedReport = ::collectFallback,
    ): ShareOutcome? {
        // A collection that throws still shares something, and every path,
        // even one where the fallback throws too, reaches the settling below:
        // an exception escaping here left the Share buttons disabled for the
        // rest of the process with nothing to say why (Codex, PR #326). A
        // report that could not be built at all is a failed share.
        val report: CollectedReport? = runCatching { collect(context) }
            .onFailure { SnoozeDebugLog.failure(it, "debug report: building it failed; sharing a minimal fallback") }
            .recoverCatching { fallback(it) }
            .onFailure { SnoozeDebugLog.failure(it, "debug report: the fallback could not be built either") }
            .getOrNull()
        // Delivery is serialized on deliveryLock, not just the outcome it
        // produces: two concurrent calls would otherwise both write the
        // clipboard and open a chooser, unordered (Codex, PR #89). Callers gate
        // their Share affordance on [shareInFlight], so in practice a second
        // concurrent call doesn't arise — this is the floor beneath that.
        //
        // The ticket is re-checked once the lock is held, rather than only at
        // entry: a concurrent tap can issue a newer ticket while this attempt
        // is still collecting. An attempt found stale here skips delivery
        // entirely rather than clobbering what a newer attempt delivered.
        return synchronized(deliveryLock) {
            if (attempt < synchronized(applyLock) { attemptTicket }) return@synchronized null
            val outcome = if (report == null) {
                ShareOutcome.FAILED
            } else {
                runCatching { deliver(context, report) }
                    .onFailure { SnoozeDebugLog.failure(it, "debug report: delivering it failed") }
                    .getOrDefault(ShareOutcome.FAILED)
            }
            // Only the most-recently-issued attempt may update the visible
            // state, whether or not a newer tap has completed yet, so a stale
            // outcome is never shown over a retry the user is waiting on
            // (Codex, PR #89). A superseded attempt returns above and never
            // clears the in-flight flag, which belongs to the newer attempt.
            synchronized(applyLock) {
                if (attempt >= attemptTicket) {
                    lastShareFailed = outcome == ShareOutcome.FAILED
                    shareInFlight = false
                    runCatching { onShareOutcome?.invoke() }
                }
            }
            outcome
        }
    }

    /**
     * The fallback still goes through the log's sink where it can, so a copy
     * that lands lowers the crash banner like any other share (Codex, PR
     * #326): without the sink a fallback reported success and left the banner
     * up for the next tap. A sink that fails again falls back to none, since
     * the sink is the one part that may be what threw.
     */
    private fun collectFallback(failure: Throwable): CollectedReport {
        val sink = runCatching { (DebugLogging.sinkForReport() as? DebugLogging.SinkForReport.Available)?.sink }
            .onFailure { SnoozeDebugLog.failure(it, "debug report: the fallback could not get the log sink") }
            .getOrNull()
        return runCatching {
            SharedReport.collect(SnoozeDebugLog, sink, heading = EARLIER_RUNS_HEADING) { fallbackPayload(failure) }
        }.onFailure { SnoozeDebugLog.failure(it, "debug report: the fallback could not read the earlier runs") }
            .getOrElse { SharedReport.collect(SnoozeDebugLog, null) { fallbackPayload(failure) } }
    }

    /**
     * A minimal report for when collecting the real one threw: the one part
     * that cannot fail to build is still worth sending. This line names only
     * the failure's type; the failure itself is in the recent log below, which
     * carries a thrown message under the log's own rule (SPEC §4.6) like any
     * other failure the app records.
     */
    private fun fallbackPayload(failure: Throwable): String = buildString {
        appendLine("Snoozemo debug log")
        appendLine("Report collection failed: ${failure.javaClass.name}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append(renderRecentLog(recentLogForReport(FALLBACK_LOG_CHARS), FALLBACK_LOG_CHARS))
    }

    /**
     * The report: this app's section, with the earlier runs the library reads
     * and appends ([SharedReport.collect]).
     *
     * Blocks on disk and binder calls; [share] runs on a background thread.
     */
    private fun collectReport(context: Context): CollectedReport {
        val handed = DebugLogging.sinkForReport()
        val sink = (handed as? DebugLogging.SinkForReport.Available)?.sink
        return SharedReport.collect(SnoozeDebugLog, sink, heading = EARLIER_RUNS_HEADING) {
            buildDebugReportPayload(
                nowMillis = System.currentTimeMillis(),
                versionName = appVersionName(context),
                versionCode = appVersionCode(context),
                applicationId = context.packageName,
                isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE,
                androidSdkInt = Build.VERSION.SDK_INT,
                locale = Locale.getDefault(),
                policyAccessGranted = isPolicyAccessGranted(context),
                notificationsGranted = isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS),
                locationFineGranted = isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION),
                locationCoarseGranted = isPermissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
                locationBackgroundGranted = isPermissionGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                locationBackgroundRequired = locationTrackingNeedsBackgroundPermission,
                locationServicesEnabled = isLocationServicesEnabled(context),
                batterySaverOn = isBatterySaverOn(context),
                tileAdded = isTileAdded(context),
                ringerMode = ringerMode(context),
                snoozeRingerCeiling = snoozeRingerCeiling(context),
                // Said in the report, not only the log: a share that quietly
                // left the earlier runs out would read like one with none to
                // send (Codex, PR #89).
                earlierRunsUnavailable = handed == DebugLogging.SinkForReport.Unavailable,
                recentLog = ::recentLogForReport,
            )
        }
    }

    /**
     * [SharedReport.deliver] on the main thread, which it asks for because it
     * touches the clipboard. Inline when already there (tests, Robolectric);
     * otherwise posted and waited for, which costs the background thread
     * [share] runs on and nothing else.
     */
    private fun deliverOnMain(context: Context, report: CollectedReport): ShareOutcome {
        val deliver = {
            SharedReport.deliver(
                context = context,
                log = SnoozeDebugLog,
                report = report,
                subject = "Snoozemo debug log — ${appVersionName(context)}",
                chooserTitle = context.getString(R.string.setup_debug_log_share_title),
                clipboardLabel = "Snoozemo debug log",
            )
        }
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) return deliver()
        val result = LinkedBlockingQueue<ShareOutcome>(1)
        // Whichever comes first, the delivery starting or the wait giving up,
        // claims the attempt: a delivery that ran after the attempt was
        // reported as failed would copy, share and lower the banner behind the
        // user's back (Codex, PR #326).
        val claimed = AtomicBoolean(false)
        Handler(main).post {
            if (!claimed.compareAndSet(false, true)) return@post
            val outcome = runCatching(deliver)
                .onFailure { SnoozeDebugLog.failure(it, "debug report: delivering it failed") }
                .getOrDefault(ShareOutcome.FAILED)
            result.offer(outcome)
        }
        result.poll(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)?.let { return it }
        if (claimed.compareAndSet(false, true)) {
            SnoozeDebugLog.warning("debug report: delivery did not get the main thread in time")
            return ShareOutcome.FAILED
        }
        // It started just as the wait ran out, so it is the outcome.
        return result.take()
    }

    // Each of these returns null, not a substituted false, on its own
    // exception: the report exists specifically to diagnose failures, and
    // a check that couldn't run is not the same fact as a confirmed denial
    // or a confirmed-off toggle — collapsing "couldn't tell" into "false"
    // let a transient system-service failure read as a real capability
    // problem, with the only trace of the difference sitting in logcat,
    // which the report's recipient never sees (Codex, PR #89). Rendered as
    // "unknown" by grantLabel/boolLabel below rather than a state that
    // looks the same as a genuine answer.
    private fun isPolicyAccessGranted(context: Context): Boolean? =
        runCatching { AndroidZenController.default(context).policyAccess() == PolicyAccess.GRANTED }
            .onFailure { Log.w(TAG, "DebugReport could not read policy access.", it) }
            .getOrNull()

    private fun isPermissionGranted(context: Context, permission: String): Boolean? =
        runCatching {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }.onFailure { Log.w(TAG, "DebugReport permission check failed: $permission", it) }.getOrNull()

    private fun isLocationServicesEnabled(context: Context): Boolean? =
        runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true }
            .onFailure { Log.w(TAG, "DebugReport could not read location-services state.", it) }
            .getOrNull()

    private fun isBatterySaverOn(context: Context): Boolean? =
        runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }
            .onFailure { Log.w(TAG, "DebugReport could not read battery-saver state.", it) }
            .getOrNull()

    /**
     * The ringer, and the ceiling it is judged against (SPEC.md §5.9).
     *
     * Together, because either alone explains nothing: "the phone was ringing"
     * is a bug only if a ceiling said it should not have been, and a ceiling is
     * only interesting beside what the phone actually did. This is the pair that
     * answers "why did it still ring", which is the report's whole job.
     *
     * Null on its own exception, like every reader above it and for the same
     * reason: a check that could not run is not the same fact as a confirmed
     * mode.
     */
    private fun ringerMode(context: Context): String? =
        runCatching {
            when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "ring"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_SILENT -> "silent"
                else -> null
            }
        }.onFailure { Log.w(TAG, "DebugReport could not read the ringer mode.", it) }.getOrNull()

    /**
     * The ceiling **in force for the running snooze** where there is one, and
     * only otherwise the choice saved for next time — labeled, so the reader
     * knows which they are looking at (Codex, PR #176).
     *
     * Reading the setting alone diagnosed a healthy snooze as broken: a choice
     * changed mid-snooze governs the *next* one, so a running `VIBRATE` snooze
     * beside a freshly-picked `SILENT` read as `Ringer now: vibrate` under
     * `ceiling: SILENT` — a mismatch the reader would chase.
     */
    private fun snoozeRingerCeiling(context: Context): String? =
        runCatching {
            ringerCeilingLabel(
                inForce = PrefsRingerLoanStore(context).activeChoice(),
                chosen = SnoozeRingerStore(context).chosen(),
            )
        }
            .onFailure { Log.w(TAG, "DebugReport could not read the ringer ceiling.", it) }
            .getOrNull()

    private fun isTileAdded(context: Context): Boolean? =
        runCatching { TilePresenceStore(context).isAdded() }
            .onFailure { Log.w(TAG, "DebugReport could not read the tile's presence.", it) }
            .getOrNull()

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.onFailure { Log.w(TAG, "DebugReport could not read the app's own version name.", it) }
        .getOrDefault("unknown")

    private fun appVersionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.onFailure { Log.w(TAG, "DebugReport could not read the app's own version code.", it) }
        .getOrDefault(-1L)
}

/**
 * The pure payload builder — no Android dependencies beyond [Build]/[Locale],
 * so its shape is unit-testable without Robolectric.
 */
/**
 * Which ceiling the report names, and which of the two it is.
 *
 * Pure so both branches can be enumerated: the distinction is the whole point
 * of the line, and reading only the setting diagnosed a healthy snooze as
 * broken (Codex, PR #176).
 */
internal fun ringerCeilingLabel(inForce: SnoozeRinger?, chosen: SnoozeRinger): String =
    if (inForce != null) "${inForce.name} (in force)" else "${chosen.name} (next snooze)"

internal fun buildDebugReportPayload(
    nowMillis: Long,
    versionName: String,
    versionCode: Long,
    applicationId: String,
    isDebuggable: Boolean,
    deviceManufacturer: String,
    deviceModel: String,
    androidRelease: String,
    androidSdkInt: Int,
    locale: Locale,
    policyAccessGranted: Boolean?,
    notificationsGranted: Boolean?,
    locationFineGranted: Boolean?,
    locationCoarseGranted: Boolean?,
    locationBackgroundGranted: Boolean?,
    locationBackgroundRequired: Boolean,
    locationServicesEnabled: Boolean?,
    batterySaverOn: Boolean?,
    tileAdded: Boolean?,
    ringerMode: String? = null,
    snoozeRingerCeiling: String? = null,
    earlierRunsUnavailable: Boolean = false,
    recentLog: (budgetChars: Int) -> List<String>,
): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(nowMillis))
    val head = buildString {
        appendLine("Snoozemo debug log")
        appendLine("Captured: $timestamp")
        appendLine()
        appendLine("--- Build ---")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Application id: $applicationId")
        appendLine("Debuggable: $isDebuggable")
        appendLine()
        appendLine("--- Device ---")
        appendLine("Model: $deviceManufacturer $deviceModel")
        appendLine("Android: $androidRelease (SDK $androidSdkInt)")
        appendLine("Locale: ${locale.toLanguageTag()}")
        appendLine()
        // Every one of these is a plausible whole answer to "why didn't it
        // end" (SPEC.md §4.6) — a denied permission or a system-wide toggle
        // is often the entire explanation for a snooze that misbehaved.
        appendLine("--- State ---")
        appendLine("Do Not Disturb access: ${grantLabel(policyAccessGranted)}")
        appendLine("Notifications: ${grantLabel(notificationsGranted)}")
        appendLine("Location (foreground): ${foregroundLocationLabel(locationFineGranted, locationCoarseGranted)}")
        appendLine(
            "Location (background): " +
                backgroundLocationLabel(locationBackgroundGranted, locationBackgroundRequired),
        )
        appendLine("Location services on: ${boolLabel(locationServicesEnabled)}")
        appendLine("Battery saver on: ${boolLabel(batterySaverOn)}")
        appendLine("Quick Settings tile added: ${boolLabel(tileAdded)}")
        appendLine("Ringer now: ${ringerMode ?: "unknown"}")
        appendLine("Snooze ringer ceiling: ${snoozeRingerCeiling ?: "unknown"}")
    }
    val boundedHead = if (head.length > MAX_STRUCTURED_CHARS) {
        head.take(MAX_STRUCTURED_CHARS) + "\n…(details truncated to keep the report shareable)\n"
    } else {
        head
    }
    // The earlier runs themselves are the library's to append, after this
    // section (`SharedReport.collect`). What it cannot say is that they could
    // not be read at all, since it was handed no log to read.
    val unavailable = if (earlierRunsUnavailable) {
        "\n$EARLIER_RUNS_HEADING\n(could not be included in this report — try Share again)\n"
    } else {
        ""
    }
    // The log takes the room the rest left, fetched to fit it rather than
    // fetched whole and trimmed here: a trim keeps the newest and so drops
    // the pinned lines first, the ones that say why the last processes
    // ended.
    val rest = boundedHead + unavailable
    val logBudget = (APP_SECTION_CHARS - rest.length - LOG_HEADING_CHARS).coerceAtLeast(0)
    return rest + renderRecentLog(recentLog(logBudget), logBudget)
}

/**
 * The current run's log for a report, within [budgetChars]: the ring's newest
 * lines, with the pinned lines it has since evicted put back ahead of them, in
 * one read ([SharedReport.logLines]).
 *
 * Pinned lines (why the previous processes ended) are written once at startup,
 * so on a busy run the ring has dropped them by the time a report is shared.
 * [SnoozeDebugLog.snapshot] is the ring alone and would leave them out (Codex,
 * PR #302). They come first, up to a whole process-exit batch, and whatever of
 * that they don't need goes to the newest lines.
 */
internal fun recentLogForReport(budgetChars: Int): List<String> =
    SharedReport.logLines(SnoozeDebugLog, budgetChars)

/**
 * The "Recent log" section — newest last, bounded to [budgetChars].
 *
 * The heading counts what is here and claims nothing about what isn't. It used
 * to read "N of M shown", with M the size of the list handed in, which was the
 * whole ring when this function did the only trimming. [recentLogForReport]
 * trims first, so M would equal N and the heading would call a report complete
 * exactly when lines had been dropped (the same fix as typelauncher's #706).
 */
private fun renderRecentLog(recentLog: List<String>, budgetChars: Int): String = buildString {
    appendLine()
    val kept = boundedLogTail(recentLog, budgetChars)
    appendLine(
        "--- Recent log (${kept.size} lines, newest last; older lines are dropped to keep the report shareable) ---",
    )
    if (kept.isEmpty()) {
        appendLine("(no captured log lines — has a snooze run since the app started?)")
    } else {
        kept.forEach { appendLine(it) }
    }
}

private fun grantLabel(granted: Boolean?): String = when (granted) {
    true -> "granted"
    false -> "denied"
    null -> "unknown"
}

/** See the helper functions in [DebugReport] this renders: null means the check itself failed. */
private fun boolLabel(value: Boolean?): String = value?.toString() ?: "unknown"

/**
 * A build whose tracking never needs `ACCESS_BACKGROUND_LOCATION` — and so
 * never declares it (`AndroidManifest.xml`, `SPEC.md` §3.4) — would always read
 * "denied" through [grantLabel] alone: a false capability problem for anyone
 * diagnosing it, since nothing in such a build ever requests or expects the grant
 * (Codex, PR #89). Says "not required" instead whenever
 * [required] is false, from the flavor-specific
 * `locationTrackingNeedsBackgroundPermission` — a compile-time constant,
 * never itself a failed runtime check, so it stays non-nullable.
 */
private fun backgroundLocationLabel(granted: Boolean?, required: Boolean): String =
    if (!required) "not required for this build" else grantLabel(granted)

/**
 * A coarse-only foreground grant (`ACCESS_COARSE_LOCATION` held,
 * `ACCESS_FINE_LOCATION` denied) reads as a plain "denied" through
 * [grantLabel] alone — indistinguishable from no grant at all, even though
 * the presence engine treats it as a real, if fatal, capability loss
 * (`LocationPermission`'s own KDoc, Codex PR #79) rather than a simple
 * missing permission (Codex, PR #89). This says which of the three it is —
 * and if either underlying check itself failed, says so rather than
 * reading as a confirmed "denied" (Codex, PR #89, fresh evidence).
 *
 * A confirmed coarse grant alongside a *failed* fine check is its own case,
 * not folded into "coarse only": that label asserts fine is confirmed
 * absent, which a failed check never confirmed — fine could genuinely be
 * granted too (Codex, PR #89, fresh evidence).
 */
private fun foregroundLocationLabel(fineGranted: Boolean?, coarseGranted: Boolean?): String = when {
    fineGranted == true -> "granted (fine)"
    fineGranted == null && coarseGranted == true -> "granted (coarse); fine check failed"
    coarseGranted == true -> "granted (coarse only)"
    fineGranted == null || coarseGranted == null -> "unknown"
    else -> "denied"
}

/**
 * The newest lines of [lines] (oldest-first) whose combined length fits
 * [budgetChars], returned oldest-first; at least the single newest line is
 * kept even if it alone exceeds the budget (entries are already per-entry
 * capped by `SnoozeDebugLog`).
 */
internal fun boundedLogTail(lines: List<String>, budgetChars: Int): List<String> {
    val kept = ArrayDeque<String>()
    var used = 0
    for (line in lines.asReversed()) {
        val cost = line.length + 1 // + the newline appendLine adds
        if (used + cost > budgetChars && kept.isNotEmpty()) break
        kept.addFirst(line)
        used += cost
    }
    return kept
}

/**
 * The room this app's section has: the report's ceiling, androidlog's own
 * (`docs/DEBUG.md`), less the earlier runs' share, which the library appends.
 * The library sets the ceiling so a report survives being pasted whole into a
 * chat, a message or an email.
 */
internal const val APP_SECTION_CHARS = SharedReport.APP_SECTION_CHARS

/** The heading the earlier runs go under, matching this report's own. */
internal const val EARLIER_RUNS_HEADING = "--- Earlier runs (newest last) ---"

/** Ceiling for the structured build/device/state header. */
private const val MAX_STRUCTURED_CHARS = 4_000

/** Room kept for the recent log's own heading line, which counts its lines. */
private const val LOG_HEADING_CHARS = 200

/** The log's budget in the fallback report, which has only a few lines besides. */
private const val FALLBACK_LOG_CHARS = APP_SECTION_CHARS - 1_000

/**
 * How long [DebugReport.share] waits for the main thread to deliver. The main
 * thread is the app's own UI thread and runs a post at once; this bounds only
 * a process that is going away.
 */
private const val MAIN_THREAD_TIMEOUT_SECONDS = 30L
