package app.snoozemo.snooze

import android.content.Context
import android.util.Log
import app.snoozemo.core.ZenFailure

/**
 * Why an arm didn't take, in the two shapes it can take.
 *
 * The zen rule is only the last step of arming. The cap alarm has to schedule
 * first (SPEC.md §7) and the record has to reach disk after (§8.1), and a
 * failure at either one aborts the arm without the zen controller ever
 * reporting anything — so a store that could only hold a [ZenFailure] left
 * those two permanently unexplainable.
 */
sealed interface ArmFailure {

    /** The platform refused to turn the rule on, for [failure]'s reason. */
    data class Zen(val failure: ZenFailure) : ArmFailure

    /**
     * Something below the rule gave way — the cap alarm wouldn't schedule, or
     * the record wouldn't write. Not distinguished further, for the same reason
     * `SnoozeNotifications.showCouldNotArm` gives one message for both: which
     * internal step gave way is a debug-log fact, not something a user can act
     * on.
     */
    data object BelowZen : ArmFailure
}

/**
 * Remembers the reason an arm failed, so a notification the system dropped can
 * still be delivered later.
 *
 * The tile-first case this exists for: the very first tap happens with
 * `POST_NOTIFICATIONS` still denied, the arm fails, and the message explaining
 * *why* is silently discarded by the platform. The user is left with a tile they
 * tapped and a phone that didn't go quiet, and no reason for either — principle
 * 2's failure. When the permission is later granted, `ACTION_REFRESH` reads this
 * and posts what was lost.
 *
 * Persisted rather than held in memory because the service that saw the failure
 * has usually stopped by then — an arm that failed leaves nothing running.
 *
 * Only the failure *reason* is stored, which is app state (`ZenFailure` names a
 * platform outcome); nothing about the user, the place, or the time is written
 * here.
 */
class PendingFailureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Records [failure] as the explanation owed to the user. */
    fun remember(failure: ArmFailure) {
        val stored = when (failure) {
            // Prefixed rather than stored bare, so a `ZenFailure` constant can
            // never collide with the sentinel below as the enum grows.
            is ArmFailure.Zen -> ZEN_PREFIX + failure.failure.name
            ArmFailure.BelowZen -> BELOW_ZEN
        }
        prefs.edit().putString(KEY_FAILURE, stored).apply()
    }

    /**
     * Returns the owed explanation, leaving it where it is.
     *
     * Read-then-clear-on-delivery, not read-and-clear. The reason this exists
     * at all is that the message was dropped once already — posted while
     * `POST_NOTIFICATIONS` was still denied — and the thing replaying it can
     * fail for the same family of reasons: a refused `notify`, channels that
     * wouldn't create. Clearing on the way past would spend the last copy of an
     * explanation nobody ever saw, and this arm has already failed, so nothing
     * later will re-record it. The caller clears once the post is confirmed.
     */
    fun peek(): ArmFailure? {
        val stored = prefs.getString(KEY_FAILURE, null) ?: return null
        if (stored == BELOW_ZEN) return ArmFailure.BelowZen
        return try {
            ArmFailure.Zen(ZenFailure.valueOf(stored.removePrefix(ZEN_PREFIX)))
        } catch (e: IllegalArgumentException) {
            // Written by a build that knew a failure this one doesn't. Nothing
            // specific to post, but the arm still failed and the user is still
            // owed the reason it didn't snooze — so this degrades to the
            // generic message rather than dropping the explanation entirely.
            Log.w(TAG, "A pending arm failure could not be read back; posting the generic one.", e)
            ArmFailure.BelowZen
        }
    }

    /**
     * Forgets the owed explanation — for an arm that then succeeded, or once
     * the message has actually reached the shade.
     */
    fun clear() {
        prefs.edit().remove(KEY_FAILURE).apply()
    }

    /**
     * Records that Snoozemo's rule may be on with nothing on disk describing it
     * — the one leftover no other mechanism can pick up.
     *
     * Kept **separately** from the arm failure above, because it is a different
     * kind of thing with a different lifetime. That one is an explanation owed
     * to the user, cleared once it has been said; this is an *obligation*,
     * cleared only when the rule is actually off. They can also both be
     * outstanding at once: a refused arm owes "Couldn't snooze" *and* may have
     * left a rule behind.
     *
     * `commit`, not `apply`, and this is the reason the flag exists at all: the
     * process holding the in-process retry can be stopped at any moment — the
     * service is an ordinary started one — and a write still sitting in the
     * background queue when that happens is a promise that was never made.
     *
     * No identity, deliberately, unlike every other retry here. Those name the
     * snooze they are for because ending the *wrong* snooze is a real harm.
     * This one cannot pick a wrong target: it means "our own rule may be on
     * with no snooze behind it", every reader checks that there is still no
     * record before acting, and driving an already-off rule off is a no-op. A
     * flag is the whole of what needs to survive.
     *
     * Returns whether the write actually landed. On the paths that reach here
     * last — no record, no alarm, no service — this *is* the successor, so a
     * caller that assumed it took would leave nothing at all behind a rule that
     * may still be silencing the phone.
     */
    fun rememberRuleMayBeStuck(): Boolean {
        val written = prefs.edit().putBoolean(KEY_RULE_STUCK, true).commit()
        if (!written) {
            Log.e(TAG, "Recording a possibly-stuck rule failed; only this process can still release it.")
        }
        return written
    }

    /** Whether a release is still owed for a rule nothing on disk describes. */
    fun ruleMayBeStuck(): Boolean = prefs.getBoolean(KEY_RULE_STUCK, false)

    /** The rule is confirmed off, so nothing is owed for it any more. */
    fun clearRuleStuck() {
        prefs.edit().remove(KEY_RULE_STUCK).apply()
    }

    private companion object {
        const val TAG = "PendingFailure"
        const val FILE_NAME = "pending_failure"
        const val KEY_FAILURE = "failure"
        const val KEY_RULE_STUCK = "rule_may_be_stuck"
        const val ZEN_PREFIX = "zen:"
        const val BELOW_ZEN = "below_zen"
    }
}
