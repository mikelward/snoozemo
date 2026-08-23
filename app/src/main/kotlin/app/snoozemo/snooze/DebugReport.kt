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
     * anything. A share whose clipboard copy failed leaves the pin in place
     * for a retry, so the evidence a crash banner exists for is never lost
     * to a share that didn't actually land. The pin's own consumer already
     * notifies its own watch ([DebugLogging.watchCrashPinOutcome]) with the
     * *real* outcome, so a caller must not infer the pin is gone from
     * [Result.clipboardCopied] alone — a landed copy only means consuming was
     * *attempted*, not that the file layer actually let it happen.
     *
     * [payloadCollect], [clipboardWrite], [chooserLaunch], and
     * [consumeCrashPin] are injectable test seams; production uses the
     * defaults.
     */
    fun share(
        context: Context,
        payloadCollect: (Context) -> String = ::collectPayload,
        clipboardWrite: (Context, String) -> Boolean = ::copyToClipboard,
        chooserLaunch: (Context, String) -> Boolean = ::startShare,
        consumeCrashPin: (onResult: (Boolean) -> Unit) -> Unit = DebugLogging::consumeCrashPin,
    ): Result {
        val text = runCatching { payloadCollect(context) }
            .onFailure { Log.e(TAG, "Building the debug report failed; sharing a minimal fallback.", it) }
            .getOrElse { fallbackPayload(it) }
        val copied = runCatching { clipboardWrite(context, text) }
            .onFailure { Log.e(TAG, "Copying the debug report to the clipboard failed.", it) }
            .getOrDefault(false)
        val launched = runCatching { chooserLaunch(context, text) }
            .onFailure { Log.e(TAG, "Launching the share chooser failed.", it) }
            .getOrDefault(false)
        if (!copied && !launched) {
            Log.e(TAG, "Sharing the debug report reached neither the clipboard nor the chooser.")
        }
        if (copied) {
            val latch = CountDownLatch(1)
            runCatching { consumeCrashPin { latch.countDown() } }
                .onFailure { Log.e(TAG, "Consuming the crash pin failed.", it) }
            if (!latch.await(CONSUME_PIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Consuming the crash pin timed out.")
            }
        }
        val result = Result(clipboardCopied = copied, reachedUser = copied || launched)
        lastShareFailed = !result.reachedUser
        runCatching { onShareOutcome?.invoke() }
        return result
    }

    /** How long [collectPayload] waits on the debug-log worker for the previous run's file. */
    private const val READ_TIMEOUT_MS = 2_000L

    private fun collectPayload(context: Context): String {
        val (previousRun, previousRunCrashed) = blockingReadPreviousOrCrash()
        return buildDebugReportPayload(
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
            locationForegroundGranted = isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION),
            locationBackgroundGranted = isPermissionGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            locationServicesEnabled = isLocationServicesEnabled(context),
            batterySaverOn = isBatterySaverOn(context),
            tileAdded = isTileAdded(context),
            previousRun = previousRun,
            previousRunCrashed = previousRunCrashed,
            recentLog = SnoozeDebugLog.snapshot(),
        )
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
     * Bridges [DebugLogging.readPreviousOrCrash]'s callback so [collectPayload]
     * can read it inline — safe to block on here because this already runs on
     * its own caller-provided thread, never the FIFO worker it is waiting on.
     * Bounded so a stuck worker degrades the report rather than hanging the
     * share indefinitely.
     */
    private fun blockingReadPreviousOrCrash(): Pair<String?, Boolean> {
        val latch = CountDownLatch(1)
        var result: Pair<String?, Boolean> = null to false
        DebugLogging.readPreviousOrCrash { text, wasCrash ->
            result = text to wasCrash
            latch.countDown()
        }
        if (!latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Reading the previous run's debug log timed out; reporting without it.")
        }
        return result
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
    locationForegroundGranted: Boolean,
    locationBackgroundGranted: Boolean,
    locationServicesEnabled: Boolean,
    batterySaverOn: Boolean,
    tileAdded: Boolean,
    previousRun: String?,
    previousRunCrashed: Boolean,
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
        appendLine("Location (foreground): ${grantLabel(locationForegroundGranted)}")
        appendLine("Location (background): ${grantLabel(locationBackgroundGranted)}")
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
        ""
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
