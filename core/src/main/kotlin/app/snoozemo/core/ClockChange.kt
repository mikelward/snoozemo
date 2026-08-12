package app.snoozemo.core

/**
 * What a wall-clock change means for the snooze that is running (SPEC.md §7).
 *
 * Pure, and shared, because a clock change has two performers: the running
 * service where one exists, and the broadcast receiver's own no-service
 * fallback where it does not. The decision is the same in both — end at the
 * cap, restate the record, end because the record has no frame left to be read
 * in, or do nothing — and it is the decision, not the performing, that has to
 * be right. Split across two hand-written `when`s it would drift, and the half
 * that drifted would be the one nobody can reach on a device.
 *
 * The caller reads the clock and passes it in rather than this reading one, for
 * the same reason [SnoozeController] takes a [ClockReading]: the deadline, the
 * alarm and the offset all have to be judged against a single reading, and a
 * second one taken here would be a different frame by definition — a clock
 * change is the one moment when two readings microseconds apart can disagree
 * by hours.
 */
object ClockChange {

    fun resolve(snooze: ActiveSnooze, now: ClockReading): ClockChangeAction = when {
        // The cap first, since an expired snooze needs no record written — it
        // needs ending. This also catches the forward jump that lands past the
        // deadline, which is the case `TIME_SET` exists for: the alarm counts
        // in elapsed realtime and does not move with the wall clock, so nothing
        // else would notice until it fired.
        snooze.isExpired(now) -> ClockChangeAction.EndAtCap

        // Restated onto the clock that has just been set, so the truth survives
        // the next reboot. Null means the record carries no offset, so there was
        // never a second frame and the wall clock — the one thing that just
        // moved by an unknown amount in an unknown direction — is all it has.
        else -> when (val restated = snooze.reconciledOnto(now)) {
            null -> ClockChangeAction.EndUnframed
            snooze -> ClockChangeAction.None
            else -> ClockChangeAction.Restate(restated)
        }
    }
}

/** The one action a clock change can call for. */
sealed interface ClockChangeAction {

    /**
     * The cap is due as of this reading: end the snooze with
     * [EndReason.DURATION_CAP].
     */
    data object EndAtCap : ClockChangeAction

    /**
     * Write [snooze] back to the record — the same snooze with both clock
     * frames restated onto the clock the user just set.
     *
     * The armed alarm is deliberately **not** part of this: it counts in
     * elapsed realtime, nothing since has moved it, and replacing it is the
     * failure SPEC.md §7 keeps the receiver away from.
     */
    data class Restate(val snooze: ActiveSnooze) : ClockChangeAction

    /**
     * The record carries no clock offset, so end it with
     * [EndReason.LOST_CAPABILITY].
     *
     * Such a record is read against wall time alone (SPEC.md §7), which is
     * exactly the frame that has just moved — by an amount and in a direction
     * nothing here can recover, since there is no second clock to compare
     * against. Restating it would only stamp the moved reading as though it
     * were sound, handing the next reader a deadline that *looks* framed and
     * is not. The 24 h ceiling still catches a shift big enough to make the
     * deadline implausible, but a three-hour one sails past it.
     *
     * So this is fail-open (D7): the honest answer is that how much time is
     * really left is now unknowable, and principle 1 settles what to do about
     * an unknowable bound. Only records written before the offset existed can
     * reach it, and only at the moment the clock is set — every snooze armed
     * since carries a frame.
     */
    data object EndUnframed : ClockChangeAction

    /** Both frames already read correctly against this clock. Nothing to do. */
    data object None : ClockChangeAction
}
