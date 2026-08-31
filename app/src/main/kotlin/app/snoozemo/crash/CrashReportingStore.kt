package app.snoozemo.crash

import android.content.Context

/**
 * Remembers the user's answer to the telemetry question (`SPEC.md` §12):
 * whether crash reporting and anonymous analytics are on, and — separately —
 * whether they have been asked at all.
 *
 * **Defaults to off** (maintainer, 2026-08-28), reversing §12's original
 * on-by-default decision: reporting leaves the device, so it waits for the
 * user's explicit agreement. Nothing here decides whether a reporter exists to
 * be turned on; that is the flavor's answer (`CrashReporter`), and `direct`
 * has none.
 *
 * `SharedPreferences`, like the other one-key stores in this app — read while
 * deciding what to draw, so it must not cost a coroutine or a disk wait — and
 * it holds one boolean about the app's own diagnostics. Nothing about the
 * user, the place, or the time is written here.
 */
internal class CrashReportingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /**
     * Whether the user has answered the question at all.
     *
     * Distinct from [isEnabled] because off and unasked are different facts
     * about an install and the consent card has to tell them apart: a
     * recorded "no" must not be re-asked, and an install that never saw the
     * question must be. [isEnabled] alone cannot say which — it reads false
     * for both — which is why declining writes this rather than merely
     * leaving the switch where it was.
     */
    fun hasAnswered(): Boolean = prefs.getBoolean(KEY_ANSWERED, false)

    /**
     * Marks the question as answered, whichever way it went.
     *
     * Both buttons on the consent card reach this: a question you can only
     * walk away from is not one that was asked, so declining is a recorded
     * "no" rather than an absence and the card does not come back (simmo's
     * `AnalyticsInviteCard` is the prior art).
     *
     * Set unconditionally, and never unset. An install whose *enabled* write
     * was refused is still an install that answered — re-asking would put the
     * question a second time to someone who already decided — and the refusal
     * has its own report through [setEnabled].
     *
     * **Returns whether the write reached disk, and rolls the map back when it
     * did not** — the same shape as [setEnabled] and for the same reason
     * (Codex, PR #166). `commit()` applies to the process-local map before the
     * disk write it reports on, so a refused write left as-is reads *answered*
     * for the rest of this process and *unanswered* after it. That split is
     * the worst of the three states available here: the card goes away, this
     * session collects because [collectionPermitted] sees both halves true,
     * and the next launch silently stops collecting and asks again while
     * Settings still shows the switch on. Rolling back keeps the card up and
     * collection off, which is at least what the durable state says, and the
     * caller reports the refusal on the switch's own failure line.
     */
    fun setAnswered(): Boolean {
        // Already answered is already done, so there is nothing to write and
        // nothing that can fail (Codex, PR #166). Both surfaces call this on
        // every change, so for anyone past the card this is the common path,
        // and skipping it is what keeps a redundant write from ever being able
        // to un-answer them.
        if (hasAnswered()) return true
        val persisted = prefs.edit().putBoolean(KEY_ANSWERED, true).commit()
        if (!persisted) {
            // `false` is the value that was there, not a guess: the early
            // return above is what makes that true. Without it this rollback
            // would un-answer an already-answered user whose redundant write
            // happened to fail — the card back, collection stopped, over a
            // choice they had already made.
            prefs.edit().putBoolean(KEY_ANSWERED, false).commit()
        }
        return persisted
    }

    /**
     * Whether collection is permitted: the user said yes **and** was asked.
     *
     * Both halves, because the stored `enabled` predates the question (Codex,
     * PR #166). An install upgrading from a build that had only the Crash
     * reports switch carries `enabled = true` with no answer recorded, and
     * reading the preference alone would take a yes about crash reports as a
     * yes about analytics — a consent the user was never offered, applied at
     * startup before the card could even appear.
     *
     * So an unanswered install collects nothing whatever the preference says,
     * and the only thing that changes that is the user answering.
     */
    fun collectionPermitted(): Boolean = isEnabled() && hasAnswered()

    /**
     * Persists the choice, returning whether the write reached disk.
     *
     * On a refused write the old value is put back first, exactly as
     * `DebugLogStore` does and for the same reason: `commit()` applies the
     * change to the process-local map *before* the disk write it reports on,
     * so without the restore every later read would return a value that was
     * neither applied nor stored — a switch reading `off` over a reporter
     * still collecting, until a process restart flipped it back. The
     * restore's own disk write may fail too; the map is restored regardless,
     * which is the part every reader sees.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val before = isEnabled()
        val persisted = prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
        if (!persisted) {
            prefs.edit().putBoolean(KEY_ENABLED, before).commit()
        }
        return persisted
    }

    /**
     * Loads the preferences file so the first real read is a memory hit.
     * Called from `Application.onCreate` off the main thread, like the other
     * warmed stores on the arm path (`SPEC.md` §4.1).
     */
    fun warm() {
        isEnabled()
        hasAnswered()
    }

    private companion object {
        const val FILE_NAME = "crash_reporting"
        const val KEY_ENABLED = "enabled"
        const val KEY_ANSWERED = "answered"
    }
}
