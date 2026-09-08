package app.snoozemo.snooze

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import app.snoozemo.R
import app.snoozemo.core.Anchor
import app.snoozemo.core.DepartureObservation
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenOutcome
import app.snoozemo.ui.MainActivity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

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
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
        ActiveSnoozeStore(appContext).clear()
        // Process-wide and in memory, so a reading published by an earlier test
        // in this JVM is still the latest one here — which the distance on the
        // card now reads (SPEC.md §4.6).
        DepartureObservations.clear()
    }

    /**
     * The mode on disk, read against the test's own clock.
     *
     * `SETTLING` on a record expires against wall-clock now — a capture cannot
     * outlive its ceiling, so a stored one that has is a capture killed with
     * its process (Codex, PR #221). These tests run on a fixed instant, so the
     * default real clock would read every record they write as ancient and
     * resolve the claim away before the assertion saw it.
     */
    private fun storedMode(): TrackingMode? =
        ActiveSnoozeStore(appContext) { TestSnoozeService.testReading.wallMillis }.load()?.mode

    /** The top row of the ongoing card currently in the shade. */
    private fun ongoingSubText(): String? =
        shadowOf(appContext.getSystemService(NotificationManager::class.java))
            .allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.ongoing_title) }
            .extras
            .getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)
            ?.toString()

    /** The body line of the ongoing card currently in the shade. */
    private fun ongoingBody(): String? =
        shadowOf(appContext.getSystemService(NotificationManager::class.java))
            .allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.ongoing_title) }
            .let { shadowOf(it).contentText?.toString() }

    private fun sameInstance(service: TestSnoozeService, action: String, startId: Int) {
        service.onStartCommand(
            Intent(appContext, TestSnoozeService::class.java).setAction(action),
            0,
            startId,
        )
    }

    @Test
    fun `the state transition keeps the snooze summary separated from the state`() {
        // Converting this line to format-plus-arguments dropped the "; " and
        // ran the two together — `state → ARMINGsnooze(...)` (Codex, PR #129).
        // It is the first line anyone reads in a debug report, so an ambiguous
        // one costs exactly the diagnostic the log exists for.
        SnoozeDebugLog.resetForTest()

        startService(SnoozeService.ACTION_ARM)

        val withSummary = SnoozeDebugLog.snapshot()
            .filter { it.contains("state → ") && it.contains("snooze(") }
        assertTrue("no transition carried a summary to check", withSummary.isNotEmpty())
        assertTrue(withSummary.toString(), withSummary.all { it.contains("; snooze(") })
    }

    @Test
    fun `arming starts a capture and says Snoozing before it finishes`() {
        startService(SnoozeService.ACTION_ARM)

        assertEquals(1, TestSnoozeService.captureRequests.size)
        // The user is not kept waiting on the ceiling: the ongoing
        // notification is up while the anchor is still being captured.
        assertTrue(shadeShows(stringOf(R.string.ongoing_title)))
        // And it does not yet claim a mode. The record used to read
        // DURATION_ONLY here, which the card rendered as "timer only" —
        // a capability report about a capture that had not finished
        // (maintainer, 2026-09-07, from a device log).
        assertEquals(TrackingMode.SETTLING, storedMode())
    }

    @Test
    fun `the ongoing card says it is still checking, not that it is a timer`() {
        // The half the first version of this fix missed entirely: `armWithCap`
        // posts this card *before* it starts the capture, so the notification
        // carried "Timer only" for the whole ~10 s window exactly as the
        // screen did (Codex, PR #221). Asserted on the card's own text
        // rather than on the record, because a mode the notification never
        // reads is a mode that fixes nothing.
        startService(SnoozeService.ACTION_ARM)

        assertEquals(stringOf(R.string.ongoing_settling), ongoingBody())
    }

    @Test
    fun `the ongoing card names the real mode once the anchor lands`() {
        startService(SnoozeService.ACTION_ARM)

        TestSnoozeService.captureRequests.single().invoke(captured)

        // And it does not stay on the settling copy: a card that never stops
        // saying "checking" is the same wrong claim, held for ever instead of
        // ten seconds.
        assertEquals(stringOf(R.string.ongoing_ends_when_you_leave), ongoingBody())
    }

    @Test
    fun `a capture that finds nothing settles the card on the timer`() {
        startService(SnoozeService.ACTION_ARM)

        TestSnoozeService.captureRequests.single().invoke(
            Anchor(lat = null, lon = null, fixAccuracyM = null, capturedAt = now),
        )

        // "Timer only" is still the right answer when it is true. This is the
        // assertion that stops the fix from being "never say timer only".
        assertEquals(stringOf(R.string.ongoing_timer_only), ongoingBody())
    }

    @Test
    fun `a build that can never track posts the timer card from the start`() {
        // The `direct` flavor's whole life until Phase 7. Driven through the
        // service so the flavor's answer really reaches the card, rather than
        // being asserted on the controller alone — the omission that let the
        // first version of this fix ship a no-op (Codex, PR #221).
        TestSnoozeService.presence.canTrackDeparture = false

        startService(SnoozeService.ACTION_ARM)

        assertEquals(stringOf(R.string.ongoing_timer_only), ongoingBody())
        assertEquals(TrackingMode.DURATION_ONLY, storedMode())
    }

    @Test
    fun `tapping the ongoing notification opens the app`() {
        startService(SnoozeService.ACTION_ARM)

        val ongoing = shadowOf(appContext.getSystemService(NotificationManager::class.java))
            .allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.ongoing_title) }
        val opens = shadowOf(ongoing.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, opens?.component?.className)
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
    fun `a fresh reading reposts the card, so the distance is not frozen`() {
        // The half a notification test alone cannot cover. `buildOngoing` puts
        // the distance on the card, but the card is a posted object: without a
        // repost it keeps the number it was built with, which is worse than
        // showing none — a distance that looks current and is not (SPEC.md
        // §4.6).
        val service = startService(SnoozeService.ACTION_ARM).get()
        TestSnoozeService.captureRequests.single().invoke(captured)
        // Settle first, so nothing is left queued that would repost for its own
        // reasons: `showOngoing` schedules a calendar-offer refresh after the
        // card is up, and that would land after the reading below and rebuild
        // the card from it — passing this test with the repost removed.
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("nothing to show before the first fix", ongoingSubText())

        service.onDepartureObservation(
            DepartureObservation(
                distanceM = 60.0,
                accuracyM = 15f,
                anchorAccuracyM = 10f,
                radiusM = 100,
                elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            ),
        )

        // 60 m inside a 100 m anchor: 18.0 m of combined uncertainty, so 108.0 m
        // of ground left once the 50 m hysteresis is counted — 355 ft on this
        // US-locale test device, ceiled after the conversion rather than from a
        // rounded meter figure.
        assertEquals(
            appContext.getString(R.string.distance_feet, 355),
            ongoingSubText(),
        )
    }

    @Test
    fun `the distance leaves the card when its reading goes stale`() {
        // Codex, PR #228. The freshness test runs while the card is *built*,
        // and a card is a posted object — so without an expiry the row keeps a
        // distance the app itself no longer trusts. At rest that gap is real:
        // the duty cycle can put the next fix ten minutes out against a
        // five-minute window (SPEC.md §6.7).
        val service = startService(SnoozeService.ACTION_ARM).get()
        TestSnoozeService.captureRequests.single().invoke(captured)
        shadowOf(Looper.getMainLooper()).idle()
        val observation = DepartureObservation(
            distanceM = 60.0,
            accuracyM = 15f,
            anchorAccuracyM = 10f,
            radiusM = 100,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )

        service.onDepartureObservation(observation)

        assertNotNull("the reading is on the card to begin with", ongoingSubText())
        val alarms = shadowOf(appContext.getSystemService(AlarmManager::class.java))
        // Picked by its listener, not by position: the cap's own wake-up alarm
        // is also scheduled here, and it is the one that happens to be last.
        val scheduled = alarms.scheduledAlarms.single { it.onAlarmListener != null }
        // On the elapsed-realtime clock, which is the one `isFresh` reads
        // (Codex, PR #228, second pass): `Handler.postDelayed` measures against
        // uptime, which stops in deep sleep, so a phone that slept after a fix
        // would wake still showing an expired distance.
        assertEquals(AlarmManager.ELAPSED_REALTIME, scheduled.type)
        // And past the window rather than on it, since `isFresh` is inclusive.
        assertEquals(
            observation.elapsedRealtimeMs + DepartureObservation.FRESH_FOR_MS + 1,
            scheduled.triggerAtTime,
        )

        // The clock reaching that time is what the alarm represents, so the
        // test moves it before firing — otherwise the reading is still fresh
        // and the repost rebuilds the same row.
        ShadowSystemClock.advanceBy(
            java.time.Duration.ofMillis(DepartureObservation.FRESH_FOR_MS + 1),
        )
        scheduled.onAlarmListener!!.onAlarm()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("the stale distance was cleared", ongoingSubText())
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

        assertNull(ActiveSnoozeStore(appContext).load()?.anchor?.ssid)
        // Still SETTLING: the new snooze's own capture is the one that
        // gets to answer for it, and that has not landed.
        assertEquals(TrackingMode.SETTLING, storedMode())
    }
}
