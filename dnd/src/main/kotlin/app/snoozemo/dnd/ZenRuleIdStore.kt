package app.snoozemo.dnd

import android.content.Context

/**
 * Remembers the id of Snoozemo's one long-lived zen rule (SPEC.md §5.3).
 *
 * An interface so the controller is testable without Android, and because the
 * storage is likely to move to DataStore alongside the rest of the settings —
 * the id is small, read on every start, and read on the arm path, which is
 * exactly the kind of thing that must already be in memory before a tile tap.
 */
interface ZenRuleIdStore {
    fun ruleId(): String?

    /**
     * Records [id] durably, returning false if it did not reach disk.
     *
     * The result is not decoration. This id is the **only** thing that can turn
     * a running rule back off: a process that comes up without it cannot name
     * the rule the platform is still holding, so it would create a second one
     * and drive *that* off while the original kept the phone quiet. The caller
     * treats a false here as a failure to create the rule at all (SPEC.md §5.3).
     */
    fun setRuleId(id: String): Boolean

    fun clear(): Boolean
}

/**
 * SharedPreferences rather than DataStore, deliberately, for this one value:
 * the arm path reads it and must not wait on a coroutine or a disk read, and
 * `getString` on an already-loaded preferences file is a memory hit. The file is
 * loaded at construction, which happens at startup, not at tap time.
 */
class PrefsZenRuleIdStore(context: Context) : ZenRuleIdStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun ruleId(): String? {
        cached?.let { return it.id }
        // The read and the publish under one lock, with the writers. Apart,
        // they are a read-then-publish that a concurrent write can land inside:
        // a warm-up reads the old id (or nothing), `setRuleId` persists and
        // caches the new one, and then the warm-up publishes its stale answer
        // over the top. The process would go on naming a rule that no longer
        // exists — creating a replacement on the next release while the *live*
        // rule kept the phone quiet with nothing able to name it.
        //
        // Off the fast path entirely: a populated cache returns above without
        // taking the lock, and this branch was already going to block on the
        // file. Cheap to hold, because a `commit` is all that shares it.
        synchronized(CACHE_LOCK) {
            // Someone may have populated it while this thread waited, and
            // theirs is at least as fresh as the read this one is about to do.
            cached?.let { return it.id }
            return prefs.getString(KEY_RULE_ID, null).also { remember(it) }
        }
    }

    /**
     * Reads the id into memory off the main thread, so the arm path finds it
     * already there.
     *
     * `getSharedPreferences` only *starts* the file load; the first `getString`
     * blocks until it finishes. That read is the very first thing on the way to
     * `setAutomaticZenRuleState(STATE_TRUE)` — the one call the arm path exists
     * to reach quickly — so on a cold process a tile tap would otherwise wait on
     * a disk read before the phone went quiet.
     *
     * **This narrows the window rather than closing it.** A tap that lands a few
     * milliseconds after process start can still overtake this thread and take
     * the blocking read — or block on the lock this one is holding. Closing it
     * from *here* would mean making the arm path wait for the warm-up, which is
     * the very thing being avoided: a tap that blocks on a latch is no better
     * than one that blocks on the file.
     *
     * What closes it is having somewhere else to pay. The tile's
     * `onStartListening` calls [ruleId] outright rather than warming, because
     * the shade must be listening before a tap can arrive — so on the tile
     * path, the app's primary one, the read is finished before a tap is
     * possible. This stays asynchronous for `Application.onCreate`, which has
     * no such guarantee and no budget to block in.
     */
    fun warm() {
        if (cached != null) return
        Thread { ruleId() }.start()
    }

    /**
     * `commit`, not `apply`, and the caller checks it.
     *
     * This runs once per install, off the arm path — `ensureRule` is called at
     * startup and from the slow branch of `setSnoozed`, never between the tap
     * and `STATE_TRUE` — so the blocking write costs nothing that matters. What
     * `apply` cost was the guarantee: it updates the in-memory map and hands
     * the file write to a background thread, so a process that died in that gap
     * left the platform holding a rule this app could no longer name.
     */
    override fun setRuleId(id: String): Boolean = synchronized(CACHE_LOCK) {
        // Cached only *after* the write is known to have landed. Remembering
        // first would publish an id that isn't on disk: a later arm in the same
        // process would find it and activate the rule it names, while a process
        // that started fresh would find nothing and create a replacement —
        // leaving the first rule silencing the phone with no id anywhere that
        // can turn it off. The whole point of returning a result here is that
        // the caller can refuse; the cache must refuse with it.
        if (prefs.edit().putString(KEY_RULE_ID, id).commit()) {
            remember(id)
            return true
        }

        // `commit` populates the in-memory map before it writes, so the failed
        // value is *already* readable through `prefs` — clearing the key is what
        // actually takes it back. Left as "not read yet" rather than cached as
        // absent, so the next read goes to the file rather than trusting this.
        prefs.edit().remove(KEY_RULE_ID).commit()
        cached = null
        return false
    }

    /**
     * Also `commit`, though a failure here is the harmless direction: a stale id
     * that outlives its rule is re-verified by the next [AndroidZenController.ensureRule]
     * and cleared again. Returned anyway so the caller can log rather than guess.
     */
    override fun clear(): Boolean = synchronized(CACHE_LOCK) {
        remember(null)
        return prefs.edit().remove(KEY_RULE_ID).commit()
    }

    private fun remember(id: String?) {
        cached = Cached(id)
    }

    /**
     * A read that happened, holding whatever it found — including nothing.
     *
     * One reference rather than a value plus an `isCached` flag, so a reader
     * can never see "cached" set while the value it guards is still the
     * previous one. Null here means only "not read yet".
     */
    private class Cached(val id: String?)

    private companion object {
        const val FILE_NAME = "zen_rule"
        const val KEY_RULE_ID = "rule_id"

        /**
         * Process-wide, not per-instance, because the id is.
         *
         * Four places build one of these — the screen, the service, and the two
         * receiver fallbacks — all against the same file in the same process.
         * A per-instance cache made them disagree: one could hold an id the
         * platform had already dropped while another had created and persisted
         * its replacement, and the stale holder would then "helpfully" clear the
         * new id and create a *third* rule. The platform would be left holding
         * an active rule that nothing recorded — the exact failure the durable
         * write above exists to prevent, reintroduced by the cache in front of
         * it. `SharedPreferences` is itself process-wide and self-consistent;
         * anything cached in front of it has to be too.
         *
         * Safe as a single static because the app runs in one process (no
         * `android:process` anywhere in the manifest) — a second process would
         * need this to be invalidation-aware instead.
         */
        @Volatile
        private var cached: Cached? = null

        /**
         * Held by everything that *publishes* to [cached] — the two writers and
         * the first read that populates it — so the id in memory always comes
         * from the newest of them rather than from whichever thread happened to
         * finish last. Volatile alone was not enough: it makes each write
         * visible, but a read-then-publish is two steps and a write can land
         * between them.
         *
         * Deliberately not held by the fast path. A populated cache is a
         * volatile read and nothing more, which is what keeps the arm path free
         * of everything but a field access.
         */
        private val CACHE_LOCK = Any()
    }
}
