package app.snoozemo.snooze

import android.content.Intent
import android.os.Looper.getMainLooper
import app.snoozemo.core.Anchor
import app.snoozemo.core.CapabilityLossCause
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.PresenceEvent
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenOutcome
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

/**
 * The wiring that makes "until you leave" a watched promise: the service
 * starts the monitor with the captured anchor, feeds its reports to the
 * controller, and takes the watch down with the snooze.
 *
 * Driven through the monitor seam; what a real geofence or fix delivery does
 * to a report is the monitor's own tested ground, and a handset's.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServicePresenceTest {

    private val now: Instant = Instant.parse("2026-08-22T10:00:00Z")

    // Stock stand-ins, never a device capture (AGENTS.md, *Privacy*).
    private val captured = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = now,
        ssid = "ExampleWifi",
    )

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
        ActiveSnoozeStore(appContext).clear()
    }

    /** Arms and completes capture, so the watch is running. */
    private fun armWatched() {
        startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single().invoke(captured)
        shadowOf(getMainLooper()).idle()
    }

    private fun emit(update: PresenceUpdate) {
        assertTrue(TestSnoozeService.presence.updates.tryEmit(update))
        shadowOf(getMainLooper()).idle()
    }

    @Test
    fun `the captured anchor is watched and the mode says so`() {
        armWatched()

        assertEquals(listOf(captured), TestSnoozeService.presence.startedWith)
        // FULL is a claim with a warrant now: the monitor supports it and the
        // service is collecting its reports.
        assertEquals(TrackingMode.FULL, ActiveSnoozeStore(appContext).load()?.mode)
    }

    @Test
    fun `a confirmed departure ends the snooze`() {
        armWatched()

        emit(PresenceUpdate(event = PresenceEvent.Departed, degradation = null))

        assertNull(ActiveSnoozeStore(appContext).load())
        assertTrue(TestSnoozeService.zen.calls.any { !it.first })
        assertTrue(TestSnoozeService.presence.stops >= 1)
    }

    @Test
    fun `capability loss fails open`() {
        armWatched()

        emit(
            PresenceUpdate(
                event = PresenceEvent.CapabilityLost(CapabilityLossCause.LOCATION_PERMISSION_REVOKED),
                degradation = null,
            ),
        )

        assertNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a degradation report lowers the recorded mode`() {
        armWatched()

        emit(PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX))

        // Straight to the timer, not to WIFI_ONLY: the monitor's supported
        // set has no Wi-Fi watch to fall back on (Codex, PR #73).
        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
    }

    @Test
    fun `ending the snooze stops the watch`() {
        val controller = startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single().invoke(captured)
        shadowOf(getMainLooper()).idle()

        controller.get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java).setAction(SnoozeService.ACTION_END),
            0,
            2,
        )

        assertTrue(TestSnoozeService.presence.stops >= 1)
        assertNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `the watch is seeded from the arm moment, at arm and at restore`() {
        // The engine drops observations older than its seed, so a seed of
        // "now" at restore would drop the very exit the restart was woken to
        // collect (Codex, PR #73). The record's stored clock frame is what
        // restates the arm moment in elapsed realtime.
        armWatched()
        val armed = ActiveSnoozeStore(appContext).load()!!
        val expected = armed.startedAt.toEpochMilli() - armed.bootReference!!
        assertEquals(listOf(expected), TestSnoozeService.presence.startedSeeds)

        // A later restore — fresh instance, same record — seeds identically,
        // not from its own later clock.
        TestSnoozeService.reset(now.plusSeconds(600))
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
        startService(SnoozeService.ACTION_CHECK_CAP, record = armed)
        shadowOf(getMainLooper()).idle()

        assertEquals(listOf(expected), TestSnoozeService.presence.startedSeeds)
    }

    @Test
    fun `a snooze ended from a cold process still takes the watch down`() {
        // An `End now` on a fresh process adopts the record without ever
        // starting a collection — but the durable geofence its arm registered
        // is still out there, and the end is the only thing that removes it
        // (Codex, PR #73).
        startService(SnoozeService.ACTION_END, record = snoozeFixture(now))

        assertTrue(TestSnoozeService.presence.stops >= 1)
        assertNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a refused presence-driven end enters the release ladder`() {
        // The engine's question is answered — no further presence event is
        // coming — so a refusal here has no natural retry the way a tap or a
        // due cap does. Left unescalated, a *confirmed* departure kept the
        // phone quiet for the rest of the cap with nothing trying and nothing
        // said (Codex, PR #73). Same pairing as `End now`.
        armWatched()
        TestSnoozeService.zen.outcome =
            ZenOutcome.NotApplied(app.snoozemo.core.ZenFailure.PLATFORM_REFUSED)

        emit(PresenceUpdate(event = PresenceEvent.Departed, degradation = null))

        assertTrue(
            "the refused end must arm its release retry, not wait out the cap",
            scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_CAP_LOST },
        )
    }

    @Test
    fun `starting the watch never passes through the snooze-over teardown`() {
        // `stopPresence` means the snooze is over: the monitor's stop()
        // settles the bridge's held exit and removes the durable fence, so a
        // start routed through it cleared the very exit a restore was woken
        // to collect before the new flow could attach (Codex, PR #73). A
        // start replaces the collection only.
        armWatched()
        assertEquals(0, TestSnoozeService.presence.stops)

        // The restore path — the one the wake ladder drives for a held exit —
        // is where the cleared mailbox cost the departure.
        val armed = ActiveSnoozeStore(appContext).load()!!
        TestSnoozeService.reset(now.plusSeconds(60))
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
        startService(SnoozeService.ACTION_CHECK_CAP, record = armed)
        shadowOf(getMainLooper()).idle()

        assertEquals(0, TestSnoozeService.presence.stops)
    }

    @Test
    fun `the presence retry alarm leaves the cap alarm standing`() {
        // The retry once borrowed the cap's pending intent, so arming it
        // silently displaced the real cap until a successful restore re-armed
        // it (Codex, PR #73). Its own action keeps both scheduled.
        assertTrue(CapAlarm.armCheckIn(appContext, java.time.Duration.ofHours(8).toMillis()))
        assertTrue(CapAlarm.armPresenceRetry(appContext, 60_000L))

        val armed = scheduledAlarmIntents().map { it.action }
        assertTrue("the cap alarm must survive the retry", SnoozeService.ACTION_CHECK_CAP in armed)
        assertTrue("the retry must be armed too", SnoozeService.ACTION_RESTORE in armed)
    }

    @Test
    fun `a presence retry refused again waits for the cap, not an early end`() {
        // The receiver's no-service fallback for a cap wake is an immediate
        // release — right for a spent cap alarm, and an end hours early under
        // DURATION_CAP for a retry armed a minute ago (Codex, PR #73). The
        // retry's own action must never reach that branch: its successor is
        // the cap alarm it left standing.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val refusing = object : android.content.ContextWrapper(appContext) {
            override fun startService(service: Intent?): android.content.ComponentName? =
                throw IllegalStateException("the platform refuses the start")
        }

        CapAlarmReceiver().onReceive(refusing, Intent(SnoozeService.ACTION_RESTORE))

        assertNotNull(
            "the snooze must wait for its still-armed cap, not end hours early",
            ActiveSnoozeStore(appContext).load(),
        )
    }

    @Test
    fun `an SSID-only anchor arms as a timer, not a Wi-Fi claim`() {
        // Nothing watches Wi-Fi yet (TODO.md): the monitor says so through
        // `supports`, and the record must not promise more.
        startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single()
            .invoke(Anchor(capturedAt = now, ssid = "ExampleWifi"))
        shadowOf(getMainLooper()).idle()

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
    }
}
