package app.snoozemo.crash

import android.content.Context
import com.google.firebase.FirebaseApp
import app.snoozemo.core.SnoozeDebugLog
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * The `play` flavor's crash reporter: Crashlytics, behind the user's opt-out
 * (`SPEC.md` §12). `direct`'s own copy of this file is a no-op — that flavor
 * carries no Play Services dependency and no `INTERNET` permission at all
 * (`SPEC.md` §3.4), which is the flavor's reason to exist.
 *
 * Nothing here is on the arm path. Firebase initializes from its own
 * `ContentProvider` during process creation, ahead of `Application.onCreate`,
 * and every call below is made from `CrashReporting`'s worker thread — never
 * between a tile tap and the zen rule going `STATE_TRUE` (`SPEC.md` §4.1).
 *
 * What it sends is a stack trace, the device model, and the app version.
 * §12's floor is untouched: no coordinates, no SSID or BSSID, no user-typed
 * place name is ever attached — this file adds no custom keys and no
 * breadcrumbs, so there is nothing here that could carry one.
 */
internal object CrashReporter {

    /**
     * Whether this build has a reporter at all.
     *
     * False when the build carried no `google-services.json` (a fresh clone, a
     * fork, CI — see `docs/crashlytics.md`): the SDK is compiled in but never
     * initialized, and [FirebaseCrashlytics.getInstance] would throw without
     * an initialized [FirebaseApp]. Settings offers no switch in that case,
     * because a switch over a reporter that does not exist would be a lie.
     */
    fun isAvailable(context: Context): Boolean =
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()

    /**
     * Applies [enabled] to Crashlytics now. Returns whether the SDK was
     * actually reachable, so the caller can say so rather than assume.
     *
     * On a disable this also drops reports already captured on disk.
     * Crashlytics only honors the collection switch from the *next* launch, so
     * without the delete an opt-out would leave whatever the current session
     * captured waiting to upload — and since the stored `false` is re-applied
     * on every later launch, that delete also catches a crash captured in the
     * tail of the opt-out session before any re-enable could send it.
     */
    fun apply(context: Context, enabled: Boolean): ReporterOutcome {
        if (!isAvailable(context)) return ReporterOutcome.NO_REPORTER
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
        if (enabled) return ReporterOutcome.APPLIED
        // Make the opt-out *durable* before returning, not merely called. See
        // [flushCollectionOverride] — the caller commits this app's own
        // preference synchronously right after, and the pair must never come
        // to rest with ours saying off and the SDK's saying on. When the flush
        // cannot promise that, the caller is told so rather than left to
        // assume it (Codex, PR #113).
        val durable = flushCollectionOverride(context)
        crashlytics.deleteUnsentReports()
        return if (durable) ReporterOutcome.APPLIED else ReporterOutcome.NOT_DURABLE
    }

    /**
     * Blocks until Crashlytics' own collection override has actually reached
     * disk.
     *
     * `setCrashlyticsCollectionEnabled` persists through `Editor.apply()`, so
     * it returns before the value is durable — verified against the SDK
     * artifact rather than assumed (`DataCollectionArbiter
     * .storeDataCollectionValueInSharedPreferences` ends in `apply()`). This
     * app's own store commits synchronously. Without a flush between them a
     * process death can leave the pair in the one state that breaks the
     * promise in Settings: our preference durably off while the SDK's override
     * is durably on, so the next launch initializes Crashlytics *collecting*
     * and can upload a queued report before `CrashReporting.install` corrects
     * it (Codex, PR #113, second pass — call ordering alone does not
     * establish durable-write order, which was right).
     *
     * A no-op `commit()` on the same preferences file is what waits: writes to
     * one file are serialized, so blocking on ours implies the SDK's earlier
     * `apply()` has landed. `getSharedPreferences` hands back the
     * process-wide instance for a name, so this is the same file the SDK
     * wrote — not a second copy of it.
     *
     * Both names below are Crashlytics internals. If a future SDK renames
     * either, this would flush a file nobody reads and quietly stop working,
     * so it checks that the key it expects is actually there and says so when
     * it is not — a degraded opt-out that announces itself, rather than one
     * that looks fine.
     */
    private fun flushCollectionOverride(context: Context): Boolean =
        runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(CRASHLYTICS_PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(COLLECTION_ENABLED_KEY)) {
                SnoozeDebugLog.warning(
                    "crash reporting: the SDK's collection override was not where this build " +
                        "expects it, so the opt-out could not be forced to disk",
                )
                return@runCatching false
            }
            // No-op edit: nothing to change, and the commit is only here for
            // what it waits on. **Its result is the answer** — `commit()`
            // reports a failed write by returning false rather than throwing,
            // so discarding it would report a durable opt-out on a disk write
            // that never landed, which is the one thing this method exists to
            // rule out (Codex, PR #113).
            val flushed = prefs.edit().commit()
            if (!flushed) {
                SnoozeDebugLog.warning("crash reporting: the SDK's opt-out could not be written to disk")
            }
            flushed
        }.getOrElse {
            SnoozeDebugLog.warning("crash reporting: could not flush the SDK's opt-out to disk", it)
            false
        }

    /** Crashlytics' `CommonUtils.SHARED_PREFS_NAME`. */
    private const val CRASHLYTICS_PREFS = "com.google.firebase.crashlytics"

    /** Crashlytics' `DataCollectionArbiter.FIREBASE_CRASHLYTICS_COLLECTION_ENABLED`. */
    private const val COLLECTION_ENABLED_KEY = "firebase_crashlytics_collection_enabled"
}
