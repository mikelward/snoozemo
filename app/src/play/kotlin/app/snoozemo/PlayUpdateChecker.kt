package app.snoozemo

import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.VisibleForTesting
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

private const val TAG = "PlayUpdateChecker"

/**
 * Asks Play whether a newer Snoozemo is waiting, and drives the *flexible*
 * update flow when the user accepts (download in the background, install on
 * the next restart) — never immediate, which would take the screen over while
 * the user may be mid-snooze.
 *
 * Every call here is asynchronous and lives on [app.snoozemo.ui.SettingsScreen]'s
 * banner, nowhere near the tile-tap arm path: `SnoozeController` reads only its
 * own in-memory state, and nothing in this class touches it. `play` flavor
 * only — `direct`'s own copy of this file is a no-op, since that flavor
 * carries no Play Services dependency at all (`SPEC.md` §3.4).
 */
internal class PlayUpdateChecker @VisibleForTesting constructor(
    app: Application,
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(app),
) {
    private var updateInfo: AppUpdateInfo? = null
    private var installListenerRegistered = false

    /**
     * Bumped on every [checkForUpdate] call, so a slower check that finishes
     * after a newer one started can't overwrite what the newer one already
     * found. `MainActivity` has its own generation guard around which
     * callback it *acts on*, but that alone doesn't protect [updateInfo]:
     * without this, a stale "unavailable" response could still null it out
     * from underneath a genuinely available update — the banner would keep
     * showing Update, but `startUpdate()` would silently find no handle to
     * launch (Codex, PR #99).
     */
    private var checkGeneration = 0

    /**
     * Latched by [unregisterInstallListener] — i.e. by the owning activity's
     * `onDestroy`. Play's check is asynchronous, so a rotation while it is in
     * flight can run the cleanup *before* the answer arrives; registering a
     * listener after that point would leave one behind that nothing ever
     * unregisters, once per recreation.
     */
    private var destroyed = false
    private var onInstallStatus: ((Int) -> Unit)? = null
    private val installListener = InstallStateUpdatedListener { state ->
        onInstallStatus?.invoke(state.installStatus())
    }

    fun setInstallStatusListener(listener: (Int) -> Unit) {
        onInstallStatus = listener
    }

    fun checkForUpdate(
        onAvailable: (availableVersionCode: Int?, installStatus: Int) -> Unit,
        onUnavailable: () -> Unit,
        onCheckFailed: () -> Unit = onUnavailable,
    ) {
        if (!BuildConfig.PLAY_UPDATE_CHECKS_ENABLED) {
            updateInfo = null
            onUnavailable()
            return
        }
        val generation = ++checkGeneration
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (generation != checkGeneration) return@addOnSuccessListener
                if (info.isFlexibleUpdateAvailable() || info.isFlexibleUpdateInProgress()) {
                    updateInfo = info
                    // After process death mid-download, `startUpdate` has not
                    // run this process — register the listener now so live
                    // PENDING / DOWNLOADING / DOWNLOADED transitions still
                    // reach the banner instead of it waiting for the next
                    // resume poll.
                    if (info.isFlexibleUpdateInProgress()) {
                        registerInstallListener()
                    }
                    onAvailable(info.availableVersionCode(), info.installStatus())
                } else {
                    updateInfo = null
                    onUnavailable()
                }
            }
            .addOnFailureListener { exception ->
                // A failed fetch (flaky network, Play transiently unavailable)
                // is inconclusive — it does *not* mean "no update", so report
                // it separately and let the caller keep the banner it has.
                // Deliberately keep `updateInfo`: if an update is already
                // downloaded, Restart and the install listener must keep
                // working across a transient recheck failure.
                Log.w(TAG, "Play update check failed", exception)
                onCheckFailed()
            }
    }

    /**
     * Launches Play's confirmation sheet. Returns false when it could not be
     * opened at all, so the caller can fall back to the store listing rather
     * than leaving the banner stuck on "Updating…" with no listener event
     * coming to recover it.
     *
     * Takes an [ActivityResultLauncher] rather than an activity + request code
     * so the *canceled* sheet is visible to the caller: backing out of it
     * fires no install event, so without that result the banner would keep
     * spinning.
     */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val info = updateInfo?.takeIf { it.isFlexibleUpdateAvailable() } ?: return false
        return try {
            registerInstallListener()
            val launched = appUpdateManager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
            if (launched) {
                // An AppUpdateInfo is single-use: once handed to
                // startUpdateFlowForResult it can't drive a second flow, so a
                // canceled sheet followed by another Update tap would throw
                // on the consumed token. Drop it and let the next resume's
                // checkForUpdate fetch a fresh one.
                updateInfo = null
            }
            launched
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Play update flow failed to start", exception)
            false
        }
    }

    /**
     * The banner's Restart: install the downloaded update now. On success the
     * app is restarted by Play, so only the failure path returns here — and
     * it is a real one (the installer is busy, Play errors transiently),
     * where the tap would otherwise visibly do nothing. Logged either way,
     * and [onFailure] is what lets the banner say so too (Codex, PR #99) —
     * the banner is left untouched otherwise, so it keeps offering Restart
     * and the tap is simply retryable.
     */
    fun completeFlexibleUpdate(onFailure: () -> Unit = {}) {
        Log.i(TAG, "Play update: completing flexible update on user request")
        appUpdateManager.completeUpdate()
            .addOnFailureListener { exception ->
                Log.w(TAG, "Play update install failed to start", exception)
                onFailure()
            }
    }

    @VisibleForTesting
    internal fun registerInstallListener() {
        if (destroyed || installListenerRegistered) return
        appUpdateManager.registerListener(installListener)
        installListenerRegistered = true
    }

    /**
     * Drops the install-state listener. `MainActivity` owns one checker and
     * builds a fresh one on every recreation (rotation, theme change, process
     * restore), so without this the manager would accumulate a dead listener
     * — capturing the old activity — on each one. Call from `onDestroy`;
     * idempotent when the listener was never registered.
     *
     * Also closes the checker for good: a check still in flight can land
     * after this runs, and registering then would slip a listener past the
     * only cleanup this checker gets.
     */
    fun unregisterInstallListener() {
        destroyed = true
        if (!installListenerRegistered) return
        appUpdateManager.unregisterListener(installListener)
        installListenerRegistered = false
    }
}

private fun AppUpdateInfo.isFlexibleUpdateAvailable(): Boolean =
    updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
        isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

private fun AppUpdateInfo.isFlexibleUpdateInProgress(): Boolean =
    updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
        installStatus() != InstallStatus.UNKNOWN &&
        installStatus() != InstallStatus.INSTALLED
