package app.snoozemo.core

/**
 * Whether Snoozemo can post the messages it promises to post — and, when it
 * can't, whether asking again would do anything.
 *
 * `POST_NOTIFICATIONS` is where principle 2 lives: a degraded mode, a failed
 * release, or a snooze that ended for a reason the user didn't choose is *said*,
 * and without this permission the system drops every one of those. So the app
 * screen states it and offers the way to fix it — which means it has to know
 * which way that is, because there are two and only one of them works at a time.
 *
 * Pure and in `:core` because the third state is the one that is easy to get
 * wrong and impossible to reproduce on demand: the system shows the runtime
 * dialog at most twice and then silently ignores every later request, so a row
 * that always launches the request becomes a tap that does nothing — the exact
 * defect this screen is being fixed for.
 */
enum class NotificationPermission {

    /** Held. Nothing is owed and nothing is being dropped. */
    GRANTED,

    /**
     * Not held, and the system will still show the runtime dialog. The fix is
     * in-app: one tap, one prompt, no trip to Settings.
     */
    ASKABLE,

    /**
     * Not held, and the system will silently ignore further requests. The only
     * live route left is the app's notification settings, so the row has to say
     * so rather than offering a prompt that will never appear.
     */
    BLOCKED,

    ;

    companion object {

        /**
         * @param granted whether `POST_NOTIFICATIONS` is currently held.
         * @param everDenied whether Snoozemo has ever observed [rationale] be
         *   true — persisted, because the platform gives no way to ask it about
         *   the past. Cleared on a grant, since granting resets the denial
         *   history the system counts.
         * @param rationale `shouldShowRequestPermissionRationale`, which is true
         *   only between the first explicit denial and the permanent one.
         *
         * [everDenied] is what separates the two states that look identical from
         * the outside: `rationale` reads false both before the first denial and
         * after the last, so the reading alone cannot tell a fresh install from
         * a permission the system has stopped prompting for.
         *
         * It tracks an **explicit denial**, not a request. A dialog the user
         * swipes away is not a denial and does not count against the system's
         * two — so treating every launch as a spent prompt would send a user who
         * pressed back once to Settings for a dialog that was still available,
         * and would stop the tile asking at all. `rationale` going true is the
         * only signal the platform gives that a denial actually landed, and it
         * is the one this reads.
         */
        fun of(granted: Boolean, everDenied: Boolean, rationale: Boolean): NotificationPermission = when {
            granted -> GRANTED
            // Denied once. The system still shows the dialog, and this is the
            // state it says so in.
            rationale -> ASKABLE
            // Never explicitly denied — a fresh install, or a dialog dismissed
            // without an answer. Either way there is a prompt left to show.
            !everDenied -> ASKABLE
            // Denied before, and the platform has stopped offering the prompt.
            else -> BLOCKED
        }
    }
}
