package app.snoozemo.crash

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import app.snoozemo.core.SnoozeDebugLog
import com.google.firebase.analytics.FirebaseAnalytics
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
 * What it sends is a stack trace, the device model, and the app version,
 * plus Analytics' own automatically-collected events — and, because those two
 * SDKs are in one build, a **breadcrumb trail** on each crash report:
 * Crashlytics picks Analytics' events up automatically, which is Firebase's
 * behavior rather than a call this file makes (Codex, PR #166; accepted by the
 * maintainer, 2026-08-31).
 *
 * §12's floor still holds — no coordinate, no SSID or BSSID, no user-typed
 * place name reaches any of it — but on a different footing than before. This
 * file adds no custom keys and the app logs no custom events, so what rides
 * the trail is the SDK's automatic events and nothing of the user's. The
 * guarantee is therefore *nothing that goes down the channel is forbidden*,
 * not *there is no channel*: a custom Analytics event added later would land
 * in crash reports too, and has to be checked against the floor when it is
 * written rather than assumed safe because this file attaches nothing.
 *
 * **One switch governs both**, because the user was asked one question. The
 * consent card offers crash reports and anonymous analytics together, so
 * they turn on and off together; a build where one could be live while the
 * other was not would answer a question nobody was asked.
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
        return decide(
            enabled = enabled,
            setCrashlyticsEnabled = crashlytics::setCrashlyticsCollectionEnabled,
            setAnalyticsEnabled = { applyAnalytics(context, it) },
            flushOverride = { flushCollectionOverride(context) },
            deleteUnsentReports = crashlytics::deleteUnsentReports,
        )
    }

    /**
     * [apply] with the four SDK effects lifted out as parameters, so the
     * decisions between them can be tested without an initialized
     * [FirebaseApp] (Codex, PR #166).
     *
     * The coupling this exists to guard is new and easy to break silently:
     * that **both** SDKs are told the same `enabled` value, and that an
     * opt-out Analytics refused comes back as [ReporterOutcome.NOT_DURABLE]
     * rather than [ReporterOutcome.APPLIED]. Neither is reachable from
     * `CrashReporterTest`'s dormant-Firebase case — `apply` returns
     * `NO_REPORTER` before any of this runs — and Robolectric never fires
     * `FirebaseInitProvider`, so there is no cheap way to reach it through
     * the real SDKs. Naming the effects is what makes them assertable, and
     * the alternative (assert the outcome only) would have missed a
     * regression that stopped forwarding to Analytics entirely, since the
     * outcome on the enable path does not depend on it.
     */
    internal fun decide(
        enabled: Boolean,
        setCrashlyticsEnabled: (Boolean) -> Unit,
        setAnalyticsEnabled: (Boolean) -> Boolean,
        flushOverride: () -> Boolean,
        deleteUnsentReports: () -> Unit,
    ): ReporterOutcome {
        setCrashlyticsEnabled(enabled)
        // Applied alongside, never separately — see the note on one switch
        // above. Analytics honors this immediately and persists it itself, so
        // unlike Crashlytics there is no queued-report problem to flush or
        // delete; the collection switch is the whole mechanism.
        val analyticsApplied = setAnalyticsEnabled(enabled)
        if (enabled) {
            // Named rather than left to be inferred from the per-SDK failure
            // line above (Codex, PR #166). Deliberately not treated the way a
            // failed *disable* is: a flag stuck off collects less than the
            // user allowed, which harms nobody, while the two available
            // "reconciliations" are both worse than the gap. Refusing the save
            // would discard an answer the user gave and bring the card back
            // because an SDK call threw; holding Crashlytics off because
            // Analytics failed punishes their yes for something unrelated to
            // it. The retry is the stored preference, re-applied at the next
            // launch by the startup gate, which is the same durable path the
            // opt-out direction leans on.
            if (!analyticsApplied) {
                SnoozeDebugLog.warning(
                    "analytics: opt-in incomplete; crash reports on, analytics off until next launch",
                )
            }
            return ReporterOutcome.APPLIED
        }
        // An opt-out that did not reach Analytics is not a finished opt-out
        // (Codex, PR #166). Containing this failure was right while Analytics
        // sat behind its own switch; it stopped being right the moment one
        // answer started governing both, because the card and the Settings
        // row now promise the user that saying no covers analytics too. And
        // Analytics is the half where the promise cannot be kept some other
        // way: it records its own automatic events, so unlike Crashlytics
        // there is no gate of ours left to shut behind a stuck flag.
        if (!analyticsApplied) return ReporterOutcome.NOT_DURABLE
        // Make the opt-out *durable* before returning, not merely called. See
        // [flushCollectionOverride] — the caller commits this app's own
        // preference synchronously right after, and the pair must never come
        // to rest with ours saying off and the SDK's saying on. When the flush
        // cannot promise that, the caller is told so rather than left to
        // assume it (Codex, PR #113).
        val durable = flushOverride()
        deleteUnsentReports()
        return if (durable) ReporterOutcome.APPLIED else ReporterOutcome.NOT_DURABLE
    }

    /**
     * Turns Analytics collection on or off.
     *
     * **Returns whether it took**, and [apply] folds that into its outcome on
     * an opt-out. It used to be contained outright, on the reasoning that
     * Crashlytics is the half with the durability problem and the queued
     * reports — sound while Analytics had a switch of its own, and wrong once
     * one answer governs both: the caller then persists and shows the
     * combined switch as off over an SDK that may still be collecting.
     *
     * Still never reported as `NO_REPORTER`, which would say the crash half
     * had not landed when it had. `NOT_DURABLE` is the honest shape: the
     * choice is recorded, and the caller is told the SDK state does not yet
     * match it.
     */
    private fun applyAnalytics(context: Context, enabled: Boolean): Boolean =
        runCatching {
            FirebaseAnalytics.getInstance(context.applicationContext)
                .setAnalyticsCollectionEnabled(enabled)
            awaitAnalyticsOverride(
                prefs = context.applicationContext
                    .getSharedPreferences(MEASUREMENT_PREFS, Context.MODE_PRIVATE),
                enabled = enabled,
            )
        }.getOrElse {
            SnoozeDebugLog.failure(it, "analytics: could not apply the collection setting")
            false
        }

    /**
     * Blocks until Analytics' own persisted override reads back as [enabled] and
     * has reached disk — or gives up and says so.
     *
     * **`setAnalyticsCollectionEnabled` returns before anything is written, and
     * before anything is even queued** (Codex, PR #166). Verified against the
     * SDK artifacts rather than assumed:
     * `FirebaseAnalytics.setAnalyticsCollectionEnabled` hands a `Runnable` to an
     * `ExecutorService` and returns; that runnable later does
     * `putBoolean("measurement_enabled_from_api", …).apply()` on the
     * measurement preferences. So the same failure [flushCollectionOverride]
     * exists for is open here too: this app's own store commits synchronously,
     * and a process death in between can leave our preference durably off while
     * Analytics' override is durably on — and Analytics is the SDK that then
     * collects on its own, with no gate of ours left to shut.
     *
     * **The Crashlytics flush is not the fix here, and copying it would be a
     * guard that proves nothing.** That one works because
     * `setCrashlyticsCollectionEnabled` does its `apply()` on the calling
     * thread, so a later `commit()` on the same file necessarily serializes
     * behind an already-queued write. Analytics' write is on another thread and
     * may not be queued yet, so a bare no-op commit could return before the
     * SDK's runnable has run at all.
     *
     * So this waits for the value itself, then commits to force it down. A
     * bounded wait rather than an unbounded one: nothing here is on the arm
     * path or in front of a frame — `CrashReporting` calls it from its own
     * worker — but a wait that could never end would be its own bug.
     *
     * Both names below are SDK internals. If a future release renames either,
     * the wait would time out on every call rather than silently pass, and the
     * warning says which.
     */
    internal fun awaitAnalyticsOverride(
        prefs: SharedPreferences,
        enabled: Boolean,
        attempts: Int = OVERRIDE_ATTEMPTS,
        sleep: (Long) -> Unit = Thread::sleep,
    ): Boolean {
        repeat(attempts) { attempt ->
            // `contains` first: the key is absent until the SDK's worker writes
            // it, and a default would otherwise read as a match half the time.
            if (prefs.contains(MEASUREMENT_ENABLED_FROM_API_KEY) &&
                prefs.getBoolean(MEASUREMENT_ENABLED_FROM_API_KEY, !enabled) == enabled
            ) {
                // The map holds the value; this is what makes it durable. Its
                // result is the answer — `commit()` reports a failed write by
                // returning false rather than throwing.
                val flushed = prefs.edit().commit()
                if (!flushed) {
                    SnoozeDebugLog.warning("analytics: the SDK's collection override could not be written to disk")
                }
                return flushed
            }
            if (attempt < attempts - 1) sleep(OVERRIDE_POLL_MILLIS)
        }
        SnoozeDebugLog.warning(
            "analytics: the SDK did not record the collection setting where this build expects it, " +
                "so the change could not be confirmed durable",
        )
        return false
    }

    /**
     * Drops whatever the reporter is holding, without touching the collection
     * switch.
     *
     * For the moment before an opt-in takes effect. Starting Crashlytics with
     * collection off stops it *sending*, not capturing — the uncaught-exception
     * handler is installed either way — so a crash while reporting is off is
     * written to disk and sits there unsent. Enabling would then release it:
     * a report from a period the user had not agreed to, sent because they
     * later agreed to something else (Codex, ClothesCast PR #1161, against the
     * same design).
     *
     * The disable path deletes as part of [apply]; this is the same deletion
     * on its own, for the enable path where there is no collection change to
     * hang it off.
     */
    fun discardPending(context: Context) {
        if (!isAvailable(context)) return
        FirebaseCrashlytics.getInstance().deleteUnsentReports()
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
            SnoozeDebugLog.failure(it, "crash reporting: could not flush the SDK's opt-out to disk")
            false
        }

    /** Crashlytics' `CommonUtils.SHARED_PREFS_NAME`. */
    private const val CRASHLYTICS_PREFS = "com.google.firebase.crashlytics"

    /** Crashlytics' `DataCollectionArbiter.FIREBASE_CRASHLYTICS_COLLECTION_ENABLED`. */
    private const val COLLECTION_ENABLED_KEY = "firebase_crashlytics_collection_enabled"

    /** The measurement SDK's own preferences file. */
    private const val MEASUREMENT_PREFS = "com.google.android.gms.measurement.prefs"

    /** Where the measurement SDK records an override set through its API. */
    private const val MEASUREMENT_ENABLED_FROM_API_KEY = "measurement_enabled_from_api"

    /**
     * How long [awaitAnalyticsOverride] will wait, as attempts × poll. Half a
     * second is generous for an executor hop and a preferences write, and this
     * runs on a worker with nothing waiting on it.
     */
    private const val OVERRIDE_ATTEMPTS = 20
    private const val OVERRIDE_POLL_MILLIS = 25L
}
