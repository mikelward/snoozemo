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

/** The result of feeding one fix to the test: the verdict, and the state to carry forward. */
data class DepartureStep(
    val verdict: DepartureVerdict,
    val progress: DepartureProgress,
)

/**
 * Whether the user has left the anchor (SPEC.md §6.6).
 *
 * The rules exist to reject specific real-world failures rather than to be
 * conservative in the abstract:
 *
 * - **Never compare raw distance to radius.** Subtracting the fix's own accuracy
 *   first is what stops a 500 m-accuracy cell fix from "leaving" a 150 m radius
 *   while the phone sits on a desk.
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
        return distance - fix.accuracyM - anchor.radiusM
    }

    /** Whether this fix is evidence of being outside, accuracy already deducted. */
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
        return distance + fix.accuracyM <= anchor.radiusM
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
            return DepartureStep(DepartureVerdict.DEPARTED, DepartureProgress.NONE)
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
            return DepartureStep(DepartureVerdict.DEPARTED, DepartureProgress.NONE)
        }

        // Qualifying, but too soon to be independent evidence. The window keeps
        // its *original* start rather than restarting here — otherwise a burst
        // of fixes every few seconds would push the deadline ahead of itself and
        // never confirm, which is the shape of bug that leaves a phone silent.
        return DepartureStep(DepartureVerdict.AWAITING_CONFIRMATION, progress)
    }
}
