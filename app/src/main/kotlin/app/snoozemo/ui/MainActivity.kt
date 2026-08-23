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
import androidx.lifecycle.Lifecycle
import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.EndReason
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.PolicyAccessAction
import app.snoozemo.core.PolicyAccessChange
import app.snoozemo.core.StaleRuleClaim
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenRuleState
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.DebugLogStore
import app.snoozemo.snooze.DebugLogging
import app.snoozemo.snooze.LocationPromptStore
import app.snoozemo.snooze.NotificationPromptStore
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

/** How often [MainActivity.now] advances while visible — see its own comment. */
private const val TICK_INTERVAL_MS = 60_000L

/**
 * The three screens this activity switches between; there is no back stack
 * beyond one level. Internal rather than private only so a lifecycle test
 * can assert on [MainActivity.screen] directly; nothing outside this file
 * constructs one in production.
 */
internal enum class Screen { MAIN, PERMISSIONS, SETTINGS }

/**
 * Hosts the three screens the app is split into (`TODO.md` Phase 4): [MainScreen],
 * [PermissionsScreen] and [SettingsScreen]. There is no navigation library —
 * deliberately, per the same TODO entry — so this class holds the one piece of
 * navigation state itself.
 */
class MainActivity : ComponentActivity() {

    /**
     * Which of the three screens is on top.
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
    private lateinit var store: ActiveSnoozeStore
    private lateinit var promptStore: NotificationPromptStore
    private lateinit var locationPromptStore: LocationPromptStore
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
     * The record itself, read alongside [snoozing] and for the same reason —
     * `MainScreen`'s status line needs the mode and the cap to report anything
     * beyond "running", and re-deriving them separately would be a second read
     * of the same file the [refreshSnoozing] load already did.
     */
    private var activeSnooze by mutableStateOf<ActiveSnooze?>(null)

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
    private var lastOutcome by mutableStateOf<String?>(null)

    /**
     * Whether the debug log is on (SPEC.md §4.6). Null until the store has been
     * read, so the row appears stating the truth rather than a default
     * corrected a frame later — the same discipline as every row above.
     */
    private var debugLogEnabled by mutableStateOf<Boolean?>(null)

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

    /**
     * Whether the last debug-log save was refused by storage. Cleared by the
     * next tap: the message describes the previous attempt, and a new attempt
     * supersedes it whatever it returns — the same rule the Settings-trip
     * failures follow.
     */
    private var debugLogSaveFailed by mutableStateOf(false)

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
        savedInstanceState?.let {
            screen = Screen.entries.firstOrNull { s -> s.name == it.getString(KEY_SCREEN) } ?: screen
            permissionsOrigin =
                Screen.entries.firstOrNull { s -> s.name == it.getString(KEY_PERMISSIONS_ORIGIN) }
                    ?: permissionsOrigin
            routedToPermissionsOnce = it.getBoolean(KEY_ROUTED_TO_PERMISSIONS_ONCE, routedToPermissionsOnce)
        }
        store = ActiveSnoozeStore(applicationContext)
        promptStore = NotificationPromptStore(applicationContext)
        locationPromptStore = LocationPromptStore(applicationContext)
        tileStore = TilePresenceStore(applicationContext)
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
                            lastOutcome = lastOutcome,
                            settingsFailure = settingsFailure,
                            onOpenPermissions = { openPermissions(Screen.MAIN) },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onAddTile = ::addTile,
                            onDismissTileBanner = { tileStore.dismissBanner() },
                            onArm = ::armFromScreen,
                            onRelease = ::endFromScreen,
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
                                settingsFailure = settingsFailure,
                                onAccessRow = ::openPolicyAccessSettings,
                                onNotificationsRow = ::fixNotifications,
                                onLocationRow = ::fixLocation,
                                onDone = { screen = permissionsOrigin },
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
                                onOpenPermissions = { openPermissions(Screen.SETTINGS) },
                                onTileRow = ::addTile,
                                onFiltersRow = ::openFilters,
                                onDebugLog = ::setDebugLog,
                            )
                        }
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
        debugLogWatch = DebugLogging.watchSaveOutcome {
            runOnUiThread {
                if (debugLogWrites == 0) {
                    debugLogEnabled = DebugLogStore(this).isEnabled()
                    debugLogSaveFailed = DebugLogging.lastSaveRefused
                }
            }
        }
        // Fresh on every start, same as `refreshSnoozing` above — a screen
        // reopened after sitting in the background for an hour must not show
        // the reading it had when it was last visible.
        now = SnoozeClock.read()
        tickHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
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
            val loaded = store.load()
            runOnUiThread {
                if (refresh != latestSnoozingRefresh) return@runOnUiThread
                val running = loaded != null
                val changed = snoozing != running
                snoozing = running
                activeSnooze = loaded
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
                refreshLocation()
                // Read here rather than in `onStart` for the same reason as the
                // rest: it is a preferences file, and no disk read belongs in
                // front of the first frame.
                tileAdded = tileStore.isAdded()
                tileBannerDismissed = tileStore.isBannerDismissed()
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
        if (current == LocationPermission.GRANTED) clearFailure(SetupRowId.LOCATION)
        return current
    }

    /**
     * What tapping the location row does. `ASKABLE` launches the foreground
     * request directly, the same as every other row's runtime prompt — no
     * disclosure precedes it (see [foregroundLocationPermission]).
     */
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
        debugLogWatch?.close()
        debugLogWatch = null
        // The one background wakeup this screen owns on its own — every
        // minute while visible is negligible, but only while visible. Left
        // running past `onStop` would tick a screen nobody can see.
        tickHandler.removeCallbacks(tickRunnable)
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
    private fun setDebugLog(enabled: Boolean) {
        if (debugLogEnabled == null) return
        debugLogSaveFailed = false
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
                }
            }
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

    /** Switches to the permissions interstitial, remembering where to return. */
    private fun openPermissions(origin: Screen) {
        permissionsOrigin = origin
        screen = Screen.PERMISSIONS
    }

    private fun openPolicyAccessSettings() {
        openSettings(SetupRowId.DND, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
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
