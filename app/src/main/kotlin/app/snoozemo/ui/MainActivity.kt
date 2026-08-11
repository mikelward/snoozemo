package app.snoozemo.ui

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.PolicyAccessAction
import app.snoozemo.core.PolicyAccessChange
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleState
import app.snoozemo.core.ZenTrigger
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.dnd.PrefsZenRuleIdStore

/**
 * Phase 1's screen: enough to prove the DND half works on a real device before
 * anything is built on top of it (`TODO.md`). The product's real entry point is
 * the Quick Settings tile, which arrives in Phase 2 — this screen becomes
 * onboarding and settings.
 */
class MainActivity : ComponentActivity() {

    private lateinit var zen: ZenController
    private lateinit var snoozeFlag: DebugSnoozeFlag

    /**
     * Mirrors the persisted flag, because the platform rule outlives this
     * activity: after process death, in-memory state would say "not snoozing"
     * while the phone was still silent — and the screen would offer no way to
     * end it. Phase 2 replaces this with the real `ActiveSnooze` record.
     */
    private var snoozing by mutableStateOf(false)
    private var access by mutableStateOf(PolicyAccess.DENIED)
    private var lastOutcome by mutableStateOf<String?>(null)

    /**
     * The platform says only that policy access *changed*, for grants and
     * revocations alike, so read the current state rather than inferring a
     * direction (SPEC.md §5.2).
     */
    private val accessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        snoozeFlag = DebugSnoozeFlag(applicationContext)
        snoozing = snoozeFlag.isSnoozing()
        zen = AndroidZenController(
            context = applicationContext,
            store = PrefsZenRuleIdStore(applicationContext),
            configurationActivity = ComponentName(this, MainActivity::class.java),
        )
        setContent {
            SnoozemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DebugScreen(
                        access = access,
                        snoozing = snoozing,
                        lastOutcome = lastOutcome,
                        onGrantAccess = ::openPolicyAccessSettings,
                        onArm = { setSnoozed(true) },
                        onRelease = { setSnoozed(false) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            accessReceiver,
            IntentFilter(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED),
            RECEIVER_NOT_EXPORTED,
        )
        refreshAccess()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(accessReceiver)
    }

    private fun refreshAccess() {
        access = zen.policyAccess()
        when (PolicyAccessChange.resolve(access, snoozing)) {
            // The invariant from SPEC.md §8.2: access lost mid-snooze ends the
            // snooze rather than leaving the phone quiet with nothing to release
            // it. There is no rule left to drive, so the local state is corrected
            // and the reason surfaced.
            PolicyAccessAction.EndSnooze -> {
                snoozing = false
                snoozeFlag.set(false)
                lastOutcome = getString(R.string.debug_ended_access_lost)
            }
            // Not discarded: access granted but the rule uncreatable is a state
            // where the screen would otherwise look ready and only reveal the
            // problem when the user tried to snooze.
            PolicyAccessAction.EnsureRule -> when (zen.ensureRule()) {
                ZenRuleState.FAILED -> lastOutcome = getString(R.string.debug_rule_failed)
                // Switched off in Settings: the app cannot snooze and must say so
                // rather than looking ready. Snoozemo does not re-enable it —
                // that switch is the user's (SPEC.md §5.1).
                ZenRuleState.DISABLED -> lastOutcome = getString(R.string.debug_rule_disabled)
                ZenRuleState.READY, ZenRuleState.MISSING_ACCESS -> Unit
            }
            PolicyAccessAction.None -> Unit
        }
    }

    private fun setSnoozed(on: Boolean) {
        // Written before the rule is enabled, never after: a process death in
        // between must leave evidence that a snooze may be running, because the
        // recoverable mistake is believing we are snoozed when we aren't.
        if (on) snoozeFlag.set(true)
        val outcome = zen.setSnoozed(
            snoozed = on,
            trigger = ZenTrigger.USER_ACTION,
            placeName = ActiveSnooze.DEFAULT_PLACE_NAME,
        )
        lastOutcome = when (outcome) {
            is ZenOutcome.Applied -> {
                snoozing = on
                snoozeFlag.set(on)
                getString(if (on) R.string.debug_armed else R.string.debug_released)
            }
            // Reported, never assumed: a failed arm that looked like a success is
            // how someone ends up believing they are snoozed when they aren't.
            is ZenOutcome.NotApplied -> {
                // An arm that failed leaves nothing running, so clear the flag we
                // optimistically set. A failed *release* keeps it: the rule may
                // still be on.
                if (on) snoozeFlag.set(false)
                getString(R.string.debug_failed, outcome.reason.name)
            }
        }
    }

    private fun openPolicyAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
}

@Composable
fun SnoozemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@Composable
fun DebugScreen(
    access: PolicyAccess,
    snoozing: Boolean,
    lastOutcome: String?,
    onGrantAccess: () -> Unit,
    onArm: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(
                if (access == PolicyAccess.GRANTED) {
                    R.string.debug_access_granted
                } else {
                    R.string.debug_access_missing
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (access == PolicyAccess.DENIED) {
            Button(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.debug_grant_access))
            }
        } else {
            Button(
                onClick = onArm,
                enabled = !snoozing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.debug_arm))
            }
            // Deliberately always enabled, even when we believe nothing is
            // running. Manual exit is "always available, always instant"
            // (SPEC.md §7), and endSnooze is idempotent — so a stale belief must
            // never be what stops someone turning their phone back on.
            OutlinedButton(
                onClick = onRelease,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.debug_release))
            }
        }

        lastOutcome?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Preview
@Composable
private fun DebugScreenPreview() {
    SnoozemoTheme {
        DebugScreen(
            access = PolicyAccess.GRANTED,
            snoozing = false,
            lastOutcome = null,
            onGrantAccess = {},
            onArm = {},
            onRelease = {},
        )
    }
}
