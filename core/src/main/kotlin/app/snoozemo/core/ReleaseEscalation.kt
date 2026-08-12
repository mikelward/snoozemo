package app.snoozemo.core

/**
 * What to do next about a release the platform refused (SPEC.md §7.1).
 *
 * When Snoozemo asks its zen rule to go off and the platform declines, the rule
 * may still be silencing the phone with nothing left in the app that knows to
 * turn it off — principle 1's failure, arrived at quietly. The app answers with
 * an escalation: write the obligation down, schedule an alarm, retry in process,
 * hand the user an exit, and — when there is genuinely nothing left — say so.
 *
 * That sequence used to be written out separately at five entry points, each
 * reached when a different mechanism had already failed. Over one review pass of
 * that code, nine findings were instances of one copy forgetting a rule the
 * others remembered, and four of those were introduced by the fix to the
 * previous one. The defect rate tracked the number of copies, so the copies are
 * what had to go.
 *
 * So the ladder is one pure decision and each call site is reduced to *ask,
 * perform, record what actually happened*. Three rules are stated here once
 * instead of five times:
 *
 * - **The durable obligation is written first, always.** Everything below it can
 *   be lost — an alarm to an unrelated cleanup that cancels it, an in-process
 *   retry to a service Android stops — so the thing that survives all of them
 *   goes down before any of them is relied on.
 * - **The inputs are "did this take", never "did we try".** A refused `commit`,
 *   an alarm the platform declined and a `notify` that threw are all *not done*,
 *   and each has to move the ladder on rather than be assumed away. That is what
 *   [Attempt] exists to make unsayable-wrong.
 * - **Running out is a named state.** [ReleaseStep.Exhausted] is reachable and
 *   explicit, so no caller can fall off the end while still telling the user
 *   something is trying — the specific dishonesty SPEC.md §4.5's copy rules are
 *   there to prevent.
 *
 * The testing argument is the strongest one. None of these branches is reachable
 * without a platform that refuses a zen write, so no device and no emulator can
 * exercise them; as prose spread over five files they were argued rather than
 * executed, and review was the only thing checking them. As a pure function they
 * are reachable from a JVM test — the same reasoning SPEC.md §11 already applies
 * to [SnoozeController].
 */
object ReleaseEscalation {

    /**
     * How many times a caller may retry inside its own process before the ladder
     * moves on to the user.
     *
     * Ten tries at thirty seconds is five minutes of holding a service up for a
     * rule the platform keeps refusing: long enough to outlast a transient
     * refusal, short enough not to become a background service that never goes
     * away.
     */
    const val MAX_IN_PROCESS_ATTEMPTS: Int = 10

    /**
     * How many times the alarm rung may be attempted in one pass down the ladder.
     *
     * Two, and the second is deliberate rather than a retry loop. An alarm is the
     * only successor that both outlives the process *and* acts on its own, so once
     * the softer rungs below it are spent it is worth asking the platform again —
     * a refusal several minutes ago and a refusal now are different refusals, and
     * the cost of being wrong is a phone that stays quiet.
     */
    const val MAX_ALARM_ATTEMPTS: Int = 2

    /**
     * The next rung for a release in [progress].
     *
     * Callers perform the returned step, record whether it *took*, and ask again.
     * Both terminal answers stop the loop: [ReleaseStep.Settled] and
     * [ReleaseStep.HandOff] because something else has it now,
     * [ReleaseStep.Exhausted] because nothing does.
     */
    fun next(progress: ReleaseProgress): ReleaseStep = when {
        // Confirmed off. Nothing is owed, and the obligation and the card that
        // claim otherwise are now false — the caller retires both.
        progress.ruleOff -> ReleaseStep.Settled

        // The durable record of the obligation, before anything that can be lost.
        progress.obligationStored == Attempt.NOT_TRIED -> ReleaseStep.StoreObligation

        // The store refused, so the notification is promoted to the top of the
        // ladder: once in the shade it outlives this process with `Unsnooze`
        // still live, which makes it the only durable thing left. Told *now*
        // rather than at the give-up, because the give-up is precisely the code
        // a service teardown never reaches.
        progress.obligationStored == Attempt.REFUSED &&
            progress.userTold == Attempt.NOT_TRIED -> ReleaseStep.TellUser

        // An alarm is scheduled: it outlives this process and will wake something
        // that tries again, so stop here rather than paying for a retry as well.
        progress.alarmArmed -> ReleaseStep.HandOff

        progress.alarmAttempts == 0 -> ReleaseStep.ArmAlarm(progress.snoozeIdentified)

        // No durable successor took, so this process is what is left. Bounded, so
        // a permanently refusing platform doesn't leave a timer ticking forever.
        progress.inProcessAttemptsUsed < MAX_IN_PROCESS_ATTEMPTS -> ReleaseStep.RetryInProcess

        // Out of everything the app can do by itself. The user gets the exit.
        progress.userTold == Attempt.NOT_TRIED -> ReleaseStep.TellUser

        // The card didn't take either, so nothing is scheduled, stored or on
        // screen. One more ask at the only rung that would outlive us.
        progress.userTold != Attempt.TOOK &&
            progress.alarmAttempts < MAX_ALARM_ATTEMPTS -> ReleaseStep.ArmAlarm(progress.snoozeIdentified)

        else -> ReleaseStep.Exhausted
    }
}

/**
 * Whether a rung was tried, and whether it actually took.
 *
 * Three values rather than a boolean, because "not tried" and "tried and
 * refused" call for opposite things and collapsing them is how the duplicated
 * ladders went wrong: a refusal read as "not tried" repeats forever, and read as
 * "done" is a successor the app believes in and does not have.
 */
enum class Attempt {
    NOT_TRIED,

    /** It landed. This rung is satisfied. */
    TOOK,

    /** The platform, the disk, or the notification manager declined. Not done. */
    REFUSED,
}

/**
 * What the app has actually managed to put behind a refused release.
 *
 * One pass down the ladder per moment: [alarmAttempts] and the two [Attempt]
 * fields describe the current pass, while [inProcessAttemptsUsed] accumulates
 * across the whole escalation and is what eventually ends it. A caller that
 * retries in process therefore starts its next pass with the per-pass fields
 * reset — the alarm the platform refused a moment ago is worth asking for again
 * ([ReleaseEscalation.MAX_ALARM_ATTEMPTS]), and that reset is what makes each
 * retry a real attempt rather than a countdown.
 */
data class ReleaseProgress(
    /**
     * Whether the rule is **confirmed** off — a zen write that was applied, or
     * one refused for a reason meaning there was never anything left to release
     * ([ZenFailure.nothingLeftToRelease]). Never "we asked and moved on".
     */
    val ruleOff: Boolean = false,

    /**
     * Whether this release has a `startedAt` that names the snooze it is about.
     *
     * Decides which alarm the ladder asks for, which is a distinction that has
     * gone wrong before: a plain cap check asks "has the record expired yet?",
     * so on an early refused end — a manual exit hours before the cap — it would
     * restore the snooze, find it unexpired, decline to act, and have spent the
     * one retry the path had.
     *
     * *Identified*, deliberately, not "a record is on disk". The two normally
     * coincide and one path separates them: an arm whose zen write landed but
     * whose `save` failed is unwound with a live in-memory snooze and nothing
     * written down. That release still needs the identified alarm — there is a
     * snooze to end — while a caller asking "does the user have a snooze?" must
     * be told no. Conflating them had this ladder replace `Couldn't snooze` with
     * `Couldn't end the snooze`, announcing a snooze the app had just failed to
     * create. Callers that need the other fact track it themselves.
     */
    val snoozeIdentified: Boolean = false,

    /** Whether the durable "our rule may be on" obligation reached disk. */
    val obligationStored: Attempt = Attempt.NOT_TRIED,

    /** Whether an alarm is scheduled to come back at this. */
    val alarmArmed: Boolean = false,

    /** Alarm attempts made in this pass, landed or refused. */
    val alarmAttempts: Int = 0,

    /** Whether the stuck-rule card, carrying the user's own exit, is showing. */
    val userTold: Attempt = Attempt.NOT_TRIED,

    /** In-process retries spent across the whole escalation. */
    val inProcessAttemptsUsed: Int = 0,
) {
    init {
        require(alarmAttempts >= 0) { "alarmAttempts must not be negative" }
        require(inProcessAttemptsUsed >= 0) { "inProcessAttemptsUsed must not be negative" }
    }

    /**
     * Records the outcome of an [ReleaseStep.ArmAlarm] step.
     *
     * The attempt is counted whether or not it landed, which is the whole point:
     * a refusal that didn't move the state is a rung the ladder would ask for
     * again forever.
     */
    fun afterArmingAlarm(armed: Boolean): ReleaseProgress =
        copy(alarmArmed = armed, alarmAttempts = alarmAttempts + 1)

    /**
     * Records an in-process retry that has just been scheduled or run, opening a
     * fresh pass down the ladder.
     *
     * The per-pass fields reset with it. The next moment is entitled to its own
     * alarm attempt, and to tell the user if the ladder gets that far — carrying
     * a stale `REFUSED` forward would let one bad moment spend rungs that were
     * never really tried at this one.
     *
     * `REFUSED` resets, `TOOK` does not, and the asymmetry is the point: a card
     * that posted is still in the shade with `Unsnooze` live, so re-posting it
     * buys nothing, while a card the platform *dropped* left nothing behind at
     * all. Keeping a refusal would mean one transient `notify` failure early in
     * an escalation cost the user their only exit for the whole of it — the
     * ladder would walk to [ReleaseStep.Exhausted] having never asked again.
     */
    fun afterInProcessAttempt(): ReleaseProgress = copy(
        alarmArmed = false,
        alarmAttempts = 0,
        userTold = retriableUserTold(),
        inProcessAttemptsUsed = inProcessAttemptsUsed + 1,
    )

    /**
     * Spends the in-process rung outright, for a caller with exactly one
     * in-process option rather than a retry loop.
     *
     * The receiver path is that caller: its in-process rung is *starting the
     * service*, and a broadcast receiver has neither the budget nor the lifetime
     * to ask ten times. A refused start is therefore the end of that rung, not
     * one attempt at it.
     *
     * Unlike [afterInProcessAttempt] this opens no fresh pass — the alarm count
     * carries over, because no time has passed and asking the platform again in
     * the same breath is not a new moment. But a *refused* card still resets,
     * for the reason it always does: a card the platform dropped left nothing
     * behind, and the rungs after this one are the last chance to hand the user
     * an exit.
     */
    fun afterFinalInProcessAttempt(): ReleaseProgress = copy(
        userTold = retriableUserTold(),
        inProcessAttemptsUsed = ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
    )

    /**
     * `REFUSED` reopens, `TOOK` does not.
     *
     * A card that posted is still in the shade with `Unsnooze` live, so asking
     * again buys nothing. A card the platform dropped left nothing at all —
     * and carrying that refusal forward is how one transient `notify` failure
     * costs the user their only exit for a whole escalation.
     */
    private fun retriableUserTold(): Attempt =
        if (userTold == Attempt.REFUSED) Attempt.NOT_TRIED else userTold
}

/** One rung of the ladder — what the caller does next, and nothing more. */
sealed interface ReleaseStep {

    /**
     * The rule is off. Stop escalating, and retire the claims that it might not
     * be: clear the stored obligation and take the stuck-rule card down.
     */
    data object Settled : ReleaseStep

    /**
     * Persist "Snoozemo's own rule may be on with nothing behind it".
     *
     * Deliberately carries no identity. It means exactly that sentence, so every
     * reader re-checks that there is still no snooze before acting on it, and
     * driving an already-off rule off is a no-op — which is what keeps it from
     * becoming the state-sync hazard every *identified* retry here has to guard
     * against.
     */
    data object StoreObligation : ReleaseStep

    /**
     * Schedule a wake-up that will try again.
     *
     * [forIdentifiedSnooze] picks the alarm that carries the right instruction: a
     * snooze with a record on disk needs "end it, whatever the clock says",
     * identified so it cannot end a snooze the user armed in the meantime, while
     * a rule with no record needs the plain wake-up that drives it off.
     */
    data class ArmAlarm(val forIdentifiedSnooze: Boolean) : ReleaseStep

    /**
     * Try again inside this process after a short delay.
     *
     * The weakest successor — it dies with the service — so it is reached only
     * once nothing durable would take, and it is bounded by
     * [ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS].
     */
    data object RetryInProcess : ReleaseStep

    /**
     * Post the stuck-rule card: `Do Not Disturb may still be on`, carrying
     * `Unsnooze`.
     *
     * The user is the last actor with an exit, and the card is the last thing
     * that outlives this process. It says the rule *may* be on rather than
     * promising a retry, because by here there may be nothing retrying.
     */
    data object TellUser : ReleaseStep

    /**
     * Something durable is scheduled and will act. Stop — and in particular do
     * not tell the user the rule may be stuck while it isn't yet true.
     */
    data object HandOff : ReleaseStep

    /**
     * Every rung is spent. Nothing is scheduled, nothing is retrying, and
     * whatever the user was told is all there is.
     *
     * A named state rather than a fall-through, so a caller cannot arrive here
     * and go on claiming something is trying. What is left is the persisted
     * obligation, which any later start discharges — and, where the snooze still
     * has a record, that record's own cap.
     */
    data object Exhausted : ReleaseStep
}
