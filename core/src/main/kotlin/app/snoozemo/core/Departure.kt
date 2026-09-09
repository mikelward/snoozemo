package app.snoozemo.core

import java.time.Duration
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One location reading, as the departure test needs it (SPEC.md §6.6).
 *
 * Deliberately not Android's `Location`: this is the only input to the decision
 * that decides whether a snooze ends, and that decision belongs in a plain
 * Kotlin type a JVM test can build by hand (`AGENTS.md`, testing expectations).
 * The Android layer maps into this and owns nothing else about it.
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    /**
     * Radius of 68% confidence, in meters, as every Android provider reports it.
     *
     * The single most important field here, and the reason this test exists at
     * all: a fix 400 m from the anchor with 500 m of accuracy says "somewhere in
     * the neighborhood", not "gone".
     */
    val accuracyM: Float,
    /**
     * When this fix was taken, in milliseconds of elapsed realtime since boot —
     * Android's `Location.getElapsedRealtimeMillis()` (Codex, PR #30).
     *
     * **Monotonic, deliberately, and not a wall-clock `Instant`.** The only
     * thing this field is used for is the gap between two qualifying fixes, and
     * a wall clock can move under that arithmetic: a forward jump would let two
     * near-simultaneous outliers satisfy the 30-second rule, and a backward one
     * would stop a real departure confirming. Elapsed realtime counts from boot
     * across sleep and nothing can move it — the same reasoning that puts the
     * duration cap on `ELAPSED_REALTIME_WAKEUP` (SPEC.md §7).
     *
     * Carrying it per-fix rather than reading a clock when the fix is handled
     * also means a queued or replayed fix keeps its own timing.
     */
    val elapsedRealtimeMs: Long,
)

/**
 * What a fix says about whether the user has left (SPEC.md §6.6).
 *
 * Three outcomes rather than a boolean, because "not yet" and "no" have to be
 * told apart: the first keeps a confirmation window open, the second closes it.
 */
enum class DepartureVerdict {
    /**
     * The fix places the user at the anchor: its whole uncertainty circle sits
     * inside the radius, so this is positive evidence of presence.
     */
    STILL_HERE,

    /**
     * The fix establishes nothing either way (Codex, PR #30).
     *
     * A reading 400 m out with ±500 m of accuracy is not evidence of being
     * home; it is evidence of nothing. Folding it into [STILL_HERE] would have
     * a monitor de-escalate to `ARMED` and back off its duty cycle on the
     * strength of a reading that never located anyone — claiming presence the
     * app cannot see, which is principle 2's failure rather than principle 1's.
     *
     * The caller should keep looking, and say tracking is degraded if this
     * persists, rather than treating it as an answer.
     */
    INCONCLUSIVE,

    /**
     * The fix qualifies as evidence of leaving, but not yet enough of it. One
     * more qualifying fix, at least [Departure.CONFIRMATION_GAP] later, ends the
     * snooze.
     */
    AWAITING_CONFIRMATION,

    /** Confirmed. The snooze ends. */
    DEPARTED,
}

/**
 * The confirmation window, carried between fixes.
 *
 * A value rather than mutable state inside a monitor, so the whole test is a
 * pure function of (previous progress, new fix, anchor) — which is what lets a
 * recorded trace be replayed through it in a JVM test with no Android and no
 * clock.
 */
data class DepartureProgress(
    /**
     * Elapsed realtime at which the currently-open run of qualifying fixes
     * began, or null if none is open.
     */
    val firstQualifyingAtMs: Long? = null,
) {
    companion object {
        val NONE = DepartureProgress()
    }
}

/**
 * The departure test's arithmetic for one fix, without the position it came
 * from (`SPEC.md` §4.6).
 *
 * The same numbers the debug log already records — distance from the anchor,
 * both accuracies (this fix's and the anchor's, which is why [uncertaintyM]
 * can be derived here), and the radius they are compared against — carried
 * live rather than read back afterward. What makes them safe to show is what
 * makes them safe to log: a distance and a precision locate nobody, where a
 * coordinate locates exactly one place.
 *
 * Built by [Departure.observe] so the readout and the verdict cannot drift
 * apart: both are computed from the same fix by the same functions, so a screen
 * that says a phone is 40 m short of leaving is quoting the test rather than
 * re-deriving it.
 */
data class DepartureObservation(
    /** Great-circle meters from the anchor, before accuracy is accounted for. */
    val distanceM: Double,
    /** The fix's own 68%-confidence radius, in meters. */
    val accuracyM: Float,
    /**
     * The **anchor's** 68%-confidence radius, in meters, as reported by the
     * fix that captured it.
     *
     * Carried because the origin every distance is measured from is itself a
     * reported point, not a known one — see [Departure.uncertaintyM].
     */
    val anchorAccuracyM: Float,
    /** The anchor's radius, in meters — what [distanceM] is measured against. */
    val radiusM: Int,
    /** Elapsed realtime of the fix, so a stale reading can be told from a fresh one. */
    val elapsedRealtimeMs: Long,
) {
    /**
     * How far past the anchor's edge this fix can be *trusted* to be — the
     * quantity the test actually thresholds ([Departure.marginM]).
     *
     * Negative means the fix does not establish being outside at all.
     */
    val marginM: Double get() = distanceM - uncertaintyM - radiusM

    /**
     * How far the *separation* itself could be wrong, in meters — the two
     * confidence radii combined ([Departure.uncertaintyM]).
     *
     * The one number a readout should show, and the reason it is derived here
     * rather than formatted from [accuracyM]: it is the quantity the test
     * thresholds, so a screen quoting it cannot drift from the verdict.
     */
    val uncertaintyM: Double get() = Departure.uncertaintyM(accuracyM, anchorAccuracyM)

    /**
     * Meters still to go before a fix this accurate could qualify, or zero once
     * one already does.
     *
     * The honest form of "how far left", and the reason it is derived rather
     * than a plain `radius - distance`: the test subtracts [uncertaintyM]
     * before comparing, so a vague reading genuinely needs more distance than a
     * sharp one — and so does a sharp reading against a vague anchor, since
     * both endpoints count. Reporting the nominal edge would promise a
     * departure that this fix could not deliver.
     */
    val remainingM: Double get() = (Departure.HYSTERESIS_M - marginM).coerceAtLeast(0.0)

    /** Whether this fix on its own is evidence of being outside. */
    val qualifies: Boolean get() = marginM > Departure.HYSTERESIS_M

    /**
     * Whether these numbers can be put in front of anyone at all.
     *
     * A non-finite coordinate or accuracy — a mock provider, a driver
     * returning garbage — poisons the whole record rather than one field of
     * it: the haversine yields NaN, so [marginM], [remainingM] and the
     * combined [uncertaintyM] are all NaN, and [qualifies] reads false only
     * because every NaN comparison does. A readout then hands `roundToInt` a
     * value it throws on, on the composition thread (Codex, PR #244).
     *
     * The observation is deliberately **kept** rather than refused where it is
     * built: PR #233 settled that such a fix must still fall out inconclusive,
     * still count toward degradation, and still write its `distance=unknown`
     * line, because a throw on the fix path leaves the snooze armed with
     * nothing running to end it. Deleting the observation deletes all three.
     * So what is wanted is not a rejection but a question a formatter can ask
     * — here, once, rather than at each surface that renders a number.
     *
     * Asked of the two stored fields a readout renders; everything else it
     * shows is derived from them, and `radiusM` is an `Int`.
     *
     * **And of whether the rounding ladder can describe it at all.** A step is
     * chosen as the finest rung not finer than the uncertainty, and that
     * ladder has to stop somewhere because it returns an `Int` — so above
     * [DistanceUnit.CEILING_M] the only answers available are to saturate,
     * which understates, or to decline. Declining here is what makes the
     * understatement unreachable rather than merely unlikely: the same three
     * findings in review were each a different boundary of that ceiling, and
     * a reading a quarter of the Earth's circumference wide is not a reading
     * about a place.
     */
    val isReportable: Boolean
        get() = distanceM.isFinite() &&
            uncertaintyM.isFinite() &&
            uncertaintyM <= DistanceUnit.CEILING_M

    /**
     * Whether this reading is recent enough to put in front of someone.
     *
     * The duty cycle asks every 90 seconds while a departure is being tested
     * and every ten minutes or so while nothing suggests movement (SPEC.md
     * §6.7), so a resting snooze legitimately has nothing fresh — and a
     * ten-minute-old distance shown as if it were current is exactly the
     * quietly-wrong reading principle 2 is about. Saying nothing is the honest
     * answer; the number reappears as soon as movement escalates the duty
     * cycle, which is the moment it means anything.
     */
    fun isFresh(nowElapsedRealtimeMs: Long): Boolean =
        nowElapsedRealtimeMs - elapsedRealtimeMs in 0..FRESH_FOR_MS

    companion object {
        /**
         * How long a reading stays showable — comfortably more than the
         * 90-second [LocationDuty.ACTIVE] request that produces the readings
         * worth watching, and well short of the ten-minute idle poll.
         */
        const val FRESH_FOR_MS: Long = 5 * 60 * 1000
    }
}

/**
 * Which of §6.6's two routes to [DepartureVerdict.DEPARTED] this fix took.
 *
 * The verdict collapses them, and for the debug log that loses the thing §4.6
 * asks to record — "which confirmation rule matched" (Codex, PR #233). A
 * reader can *infer* it from the margin, since only [Departure.isUnambiguous]
 * clears [Departure.UNAMBIGUOUS_MARGIN_M], but that asks them to know a
 * constant that has moved before; naming it is one word and cannot drift from
 * the branch that produced it.
 *
 * Null for every other verdict: those name their own rule already.
 */
enum class DepartureRule {
    /**
     * One fix, far enough out that confirmation would only keep a phone silent
     * for somebody plainly gone ([Departure.UNAMBIGUOUS_MARGIN_M]).
     */
    UNAMBIGUOUS,

    /**
     * Two qualifying fixes at least [Departure.CONFIRMATION_GAP] apart — the
     * rule that kills the GPS jump.
     */
    TWO_FIX,
}

/** The result of feeding one fix to the test: the verdict, and the state to carry forward. */
data class DepartureStep(
    val verdict: DepartureVerdict,
    val progress: DepartureProgress,
    /** Which rule produced a [DepartureVerdict.DEPARTED]; null for the rest. */
    val rule: DepartureRule? = null,
)

/**
 * Whether the user has left the anchor (SPEC.md §6.6).
 *
 * The rules exist to reject specific real-world failures rather than to be
 * conservative in the abstract:
 *
 * - **Never compare raw distance to radius.** Subtracting the reading's own
 *   uncertainty first is what stops a 500 m-accuracy cell fix from "leaving" a
 *   100 m radius while the phone sits on a desk. Both endpoints count: the
 *   anchor is a reported point too, so [uncertaintyM] combines its accuracy
 *   with the fix's rather than treating the origin as exact.
 * - **Hysteresis**, so a fix hovering on the boundary does not flap.
 * - **Two qualifying fixes ≥30 s apart**, which kills the GPS jump: a single
 *   wild fix cannot end a snooze, because the next one a moment later disagrees.
 * - **Unless the fix is unambiguous** — beyond the radius by half a kilometer
 *   even after its accuracy is subtracted. Someone a kilometer away should get
 *   their phone back now, not after a debounce designed for boundary noise.
 *
 * Everything here is pure. The Android layer decides *when* to ask; this decides
 * what the answer means.
 */
object Departure {

    /** Added to the radius so a fix on the boundary does not flap (SPEC.md §6.6). */
    const val HYSTERESIS_M: Int = 50

    /**
     * Beyond this much past the radius, one fix is enough.
     *
     * Not a debounce shortcut so much as a different claim: at this distance the
     * reading cannot be boundary noise, and waiting would keep a phone silent
     * for someone who is plainly gone.
     */
    const val UNAMBIGUOUS_MARGIN_M: Int = 500

    /**
     * How far apart two qualifying fixes must be to confirm each other.
     *
     * Two readings a second apart are one observation, not two — a GPS jump
     * lasts longer than that. The gap is what makes the second fix independent
     * evidence.
     */
    val CONFIRMATION_GAP: Duration = Duration.ofSeconds(30)

    /**
     * Great-circle distance in meters.
     *
     * Haversine rather than anything cleverer: at the scale that matters here —
     * tens to thousands of meters — the difference between a spherical earth and
     * an ellipsoidal one is far below the accuracy of the fixes being compared.
     */
    fun distanceM(fix: Fix, anchor: Anchor): Double? {
        // The same gate `Anchor.hasUsableFix` applies, and applied *here* rather
        // than left to callers (Codex, PR #30). An anchor can carry coordinates
        // whose accuracy was too poor to trust — capture keeps them, and the
        // model already says such a snooze runs Wi-Fi-only (SPEC.md §8.4).
        // Measuring against them anyway would make this function disagree with
        // `hasUsableFix` about the same anchor, and a departure computed from a
        // reference point the app has declared unusable is a snooze ended by a
        // fix that was wrong when it was captured, not by anyone moving.
        if (!anchor.hasUsableFix) return null
        val lat = anchor.lat ?: return null
        val lon = anchor.lon ?: return null
        val earthRadiusM = 6_371_008.8
        val dLat = Math.toRadians(fix.lat - lat)
        val dLon = Math.toRadians(fix.lon - lon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(fix.lat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusM * asin(min(1.0, sqrt(a)))
    }

    /**
     * The two 68%-confidence radii combined, in meters — how far the
     * *separation* between anchor and fix could be wrong.
     *
     * **Both terms, because both points are reported rather than known.** The
     * fix's accuracy was always subtracted; the anchor's never was, so the
     * origin every distance is measured from was treated as exact while being
     * accepted at up to [Anchor.MAX_ANCHOR_ACCURACY_M]. A capture that vague
     * can put the origin further from the phone than the whole departure bar,
     * and nothing downstream could see it (maintainer, 2026-09-07).
     *
     * **In quadrature, not added.** Two independent errors combine as the root
     * of the sum of squares, and two fixes minutes-to-hours apart are close
     * enough to independent for that to be the honest combination. Adding them
     * would be a *higher*-confidence bound obtained by accident rather than by
     * choice — 100 m where the real 68% figure is 71 — which buys caution the
     * app never decided to buy and pays for it in walking distance.
     *
     * These are confidence radii and not caps, so this bounds nothing
     * absolutely: the true separation lies outside it a third of the time or
     * so, on each fix. The two-fix confirmation is what turns that into a
     * decision worth making, not this number.
     */
    fun uncertaintyM(fixAccuracyM: Float, anchorAccuracyM: Float): Double =
        sqrt(
            fixAccuracyM.toDouble() * fixAccuracyM.toDouble() +
                anchorAccuracyM.toDouble() * anchorAccuracyM.toDouble(),
        )

    /**
     * The same, for a fix and the anchor it is measured against. Null for
     * exactly the case [distanceM] is null for.
     */
    fun uncertaintyM(fix: Fix, anchor: Anchor): Double? {
        if (!anchor.hasUsableFix) return null
        val anchorAccuracy = anchor.fixAccuracyM ?: return null
        return uncertaintyM(fix.accuracyM, anchorAccuracy)
    }

    /**
     * How far past the anchor's edge this fix can be trusted to be, in meters.
     *
     * Negative or zero means the fix does not establish being outside at all.
     * Null means the anchor has nothing trustworthy to measure against — no
     * coordinates, or coordinates whose accuracy failed
     * `Anchor.MAX_ANCHOR_ACCURACY_M` — which is Wi-Fi-only mode rather than a
     * departure (SPEC.md §8.4).
     */
    fun marginM(fix: Fix, anchor: Anchor): Double? {
        val distance = distanceM(fix, anchor) ?: return null
        val uncertainty = uncertaintyM(fix, anchor) ?: return null
        return distance - uncertainty - anchor.radiusM
    }

    /** Whether this fix is evidence of being outside, uncertainty already deducted. */
    fun qualifies(fix: Fix, anchor: Anchor): Boolean {
        val margin = marginM(fix, anchor) ?: return false
        return margin > HYSTERESIS_M
    }

    /**
     * Whether this fix is positive evidence of being *at* the anchor.
     *
     * The mirror of [qualifies], and deliberately not its negation: accuracy is
     * **added** here rather than subtracted, so the whole uncertainty circle has
     * to fit inside the radius. A fix that is neither confidently in nor
     * confidently out is [DepartureVerdict.INCONCLUSIVE] — it locates nobody,
     * and treating it as presence is how an app reports someone as home on the
     * strength of a reading that says "somewhere in this town".
     */
    fun confirmsPresence(fix: Fix, anchor: Anchor): Boolean {
        val distance = distanceM(fix, anchor) ?: return false
        val uncertainty = uncertaintyM(fix, anchor) ?: return false
        return distance + uncertainty <= anchor.radiusM
    }

    /**
     * The arithmetic behind this fix's verdict, for a live readout or a log.
     *
     * Null for exactly the case [distanceM] is null for — an anchor with no
     * usable coordinates, which is a Wi-Fi-only snooze rather than a departure
     * that can be measured (SPEC.md §8.4). A screen with nothing to measure
     * against must say so rather than show a number computed from a reference
     * point the app has already declared untrustworthy.
     */
    fun observe(fix: Fix, anchor: Anchor): DepartureObservation? {
        val distance = distanceM(fix, anchor) ?: return null
        val anchorAccuracy = anchor.fixAccuracyM ?: return null
        return DepartureObservation(
            distanceM = distance,
            accuracyM = fix.accuracyM,
            anchorAccuracyM = anchorAccuracy,
            radiusM = anchor.radiusM,
            elapsedRealtimeMs = fix.elapsedRealtimeMs,
        )
    }

    /** Whether this fix is so far out that no confirmation is needed. */
    fun isUnambiguous(fix: Fix, anchor: Anchor): Boolean {
        val margin = marginM(fix, anchor) ?: return false
        return margin > UNAMBIGUOUS_MARGIN_M
    }

    /**
     * Feeds one fix to the test, returning the verdict and the state to carry.
     *
     * [progress] is whatever the previous call returned, or
     * [DepartureProgress.NONE] to start. A fix that does not qualify **clears**
     * the window rather than leaving it open: the spec asks for two
     * *consecutive* qualifying fixes, and a reading that puts the phone back at
     * the anchor in between is exactly the evidence that the first one was
     * noise.
     */
    fun consider(
        fix: Fix,
        anchor: Anchor,
        progress: DepartureProgress = DepartureProgress.NONE,
    ): DepartureStep {
        if (isUnambiguous(fix, anchor)) {
            return DepartureStep(
                DepartureVerdict.DEPARTED,
                DepartureProgress.NONE,
                DepartureRule.UNAMBIGUOUS,
            )
        }
        if (!qualifies(fix, anchor)) {
            // Both close the window, and only the *verdict* differs (Codex,
            // PR #30). §6.6 asks for two **consecutive** qualifying fixes, and a
            // reading that cannot place the user is not one — letting it join
            // two isolated outliers would rebuild the very false positive the
            // confirmation rule exists to prevent.
            //
            // The cost is real and worth knowing: leaving a building is exactly
            // when fixes go vague, so in poor signal a genuine departure may
            // need several attempts before two clean readings land in a row.
            // That delays confirmation rather than preventing it — more fixes
            // keep arriving, Wi-Fi loss escalates independently (D4), and the
            // cap bounds the whole thing — but if field measurement shows the
            // delay is material, this is the line to revisit. See `TODO.md`.
            val verdict = if (confirmsPresence(fix, anchor)) {
                DepartureVerdict.STILL_HERE
            } else {
                DepartureVerdict.INCONCLUSIVE
            }
            return DepartureStep(verdict, DepartureProgress.NONE)
        }

        val openedAtMs = progress.firstQualifyingAtMs
            ?: return DepartureStep(
                DepartureVerdict.AWAITING_CONFIRMATION,
                DepartureProgress(firstQualifyingAtMs = fix.elapsedRealtimeMs),
            )

        // `>=` rather than `>`: the spec says "at least 30 s apart", and a fix
        // landing exactly on the boundary is evidence, not a tie to break
        // against the user's phone staying silent.
        val gapMs = fix.elapsedRealtimeMs - openedAtMs
        if (gapMs >= CONFIRMATION_GAP.toMillis()) {
            return DepartureStep(
                DepartureVerdict.DEPARTED,
                DepartureProgress.NONE,
                DepartureRule.TWO_FIX,
            )
        }

        // Qualifying, but too soon to be independent evidence. The window keeps
        // its *original* start rather than restarting here — otherwise a burst
        // of fixes every few seconds would push the deadline ahead of itself and
        // never confirm, which is the shape of bug that leaves a phone silent.
        return DepartureStep(DepartureVerdict.AWAITING_CONFIRMATION, progress)
    }
}
