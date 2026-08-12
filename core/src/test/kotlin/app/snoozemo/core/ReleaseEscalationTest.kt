package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder SPEC.md §7.1 moved into `:core` so it could be executed rather than
 * argued.
 *
 * Every one of these states is reached only when the platform refuses a zen
 * write, so none of them is reachable on a device or an emulator. That is the
 * reason the decision is pure, and the reason this file covers each rung *and*
 * each "attempted but refused" transition rather than only the happy walk down.
 */
class ReleaseEscalationTest {

    private fun next(progress: ReleaseProgress) = ReleaseEscalation.next(progress)

    // --- The terminal answers -------------------------------------------------

    @Test
    fun `a confirmed-off rule settles, whatever else is outstanding`() {
        // The one input that outranks the rest. Nothing is owed once the rule is
        // off, and the obligation and the card both become claims that are no
        // longer true — so the caller retires them rather than escalating.
        assertEquals(
            ReleaseStep.Settled,
            next(
                ReleaseProgress(
                    ruleOff = true,
                    obligationStored = Attempt.TOOK,
                    userTold = Attempt.TOOK,
                    inProcessAttemptsUsed = ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
                ),
            ),
        )
    }

    @Test
    fun `an armed alarm hands off rather than also retrying`() {
        // An alarm outlives this process and wakes something that tries again,
        // so the rungs below it would be paying twice for one obligation — and
        // telling the user the rule may be stuck while something is genuinely
        // about to retry is the dishonesty in the other direction.
        assertEquals(
            ReleaseStep.HandOff,
            next(
                ReleaseProgress(
                    obligationStored = Attempt.TOOK,
                    alarmArmed = true,
                    alarmAttempts = 1,
                ),
            ),
        )
    }

    @Test
    fun `everything spent is Exhausted, not a fall-through`() {
        assertEquals(
            ReleaseStep.Exhausted,
            next(
                ReleaseProgress(
                    obligationStored = Attempt.TOOK,
                    alarmAttempts = ReleaseEscalation.MAX_ALARM_ATTEMPTS,
                    userTold = Attempt.TOOK,
                    inProcessAttemptsUsed = ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
                ),
            ),
        )
    }

    // --- The ordering ---------------------------------------------------------

    @Test
    fun `the obligation is written before anything else is relied on`() {
        // Everything below it can be lost — an alarm to an unrelated cleanup
        // that cancels it, an in-process retry to a service Android stops — so
        // the thing that survives all of them goes down first.
        assertEquals(ReleaseStep.StoreObligation, next(ReleaseProgress()))
    }

    @Test
    fun `a stored obligation moves on to the alarm`() {
        assertEquals(
            ReleaseStep.ArmAlarm(forIdentifiedSnooze = false),
            next(ReleaseProgress(obligationStored = Attempt.TOOK)),
        )
    }

    @Test
    fun `a refused alarm falls to the in-process retry, not back to the alarm`() {
        // The "did this take" rule with teeth: a refusal read as "not tried"
        // would ask for the same alarm forever and the ladder would never reach
        // the rungs that can still help.
        assertEquals(
            ReleaseStep.RetryInProcess,
            next(
                ReleaseProgress(
                    obligationStored = Attempt.TOOK,
                    alarmArmed = false,
                    alarmAttempts = 1,
                ),
            ),
        )
    }

    @Test
    fun `the user is told only once the app has run out of its own options`() {
        assertEquals(
            ReleaseStep.TellUser,
            next(
                ReleaseProgress(
                    obligationStored = Attempt.TOOK,
                    alarmAttempts = 1,
                    inProcessAttemptsUsed = ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
                ),
            ),
        )
    }

    @Test
    fun `a card that took ends the ladder without another alarm`() {
        // The user holds an exit that outlives this process, so buying a further
        // wake-up against SPEC.md §9's budget would be paying for a successor
        // that already exists.
        assertEquals(
            ReleaseStep.Exhausted,
            next(
                ReleaseProgress(
                    obligationStored = Attempt.TOOK,
                    alarmAttempts = 1,
                    userTold = Attempt.TOOK,
                    inProcessAttemptsUsed = ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
                ),
            ),
        )
    }

    @Test
    fun `a card that would not post buys one more alarm attempt`() {
        // Nothing is scheduled, stored or on screen at this point, so the only
        // rung that would outlive us is worth one more ask: a refusal minutes
        // ago and a refusal now are different refusals, and being wrong here
        // leaves the phone quiet.
        assertEquals(
            ReleaseStep.ArmAlarm(forIdentifiedSnooze = false),
            next(
                ReleaseProgress(
                    obligationStored = Attempt.TOOK,
                    alarmAttempts = 1,
                    userTold = Attempt.REFUSED,
                    inProcessAttemptsUsed = ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
                ),
            ),
        )
    }

    @Test
    fun `the extra alarm attempt is bounded too`() {
        assertEquals(
            ReleaseStep.Exhausted,
            next(
                ReleaseProgress(
                    obligationStored = Attempt.TOOK,
                    alarmAttempts = ReleaseEscalation.MAX_ALARM_ATTEMPTS,
                    userTold = Attempt.REFUSED,
                    inProcessAttemptsUsed = ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
                ),
            ),
        )
    }

    // --- A refused obligation promotes the card ------------------------------

    @Test
    fun `an obligation that would not store tells the user straight away`() {
        // The notification is the substitute durable record: once in the shade
        // it outlives this process with `Unsnooze` still live. Posted now rather
        // than at the give-up, because the give-up is exactly the code a service
        // teardown never reaches.
        assertEquals(
            ReleaseStep.TellUser,
            next(ReleaseProgress(obligationStored = Attempt.REFUSED)),
        )
    }

    @Test
    fun `a refused obligation still carries on down the ladder afterwards`() {
        // Telling the user early is a promotion, not a short circuit — the alarm
        // is still the better successor and is still asked for.
        assertEquals(
            ReleaseStep.ArmAlarm(forIdentifiedSnooze = false),
            next(
                ReleaseProgress(
                    obligationStored = Attempt.REFUSED,
                    userTold = Attempt.TOOK,
                ),
            ),
        )
    }

    @Test
    fun `a refused obligation whose card also failed does not retry the card`() {
        assertEquals(
            ReleaseStep.ArmAlarm(forIdentifiedSnooze = false),
            next(
                ReleaseProgress(
                    obligationStored = Attempt.REFUSED,
                    userTold = Attempt.REFUSED,
                ),
            ),
        )
    }

    // --- Which alarm ----------------------------------------------------------

    @Test
    fun `a snooze with a record asks for the identified end, not a cap check`() {
        // The distinction that has gone wrong before: a plain cap check asks
        // "has the record expired yet?", so on an early refused end — a manual
        // exit hours before the cap — it would restore the snooze, find it
        // unexpired, decline to act, and have spent the one retry this path had.
        assertEquals(
            ReleaseStep.ArmAlarm(forIdentifiedSnooze = true),
            next(ReleaseProgress(snoozeIdentified = true, obligationStored = Attempt.TOOK)),
        )
    }

    @Test
    fun `the record decides the alarm at the last rung too`() {
        assertEquals(
            ReleaseStep.ArmAlarm(forIdentifiedSnooze = true),
            next(
                ReleaseProgress(
                    snoozeIdentified = true,
                    obligationStored = Attempt.TOOK,
                    alarmAttempts = 1,
                    userTold = Attempt.REFUSED,
                    inProcessAttemptsUsed = ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
                ),
            ),
        )
    }

    // --- The progress bookkeeping --------------------------------------------

    @Test
    fun `a refused alarm still counts as an attempt`() {
        val after = ReleaseProgress(obligationStored = Attempt.TOOK).afterArmingAlarm(armed = false)
        assertEquals(1, after.alarmAttempts)
        assertEquals(false, after.alarmArmed)
        assertEquals(ReleaseStep.RetryInProcess, next(after))
    }

    @Test
    fun `an in-process retry opens a fresh pass with its own alarm attempt`() {
        // Carrying the refusal forward would let one bad moment spend a rung
        // that was never really tried at the next one — and the alarm is the
        // rung most worth asking for again, since it is the only successor that
        // both outlives the process and acts on its own.
        val after = ReleaseProgress(obligationStored = Attempt.TOOK)
            .afterArmingAlarm(armed = false)
            .afterInProcessAttempt()

        assertEquals(0, after.alarmAttempts)
        assertEquals(1, after.inProcessAttemptsUsed)
        assertEquals(ReleaseStep.ArmAlarm(forIdentifiedSnooze = false), next(after))
    }

    @Test
    fun `an identified snooze gets the identified alarm even with no record`() {
        // The failed-arm unwind: the zen write landed, `save` did not, so there
        // is a live snooze to end and nothing on disk describing it. The alarm
        // still has to name it — a plain cap check would find no record, and
        // the one retry this path has would be spent doing nothing.
        val progress = ReleaseProgress(snoozeIdentified = true, obligationStored = Attempt.TOOK)

        assertEquals(ReleaseStep.ArmAlarm(forIdentifiedSnooze = true), next(progress))
    }

    @Test
    fun `a recordless rule gets the plain wake-up`() {
        // Nothing names it, so nothing can end it through the controller — only
        // the plain check can drive off a rule nothing describes.
        val progress = ReleaseProgress(snoozeIdentified = false, obligationStored = Attempt.TOOK)

        assertEquals(ReleaseStep.ArmAlarm(forIdentifiedSnooze = false), next(progress))
    }

    @Test
    fun `a card the platform dropped is asked for again on a fresh pass`() {
        // A transient `notify` failure early in an escalation must not cost the
        // user their only exit for the whole of it. The card is the one rung
        // that leaves *nothing* behind when it is refused, so unlike the stored
        // obligation it is worth asking for again.
        val after = ReleaseProgress(obligationStored = Attempt.REFUSED)
            .copy(userTold = Attempt.REFUSED)
            .afterArmingAlarm(armed = false)
            .afterInProcessAttempt()

        assertEquals(Attempt.NOT_TRIED, after.userTold)
        assertEquals(ReleaseStep.TellUser, next(after))
    }

    @Test
    fun `a card that posted is not re-posted on a fresh pass`() {
        // Still in the shade with `Unsnooze` live, so asking again buys nothing.
        val after = ReleaseProgress(obligationStored = Attempt.TOOK, userTold = Attempt.TOOK)
            .afterArmingAlarm(armed = false)
            .afterInProcessAttempt()

        assertEquals(Attempt.TOOK, after.userTold)
    }

    @Test
    fun `a refused card is still offered at the give-up`() {
        // The regression this guards: walking to `Exhausted` having asked once,
        // failed, and never asked again leaves a possibly-quiet phone with no
        // user-visible exit anywhere.
        var progress = ReleaseProgress(obligationStored = Attempt.REFUSED, snoozeIdentified = true)
        var told = 0

        var step: ReleaseStep = next(progress)
        while (told < 100) {
            progress = when (step) {
                ReleaseStep.StoreObligation -> progress.copy(obligationStored = Attempt.REFUSED)
                is ReleaseStep.ArmAlarm -> progress.afterArmingAlarm(armed = false)
                ReleaseStep.RetryInProcess -> progress.afterInProcessAttempt()
                ReleaseStep.TellUser -> {
                    told++
                    progress.copy(userTold = Attempt.REFUSED)
                }
                ReleaseStep.Settled, ReleaseStep.HandOff, ReleaseStep.Exhausted -> break
            }
            step = next(progress)
        }

        assertEquals(ReleaseStep.Exhausted, step)
        assertTrue(
            "the card must be re-attempted across passes, not asked for once",
            told > 1,
        )
    }

    @Test
    fun `spending the only in-process attempt still reopens a refused card`() {
        // The receiver path's rung is *starting the service*, which it gets one
        // go at — so this is its last chance to reopen the card before the
        // ladder ends. The same transient-`notify` failure must not cost the
        // user their exit here either.
        val after = ReleaseProgress(obligationStored = Attempt.REFUSED)
            .copy(userTold = Attempt.REFUSED)
            .afterArmingAlarm(armed = false)
            .afterFinalInProcessAttempt()

        assertEquals(Attempt.NOT_TRIED, after.userTold)
        assertEquals(ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS, after.inProcessAttemptsUsed)
        assertEquals(ReleaseStep.TellUser, next(after))
    }

    @Test
    fun `spending the only in-process attempt keeps the alarm count`() {
        // No fresh pass here: no time has passed, so asking the platform again
        // in the same breath is not a new moment. Resetting the count would let
        // a receiver make a third alarm attempt it was never budgeted for.
        val after = ReleaseProgress(obligationStored = Attempt.TOOK)
            .afterArmingAlarm(armed = false)
            .afterFinalInProcessAttempt()

        assertEquals(1, after.alarmAttempts)
    }

    @Test
    fun `the receiver's walk offers the card before exhausting`() {
        // The whole receiver ladder against a platform that refuses everything,
        // driven exactly as `escalateWithoutService` drives it — the performer
        // that bypasses the retry loop, and so the one where a missed reset is
        // invisible until a phone stays quiet.
        var progress = ReleaseProgress(snoozeIdentified = true)
        var told = 0

        var step: ReleaseStep = next(progress)
        while (told < 100) {
            progress = when (step) {
                ReleaseStep.StoreObligation -> progress.copy(obligationStored = Attempt.REFUSED)
                is ReleaseStep.ArmAlarm -> progress.afterArmingAlarm(armed = false)
                ReleaseStep.RetryInProcess -> progress.afterFinalInProcessAttempt()
                ReleaseStep.TellUser -> {
                    told++
                    progress.copy(userTold = Attempt.REFUSED)
                }
                ReleaseStep.Settled, ReleaseStep.HandOff, ReleaseStep.Exhausted -> break
            }
            step = next(progress)
        }

        assertEquals(ReleaseStep.Exhausted, step)
        assertTrue("the card must be offered again after the service start is refused", told > 1)
    }

    @Test
    fun `the stored obligation is not re-asked on a later pass`() {
        // It is durable by construction, so re-writing it every thirty seconds
        // would be a preferences commit per retry for no gain.
        val after = ReleaseProgress(obligationStored = Attempt.TOOK).afterInProcessAttempt()
        assertNotEquals(ReleaseStep.StoreObligation, next(after))
    }

    // --- The whole walk -------------------------------------------------------

    @Test
    fun `a platform that refuses everything reaches Exhausted rather than looping`() {
        // The property that matters most, and the one no device test could show:
        // a ladder that cannot terminate is a service held up forever, and one
        // that terminates silently is a phone left quiet with the app still
        // claiming it is trying.
        var progress = ReleaseProgress(snoozeIdentified = true)
        val walked = mutableListOf<ReleaseStep>()

        // Bounded well above the ladder's own limits, so a non-terminating
        // decision fails this test rather than hanging it.
        var step: ReleaseStep = next(progress)
        while (walked.size < 200) {
            walked += step
            progress = when (step) {
                // Everything refuses, which is the premise.
                ReleaseStep.StoreObligation -> progress.copy(obligationStored = Attempt.REFUSED)
                is ReleaseStep.ArmAlarm -> progress.afterArmingAlarm(armed = false)
                ReleaseStep.RetryInProcess -> progress.afterInProcessAttempt()
                ReleaseStep.TellUser -> progress.copy(userTold = Attempt.REFUSED)
                ReleaseStep.Settled, ReleaseStep.HandOff, ReleaseStep.Exhausted -> break
            }
            step = next(progress)
        }

        assertEquals(ReleaseStep.Exhausted, walked.last())
        assertTrue(
            "the user must be offered the exit before the ladder gives up",
            walked.contains(ReleaseStep.TellUser),
        )
        assertEquals(
            "every in-process retry is spent before giving up",
            ReleaseEscalation.MAX_IN_PROCESS_ATTEMPTS,
            progress.inProcessAttemptsUsed,
        )
    }

    @Test
    fun `a release that starts working stops the ladder wherever it is`() {
        // The three exits can race, so any rung can be the one that finds the
        // rule already off — and from there the caller's job is cleanup, not
        // another wake-up.
        var progress = ReleaseProgress(obligationStored = Attempt.TOOK)
        progress = progress.afterArmingAlarm(armed = false).afterInProcessAttempt()
        progress = progress.copy(ruleOff = true)

        assertEquals(ReleaseStep.Settled, next(progress))
    }

    @Test
    fun `an alarm taking on a later pass hands off rather than exhausting`() {
        var progress = ReleaseProgress(snoozeIdentified = true, obligationStored = Attempt.TOOK)
        repeat(3) { progress = progress.afterArmingAlarm(armed = false).afterInProcessAttempt() }

        progress = progress.afterArmingAlarm(armed = true)

        assertEquals(ReleaseStep.HandOff, next(progress))
    }
}
