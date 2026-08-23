package app.snoozemo.presence.geofence

import android.content.Context
import app.snoozemo.core.ClockReading

/**
 * Persists [app.snoozemo.core.PresenceState.graceDeadlineMs] across process
 * death (TODO.md, "the grace deadline has to survive process death"): the
 * real [GraceAlarm] already does, since it's an `AlarmManager` entry, but the
 * engine's own memory of the deadline is a plain in-memory value that a fresh
 * [app.snoozemo.presence.PresenceFeed] otherwise loses on every restart.
 *
 * The deadline itself is stored as a wall-clock instant, not the
 * elapsed-realtime value the engine and the platform alarm both use — wall
 * time is the one frame that survives not just process death but a reboot
 * too, so [load] can hand back a same-boot restore and a post-reboot one
 * through the identical arithmetic, with no separate reboot path to get
 * wrong.
 *
 * **Deliberately without `ActiveSnooze.bootReference`'s dual-frame defense**
 * against a backwards clock change. That defense exists because the
 * duration cap has nothing above it — it is the backstop. The grace
 * deadline is not: it is a *soft* mechanism that the cap already bounds
 * regardless of what this store says, so a clock wound back during an
 * outage can make grace run longer than five minutes, but never longer than
 * the cap. Matching the cap's full mechanism for a value the cap already
 * backstops was judged not worth the second reboot-reconciliation path.
 *
 * **Keyed to the arm moment, not just present** (Codex, PR #91). A snooze
 * that ends *during* its own grace period — presence confirmed, or a manual
 * end — must not leave this store's entry for a process death between the
 * record's own erase and [GeofencePresenceMonitor.stop]'s [clear] to hand a
 * brand-new, unrelated snooze someone else's deadline: the next arm would
 * seed a grace period nobody started and could end it early on an alarm or a
 * held mailbox observation that belongs to the snooze before it.
 *
 * The identity itself is [PresenceMonitor.start]'s `armedAtEpochMs` —
 * `ActiveSnooze.startedAt` — compared **directly**, with no clock-frame
 * translation of its own (Codex, second pass on PR #91): an earlier version
 * derived it from `sinceElapsedRealtimeMs` the same way the deadline is
 * derived, but that value resets at reboot and drifts under a mid-snooze
 * clock-change restatement, so the *same* snooze could compare unequal to
 * itself across a restart. `armedAtEpochMs` is wall-clock already, set once
 * at genuine arm time and never recomputed — the one field `ActiveSnooze`
 * itself trusts as identity, for the same reason (`retryStillApplies`).
 */
internal object GraceDeadlineStore {
    private const val PREFS_NAME = "presence_grace"
    private const val KEY_DEADLINE_WALL_MS = "deadline_wall_ms"
    private const val KEY_ARMED_AT_EPOCH_MS = "armed_at_epoch_ms"

    /**
     * Records [deadlineElapsedMs] (or clears it, if null) as read at [now],
     * identified by [armedAtEpochMs] — the snooze's wall-clock arm moment —
     * so a later [load] for a *different* snooze cannot mistake this for its
     * own. Returns whether the write actually reached disk.
     *
     * `commit`, not `apply` (Codex, third pass on PR #91) — the same choice
     * `ActiveSnoozeStore.save` makes and for the same reason. `apply` returns
     * before the write is durable, so ordering this call before
     * [GraceAlarm.reconcile] proved nothing on its own: a process death
     * between the still-pending write and the alarm arming left exactly the
     * gap that ordering was meant to close. The caller only arms the alarm
     * on a confirmed `true`.
     */
    fun save(context: Context, deadlineElapsedMs: Long?, armedAtEpochMs: Long, now: ClockReading): Boolean {
        val prefs = prefs(context)
        if (deadlineElapsedMs == null) {
            return prefs.edit().clear().commit()
        }
        return prefs.edit()
            .putLong(KEY_DEADLINE_WALL_MS, wallMsFor(deadlineElapsedMs, now))
            .putLong(KEY_ARMED_AT_EPOCH_MS, armedAtEpochMs)
            .commit()
    }

    /**
     * The persisted deadline restated into [now]'s elapsed-realtime frame —
     * the current boot's, whichever boot [now] belongs to — or null if
     * nothing is persisted **for this snooze**: [armedAtEpochMs] must equal
     * the arm moment [save] recorded the entry under, or the entry belongs
     * to a snooze that has since ended and is not this one's to resume.
     */
    fun load(context: Context, armedAtEpochMs: Long, now: ClockReading): Long? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_DEADLINE_WALL_MS) || !prefs.contains(KEY_ARMED_AT_EPOCH_MS)) return null
        if (prefs.getLong(KEY_ARMED_AT_EPOCH_MS, 0L) != armedAtEpochMs) return null
        return elapsedMsFor(prefs.getLong(KEY_DEADLINE_WALL_MS, 0L), now)
    }

    /** Returns whether the clear actually reached disk — see [save]'s own comment on why that matters. */
    fun clear(context: Context): Boolean = prefs(context).edit().clear().commit()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The pure half of this store — no [Context], so a plain JUnit test can
     * pin the arithmetic without the Android test harness `:presence` has no
     * other reason to carry (the same trade-off `TilePresenceStoreTest`'s own
     * comment makes explicit, in the other direction: there, the store is
     * public and the test rides `:app`'s existing Robolectric; here, the
     * store is `internal` to this flavor source set and small enough that
     * proving the frame conversion round-trips is the whole of what a test
     * needs to cover). Only the deadline needs this — [armedAtEpochMs] is
     * wall-clock already and compared with no arithmetic at all.
     */
    internal fun wallMsFor(deadlineElapsedMs: Long, now: ClockReading): Long =
        now.wallMillis + (deadlineElapsedMs - now.uptimeMillis)

    internal fun elapsedMsFor(deadlineWallMs: Long, now: ClockReading): Long =
        now.uptimeMillis + (deadlineWallMs - now.wallMillis)
}
