package app.snoozemo.ui

import android.Manifest
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import app.snoozemo.R
import app.snoozemo.core.EndReason
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.PolicyAccessAction
import app.snoozemo.core.PolicyAccessChange
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenRuleState
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.NotificationPromptStore
import app.snoozemo.snooze.SnoozeNotifications
import app.snoozemo.snooze.SnoozeService
import app.snoozemo.snooze.releaseDirectly
import app.snoozemo.tile.SnoozeTileService
import app.snoozemo.tile.TilePresenceStore
import app.snoozemo.tile.R as TileR

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
    private lateinit var promptStore: NotificationPromptStore
    private lateinit var tileStore: TilePresenceStore
    private var recordWatch: AutoCloseable? = null
    private var tileWatch: AutoCloseable? = null

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

    /**
     * Null for the same reason, and read at the same point: the check is a
     * `PackageManager` call, so it waits until after the first frame like
     * everything else here. Denied is not a safe default either — it would tell
     * a user whose notifications work that they don't.
     */
    private var notifications by mutableStateOf<NotificationPermission?>(null)

    /**
     * Whether a posted message would actually reach the shade — the permission
     * held *and* the app and both channels not silenced in Settings.
     *
     * Defaults to true so the row never accuses the platform before it has
     * looked; the row it feeds is hidden until [notifications] has been read
     * anyway.
     */
    private var notificationsReachTheUser by mutableStateOf(true)

    /**
     * Whether the tile is known to be in Quick Settings.
     *
     * Defaults to **true** so the row does not flash on every launch before the
     * store has been read: the tile is normally there, and offering to add
     * something the user already has is the one wrong answer that costs them a
     * dialog. Read alongside everything else after the first frame.
     */
    private var tileAdded by mutableStateOf<Boolean?>(null)

    /**
     * Whether the tile banner has been sent away for good.
     *
     * Defaults to **dismissed** so the loud thing cannot flash on a screen that
     * has not finished reading yet. A boolean is enough where [tileAdded] needs
     * a third state: there is only one thing to suppress here, while the row has
     * two states to tell apart.
     */
    private var tileBannerDismissed by mutableStateOf(true)
    private var lastOutcome by mutableStateOf<String?>(null)

    /**
     * Which row's Settings trip was refused, if either was.
     *
     * Held per row rather than as one message at the foot of the screen: the
     * column scrolls, so a failure appended below the buttons can be off screen
     * exactly when the user is looking at the row they just tapped.
     */
    private var settingsFailure by mutableStateOf<SetupRowId?>(null)

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
            // Whatever the answer, the row states it — refused is a state the
            // user should be able to see standing there, not a message that
            // scrolls past once. Re-read rather than inferred from `granted`,
            // because a *denial* also moves the row: the second one is what
            // takes the system's prompt away, and the row has to stop offering
            // one and point at Settings instead.
            refreshNotifications()
            // A grant has work to do beyond the row: a snooze armed before this
            // point had its ongoing notification dropped by the system, so
            // nothing on screen carries the countdown or the way to end it.
            // Asking the service to re-post is what puts that back, rather than
            // waiting for the next state change.
            if (granted) SnoozeService.refresh(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window is drawn edge to edge whether or not we ask for it —
        // Android 15 made that the behavior for every app targeting SDK 35 and
        // up, and Android 16 dropped the opt-out — so the only real choice is
        // between handling the insets and drawing the title underneath the
        // status bar. Declared here as well as handled in the layout, because
        // this is also what makes the bars transparent and picks the icon
        // contrast to sit on top of them, and it has to run before the first
        // frame.
        //
        // The default `auto` bar styling is right for this app, unlike the
        // sibling Simmo repo, which passes an explicit style: `auto` reads the
        // system's day/night setting, and `SnoozemoTheme` follows that same
        // setting rather than a per-app Light/Dark choice, so the icons cannot
        // end up contrasting against a background the app didn't draw.
        //
        // Cheap enough to sit in front of the record read below: it is window
        // flags and an insets-controller call, no disk and no binder.
        enableEdgeToEdge()
        store = ActiveSnoozeStore(applicationContext)
        promptStore = NotificationPromptStore(applicationContext)
        tileStore = TilePresenceStore(applicationContext)
        zen = AndroidZenController.default(applicationContext)
        setContent {
            SnoozemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DebugScreen(
                        access = access,
                        notifications = notifications,
                        snoozing = snoozing,
                        lastOutcome = lastOutcome,
                        notificationsReachTheUser = notificationsReachTheUser,
                        tileAdded = tileAdded,
                        tileBannerDismissed = tileBannerDismissed,
                        settingsFailure = settingsFailure,
                        onAccessRow = ::openPolicyAccessSettings,
                        onNotificationsRow = ::fixNotifications,
                        onTileRow = ::addTile,
                        onDismissTileBanner = { tileStore.dismissBanner() },
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
        readNotificationsAfterFirstFrame()
        // The service can arm or end while this screen is up — the cap firing,
        // a tile tap, a notification action — so follow the record rather than
        // reading it once.
        refreshSnoozing()
        recordWatch = store.observe { refreshSnoozing() }
        // Followed rather than read once. The tile service writes this when the
        // tile is added or removed from the shade, and the add-request's answer
        // arrives after a system dialog that can outlive the activity which
        // opened it — a configuration change mid-dialog leaves the replacement
        // with a stale reading and nothing to correct it.
        tileWatch = tileStore.observe {
            tileAdded = tileStore.isAdded()
            tileBannerDismissed = tileStore.isBannerDismissed()
        }
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
     * Reads the notification permission once the screen is actually on screen.
     *
     * Reading only — this screen no longer launches the request by itself. It
     * used to, on every start, which turned the new row against the user: tap
     * the granted row, switch notifications off in Settings, come back, and the
     * screen would immediately ask to turn them back on, over a choice they had
     * just made deliberately (flagged by Codex on PR #18). The row is the better
     * affordance anyway — it says `Tap to allow` and stays saying it, where a
     * dialog fires once and is gone.
     *
     * The tile trampoline still asks on its own, and must: a user who added the
     * tile from the Quick Settings editor may never open this screen, and for
     * them a denied permission means an armed snooze with no visible state
     * anywhere (SPEC.md §4.2).
     *
     * Deferred past the first frame because the read is a `PackageManager` call
     * plus two binder calls for the channels, and `onStart` runs before the
     * first draw. A frame callback and *then* a post: the frame callback runs
     * as part of drawing, so queuing from inside it lands on the looper after
     * that frame is done.
     *
     * It runs on every start rather than once, because the user can change any
     * of this from Settings while the screen is in the background and the row
     * would otherwise still say `Allowed`.
     */
    private fun readNotificationsAfterFirstFrame() {
        Choreographer.getInstance().postFrameCallback {
                window.decorView.post {
                refreshNotifications()
                // Read here rather than in `onStart` for the same reason as the
                // rest: it is a preferences file, and no disk read belongs in
                // front of the first frame.
                tileAdded = tileStore.isAdded()
                tileBannerDismissed = tileStore.isBannerDismissed()
            }
        }
    }

    /**
     * Re-reads the notification permission onto the screen, and returns what it
     * read so the caller can act on the same answer.
     *
     * On the main thread, unlike the policy-access and record reads beside it:
     * this one has no binder round trip in it — `checkSelfPermission` is a
     * local package-manager cache hit and the rest is a preferences read the
     * application has already warmed — and every caller is either already
     * past the first frame or reacting to a dialog the user just answered.
     */
    private fun refreshNotifications(): NotificationPermission {
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED
        val rationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        // Recorded on the way past, because this reading is the only place the
        // platform admits a denial landed — `rationale` is true only between
        // the first denial and the permanent one, and by the time the row needs
        // to know, it is false again.
        promptStore.record(granted = granted, rationale = rationale)
        // Read here rather than in the composable: both are binder calls, and
        // this whole method already runs after the first frame.
        // Constructed rather than called statically: building it runs the
        // channel creation, so an absent channel afterwards means the creation
        // was refused rather than merely not attempted yet.
        notificationsReachTheUser = SnoozeNotifications(applicationContext).canReachTheUser()
        val current = NotificationPermission.of(
            granted = granted,
            everDenied = promptStore.everDenied(),
            rationale = rationale,
        )
        notifications = current
        // Same as the access row: once messages actually reach the user this
        // row has no button left, so a failure recorded before that would have
        // nothing to clear it. Working means *delivered*, not merely granted —
        // the permission can be held while a channel is switched off, which is
        // a state the row still offers to fix.
        if (current == NotificationPermission.GRANTED && notificationsReachTheUser) {
            clearFailure(SetupRowId.NOTIFICATIONS)
        }
        return current
    }

    /** Drops [row]'s failure message, if that is the one currently shown. */
    private fun clearFailure(row: SetupRowId) {
        if (settingsFailure == row) settingsFailure = null
    }

    /**
     * What tapping the notifications row does — which is never nothing.
     *
     * The row is the repair surface for the permission that carries every
     * message this app promises to send, so a tap that silently achieves
     * nothing is principle 2's failure on the screen that exists to prevent it.
     * [NotificationPermission] is what makes the choice: there is a prompt to
     * show, or there isn't and Settings is the only route left.
     *
     * A stale reading can't send the tap to the wrong place — a row showing
     * `ASKABLE` over a permission that has since been granted launches a
     * request the system answers immediately and harmlessly — but it is re-read
     * first anyway, since the row is being acted on and the read is cheap.
     */
    private fun fixNotifications() {
        when (refreshNotifications()) {
            NotificationPermission.ASKABLE -> askForNotifications()
            // Granted, or asked for as often as the system allows. Either way
            // the toggle the user needs is the app's own notification settings.
            NotificationPermission.GRANTED, NotificationPermission.BLOCKED ->
                openSettings(
                    SetupRowId.NOTIFICATIONS,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
        }
    }

    /**
     * Launches the runtime request — only ever from a tap on the row.
     *
     * Nothing is recorded here, deliberately: launching is not spending. The
     * system counts explicit denials, not requests, so a dialog the user swipes
     * away leaves the prompt available — and the flag that says otherwise is
     * written by [refreshNotifications], from the one reading that can tell.
     */
    private fun askForNotifications() {
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        tileWatch?.close()
        tileWatch = null
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
        // A failure describes a tap that could not reach Settings, and the row
        // carrying it loses its button the moment access is granted — so
        // nothing would clear it, and the row would read `Granted` above
        // `Couldn't open Settings` until the activity was recreated. Access can
        // arrive from anywhere while this screen is up: another route into
        // Settings, an administrator, the receiver above (flagged by Codex on
        // PR #21).
        if (current == PolicyAccess.GRANTED) clearFailure(SetupRowId.DND)
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

    /**
     * Do Not Disturb access, whichever way it is currently set.
     *
     * Not a runtime permission, which is half of why the old shape was
     * confusing: there is no in-app dialog and no result callback, so the user
     * leaves for Settings, flips a toggle, and comes back (SPEC.md §5.2). What
     * this screen owns is noticing the return — `accessReceiver` and the
     * `onStart` refresh both do — so nothing here waits for an answer.
     */
    /**
     * Offers to put the tile in Quick Settings.
     *
     * `requestAddTileService` is the only sanctioned route — there is no way to
     * add a tile without the user's consent, and no way to *ask* whether it is
     * already there. The platform answers this one request, and that answer is
     * the third and last place the tile's presence is knowable, so it is
     * recorded like the other two.
     *
     * `TILE_ALREADY_ADDED` is a success for our purposes: the user has it, the
     * row was wrong, and the row should go. Everything else leaves the row where
     * it is, since the tile still isn't there.
     *
     * Contained because this is a binder call into the system UI, and the
     * failure this screen must never have is a row that describes something and
     * does nothing when tapped.
     */
    private fun addTile() {
        val manager = getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            Log.e(TAG, "No StatusBarManager; cannot offer to add the tile.")
            settingsFailure = SetupRowId.TILE
            return
        }
        // Cleared on the way in, not only on success. A failure describes the
        // *last* attempt, and the next tap supersedes it whatever it returns —
        // otherwise an error from a rapid double-tap
        // (`TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS`) would still be on
        // screen after the dialog it collided with came back declined, which is
        // not an error at all (flagged by Codex on PR #20).
        clearFailure(SetupRowId.TILE)
        runCatching {
            manager.requestAddTileService(
                ComponentName(this, SnoozeTileService::class.java),
                getString(TileR.string.tile_snooze_here),
                Icon.createWithResource(this, TileR.drawable.ic_tile_snooze),
                mainExecutor,
            ) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                if (added) {
                    // The store only — the watch above puts it on screen, and
                    // does so on whichever activity is current rather than the
                    // one this callback closed over.
                    tileStore.setAdded(true)
                } else if (result >= StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE) {
                    // An error, as against the user simply declining. Declining
                    // is an answer and needs no message; a refused *request* is
                    // a tap that achieved nothing and has to say so.
                    Log.e(TAG, "The system refused the add-tile request (result $result).")
                    settingsFailure = SetupRowId.TILE
                }
            }
        }.onFailure {
            Log.e(TAG, "Requesting the tile be added was refused.", it)
            settingsFailure = SetupRowId.TILE
        }
    }

    private fun openPolicyAccessSettings() {
        openSettings(SetupRowId.DND, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    /**
     * Leaves for a Settings screen, and says so when it can't.
     *
     * Contained because `startActivity` throws when nothing resolves the
     * intent, and both of these are optional in principle — an OEM build or a
     * restricted profile without the screen would otherwise crash the app from
     * a tap on a row that describes a problem. Swallowing it is worse than the
     * crash in one specific way: the row would then be the dead tap this whole
     * change exists to remove, so the failure gets said.
     */
    private fun openSettings(row: SetupRowId, intent: Intent) {
        runCatching { startActivity(intent) }
            // Cleared on success as well as set on failure: a refusal that has
            // since stopped happening must not keep an error under a row that
            // now works.
            .onSuccess { clearFailure(row) }
            .onFailure {
                Log.e(TAG, "Opening a Settings screen was refused.", it)
                settingsFailure = row
            }
    }
}

@Composable
fun SnoozemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** Which setup row a failure belongs beside. */
enum class SetupRowId {
    DND,
    NOTIFICATIONS,
    TILE,
}

@Composable
fun DebugScreen(
    access: PolicyAccess?,
    notifications: NotificationPermission?,
    notificationsReachTheUser: Boolean,
    tileAdded: Boolean?,
    tileBannerDismissed: Boolean,
    snoozing: Boolean?,
    lastOutcome: String?,
    settingsFailure: SetupRowId?,
    onAccessRow: () -> Unit,
    onNotificationsRow: () -> Unit,
    onTileRow: () -> Unit,
    onDismissTileBanner: () -> Unit,
    onArm: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Outside the scroll, so the whole column — not just its resting
            // position — stays clear of the status bar, the navigation bar and
            // any display cutout. `safeDrawing` rather than `systemBars`
            // because a cutout on a rotated phone takes a side inset that the
            // bars alone do not describe, and a title half under a cutout is
            // the same defect as one under the status bar. Inside the scroll it
            // would only pad the content, leaving a row to slide under the
            // status bar as soon as the user scrolled.
            .safeDrawingPadding()
            // Scrolls, and this is not cosmetic. Two three-line rows, a title
            // and two buttons overflow a landscape window or a large font
            // scale, and an unscrolled `Column` clips its later children — the
            // last of which is `End snooze` (flagged by Codex on PR #18).
            // Manual exit is "always available, always instant" (SPEC.md §7),
            // and a user who cannot reach it because their font is large has
            // lost the exit from this screen entirely.
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        // Above everything, and louder than the rows, because the screen leads
        // with the tile rather than offering a symmetrical choice (SPEC.md
        // §4.2): the tile is easier and is where people already go to silence a
        // phone. Dismissible — a banner that cannot be dismissed is an argument
        // the user cannot end — and dismissed for good, which only works
        // because the row below it is permanent.
        if (tileAdded == false && !tileBannerDismissed) {
            TileBanner(onAdd = onTileRow, onDismiss = onDismissTileBanner)
        }
        // Nothing at all until access has been read, rather than a guess in
        // either direction: the wrong guess either tells a user who granted
        // access that they haven't, or offers to arm something that can't.
        access?.let {
            val granted = it == PolicyAccess.GRANTED
            SetupRow(
                title = stringResource(R.string.setup_dnd_title),
                status = stringResource(
                    if (granted) R.string.setup_dnd_granted else R.string.setup_dnd_missing,
                ),
                // `Grant` rather than `Allow`: this one is a Settings toggle
                // with no in-app dialog and no result callback (SPEC.md §5.2),
                // and the pair of verbs is what is left of saying so now that
                // the row no longer spends a line on the mechanism.
                //
                // Absent once it is granted, which is the whole of what the row
                // then is: a statement that this is done. A button offering to
                // grant what is already granted is a tap with nothing behind
                // it, and turning access back off is Settings' job — the user
                // goes there deliberately or not at all.
                action = stringResource(R.string.setup_action_grant).takeUnless { granted },
                onAction = onAccessRow,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.DND },
            )
        }
        // Same discipline, same reason: unread is not "denied". Read after the
        // first frame like everything else here, so this is briefly absent
        // rather than briefly wrong.
        notifications?.let {
            // Granted is necessary and not sufficient. The permission can be
            // held while the app is switched off in Settings or either channel
            // is blocked, and the system then drops every post — so the row may
            // only say `Allowed`, and only drop its button, when a message
            // would actually arrive (flagged by Codex on PR #18).
            val working = it == NotificationPermission.GRANTED && notificationsReachTheUser
            SetupRow(
                title = stringResource(R.string.setup_notifications_title),
                status = stringResource(
                    if (working) {
                        R.string.setup_notifications_granted
                    } else {
                        R.string.setup_notifications_missing
                    },
                ),
                // One verb for all three broken states, deliberately. What the
                // tap does differs — `ASKABLE` is a prompt in place, and the
                // other two leave for Settings, because the system has stopped
                // showing that prompt or the permission is held and something
                // else is dropping the post — but what the user wants is the
                // same in each, and naming the route was what made the old row
                // read as a description instead of an offer.
                action = stringResource(R.string.setup_action_allow).takeUnless { working },
                onAction = onNotificationsRow,
                failure = stringResource(R.string.failure_could_not_open_settings)
                    .takeIf { settingsFailure == SetupRowId.NOTIFICATIONS },
            )
        }
        // Only while it is missing. The tile is the product (SPEC.md §4.2), so
        // a user without it has an app whose whole interaction is out of reach
        // — but once it is there this row is clutter on the one screen there
        // is, and the platform's own answer to a redundant request is a dialog
        // saying it is already added.
        // Permanent, and null until the store has answered. Permanent because it
        // is what makes the banner's forever-dismissal safe (SPEC.md §4.2) — the
        // banner can be sent away for good precisely because this outlives it —
        // and null-until-read because a permanent row with a default would
        // assert `Added` on the first frame of a cold launch and then correct
        // itself, which is the one lie this screen is least entitled to tell.
        tileAdded?.let { added ->
            SetupRow(
                title = stringResource(R.string.setup_tile_title),
                status = stringResource(
                    if (added) R.string.setup_tile_added else R.string.setup_tile_missing,
                ),
                // Absent once the tile is there: nothing is left to create, and
                // the platform's own answer to a redundant request is a dialog
                // saying it is already added. The row becomes a statement.
                action = stringResource(R.string.setup_action_add).takeUnless { added },
                onAction = onTileRow,
                failure = stringResource(R.string.failure_could_not_add_tile)
                    .takeIf { settingsFailure == SetupRowId.TILE },
            )
        }

        if (access == PolicyAccess.GRANTED) {
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

        lastOutcome?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * One capability: what it is, how it stands, and — only while something is
 * actually left to do — the button that fixes it.
 *
 * The trailing-button shape is the sibling Simmo repo's `GrantRow`, and the two
 * halves of it are load-bearing separately.
 *
 * **A verb, not a route.** The action line used to describe the mechanism —
 * `Opens Settings`, `Tap to add` — which reads as a note about what will happen
 * rather than an offer to do it, and made a granted row look like it still
 * wanted something. `Grant`, `Allow` and `Add` say what the user gets.
 *
 * **Nothing to tap once it is done.** [action] is null when the capability is
 * in place, and the row is then a statement: title and status, no control. A
 * button that only re-opens a screen the user has already finished with is a
 * tap with nothing behind it, and it keeps first-run urgency on a screen where
 * everything is already fine.
 *
 * That is a deliberate trade against the shape this row had before, where the
 * whole surface was clickable so the sentence naming the problem was itself the
 * target (`TODO.md` Phase 2). One target per row is what TalkBack can announce
 * unambiguously — a button nested inside a clickable row gives the same tap two
 * different names — so the row keeps the single target and moves it onto the
 * control that says what it does.
 */
/**
 * The screen's one piece of advocacy: add the tile.
 *
 * Deliberately not a [SetupRow]. The rows state a fact about a capability and
 * offer the repair; this makes a case, and looking different is the point —
 * `SPEC.md` §4.2 asks the screen to push toward the tile rather than list it as
 * one option among equals. It is the only element here that says *why*.
 *
 * Two actions, weighted: adding is the filled button, dismissing is a text one.
 */
@Composable
private fun TileBanner(
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tile_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.tile_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.tile_banner_dismiss))
                }
                Button(onClick = onAdd) {
                    Text(stringResource(R.string.tile_banner_add))
                }
            }
        }
    }
}

@Composable
private fun SetupRow(
    title: String,
    status: String,
    action: String?,
    onAction: () -> Unit,
    failure: String? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            // The same gap the sibling repo keeps between a label block and its
            // trailing control: the text takes whatever width the button
            // leaves, so a status long enough to wrap would otherwise end flush
            // against the button and read as one object with it.
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = status, style = MaterialTheme.typography.bodyMedium)
                // Inside the row, not at the foot of the screen. A tap that
                // could not open Settings has to say so where the tap was: the
                // column scrolls, so a message appended below the buttons is
                // off screen in landscape or at a large font scale, and the row
                // would read as the dead tap this screen exists to remove
                // (flagged by Codex on PR #18).
                //
                // And only beside an offer. The message is about a tap, so a
                // row with nothing left to tap has nothing to report — showing
                // it there would put `Couldn't open Settings` under `Granted`,
                // which is the screen contradicting itself about the user's own
                // phone. The state is cleared as well, in the two places that
                // learn the capability recovered; this is what keeps the two
                // from disagreeing in the frame between.
                failure?.takeIf { action != null }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            action?.let {
                Button(onClick = onAction) { Text(it) }
            }
        }
    }
}

@Preview
@Composable
private fun DebugScreenPreview() {
    SnoozemoTheme {
        DebugScreen(
            access = PolicyAccess.GRANTED,
            notifications = NotificationPermission.GRANTED,
            notificationsReachTheUser = true,
            tileAdded = true,
            tileBannerDismissed = true,
            snoozing = false,
            lastOutcome = null,
            settingsFailure = null,
            onAccessRow = {},
            onNotificationsRow = {},
            onTileRow = {},
            onDismissTileBanner = {},
            onArm = {},
            onRelease = {},
        )
    }
}
