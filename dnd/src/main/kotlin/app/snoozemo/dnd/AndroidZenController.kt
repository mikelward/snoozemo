package app.snoozemo.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleState
import app.snoozemo.core.ZenTrigger

/**
 * The only place in the app that touches `NotificationManager` or
 * `AutomaticZenRule` (SPEC.md §11).
 *
 * @param configurationActivity the settings screen the platform deep-links to
 *   from the rule in Settings and in the Modes UI. Passed in rather than
 *   referenced directly, so `:dnd` doesn't depend on `:app`.
 */
class AndroidZenController(
    private val context: Context,
    private val store: ZenRuleIdStore,
    private val configurationActivity: ComponentName,
) : ZenController {

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    override fun policyAccess(): PolicyAccess =
        if (notificationManager.isNotificationPolicyAccessGranted) {
            PolicyAccess.GRANTED
        } else {
            PolicyAccess.DENIED
        }

    override fun ensureRule(): ZenRuleState {
        if (policyAccess() == PolicyAccess.DENIED) return ZenRuleState.MISSING_ACCESS

        // A persisted id is only good while the platform still has the rule: the
        // user can delete it from Settings, and a restore or an app-data clear
        // can leave the id pointing at nothing. Verify rather than assume, or
        // the first arm after a deletion fails with the id looking fine.
        store.ruleId()?.let { existing ->
            val lookup = runCatching { notificationManager.getAutomaticZenRule(existing) }
            when {
                // Confirmed gone. Drop the id and make a new rule.
                lookup.isSuccess && lookup.getOrNull() == null -> store.clear()

                // Present, but switched off in Settings or the Modes UI. The
                // platform still accepts a state change on a disabled rule and
                // still reports success — while the phone goes on ringing. So
                // this must not read as READY, or the app would say "Snoozing"
                // over an audible phone, which is the worst lie it can tell.
                lookup.getOrNull()?.isEnabled == false -> return ZenRuleState.DISABLED

                lookup.isSuccess -> return ZenRuleState.READY

                // The lookup itself failed, which is NOT the same as the rule
                // being gone — and treating it as such is dangerous. Clearing the
                // id here would create a *second* rule, and a later release would
                // set STATE_FALSE on the replacement while the original stayed
                // active: a phone left quiet with no id left that can turn it
                // back off. Keep the id and report the failure instead.
                else -> {
                    Log.e(TAG, "Reading the existing zen rule failed; keeping its id.", lookup.exceptionOrNull())
                    return ZenRuleState.FAILED
                }
            }
        }

        return runCatching { notificationManager.addAutomaticZenRule(buildRule()) }
            .fold(
                onSuccess = { id ->
                    store.setRuleId(id)
                    ZenRuleState.READY
                },
                onFailure = { error ->
                    // Never swallowed: the caller turns this into something the
                    // user can see, because an app that can't create its rule
                    // can't snooze at all and must say so rather than appearing
                    // to work.
                    Log.e(TAG, "Creating the zen rule failed.", error)
                    ZenRuleState.FAILED
                },
            )
    }

    override fun setSnoozed(
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
    ): ZenOutcome {
        // Straight to the warmed id, with no checks in front of it. AGENTS.md's
        // arm-path rule is explicit that no NotificationManager policy IPC may
        // sit between the tap and the rule going STATE_TRUE, and a "is access
        // granted / does the rule still exist" preamble is exactly that: two
        // binder round-trips on the one path in the app that has to feel
        // instant. Onboarding and startup have already prepared this id
        // (`ensureRule`), so the common case needs no preparation here at all.
        val warmId = store.ruleId()
        if (warmId != null) {
            val applied = trySetState(warmId, snoozed, trigger, placeName)
            if (applied is ZenOutcome.Applied) return applied
        }

        // Only now — having already failed, or never having had an id — is it
        // worth paying for diagnosis. This path is slow and that is fine: it is
        // not the happy path, and the alternative is being fast and wrong.
        if (policyAccess() == PolicyAccess.DENIED) {
            return ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS)
        }
        when (ensureRule()) {
            ZenRuleState.READY -> Unit
            ZenRuleState.DISABLED -> return ZenOutcome.NotApplied(ZenFailure.RULE_DISABLED)
            ZenRuleState.MISSING_ACCESS -> return ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS)
            ZenRuleState.FAILED -> return ZenOutcome.NotApplied(ZenFailure.NO_RULE)
        }
        val ruleId = store.ruleId() ?: return ZenOutcome.NotApplied(ZenFailure.NO_RULE)
        // Retried rather than abandoned, because this runs on the *release* path
        // too, where giving up means leaving the phone silent.
        return trySetState(ruleId, snoozed, trigger, placeName)
    }

    private fun trySetState(
        ruleId: String,
        snoozed: Boolean,
        trigger: ZenTrigger,
        placeName: String,
    ): ZenOutcome {
        val state = if (snoozed) Condition.STATE_TRUE else Condition.STATE_FALSE
        val summary = if (snoozed) "Snoozing at $placeName" else "Left $placeName"
        val condition = if (Build.VERSION.SDK_INT >= 35) {
            Condition(CONDITION_URI, summary, state, trigger.toConditionSource())
        } else {
            Condition(CONDITION_URI, summary, state)
        }

        return runCatching { notificationManager.setAutomaticZenRuleState(ruleId, condition) }
            .fold(
                onSuccess = { ZenOutcome.Applied },
                onFailure = { error ->
                    // The release path's worst case: if this throws while ending a
                    // snooze, the phone stays silent. Report it so the caller can
                    // retry and tell the user, rather than assuming success.
                    Log.e(TAG, "Setting the zen rule state to $snoozed failed.", error)
                    ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)
                },
            )
    }

    private fun buildRule(): AutomaticZenRule =
        if (Build.VERSION.SDK_INT >= 35) {
            AutomaticZenRule.Builder(ZenRule.NAME, CONDITION_URI)
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(defaultPolicy())
                .setConfigurationActivity(configurationActivity)
                .setTriggerDescription(TRIGGER_DESCRIPTION)
                .setManualInvocationAllowed(true)
                .setEnabled(true)
                .build()
        } else {
            // API 33–34. `owner` may be null provided configurationActivity is
            // set; a ConditionProviderService has not been needed since API 29,
            // when setAutomaticZenRuleState replaced it, and is deprecated
            // (SPEC.md §5.3).
            @Suppress("DEPRECATION")
            AutomaticZenRule(
                ZenRule.NAME,
                null,
                configurationActivity,
                CONDITION_URI,
                defaultPolicy(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                true,
            )
        }

    /**
     * The shape most people already expect from DND, and one that keeps a
     * genuine emergency reachable (SPEC.md §5.5). Total silence is available in
     * settings but is deliberately not the default: defaulting a
     * location-triggered mechanism to "nothing gets through" is how someone
     * misses something that mattered.
     */
    private fun defaultPolicy(): ZenPolicy = ZenPolicy.Builder()
        .allowAlarms(true)
        .allowMedia(true)
        .allowSystem(true)
        .allowRepeatCallers(true)
        .build()

    private fun ZenTrigger.toConditionSource(): Int = when (this) {
        ZenTrigger.USER_ACTION -> Condition.SOURCE_USER_ACTION
        ZenTrigger.CONTEXT -> Condition.SOURCE_CONTEXT
    }

    private companion object {
        const val TAG = "ZenController"
        val CONDITION_URI: Uri = Uri.parse(ZenRule.CONDITION_ID)
        const val TRIGGER_DESCRIPTION = "While you're at a place you snoozed"
    }
}
