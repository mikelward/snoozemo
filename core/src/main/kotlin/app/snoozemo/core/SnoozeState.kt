package app.snoozemo.core

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
    DURATION_ONLY;

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
    }
}
