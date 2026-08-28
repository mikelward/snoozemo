package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule is default-safe: an argument may leave the device only if its type
 * cannot name anything of the user's. Nothing leaves today — there is no mirror
 * — so these tests are what make adding one a change that cannot quietly widen
 * what is sent.
 */
class LogValueTest {

    @Test
    fun `a String is withheld, because that is what an SSID arrives as`() {
        assertFalse(logArgumentMayLeaveDevice("ExampleWifi"))
        assertFalse(logArgumentMayLeaveDevice("Home"))
    }

    @Test
    fun `numbers, booleans and enums are carried`() {
        // Distance and accuracy in meters are the diagnostic: they say whether
        // the departure test worked, without saying where.
        assertTrue(logArgumentMayLeaveDevice(42))
        assertTrue(logArgumentMayLeaveDevice(12.5f))
        assertTrue(logArgumentMayLeaveDevice(true))
        assertTrue(logArgumentMayLeaveDevice(TrackingMode.FULL))
        assertTrue(logArgumentMayLeaveDevice(null))
    }

    @Test
    fun `the type rule is a default, not a verdict, in both directions`() {
        assertTrue("fixed vocabulary opts in", logArgumentMayLeaveDevice(safe("android.intent.action.BOOT_COMPLETED")))
        assertFalse("a coordinate opts out despite being a Double", logArgumentMayLeaveDevice(sensitive(12.345678)))
    }

    @Test
    fun `an unknown type is withheld rather than guessed at`() {
        // The failure mode this design exists to avoid: a call site added later
        // is safe without anyone remembering to teach a filter about it.
        class SomethingNew(val ssid: String)
        assertFalse(logArgumentMayLeaveDevice(SomethingNew("ExampleWifi")))
    }

    @Test
    fun `the on-device rendering keeps everything the redacted one withholds`() {
        val args = arrayOf<Any?>("ExampleWifi", 42, safe("BOOT_COMPLETED"), sensitive(12.345678))
        val format = "ssid=%s distance=%s action=%s lat=%s"

        assertEquals(
            "ssid=ExampleWifi distance=42 action=BOOT_COMPLETED lat=12.345678",
            formatLogMessage(format, args, redactSensitive = false),
        )
        assertEquals(
            "ssid=$REDACTED_PLACEHOLDER distance=42 action=BOOT_COMPLETED lat=$REDACTED_PLACEHOLDER",
            formatLogMessage(format, args, redactSensitive = true),
        )
    }

    @Test
    fun `a summary splits itself rather than choosing for the whole`() {
        val summary = LogSummary(full = "mode=FULL ssid=ExampleWifi", mirrored = "mode=FULL")

        assertEquals("s=mode=FULL ssid=ExampleWifi", formatLogMessage("s=%s", arrayOf<Any?>(summary), redactSensitive = false))
        assertEquals("s=mode=FULL", formatLogMessage("s=%s", arrayOf<Any?>(summary), redactSensitive = true))
    }

    @Test
    fun `a mismatched format surfaces rather than dropping the value`() {
        // A surplus argument still goes through redaction, so a wrong format
        // string can never become a leak.
        assertEquals(
            "a=1 [unplaced arg] $REDACTED_PLACEHOLDER",
            formatLogMessage("a=%s", arrayOf<Any?>(1, "ExampleWifi"), redactSensitive = true),
        )
        assertEquals("a=%s", formatLogMessage("a=%s", emptyArray<Any?>(), redactSensitive = true))
    }
}
