package app.snoozemo.core

/**
 * Whether Snoozemo can read when the user's next meeting ends — and, when it
 * can't, whether asking again would do anything.
 *
 * `READ_CALENDAR` backs one thing only: the ongoing notification's third action
 * (`SPEC.md` §4.5). Missing it costs that action and nothing else, so this is
 * the most optional capability the app has — a row that states a gap, never a
 * blocked product.
 *
 * The three states are [NotificationPermission]'s, for the reason spelled out
 * there: the platform shows a runtime dialog at most twice and then silently
 * ignores every later request, so a row that always offers to ask becomes a tap
 * that does nothing. [LocationPermission] carries the same shape over two
 * permissions; this one has a single permission behind it, which is why it is
 * the simpler of the two rather than a special case of it.
 */
enum class CalendarPermission {

    /** Held. The notification can offer a meeting's end time. */
    GRANTED,

    /** Not held, and the system will still show the runtime dialog. */
    ASKABLE,

    /**
     * Not held, and the system will silently ignore further requests. Settings
     * is the only live route, so the row has to say so rather than offering a
     * prompt that will never appear.
     */
    BLOCKED,

    ;

    companion object {

        /**
         * @param granted whether `READ_CALENDAR` is currently held.
         * @param everDenied whether Snoozemo has ever observed [rationale] be
         *   true — persisted, since the platform gives no way to ask it about
         *   the past.
         * @param rationale `shouldShowRequestPermissionRationale`, true only
         *   between the first explicit denial and the permanent one.
         *
         * The same reading [NotificationPermission.of] documents at length:
         * `rationale` alone cannot tell a fresh install from a permission the
         * system has stopped prompting for, because it reads false at both ends.
         */
        fun of(granted: Boolean, everDenied: Boolean, rationale: Boolean): CalendarPermission = when {
            granted -> GRANTED
            rationale -> ASKABLE
            !everDenied -> ASKABLE
            else -> BLOCKED
        }
    }
}
