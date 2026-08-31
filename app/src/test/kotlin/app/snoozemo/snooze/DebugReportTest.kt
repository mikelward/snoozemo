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
    fun `a coarse-only foreground location grant is labeled distinctly from denied`() {
        // A downgrade to ACCESS_COARSE_LOCATION alone reads as a fatal
        // capability loss to the presence engine (LocationPermission's own
        // KDoc), not a simple missing permission — collapsing it into a
        // plain "denied" would make that downgrade indistinguishable from
        // no grant at all in the report meant to diagnose it (Codex, PR #89).
        val coarseOnly = payload(locationFineGranted = false, locationCoarseGranted = true)
        assertTrue(coarseOnly.contains("Location (foreground): granted (coarse only)"))

        val neither = payload(locationFineGranted = false, locationCoarseGranted = false)
        assertTrue(neither.contains("Location (foreground): denied"))

        val fine = payload(locationFineGranted = true, locationCoarseGranted = true)
        assertTrue(fine.contains("Location (foreground): granted (fine)"))
    }

    @Test
    fun `an unrequired background-location grant is labeled not required, not denied`() {
        // The direct flavor never declares ACCESS_BACKGROUND_LOCATION and
        // never needs it (SPEC.md §3.4) — a plain "denied" there reads as a
        // capability problem that doesn't exist in that build (Codex, PR #89).
        val notRequired = payload(locationBackgroundGranted = false, locationBackgroundRequired = false)
        assertTrue(notRequired.contains("Location (background): not required for this build"))

        val requiredAndGranted = payload(locationBackgroundGranted = true, locationBackgroundRequired = true)
        assertTrue(requiredAndGranted.contains("Location (background): granted"))

        val requiredAndDenied = payload(locationBackgroundGranted = false, locationBackgroundRequired = true)
        assertTrue(requiredAndDenied.contains("Location (background): denied"))
    }

    /**
     * A capability check that itself threw (a transient system-service
     * failure) used to substitute `false`, so the report said "denied" —
     * indistinguishable from a genuine, confirmed denial — even though the
     * report's recipient has no way to see the logcat line recording the
     * real cause. This is the report's whole reason to exist: a state that
     * couldn't be determined must not read the same as a state that was
     * (Codex, PR #89).
     */
    @Test
    fun `a capability check that itself failed reads as unknown, not a confirmed denial`() {
        val payload = payload(
            policyAccessGranted = null,
            notificationsGranted = null,
            locationFineGranted = null,
            locationCoarseGranted = null,
            locationBackgroundGranted = null,
            locationServicesEnabled = null,
            batterySaverOn = null,
            tileAdded = null,
        )

        assertTrue(payload.contains("Do Not Disturb access: unknown"))
        assertTrue(payload.contains("Notifications: unknown"))
        assertTrue(payload.contains("Location (foreground): unknown"))
        assertTrue(payload.contains("Location (background): unknown"))
        assertTrue(payload.contains("Location services on: unknown"))
        assertTrue(payload.contains("Battery saver on: unknown"))
        assertTrue(payload.contains("Quick Settings tile added: unknown"))
    }

    @Test
    fun `an unknown foreground-location fine check still reports a real coarse grant`() {
        // Only a genuinely confirmed grant should read as granted — one
        // failed check among the pair must not silently fall through to
        // "denied" just because the other happened to succeed and be true.
        // A failed fine check alongside a confirmed coarse grant must not
        // read as "coarse only" either — that label asserts fine is
        // confirmed absent, which a failed check never confirmed (Codex,
        // PR #89, fresh evidence).
        val fineUnknown = payload(locationFineGranted = null, locationCoarseGranted = true)
        assertTrue(fineUnknown.contains("Location (foreground): granted (coarse); fine check failed"))

        val bothUnknown = payload(locationFineGranted = null, locationCoarseGranted = null)
        assertTrue(bothUnknown.contains("Location (foreground): unknown"))
    }

    @Test
    fun `omits the previous-run section when there is nothing to show`() {
        val payload = payload(previousRun = null)

        assertFalse(payload.contains("Earlier runs"))
    }

    @Test
    fun `says the previous run could not be read, rather than reading like nothing happened`() {
        // A blank previousRun is ambiguous on its own: genuinely nothing to
        // report, or a read that timed out or failed and silently dropped a
        // pinned crash along with it. A share that lands while quietly
        // omitting the one thing the crash banner exists to deliver must
        // not read as a clean report (Codex, PR #89).
        val omitted = payload(previousRun = null, previousRunOmitted = true)

        assertTrue(omitted.contains("--- Earlier runs ---"))
        assertTrue(omitted.contains("could not be included in this report"))
    }

    @Test
    fun `labels an ordinary earlier run distinctly from a crashed one`() {
        val ordinary = payload(previousRun = "state=ARMED", previousRunCrashed = false)
        val crashed = payload(previousRun = "state=ARMED", previousRunCrashed = true)

        assertTrue(ordinary.contains("--- Earlier runs ---"))
        assertFalse(ordinary.contains("uncaught exception"))
        // Plural, and the crash named as one *of* them: the section can carry
        // several runs and only one of them crashed, so labeling the whole
        // aggregate as the crash misclassifies the ordinary restarts beside it
        // (Codex, PR #153).
        assertTrue(crashed.contains("--- Earlier runs (one ended in an uncaught exception) ---"))
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
            .substringAfter("--- Earlier runs ---")
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
    fun `the structured header has no field for a coordinate, SSID, or place name`() {
        // The genuinely structural guarantee: every parameter this section
        // is built from is a typed grant/boolean/enum-ish value (SPEC.md
        // §4.6's build/device/state facts) — there is no string parameter
        // here a coordinate, an SSID, or a typed place name could ever be
        // passed through as. This says nothing about previousRun/recentLog,
        // which is a different channel with a different guarantee — see the
        // test below (Codex, PR #89: the previous version of this test
        // asserted against fixtures that never contained a banned value in
        // the first place, so it passed regardless of whether the code was
        // right).
        val bannedCoordinate = "0.000000,0.000000"
        val bannedSsid = "TheSmithFamilyHomeWiFi"
        val bannedPlaceName = "123 Fictional Street Apartment 4B"

        val payload = payload(previousRun = null, recentLog = emptyList())

        assertFalse(payload.contains(bannedCoordinate))
        assertFalse(payload.contains(bannedSsid))
        assertFalse(payload.contains(bannedPlaceName))
    }

    @Test
    fun `previousRun and recentLog are forwarded verbatim — their floor is a call-site discipline, not this function's`() {
        // Unlike the structured header above, buildDebugReportPayload does
        // no filtering of these two: whatever SnoozeDebugLog's own call
        // sites throughout the app choose to record is what a share
        // includes, unredacted. That is by design — this is a formatter,
        // not a sanitizer — but it means the coordinate/SSID/place-name
        // floor for these two channels is held entirely upstream, by every
        // SnoozeDebugLog.event(...) call site never writing one, not by
        // anything checked here. Exercising a realistic banned-looking
        // value and asserting it *does* survive is what actually pins that
        // contract, rather than a fixture that never contained one testing
        // nothing (Codex, PR #89).
        val previousRunLeak = "left previous run at 0.000000,0.000000"
        val recentLogLeak = "connected to TheSmithFamilyHomeWiFi"

        val payload = payload(previousRun = previousRunLeak, recentLog = listOf(recentLogLeak))

        assertTrue(
            "previousRun is forwarded verbatim — this function performs no redaction",
            payload.contains(previousRunLeak),
        )
        assertTrue(
            "recentLog is forwarded verbatim — this function performs no redaction",
            payload.contains(recentLogLeak),
        )
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
        policyAccessGranted: Boolean? = true,
        notificationsGranted: Boolean? = true,
        locationFineGranted: Boolean? = true,
        locationCoarseGranted: Boolean? = true,
        locationBackgroundGranted: Boolean? = true,
        locationBackgroundRequired: Boolean = true,
        locationServicesEnabled: Boolean? = true,
        batterySaverOn: Boolean? = false,
        tileAdded: Boolean? = true,
        previousRun: String? = null,
        previousRunCrashed: Boolean = false,
        previousRunOmitted: Boolean = false,
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
        locationFineGranted = locationFineGranted,
        locationCoarseGranted = locationCoarseGranted,
        locationBackgroundGranted = locationBackgroundGranted,
        locationBackgroundRequired = locationBackgroundRequired,
        locationServicesEnabled = locationServicesEnabled,
        batterySaverOn = batterySaverOn,
        tileAdded = tileAdded,
        previousRun = previousRun,
        previousRunCrashed = previousRunCrashed,
        previousRunOmitted = previousRunOmitted,
        recentLog = recentLog,
    )
}
