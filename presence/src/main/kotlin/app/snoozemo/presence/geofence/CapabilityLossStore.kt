package app.snoozemo.presence.geofence

import android.content.Context
import app.snoozemo.core.CapabilityLossCause

/**
 * Persists a decided [CapabilityLossCause] across process death and reboot
 * (TODO.md; maintainer decision following Codex's ninth pass on PR #91).
 * Ending a snooze via `trySend(PresenceUpdate(event =
 * PresenceEvent.CapabilityLost(...)))` alone is only an in-process `Flow`
 * send — if the process dies before `SnoozeService`'s collector actually
 * consumes it, the decision is lost and a restore starts fresh believing the
 * snooze is still healthy, which is exactly the failure the whole grace
 * deadline item (this PR's parent) exists to close for the other ending path.
 *
 * Deliberately simpler than [GraceDeadlineStore]: a cause has no time
 * component, so there is no wall/elapsed frame to translate across a boot —
 * the value is either present for this snooze or it isn't.
 *
 * **Keyed to the arm moment, exactly like [GraceDeadlineStore]** and for the
 * same reason: a snooze that ends before this store's own [clear] runs must
 * not hand a brand-new, unrelated snooze a decision that was never its own.
 */
internal object CapabilityLossStore {
    private const val PREFS_NAME = "presence_capability_loss"
    private const val KEY_CAUSE = "cause"
    private const val KEY_ARMED_AT_EPOCH_MS = "armed_at_epoch_ms"

    /**
     * Records [cause] for the snooze identified by [armedAtEpochMs]. Returns
     * whether the write actually reached disk — `commit`, not `apply`, for
     * the same reason [GraceDeadlineStore.save] gives: the caller only arms
     * the wake-up alarm on a confirmed write.
     */
    fun save(context: Context, cause: CapabilityLossCause, armedAtEpochMs: Long): Boolean =
        prefs(context).edit()
            .putString(KEY_CAUSE, cause.name)
            .putLong(KEY_ARMED_AT_EPOCH_MS, armedAtEpochMs)
            .commit()

    /**
     * The persisted cause for **this** snooze, or null if none is recorded —
     * [armedAtEpochMs] must equal the arm moment [save] recorded the entry
     * under, or the entry belongs to a snooze that has since ended and is
     * not this one's to inherit.
     */
    fun load(context: Context, armedAtEpochMs: Long): CapabilityLossCause? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_CAUSE) || !prefs.contains(KEY_ARMED_AT_EPOCH_MS)) return null
        if (prefs.getLong(KEY_ARMED_AT_EPOCH_MS, 0L) != armedAtEpochMs) return null
        // The identity check above already proved this record is this
        // snooze's own ending decision — a version rollback that no longer
        // recognizes the saved name must not turn that into "no decision
        // recorded" (Codex, PR #95, sixth pass): that reads as a healthy
        // snooze and leaves DND on until the duration cap, principle 1's
        // exact failure. Fail open to the generic cause instead — the
        // decision to end was already made, only its detail is unreadable.
        val name = prefs.getString(KEY_CAUSE, null) ?: return null
        return runCatching { CapabilityLossCause.valueOf(name) }
            .getOrDefault(CapabilityLossCause.MONITORING_UNAVAILABLE)
    }

    /** Returns whether the clear actually reached disk — see [save]'s own comment on why that matters. */
    fun clear(context: Context): Boolean = prefs(context).edit().clear().commit()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
