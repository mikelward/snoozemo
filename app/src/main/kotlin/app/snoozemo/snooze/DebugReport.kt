package app.snoozemo.snooze

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.snoozemo.R
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.tile.TilePresenceStore
import app.snoozemo.ui.locationTrackingNeedsBackgroundPermission
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "DebugReport"

/**
 * Builds the SPEC.md §4.6 share payload and hands it to the system share
 * sheet, with a copy-to-clipboard fallback (`TODO.md` Phase 5, `docs/DEBUG.md`).
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

    /** What a share attempt actually achieved. */
    data class Result(
        /** Whether the clipboard copy landed — the durable, retryable proof of delivery. */
        val clipboardCopied: Boolean,
        /** Whether *either* route reached the user — what gates the failure message. */
        val reachedUser: Boolean,
    )

    /**
     * What [collectPayload] (or a test's own seam) produced.
     *
     * [pinConsumeSafe] is false whenever the text might not actually carry a
     * pinned crash that exists — the previous-run read timed out, or the
     * fallback path ran — so [share] must not treat a landed copy as proof
     * the crash was shared (Codex, PR #89): consuming the pin on a report
     * that silently omitted the crash would lose the only evidence a banner
     * exists to protect, for a share that never actually carried it.
     */
    data class Payload(val text: String, val pinConsumeSafe: Boolean)

    /** How long [share] waits for [consumeCrashPin] before giving up on it for this attempt. */
    private const val CONSUME_PIN_TIMEOUT_MS = 2_000L

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
     * Serializes the actual delivery (clipboard write, chooser launch, pin
     * consume) across concurrently-running [share] calls — separate from
     * [applyLock], which stays fast and non-blocking since [nextAttempt] is
     * called synchronously on the tap thread and must never wait on a
     * background attempt's binder/IPC work (Codex, PR #89, fourth round on
     * this mechanism: the ticket only ever guarded [lastShareFailed], never
     * these side effects, so two taps close together could still both write
     * the clipboard and open a chooser, unordered).
     */
    private val deliveryLock = Any()

    /**
     * How many deliveries have actually completed so far — a monotonic
     * counter bumped once per real clipboard-write/chooser-launch attempt,
     * touched only from inside [deliveryLock]. Snapshotted at each ticket's
     * issuance (see [deliveriesAtIssue]), and compared in [share] against
     * the current value to detect whether *another* attempt delivered while
     * this one was still collecting its payload or waiting on
     * [deliveryLock]. The ticket check above only catches an
     * attempt superseded *before* it starts delivering; it can't catch a
     * tap that lands while an earlier, still-current attempt is already
     * mid-delivery — that earlier attempt can't be interrupted once its
     * clipboard write or chooser launch has started, so without this check
     * the later tap would still go on to open a second chooser and copy a
     * second time once it finally reached the front of the queue (Codex,
     * PR #89, nineteenth round on this mechanism).
     */
    @Volatile
    private var deliveriesCompleted: Int = 0

    /**
     * The most recently completed delivery's own outcome. An attempt that
     * finds [deliveriesCompleted] moved since it started reuses this
     * instead of delivering again, so its own outcome-application below
     * reports what genuinely reached the user rather than fabricating a
     * false failure for a tap that was really just a duplicate of a
     * delivery that had already happened.
     */
    @Volatile
    private var lastDeliveryResult: Result = Result(clipboardCopied = false, reachedUser = false)

    /**
     * Whether [lastDeliveryResult]'s own payload safely included a pinned
     * crash — [Payload.pinConsumeSafe] as of that delivery, independent of
     * which channel actually reached the user. A delivery that reached the
     * user without this being true is *incomplete*, not merely successful:
     * the previous-run read that produces the crash text may have timed out
     * or failed, so what actually landed on the clipboard or in the chooser
     * might not carry the crash at all. Reuse must not treat that as
     * equivalent to a delivery that genuinely carried it (Codex, PR #89,
     * fresh evidence) — a retry tapped mid-delivery deserves its own real
     * attempt at including the crash, not a report that the first,
     * incomplete one already "reached" someone.
     */
    @Volatile
    private var lastDeliveryPinConsumeSafe: Boolean = false

    /**
     * [deliveriesCompleted]'s value at the moment each ticket was issued,
     * keyed by the ticket — read (and removed) by [share] instead of
     * re-sampling [deliveriesCompleted] itself on its own background thread.
     * A retry tapped while an earlier attempt is delivering, but whose
     * background thread isn't actually scheduled until after that delivery
     * finishes, would otherwise sample [deliveriesCompleted] *after* it had
     * already been bumped — missing the overlap the tap genuinely occurred
     * during, and delivering a second time regardless (Codex, PR #89,
     * twenty-second round on this mechanism). [nextAttempt] captures this
     * synchronously on the tap thread, where thread-scheduling delay can't
     * reach it.
     */
    private val deliveriesAtIssue = java.util.concurrent.ConcurrentHashMap<Int, Int>()

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
     */
    fun nextAttempt(): Int = synchronized(applyLock) {
        lastShareFailed = false
        val ticket = ++attemptTicket
        deliveriesAtIssue[ticket] = deliveriesCompleted
        ticket
    }

    /** Test-only: resets the process-level outcome state so tests don't leak into each other. */
    internal fun resetForTest() {
        lastShareFailed = false
        onShareOutcome = null
        synchronized(applyLock) { attemptTicket = 0 }
        deliveriesCompleted = 0
        lastDeliveryResult = Result(clipboardCopied = false, reachedUser = false)
        lastDeliveryPinConsumeSafe = false
        deliveriesAtIssue.clear()
    }

    /** Mirrors [DebugLogging.watchSaveOutcome]; see [lastShareFailed]. */
    fun watchShareOutcome(onChange: () -> Unit): AutoCloseable {
        onShareOutcome = onChange
        return AutoCloseable { if (onShareOutcome === onChange) onShareOutcome = null }
    }

    /**
     * Builds the payload, copies it to the clipboard, and fires the share
     * chooser. Both routes are attempted independently and best-effort — the
     * share sheet has no delivery callback, so [Result.clipboardCopied] is
     * the only outcome this can safely gate anything on.
     *
     * On a landed clipboard copy, also consumes the crash pin if one was
     * pinned — SPEC.md §4.6, "sharing consumes the pin" — never on the
     * chooser merely opening, which is not proof the user completed
     * anything, and never when [Payload.pinConsumeSafe] is false, which
     * means the text this landed copy carries might not actually include a
     * pinned crash that exists (Codex, PR #89). A share whose clipboard
     * copy failed leaves the pin in place for a retry, so the evidence a
     * crash banner exists for is never lost to a share that didn't actually
     * land. The pin's own consumer already
     * notifies its own watch ([DebugLogging.watchCrashPinOutcome]) with the
     * *real* outcome, so a caller must not infer the pin is gone from
     * [Result.clipboardCopied] alone — a landed copy only means consuming was
     * *attempted*, not that the file layer actually let it happen.
     *
     * [payloadCollect], [clipboardWrite], [chooserLaunch], and
     * [consumeCrashPin] are injectable test seams; production uses the
     * defaults.
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
        payloadCollect: (Context) -> Payload = ::collectPayload,
        clipboardWrite: (Context, String) -> Boolean = ::copyToClipboard,
        chooserLaunch: (Context, String) -> Boolean = ::startShare,
        consumeCrashPin: (onResult: (Boolean) -> Unit) -> Unit = DebugLogging::consumeCrashPin,
    ): Result {
        // Looked up from what nextAttempt() captured on the tap thread,
        // not re-sampled here: this call runs on a caller-provided
        // background thread whose actual scheduling can lag the tap by an
        // unpredictable amount, and deliveriesCompleted can move in that
        // gap (Codex, PR #89, twenty-second round). Falls back to a fresh
        // read only for the untracked default attempt (0), which never
        // went through nextAttempt() and has no concurrent attempt to
        // guard against anyway.
        val deliveriesAtEntry = deliveriesAtIssue.remove(attempt) ?: deliveriesCompleted
        val payload = runCatching { payloadCollect(context) }
            .onFailure { Log.e(TAG, "Building the debug report failed; sharing a minimal fallback.", it) }
            .getOrElse { Payload(fallbackPayload(it), pinConsumeSafe = false) }
        val text = payload.text
        // Delivery itself is serialized on deliveryLock, not just the
        // outcome it produces: two taps close together previously started
        // two independent threads that both wrote the clipboard and opened
        // a chooser, unordered, so a slower first attempt could overwrite
        // a retry's report on the clipboard or open a second chooser after
        // the user had already seen the retry resolve (Codex, PR #89,
        // fourth round on this mechanism). The ticket is re-checked here,
        // against a fresh snapshot, rather than only at entry — entry can
        // pass before a concurrent tap issues a newer ticket while this
        // attempt is still mid-flight (collecting its payload, above, or
        // waiting for this very lock); an attempt found stale only once it
        // finally reaches the front of the queue skips delivery entirely
        // rather than clobbering what a newer attempt already delivered.
        val result = synchronized(deliveryLock) {
            if (attempt < synchronized(applyLock) { attemptTicket }) {
                return@synchronized Result(clipboardCopied = false, reachedUser = false) to false
            }
            if (
                deliveriesCompleted != deliveriesAtEntry &&
                lastDeliveryResult.reachedUser &&
                lastDeliveryPinConsumeSafe
            ) {
                // Another attempt delivered — successfully, and completely
                // — while this one was collecting its payload or waiting on
                // this very lock; reuse that outcome rather than writing the
                // clipboard or opening the chooser a second time for what
                // already reached the user (Codex, PR #89). A *failed*
                // overlapping delivery is deliberately not reused here:
                // nothing has reached the user yet, so falling through lets
                // the latest attempt make a genuine retry instead of just
                // re-reporting the same failure it never actually attempted
                // itself (Codex, PR #89, twenty-first round on this
                // mechanism). Neither is a delivery that reached the user
                // without safely including a pinned crash — reusing it would
                // silently swallow a retry's own chance to include the crash
                // this attempt's own payload collection might actually
                // capture (Codex, PR #89, fresh evidence).
                // Still apply the reused outcome as this attempt's own: the
                // earlier attempt's ticket is stale by now, so its own
                // outcome-application below never runs — without doing it
                // here too, a reused outcome would never reach
                // lastShareFailed or onShareOutcome at all (Codex, PR #89,
                // twentieth round).
                synchronized(applyLock) {
                    if (attempt >= attemptTicket) {
                        lastShareFailed = false
                        runCatching { onShareOutcome?.invoke() }
                    }
                }
                return@synchronized lastDeliveryResult to false
            }
            val copied = runCatching { clipboardWrite(context, text) }
                .onFailure { Log.e(TAG, "Copying the debug report to the clipboard failed.", it) }
                .getOrDefault(false)
            val launched = runCatching { chooserLaunch(context, text) }
                .onFailure { Log.e(TAG, "Launching the share chooser failed.", it) }
                .getOrDefault(false)
            if (!copied && !launched) {
                Log.e(TAG, "Sharing the debug report reached neither the clipboard nor the chooser.")
            }
            val delivered = Result(clipboardCopied = copied, reachedUser = copied || launched)
            // Recorded — and deliveryLock released, below — before the pin
            // consume even starts, not after it finishes: that wait can run
            // up to CONSUME_PIN_TIMEOUT_MS, but the clipboard/chooser
            // delivery this attempt performs is already fully done by this
            // point. A concurrent attempt tapped while only that cleanup
            // step is still outstanding previously blocked on deliveryLock
            // for the same duration before it could even see this outcome
            // waiting for it — reading as the retry silently doing nothing
            // for however long the cleanup took (Codex, PR #89, twenty-
            // seventh round on this mechanism).
            // Both writes go under applyLock as well as deliveryLock:
            // nextAttempt() reads deliveriesCompleted under applyLock, not
            // deliveryLock (deliberately — it must never block a tap on an
            // in-progress delivery), so without a shared lock that read has
            // no happens-before relationship with this write and could
            // observe the counter bumped before lastDeliveryResult is,
            // taking an inconsistent baseline (Codex, PR #89, thirty-first
            // round). Cheap: applyLock's critical sections are already brief
            // field writes, and the lock is reentrant, so nesting it here
            // costs nothing extra.
            synchronized(applyLock) {
                deliveriesCompleted++
                lastDeliveryResult = delivered
                lastDeliveryPinConsumeSafe = payload.pinConsumeSafe
            }
            // Only the single most-recently-issued attempt may update the
            // visible state — an attempt superseded by a later tap discards
            // its own outcome here regardless of whether that later tap has
            // completed yet, so a stale failure (or success) is never shown
            // over a retry the user is still waiting on (Codex, PR #89,
            // third round on this narrower point). applyLock's own critical
            // section here is brief — a field compare, assign, and a
            // callback invocation — so this never holds up nextAttempt.
            synchronized(applyLock) {
                if (attempt >= attemptTicket) {
                    lastShareFailed = !delivered.reachedUser
                    runCatching { onShareOutcome?.invoke() }
                }
            }
            delivered to (copied && payload.pinConsumeSafe)
        }
        val (delivered, shouldConsumePin) = result
        if (shouldConsumePin) {
            val latch = CountDownLatch(1)
            runCatching { consumeCrashPin { latch.countDown() } }
                .onFailure { Log.e(TAG, "Consuming the crash pin failed.", it) }
            if (!latch.await(CONSUME_PIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Consuming the crash pin timed out.")
            }
        }
        return delivered
    }

    /** How long [collectPayload] waits on the debug-log worker for the previous run's file. */
    private const val READ_TIMEOUT_MS = 2_000L

    private fun collectPayload(context: Context): Payload {
        val previousRunRead = blockingReadPreviousOrCrash()
        val previousRunOmitted = previousRunRead.omitted
        val text = buildDebugReportPayload(
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
            previousRun = previousRunRead.text,
            previousRunCrashed = previousRunRead.wasCrash,
            previousRunOmitted = previousRunOmitted,
            recentLog = SnoozeDebugLog.snapshot(),
        )
        // Only safe when the read actually completed, actually succeeded,
        // *and* the crash it was reading actually has content: a timeout
        // can't tell "nothing was pinned" from "something was pinned and we
        // didn't wait long enough to find out", a thrown `readText()` can't
        // tell it from "the crash file couldn't be read" either (Codex,
        // PR #89, two rounds), and a pinned crash whose file exists but
        // reads back blank — process death mid-write, between the marker
        // landing and the content itself — can't tell it from a genuinely
        // empty previous run (Codex, PR #89, third round). Confusing any of
        // these with a clean, complete report is exactly what would consume
        // a pin the shared text never actually carried.
        return Payload(text, pinConsumeSafe = !previousRunOmitted)
    }

    /** Whether [collectPayload]'s read couldn't confirm the pin's content actually reached the report. */
    private val PreviousRunRead.omitted: Boolean
        get() = timedOut || !readSucceeded || (wasCrash && text.isNullOrBlank())

    /**
     * A minimal payload for when [collectPayload] itself throws — the one
     * section that cannot fail to build (it touches nothing but [Build] and
     * the in-memory buffer) is still worth sending, rather than nothing at
     * all. Only the failure's type name is recorded, never its message — the
     * paths this can run from include the ones the privacy floor is about.
     */
    private fun fallbackPayload(failure: Throwable): String = buildString {
        appendLine("Snoozemo debug log")
        appendLine("Report collection failed: ${failure.javaClass.name}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append(renderRecentLog(SnoozeDebugLog.snapshot(), MAX_LOG_PAYLOAD_CHARS))
    }

    /**
     * What [blockingReadPreviousOrCrash] found: whether the read completed
     * in time, and — if it did — whether it actually succeeded (as against
     * a real, still-pinned file that couldn't be read).
     */
    private data class PreviousRunRead(
        val text: String?,
        val wasCrash: Boolean,
        val timedOut: Boolean,
        val readSucceeded: Boolean,
    )

    /**
     * Bridges [DebugLogging.readPreviousOrCrash]'s callback so [collectPayload]
     * can read it inline — safe to block on here because this already runs on
     * its own caller-provided thread, never the FIFO worker it is waiting on.
     * Bounded so a stuck worker degrades the report rather than hanging the
     * share indefinitely; [PreviousRunRead.timedOut] and
     * [PreviousRunRead.readSucceeded] are what tell the caller a timeout or a
     * read failure happened, since neither must be reported the same way as
     * a genuinely empty read (SPEC.md §4.6; Codex, PR #89, two rounds).
     */
    private fun blockingReadPreviousOrCrash(): PreviousRunRead {
        val latch = CountDownLatch(1)
        var result = Triple<String?, Boolean, Boolean>(null, false, true)
        DebugLogging.readPreviousOrCrash { text, wasCrash, readSucceeded ->
            result = Triple(text, wasCrash, readSucceeded)
            latch.countDown()
        }
        val completed = latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            Log.w(TAG, "Reading the previous run's debug log timed out; reporting without it.")
        }
        return PreviousRunRead(result.first, result.second, timedOut = !completed, readSucceeded = result.third)
    }

    private fun isPolicyAccessGranted(context: Context): Boolean =
        runCatching { AndroidZenController.default(context).policyAccess() == PolicyAccess.GRANTED }
            .onFailure { Log.w(TAG, "DebugReport could not read policy access.", it) }
            .getOrDefault(false)

    private fun isPermissionGranted(context: Context, permission: String): Boolean =
        runCatching {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }.onFailure { Log.w(TAG, "DebugReport permission check failed: $permission", it) }.getOrDefault(false)

    private fun isLocationServicesEnabled(context: Context): Boolean =
        runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true }
            .onFailure { Log.w(TAG, "DebugReport could not read location-services state.", it) }
            .getOrDefault(false)

    private fun isBatterySaverOn(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }
            .onFailure { Log.w(TAG, "DebugReport could not read battery-saver state.", it) }
            .getOrDefault(false)

    private fun isTileAdded(context: Context): Boolean =
        runCatching { TilePresenceStore(context).isAdded() }
            .onFailure { Log.w(TAG, "DebugReport could not read the tile's presence.", it) }
            .getOrDefault(false)

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun appVersionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(-1L)

    /** Returns whether the share chooser actually launched. */
    private fun startShare(context: Context, text: String): Boolean =
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Snoozemo debug log — ${appVersionName(context)}")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.setup_debug_log_share_title))
            // Callers may pass a non-Activity context (applicationContext);
            // harmless when the caller is one too.
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }.isSuccess

    /** Returns whether the report actually landed on the clipboard. */
    private fun copyToClipboard(context: Context, text: String): Boolean =
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            if (cm == null) {
                false
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("Snoozemo debug log", text))
                true
            }
        }.getOrDefault(false)
}

/**
 * The pure payload builder — no Android dependencies beyond [Build]/[Locale],
 * so its shape is unit-testable without Robolectric.
 */
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
    policyAccessGranted: Boolean,
    notificationsGranted: Boolean,
    locationFineGranted: Boolean,
    locationCoarseGranted: Boolean,
    locationBackgroundGranted: Boolean,
    locationBackgroundRequired: Boolean,
    locationServicesEnabled: Boolean,
    batterySaverOn: Boolean,
    tileAdded: Boolean,
    previousRun: String?,
    previousRunCrashed: Boolean,
    previousRunOmitted: Boolean = false,
    recentLog: List<String>,
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
        appendLine("Location services on: $locationServicesEnabled")
        appendLine("Battery saver on: $batterySaverOn")
        appendLine("Quick Settings tile added: $tileAdded")
    }
    val boundedHead = if (head.length > MAX_STRUCTURED_CHARS) {
        head.take(MAX_STRUCTURED_CHARS) + "\n…(details truncated to keep the report shareable)\n"
    } else {
        head
    }
    val previousSection = if (previousRun.isNullOrBlank()) {
        // A blank previousRun is ambiguous on its own — genuinely nothing to
        // report, or a read that timed out, failed, or (for a pinned crash
        // specifically) came back empty and silently dropped whatever it
        // would have shown. Say so explicitly rather than rendering the
        // same as "nothing to show", since a share that landed while
        // quietly omitting the one thing the crash banner exists to
        // deliver is otherwise indistinguishable from a clean one (Codex,
        // PR #89, two rounds — the wording deliberately names no specific
        // cause, since a timeout and a genuinely blank pinned crash file
        // land here the same way).
        if (previousRunOmitted) {
            "\n--- Previous run ---\n(could not be included in this report — try Share again)\n"
        } else {
            ""
        }
    } else {
        buildString {
            appendLine()
            val label = if (previousRunCrashed) {
                "--- Previous run (ended in an uncaught exception) ---"
            } else {
                "--- Previous run ---"
            }
            appendLine(label)
            // Keep the newest lines: the file is oldest-first, so a crash
            // entry or the last decisions are at the end.
            appendLine(
                boundedLogTail(previousRun.trimEnd().split("\n"), MAX_PREVIOUS_RUN_CHARS).joinToString("\n"),
            )
        }
    }
    return boundedHead + previousSection + renderRecentLog(recentLog, MAX_LOG_PAYLOAD_CHARS)
}

/** The "Recent log" section — the in-memory ring buffer, newest last, bounded to [budgetChars]. */
private fun renderRecentLog(recentLog: List<String>, budgetChars: Int): String = buildString {
    appendLine()
    val kept = boundedLogTail(recentLog, budgetChars)
    val dropped = recentLog.size - kept.size
    appendLine("--- Recent log (newest last, ${kept.size} of ${recentLog.size} shown) ---")
    if (recentLog.isEmpty()) {
        appendLine("(no captured log lines — has a snooze run since the app started?)")
    } else {
        if (dropped > 0) appendLine("($dropped older line(s) omitted to keep the report shareable)")
        kept.forEach { appendLine(it) }
    }
}

private fun grantLabel(granted: Boolean): String = if (granted) "granted" else "denied"

/**
 * The `direct` flavor never declares `ACCESS_BACKGROUND_LOCATION`
 * (`AndroidManifest.xml`, `SPEC.md` §3.4) and its foreground-service
 * tracking never needs the grant, so [grantLabel] alone would always read
 * "denied" there — a false capability problem for anyone diagnosing a
 * direct build, since nothing in that flavor ever requests or expects it
 * (Codex, PR #89). Says "not required" instead whenever
 * [required] is false, from the flavor-specific
 * `locationTrackingNeedsBackgroundPermission`.
 */
private fun backgroundLocationLabel(granted: Boolean, required: Boolean): String =
    if (!required) "not required for this build" else grantLabel(granted)

/**
 * A coarse-only foreground grant (`ACCESS_COARSE_LOCATION` held,
 * `ACCESS_FINE_LOCATION` denied) reads as a plain "denied" through
 * [grantLabel] alone — indistinguishable from no grant at all, even though
 * the presence engine treats it as a real, if fatal, capability loss
 * (`LocationPermission`'s own KDoc, Codex PR #79) rather than a simple
 * missing permission (Codex, PR #89). This says which of the three it is.
 */
private fun foregroundLocationLabel(fineGranted: Boolean, coarseGranted: Boolean): String = when {
    fineGranted -> "granted (fine)"
    coarseGranted -> "granted (coarse only)"
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
 * The ceiling a whole shared report stays under (`docs/DEBUG.md`). Strings
 * parcel as UTF-16, so N characters cost 2N bytes on the wire, and the
 * payload crosses Binder twice — into the clipboard, then again in the
 * chooser's `ACTION_SEND` extra — against a per-process buffer shared across
 * every in-flight transaction and roughly 1 MB. The three section budgets
 * below (4,000 + 25,000 + 30,000) sum to 59,000, leaving margin under this.
 */
internal const val MAX_SHARE_PAYLOAD_CHARS = 60_000

/** Ceiling for the structured build/device/state header. */
private const val MAX_STRUCTURED_CHARS = 4_000

/** Ceiling for the previous (or crashed) run's section. */
private const val MAX_PREVIOUS_RUN_CHARS = 25_000

/** Ceiling for the current run's recent-log section. */
private const val MAX_LOG_PAYLOAD_CHARS = 30_000
