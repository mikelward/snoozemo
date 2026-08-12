package app.snoozemo.snooze

import android.os.SystemClock
import app.snoozemo.core.ClockReading

/**
 * Reads the device's two clocks together, for every cap decision (SPEC.md §7).
 *
 * The duration cap used to have two enforcers and both moved with the wall
 * clock: an `RTC_WAKEUP` alarm at the record's expiry, and an expiry test
 * reading `Clock.systemUTC()`. Setting the date back therefore kept Do Not
 * Disturb on past the 24 h backstop — principle 1's failure, reachable from
 * Settings in two taps.
 *
 * The asymmetry is worth keeping in mind: a *forward* clock change ends the
 * snooze early, which is the safe direction and needs no defense. Only a
 * deliberate backwards change extends one.
 *
 * What is deliberately **not** here is a process-wide anchor — a wall clock
 * frozen at process start and advanced by uptime. That reads as the obvious fix
 * and is a trap: the record outlives the process, so a deadline stamped in one
 * process's anchored frame is read by the next process against a different
 * anchor, and the mismatch is a cap that overruns by however far the clock
 * moved. Anchoring hides the clock change instead of measuring it.
 *
 * So nothing is cached. Both clocks are read at the moment they are compared,
 * and `ActiveSnooze.remaining` uses the pair — with the offset stored in the
 * record — to work out which frame is still trustworthy.
 */
object SnoozeClock {

    /** Both clocks, read together. */
    fun read(): ClockReading = ClockReading(
        wallMillis = System.currentTimeMillis(),
        uptimeMillis = SystemClock.elapsedRealtime(),
    )
}
