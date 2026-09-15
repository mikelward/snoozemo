package app.snoozemo.snooze

/**
 * Whether the first-run welcome flow is still unfinished, cached process-wide so
 * a tile tap can ask without touching disk (`SPEC.md` §4.2).
 *
 * A tap arriving before the user has finished onboarding resumes the flow rather
 * than snoozing — even a tap that *could* arm, so a user part-way through the
 * cards is taken back to where they were instead of starting a snooze mid-setup
 * (maintainer, 2026-09-15). Deciding that in [TileTrampolineActivity] needs the
 * answer in memory: the trampoline reads it on the arm path, and nothing between
 * a tap and the zen rule going on may wait on a file (§4.1, §6.9). So this is the
 * same shape as [app.snoozemo.core.ChooserMode] — a volatile flag published from
 * `WelcomeStore`'s warm-up and updated on every write that changes the answer.
 *
 * **Fails open toward arming.** Until the warm-up has published, [unfinished]
 * reads `false` and a tap arms as it always did; on a genuinely fresh install
 * that arm cannot silence anything anyway (no Do Not Disturb access yet) and
 * `MainActivity`'s own welcome gate still opens the flow, so the uncached race
 * lands the user in the tutorial by the longer road rather than snoozing by
 * mistake. A wrong answer that arms is recoverable; one that swallowed a tap in
 * front of a first frame is the blank window §6.9 warns against.
 *
 * **The generation guard is why a slow warm-up cannot clobber a write.** The
 * warm-up reads three preferences off the main thread, and a `rememberCard` /
 * `forgetCard` on the main thread can land between that read and its publish;
 * without the guard the worker would then overwrite the newer value with the
 * stale one it read — a tile tap arming during a replay, or subsequent taps
 * reopening the app instead of arming until the process dies (Codex, PR #291).
 * A write [publish]es and bumps the generation; the warm-up [publishIfUnchanged]
 * only if no write landed since it captured the generation with [beginRead] —
 * the same mechanism `EndSheetStore`/[app.snoozemo.core.ChooserMode] already use
 * for this exact interleaving, and it adds no disk to the arm path.
 *
 * Nothing here is about the user — one boolean about how far setup has got.
 */
object WelcomeGate {

    @Volatile
    private var cached: Boolean? = null

    /** Guards [publish]/[publishIfUnchanged] and [writeGeneration]. */
    private val lock = Any()

    /**
     * Bumped under [lock] on every [publish], so a warm-up read that captured a
     * generation with [beginRead] can tell whether a write landed while it was
     * reading disk. Monotonic; never reset.
     */
    private var writeGeneration = 0L

    /**
     * Whether a tile tap should resume the flow instead of arming, or `false`
     * until the warm-up lands. Read on the arm path, so it never touches disk.
     */
    fun unfinished(): Boolean = cached ?: false

    /** The generation to capture before a warm-up disk read; see [publishIfUnchanged]. */
    fun beginRead(): Long = synchronized(lock) { writeGeneration }

    /**
     * Publishes [value] only if no write landed since [seenGeneration] was
     * captured — so a slow warm-up read preempted by a write cannot overwrite
     * the newer value with the stale one it read.
     */
    fun publishIfUnchanged(value: Boolean, seenGeneration: Long) = synchronized(lock) {
        if (writeGeneration == seenGeneration) cached = value
    }

    /** Publishes the value now in force after a write, bumping the generation. */
    fun publish(value: Boolean) = synchronized(lock) {
        writeGeneration++
        cached = value
    }

    /** Drops the cache, so a test cannot inherit another's warmed value. */
    fun resetForTest() = synchronized(lock) { cached = null }
}
