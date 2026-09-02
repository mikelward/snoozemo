package app.snoozemo.snooze

import android.app.AlarmManager
import android.content.Intent
import android.media.AudioManager
import app.snoozemo.core.RingerHandBack
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.dnd.AudioRingerController
import app.snoozemo.dnd.PrefsRingerLoanStore
import app.snoozemo.dnd.SnoozeRingerStore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The hand-back check that runs outside a release (SPEC.md §5.9) — at process
 * start, when the app is opened, and from its own alarm.
 *
 * Its whole job is to tell "a snooze ended without handing the ringer back"
 * from "a snooze is running and still needs it quiet", and both mistakes are
 * expensive in opposite directions: one leaves a phone silent after a snooze it
 * was told had ended, the other un-quiets a live snooze. Neither is visible
 * from `AudioRingerController`'s own tests, which never see a record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RingerReconcileTest {

    private val now = Instant.parse("2026-08-22T09:00:00Z")

    private val audio: AudioManager
        get() = appContext.getSystemService(AudioManager::class.java)

    @Before
    fun startAudible() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        PrefsRingerLoanStore(appContext).clear()
        PrefsRingerLoanStore(appContext).recordChoice(null)
        ActiveSnoozeStore(appContext).clear()
        SnoozeRingerStore(appContext).setChosen(SnoozeRinger.DEFAULT)
    }

    /** A snooze that quieted the phone and then vanished without cleaning up. */
    private fun borrowThenLoseTheSnooze() {
        AudioRingerController.default(appContext).quiet()
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        ActiveSnoozeStore(appContext).clear()
    }

    @Test
    fun `a loan left behind by a vanished snooze is handed back`() {
        borrowThenLoseTheSnooze()

        handBackRingerNow(appContext)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertNull(PrefsRingerLoanStore(appContext).borrowed())
    }

    @Test
    fun `nothing borrowed is a no-op rather than a change`() {
        handBackRingerNow(appContext)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun `a record written by the arm path holds the loan, even before it is stamped`() {
        AudioRingerController.default(appContext).quiet()
        // What the `ARMING` transition does, and the case that matters: it goes
        // through `saveAsync`, which skips the device-stamp lookup on purpose,
        // so `load()` refuses the record as unattributed for the moment before
        // the post-arm blocking save stamps it (Codex, PR #176). A check that
        // asked `load()` would read "nothing running" here and hand the ringer
        // back over a snooze that had only just armed.
        ActiveSnoozeStore(appContext).saveAsync(snoozeFixture(now))
        shadowOf(appContext.mainLooper).idle()

        handBackRingerNow(appContext)

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertNotNull(PrefsRingerLoanStore(appContext).borrowed())
    }

    @Test
    fun `the alarm's own receiver hands the ringer back`() {
        borrowThenLoseTheSnooze()

        // The durable rung, driven directly as the sibling receiver tests do.
        // Deterministic because the branch does the work inline: no thread to
        // join and no sleep to guess at.
        CapAlarmReceiver().onReceive(appContext, Intent(SnoozeService.ACTION_RINGER_RETRY))

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertNull(PrefsRingerLoanStore(appContext).borrowed())
    }

    @Test
    fun `the retry alarm is armed against elapsed realtime, not the wall clock`() {
        assertEquals(true, CapAlarm.armRingerRetry(appContext, RingerHandBack.FIRST_RETRY_MILLIS))

        val alarm = shadowOf(appContext.getSystemService(AlarmManager::class.java))
            .scheduledAlarms
            .last()
        // Winding the wall clock back must not postpone the only scheduled exit
        // from a silent phone.
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
    }
}
