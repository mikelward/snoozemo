package app.snoozemo.ui

import android.os.Bundle
import android.os.Looper.getMainLooper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.EndChoiceOutcome
import app.snoozemo.snooze.EndChoiceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant

/**
 * The main screen's end-condition rows stand for as long as a snooze does
 * (SPEC.md §4.4) — unlike the sheet, which is offered once at the arm.
 *
 * What is covered here is the activity's own half: seeding from a snooze that
 * was already running when the screen opened, dropping the rows when it ends,
 * and not carrying one snooze's meeting times onto another. The offer's
 * content rules are pure and live in `EndChoiceUiStateTest`; the commit
 * lifecycle is `EndChoiceControllerTest`.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityEndRowsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * Truncated to milliseconds, which is what the record round-trips through
     * the store — a nanosecond-precision `Instant.now()` comes back different
     * from the one that went in and the identity comparison then never matches.
     */
    private fun at(offsetSeconds: Long = 0L): Instant =
        Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(offsetSeconds)

    private fun snooze(startedAt: Instant = at(), capIn: Duration = ActiveSnooze.DEFAULT_CAP) =
        ActiveSnooze(
            anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
            startedAt = startedAt,
            capExpiresAt = startedAt.plus(capIn),
            mode = TrackingMode.DURATION_ONLY,
        )

    /**
     * The record read runs inline, so each test asserts on a settled state
     * rather than racing a thread.
     *
     * **The seam goes in before `setup()`, not after it** (Codex, PR #234).
     * `setup()` runs `onStart`, which launches the first refresh — installed
     * afterwards, the runner cannot make *that* read synchronous, and idling
     * the looper does not join the real thread it went to. Every assertion
     * about state the first read produces was therefore racing it, and passing
     * only because a preferences load is quick.
     */
    private fun screen(): MainActivity = controller().get()

    private fun controller(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).also {
            it.get().runOffMainThread = { work -> work() }
            it.setup()
        }

    private fun settle() {
        shadowOf(getMainLooper()).idle()
    }

    @Test
    fun `opening the app during a snooze offers a way to change it`() {
        // The point of the change: refining used to be reachable only in the
        // seconds after arming.
        val running = snooze()
        ActiveSnoozeStore(context).arm(running)

        val activity = screen()
        settle()

        assertNotNull(activity.rows.endCondition)
        assertEquals(running.startedAt, activity.rows.offerFor)
    }

    @Test
    fun `no snooze means nothing to refine`() {
        ActiveSnoozeStore(context).clear()

        val activity = screen()
        settle()

        assertNull(activity.rows.endCondition)
    }

    @Test
    fun `a cap already inside the floor offers nothing`() {
        // The service declines anything inside `MIN_CAP` and honors anything
        // at or past the cap by doing nothing, so there is no time to offer.
        ActiveSnoozeStore(context).arm(snooze(capIn = ActiveSnooze.MIN_CAP.minusMinutes(5)))

        val activity = screen()
        settle()

        assertNull(activity.rows.endCondition)
    }

    @Test
    fun `a snooze ending underneath takes the rows with it`() {
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        assertNotNull(activity.rows.endCondition)

        ActiveSnoozeStore(context).clear()
        settle()

        assertNull(activity.rows.endCondition)
    }

    @Test
    fun `a choice the service applied by changing nothing leaves the rows up`() {
        // `Until I leave` on a snooze already running to its ceiling is the
        // ordinary case, and the service honors it by doing nothing — so no
        // record change follows and nothing else would put the rows back. They
        // used to vanish for the rest of the snooze (Codex, PR #234).
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        val offeredFor = activity.rows.offerFor

        activity.rows.commitDeparture()
        settle()
        EndChoiceOutcome.report(activity.rows.committingRequestId, EndChoiceResult.APPLIED)
        settle()

        assertNotNull("the snooze is still running, so there is still something to refine", activity.rows.endCondition)
        assertEquals(offeredFor, activity.rows.offerFor)
        // And they came back because the choice was *applied*, not because a
        // refusal left them standing — those are opposite outcomes that would
        // otherwise satisfy the assertion above the same way.
        assertFalse(activity.rows.commitFailed)
    }

    @Test
    fun `an answer that lands during a recreation does not crash the new activity`() {
        // A commit in flight across a rotation is settled by `restore` on the
        // spot, from the result `EndChoiceOutcome` held while no watcher
        // existed — and settling one re-reads the record. Restoring before the
        // store was built crashed `onCreate` on an uninitialized `lateinit`
        // (Codex, PR #234).
        val running = snooze()
        ActiveSnoozeStore(context).arm(running)
        val requestId = EndChoiceOutcome.nextRequestId()
        EndChoiceOutcome.report(requestId, EndChoiceResult.APPLIED)
        val saved = Bundle().apply {
            putBoolean("rowsCommitting", true)
            putLong("rowsRequestId", requestId)
            putLong("rowsOfferedFor", running.startedAt.toEpochMilli())
            putLong("rowsEndsAt", running.startedAt.plus(Duration.ofHours(1)).toEpochMilli())
            putLong("rowsFloor", running.startedAt.toEpochMilli())
            putLong("rowsCeiling", running.capExpiresAt.toEpochMilli())
        }

        val built = Robolectric.buildActivity(MainActivity::class.java)
        built.get().runOffMainThread = { work -> work() }
        val activity = built.create(saved).start().resume().get()
        settle()

        // Reaching here at all is the assertion: `onCreate` used to throw.
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `coming back to the screen does not show what the calendar said last time`() {
        // A meeting deleted or moved while the screen was away leaves a row
        // naming an end that no longer exists — and against a wedged provider
        // it would stand until something else changed the record.
        ActiveSnoozeStore(context).arm(snooze())
        val controller = controller()
        val activity = controller.get()
        settle()
        activity.meetingOffers = listOf(at().plus(Duration.ofHours(1)))
        // The read itself is held, so what is asserted is that the rows go
        // when the re-read *begins* — a read that lands empty would clear them
        // on its own and prove nothing about the moment their claim stopped
        // being checkable.
        activity.runOffMainThread = { }

        controller.stop().start()
        settle()

        assertEquals(emptyList<Instant>(), activity.meetingOffers)
    }

    @Test
    fun `another snooze does not inherit the last one's meeting times`() {
        // The offers are read for a specific record; carrying them over would
        // put one snooze's meeting ends on a different snooze's rows for as
        // long as the calendar took to answer for the new one.
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        activity.meetingOffers = listOf(at().plus(Duration.ofHours(1)))

        ActiveSnoozeStore(context).clear()
        ActiveSnoozeStore(context).arm(snooze(startedAt = at(offsetSeconds = 1)))
        settle()

        assertEquals(emptyList<Instant>(), activity.meetingOffers)
    }
}
