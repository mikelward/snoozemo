package app.snoozemo.ui

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.Lifecycle
import app.snoozemo.R
import app.snoozemo.core.EndReason
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.PolicyAccessAction
import app.snoozemo.core.PolicyAccessChange
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenRuleState
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.dnd.PrefsZenRuleIdStore
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.SnoozeService
import app.snoozemo.snooze.releaseDirectly

private const val TAG = "MainActivity"

/**
 * Phase 1's screen: enough to prove the DND half works on a real device before
 * anything is built on top of it (`TODO.md`). The product's real entry point is
 * the Quick Settings tile, which arrives in Phase 2 — this screen becomes
 * onboarding and settings.
 */
class MainActivity : ComponentActivity() {

    private lateinit var zen: ZenController
    private lateinit var store: ActiveSnoozeStore
    private var recordWatch: AutoCloseable? = null

    /**
     * Read from the persisted record, never kept independently.
     *
     * The snooze belongs to `SnoozeService` — it owns the state machine, the cap
     * alarm, the notification, and the tile. A screen with its own idea of
     * whether one is running would drift out of step with all of them, and the
     * drift is not cosmetic: a release that only this screen knew about would
     * leave the record, the alarm, and the service intact, and the next sticky
     * recreation would re-assert the rule with no user action behind it.
     */
    private var snoozing by mutableStateOf<Boolean?>(null)
    /**
     * Null until the platform has been asked, for the same reason [snoozing] is:
     * reading it costs a binder round trip that must not sit in front of the
     * first frame, and neither default is safe to render in the meantime.
     * `DENIED` would flash `Snoozemo needs Do Not Disturb access` and a grant
     * button at a user who granted it months ago; `GRANTED` would offer to arm
     * something that cannot arm.
     */
    private var access by mutableStateOf<PolicyAccess?>(null)
    private var lastOutcome by mutableStateOf<String?>(null)

    /**
     * Bumped by every [refreshAccess]; only the newest reading may act or paint.
     *
     * Main thread only — bumped there, and checked there in [applyAccess],
     * which is what makes the check safe: the decision and the action it guards
     * run without anything able to bump this in between. The workers never read
     * it, so it needs no synchronization.
     */
    private var latestAccessRefresh = 0

    /** Whether [accessReceiver] is registered, since registering is deferred. */
    private var accessReceiverRegistered = false

    /** The same, for [refreshSnoozing]. Main thread only, for the same reason. */
    private var latestSnoozingRefresh = 0

    /**
     * The platform says only that policy access *changed*, for grants and
     * revocations alike, so read the current state rather than inferring a
     * direction (SPEC.md §5.2).
     */
    private val accessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshAccess()
    }

    /**
     * Notifications are how this app keeps its second promise — a degraded mode,
     * a failed release, or a snooze that ended for a reason the user didn't
     * choose is *said*, not left to be discovered. Declaring `POST_NOTIFICATIONS`
     * grants nothing since Android 13, so without this every one of those
     * messages is dropped by the system and the app fails silently by default.
     *
     * Asked here rather than on the arm path: a permission dialog in front of a
     * tile tap is exactly the wait the one-tap path exists to avoid, and the tile
     * still arms correctly without it.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Refused is a state the user should be able to see, not one that
            // silently costs them every later message. And a *grant* has work to
            // do: a snooze armed before this point had its ongoing notification
            // dropped by the system, so nothing on screen carries the countdown
            // or the way to end it. Asking the service to re-post is what puts
            // that back, rather than waiting for the next state change.
            if (granted) SnoozeService.refresh(this) else {
                lastOutcome = getString(R.string.debug_notifications_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ActiveSnoozeStore(applicationContext)
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
                        onArm = ::armFromScreen,
                        onRelease = ::endFromScreen,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        watchAccessAfterFirstFrame()
        askForNotificationsAfterFirstFrame()
        // The service can arm or end while this screen is up — the cap firing,
        // a tile tap, a notification action — so follow the record rather than
        // reading it once.
        refreshSnoozing()
        recordWatch = store.observe { refreshSnoozing() }
    }

    /**
     * Re-reads the record off the main thread and updates the screen with it.
     *
     * Off the main thread because the file may still be loading on a cold start
     * — the application warms it, but a launch fast enough to reach here first
     * would otherwise put a disk wait in front of the first frame. The screen
     * renders immediately with what it has and this fills it in a moment later
     * (`AGENTS.md`, jank-free UI). [snoozing] stays null until then, so nothing
     * on screen claims a state that hasn't been read yet.
     */
    private fun refreshSnoozing() {
        // The same generation guard the access refresh has, for the same
        // reason: `observe` fires one of these per record change, they finish
        // in whatever order the disk returns them, and a stale `true` landing
        // after a fresh `false` leaves `Arm` disabled over a snooze that has
        // already ended — with nothing to correct it until the next record
        // change. Bumped and checked on the main thread only.
        val refresh = ++latestSnoozingRefresh
        Thread {
            val running = store.load() != null
            runOnUiThread {
                if (refresh != latestSnoozingRefresh) return@runOnUiThread
                val changed = snoozing != running
                snoozing = running
                // Reconciling policy access reads whether a snooze is running,
                // so the pass in onStart ran before this was known. Re-run it
                // now that it is: access revoked while the service was dead has
                // to end the snooze (SPEC.md §8.2), and with the service gone
                // this screen can be the first thing in a position to notice.
                if (changed) refreshAccess()
            }
        }.start()
    }

    /**
     * Asks for `POST_NOTIFICATIONS` once the screen is actually on screen.
     *
     * `onStart` runs before the first draw, and launching the request there is
     * a binder call plus a system dialog in front of the content — the same
     * thing the record read and the policy reconciliation were moved off, and
     * more visible than either, since the user's first sight of Snoozemo would
     * be a permission dialog over a blank window.
     *
     * A frame callback and *then* a post: the frame callback runs as part of
     * drawing, so queuing from inside it lands on the looper after that frame
     * is done. The trampoline uses the plain post because it has no content to
     * protect — here there is.
     */
    private fun askForNotificationsAfterFirstFrame() {
        Choreographer.getInstance().postFrameCallback {
            window.decorView.post {
                // The *check* is deferred too, not just the dialog. It is a
                // `PackageManager` call, so leaving it in `onStart` put system
                // IPC ahead of the first draw for every cold launch — including
                // the common one where the permission is already granted and
                // nothing was going to be asked at all.
                //
                // The system shows the dialog twice at most and then silently
                // ignores repeat requests, so this costs nothing once the user
                // has answered.
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED) {
                    return@post
                }
                // The screen may have gone by the time this runs — a fast back
                // press, or a configuration change — and a request from a
                // stopped activity has nowhere to deliver its answer.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    /**
     * Starts watching policy access, once the screen is actually drawn.
     *
     * `registerReceiver` is a system-server call, and `onStart` runs before the
     * first draw — so doing it there put IPC in front of the first frame,
     * beside the permission check and the access read that were both already
     * moved off it for the same reason.
     *
     * Deferring widens the window where a change could go unseen, which is why
     * the read comes *after* the registration rather than instead of it:
     * whatever changed during the gap is picked up by that read, so the state
     * ends up correct either way. `refreshAccess` is itself off-thread, so this
     * adds nothing back to the frame.
     */
    private fun watchAccessAfterFirstFrame() {
        Choreographer.getInstance().postFrameCallback {
            window.decorView.post {
                // The screen may already be gone — a fast back press, or a
                // configuration change — and registering here would leak a
                // receiver `onStop` has finished looking for.
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@post
                // Contained, like the service's copy of this registration. It
                // runs from a posted callback, so a throw here is an unhandled
                // exception on the main looper — the screen would crash
                // immediately after drawing its first frame, over a watch that
                // is an optimization. Losing it means a revocation is noticed
                // on the next refresh instead of the moment it happens, which
                // is a degradation; crashing the only screen the user has is
                // not a lesser one.
                accessReceiverRegistered = runCatching {
                    registerReceiver(
                        accessReceiver,
                        IntentFilter(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED),
                        RECEIVER_NOT_EXPORTED,
                    )
                }.onFailure {
                    Log.e(TAG, "Registering the policy-access receiver failed; the screen still refreshes.", it)
                }.isSuccess
                // Either way, and deliberately outside the containment: the
                // read is the part that actually puts the current state on
                // screen, and it matters more when the watch is missing.
                refreshAccess()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Only what actually registered. The registration is deferred past the
        // first frame now, so a screen stopped before that ran never had a
        // receiver — and unregistering one the framework has no record of
        // throws.
        if (accessReceiverRegistered) {
            unregisterReceiver(accessReceiver)
            accessReceiverRegistered = false
        }
        recordWatch?.close()
        recordWatch = null
    }

    /**
     * Reads policy access off the main thread and hands the answer to
     * [applyAccess], which decides what to do with it.
     *
     * `isNotificationPolicyAccessGranted` is a binder round trip, and `onStart`
     * runs before the first draw, so doing it here would put IPC in front of
     * the first frame (`AGENTS.md`, jank-free UI). The screen renders with what
     * it has and this fills it in.
     *
     * The record read happens on the caller's thread, before the hop: it is a
     * memory hit by this point, and reading it inside the worker would race
     * whatever [refreshSnoozing] is doing.
     */
    private fun refreshAccess() {
        val running = snoozing == true
        // Which refresh this is. Several can be in flight at once — `onStart`,
        // the access broadcast, and every record change all call this — and
        // they finish in whatever order the binder calls return, not the order
        // they started. Without this, a slow `GRANTED` read can land *after* a
        // later revocation and paint the screen back to granted, re-enabling an
        // arm that cannot succeed. Only the newest result is allowed on screen.
        val refresh = ++latestAccessRefresh
        Thread {
            // Contained: a bare thread has no handler, so a refused binder read
            // would take the process down from a screen doing nothing but
            // reconciling. Nothing is the right outcome for a failed read — the
            // screen keeps whatever it last knew, and the next refresh asks
            // again.
            val current = runCatching { zen.policyAccess() }.getOrElse {
                Log.e(TAG, "Reading policy access failed; leaving the screen as it is.", it)
                return@Thread
            }
            runOnUiThread { applyAccess(refresh, current, running) }
        }.start()
    }

    /**
     * Decides what a policy-access reading means, and acts on it — both on the
     * main thread, deliberately.
     *
     * Checking the generation on the worker and *then* acting was still a
     * check-then-act: the worker could pass the check, be descheduled while a
     * newer refresh landed and the user armed again, and resume to end that new
     * snooze. `latestAccessRefresh` is written here, on this thread, so a check
     * made here cannot be overtaken before the action it guards — nothing else
     * can bump it in between.
     *
     * `end()` is a `startService`, which is cheap and belongs on this thread
     * anyway. The slow call — `ensureRule`, a lookup and possibly a creation —
     * goes back to a worker in [ensureRuleInBackground]; it is idempotent and
     * creates nothing a later refresh would have to undo, so it does not need
     * the same protection.
     *
     * The generation counter is per-instance, which is not enough on its own:
     * a configuration change replaces this activity while its workers keep
     * running, and their `latestAccessRefresh` — this instance's field, which
     * nothing bumps after it is destroyed — still matches. Such a worker can
     * carry a `DENIED` read taken before the change and land after access has
     * been granted and the replacement screen (or the tile) has armed a fresh
     * snooze, ending a snooze that has nothing wrong with it. So the lifecycle
     * is checked as well: a reading belonging to a screen that is no longer
     * on-screen is thrown away rather than acted on. Reading the record is
     * always safe; *acting* on a stale reading is not.
     *
     * One window is left and cannot be closed from here: a tile tap can arm a
     * snooze in another process between this decision and the service handling
     * it. The service is the owner and reconciles on arrival; this screen is
     * the belt-and-braces path for a revocation noticed while the service was
     * dead.
     */
    private fun applyAccess(refresh: Int, current: PolicyAccess, running: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (refresh != latestAccessRefresh) return
        access = current
        when (PolicyAccessChange.resolve(current, running)) {
            // The invariant from SPEC.md §8.2: access lost mid-snooze ends the
            // snooze rather than leaving the phone quiet with nothing to release
            // it. There is no rule left to drive, so the local state is
            // corrected and the reason surfaced.
            //
            // Routed through the service, which owns the record, the cap alarm,
            // the notification and the tile.
            //
            // And checked, not fired and forgotten: a refused start here would
            // leave the record, the cap, the tile and the notification all
            // intact while this screen reported the snooze ended — and a later
            // grant would then let a wake-up restore the snooze that supposedly
            // ended.
            // And nothing is claimed that hasn't been observed, on the same
            // rule [endFromScreen] follows: an accepted service start is a
            // delivered request, not a released rule, and the service says
            // what actually happened. Only a *refused* direct release is
            // reported here, because that outcome is known here.
            //
            // The user is not left without the actionable part either way —
            // `access` is set above from a reading that did happen, so the
            // screen already shows that Do Not Disturb access is gone.
            PolicyAccessAction.EndSnooze -> {
                if (!endThroughServiceOrDirectly(EndReason.LOST_CAPABILITY)) {
                    lastOutcome = getString(R.string.failure_could_not_end)
                }
            }
            PolicyAccessAction.EnsureRule -> ensureRuleInBackground(refresh)
            PolicyAccessAction.None -> Unit
        }
    }

    /**
     * Creates or checks the zen rule off the main thread, and reports only what
     * the user still needs to know.
     *
     * Not discarded when it fails: access granted but the rule uncreatable is a
     * state where the screen would otherwise look ready and only reveal the
     * problem when the user tried to snooze.
     */
    private fun ensureRuleInBackground(refresh: Int) {
        Thread {
            val state = runCatching { zen.ensureRule() }.getOrElse {
                Log.e(TAG, "Ensuring the zen rule failed; leaving the screen as it is.", it)
                return@Thread
            }
            val outcome = when (state) {
                ZenRuleState.FAILED -> R.string.debug_rule_failed
                // Switched off in Settings: the app cannot snooze and must say
                // so rather than looking ready. Snoozemo does not re-enable it
                // — that switch is the user's (SPEC.md §5.1).
                ZenRuleState.DISABLED -> R.string.debug_rule_disabled
                ZenRuleState.READY, ZenRuleState.MISSING_ACCESS -> return@Thread
            }
            runOnUiThread {
                // A rule lookup can take a while, so a refresh that was current
                // when this started may not be by the time it answers.
                if (refresh != latestAccessRefresh) return@runOnUiThread
                lastOutcome = getString(outcome)
            }
        }.start()
    }

    /**
     * Both buttons check whether the service actually started.
     *
     * `SnoozeService.arm`/`end` return false when the platform refuses the
     * start, and discarding that leaves a button that does nothing and explains
     * nothing. The same asymmetry as the tile trampoline applies: a refused arm
     * has nothing running behind it and only needs saying, while a refused end
     * has spent the user's exit on a snooze that is still going.
     */
    private fun armFromScreen() {
        if (SnoozeService.arm(this)) return
        Log.e(TAG, "Starting the service to arm was refused.")
        lastOutcome = getString(R.string.failure_could_not_start)
    }

    /**
     * Ends the snooze and reports only what this screen actually knows.
     *
     * The service branch reports **nothing**, deliberately. An accepted
     * `startService` says the request was delivered, not that the rule came
     * off — the release can still be refused inside the service, which then
     * keeps the record and posts its own `Couldn't end the snooze` — and a
     * screen claiming `Snooze ended` beside that notification is the app
     * contradicting itself about the user's own phone.
     *
     * The tap is not left looking inert: the record observer flips [snoozing]
     * when the release lands, so the buttons change, and the service is the
     * one surface that knows the outcome and already says it.
     *
     * The direct branch does report, because there the outcome is known here:
     * `releaseDirectly` returns whether the rule is confirmed off.
     */
    private fun endFromScreen() {
        if (!endThroughServiceOrDirectly(EndReason.MANUAL)) {
            lastOutcome = getString(R.string.failure_could_not_end)
        }
    }

    /**
     * Ends the snooze through the service, or without it if the start is
     * refused.
     *
     * Released here rather than merely reported, for the reason the tile
     * trampoline does the same: nothing else is scheduled to end this on the
     * user's behalf, and either the user has just asked for it explicitly or
     * policy access has gone and there is no rule left to drive.
     */
    private fun endThroughServiceOrDirectly(reason: EndReason): Boolean {
        if (SnoozeService.end(this)) return true
        Log.e(TAG, "Starting the service to end was refused; releasing without it.")
        return releaseDirectly(applicationContext, reason)
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
    access: PolicyAccess?,
    snoozing: Boolean?,
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
        // Nothing at all until access has been read, rather than a guess in
        // either direction: the wrong guess either tells a user who granted
        // access that they haven't, or offers to arm something that can't.
        access?.let {
            Text(
                text = stringResource(
                    if (it == PolicyAccess.GRANTED) {
                        R.string.debug_access_granted
                    } else {
                        R.string.debug_access_missing
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (access) {
            null -> Unit
            PolicyAccess.DENIED -> Button(
                onClick = onGrantAccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.debug_grant_access))
            }
            PolicyAccess.GRANTED -> {
                Button(
                    onClick = onArm,
                    // Disabled until the record has actually been read. Unknown
                    // is not "nothing is running": offering to arm over a snooze
                    // the screen hasn't read yet is how the user loses the
                    // deadline they were promised. A button that is briefly
                    // inert costs a tap; the other direction costs the cap.
                    enabled = snoozing == false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.debug_arm))
                }
                // Deliberately always enabled, even when we believe nothing is
                // running. Manual exit is "always available, always instant"
                // (SPEC.md §7), and endSnooze is idempotent — so a stale belief
                // must never be what stops someone turning their phone back on.
                OutlinedButton(
                    onClick = onRelease,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.debug_release))
                }
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
