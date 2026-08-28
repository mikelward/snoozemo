package app.snoozemo.core

import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SnoozeDebugLogTest {

    @Before
    fun reset() {
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.clearSinksForTest()
        SnoozeDebugLog.readMillis = { FIXED_MILLIS }
    }

    @After
    fun restoreClock() {
        SnoozeDebugLog.readMillis = System::currentTimeMillis
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.clearSinksForTest()
    }

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

        val summary = snooze.logSummary()

        // The floor holds in both renderings — it is absolute, not a matter of
        // which audience is reading.
        for ((audience, line) in listOf("on device" to summary.full, "off device" to summary.mirrored)) {
            assertFalse("$audience: the SSID is banned", line.contains("ExampleWifi"))
            assertFalse("$audience: the BSSID is banned", line.contains("02:00"))
            assertFalse("$audience: the place name is banned", line.contains("Cinema"))
            assertFalse("$audience: latitude is banned", line.contains("12.345678"))
            assertFalse("$audience: longitude is banned", line.contains("87.654321"))
            // What the summary is *for* survives either way: the mode, what
            // the anchor has, and the fix's accuracy in meters.
            assertTrue("$audience: the mode survives", line.contains("FULL"))
            assertTrue("$audience: the anchor's shape survives", line.contains("ssid captured"))
            assertTrue("$audience: the accuracy survives", line.contains("accuracy=12.5m"))
        }

        // The times are sanctioned on device and are the diagnostic there. Off
        // device they say when someone was asleep or in a cinema, which the
        // *Privacy* rule names as the user's — so only the shape travels.
        assertTrue("the times are kept on device", summary.full.contains("2026-01-01T12:00:00Z"))
        assertFalse("and withheld off it", summary.mirrored.contains("2026-01-01T12:00:00Z"))
    }

    @Test
    fun `a throwable renders as types and frames, never its message`() {
        // The message is where a platform exception quotes what it was given —
        // which on the Wi-Fi and location stacks is exactly what the floor
        // bans — and this log has no scrubber, so it must not be read at all.
        val cause = IllegalStateException("ssid=ExampleWifi lat=12.345678")
        val thrown = RuntimeException("place=Home", cause)

        SnoozeDebugLog.failure(thrown, "capture failed")

        val entry = SnoozeDebugLog.snapshot().single()
        assertFalse(entry.contains("ExampleWifi"))
        assertFalse(entry.contains("12.345678"))
        assertFalse(entry.contains("Home"))
        assertTrue("the type is the diagnostic", entry.contains("java.lang.RuntimeException"))
        assertTrue("the cause chain survives", entry.contains("Caused by: java.lang.IllegalStateException"))
    }

    @Test
    fun `a throwable handed to warning is rerouted without re-rendering its message`() {
        // warning() reroutes to failure() rather than letting the exception
        // bind as a formatting argument. Leaving it in the arguments would put
        // it back: with no `%s` to fill it renders through toString(), which
        // carries the message typesAndFrames() exists to exclude.
        val thrown = IllegalStateException("ssid=ExampleWifi")

        SnoozeDebugLog.warning("wifi callback refused", thrown)

        val entry = SnoozeDebugLog.snapshot().single()
        assertFalse("the message must not come back through toString()", entry.contains("ExampleWifi"))
        assertTrue("but the type still does", entry.contains("java.lang.IllegalStateException"))
        assertTrue("and the mistake is named", entry.contains("use failure()"))
    }

    // --- recording mechanics ---

    @Test
    fun `entries carry a real timestamp with a zone offset`() {
        SnoozeDebugLog.event("something happened")

        val entry = SnoozeDebugLog.snapshot().single()
        // The fixed instant, rendered in whatever zone the JVM runs in — the
        // offset suffix is what makes "when" answerable across a DST boundary
        // (SPEC.md §4.6). Matching the shape rather than a literal keeps the
        // test zone-independent.
        assertTrue(
            "expected an ISO timestamp with offset, got: $entry",
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{4}) D something happened$""")
                .matches(entry),
        )
    }

    @Test
    fun `the buffer is bounded and drops the oldest`() {
        repeat(SnoozeDebugLog.MAX_ENTRIES + 5) { SnoozeDebugLog.event("entry $it") }

        val lines = SnoozeDebugLog.snapshot()
        assertEquals(SnoozeDebugLog.MAX_ENTRIES, lines.size)
        assertTrue("the oldest entries fall off the front", lines.first().endsWith("entry 5"))
        assertTrue(lines.last().endsWith("entry ${SnoozeDebugLog.MAX_ENTRIES + 4}"))
    }

    @Test
    fun `one entry cannot dominate the buffer`() {
        SnoozeDebugLog.event("x".repeat(SnoozeDebugLog.MAX_ENTRY_CHARS * 3))

        val entry = SnoozeDebugLog.snapshot().single()
        assertTrue(entry.length <= SnoozeDebugLog.MAX_ENTRY_CHARS + "…(truncated)".length)
        assertTrue(entry.endsWith("…(truncated)"))
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        SnoozeDebugLog.failure(a, "cycle")

        // Reaching the assertion is the test; a cycle would never get here.
        assertEquals(1, SnoozeDebugLog.snapshot().size)
    }

    @Test
    fun `recording off means off, and clears what was already collected`() {
        // Off has to stop *collection*, not only persistence: gating the file
        // sink alone left the buffer filling through the disabled period, and
        // a later re-enable would have written all of it to disk (Codex,
        // PR #62). Sinks only see what recording hands them, so this one gate
        // covers the logcat mirror too.
        val mirrored = mutableListOf<String>()
        SnoozeDebugLog.addSink { mirrored.add(it) }
        SnoozeDebugLog.event("collected while on")

        SnoozeDebugLog.setRecording(false)
        SnoozeDebugLog.event("collected while off")
        SnoozeDebugLog.setRecording(true)
        SnoozeDebugLog.event("collected after re-enable")

        val lines = SnoozeDebugLog.snapshot()
        assertEquals("disabling empties the buffer as well as gating it", 1, lines.size)
        assertTrue(lines.single().endsWith("collected after re-enable"))
        assertEquals(
            "no sink hears anything while recording is off",
            listOf("collected while on", "collected after re-enable"),
            mirrored.map { it.substringAfter(" D ") },
        )
    }

    @Test
    fun `each entry reaches every sink, and a throwing sink stops nothing`() {
        val seen = mutableListOf<String>()
        SnoozeDebugLog.addSink { throw IllegalStateException("sink broken") }
        SnoozeDebugLog.addSink { seen.add(it) }

        SnoozeDebugLog.event("fanned out")

        assertEquals(1, seen.size)
        assertTrue(seen.single().endsWith("fanned out"))
        assertEquals("the buffer keeps it too", 1, SnoozeDebugLog.snapshot().size)
    }

    private companion object {
        /** 2026-01-01T12:00:00Z, so the timestamp test is deterministic. */
        const val FIXED_MILLIS = 1_767_268_800_000L
    }
}
