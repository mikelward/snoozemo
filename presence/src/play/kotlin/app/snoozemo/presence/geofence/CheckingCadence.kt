package app.snoozemo.presence.geofence

import app.snoozemo.core.Departure

/**
 * How the checking burst paces its one-shot fixes (SPEC.md §6.6, §6.10).
 *
 * The `play` flavor has no foreground service, so it cannot hold a continuous
 * 90-second request — background apps get location
 * a handful of times an hour. What it can do is take a **one-shot fix per
 * step of the confirmation**: the §6.6 test needs two qualifying fixes at
 * least [Departure.CONFIRMATION_GAP] apart, so while the engine is checking,
 * a fix is taken every [confirmSpacingMs] — which satisfies the gap by
 * construction.
 *
 * The backoff is the battery bound (SPEC.md §9): a provider that answers
 * nothing must not be asked twice a minute for the rest of a snooze the
 * engine cannot resolve. After [BACKOFF_AFTER] consecutive requests with no
 * fix — the same threshold at which the engine calls tracking degraded — the
 * cadence drops to [BACKOFF_SPACING_MS], and one delivered fix restores it.
 *
 * Pure, so the pacing rules are JVM-tested; the platform half just asks
 * [nextDelayMs] after each outcome.
 */
internal class CheckingCadence(
    /**
     * The gap this snooze's confirmation actually needs.
     *
     * Taken rather than read off a constant, because the two had drifted apart
     * by construction: the engine accepted two fixes [Departure.CONFIRMATION_GAP]
     * apart while this class asked for one every hard-coded 30 s, so shortening
     * the accepted gap would have paced nothing and shipped as a latency win
     * that never happens (Codex, PR #222; `TODO.md`). Passed in per snooze
     * because that is where the cadence is built — see [CheckingFixes] — so a
     * gap that later varies by anchor changes this expression and nothing else.
     */
    private val confirmSpacingMs: Long = CONFIRM_SPACING_MS,
) {

    private var consecutiveUnanswered = 0

    /** A fix was delivered — whatever the engine makes of it. */
    fun onFixDelivered() {
        consecutiveUnanswered = 0
    }

    /** The request produced nothing: timeout, refusal, or an unusable reading. */
    fun onNothing() {
        consecutiveUnanswered++
    }

    /**
     * The platform layer these requests were failing against says it is
     * working again — location switched back on (SPEC.md §8.4).
     *
     * Deliberately not [onFixDelivered] under another name, even though the
     * effect is the same: no fix has arrived, and the reason the backoff is
     * being forgiven is what the next reader needs. A backoff is a bound on
     * asking a provider that is not answering; once the *reason* it was not
     * answering is provably over, serving out five more minutes of it means
     * a snooze reports degraded tracking long after the outage ended, which
     * is the very latency this recovery path exists to remove. The bound
     * still holds if the provider goes on failing — the count simply starts
     * again from the recovery.
     */
    fun onPlatformRecovered() {
        consecutiveUnanswered = 0
    }

    /** How long to wait before the next one-shot. */
    val nextDelayMs: Long
        get() = if (consecutiveUnanswered >= BACKOFF_AFTER) {
            BACKOFF_SPACING_MS
        } else {
            confirmSpacingMs
        }

    companion object {
        /**
         * §6.6's confirmation gap, in milliseconds: the second qualifying fix
         * must be at least this long after the first, so taking one per gap
         * satisfies it exactly.
         *
         * **Derived, never restated.** It was a hard-coded `30_000L` beside a
         * `Duration.ofSeconds(30)` in `:core`, which is a duplicated number
         * whose divergence nothing would have reported.
         */
        val CONFIRM_SPACING_MS: Long = Departure.CONFIRMATION_GAP.toMillis()

        /**
         * Matches `Presence.DEGRADED_AFTER_USELESS_OBSERVATIONS`: by this
         * point the engine has already told the user tracking degraded, so
         * the burst stops paying full rate for answers that are not coming.
         */
        const val BACKOFF_AFTER: Int = 3

        const val BACKOFF_SPACING_MS: Long = 5 * 60_000L
    }
}
