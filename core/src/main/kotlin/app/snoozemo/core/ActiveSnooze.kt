package app.snoozemo.core

import java.time.Duration
import java.time.Instant

/**
 * Where "here" was when the tile was tapped (SPEC.md §6.2).
 *
 * The SSID — never the BSSID — is the Wi-Fi anchor: in any building with a mesh
 * or several access points the BSSID changes as the phone roams between them
 * while the user has obviously not gone anywhere, which would produce constant
 * false departures in exactly the large venues the app is most useful in. The
 * BSSID is carried for diagnostics only.
 */
data class Anchor(
    val lat: Double? = null,
    val lon: Double? = null,
    val fixAccuracyM: Float? = null,
    val capturedAt: Instant,
    val ssid: String? = null,
    val bssid: String? = null,
    val radiusM: Int = DEFAULT_RADIUS_M,
) {
    /**
     * Whether this anchor has coordinates precise enough to test departures
     * against. A fix vaguer than this says "somewhere in the neighborhood",
     * which cannot distinguish leaving from standing still, so an anchor that
     * fails this runs Wi-Fi-only rather than pretending to track (SPEC.md §8.4).
     */
    val hasUsableFix: Boolean
        get() = lat != null && lon != null &&
            fixAccuracyM != null && fixAccuracyM <= MAX_ANCHOR_ACCURACY_M

    companion object {
        const val DEFAULT_RADIUS_M: Int = 150

        /** Discard coordinates vaguer than this when capturing an anchor. */
        const val MAX_ANCHOR_ACCURACY_M: Float = 200f
    }
}

/**
 * The persisted record of a running snooze, written on every state transition so
 * process death is fully recoverable (SPEC.md §11).
 *
 * [capExpiresAt] is absolute rather than a duration-from-start: a reboot must not
 * extend a snooze, so the cap continues from its original start time however many
 * times the process dies in between (SPEC.md §8.3).
 */
data class ActiveSnooze(
    val anchor: Anchor,
    val startedAt: Instant,
    val capExpiresAt: Instant,
    // Deliberately has no default. A snooze whose anchor capture timed out would
    // otherwise be recorded as FULL and restored claiming healthy tracking, and
    // the mode is exactly what the notification's degraded line is written from
    // (SPEC.md §8.1) — the wrong value here is a silent failure, not a cosmetic
    // one. Callers state it, or derive it with [TrackingMode.from].
    val mode: TrackingMode,
    val placeName: String = DEFAULT_PLACE_NAME,
    /**
     * The [ClockReading.bootReference] in force when this record was written, or
     * null for a record that carries none.
     *
     * This is what lets [capExpiresAt] be read in a frame the user cannot move.
     * Wall time is the only frame that survives a reboot, so the deadline is
     * stored in it — but the wall clock is also the one thing standing between a
     * snooze and its cap that a user can wind backwards, and doing so while no
     * process was alive to notice used to keep Do Not Disturb on indefinitely.
     * Stored here, the offset converts that deadline back into uptime, which
     * nothing can move (see [remaining]).
     *
     * Nullable because records written before this field existed don't carry
     * one, and because a store that cannot read it must say so rather than
     * invent one: a *wrong* offset is worse than none, since it would be
     * believed. Null degrades to wall-clock-only, which is the behavior this
     * improves on rather than replaces.
     */
    val bootReference: Long? = null,
    /**
     * The absolute instant `+30 min` may not extend past — the 8-hour backstop
     * of SPEC.md §7, carried in the record rather than derived from [startedAt].
     *
     * Derived, it was correct right up until [reconciledOnto] moved the
     * deadline. That rewrites [capExpiresAt] into the wall frame the user has
     * just set while [startedAt] stays where it is, because [startedAt] is the
     * snooze's identity (see [retryStillApplies]) and moving it would strand
     * every retry queued against the old value. So after a backwards change the
     * two no longer share a frame, and `startedAt + DEFAULT_CAP` names an
     * instant the shift put hours beyond the real backstop: six taps of `+30
     * min` on a three-hour shift walk an eight-hour snooze to eleven, which is
     * the backstop failing at exactly the button it exists to bound.
     *
     * Stored, it moves with the deadline and keeps naming the same real moment.
     * Defaults to the derivation for every caller that doesn't set it, which is
     * what a record written before this field existed also falls back to.
     */
    val capCeilingAt: Instant = startedAt.plus(DEFAULT_CAP),
) {
    /**
     * How long is left before the cap fires, floored at zero. Never negative: an
     * overdue cap is expressed as [isExpired], not as a negative countdown that a
     * caller might format into the notification as "ends in -4m".
     */
    fun remaining(now: ClockReading): Duration =
        Duration.ofMillis(remainingMillis(now).coerceAtLeast(0L))

    /**
     * Whether the cap has fired. Inclusive of the exact instant, so a cap that
     * lands precisely on `now` ends the snooze — the fail-open direction
     * (SPEC.md D7).
     *
     * A deadline further off than [MAX_CAP] counts as fired too, and that is the
     * same direction rather than a separate rule. No snooze can legitimately
     * have more than the maximum cap left: it is set to at most that at arm, and
     * `+30 min` cannot push past it. So a record claiming more is not a long
     * snooze — it is a deadline whose frame of reference is gone, which is what
     * a record with no [bootReference] looks like after the clock is wound back.
     * How much real time actually passed is then unknowable, and principle 1
     * settles what to do about it: a snooze that ends early is a small
     * annoyance, one that never ends is the product failing.
     */
    fun isExpired(now: ClockReading): Boolean {
        val left = remainingMillis(now)
        return left <= 0L || left > MAX_CAP.toMillis()
    }

    /**
     * Milliseconds until the cap, which may be negative — the shared arithmetic
     * behind [remaining] and [isExpired], so the two can never disagree about
     * whether a snooze is over.
     *
     * The answer is the **smaller** of what the two clocks say, and that single
     * choice is what makes the cap hold across every way the clocks can lie:
     *
     * - **Same boot, clock untouched.** Both frames agree. Either answer is
     *   right.
     * - **Clock wound back while this process was dead** — the failure this
     *   exists for. Wall time says the deadline is further away than it is;
     *   uptime is unaffected and exact, and it is the smaller of the two.
     * - **Clock wound forward.** Wall time says the deadline is closer than it
     *   is, so this reports the snooze over before its real duration is up. Only
     *   ever shortening a snooze, a forward change is the harmless direction —
     *   but the alarm counts in uptime and does *not* move with it, so the
     *   countdown would sit at zero over a phone still silent. `TIME_SET` is
     *   what closes that gap (SPEC.md §7).
     * - **Across a reboot.** Uptime reset, so the stored offset no longer
     *   describes this boot and the uptime answer is meaningless — though never
     *   *smaller* than the truth, since the uptime spent before the reboot is
     *   time it cannot see. Wall time is exact here and wins the comparison.
     *   **Only while the wall clock is untouched**, which is why [rebasedOnto]
     *   exists: a reboot followed by a backwards change leaves both answers too
     *   high, and the minimum of two overestimates is still an overestimate.
     *   Restating the offset for the new boot is what restores a clock the user
     *   cannot move; without it the cap overruns by the size of the shift.
     *
     * So the minimum is exact whenever either clock is trustworthy, and errs
     * toward ending the snooze in the cases where neither is — given that every
     * reboot restates the offset on its way past.
     */
    private fun remainingMillis(now: ClockReading): Long {
        val byWallClock = capExpiresAt.toEpochMilli() - now.wallMillis
        val reference = bootReference ?: return byWallClock
        val byUptime = (capExpiresAt.toEpochMilli() - reference) - now.uptimeMillis
        return minOf(byWallClock, byUptime)
    }

    /**
     * The same snooze with its clock offset restated for the boot [now] belongs
     * to — what the boot receiver writes back before it re-arms anything.
     *
     * A reboot resets uptime, so the stored offset stops describing the device
     * and the deadline is left with only the wall clock to be read against. That
     * is fine right up until the wall clock moves, and then nothing is left to
     * catch it: a phone rebooted mid-snooze and then wound back keeps Do Not
     * Disturb on past the cap by exactly the size of the shift, which is the
     * invariant this whole mechanism exists to hold.
     *
     * Only the offset changes. [capExpiresAt] is already in wall time and wall
     * time is the trustworthy frame at this moment — a reboot does not move it —
     * so restating the offset is the whole repair, and it deliberately does not
     * recompute the deadline. Doing so would bake this instant's wall reading
     * into the record and make an already-overdue cap look fresh.
     *
     * What this cannot cover is a clock moved *while the phone was off*, before
     * anything ran to notice. Wall time is then already wrong when it arrives
     * here and there is no second frame left to check it against. The `MAX_CAP`
     * ceiling in [isExpired] is the only remaining backstop, and a shift smaller
     * than that is not detectable at all — see `TODO.md`, which tracks this
     * alongside the locked-boot case it shares a cause with.
     */
    fun rebasedOnto(now: ClockReading): ActiveSnooze =
        copy(bootReference = now.bootReference)

    /**
     * The same snooze with **both** frames restated onto [now] — the deadline
     * rewritten to the time actually left, and the offset stamped to match.
     *
     * The counterpart to [rebasedOnto], and the difference between them is which
     * clock is trustworthy at the moment each is used. At boot, wall time is
     * sound and only the offset is stale, so [rebasedOnto] leaves the deadline
     * alone. When the user *sets the clock*, it is the other way round: wall time
     * has just become wrong by however far it moved, and uptime is the only frame
     * still carrying the truth.
     *
     * That truth lives in memory only — it is the difference between the stored
     * offset and the current uptime, and a reboot resets uptime and destroys it.
     * So a backwards change followed by a reboot would otherwise lose it
     * completely: the boot would find an untouched, now-inflated wall deadline,
     * rebase onto it, and hold Do Not Disturb open by exactly the size of the
     * shift. Writing the remaining time back into wall time while wall time can
     * still be read is what survives the reboot.
     *
     * A forward change is a no-op here by construction: wall time is already the
     * smaller of the two, so the recomputed deadline lands where it was.
     *
     * **Null when there is no [bootReference] to reconcile from**, rather than a
     * copy of the same deadline with this reading's offset stamped on it. Such a
     * record has only wall time to be read against, and wall time is precisely
     * what has just moved: the "remaining" that would be folded back in is the
     * moved reading itself, so the write preserves the error exactly and then
     * labels it with a frame it was never measured in. What the next reader
     * would find is a deadline that looks framed and is not, which is worse than
     * one that admits it carries none. [ClockChange] ends the snooze instead.
     */
    fun reconciledOnto(now: ClockReading): ActiveSnooze? {
        bootReference ?: return null
        val restated = Instant.ofEpochMilli(now.wallMillis + remainingMillis(now))
        // Every wall-frame instant the cap is judged in moves by the same
        // amount, or they stop describing the same snooze. [capCeilingAt] is
        // the other one; [startedAt] is deliberately not, since it is the
        // identity rather than a deadline (see [capCeilingAt]).
        val shift = Duration.between(capExpiresAt, restated)
        return copy(
            capExpiresAt = restated,
            capCeilingAt = capCeilingAt.plus(shift),
            bootReference = now.bootReference,
        )
    }

    /**
     * Where the cap lands if the notification's `+30 min` is tapped (SPEC.md
     * §4.3), clamped to [capCeilingAt] — repeated taps may not walk a snooze
     * past the backstop.
     *
     * The ceiling is the **8-hour default**, not [MAX_CAP]. SPEC.md §7 is
     * explicit: a time chosen in the end-condition sheet only ever *lowers* the
     * cap, "the 8-hour default remains an absolute backstop above any chosen
     * value, and `+30 min` may not push past it." Clamping to [MAX_CAP] instead
     * let sixteen taps walk a default snooze from 8 hours to 24 — sixteen hours
     * of silence past the backstop the whole design leans on, reached by a
     * button whose only job is to add half an hour.
     *
     * Stated as a ceiling rather than a subtraction so a cap already *above* the
     * default — which only a per-place setting could produce, and none exists
     * yet — declines to extend rather than jumping backwards.
     *
     * Returns [capExpiresAt] unchanged when the ceiling is already reached, so a
     * caller can tell "extended" from "cannot extend" by comparison rather than
     * re-deriving the clamp and getting it subtly different.
     */
    fun extendedCap(by: Duration): Instant {
        val ceiling = capCeilingAt
        val extended = capExpiresAt.plus(by)
        return if (extended.isAfter(ceiling)) maxOf(capExpiresAt, ceiling) else extended
    }

    companion object {
        /** Shown until saved places land and the anchor can be named. */
        const val DEFAULT_PLACE_NAME: String = "Here"

        /** The cap the user gets without choosing one (SPEC.md §7). */
        val DEFAULT_CAP: Duration = Duration.ofHours(8)

        /**
         * The ceiling on a cap the user asks for — the top of the configurable
         * 30 min – 24 h range (SPEC.md §7).
         *
         * Not the ceiling `+30 min` clamps to: that is [DEFAULT_CAP], because
         * extending is not choosing a cap. See [extendedCap].
         */
        val MAX_CAP: Duration = Duration.ofHours(24)

        /** The floor the sheet's `−` may not go below. */
        val MIN_CAP: Duration = Duration.ofMinutes(30)

        /**
         * The absolute instant a snooze started at [now] with a requested
         * [cap] should end, clamped to [MIN_CAP]–[MAX_CAP].
         *
         * Exists so the cap is settled **once**, before anything is scheduled or
         * recorded. The alarm and the record have to name the same moment: two
         * derivations from two clock readings put them milliseconds apart, and
         * an alarm that fires just before its own record counts as expired is a
         * spent alarm and a snooze with no duration exit.
         */
        fun capExpiryFor(now: Instant, cap: Duration = DEFAULT_CAP): Instant =
            now.plus(cap.coerceIn(MIN_CAP, MAX_CAP))

        /** [capExpiryFor] against the wall half of a reading of both clocks. */
        fun capExpiryFor(now: ClockReading, cap: Duration = DEFAULT_CAP): Instant =
            capExpiryFor(Instant.ofEpochMilli(now.wallMillis), cap)

        /**
         * Whether a retry queued for the snooze that started at [queuedFor] is
         * still entitled to act on the record now on disk ([onDisk]).
         *
         * Both retries the app schedules — erasing a released record, and
         * ending a snooze whose cap could not be rescheduled — can outlive the
         * snooze they were queued for: the alarm behind each is durable, the
         * process is not, and the user can arm a *new* snooze before either
         * fires. Both would then act on the wrong snooze. A stale erase deletes
         * the new record, taking its cap with it while its zen rule stays on —
         * a phone left quiet with nothing scheduled to bring it back, produced
         * by the mechanism meant to prevent exactly that. A stale release ends
         * the snooze the user just armed, and explains it as a reboot that
         * couldn't resume, which never happened.
         *
         * [startedAt] is the identity, because it is the one field that is fixed
         * for a snooze's whole life: the cap moves with `+30 min`, the mode and
         * the anchor change as tracking degrades.
         *
         * Both nulls mean "nothing to disagree with" and answer true — an absent
         * record has nothing to protect, and an unidentified retry is one no
         * caller can currently produce, so refusing it would only strand a
         * record that a later cold start would restore.
         */
        fun retryStillApplies(onDisk: ActiveSnooze?, queuedFor: Instant?): Boolean =
            onDisk == null || queuedFor == null || onDisk.startedAt == queuedFor
    }
}
