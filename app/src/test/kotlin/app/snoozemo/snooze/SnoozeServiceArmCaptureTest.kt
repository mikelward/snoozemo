package app.snoozemo.snooze

import android.content.Intent
import app.snoozemo.R
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenOutcome
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The arm path's half of anchor capture: what the service starts, what it does
 * with the anchor, and which snooze is allowed to receive it.
 *
 * The assembly rules — the ceiling, the accuracy gate, the placeholder
 * rejection — are `AnchorCapture`'s and covered in `:core`; the platform
 * callbacks need a handset. What lives here is the wiring, driven through the
 * same seam pattern as the zen controller: the test is the runner, so it
 * delivers the anchor at exactly the moment under test.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceArmCaptureTest {

    private val now: Instant = Instant.parse("2026-08-22T09:00:00Z")

    // Stock stand-ins, never a device capture (AGENTS.md, *Privacy*).
    private val captured = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = now,
        ssid = "ExampleWifi",
        bssid = "00:11:22:33:44:55",
    )

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
        ActiveSnoozeStore(appContext).clear()
    }

    private fun sameInstance(service: TestSnoozeService, action: String, startId: Int) {
        service.onStartCommand(
            Intent(appContext, TestSnoozeService::class.java).setAction(action),
            0,
            startId,
        )
    }

    @Test
    fun `arming starts a capture and says Snoozing before it finishes`() {
        startService(SnoozeService.ACTION_ARM)

        assertEquals(1, TestSnoozeService.captureRequests.size)
        // The user is not kept waiting on the ceiling: the ongoing
        // notification is up while the anchor is still being captured.
        assertTrue(shadeShows(stringOf(R.string.ongoing_title)))
        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
    }

    @Test
    fun `the captured anchor is recorded, and the mode is the monitor's claim`() {
        startService(SnoozeService.ACTION_ARM)

        TestSnoozeService.captureRequests.single().invoke(captured)

        val record = ActiveSnoozeStore(appContext).load()
        assertEquals("ExampleWifi", record?.anchor?.ssid)
        assertEquals("00:11:22:33:44:55", record?.anchor?.bssid)
        assertEquals(true, record?.anchor?.hasUsableFix)
        // The mode is what the monitor says it can watch for these fields
        // (SPEC.md §6.1, §8.1) — a fenced anchor is fully watched now.
        assertEquals(TrackingMode.FULL, record?.mode)
    }

    @Test
    fun `ending during capture closes it, and a late anchor changes nothing`() {
        val controller = startService(SnoozeService.ACTION_ARM)

        sameInstance(controller.get(), SnoozeService.ACTION_END, startId = 2)

        assertTrue(TestSnoozeService.captureClosed >= 1)
        TestSnoozeService.captureRequests.single().invoke(captured)
        assertNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a capture outliving its snooze cannot land on the next one`() {
        // End-and-re-arm inside the ceiling: the old runner's anchor names a
        // place the new snooze was never at, and liveness alone cannot tell
        // the two apart — only identity can.
        val controller = startService(SnoozeService.ACTION_ARM)
        val service = controller.get()
        sameInstance(service, SnoozeService.ACTION_END, startId = 2)

        TestSnoozeService.testReading = TestSnoozeService.testReading.let {
            it.copy(wallMillis = it.wallMillis + 60_000, uptimeMillis = it.uptimeMillis + 60_000)
        }
        sameInstance(service, SnoozeService.ACTION_ARM, startId = 3)
        assertEquals(2, TestSnoozeService.captureRequests.size)

        TestSnoozeService.captureRequests.first().invoke(captured)

        val record = ActiveSnoozeStore(appContext).load()
        assertNull(record?.anchor?.ssid)
        assertEquals(TrackingMode.DURATION_ONLY, record?.mode)
    }
}
