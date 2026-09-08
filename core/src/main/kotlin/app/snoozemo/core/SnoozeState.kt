package app.snoozemo.core

import java.time.Duration

/**
 * The states of SPEC.md §4.1. The controller that drives these transitions lands
 * in Phase 1; this is the vocabulary the rest of the app is written against.
 */
enum class SnoozeState {
    /** Not snoozing. Snoozemo's zen rule is `STATE_FALSE`. */
    IDLE,

    /**
     * Capturing the anchor. Bounded at 10 s and never allowed to block: on
     * timeout the snooze arms in a degraded [TrackingMode] rather than refusing,
     * because arming must never feel slow (SPEC.md §4.1).
     */
    ARMING,

    /** Snoozing. The zen rule is `STATE_TRUE` and presence is being watched. */
    ARMED,

    /**
     * Something suggested the user may have left — Wi-Fi dropped, significant
     * motion fired, a geofence exit arrived — and the departure test is running.
     * No single signal ends a snooze on its own evidence (SPEC.md §6.10).
     */
    CHECKING,

    /** The snooze has ended; the zen rule is back to `STATE_FALSE`. */
    RELEASED,
}

/**
 * Why a snooze ended. Every exit records one: a snooze that ends for a reason the
 * user cannot reconstruct is the "never fail silently" failure, so this is what
 * the ended-notification and the debug log are written from (SPEC.md §4.5, §7).
 */
enum class EndReason {
    /** The departure test confirmed the user left the anchor. The intended path. */
    DEPARTURE,

    /** The duration cap fired — the backstop that holds when every sensor has failed. */
    DURATION_CAP,

    /** The user ended it: tile tap, notification action, or in-app. */
    MANUAL,

    /**
     * The user turned Do Not Disturb off themselves — the shade toggle, or the
     * Modes UI — deactivating Snoozemo's rule underneath a running snooze
     * (SPEC.md §5.8).
     *
     * Separate from [MANUAL] because it did not come through Snoozemo at all,
     * and separate from [LOST_CAPABILITY] because nothing is broken: the user
     * asked for the phone to ring, and got it. Both distinctions are for the
     * debug log's benefit — the user needs no explanation for something they
     * just did.
     */
    DND_TURNED_OFF,

    /**
     * Snoozemo could no longer do its job — policy access revoked, location
     * permission downgraded — so it ended the snooze rather than staying armed
     * on state it cannot verify (SPEC.md D7, §8.2).
     */
    LOST_CAPABILITY,
}

/**
 * Who the platform is told ended the snooze (SPEC.md §5.4).
 *
 * Here rather than at either call site, because there are two — the controller
 * and the no-service fallback — and keeping the mapping in both is what let
 * [EndReason.DND_TURNED_OFF] be reported as automation twice: once when it was
 * introduced, and again on the fallback when a later fix made that path reach
 * it (Codex, PR #36). Exhaustive, so the next reason added has to be decided
 * rather than defaulted.
 */
fun EndReason.zenTrigger(): ZenTrigger = when (this) {
    // Both are the user, reaching the same switch from different directions:
    // our tile or notification, or the platform's own Do Not Disturb toggle.
    EndReason.MANUAL, EndReason.DND_TURNED_OFF -> ZenTrigger.USER_ACTION
    EndReason.DEPARTURE,
    EndReason.DURATION_CAP,
    EndReason.LOST_CAPABILITY,
    -> ZenTrigger.CONTEXT
}

/**
 * How much of the presence engine is actually working for this snooze. Anything
 * short of [FULL] is user-visible: the ongoing notification says so, because a
 * silently degraded snooze is indistinguishable from a working one until it
 * fails (SPEC.md §8.1).
 */
enum class TrackingMode {
    /** Location and Wi-Fi both available; the departure test can run. */
    FULL,

    /**
     * No usable location fix, but associated with the anchor SSID. Wi-Fi loss
     * escalates, and with nothing to confirm against it resolves by ending after
     * a grace period (SPEC.md §6.6).
     */
    WIFI_ONLY,

    /**
     * The anchor's Wi-Fi is gone and location cannot confirm a departure
     * either — unverifiable, and the §6.6 grace period is running: the snooze
     * ends automatically unless something recovers first. Distinct from
     * [WIFI_ONLY] because that name means "Wi-Fi is what's tracking this",
     * which stops being true the instant Wi-Fi is what was just lost (Codex,
     * PR #31) — the same watch, a worse answer, not a different capability
     * tier. Not a rung [honest] can reach on its own: it stands or falls with
     * [WIFI_ONLY]'s own support, since nothing watches this that doesn't also
     * watch that.
     */
    WIFI_GRACE,

    /** Neither signal is available. Only the duration cap will end this snooze. */
    DURATION_ONLY,

    /**
     * The anchor has not been captured yet — the arm is still looking
     * (maintainer, 2026-09-07, from a device log).
     *
     * **Not a capability tier, and deliberately last.** [honest] walks the
     * others by ordinal as a ladder from most to least capable; this sits below
     * the end of it so nothing can ever step onto it, and [honest] returns it
     * untouched rather than walking *off* the end. It is the absence of a
     * claim, not a worse one.
     *
     * It exists because the record is written before the anchor is
     * (SPEC.md §4.1: arming must never wait on a fix), and for the ~10 s the
     * fix takes the mode had to say *something*. Saying [DURATION_ONLY] meant
     * both the main screen and the ongoing notification told the user their
     * snooze was a timer — on snoozes that went on to track perfectly. A mode
     * on a record that is still arming is the absence of a decision, so it now
     * says so, and both surfaces render it as such because both read this one
     * field.
     *
     * **Never survives a process.** [SnoozeController.restore] resolves it to
     * whatever the stored anchor actually supports: a capture that was in
     * flight when the process died is not still running, and a snooze that came
     * back claiming to be looking would look for ever.
     */
    SETTLING,
    ;

    /**
     * Whether the arm is still looking — the anchor has not landed yet.
     *
     * Named rather than compared against [SETTLING] directly, because it is
     * the question three places ask (the ladder walk, the restore-time
     * resolution, and the record's staleness window) and none of them cares
     * which settling value answers it. It briefly covered two
     * (`SETTLING_AWAITING_WIFI`, removed 2026-09-08 with the second string it
     * existed for); the name is what let those three sites stay correct
     * through both changes without being edited.
     */
    val isSettling: Boolean
        get() = this == SETTLING

    companion object {
        /**
         * The most capable mode [anchor] actually supports.
         *
         * Coordinates too vague to test against are worth nothing (SPEC.md
         * §8.4), so this reads [Anchor.hasUsableFix] rather than "did we get a
         * fix": an anchor captured indoors with a 500 m cell fix degrades to
         * Wi-Fi, or to the cap alone if there is no SSID either.
         */
        fun from(anchor: Anchor): TrackingMode = when {
            anchor.hasUsableFix -> FULL
            anchor.ssid != null -> WIFI_ONLY
            else -> DURATION_ONLY
        }

        /**
         * How long a stored [SETTLING] can still mean a capture is running.
         *
         * A capture cannot outlive its own ceiling: by then it has either
         * delivered an anchor — which replaces the mode — or been settled by
         * the ceiling itself. So a [SETTLING] older than this was written by a
         * process that has since died mid-capture, and the honest reading is
         * that nothing is watching (Codex, PR #221).
         *
         * The window exists because the value is **liveness-dependent but
         * durable**: it says "a capture is running", and it outlives the
         * process running it. Readers cold-read the record straight off disk —
         * the main screen through [ActiveSnoozeStore], the tile out of the
         * preferences file — and none of them can see whether the service is
         * alive. Rather than give each one a liveness signal to consult,
         * [SETTLING] carries its own expiry, derived from `startedAt`, which
         * every reader already has.
         *
         * **Generous on purpose, and by a wide margin.** `startedAt` is stamped
         * before the record is saved, the notification posted, the tile
         * refreshed and the backstop scheduled — the runner's own ceiling only
         * starts ticking after all of that — so the window has to cover the arm
         * path's latency as well as the capture, and a forward wall-clock
         * adjustment ages it faster than the runner's monotonic timer besides
         * (Codex, PR #221).
         *
         * The two errors are not symmetric, which is what settles the size.
         * Expiring **early** contradicts a capture that is genuinely still
         * running: the screen and the tile would say `Timer only` while the
         * notification still says the opposite, which is the exact failure
         * this whole change exists to remove. Expiring **late** only means a
         * record left by a dead process is believed a few seconds longer —
         * the same bounded, self-healing residual already accepted for the
         * posted card. So the margin is sized to be comfortably longer than
         * any arm, not tight to the ceiling.
         *
         * Deriving it from the runner's actual deadline in a monotonic frame
         * would remove the guesswork, and it is part of the design question
         * recorded in `TODO.md` rather than settled here.
         */
        val SETTLING_VALID_FOR: Duration = AnchorCapture.CEILING.plusSeconds(30)

        /**
         * Whether a stored [SETTLING] written at [startedAtMillis] still
         * stands as of [nowMillis].
         *
         * Anything outside the window reads as dead, **including a negative
         * age**: a backwards clock change puts `now` before `startedAt`, and
         * the safe direction is to stop claiming a capture is running rather
         * than to keep claiming it for the length of the shift. Degrading here
         * costs a truthful `Timer only` a few seconds early; the other
         * direction is the app saying it is still checking when nothing is.
         */
        fun settlingStillStands(startedAtMillis: Long, nowMillis: Long): Boolean =
            (nowMillis - startedAtMillis) in 0..SETTLING_VALID_FOR.toMillis()
    }
}
