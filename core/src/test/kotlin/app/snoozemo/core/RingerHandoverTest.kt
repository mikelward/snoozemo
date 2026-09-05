package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who owns the ringer while a snooze runs (SPEC.md §5.9).
 *
 * Both directions of every rule, per `AGENTS.md`'s testing expectations: a
 * ceiling that quietly widened to "always take the ringer" would pass a
 * one-sided suite while making phones louder or quieter than their owners asked
 * — and the give-back cases are the ones that decide whether a phone is audible
 * again afterwards at all.
 */
class RingerHandoverTest {

    // -- taking it ----------------------------------------------------------

    @Test
    fun `a vibrate ceiling takes an audible ringer and records the way back`() {
        val step = RingerHandover.quiet(
            setting = SnoozeRinger.VIBRATE,
            current = RingerMode.NORMAL,
            borrowed = null,
        )

        assertEquals(
            RingerStep.Borrow(BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = RingerMode.VIBRATE)),
            step,
        )
    }

    @Test
    fun `a silent ceiling takes a vibrating ringer`() {
        val step = RingerHandover.quiet(SnoozeRinger.SILENT, RingerMode.VIBRATE, borrowed = null)

        assertEquals(
            RingerStep.Borrow(BorrowedRinger(restoreTo = RingerMode.VIBRATE, setTo = RingerMode.SILENT)),
            step,
        )
    }

    @Test
    fun `ring takes nothing at all`() {
        assertEquals(
            RingerStep.Nothing,
            RingerHandover.quiet(SnoozeRinger.RING, RingerMode.NORMAL, borrowed = null),
        )
    }

    @Test
    fun `a phone already quieter than the ceiling is left alone`() {
        // The ceiling is not a target: a silent phone under a vibrate snooze
        // stays silent rather than being raised to buzz.
        assertEquals(
            RingerStep.Nothing,
            RingerHandover.quiet(SnoozeRinger.VIBRATE, RingerMode.SILENT, borrowed = null),
        )
    }

    @Test
    fun `a phone already at the ceiling owes nothing`() {
        assertEquals(
            RingerStep.Nothing,
            RingerHandover.quiet(SnoozeRinger.VIBRATE, RingerMode.VIBRATE, borrowed = null),
        )
    }

    @Test
    fun `an unreadable mode is not taken, because there would be no way back`() {
        assertEquals(
            RingerStep.Nothing,
            RingerHandover.quiet(SnoozeRinger.VIBRATE, current = null, borrowed = null),
        )
    }

    @Test
    fun `a re-asserted arm never overwrites an outstanding loan`() {
        // The case that would be silent and permanent: a restore after process
        // death re-asserts the arm with the phone already on vibrate, and a
        // second borrow would record VIBRATE as the way back — losing NORMAL,
        // so no later release could make the phone audible again.
        val outstanding = BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = RingerMode.VIBRATE)

        assertEquals(
            RingerStep.Nothing,
            RingerHandover.quiet(SnoozeRinger.VIBRATE, RingerMode.VIBRATE, borrowed = outstanding),
        )
    }

    // -- giving it back -----------------------------------------------------

    @Test
    fun `an untouched loan is handed back to what the phone was`() {
        val outstanding = BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = RingerMode.VIBRATE)

        assertEquals(
            RingerStep.GiveBack(RingerMode.NORMAL),
            RingerHandover.giveBack(outstanding, current = RingerMode.VIBRATE),
        )
    }

    @Test
    fun `nothing borrowed means nothing to hand back`() {
        assertEquals(
            RingerStep.Nothing,
            RingerHandover.giveBack(borrowed = null, current = RingerMode.NORMAL),
        )
    }

    @Test
    fun `a ringer the user moved mid-snooze is theirs now`() {
        val outstanding = BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = RingerMode.VIBRATE)

        // They silenced it themselves while snoozed. Putting it back to NORMAL
        // would override a deliberate choice, so the loan is dropped instead.
        assertEquals(
            RingerStep.Disown,
            RingerHandover.giveBack(outstanding, current = RingerMode.SILENT),
        )
    }

    @Test
    fun `an unreadable mode still hands the ringer back`() {
        val outstanding = BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = RingerMode.VIBRATE)

        // The user's own change cannot be ruled out — and the two mistakes are
        // not priced alike. A phone left quiet after a snooze it was told had
        // ended is principle 1's failure; one put back to ringing is a gesture
        // to undo.
        assertEquals(
            RingerStep.GiveBack(RingerMode.NORMAL),
            RingerHandover.giveBack(outstanding, current = null),
        )
    }

    @Test
    fun `a loan that cannot say what it set still hands the ringer back`() {
        val unverifiable = BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = null)

        assertEquals(
            RingerStep.GiveBack(RingerMode.NORMAL),
            RingerHandover.giveBack(unverifiable, current = RingerMode.VIBRATE),
        )
    }

    // -- the ordering the ceiling rests on ----------------------------------

    @Test
    fun `the modes are ordered loudest first`() {
        assertEquals(true, RingerMode.NORMAL.isLouderThan(RingerMode.VIBRATE))
        assertEquals(true, RingerMode.VIBRATE.isLouderThan(RingerMode.SILENT))
        assertEquals(true, RingerMode.NORMAL.isLouderThan(RingerMode.SILENT))
        assertEquals(false, RingerMode.SILENT.isLouderThan(RingerMode.NORMAL))
        assertEquals(false, RingerMode.VIBRATE.isLouderThan(RingerMode.VIBRATE))
    }

    @Test
    fun `the default ceiling is vibrate only`() {
        // Pinned because it is a product decision (maintainer, 2026-09-02) and
        // the one thing about this feature every install gets without asking.
        assertEquals(SnoozeRinger.VIBRATE, SnoozeRinger.DEFAULT)
    }

    @Test
    fun `an unknown stored name reads as the default rather than throwing`() {
        assertEquals(SnoozeRinger.DEFAULT, SnoozeRinger.named("SOMETHING_A_LATER_BUILD_WROTE"))
        assertEquals(SnoozeRinger.DEFAULT, SnoozeRinger.named(null))
        assertEquals(SnoozeRinger.SILENT, SnoozeRinger.named("SILENT"))
    }

    @Test
    fun `the retry interval doubles and then stops`() {
        // The shape that matters: short at first, because a refusal is usually
        // transient, then rare, then over — a permanently refused hand-back
        // must stop costing wake-ups (Codex, PR #176).
        assertEquals(60_000L, RingerHandBack.retryDelayMillis(1))
        assertEquals(120_000L, RingerHandBack.retryDelayMillis(2))
        assertEquals(240_000L, RingerHandBack.retryDelayMillis(3))
        assertEquals(RingerHandBack.LONGEST_RETRY_MILLIS, RingerHandBack.retryDelayMillis(7))
        // Capped rather than doubling past an hour, so a long sequence still
        // checks at a rate a stuck phone can be recovered from.
        assertEquals(
            RingerHandBack.LONGEST_RETRY_MILLIS,
            RingerHandBack.retryDelayMillis(RingerHandBack.MAX_RETRIES),
        )
        // And then nothing: the loan stays, but nothing more is scheduled.
        assertNull(RingerHandBack.retryDelayMillis(RingerHandBack.MAX_RETRIES + 1))
    }

    @Test
    fun `a lost tally still gets a retry`() {
        // A tally that could not be read counts as none, and none must not read
        // as "spent" — that would end the sequence on the first refusal.
        assertEquals(RingerHandBack.FIRST_RETRY_MILLIS, RingerHandBack.retryDelayMillis(0))
        assertEquals(RingerHandBack.FIRST_RETRY_MILLIS, RingerHandBack.retryDelayMillis(-3))
    }

    @Test
    fun `the whole sequence is hours rather than days`() {
        // Long enough to outlast a transient refusal, short enough that a
        // permanent one is done costing wake-ups the same day.
        val total = (1..RingerHandBack.MAX_RETRIES).sumOf { RingerHandBack.retryDelayMillis(it)!! }
        assertEquals(true, total > 60L * 60_000L)
        assertEquals(true, total < 12L * 60L * 60_000L)
    }

    @Test
    fun `a recorded borrow that never landed is finished`() {
        // The loan is written before the ringer moves, so a process death in
        // between leaves exactly this (Codex, PR #176): a loan that exists and
        // a phone that never changed. Answering `Nothing` there left the snooze
        // loud for its whole length.
        val unfinished = BorrowedRinger(
            restoreTo = RingerMode.NORMAL,
            setTo = RingerMode.VIBRATE,
            applied = false,
        )

        val step = RingerHandover.quiet(SnoozeRinger.VIBRATE, RingerMode.NORMAL, unfinished)

        // The *recorded* loan, not a fresh one: its `restoreTo` is the only
        // surviving record of what the phone was before the snooze.
        assertEquals(RingerStep.Borrow(unfinished), step)
    }

    @Test
    fun `an unfinished loan with nothing to set is left alone`() {
        // An unreadable `setTo` cannot be re-applied, so there is nothing to
        // finish — and guessing one would be the second borrow rule 2 forbids.
        val step = RingerHandover.quiet(
            SnoozeRinger.VIBRATE,
            RingerMode.NORMAL,
            BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = null, applied = false),
        )

        assertEquals(RingerStep.Nothing, step)
    }

    @Test
    fun `an unfinished loan is not forced onto a ringer somebody else moved`() {
        // Same dead process, but the user reached the volume panel first and
        // set the phone quieter than the pending borrow (Codex, PR #176).
        // Finishing it would *raise* them to vibrate — the leave-quieter-alone
        // rule broken by the mechanism meant to honor it.
        val step = RingerHandover.quiet(
            SnoozeRinger.VIBRATE,
            RingerMode.SILENT,
            BorrowedRinger(RingerMode.NORMAL, RingerMode.VIBRATE, applied = false),
        )

        assertEquals(RingerStep.Nothing, step)
    }

    @Test
    fun `an unfinished loan whose change did land is left for the release`() {
        // The other side of the same window: the write happened and the marker
        // did not. The mode is already the ceiling, so there is nothing to
        // finish, and the release reads `setTo` and gives it back correctly.
        val step = RingerHandover.quiet(
            SnoozeRinger.VIBRATE,
            RingerMode.VIBRATE,
            BorrowedRinger(RingerMode.NORMAL, RingerMode.VIBRATE, applied = false),
        )

        assertEquals(RingerStep.Nothing, step)
    }

    @Test
    fun `an unfinished loan over an unreadable mode takes nothing`() {
        // No evidence either way, so the audible direction: the card reports the
        // ceiling as not holding, and the release hands the loan back.
        val step = RingerHandover.quiet(
            SnoozeRinger.VIBRATE,
            null,
            BorrowedRinger(RingerMode.NORMAL, RingerMode.VIBRATE, applied = false),
        )

        assertEquals(RingerStep.Nothing, step)
    }

    @Test
    fun `an applied loan is never re-taken`() {
        val step = RingerHandover.quiet(
            SnoozeRinger.SILENT,
            RingerMode.VIBRATE,
            BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = RingerMode.VIBRATE),
        )

        // Even under a lower ceiling than the loan took: a second borrow would
        // record the quiet mode as the way back.
        assertEquals(RingerStep.Nothing, step)
    }

    @Test
    fun `a record from a build without the marker counts as applied`() {
        // The default, so an existing loan behaves as it did rather than being
        // re-applied on sight.
        assertEquals(true, BorrowedRinger(RingerMode.NORMAL, RingerMode.VIBRATE).applied)
    }

    @Test
    fun `a confirmed arm applies the ceiling`() {
        assertEquals(
            RingerFollowUp.QUIET,
            ringerFollowUp(snoozed = true, ZenOutcome.Applied("rule")),
        )
    }

    @Test
    fun `a refused arm keeps the loan for the snooze that is still running`() {
        // `SnoozeController` stays armed on this and lets the cap retry, so the
        // ringer is still owed to a live snooze.
        assertEquals(
            RingerFollowUp.NOTHING,
            ringerFollowUp(snoozed = true, ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)),
        )
    }

    @Test
    fun `an arm that finds nothing silencing the phone owes the ringer back`() {
        // The restore case (Codex, PR #176): the snooze is finalized without a
        // second zen call, so nothing else would ever hand back a loan taken
        // before the process died.
        for (reason in listOf(ZenFailure.NO_POLICY_ACCESS, ZenFailure.NO_RULE, ZenFailure.RULE_DISABLED)) {
            assertEquals(
                RingerFollowUp.HAND_BACK_AND_FORGET,
                ringerFollowUp(snoozed = true, ZenOutcome.NotApplied(reason)),
            )
        }
    }

    @Test
    fun `a refused release puts the ceiling back rather than leaving it up`() {
        // The release handed the ringer back before the rule write, and this
        // refusal keeps the snooze running (Codex, PR #176) — so the phone
        // would sit above its ceiling until some later re-assertion.
        assertEquals(
            RingerFollowUp.RE_QUIET,
            ringerFollowUp(snoozed = false, ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED)),
        )
    }

    @Test
    fun `a refused release leaves a ringer the user took over alone`() {
        // The hand-back that already ran recognized the live mode as theirs and
        // dropped the loan; re-applying the ceiling would find no loan, borrow
        // again, and lower the very ringer rule 4 just left as theirs (Codex,
        // PR #176).
        assertEquals(
            RingerFollowUp.NOTHING,
            ringerFollowUp(
                snoozed = false,
                ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED),
                ringerDisowned = true,
            ),
        )
    }

    @Test
    fun `a disowned ringer does not stop a confirmed release forgetting the ceiling`() {
        // Their mode either way, but the record is still Snoozemo's to clear.
        assertEquals(
            RingerFollowUp.HAND_BACK_AND_FORGET,
            ringerFollowUp(snoozed = false, ZenOutcome.Applied("rule"), ringerDisowned = true),
        )
    }

    @Test
    fun `a confirmed release forgets the ceiling`() {
        assertEquals(
            RingerFollowUp.HAND_BACK_AND_FORGET,
            ringerFollowUp(snoozed = false, ZenOutcome.Applied("rule")),
        )
    }

    @Test
    fun `only a refused platform leaves something still to release`() {
        // What decides whether per-snooze state is let go of. Enumerated
        // because two readings of it drifted apart once: the ringer held its
        // ceiling past a revoked-access release the rest of the app had already
        // finalized (Codex, PR #176).
        assertEquals(true, ZenOutcome.Applied("rule").confirmsNothingSilencing)
        assertEquals(true, ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS).confirmsNothingSilencing)
        assertEquals(true, ZenOutcome.NotApplied(ZenFailure.NO_RULE).confirmsNothingSilencing)
        assertEquals(true, ZenOutcome.NotApplied(ZenFailure.RULE_DISABLED).confirmsNothingSilencing)
        // The one that keeps the snooze — and therefore its ceiling — alive.
        assertEquals(false, ZenOutcome.NotApplied(ZenFailure.PLATFORM_REFUSED).confirmsNothingSilencing)
    }
}
