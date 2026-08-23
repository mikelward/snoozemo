package app.snoozemo

import com.google.android.play.core.install.model.InstallStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure logic, no Play objects — [progressForInstallStatus] and
 * [playUpdateDismissalKey] are what turns Play's raw answer into the
 * banner's state (`app.snoozemo.ui.SettingsScreen`). `play`-flavor test
 * source set (`app/src/testPlay`) since this exercises the real
 * `InstallStatus` constants, which `direct`'s own copy of this file never
 * imports.
 */
class PlayUpdateStateTest {

    @Test
    fun `pending maps to starting`() {
        assertEquals(
            UpdateProgress.Starting,
            progressForInstallStatus(InstallStatus.PENDING, fallback = UpdateProgress.Idle),
        )
    }

    @Test
    fun `downloading maps to downloading`() {
        assertEquals(
            UpdateProgress.Downloading,
            progressForInstallStatus(InstallStatus.DOWNLOADING, fallback = UpdateProgress.Idle),
        )
    }

    @Test
    fun `downloaded maps to downloaded`() {
        assertEquals(
            UpdateProgress.Downloaded,
            progressForInstallStatus(InstallStatus.DOWNLOADED, fallback = UpdateProgress.Starting),
        )
    }

    @Test
    fun `canceled and failed reset to idle regardless of fallback`() {
        assertEquals(
            UpdateProgress.Idle,
            progressForInstallStatus(InstallStatus.CANCELED, fallback = UpdateProgress.Downloading),
        )
        assertEquals(
            UpdateProgress.Idle,
            progressForInstallStatus(InstallStatus.FAILED, fallback = UpdateProgress.Downloading),
        )
    }

    @Test
    fun `unknown preserves an in-flight starting rather than snapping back to idle`() {
        // Play reports UNKNOWN in the gap after the user accepts the sheet
        // but before the download registers — losing `Starting` there would
        // flash the banner back to "Update" under the user's own tap.
        assertEquals(
            UpdateProgress.Starting,
            progressForInstallStatus(InstallStatus.UNKNOWN, fallback = UpdateProgress.Starting),
        )
    }

    @Test
    fun `unknown preserves a real download or a finished one, not just starting`() {
        // A stray UNKNOWN reading must not regress a genuinely in-progress
        // download's banner back to "Update", or drop the Restart action on
        // one already fetched (Codex, PR #99) — UNKNOWN carries no
        // information regardless of what it's replacing.
        assertEquals(
            UpdateProgress.Downloading,
            progressForInstallStatus(InstallStatus.UNKNOWN, fallback = UpdateProgress.Downloading),
        )
        assertEquals(
            UpdateProgress.Downloaded,
            progressForInstallStatus(InstallStatus.UNKNOWN, fallback = UpdateProgress.Downloaded),
        )
        assertEquals(
            UpdateProgress.Idle,
            progressForInstallStatus(InstallStatus.UNKNOWN, fallback = UpdateProgress.Idle),
        )
    }

    @Test
    fun `dismissal key uses Play's version code when it reports one`() {
        assertEquals(42, playUpdateDismissalKey(versionCode = 42, currentVersionCode = 10))
    }

    @Test
    fun `dismissal key falls back to one past the running build when Play reports none`() {
        // Suppresses the banner for this install without also suppressing it
        // after Snoozemo itself has updated to a genuinely newer build.
        assertEquals(11, playUpdateDismissalKey(versionCode = null, currentVersionCode = 10))
    }
}
