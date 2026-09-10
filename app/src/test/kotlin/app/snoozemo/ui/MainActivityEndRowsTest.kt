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
import org.junit.Assert.assertTrue
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
    fun `a refused until I move is shown where the tap happened`() {
        // The row is a plain choice with no state of its own (maintainer,
        // 2026-09-10), so the service's answer is the only signal a user gets
        // that the exit they asked for is not armed (Codex, PR #255). Same
        // lifecycle as `Until I leave`: inert rows while it is out, the
        // refusal on the rows, and the rows still standing for a retry.
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()

        activity.rows.commitMotionEnd()
        settle()
        assertTrue("the choice reached the service", sentMotionEnd())
        assertTrue("and the rows wait on its answer", activity.rows.committing)

        EndChoiceOutcome.report(activity.rows.committingRequestId, EndChoiceResult.REFUSED)
        settle()

        assertTrue(activity.rows.commitFailed)
        assertNotNull("still offered, so the user can try again", activity.rows.endCondition)
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

    private fun shadowApp() =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())

    /**
     * Forgets every service start so far, so what follows measures the tap
     * rather than the activity's own startup.
     *
     * **`nextStartedService` is a queue, and it pops the oldest.** Read
     * straight after a tap it returns whatever the screen had already sent
     * while opening — an `ACTION_END` here — so the assertion was about a
     * different call entirely. Drained with the same loop the sibling tests
     * use rather than a `clear` helper, so there is one idiom in the file.
     */
    private fun forgetServiceStarts() {
        while (shadowApp().nextStartedService != null) Unit
    }

    /** Every action sent since [forgetServiceStarts], oldest first. */
    private fun serviceActions(): List<String?> {
        val actions = mutableListOf<String?>()
        while (true) {
            val next = shadowApp().nextStartedService ?: return actions
            actions.add(next.action)
        }
    }

    /**
     * Whether the motion tap reached the service since [forgetServiceStarts].
     *
     * Asked as *did it happen* rather than *was it the last thing to happen*,
     * because this activity sends the service more than the tap does: under
     * Robolectric there is no Do Not Disturb access, so a startup or resume
     * refresh decides the snooze should end and sends `ACTION_END` on its own
     * schedule. Which of the two lands last is the harness's business; whether
     * the tap was delivered is the test's.
     */
    /**
     * Whether a departure commit reached the service since the last drain.
     *
     * `Until I leave` goes out as `ACTION_SET_CAP` carrying
     * [SnoozeService.EXTRA_RESTORE_END], so the extra is what tells it from a
     * chosen time.
     */
    private fun sentDeparture(): Boolean {
        var found = false
        while (true) {
            val next = shadowApp().nextStartedService ?: return found
            if (
                next.action == app.snoozemo.snooze.SnoozeService.ACTION_SET_CAP &&
                next.getBooleanExtra(app.snoozemo.snooze.SnoozeService.EXTRA_RESTORE_END, false)
            ) {
                found = true
            }
        }
    }

    private fun sentMotionEnd(): Boolean =
        serviceActions().contains(app.snoozemo.snooze.SnoozeService.ACTION_SET_MOTION_END)

    @Test
    fun `when I move asks for location rather than arming without it`() {
        // The foreground service that keeps the sensor alive is typed
        // `location`, and the platform refuses to start one without the grant
        // — so arming here would record a promise it will not let us keep
        // (SPEC.md §4.4).
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        forgetServiceStarts()

        activity.chooseMotionEndFromScreen()
        settle()

        assertFalse("the tap armed nothing", sentMotionEnd())
    }

    @Test
    fun `when I move arms once location is granted`() {
        // The other direction, so the gate above cannot pass by refusing
        // everything.
        // All three, not just fine. `LocationPermission.GRANTED` means both
        // halves are held, and the background half is required on `play` —
        // granting fine alone would read as `ASKABLE` there and pass only on
        // `direct`, which is a test that agrees with itself on one flavor.
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        forgetServiceStarts()

        activity.chooseMotionEndFromScreen()
        settle()

        assertTrue("the tap reached the service", sentMotionEnd())
    }

    @Test
    fun `approximate location is enough for when I move`() {
        // The platform's prerequisite for a `location`-typed foreground
        // service is "at least one of ACCESS_COARSE_LOCATION,
        // ACCESS_FINE_LOCATION" — background is not among them, and the sensor
        // needs no location at all. Holding this row to the departure
        // aggregate refused it to everyone who picked Android's approximate
        // option (Codex, PR #252).
        shadowApp().grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        forgetServiceStarts()

        activity.chooseMotionEndFromScreen()
        settle()

        assertTrue("the tap reached the service", sentMotionEnd())
    }

    @Test
    fun `a when I move grant does not go on to ask for background location`() {
        // The background rationale belongs to departure tracking. Following a
        // motion tap with it would be a detour for a permission that exit will
        // never use — and leaving the switch waiting on the answer would be
        // worse still (Codex, PR #252).
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        activity.chooseMotionEndFromScreen()
        settle()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        assertFalse(
            "no background rationale behind a motion tap",
            activity.showBackgroundLocationRationale,
        )
    }

    @Test
    fun `a grant that lands after a rotation still runs the tap that asked`() {
        // The permission dialog is a window over this activity, so a rotation
        // behind it recreates the activity while the request is still out. The
        // result is delivered to the *new* instance, which without the saved
        // pending action finds nothing to resume — the user grants location
        // and the row they touched silently does nothing (Codex, PR #252).
        ActiveSnoozeStore(context).arm(snooze())
        val controller = controller()
        settle()
        controller.get().chooseMotionEndFromScreen()
        settle()

        controller.recreate()
        settle()
        // The recreation's own startup calls are not what this measures.
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        controller.get().onForegroundLocationResult(fineGranted = true)
        settle()

        assertTrue("the tap the user made before the rotation was replayed", sentMotionEnd())
    }

    @Test
    fun `a grant for a snooze that has ended is not applied to the next one`() {
        // The wait is unbounded — the user can sit on the permission dialog —
        // and a snooze can end and another be armed from the shade underneath
        // it. Without the tap's own snooze riding along, the grant would give
        // the new one a movement exit nobody asked for (Codex, PR #252).
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        activity.chooseMotionEndFromScreen()
        settle()

        // The one it was tapped on ends; a different one is armed.
        ActiveSnoozeStore(context).arm(snooze(startedAt = at(offsetSeconds = 60)))
        settle()
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        assertFalse("the new snooze keeps its own end conditions", sentMotionEnd())
    }

    @Test
    fun `dismissing the rationale after the snooze changed reports nothing`() {
        // Both endings of a pending tap need the same identity check: the
        // grant that resumes it, and this dismissal that abandons it.
        // Reporting here would tell the user the *running* snooze's row failed
        // when it never tried (Codex, PR #252).
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        activity.chooseDepartureFromScreen()
        settle()
        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        // The one it was tapped on ends; a different one is armed.
        ActiveSnoozeStore(context).arm(snooze(startedAt = at(offsetSeconds = 60)))
        settle()
        activity.dismissBackgroundLocationRationale()
        settle()

        assertNull("the new snooze is told nothing about the old one's tap", activity.lastOutcome)
    }

    @Test
    fun `a grant that lands before the record has loaded still runs the tap`() {
        // The recreated activity restores the pending tap immediately but
        // reads the record asynchronously, so `activeSnooze` is null for a
        // moment — which must read as "not known yet", not as "the snooze
        // ended". Reading it as the latter dropped a valid tap and granting
        // permission then applied nothing (Codex, PR #252).
        ActiveSnoozeStore(context).arm(snooze())
        val controller = controller()
        settle()
        controller.get().chooseMotionEndFromScreen()
        settle()

        controller.recreate()
        // Deliberately no `settle()` here: the recreated activity's record
        // read has been posted and not yet delivered, which is the race.
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        controller.get().onForegroundLocationResult(fineGranted = true)

        assertTrue("the tap survives the record read it raced", sentMotionEnd())
    }

    @Test
    fun `a grant with no tap behind it replays nothing`() {
        // The other direction: a grant arriving from the Permissions screen,
        // or from a trip to Settings, must not replay a tap the user made
        // minutes ago and has forgotten about.
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        forgetServiceStarts()

        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        assertFalse("nothing was replayed", sentMotionEnd())
    }

    @Test
    fun `a departure tap waits for the background half rather than failing`() {
        // On `play` the whole grant is two prompts, and the second one used to
        // land nowhere: the foreground result found the aggregate still
        // `ASKABLE`, reported the tap failed, and the background callback only
        // refreshed state — so granting it applied nothing (Codex, PR #252).
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        forgetServiceStarts()

        activity.chooseDepartureFromScreen()
        settle()
        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        // Flavor-aware rather than hard-coded: `direct` needs no background
        // half, so there the tap is already done by here, and pinning either
        // answer would be a test that agrees with itself on one flavor.
        if (locationTrackingNeedsBackgroundPermission) {
            assertTrue("the rationale is up", activity.showBackgroundLocationRationale)
            assertFalse("and the tap is waiting, not failed", sentDeparture())
            shadowApp().grantPermissions(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            activity.onBackgroundLocationResult()
            settle()
        }
        assertTrue("the grant applies the tap the user made", sentDeparture())
    }

    @Test
    fun `the background rationale survives a rotation`() {
        // The rotation happens *after* the foreground result is consumed, so
        // nothing would put the dialog back — and the pending departure would
        // sit there with no callback left to resume or reject it, silently
        // unapplied (Codex, PR #252).
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        ActiveSnoozeStore(context).arm(snooze())
        val controller = controller()
        settle()
        controller.get().chooseDepartureFromScreen()
        settle()
        controller.get().onForegroundLocationResult(fineGranted = true)
        settle()
        assertEquals(
            "the rationale is up exactly where the flavor needs the background half",
            locationTrackingNeedsBackgroundPermission,
            controller.get().showBackgroundLocationRationale,
        )

        controller.recreate()
        settle()

        assertEquals(
            "and still up afterwards, so the tap can still be resumed",
            locationTrackingNeedsBackgroundPermission,
            controller.get().showBackgroundLocationRationale,
        )
    }
}
