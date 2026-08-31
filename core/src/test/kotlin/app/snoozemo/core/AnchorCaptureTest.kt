package app.snoozemo.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorCaptureTest {

    private val capturedAt: Instant = Instant.parse("2026-08-22T09:00:00Z")

    private fun capture() = AnchorCapture(capturedAt)

    @Test
    fun `completes as soon as both halves have answered`() {
        val capture = capture()

        assertNull(capture.onWifi("\"ExampleWifi\"", "00:11:22:33:44:55"))
        val anchor = capture.onFix(0.0, 0.0, 25f)

        assertNotNull(anchor)
        assertEquals("ExampleWifi", anchor?.ssid)
        assertEquals("00:11:22:33:44:55", anchor?.bssid)
        assertEquals(25f, anchor?.fixAccuracyM)
        assertEquals(capturedAt, anchor?.capturedAt)
        assertEquals(true, anchor?.hasUsableFix)
    }

    @Test
    fun `the ceiling finishes with whatever arrived`() {
        // Arming must never block on capture (SPEC.md §4.1): a ceiling with
        // only the Wi-Fi half in still arms, Wi-Fi-only shaped.
        val capture = capture()
        capture.onWifi("ExampleWifi", null)

        val anchor = capture.onCeiling()

        assertEquals("ExampleWifi", anchor?.ssid)
        assertNull(anchor?.lat)
        assertEquals(false, anchor?.hasUsableFix)
    }

    @Test
    fun `the ceiling with nothing at all still answers`() {
        val anchor = capture().onCeiling()

        assertNotNull(anchor)
        assertNull(anchor?.ssid)
        assertNull(anchor?.lat)
    }

    @Test
    fun `a vague fix is discarded, not stored, and a better one still counts`() {
        // The gate exists because a 500 m cell fix cannot distinguish leaving
        // from standing still (SPEC.md §8.4) — and discarding must keep the
        // question open rather than spending the location half on nothing.
        val capture = capture()
        capture.onNoWifi()

        assertNull(capture.onFix(0.0, 0.0, 500f))
        val anchor = capture.onFix(0.0, 0.0, 30f)

        assertEquals(30f, anchor?.fixAccuracyM)
    }

    @Test
    fun `a vague fix never reaches the anchor even at the ceiling`() {
        val capture = capture()
        capture.onFix(0.0, 0.0, Anchor.MAX_ANCHOR_ACCURACY_M + 1f)

        val anchor = capture.onCeiling()

        assertNull(anchor?.lat)
        assertNull(anchor?.fixAccuracyM)
    }

    @Test
    fun `a fix with no accuracy is no fix`() {
        val capture = capture()
        capture.onNoWifi()

        assertNull(capture.onFix(0.0, 0.0, accuracyM = null))
        assertNull(capture.onCeiling()?.lat)
    }

    @Test
    fun `the redacted placeholders are rejected, never anchored on`() {
        // What a callback without location access hands back (SPEC.md §6.4):
        // real objects, plausible strings, an anchor that matches nothing. A
        // test that accepted them as values would hide exactly that failure.
        val capture = capture()

        assertNull(capture.onWifi("<unknown ssid>", "02:00:00:00:00:00"))
        val anchor = capture.onCeiling()

        assertNull(anchor?.ssid)
        assertNull(anchor?.bssid)
    }

    @Test
    fun `a redacted read keeps the question open for a real one`() {
        // The redaction can race the grant; the first useless answer must not
        // close the Wi-Fi half against the real one behind it.
        val capture = capture()
        capture.onWifi("<unknown ssid>", "02:00:00:00:00:00")

        capture.onWifi("\"ExampleWifi\"", "00:11:22:33:44:55")
        val anchor = capture.onCeiling()

        assertEquals("ExampleWifi", anchor?.ssid)
    }

    @Test
    fun `a blank ssid is no answer`() {
        val capture = capture()
        capture.onWifi("\"\"", "00:11:22:33:44:55")

        assertNull(capture.onCeiling()?.ssid)
    }

    @Test
    fun `a bssid without an ssid is recorded as nothing`() {
        // The SSID is the anchor (SPEC.md §6.2); a lone AP identifier anchors
        // nothing and is not worth keeping.
        val capture = capture()
        capture.onWifi("<unknown ssid>", "00:11:22:33:44:55")

        assertNull(capture.onCeiling()?.bssid)
    }

    @Test
    fun `losing wifi settles that half as none`() {
        val capture = capture()
        capture.onNoWifi()

        val anchor = capture.onFix(0.0, 0.0, 15f)

        assertNull(anchor?.ssid)
        assertEquals(true, anchor?.hasUsableFix)
    }

    @Test
    fun `a definitive no-fix completes without waiting for the ceiling`() {
        // Permission missing or location off: nothing better is coming, and
        // waiting would only delay the armed record.
        val capture = capture()
        capture.onWifi("ExampleWifi", null)

        assertNotNull(capture.onNoFix())
    }

    @Test
    fun `the anchor is delivered exactly once`() {
        val capture = capture()
        capture.onNoWifi()

        assertNotNull(capture.onNoFix())
        assertNull(capture.onCeiling())
        assertNull(capture.onWifi("ExampleWifi", null))
        assertNull(capture.onFix(0.0, 0.0, 10f))
    }

    @Test
    fun `awaitingFix reports whether a live location request is still needed`() {
        // What lets a seeded fix — the cached one read while the tap still
        // holds the app foreground — skip the ten-second request entirely.
        val capture = capture()
        assertEquals(true, capture.awaitingFix)

        capture.onFix(0.0, 0.0, 500f)
        assertEquals(true, capture.awaitingFix)

        capture.onFix(0.0, 0.0, 20f)
        assertEquals(false, capture.awaitingFix)
    }

    @Test
    fun `the first settled answer for a half wins`() {
        // Callbacks repeat; the anchor is what the tap saw, not the last
        // reading before the ceiling.
        val capture = capture()
        capture.onWifi("\"ExampleWifi\"", null)

        capture.onWifi("\"OtherWifi\"", null)
        val anchor = capture.onCeiling()

        assertEquals("ExampleWifi", anchor?.ssid)
    }

    @Test
    fun `a redacted read is told apart from no network at all`() {
        // The distinction `sanitizeSsid` cannot express, because both answer
        // "not a usable anchor value". A watch needs the other question —
        // *did the platform answer?* — since a withheld SSID says nothing
        // about whether the anchor's network is there, and reading it as an
        // absence is what ended a snooze on a phone sitting on its own
        // network.
        assertTrue(AnchorCapture.isRedactedSsid("<unknown ssid>"))
        assertTrue(
            "the platform quotes what it reports; the placeholder is seen both ways",
            AnchorCapture.isRedactedSsid("\"<unknown ssid>\""),
        )

        // Every one of these sanitizes to null, and none of them is a
        // redaction: no network, an empty name, a real one.
        assertFalse(AnchorCapture.isRedactedSsid(null))
        assertFalse(AnchorCapture.isRedactedSsid(""))
        assertFalse(AnchorCapture.isRedactedSsid("\"ExampleWifi\""))
    }

}
