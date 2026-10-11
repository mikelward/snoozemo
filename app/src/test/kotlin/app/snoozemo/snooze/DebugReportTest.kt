package app.snoozemo.snooze

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.SnoozeRinger
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.safe
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
        // A build that never declares ACCESS_BACKGROUND_LOCATION and never
        // needs it (SPEC.md §3.4) — a plain "denied" there reads as a
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
            ringerMode = null,
            snoozeRingerCeiling = null,
        )

        assertTrue(payload.contains("Do Not Disturb access: unknown"))
        assertTrue(payload.contains("Notifications: unknown"))
        assertTrue(payload.contains("Location (foreground): unknown"))
        assertTrue(payload.contains("Location (background): unknown"))
        assertTrue(payload.contains("Location services on: unknown"))
        assertTrue(payload.contains("Battery saver on: unknown"))
        assertTrue(payload.contains("Quick Settings tile added: unknown"))
        assertTrue(payload.contains("Ringer now: unknown"))
        assertTrue(payload.contains("Snooze ringer ceiling: unknown"))
    }

    @Test
    fun `the ceiling line says whether it is in force or only chosen`() {
        // A running snooze is judged against its own ceiling; the saved choice
        // is only what the *next* one will use, so the two are labeled apart —
        // unlabeled, a mid-snooze change read as a snooze that had broken
        // (Codex, PR #176).
        assertEquals(
            "VIBRATE (in force)",
            ringerCeilingLabel(inForce = SnoozeRinger.VIBRATE, chosen = SnoozeRinger.SILENT),
        )
        assertEquals(
            "SILENT (next snooze)",
            ringerCeilingLabel(inForce = null, chosen = SnoozeRinger.SILENT),
        )
    }

    @Test
    fun `the report pairs the live ringer with the ceiling it is judged against`() {
        // Either alone explains nothing: a ringing phone is only a bug if a
        // ceiling said it should not have been (SPEC.md §5.9), and this pair is
        // what answers "why did it still ring" — the report's whole job.
        val payload = payload(ringerMode = "ring", snoozeRingerCeiling = "VIBRATE")

        assertTrue(payload.contains("Ringer now: ring"))
        assertTrue(payload.contains("Snooze ringer ceiling: VIBRATE"))
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
    fun `leaves the earlier runs to the library when they were read`() {
        // The library appends them after this section (SharedReport.collect);
        // a heading here would be a second, empty one.
        assertFalse(payload().contains("Earlier runs"))
    }

    @Test
    fun `says the earlier runs could not be read, rather than reading like nothing happened`() {
        // With no log handed over, the library has nothing to append and
        // nothing to say; a share that quietly left the runs out must not
        // read as a clean report (Codex, PR #89).
        val omitted = payload(earlierRunsUnavailable = true)

        assertTrue(omitted.contains(EARLIER_RUNS_HEADING))
        assertTrue(omitted.contains("could not be included in this report"))
    }

    @Test
    fun `the recent log section says how many lines are shown and that older ones may be dropped`() {
        val payload = payload(recentLog = listOf("line one", "line two"))

        // No "of M": the log arrives already trimmed, so a total would claim
        // the report is complete precisely when it isn't.
        assertTrue(
            payload.contains(
                "--- Recent log (2 lines, newest last; older lines are dropped to keep the report shareable) ---",
            ),
        )
        assertTrue(payload.contains("line one"))
        assertTrue(payload.contains("line two"))
    }

    @Test
    fun `a pinned line the ring has evicted still reaches the report`() {
        SnoozeDebugLog.resetForTest()
        SnoozeDebugLog.setRecording(true)
        try {
            SnoozeDebugLog.pinnedEvent("processExit reason=%s", safe("crash"))
            // Well past the ring's capacity, as a busy run would be.
            repeat(DebugLog.DEFAULT_MAX_ENTRIES + 50) { SnoozeDebugLog.event("busy %s", it) }
            // Precondition: the ring alone has lost it, so the report can only
            // be carrying it from the pinned copy.
            assertFalse(SnoozeDebugLog.snapshot().any { "processExit reason=crash" in it })

            val payload = payload(recentLogFor = ::recentLogForReport)

            val section = payload.substringAfter("--- Recent log")
            assertTrue(section, section.contains("processExit reason=crash"))
            // Ahead of the recent lines, as it happened before them, and those
            // are still there.
            assertTrue(section.indexOf("processExit reason=crash") < section.indexOf("busy 349"))
        } finally {
            SnoozeDebugLog.resetForTest()
            SnoozeDebugLog.setRecording(true)
        }
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
    fun `the recent log is bounded regardless of how much was captured`() {
        val hugeLog = (1..10_000).map { "entry $it" }

        val payload = payload(recentLog = hugeLog)

        assertTrue(payload.length < APP_SECTION_CHARS)
        assertTrue(payload.contains("entry 10000"))
        assertFalse(payload.contains("entry 1\n"))
    }

    @Test
    fun `a section with every part full still fits its share of the report`() {
        val payload = payload(
            deviceModel = "x".repeat(50_000),
            earlierRunsUnavailable = true,
            recentLog = (1..10_000).map { "entry $it" },
        )

        // The library cuts an oversized section from the middle; one that
        // fits its share is never cut.
        assertTrue("${payload.length}", payload.length <= APP_SECTION_CHARS)
        assertTrue(payload.contains("entry 10000"))
    }

    @Test
    fun `the log is fetched to the room the rest left, so its pinned lines survive`() {
        SnoozeDebugLog.resetForTest()
        SnoozeDebugLog.setRecording(true)
        try {
            SnoozeDebugLog.pinnedEvent("processExit reason=%s", safe("crash"))
            repeat(DebugLog.DEFAULT_MAX_ENTRIES + 50) { SnoozeDebugLog.event("busy %s %s", it, "x".repeat(100)) }
            var asked = -1

            val payload = payload(
                recentLogFor = { budget -> asked = budget; recentLogForReport(budget) },
            )

            // Asked for less than the section's share: the header came off first.
            assertTrue("$asked", asked in 1 until APP_SECTION_CHARS)
            assertTrue("${payload.length}", payload.length <= APP_SECTION_CHARS)
            val section = payload.substringAfter("--- Recent log")
            assertTrue(section.contains("processExit reason=crash"))
            assertTrue(section.contains("busy ${DebugLog.DEFAULT_MAX_ENTRIES + 49} "))
        } finally {
            SnoozeDebugLog.resetForTest()
            SnoozeDebugLog.setRecording(true)
        }
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
        // passed through as. This says nothing about recentLog,
        // which is a different channel with a different guarantee — see the
        // test below (Codex, PR #89: the previous version of this test
        // asserted against fixtures that never contained a banned value in
        // the first place, so it passed regardless of whether the code was
        // right).
        val bannedCoordinate = "0.000000,0.000000"
        val bannedSsid = "TheSmithFamilyHomeWiFi"
        val bannedPlaceName = "123 Fictional Street Apartment 4B"

        val payload = payload(recentLog = emptyList())

        assertFalse(payload.contains(bannedCoordinate))
        assertFalse(payload.contains(bannedSsid))
        assertFalse(payload.contains(bannedPlaceName))
    }

    @Test
    fun `recentLog is forwarded verbatim — its floor is a call-site discipline, not this function's`() {
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
        val recentLogLeak = "connected to TheSmithFamilyHomeWiFi"

        val payload = payload(recentLog = listOf(recentLogLeak))

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
        ringerMode: String? = "ring",
        snoozeRingerCeiling: String? = "VIBRATE",
        earlierRunsUnavailable: Boolean = false,
        recentLog: List<String> = emptyList(),
        recentLogFor: (Int) -> List<String> = { recentLog },
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
        ringerMode = ringerMode,
        snoozeRingerCeiling = snoozeRingerCeiling,
        earlierRunsUnavailable = earlierRunsUnavailable,
        recentLog = recentLogFor,
    )
}
