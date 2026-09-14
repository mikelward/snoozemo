package app.snoozemo.tile

import android.content.Context
import android.text.format.DateFormat
import app.snoozemo.core.TrackingMode
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
     * also switch on.
     */
    val mode: TrackingMode? = null,
) {

    /** What the tile's subtitle says how this snooze ends. */
    internal enum class SubtitleKind { NONE, UNTIL_TIME, UNTIL_LEAVE, UNTIL_MOVE, ENDING_SOON }

    /**
     * Which subtitle the shade says, decided without a `Context` so it is unit-
     * tested directly; [subtitle] and [stateDescription] map it to strings.
     *
     * A running snooze always names how it ends: the chosen end time for a timer,
     * or the event exit for a watched one. The watched exits carry no *time* —
     * their cap is the passive eight-hour failsafe, and fronting it would promise
     * a deadline the snooze does not expect to reach (SPEC.md §4.2) — so they
     * name the exit instead. [timerOnly] is the tile's read of whether to front
     * the time — `ActiveSnooze.capCountdownShown` (a chosen time, a shortened
     * cap, or a settled failsafe), not the narrower `capIsEffectiveEnd`, so a
     * chosen time still fronts when an exit is left armed (see [claimsTimerOnly]).
     * A movement exit is `Until you move`, and any other watched snooze is
     * `Until you leave`.
     *
     * **Wi-Fi grace is checked ahead of the exits**, exactly as the screen and
     * the notification do (`motionOnly = endsOnMotion && mode != WIFI_GRACE`).
     * A grace period is a five-minute deadline that ends the snooze on expiry
     * whether or not the phone ever leaves or moves, so naming either exit would
     * put a claim the user could see disproved minutes later — the screen and
     * the notification say `Wi-Fi lost — ending soon` for that reason, and the
     * tile says `Ending soon` (SPEC.md §4.2, Codex PR #281).
     */
    internal val subtitleKind: SubtitleKind get() = when {
        !snoozing -> SubtitleKind.NONE
        timerOnly -> SubtitleKind.UNTIL_TIME
        mode == TrackingMode.WIFI_GRACE -> SubtitleKind.ENDING_SOON
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
         * Whether the record is a settled claim that nothing is watching.
         *
         * **Any event exit at all disqualifies the claim.** `Timer only` says
         * nothing but the clock can end this snooze, so the movement exit
         * counts as much as departure does — and a time chosen *then*
         * `Until I move` is a state this change made newly reachable, which
         * the tile alone rendered as `Timer only` while the notification and
         * the screen both named the movement exit (Codex, PR #267).
         *
         * **The user's choice first, then the mode.** `ends_on_departure` is
         * the tile's half of [app.snoozemo.core.ActiveSnooze.effectiveMode]:
         * a snooze narrowed to its timer keeps the capability `mode` it was
         * tracking with, deliberately, so `Until I leave` can put the exit
         * back — and reading the mode alone therefore left the shade showing
         * an unqualified countdown, as though a departure were still watched,
         * while the screen and the notification said `Timer only` (Codex,
         * PR #267). Missing reads as `true`, matching the record's own
         * default: a record written before the flag existed comes from a build
         * where leaving always ended a snooze.
         *
         * Parsed to the enum and decided by an exhaustive `when` rather than
         * compared to the string `"FULL"`, which is how the tile came to
         * report `Timer only` for a mode that means the opposite. The mode is
         * persisted as text, so the compiler cannot see this reader from
         * [TrackingMode]'s declaration: a member added there used to reach the
         * shade as a silent misreading, while the screen and the notification
         * — which switch on the enum — failed to compile until they were
         * taught. Parsing first puts this reader under the same rule, so the
         * next member is a build error here too (Codex, PR #221).
         *
         * A missing or unrecognized value degrades to timer-only, matching
         * `ActiveSnoozeStore`'s own fallback for the same input — a record
         * written before the mode was stored, or by a newer build than this
         * tile. Reading it as "no claim" was wrong twice over: the store
         * already answers `DURATION_ONLY`, so the app and the shade would
         * contradict each other over one record, and the tile has only two
         * renderings — dropping the qualifier is not silence, it is the
         * tracked-looking one (Codex, PR #221).
         *
         * A settling mode is the one case where dropping it is right, because
         * something genuinely is pending. Nothing is pending for a value nobody
         * can read.
         */
        internal fun claimsTimerOnly(
            stored: String?,
            startedAtMillis: Long,
            nowMillis: Long,
            endsOnDeparture: Boolean = true,
            endsOnMotion: Boolean = false,
            timerOnlyRequested: Boolean = false,
            capBelowCeiling: Boolean = false,
        ): Boolean =
            if (timerOnlyRequested || capBelowCeiling) {
                // A chosen time always fronts, even with an exit still armed —
                // it can end the snooze before the exit fires, and naming only
                // the residual exit would hide a deadline the user set. Two
                // ways a chosen cap coexists with an armed exit: the durable
                // PARTIAL state (`timerOnlyRequested` — an exit-removal write
                // failed, or the process died mid-replacement), and a cap
                // shortened below its ceiling (`capBelowCeiling`) that repeated
                // `+30 min` can clamp back up while it is still a time the user
                // set. These are `ActiveSnooze.capCountdownShown`'s first two
                // disjuncts; the tile front-loads them ahead of every exit so
                // it never claims a sole exit over a chosen deadline (Codex,
                // PR #281). Kept as the tile's own re-derivation rather than the
                // model property because the tile diverges from it deliberately
                // on SETTLING below; the shared-decision question is in
                // `TODO.md`.
                true
            } else if (endsOnMotion) {
                // An armed movement exit ends this snooze on something other
                // than the clock, whatever the mode says and whatever the
                // departure choice was. Asked after the chosen time, because a
                // time the user set can still end the snooze before it.
                false
            } else if (!endsOnDeparture) {
                // Nothing pending and nothing to wait for: the user answered
                // this question themselves, so no mode — a settling one
                // included — can soften it.
                true
            } else when (TrackingMode.entries.firstOrNull { it.name == stored }) {
                TrackingMode.DURATION_ONLY -> true
                // Watched, by something, so it names the exit rather than a time.
                TrackingMode.FULL, TrackingMode.WIFI_ONLY, TrackingMode.WIFI_GRACE -> false
                // The anchor has not landed *yet*, so this is not a settled
                // `Timer only` claim: `false`, which names the exit rather than a
                // time ([subtitleKind]). That is
                // the same as every other surface while the cap is the failsafe
                // rather than the plan — during settling the cap is
                // `startedAt + DEFAULT_CAP`, the backstop, not a chosen deadline,
                // so fronting a countdown to it would front a plan the snooze has
                // not made (SPEC.md §4.2, §491-494). The ongoing notification
                // beside the tile names what the arm is waiting on. (This reader
                // used to return an unqualified countdown here; the
                // drop-the-failsafe decision made the three surfaces agree, and a
                // tile countdown during settling was the one that no longer did —
                // Codex, PR #278.)
                //
                // Past the window the claim is settled: a capture dies with its
                // process while the record does not, and this reader — reading the
                // preferences file directly so the shade stays instant — cannot
                // tell a live capture from a dead one except by the window. Past
                // it, nothing is watching, which is exactly `Timer only` (Codex,
                // PR #221).
                TrackingMode.SETTLING ->
                    !TrackingMode.settlingStillStands(startedAtMillis, nowMillis)
                null -> true
            }

        fun read(context: Context): TileSnapshot {
            val prefs = context.getSharedPreferences("active_snooze", Context.MODE_PRIVATE)
            val capExpiresAt = prefs.getLong("cap_expires_at", 0L)
            // A record whose snooze ended but whose erase failed is still on
            // disk, marked. The tile must read that as "not snoozing" like
            // everything else, or it offers `End now` for a snooze that is over.
            val released = prefs.getBoolean("released", false)
            val storedMode = prefs.getString("mode", null)
            // The 8-hour backstop this cap may not be pushed past. A record
            // written before the ceiling was stored carries none (0), which
            // reads as "not shortened" — the honest default, since without a
            // ceiling to compare against there is no chosen-time signal here.
            val capCeilingAt = prefs.getLong("cap_ceiling_at", 0L)
            return TileSnapshot(
                snoozing = capExpiresAt != 0L && !released,
                capExpiresAtMillis = capExpiresAt,
                timerOnly = claimsTimerOnly(
                    stored = storedMode,
                    startedAtMillis = prefs.getLong("started_at", 0L),
                    nowMillis = System.currentTimeMillis(),
                    endsOnDeparture = prefs.getBoolean("ends_on_departure", true),
                    endsOnMotion = prefs.getBoolean("ends_on_motion", false),
                    timerOnlyRequested = prefs.getBoolean("timer_only_requested", false),
                    capBelowCeiling = capCeilingAt != 0L && capExpiresAt < capCeilingAt,
                ),
                endsOnMotion = prefs.getBoolean("ends_on_motion", false),
                // Kept whole for the grace rendering (see [mode]); an
                // unrecognized value is `null`, which never matches WIFI_GRACE
                // and so falls through to the exit branches like any other
                // non-grace mode.
                mode = TrackingMode.entries.firstOrNull { it.name == storedMode },
            )
        }
    }
}
