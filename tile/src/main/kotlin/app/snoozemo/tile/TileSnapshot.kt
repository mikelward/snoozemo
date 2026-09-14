package app.snoozemo.tile

import android.content.Context
import android.text.format.DateFormat
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.capTimeFronted
import java.util.Date

/**
 * What the tile needs to render, read straight from the persisted snooze record.
 *
 * A file read rather than a bound service on purpose: `onStartListening` runs
 * every time the shade opens, and binding for two fields would put IPC on the
 * path that has to feel instant. The file is small and already in the page cache.
 */
internal data class TileSnapshot(
    val snoozing: Boolean,
    val capExpiresAtMillis: Long,
    /**
     * Whether to qualify the countdown as timer-only — false both when the
     * snooze *is* tracked and when the tile has no business claiming either
     * way yet. See [claimsTimerOnly].
     */
    val timerOnly: Boolean,
    /**
     * Whether an armed movement exit ends this snooze. When it is watched but
     * not a timer ([timerOnly] false), this is what tells `Until you move` from
     * `Until you leave` — the two share the "watched, no fronted deadline" shape
     * but name different exits. [mode] outranks it: a Wi-Fi grace period ends on
     * a deadline whether or not the phone moves (see [subtitleKind]).
     */
    val endsOnMotion: Boolean = false,
    /**
     * The persisted tracking mode, parsed, or `null` for a value this build
     * cannot read. Kept whole rather than reduced to booleans so the shade can
     * render [TrackingMode.WIFI_GRACE] honestly: grace is not a watched exit
     * but a five-minute deadline that ends the snooze on expiry, and the two
     * booleans above cannot tell it from `Until you leave` — the loss that
     * would have put a disprovable exit over a grace period (Codex, PR #281).
     * [timerOnly] stays the tile's compression of the *effective* mode (see
     * [claimsTimerOnly]); this is the raw mode the screen and the notification
     * also switch on. Grace is only *active* when [endsOnDeparture] is still
     * true — see [subtitleKind].
     */
    val mode: TrackingMode? = null,
    /**
     * Whether a departure still ends this snooze — the user's `Until I leave`
     * choice. With [mode] it decides whether Wi-Fi grace is *active*
     * ([subtitleKind]): a snooze narrowed to a timer keeps its raw [mode] so
     * `Until I leave` can restore the exit, so a residual `WIFI_GRACE` left
     * after a successful timer conversion (this false) is not a running grace
     * period and must not read `Ending soon` over the chosen time (Codex,
     * PR #281). `effectiveMode == WIFI_GRACE` is exactly `mode == WIFI_GRACE &&
     * endsOnDeparture`, the lens the notification uses.
     */
    val endsOnDeparture: Boolean = true,
) {

    /** What the tile's subtitle says how this snooze ends. */
    internal enum class SubtitleKind { NONE, UNTIL_TIME, UNTIL_LEAVE, UNTIL_MOVE, ENDING_SOON }

    /**
     * Which subtitle the shade says, decided without a `Context` so it is unit-
     * tested directly; [subtitle] and [stateDescription] map it to strings.
     *
     * A running snooze always names how it ends, ordered soonest-and-surest
     * first: a watched exit carries no *time* (its cap is the passive failsafe,
     * and fronting it would promise a deadline the snooze does not expect to
     * reach, SPEC.md §4.2), so it names the exit instead of a countdown.
     *
     * **Active Wi-Fi grace outranks every other ending, including a chosen
     * time.** A grace period is a five-minute deadline that ends the snooze on
     * expiry whether or not the phone leaves or moves, and before a chosen time
     * further out — so naming any other ending would put a claim the user could
     * see disproved minutes later. The screen and the notification say `Wi-Fi
     * lost — ending soon`; the tile says `Ending soon`. It is checked first, so
     * it wins even when a chosen time or a shortened cap has made [timerOnly]
     * true in the durable partial state where a time was chosen during grace and
     * the exit-removal did not persist (SPEC.md §4.2, Codex PR #281).
     *
     * **Active** — `effectiveMode == WIFI_GRACE`, i.e. [mode] is `WIFI_GRACE`
     * *and* [endsOnDeparture] is still true — not the raw mode. A snooze narrowed
     * to a timer keeps its raw [mode], so a residual `WIFI_GRACE` after a
     * successful conversion is a plain timer and reads `Until <time>`, not
     * `Ending soon`.
     *
     * Otherwise: [timerOnly] is the tile's read of whether to front the time —
     * `ActiveSnooze.capCountdownShown` (a chosen time, a shortened cap, or a
     * settled failsafe), not the narrower `capIsEffectiveEnd` (see
     * [claimsTimerOnly]). A movement exit is `Until you move`, and any other
     * watched snooze is `Until you leave`.
     */
    internal val subtitleKind: SubtitleKind get() = when {
        !snoozing -> SubtitleKind.NONE
        mode == TrackingMode.WIFI_GRACE && endsOnDeparture -> SubtitleKind.ENDING_SOON
        timerOnly -> SubtitleKind.UNTIL_TIME
        endsOnMotion -> SubtitleKind.UNTIL_MOVE
        else -> SubtitleKind.UNTIL_LEAVE
    }

    fun subtitle(context: Context): String? = when (subtitleKind) {
        SubtitleKind.NONE -> null
        SubtitleKind.UNTIL_TIME -> context.getString(R.string.tile_until_time, endsAtTime(context))
        SubtitleKind.UNTIL_LEAVE -> context.getString(R.string.tile_until_leave)
        SubtitleKind.UNTIL_MOVE -> context.getString(R.string.tile_until_move)
        SubtitleKind.ENDING_SOON -> context.getString(R.string.tile_ending_soon)
    }

    fun stateDescription(context: Context): String = when (subtitleKind) {
        SubtitleKind.NONE -> context.getString(R.string.tile_state_off)
        SubtitleKind.UNTIL_TIME -> context.getString(R.string.tile_state_until_time, endsAtTime(context))
        SubtitleKind.UNTIL_LEAVE -> context.getString(R.string.tile_state_leave)
        SubtitleKind.UNTIL_MOVE -> context.getString(R.string.tile_state_move)
        SubtitleKind.ENDING_SOON -> context.getString(R.string.tile_state_ending_soon)
    }

    /**
     * The cap as a clock time in the phone's own 12/24-hour format. An absolute
     * time rather than a countdown, so the shade never shows a stale figure it
     * only recomputes when reopened — the tile does not tick between opens. Wall
     * clock is exactly what the user reads here; the two-clock flooring the
     * countdown needed was to keep a *duration* honest across a clock change, and
     * a stated end time has no duration to keep.
     */
    private fun endsAtTime(context: Context): String =
        DateFormat.getTimeFormat(context).format(Date(capExpiresAtMillis))

    companion object {

        /**
         * Whether the tile fronts the cap's *time* rather than naming an exit —
         * the tile's side of [app.snoozemo.core.ActiveSnooze.capCountdownShown].
         *
         * The disjunction itself lives once, in [app.snoozemo.core.capTimeFronted];
         * this resolves the one input the surfaces read differently — whether a
         * departure is tracked — and delegates the rest. Sharing it is what stops
         * the tile drifting from the model: it re-derived the whole decision
         * before and diverged twice in one review, on `WIFI_GRACE` and on the
         * partial-timer state, because each missing input was invisible until a
         * reviewer named it (Codex, PR #281).
         *
         * Resolving `tracksDeparture`:
         * - `ends_on_departure` is the tile's half of [app.snoozemo.core.ActiveSnooze.effectiveMode]:
         *   a snooze narrowed to its timer keeps the capability `mode` it was
         *   tracking with, so the mode alone would read as still watching a
         *   departure while the user has switched to a timer. Off ⇒ not tracking.
         * - The mode is parsed to the enum and decided by an exhaustive `when`
         *   rather than compared to the string `"FULL"`, so a member added to
         *   [TrackingMode] is a build error here too rather than a silent
         *   misreading (Codex, PR #221). A missing or unrecognized value ⇒ not
         *   tracking, matching `ActiveSnoozeStore`'s own `DURATION_ONLY`
         *   fallback for a record written before the mode existed, or by a
         *   newer build than this tile.
         * - `SETTLING` is where the tile diverges from the model deliberately.
         *   A *live* capture (within the window) is still on its way to watching
         *   a departure, so the tile counts it as tracking and shows the exit it
         *   is capturing an anchor for (SPEC.md §4.2), rather than fronting a
         *   countdown to a backstop the snooze has not chosen. A record left past
         *   the window is a dead process's — the capture died with it while the
         *   record did not, and this reader, reading the preferences file
         *   directly so the shade stays instant, cannot tell a live capture from
         *   a dead one except by the window — so past it nothing is watching and
         *   the failsafe time fronts (Codex, PR #221, #278).
         */
        /**
         * Whether the cap sits below its 8-hour ceiling — a shortened deadline
         * the tile must front even behind an armed exit ([capTimeFronted]'s
         * second disjunct).
         *
         * A record written before `cap_ceiling_at` existed carries none
         * ([storedCeilingMillis] `0`), and the ceiling then derives to
         * `startedAt + DEFAULT_CAP` — the exact fallback
         * `ActiveSnoozeStore.read()` uses. Reading a missing ceiling as `0`
         * instead made every legacy record look un-shortened, hiding a chosen
         * deadline the model still recognizes on an update that restores an
         * old running record (Codex, PR #281).
         */
        internal fun capBelowCeiling(
            capExpiresAtMillis: Long,
            storedCeilingMillis: Long,
            startedAtMillis: Long,
        ): Boolean {
            val ceiling =
                if (storedCeilingMillis != 0L) storedCeilingMillis
                else startedAtMillis + ActiveSnooze.DEFAULT_CAP.toMillis()
            return capExpiresAtMillis < ceiling
        }

        internal fun claimsTimerOnly(
            stored: String?,
            startedAtMillis: Long,
            nowMillis: Long,
            endsOnDeparture: Boolean = true,
            endsOnMotion: Boolean = false,
            timerOnlyRequested: Boolean = false,
            capBelowCeiling: Boolean = false,
        ): Boolean {
            val tracksDeparture: Boolean =
                if (!endsOnDeparture) {
                    false
                } else when (TrackingMode.entries.firstOrNull { it.name == stored }) {
                    TrackingMode.FULL, TrackingMode.WIFI_ONLY, TrackingMode.WIFI_GRACE -> true
                    TrackingMode.DURATION_ONLY -> false
                    TrackingMode.SETTLING ->
                        TrackingMode.settlingStillStands(startedAtMillis, nowMillis)
                    null -> false
                }
            return capTimeFronted(
                timerOnlyRequested = timerOnlyRequested,
                capBelowCeiling = capBelowCeiling,
                endsOnMotion = endsOnMotion,
                tracksDeparture = tracksDeparture,
            )
        }

        fun read(context: Context): TileSnapshot {
            val prefs = context.getSharedPreferences("active_snooze", Context.MODE_PRIVATE)
            val capExpiresAt = prefs.getLong("cap_expires_at", 0L)
            // A record whose snooze ended but whose erase failed is still on
            // disk, marked. The tile must read that as "not snoozing" like
            // everything else, or it offers `End now` for a snooze that is over.
            val released = prefs.getBoolean("released", false)
            val storedMode = prefs.getString("mode", null)
            val startedAt = prefs.getLong("started_at", 0L)
            return TileSnapshot(
                snoozing = capExpiresAt != 0L && !released,
                capExpiresAtMillis = capExpiresAt,
                timerOnly = claimsTimerOnly(
                    stored = storedMode,
                    startedAtMillis = startedAt,
                    nowMillis = System.currentTimeMillis(),
                    endsOnDeparture = prefs.getBoolean("ends_on_departure", true),
                    endsOnMotion = prefs.getBoolean("ends_on_motion", false),
                    timerOnlyRequested = prefs.getBoolean("timer_only_requested", false),
                    capBelowCeiling = capBelowCeiling(
                        capExpiresAtMillis = capExpiresAt,
                        storedCeilingMillis = prefs.getLong("cap_ceiling_at", 0L),
                        startedAtMillis = startedAt,
                    ),
                ),
                endsOnMotion = prefs.getBoolean("ends_on_motion", false),
                // Kept whole for the grace rendering (see [mode]); an
                // unrecognized value is `null`, which never matches WIFI_GRACE
                // and so falls through to the exit branches like any other
                // non-grace mode.
                mode = TrackingMode.entries.firstOrNull { it.name == storedMode },
                // With [mode] this decides whether grace is *active*
                // ([subtitleKind]); missing reads as `true`, the record's own
                // default.
                endsOnDeparture = prefs.getBoolean("ends_on_departure", true),
            )
        }
    }
}
