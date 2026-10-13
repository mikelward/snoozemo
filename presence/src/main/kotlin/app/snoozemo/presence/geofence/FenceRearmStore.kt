package app.snoozemo.presence.geofence

import android.content.Context
import app.snoozemo.core.FenceRearm

/**
 * Persists [FenceRearm.left], so the budget of geofence re-registrations after
 * a false alarm is per snooze rather than per process (SPEC.md §6.10). Without
 * it, every restore would start a fresh budget, and a platform stuck believing
 * the phone is outside could spend a checking burst per round for the whole
 * snooze.
 *
 * Keyed to the arm moment like [GraceDeadlineStore], and for the same reason:
 * a new snooze starts with a full budget rather than inheriting what the one
 * before it spent. The monitor writes it only from the generation that owns
 * persistence, so a retired flow cannot write over a newer snooze's.
 */
internal object FenceRearmStore {
    private const val PREFS_NAME = "presence_fence_rearm"
    private const val KEY_LEFT = "left"
    private const val KEY_ARMED_AT_EPOCH_MS = "armed_at_epoch_ms"

    /** What this snooze has left to spend, or the full budget if nothing is stored for it. */
    fun load(context: Context, armedAtEpochMs: Long): Int {
        val prefs = prefs(context)
        if (prefs.getLong(KEY_ARMED_AT_EPOCH_MS, 0L) != armedAtEpochMs) return FenceRearm.BUDGET
        return prefs.getInt(KEY_LEFT, FenceRearm.BUDGET).coerceIn(0, FenceRearm.BUDGET)
    }

    /**
     * Records [left] for this snooze.
     *
     * `apply`, unlike [GraceDeadlineStore.save]'s `commit`: a write lost to a
     * process death restores a larger budget, which costs at most one more
     * checking burst and never a missed departure. That is not worth a
     * synchronous disk write on the thread that delivered the false alarm,
     * which is sometimes the main one.
     */
    fun save(context: Context, armedAtEpochMs: Long, left: Int) {
        prefs(context).edit()
            .putLong(KEY_ARMED_AT_EPOCH_MS, armedAtEpochMs)
            .putInt(KEY_LEFT, left)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
