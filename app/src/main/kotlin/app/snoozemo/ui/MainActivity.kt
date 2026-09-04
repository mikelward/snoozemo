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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import app.snoozemo.BuildConfig
import app.snoozemo.PlayUpdateChecker
import app.snoozemo.PlayUpdateState
import app.snoozemo.R
import app.snoozemo.UpdateProgress
import app.snoozemo.playUpdateDismissalKey
import app.snoozemo.progressForInstallStatus
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.EndReason
import app.snoozemo.core.CalendarPermission
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.PolicyAccessAction
import app.snoozemo.core.PolicyAccessChange
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.StaleRuleClaim
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenRuleState
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.crash.CrashReporting
import app.snoozemo.snooze.DebugLogStore
import app.snoozemo.snooze.DebugLogging
import app.snoozemo.snooze.EndSheetSetting
import app.snoozemo.snooze.reconcileRingerInBackground
import app.snoozemo.snooze.SnoozeRingerSetting
import app.snoozemo.core.EndCondition
import java.time.Instant
import app.snoozemo.snooze.EndChoiceController
import app.snoozemo.snooze.EndChoiceOutcome
import app.snoozemo.snooze.EndSheetStore
import app.snoozemo.dnd.SnoozeRingerStore
import app.snoozemo.snooze.DebugReport
import app.snoozemo.snooze.CalendarPromptStore
import app.snoozemo.snooze.LocationPromptStore
import app.snoozemo.snooze.NotificationPromptStore
import app.snoozemo.snooze.RecreationMarker
import app.snoozemo.snooze.PlayUpdateStore
import app.snoozemo.snooze.SnoozeClock
import app.snoozemo.snooze.SnoozeNotifications
import app.snoozemo.snooze.SnoozeService
import app.snoozemo.snooze.releaseDirectly
import app.snoozemo.tile.SnoozeTileService
import app.snoozemo.tile.TilePresenceStore
import app.snoozemo.tile.R as TileR

private const val TAG = "MainActivity"

/** `onSaveInstanceState` keys for the navigation state a configuration change would otherwise lose. */
private const val KEY_SCREEN = "screen"
private const val KEY_PERMISSIONS_ORIGIN = "permissionsOrigin"
private const val KEY_ROUTED_TO_PERMISSIONS_ONCE = "routedToPermissionsOnce"
private const val KEY_SHEET_COMMITTING = "sheetCommitting"
private const val KEY_SHEET_REQUEST_ID = "sheetRequestId"
private const val KEY_SHEET_OFFERED_FOR = "sheetOfferedFor"
private const val KEY_SHEET_FAILED = "sheetFailed"
private const val KEY_SHEET_ENDS_AT = "sheetEndsAt"
private const val KEY_SHEET_FLOOR = "sheetFloor"
private const val KEY_SHEET_CEILING = "sheetCeiling"

/** How often [MainActivity.now] advances while visible — see its own comment. */
private const val TICK_INTERVAL_MS = 60_000L

/**
 * The four screens this activity switches between; there is no back stack
 * beyond one level. Internal rather than private only so a lifecycle test
 * can assert on [MainActivity.screen] directly; nothing outside this file
 * constructs one in production.
 */
internal enum class Screen { MAIN, PERMISSIONS, SETTINGS, LICENSES }

/**
 * Hosts the four screens the app is split into (`TODO.md` Phase 4): [MainScreen],
 * [PermissionsScreen], [SettingsScreen], and [LicensesScreen] as a leaf off the
 * Settings foot. There is no navigation library — deliberately, per the same
 * TODO entry — so this class holds the one piece of navigation state itself.
 */
class MainActivity : ComponentActivity() {

    /**
     * Which of the four screens is on top.
     *
     * Internal rather than private, like [latestAccessRefresh] below, only so
     * a test can pin it directly; nothing outside this class reads it in
     * production.
     */
    internal var screen by mutableStateOf(Screen.MAIN)

    /**
     * Which screen [Screen.PERMISSIONS] returns to on Done or back — [MainScreen]
     * when it was reached automatically or from its own banner, [SettingsScreen]
     * when reached from there. Read only while [screen] is [Screen.PERMISSIONS].
     * Internal for the same test-only reason as [screen].
     */
    internal var permissionsOrigin by mutableStateOf(Screen.MAIN)

    /**
     * Whether [applyAccess] has already made its one routing decision.
     *
     * Not `by mutableStateOf`: this gates a side effect on the *first* access
     * reading, not something the screen renders, and every later reading must
     * leave [screen] alone — a revocation mid-snooze must land the user on
     * `MainScreen`'s banner (`SPEC.md` §8.2's own recovery path), not yank them
     * into the interstitial out from under whatever they were doing.
     */
    private var routedToPermissionsOnce = false

    private lateinit var zen: ZenController

    /**
     * [zen] for a test that needs to drive a refusal from the real read — the
     * failure path is otherwise unreachable from a JVM test, and it is the one
     * that decides what the screen keeps.
     */
    internal var zenForTest: ZenController
        get() = zen
        set(value) { zen = value }
    private lateinit var store: ActiveSnoozeStore

    /**
     * Generation guard for [offerSheetForThisArm], the same shape
     * [refreshSnoozing] uses and for the same reason: two arms in quick
     * succession finish their reads in whatever order the disk returns them,
     * and the older answer must not open a sheet over the newer arm.
     */
    private var latestSheetOffer = 0

    /**
     * Bumped whenever a sheet goes up, so a record read that started *before*
     * it cannot tear it back down. `refreshSnoozing`'s reads finish in
     * whatever order the disk returns them, and one that began before an arm
     * carries a record from before that arm — a `null` that would otherwise
     * read as "the snooze this sheet is refining is gone".
     */
    private var sheetGeneration = 0

    /** Read-only view of [sheetGeneration], so a test can pin a read to an arm. */
    @androidx.annotation.VisibleForTesting
    internal val sheetGenerationForTest: Int
        get() = sheetGeneration

    /**
     * Where [offerSheetForThisArm]'s disk reads run. A seam only so a test can
     * make them synchronous — production always hands them to a thread, since
     * neither the setting file nor the record belongs in front of a frame.
     */
    @androidx.annotation.VisibleForTesting
    internal var runOffMainThread: (() -> Unit) -> Unit = { work -> Thread(work).start() }

    /**
     * The end-condition sheet, driven by the same controller the tile uses
     * (SPEC.md §4.4). Arming from this screen used to skip it entirely, so the
     * same action asked when it came from the shade and silently took the
     * default cap when it came from the button.
     *
     * The ceiling comes from [activeSnooze], the copy this screen already keeps
     * warm — no disk read in front of the sheet.
     */
    @androidx.annotation.VisibleForTesting
    internal val sheet = EndChoiceController(
        // The warm copy, which may be stale or null — the controller checks
        // its identity against the offer's before trusting it.
        currentRecord = { activeSnooze },
        chooseEnd = { endsAt, requestId, forSnooze ->
            SnoozeService.chooseEnd(this, endsAt, requestId, forSnooze)
        },
        watchOutcome = EndChoiceOutcome::watch,
        // Nothing to finish: clearing the offer is what closes this sheet,
        // unlike the trampoline where the activity *is* the sheet.
        onDismiss = {},
    )
    private lateinit var promptStore: NotificationPromptStore
    private lateinit var locationPromptStore: LocationPromptStore
    private lateinit var calendarPromptStore: CalendarPromptStore
    private lateinit var tileStore: TilePresenceStore
    private var recordWatch: AutoCloseable? = null
    private var tileWatch: AutoCloseable? = null

    /**
     * Asks Play about a waiting update for `SettingsScreen`'s banner. Owned by
     * the activity because starting the update flow needs one; recreated
     * with it, so [onDestroy] drops its install listener. `direct`'s own
     * copy of [PlayUpdateChecker] is a no-op — see its own comment.
     */
    private lateinit var playUpdateChecker: PlayUpdateChecker
    private lateinit var playUpdateStore: PlayUpdateStore

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
     * The record itself, read alongside [snoozing] and for the same reason —
     * `MainScreen`'s status line needs the mode and the cap to report anything
     * beyond "running", and re-deriving them separately would be a second read
     * of the same file the [refreshSnoozing] load already did.
     */
    @androidx.annotation.VisibleForTesting
    internal var activeSnooze by mutableStateOf<ActiveSnooze?>(null)

    /**
     * The clock reading `MainScreen`'s status line computes [activeSnooze]'s
     * remaining time against.
     *
     * Not read fresh at every recomposition: `SnoozeClock.read()` is a plain
     * function call, not a state read, so a `remaining` computed from it
     * inline would only ever update when something *else* triggered a
     * recomposition — the countdown would go stale and stay stale for as
     * long as the user left the screen open with nothing else changing
     * (Codex, PR #87). This field is what makes time itself a reason to
     * repaint: [tickRunnable] advances it once a minute while the activity is
     * `STARTED`, which is exactly the display's own granularity (`Xh Ym
     * left`) — no reason to tick faster than the text can show. Still not a
     * live per-second chronometer: that stays the ongoing notification's job.
     */
    internal var now by mutableStateOf(SnoozeClock.read())

    private val tickHandler = Handler(Looper.getMainLooper())

    /** Re-posts itself every [TICK_INTERVAL_MS] while running; see [now]. */
    private val tickRunnable: Runnable = Runnable {
        now = SnoozeClock.read()
        tickHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }
    /**
     * Null until the platform has been asked, for the same reason [snoozing] is:
     * reading it costs a binder round trip that must not sit in front of the
     * first frame, and neither default is safe to render in the meantime.
     * `DENIED` would flash `Snoozemo needs Do Not Disturb access` and a grant
     * button at a user who granted it months ago; `GRANTED` would offer to arm
     * something that cannot arm.
     *
     * Internal rather than private, like [zenRuleId] below, only so a test can
     * drive [filtersRuleId]/[openFilters] directly without the real async
     * `ensureRule()` round trip in the way; nothing outside this class reads
     * it in production.
     */
    internal var access by mutableStateOf<PolicyAccess?>(null)

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
     * Null until the platform has been asked, for the same reason [notifications]
     * is: two `checkSelfPermission` calls plus a persisted denial history, none
     * of which belongs in front of the first frame, and neither default is safe
     * to render in the meantime.
     *
     * Missing this permission never blocks a snooze — the presence engine
     * degrades to Wi-Fi-only or duration-only and says so (`SPEC.md` §3.6) — so
     * this is purely the settings row's own repair surface, the same shape
     * [notifications] uses for the same reason.
     */
    private var location by mutableStateOf<LocationPermission?>(null)

    /**
     * The two raw location grants as [refreshLocation] last read them, so a
     * grant *rising* — either half — can be told apart from a reading that
     * changed nothing. Not state the screen draws: [location] is that, and
     * it folds the pair into one reading that cannot see a fine-only grant
     * arrive under a background half still denied.
     */
    private var locationGrantsRead: LocationGrants? = null

    /**
     * Null until read, for the same reason [location] is — and the least
     * consequential of the three: missing this costs the ongoing
     * notification's `Until <time>` action and nothing else (`SPEC.md` §4.3),
     * so the row states a gap in what is offered, never a broken snooze.
     */
    private var calendar by mutableStateOf<CalendarPermission?>(null)

    /**
     * Whether the background-location rationale dialog is on screen. Only the
     * background half needs one: `SPEC.md` §12 requires disclosure before
     * *that* prompt specifically (it's the Play-restricted permission), and
     * unlike the foreground request, background is asked for as a follow-up
     * step the user did not just tap a button for — the dialog is what
     * explains why a second system prompt is coming.
     *
     * Composable-local `remember` would lose this on rotation, same as any
     * other dismissable confirmation dialog; that is an acceptable trade here
     * since dismissing just means re-tapping the row.
     */
    internal var showBackgroundLocationRationale by mutableStateOf(false)

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
     * The zen rule's own id, so `SettingsScreen`'s Filters row can deep-link
     * straight to the system's interruption-filter screen for it (`TODO.md`).
     * Null hides the row — while this hasn't been read yet, while there is
     * genuinely nothing to edit, and while the last check couldn't confirm
     * the id is still good, which is the same "nothing to show until there's
     * something to show" discipline every other row here follows.
     *
     * Set **only** from [ensureRuleInBackground]'s verified result — `READY`
     * or `DISABLED`, both reached only after `ensureRule()` has confirmed the
     * rule exists — and cleared on `FAILED`. Never from a bare
     * [ZenController.ruleId] read: that is the persisted store's unverified
     * value, and a rule the user deleted from system Settings leaves a stale
     * id there until `ensureRule()` next confirms or replaces it. Rendering
     * that unverified id would put up a tappable row whose tap opens Settings
     * to a rule that no longer exists — a silent dead end `startActivity`
     * cannot report as a failure, since launching the intent still succeeds
     * (Codex, PR #88).
     *
     * Internal rather than private so a test can set it directly and drive
     * [openFilters]'s actual `startActivity` call — the async chain that
     * populates this in production (`ensureRuleInBackground`, off a raw
     * background `Thread`) has no seam a JVM test can advance deterministically,
     * and this repo's own testing rules rule out papering over that with
     * sleeps or polling (Codex, PR #88).
     */
    internal var zenRuleId by mutableStateOf<String?>(null)

    /**
     * The last verified [ZenRuleState], or null while unread.
     *
     * The permissions screen needs it because policy access alone does not mean
     * a snooze can silence the phone: the user can switch Snoozemo's rule off in
     * Settings, and the platform can refuse to create it. That screen does not
     * render [lastOutcome], so without this it would claim the capability while
     * the app knew better (Codex, PR #171). Set from the same verified result
     * [zenRuleId] is, so the two never disagree.
     */
    internal var zenRuleState by mutableStateOf<ZenRuleState?>(null)

    /**
     * The refresh whose rule check has not answered yet, or null when none is
     * outstanding.
     *
     * [zenRuleState] is the last *verified* answer and is never cleared; this
     * says whether it is still current. A check that ends without an answer
     * clears this and leaves that answer standing, which is why no failure path
     * has anything to restore.
     *
     * Only the newest refresh clears it ([finishRuleCheck]): an older one
     * finishing while a newer is still running would otherwise declare the
     * newer one's answer current before it exists.
     */
    private var ruleCheckInFlight by mutableStateOf<Int?>(null)

    /**
     * What the permissions screen may claim: the last verified rule state, or
     * null while a check that could change it is outstanding.
     */
    internal val renderableRuleState: ZenRuleState?
        get() = zenRuleState.takeIf { ruleCheckInFlight == null }

    /** Marks [refresh]'s rule check as finished, if it is still the newest. */
    private fun finishRuleCheck(refresh: Int) {
        if (ruleCheckInFlight == refresh) ruleCheckInFlight = null
    }

    /**
     * What [zenRuleId] should actually show as, once [access] is known: a rule
     * id read before Do Not Disturb access was confirmed granted — or one left
     * over from before access was revoked — must not offer a row that deep-links
     * into a filter screen for a rule this app can no longer be sure is usable.
     *
     * No resolution check against `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS`
     * here: that action's AOSP intent-filter is gated behind Modes UI, an
     * Android 15+ feature flag with no lower-API path to the same screen —
     * minSdk is 35 precisely so this is guaranteed rather than probed
     * per-device (`SPEC.md` §11, PR #88).
     */
    private val filtersRuleId: String?
        get() = zenRuleId.takeIf { access == PolicyAccess.GRANTED }

    /**
     * Whether the tile banner has been sent away for good.
     *
     * Defaults to **dismissed** so the loud thing cannot flash on a screen that
     * has not finished reading yet. A boolean is enough where [tileAdded] needs
     * a third state: there is only one thing to suppress here, while the row has
     * two states to tell apart.
     */
    private var tileBannerDismissed by mutableStateOf(true)

    /**
     * Whether `ACCESS_BACKGROUND_LOCATION` is missing on a flavor whose
     * tracking needs it — what `MainScreen`'s banner reads.
     *
     * Kept beside [location] rather than derived from it, because
     * [LocationPermission] deliberately collapses the two grants into one
     * verdict: `ASKABLE` says *something* is missing, not which. The banner
     * has to name the background half specifically, since the foreground
     * half missing is a different message with a different consequence.
     *
     * Defaults to **false**, the same direction [tileBannerDismissed]
     * defaults to dismissed: unread is not "missing", and a banner that
     * flashed on every cold start before the first read would be worse than
     * one that appears a frame late.
     */
    private var backgroundLocationMissing by mutableStateOf(false)

    /** Dismissed until read, for the reason [tileBannerDismissed] is. */
    private var backgroundLocationBannerDismissed by mutableStateOf(true)

    /**
     * Whether the telemetry question is still outstanding — what raises the
     * consent card (`SPEC.md` §12).
     *
     * Starts **answered**, the same direction [tileBannerDismissed] starts
     * dismissed: a card asking for data must never flash before the store has
     * been read, and a frame late is the harmless direction here.
     */
    private var telemetryUnanswered by mutableStateOf(false)
    private var lastOutcome by mutableStateOf<String?>(null)

    /**
     * What Play last told us about a waiting update, before [dismissedPlayUpdateVersionCode]
     * is applied — see [displayedPlayUpdate], which is what `SettingsScreen`'s
     * banner (`play` flavor only) actually reads. Not persisted: reread on
     * every resume, the same as the record and the access check.
     *
     * Internal rather than private, like [setPlayUpdateAvailable] below, so
     * a test can read the state the banner's setters actually produced.
     */
    internal var playUpdate by mutableStateOf<PlayUpdateState>(PlayUpdateState.NotAvailable)

    /**
     * Tracked beside [playUpdate] because a recheck's `UNKNOWN` install
     * status carries no progress of its own: it has to fall back to what the
     * banner was already showing (see [progressForInstallStatus]).
     */
    private var currentPlayUpdateProgress: UpdateProgress = UpdateProgress.Idle

    /**
     * Bumped by every [checkPlayUpdate]; only the newest check's answer may
     * paint. `onResume` can fire again — backgrounding and resuming quickly,
     * or returning from the Play sheet — before an earlier check's Play
     * Store IPC has actually completed, and Play's replies aren't
     * guaranteed to land in the order the requests were made. Without this,
     * a slow, stale "unavailable" landing after a fresh "available" would
     * wipe the banner the newer check just painted (Codex, PR #99) — the
     * same class of bug [latestAccessRefresh] guards against for the
     * access read.
     */
    private var latestPlayUpdateRefresh = 0

    /**
     * The build whose update banner the user dismissed (0 = none), null
     * until the store has been read — the same "unread is not zero"
     * discipline every other field on this screen follows ([tileAdded],
     * [debugLogEnabled], and friends), and for the same reason: `0` and
     * "haven't checked yet" would otherwise be indistinguishable, and
     * `checkPlayUpdate()` (from `onResume`) can land before this field's own
     * post-first-frame read has run. Reading `null` as "not dismissed" would
     * flash an already-dismissed banner onto the screen for a frame before
     * the real answer arrived and hid it again (Codex, PR #99) — instead
     * [displayedPlayUpdate] reads `null` the same direction [tileBannerDismissed]
     * defaults: toward *not* showing the loud thing until it's confirmed safe to.
     *
     * Kept apart from [playUpdate] rather than baked into it, and recombined
     * only in [displayedPlayUpdate], so the store's answer — whenever it
     * arrives — is never missed by a dismissal that was computed too early.
     */
    private var dismissedPlayUpdateVersionCode by mutableStateOf<Int?>(null)

    /**
     * What `SettingsScreen`'s banner actually renders: [playUpdate] with
     * [dismissedPlayUpdateVersionCode] folded in. See that field's own
     * comment for why this isn't just [playUpdate] with the flag set inline.
     *
     * Internal rather than private, like [setPlayUpdateAvailable] above, so
     * a test can read the state the banner's setters actually produced.
     */
    internal val displayedPlayUpdate: PlayUpdateState
        get() {
            val available = playUpdate as? PlayUpdateState.Available ?: return playUpdate
            val dismissed = dismissedPlayUpdateVersionCode
            return available.copy(
                isDismissed = dismissed == null ||
                    dismissed == playUpdateDismissalKey(available.versionCode, BuildConfig.VERSION_CODE),
            )
        }

    /**
     * Whether the last tap on the banner's Restart failed to hand off to
     * Play (the installer busy, a transient Play error) — the one outcome
     * where the tap would otherwise visibly do nothing (Codex, PR #99).
     * Cleared on the way into the next attempt, like [settingsFailure], and
     * whenever the banner starts tracking something else: a stale failure
     * from a build that has since finished downloading, or been superseded
     * by a newer one, describes a tap that is no longer the one on screen.
     */
    private var playUpdateRestartFailed by mutableStateOf(false)

    /**
     * Whether the debug log is on (SPEC.md §4.6). Null until the store has been
     * read, so the row appears stating the truth rather than a default
     * corrected a frame later — the same discipline as every row above.
     */
    private var debugLogEnabled by mutableStateOf<Boolean?>(null)

    /**
     * Whether the tile asks when to unsnooze (SPEC.md §4.4). Null until the
     * store has been read, same discipline as [debugLogEnabled]: off is the
     * default, so a row that asserted it and corrected itself a frame later
     * would flash the wrong answer at exactly the user who had turned it on.
     */
    private var askWhenToUnsnooze by mutableStateOf<Boolean?>(null)

    /** Whether the last tap on that switch failed to reach disk. */
    private var askWhenToUnsnoozeSaveFailed by mutableStateOf(false)

    /**
     * How many of its writes are still on the worker. Main thread only, like
     * [debugLogWrites], and for the same reason: while it is non-zero the
     * switch shows the user's tap and every read-back holds off, so a start
     * repainting the old stored value over an in-flight tap can't happen.
     */
    private var askWhenToUnsnoozeWrites = 0

    /** The handle for its save-outcome watch; see [onStart] and [EndSheetSetting.watchSaveOutcome]. */
    private var askWhenToUnsnoozeWatch: AutoCloseable? = null

    /**
     * How loud a snooze may be (SPEC.md §5.9). Null until the store has been
     * read, the same discipline as [askWhenToUnsnooze] — and it matters more
     * here, because the default is not the first option: a row that asserted
     * it and corrected itself a frame later would flash the wrong answer at
     * whoever had chosen either of the others.
     */
    private var snoozeRinger by mutableStateOf<SnoozeRinger?>(null)

    /** Whether the last tap on that row failed to reach disk. */
    private var snoozeRingerSaveFailed by mutableStateOf(false)

    /** How many of its writes are still on the worker; see [askWhenToUnsnoozeWrites]. */
    private var snoozeRingerWrites = 0

    /** The handle for its save-outcome watch; see [onStart]. */
    private var snoozeRingerWatch: AutoCloseable? = null

    /**
     * How many debug-log writes are still on the worker. Main thread only,
     * like the generation counters: while it is non-zero, the switch shows the
     * user's tap and every read-back holds off — a start or store change
     * repainting the *old* stored value over an in-flight tap would show the
     * opposite of what is about to be true (flagged by Codex on PR #68). Each
     * write's completion reconciles the switch from the store, which by then
     * holds the truth on success and failure alike.
     */
    private var debugLogWrites = 0

    /** The handle for the debug-log setting watch; see [onStart]. */
    private var debugLogWatch: AutoCloseable? = null

    /** The handle for the crash-reporting setting watch; see [onStart]. */
    private var crashReportingWatch: AutoCloseable? = null

    /**
     * Whether the last debug-log save was refused by storage. Cleared by the
     * next tap: the message describes the previous attempt, and a new attempt
     * supersedes it whatever it returns — the same rule the Settings-trip
     * failures follow.
     */
    private var debugLogSaveFailed by mutableStateOf(false)

    /**
     * Whether crash reporting is on (SPEC.md §12), or **null when this build
     * has no reporter** — `direct` always, and a `play` build made without a
     * Firebase config. Null is also the value before the store has been read,
     * and both mean the same thing to the screen: draw no row. The
     * availability check is what separates them, and it runs before the first
     * read below rather than after, so a build with no reporter never briefly
     * shows a switch it would then have to take away.
     */
    private var crashReportingEnabled by mutableStateOf<Boolean?>(null)

    /**
     * How many crash-reporting writes are still on the worker. Main thread
     * only, exactly as [debugLogWrites] is and for the same reason: while it
     * is non-zero the switch shows the user's tap and every read-back holds
     * off, so a start or a store re-read cannot repaint the *old* stored value
     * over an in-flight tap.
     */
    private var crashReportingWrites = 0

    /**
     * Whether the last crash-reporting save was refused by storage. Cleared by
     * the next tap, same rule as [debugLogSaveFailed].
     */
    private var crashReportingSaveFailed by mutableStateOf(false)

    /**
     * Whether the most recent Off toggle left one or more debug-log files
     * undeleted. Cleared by the next tap on this switch, same rule as
     * [debugLogSaveFailed]. Every re-read is unconditional, not gated on
     * whether the tap that triggered it was itself a disable: a requested
     * *enable* that storage refused never reaches the persisted branch that
     * would clear this field, so gating the read hid a real warning behind
     * a switch that had actually snapped back to Off, and a config change
     * mid-retry could leave a stale `true` nothing ever corrected, since
     * the tap's own completion belongs to a dead instance (Codex, PR #89 —
     * three separate rounds on this field: [debugLogWatch], [crashPinWatch]
     * — for the same dead-instance reason [crashPending] is, both firing
     * from the same disable completion — and `setDebugLog`'s own
     * completion).
     *
     * Internal rather than private only so a test can pin it directly, the
     * same test-only reason [shareFailed] and [dismissFailed] already are.
     */
    internal var debugLogCleanupFailed by mutableStateOf(false)

    /**
     * Whether a crashed run is currently pinned (SPEC.md §4.6) — the post-crash
     * banner's own state. Defaults to **false** so the banner cannot flash on a
     * screen that has not finished reading yet, the same discipline
     * [tileBannerDismissed] follows for the same reason. Read once at the
     * first frame after this activity's own (re)start, and kept current after
     * that by [crashPinWatch] — never written directly from a Share/Dismiss
     * tap's own completion callback, which may by then belong to a dead
     * instance (Codex, PR #89; see [crashPinWatch]'s own comment).
     */
    private var crashPending by mutableStateOf(false)

    /** The handle for the crash-pin watch; see [onStart] and [DebugLogging.watchCrashPinOutcome]. */
    private var crashPinWatch: AutoCloseable? = null

    /**
     * Whether the last debug-log share reached neither the clipboard nor the
     * chooser. Cleared by the next tap, like [debugLogSaveFailed] — the
     * message describes the previous attempt, and a new one supersedes it
     * whatever it returns. Applied through [shareWatch], for the same
     * dead-instance reason [crashPending] is.
     *
     * Internal rather than private only so a test can pin it directly, the
     * same test-only reason [screen] and [latestAccessRefresh] already are.
     */
    internal var shareFailed by mutableStateOf(false)

    /**
     * Whether a share is currently in flight, so both Share affordances (the
     * permanent Settings row and the crash banner's own button) can be
     * disabled while it resolves.
     *
     * This is what makes a second concurrent tap impossible, which is why
     * `DebugReport` no longer has to reconcile one after the fact — see
     * [DebugReport.shareInFlight]. Read from the same process-level owner
     * and applied through [shareWatch], for the same dead-instance reason
     * [shareFailed] is: a configuration change mid-share must not hand the
     * replacement instance a re-enabled button for a share still running.
     */
    internal var sharing by mutableStateOf(false)

    /** The handle for the share-outcome watch; see [onStart] and [DebugReport.watchShareOutcome]. */
    private var shareWatch: AutoCloseable? = null

    /**
     * Whether the last Dismiss tap on the crash banner was refused by the
     * file layer. [crashPending] alone already re-syncs correctly on a
     * refused dismiss (the pin really is still there), but that leaves the
     * tap looking like it did nothing — this is the explanation (Codex, PR
     * #89). Applied through [dismissWatch], for the same dead-instance
     * reason [crashPending] and [shareFailed] are; deliberately distinct
     * from [shareFailed], since a Share's own refused consume is not
     * surfaced the same way (`DebugLogging.lastDismissFailed`'s own doc).
     *
     * Internal rather than private only so a test can pin it directly, the
     * same test-only reason [shareFailed] already is.
     */
    internal var dismissFailed by mutableStateOf(false)

    /** The handle for the dismiss-outcome watch; see [onStart] and [DebugLogging.watchDismissOutcome]. */
    private var dismissWatch: AutoCloseable? = null

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
     *
     * Also bumped by [onStop]: a worker from one visible session must never act
     * in the next. Internal rather than private only so the lifecycle test can
     * pin that invalidation; nothing outside this class reads it in production.
     */
    internal var latestAccessRefresh = 0

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

    /**
     * The foreground half of the location grant: `ACCESS_FINE_LOCATION` and
     * `ACCESS_COARSE_LOCATION` requested together in one system dialog, like
     * the platform expects for a permission group — COARSE rides beside FINE
     * only so the request isn't refused outright (AndroidManifest.xml's own
     * comment on this; Android 12+ would otherwise decline to offer the
     * approximate downgrade). Launched directly from the settings row, the
     * same as every other row's runtime prompt — no disclosure precedes it;
     * `SPEC.md` §12's requirement is specific to the background half below.
     *
     * On a **fine** grant, and only when
     * [locationTrackingNeedsBackgroundPermission], opens the background
     * rationale dialog rather than launching the background request directly
     * — that dialog, not this one, is what Play's declaration requires
     * precede the *background* prompt. A coarse-only grant never opens it: it
     * isn't a grant [refreshLocation] treats as foreground held (see its own
     * comment), so nothing downstream is watching yet.
     */
    private val foregroundLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            onForegroundLocationResult(results[Manifest.permission.ACCESS_FINE_LOCATION] == true)
        }

    /**
     * The foreground result-handling logic, pulled out of
     * [foregroundLocationPermission]'s callback so a test can drive the
     * foreground-grant → background-dialog ordering directly, without the
     * real `ActivityResultLauncher` machinery in the way — the same
     * testability seam [latestAccessRefresh] uses. Internal rather than
     * private only for that; nothing outside this class calls it in
     * production.
     */
    internal fun onForegroundLocationResult(fineGranted: Boolean) {
        refreshLocation()
        if (fineGranted && locationTrackingNeedsBackgroundPermission) {
            showBackgroundLocationRationale = true
        }
    }

    /**
     * The background half, launched only from the rationale dialog's
     * Continue — see [showBackgroundLocationRationale]. Whatever the answer,
     * the settings row is what states it — a snooze still works without
     * this, degraded (`SPEC.md` §3.6), so a denial here is never treated as a
     * failure to recover from.
     */
    private val backgroundLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshLocation()
        }

    /**
     * `READ_CALENDAR`, launched from the calendar row (`SPEC.md` §4.3). No
     * disclosure precedes it: this is an ordinary runtime permission with no
     * Play declaration behind it, unlike the background half of location.
     *
     * The repost a grant needs is [refreshCalendar]'s, not this callback's: it
     * fires on any transition into `GRANTED`, which covers this dialog and the
     * trip to Settings the `BLOCKED` row takes alike. Asking for one here as
     * well started the service twice and queued two provider reads before
     * either answered (Codex, PR #156). Re-read whatever the answer, since a
     * denial moves the row too.
     */
    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshCalendar()
        }

    /**
     * Play's in-app update confirmation sheet. Backing out of it fires no
     * install event, and the next resume's check reports the same update
     * with an `UNKNOWN` status that deliberately preserves `Starting` — so
     * without this result the banner would keep spinning with neither
     * Update nor Dismiss reachable.
     */
    private val playUpdateLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                setPlayUpdateProgress(UpdateProgress.Idle)
            }
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
        // Restored before anything reads `screen`: a configuration change
        // recreates this activity from scratch, and without this a rotation
        // while on Settings — or on Permissions reached from Settings — would
        // silently land the user back on Main, with Permissions' own Done/back
        // now returning to the wrong place (Codex, PR #82). Everything else
        // this class tracks is re-derived from the platform or the record on
        // every fresh instance; navigation position has no such source of
        // truth to re-derive from, so it is the one thing here that needs
        // actual persisting.
        // **Whether this is a rotation or a relaunch after a process death**,
        // which `savedInstanceState` cannot answer — it is non-null either way
        // — and which the sheet's restore needs opposite answers for.
        // `RecreationMarker` is a retained `ViewModel`: Android hands one to
        // the replacement activity down the configuration-relaunch path and
        // clears the store on any other destroy, so finding one that has
        // already been through `onCreate` means a configuration change and
        // nothing else. The trampoline reads the same marker for the same
        // reason; see its `onCreate`.
        //
        // Read before it is set, and set unconditionally, so a fresh launch
        // leaves the marker ready for whatever recreation comes next. In
        // memory only — no disk, nothing in front of the first frame.
        val wasRecreatedByConfiguration =
            ViewModelProvider(this)[RecreationMarker::class.java].let { marker ->
                val already = marker.created
                marker.created = true
                already
            }
        savedInstanceState?.let {
            screen = Screen.entries.firstOrNull { s -> s.name == it.getString(KEY_SCREEN) } ?: screen
            permissionsOrigin =
                Screen.entries.firstOrNull { s -> s.name == it.getString(KEY_PERMISSIONS_ORIGIN) }
                    ?: permissionsOrigin
            routedToPermissionsOnce = it.getBoolean(KEY_ROUTED_TO_PERMISSIONS_ONCE, routedToPermissionsOnce)
            restoreSheet(it, configurationChange = wasRecreatedByConfiguration)
        }
        store = ActiveSnoozeStore(applicationContext)
        promptStore = NotificationPromptStore(applicationContext)
        locationPromptStore = LocationPromptStore(applicationContext)
        calendarPromptStore = CalendarPromptStore(applicationContext)
        tileStore = TilePresenceStore(applicationContext)
        playUpdateChecker = PlayUpdateChecker(application)
        // Set once, for the activity's whole lifetime: unlike `recordWatch`/
        // `tileWatch`, this is a plain callback field on the checker, not a
        // registration with the platform — the platform-side registration
        // only happens inside `checkForUpdate`/`startUpdate`, and
        // `onDestroy`'s `unregisterInstallListener()` is what actually tears
        // that down.
        playUpdateChecker.setInstallStatusListener(::onPlayUpdateInstallStatus)
        playUpdateStore = PlayUpdateStore(applicationContext)
        zen = AndroidZenController.default(applicationContext)
        setContent {
            SnoozemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.MAIN -> MainScreen(
                            access = access,
                            tileAdded = tileAdded,
                            tileBannerDismissed = tileBannerDismissed,
                            snoozing = snoozing,
                            trackingMode = activeSnooze?.mode,
                            remaining = activeSnooze?.remaining(now),
                            degradation = activeSnooze?.degradation,
                            playUpdate = displayedPlayUpdate,
                            playUpdateRestartFailed = playUpdateRestartFailed,
                            backgroundLocationMissing = backgroundLocationMissing,
                            backgroundLocationBannerDismissed = backgroundLocationBannerDismissed,
                            telemetryUnanswered = telemetryUnanswered,
                            lastOutcome = lastOutcome,
                            crashPending = crashPending,
                            shareFailed = shareFailed,
                            dismissFailed = dismissFailed,
                            sharing = sharing,
                            settingsFailure = settingsFailure,
                            onOpenPermissions = { openPermissions(Screen.MAIN) },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onAddTile = ::addTile,
                            onDismissTileBanner = { tileStore.dismissBanner() },
                            // The location row's own routing, reused whole:
                            // an askable state launches the foreground
                            // request (already-granted returns immediately
                            // and its callback raises the disclosure), and a
                            // blocked one opens app settings, which is the
                            // only live route left. A banner-specific path
                            // would have to re-derive all of that, and would
                            // be the one that forgets the disclosure Play
                            // requires before the background prompt.
                            onAllowBackgroundLocation = ::fixLocation,
                            onAnswerTelemetry = ::answerTelemetry,
                            onDismissBackgroundLocationBanner = {
                                // Written and reflected in the same breath:
                                // unlike the tile store there is no listener
                                // on this file, because nothing outside this
                                // activity ever writes it.
                                locationPromptStore.dismissBackgroundBanner()
                                backgroundLocationBannerDismissed = true
                            },
                            onArm = ::armFromScreen,
                            onRelease = ::endFromScreen,
                            onShareDebugLog = ::shareDebugLog,
                            onDismissCrash = ::dismissCrash,
                            onStartPlayUpdate = ::startPlayUpdate,
                            onCompletePlayUpdate = ::completePlayUpdate,
                            onDismissPlayUpdate = ::dismissPlayUpdate,
                        )
                        Screen.PERMISSIONS -> {
                            // Falls back to whoever opened this screen —
                            // `permissionsOrigin`, saved and restored across a
                            // configuration change alongside `screen` itself
                            // (see `onCreate`/`onSaveInstanceState`), so a
                            // rotation here doesn't strand Done/back with
                            // nowhere it remembers coming from.
                            BackHandler { screen = permissionsOrigin }
                            PermissionsScreen(
                                access = access,
                                notifications = notifications,
                                notificationsReachTheUser = notificationsReachTheUser,
                                location = location,
                                calendar = calendar,
                                // The flavor seam, read at the call site like
                                // EndConditionSheet's (SPEC.md §3.4).
                                tracksDeparture = app.snoozemo.presence.PRESENCE_TRACKS_DEPARTURE,
                                ruleState = renderableRuleState,
                                settingsFailure = settingsFailure,
                                crashPending = crashPending,
                                shareFailed = shareFailed,
                                dismissFailed = dismissFailed,
                                sharing = sharing,
                                onAccessRow = ::openPolicyAccessSettings,
                                onRuleRow = ::openFilters,
                                onNotificationsRow = ::fixNotifications,
                                onLocationRow = ::fixLocation,
                                onCalendarRow = ::fixCalendar,
                                onDone = { screen = permissionsOrigin },
                                onShareDebugLog = ::shareDebugLog,
                                onDismissCrash = ::dismissCrash,
                            )
                        }
                        Screen.SETTINGS -> {
                            BackHandler { screen = Screen.MAIN }
                            SettingsScreen(
                                tileAdded = tileAdded,
                                filtersRuleId = filtersRuleId,
                                settingsFailure = settingsFailure,
                                debugLogEnabled = debugLogEnabled,
                                debugLogSaveFailed = debugLogSaveFailed,
                                crashReportingEnabled = crashReportingEnabled,
                                crashReportingSaveFailed = crashReportingSaveFailed,
                                askWhenToUnsnooze = askWhenToUnsnooze,
                                askWhenToUnsnoozeSaveFailed = askWhenToUnsnoozeSaveFailed,
                                snoozeRinger = snoozeRinger,
                                snoozeRingerSaveFailed = snoozeRingerSaveFailed,
                                playUpdate = displayedPlayUpdate,
                                playUpdateRestartFailed = playUpdateRestartFailed,
                                debugLogCleanupFailed = debugLogCleanupFailed,
                                shareFailed = shareFailed,
                                sharing = sharing,
                                crashPending = crashPending,
                                dismissFailed = dismissFailed,
                                versionName = BuildConfig.VERSION_NAME,
                                onOpenPermissions = { openPermissions(Screen.SETTINGS) },
                                onTileRow = ::addTile,
                                onFiltersRow = ::openFilters,
                                onDebugLog = ::setDebugLog,
                                onCrashReporting = ::setCrashReporting,
                                onAskWhenToUnsnooze = ::setAskWhenToUnsnooze,
                                onSnoozeRinger = ::chooseSnoozeRinger,
                                onStartPlayUpdate = ::startPlayUpdate,
                                onCompletePlayUpdate = ::completePlayUpdate,
                                onDismissPlayUpdate = ::dismissPlayUpdate,
                                onShareDebugLog = ::shareDebugLog,
                                onDismissCrash = ::dismissCrash,
                                onOpenPrivacyPolicy = ::openPrivacyPolicy,
                                onOpenLicenses = { screen = Screen.LICENSES },
                            )
                        }
                        // Reached only from Settings, so Back goes there rather
                        // than to Main. `screen` is persisted in the saved
                        // instance state above, so a rotation partway down the
                        // list stays on the page.
                        Screen.LICENSES -> {
                            BackHandler { screen = Screen.SETTINGS }
                            LicensesScreen(onBack = { screen = Screen.SETTINGS })
                        }
                    }
                    // The end-condition sheet (SPEC.md §4.4), when the arm
                    // came from this screen's `Snooze` button rather than the
                    // tile. A `ModalBottomSheet` rather than the trampoline's
                    // hand-built scrim: that one is drawn over a transparent
                    // activity with nothing behind it, while here there is a
                    // real screen to sit on and the platform's own sheet is
                    // what a user expects over one.
                    //
                    // Dismissing leaves the user correctly snoozed — §4.4 —
                    // and they are: the snooze is already running on its
                    // default cap, so the sheet only ever refines it.
                    sheet.endCondition?.let { condition ->
                        EndConditionBottomSheet(
                            condition = condition,
                            formattedTime = formatSheetTime(this@MainActivity, condition.endsAt),
                            committing = sheet.committing,
                            failed = sheet.commitFailed,
                            onChooseTime = { sheet.commit(condition.endsAt) },
                            onDismiss = sheet::dismiss,
                            onStepDown = sheet::stepDown,
                            onStepUp = sheet::stepUp,
                        )
                    }
                    // The one disclosure this app shows: SPEC.md §12 requires it
                    // precede the background-location prompt specifically (Play's
                    // restricted-permission declaration), so it appears as a
                    // small dialog over the settings screen rather than a
                    // dedicated screen of its own — same shape as the sibling
                    // ClothesCast repo's background-location rationale, which
                    // has already cleared Play review with this pattern.
                    if (showBackgroundLocationRationale) {
                        AlertDialog(
                            onDismissRequest = { showBackgroundLocationRationale = false },
                            title = { Text(stringResource(R.string.location_background_rationale_title)) },
                            text = { Text(stringResource(R.string.location_background_rationale_body)) },
                            confirmButton = {
                                TextButton(onClick = ::beginBackgroundLocationRequest) {
                                    Text(stringResource(R.string.location_background_rationale_continue))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showBackgroundLocationRationale = false }) {
                                    Text(stringResource(R.string.location_background_rationale_dismiss))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** The counterpart to `onCreate`'s restore, saved on every stop — see its comment. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
        outState.putString(KEY_PERMISSIONS_ORIGIN, permissionsOrigin.name)
        outState.putBoolean(KEY_ROUTED_TO_PERMISSIONS_ONCE, routedToPermissionsOnce)
        // The sheet survives a rotation, chosen time and all: stepping to a
        // time is the only work the user has done there, and dropping it would
        // leave them on the default cap having answered.
        outState.putBoolean(KEY_SHEET_COMMITTING, sheet.committing)
        outState.putLong(KEY_SHEET_REQUEST_ID, sheet.committingRequestId)
        sheet.offerFor?.let { outState.putLong(KEY_SHEET_OFFERED_FOR, it.toEpochMilli()) }
        outState.putBoolean(KEY_SHEET_FAILED, sheet.commitFailed)
        sheet.endCondition?.let {
            outState.putLong(KEY_SHEET_ENDS_AT, it.endsAt.toEpochMilli())
            outState.putLong(KEY_SHEET_FLOOR, it.floor.toEpochMilli())
            outState.putLong(KEY_SHEET_CEILING, it.ceiling.toEpochMilli())
        }
    }

    /** Puts a sheet that survived a configuration change back as it was. */
    private fun restoreSheet(state: Bundle, configurationChange: Boolean) {
        val saved = if (state.containsKey(KEY_SHEET_ENDS_AT)) {
            EndCondition(
                endsAt = Instant.ofEpochMilli(state.getLong(KEY_SHEET_ENDS_AT)),
                floor = Instant.ofEpochMilli(state.getLong(KEY_SHEET_FLOOR)),
                ceiling = Instant.ofEpochMilli(state.getLong(KEY_SHEET_CEILING)),
            )
        } else {
            null
        }
        if (saved != null) sheetGeneration++
        sheet.restore(
            condition = saved,
            wasCommitting = state.getBoolean(KEY_SHEET_COMMITTING),
            failed = state.getBoolean(KEY_SHEET_FAILED),
            configurationChange = configurationChange,
            requestId = state.getLong(KEY_SHEET_REQUEST_ID),
            offeredFor = if (state.containsKey(KEY_SHEET_OFFERED_FOR)) {
                Instant.ofEpochMilli(state.getLong(KEY_SHEET_OFFERED_FOR))
            } else {
                null
            },
        )
    }

    /**
     * Drops a restored or open sheet the running record can no longer honor.
     *
     * `onSaveInstanceState` cannot tell a rotation from a process death, so a
     * saved sheet comes back either way — and after a process death the snooze
     * it was refining may be long over, ended by a departure or by its own cap
     * while nothing of this app was running. Putting that sheet back unchecked
     * offers times against a snooze that no longer exists; the first tap would
     * come back `GONE` and dismiss, which is a screen answering a question it
     * should never have asked (Codex, PR #152).
     *
     * The same check covers the live case for free: a snooze that ends *under*
     * an open sheet now takes the sheet with it rather than waiting for a tap
     * to discover it. Whatever ended it has posted its own card, so nothing
     * here is silent (SPEC.md §7).
     *
     * Two things it will not do. It never touches a sheet with a commit in
     * flight — that answer is coming and settles the sheet itself, and a
     * dismissal underneath it would lose exactly the refusal message §4.2
     * says must reach a user who denied notifications. And it ignores a record
     * read that began before the sheet went up, since such a read predates the
     * arm the sheet belongs to.
     */
    @androidx.annotation.VisibleForTesting
    internal fun reconcileSheet(record: ActiveSnooze?, seenAtGeneration: Int) {
        // A record read that began before the sheet went up predates the arm
        // the sheet belongs to, so its answer says nothing about this offer.
        // The rest of the question is the controller's, and both hosts get it.
        if (seenAtGeneration != sheetGeneration) return
        sheet.reconcile(record, Instant.ofEpochMilli(SnoozeClock.read().wallMillis))
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
        // Followed for the same reason as the tile store: the writer is
        // `DebugLogging`'s worker, so a tap made in a previous instance — a
        // configuration change mid-toggle — completes after this instance has
        // already done its read, and its callback can only reach the dead
        // screen. The outcome watch, not a preferences listener, because only
        // the watch fires after the write's *result* is known — a preference
        // notification is dispatched before it (see `watchSaveOutcome`).
        // Same shape as the debug log's below, and for the same reason: a tap
        // whose write is still on the worker when a configuration change hands
        // off completes into the dead screen, so this instance's own read — which
        // has already run — would leave the switch showing the pre-tap value
        // until the next launch (Codex, PR #118).
        askWhenToUnsnoozeWatch = EndSheetSetting.watchSaveOutcome {
            runOnUiThread {
                if (askWhenToUnsnoozeWrites == 0) {
                    askWhenToUnsnooze = EndSheetStore(this).isEnabled()
                    askWhenToUnsnoozeSaveFailed = EndSheetSetting.lastSaveRefused
                }
            }
        }
        // The same watch, for the same reason, on the row below it.
        snoozeRingerWatch = SnoozeRingerSetting.watchSaveOutcome {
            runOnUiThread {
                if (snoozeRingerWrites == 0) {
                    snoozeRinger = SnoozeRingerStore(this).chosen()
                    snoozeRingerSaveFailed = SnoozeRingerSetting.lastSaveRefused
                }
            }
        }
        debugLogWatch = DebugLogging.watchSaveOutcome {
            runOnUiThread {
                if (debugLogWrites == 0) {
                    debugLogEnabled = DebugLogStore(this).isEnabled()
                    debugLogSaveFailed = DebugLogging.lastSaveRefused
                    // A retry-enable tapped on a previous instance can still
                    // be in flight when a configuration change hands off to
                    // this one: onStart's own read above already ran, so it
                    // can have copied the stale pre-completion
                    // lastDisableCleanupFailed — and nothing before this
                    // watch ever re-reads it for this instance, since the
                    // tap's own completion callback belongs to the dead one
                    // (Codex, PR #89).
                    debugLogCleanupFailed = DebugLogging.lastDisableCleanupFailed
                }
            }
        }
        // Followed for exactly the reason above, and it is not optional here
        // either: a tap on the crash-reporting switch is a privacy choice, so
        // a replacement instance left showing the pre-tap value would tell the
        // user they had turned reporting off when the write had not landed —
        // and would swallow a refused save with it (Codex, PR #113). A fresh
        // replacement's own `crashReportingWrites` is zero, so this is the
        // read that corrects it.
        crashReportingWatch = CrashReporting.watchSaveOutcome {
            runOnUiThread {
                if (crashReportingWrites == 0 && CrashReporting.isAvailable(this)) {
                    crashReportingEnabled = CrashReporting.collectionPermitted(this)
                    crashReportingSaveFailed = CrashReporting.lastSaveRefused
                    // The card, too, and for the same reason as the switch
                    // (Codex, PR #166). A recreation between a consent tap
                    // and the worker's commit gives the replacement
                    // `answered = false`, so it draws the card; the tap's own
                    // completion then lands on the destroyed instance and
                    // cannot correct it. Refreshing only the switch here left
                    // that stale card up until the next start, asking a
                    // question the user had already answered.
                    telemetryUnanswered = !CrashReporting.hasAnswered(this)
                }
            }
        }
        // Fresh on every start, same as `refreshSnoozing` above — a screen
        // reopened after sitting in the background for an hour must not show
        // the reading it had when it was last visible.
        now = SnoozeClock.read()
        tickHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        // Same shape as debugLogWatch, and for the same reason: a Share or
        // Dismiss tap's own completion runs on a worker thread and may not
        // land until after a configuration change has replaced this
        // instance. Re-reads the pin rather than trusting a captured value,
        // so it is correct regardless of which instance's tap triggered it.
        crashPinWatch = DebugLogging.watchCrashPinOutcome {
            // A failed check (checkSucceeded = false) is left alone rather
            // than applied as `false`: the metadata read itself threw, so
            // there is no fresh answer to show, and treating "couldn't
            // check" the same as "nothing pinned" would silently hide a
            // crash still genuinely sitting there (Codex, PR #89). Already
            // logged where the read actually failed.
            DebugLogging.hasPinnedCrash { pinned, checkSucceeded ->
                if (checkSucceeded) runOnUiThread { crashPending = pinned }
            }
            runOnUiThread { debugLogCleanupFailed = DebugLogging.lastDisableCleanupFailed }
        }
        // Same immediate-sync reason as shareFailed just below: a disable's
        // delete that finished while this activity was stopped fired the
        // watch above on a dead or not-yet-registered instance, so this can
        // already hold an outcome nothing has shown yet.
        debugLogCleanupFailed = DebugLogging.lastDisableCleanupFailed
        shareWatch = DebugReport.watchShareOutcome {
            runOnUiThread {
                shareFailed = DebugReport.lastShareFailed
                sharing = DebugReport.shareInFlight
            }
        }
        // Syncs immediately, unlike crashPending (which self-heals through
        // readNotificationsAfterFirstFrame's own hasPinnedCrash read): a share
        // that finished while this activity was stopped — backgrounded during
        // the background thread's binder/disk work, or a configuration change
        // — fired the watch above on a dead or not-yet-registered instance,
        // so lastShareFailed can already hold an outcome nothing has shown yet
        // (Codex, PR #89). A plain volatile read, not file I/O, so this costs
        // nothing in front of the first frame.
        shareFailed = DebugReport.lastShareFailed
        // Same read, same reason: a share started before a configuration
        // change is still running, and this replacement instance must show
        // its button disabled rather than re-offering a tap that is already
        // in flight.
        sharing = DebugReport.shareInFlight
        // Same shape and same reason as shareWatch/shareFailed just above,
        // for a Dismiss tap's own refused consume (Codex, PR #89).
        dismissWatch = DebugLogging.watchDismissOutcome {
            runOnUiThread { dismissFailed = DebugLogging.lastDismissFailed }
        }
        dismissFailed = DebugLogging.lastDismissFailed
    }

    /**
     * Checks Play for a waiting update, on the first frame and every later
     * resume — not `onStart`: Play's confirmation sheet returns here through
     * `onResume` without necessarily stopping this activity first, and
     * returning from it is exactly the moment the answer changes.
     */
    override fun onResume() {
        super.onResume()
        checkPlayUpdate()
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
        val sheetAt = sheetGeneration
        Thread {
            val loaded = store.load()
            runOnUiThread {
                if (refresh != latestSnoozingRefresh) return@runOnUiThread
                val running = loaded != null
                val changed = snoozing != running
                snoozing = running
                activeSnooze = loaded
                reconcileSheet(loaded, sheetAt)
                // Reconciling policy access reads whether a snooze is running,
                // so the pass in onStart ran before this was known. Re-run it
                // now that it is: access revoked while the service was dead has
                // to end the snooze (SPEC.md §8.2), and with the service gone
                // this screen can be the first thing in a position to notice.
                // Not a return from Settings, so the rule state it already has
                // is still the right thing to show while this re-reads access.
                if (changed) refreshAccess(ruleMayHaveChanged = false)
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
                refreshLocation()
                refreshCalendar()
                // Read here rather than in `onStart` for the same reason as the
                // rest: it is a preferences file, and no disk read belongs in
                // front of the first frame.
                tileAdded = tileStore.isAdded()
                tileBannerDismissed = tileStore.isBannerDismissed()
                // Beside the tile banner's, and off the first-frame path for
                // the same reason: another small preferences read, and this
                // activity is the only writer, so once is enough — no
                // listener the way the tile store needs one.
                backgroundLocationBannerDismissed =
                    locationPromptStore.isBackgroundBannerDismissed()
                // Gated on the reporter existing as well as the question
                // being open: `direct` has neither, and a build with no
                // `google-services.json` has nothing to switch on either, so
                // asking would be a question with no effect behind it.
                //
                // Held off while a tap's write is still on the worker, exactly
                // as the two reads below are: `answerTelemetry` retires the
                // card optimistically and only then queues the write, so a
                // stop/start of *this same instance* before it completes would
                // otherwise read the store's pre-tap `false` and put the card
                // back — able to take a second answer to a question already
                // answered. A recreated instance is the case this read exists
                // for and still gets it, since its own counter starts at zero
                // (Codex, PR #166).
                if (crashReportingWrites == 0) {
                    telemetryUnanswered =
                        CrashReporting.isAvailable(this) && !CrashReporting.hasAnswered(this)
                }
                // `zenRuleId` is deliberately not read here. Unlike the two
                // stores above, `zen.ruleId()` is the *unverified* persisted
                // value — see `zenRuleId`'s own comment — so it is left for
                // `ensureRuleInBackground` to set once `ensureRule()` has
                // actually confirmed it. `watchAccessAfterFirstFrame` (called
                // alongside this) is what starts that chain.
                // Its own one-key file, warmed by `DebugLogging.install` at
                // process start, so this is a memory hit like the two above.
                // Held off while a tap's write is still on the worker: the
                // store would answer with the value the tap is replacing.
                if (debugLogWrites == 0) {
                    debugLogEnabled = DebugLogStore(this).isEnabled()
                    debugLogSaveFailed = DebugLogging.lastSaveRefused
                    debugLogCleanupFailed = DebugLogging.lastDisableCleanupFailed
                }
                // Its own one-key file too, warmed by `CrashReporting.install`
                // at process start, so this is a memory hit like the reads
                // above. Held off the same way while a tap's write is still on
                // the worker. Left null — and so unrendered — where the build
                // carries no reporter at all (SPEC.md §3.4).
                if (crashReportingWrites == 0 && CrashReporting.isAvailable(this)) {
                    crashReportingEnabled = CrashReporting.collectionPermitted(this)
                    crashReportingSaveFailed = CrashReporting.lastSaveRefused
                }
                // Its own one-key file, and read here rather than in `onCreate`
                // for the same reason as the rows above — this runs after the
                // first frame, so a cold launch never waits on it. Held off
                // while a tap's write is still on the worker, same as above.
                if (askWhenToUnsnoozeWrites == 0) {
                    askWhenToUnsnooze = EndSheetStore(this).isEnabled()
                    // The failure alongside the value, exactly as the two reads
                    // above do it. Reading only the value meant a recreation
                    // after a refused write — a rotation, or the write landing
                    // between the old instance's `onStop` and the replacement's
                    // `onStart`, which the watch cannot cover — silently dropped
                    // the message while the outcome was still true (Codex,
                    // PR #118). A failure that disappears on rotation reads as
                    // a tap that worked.
                    askWhenToUnsnoozeSaveFailed = EndSheetSetting.lastSaveRefused
                }
                // Its own one-key file too, and read here for the same reason
                // as the rows above: this runs after the first frame, so a cold
                // launch never waits on it, and the read is held off while a
                // tap's write is still on the worker.
                if (snoozeRingerWrites == 0) {
                    snoozeRinger = SnoozeRingerStore(this).chosen()
                    snoozeRingerSaveFailed = SnoozeRingerSetting.lastSaveRefused
                }
                // And the other half of that setting: if a snooze ended without
                // handing the ringer back, put it back now (SPEC.md §5.9).
                // Opening the app is the moment a user whose phone is
                // unexpectedly quiet is most likely to reach, and `onCreate`'s
                // own check only runs on a fresh process — a long-lived one
                // would never reach it (Codex, PR #176). Its own thread, and a
                // no-op with nothing outstanding.
                reconcileRingerInBackground(this)
                // Its own one-key file too. `compareAndSet`-style guard isn't
                // needed the way a coroutine version would need one: this is
                // a single main-thread assignment, and a dismissal made
                // between `onCreate` and this read is only possible from a
                // tap on a banner that had nothing to dismiss yet (`playUpdate`
                // starts at `NotAvailable`), so there is nothing in flight to
                // clobber.
                dismissedPlayUpdateVersionCode = playUpdateStore.dismissedVersionCode
                // Async, unlike the reads above: this is a real file check on
                // the debug-log worker, not a cache hit off an
                // already-loaded preferences file. See `crashPending`'s own
                // comment for why once, here, is enough.
                // See crashPinWatch's own comment: a failed check is left
                // alone rather than downgraded to "nothing pinned". This is
                // the only read that ever runs for a fresh cold start,
                // though — nothing else re-checks until a Share/Dismiss/
                // settings-toggle outcome fires crashPinWatch — so leaving
                // it alone here would leave crashPending stuck at its
                // default false for this process's whole lifetime,
                // indistinguishable from a confirmed absence (Codex,
                // PR #89). One immediate retry is enough for the failure
                // this actually guards against: a momentary metadata-access
                // hiccup, not a persistent condition; a second consecutive
                // failure is already logged at the file layer and left as
                // the only case this can't self-heal without waiting for
                // the app's next launch.
                DebugLogging.hasPinnedCrash { pinned, checkSucceeded ->
                    if (checkSucceeded) {
                        runOnUiThread { crashPending = pinned }
                    } else {
                        DebugLogging.hasPinnedCrash { retryPinned, retrySucceeded ->
                            if (retrySucceeded) runOnUiThread { crashPending = retryPinned }
                        }
                    }
                }
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
     * Re-reads both location permissions onto the screen, and returns what it
     * read so a caller can act on the same answer — the same shape as
     * [refreshNotifications], for the same reasons: on the main thread because
     * `checkSelfPermission` and `shouldShowRequestPermissionRationale` are local
     * package-manager cache hits, not binder round trips, and every caller is
     * either already past the first frame or reacting to a dialog the user just
     * answered.
     */
    private fun refreshLocation(): LocationPermission {
        // FINE specifically, not FINE-or-COARSE: the presence engine's
        // departure test needs a fine fix and treats a downgrade to
        // coarse-only as a fatal capability loss, not a degraded-but-working
        // state (PlatformFixRequester, AnchorCaptureRunner both gate on
        // ACCESS_FINE_LOCATION alone) — so "granted" here has to mean the
        // grant the engine can actually use, or this row would say `Tracking
        // your place` over a snooze the engine has already given up watching
        // (Codex, PR #79). COARSE is still requested alongside FINE
        // (foregroundLocationPermission) and never checked on its own.
        val foregroundGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
        val backgroundGranted =
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PERMISSION_GRANTED
        val foregroundRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundRationale =
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        // Recorded unconditionally, like the notification history — a reading
        // with nothing new in it (`granted = false, rationale = false`) is
        // already a safe no-op in the store, so there is nothing to gate here.
        locationPromptStore.recordForeground(granted = foregroundGranted, rationale = foregroundRationale)
        // The background history is meaningless on a flavor that never
        // declares the permission — checkSelfPermission and
        // shouldShowRequestPermissionRationale both read as "never granted,
        // never denied" forever on `direct`, and recording that would just be
        // a write with nothing behind it.
        if (locationTrackingNeedsBackgroundPermission) {
            locationPromptStore.recordBackground(granted = backgroundGranted, rationale = backgroundRationale)
        }
        val current = LocationPermission.of(
            foregroundGranted = foregroundGranted,
            backgroundGranted = backgroundGranted,
            foregroundEverDenied = locationPromptStore.foregroundEverDenied(),
            foregroundRationale = foregroundRationale,
            backgroundEverDenied = locationPromptStore.backgroundEverDenied(),
            backgroundRationale = backgroundRationale,
            backgroundRequired = locationTrackingNeedsBackgroundPermission,
        )
        location = current
        // The banner's own reading, taken here so it moves with every other
        // permission read rather than needing its own refresh site. Gated on
        // the flavor for the same reason the history above is: `direct`
        // declares no such permission, so `checkSelfPermission` reads denied
        // forever and the banner would be permanent and unfixable.
        backgroundLocationMissing =
            locationTrackingNeedsBackgroundPermission && !backgroundGranted
        if (current == LocationPermission.GRANTED) clearFailure(SetupRowId.LOCATION)
        // A grant landing is the presence monitor's business as much as this
        // row's (SPEC.md §8.2). Android broadcasts no permission change, so
        // a monitor that degraded on a lost grant learned it was back only
        // from the §6.10 backstop's next wake — up to half an hour with §6.6
        // grace shut, so a user who left inside that window stayed quiet to
        // the cap (Codex, PR #150). This reading is the one place that sees
        // the grant land, whichever route it took: the prompt's callback, a
        // trip to Settings and back, or system App info before the app was
        // first opened — which is why an **unread** previous counts too,
        // exactly as [refreshCalendar]'s does, and once per activity
        // instance for the same reason.
        //
        // **Either raw grant rising, not the combined reading reaching
        // `GRANTED`** (Codex, PR #185). A fine grant with the background
        // half still denied leaves `current` below `GRANTED`, but it is
        // news the monitor acts on now: a latched `LOCATION_PERMISSION_GONE`
        // reclassifies to `NO_LOCATION_IN_BACKGROUND`, so the card stops
        // asking for the permission the user has just restored and names
        // the half still missing. Read from the two grants this reading
        // already took, since the enum cannot tell a fine-only `ASKABLE`
        // from none.
        //
        // The grant direction only. A revocation kills the process, so the
        // cold restore is what sees it, and the monitor reads the loss
        // itself from the refusal it gets.
        //
        // Only while a snooze is running — read from the store, not
        // `activeSnooze`, for the reason [refreshCalendar] gives — and only
        // on a flavor whose monitor reads location at all: `direct` watches
        // no grant, so the start would be a service woken for nothing.
        val previousGrants = locationGrantsRead
        val grants = LocationGrants(foregroundGranted, backgroundGranted)
        locationGrantsRead = grants
        val grantLanded = grants.risesFrom(previousGrants)
        if (grantLanded && app.snoozemo.presence.PRESENCE_TRACKS_DEPARTURE && store.load() != null) {
            SnoozeService.locationGranted(this)
        }
        return current
    }

    /**
     * What tapping the location row does. `ASKABLE` launches the foreground
     * request directly, the same as every other row's runtime prompt — no
     * disclosure precedes it (see [foregroundLocationPermission]).
     */
    /**
     * The consent card's answer, either way (`SPEC.md` §12).
     *
     * The card is retired **immediately**, before the write lands: it asked a
     * question and got one, so leaving it up while a worker writes would read
     * as the tap having done nothing, and a second tap would ask the same
     * question again. The write's own outcome reaches the Settings switch,
     * which is where a refused save is already explained.
     *
     * **Nothing separates this from the Settings switch any more**, and that
     * is the fix rather than a simplification: both are the user deciding, so
     * both record an answer. Treating only the card as an answer left a fresh
     * install able to turn collection on from a Settings row that said
     * nothing about analytics, with the card still standing unanswered
     * (Codex, PR #166).
     */
    private fun answerTelemetry(enabled: Boolean) {
        telemetryUnanswered = false
        crashReportingEnabled = enabled
        crashReportingWrites++
        CrashReporting.setEnabled(this, enabled) { _ ->
            runOnUiThread {
                // Counted like the Settings switch's writes, and for the same
                // reason: a rotation between the tap and the commit hands the
                // screen off, and the replacement's own `onStart` read can run
                // while `answered` is still false — which put the card back up
                // and left it there, since the completion callback belonged to
                // the destroyed instance (Codex, PR #166). Reconciling from
                // the store on the surviving instance is what closes that; the
                // counter is what stops an earlier write repainting over a
                // later tap.
                crashReportingWrites--
                if (crashReportingWrites == 0) {
                    crashReportingEnabled = CrashReporting.collectionPermitted(this)
                    telemetryUnanswered = !CrashReporting.hasAnswered(this)
                    // The failure line too (Codex, PR #166). The process-level
                    // watcher copies this, but it runs while this write is
                    // still outstanding and skips for that reason — so on the
                    // card's own path nothing else was going to. Without it a
                    // refused answer put the card back with no explanation,
                    // and Settings showed a clean switch in the same session:
                    // the user taps Yes please, the question returns, and
                    // nothing anywhere says a write failed. Principle 2's
                    // failure exactly.
                    crashReportingSaveFailed = CrashReporting.lastSaveRefused
                }
            }
        }
    }

    private fun fixLocation() {
        when (refreshLocation()) {
            LocationPermission.ASKABLE -> beginLocationRequest()
            // Granted, or asked for as often as the system allows. Either way
            // the only route left is the app's own permission settings — there
            // is no single-permission deep link for location the way
            // notifications has `ACTION_APP_NOTIFICATION_SETTINGS`.
            LocationPermission.GRANTED, LocationPermission.BLOCKED ->
                openSettings(
                    SetupRowId.LOCATION,
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null)),
                )
        }
    }

    /**
     * Launches the foreground request, mirroring [askForNotifications]:
     * launching is not spending, so nothing is recorded here. [refreshLocation]
     * is what learns whether a denial actually landed.
     */
    private fun beginLocationRequest() {
        foregroundLocationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    /**
     * Launches the background request from the rationale dialog's Continue —
     * see [showBackgroundLocationRationale]. Internal, like
     * [onForegroundLocationResult], so a test can drive Continue directly
     * and confirm it closes the dialog.
     */
    internal fun beginBackgroundLocationRequest() {
        showBackgroundLocationRationale = false
        backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    /**
     * Re-reads `READ_CALENDAR` onto the screen and returns what it read — the
     * same shape and the same main-thread reasoning as [refreshLocation].
     */
    private fun refreshCalendar(): CalendarPermission {
        val granted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PERMISSION_GRANTED
        val rationale = shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
        // Unconditional, like the other two histories: a reading with nothing
        // new in it is already a no-op in the store.
        calendarPromptStore.record(granted = granted, rationale = rationale)
        val current = CalendarPermission.of(
            granted = granted,
            everDenied = calendarPromptStore.everDenied(),
            rationale = rationale,
        )
        val previous = calendar
        calendar = current
        if (current == CalendarPermission.GRANTED) clearFailure(SetupRowId.CALENDAR)
        // A change that arrived while this screen was in the background — the
        // `BLOCKED` row opens Settings rather than a runtime prompt, and a
        // revocation has no in-app route at all, so this reading is the only
        // thing that sees either (Codex, PR #156). Without a repost, a snooze
        // already running keeps the card it had, and on a duration-only snooze
        // nothing rebuilds it again before the cap.
        //
        // **Both directions**, because the card is wrong either way: a grant
        // never shows the action for the snooze it was granted during, and a
        // revocation leaves `Until <time>` standing over a calendar Snoozemo
        // can no longer read — which the offer cache's own key promises it
        // will not.
        //
        // Keyed on readability rather than the enum, so the transitions that
        // change nothing about the card — `ASKABLE` to `BLOCKED`, say — do not
        // repost.
        //
        // An **unread** `previous` reposts too, which is not the same as a
        // transition and is why it is spelled out separately. The tile arms
        // without this screen ever being created (SPEC.md §4.2), so a card can
        // already be up, built and cached under whatever the permission was
        // then — and a grant taken in system App info before the app is first
        // opened would otherwise never reach it. Once per activity instance,
        // and only while a snooze is running; the offer cache makes it a plain
        // rebuild rather than a second calendar read when nothing changed.
        val couldRead = previous == CalendarPermission.GRANTED
        val canRead = current == CalendarPermission.GRANTED
        // **Only while a snooze is actually running**, and that gate covers both
        // branches. `ACTION_REFRESH` with no record is not a no-op: it replays
        // whatever the last arm failed on, and failing that falls through to a
        // policy-access check that posts `Couldn't snooze`. On a fresh setup —
        // no snooze, Do Not Disturb access not granted yet — allowing the
        // calendar row would then raise a card about an arm nobody attempted
        // (Codex, PR #156).
        //
        // The record is read straight from the store rather than from
        // `snoozing`, which a worker fills in with no ordering against this:
        // on a first read the two race, and the `previous == null` branch is
        // exactly the one that would lose it, with nothing to recheck when the
        // load lands. Reading here removes the second source of truth instead
        // of interlocking with it, and costs a preferences hit on a path that
        // already takes two beside it, well past the first frame.
        val readabilityChanged = if (previous == null) true else couldRead != canRead
        if (readabilityChanged && store.load() != null) SnoozeService.refresh(this)
        return current
    }

    /**
     * What tapping the calendar row does — [fixLocation]'s shape exactly:
     * `ASKABLE` launches the prompt, and both other states go to the app's own
     * permission settings, since the platform offers no single-permission deep
     * link for the calendar either.
     */
    private fun fixCalendar() {
        when (refreshCalendar()) {
            CalendarPermission.ASKABLE -> calendarPermission.launch(Manifest.permission.READ_CALENDAR)
            CalendarPermission.GRANTED, CalendarPermission.BLOCKED ->
                openSettings(
                    SetupRowId.CALENDAR,
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null)),
                )
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
        // Invalidate every access reading still in flight. The lifecycle check
        // in `applyAccess` covers a worker landing while the screen is stopped,
        // but not one landing after this same instance is STARTED again —
        // there is a window before the deferred refresh issues a fresh
        // generation, and a stale `DENIED` read acting inside it would end a
        // snooze armed in between (deferred from Codex's PR #8 review). A
        // reading taken in one visible session may never act in the next.
        latestAccessRefresh++
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
        askWhenToUnsnoozeWatch?.close()
        askWhenToUnsnoozeWatch = null
        snoozeRingerWatch?.close()
        snoozeRingerWatch = null
        debugLogWatch?.close()
        debugLogWatch = null
        crashReportingWatch?.close()
        crashReportingWatch = null
        // The one background wakeup this screen owns on its own — every
        // minute while visible is negligible, but only while visible. Left
        // running past `onStop` would tick a screen nobody can see.
        tickHandler.removeCallbacks(tickRunnable)
        crashPinWatch?.close()
        crashPinWatch = null
        shareWatch?.close()
        shareWatch = null
        dismissWatch?.close()
        dismissWatch = null
    }

    override fun onDestroy() {
        // The outcome channel holds a lambda reaching this activity, so an
        // unclosed watch outlives the sheet it was answering and leaks the
        // activity with it. Idempotent, and a no-op on the ordinary session
        // that never opened a sheet at all.
        sheet.close()
        // One checker per activity instance, so drop its install listener
        // here or Play's update manager would retain a dead one — capturing
        // this activity — on every recreation. Idempotent, and guarded like
        // the tile row's own `lateinit` checks: `onCreate` sets this early,
        // but nothing here should assume it always ran first.
        if (::playUpdateChecker.isInitialized) {
            playUpdateChecker.unregisterInstallListener()
        }
        super.onDestroy()
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
    /**
     * [refreshAccess] for a test, which cannot reach a private method and has
     * no seam on the background rule check itself — same reason
     * `MainActivityFiltersIntentTest` drives [openFilters] directly.
     */
    internal fun refreshAccessForTest(ruleMayHaveChanged: Boolean = true) =
        refreshAccess(ruleMayHaveChanged)

    private fun refreshAccess(ruleMayHaveChanged: Boolean = true) {
        val running = snoozing == true
        // Which refresh this is. Several can be in flight at once — `onStart`,
        // the access broadcast, and every record change all call this — and
        // they finish in whatever order the binder calls return, not the order
        // they started. Without this, a slow `GRANTED` read can land *after* a
        // later revocation and paint the screen back to granted, re-enabling an
        // arm that cannot succeed. Only the newest result is allowed on screen.
        val refresh = ++latestAccessRefresh
        // Coming back from Settings, the user has very likely just changed the
        // thing being re-read, so the answer on screen is no longer one to
        // trust: the permissions screen hides its capability claim while a
        // check is in flight. What it does *not* do is throw the answer away —
        // `zenRuleState` keeps the last verified one, so a check that ends
        // without producing a new answer leaves the row saying what was last
        // known rather than disappearing.
        //
        // An earlier shape cleared the state and had each failure path put it
        // back. That needed a restore on every path that can end without an
        // answer — there are two, and the second was missed — and two
        // overlapping refreshes could still restore a null the other had just
        // written (Codex, PR #171, three rounds). Marking rather than clearing
        // has neither problem: nothing to restore, and nothing to race over.
        //
        // Not on a record change: that caller cannot follow a trip to Settings,
        // so its re-read has no reason to hide an answer that is still good.
        //
        // It does take the marker *over* when one is already outstanding,
        // though. Every refresh supersedes the one before it, so the older
        // check's answer will be rejected as stale — and if the marker still
        // named that older generation, this refresh's own check could not
        // retire it and the row would stay hidden until some later
        // invalidating refresh happened along (Codex, PR #171).
        if (ruleMayHaveChanged || ruleCheckInFlight != null) ruleCheckInFlight = refresh
        Thread {
            // Contained: a bare thread has no handler, so a refused binder read
            // would take the process down from a screen doing nothing but
            // reconciling. Keeping whatever the screen last knew is the chosen
            // outcome (maintainer, 2026-08-31), not an absence of one — and it
            // is chosen over the two alternatives rather than by default.
            //
            // **"The next refresh asks again" is true of two callers and false
            // of the third, which is the one that matters.** `onStart` and a
            // record change both come round again on their own. The access
            // *broadcast* does not: the platform says only that access changed,
            // so the read that failed was itself the notification, and nothing
            // is queued behind it. A revocation missed that way leaves the
            // screen offering an `Arm` that cannot succeed until the user
            // leaves and comes back.
            //
            // That is survivable, which is why it stands. The service owns the
            // arm and posts `Couldn't snooze`, so the outcome is wrong rather
            // than silent; the reverse staleness shows the banner and hides
            // `Arm`, which is the safe direction anyway. Clearing `access` to
            // unknown instead was considered and rejected: `MainScreen` gates
            // the whole primary-action block on `GRANTED`, so it would take
            // `Release` away from a running snooze to report a binder blip —
            // and on a *first*-read failure it changes nothing, since `access`
            // is already null there. Retrying behind an injected executor is
            // the only option that actually closes the window, and it is
            // available if this is ever seen in the field; it was not worth the
            // machinery for a read that fails only when the process is going
            // down with it.
            //
            // `zenRuleId` is left alone, and the gate that makes that mostly
            // safe covers only one direction. `filtersRuleId` is
            // `zenRuleId.takeIf { access == GRANTED }`, so a stale *denied*
            // reading hides the row — but a stale *granted* one, which is what
            // a failed revocation broadcast leaves, still offers a Filters row
            // deep-linking a rule the revocation removed (Codex, PR #159).
            // Tracked rather than fixed: it needs the revocation and the read
            // failure together, it costs a dead link rather than a snooze, and
            // clearing the id here would blank a working row every time the
            // read merely blipped.
            val current = runCatching { zen.policyAccess() }.getOrElse {
                Log.e(TAG, "Reading policy access failed; leaving the screen as it is.", it)
                // No answer is coming, so this check stops being in flight and
                // the last verified one is current again — the screen shows
                // what it knew rather than nothing at all.
                runOnUiThread { finishRuleCheck(refresh) }
                return@Thread
            }
            runOnUiThread { applyAccess(refresh, current, running) }
        }.also {
            // Held only so a test can join it: the read runs on a raw thread
            // with no other seam, and joining is explicit ordering rather than
            // the sleep or poll this repo's testing rules rule out.
            lastAccessRead = it
            it.start()
        }
    }

    /** The thread [refreshAccess] last started. Test seam only; see above. */
    internal var lastAccessRead: Thread? = null
        private set

    /** The same, for [ensureRuleInBackground]'s check. */
    internal var lastRuleCheck: Thread? = null
        private set

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
        // Stopped: nothing further runs for this refresh, so it stops being in
        // flight. A stale one is left alone — a newer refresh owns the marker,
        // and clearing it here would declare that newer answer current early.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            finishRuleCheck(refresh)
            return
        }
        if (refresh != latestAccessRefresh) return
        access = current
        // The one routing decision this screen makes on its own: land a user
        // with no Do Not Disturb access straight on the interstitial the first
        // time that becomes known, rather than on a Main screen whose Arm
        // button is disabled with nothing else on it yet explaining why.
        // Once only — every later reading follows MainScreen's banner instead
        // (SPEC.md §8.2: access lost mid-snooze must not yank the user off
        // whatever they were doing), and only if nothing has already
        // navigated away from Main on its own.
        if (!routedToPermissionsOnce) {
            routedToPermissionsOnce = true
            if (current != PolicyAccess.GRANTED && screen == Screen.MAIN) {
                openPermissions(Screen.MAIN)
            }
        }
        // A failure describes a tap that could not reach Settings, and the row
        // carrying it loses its button the moment access is granted — so
        // nothing would clear it, and the row would read `Granted` above
        // `Couldn't open Settings` until the activity was recreated. Access can
        // arrive from anywhere while this screen is up: another route into
        // Settings, an administrator, the receiver above (flagged by Codex on
        // PR #21).
        if (current == PolicyAccess.GRANTED) clearFailure(SetupRowId.DND)
        val action = PolicyAccessChange.resolve(current, running)
        when (action) {
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
        // Every branch but the rule check itself ends this refresh here, so the
        // marker has to come off — otherwise a revocation, which runs
        // `EndSnooze` and never reaches a rule check, would leave the screen
        // waiting for an answer nothing is going to produce.
        if (action != PolicyAccessAction.EnsureRule) finishRuleCheck(refresh)
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
        val work = Thread {
            // Same trigger as the rule below: access just arrived, which is
            // exactly when a channel `warm()` created before onboarding granted
            // it needs to be re-issued so its DND-bypass actually takes
            // (SnoozeNotifications.reapplyDndBypass). Fire-and-forget, like the
            // rule check — a later reconciliation retries on failure.
            SnoozeNotifications(applicationContext).reapplyDndBypass()
            val state = runCatching { zen.ensureRule() }.getOrElse {
                Log.e(TAG, "Ensuring the zen rule failed; leaving the screen as it is.", it)
                // Same as the read above: no answer, so the last verified one
                // stands rather than the row disappearing.
                runOnUiThread { finishRuleCheck(refresh) }
                return@Thread
            }
            // `zenRuleId`, published or cleared before anything else below
            // reads `state`. READY and DISABLED are the two outcomes
            // `ensureRule()` reaches only after confirming the rule exists —
            // freshly created, or looked up and found present-but-off — and a
            // disabled rule is not a rule that "doesn't exist yet", so
            // Filters stays offered for it too. FAILED means the lookup
            // itself didn't complete, so a previously published id is no
            // longer confirmed current: a rule deleted from system Settings
            // while this screen was backgrounded, then re-verified with a
            // transient binder failure, must not leave that dead id tappable
            // indefinitely (Codex, PR #88). Unlike the arm path's own id,
            // which deliberately *keeps* a FAILED reading's id
            // (`AndroidZenController`) because an active rule must stay
            // nameable, hiding this convenience row on uncertainty costs
            // nothing — it reappears on the next successful check.
            // MISSING_ACCESS touches nothing: `filtersRuleId` already hides
            // the row on `access` alone.
            when (state) {
                ZenRuleState.READY, ZenRuleState.DISABLED -> {
                    val id = zen.ruleId()
                    runOnUiThread { if (refresh == latestAccessRefresh) zenRuleId = id }
                }
                ZenRuleState.FAILED -> {
                    runOnUiThread { if (refresh == latestAccessRefresh) zenRuleId = null }
                }
                ZenRuleState.MISSING_ACCESS -> Unit
            }
            // Behind the same staleness guard as the id above, so a superseded
            // check cannot publish its answer over a newer one.
            runOnUiThread {
                if (refresh == latestAccessRefresh) zenRuleState = state
                finishRuleCheck(refresh)
            }
            val outcome = when (state) {
                ZenRuleState.FAILED -> R.string.rule_failed
                // Switched off in Settings: the app cannot snooze and must say
                // so rather than looking ready. Snoozemo does not re-enable it
                // — that switch is the user's (SPEC.md §5.1).
                ZenRuleState.DISABLED -> R.string.rule_disabled
                ZenRuleState.READY, ZenRuleState.MISSING_ACCESS -> {
                    // READY refutes a rule-status message an earlier check left
                    // standing — the user re-enabled the rule in Settings, and
                    // the screen would otherwise keep claiming it is off until
                    // the activity was recreated. Only that message: the slot is
                    // shared with arm/end failures a rule check knows nothing
                    // about, so those stand. MISSING_ACCESS retires nothing —
                    // see `StaleRuleClaim`.
                    if (StaleRuleClaim.refutedBy(state)) {
                        runOnUiThread {
                            if (refresh != latestAccessRefresh) return@runOnUiThread
                            if (lastOutcome == getString(R.string.rule_disabled) ||
                                lastOutcome == getString(R.string.rule_failed)
                            ) {
                                lastOutcome = null
                            }
                        }
                    }
                    return@Thread
                }
            }
            runOnUiThread {
                // A rule lookup can take a while, so a refresh that was current
                // when this started may not be by the time it answers.
                if (refresh != latestAccessRefresh) return@runOnUiThread
                lastOutcome = getString(outcome)
            }
        }
        // Held for the same reason the access read's thread is: a test needs to
        // join it, and joining is explicit ordering rather than a sleep.
        lastRuleCheck = work
        work.start()
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
        if (SnoozeService.arm(this)) {
            offerSheetForThisArm()
            return
        }
        Log.e(TAG, "Starting the service to arm was refused.")
        lastOutcome = getString(R.string.failure_could_not_start)
    }

    /**
     * Offers the §4.4 sheet for the arm just requested — **one shot, no debt**.
     *
     * The trampoline reads the record once after its own service start and
     * shows no sheet where the arm has not landed; this does the same, which
     * is the point of the change. An earlier version held a "sheet owed" flag
     * until a record turned up, and that flag had no way to expire: an arm the
     * service accepted but failed to complete — no policy access, a zen rule
     * that would not go on — left it set for the life of the screen, and the
     * *next* record from anywhere, a tile arm included, opened a sheet nobody
     * had asked this screen for (Codex, PR #152). A one-shot read cannot go
     * stale, and where it loses the race it fails exactly as the tile does: no
     * sheet, over a snooze correctly armed on its cap.
     *
     * Both reads are off the main thread — the setting is a disk-backed file
     * and the record may still be loading, and neither belongs in front of a
     * frame. After the service start, never before it (SPEC.md §6.9).
     *
     * No keyguard test, unlike the tile's: this screen is in front of an
     * unlocked phone by definition.
     */
    @androidx.annotation.VisibleForTesting
    internal fun offerSheetForThisArm() {
        val offer = ++latestSheetOffer
        // **Posted, and the post is load-bearing** — the same ordering the
        // trampoline's own decision uses. `startService` only enqueues
        // `onStartCommand`, which runs on this looper and is what writes the
        // record; reading straight away would race it, and on a fresh arm the
        // read would win and find nothing, so the sheet would almost never
        // appear at all. Posting puts this behind that message.
        window.decorView.post {
            runOffMainThread {
                val ask = EndSheetStore(this).isEnabled()
                val loaded = if (ask) store.load() else null
                runOnUiThread {
                    // A newer arm has since been made; this answer is stale.
                    if (offer != latestSheetOffer) return@runOnUiThread
                    // No record: this arm did not land, so there is nothing to
                    // refine and nothing left owed.
                    if (loaded == null) return@runOnUiThread
                    // The wall clock, because the record's cap and the times
                    // the sheet offers are both written in that frame.
                    val wallNow = Instant.ofEpochMilli(SnoozeClock.read().wallMillis)
                    if (!EndCondition.offersAChoice(loaded, wallNow)) return@runOnUiThread
                    sheetGeneration++
                    sheet.seed(loaded, wallNow)
                }
            }
        }
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
     * Applies the debug-log switch, and puts the truth back if it didn't take.
     *
     * The switch flips at once — the store write and the file work run on the
     * log's own worker, and a control that waits on disk is a control that
     * feels broken — but only a *persisted* choice is applied
     * (`DebugLogging.setEnabled`): a write the storage refused would otherwise
     * look applied and silently revert at the next process start (deferred
     * from Codex's PR #62 review).
     *
     * Every completion reconciles the switch **from the store**, rather than
     * reverting to a value captured at the tap: the store is authoritative on
     * success and failure alike, since a refused write restores it. Only the
     * last outstanding write repaints ([debugLogWrites]), so a later tap's
     * value is never clobbered by an earlier tap's completion — the callbacks
     * arrive in tap order off one FIFO worker.
     *
     * A failed save also says so under the row, not only by the snap-back:
     * a switch that quietly returns to where it was reads as a missed tap,
     * which is principle 2's failure on the screen built to prevent it.
     */
    /**
     * The `Ask when to unsnooze` switch (SPEC.md §4.4).
     *
     * The same shape as [setDebugLog] and for the same reasons: the tap is shown
     * immediately, the write goes to a worker, and only the *last* outstanding
     * write repaints — surfacing an earlier write's refusal would leave a
     * failure line standing over a later tap that saved fine. A failed save says
     * so under the row, not only by the snap-back: a switch that quietly returns
     * to where it was reads as a missed tap.
     *
     * Simpler than [setDebugLog] in one way that matters — there is nothing to
     * clean up. Turning the debug log off deletes what it kept; turning this off
     * only stops a sheet appearing, so there is no second failure to report.
     */
    private fun setAskWhenToUnsnooze(enabled: Boolean) {
        if (askWhenToUnsnooze == null) return
        askWhenToUnsnoozeSaveFailed = false
        askWhenToUnsnoozeWrites++
        askWhenToUnsnooze = enabled
        EndSheetSetting.setEnabled(this, enabled) { _ ->
            runOnUiThread {
                askWhenToUnsnoozeWrites--
                if (askWhenToUnsnoozeWrites == 0) {
                    askWhenToUnsnooze = EndSheetStore(this).isEnabled()
                    // The process-level outcome rather than this callback's own
                    // argument, so it reads the same value the watch would — one
                    // source, whichever path gets here first.
                    askWhenToUnsnoozeSaveFailed = EndSheetSetting.lastSaveRefused
                }
            }
        }
    }

    /**
     * The chosen ceiling, optimistically shown and reconciled from the worker —
     * the same shape as the switch above, and with the same reason for the
     * write counter: a rotation mid-write must not repaint the stored value
     * over a tap that has not landed yet.
     *
     * Applies to the next snooze, not one already running: see
     * [SnoozeRingerSetting.setChosen].
     *
     * `chooseSnoozeRinger` rather than `setSnoozeRinger`, because the latter is
     * the JVM signature the nullable [snoozeRinger] property's own setter
     * already takes — a nullable and a non-null `SnoozeRinger` erase to the
     * same parameter type, so the two would clash. (The switch above gets away
     * with the `set` prefix only because `Boolean?` boxes and `Boolean` does
     * not.)
     */
    private fun chooseSnoozeRinger(ceiling: SnoozeRinger) {
        if (snoozeRinger == null) return
        snoozeRingerSaveFailed = false
        snoozeRingerWrites++
        snoozeRinger = ceiling
        SnoozeRingerSetting.setChosen(this, ceiling) { _ ->
            runOnUiThread {
                snoozeRingerWrites--
                if (snoozeRingerWrites == 0) {
                    snoozeRinger = SnoozeRingerStore(this).chosen()
                    snoozeRingerSaveFailed = SnoozeRingerSetting.lastSaveRefused
                }
            }
        }
    }

    private fun setDebugLog(enabled: Boolean) {
        if (debugLogEnabled == null) return
        debugLogSaveFailed = false
        debugLogCleanupFailed = false
        debugLogWrites++
        debugLogEnabled = enabled
        DebugLogging.setEnabled(this, enabled) { _ ->
            runOnUiThread {
                // Only the last outstanding write repaints — the value *and*
                // the failure line. Surfacing an earlier write's refusal here
                // would leave `Couldn't save this setting` standing over a
                // later tap that saved fine (flagged by Codex on PR #68); the
                // process-level outcome is written per-completion on the FIFO
                // worker, so once the queue drains it is the latest write's.
                debugLogWrites--
                if (debugLogWrites == 0) {
                    debugLogEnabled = DebugLogStore(this).isEnabled()
                    debugLogSaveFailed = DebugLogging.lastSaveRefused
                    // Always reconciled, not only on a requested disable: a
                    // requested *enable* that storage refused never reaches
                    // DebugLogging.setEnabled's persisted branch at all, so
                    // lastDisableCleanupFailed is left exactly as it was —
                    // gating this read on the requested `enabled` value
                    // read that untouched field as if the enable had
                    // succeeded, hiding a real retained-files warning
                    // behind a switch that had actually just snapped back
                    // to Off (Codex, PR #89). Safe unconditionally: a
                    // successful enable already clears the field itself,
                    // synchronously, before this callback ever runs.
                    debugLogCleanupFailed = DebugLogging.lastDisableCleanupFailed
                }
            }
        }
    }

    /**
     * The crash-reporting switch (SPEC.md §12), same shape as [setDebugLog]:
     * the tap is shown immediately, the write goes to a worker, and the
     * completion reconciles the switch from the store — which by then holds
     * the truth on success and failure alike.
     */
    private fun setCrashReporting(enabled: Boolean) {
        // Nothing to toggle before the store has answered, or on a build with
        // no reporter — where this stays null and the row is never drawn.
        if (crashReportingEnabled == null) return
        crashReportingSaveFailed = false
        crashReportingWrites++
        crashReportingEnabled = enabled
        CrashReporting.setEnabled(this, enabled) { _ ->
            runOnUiThread {
                // Only the last outstanding write repaints — the value *and*
                // the failure line — so an earlier write's refusal can't leave
                // `Couldn't save this setting` standing over a later tap that
                // saved fine. The process-level outcome is written
                // per-completion on the FIFO worker, so once the queue drains
                // it is the latest write's.
                crashReportingWrites--
                if (crashReportingWrites == 0) {
                    crashReportingEnabled = CrashReporting.collectionPermitted(this)
                    crashReportingSaveFailed = CrashReporting.lastSaveRefused
                    // Moving the switch answers the question, so the card must
                    // go with it — read back rather than assumed, so a refused
                    // write leaves the card standing and the user is asked
                    // rather than silently recorded as having answered.
                    telemetryUnanswered = !CrashReporting.hasAnswered(this)
                }
            }
        }
    }

    /** Asks Play what's waiting and hands the answer to the banner's state. */
    private fun checkPlayUpdate() {
        val refresh = ++latestPlayUpdateRefresh
        playUpdateChecker.checkForUpdate(
            onAvailable = { versionCode, installStatus ->
                if (refresh == latestPlayUpdateRefresh) setPlayUpdateAvailable(versionCode, installStatus)
            },
            onUnavailable = { if (refresh == latestPlayUpdateRefresh) setPlayUpdateUnavailable() },
            // No guard needed: setPlayUpdateCheckFailed() is a no-op either way.
            onCheckFailed = ::setPlayUpdateCheckFailed,
        )
    }

    /**
     * Play reports a flexible update waiting (or one already downloading).
     *
     * Internal rather than private, like [setPlayUpdateCheckFailed] and
     * [setPlayUpdateProgress] below, only so a test can drive the banner's
     * state machine directly — the real trigger, [PlayUpdateChecker], talks
     * to Play Services, which has no seam a JVM test can drive
     * deterministically.
     */
    internal fun setPlayUpdateAvailable(availableVersionCode: Int?, installStatus: Int) {
        val previous = playUpdate as? PlayUpdateState.Available
        if (previous?.versionCode != availableVersionCode) {
            // A different build than the banner was tracking: whatever
            // progress we were showing belonged to the old one, and so does
            // any Restart failure — it described a tap on a build that has
            // since finished downloading or been superseded.
            currentPlayUpdateProgress = UpdateProgress.Idle
            playUpdateRestartFailed = false
        }
        val progress = progressForInstallStatus(installStatus, fallback = currentPlayUpdateProgress)
        currentPlayUpdateProgress = progress
        playUpdate = PlayUpdateState.Available(versionCode = availableVersionCode, progress = progress)
    }

    /** Play checked and there is nothing waiting: no banner. */
    private fun setPlayUpdateUnavailable() {
        currentPlayUpdateProgress = UpdateProgress.Idle
        playUpdateRestartFailed = false
        playUpdate = PlayUpdateState.NotAvailable
    }

    /**
     * The *check* failed (flaky network, Play transiently unavailable) —
     * which is not the same as "no update". It carries no information, so
     * the banner is left exactly as it is, in every progress state
     * including `Starting`.
     *
     * An earlier version reset `Starting` back to `Idle` here, reasoning
     * that a failed recheck can't hand back the update handle the Play
     * sheet consumed and would otherwise strand the banner on "Updating…".
     * That raced [PlayUpdateChecker.startUpdate]'s own install listener,
     * which is registered *before* the sheet opens — so a failed recheck
     * landing before that listener's first `PENDING`/`DOWNLOADING` callback
     * would snap a real, in-progress download's banner back to "Update",
     * exposing a button whose cached handle was already consumed (Codex, PR
     * #99). Both of the real cases that reset needed are covered more
     * reliably elsewhere and don't depend on a recheck at all: declining
     * the sheet is caught directly by [playUpdateLauncher]'s own result
     * callback (`resultCode != RESULT_OK`), and a genuine download is heard
     * from directly by that same install listener.
     */
    internal fun setPlayUpdateCheckFailed() = Unit

    /**
     * The banner's Dismiss: hide it for this build. Remembered by version
     * code, so the next release's banner still shows.
     *
     * Called directly on the main thread, deliberately: [PlayUpdateStore]'s
     * setter is `SharedPreferences.edit().apply()`, which is already
     * non-blocking — it updates the file's in-memory cache synchronously
     * and only defers the disk flush — so wrapping it in a background
     * `Thread` bought nothing and cost correctness. `MainActivity` carries
     * no state across a configuration change (unlike a `ViewModel`), so a
     * recreated instance re-reads [dismissedPlayUpdateVersionCode] straight
     * from the store; a deferred write that hadn't yet run by the time that
     * read happened would let the dismissal lose a race with its own
     * rotation, silently reappearing until the next dismiss (Codex, PR
     * #99). Calling `apply()` here means the in-memory value the next
     * instance's read sees is never stale.
     *
     * Internal rather than private, like [setPlayUpdateAvailable] above, so
     * a test can drive it directly.
     */
    internal fun dismissPlayUpdate() {
        val update = playUpdate as? PlayUpdateState.Available ?: return
        val key = playUpdateDismissalKey(update.versionCode, BuildConfig.VERSION_CODE)
        dismissedPlayUpdateVersionCode = key
        playUpdateStore.dismissedVersionCode = key
    }

    /**
     * Drives the banner's in-flight status: `Starting` when the user taps
     * Update, back to `Idle` when the Play sheet couldn't be opened (so the
     * banner returns to its plain offer instead of spinning forever).
     *
     * Internal rather than private, like [setPlayUpdateAvailable] above, so
     * a test can drive it directly.
     */
    internal fun setPlayUpdateProgress(progress: UpdateProgress) {
        currentPlayUpdateProgress = progress
        val update = playUpdate as? PlayUpdateState.Available ?: return
        if (update.progress == progress) return
        playUpdate = update.copy(progress = progress)
    }

    /**
     * Bridge for the checker's install-state listener.
     *
     * Internal rather than private, like [setPlayUpdateAvailable] above, so
     * a test can drive it directly.
     */
    internal fun onPlayUpdateInstallStatus(installStatus: Int) {
        val previousProgress = currentPlayUpdateProgress
        val progress = progressForInstallStatus(installStatus, fallback = previousProgress)
        setPlayUpdateProgress(progress)
        // The only way this lands on Idle from something that wasn't
        // already Idle is a genuine CANCELED/FAILED (UNKNOWN preserves
        // `previousProgress`, and every other status maps to its own
        // non-Idle progress) — heard directly, with no onResume in between,
        // if the user never left this screen while the download was live.
        // `PlayUpdateChecker.startUpdate()` already cleared its cached
        // handle the moment the sheet opened, and nothing else refreshes
        // it, so a repeat Update tap would otherwise find no handle and
        // fall back to the external Play listing instead of retrying the
        // in-app flow (Codex, PR #99). Recheck now instead of waiting on a
        // resume that may not come.
        if (progress == UpdateProgress.Idle && previousProgress != UpdateProgress.Idle) {
            checkPlayUpdate()
        }
    }

    /**
     * The banner's Update: open Play's confirmation sheet. When it can't be
     * opened at all (a consumed update handle, Play refusing the flow), fall
     * back to the store listing and clear the in-flight state — otherwise
     * the banner would sit on "Updating…" with no install event coming to
     * recover it.
     */
    private fun startPlayUpdate() {
        setPlayUpdateProgress(UpdateProgress.Starting)
        if (!playUpdateChecker.startUpdate(playUpdateLauncher)) {
            setPlayUpdateProgress(UpdateProgress.Idle)
            openPlayStoreListing()
        }
    }

    /**
     * The banner's Restart. Cleared on the way in, like every other failure
     * line on these screens — the next tap supersedes whatever the last one
     * reported, whichever way this one goes.
     */
    private fun completePlayUpdate() {
        playUpdateRestartFailed = false
        playUpdateChecker.completeFlexibleUpdate(onFailure = { playUpdateRestartFailed = true })
    }

    /**
     * Falls back to Snoozemo's Play listing when the in-app update sheet
     * itself couldn't be opened — the Play Store app's own deep link first,
     * then the web listing for a device without it. A missing target
     * (`ActivityNotFoundException`) or a denied one (`SecurityException`)
     * never throws: a device with neither leaves the tap doing nothing, and
     * the banner has already returned to its plain offer either way, so
     * nothing is left stranded mid-update. Narrowed to `RuntimeException`,
     * not `Throwable` — a genuinely fatal error (`OutOfMemoryError` and
     * friends) must not be swallowed and reported as merely "no target
     * available" (Codex, PR #99).
     */
    private fun openPlayStoreListing() {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()),
            Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()),
        )
        for (candidate in candidates) {
            try {
                startActivity(candidate)
                return
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Play Store listing candidate ${candidate.data} failed to launch.", exception)
            }
        }
        Log.e(TAG, "No Play Store target available to open the listing.")
    }

    /**
     * Shares the debug log (SPEC.md §4.6), from either the permanent row or
     * the crash banner's own Share button — the same flow either way, since
     * [DebugReport.share] already picks up a pinned crash automatically
     * (`DebugLogging.readPreviousOrCrash`) and consumes the pin itself once
     * the share actually lands.
     *
     * Runs off the main thread: [DebugReport.share] does binder and disk I/O
     * (policy access, permission checks, reading the previous run's file),
     * none of which belongs in front of a tap the user expects to be instant.
     *
     * Neither [crashPending] nor [shareFailed] is written from this
     * function's own completion — [crashPinWatch] and [shareWatch] apply
     * both, from whichever instance is live when the result actually lands,
     * which a configuration change mid-share may make a different one than
     * this (Codex, PR #89).
     *
     * The [DebugReport.nextAttempt] ticket is drawn here, before the
     * background thread starts — a second tap while an earlier attempt is
     * still running must not let that earlier attempt's slower completion
     * overwrite the later tap's outcome (Codex, PR #89). Drawn from
     * `DebugReport` itself rather than an activity-scoped counter: a field
     * on this activity would reset to zero on a configuration change,
     * behind the process-level high-water mark `DebugReport` already
     * compares against, and every share the replacement instance fired
     * would then read as stale (Codex, PR #89, second round on this same
     * mechanism).
     *
     * Drawing that ticket also raises [DebugReport.shareInFlight], which
     * both Share affordances are disabled on ([sharing]) — so the second
     * tap this whole mechanism used to reconcile after the fact simply
     * can't be made. Set synchronously here on the main thread, before the
     * background work starts, so there is no window in which a thread's
     * scheduling delay could let another tap through.
     */
    private fun shareDebugLog() {
        shareFailed = false
        val attempt = DebugReport.nextAttempt()
        sharing = true
        Thread { DebugReport.share(applicationContext, attempt) }.start()
    }

    /**
     * Dismisses the crash banner without sharing — consumes the pin directly
     * (SPEC.md §4.6): afterward the run is an ordinary previous run, shareable
     * from the permanent row and rotated away like any other. [crashPinWatch]
     * and [dismissWatch] apply the result, for the same reason [shareDebugLog]
     * leaves it to a watch rather than this call's own completion. A refused
     * consume leaves the pin in place — [crashPinWatch] alone would already
     * show that correctly, but silently: [dismissFailed] is what tells the
     * user the tap did something and it didn't work (Codex, PR #89).
     */
    private fun dismissCrash() {
        dismissFailed = false
        DebugLogging.dismissCrashPin()
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

    /** Switches to the permissions interstitial, remembering where to return. */
    private fun openPermissions(origin: Screen) {
        permissionsOrigin = origin
        screen = Screen.PERMISSIONS
    }

    private fun openPolicyAccessSettings() {
        openSettings(SetupRowId.DND, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    /**
     * Opens the hosted privacy policy in a browser (`SettingsScreen`'s foot,
     * same row as the sibling Simmo repo's own Settings screen). Not routed
     * through [openSettings]: that reports a failure under a `SetupRowId` row
     * this link isn't one of, and a missing browser is the same "nothing to
     * crash Settings over" case Simmo's own equivalent swallows silently — a
     * genuinely ignored failure, so it still gets a debug-level log rather
     * than a silent fall-through (AGENTS.md, *Error handling*).
     */
    private fun openPrivacyPolicy() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.settings_privacy_url).toUri()))
        }.onFailure {
            Log.d(TAG, "No activity resolved the privacy policy link.", it)
        }
    }

    /**
     * Opens the system's own screen for Snoozemo's rule — which calls,
     * messages, alarms and apps still break through — pre-selected to the
     * rule this app already owns (`TODO.md`). Distinct from
     * [openPolicyAccessSettings]: that grants the *permission* to change zen
     * state at all, this edits the *rule's own* interruption filter, which is
     * otherwise only reachable by finding the rule in system DND settings by
     * hand.
     *
     * `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` /
     * `Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID`, **not**
     * `NotificationManager`'s near-identically-named
     * `ACTION_AUTOMATIC_ZEN_RULE` / `EXTRA_AUTOMATIC_RULE_ID` — this PR's own
     * first attempt used those, and they don't reach this screen: AOSP's
     * Settings manifest declares an intent-filter for
     * `android.settings.AUTOMATIC_ZEN_RULE_SETTINGS` on
     * `Settings$ModeSettingsActivity`, whose fragment
     * (`ZenModeFragmentBase`) reads the rule id via
     * `Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID` — confirmed by reading that
     * source directly, not by javadoc alone, after `NotificationManager`'s
     * own action turned out to have no receiver anywhere in AOSP Settings
     * (Codex, PR #88).
     *
     * `filtersRuleId` is what the row is shown or hidden on, so a stale null
     * here would mean the row was never offered in the first place — nothing
     * left to guard.
     *
     * Internal rather than private, like [access] and [zenRuleId] above, so a
     * test can call it directly with those set and inspect the resulting
     * intent (Codex, PR #88).
     */
    internal fun openFilters() {
        val ruleId = filtersRuleId ?: return
        openSettings(
            SetupRowId.FILTERS,
            Intent(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
                .putExtra(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID, ruleId),
        )
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

/**
 * The two runtime location grants as read together (see
 * `MainActivity.refreshLocation`).
 */
private data class LocationGrants(val foreground: Boolean, val background: Boolean) {
    /**
     * Whether either grant is held now and was not at [previous] — an unread
     * [previous] counts as nothing held, so a first reading over a running
     * snooze rises too, the way the calendar row's first reading reposts.
     */
    fun risesFrom(previous: LocationGrants?): Boolean =
        (foreground && previous?.foreground != true) || (background && previous?.background != true)
}
