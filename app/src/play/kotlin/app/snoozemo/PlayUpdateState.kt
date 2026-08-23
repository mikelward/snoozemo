package app.snoozemo

import com.google.android.play.core.install.model.InstallStatus

/**
 * What [app.snoozemo.ui.SettingsScreen]'s update banner shows. Pure state — no
 * Play objects — so the mapping below is testable without a device. `play`
 * flavor only; `direct`'s own copy of this file never leaves [NotAvailable].
 */
internal sealed interface PlayUpdateState {
    /** Whether the banner should show at all. */
    val shouldPrompt: Boolean

    data object NotAvailable : PlayUpdateState {
        override val shouldPrompt: Boolean = false
    }

    data class Available(
        /** Play's version code for the waiting build; null when Play doesn't report one. */
        val versionCode: Int?,
        val isDismissed: Boolean = false,
        val progress: UpdateProgress = UpdateProgress.Idle,
    ) : PlayUpdateState {
        override val shouldPrompt: Boolean
            get() = !isDismissed
    }
}

/** How far a flexible update has got, which is what the banner's copy tracks. */
internal sealed interface UpdateProgress {
    /** Nothing started — the banner offers Update. */
    data object Idle : UpdateProgress

    /** The Play sheet is up or the download is queued. */
    data object Starting : UpdateProgress

    data object Downloading : UpdateProgress

    /** Downloaded and waiting for the restart that installs it. */
    data object Downloaded : UpdateProgress
}

/**
 * The key a dismissal is remembered under, so dismissing one update doesn't
 * silence the next one. Play normally reports the waiting build's version
 * code; when it doesn't, fall back to one past the running build — that
 * suppresses the banner for this install but not after Snoozemo itself has
 * updated, which is the same "until there's a genuinely newer build" promise.
 */
internal fun playUpdateDismissalKey(versionCode: Int?, currentVersionCode: Int): Int =
    versionCode ?: (currentVersionCode + 1)

/**
 * Play's raw install status → the banner's state. [fallback] carries what we
 * were already showing, and it is what every uninformative status falls back
 * to — not just `UNKNOWN`, and not just when the fallback is `Starting`.
 * Play reports `UNKNOWN` before anything starts, in the gap after the user
 * accepts the sheet but before the download registers, and (Codex, PR #99)
 * apparently in other gaps too around an in-progress or finished download —
 * so resetting on it whenever the fallback wasn't `Starting` could snap a
 * real, in-progress download's banner back to "Update", or drop the Restart
 * action on one already fetched, until the next resume poll corrected it.
 * Preserving the fallback unconditionally is what both of those callers
 * actually want: nothing here is information, only its absence.
 */
internal fun progressForInstallStatus(installStatus: Int, fallback: UpdateProgress): UpdateProgress =
    when (installStatus) {
        InstallStatus.PENDING -> UpdateProgress.Starting
        InstallStatus.DOWNLOADING -> UpdateProgress.Downloading
        InstallStatus.DOWNLOADED -> UpdateProgress.Downloaded
        // The user canceled the Play sheet, or the download failed: back to
        // the plain offer so they can try again.
        InstallStatus.CANCELED, InstallStatus.FAILED -> UpdateProgress.Idle
        // UNKNOWN and anything else Play might report.
        else -> fallback
    }
