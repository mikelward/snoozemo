package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backstop's own departure test (SPEC.md §6.10), driven by recorded fix
 * traces and a fake clock — never real elapsed time.
 */
class BackstopProbeTest {

    private val start: Instant = Instant.parse("2026-08-11T09:00:00Z")

    // Stock stand-ins, never a device capture (AGENTS.md, *Privacy*).
    private val anchor = Anchor(lat = 0.0, lon = 0.0, fixAccuracyM = 20f, capturedAt = start)

    private val snooze = ActiveSnooze(
        anchor = anchor,
        startedAt = start,
        capExpiresAt = start.plus(ActiveSnooze.DEFAULT_CAP),
        mode = TrackingMode.FULL,
    )

    /** One degree of latitude is about 111 km, so this is meters north. */
    private fun north(meters: Double) = meters / 111_195.0

    private class Trace(private val fixes: List<Pair<Double, Float>?>, private val toLat: (Double) -> Double) {
        var nowMs = 1_000_000L
        var taken = 0
        val sleeps = mutableListOf<Long>()

        fun take(): Fix? {
            val next = fixes.getOrNull(taken++) ?: return null
            return Fix(lat = toLat(next.first), lon = 0.0, accuracyM = next.second, elapsedRealtimeMs = nowMs)
        }

        fun sleep(ms: Long) {
            sleeps += ms
            nowMs += ms
        }
    }

    private fun run(s: ActiveSnooze, vararg fixes: Pair<Double, Float>?): Pair<BackstopProbe.Outcome, Trace> {
        val trace = Trace(fixes.toList(), ::north)
        val outcome = BackstopProbe.run(s, trace::take, trace::sleep, { trace.nowMs })
        return outcome to trace
    }

    @Test
    fun `the field log's walk ends on two qualifying fixes a gap apart`() {
        // 300 m out on a 20 m fix, twice: past the hysteresis, short of the
        // single-fix shortcut, so it needs the confirmation the spec asks for.
        val (outcome, trace) = run(snooze, 300.0 to 20f, 310.0 to 20f)

        assertEquals(BackstopProbe.Outcome.Departed(DepartureRule.TWO_FIX), outcome)
        assertEquals(listOf(Departure.CONFIRMATION_GAP.toMillis()), trace.sleeps)
    }

    @Test
    fun `one fix far past the margin ends it alone`() {
        val (outcome, trace) = run(snooze, 2_000.0 to 20f)

        assertEquals(BackstopProbe.Outcome.Departed(DepartureRule.UNAMBIGUOUS), outcome)
        assertTrue("no second fix taken", trace.sleeps.isEmpty())
    }

    @Test
    fun `a fix at the anchor stops the probe`() {
        val (outcome, trace) = run(snooze, 10.0 to 15f, 300.0 to 20f)

        assertEquals(BackstopProbe.Outcome.StillHere, outcome)
        assertEquals("nothing taken after presence was confirmed", 1, trace.taken)
    }

    @Test
    fun `a GPS jump is not confirmed by a return to the anchor`() {
        // One qualifying outlier, then home: the window closes on the second.
        val (outcome, _) = run(snooze, 300.0 to 20f, 10.0 to 15f)

        assertEquals(BackstopProbe.Outcome.StillHere, outcome)
    }

    @Test
    fun `a 500 m cell fix cannot end it`() {
        val (outcome, _) = run(snooze, 400.0 to 500f, 400.0 to 500f, 400.0 to 500f)

        assertEquals(BackstopProbe.Outcome.Inconclusive, outcome)
    }

    @Test
    fun `a vague fix between two good ones restarts the window`() {
        val (outcome, _) = run(snooze, 300.0 to 20f, 300.0 to 500f, 300.0 to 20f)

        assertEquals(
            "two consecutive qualifying fixes are needed, and there were none",
            BackstopProbe.Outcome.Inconclusive,
            outcome,
        )
    }

    @Test
    fun `no fix at all says so`() {
        val (outcome, _) = run(snooze, null, null, null)

        assertEquals(BackstopProbe.Outcome.NoFix, outcome)
    }

    @Test
    fun `a stale cached fix is not evidence`() {
        // The platform answering with where the phone was before it left.
        val trace = Trace(listOf(300.0 to 20f), ::north)
        val outcome = BackstopProbe.run(
            snooze,
            { trace.take()?.copy(elapsedRealtimeMs = trace.nowMs - Duration.ofMinutes(10).toMillis()) },
            trace::sleep,
            { trace.nowMs },
        )

        assertEquals(BackstopProbe.Outcome.NoFix, outcome)
    }

    @Test
    fun `a chosen timer or an anchor with no fix asks nothing of location`() {
        var asked = 0
        val countFixes = { asked++; null }
        assertEquals(
            BackstopProbe.Outcome.NotTestable,
            BackstopProbe.run(snooze.copy(endsOnDeparture = false), countFixes, {}, { 0L }),
        )
        assertEquals(
            BackstopProbe.Outcome.NotTestable,
            BackstopProbe.run(snooze.copy(anchor = Anchor(capturedAt = start)), countFixes, {}, { 0L }),
        )
        assertEquals(0, asked)
    }

    /** A Wi-Fi view scripted per fix taken: the state before each fix. */
    private class ScriptedWifi(private val trace: Trace, private val byFix: List<Boolean>) :
        BackstopProbe.AnchorWifi {
        private var last = false
        private var count = 0L
        override fun associatedNow(): Boolean {
            val now = byFix.getOrElse(trace.taken) { byFix.last() }
            if (now != last) {
                count++
                last = now
            }
            return now
        }
        override fun transitions(): Long {
            associatedNow()
            return count
        }
    }

    private fun runWithWifi(wifiByFix: List<Boolean>, vararg fixes: Pair<Double, Float>?): BackstopProbe.Outcome {
        val trace = Trace(fixes.toList(), ::north)
        return BackstopProbe.run(snooze, trace::take, trace::sleep, { trace.nowMs }, ScriptedWifi(trace, wifiByFix))
    }

    @Test
    fun `rejoining the anchor's Wi-Fi mid-probe stops it`() {
        // Codex, PR #310: present, whatever the fixes go on to say (D4).
        assertEquals(
            BackstopProbe.Outcome.AtAnchorWifi,
            runWithWifi(listOf(false, true), 300.0 to 20f, 310.0 to 20f),
        )
    }

    @Test
    fun `a brief rejoin and a later clear departure still ends it`() {
        // Codex, PR #310, second pass: a sticky "was ever associated" held the
        // phone silent after the user had dropped the network and left.
        assertEquals(
            BackstopProbe.Outcome.Departed(DepartureRule.UNAMBIGUOUS),
            BackstopProbe.run(
                snooze,
                Trace(listOf(2_000.0 to 20f), ::north).let { it::take },
                {},
                { 1_000_000L },
                object : BackstopProbe.AnchorWifi {
                    override fun associatedNow() = false
                    override fun transitions() = 2L
                },
            ),
        )
    }

    @Test
    fun `a change in association restarts the confirmation window`() {
        // Qualifying, then Wi-Fi drops (a transition) before the second fix:
        // no confirmation may span it, so the second fix only reopens the
        // window, and the third confirms against it.
        val trace = Trace(listOf(300.0 to 20f, 305.0 to 20f, 310.0 to 20f), ::north)
        var transitions = 0L
        val wifi = object : BackstopProbe.AnchorWifi {
            override fun associatedNow() = false
            override fun transitions(): Long {
                if (trace.taken == 2) transitions = 1L
                return transitions
            }
        }
        val outcome = BackstopProbe.run(snooze, trace::take, trace::sleep, { trace.nowMs }, wifi)

        assertEquals(BackstopProbe.Outcome.Departed(DepartureRule.TWO_FIX), outcome)
        assertEquals("three fixes, not two", 3, trace.taken)
    }
}
