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
     *
     * [snooze] names the snooze this call is for, so that what the ringer
     * records for it — the ceiling it started with — is never mistaken for a
     * later snooze's (SPEC.md §5.9 rule 2). A release may pass null; an arm or
     * a re-assertion passes the record's [identity].
     */
    fun setSnoozed(
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
        snooze: SnoozeIdentity? = null,
    ): ZenOutcome

    /**
     * The id of the rule [ensureRule] has already prepared, if it has.
     *
     * A memory read, not a binder call, so it is safe to call from the main
     * thread once the id has been warmed — the same discipline the arm path's
     * own id read follows. Null means only "not known yet" or "no rule
     * exists"; a caller that needs to tell those apart, or that needs the id
     * confirmed against the platform, calls [ensureRule] instead.
     */
    fun ruleId(): String?

    /**
     * What Snoozemo's rule is currently doing (SPEC.md §5.8).
     *
     * The broadcast in §5.8 is the timely answer to "did the user turn Do Not
     * Disturb off"; this is the reliable one. A receiver can fail to register,
     * and the process only lives between wake-ups, so a snooze can outlive the
     * only thing watching for it — this re-asks on every wake-up instead, which
     * turns that failure into *late* rather than *never*.
     *
     * The four-value answer matters: [ZenRuleActivation.UNKNOWN] must never end
     * a snooze, and [ZenRuleActivation.MISSING] is a lost capability rather than
     * the user's choice.
     */
    fun ruleActivation(): ZenRuleActivation

    /**
     * Whether [ruleId] is Snoozemo's own rule (SPEC.md §5.8).
     *
     * The gate on every status broadcast, because the platform reports changes
     * to *all* rules: §5.6's "only its own rule" has to hold for reading as well
     * as writing, or another app's mode ending would end our snooze.
     *
     * Answers **false when it cannot tell** — an unreadable id means we have no
     * evidence this rule is ours, and the safe reading of that is to leave a
     * running snooze alone rather than end it on a guess.
     */
    fun ownsRule(ruleId: String?): Boolean
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
    ;

    /**
     * Whether this failure means there is no rule left holding the phone quiet.
     *
     * The distinction decides what a *failed release* does, and it is the
     * difference between a snooze that can still be retried and one that has
     * already effectively ended:
     *
     * - **True** — the rule is gone, was never created, or is switched off, so
     *   nothing is silencing the phone on Snoozemo's behalf and there is nothing
     *   left to turn off. Revoking policy access is the case that matters: the
     *   platform deletes the app's rules, so every retry would fail the same way
     *   forever and the record would strand the app claiming `Snoozing` over a
     *   phone that is already ringing.
     * - **False** — the platform refused a change to a rule that still exists,
     *   which may well work on the next try. Here the record *is* the retry
     *   mechanism and must be kept (SPEC.md §7).
     */
    val nothingLeftToRelease: Boolean
        get() = when (this) {
            NO_POLICY_ACCESS, NO_RULE, RULE_DISABLED -> true
            PLATFORM_REFUSED -> false
        }
}

/**
 * Whether this outcome means nothing of Snoozemo's is silencing the phone.
 *
 * The question a *failed release* turns on, and the same one
 * [SnoozeController], `SnoozeService` and `CapAlarm` already ask inline — named
 * here so the answers cannot drift apart. They finalize the snooze on it, so
 * anything that keeps per-snooze state must let go on exactly the same reading
 * or it holds state for a snooze the rest of the app has ended (Codex, PR #176:
 * the ringer's ceiling record was doing that on a revoked-access release).
 */
val ZenOutcome.confirmsNothingSilencing: Boolean
    get() = this !is ZenOutcome.NotApplied || reason.nothingLeftToRelease

