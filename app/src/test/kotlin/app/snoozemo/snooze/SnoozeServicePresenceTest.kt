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
    fun `the backstop is armed before capture completes`() {
        // Capture takes up to 10 s, and a process death inside that window
        // used to leave a record and a rule with no fence and no periodic
        // wake — duration-only until the cap (Codex, PR #75). The backstop
        // must exist the moment the record does, not once the watch starts.
        startService(SnoozeService.ACTION_ARM)
        shadowOf(getMainLooper()).idle()

        val armed = androidx.work.WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWork(SnoozeBackstop.NAME).get()
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, armed.single().state)
        // And the capture really is still outstanding: no watch yet.
        assertTrue(TestSnoozeService.presence.startedWith.isEmpty())
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
                event = PresenceEvent.CapabilityLost(CapabilityLossCause.MONITORING_UNAVAILABLE),
                degradation = null,
            ),
        )

        assertNull(ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a degradation report lowers the recorded mode`() {
        armWatched()

        emit(PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX))

        // To WIFI_ONLY now, not the timer: the Wi-Fi watch backs the claim
        // (D4), so a fenced anchor that loses location falls to a mode
        // something is actually watching.
        assertEquals(TrackingMode.WIFI_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
    }

    @Test
    fun `grace running records WIFI_GRACE through the real fixture's supportedModes`() {
        // The fixture's supportedModes() above never lists WIFI_GRACE — same
        // as the real GeofencePresenceMonitor — so this is what actually
        // proves `honest()` doesn't walk it down to DURATION_ONLY for want
        // of its own explicit entry (Codex, PR #31; the mode half of the bug
        // the missing `graceActive` signal was the cause half of).
        armWatched()

        emit(PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX, graceActive = true))

        assertEquals(TrackingMode.WIFI_GRACE, ActiveSnoozeStore(appContext).load()?.mode)
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

    /**
     * The restart hand-off (Codex, PR #141). The monitor's own levels die with
     * the process — on `play` that is every wake, not an edge case — so the
     * record's cause is the only thing that can tell a restarted watch it is
     * resuming a degraded snooze rather than a healthy one. Without it the
     * first update carries a synthesized null and the card silently promotes.
     */
    @Test
    fun `a restore hands the monitor the cause the record already carries`() {
        armWatched()
        val armed = ActiveSnoozeStore(appContext).load()!!
        // Fresh arm: nothing degraded yet, so nothing to restore.
        assertEquals(listOf<DegradationCause?>(null), TestSnoozeService.presence.startedDegradations)

        val degraded = armed.copy(
            mode = TrackingMode.DURATION_ONLY,
            degradation = DegradationCause.FIXES_TOO_VAGUE,
        )
        TestSnoozeService.reset(now.plusSeconds(600))
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
        startService(SnoozeService.ACTION_CHECK_CAP, record = degraded)
        shadowOf(getMainLooper()).idle()

        assertEquals(
            listOf<DegradationCause?>(DegradationCause.FIXES_TOO_VAGUE),
            TestSnoozeService.presence.startedDegradations,
        )
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
    fun `a warm wake repairs a degraded watch without replacing it`() {
        // A cold wake re-registers through the restore; a warm service skips
        // it, so a fence whose registration failed transiently stayed dead
        // until the next cold start (Codex, PR #75). The repair is a poke,
        // never a restart: a replacement feed forgets its failure history,
        // and its first unanswered probe would promote a broken snooze back
        // to FULL (Codex, PR #75).
        val controller = startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single().invoke(captured)
        shadowOf(getMainLooper()).idle()
        emit(PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX))

        controller.get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java)
                .setAction(SnoozeService.ACTION_CHECK_CAP),
            0,
            2,
        )
        shadowOf(getMainLooper()).idle()

        assertEquals("the fence must be poked, not the watch restarted", 1, TestSnoozeService.repairPokes)
        assertEquals(
            "the collection — and the engine's memory — must survive the repair",
            1,
            TestSnoozeService.presence.startedWith.size,
        )
    }

    @Test
    fun `a healthy watch is left alone by a warm wake`() {
        // The repair must not churn: even the poke is skipped when the
        // recorded mode says the fence is healthy.
        val controller = startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single().invoke(captured)
        shadowOf(getMainLooper()).idle()

        controller.get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java)
                .setAction(SnoozeService.ACTION_CHECK_CAP),
            0,
            2,
        )
        shadowOf(getMainLooper()).idle()

        assertEquals(0, TestSnoozeService.repairPokes)
        assertEquals(1, TestSnoozeService.presence.startedWith.size)
    }

    @Test
    fun `a grant landing in the app re-asks a running monitor`() {
        // Android broadcasts no permission change, so a monitor that
        // degraded on a lost grant learned it was back only from the
        // backstop's next wake — up to half an hour with grace shut (Codex,
        // PR #150). The app's own permission read is the prompt now, and it
        // reaches the monitor as a poke: never a restart, for the reason the
        // repair poke is not one.
        val controller = startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single().invoke(captured)
        shadowOf(getMainLooper()).idle()

        controller.get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java)
                .setAction(SnoozeService.ACTION_LOCATION_GRANTED),
            0,
            2,
        )
        shadowOf(getMainLooper()).idle()

        assertEquals("the monitor must be asked, not restarted", 1, TestSnoozeService.grantPokes)
        assertEquals(1, TestSnoozeService.presence.startedWith.size)
        // Not gated on the recorded mode: a Wi-Fi-only anchor's latch never
        // shows there the way a fence repair does, so the mode cannot say
        // whether the poke is needed. The monitor's own slots decide.
        assertEquals(TrackingMode.FULL, ActiveSnoozeStore(appContext).load()?.mode)
    }

    @Test
    fun `a grant landing with no snooze running asks nothing`() {
        // Nothing is watching, so there is nothing to re-ask — and a cold
        // start with a record restores on the way in, which registers and
        // re-asks by itself.
        startService(SnoozeService.ACTION_LOCATION_GRANTED)
        shadowOf(getMainLooper()).idle()

        assertEquals(0, TestSnoozeService.grantPokes)
    }

    @Test
    fun `a warm restore wake re-enqueues a missing backstop`() {
        // The schedule-rejection retry alarm fires ACTION_RESTORE; warm, the
        // restore is a no-op, so the branch must carry the re-enqueue itself
        // or the promised retry retries nothing (Codex, PR #75).
        val controller = startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single().invoke(captured)
        shadowOf(getMainLooper()).idle()
        // The enqueue this simulates having failed.
        androidx.work.WorkManager.getInstance(appContext)
            .cancelUniqueWork(SnoozeBackstop.NAME).result.get()

        controller.get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java)
                .setAction(SnoozeService.ACTION_RESTORE),
            0,
            2,
        )
        shadowOf(getMainLooper()).idle()

        val infos = androidx.work.WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWork(SnoozeBackstop.NAME).get()
        assertTrue(
            "the warm retry must re-enqueue the backstop",
            infos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED },
        )
    }

    @Test
    fun `the watch arms the periodic backstop and the end retires it`() {
        // The backstop rides with the watch (SPEC.md §6.10): armed wherever a
        // watch starts, gone when the snooze ends, so its wakes are paid only
        // while there is something to catch.
        armWatched()
        val armed = androidx.work.WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWork(SnoozeBackstop.NAME).get()
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, armed.single().state)

        startService(SnoozeService.ACTION_END)
        shadowOf(getMainLooper()).idle()

        val after = androidx.work.WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWork(SnoozeBackstop.NAME).get()
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, after.single().state)
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
    fun `a refused presence retry re-arms itself with one fewer attempt`() {
        // A single spent firing must not burn the whole "durable" retry while
        // its caller's budget still stands (Codex, PR #75); the bound travels
        // on the alarm because the alarm outlives the process that armed it.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val refusing = object : android.content.ContextWrapper(appContext) {
            override fun startService(service: Intent?): android.content.ComponentName? =
                throw IllegalStateException("the platform refuses the start")
        }

        CapAlarmReceiver().onReceive(
            refusing,
            Intent(SnoozeService.ACTION_RESTORE)
                .putExtra(SnoozeService.EXTRA_RETRIES_LEFT, 2),
        )

        val rearmed = scheduledAlarmIntents().single { it.action == SnoozeService.ACTION_RESTORE }
        assertEquals(1, rearmed.getIntExtra(SnoozeService.EXTRA_RETRIES_LEFT, -1))
    }

    @Test
    fun `a refused re-arm falls to an in-process rung, keeping the budget`() {
        // The re-arm's own alarm can be refused too, and dropping that result
        // stranded the remaining attempts with no successor before the cap
        // (Codex, PR #75). The process running the receiver is alive, so its
        // handler is the same last rung the app's wake ladder uses.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        var alarmsRefused = true
        val refusing = object : android.content.ContextWrapper(appContext) {
            override fun startService(service: Intent?): android.content.ComponentName? =
                throw IllegalStateException("the platform refuses the start")

            override fun getSystemService(name: String): Any? =
                if (name == android.content.Context.ALARM_SERVICE && alarmsRefused) {
                    null
                } else {
                    super.getSystemService(name)
                }
        }

        CapAlarmReceiver().onReceive(
            refusing,
            Intent(SnoozeService.ACTION_RESTORE)
                .putExtra(SnoozeService.EXTRA_RETRIES_LEFT, 2),
        )
        assertTrue(
            "the refused re-arm must not pretend an alarm is standing",
            scheduledAlarmIntents().none { it.action == SnoozeService.ACTION_RESTORE },
        )

        // The alarm recovers before the in-process rung fires; the restore it
        // retries is still refused, so the rung re-enters the ladder one
        // attempt down rather than dropping the chain.
        alarmsRefused = false
        shadowOf(getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))

        val rearmed = scheduledAlarmIntents().single { it.action == SnoozeService.ACTION_RESTORE }
        assertEquals(0, rearmed.getIntExtra(SnoozeService.EXTRA_RETRIES_LEFT, -1))
    }

    @Test
    fun `a fired retry alarm hands its budget to the backstop before restoring`() {
        // The wiring half of the adoption rule: the receiver adopts the
        // alarm's remaining count before the restore, so a schedule rejected
        // again inside a fresh process spends the same bound, not a refilled
        // one (Codex, PR #75).
        SnoozeBackstop.cancel(appContext)
        shadowOf(getMainLooper()).idle()
        assertEquals(SnoozeBackstop.SCHEDULE_RETRIES, SnoozeBackstop.retryBudget())
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))

        CapAlarmReceiver().onReceive(
            appContext,
            Intent(SnoozeService.ACTION_RESTORE)
                .putExtra(SnoozeService.EXTRA_RETRIES_LEFT, 1),
        )

        assertEquals(1, SnoozeBackstop.retryBudget())
    }

    @Test
    fun `an exhausted presence retry rests on the cap instead of looping`() {
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val refusing = object : android.content.ContextWrapper(appContext) {
            override fun startService(service: Intent?): android.content.ComponentName? =
                throw IllegalStateException("the platform refuses the start")
        }

        CapAlarmReceiver().onReceive(
            refusing,
            Intent(SnoozeService.ACTION_RESTORE)
                .putExtra(SnoozeService.EXTRA_RETRIES_LEFT, 0),
        )

        assertTrue(scheduledAlarmIntents().none { it.action == SnoozeService.ACTION_RESTORE })
        assertNotNull("no early end either — the cap is the bound", ActiveSnoozeStore(appContext).load())
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
    fun `an SSID-only anchor arms as a real Wi-Fi watch`() {
        // The Wi-Fi watch backs the claim now (D4): loss escalates, and the
        // grace alarm ends an unverifiable snooze — a watch, not a labeled
        // timer.
        startService(SnoozeService.ACTION_ARM)
        TestSnoozeService.captureRequests.single()
            .invoke(Anchor(capturedAt = now, ssid = "ExampleWifi"))
        shadowOf(getMainLooper()).idle()

        assertEquals(TrackingMode.WIFI_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
    }
}
