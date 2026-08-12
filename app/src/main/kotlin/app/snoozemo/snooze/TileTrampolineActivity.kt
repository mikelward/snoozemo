package app.snoozemo.snooze

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.snoozemo.core.EndReason

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
 * rendering. The end-condition sheet (§4.4) will live here in Phase 4 — which is
 * why this is a transparent activity rather than `Theme.NoDisplay`: a no-display
 * activity cannot host a sheet or a runtime permission request.
 */
class TileTrampolineActivity : ComponentActivity() {

    /**
     * Whether the permission dialog is currently in front of the user. This
     * activity is `singleInstance`, so further tile taps arrive at [onNewIntent]
     * while it is up rather than starting a new instance, and each of those has
     * to leave the dialog exactly as it found it.
     */
    private var awaitingPermission = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The snooze is already armed by now, and its ongoing notification
            // was posted while the permission was still denied — so the system
            // dropped it. Without this the whole first snooze runs with no
            // countdown and no End now, which on a 1×1 tile is no visible state
            // at all (SPEC.md §4.2). Asking the service to post it again is the
            // difference between the grant taking effect now and taking effect
            // next time.
            awaitingPermission = false
            if (granted) SnoozeService.refresh(this)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
    }

    /**
     * The second tap, while the first one's activity is still up.
     *
     * This activity is `singleInstance`, so a tile tap arriving while it is
     * alive — which it is for as long as the notification-permission dialog is
     * showing, and will be for the end-condition sheet in Phase 4 — is
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

    private fun dispatch(intent: Intent?) {
        // Whichever tap sent us here. Anything unrecognized arms, because the
        // only other way in is a launcher-less component nobody can reach: a
        // stray start that armed is recoverable, one that silently did nothing
        // would look like the tile is broken.
        val action = when (intent?.action) {
            SnoozeService.ACTION_END -> SnoozeService.ACTION_END
            SnoozeService.ACTION_EXTEND -> SnoozeService.ACTION_EXTEND
            SnoozeService.ACTION_RELEASE_STUCK -> SnoozeService.ACTION_RELEASE_STUCK
            else -> SnoozeService.ACTION_ARM
        }
        val arming = action == SnoozeService.ACTION_ARM
        // Contained, though an activity is on the documented exemption list for
        // starting a service — "documented" and "every OEM, every state of the
        // device" are not the same claim, and this is the app's only
        // interaction. A crash here is a tap that does nothing and takes the
        // trampoline down with it.
        val started = runCatching {
            startService(Intent(this, SnoozeService::class.java).setAction(action))
        }.onFailure {
            Log.e(TAG, "Starting the snooze service from the tile was refused.", it)
        }.getOrNull() != null
        if (!started) recoverFromRefusedStart(action)

        // Everything else is queued, not called. `startService` only *posts*
        // onStartCommand to this same main looper, so anything done
        // synchronously after that line runs *before* the arm rather than after
        // it — and that includes the decision about whether to ask, not just
        // the asking. `checkSelfPermission` and `isKeyguardLocked` are both
        // system calls, and neither has anything to do with this snooze; a slow
        // one would sit between the tap and `STATE_TRUE`. So does `finish`.
        // The arm keeps the thread; everything else takes what is left.
        window.decorView.post {
            // A tap that arrived while the user is mid-answer gets the service
            // action and nothing else: asking again would stack a second dialog
            // on the one in front of them, and finishing would dismiss it and
            // discard the answer they were giving.
            if (awaitingPermission) return@post
            // Only on the way in. A user ending a snooze is on their way out of
            // the app's way, and a permission dialog in front of that is the
            // opposite of "always available, always instant" (SPEC.md §7).
            if (arming && shouldAskForNotifications()) {
                awaitingPermission = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Nothing to render, so don't linger: an empty transparent
                // window on screen is the "flash of blank" §6.9 warns about.
                finish()
            }
        }
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
     * Two things keep it from being a nuisance. The system shows the dialog at
     * most twice and silently ignores later requests, so this stops asking by
     * itself. And it is skipped on the lock screen, where a permission dialog
     * cannot be answered and arming locked is a supported case (§4.2).
     */
    private fun shouldAskForNotifications(): Boolean {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED) {
            return false
        }
        return !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }
}
