package app.snoozemo.snooze

import android.content.Context
import android.util.Log

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
        }.start()
    }

    /** Whether the flow has been seen through to its exit. */
    fun seen(): Boolean = prefs.getBoolean(KEY_SEEN, false)

    /** Records that the user left the flow, by `Skip` or from the last card. */
    fun markSeen() {
        // `apply`, not `commit`: this runs as the user leaves the last card,
        // and a disk write on that frame is a stutter on the way into the app.
        // Losing it to a process death in that window costs one extra showing
        // of the flow, which is the cheap direction to fail.
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
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
    }
}
