package app.snoozemo.presence.geofence

/**
 * How the checking burst paces its one-shot fixes (SPEC.md §6.6, §6.10).
 *
 * The `play` flavor has no foreground service, so it cannot hold the `direct`
 * flavor's continuous 90-second request (§6.5) — background apps get location
 * a handful of times an hour. What it can do is take a **one-shot fix per
 * step of the confirmation**: the §6.6 test needs two qualifying fixes at
 * least 30 seconds apart, so while the engine is checking, a fix is taken
 * every [CONFIRM_SPACING_MS] — which satisfies the gap by construction.
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
internal class CheckingCadence {

    private var consecutiveUnanswered = 0

    /** A fix was delivered — whatever the engine makes of it. */
    fun onFixDelivered() {
        consecutiveUnanswered = 0
    }

    /** The request produced nothing: timeout, refusal, or an unusable reading. */
    fun onNothing() {
        consecutiveUnanswered++
    }

    /** How long to wait before the next one-shot. */
    val nextDelayMs: Long
        get() = if (consecutiveUnanswered >= BACKOFF_AFTER) {
            BACKOFF_SPACING_MS
        } else {
            CONFIRM_SPACING_MS
        }

    companion object {
        /**
         * §6.6's confirmation gap: the second qualifying fix must be at least
         * 30 s after the first, so taking one per 30 s satisfies it exactly.
         */
        const val CONFIRM_SPACING_MS: Long = 30_000L

        /**
         * Matches `Presence.DEGRADED_AFTER_USELESS_OBSERVATIONS`: by this
         * point the engine has already told the user tracking degraded, so
         * the burst stops paying full rate for answers that are not coming.
         */
        const val BACKOFF_AFTER: Int = 3

        const val BACKOFF_SPACING_MS: Long = 5 * 60_000L
    }
}
