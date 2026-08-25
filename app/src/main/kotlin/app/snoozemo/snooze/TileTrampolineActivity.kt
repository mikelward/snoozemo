package app.snoozemo.snooze

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.EndCondition
import app.snoozemo.core.EndReason
import app.snoozemo.presence.PRESENCE_TRACKS_DEPARTURE
import app.snoozemo.ui.EndConditionSheetContent
import app.snoozemo.ui.SnoozemoTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Date

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
 * rendering. The end-condition sheet (§4.4) lives here — which is why this is a
 * transparent activity rather than `Theme.NoDisplay`: a no-display activity
 * cannot host a sheet or a runtime permission request. The sheet is off by
 * default ([EndSheetStore]), so on most installs this activity still draws
 * nothing at all and finishes as soon as the service start is queued.
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
     * Lazy for the same reason [promptStore] is, and read in the same place:
     * from the posted block, after the service start. Off by default, so most
     * taps never open this file at all — the read happens only where the sheet
     * is actually being considered.
     */
    private val endSheetStore by lazy { EndSheetStore(this) }

    /**
     * The end-condition sheet's own state, or null while no sheet is up.
     *
     * Held here rather than inside the composable because a second tile tap
     * arrives at [onNewIntent] in this same instance and re-seeds it — the arm
     * it triggered is a *new* snooze, so the time the sheet is offering has to
     * move with it rather than still naming an hour from the first tap.
     */
    @VisibleForTesting
    internal var endCondition by mutableStateOf<EndCondition?>(null)

    /**
     * Whether the service refused the time the user just chose. Shown in the
     * sheet rather than dismissing, because a dismissal on a refused tap is
     * indistinguishable from one on an accepted tap.
     */
    private var commitFailed by mutableStateOf(false)

    /** Whether [setContent] has already run, so a re-seed doesn't re-set it. */
    private var sheetRendered = false

    /**
     * Whether the tap being handled got the service started at all.
     *
     * Necessary but **not sufficient** for the sheet — see [snoozeIsRunning].
     * The notification-permission request still runs on a refused start, since
     * that card is exactly what the permission makes visible.
     */
    private var startAccepted = false

    /** Committing choices while this is set; see [commit]. */
    private var outcomeWatch: AutoCloseable? = null

    /**
     * Whether a posted [decide] is still owed an answer.
     *
     * Survives a configuration change because the runnable does not: a second
     * tile tap reaching `onNewIntent` while a sheet is already up posts a fresh
     * decision, and a recreation before it ran left the replacement rendering
     * the *old* sheet and never asking again — a stale sheet over a snooze the
     * new tap had ended, or a kept selection where a new arm should have
     * reseeded (Codex, PR #118). `sheetRendered` cannot answer this; it says
     * what was drawn, not what is owed.
     */
    private var decisionPending = false

    /**
     * Whether a chosen time is with the service and unanswered. The rows are
     * inert while it is, so a second tap can't stack a second commit on the
     * first (and the sheet is about to dismiss on the usual path anyway).
     *
     * `internal` for the same reason [endCondition] is: the Robolectric test
     * has no Compose rule to drive the row with, and a commit in flight across
     * a rotation is a state worth covering.
     */
    internal var committing by mutableStateOf(false)

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
            // stops an `End now` tap being answered with a sheet offering to
            // extend the snooze they just asked to leave — reachable whenever
            // the release is refused, since the record then survives (Codex,
            // PR #118).
            //
            // Where the arm is still current and took, the sheet is what comes
            // next (SPEC.md §4.4): finishing here would mean a user who answered
            // the dialog never got offered a time, while one who was never asked
            // did. Where it didn't, the card the grant has just made visible is
            // the whole answer.
            val stillArming = serviceActionFor(intent) == SnoozeService.ACTION_ARM
            // One reading for the gate and the seed alike, as in [decide], and
            // not [decide] itself: the permission has just been answered, so
            // re-running its ask branch would put the dialog straight back up.
            val now = Instant.ofEpochMilli(System.currentTimeMillis())
            if (shouldOfferSheet(stillArming, now)) showEndConditionSheet(now) else finish()
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
            // user turned the phone — and would reseed the sheet, throwing away
            // the time they had already stepped to.
            restore(savedState)
        } else {
            // Either a fresh tap or a tap that restored a killed process. The
            // sheet's saved state is deliberately *not* restored on the second:
            // its in-flight commit, if any, was answered into a process that no
            // longer exists, so waiting on it would wait forever. This tap gets
            // the same treatment as any other.
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
        outState.putBoolean(STATE_SHEET_SHOWN, sheetRendered)
        outState.putBoolean(STATE_COMMITTING, committing)
        outState.putBoolean(STATE_COMMIT_FAILED, commitFailed)
        endCondition?.let {
            outState.putLong(STATE_ENDS_AT, it.endsAt.toEpochMilli())
            outState.putLong(STATE_FLOOR, it.floor.toEpochMilli())
            outState.putLong(STATE_CEILING, it.ceiling.toEpochMilli())
        }
    }

    /**
     * Picks the sheet back up after a configuration change, exactly where it
     * was — the chosen time included, since stepping to it is the only work the
     * user has done here and reseeding would silently undo it.
     *
     * A commit that was in flight is picked back up too. The watch went with the
     * old activity, so the service may have answered while nothing was
     * listening; [EndChoiceOutcome.takePending] is that answer, and a fresh
     * watch covers the case where it hasn't come yet.
     */
    /**
     * Queues [decide] behind the arm, and is the only way it is ever reached.
     *
     * Separate from [dispatch] because a configuration change can land between
     * the two: the block belongs to the activity that posted it, so a
     * replacement built before it ran inherited a started service, no sheet,
     * and nothing left to render or finish — a transparent window blank for as
     * long as the user left it there (Codex, PR #118). [restore] posts it again
     * rather than re-dispatching, which would arm a second time.
     */
    private fun postDecision(arming: Boolean) {
        decisionPending = true
        window.decorView.post { decide(arming) }
    }

    /**
     * What happens once the arm is away: ask, offer the sheet, or get out of
     * the way. Never called directly — see [postDecision].
     */
    private fun decide(arming: Boolean) {
        decisionPending = false
        // One reading, handed to the gate and the seed alike. Two would let a
        // cap sitting just outside the floor pass the gate and then be seeded
        // against a clock that has moved past it, opening a sheet whose every
        // row the service refuses and whose reseed reproduces the same dead
        // offer (Codex, PR #118). The window is vanishing, and a screen the
        // user cannot answer is precisely what the gate exists to prevent.
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
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
        if (arming && shouldAskForNotifications()) {
            awaitingPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (shouldOfferSheet(arming, now)) {
            showEndConditionSheet(now)
        } else {
            // Nothing to render, so don't linger: an empty transparent
            // window on screen is the "flash of blank" §6.9 warns about.
            // This is the default path, not an edge case: the sheet is off
            // unless asked for, so an ordinary tap arms and gets out of the
            // way exactly as it did before the sheet existed. Ending or
            // extending never gets one either — a user on their way out of
            // the app's way is the last person to offer a menu to.
            finish()
        }
    }

    private fun restore(state: Bundle) {
        startAccepted = state.getBoolean(STATE_START_ACCEPTED)
        awaitingPermission = state.getBoolean(STATE_AWAITING_PERMISSION)
        commitFailed = state.getBoolean(STATE_COMMIT_FAILED)
        if (state.containsKey(STATE_ENDS_AT)) {
            endCondition = EndCondition(
                endsAt = Instant.ofEpochMilli(state.getLong(STATE_ENDS_AT)),
                floor = Instant.ofEpochMilli(state.getLong(STATE_FLOOR)),
                ceiling = Instant.ofEpochMilli(state.getLong(STATE_CEILING)),
            )
        }
        // Owed a decision, whether or not a sheet was drawn before it. Posted
        // again against the same intent and *without* touching the service,
        // which is already handling that action — re-dispatching is round 6's
        // second arm. Where nothing is owed this stays false and the sheet is
        // simply restored, which is what keeps a rotation from reseeding the
        // time the user stepped to.
        val owedDecision = state.getBoolean(STATE_DECISION_PENDING)
        if (owedDecision) postDecision(serviceActionFor(intent) == SnoozeService.ACTION_ARM)
        // Nothing drawn yet: the decision above is the whole of what happens
        // next, and without it this activity would sit blank and transparent
        // for as long as the user left it there.
        if (!state.getBoolean(STATE_SHEET_SHOWN)) return
        renderSheet()
        if (!state.getBoolean(STATE_COMMITTING)) return

        committing = true
        val alreadyAnswered = EndChoiceOutcome.takePending()
        if (alreadyAnswered != null) onCommitOutcome(alreadyAnswered) else watchForOutcome()
    }

    /**
     * The second tap, while the first one's activity is still up.
     *
     * This activity is `singleInstance`, so a tile tap arriving while it is
     * alive — which it is for as long as the notification-permission dialog or
     * the end-condition sheet is showing — is
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
        else -> SnoozeService.ACTION_ARM
    }

    private fun dispatch(intent: Intent?) {
        // Whichever tap sent us here.
        val action = serviceActionFor(intent)
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
        startAccepted = started

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

    /**
     * Whether to put the sheet in front of the user for the tap just handled.
     *
     * Three things have to hold, and the third is the one a started service
     * cannot answer: this was an arm, the setting is on, and **a snooze is
     * actually running**. `startService` succeeding only means the start was
     * accepted — arming can still be refused for missing Do Not Disturb access,
     * a switched-off rule, or a platform refusal, and every one of those erases
     * the record on its way out. Gating on the start alone showed an opted-in
     * user a sheet for a snooze that did not exist, over the top of the card
     * saying it hadn't happened, and a time chosen there did nothing at all
     * (Codex, PR #118).
     *
     * Read here, not in `onCreate`: this runs from the posted block, off the
     * arm path, against a preferences file the application has already warmed.
     *
     * It is **not** guaranteed the arm has landed by then. `startService` is a
     * binder round trip into `ActivityManagerService`, and the way back is a
     * oneway `IApplicationThread` callback that posts `onStartCommand` from a
     * binder thread — nothing orders that against a local `post` (Codex,
     * PR #118). In practice the decor view's post lands in the `ViewRootImpl`
     * run queue and drains on a traversal, which is later than the service
     * message; but "in practice" is the honest word, and the options for making
     * it a guarantee are in `TODO.md`.
     *
     * **Fails closed**, deliberately: if the record somehow isn't there yet, the
     * user gets no sheet and a correctly armed snooze on its default cap, which
     * is exactly what the setting being off would have given them. The opposite
     * failure is a sheet whose taps go nowhere.
     */
    private fun shouldOfferSheet(arming: Boolean, now: Instant): Boolean =
        arming && startAccepted && !isLocked() && endSheetStore.isEnabled() && thereIsAChoice(now)

    /**
     * Whether the phone is locked, in which case there is no sheet.
     *
     * The same answer the notification-permission request already gives on this
     * path, for the same reason: this activity declares no `showWhenLocked`, so
     * a sheet rendered behind the keyguard is one the user cannot see, cannot
     * answer, and would meet later — with a time chosen against a clock that has
     * moved on — the moment they unlock (Codex, PR #118).
     *
     * Skipped rather than shown over the keyguard, deliberately. Arming locked
     * is a supported case (SPEC.md §4.2) and the snooze is already running on
     * its default cap; putting a window in front of a locked phone to refine it
     * is the opposite of the one-tap path this activity exists to protect.
     */
    private fun isLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /**
     * Whether there is a snooze on disk *and* a time the sheet could set on it.
     *
     * The record alone is not enough. A cap already closer than [MIN_CAP] leaves
     * nothing to choose — the service declines anything inside that floor, and
     * the only value above it is later than the cap, which the service honors by
     * doing nothing and reports as applied. Either way the sheet would be a
     * screen the user cannot answer. A duplicate arm from a stale tile snapshot
     * is what produces one, since the service keeps the snooze already running
     * (§4.2) and that snooze can be minutes from its cap (Codex, PR #118).
     *
     * Failing closed here is the same trade the rest of this gate makes: no
     * sheet and a correctly armed snooze is exactly what the setting being off
     * would have given them.
     */
    private fun thereIsAChoice(now: Instant): Boolean {
        val cap = ActiveSnoozeStore(this).load()?.capExpiresAt ?: return false
        return cap.isAfter(now.plus(ActiveSnooze.MIN_CAP))
    }

    /**
     * The latest time the sheet may offer: the cap the running snooze actually
     * carries, not a fresh eight hours from now.
     *
     * They coincide on a fresh arm and part company on a duplicate one. The
     * tile arms from its own snapshot, and a stale "not snoozing" one sends a
     * second `ACTION_ARM` that [SnoozeService] answers by *keeping* the snooze
     * already running (§4.2) — so the record this sheet is about can be one
     * that started long ago. Seeded against a constant, the sheet would offer
     * an hour over a snooze with ten minutes left, and `applyChosenEnd` would
     * answer `APPLIED` — correctly, since a chosen time no earlier than the cap
     * is honored by doing nothing — and dismiss. The user is shown 14:00 and
     * gets 12:10 (Codex, PR #118).
     *
     * Reading the record here is not a §6.9 violation: this runs in the posted
     * block, after `startService`, and [shouldOfferSheet] has already read the
     * same warmed file to decide there is a snooze at all.
     *
     * The default is the unreachable-in-practice fallback for a record that
     * vanished between those two reads — a cap-driven end landing in between.
     * The service re-clamps whatever is committed either way, so the worst case
     * is an offer it declines rather than one it wrongly accepts.
     */
    private fun sheetCeiling(now: Instant): Instant =
        ActiveSnoozeStore(this).load()?.capExpiresAt ?: now.plus(ActiveSnooze.DEFAULT_CAP)

    /**
     * The end-condition sheet (SPEC.md §4.4), once the snooze is already armed.
     *
     * **Nothing here is on the arm path.** It runs from the posted block, so
     * every call below — `enableEdgeToEdge`, the clock read, the first
     * composition — happens after `startService` rather than in front of it,
     * which is what §6.9 asks for. (Whether the service has *finished* arming
     * by then is a different question, and not one this path waits on; see
     * `thereIsAChoice`.)
     *
     * The sheet's ceiling is derived from the clock rather than read back from
     * the record, deliberately: the record is written by the service that is
     * still starting, and waiting on it is exactly what §6.9 forbids. A snooze
     * armed a few milliseconds ago has its backstop a default cap from now, so
     * this is that value to within the gap — and the service re-clamps whatever
     * gets committed against the record that actually exists, so a stale
     * reading can never outlive the tap.
     *
     * Edge-to-edge is declared here for the same reason: the trampoline's own
     * theme leaves it off so nothing runs before the service start, and the
     * sheet is the first thing this activity has ever drawn.
     */
    private fun showEndConditionSheet(now: Instant) {
        // Re-seeded on every arm, including a second tile tap arriving at
        // `onNewIntent` while the sheet is up: that tap armed a *new* snooze, so
        // an hour from the first tap is no longer the offer being made.
        //
        // The zone is the device default because that is the one `formatTime`
        // renders in: rounding against any other would put the seed on a half
        // hour the user is not being shown (Codex, PR #118). Reading it here is
        // an in-memory lookup, and this runs after the service is already away.
        endCondition = EndCondition.seededAt(now, sheetCeiling(now), ZoneId.systemDefault())
        commitFailed = false
        renderSheet()
    }

    /**
     * Puts the sheet on screen from whatever [endCondition] currently holds.
     *
     * Split from the seeding above so a configuration change can put the same
     * sheet back rather than a new one. Idempotent: a second tile tap arriving
     * while the sheet is up re-seeds through the caller and this does nothing,
     * since the composition already reads that state.
     */
    private fun renderSheet() {
        if (sheetRendered) return
        sheetRendered = true

        enableEdgeToEdge()
        setContent {
            SnoozemoTheme {
                // The scrim is the dismissal: §4.4 says dismissing leaves the
                // user correctly snoozed, and they are — the snooze is running
                // on its default cap, and the back gesture finishes this
                // activity to the same effect.
                // Held while a commit is out, so the sheet cannot be dismissed
                // out from under the answer it is waiting for: `onDestroy`
                // drops the watch, and a failure arriving after that is silent
                // for a user who denied notifications — which is the whole
                // reason the sheet waits at all (Codex, PR #118). Both exits
                // are covered, the scrim and the back gesture.
                BackHandler(enabled = committing) {}
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // The scrim is its own layer *behind* the sheet, not the
                    // parent holding it. As the parent it received every tap the
                    // sheet's own content didn't consume — the title, the footer,
                    // the padding — so touching inside the sheet dismissed it
                    // (Codex, PR #118). A sibling only ever sees what misses.
                    //
                    // No ripple and no indication: this is the whole screen
                    // behind a sheet, and a ripple spreading across it would read
                    // as the background itself being a control.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = !committing,
                                onClick = ::finish,
                            ),
                    )
                    endCondition?.let { condition ->
                        EndConditionSheetContent(
                            condition = condition,
                            formattedTime = formatTime(condition.endsAt),
                            onChooseTime = { commit(condition.endsAt) },
                            // The departure row commits by changing nothing:
                            // tracking is already armed and the default cap is
                            // already the backstop, so "until I leave" is the
                            // snooze exactly as it stands (§4.4).
                            onChooseDeparture = ::finish,
                            onStepDown = { endCondition = condition.stepDown() },
                            onStepUp = { endCondition = condition.stepUp() },
                            failed = commitFailed,
                            committing = committing,
                            tracksDeparture = PRESENCE_TRACKS_DEPARTURE,
                            modifier = Modifier.navigationBarsPadding(),
                        )
                    }
                }
            }
        }
    }

    /**
     * Sends a chosen time to the service and **waits to hear what happened**
     * before dismissing.
     *
     * A started service is not an applied change: the alarm can still refuse,
     * the record can still fail to write, and the snooze can have ended in
     * between. Dismissing on the start alone left the user believing a time they
     * could no longer see had been taken — and the card those failures post is a
     * notification, invisible to exactly the tile-first user who denied that
     * permission and for whom the tile is the only surface there is (SPEC.md
     * §4.2; Codex, PR #118).
     *
     * So the watch goes on **before** the start, since the service reports from
     * `onStartCommand` on this same looper and an outcome delivered before the
     * watch existed would be one nobody heard.
     *
     * Staying put on a failure matters twice over: the snooze is still running
     * on the cap it had, so nothing is stranded, and the sheet is the one place
     * the user is certain to be looking.
     */
    private fun commit(endsAt: Instant) {
        if (committing) return
        committing = true
        commitFailed = false
        // Discarded, not delivered: anything held here answers an *earlier*
        // tap, and handing it to this one would report a fresh choice as
        // already applied.
        EndChoiceOutcome.takePending()
        watchForOutcome()
        if (!SnoozeService.chooseEnd(this, endsAt)) {
            // Never dispatched, so no outcome is coming; settle this one here.
            Log.e(TAG, "Starting the snooze service for a chosen end time was refused.")
            onCommitOutcome(EndChoiceResult.REFUSED)
        }
    }

    private fun watchForOutcome() {
        outcomeWatch?.close()
        outcomeWatch = EndChoiceOutcome.watch(::onCommitOutcome)
    }

    /** What the service said about the time the user chose. */
    private fun onCommitOutcome(result: EndChoiceResult) {
        outcomeWatch?.close()
        outcomeWatch = null
        committing = false
        // `GONE` dismisses like `APPLIED`, and deliberately: the snooze ended
        // under the sheet — a departure, the cap, a capability loss — so there
        // is nothing left to refine and every later tap would fail the same
        // way. Standing there offering a retry over a snooze that is already
        // over is the dead end this case exists to avoid (Codex, PR #118).
        // Whatever ended it has posted its own card.
        if (result != EndChoiceResult.REFUSED) {
            finish()
            return
        }
        commitFailed = true
        // A sheet left open long enough can be showing a time that has since
        // fallen inside the floor, and the service declines those rather than
        // quietly substituting a later one (Codex, PR #118). Declining alone
        // would make the sheet a dead end — the same tap failing forever — so
        // the offer is reseeded against the clock as it is now.
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        if (endCondition?.endsAt?.isBefore(now.plus(ActiveSnooze.MIN_CAP)) == true) {
            endCondition = EndCondition.seededAt(now, sheetCeiling(now), ZoneId.systemDefault())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The channel holds this activity's lambda, so an unclosed watch outlives
        // the sheet it was answering and leaks the activity with it.
        outcomeWatch?.close()
        outcomeWatch = null
    }

    /**
     * [instant] as the user's own phone writes a time — their 12/24-hour setting,
     * their locale, their zone.
     *
     * Through `DateFormat.getTimeFormat` rather than a fixed pattern: a sheet
     * offering "14:00" to someone whose phone says "2:00 PM" everywhere else
     * reads as a different app's screen.
     */
    private fun formatTime(instant: Instant): String =
        DateFormat.getTimeFormat(this).format(Date(instant.toEpochMilli()))

    private companion object {
        const val STATE_DECISION_PENDING = "decision_pending"
        const val STATE_START_ACCEPTED = "start_accepted"
        const val STATE_AWAITING_PERMISSION = "awaiting_permission"
        const val STATE_SHEET_SHOWN = "sheet_shown"
        const val STATE_COMMITTING = "committing"
        const val STATE_COMMIT_FAILED = "commit_failed"

        // The sheet's own three instants. On-device only and never logged: when
        // a user intends to stop being disturbed is theirs (`AGENTS.md`,
        // *Privacy*), and saved instance state does not leave the process.
        const val STATE_ENDS_AT = "ends_at"
        const val STATE_FLOOR = "floor"
        const val STATE_CEILING = "ceiling"
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
