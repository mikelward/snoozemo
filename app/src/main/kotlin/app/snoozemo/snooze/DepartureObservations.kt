package app.snoozemo.snooze

import app.snoozemo.core.DepartureObservation

/**
 * The departure test's latest arithmetic, for anything drawing it live
 * (`SPEC.md` §4.6).
 *
 * **In memory and process-wide, never on disk.** The service and the screen run
 * in one process, so a reading needs no store to cross between them — and it
 * should not have one: this is what the last fix said, not part of the snooze,
 * and a snooze that outlives the process comes back with nothing to show until
 * the next fix arrives, which is the truth rather than a gap. It also keeps the
 * readout off every path that writes: no disk on the fix path, and nothing to
 * erase when the snooze ends beyond [clear].
 *
 * The shape is [app.snoozemo.crash.CrashReporting.watchSaveOutcome]'s, for the
 * same reason: the activity that registers may be replaced by a configuration
 * change, so the value lives here and the watch is what comes and goes.
 */
internal object DepartureObservations {

    @Volatile
    private var latest: DepartureObservation? = null

    @Volatile
    private var onChange: (() -> Unit)? = null

    /** The last reading, or null if none has arrived since this process started. */
    fun latest(): DepartureObservation? = latest

    /** Records [observation] and notifies whoever is watching. */
    fun publish(observation: DepartureObservation) {
        latest = observation
        onChange?.invoke()
    }

    /**
     * Forgets the last reading — for a snooze ending, so the next one does not
     * open on the distance the previous one ended at.
     */
    fun clear() {
        val had = latest != null
        latest = null
        if (had) onChange?.invoke()
    }

    /**
     * Registers [listener], returning a handle that clears **only** this
     * registration — a later registrant must not be unregistered by an earlier
     * one's handle closing, which is what a configuration change produces.
     *
     * **[listener] is called once on registration, and that is what makes
     * subscribing and reading atomic** (Codex, PR #210). A registrant that
     * subscribed and then read [latest] separately had a window between the
     * two, and a publish landing in it reached neither — leaving the screen on
     * the older number until the next fix, which the resting duty cycle can
     * put ten minutes away. Delivering the current value through the same
     * callback removes the window rather than narrowing it: there is no second
     * read to race.
     */
    fun watch(listener: () -> Unit): AutoCloseable {
        onChange = listener
        listener()
        return AutoCloseable { if (onChange === listener) onChange = null }
    }
}
