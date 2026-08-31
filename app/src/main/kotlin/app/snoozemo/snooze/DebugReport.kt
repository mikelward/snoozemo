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
import com.mikelward.androidlog.android.PreviousRun
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
    data class Payload(
        val text: String,
        val pinConsumeSafe: Boolean,
        /**
         * The files [text] was actually built from, or null when it was built
         * from none — a fallback payload, or a read that found nothing.
         *
         * Carried through rather than remembered anywhere: [share] hands it
         * straight back to [DebugLogging.consumeCrashPin], so a delivered
         * report deletes exactly what it contained. A single remembered slot
         * is what let two overlapping shares have the earlier one destroy a
         * run only the later had read, whose own delivery might still fail.
         *
         * Defaults to null so a caller that has no handle — a fallback
         * payload, a test's own seam — says nothing rather than saying
         * something wrong. Null means "consume nothing", which is the
         * direction that loses no evidence.
         */
        val previousRun: PreviousRun? = null,
    )

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
        consumeCrashPin: (run: PreviousRun?, onResult: (Boolean) -> Unit) -> Unit =
            DebugLogging::consumeCrashPin,
    ): Result {
        val payload = runCatching { payloadCollect(context) }
            .onFailure { Log.e(TAG, "Building the debug report failed; sharing a minimal fallback.", it) }
            .getOrElse { Payload(fallbackPayload(it), pinConsumeSafe = false, previousRun = null) }
        val text = payload.text
        // Delivery is serialized on deliveryLock, not just the outcome it
        // produces: two concurrent calls would otherwise both write the
        // clipboard and open a chooser, unordered, so a slower first one
        // could overwrite a later report on the clipboard or open a second
        // chooser after the user had already seen the later one resolve
        // (Codex, PR #89). Callers gate their Share affordance on
        // [shareInFlight], so in practice a second concurrent call doesn't
        // arise — but gating is a UI contract and this is the floor beneath
        // it, so the guarantee doesn't depend on every caller honoring it.
        //
        // The ticket is re-checked here, once the lock is actually held,
        // rather than only at entry: entry can pass before a concurrent tap
        // issues a newer ticket while this attempt is still mid-flight
        // (collecting its payload, above, or waiting for this very lock).
        // An attempt found stale only once it reaches the front of the
        // queue skips delivery entirely rather than clobbering what a newer
        // attempt already delivered.
        val result = synchronized(deliveryLock) {
            if (attempt < synchronized(applyLock) { attemptTicket }) {
                return@synchronized Result(clipboardCopied = false, reachedUser = false) to false
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
            // Only the single most-recently-issued attempt may update the
            // visible state — an attempt superseded by a later tap discards
            // its own outcome here regardless of whether that later tap has
            // completed yet, so a stale failure (or success) is never shown
            // over a retry the user is still waiting on (Codex, PR #89,
            // third round on this narrower point). applyLock's own critical
            // section here is brief — two field writes and a callback — so
            // this never holds up nextAttempt.
            //
            // shareInFlight clears here, and deliveryLock is released just
            // below, *before* the pin consume rather than after it: that
            // wait runs up to CONSUME_PIN_TIMEOUT_MS, but the delivery the
            // user is actually waiting on is already done by this point, so
            // holding the button disabled through the cleanup would read as
            // the app doing nothing for however long the cleanup took
            // (Codex, PR #89, on the older mechanism, for the same reason).
            // A superseded attempt returns above and so never clears the
            // flag, which correctly belongs to the newer attempt still
            // running.
            synchronized(applyLock) {
                if (attempt >= attemptTicket) {
                    lastShareFailed = !delivered.reachedUser
                    shareInFlight = false
                    runCatching { onShareOutcome?.invoke() }
                }
            }
            delivered to (copied && payload.pinConsumeSafe)
        }
        val (delivered, shouldConsumePin) = result
        if (shouldConsumePin) {
            val latch = CountDownLatch(1)
            runCatching { consumeCrashPin(payload.previousRun) { latch.countDown() } }
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
            previousRunCrashTooLarge =
                previousRunRead.wasCrash && previousRunRead.renderDroppedPartOfPreviousRun,
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
        return Payload(
            text,
            pinConsumeSafe = !previousRunOmitted,
            previousRun = previousRunRead.run,
        )
    }

    /**
     * Whether [collectPayload]'s read couldn't confirm the pin's content actually reached the report.
     *
     * The last clause is about this app's *own* rendering rather than the read:
     * a report keeps only the newest [MAX_PREVIOUS_RUN_CHARS] of the prior runs,
     * and a pinned crash can be an older one of several. Newer ordinary runs can
     * then push it out of the tail entirely — read perfectly, then dropped here —
     * and consuming the pin on that share would lower the banner over a report
     * that never carried the crash (Codex, PR #153). The text is opaque, so the
     * question asked is the one that can be answered: did the render drop *any*
     * of what was read? If it did, and a crash is pinned, the crash may be what
     * went, and the safe direction is to leave the banner up for a later share.
     */
    private val PreviousRunRead.omitted: Boolean
        get() = timedOut || !readSucceeded || (wasCrash && text.isNullOrBlank()) ||
            (wasCrash && renderDroppedPartOfPreviousRun) ||
            (wasCrash && run?.complete == false)

    // The last clause is the library saying its handle does not cover every run
    // still on disk -- one it could not read was skipped and left in place, or
    // the directory would not list at all. The skipped run is in neither the
    // handle's files nor its text, so a handle missing an unreadable crash
    // reads exactly like the ordinary run beside it: text non-blank, nothing
    // truncated, and every other clause here false. Consuming the pin on that
    // report would lower the banner over a share that never carried the crash,
    // while the crash file itself stays on disk with nothing left to offer it
    // (Codex, PR #153). Conservative in the same direction as the truncation
    // clause: a skipped *ordinary* run also refuses, which costs a banner
    // staying up for a later share.

    /** Whether the report's own bound cut anything off what [text] carried. */
    private val PreviousRunRead.renderDroppedPartOfPreviousRun: Boolean
        get() {
            val full = text?.trimEnd() ?: return false
            val rendered = boundedLogTail(full.split("\n"), MAX_PREVIOUS_RUN_CHARS).joinToString("\n")
            return rendered != full
        }

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
        /** The handle, kept so a delivered report can consume exactly these files. */
        val run: PreviousRun?,
        val wasCrash: Boolean,
        val timedOut: Boolean,
        val readSucceeded: Boolean,
    ) {
        val text: String? get() = run?.text
    }

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
        var result = Triple<PreviousRun?, Boolean, Boolean>(null, false, true)
        DebugLogging.readPreviousOrCrash { run, wasCrash, readSucceeded ->
            result = Triple(run, wasCrash, readSucceeded)
            latch.countDown()
        }
        val completed = latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            Log.w(TAG, "Reading the previous run's debug log timed out; reporting without it.")
        }
        return PreviousRunRead(result.first, result.second, timedOut = !completed, readSucceeded = result.third)
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
        }
            // Logged here, not left to share()'s own outer runCatching: this
            // function already catches its own exception and returns false
            // normally, so that outer catch never sees it — with a landed
            // clipboard copy, that left a chooser that silently never opened
            // with no diagnostic explanation anywhere (Codex, PR #89).
            .onFailure { Log.e(TAG, "Launching the share chooser failed.", it) }
            .isSuccess

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
        }
            // Same reason as startShare's own logging just above: this
            // function's own runCatching already stops the exception from
            // reaching share()'s outer one.
            .onFailure { Log.e(TAG, "Copying the debug report to the clipboard failed.", it) }
            .getOrDefault(false)
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
    policyAccessGranted: Boolean?,
    notificationsGranted: Boolean?,
    locationFineGranted: Boolean?,
    locationCoarseGranted: Boolean?,
    locationBackgroundGranted: Boolean?,
    locationBackgroundRequired: Boolean,
    locationServicesEnabled: Boolean?,
    batterySaverOn: Boolean?,
    tileAdded: Boolean?,
    previousRun: String?,
    previousRunCrashed: Boolean,
    previousRunOmitted: Boolean = false,
    /**
     * Whether a pinned crash was among what this report's own bound cut off.
     *
     * Says so in the section rather than leaving the reader to wonder, and
     * points at the way out: sharing cannot clear the banner in this state —
     * the same runs truncate the same way every retry — so Dismiss is the only
     * route (maintainer, 2026-08-31; `TODO.md` carries the proper fix).
     */
    previousRunCrashTooLarge: Boolean = false,
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
        appendLine("Location services on: ${boolLabel(locationServicesEnabled)}")
        appendLine("Battery saver on: ${boolLabel(batterySaverOn)}")
        appendLine("Quick Settings tile added: ${boolLabel(tileAdded)}")
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
            "\n--- Earlier runs ---\n(could not be included in this report — try Share again)\n"
        } else {
            ""
        }
    } else {
        buildString {
            appendLine()
            // Plural, and the crash named as *one of* them rather than as what
            // the whole section is. The library hands over one text covering
            // every unshared prior run with no marker saying where each begins,
            // and the crash flag is the global banner state -- so labeling the
            // aggregate "Previous run (ended in an uncaught exception)" told a
            // reader that ordinary restarts were the crash (Codex, PR #153).
            // Naming which one crashed needs per-run metadata the handle does
            // not carry; saying it truthfully does not.
            val label = if (previousRunCrashed) {
                "--- Earlier runs (one ended in an uncaught exception) ---"
            } else {
                "--- Earlier runs ---"
            }
            appendLine(label)
            // Before the text, not after: the reader needs to know what is
            // missing before they read what is there, and a line at the end of
            // 25,000 characters is a line nobody reaches.
            if (previousRunCrashTooLarge) {
                appendLine("(crash details too large to include - dismiss the banner to clear)")
            }
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

private fun grantLabel(granted: Boolean?): String = when (granted) {
    true -> "granted"
    false -> "denied"
    null -> "unknown"
}

/** See the helper functions in [DebugReport] this renders: null means the check itself failed. */
private fun boolLabel(value: Boolean?): String = value?.toString() ?: "unknown"

/**
 * The `direct` flavor never declares `ACCESS_BACKGROUND_LOCATION`
 * (`AndroidManifest.xml`, `SPEC.md` §3.4) and its foreground-service
 * tracking never needs the grant, so [grantLabel] alone would always read
 * "denied" there — a false capability problem for anyone diagnosing a
 * direct build, since nothing in that flavor ever requests or expects it
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
