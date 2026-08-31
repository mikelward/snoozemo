package app.snoozemo.core

import com.mikelward.androidlog.REDACTED_PLACEHOLDER
import com.mikelward.androidlog.formatLogMessage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What stays this app's to test now that the log itself is
 * [mikelward/androidlog](https://github.com/mikelward/androidlog).
 *
 * The buffer's bounds, the throwable rendering, the `warning`-misuse reroute,
 * the offset markers, the sink fan-out and the disable semantics were all
 * tested here and are all tested there, in several cases more thoroughly —
 * the library covers suppressed exceptions, a throwable wrapped in `safe(...)`,
 * an in-flight sink delivery racing a disable, and five offset-marker cases
 * against the one this file had. Keeping copies is precisely the duplication
 * that let four hand-maintained loggers drift apart, so they are not kept.
 *
 * Two things are still this repo's, and only these:
 *
 * 1. [logSummary] is snoozemo's own — the one sanctioned way to put a snooze
 *    in the log, and the floor it holds is about *this* app's data.
 * 2. A thin conformance check that [SnoozeDebugLog] really is wired to that
 *    floor, so a future change to how it is constructed cannot quietly leave
 *    the app logging in full while the library's own tests stay green.
 */
class SnoozeDebugLogTest {

    // --- the privacy floor (SPEC.md §4.6: absolute, and tested on its own) ---

    @Test
    fun `the snooze summary carries none of what the floor bans`() {
        // Stock stand-ins, never a real capture (AGENTS.md, *Privacy*) — but
        // shaped like the real thing, so the test means something: coordinates,
        // an SSID, a BSSID, and a typed place name, all present on the record.
        val snooze = ActiveSnooze(
            anchor = Anchor(
                lat = 12.345678,
                lon = -87.654321,
                fixAccuracyM = 12.5f,
                capturedAt = Instant.parse("2026-01-01T12:00:00Z"),
                ssid = "ExampleWifi",
                bssid = "02:00:00:00:00:01",
            ),
            startedAt = Instant.parse("2026-01-01T12:00:00Z"),
            capExpiresAt = Instant.parse("2026-01-01T20:00:00Z"),
            mode = TrackingMode.FULL,
            placeName = "Cinema",
        )

        val line = snooze.logSummary().value.toString()

        assertFalse("the SSID is banned", line.contains("ExampleWifi"))
        assertFalse("the BSSID is banned", line.contains("02:00"))
        assertFalse("the place name is banned", line.contains("Cinema"))
        assertFalse("latitude is banned", line.contains("12.345678"))
        assertFalse("longitude is banned", line.contains("87.654321"))

        // What the summary is *for*: the mode, what the anchor has, and the
        // fix's accuracy in meters.
        assertTrue("the mode survives", line.contains("FULL"))
        assertTrue("the anchor's shape survives", line.contains("ssid captured"))
        assertTrue("the accuracy survives", line.contains("accuracy=12.5m"))

        // The times are in, deliberately (maintainer, 2026-08-30): not
        // sensitive, and necessary for debugging a snooze that ended early or
        // never ended. They used to be split into a second rendering withheld
        // from anything leaving the device; that split is retired, so this
        // pins the times as *present* where it once pinned them as withheld.
        assertTrue("the start time is kept", line.contains("2026-01-01T12:00:00Z"))
        assertTrue("the cap time is kept", line.contains("2026-01-01T20:00:00Z"))
    }

    // --- conformance: the floor is in force in this app, not just upstream ---

    @Test
    fun `an untagged String is kept on this device and withheld from anything leaving`() {
        // The library owns the type rule and tests it exhaustively. What this
        // asserts is narrower and is the part the library cannot: that the
        // instance this app actually logs through is subject to it. A base
        // class swapped, or a constructor argument added in the wrong place,
        // fails here and nowhere else.
        //
        // Reversed with the library's floor (androidlog, 2026-08-31): the
        // device's own log keeps the value, and the placeholder appears in the
        // rendering that leaves. This app has nowhere that leaves — every sink
        // is on-device and a report goes only where the user sends it — so the
        // second half is asserted through the boundary directly.
        SnoozeDebugLog.event("candidate=%s", "candidate-1")

        val line = SnoozeDebugLog.snapshot().last { !it.contains("timezone offset") }
        assertTrue("this device's own log keeps it", line.contains("candidate-1"))
        assertFalse("and does not hide it", line.contains(REDACTED_PLACEHOLDER))

        assertEquals(
            "candidate=$REDACTED_PLACEHOLDER",
            formatLogMessage("candidate=%s", arrayOf<Any?>("candidate-1"), leavingDevice = true),
        )
    }

    @Test
    fun `what the floor bans never reaches the log in the first place`() {
        // The one that matters now that the device's own copy is whole, and it
        // asserts the *structural* protection rather than a call this test
        // makes safe itself (Codex, PR #164).
        //
        // `ActiveSnooze` is a data class, so its generated `toString()` would
        // print the SSID, the coordinates and the place name in full. What
        // stops a call site that passes the record directly is that the shared
        // logger renders an unknown type as its class name and never calls a
        // `toString()` it did not write. So this logs the record *itself*,
        // unwrapped and untagged — the shape a future call site would reach for
        // by mistake — and pins that the floor holds anyway.
        val snooze = ActiveSnooze(
            anchor = Anchor(
                lat = 12.345678,
                lon = -87.654321,
                fixAccuracyM = 12.5f,
                capturedAt = Instant.parse("2026-01-01T12:00:00Z"),
                ssid = "ExampleWifi",
                bssid = "02:00:00:00:00:01",
            ),
            startedAt = Instant.parse("2026-01-01T12:00:00Z"),
            capExpiresAt = Instant.parse("2026-01-01T20:00:00Z"),
            mode = TrackingMode.FULL,
            placeName = "Cinema",
        )

        // The mistake case: the record itself, with nothing to make it safe.
        SnoozeDebugLog.event("state → %s", snooze)

        val direct = SnoozeDebugLog.snapshot().last { !it.contains("timezone offset") }
        assertFalse("the SSID is banned", direct.contains("ExampleWifi"))
        assertFalse("the BSSID is banned", direct.contains("02:00"))
        assertFalse("the place name is banned", direct.contains("Cinema"))
        assertFalse("latitude is banned", direct.contains("12.345678"))
        assertFalse("longitude is banned", direct.contains("87.654321"))
        // Asserted positively too, so this cannot pass because the line was
        // empty or the record never reached the log at all.
        assertTrue(direct, direct.contains("app.snoozemo.core.ActiveSnooze"))

        // And the sanctioned route still yields the diagnostic it exists for.
        SnoozeDebugLog.event("state → %s", snooze.logSummary())

        val summarized = SnoozeDebugLog.snapshot().last { !it.contains("timezone offset") }
        assertFalse("the SSID is banned", summarized.contains("ExampleWifi"))
        assertFalse("the place name is banned", summarized.contains("Cinema"))
        assertFalse("latitude is banned", summarized.contains("12.345678"))
        assertTrue("and the summary is what survives", summarized.contains("ssid captured"))
    }

    @Test
    fun `a tagged value is carried, so the floor is a default and not a gag`() {
        // The other direction, and it matters as much: a floor that withheld
        // everything would pass a one-sided test while leaving the app unable
        // to explain its own failures.
        SnoozeDebugLog.event("mode=%s accuracy=%sm", TrackingMode.FULL, 12.5f)

        val line = SnoozeDebugLog.snapshot().last { !it.contains("timezone offset") }
        assertTrue("an enum is carried", line.contains("FULL"))
        assertTrue("a number is carried", line.contains("12.5"))
    }
}
