package app.snoozemo.snooze

import android.content.Intent
import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.SnoozeDebugLog
import app.snoozemo.core.TrackingMode
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

    /** `Until I leave`: no time, the record's own ceiling. */
    private fun restoreEnd(record: ActiveSnooze?) =
        startService(SnoozeService.ACTION_SET_CAP, record) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
            record?.let { putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, it.startedAt.toEpochMilli()) }
        }

    @Test
    fun `restoring puts a shortened cap back to its ceiling`() {
        val record = snoozeFixture(now).let { it.copy(capExpiresAt = now.plus(Duration.ofHours(1))) }

        restoreEnd(record)

        assertEquals(record.capCeilingAt, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `restoring never runs past the ceiling the snooze started with`() {
        // The one control besides `+30 min` that lengthens a cap, and it is
        // bounded by the same backstop: what it restores is where the snooze
        // was already heading before the user shortened it.
        val record = snoozeFixture(now)

        restoreEnd(record)

        val after = ActiveSnoozeStore(appContext).load()
        assertEquals("already at its ceiling, so nothing moves", record.capExpiresAt, after?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `a departure restore is declined once nothing can track a departure now`() {
        // The hazard the live read exists for. A timer-only snooze stops its
        // watch, and `mode` is only ever recomputed from a presence update —
        // so the record goes on claiming the capability it had when the watch
        // came down. Revoking location in that window announces itself to
        // nobody: Android broadcasts no permission change, and `MODE_CHANGED`
        // reaches only a registered receiver. The frozen mode used to pass the
        // guard and the restore then pushed the cap back out to the ceiling.
        val record = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
        )
        assertTrue(
            "precondition: the record still claims it can track a departure",
            record.mode.tracksDeparture,
        )
        // Restored while the grants are there, so the controller's mode is
        // honest and departure-tracking — then revoked underneath it, which is
        // the window a stopped watch cannot notice.
        val service = startService(SnoozeService.ACTION_RESTORE, record)
        SnoozeDebugLog.resetForTest()
        TestSnoozeService.presence.canActOnAnchors = false

        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        assertEquals(
            "the shortened cap stands rather than going back to the ceiling",
            record.capExpiresAt,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
        assertEquals("and the tap is not left looking accepted", EndChoiceResult.REFUSED, reported)
        // **Which guard fired matters.** The record's own mode check would
        // decline this too if the controller had been lowered, and then this
        // test would pass while proving nothing about the live read.
        assertTrue(
            "the live capability check is what declined it",
            SnoozeDebugLog.snapshot().any { it.contains("nothing can track a departure now") },
        )
    }

    @Test
    fun `a departure restore still works while the grants are there`() {
        // The other direction, and the one that matters most: the live read
        // must not decline a restore that is perfectly fine, which would make
        // `Until I leave` a one-way door again.
        val record = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
        )

        restoreEnd(record)

        assertEquals("the restore applied", EndChoiceResult.APPLIED, reported)
        val after = ActiveSnoozeStore(appContext).load()
        assertEquals("the exit is back", true, after?.endsOnDeparture)
        assertEquals("and the cap is back at the ceiling", record.capCeilingAt, after?.capExpiresAt)
    }

    @Test
    fun `a departure restore is declined once the snooze tracks no departure`() {
        // A snooze that degrades mid-flight keeps its `startedAt`, so the
        // identity check passes and the screen's own mode check is by
        // definition a moment behind. Restoring anyway would leave the phone
        // silent for up to the full ceiling with nothing watching for the
        // departure the row promised (Codex, PR #234).
        val record = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            mode = TrackingMode.DURATION_ONLY,
        )

        restoreEnd(record)

        assertEquals(
            "the shortened cap stands",
            record.capExpiresAt,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
        assertEquals("and the tap is not left looking accepted", EndChoiceResult.REFUSED, reported)
    }

    @Test
    fun `restoring takes the movement exit off`() {
        // The maintainer's case (2026-09-11): tap `Until I move`, then tap
        // `Until I leave`. `Until I move` moves no cap, so this snooze is still
        // at its ceiling and the cap half of the restore has nothing to do —
        // which is exactly why the clear cannot live behind that early return,
        // or the second tap would change nothing at all.
        val record = snoozeFixture(now).copy(endsOnMotion = true)

        restoreEnd(record)

        val after = ActiveSnoozeStore(appContext).load()
        assertEquals("the movement exit is forgotten", false, after?.endsOnMotion)
        assertEquals("and the cap it never moved stands", record.capExpiresAt, after?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `restoring takes the movement exit off and puts the cap back together`() {
        // Both halves in one tap, and the ordering that makes it possible: the
        // cap write copies from a snapshot, so clearing after it would carry
        // the flag straight back in.
        val record = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnMotion = true,
        )

        restoreEnd(record)

        val after = ActiveSnoozeStore(appContext).load()
        assertEquals("the movement exit is forgotten", false, after?.endsOnMotion)
        assertEquals("and the cap is back at its ceiling", record.capCeilingAt, after?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `a chosen time takes departure tracking off`() {
        // The maintainer's ask: `Until (time)` starts or switches to a
        // timer-only snooze. A chosen time used to add a deadline beside
        // whatever else was armed, so 14:00 on a departure snooze still ended
        // when the user walked out at 13:40 — the row naming one thing and the
        // snooze doing another (SPEC.md §4.4, §7).
        val record = snoozeFixture(now, capIn = Duration.ofHours(5))
        assertTrue("the fixture has to start with departure armed", record.endsOnDeparture)

        chooseEnd(now.plus(Duration.ofHours(2)), record)

        assertEquals(
            "the timer is the only exit now",
            false,
            ActiveSnoozeStore(appContext).load()?.endsOnDeparture,
        )
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `restoring departure puts the exit back`() {
        // The way out of the one above, and why the `Until I leave` row is
        // offered from what the machinery can do rather than from what this
        // snooze currently does: a snooze narrowed to its timer is exactly the
        // one whose row has something to do.
        val record = snoozeFixture(now, capIn = Duration.ofHours(2))
            .copy(endsOnDeparture = false)

        restoreEnd(record)

        assertEquals(
            "departure ends this snooze again",
            true,
            ActiveSnoozeStore(appContext).load()?.endsOnDeparture,
        )
    }

    /**
     * The mirror of the first P1 on this PR, and the rule generalized: within
     * the exits as well as between the exits and the cap, **the addition goes
     * first and the removal last**, because the removal is the half whose
     * failure can make a snooze outlast something.
     *
     * `Until I leave` over a timer-only snooze that has since taken a movement
     * exit has two writes to do. Clearing movement first meant a clear that
     * landed over a departure write that did not left *both* flags off under
     * the still-shortened cap — nothing watching at all, produced by the one
     * control the user reached for to put an exit back (Codex, PR #267).
     *
     * Refuses the departure write by name rather than refusing everything: a
     * switch that fails every write never gets past the first one, so it
     * cannot tell the two orderings apart.
     */
    @Test
    fun `a departure restore never leaves both exits off`() {
        val record = snoozeFixture(now, capIn = Duration.ofHours(2))
            .copy(endsOnDeparture = false, endsOnMotion = true)
        val service = startService(SnoozeService.ACTION_RESTORE, record)

        TestSnoozeService.refuseRecordUpdateWhen = { it.endsOnDeparture }
        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
            putExtra(SnoozeService.EXTRA_CHOICE_FOR_SNOOZE, record.startedAt.toEpochMilli())
        }

        val after = ActiveSnoozeStore(appContext).load()
        assertTrue(
            "a refused restore must still leave something ending this snooze",
            after != null && (after.endsOnDeparture || after.endsOnMotion),
        )
        assertEquals(
            "and the choice is reported as refused, since it did not take",
            EndChoiceResult.REFUSED,
            reported,
        )
    }

    @Test
    fun `a chosen time that changes no cap still takes departure off`() {
        // The exits and the cap move together, and the cap being already right
        // is not a reason to leave an exit the user replaced. Picking the time
        // the snooze already ends at is the one no-op left for the *cap* — it
        // is not a no-op for what ends the snooze.
        val record = snoozeFixture(now, capIn = Duration.ofHours(2))

        chooseEnd(record.capExpiresAt, record)

        assertEquals(
            "the cap is unchanged",
            record.capExpiresAt,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
        assertEquals(
            "and the timer is still made the only exit",
            false,
            ActiveSnoozeStore(appContext).load()?.endsOnDeparture,
        )
    }

    @Test
    fun `a chosen time takes the movement exit off too`() {
        // `Until (time)` replaces the end conditions rather than adding to
        // them, so *both* go — a significant-motion listener left armed ends a
        // "timer-only" snooze the moment the user stands up (Codex, PR #267).
        // This test asserted the opposite until the replacement model reached
        // the time row; it is the premise that changed, not the test.
        val record = snoozeFixture(now).copy(endsOnMotion = true)
        val chosen = now.plus(Duration.ofHours(1))

        chooseEnd(chosen, record)

        val after = ActiveSnoozeStore(appContext).load()
        assertEquals("the movement exit is off", false, after?.endsOnMotion)
        assertEquals("and so is departure", false, after?.endsOnDeparture)
        assertEquals(chosen, after?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    /**
     * The repaint the state machine will not do for this change.
     *
     * [SnoozeService.onStateChanged]'s `ARMING` branch deliberately posts no
     * card and skips the tile refresh — it runs from inside `beginArming`,
     * before the rule goes on, and a `notify` there would sit on the
     * tap-to-`STATE_TRUE` stretch. Right for the arm's own delivery, wrong for
     * a choice the user makes during that window — and choosing a time from
     * the tile sheet lands squarely in it, since the anchor capture can hold
     * `ARMED` off for ten seconds. The card went on saying the snooze ends when
     * you leave until the capture happened to finish (Codex, PR #267).
     *
     * The capture is deliberately never delivered here, which is what keeps
     * the state at `ARMING` — the ordinary tile-sheet window, not an edge of
     * it.
     */
    @Test
    fun `choosing a time repaints the card before the capture lands`() {
        val service = startService(SnoozeService.ACTION_ARM)
        // The arm's own card, posted the moment the rule went on. The mode is
        // `SETTLING` until the capture lands, so this is `Waiting for
        // location` rather than `Ends when you leave` — which is the state the
        // choice below has to move it off.
        assertTrue(
            "precondition: the card should still be waiting on the capture",
            shadeText().contains(stringOf(R.string.ongoing_settling)),
        )

        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(
                SnoozeService.EXTRA_CAP_EXPIRES_AT,
                now.plus(Duration.ofHours(1)).toEpochMilli(),
            )
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        val text = shadeText()
        assertTrue(
            "the card has to say what the snooze now ends on, not what it did",
            text.contains(stringOf(R.string.ongoing_timer_only)),
        )
        assertFalse(
            "and must stop waiting on a location it will never use",
            text.contains(stringOf(R.string.ongoing_settling)),
        )
    }

    /**
     * A stale intent must not take down the running snooze's card on its way
     * to `GONE`.
     *
     * The previous attempt's warning is cleared as an attempt *starts* rather
     * than when one succeeds, which is what lets this attempt's own warnings
     * survive. Done at the top of the action that runs for *every* intent, it
     * also cleared for intents that turn out not to be about the running
     * snooze at all — so an old action arriving late silently removed a
     * current `Still ends when you leave` and then returned `GONE` without
     * replacing it (Codex, PR #267). Nothing clears by position any more: a
     * card comes down when something truer replaces it, or on the one outcome
     * that has nothing left to say.
     */
    @Test
    fun `restoring departure names the exit again on the ongoing card`() {
        // The way back from a chosen time puts departure back and takes the
        // movement exit off, and the card that says what ends this snooze is
        // rebuilt from the record — so it names the new pair with nothing
        // retired by hand. This is what replaced a warning card whose lifetime
        // every one of those operations had to remember (SPEC.md §4.4).
        val record = snoozeFixture(now)
            .copy(capExpiresAt = now.plus(Duration.ofHours(1)), endsOnMotion = true)
        val service = startService(SnoozeService.ACTION_RESTORE, record)

        TestSnoozeService.refuseRecordUpdates = true
        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, record.capExpiresAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }
        assertEquals(
            "precondition: both exits stayed armed, so the choice is partial",
            EndChoiceResult.PARTIAL,
            reported,
        )

        TestSnoozeService.refuseRecordUpdates = false
        service.send(SnoozeService.ACTION_SET_CAP, startId = 3) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        assertEquals("the restore applied", EndChoiceResult.APPLIED, reported)
        val body = ongoingBody()
        assertTrue(
            "the departure the restore put back is named: $body",
            body?.startsWith(stringOf(R.string.ongoing_ends_when_you_leave)) == true,
        )
        assertFalse(
            "and the movement exit it took off is not: $body",
            body?.contains(movementClause()) == true,
        )
    }

    @Test
    fun `a restore that cannot clear the movement exit says so`() {
        // A restore is two independent writes, and the retire that belongs to
        // the first must not hide what the second failed to do. Departure goes
        // back — which retires any warning about it — and then the movement
        // clear is refused, leaving an exit armed that the user has just been
        // shown nothing about (Codex, PR #267). `makeTimerOnly` states what
        // survived after both halves; this is the mirror case.
        val record = snoozeFixture(now).copy(
            capExpiresAt = now.plus(Duration.ofHours(1)),
            endsOnDeparture = false,
            endsOnMotion = true,
        )
        val service = startService(SnoozeService.ACTION_RESTORE, record)

        // By name rather than refusing everything: the departure write has to
        // land for this to be the case it is about.
        TestSnoozeService.refuseRecordUpdateWhen = { !it.endsOnMotion }
        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_RESTORE_END, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        assertEquals("the restore could not finish", EndChoiceResult.REFUSED, reported)
        // Not `PARTIAL`: the cap restore below the exit work never ran, so the
        // snooze still ends at the shortened time — earlier than asked, which
        // is the safe direction and a retry away. What it does still end on is
        // the ongoing card's job, and it says so without being told to.
        val body = ongoingBody()
        assertTrue(
            "the exit it could not clear is still named: $body",
            body?.contains(movementClause()) == true,
        )
    }

    @Test
    fun `the departure choice is written under the name the tile reads`() {
        val record = snoozeFixture(now)

        chooseEnd(now.plus(Duration.ofHours(1)), record)

        val prefs = appContext.getSharedPreferences("active_snooze", android.content.Context.MODE_PRIVATE)
        assertTrue("the key has to exist for the tile to read it", prefs.contains("ends_on_departure"))
        assertFalse(
            "and it has to carry the choice, not the default",
            prefs.getBoolean("ends_on_departure", true),
        )
    }

    /**
     * The warning `makeTimerOnly` posts has to survive the call that posts it.
     *
     * `setCap` clears the previous attempt's failure card, and it used to do
     * that *after* running the attempt, keyed off `APPLIED` — so an exit that
     * would not come off posted its card and had it deleted a line later, and
     * the user was told the whole choice took while a movement exit was still
     * armed to end their "timer-only" snooze (Codex, PR #267).
     *
     * The time chosen is the one the record already ends at, which is the
     * branch that reaches the narrowing with no cap write of its own — so the
     * refused write below is the exit's, not the cap's, and the outcome really
     * is a partial success rather than a plain refusal.
     */
    @Test
    fun `an exit that will not come off answers partial, not applied`() {
        val record = snoozeFixture(now).copy(endsOnMotion = true)
        val service = startService(SnoozeService.ACTION_RESTORE, record)

        TestSnoozeService.refuseRecordUpdates = true
        service.send(SnoozeService.ACTION_SET_CAP, startId = 2) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, record.capExpiresAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

        // **`PARTIAL`, not `APPLIED`**: the deadline is set, so refusing would
        // describe the opposite failure — but an exit stayed armed, and a sheet
        // that dismissed here would look exactly like one dismissing on a clean
        // apply. The sheet says so once; the ongoing card carries which exit.
        assertEquals(
            "the time applied over exits that stayed armed",
            EndChoiceResult.PARTIAL,
            reported,
        )
        assertFalse(
            "and not as a failure to set the time, which did apply",
            shadeShows(stringOf(R.string.failure_could_not_set_end)),
        )
        // Both writes are refused here, so both exits survive — and the card
        // rebuilt from the record names both, with nothing posted for it.
        // Naming only the one that failed first left the snooze ending on a
        // departure the user had been told nothing about (Codex, PR #267).
        val body = ongoingBody()
        assertTrue(
            "the departure that stayed armed is named: $body",
            body?.startsWith(stringOf(R.string.ongoing_ends_when_you_leave)) == true,
        )
        assertTrue(
            "and so is the movement exit: $body",
            body?.contains(movementClause()) == true,
        )
    }

    /**
     * What the ongoing card appends when the movement exit is armed, taken from
     * the resource rather than spelled out — the assertions are about whether
     * the clause is there, not about its wording.
     */
    private fun movementClause(): String =
        appContext.getString(R.string.ongoing_or_when_you_move, "").trimStart()

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
        // The fixture already runs to its backstop, so the clamp lands the
        // request exactly on the cap and there is nothing to write. `a
        // lengthening chosen time stops at the backstop` covers the case where
        // the clamp has somewhere to move to. This pins the guarantee, which
        // holds either way, rather than whichever line delivers it.
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
    fun `a time later than the cap already set moves the cap out to it`() {
        // The half that makes `+` a stepper rather than a one-way door
        // (maintainer, 2026-09-11: "the plus button should always be available
        // to increase that time"). This used to be honored by doing nothing —
        // a chosen time could only shorten — so a snooze stepped down to two
        // hours could never be stepped back up, whatever the sheet offered.
        //
        // Still bounded: the fixture's backstop is seven hours out, and five is
        // inside it.
        val record = snoozeFixture(now, capIn = Duration.ofHours(2))
        val chosen = now.plus(Duration.ofHours(5))

        chooseEnd(chosen, record)

        assertEquals(
            "the cap moves out to the time the user chose",
            chosen,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
        // `postedOneShot` would see the ongoing notification too, so this asks
        // the narrower question: nothing failed, so no failure was reported.
        assertFalse(
            "nothing failed, so nothing is reported",
            shadeShows(stringOf(R.string.failure_could_not_set_end)),
        )
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `a time exactly on the cap already set changes nothing`() {
        // The one real no-op left: the snooze already ends at the moment the
        // user picked, so their choice is honored by doing nothing. Narrower
        // than it was — anything not *equal* is now a move, in one direction or
        // the other — and that narrowing is what the test above needed.
        val record = snoozeFixture(now, capIn = Duration.ofHours(2))

        chooseEnd(record.capExpiresAt, record)

        assertEquals(record.capExpiresAt, ActiveSnoozeStore(appContext).load()?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `a lengthening chosen time stops at the backstop`() {
        // `+` reaches further than it did, and §7 is what it reaches *to*. The
        // clamp used to be unreachable — a request above the ceiling was above
        // the cap too, and the direction test declined it first — so this is
        // the case that turned it from defensive into load-bearing.
        val record = snoozeFixture(now, startedAgo = Duration.ofHours(3), capIn = Duration.ofHours(1))

        chooseEnd(now.plus(Duration.ofHours(20)), record)

        assertEquals(
            "the backstop the snooze armed with, not the time asked for",
            record.capCeilingAt,
            ActiveSnoozeStore(appContext).load()?.capExpiresAt,
        )
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
        assertEquals(
            "and the exits it was being traded for stay armed: a cap that did " +
                "not move plus a departure that came off is eight hours of " +
                "silence after the user leaves (Codex, PR #267)",
            true,
            ActiveSnoozeStore(appContext).load()?.endsOnDeparture,
        )
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
