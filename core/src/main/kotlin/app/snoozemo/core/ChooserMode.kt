package app.snoozemo.core

/**
 * Whether the tile should ask when to unsnooze — open the end-condition chooser
 * — or arm instantly (SPEC.md §4.4). Held in memory so both the trampoline
 * (`:app`) and the tile (`:tile`) can read it off the tap path without a disk
 * hit (SPEC.md §6.9).
 *
 * It lives here in `:core`, not beside its `EndSheetStore` backing in `:app`,
 * because `:tile` depends on `:core` only and must read the *same* value the
 * trampoline routes on — a second copy in each module would be a source that
 * could disagree. `EndSheetStore` is the sole writer (its warm-up, its reads,
 * and the settings toggle publish here); the tile only reads, through [isOn].
 *
 * **Defaults to off** ([isOn] returns `false` until the warm-up lands) — the
 * arm-preserving fallback: a tap that overtakes a cold start arms instantly
 * rather than waiting, which is goal 1 (SPEC.md §4.1). The rare cost is that the
 * very first tap after a cold start can miss an enabled chooser and arm straight
 * away — one snooze on the default cap, never a stall.
 *
 * A process-wide singleton, so the value survives the throwaway `EndSheetStore`
 * instances the trampoline and the settings screen each build. It is one boolean
 * about how the app behaves; nothing about the user.
 */
object ChooserMode {
    @Volatile
    private var cached: Boolean? = null

    /** Guards [publish]/[publishIfUnchanged] and [writeGeneration]. */
    private val lock = Any()

    /**
     * Bumped under [lock] on every [publish], so a read that captured a
     * generation with [beginRead] can tell whether a write landed while it was
     * reading disk and [publishIfUnchanged] can decline to publish a value that
     * is now stale. Monotonic; never reset, so an in-flight read's captured
     * value cannot match a later one.
     */
    private var writeGeneration = 0L

    /** The warmed value, or `false` until the warm-up has landed. */
    fun isOn(): Boolean = cached ?: false

    /** The generation to capture before a disk read; see [publishIfUnchanged]. */
    fun beginRead(): Long = synchronized(lock) { writeGeneration }

    /**
     * Publishes [value] only if no write landed since [seenGeneration] was
     * captured — so a slow warm-up read that is preempted by a write cannot
     * overwrite the newer value with the stale one it read (Codex, PR #284).
     */
    fun publishIfUnchanged(value: Boolean, seenGeneration: Long) = synchronized(lock) {
        if (writeGeneration == seenGeneration) cached = value
    }

    /** Publishes the value now in force, bumping the generation. */
    fun publish(value: Boolean) = synchronized(lock) {
        writeGeneration++
        cached = value
    }

    /** Drops the cache, so a test cannot inherit another's warmed value. */
    fun resetForTest() = synchronized(lock) { cached = null }
}
