package app.snoozemo.snooze

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.snoozemo.ui.WelcomeCardMemory
import app.snoozemo.ui.shouldOpenWelcome

/**
 * Remembers that the welcome flow has been shown (`SPEC.md` §4.2).
 *
 * The flow runs once, on a fresh install, before the permissions screen — so
 * something has to outlive the process that showed it. That is [seen]. The
 * other half of the question, [freshInstall], outlives nothing and is stored
 * nowhere: it is a fact the platform already knows about this install, and it
 * lives here so both inputs to that one decision sit behind one warm-up.
 *
 * **Written when the flow is left, not when it is entered.** A flag set on
 * arrival would be spent by a process death mid-flow, and the user would never
 * see the cards they were part-way through; a flow that can be lost to a crash
 * is worse than one shown twice. `Skip` and the last card's `Done` both write
 * it, since both are the user saying they are finished with it — and the help
 * icon replays it afterwards without clearing anything, because a replay is not
 * a fresh install.
 *
 * One boolean about the app's own history, and one about the install's. Nothing
 * here is about the user.
 */
class WelcomeStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Loads both answers off the main thread, so the reads below find them in
     * memory.
     *
     * `MainActivity` asks [seen] and [freshInstall] in `onCreate`, *before*
     * `setContent` — together they decide which screen the first frame draws,
     * so they cannot be deferred to after it. That makes them exactly the reads
     * principle 5 forbids leaving in front of a first frame, and warming at
     * process start is principle 4's answer: the same treatment the zen rule
     * id, the active snooze and the ringer setting already get
     * (`SnoozemoApplication`). [freshInstall] is a `PackageManager` binder call
     * and the more expensive of the two, so it matters more that it is warm.
     *
     * Same caveat as those, stated for the same reason [ActiveSnoozeStore.warm]
     * states it — **this narrows the window, it does not close it**. A launch
     * fast enough to reach `onCreate` before the warm-up finishes still waits,
     * and closing that fully would mean blocking somewhere earlier on the same
     * thread, which moves the cost rather than removing it.
     */
    fun warm() {
        Thread {
            seen()
            freshInstall()
            // Off this thread too, since it reads the same warmed values: the
            // arm path asks [WelcomeGate] rather than this store, so the answer
            // has to be in memory before the first tile tap (SPEC.md §4.2).
            publishGateFromWarm()
        }.start()
    }

    /** Whether the flow has been seen through to its exit. */
    fun seen(): Boolean = prefs.getBoolean(KEY_SEEN, false)

    /**
     * Whether the welcome flow is open and a tile tap should resume it rather
     * than arm.
     *
     * **This is exactly [shouldOpenWelcome]'s question, so it calls it** rather
     * than restating the expression (Codex, PR #291): a replay is `seen` and
     * not fresh but still in progress, so `!seen && …` answered `false` for it
     * and a mid-replay tap armed past the card the user was on. The gate must
     * open wherever `MainActivity` would open or resume the flow, and one
     * function is what keeps the two from disagreeing.
     */
    private fun computeUnfinished(): Boolean = shouldOpenWelcome(
        seen = seen(),
        freshInstall = ::freshInstall,
        inProgress = rawCard() != null,
    )

    /**
     * Publishes the gate after a write on this thread — authoritative, so it
     * bumps the generation and cannot be overwritten by an in-flight warm-up
     * read ([WelcomeGate.publish]).
     */
    private fun publishGateAfterWrite() = WelcomeGate.publish(computeUnfinished())

    /**
     * Publishes the gate from the warm-up worker, yielding to any write that
     * landed while it read (Codex, PR #291). [WelcomeGate.beginRead] is captured
     * *before* the preference reads and [WelcomeGate.publishIfUnchanged] declines
     * if a write has bumped the generation since — the same guard `EndSheetStore`
     * uses, so a slow read cannot clobber a `rememberCard`/`forgetCard`.
     */
    private fun publishGateFromWarm() {
        val generation = WelcomeGate.beginRead()
        val value = computeUnfinished()
        afterWarmReadBeforePublishForTest?.invoke()
        WelcomeGate.publishIfUnchanged(value, generation)
    }

    /**
     * Fires between the warm-up read and its publish, so a test can drive the
     * exact write-during-read interleaving the generation guard exists for —
     * the same seam `EndSheetStore` exposes.
     */
    @VisibleForTesting
    var afterWarmReadBeforePublishForTest: (() -> Unit)? = null

    /** Runs [publishGateFromWarm] synchronously, so a test need not race the worker. */
    @VisibleForTesting
    fun publishGateFromWarmForTest() = publishGateFromWarm()

    /** The stored card name under any key, unmigrated — the "in progress" bit. */
    private fun rawCard(): String? =
        prefs.getString(WelcomeCardMemory.KEY, null)
            ?: prefs.getString(WelcomeCardMemory.LEGACY_KEY, null)
            ?: prefs.getString(WelcomeCardMemory.OLDEST_KEY, null)

    /**
     * The card the flow was left on, so a tile tap part-way through it comes
     * back to where the user was rather than to the start (maintainer,
     * 2026-09-07).
     *
     * Saved instance state already covers a rotation, and a live activity
     * covers a tap that reaches it — but a tap can arrive minutes later, after
     * the process is gone, and then the flow is rebuilt from nothing. Null when
     * the flow has never been entered or has been left; the name of a
     * `WelcomeCard` otherwise, resolved by the caller so a card removed in a
     * later build reads as "no memory" rather than as a crash.
     *
     * **A name written before the 2026-09-08 card reorder is migrated**, by the
     * same [WelcomeCardMemory] the saved-instance-state path uses — the reasons
     * are there, and one owner is what keeps the two stores from drifting
     * (Codex, PR #226).
     */
    fun lastCard(): String? = WelcomeCardMemory.resolve(
        current = prefs.getString(WelcomeCardMemory.KEY, null),
        legacy = prefs.getString(WelcomeCardMemory.LEGACY_KEY, null)
            ?: prefs.getString(WelcomeCardMemory.OLDEST_KEY, null),
    )

    /** Remembers [card] as the one the flow is on. */
    fun rememberCard(card: String) {
        // `apply`, not `commit`: this is a breadcrumb, and the cost of losing
        // the last one to a kill is starting the flow one card earlier — worth
        // less than a disk write in front of the card's own frame.
        //
        // The older keys go in the same edit, so the migration fires at most
        // once per install: after this there is a new-key breadcrumb, and it
        // wins.
        prefs.edit()
            .putString(WelcomeCardMemory.KEY, card)
            .remove(WelcomeCardMemory.LEGACY_KEY)
            .remove(WelcomeCardMemory.OLDEST_KEY)
            .apply()
        // A card was just remembered, so the flow is in progress — republish so
        // a tile tap resumes it rather than arming (SPEC.md §4.2).
        publishGateAfterWrite()
    }

    /** Forgets it, once the flow has been left. */
    fun forgetCard() {
        // All keys: leaving an older one behind would resume a flow the user
        // has finished with.
        prefs.edit()
            .remove(WelcomeCardMemory.KEY)
            .remove(WelcomeCardMemory.LEGACY_KEY)
            .remove(WelcomeCardMemory.OLDEST_KEY)
            .apply()
        publishGateAfterWrite()
    }

    /**
     * Whether the hint pointing at the help icon has been dismissed.
     *
     * Its own flag rather than a second meaning for [seen]: the hint exists
     * *because* the flow has been seen, so the two are never the same question,
     * and a user who dismisses the hint has not un-seen the flow.
     */
    fun replayHintDismissed(): Boolean = prefs.getBoolean(KEY_HINT_DISMISSED, false)

    /** Records that the user dismissed the hint. It does not come back. */
    fun dismissReplayHint() {
        prefs.edit().putBoolean(KEY_HINT_DISMISSED, true).apply()
    }

    /** Records that the user left the flow, by `Skip` or from the last card. */
    fun markSeen() {
        // `apply`, not `commit`: this runs as the user leaves the last card,
        // and a disk write on that frame is a stutter on the way into the app.
        // Losing it to a process death in that window costs one extra showing
        // of the flow, which is the cheap direction to fail.
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
        // The flow is finished, so a tile tap arms again from here on.
        publishGateAfterWrite()
    }

    /**
     * Whether this install has never been updated, which is the platform's own
     * way of saying "fresh install" — the two timestamps are equal until the
     * first update rewrites one of them.
     *
     * Read rather than persisted: a flag of our own would have to be seeded for
     * installs that predate it, and getting *that* ordering wrong is the same
     * bug one step further back. A lookup that throws is treated as *not*
     * fresh, because the costly mistake is showing onboarding to someone who
     * has been using the app for months, not withholding it from someone who
     * can replay it from the title row whenever they like.
     */
    fun freshInstall(): Boolean = cachedFreshInstall ?: compute().also { cachedFreshInstall = it }

    private fun compute(): Boolean = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrElse {
        Log.w(TAG, "Reading the install times failed; treating this as an existing install.", it)
        false
    }

    private companion object {
        // Process-wide, not per-instance: `SnoozemoApplication` warms one
        // `WelcomeStore` and `MainActivity` constructs another, so a field on
        // the instance would leave the activity's copy cold and the warm-up
        // buying nothing. (`seen` needs no equivalent — the framework already
        // shares one `SharedPreferences` per file across instances.)
        //
        // Stable for the process's whole life, since an update cannot land
        // underneath a running process, so a race between two readers costs one
        // repeated lookup and can never produce two different answers. That is
        // why there is no lock: the cheap wrong outcome is doing the work twice.
        @Volatile
        var cachedFreshInstall: Boolean? = null

        const val TAG = "WelcomeStore"
        const val FILE_NAME = "welcome"
        const val KEY_SEEN = "seen"
        const val KEY_HINT_DISMISSED = "replayHintDismissed"
    }
}
