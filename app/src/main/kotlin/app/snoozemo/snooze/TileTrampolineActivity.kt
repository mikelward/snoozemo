package app.snoozemo.snooze

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.snoozemo.core.EndReason
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.tileTapNeedsSetup
import app.snoozemo.ui.EXTRA_BLOCKED_TAP_ID
import app.snoozemo.ui.EXTRA_OPEN_PERMISSIONS
import app.snoozemo.ui.EXTRA_TILE_CHOOSER
import app.snoozemo.ui.MainActivity
import java.util.UUID

private const val TAG = "TileTrampoline"

/**
 * The invisible activity **both** tile taps go through (SPEC.md §6.9).
 *
 * `TileService.onClick` is not on the documented list of exemptions for starting
 * a service from the background; activities are. In practice a direct start
 * usually works, but "usually" is not a design for the app's only interaction —
 * and it is no design at all for `End now`, where a refusal would leave the
 * phone quiet with the user's own exit spent.
 *
 * It starts the service **first, before any UI**, so arming never waits on
 * rendering. It is transparent rather than `Theme.NoDisplay` because it still
 * has to host the runtime notification-permission dialog, which a no-display
 * activity cannot; most taps draw nothing at all and finish as soon as the
 * service start is queued.
 *
 * **Declared `showWhenLocked`** (the manifest) so a locked tap actually arms.
 * Both taps reach here through `startActivityAndCollapse`, and a *secured*
 * keyguard holds an ordinary activity launch behind unlock — so without the flag
 * the tile lit up but nothing silenced until the user authenticated, which is
 * §4.2's instant locked arm not landing (confirmed on a Pixel). Showing over the
 * keyguard lets the arm run at once. It does not weaken the lock: the guardrail is
 * sensitivity, not the verb (SPEC.md §4.2). Anything sensitive — the debug log,
 * system settings, meeting/place details, rule config — lives on a screen reached
 * through `MainActivity` (not this), which does not inherit the flag and still waits
 * for unlock; the notification prompt is likewise skipped while locked
 * ([shouldAskForNotifications]). Starting, stopping and modifying a snooze are not
 * sensitive, so the ongoing notification's `End now`, `+30 min` and `Until <time>`
 * reach here over the lock too — each only flips the zen rule or the cap and shows
 * nothing, the same latitude the volume keys and the shade's DND toggle already have.
 *
 * The one arm tap that does not start the service is the chooser: with
 * "ask when to unsnooze" on ([EndSheetStore]), an arm opens the main screen as
 * the end-condition chooser instead, and the user's row tap there is what arms
 * (SPEC.md §4.4). That branch never touches the service — see [dispatch].
 */
class TileTrampolineActivity : ComponentActivity() {

    /**
     * Whether the permission dialog is currently in front of the user. This
     * activity is `singleInstance`, so further tile taps arrive at [onNewIntent]
     * while it is up rather than starting a new instance, and each of those has
     * to leave the dialog exactly as it found it.
     */
    private var awaitingPermission = false

    /**
     * Lazy so the preferences file is opened only where the permission is
     * actually being considered. Everything here runs after the service start,
     * but an end or an extend has no reason to pay for a disk read at all.
     */
    private val promptStore by lazy { NotificationPromptStore(this) }

    /**
     * Whether the tap being handled got the service started at all.
     *
     * The notification-permission request still runs on a refused start, since
     * that card is exactly what the permission makes visible; this only records
     * whether the start was accepted, for the debug log line.
     */
    private var startAccepted = false

    /**
     * Whether a posted [decide] is still owed an answer.
     *
     * Survives a configuration change because the runnable does not: a
     * recreation before the posted decision ran left the replacement with a
     * started service and nothing left to do, so it never asked for the
     * notification permission the arm needed (Codex, PR #118). [restore] re-posts
     * it from this flag.
     */
    private var decisionPending = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Recorded here rather than before the request, and this is the
            // only place the tile touches the store: the snooze is already
            // armed by the time an answer arrives, so neither the disk read nor
            // the write is anywhere near the zen rule. A tile-first user may
            // never open the app screen, so a denial only the tile witnessed
            // still has to be one the screen knows about.
            promptStore.record(
                granted = granted,
                rationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
            )
            // The snooze is already armed by now, and its ongoing notification
            // was posted while the permission was still denied — so the system
            // dropped it. Without this the whole first snooze runs with no
            // countdown and no End now, which on a 1×1 tile is no visible state
            // at all (SPEC.md §4.2). Asking the service to post it again is the
            // difference between the grant taking effect now and taking effect
            // next time.
            awaitingPermission = false
            if (granted) SnoozeService.refresh(this)
            // Only the arm path launches this, but by the time it answers the
            // arm may no longer be what the user last asked for: this activity
            // is `singleInstance`, so a tap arriving while the dialog is up
            // reaches `onNewIntent`, which dispatches it and calls `setIntent`.
            // Asking the *current* intent rather than assuming an arm is what
            // keeps an `End now` tap from being routed as if it were still the
            // arm that opened the dialog (Codex, PR #118).
            val stillArming = serviceActionFor(intent) == SnoozeService.ACTION_ARM
            // Re-asked after the answer, because granting notifications does
            // not grant the other half: a user who has just allowed the prompt
            // while Do Not Disturb access is still missing has a snooze that
            // did not happen and now no dialog left to explain it. Otherwise
            // there is nothing more to show — the card the grant just made
            // visible is the whole answer — so get out of the way.
            if (stillArming && tapNeedsSetup()) openApp() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A non-null bundle is **not** enough to know this was a rotation. This
        // activity is `singleInstance`, so a process Android killed while the
        // sheet sat in the background is restored by the *next tile tap*, and
        // that creation carries saved state too — treating it as a
        // configuration change would restore the old sheet and swallow the tap
        // entirely (Codex, PR #118). On `End now` that is the user's only exit
        // before the notification permission is granted (SPEC.md §4.2).
        //
        // A **retained** object is what actually separates them, and
        // [RecreationMarker] is one: Android hands a `ViewModel` to the
        // replacement activity only down the configuration-relaunch path, and
        // clears the store on any other destroy. Finding one that has already
        // been through `onCreate` therefore means a configuration change and
        // nothing else.
        //
        // `onSaveInstanceState` cannot answer this. `ActivityThread` saves
        // state only when none is saved yet, so an activity Android had
        // already stopped keeps the bundle it wrote *then* — before any
        // configuration change was known — and a sheet backgrounded and then
        // rotated would come back carrying a stale "not a rotation" and be
        // re-dispatched, arming a second time (Codex, PR #118).
        //
        // The bundle is required alongside the marker only because there is
        // nothing to restore without one. A relaunch always carries state, so
        // the pairing should hold; where it somehow didn't, falling through to
        // `dispatch` is the safe side — a second arm is an annoyance, and a
        // swallowed `End now` leaves the phone quiet with the user's only exit
        // spent.
        //
        // Ordered so a fresh tap never touches the marker before the service
        // is away (SPEC.md §6.9): a tap carries no bundle, so the left half
        // short-circuits and `dispatch` runs first. The marker is only read
        // where there is state to restore, and only *written* below.
        val savedState = savedInstanceState
        if (savedState != null && marker().created) {
            // A rotation, not a tap. Re-dispatching here would send the tile's
            // action to the service a *second* time — arming again because the
            // user turned the phone. [restore] only re-posts a decision the
            // rotation interrupted; it never re-dispatches.
            restore(savedState)
        } else {
            // Either a fresh tap or a tap that restored a killed process, and
            // both are dispatched fresh: a process restore has no live in-flight
            // work to pick up, so it is treated as any other tap.
            dispatch(intent)
            // After the service, for the reason above. The next creation only
            // needs the marker to exist by the time it looks.
            marker().created = true
        }
    }

    /** The recreation marker for this activity; see [RecreationMarker]. */
    private fun marker(): RecreationMarker =
        ViewModelProvider(this)[RecreationMarker::class.java]

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DECISION_PENDING, decisionPending)
        outState.putBoolean(STATE_START_ACCEPTED, startAccepted)
        outState.putBoolean(STATE_AWAITING_PERMISSION, awaitingPermission)
    }

    /**
     * Queues [decide] behind the arm, and is the only way it is ever reached.
     *
     * Separate from [dispatch] because a configuration change can land between
     * the two: the block belongs to the activity that posted it, so a
     * replacement built before it ran inherited a started service and nothing
     * left to do — the notification-permission ask the arm needed never
     * happened (Codex, PR #118). [restore] posts it again rather than
     * re-dispatching, which would arm a second time.
     */
    private fun postDecision(arming: Boolean) {
        decisionPending = true
        window.decorView.post { decide(arming) }
    }

    /**
     * What happens once the arm is away: ask for notifications, route to setup,
     * or get out of the way. Never called directly — see [postDecision].
     *
     * This is the instant-arm path: the chooser branch in [dispatch] has already
     * peeled off the choose-then-arm case, so an arm reaching here has been made
     * and the only questions left are whether to surface a permission the
     * tile-first user needs.
     */
    private fun decide(arming: Boolean) {
        decisionPending = false
        // A tap that arrived while the user is mid-answer gets the service
        // action and nothing else: asking again would stack a second dialog
        // on the one in front of them, and finishing would dismiss it and
        // discard the answer they were giving. This also covers a rotation
        // *during* the dialog, where the platform redelivers the result to the
        // replacement's own launcher and this must not pre-empt it.
        if (awaitingPermission) return
        // Only on the way in. A user ending a snooze is on their way out of
        // the app's way, and a permission dialog in front of that is the
        // opposite of "always available, always instant" (SPEC.md §7).
        val askFirst = arming && shouldAskForNotifications()
        // A tap that cannot produce a snooze opens the app instead of leaving
        // the user with nothing (SPEC.md §4.1). Only where a prompt is not the
        // better answer: [shouldAskForNotifications] fixes an askable
        // permission in one tap, and a screen would be a detour past it.
        val needsSetup = arming && !askFirst && tapNeedsSetup()
        if (askFirst) {
            awaitingPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (needsSetup) {
            openApp()
        } else {
            // Nothing to render, so don't linger: an empty transparent
            // window on screen is the "flash of blank" §6.9 warns about.
            // An ordinary arm gets out of the way exactly as it did before, and
            // ending or extending never lingers either — a user on their way out
            // of the app's way is the last person to hold a window in front of.
            finish()
        }
    }

    private fun restore(state: Bundle) {
        startAccepted = state.getBoolean(STATE_START_ACCEPTED)
        awaitingPermission = state.getBoolean(STATE_AWAITING_PERMISSION)
        // Owed a decision if the arm's [postDecision] had not run before the
        // rotation. Posted again against the same intent and *without* touching
        // the service, which is already handling that action — re-dispatching
        // would arm a second time. Where nothing is owed (the ordinary case:
        // the decision ran and this window is only still up for the permission
        // dialog) this does nothing and the dialog stays as it was.
        if (state.getBoolean(STATE_DECISION_PENDING)) {
            postDecision(serviceActionFor(intent) == SnoozeService.ACTION_ARM)
        }
    }

    /**
     * The second tap, while the first one's activity is still up.
     *
     * This activity is `singleInstance`, so a tile tap arriving while it is
     * alive — which it is for as long as the notification-permission dialog is
     * showing — is
     * delivered here instead of creating another instance. Without this the tap
     * does nothing at all: on the arm path that reads as a broken tile, and on
     * the end path it is worse, because before the notification permission is
     * granted the tile is the *only* way out of a running snooze (SPEC.md §4.2)
     * and the user's exit would be swallowed by a dialog they hadn't answered.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // So anything reading `this.intent` later — including a re-entry into
        // the permission callback — sees the tap that is actually current.
        setIntent(intent)
        dispatch(intent)
    }

    /**
     * Which service action [intent] means.
     *
     * Anything unrecognized arms, because the only other way in is a
     * launcher-less component nobody can reach: a stray start that armed is
     * recoverable, one that silently did nothing would look like the tile is
     * broken.
     *
     * Extracted so the permission callback can ask the same question of the
     * *current* intent rather than assuming the tap that opened the dialog is
     * still the one being answered.
     */
    private fun serviceActionFor(intent: Intent?): String = when (intent?.action) {
        SnoozeService.ACTION_END -> SnoozeService.ACTION_END
        SnoozeService.ACTION_EXTEND -> SnoozeService.ACTION_EXTEND
        SnoozeService.ACTION_RELEASE_STUCK -> SnoozeService.ACTION_RELEASE_STUCK
        SnoozeService.ACTION_SET_CAP -> SnoozeService.ACTION_SET_CAP
        else -> SnoozeService.ACTION_ARM
    }

    private fun dispatch(intent: Intent?) {
        // Whichever tap sent us here.
        val action = serviceActionFor(intent)
        val arming = action == SnoozeService.ACTION_ARM
        // **Choose-then-arm: with the chooser on, an arm tap opens the main
        // screen instead of arming** (SPEC.md §4.4). Nothing is armed here —
        // the user's row tap on that screen is what starts the snooze, and
        // finishes it back to where they were ([EXTRA_TILE_CHOOSER]). This
        // replaces the old arm-first-then-sheet: the sheet refined a snooze
        // already running, which is not what "ask when to unsnooze" should mean.
        //
        // The chooser flag is read from memory, never disk — [EndSheetStore]'s
        // warmed cache — so this stays off the arm path (§6.9): a tap that
        // overtakes the warm-up reads `false` and arms instantly below, the
        // arm-preserving fallback.
        //
        // **No keyguard check here, deliberately** (Codex, PR #284). Knowing
        // whether to arm-instead-when-locked would need `isKeyguardLocked`, a
        // system call, before `startService` — exactly what §6.9 keeps off the
        // arm path. So an unlocked chooser and a locked one are not told apart:
        // the chooser opens either way, showing after the user unlocks. The
        // instant locked arm (§4.2) stays the *off* default, where this branch
        // never runs; a user who has opted into being asked cannot be asked
        // behind the keyguard, and unlocking to answer is inherent to that.
        if (arming && EndSheetStore.cachedEnabled()) {
            SnoozeDebugLog.event("tap: arm from the tile, opening the chooser")
            openChooser()
            return
        }
        // Contained, though an activity is on the documented exemption list for
        // starting a service — "documented" and "every OEM, every state of the
        // device" are not the same claim, and this is the app's only
        // interaction. A crash here is a tap that does nothing and takes the
        // trampoline down with it.
        val started = runCatching {
            startService(
                Intent(this, SnoozeService::class.java).setAction(action).also { forward ->
                    // The notification's `Until <time>` action is the one tap
                    // that carries data — the time it offered and the snooze it
                    // offered it for — and the service declines a claim that no
                    // longer matches. Copied by name rather than wholesale so a
                    // start can never smuggle an extra this activity has not
                    // considered into the service.
                    if (action != SnoozeService.ACTION_SET_CAP) return@also
                    forward.putExtra(
                        SnoozeService.EXTRA_CAP_EXPIRES_AT,
                        intent?.getLongExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, 0L) ?: 0L,
                    )
                    forward.putExtra(
                        SnoozeService.EXTRA_CHOICE_FOR_SNOOZE,
                        intent?.getLongExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, 0L) ?: 0L,
                    )
                },
            )
        }.onFailure {
            Log.e(TAG, "Starting the snooze service from the tile was refused.", it)
        }.getOrNull() != null
        startAccepted = started

        // **After the start, before the recovery** (Codex, PR #238, both
        // halves). This activity is the door for both the tile and the shade,
        // so without this line a capture cannot tell an intended `End now` from
        // a tile tap that toggled a state the user thought was something else
        // (maintainer, device capture 2026-09-09) — but nothing goes between
        // the tap and `startService`, which is the rule the block below states
        // for everything else here and the reason arming feels instant. Logged
        // for every action, since the same ambiguity applies to an arm the user
        // read as a second tap on something else, and it carries whether the
        // start was accepted: a refused one is already the difference between a
        // tap that did nothing and a tap that did the wrong thing.
        //
        // The other bound is `recoverFromRefusedStart` below, which for
        // `ACTION_END` and `ACTION_RELEASE_STUCK` releases the snooze here and
        // now and writes its own `no-service release` line doing it. Recovering
        // first put that ending *above* the tap that asked for it, in the one
        // log whose whole job is establishing that order.
        //
        // Synchronous on purpose, and affordable: `DebugFileSink.log` is a
        // compare-and-set plus a debounced hand-off to the sink's own executor,
        // so no disk and no IPC touch this thread — the same reasoning
        // `SnoozeService.onStateChanged` records for the `ARMING` delivery,
        // which sits closer to the rule than this does. Posting it off the
        // looper would buy nothing and cost the ordering above, since a line
        // whose position is non-deterministic cannot establish which came
        // first (Codex, PR #238; declined with the reasoning on the thread).
        SnoozeDebugLog.event(
            "tap: $action from " +
                (intent?.getStringExtra(SnoozeService.EXTRA_REQUESTED_FROM) ?: "the tile") +
                if (started) "" else " (the service refused to start)",
        )
        if (!started) recoverFromRefusedStart(action)

        // Everything else is queued, not called. `startService` does not *run*
        // the service — it is a binder round trip into `ActivityManagerService`,
        // which comes back through a oneway `IApplicationThread` callback that
        // posts `onStartCommand` to this looper from a binder thread. So work
        // done synchronously after that line certainly runs *before* the arm,
        // and that includes the decision about whether to ask, not just the
        // asking. What it does *not* buy is the converse: nothing orders the
        // arm ahead of the posted block either (see `thereIsAChoice`). `checkSelfPermission` and `isKeyguardLocked` are both
        // system calls, and neither has anything to do with this snooze; a slow
        // one would sit between the tap and `STATE_TRUE`. So does `finish`.
        // The arm keeps the thread; everything else takes what is left.
        postDecision(arming)
    }

    private companion object {
        const val STATE_DECISION_PENDING = "decision_pending"
        const val STATE_START_ACCEPTED = "start_accepted"
        const val STATE_AWAITING_PERMISSION = "awaiting_permission"
    }

    /**
     * The tap had nowhere to go, so this is what happens instead.
     *
     * The two directions are not symmetric. A refused **arm** leaves nothing
     * running: say it didn't work, and remember why, so a later notification
     * grant can still explain a tap that appeared to do nothing.
     *
     * A refused **end** is the one with something at stake — the snooze is
     * still running and the user has just spent their exit on it. So the
     * release happens here, in the activity, using the same no-service path the
     * cap alarm's receiver falls back to. That means binder calls and a
     * preferences write on the main thread, which is exactly what the arm path
     * exists to avoid; it is worth it only because the alternative is a phone
     * that stays quiet after the user explicitly asked it not to, and because
     * this runs only when the service has already refused to start.
     */
    private fun recoverFromRefusedStart(action: String) {
        when (action) {
            // Nothing is running and nothing was extended — but the user tapped
            // something and is owed an answer. `+30 min` has no recovery beyond
            // saying so: the snooze and its cap are exactly as they were, so
            // there is nothing stranded, and the tap can simply be repeated.
            SnoozeService.ACTION_EXTEND ->
                SnoozeNotifications(applicationContext).showCouldNotExtend()
            // Same shape as `+30 min`, opposite direction: the cap stands where
            // it was, so nothing is stranded and the tap can be repeated — but a
            // button that silently kept the old deadline is the app quietly
            // ignoring the user (AGENTS.md, principle 2).
            SnoozeService.ACTION_SET_CAP ->
                SnoozeNotifications(applicationContext).showCouldNotSetEnd()
            // The last exit refusing to start is the one case with nothing
            // behind it at all — no record, no alarm, and the notification the
            // user just tapped is the only thing that was pointing at this
            // rule. So the release happens here, and `releaseDirectly` re-posts
            // that notification itself if the rule still will not come off.
            SnoozeService.ACTION_END, SnoozeService.ACTION_RELEASE_STUCK ->
                releaseDirectly(applicationContext, EndReason.MANUAL)
            else -> {
                PendingFailureStore(this).remember(ArmFailure.BelowZen)
                SnoozeNotifications(applicationContext).showCouldNotArm()
            }
        }
    }

    /**
     * Whether to put the notification-permission dialog in front of the user
     * here, on the arm path, rather than leaving it to the app screen.
     *
     * The tile can be added straight from the Quick Settings editor, so a user
     * may arm many times without ever opening Snoozemo — and for that user the
     * app screen's request never runs. That would be survivable if the tile
     * carried the status, but it doesn't: it runs 1×1, icon-only (§4.2), so a
     * denied notification permission leaves an armed snooze with **no visible
     * state anywhere** and a failed arm with no explanation. This is the one
     * place the tile-first path passes through.
     *
     * Two things keep it from being a nuisance. The system shows the dialog
     * only until it has been denied twice and silently ignores every request
     * after that, so this stops mattering by itself. And it is skipped on the
     * lock screen, where a permission dialog cannot be answered and arming
     * locked is a supported case (§4.2).
     *
     * Deliberately *not* the three-way decision the app screen makes. Telling
     * "denied once, still promptable" from "the system has stopped asking"
     * needs `shouldShowRequestPermissionRationale` or a preferences read, and
     * nothing between the tap and the zen rule going on may be either (flagged
     * by Codex on PR #18). The screen answers that question where it is free
     * to; here the cost of not knowing is a request the system drops.
     */
    private fun shouldAskForNotifications(): Boolean {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED) {
            return false
        }
        return !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    /**
     * Whether this tap landed on an app that can do nothing at all, so the user
     * should be shown why rather than left with a tile that did nothing.
     *
     * The decision itself is [tileTapNeedsSetup], in `:core` where its cases are
     * enumerated by a JVM test; everything here is reading the three inputs.
     *
     * **Skipped on the lock screen**, the same exclusion
     * [shouldAskForNotifications] makes and for a stronger reason: a screen
     * started from behind the keyguard is not seen now, and surfaces later with
     * no connection to the tap that caused it — which is worse than the silent
     * failure it was meant to explain. Arming locked is a supported case
     * (§4.2), and there the ongoing notification is the only report available.
     *
     * Every read here is after the service start, so none of it is between the
     * tap and the zen rule (§4.1, §6.9).
     */
    private fun tapNeedsSetup(): Boolean {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val access = runCatching {
            if (getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
                PolicyAccess.GRANTED
            } else {
                PolicyAccess.DENIED
            }
        }.getOrElse {
            // Unread, not missing: routing on a failed read would open the app
            // over a question nothing answered.
            Log.w(TAG, "Reading policy access failed; treating it as unread.", it)
            null
        }
        val notifications = NotificationPermission.of(
            granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED,
            everDenied = promptStore.everDenied(),
            rationale = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        )
        return tileTapNeedsSetup(
            access = access,
            notifications = notifications,
            activeChannelEnabled = activeChannelEnabled(getSystemService(NotificationManager::class.java)),
        )
    }


    /**
     * Opens the setup screen, which carries the rows that repair whatever
     * [tapNeedsSetup] found, and gets this transparent window out of the way.
     *
     * The destination is explicit rather than left to the app to work out
     * (Codex, PR #215). `MainActivity` routes to it on its own only for missing
     * Do Not Disturb access, and the main screen carries no notification row at
     * all — so a plain launch answered a tap blocked by notifications with the
     * ordinary arm screen and nothing saying why, which is the silence this
     * gate exists to end.
     */
    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_PERMISSIONS, true)
                    // An identity for *this* tap (Codex, PR #220). The extra
                    // sticks to the activity's launch intent, so Android
                    // re-delivers it verbatim when it rebuilds a task whose
                    // process it killed — and the app, inferring "is this a new
                    // tap?" from whether it had a saved bundle or a live
                    // `ViewModel`, kept getting that question wrong in one
                    // direction or the other. A tap that says which tap it is
                    // can be consumed exactly once, whatever the platform does
                    // with the intent afterwards.
                    .putExtra(EXTRA_BLOCKED_TAP_ID, UUID.randomUUID().toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure {
            // Nothing else to try: the snooze has already been attempted and
            // the report it would have made is exactly what is missing. Logged
            // so a user who does have the debug log can see the tap was not
            // ignored.
            Log.e(TAG, "Could not open the app to repair a tile tap.", it)
            SnoozeDebugLog.failure(it, "tile tap needed setup and the app would not open")
        }
        finish()
    }

    /**
     * Opens the main screen as the end-condition chooser (SPEC.md §4.4), and
     * gets this transparent window out of the way.
     *
     * [EXTRA_TILE_CHOOSER] is what tells the screen this launch came from the
     * tile, so a row that arms finishes it and collapses back to where the user
     * was — the tile's one-tap feel across two taps. Nothing is armed here; the
     * screen's own arm path handles Do Not Disturb access, notifications and
     * location, showing why on the spot rather than in a notification the
     * tile-first user may have denied.
     */
    private fun openChooser() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_TILE_CHOOSER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure {
            // The chooser could not open, and the Ask-on branch deliberately did
            // not arm — so without a fallback the tap does nothing and says
            // nothing, principle 2's silent failure. Fall back to the instant
            // arm, the Ask-off behavior: a user who tapped the tile to snooze
            // gets the snooze (on its default cap, still endable on departure)
            // rather than nothing when the app cannot be shown to ask (SPEC.md
            // §4.1). The failure is logged either way, and if the fallback arm
            // is itself refused there is genuinely nothing left to try.
            Log.e(TAG, "Could not open the app as the end-condition chooser; arming instead.", it)
            SnoozeDebugLog.failure(it, "tile tap opening the chooser and the app would not open; arming instead")
            // Through the same start-and-recover the Ask-off instant arm uses,
            // not a bare `startService`: a refused fallback arm must remember the
            // failure and show "could not arm" — the notification a later grant
            // can still explain — rather than only logging, principle 2 (Codex,
            // PR #284). The notification-permission *ask* is not part of the
            // fallback: the app window just failed to open, so there is nowhere
            // to host that dialog; the refusal recovery is what matters here.
            val started = runCatching {
                startService(
                    Intent(this, SnoozeService::class.java).setAction(SnoozeService.ACTION_ARM),
                )
            }.onFailure { armFailure ->
                Log.e(TAG, "Falling back to an instant arm after the chooser would not open also failed.", armFailure)
                SnoozeDebugLog.failure(armFailure, "tile tap fell back to an instant arm and the service would not start")
            }.getOrNull() != null
            if (!started) recoverFromRefusedStart(SnoozeService.ACTION_ARM)
        }
        finish()
    }
}

/**
 * Survives a configuration change and nothing else, which is exactly the
 * question [TileTrampolineActivity.onCreate] has to answer.
 *
 * Android retains an activity's `ViewModelStore` only when it is being torn
 * down to be recreated with a new configuration, and clears it on every other
 * destroy — so a marker whose [created] is already `true` can only have come
 * from a recreation in this same process. A process death leaves a fresh one.
 */
internal class RecreationMarker : ViewModel() {
    /** Set by the first `onCreate` to see this marker; read by the next one. */
    var created: Boolean = false
}
