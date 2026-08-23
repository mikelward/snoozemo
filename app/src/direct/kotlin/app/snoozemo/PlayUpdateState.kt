package app.snoozemo

/**
 * See the `play` flavor's copy of this file for the full explanation. This
 * flavor's checker never reports an update, so [PlayUpdateState.Available] is
 * never constructed here — but the shape has to match so shared code
 * ([app.snoozemo.ui.MainActivity], [app.snoozemo.ui.SettingsScreen]) compiles
 * against either flavor without a branch.
 */
internal sealed interface PlayUpdateState {
    val shouldPrompt: Boolean

    data object NotAvailable : PlayUpdateState {
        override val shouldPrompt: Boolean = false
    }

    data class Available(
        val versionCode: Int?,
        val isDismissed: Boolean = false,
        val progress: UpdateProgress = UpdateProgress.Idle,
    ) : PlayUpdateState {
        override val shouldPrompt: Boolean
            get() = !isDismissed
    }
}

internal sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    data object Starting : UpdateProgress
    data object Downloading : UpdateProgress
    data object Downloaded : UpdateProgress
}

/** See the `play` flavor's copy of this file. Never called from this flavor's checker. */
internal fun playUpdateDismissalKey(versionCode: Int?, currentVersionCode: Int): Int =
    versionCode ?: (currentVersionCode + 1)

/** See the `play` flavor's copy of this file. Never called from this flavor's checker. */
internal fun progressForInstallStatus(installStatus: Int, fallback: UpdateProgress): UpdateProgress = fallback
