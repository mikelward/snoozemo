package app.snoozemo.dnd

import android.content.Context

/**
 * Remembers that Snoozemo's zen rule needs turning off before it will go back
 * on (SPEC.md §5.9).
 *
 * The requirement is real state and it used to be inferred. A ceiling write
 * that finishes an earlier loan deactivates the rule through the platform's
 * coupling, and the arm that noticed can fail to revive it — reporting itself
 * unconfirmed, which is *always* what happens below API 35, where the platform
 * will not say whether the rule came back. The snooze is then kept for the cap
 * to retry, and the retry had no way to know: the finishing write marks the
 * loan applied, so the next `RingerHandover.quiet` returns `Nothing`, the arm
 * sees an ordinary re-assertion, and a `STATE_TRUE` it reports `Applied` lands
 * on a rule the platform is still ignoring — this PR's own bug, one path along.
 *
 * So the arm that cannot confirm the revival writes the requirement down, and
 * every arm reads it. Durable because a restore after process death is itself
 * one of the re-assertion paths.
 *
 * **Bounded, not sticky.** A flag set where it was not needed costs exactly one
 * extra off-and-on cycle: the next arm does it, the reset lands, the re-arm is
 * confirmed, and the flag clears. On a *fresh* arm that cycle is not even
 * visible — Do Not Disturb is off before it anyway — so the cost the maintainer
 * accepted for the rare recovery path is not widened by a stale flag.
 */
interface StuckRuleStore {
    fun stuck(): Boolean

    /**
     * Records [stuck] durably, returning false if it did not reach disk.
     *
     * Unlike [ZenRuleIdStore.setRuleId] a failure here is not fatal: the arm
     * still un-sticks the rule in this process, and what is lost is only the
     * knowledge a *later* process would have used. The caller says so rather
     * than refusing the arm, because refusing would leave the phone audible to
     * protect a retry.
     */
    fun setStuck(stuck: Boolean): Boolean
}

/**
 * `SharedPreferences`, and **the same file the rule id uses**, deliberately:
 * every arm reads this, so it has to be a memory hit rather than a disk load,
 * and `PrefsZenRuleIdStore.warm` already loads that file at startup. A file of
 * its own would be a second thing to warm and a second thing to forget to warm.
 */
class PrefsStuckRuleStore(context: Context) : StuckRuleStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun stuck(): Boolean = prefs.getBoolean(KEY_RULE_STUCK, false)

    /**
     * `commit`, not `apply`, for the same reason the id uses it: the value is
     * only worth anything to a process that comes up after this one, so a write
     * still queued when this process dies is a write that never happened. It
     * runs on the rare finishing branch and on a release, never between a tap
     * and `STATE_TRUE`.
     */
    override fun setStuck(stuck: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_RULE_STUCK, stuck).commit()

    private companion object {
        /** `PrefsZenRuleIdStore.FILE_NAME`, shared on purpose — see above. */
        const val FILE_NAME = "zen_rule"
        const val KEY_RULE_STUCK = "rule_stuck"
    }
}
