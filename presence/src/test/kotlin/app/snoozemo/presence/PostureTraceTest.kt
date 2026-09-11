package app.snoozemo.presence

import app.snoozemo.core.SnoozeDebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The posture trace (SPEC.md §4.6) over manual sensors: what one reading
 * says, and that the pick-up watch re-arms after firing without acting.
 *
 * Both directions throughout: the trace exists to log, so a line that never
 * lands is the silent failure, and a line that acts on anything is the other.
 */
class PostureTraceTest {

    private class FakeSensors : PostureSensors {
        val pendingSamples = mutableListOf<(PostureSample?) -> Unit>()
        var samplesAsked = 0
        var available = true
        var pickUp: (() -> Unit)? = null
        var pickUpArms = 0

        override fun sample(onSample: (PostureSample?) -> Unit): AutoCloseable? {
            samplesAsked++
            if (!available) return null
            pendingSamples += onSample
            return AutoCloseable { pendingSamples.remove(onSample) }
        }

        override fun watchPickUp(onFired: () -> Unit): AutoCloseable? {
            if (!available) return null
            pickUpArms++
            pickUp = onFired
            return AutoCloseable { if (pickUp === onFired) pickUp = null }
        }

        fun deliver(sample: PostureSample?) = pendingSamples.removeAt(0)(sample)

        /**
         * The oldest reading settles on the sensor thread — its handle can
         * no longer be closed — but its landing is still queued on the main
         * one. Returns that landing for the test to run later.
         */
        fun settleOldest(): (PostureSample?) -> Unit = pendingSamples.removeAt(0)

        fun firePickUp() {
            val callback = pickUp ?: error("nothing armed")
            pickUp = null
            callback()
        }
    }

    private val sensors = FakeSensors()
    private val trace = PostureTrace(sensors)

    /**
     * [SnoozeDebugLog] is a process-wide buffer every test in this JVM shares,
     * so this test's lines are the ones after a marker it logs first — the
     * same fence `PresenceTest` uses, since the presence module has no reset
     * seam for the log.
     */
    private var marker = ""

    /** Logs a fresh fence; the lines this test reads are the ones after it. */
    private fun fence() {
        marker = "PostureTraceTest ${System.nanoTime()}"
        SnoozeDebugLog.event(marker)
    }

    @Before
    fun setUp() {
        SnoozeDebugLog.setRecording(true)
        fence()
    }

    @After
    fun tearDown() {
        // A process-wide setting; a test that turned it off must not leave
        // the next one reading an empty log.
        SnoozeDebugLog.setRecording(true)
    }

    private fun lines(): List<String> {
        val all = SnoozeDebugLog.snapshot()
        val at = all.indexOfLast { it.contains(marker) }
        assertTrue("the marker survived the buffer", at >= 0)
        return all.drop(at + 1).filter { it.contains("posture") }
    }

    @Test
    fun `a reading names the posture and the proximity state, never the numbers`() {
        trace.sample("after state → ARMED")
        sensors.deliver(PostureSample(gravityZ = -9.7f, proximityNear = true))

        val line = lines().single()
        assertTrue(line, line.contains("posture after state → ARMED: face down, sensor covered"))
        assertFalse("the raw reading stays out", line.contains("9.7"))
    }

    @Test
    fun `the vocabulary covers flat, propped and absent sensors`() {
        assertEquals("face up, sensor clear", PostureSample(9.8f, false).describe())
        assertEquals("face down, sensor covered", PostureSample(-9.8f, true).describe())
        assertEquals("on edge, sensor clear", PostureSample(3f, false).describe())
        // A table with a slight tilt still reads as flat.
        assertEquals("face down, no proximity reading", PostureSample(-7.5f, null).describe())
        assertEquals("no gravity reading, sensor covered", PostureSample(null, true).describe())
    }

    @Test
    fun `readings in flight are independent, and each lands against its own moment`() {
        // Two transitions inside the window — ARMED to CHECKING — must both
        // get their line (Codex, PR #258); dropping the first for the second
        // broke the promise of a reading at every transition.
        trace.sample("after state → ARMED")
        trace.sample("after state → CHECKING")
        assertEquals("both registrations stand", 2, sensors.pendingSamples.size)

        sensors.deliver(PostureSample(-9.8f, true))
        sensors.deliver(PostureSample(9.8f, false))

        val moments = lines().map { it.substringAfter("posture ").substringBefore(":") }
        assertEquals(listOf("after state → ARMED", "after state → CHECKING"), moments)
        assertTrue(lines()[0].contains("face down, sensor covered"))
        assertTrue(lines()[1].contains("face up, sensor clear"))
    }

    @Test
    fun `the pick-up is not watched while nothing holds the process, and says so once`() {
        trace.reconcilePickUp(needed = true, processHeld = false)
        trace.reconcilePickUp(needed = true, processHeld = false)

        assertFalse(trace.watchingPickUp)
        assertEquals(0, sensors.pickUpArms)
        assertEquals(1, lines().count { it.contains("pick-ups cannot be observed") })

        trace.reconcilePickUp(needed = true, processHeld = true)
        assertTrue("armed once the process is held", trace.watchingPickUp)
    }

    @Test
    fun `a phone with no sensors is said once`() {
        sensors.available = false

        trace.sample("after state → ARMED")
        trace.sample("after state → RELEASED")

        assertEquals(2, sensors.samplesAsked)
        assertEquals(1, lines().count { it.contains("no sensor to read") })
    }

    @Test
    fun `no reading is asked for while the log is off, so none is remembered`() {
        // The log would refuse the line, so the registration would be paid
        // for nothing (Codex, PR #258) — and a posture observed while
        // recording was off must not surface later as "last seen" either.
        SnoozeDebugLog.setRecording(false)
        trace.sample("after state → ARMED")
        trace.reconcilePickUp(needed = true)
        assertEquals("nothing registered for a line the log would refuse", 0, sensors.samplesAsked)
        assertTrue("but the one-shot watch is kept, so a log turned on later sees the next pick-up", trace.watchingPickUp)
        sensors.firePickUp()
        assertEquals("a pick-up while off takes no reading either", 0, sensors.samplesAsked)
        assertTrue("and is re-armed", trace.watchingPickUp)

        SnoozeDebugLog.setRecording(true)
        fence()
        trace.sample("after state → RELEASED (CAP)")
        assertEquals("on again: the reading is asked for", 1, sensors.samplesAsked)
        sensors.deliver(null)

        val line = lines().last()
        assertTrue(line, line.contains("after state → RELEASED (CAP): no reading arrived"))
        assertFalse(line, line.contains("last seen"))
    }

    @Test
    fun `a posture the log has since erased is not remembered as last seen`() {
        // Turning the log off deletes it; a reading it took before that is
        // then the deleted data, and resurfaces in the new log if the trace
        // keeps it (Codex, PR #258).
        trace.sample("after state → ARMED")
        sensors.deliver(PostureSample(-9.8f, true))
        assertTrue("the setup this rests on: the reading was taken", lines().any { it.contains("face down") })

        SnoozeDebugLog.applyRecording(false)
        SnoozeDebugLog.applyRecording(true)
        fence()
        trace.sample("after state → RELEASED (CAP)")
        sensors.deliver(null)

        val line = lines().last()
        assertTrue(line, line.contains("after state → RELEASED (CAP): no reading arrived"))
        assertFalse(line, line.contains("last seen"))
    }

    @Test
    fun `explanations erased with the log are said again once it is on`() {
        // Off empties the log, so a "no sensor" line said before the off is
        // gone with it; a fresh log without it reads an absent sensor as a
        // silent week (Codex, PR #258). Same for the pick-up sensor and the
        // unobservable line.
        sensors.available = false
        trace.sample("after state → ARMED")
        trace.reconcilePickUp(needed = true)
        assertEquals("the setup this rests on", 1, lines().count { it.contains("no sensor to read") })
        assertEquals("the setup this rests on", 1, lines().count { it.contains("no pick-up gesture sensor") })

        SnoozeDebugLog.applyRecording(false)
        SnoozeDebugLog.applyRecording(true)
        fence()
        trace.sample("after state → CHECKING")
        trace.reconcilePickUp(needed = true)
        trace.reconcilePickUp(needed = true, processHeld = false)

        assertEquals(1, lines().count { it.contains("no sensor to read") })
        assertEquals(1, lines().count { it.contains("no pick-up gesture sensor") })
        assertEquals(1, lines().count { it.contains("pick-ups cannot be observed") })
        // Said again once, not on every touch.
        trace.sample("after state → ARMED")
        trace.reconcilePickUp(needed = true, processHeld = false)
        assertEquals(1, lines().count { it.contains("no sensor to read") })
        assertEquals(1, lines().count { it.contains("pick-ups cannot be observed") })
    }

    @Test
    fun `last seen is the newest reading asked for, not the newest to land`() {
        // A transition's reading and a pick-up's beside it can land in either
        // order; the older one landing late must not move last-seen backward
        // (Codex, PR #258).
        trace.sample("after state → ARMED")
        trace.reconcilePickUp(needed = true)
        val armedLandsLate = sensors.settleOldest()
        sensors.firePickUp()
        sensors.deliver(PostureSample(4f, false))
        armedLandsLate(PostureSample(-9.8f, true))
        assertTrue(lines().any { it.contains("after state → ARMED: face down, sensor covered") })
        assertTrue(lines().any { it.contains("on pick-up: on edge, sensor clear") })

        trace.sample("after state → RELEASED (CAP)")
        sensors.deliver(null)

        val line = lines().last()
        assertTrue(line, line.contains("no reading arrived; last seen on edge, sensor clear (on pick-up)"))
    }

    @Test
    fun `an absence line withheld while the log was off is said once it is on`() {
        // The log discards a line while recording is off; a latch set on the
        // attempt would leave a log turned on later with no way to tell an
        // absent sensor from a silent one (Codex, PR #258).
        sensors.available = false
        SnoozeDebugLog.setRecording(false)
        trace.sample("after state → ARMED")
        trace.reconcilePickUp(needed = true)

        SnoozeDebugLog.setRecording(true)
        fence()
        trace.sample("after state → CHECKING")
        trace.reconcilePickUp(needed = true)

        assertEquals(1, lines().count { it.contains("no sensor to read") })
        assertEquals(1, lines().count { it.contains("no pick-up gesture sensor") })
        // Said, and then not again — the answer is still permanent.
        trace.sample("after state → RELEASED (CAP)")
        trace.reconcilePickUp(needed = true)
        assertEquals(1, lines().count { it.contains("no sensor to read") })
        assertEquals(1, lines().count { it.contains("no pick-up gesture sensor") })
    }

    @Test
    fun `the unobservable line withheld while the log was off is said once it is on`() {
        SnoozeDebugLog.setRecording(false)
        trace.reconcilePickUp(needed = true, processHeld = false)

        SnoozeDebugLog.setRecording(true)
        fence()
        trace.reconcilePickUp(needed = true, processHeld = false)
        trace.reconcilePickUp(needed = true, processHeld = false)

        assertEquals(1, lines().count { it.contains("pick-ups cannot be observed") })
    }

    @Test
    fun `a pick-up is logged, read, and re-armed, and nothing else happens`() {
        trace.reconcilePickUp(needed = true)
        assertTrue(trace.watchingPickUp)

        sensors.firePickUp()

        assertTrue(lines().any { it.contains("pick-up gesture fired; nothing acted on it") })
        assertEquals("the reading that goes with it was asked for", 1, sensors.pendingSamples.size)
        assertTrue("and the watch re-armed", trace.watchingPickUp)
        assertEquals(2, sensors.pickUpArms)
    }

    @Test
    fun `a pick-up's reading stands beside a transition's, not in place of it`() {
        // A pick-up inside the two seconds after ARMED must not cost the arm
        // posture it is going to be compared with (Codex, PR #258).
        trace.sample("after state → ARMED")
        trace.reconcilePickUp(needed = true)

        sensors.firePickUp()

        assertEquals("both readings are in flight", 2, sensors.pendingSamples.size)
        sensors.deliver(PostureSample(-9.8f, true))
        sensors.deliver(PostureSample(4f, false))
        assertTrue(lines().any { it.contains("after state → ARMED: face down, sensor covered") })
        assertTrue(lines().any { it.contains("on pick-up: on edge, sensor clear") })
        // And nothing later reaches for either: every reading stands until
        // it lands or times out.
        trace.sample("after state → CHECKING")
        sensors.firePickUp()
        trace.sample("after state → RELEASED (CAP)")
        assertEquals("CHECKING's, the pick-up's and RELEASED's all stand", 3, sensors.pendingSamples.size)
    }

    @Test
    fun `restating the watch arms no second sensor, and unwanting it disarms`() {
        trace.reconcilePickUp(needed = true)
        trace.reconcilePickUp(needed = true)
        assertEquals(1, sensors.pickUpArms)

        trace.reconcilePickUp(needed = false)

        assertFalse(trace.watchingPickUp)
        assertEquals("a spent registration cannot fire", null, sensors.pickUp)
    }

    @Test
    fun `a phone without the gesture is said once and not asked again`() {
        sensors.available = false

        trace.reconcilePickUp(needed = true)
        trace.reconcilePickUp(needed = false)
        trace.reconcilePickUp(needed = true)

        assertEquals(1, lines().count { it.contains("no pick-up gesture sensor") })
        assertFalse(trace.watchingPickUp)
    }

    @Test
    fun `a reading that never arrives is said, against its moment`() {
        // What a background process sees: the platform half gives up after
        // its deadline and answers null. The line still lands, so the trial
        // can tell a transition it could not see from a sensor that stayed
        // silent for no reason.
        trace.sample("after state → RELEASED")

        sensors.deliver(null)

        val line = lines().single()
        assertTrue(line, line.contains("posture after state → RELEASED: no reading arrived"))
        assertFalse("nothing seen before it, so nothing claimed", line.contains("last seen"))
    }

    @Test
    fun `a reading that never arrives carries the last posture that did`() {
        // An ending away from the screen mostly cannot be read — the ended
        // card's post gives the foreground service back in the same pass —
        // so its line carries the last posture the process could see, which
        // for a phone lying still is the posture it ended in (Codex, PR #258).
        trace.sample("after state → ARMED")
        sensors.deliver(PostureSample(-9.8f, true))
        trace.sample("after state → RELEASED (CAP)")

        sensors.deliver(null)

        val line = lines().last()
        assertTrue(
            line,
            line.contains("after state → RELEASED (CAP): no reading arrived; last seen face down, sensor covered (after state → ARMED)"),
        )
    }

    @Test
    fun `closing drops the watch but lets a reading in flight land`() {
        // The service closes the trace on its way out of an ending, before
        // the sensor has answered; the ending posture is the line the trial
        // needs most (Codex, PR #258).
        trace.sample("after state → RELEASED (CAP)")
        trace.reconcilePickUp(needed = true)

        trace.close()

        assertFalse(trace.watchingPickUp)
        assertEquals("the reading is still in flight", 1, sensors.pendingSamples.size)
        sensors.deliver(PostureSample(-9.8f, true))
        assertTrue(lines().any { it.contains("after state → RELEASED (CAP): face down, sensor covered") })
        trace.sample("after state → ARMED")
        assertEquals("but nothing new is asked for", 1, sensors.samplesAsked)
    }
}
