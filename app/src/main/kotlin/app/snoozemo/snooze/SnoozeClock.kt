package app.snoozemo.snooze

import android.os.SystemClock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The clock every cap decision is made against — wall time that cannot be
 * wound back (SPEC.md §7).
 *
 * The duration cap had two enforcers and both moved with the wall clock: an
 * `RTC_WAKEUP` alarm at the record's expiry, and an expiry test reading
 * `Clock.systemUTC()`. Setting the date back therefore kept Do Not Disturb on
 * past the 24 h backstop — principle 1's failure, reachable from Settings.
 *
 * The asymmetry is worth keeping in mind: a *forward* clock change ends the
 * snooze early, which is the safe direction and needs no defense. Only a
 * deliberate backwards change extends one.
 *
 * So this reads `SystemClock.elapsedRealtime`, which counts from boot and no
 * app or user can move, and reports it as an [Instant] by adding the elapsed
 * progress to a wall-clock reading taken once. Callers keep working in
 * `Instant`s — the record's `capExpiresAt` stays absolute wall time, because
 * elapsed realtime resets on boot and could not survive one.
 *
 * **One anchor per process, deliberately.** Two independently-anchored clocks
 * would disagree by whatever the wall clock did between them, and the cap is
 * compared across the service and the controller.
 *
 * What this does not cover: a backwards change made while the process is
 * *dead*, which moves the anchor itself, so a deadline written by an earlier
 * process is read here in a different frame. Nothing in-process can detect
 * that, and today the answer is `ActiveSnooze.isExpired` treating a remaining
 * duration longer than `MAX_CAP` as a moved clock and ending the snooze
 * (SPEC.md D7). That bounds the damage rather than measuring it: a shift too
 * small to clear the ceiling still overruns the cap by however far it moved.
 *
 * A persisted boot reference would close the *same-boot* case exactly —
 * `currentTimeMillis - elapsedRealtime` holds still within a boot unless the
 * wall clock moves, so a restore that finds it changed knows the delta. Across
 * a real reboot the two are indistinguishable and the fail-open is all there
 * is. Sequenced in `TODO.md` behind the store seam, since it is a schema
 * change on the restore path.
 */
object SnoozeClock : Clock() {

    private val anchorElapsedMillis = SystemClock.elapsedRealtime()
    private val anchorWallMillis = System.currentTimeMillis()

    /** Milliseconds of real time since this process anchored itself. */
    fun sinceAnchorMillis(): Long = SystemClock.elapsedRealtime() - anchorElapsedMillis

    override fun instant(): Instant = Instant.ofEpochMilli(anchorWallMillis + sinceAnchorMillis())

    override fun millis(): Long = anchorWallMillis + sinceAnchorMillis()

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    /**
     * Returns this clock unchanged.
     *
     * The zone is presentational and nothing here formats anything — every
     * caller compares instants. Honoring a zone change would mean a second
     * anchor, which is the one thing this object exists to prevent.
     */
    override fun withZone(zone: ZoneId?): Clock = this
}
