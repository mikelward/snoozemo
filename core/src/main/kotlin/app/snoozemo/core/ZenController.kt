package app.snoozemo.core

/**
 * Snoozemo's contact with Do Not Disturb (SPEC.md §5).
 *
 * The contract lives in `:core` with its consumer — `SnoozeController` takes one
 * by injection — and the Android implementation lives in `:dnd`, which is the
 * only module allowed to touch `NotificationManager` or `AutomaticZenRule`.
 *
 * Two invariants this interface exists to protect:
 *
 * - Snoozemo turns off **only its own rule** (SPEC.md §5.6). There is no method
 *   here for "turn DND off", because that is not a thing this app may do: if a
 *   bedtime schedule or another app is also silencing the phone, that stays.
 * - Every call reports what happened. A caller that cannot tell whether the
 *   phone actually went quiet cannot tell the user either, and a silent failure
 *   on the arm path leaves someone believing they are snoozed when they are not.
 */
interface ZenController {

    /** Whether the user has granted notification-policy access (SPEC.md §5.2). */
    fun policyAccess(): PolicyAccess

    /**
     * Makes sure Snoozemo's one long-lived rule exists, creating it on first
     * successful onboarding and reusing it forever after (SPEC.md §5.3).
     * Idempotent, and safe to call on every start — which is the point, since
     * the arm path must never be the thing that discovers the rule is missing.
     */
    fun ensureRule(): ZenRuleState

    /**
     * Turns Snoozemo's rule on or off.
     *
     * [trigger] is surfaced by the platform's Modes UI on API 35+, so the user
     * can tell "I did this" from "my phone did this" — pass what actually caused
     * it, not what is convenient.
     */
    fun setSnoozed(snoozed: Boolean, trigger: ZenTrigger, placeName: String): ZenOutcome
}

/** Whether the app may change zen state at all. */
enum class PolicyAccess {
    GRANTED,

    /**
     * Not granted, or revoked. Arming is impossible; a snooze already running
     * ends (SPEC.md §8.2) rather than staying armed on a rule it can no longer
     * drive.
     */
    DENIED,
}

/** What caused a zen state change, as reported to the platform. */
enum class ZenTrigger {
    /** The user tapped the tile, a notification action, or an in-app control. */
    USER_ACTION,

    /** The presence engine or the duration cap decided. */
    CONTEXT,
}

/** Whether the rule is there and usable. */
enum class ZenRuleState {
    /** The rule exists and its id is persisted. */
    READY,

    /** Cannot be created without notification-policy access. */
    MISSING_ACCESS,

    /**
     * The rule exists but the user switched it off in Settings or the Modes UI.
     * Snoozemo does **not** re-enable it: being able to disable the app's
     * influence without uninstalling it is the reason SPEC.md §5.1 chose an
     * `AutomaticZenRule` in the first place, so overriding that would defeat the
     * mechanism. The app says it can't snooze instead.
     */
    DISABLED,

    /** The platform refused to create it, for a reason the caller must surface. */
    FAILED,
}

/** The result of a zen state change. Never silently discarded. */
sealed interface ZenOutcome {

    /** The rule's state was set. */
    data object Applied : ZenOutcome

    /**
     * It was not. [reason] is what the user is told and what the debug log
     * records — an arm that failed must be visible, not assumed.
     */
    data class NotApplied(val reason: ZenFailure) : ZenOutcome
}

/** Why a zen state change didn't happen. */
enum class ZenFailure {
    /** Policy access is missing or was revoked. */
    NO_POLICY_ACCESS,

    /** The rule doesn't exist and couldn't be created. */
    NO_RULE,

    /**
     * The rule exists but is switched off in Settings, so setting its state
     * would change nothing the user can hear. Reported rather than worked
     * around — claiming "Snoozing" while the phone still rings is the failure
     * this whole interface is shaped to prevent.
     */
    RULE_DISABLED,

    /** The platform rejected the change. */
    PLATFORM_REFUSED,
}
