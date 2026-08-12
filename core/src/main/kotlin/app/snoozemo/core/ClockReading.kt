package app.snoozemo.core

/**
 * Both of the device's clocks, read together (SPEC.md §7).
 *
 * The duration cap needs two facts that no single clock carries. Wall time is
 * the only frame that survives a reboot, so it is what the record persists; it
 * is also the one the user can move from Settings, which is what let a snooze
 * outlast its cap. Uptime — `SystemClock.elapsedRealtime`, counting from boot
 * across sleep — cannot be moved by anyone, but resets to zero on every boot, so
 * nothing written in it survives one.
 *
 * Reading both at once is what makes them comparable. [bootReference] is the
 * offset between the two frames, and a record that stores the offset it was
 * written under can be re-read in either frame later — see
 * [ActiveSnooze.remaining].
 *
 * The two readings are taken a few microseconds apart in practice, not
 * atomically. That is well inside the tolerance of everything here: the cap is
 * scheduled with an inexact alarm and the comparisons below are against hours.
 */
data class ClockReading(
    /** Milliseconds since the epoch — `System.currentTimeMillis`. */
    val wallMillis: Long,
    /** Milliseconds since boot, including sleep — `SystemClock.elapsedRealtime`. */
    val uptimeMillis: Long,
) {
    /**
     * What separates the two frames right now: `wall - uptime`, roughly the wall
     * time the device booted at.
     *
     * Constant for as long as a boot lasts *and* nobody moves the wall clock,
     * which is what makes it useful: stored alongside a deadline, it converts
     * that deadline into uptime, and a conversion that still lands correctly is
     * one the clock has not moved under.
     */
    val bootReference: Long get() = wallMillis - uptimeMillis
}
