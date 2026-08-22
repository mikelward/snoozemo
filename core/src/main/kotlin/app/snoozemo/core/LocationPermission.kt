package app.snoozemo.core

/**
 * Whether Snoozemo can watch a place well enough to end a snooze on its own —
 * and, when it can't, whether asking again would do anything.
 *
 * Two permissions stand behind this, not one: `ACCESS_FINE_LOCATION` /
 * `ACCESS_COARSE_LOCATION` (foreground) and `ACCESS_BACKGROUND_LOCATION`, and
 * the platform only ever grants the second once the first is already held.
 * This type mirrors that ordering rather than reimplementing it: the missing
 * one is read foreground-first, and the row this feeds offers exactly the
 * next step the platform will actually honor.
 *
 * Missing either one still leaves a working snooze — the presence engine
 * degrades to Wi-Fi-only or duration-only and says so (`SPEC.md` §3.6's
 * fallback ladder) — so this is purely what the settings row uses to state
 * the gap and offer the fix, the same shape [NotificationPermission] uses for
 * the same reason: the runtime dialog is shown at most twice per permission
 * and then silently ignored, so a row that always offers to ask becomes a tap
 * that does nothing.
 */
enum class LocationPermission {

    /** Both held. Nothing is owed and nothing is degraded on account of this. */
    GRANTED,

    /**
     * Not fully held, and the system will still show a prompt for whichever
     * permission is missing.
     */
    ASKABLE,

    /**
     * Not fully held, and the system will silently ignore a further request
     * for whichever permission is missing. Settings is the only live route.
     */
    BLOCKED,

    ;

    companion object {

        /**
         * @param foregroundGranted whether the foreground grant the presence
         *   engine can actually use is held — `ACCESS_FINE_LOCATION`
         *   specifically, not a downgrade to `ACCESS_COARSE_LOCATION` alone
         *   (`PlatformFixRequester`, `AnchorCaptureRunner` both gate on fine;
         *   coarse-only is a fatal capability loss to them, not a degraded
         *   mode — Codex, PR #79). The caller still *requests* fine and coarse
         *   together, since Android 12+ refuses a fine-only request outright
         *   rather than offering the approximate downgrade; only the grant
         *   this reads has to be the one the engine trusts.
         * @param backgroundGranted whether `ACCESS_BACKGROUND_LOCATION` is
         *   held. Meaningless when [backgroundRequired] is false and never
         *   read in that case.
         * @param foregroundEverDenied / [foregroundRationale] and
         *   [backgroundEverDenied] / [backgroundRationale]: the same
         *   history-plus-rationale pair [NotificationPermission.of] takes, kept
         *   once per permission because the two are requested — and can be
         *   blocked — independently of each other.
         * @param backgroundRequired whether this flavor's tracking needs
         *   `ACCESS_BACKGROUND_LOCATION` at all. `direct` declares no such
         *   permission (`SPEC.md` §3.4) — without this, a flavor that will
         *   never hold the permission, and whose requests the platform
         *   silently denies with no rationale ever offered, reads as
         *   permanently `ASKABLE` (Codex, PR #79).
         *
         * Foreground is read first: the platform will not grant background
         * without it, so a missing foreground permission is the actual next
         * step regardless of the background permission's own history.
         */
        fun of(
            foregroundGranted: Boolean,
            backgroundGranted: Boolean,
            foregroundEverDenied: Boolean,
            foregroundRationale: Boolean,
            backgroundEverDenied: Boolean,
            backgroundRationale: Boolean,
            backgroundRequired: Boolean = true,
        ): LocationPermission = when {
            foregroundGranted && (backgroundGranted || !backgroundRequired) -> GRANTED
            !foregroundGranted ->
                if (foregroundRationale || !foregroundEverDenied) ASKABLE else BLOCKED
            else ->
                if (backgroundRationale || !backgroundEverDenied) ASKABLE else BLOCKED
        }
    }
}
