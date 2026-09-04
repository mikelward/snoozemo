package app.snoozemo.snooze

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.BorrowedRinger
import app.snoozemo.core.RingerMode
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.dnd.AudioRingerController
import app.snoozemo.dnd.PrefsRingerLoanStore
import app.snoozemo.dnd.RingerFailure
import app.snoozemo.dnd.RingerLoanStore
import app.snoozemo.core.RingerHandBack
import app.snoozemo.dnd.RingerOutcome
import app.snoozemo.dnd.RingerShortfall
import app.snoozemo.dnd.installRingerHandBackRetry
import app.snoozemo.dnd.installRingerStuckNotice
import app.snoozemo.dnd.SnoozeRingerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The ringer ceiling end to end (SPEC.md §5.9): the real stores on disk, the
 * real `AudioManager`, and the decision in `:core` between them.
 *
 * `RingerHandoverTest` already enumerates the decisions on the JVM, so what is
 * worth testing here is the part a fake would pass while the product broke —
 * that the way back is written *durably*, and that a fresh instance can
 * therefore still hand the ringer back after the process that took it is gone.
 * That is the whole safety property, and an in-memory double cannot fail it.
 */
@RunWith(RobolectricTestRunner::class)
// A plain `Application`, not `SnoozemoApplication`: its `onCreate` starts
// `reconcileRingerInBackground` on a daemon thread, and Robolectric builds the
// application for every test — so that thread races the test body for the
// process-wide ringer lock. Reaching it while no snooze record is on disk, it
// does exactly its job: drops the ceiling as stale and hands the loan back,
// under a fixture that put both there by hand. The tests below reach that path
// deliberately where they mean to; a stray copy of it running under every
// statement is what made them fail about one run in ten.
@Config(sdk = [36], application = Application::class)
class AudioRingerControllerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val audio: AudioManager
        get() = context.getSystemService(AudioManager::class.java)

    private fun newController(loans: RingerLoanStore = PrefsRingerLoanStore(context)) =
        AudioRingerController(context, SnoozeRingerStore(context), loans)

    /** Takes the platform service away, the way a null lookup would. */
    private fun loseTheAudioManager() {
        shadowOf(context as android.app.Application).setSystemService(Context.AUDIO_SERVICE, null)
    }

    private fun choose(ceiling: SnoozeRinger) {
        SnoozeRingerStore(context).setChosen(ceiling)
    }

    @Before
    fun startAudible() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        PrefsRingerLoanStore(context).clear()
        PrefsRingerLoanStore(context).recordChoice(null)
        PrefsRingerLoanStore(context).recordHandBackFailures(0)
        // The notice hook is process-wide, so a test that installs a collector
        // would otherwise go on collecting for every test after it.
        installRingerStuckNotice { }
        installRingerHandBackRetry { }
        // The ceiling too, so a test that chooses one cannot leak into the next
        // — Robolectric keeps the preferences file for the whole class.
        SnoozeRingerStore(context).setChosen(SnoozeRinger.DEFAULT)
    }

    @Test
    fun `the default ceiling brings an audible phone down to vibrate`() {
        // No stored choice at all, which is what a fresh install has.
        val outcome = newController().quiet()

        assertEquals(RingerOutcome.Set(RingerMode.VIBRATE), outcome)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
    }

    @Test
    fun `the way back outlives the instance that took it`() {
        newController().quiet()

        // A different instance, because that is what the release path is after
        // process death: the record on disk is the only thing that knows the
        // phone used to ring.
        val outcome = newController().giveBack()

        assertEquals(RingerOutcome.Set(RingerMode.NORMAL), outcome)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertNull(PrefsRingerLoanStore(context).borrowed())
    }

    @Test
    fun `ring as usual leaves the ringer where the user put it`() {
        choose(SnoozeRinger.RING)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL

        assertEquals(RingerOutcome.Untouched, newController().quiet())
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertNull(PrefsRingerLoanStore(context).borrowed())
    }

    @Test
    fun `a silent phone is not raised to the vibrate ceiling`() {
        choose(SnoozeRinger.VIBRATE)
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT

        assertEquals(RingerOutcome.Untouched, newController().quiet())
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
        // Nothing was taken, so a later give-back has nothing to make audible —
        // which is the point: the phone was already quieter than asked for.
        assertNull(PrefsRingerLoanStore(context).borrowed())
    }

    @Test
    fun `the silent ceiling takes a ringing phone all the way down`() {
        choose(SnoozeRinger.SILENT)

        assertEquals(RingerOutcome.Set(RingerMode.SILENT), newController().quiet())
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)

        newController().giveBack()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun `a re-asserted arm keeps the original way back`() {
        newController().quiet()
        // What a restore after process death does, and what the cap alarm's own
        // re-arm does: `setSnoozed(true)` again on a phone already quieted.
        assertEquals(RingerOutcome.Untouched, newController().quiet())

        newController().giveBack()

        // NORMAL, not VIBRATE — a second borrow would have recorded the quiet
        // mode as the way back and left the phone buzzing for good.
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun `a ringer the user moved mid-snooze is left as they set it`() {
        newController().quiet()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT

        // `Disowned` rather than `Untouched`, so a caller can tell "theirs now"
        // from "nothing was ever taken" — a refused zen release re-applies the
        // ceiling and must not undo this one (Codex, PR #176).
        assertEquals(RingerOutcome.Disowned, newController().giveBack())
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
        // And the loan goes, so the next snooze starts from their choice
        // rather than putting the pre-snooze mode back at some later end.
        assertNull(PrefsRingerLoanStore(context).borrowed())
    }

    @Test
    fun `a way back that cannot be stored means the ringer is not taken`() {
        val outcome = newController(loans = RefusingLoanStore()).quiet()

        assertEquals(RingerOutcome.Refused(RingerFailure.NOT_RECORDED), outcome)
        // The whole reason the write is checked: a quieted phone with no record
        // of what it was stays quiet after the snooze ends, and nothing
        // anywhere knows better. Ringing through one snooze is the cheaper
        // mistake.
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun `handing back is safe with nothing outstanding`() {
        // What the start-up check and every release of an un-quieted snooze do.
        assertEquals(RingerOutcome.Untouched, newController().giveBack())
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun `the idle hand-back leaves a loan alone while a snooze holds it`() {
        newController().quiet()

        val outcome = newController().giveBackIfIdle(snoozeRunning = { true })

        // The snooze's own release is what hands it back. Stepping in here
        // would un-quiet a phone that is still meant to be quiet.
        assertEquals(RingerOutcome.Untouched, outcome)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertEquals(RingerMode.NORMAL, PrefsRingerLoanStore(context).borrowed()?.restoreTo)
    }

    @Test
    fun `the idle hand-back restores a loan no snooze is holding`() {
        newController().quiet()

        val outcome = newController().giveBackIfIdle(snoozeRunning = { false })

        assertEquals(RingerOutcome.Set(RingerMode.NORMAL), outcome)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertNull(PrefsRingerLoanStore(context).borrowed())
        // And the ceiling with it: the loan's own clear deliberately leaves the
        // choice for the release path's `forgetCeiling`, which nothing calls
        // here — so left behind it would be the next arm's ceiling (Codex,
        // PR #176).
        assertNull(PrefsRingerLoanStore(context).activeChoice())
    }

    @Test
    fun `an idle check with no loan still drops the ceiling`() {
        // A phone already at the ceiling never borrows, so there is no loan to
        // resolve — and the choice record is exactly what would otherwise
        // survive into the next snooze.
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        newController().quiet()
        assertEquals(SnoozeRinger.VIBRATE, PrefsRingerLoanStore(context).activeChoice())

        val outcome = newController().giveBackIfIdle(snoozeRunning = { false })

        assertEquals(RingerOutcome.Untouched, outcome)
        assertNull(PrefsRingerLoanStore(context).activeChoice())
    }

    @Test
    fun `the shortfall is the live mode when the phone is louder than the ceiling`() {
        newController().quiet()
        // Turned back up mid-snooze, under the default `Vibrate` ceiling —
        // what the ongoing card turns into "still ringing".
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL

        assertEquals(RingerShortfall.Louder(RingerMode.NORMAL), newController().shortfall())
    }

    @Test
    fun `an unreadable mode under a ceiling is a shortfall of its own`() {
        newController().quiet()
        // No `AudioManager` at all, which is one of the two ways the live mode
        // goes unreadable (the other is a ringer constant this build does not
        // know). Arming faced the same answer and declined to borrow, so the
        // ceiling is certainly not holding; which mode the phone is in is the
        // part nobody can say (Codex, PR #176).
        loseTheAudioManager()

        assertEquals(RingerShortfall.Unknown, newController().shortfall())
    }

    @Test
    fun `an unreadable mode with no ceiling in force says nothing`() {
        choose(SnoozeRinger.RING)
        newController().quiet()
        loseTheAudioManager()

        // Nothing was promised, so there is nothing to fall short of.
        assertNull(newController().shortfall())
    }

    @Test
    fun `handing the ringer back retires a stuck notice`() {
        val reports = mutableListOf<Boolean>()
        installRingerStuckNotice { reports += it }
        newController().quiet()

        newController().giveBack()

        // The notice is posted by the code that gives up and would otherwise
        // outlive the problem: a completed hand-back is the only thing that can
        // say it is over (Codex, PR #176).
        assertEquals(listOf(false), reports)
    }

    @Test
    fun `a ringer the user took over retires a stuck notice too`() {
        val reports = mutableListOf<Boolean>()
        installRingerStuckNotice { reports += it }
        newController().quiet()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT

        newController().giveBack()

        // Disowned rather than handed back, and nothing is owed either way.
        assertEquals(listOf(false), reports)
    }

    @Test
    fun `no snooze is holding a ceiling, so there is nothing to fall short of`() {
        // A ringing phone and a `Vibrate` choice, but nothing armed: the choice
        // applies to the next snooze, not to a card there is no snooze for.
        assertNull(newController().shortfall())
    }

    @Test
    fun `there is no shortfall once the ceiling is applied`() {
        newController().quiet()

        assertNull(newController().shortfall())
    }

    @Test
    fun `there is no shortfall on a phone already quieter than the ceiling`() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        // Nothing borrowed — and the ceiling is still in force and still met,
        // which is the case a loan-derived answer got wrong (Codex, PR #176).
        newController().quiet()

        assertNull(newController().shortfall())
    }

    @Test
    fun `ring as usual has no ceiling to fall short of`() {
        choose(SnoozeRinger.RING)
        newController().quiet()

        assertNull(newController().shortfall())
    }

    @Test
    fun `a ceiling that could not be applied is reported even with nothing borrowed`() {
        // The way back was unstorable, so the borrow declined and the phone is
        // still ringing — the one case the card most has to name.
        val outcome = newController(UnrecordableLoan(PrefsRingerLoanStore(context))).quiet()

        assertEquals(RingerOutcome.Refused(RingerFailure.NOT_RECORDED), outcome)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertEquals(RingerShortfall.Louder(RingerMode.NORMAL), newController().shortfall())
    }

    @Test
    fun `a ceiling met by a phone that never borrowed survives a later choice`() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        newController().quiet()
        // `Silent` from here on. This snooze was told `Vibrate` and is doing
        // it, so the card has nothing to report (Codex, PR #176).
        choose(SnoozeRinger.SILENT)

        assertNull(newController().shortfall())
    }

    @Test
    fun `the ceiling stops being in force when the snooze ends`() {
        newController().quiet()
        // The full release: hand the ringer back, then confirm the snooze is
        // over. The second half is the zen controller's, once the rule write
        // has actually succeeded (Codex, PR #176).
        newController().giveBack()
        newController().forgetCeiling()

        // Audible again because the snooze ended, which is not a shortfall.
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertNull(newController().shortfall())
    }

    @Test
    fun `the shortfall is judged against the running snooze's ceiling, not a later choice`() {
        newController().quiet()
        // Changed mid-snooze, which governs the *next* snooze — so this one is
        // still doing exactly what it was told (Codex, PR #176).
        choose(SnoozeRinger.SILENT)

        assertNull(newController().shortfall())
    }

    @Test
    fun `a later choice of ring cannot hide a ceiling the running snooze lost`() {
        newController().quiet()
        // The user turns the ringer back up mid-snooze, then picks `Ring as
        // usual` for next time. The card must still say this snooze is ringing.
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        choose(SnoozeRinger.RING)

        assertEquals(RingerShortfall.Louder(RingerMode.NORMAL), newController().shortfall())
    }

    @Test
    fun `a re-assertion keeps the ceiling the snooze started under`() {
        newController().quiet()
        // Changed mid-snooze, then the process dies and the restore re-asserts
        // — which must not adopt a choice made after the snooze began (Codex,
        // PR #176).
        choose(SnoozeRinger.SILENT)

        assertEquals(RingerOutcome.Untouched, newController().quiet())

        // Still vibrating, not silent, and nothing to report against `Vibrate`.
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertNull(newController().shortfall())
    }

    @Test
    fun `a re-assertion of a snooze that never borrowed stays hands-off`() {
        // The case with no loan to stop a second borrow: the phone was already
        // at the ceiling, so nothing was taken.
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        newController().quiet()
        choose(SnoozeRinger.SILENT)

        newController().quiet()

        // A restore must not quiet the phone further mid-snooze — applying a
        // changed ceiling to a running snooze is deferred, not silently done.
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertNull(PrefsRingerLoanStore(context).borrowed())
    }

    @Test
    fun `a ring snooze re-asserted does not adopt a later ceiling`() {
        choose(SnoozeRinger.RING)
        newController().quiet()
        // `Ring` records a choice of its own for exactly this reason: without
        // one there would be nothing to tell a re-assertion that this snooze
        // was never meant to be quiet (Codex, PR #176).
        choose(SnoozeRinger.VIBRATE)

        newController().quiet()

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertNull(newController().shortfall())
    }

    @Test
    fun `the next snooze is not held to the last one's ceiling`() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        newController().quiet()
        // Vanished without a release, so nothing cleared the record: the idle
        // check is what stops it governing the snooze after it.
        newController().giveBackIfIdle(snoozeRunning = { false })

        choose(SnoozeRinger.SILENT)
        val outcome = newController().quiet()

        assertEquals(RingerOutcome.Set(RingerMode.SILENT), outcome)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
    }

    @Test
    fun `a hand-back with no AudioManager still asks for a durable retry`() {
        val delays = mutableListOf<Long>()
        installRingerHandBackRetry { delays += it }
        newController().quiet()
        // The platform service gone, which is a refusal like any other: the zen
        // release goes ahead and takes the record and every other alarm with
        // it, so this path owes the same durable attempt as a refused write
        // (Codex, PR #176).
        loseTheAudioManager()

        val outcome = newController().giveBack()

        assertEquals(RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED), outcome)
        assertEquals(listOf(RingerHandBack.FIRST_RETRY_MILLIS), delays)
        // And the loan stays, since it is the only thing that knows what to
        // put back.
        assertEquals(RingerMode.NORMAL, PrefsRingerLoanStore(context).borrowed()?.restoreTo)
        assertEquals(1, PrefsRingerLoanStore(context).handBackFailures())
    }

    @Test
    fun `handing the ringer back does not forget the ceiling by itself`() {
        newController().quiet()

        newController().giveBack()

        // The hand-back runs *before* the zen rule goes off, and a refused rule
        // write keeps the snooze running — so the ceiling stays on record until
        // the release is confirmed (Codex, PR #176).
        assertEquals(SnoozeRinger.VIBRATE, PrefsRingerLoanStore(context).activeChoice())

        newController().forgetCeiling()
        assertNull(PrefsRingerLoanStore(context).activeChoice())
    }

    @Test
    fun `a recorded borrow that never happened is finished, not skipped`() {
        // The window the marker exists for: the loan lands before the ringer
        // moves, so a process death in between used to leave a snooze loud for
        // its whole length — a restore saw a loan and, by "never overwrite a
        // loan", did nothing at all (Codex, PR #176).
        PrefsRingerLoanStore(context).record(
            BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = RingerMode.VIBRATE, applied = false),
        )

        val outcome = newController().quiet()

        assertEquals(RingerOutcome.Set(RingerMode.VIBRATE), outcome)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        // And the way back is still the original one, not the quiet mode.
        assertEquals(RingerMode.NORMAL, PrefsRingerLoanStore(context).borrowed()?.restoreTo)
    }

    @Test
    fun `an applied borrow is still never re-taken`() {
        newController().quiet()

        assertEquals(RingerOutcome.Untouched, newController().quiet())
        assertEquals(RingerMode.NORMAL, PrefsRingerLoanStore(context).borrowed()?.restoreTo)
    }

    @Test
    fun `a loan left by an earlier snooze does not become the next one's ceiling`() {
        // A refused hand-back leaves the loan and clears the choice, so this is
        // the shape a *new* snooze can arm into. The loan says nothing about
        // whose snooze it is, so reading it as this snooze's choice would run a
        // `Silent` snooze at the old `Vibrate` ceiling and report no shortfall
        // — which is why the loan is not a fallback (Codex, PR #176, twice).
        PrefsRingerLoanStore(context).record(
            BorrowedRinger(restoreTo = RingerMode.NORMAL, setTo = RingerMode.VIBRATE),
        )
        PrefsRingerLoanStore(context).recordChoice(null)
        choose(SnoozeRinger.SILENT)

        newController().quiet()

        // The new choice is what is recorded and what the card is judged
        // against, even though a stale loan is outstanding.
        assertEquals(SnoozeRinger.SILENT, PrefsRingerLoanStore(context).activeChoice())
        assertEquals(
            RingerShortfall.Louder(RingerMode.VIBRATE),
            shortfallWithRingerAt(AudioManager.RINGER_MODE_VIBRATE),
        )
    }

    @Test
    fun `an unstorable tally ends the retry sequence rather than restarting it`() {
        val delays = mutableListOf<Long>()
        val stuck = mutableListOf<Boolean>()
        installRingerHandBackRetry { delays += it }
        installRingerStuckNotice { stuck += it }
        newController().quiet()
        loseTheAudioManager()

        newController(UnwritableTally(PrefsRingerLoanStore(context))).giveBack()

        // No alarm, because each firing could wake a fresh process that reads
        // the same stale count and schedules the same first delay forever — an
        // unbounded wake-up is exactly what the pacing exists to avoid (Codex,
        // PR #176). The user is told instead, and the loan stays for the checks
        // at start-up, app-open and the next release.
        assertEquals(emptyList<Long>(), delays)
        assertEquals(listOf(true), stuck)
        assertEquals(RingerMode.NORMAL, PrefsRingerLoanStore(context).borrowed()?.restoreTo)
    }

    @Test
    fun `an unreadable loan on the release path asks for a durable retry`() {
        val delays = mutableListOf<Long>()
        val stuck = mutableListOf<Boolean>()
        installRingerHandBackRetry { delays += it }
        installRingerStuckNotice { stuck += it }
        newController().quiet()

        val outcome = newController(UnreadableLoan(PrefsRingerLoanStore(context))).giveBack()

        // Not a finished hand-back: the release that reaches this ignores the
        // refusal, turns the rule off and forgets the ceiling, so nothing else
        // would ever come back for a phone still on vibrate (Codex, PR #176).
        assertEquals(RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED), outcome)
        assertEquals(listOf(RingerHandBack.FIRST_RETRY_MILLIS), delays)
        // And no notice yet — the rounds are not spent, and a store that cannot
        // be read cannot even say a loan exists.
        assertEquals(emptyList<Boolean>(), stuck)
    }

    @Test
    fun `an unreadable loan on the idle check asks for a retry too`() {
        val delays = mutableListOf<Long>()
        installRingerHandBackRetry { delays += it }
        newController().quiet()

        val outcome = newController(UnreadableLoan(PrefsRingerLoanStore(context)))
            .giveBackIfIdle(snoozeRunning = { false })

        // Sharper here than on the release path: this is what the retry alarm's
        // own receiver runs, so a one-shot alarm reaching an unreadable record
        // is already spent — returning without a successor leaves nothing
        // scheduled at all (Codex, PR #176).
        assertEquals(RingerOutcome.Refused(RingerFailure.PLATFORM_REFUSED), outcome)
        assertEquals(listOf(RingerHandBack.FIRST_RETRY_MILLIS), delays)
    }

    @Test
    fun `an unanswerable snooze question over a loan asks for a retry`() {
        val delays = mutableListOf<Long>()
        installRingerHandBackRetry { delays += it }
        newController().quiet()

        val outcome = newController().giveBackIfIdle(snoozeRunning = { error("record unreadable") })

        // Still the quiet answer — not knowing is no reason to un-quiet a phone
        // — but no longer a *silent* one: reached from the one-shot retry alarm,
        // that answer spent the alarm and scheduled nothing (Codex, PR #176).
        assertEquals(RingerOutcome.Untouched, outcome)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertEquals(listOf(RingerHandBack.FIRST_RETRY_MILLIS), delays)
    }

    @Test
    fun `an unanswerable snooze question with nothing borrowed asks for nothing`() {
        val delays = mutableListOf<Long>()
        installRingerHandBackRetry { delays += it }

        newController().giveBackIfIdle(snoozeRunning = { error("record unreadable") })

        // Nothing is owed, so there is nothing to come back for — and waking the
        // phone hourly over an unreadable record that costs nothing would be the
        // unbounded wake-up the pacing exists to prevent.
        assertEquals(emptyList<Long>(), delays)
    }

    @Test
    fun `a loan whose commit fails is not left in this process's memory`() {
        val outcome = newController(loans = PhantomLoan(PrefsRingerLoanStore(context))).quiet()

        // `commit` returning false still updates the in-memory map, so the
        // refusal has to roll that back (Codex, PR #176). Left there, a service
        // recreated without the process dying would finish the phantom borrow
        // and quiet the phone, and the next process death would reload a disk
        // with no loan — a quiet phone with no way back.
        assertEquals(RingerOutcome.Refused(RingerFailure.NOT_RECORDED), outcome)
        assertNull(PrefsRingerLoanStore(context).borrowed())
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun `an unreadable loan whose tally will not store stops without a false alarm`() {
        val delays = mutableListOf<Long>()
        val stuck = mutableListOf<Boolean>()
        installRingerHandBackRetry { delays += it }
        installRingerStuckNotice { stuck += it }

        newController(UnreadableUncountableLoan()).giveBack()

        // Nothing can be bounded, so the wake-ups stop — but telling someone
        // their ringer is stuck when nothing here can say it was ever taken
        // would be a false alarm.
        assertEquals(emptyList<Long>(), delays)
        assertEquals(emptyList<Boolean>(), stuck)
    }

    @Test
    fun `a loan that will not clear after a hand-back asks for a retry`() {
        val delays = mutableListOf<Long>()
        val stuck = mutableListOf<Boolean>()
        installRingerHandBackRetry { delays += it }
        installRingerStuckNotice { stuck += it }
        newController().quiet()

        val outcome = newController(UnclearableLoan(PrefsRingerLoanStore(context))).giveBack()

        // The ringer is back, so this is not a stranded loan — but a `commit`
        // that returned false updated the in-memory map and not the file, so
        // the record returns in the next process and a user who later picks
        // that same mode turns it into a hand-back that overrides them (Codex,
        // PR #176).
        assertEquals(RingerOutcome.Set(RingerMode.NORMAL), outcome)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertEquals(listOf(RingerHandBack.FIRST_RETRY_MILLIS), delays)
        // And the notice is retired rather than raised: nothing is owed.
        assertEquals(listOf(false), stuck)
    }

    @Test
    fun `a loan that will not clear after a disown asks for a retry too`() {
        val delays = mutableListOf<Long>()
        installRingerHandBackRetry { delays += it }
        newController().quiet()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT

        val outcome = newController(UnclearableLoan(PrefsRingerLoanStore(context))).giveBack()

        // "The next give-back disowns it again" holds only while the live mode
        // differs from `setTo`, which is exactly what a user picking vibrate
        // again would undo.
        assertEquals(RingerOutcome.Disowned, outcome)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
        assertEquals(listOf(RingerHandBack.FIRST_RETRY_MILLIS), delays)
    }

    @Test
    fun `a ceiling record that will not clear asks for a retry`() {
        val delays = mutableListOf<Long>()
        installRingerHandBackRetry { delays += it }
        newController().quiet()

        newController(UnclearableChoice(PrefsRingerLoanStore(context))).forgetCeiling()

        // Left behind, the record is read as authoritative by the next arm — a
        // stale `Silent` could quiet a snooze configured as `Ring` (Codex,
        // PR #176) — so the clear is retried rather than logged and dropped.
        assertEquals(listOf(RingerHandBack.FIRST_RETRY_MILLIS), delays)
        // And the round is *written down*: an alarm firing into a fresh process
        // reads this, so a tally left at zero would schedule the same first
        // minute forever (Codex, PR #176).
        assertEquals(1, PrefsRingerLoanStore(context).handBackFailures())
    }

    @Test
    fun `a ceiling clear that will not stick stops asking once its rounds are spent`() {
        val delays = mutableListOf<Long>()
        installRingerHandBackRetry { delays += it }
        newController().quiet()
        PrefsRingerLoanStore(context).recordHandBackFailures(RingerHandBack.MAX_RETRIES)

        newController(UnclearableChoice(PrefsRingerLoanStore(context))).forgetCeiling()

        // No notice either, unlike a stranded loan: the phone's ringer is right
        // where the user wants it, and a stale record is not something they
        // could act on if they were told.
        assertEquals(emptyList<Long>(), delays)
    }

    @Test
    fun `a ceiling clear whose tally will not store stops scheduling`() {
        val delays = mutableListOf<Long>()
        installRingerHandBackRetry { delays += it }
        newController().quiet()

        newController(UnclearableUncountableChoice(PrefsRingerLoanStore(context))).forgetCeiling()

        // Same reasoning as the loan's own: a count that never persists cannot
        // bound anything, so each firing would read zero and re-ask forever.
        assertEquals(emptyList<Long>(), delays)
    }

    @Test
    fun `the hand-back tally belongs to the loan, not to the process`() {
        val loans = PrefsRingerLoanStore(context)
        loans.recordHandBackFailures(4)

        // A fresh instance, because the retry it paces arrives on an alarm and
        // may wake a process that never saw the failures (Codex, PR #176).
        assertEquals(4, PrefsRingerLoanStore(context).handBackFailures())

        // A new loan starts the sequence over — a spent tally left behind would
        // deny the next snooze its retries entirely.
        newController().quiet()
        assertEquals(0, PrefsRingerLoanStore(context).handBackFailures())
    }

    @Test
    fun `handing the ringer back forgets the tally with the loan`() {
        newController().quiet()
        PrefsRingerLoanStore(context).recordHandBackFailures(2)

        newController().giveBack()

        assertEquals(0, PrefsRingerLoanStore(context).handBackFailures())
    }

    /** The live shortfall with the ringer put at [mode] first. */
    private fun shortfallWithRingerAt(mode: Int): RingerShortfall? {
        audio.ringerMode = mode
        return newController().shortfall()
    }

    /** Real storage whose retry tally never reaches disk. */
    private class UnwritableTally(private val real: RingerLoanStore) : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = real.borrowed()
        override fun record(borrowed: BorrowedRinger): Boolean = real.record(borrowed)
        override fun markApplied(): Boolean = real.markApplied()
        override fun clear(): Boolean = real.clear()
        override fun activeChoice(): SnoozeRinger? = real.activeChoice()
        override fun recordChoice(choice: SnoozeRinger?): Boolean = real.recordChoice(choice)
        override fun handBackFailures(): Int = real.handBackFailures()
        override fun recordHandBackFailures(failures: Int): Boolean = false
    }

    /**
     * Real storage that writes the loan and reports failure, which is what
     * `SharedPreferences.commit` returning false actually leaves behind.
     */
    private class PhantomLoan(private val real: RingerLoanStore) : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = real.borrowed()
        override fun record(borrowed: BorrowedRinger): Boolean {
            real.record(borrowed)
            return false
        }
        override fun markApplied(): Boolean = real.markApplied()
        override fun clear(): Boolean = real.clear()
        override fun activeChoice(): SnoozeRinger? = real.activeChoice()
        override fun recordChoice(choice: SnoozeRinger?): Boolean = real.recordChoice(choice)
        override fun handBackFailures(): Int = real.handBackFailures()
        override fun recordHandBackFailures(failures: Int): Boolean = real.recordHandBackFailures(failures)
    }

    /** Real storage whose loan will not go away. */
    private class UnclearableLoan(private val real: RingerLoanStore) : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = real.borrowed()
        override fun record(borrowed: BorrowedRinger): Boolean = real.record(borrowed)
        override fun markApplied(): Boolean = real.markApplied()
        override fun clear(): Boolean = false
        override fun activeChoice(): SnoozeRinger? = real.activeChoice()
        override fun recordChoice(choice: SnoozeRinger?): Boolean = real.recordChoice(choice)
        override fun handBackFailures(): Int = real.handBackFailures()
        override fun recordHandBackFailures(failures: Int): Boolean = real.recordHandBackFailures(failures)
    }

    /** Real storage that will not forget the choice in force. */
    private class UnclearableChoice(private val real: RingerLoanStore) : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = real.borrowed()
        override fun record(borrowed: BorrowedRinger): Boolean = real.record(borrowed)
        override fun markApplied(): Boolean = real.markApplied()
        override fun clear(): Boolean = real.clear()
        override fun activeChoice(): SnoozeRinger? = real.activeChoice()
        override fun recordChoice(choice: SnoozeRinger?): Boolean =
            if (choice == null) false else real.recordChoice(choice)
        override fun handBackFailures(): Int = real.handBackFailures()
        override fun recordHandBackFailures(failures: Int): Boolean = real.recordHandBackFailures(failures)
    }

    /** Real storage that will neither forget the choice nor count the attempt. */
    private class UnclearableUncountableChoice(private val real: RingerLoanStore) : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = real.borrowed()
        override fun record(borrowed: BorrowedRinger): Boolean = real.record(borrowed)
        override fun markApplied(): Boolean = real.markApplied()
        override fun clear(): Boolean = real.clear()
        override fun activeChoice(): SnoozeRinger? = real.activeChoice()
        override fun recordChoice(choice: SnoozeRinger?): Boolean =
            if (choice == null) false else real.recordChoice(choice)
        override fun handBackFailures(): Int = real.handBackFailures()
        override fun recordHandBackFailures(failures: Int): Boolean = false
    }

    /** Real storage whose loan cannot be read, with a tally that still writes. */
    private class UnreadableLoan(private val real: RingerLoanStore) : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = error("the loan record is unreadable")
        override fun record(borrowed: BorrowedRinger): Boolean = real.record(borrowed)
        override fun markApplied(): Boolean = real.markApplied()
        override fun clear(): Boolean = real.clear()
        override fun activeChoice(): SnoozeRinger? = real.activeChoice()
        override fun recordChoice(choice: SnoozeRinger?): Boolean = real.recordChoice(choice)
        override fun handBackFailures(): Int = real.handBackFailures()
        override fun recordHandBackFailures(failures: Int): Boolean = real.recordHandBackFailures(failures)
    }

    /** And one where neither the loan nor the tally can be reached at all. */
    private class UnreadableUncountableLoan : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = error("the loan record is unreadable")
        override fun record(borrowed: BorrowedRinger): Boolean = false
        override fun markApplied(): Boolean = false
        override fun clear(): Boolean = false
        override fun activeChoice(): SnoozeRinger? = null
        override fun recordChoice(choice: SnoozeRinger?): Boolean = false
        override fun handBackFailures(): Int = error("the tally is unreadable")
        override fun recordHandBackFailures(failures: Int): Boolean = false
    }

    /** Storage that accepts nothing, for the one refusal that must not be ignored. */
    private class RefusingLoanStore : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = null
        override fun record(borrowed: BorrowedRinger): Boolean = false
        override fun markApplied(): Boolean = false
        override fun clear(): Boolean = false
        override fun activeChoice(): SnoozeRinger? = null
        override fun recordChoice(choice: SnoozeRinger?): Boolean = false
        override fun handBackFailures(): Int = 0
        override fun recordHandBackFailures(failures: Int): Boolean = false
    }

    /**
     * Real storage that refuses the loan alone, so the ceiling in force is
     * recorded while the borrow it was for is declined.
     */
    private class UnrecordableLoan(private val real: RingerLoanStore) : RingerLoanStore {
        override fun borrowed(): BorrowedRinger? = real.borrowed()
        override fun record(borrowed: BorrowedRinger): Boolean = false
        override fun markApplied(): Boolean = real.markApplied()
        override fun clear(): Boolean = real.clear()
        override fun activeChoice(): SnoozeRinger? = real.activeChoice()
        override fun recordChoice(choice: SnoozeRinger?): Boolean = real.recordChoice(choice)
        override fun handBackFailures(): Int = real.handBackFailures()
        override fun recordHandBackFailures(failures: Int): Boolean = real.recordHandBackFailures(failures)
    }
}
