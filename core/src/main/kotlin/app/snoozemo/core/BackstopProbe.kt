package app.snoozemo.core

/**
 * The §6.6 departure test, run by the periodic backstop itself when it cannot
 * start the service (SPEC.md §6.10).
 *
 * A field log (2026-10-05) showed every backstop wake refused: Android will
 * not start a service from a background `WorkManager` worker, so "each wake
 * restores the service" restored nothing, three wakes running, and a user
 * 300 m from the anchor stayed silent with the cap as the only exit. The
 * worker holds the background-location grant the snooze already needs, so it
 * can take the fixes and run the test where it is.
 *
 * The same test as everywhere else, not a looser one: [Departure.consider]
 * decides, so a single fix ends a snooze only past the unambiguous margin, and
 * otherwise two qualifying fixes [Departure.CONFIRMATION_GAP] apart are
 * needed. Pure, over a fix source and a sleeper, so every path is JVM-tested.
 */
object BackstopProbe {

    /**
     * How many fixes one wake may take. Enough for a qualifying fix, one
     * confirmation and one retry of it; a wake that cannot settle in that
     * many leaves the question to the next one — the cap bounds the snooze
     * either way, and each fix is a battery cost (SPEC.md §9).
     */
    const val MAX_FIXES: Int = 3

    /** What one probe concluded. */
    sealed interface Outcome {
        /** Confirmed gone: the caller ends the snooze. */
        data class Departed(val rule: DepartureRule?) : Outcome

        /** A fix placed the user at the anchor. */
        data object StillHere : Outcome

        /** Fixes arrived but none could settle it. */
        data object Inconclusive : Outcome

        /** No usable fix at all. */
        data object NoFix : Outcome

        /** Nothing to test against, so nothing was asked of location. */
        data object NotTestable : Outcome

        /**
         * No fix, for a reason the platform stated outright — the grant gone,
         * or location switched off — rather than a reading that simply did not
         * come. Decided by the platform side from what its requests reported;
         * [run] itself only ever says [NoFix].
         */
        data class Unavailable(val cause: DegradationCause) : Outcome

        /**
         * On the anchor's own network, before or during the probe: D4's
         * suppressor holds here as it does in the monitor (SPEC.md §6.10),
         * because the single-fix shortcut could otherwise end a snooze on a
         * network that covers more ground than the radius.
         */
        data object AtAnchorWifi : Outcome
    }

    /**
     * The anchor's Wi-Fi as the probe sees it while it runs (D4).
     *
     * Read live, around every fix, rather than once up front or as a history
     * (Codex, PR #310, twice): the probe takes minutes, a phone can rejoin the
     * anchor's network during it — present, whatever a fix says — and can drop
     * it again and then genuinely leave, which a sticky "was ever associated"
     * read would hold silent. So association *now* stops the probe, and any
     * change in association restarts the two-fix window, so no confirmation
     * spans one.
     */
    interface AnchorWifi {
        /** Whether the anchor's network is confirmed associated right now. */
        fun associatedNow(): Boolean

        /** A counter that moves on every change in association. */
        fun transitions(): Long

        /** No Wi-Fi anchor, so nothing ever suppresses or restarts. */
        object None : AnchorWifi {
            override fun associatedNow() = false
            override fun transitions() = 0L
        }
    }

    /**
     * Runs the test for [snooze]. [takeFix] blocks for one fix or returns
     * null; [sleepMs] waits between fixes; [nowElapsedMs] dates a fix's age;
     * [wifi] is read around each fix. [onObservation] sees each reading for
     * the debug log.
     */
    fun run(
        snooze: ActiveSnooze,
        takeFix: () -> Fix?,
        sleepMs: (Long) -> Unit,
        nowElapsedMs: () -> Long,
        wifi: AnchorWifi = AnchorWifi.None,
        onObservation: (DepartureObservation, DepartureVerdict, DepartureRule?) -> Unit = { _, _, _ -> },
    ): Outcome {
        // Only a snooze that still ends on departure, against an anchor it can
        // measure from. A chosen timer has no departure to find.
        if (!snooze.endsOnDeparture || !snooze.anchor.hasUsableFix) return Outcome.NotTestable
        val anchor = snooze.anchor
        var progress = DepartureProgress.NONE
        var windowEpoch = wifi.transitions()
        var sawFix = false
        repeat(MAX_FIXES) { attempt ->
            if (attempt > 0) sleepMs(Departure.CONFIRMATION_GAP.toMillis())
            if (wifi.associatedNow()) return Outcome.AtAnchorWifi
            val fix = takeFix()?.takeIf { isFresh(it, nowElapsedMs()) } ?: return@repeat
            // Checked again after the fix, which can take its own time: an
            // association that landed meanwhile outranks the reading.
            if (wifi.associatedNow()) return Outcome.AtAnchorWifi
            val epoch = wifi.transitions()
            if (epoch != windowEpoch) {
                progress = DepartureProgress.NONE
                windowEpoch = epoch
            }
            sawFix = true
            val step = Departure.consider(fix, anchor, progress)
            Departure.observe(fix, anchor)?.let { onObservation(it, step.verdict, step.rule) }
            when (step.verdict) {
                DepartureVerdict.DEPARTED -> return Outcome.Departed(step.rule)
                DepartureVerdict.STILL_HERE -> return Outcome.StillHere
                // A vague fix closes the window (`Departure.consider`), so the
                // next attempt starts over rather than ending the wake: leaving
                // a building is exactly when fixes go vague.
                DepartureVerdict.INCONCLUSIVE -> progress = DepartureProgress.NONE
                DepartureVerdict.AWAITING_CONFIRMATION -> progress = step.progress
            }
        }
        return if (sawFix) Outcome.Inconclusive else Outcome.NoFix
    }

    /**
     * A platform may answer with a cached position, and a reading from before
     * the user left is the one that would hold the phone silent. Bounded at
     * the gap the test confirms across, so a fix that could not be one half
     * of a fresh confirmation is not used as either half.
     */
    internal fun isFresh(fix: Fix, nowElapsedMs: Long): Boolean =
        nowElapsedMs - fix.elapsedRealtimeMs <= Departure.CONFIRMATION_GAP.toMillis()
}
