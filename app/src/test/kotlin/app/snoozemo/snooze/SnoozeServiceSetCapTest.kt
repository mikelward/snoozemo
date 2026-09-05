package app.snoozemo.snooze

import android.content.Intent
import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.ZenOutcome
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * A time committed in the end-condition sheet (SPEC.md §4.4).
 *
 * The arithmetic the *sheet* does is `EndCondition`'s and is covered in
 * `:core`. What is only reachable here is the half the sheet cannot do for
 * itself: the sheet computes against the clock because §6.9 forbids it waiting
 * on the record, so this is where a value meets the record that actually
 * exists — and where it gets refused rather than half-applied.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceSetCapTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /** The commit these intents claim to come from; see [setUp]. */
    private val REQUEST = 42L

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
        // The rule goes on and comes off as asked; a refusing default would end
        // every snooze before a time could be chosen for it.
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
        // Static, so a refusing test must not leak its refusal forward.
        TogglableAlarmManager.refuse = false
        // The sheet's own channel. Every exit from `setCap` has to report
        // through it, or an opted-in user is left looking at a sheet that never
        // answers (Codex, PR #118).
        reported = null
        EndChoiceOutcome.reset()
        // Addressed: the service echoes back the request that asked, so a
        // watch names the one this test's intents carry.
        watch = EndChoiceOutcome.watch(REQUEST) { reported = it }
    }

    @After
    fun tearDown() {
        watch?.close()
        watch = null
    }

    /**
     * A second start on the **same** service instance, so the refusal a test
     * arms after the service is already up is the one this action meets — a
     * fresh instance would have to re-arm the cap on its own restore first.
     */
    private fun ServiceController<TestSnoozeService>.send(action: String, startId: Int, extras: Intent.() -> Unit = {}) =
        get().onStartCommand(
            Intent(appContext, TestSnoozeService::class.java).setAction(action).apply(extras),
            0,
            startId,
        )

    /**
     * What the last drive reported back to the sheet, or null if it reported
     * nothing — which is itself a defect: the sheet stays up waiting.
     */
    private var reported: EndChoiceResult? = null

    private var watch: AutoCloseable? = null

    /** A choice that claims to be for a snooze that is no longer the one running. */
    @Test
    fun `a chosen end for a snooze that is no longer running changes nothing`() {
        // The sheet checks this too, but only as it redraws — and the tile's
        // sheet never redraws. This is where it binds: the claim is validated
        // against the record in the same pass that would change it (Codex,
        // PR #155).
        val record = snoozeFixture(now)
        val chosen = now.plus(Duration.ofHours(1))

        startService(SnoozeService.ACTION_SET_CAP, record) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, chosen.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
            // An earlier snooze: this record replaced it while the sheet was up.
            putExtra(
                SnoozeService.EXTRA_CHOICE_FOR_SNOOZE,
                record.startedAt.minus(Duration.ofHours(2)).toEpochMilli(),
            )
        }

        assertEquals(
            "the running snooze keeps its own cap",
            record.capExpiresAt,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
        assertEquals("and the sheet is told its snooze is gone", EndChoiceResult.GONE, reported)
    }

    @Test
    fun `a chosen end naming the running snooze is applied`() {
        val record = snoozeFixture(now)
        val chosen = now.plus(Duration.ofHours(1))

        startService(SnoozeService.ACTION_SET_CAP, record) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, chosen.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, record.startedAt.toEpochMilli())
        }

        assertEquals(chosen, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    private fun chooseEnd(endsAt: Instant, record: ActiveSnooze?) =
        startService(SnoozeService.ACTION_SET_CAP, record) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, endsAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

    @Test
    fun `a chosen time is what the record ends at`() {
        val chosen = now.plus(Duration.ofHours(1))

        chooseEnd(chosen, snoozeFixture(now))

        assertEquals(chosen, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        assertEquals("the sheet has to hear that it applied", EndChoiceResult.APPLIED, reported)
    }

    @Test
    @Config(shadows = [TogglableAlarmManager::class])
    fun `a retry that takes clears the card the failed attempt left`() {
        // The failure card is a one-shot on its own id, so nothing the success
        // path posts replaces it: after a refused attempt and a successful
        // retry the shade went on saying "Couldn't set the end time" about a
        // cap that had in fact been set (Codex, PR #118). The user was sent to
        // the shade to read that card; it has to stop being true there.
        val failureCard = stringOf(R.string.failure_could_not_set_end)
        val record = snoozeFixture(now)
        val service = startService(SnoozeService.ACTION_RESTORE, record)
        val chosen = now.plus(Duration.ofHours(1))

        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, chosen.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }
        assertTrue("the refused attempt should have posted its card", shadeShows(failureCard))

        // The same choice again, with the alarm no longer refusing — which is
        // what a user does when a sheet stays up saying it failed.
        TogglableAlarmManager.refuse = false
        service.send(SnoozeService.ACTION_SET_CAP, startId = 3) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, chosen.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        assertEquals(chosen, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
        assertFalse("the card outlived the failure it explained", shadeShows(failureCard))
    }

    @Test
    fun `a chosen time re-arms the cap alarm, not just the record`() {
        // The ordering that matters: the alarm is the cap, so a record brought
        // in to a time no alarm is set for is a phone that stays quiet past the
        // moment the user picked.
        val chosen = now.plus(Duration.ofHours(1))

        chooseEnd(chosen, snoozeFixture(now))

        assertTrue(
            "the cap alarm has to move with the record",
            scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_CHECK_CAP },
        )
    }

    @Test
    fun `a time past the backstop never buys more silence`() {
        // The sheet clamps to what it believes the backstop is; this is what
        // happens when that belief is wrong. A stale reading must never buy
        // silence past the eight hours the whole design leans on.
        //
        // What declines it today is the "not sooner than the cap" guard rather
        // than the backstop clamp beside it: no record can carry a cap later
        // than its own ceiling yet, so anything above the ceiling is above the
        // cap too. This pins the guarantee, which holds either way, rather than
        // whichever line currently delivers it.
        val record = snoozeFixture(now, startedAgo = Duration.ofHours(1))
        val beyond = now.plus(Duration.ofHours(20))

        chooseEnd(beyond, record)

        val stored = requireNotNull(ActiveSnoozeStore(appContext).load())
        assertEquals(
            "the backstop is the ceiling, whatever the sheet asked for",
            record.capExpiresAt,
            stored.capExpiresAt,
        )
    }

    @Test
    fun `a time inside the floor is declined, not quietly moved later`() {
        // Thirty minutes is the shortest snooze the sheet may produce
        // (SPEC.md §7). A sheet left open long enough can be offering a time
        // that has since fallen inside it — and clamping that up meant a row
        // reading 14:00 committed 14:15 and reported success, dismissing over a
        // deadline the user was never shown (Codex, PR #118).
        val record = snoozeFixture(now)

        chooseEnd(now.plus(Duration.ofMinutes(2)), record)

        assertEquals(
            "the cap the user never chose must not be written",
            record.capExpiresAt,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
        assertEquals(
            "and the sheet has to hear that nothing was applied",
            EndChoiceResult.REFUSED,
            reported,
        )
    }

    @Test
    fun `a time later than the cap already set leaves it alone`() {
        // Not a failure and not a lengthening: the snooze already ends no later
        // than the moment chosen, so the choice is honored by doing nothing.
        // `+30 min` is the only thing that moves a cap outward (SPEC.md §4.3).
        val record = snoozeFixture(now, capIn = Duration.ofHours(2))

        chooseEnd(now.plus(Duration.ofHours(5)), record)

        assertEquals(record.capExpiresAt, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        // `postedOneShot` would see the ongoing notification too, so this asks
        // the narrower question: nothing failed, so no failure was reported.
        assertFalse(
            "nothing failed, so nothing is reported",
            shadeShows(stringOf(R.string.failure_could_not_set_end)),
        )
        // Applied, not failed: the snooze already ends no later than the moment
        // chosen, so the sheet dismisses rather than claiming a problem.
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    @Config(shadows = [TogglableAlarmManager::class])
    fun `a refused alarm leaves the cap where it was and says so`() {
        // The alarm moves first precisely so this case exists: it failed, so
        // nothing else moves either, and the user is told rather than left with
        // a sheet that dismissed over an unchanged deadline.
        val record = snoozeFixture(now)
        val service = startService(SnoozeService.ACTION_RESTORE, record)

        // Armed only now, so the restore above got its own cap scheduled and
        // this refusal is the one the chosen time meets.
        TogglableAlarmManager.refuse = true
        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(
                SnoozeService.EXTRA_CAP_EXPIRES_AT,
                now.plus(Duration.ofHours(1)).toEpochMilli(),
            )
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        assertEquals(record.capExpiresAt, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        assertTrue(
            "a refused change has to be said, not swallowed",
            shadeShows(stringOf(R.string.failure_could_not_set_end)),
        )
        // And said in the sheet, not only in the shade: a tile-first user may
        // have denied notifications, and then the card above is invisible.
        assertEquals(EndChoiceResult.REFUSED, reported)
    }

    @Test
    fun `a notification's chosen time that is refused says so in the shade`() {
        // The ongoing notification's `Until <time>` action carries no request
        // id, because there is no sheet behind it — so the refusal above has
        // nowhere to show inline the way a row does, and would otherwise be a
        // button that silently did nothing (SPEC.md §4.5; AGENTS.md,
        // principle 2). The case is real: the card is only rebuilt on a state
        // change, so an offered time can fall inside the floor while it sits
        // in the shade.
        val record = snoozeFixture(now)
        val service = startService(SnoozeService.ACTION_RESTORE, record)

        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(
                SnoozeService.EXTRA_CAP_EXPIRES_AT,
                now.plus(Duration.ofMinutes(2)).toEpochMilli(),
            )
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, record.startedAt.toEpochMilli())
        }

        assertEquals(
            "the cap the user never chose must not be written",
            record.capExpiresAt,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
        assertTrue(
            "a tap with no sheet behind it still has to be answered somewhere",
            shadeShows(stringOf(R.string.failure_could_not_set_end)),
        )
    }

    @Test
    fun `a notification's chosen time that applies posts no failure`() {
        // The other half of the guard above: it fires on `REFUSED` alone, so
        // an ordinary sheet-less commit must not leave a card behind it.
        val record = snoozeFixture(now)
        val service = startService(SnoozeService.ACTION_RESTORE, record)
        val chosen = now.plus(Duration.ofHours(1))

        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, chosen.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, record.startedAt.toEpochMilli())
        }

        assertEquals(chosen, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        assertFalse(
            "nothing failed, so nothing is reported",
            shadeShows(stringOf(R.string.failure_could_not_set_end)),
        )
    }

    @Test
    fun `a chosen time with no snooze running changes nothing and posts nothing`() {
        // The sheet outlived its snooze — a refused arm, or the cap or a
        // departure getting there first. Whichever happened has already posted
        // its own card; a second one would report the same event twice.
        chooseEnd(now.plus(Duration.ofHours(1)), null)

        assertNull(ActiveSnoozeStore(appContext).load())
        assertNull(postedOneShot())
        // `GONE`, not `REFUSED`: nothing failed and nothing is retryable — the
        // snooze is over, so the sheet dismisses rather than standing there
        // offering a tap that can only fail the same way (Codex, PR #118).
        // Whatever ended it has already posted its own card.
        assertEquals(EndChoiceResult.GONE, reported)
    }

    @Test
    fun `plus 30 min never answers a sheet waiting on a chosen time`() {
        // The channel belongs to ACTION_SET_CAP alone. `+30 min` has a rollback
        // block that reads almost identically to this action's, and a report
        // placed in that one instead reached a sheet waiting on an unrelated
        // tap — which would read the extension's outcome as its own rejection
        // (Codex, PR #118). Nothing about extending is the sheet's business.
        //
        // **What this does and does not cover.** It pins the ordinary extend
        // path, which is the reachable one. The branch that actually carried
        // the stray report needs a refused `SharedPreferences.commit()`
        // followed by an accepted one, which the harness cannot produce — the
        // same limit that made the equivalent `setCap` branch untestable and
        // sent that one to a total function instead. The guard there is
        // structural: `applyChosenEnd` returns the outcome and `setCap` is the
        // only caller of `report`, so a stray call is a visible extra
        // reference rather than a missing one.
        val record = snoozeFixture(now, capIn = Duration.ofHours(2))

        startService(SnoozeService.ACTION_EXTEND, record)

        assertNull("an extension is not an answer to a chosen end time", reported)
    }

    @Test
    fun `a start carrying no time leaves the cap alone`() {
        val record = snoozeFixture(now)

        startService(SnoozeService.ACTION_SET_CAP, record) {
            // The time is what is missing here, not the sender: a real commit
            // always names itself, and the refusal has to reach the sheet that
            // asked.
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        assertEquals(record.capExpiresAt, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        assertEquals(EndChoiceResult.REFUSED, reported)
    }
}
