package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The departure test against fix traces (SPEC.md §6.6).
 *
 * Each rule in §6.6 exists to reject a specific real-world shape — a GPS jump, a
 * 500 m cell fix, a walk to the end of the garden — so each has a trace rather
 * than an assertion about arithmetic.
 *
 * **These traces are synthesized, not recorded** (Codex, PR #30), and the
 * distinction is worth stating rather than blurring. `AGENTS.md` asks for
 * recorded fix traces, and recording one needs a handset walking around a real
 * building — which no sandbox can produce, and which the app cannot yet capture
 * because the recorder is a later Phase 3 item. What is here reproduces the
 * *shapes* those rules exist to reject, with distances and accuracies chosen to
 * sit either side of each threshold.
 *
 * That is weaker in one specific way: a synthesized trace cannot surprise its
 * author. It will not contain the accuracy pattern a particular provider
 * actually emits when walking out of a building, which is exactly the thing a
 * recorded trace is for. `TODO.md` carries the dependency; these stay either
 * way, as the regression net a recorded trace does not replace.
 *
 * Coordinates are fictional throughout: the anchor sits at (0, 0) and every fix
 * is an offset from it, so nothing here describes anywhere real
 * (`AGENTS.md`, privacy).
 */
class DepartureTest {

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    // Fix times are elapsed realtime since boot, not wall clock, so a clock
    // change cannot distort the confirmation gap (Codex, PR #30). There is no
    // wall-clock field left in `Fix` to shift, which is the point: the hazard is
    // structurally absent rather than guarded against.
    /** ~111,320 m per degree of latitude at the equator, which is where (0, 0) is. */
    private fun metersNorth(m: Double): Double = m / 111_320.0

    private fun fix(
        northM: Double,
        accuracyM: Float,
        atSeconds: Long,
    ) = Fix(
        lat = metersNorth(northM),
        lon = 0.0,
        accuracyM = accuracyM,
        elapsedRealtimeMs = atSeconds * 1_000L,
    )

    private val anchor = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 20f,
        capturedAt = t0,
        ssid = "ExampleWifi",
    )

    /** Replays a trace and returns every verdict, threading progress as the app does. */
    private fun replay(trace: List<Fix>): List<DepartureVerdict> {
        var progress = DepartureProgress.NONE
        return trace.map { f ->
            val step = Departure.consider(f, anchor, progress)
            progress = step.progress
            step.verdict
        }
    }

    @Test
    fun `distance is measured from the anchor, not assumed`() {
        // Sanity on the geometry the rest of the file leans on: 1 km north of
        // (0, 0) should measure as about a kilometer.
        val d = Departure.distanceM(fix(northM = 1_000.0, accuracyM = 10f, atSeconds = 0), anchor)!!
        assertTrue("expected ~1000 m, got $d", d in 995.0..1_005.0)
    }

    @Test
    fun `a vague cell fix outside the radius is not a departure`() {
        // The failure this whole test exists for. 400 m from a 150 m anchor
        // looks like leaving until you notice the fix is ±500 m — it says
        // "somewhere in this town", and the phone may well be on the desk.
        val vague = fix(northM = 400.0, accuracyM = 500f, atSeconds = 0)

        assertFalse(Departure.qualifies(vague, anchor))
        assertFalse(
            "it is no more evidence of being home than of being away",
            Departure.confirmsPresence(vague, anchor),
        )
        assertEquals(
            "a reading that locates nobody must not be reported as presence (Codex, PR #30)",
            DepartureVerdict.INCONCLUSIVE,
            Departure.consider(vague, anchor).verdict,
        )
    }

    @Test
    fun `an inconclusive fix closes the confirmation window`() {
        // §6.6 asks for two *consecutive* qualifying fixes, and a reading that
        // cannot place the user is not one. Letting it join two isolated
        // outliers would rebuild the false positive confirmation exists to
        // prevent (Codex, PR #30).
        //
        // The third fix therefore opens a fresh window rather than confirming.
        val trace = listOf(
            fix(northM = 400.0, accuracyM = 15f, atSeconds = 0),
            fix(northM = 400.0, accuracyM = 500f, atSeconds = 15), // tells us nothing
            fix(northM = 420.0, accuracyM = 15f, atSeconds = 40),
        )

        assertEquals(
            listOf(
                DepartureVerdict.AWAITING_CONFIRMATION,
                DepartureVerdict.INCONCLUSIVE,
                DepartureVerdict.AWAITING_CONFIRMATION,
            ),
            replay(trace),
        )
    }

    @Test
    fun `a real departure through vague readings still confirms, just later`() {
        // The cost of the rule above, made visible: leaving a building is
        // exactly when fixes go vague. Two clean readings 30 s apart still
        // confirm once they land consecutively, so this delays confirmation
        // rather than preventing it.
        val trace = listOf(
            fix(northM = 400.0, accuracyM = 15f, atSeconds = 0),
            fix(northM = 400.0, accuracyM = 500f, atSeconds = 20),
            fix(northM = 450.0, accuracyM = 15f, atSeconds = 60),
            fix(northM = 480.0, accuracyM = 15f, atSeconds = 95),
        )

        assertEquals(DepartureVerdict.DEPARTED, replay(trace).last())
    }

    @Test
    fun `a single GPS jump inside the band never ends a snooze`() {
        // One wild reading among good ones, landing in the confirmation band.
        // The fix ten seconds later disagrees with it, which is precisely why
        // one fix is not enough.
        val trace = listOf(
            fix(northM = 5.0, accuracyM = 8f, atSeconds = 0),
            fix(northM = 400.0, accuracyM = 15f, atSeconds = 10), // the jump
            fix(northM = 6.0, accuracyM = 8f, atSeconds = 20),
            fix(northM = 4.0, accuracyM = 8f, atSeconds = 40),
        )

        val verdicts = replay(trace)

        assertFalse(
            "a jump followed by fixes back at the anchor must not end the snooze",
            verdicts.contains(DepartureVerdict.DEPARTED),
        )
        assertEquals(DepartureVerdict.STILL_HERE, verdicts.last())
    }

    @Test
    fun `a jump past the unambiguous margin does end the snooze, and that is the trade`() {
        // Worth pinning down rather than leaving implied, because it is the one
        // case where a *single* bad fix ends a snooze. A reading 900 m out with
        // 15 m of claimed accuracy clears radius + 500 m, so §6.6's shortcut
        // fires and no confirmation is sought.
        //
        // That is the deliberate direction, not an oversight: the alternative is
        // making someone who really is a kilometer away wait out a debounce
        // designed for boundary noise, and between a snooze that ends early and
        // one that keeps a phone silent, principle 1 picks the first every time.
        // The cost is real though — a provider that reports a confident wrong
        // fix gets believed — which is why it is the *margin* that must clear
        // 500 m rather than the raw distance.
        val jump = fix(northM = 900.0, accuracyM = 15f, atSeconds = 10)

        assertTrue(Departure.isUnambiguous(jump, anchor))
        assertEquals(DepartureVerdict.DEPARTED, Departure.consider(jump, anchor).verdict)
    }

    @Test
    fun `a walk to the end of the garden is not leaving`() {
        // 120 m out from a 150 m anchor, good accuracy, and staying there. Inside
        // radius + hysteresis throughout, so nothing should ever qualify.
        val trace = (0..5).map { i ->
            fix(northM = 100.0 + i * 4, accuracyM = 10f, atSeconds = i * 60L)
        }

        assertEquals(List(6) { DepartureVerdict.STILL_HERE }, replay(trace))
    }

    @Test
    fun `two qualifying fixes thirty seconds apart end the snooze`() {
        // The ordinary departure: walking away, two independent readings.
        val trace = listOf(
            fix(northM = 400.0, accuracyM = 15f, atSeconds = 0),
            fix(northM = 450.0, accuracyM = 15f, atSeconds = 30),
        )

        assertEquals(
            listOf(DepartureVerdict.AWAITING_CONFIRMATION, DepartureVerdict.DEPARTED),
            replay(trace),
        )
    }

    @Test
    fun `two qualifying fixes too close together do not confirm each other`() {
        // Same two readings, four seconds apart. A GPS jump lasts longer than
        // that, so these are one observation rather than two.
        val trace = listOf(
            fix(northM = 400.0, accuracyM = 15f, atSeconds = 0),
            fix(northM = 450.0, accuracyM = 15f, atSeconds = 4),
        )

        assertEquals(
            List(2) { DepartureVerdict.AWAITING_CONFIRMATION },
            replay(trace),
        )
    }

    @Test
    fun `a burst of qualifying fixes still confirms once the window is old enough`() {
        // The window keeps its original start, so a fix every few seconds cannot
        // push the deadline ahead of itself. Getting this wrong leaves a phone
        // silent for as long as the fixes keep arriving.
        val trace = (0..8).map { i ->
            fix(northM = 400.0, accuracyM = 15f, atSeconds = i * 5L)
        }

        val verdicts = replay(trace)

        assertEquals(
            "confirmation must land at the first fix 30 s past the window's start",
            DepartureVerdict.DEPARTED,
            verdicts[6],
        )
    }

    @Test
    fun `a fix back at the anchor closes the window`() {
        // "Two *consecutive* qualifying fixes". A reading that puts the phone
        // back home in between is the evidence that the first one was noise, so
        // the clock restarts rather than carrying on.
        val trace = listOf(
            fix(northM = 400.0, accuracyM = 15f, atSeconds = 0),
            fix(northM = 2.0, accuracyM = 8f, atSeconds = 20),
            fix(northM = 400.0, accuracyM = 15f, atSeconds = 40),
        )

        assertEquals(
            listOf(
                DepartureVerdict.AWAITING_CONFIRMATION,
                DepartureVerdict.STILL_HERE,
                DepartureVerdict.AWAITING_CONFIRMATION,
            ),
            replay(trace),
        )
    }

    @Test
    fun `one unambiguous fix ends the snooze immediately`() {
        // A kilometer away with a good fix. Waiting out a debounce designed for
        // boundary noise would keep a phone silent for someone plainly gone.
        val far = fix(northM = 1_200.0, accuracyM = 20f, atSeconds = 0)

        assertTrue(Departure.isUnambiguous(far, anchor))
        assertEquals(DepartureVerdict.DEPARTED, Departure.consider(far, anchor).verdict)
    }

    @Test
    fun `distance alone is not unambiguous if the fix is vague enough`() {
        // A kilometer away, but ±900 m. The margin is what decides, not the
        // headline distance — otherwise the shortcut past confirmation becomes
        // the way a bad fix ends a snooze.
        val farButVague = fix(northM = 1_200.0, accuracyM = 900f, atSeconds = 0)

        assertFalse(Departure.isUnambiguous(farButVague, anchor))
    }

    @Test
    fun `an anchor with no coordinates never reports a departure`() {
        // Armed indoors with no fix: Wi-Fi-only mode (SPEC.md §8.4). There is
        // nothing to measure against, and inventing an answer here would end
        // snoozes for people who never moved.
        val noFixAnchor = Anchor(capturedAt = t0, ssid = "ExampleWifi")
        val anywhere = fix(northM = 10_000.0, accuracyM = 5f, atSeconds = 0)

        assertEquals(null, Departure.distanceM(anywhere, noFixAnchor))
        assertEquals(null, Departure.marginM(anywhere, noFixAnchor))
        assertFalse(Departure.qualifies(anywhere, noFixAnchor))
        assertFalse(Departure.isUnambiguous(anywhere, noFixAnchor))
        assertFalse(Departure.confirmsPresence(anywhere, noFixAnchor))
        assertEquals(
            "with nothing to measure against, location is inconclusive rather than reassuring",
            DepartureVerdict.INCONCLUSIVE,
            Departure.consider(anywhere, noFixAnchor).verdict,
        )
    }

    @Test
    fun `an anchor whose own fix was too vague never reports a departure`() {
        // Capture keeps coordinates even when their accuracy failed the gate,
        // and `Anchor.hasUsableFix` already says such a snooze runs Wi-Fi-only.
        // Measuring against them anyway would end a snooze from a reference
        // point that was wrong when it was captured — nobody moved (Codex,
        // PR #30).
        val vagueAnchor = Anchor(
            lat = 0.0,
            lon = 0.0,
            fixAccuracyM = 500f, // past MAX_ANCHOR_ACCURACY_M
            capturedAt = t0,
            ssid = "ExampleWifi",
        )
        val farAway = fix(northM = 5_000.0, accuracyM = 5f, atSeconds = 0)

        assertFalse(vagueAnchor.hasUsableFix)
        assertEquals(null, Departure.distanceM(farAway, vagueAnchor))
        assertFalse(Departure.qualifies(farAway, vagueAnchor))
        assertFalse(Departure.isUnambiguous(farAway, vagueAnchor))
        assertEquals(
            DepartureVerdict.INCONCLUSIVE,
            Departure.consider(farAway, vagueAnchor).verdict,
        )
    }

    @Test
    fun `the hysteresis band is the difference between flapping and not`() {
        // Sitting right on the radius. Without the band this alternates between
        // qualifying and not as accuracy wobbles by a meter.
        val onTheEdge = fix(northM = 160.0, accuracyM = 5f, atSeconds = 0)
        val pastTheBand = fix(northM = 220.0, accuracyM = 5f, atSeconds = 0)

        assertFalse("155 m of margin is inside the band", Departure.qualifies(onTheEdge, anchor))
        assertTrue(Departure.qualifies(pastTheBand, anchor))
    }

    @Test
    fun `a confirmation gap measured across a long pause still confirms`() {
        // The duty cycle (SPEC.md §6.7) can leave minutes between fixes. A gap
        // far longer than 30 s is more independent evidence, not less.
        val trace = listOf(
            fix(northM = 400.0, accuracyM = 15f, atSeconds = 0),
            fix(northM = 420.0, accuracyM = 15f, atSeconds = Duration.ofMinutes(20).seconds),
        )

        assertEquals(
            listOf(DepartureVerdict.AWAITING_CONFIRMATION, DepartureVerdict.DEPARTED),
            replay(trace),
        )
    }
}
