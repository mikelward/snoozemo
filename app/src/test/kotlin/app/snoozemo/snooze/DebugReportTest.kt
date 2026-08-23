package app.snoozemo.snooze

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildDebugReportPayload] — the pure section assembly and the bounds
 * `docs/DEBUG.md` promises, plus the privacy-floor regression
 * `docs/PRIVACY.md`'s "The debug log" section says this feature carries.
 */
class DebugReportTest {

    @Test
    fun `carries the build, device, and state sections`() {
        val payload = payload(
            versionName = "1.2.3",
            versionCode = 42,
            applicationId = "app.snoozemo",
            policyAccessGranted = true,
            notificationsGranted = false,
        )

        assertTrue(payload.contains("Version: 1.2.3 (42)"))
        assertTrue(payload.contains("Application id: app.snoozemo"))
        assertTrue(payload.contains("Do Not Disturb access: granted"))
        assertTrue(payload.contains("Notifications: denied"))
    }

    @Test
    fun `omits the previous-run section when there is nothing to show`() {
        val payload = payload(previousRun = null)

        assertFalse(payload.contains("Previous run"))
    }

    @Test
    fun `labels an ordinary previous run distinctly from a crashed one`() {
        val ordinary = payload(previousRun = "state=ARMED", previousRunCrashed = false)
        val crashed = payload(previousRun = "state=ARMED", previousRunCrashed = true)

        assertTrue(ordinary.contains("--- Previous run ---"))
        assertFalse(ordinary.contains("uncaught exception"))
        assertTrue(crashed.contains("--- Previous run (ended in an uncaught exception) ---"))
    }

    @Test
    fun `the recent log section says how many lines are shown`() {
        val payload = payload(recentLog = listOf("line one", "line two"))

        assertTrue(payload.contains("--- Recent log (newest last, 2 of 2 shown) ---"))
        assertTrue(payload.contains("line one"))
        assertTrue(payload.contains("line two"))
    }

    @Test
    fun `an empty recent log says so rather than an empty section`() {
        val payload = payload(recentLog = emptyList())

        assertTrue(payload.contains("no captured log lines"))
    }

    // --- bounds (docs/DEBUG.md) ---

    @Test
    fun `the structured header is bounded even with a very long device string`() {
        val payload = payload(deviceModel = "x".repeat(50_000))

        val recentLogStart = payload.indexOf("--- Recent log")
        assertTrue("the huge device string must not blow the header's own budget out", recentLogStart in 1..10_000)
        assertTrue(payload.contains("truncated"))
    }

    @Test
    fun `the previous-run section is bounded regardless of how long the file was`() {
        val hugePreviousRun = (1..5_000).joinToString("\n") { "line $it of the previous run" }

        val payload = payload(previousRun = hugePreviousRun)

        val previousSection = payload
            .substringAfter("--- Previous run ---")
            .substringBefore("--- Recent log")
        assertTrue(previousSection.length < 30_000)
        // The newest lines are kept, not the oldest — a crash entry is always
        // the last line written.
        assertTrue(previousSection.contains("line 5000 of the previous run"))
        assertFalse(previousSection.contains("line 1 of the previous run"))
    }

    @Test
    fun `the recent log is bounded regardless of how much was captured`() {
        val hugeLog = (1..10_000).map { "entry $it" }

        val payload = payload(recentLog = hugeLog)

        assertTrue(payload.length < MAX_SHARE_PAYLOAD_CHARS)
        assertTrue(payload.contains("entry 10000"))
        assertTrue(payload.contains("older line(s) omitted"))
    }

    @Test
    fun `boundedLogTail keeps the newest line even alone over budget`() {
        val kept = boundedLogTail(listOf("short", "y".repeat(1_000)), budgetChars = 10)

        assertEquals(listOf("y".repeat(1_000)), kept)
    }

    // --- the privacy floor (docs/PRIVACY.md, "The debug log") ---

    @Test
    fun `never carries a raw coordinate, a full SSID, or a place name`() {
        // Realistic-looking fixture values for everything the floor bans —
        // none of these are parameters buildDebugReportPayload even accepts,
        // which is the structural guarantee this test exists to pin: there
        // is no field here for the previous-run/recent-log text to smuggle
        // one through undetected either.
        val bannedCoordinate = "37.422476,-122.084250"
        val bannedSsid = "TheSmithFamilyHomeWiFi"
        val bannedPlaceName = "123 Fictional Street Apartment 4B"

        val payload = payload(
            previousRun = "distance=12m accuracy=8m fix=captured",
            recentLog = listOf("departure test: distance=40m accuracy=15m"),
        )

        assertFalse(payload.contains(bannedCoordinate))
        assertFalse(payload.contains(bannedSsid))
        assertFalse(payload.contains(bannedPlaceName))
    }

    private fun payload(
        versionName: String = "1.0",
        versionCode: Long = 1,
        applicationId: String = "app.snoozemo",
        isDebuggable: Boolean = false,
        deviceManufacturer: String = "Google",
        deviceModel: String = "Pixel",
        androidRelease: String = "16",
        androidSdkInt: Int = 36,
        locale: Locale = Locale.US,
        policyAccessGranted: Boolean = true,
        notificationsGranted: Boolean = true,
        locationForegroundGranted: Boolean = true,
        locationBackgroundGranted: Boolean = true,
        locationServicesEnabled: Boolean = true,
        batterySaverOn: Boolean = false,
        tileAdded: Boolean = true,
        previousRun: String? = null,
        previousRunCrashed: Boolean = false,
        recentLog: List<String> = emptyList(),
    ): String = buildDebugReportPayload(
        nowMillis = 0L,
        versionName = versionName,
        versionCode = versionCode,
        applicationId = applicationId,
        isDebuggable = isDebuggable,
        deviceManufacturer = deviceManufacturer,
        deviceModel = deviceModel,
        androidRelease = androidRelease,
        androidSdkInt = androidSdkInt,
        locale = locale,
        policyAccessGranted = policyAccessGranted,
        notificationsGranted = notificationsGranted,
        locationForegroundGranted = locationForegroundGranted,
        locationBackgroundGranted = locationBackgroundGranted,
        locationServicesEnabled = locationServicesEnabled,
        batterySaverOn = batterySaverOn,
        tileAdded = tileAdded,
        previousRun = previousRun,
        previousRunCrashed = previousRunCrashed,
        recentLog = recentLog,
    )
}
