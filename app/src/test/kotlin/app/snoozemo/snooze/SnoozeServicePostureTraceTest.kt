package app.snoozemo.snooze

import app.snoozemo.core.Anchor
import android.content.Intent
import android.os.Looper.getMainLooper
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.PresenceUpdate
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenOutcome
import app.snoozemo.presence.PostureSample
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The posture trace through the service (SPEC.md §4.6): a reading per
 * transition, the pick-up watch matched to a running snooze, and — the half
 * that matters — nothing acted on. The sensors are the harness's fake; a
 * reading is a delivered sample and a pick-up is a method call.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServicePostureTraceTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied(OWN_RULE_ID)
        TogglableAlarmManager.refuse = false
        SnoozeDebugLog.resetForTest()
    }

    private val sensors get() = TestSnoozeService.postureSensors

    private fun postureLines(): List<String> = SnoozeDebugLog.snapshot().filter { it.contains("posture") }

    @Test
    fun `a restored snooze takes a reading and watches for a pick-up`() {
        startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now))

        assertEquals("one reading, for the one transition", 1, sensors.pendingSamples.size)
        assertTrue(sensors.watchingPickUp)

        sensors.deliver(PostureSample(gravityZ = -9.6f, proximityNear = true))

        val line = postureLines().single()
        assertTrue(line, line.contains("posture after state → ARMED: face down, sensor covered"))
    }

    @Test
    fun `an arm takes its first reading once the rule is on, not while arming`() {
        startService(SnoozeService.ACTION_ARM) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, now.plus(Duration.ofHours(2)).toEpochMilli())
        }
        assertEquals("the arm path takes no sensor lookup", 0, sensors.samplesAsked)

        // The capture landing is what moves the snooze to ARMED.
        TestSnoozeService.captureRequests.single().invoke(
            Anchor(lat = null, lon = null, fixAccuracyM = null, capturedAt = now),
        )

        assertEquals("ARMED took the first one", 1, sensors.samplesAsked)
        sensors.deliver(PostureSample(9.8f, false))
        assertFalse(postureLines().any { it.contains("ARMING") })
        assertTrue(postureLines().any { it.contains("after state → ARMED: face up, sensor clear") })
    }

    @Test
    fun `a refused arm takes no reading and builds no trace`() {
        // The refusal reaches IDLE synchronously, inside the arm: no snooze
        // ran, so there is no posture to compare, and building the trace
        // there would put the sensor lookup on the arm path (Codex, PR #258).
        TestSnoozeService.zen.outcome = ZenOutcome.NotApplied(app.snoozemo.core.ZenFailure.PLATFORM_REFUSED)
        startService(SnoozeService.ACTION_ARM) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, now.plus(Duration.ofHours(2)).toEpochMilli())
        }
        // The rule is refused before any anchor is captured, so the arm is
        // over here — no capture to land.
        shadowOf(getMainLooper()).idle()

        assertNull("the setup this rests on: nothing armed", ActiveSnoozeStore(appContext).load())
        assertEquals("no trace for a snooze that never ran", 0, TestSnoozeService.postureTracesBuilt)
        assertEquals(0, sensors.samplesAsked)
        assertTrue(postureLines().isEmpty())
    }

    @Test
    fun `an ending on a fresh service takes its reading`() {
        // `End now` from the app screen starts the service cold: the record
        // is adopted, never ARMED here, and the ending is the first
        // transition this instance sees. It is half of what the trial
        // compares, so the trace is built for it (Codex, PR #258).
        startService(SnoozeService.ACTION_END, record = snoozeFixture(now))

        assertEquals(1, TestSnoozeService.postureTracesBuilt)
        assertEquals("one reading, for the ending", 1, sensors.samplesAsked)
        sensors.deliver(PostureSample(9.8f, false))
        val line = postureLines().single { it.contains("after state →") }
        assertTrue(line, line.contains("after state → RELEASED"))
        assertTrue(line, line.contains("face up, sensor clear"))
        assertFalse("nothing to pick up once the snooze is over", sensors.watchingPickUp)
    }

    @Test
    fun `adjusting a running snooze is not a transition and takes no reading`() {
        // `+30 min` restates the running state through the same callback as
        // a transition; a reading there would be labeled like one and taken
        // during a settings interaction (Codex, PR #258).
        // Two hours in, so `+30 min` has room under the eight-hour ceiling.
        val record = snoozeFixture(now, capIn = Duration.ofHours(2))
        val controller = startService(SnoozeService.ACTION_RESTORE, record)
        sensors.deliver(PostureSample(-9.8f, true))
        assertEquals(1, sensors.samplesAsked)

        controller
            .withIntent(Intent(appContext, TestSnoozeService::class.java).setAction(SnoozeService.ACTION_EXTEND))
            .startCommand(0, 2)
        shadowOf(getMainLooper()).idle()

        val stored = ActiveSnoozeStore(appContext).load()
        assertTrue("the setup this rests on: the cap moved", stored != null && stored.capExpiresAt.isAfter(record.capExpiresAt))
        assertEquals("still the one reading, for the one transition", 1, sensors.samplesAsked)
        assertEquals(1, postureLines().count { it.contains("after state →") })
        assertTrue("and the pick-up watch is unchanged", sensors.watchingPickUp)
    }

    @Test
    fun `a pick-up is written down and changes nothing`() {
        startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now))
        sensors.deliver(PostureSample(-9.8f, true))

        sensors.firePickUp()

        assertTrue(postureLines().any { it.contains("pick-up gesture fired; nothing acted on it") })
        assertNotNull("the snooze is still running", ActiveSnoozeStore(appContext).load())
        assertTrue("the rule was not touched", TestSnoozeService.zen.calls.none { !it.first })
        assertTrue("and the watch is re-armed for the next one", sensors.watchingPickUp)
        sensors.deliver(PostureSample(4f, false))
        assertTrue(postureLines().any { it.contains("posture on pick-up: on edge, sensor clear") })
    }

    @Test
    fun `an ending takes a last reading and stops watching`() {
        val controller = startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now).copy(endsOnMotion = true))
        sensors.deliver(PostureSample(-9.8f, true))

        TestSnoozeService.motionRegistrar.fire()

        assertFalse("nothing to pick up once the snooze is over", sensors.watchingPickUp)
        // Asked for while the service still held the process — the ended
        // card's post gives the foreground back, and a reading registered
        // after that hears nothing (Codex, PR #258).
        assertEquals("the foreground was given back by the ending", 1, TestSnoozeService.foregroundExits)
        assertEquals("but after the reading was asked for", 0, sensors.foregroundExitsAtAsk.last())
        // The ending stops the service, and the sensor answers after that:
        // the reading has to land anyway (Codex, PR #258).
        controller.destroy()
        assertEquals("the reading is still in flight", 1, sensors.pendingSamples.size)
        sensors.deliver(PostureSample(2f, false))
        assertTrue(postureLines().any { it.contains("after state → RELEASED (MOVED): on edge, sensor clear") })
    }

    @Test
    fun `a transition the process could not see is said as one`() {
        startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now))

        sensors.deliver(null)

        assertTrue(postureLines().any { it.contains("posture after state → ARMED: no reading arrived") })
        assertNotNull("and the snooze runs as before", ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a snooze that holds no foreground service watches for no pick-up, and says so`() {
        // A one-shot sensor delivers nothing to a background app, so a watch
        // here would read a week of silence as a week of no pick-ups (Codex,
        // PR #258). Duration-only with nothing to watch takes no foreground
        // service on `play`.
        val record = snoozeFixture(now).copy(
            mode = TrackingMode.DURATION_ONLY,
            anchor = snoozeFixture(now).anchor.copy(lat = null, lon = null, fixAccuracyM = null, ssid = null),
        )

        startService(SnoozeService.ACTION_RESTORE, record)

        assertFalse(sensors.watchingPickUp)
        assertEquals(1, postureLines().count { it.contains("pick-ups cannot be observed") })
        assertEquals("the posture reading is still asked for", 1, sensors.pendingSamples.size)
    }

    @Test
    fun `losing the foreground service mid-snooze drops the watch and says so`() {
        // A tracking change repaints the card without a state transition,
        // and the repaint is where the service is taken or given back — so
        // the watch follows the flag from where the flag moves (Codex, PR
        // #258, second finding in this mechanism). No SSID, so losing the
        // fix degrades all the way to duration-only, which holds no service.
        val record = snoozeFixture(now).copy(anchor = snoozeFixture(now).anchor.copy(ssid = null))
        startService(SnoozeService.ACTION_RESTORE, record)
        assertTrue("the setup this rests on", sensors.watchingPickUp)

        assertTrue(
            TestSnoozeService.presence.updates.tryEmit(
                PresenceUpdate(event = null, degradation = DegradationCause.NO_LOCATION_FIX),
            ),
        )
        shadowOf(getMainLooper()).idle()

        assertEquals(TrackingMode.DURATION_ONLY, ActiveSnoozeStore(appContext).load()?.mode)
        assertEquals("the foreground was given back", 1, TestSnoozeService.foregroundExits)
        assertFalse("and the watch went with it", sensors.watchingPickUp)
        assertEquals(1, postureLines().count { it.contains("pick-ups cannot be observed") })
    }

    @Test
    fun `a snooze armed after an ending on the same instance starts a fresh trace`() {
        // The ending's reading is still in flight when the next arm lands on
        // the same instance; the new snooze must neither drop it nor inherit
        // its last-seen posture (Codex, PR #258).
        val controller = startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now).copy(endsOnMotion = true))
        sensors.deliver(PostureSample(-9.8f, true))
        TestSnoozeService.motionRegistrar.fire()
        assertEquals("the ending's reading is in flight", 1, sensors.pendingSamples.size)

        controller
            .withIntent(Intent(appContext, TestSnoozeService::class.java).setAction(SnoozeService.ACTION_ARM))
            .startCommand(0, 2)
        TestSnoozeService.captureRequests.last().invoke(
            Anchor(lat = null, lon = null, fixAccuracyM = null, capturedAt = now),
        )
        shadowOf(getMainLooper()).idle()

        assertEquals("the new snooze's reading joins it rather than replacing it", 2, sensors.pendingSamples.size)
        sensors.deliver(null)
        assertTrue(postureLines().any {
            it.contains("RELEASED (MOVED): no reading arrived; last seen face down, sensor covered (after state → ARMED)")
        })
        sensors.deliver(null)
        val fresh = postureLines().last()
        assertTrue(fresh, fresh.contains("after state → ARMED: no reading arrived"))
        assertFalse("nothing seen by the new snooze yet, so nothing claimed", fresh.contains("last seen"))
    }

    @Test
    fun `a phone with none of the sensors says so once and runs the snooze as before`() {
        sensors.available = false

        startService(SnoozeService.ACTION_RESTORE, snoozeFixture(now))

        assertNotNull(ActiveSnoozeStore(appContext).load())
        assertEquals(1, postureLines().count { it.contains("no sensor to read") })
        assertEquals(1, postureLines().count { it.contains("no pick-up gesture sensor") })
    }
}
