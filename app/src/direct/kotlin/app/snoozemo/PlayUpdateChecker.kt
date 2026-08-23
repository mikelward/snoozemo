package app.snoozemo

import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

/**
 * The `direct` flavor's copy of the `play` flavor's update checker — see that
 * file for the full explanation. This flavor is never distributed through
 * Play, so there is nothing to check for and no Play Services dependency to
 * carry (`SPEC.md` §3.4): every call here is a no-op, and the update banner
 * this drives simply never shows.
 */
internal class PlayUpdateChecker(app: Application) {

    fun setInstallStatusListener(listener: (Int) -> Unit) = Unit

    fun checkForUpdate(
        onAvailable: (availableVersionCode: Int?, installStatus: Int) -> Unit,
        onUnavailable: () -> Unit,
        onCheckFailed: () -> Unit = onUnavailable,
    ) = onUnavailable()

    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean = false

    fun completeFlexibleUpdate(onFailure: () -> Unit = {}) = Unit

    fun unregisterInstallListener() = Unit
}
