package app.snoozemo.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.os.Looper.getMainLooper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.SnoozeLifecycle
import app.snoozemo.core.TrackingMode
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.EndChoiceOutcome
import app.snoozemo.snooze.EndChoiceResult
import app.snoozemo.snooze.SnoozeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
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

    /**
     * A running snooze whose cap is [capIn] from [startedAt] and whose §7
     * backstop is [ceilingIn] from it.
     *
     * The backstop is what decides whether rows are offered at all
     * (`EndCondition.offersAChoice`), because a chosen time moves the cap
     * either way and so the cap is no longer the edge of what is choosable.
     * It defaults to the cap, which is what an unshortened snooze carries.
     */
    private fun snooze(
        startedAt: Instant = at(),
        capIn: Duration = ActiveSnooze.DEFAULT_CAP,
        ceilingIn: Duration = capIn,
    ) =
        ActiveSnooze(
            anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
            startedAt = startedAt,
            capExpiresAt = startedAt.plus(capIn),
            mode = TrackingMode.DURATION_ONLY,
            capCeilingAt = startedAt.plus(ceilingIn),
        )

    /**
     * A snooze whose rule went on — what "came and went" means to the arm
     * count, which an `ARMING` record the arm path abandoned never moves.
     */
    private fun confirmedSnooze() = snooze().copy(lifecycle = SnoozeLifecycle.ARMED)

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
    fun `no snooze offers a way to start`() {
        // The idle screen's rows (SPEC.md §4.4, maintainer, 2026-09-10): the
        // same offer, naming no snooze, and a tap on it arms.
        ActiveSnoozeStore(context).clear()

        val activity = screen()
        settle()

        assertNotNull(activity.rows.endCondition)
        assertNull(activity.rows.offerFor)
        assertTrue(activity.rows.startsASnooze)
    }

    @Test
    fun `a snooze arriving replaces the offer to start with its own`() {
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        assertTrue(activity.rows.startsASnooze)

        val running = snooze()
        ActiveSnoozeStore(context).arm(running)
        settle()

        assertEquals(running.startedAt, activity.rows.offerFor)
        assertFalse(activity.rows.startsASnooze)
    }

    @Test
    fun `the idle time row starts a snooze that ends there`() {
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        forgetServiceStarts()
        val chosen = activity.rows.endCondition!!.endsAt

        activity.rows.commit(chosen)
        settle()

        assertEquals("an arm, carrying the chosen end", chosen.toEpochMilli(), sentArm()?.getLongExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, 0L))
        assertTrue("and the rows wait on its answer", activity.rows.committing)
    }

    @Test
    fun `the idle offer's count survives a recreation, for a time row tapped before the first read`() {
        // The rows are restored ahead of the recreated screen's record read,
        // and the time row needs no reading to be tapped — so the count the
        // arm carries has to come back with the rows, or a tap in that
        // window would send zero and be refused for an offer that is fine
        // (Codex, PR #257).
        ActiveSnoozeStore(context).clear()
        ActiveSnoozeStore(context).arm(confirmedSnooze())
        ActiveSnoozeStore(context).clear()
        val expected = ActiveSnoozeStore(context).armCount()
        assertTrue("the setup this rests on: a count a zero would not match", expected > 0L)
        val controller = controller()
        settle()

        controller.recreate()
        // No `settle()`: the recreated screen's record read is still posted.
        forgetServiceStarts()
        val activity = controller.get()
        activity.rows.commit(activity.rows.endCondition!!.endsAt)

        assertEquals(expected, sentArm()?.getLongExtra(SnoozeService.EXTRA_ARM_COUNT_AT_OFFER, -1L))
    }

    @Test
    fun `a stepper on the idle screen starts a snooze at the stepped time`() {
        // Every tap on the idle screen starts (maintainer, 2026-09-10): `−`
        // and `+` arm at the stepped time rather than moving a row the user
        // would then have to tap.
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        forgetServiceStarts()
        val drawn = EndChoiceUiState(
            condition = activity.rows.endCondition!!,
            formattedTime = "",
            startsASnooze = true,
        )

        activity.stepEndFromScreen(drawn, up = true)
        settle()

        assertEquals(
            drawn.condition.stepUp().endsAt.toEpochMilli(),
            sentArm()?.getLongExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, 0L),
        )
        assertEquals("the row itself did not move", drawn.condition.endsAt, activity.rows.endCondition!!.endsAt)
    }

    @Test
    fun `a stepper over a running snooze steps the row rather than arming`() {
        // The other direction: the running rows keep "step, then tap".
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        forgetServiceStarts()
        val drawn = EndChoiceUiState(
            condition = activity.rows.endCondition!!,
            formattedTime = "",
            offerFor = activity.rows.offerFor,
        )

        activity.stepEndFromScreen(drawn, up = false)
        settle()

        assertEquals(drawn.condition.stepDown().endsAt, activity.rows.endCondition!!.endsAt)
        assertNull("nothing was sent", sentArm())
        assertFalse(activity.rows.committing)
    }

    @Test
    fun `until I move on the idle screen starts a snooze that ends on motion`() {
        // The same location gate as over a running snooze, then an arm
        // carrying the choice rather than a choice over nothing.
        shadowApp().grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        forgetServiceStarts()

        activity.chooseMotionEndFromScreen()
        settle()

        val arm = sentArm()
        assertNotNull("an arm, not a choice over a snooze that does not exist", arm)
        assertTrue(arm!!.getBooleanExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, false))
        assertFalse(sentMotionEnd())
    }

    @Test
    fun `until I leave on the idle screen starts the plain snooze`() {
        // The same location gate the running row has, then the arm the
        // pinned `Snooze` makes — nothing chosen, so no cap and no motion
        // extra — carrying the row's request (maintainer, 2026-09-11).
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        forgetServiceStarts()

        activity.chooseDepartureFromScreen()
        settle()

        val arm = sentArm()
        assertNotNull("an arm, not a restore over a snooze that does not exist", arm)
        assertEquals(0L, arm!!.getLongExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, 0L))
        assertFalse(arm.getBooleanExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, false))
        assertTrue("the row hears the answer", arm.getLongExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, 0L) != 0L)
        assertEquals(
            "carrying the offer's identity, which nothing has moved",
            ActiveSnoozeStore(context).armCount(),
            arm.getLongExtra(SnoozeService.EXTRA_ARM_COUNT_AT_OFFER, -1L),
        )
        assertTrue("and the rows wait on it", activity.rows.committing)
        assertFalse(sentDeparture())
    }

    @Test
    fun `an idle tap waiting on location arms once the grant lands`() {
        // The positive half of the ones below: nothing moved under the tap,
        // so the grant replays it and the arm goes out carrying a count the
        // store still agrees with.
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        activity.chooseDepartureFromScreen()
        settle()
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        val arm = sentArm()
        assertNotNull("the tap the user made before the dialog was replayed", arm)
        assertEquals(
            ActiveSnoozeStore(context).armCount(),
            arm!!.getLongExtra(SnoozeService.EXTRA_ARM_COUNT_AT_OFFER, -1L),
        )
    }

    @Test
    fun `an idle tap resumed over rows that moved onto a snooze is dropped, not sent as its refinement`() {
        // The tile arms while the tap waits; the observer moves the rows onto
        // that snooze; a rotation saves them so. The grant then lands before
        // the recreated screen's record read, which is the one moment the
        // identity check has to let a tap over nothing through — and the
        // commit would take the controller's identity, going out as
        // `Until I leave` over the tile's snooze, restoring a cap the user
        // never touched, past the count the arm path checks (Codex, PR #257).
        ActiveSnoozeStore(context).clear()
        val controller = controller()
        settle()
        controller.get().chooseDepartureFromScreen()
        settle()
        ActiveSnoozeStore(context).arm(confirmedSnooze())
        settle()
        assertNotNull("the setup this rests on: the rows moved on", controller.get().rows.offerFor)

        controller.recreate()
        // No `settle()`: the recreated screen's record read is still posted.
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        controller.get().onForegroundLocationResult(fineGranted = true)

        assertFalse("not a refinement of the tile's snooze", sentDeparture())
        assertNull("and not an arm", sentArm())
        assertFalse(controller.get().rows.committing)
    }

    @Test
    fun `a grant resumes the waiting tap ahead of the record read it also makes`() {
        // The reading a grant lands through also pokes the monitor, which
        // reads the record — and after a cold recreation that first read is
        // the preferences file loading. It runs off the main thread, so the
        // arm goes out before it rather than behind it (Codex, PR #257).
        // Pinned by holding the off-thread work back and looking at what
        // was sent in the meantime.
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        activity.chooseDepartureFromScreen()
        settle()
        forgetServiceStarts()
        val heldBack = mutableListOf<() -> Unit>()
        activity.runOffMainThread = { work -> heldBack += work }
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )

        activity.onForegroundLocationResult(fineGranted = true)

        assertNotNull("the arm went out with the record read still held back", sentArm())
        // The poke, and so its read, exists only on a build whose monitor
        // reads location; `direct` schedules nothing here, and the arm
        // going out first is what this pins on both.
        if (app.snoozemo.presence.PRESENCE_TRACKS_DEPARTURE) {
            assertTrue("the setup this rests on: there was a read to hold back", heldBack.isNotEmpty())
        }
        heldBack.forEach { it() }
    }

    /**
     * The arm a resumed idle tap sent, which has to carry [countAtOffer] —
     * the store's arm count as the offer was drawn — while the store itself
     * has moved on; the service refuses exactly that pair
     * (`SnoozeServiceArmChoiceTest`). The screen sends rather than drops,
     * because dropping would mean reading the store on the main thread
     * (Codex, PR #257).
     */
    private fun assertArmCarriesStaleOffer(countAtOffer: Long) {
        val arm = sentArm()
        assertNotNull("the tap goes out for the service to judge", arm)
        assertEquals(
            "carrying the count the offer was drawn from",
            countAtOffer,
            arm!!.getLongExtra(SnoozeService.EXTRA_ARM_COUNT_AT_OFFER, -1L),
        )
        assertNotEquals("which the store has moved past", countAtOffer, ActiveSnoozeStore(context).armCount())
    }

    @Test
    fun `an idle tap waiting on location is dropped once a snooze has come and gone`() {
        // The tap carries no snooze identity — it was made over nothing — and
        // "nothing" is what the record reads again after the tile armed a
        // snooze and it ended under the dialog. Replaying it as a plain arm
        // would start a snooze over a screen the user last saw quiet (Codex,
        // PR #257): the tap's identity is the store's arm count as of its
        // offer instead, and the arm carries it for the service to refuse.
        ActiveSnoozeStore(context).clear()
        val countAtOffer = ActiveSnoozeStore(context).armCount()
        val activity = screen()
        settle()
        activity.chooseDepartureFromScreen()
        settle()

        ActiveSnoozeStore(context).arm(confirmedSnooze())
        settle()
        ActiveSnoozeStore(context).clear()
        settle()
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        assertArmCarriesStaleOffer(countAtOffer)
    }

    @Test
    fun `an idle tap carries its offer past a snooze that came and went while the screen was stopped`() {
        // Background-location Settings is a full screen, so the tap can wait
        // behind a stop — and a stopped screen watches no record changes.
        // The restart's own record read finds nothing running and takes the
        // count as it stands now; the resumed tap still sends the count its
        // offer was drawn from, which is the one the service refuses
        // (Codex, PR #257).
        ActiveSnoozeStore(context).clear()
        val countAtOffer = ActiveSnoozeStore(context).armCount()
        val controller = controller()
        settle()
        controller.get().chooseDepartureFromScreen()
        settle()

        controller.stop()
        ActiveSnoozeStore(context).arm(confirmedSnooze())
        ActiveSnoozeStore(context).clear()
        controller.start()
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        controller.get().onForegroundLocationResult(fineGranted = true)
        settle()

        assertArmCarriesStaleOffer(countAtOffer)
    }

    @Test
    fun `an idle tap carries the count its offer was drawn from, not the count at the tap`() {
        // The tile can arm between the record read that drew the idle rows
        // and the tap on them — the observer's refresh has not run yet — and
        // a count taken at the tap would already include that snooze. Its
        // ending then leaves the count where the tap found it, and the arm
        // would pass the service's check (Codex, PR #257). The count rides
        // the offer.
        ActiveSnoozeStore(context).clear()
        val countAtOffer = ActiveSnoozeStore(context).armCount()
        val activity = screen()
        settle()
        // Armed off the main thread — the tile's arm lands from the service
        // — so the store's change notification is posted rather than run in
        // place, and the observer's refresh is still queued: the rows the
        // user sees are the idle ones.
        Thread { ActiveSnoozeStore(context).arm(confirmedSnooze()) }.apply { start(); join() }
        assertTrue("the setup this rests on", activity.rows.startsASnooze)
        activity.chooseDepartureFromScreen()

        ActiveSnoozeStore(context).clear()
        settle()
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        assertArmCarriesStaleOffer(countAtOffer)
    }

    @Test
    fun `an idle tap's arm count rides the saved state across a recreation`() {
        // The identity the tap carries has to survive what the pending action
        // survives, or a rotation under the dialog would forget what the tap
        // was made over and send whatever the new screen read instead.
        ActiveSnoozeStore(context).clear()
        val countAtOffer = ActiveSnoozeStore(context).armCount()
        val controller = controller()
        settle()
        controller.get().chooseDepartureFromScreen()
        settle()

        controller.recreate()
        settle()
        ActiveSnoozeStore(context).arm(confirmedSnooze())
        ActiveSnoozeStore(context).clear()
        settle()
        forgetServiceStarts()
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        controller.get().onForegroundLocationResult(fineGranted = true)
        settle()

        assertArmCarriesStaleOffer(countAtOffer)
    }

    @Test
    fun `a tap to start ahead of this start's location reading is dropped, not armed on the last one`() {
        // The location rows are drawn inert until the reading exists, but a
        // tap dispatched from the frame before `onStart` cleared the marker
        // arrives with that frame's lambdas — so the marker is read again at
        // the tap (Codex, PR #257).
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        ActiveSnoozeStore(context).clear()
        val controller = controller()
        settle()
        val activity = controller.get()
        assertTrue("the setup this rests on: read once", activity.idleOfferArmableForTest)
        controller.stop().start()
        assertFalse("and cleared by the restart", activity.idleOfferArmableForTest)
        forgetServiceStarts()

        activity.chooseDepartureFromScreen()
        activity.chooseMotionEndFromScreen()
        settle()

        assertNull("nothing armed on the reading from before the stop", sentArm())
        assertFalse(activity.rows.committing)

        // That idle ran the restart's own reading; the same tap goes out now.
        assertTrue(activity.idleOfferArmableForTest)
        activity.chooseDepartureFromScreen()
        settle()

        assertNotNull("and the same tap goes out once this start has read the grant", sentArm())
    }

    @Test
    fun `a time row to start needs no reading and is not held for one`() {
        // Only the two location rows wait on the reading: a time arms
        // without any grant, so holding it for a frame would drop a tap it
        // was entitled to (Codex, PR #257). Same stale frame as above.
        ActiveSnoozeStore(context).clear()
        val controller = controller()
        settle()
        val activity = controller.get()
        controller.stop().start()
        assertFalse("the setup this rests on", activity.idleOfferArmableForTest)
        // The offer as the frame before the stop drew it: still the
        // controller's, so nothing about the offer drops the tap.
        val drawn = EndChoiceUiState(
            condition = activity.rows.endCondition!!,
            formattedTime = "",
            startsASnooze = true,
            locationArmable = true,
        )
        forgetServiceStarts()

        activity.chooseEndTimeFromScreen(drawn)
        settle()

        assertEquals(
            drawn.condition.endsAt.toEpochMilli(),
            sentArm()?.getLongExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, 0L),
        )
    }

    @Test
    fun `until I move on the idle screen rides the warmed reading too, not a lookup made during it`() {
        // The motion row's floor is the lower one — either half of the
        // grant — and `location` alone cannot see it, so it used to be two
        // permission lookups on the arm path. It is a field of the same
        // reading now: shown, as for `Until I leave`, by taking the grant
        // away *after* the reading (Codex, PR #257).
        shadowApp().grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        shadowApp().denyPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        forgetServiceStarts()

        activity.chooseMotionEndFromScreen()
        settle()

        val arm = sentArm()
        assertNotNull(arm)
        assertTrue(arm!!.getBooleanExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, false))
    }

    @Test
    fun `the offer to start's location rows are armable only once this start has read the grant`() {
        // A reading from before a stop is not one an arm may ride: Settings
        // may have moved the grant behind it, and a tap on the stale reading
        // would fall to the lookup branch on the arm path (Codex, PR #257).
        ActiveSnoozeStore(context).clear()
        val controller = controller()
        val activity = controller.get()
        settle()
        // Robolectric's `setup()` runs the first frame in place, so the
        // reading is taken by the time it returns and the inert frame after
        // a *first* start is not observable here; the restart is, since its
        // frame waits for the looper idle.
        assertTrue("read on the frame after the start", activity.idleOfferArmableForTest)

        controller.stop().start()

        assertFalse("a reading from before the stop does not count", activity.idleOfferArmableForTest)
        settle()
        assertTrue("until the restart's own lands", activity.idleOfferArmableForTest)
    }

    @Test
    fun `an idle tap rides the reading warmed before it, not a lookup made during it`() {
        // The arm path looks nothing up (SPEC.md §6.9): the reading every
        // start refreshes is what lets the tap through. Shown by taking the
        // grant away *after* the reading — a lookup would refuse, the reading
        // does not, and the engine degrades and says so if that ever happens
        // for real (Codex, PR #257).
        shadowApp().grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        activity.refreshLocationForTest()
        shadowApp().denyPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        forgetServiceStarts()

        activity.chooseDepartureFromScreen()
        settle()

        assertNotNull(sentArm())
    }

    @Test
    fun `until I leave on the idle screen asks for location rather than arming without it`() {
        // A departure nothing can watch for is not what the row names: with
        // no grant the tap asks, and nothing is armed until it lands.
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        forgetServiceStarts()

        activity.chooseDepartureFromScreen()
        settle()

        assertNull(sentArm())
        assertFalse(activity.rows.committing)
    }

    @Test
    fun `a tap on an offer the tile has replaced is dropped, not applied to the new snooze`() {
        // The record observer moves the rows onto the tile's snooze on the
        // main thread; the frame that drew the idle rows is replaced a frame
        // later, and a tap in between arrives with the old frame's offer. Sent
        // as it stood, the drawn time would go out under the *new* identity —
        // a refinement shortening a snooze it was never offered over (Codex,
        // PR #256).
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        val drawnIdle = EndChoiceUiState(
            condition = activity.rows.endCondition!!,
            formattedTime = "",
            meetings = listOf(MeetingChoice(at(3600), "")),
            startsASnooze = true,
            offerFor = null,
        )
        val running = snooze()
        ActiveSnoozeStore(context).arm(running)
        settle()
        assertEquals("the setup this rests on: the rows moved on", running.startedAt, activity.rows.offerFor)
        forgetServiceStarts()

        activity.chooseEndTimeFromScreen(drawnIdle)
        activity.chooseMeetingFromScreen(drawnIdle, 0)
        activity.stepEndFromScreen(drawnIdle, up = true)
        settle()

        // Neither a start nor a refinement. Not "nothing at all": under
        // Robolectric the screen's own refresh can send `ACTION_END` on its
        // schedule, which is the harness's business rather than the tap's.
        val sent = serviceActions()
        assertTrue("$sent", sent.none { it == SnoozeService.ACTION_ARM || it == SnoozeService.ACTION_SET_CAP })
        assertFalse(activity.rows.committing)
    }

    @Test
    fun `a tap on an offer the clock has rebuilt is dropped, not brought in silently`() {
        // The tick rebuilds the offer to start whole; a backward clock change
        // lowers its ceiling, and a meeting drawn below the old one can sit
        // above the new one, where the arm would bring it in silently. The
        // identity is null both before and after, so the match has to be the
        // whole offer (Codex, PR #256, the third finding in this gap).
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        val drawn = EndChoiceUiState(
            condition = activity.rows.endCondition!!,
            formattedTime = "",
            meetings = listOf(MeetingChoice(at(7 * 3600), "")),
            startsASnooze = true,
            offerFor = null,
        )
        activity.rows.refreshStart(at(-3 * 3600))
        assertTrue("the setup this rests on: the ceiling moved", activity.rows.endCondition != drawn.condition)
        forgetServiceStarts()

        activity.chooseMeetingFromScreen(drawn, 0)
        activity.chooseEndTimeFromScreen(drawn)
        settle()

        assertNull("nothing was sent", sentArm())
        assertFalse(activity.rows.committing)
    }

    @Test
    fun `a tap on the offer still standing goes out`() {
        // The other direction, so the guard above cannot pass by dropping
        // every tap.
        ActiveSnoozeStore(context).arm(snooze())
        val activity = screen()
        settle()
        forgetServiceStarts()
        val drawn = EndChoiceUiState(
            condition = activity.rows.endCondition!!,
            formattedTime = "",
            offerFor = activity.rows.offerFor,
        )

        activity.chooseEndTimeFromScreen(drawn)
        settle()

        assertTrue(serviceActions().contains(SnoozeService.ACTION_SET_CAP))
        assertTrue(activity.rows.committing)
    }

    @Test
    fun `the offer to start follows the clock whole`() {
        // Its ceiling is the cap the service would set, and that moves with
        // the clock (Codex, PR #256) — so a tick rebuilds it rather than
        // waiting for its time to fall inside the floor.
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        val seeded = activity.rows.endCondition!!

        val later = at(300)
        activity.rows.refreshStart(later)

        assertEquals(later.plus(ActiveSnooze.DEFAULT_CAP), activity.rows.endCondition!!.ceiling)
        assertTrue(activity.rows.endCondition!!.ceiling.isAfter(seeded.ceiling))
    }

    @Test
    fun `the idle calendar is re-read once the window has moved by the floor, or back at all`() {
        // Both directions of the clock (Codex, PR #256, twice in this
        // mechanism): forward, a re-read every half hour is what bounds the
        // cross-process query; backward by any amount, the window now holds
        // meetings the last query never asked for.
        val readAt = at().toEpochMilli()
        val floor = ActiveSnooze.MIN_CAP.toMillis()

        assertFalse(MainActivity.idleCalendarReadIsStale(readAt, readAt))
        assertFalse(MainActivity.idleCalendarReadIsStale(readAt, readAt + floor - 1))
        assertTrue(MainActivity.idleCalendarReadIsStale(readAt, readAt + floor))
        assertTrue("set back a minute", MainActivity.idleCalendarReadIsStale(readAt, readAt - 60_000L))
        assertTrue("set back three hours", MainActivity.idleCalendarReadIsStale(readAt, readAt - 3 * 3_600_000L))
    }

    @Test
    fun `a refused start leaves the offer standing where the tap happened`() {
        // Refused means nothing is running, so there is still something to
        // offer; the rows stay for a retry and say so.
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()
        activity.rows.commit(activity.rows.endCondition!!.endsAt)
        settle()

        EndChoiceOutcome.report(activity.rows.committingRequestId, EndChoiceResult.REFUSED)
        settle()

        assertTrue(activity.rows.commitFailed)
        assertTrue(activity.rows.startsASnooze)
    }

    @Test
    fun `a backstop already inside the floor offers nothing`() {
        // The service declines anything inside `MIN_CAP` and clamps anything
        // above the backstop, so with the two crossed there is no time to
        // offer.
        ActiveSnoozeStore(context).arm(snooze(capIn = ActiveSnooze.MIN_CAP.minusMinutes(5)))

        val activity = screen()
        settle()

        assertNull(activity.rows.endCondition)
    }

    @Test
    fun `a cap stepped down inside the floor still offers the way back out`() {
        // Asked of the cap, the rows vanish from a snooze the user stepped
        // down to half an hour — and vanish exactly where the way back out is
        // what they want. The backstop is hours away and every one of those
        // hours is still choosable, so the rows stand.
        ActiveSnoozeStore(context).arm(
            snooze(
                capIn = ActiveSnooze.MIN_CAP.minusMinutes(5),
                ceilingIn = Duration.ofHours(6),
            ),
        )

        val activity = screen()
        settle()

        assertNotNull(activity.rows.endCondition)
    }

    @Test
    fun `a snooze ending underneath takes its rows with it, and the offer to start stands`() {
        // The running rows named that snooze; with it gone they go, and the
        // idle screen's offer to start takes their place (SPEC.md §4.4) — a
        // tap now arms rather than refining a snooze that no longer exists.
        val running = snooze()
        ActiveSnoozeStore(context).arm(running)
        val activity = screen()
        settle()
        assertEquals(running.startedAt, activity.rows.offerFor)

        ActiveSnoozeStore(context).clear()
        settle()

        assertNull(activity.rows.offerFor)
        assertTrue(activity.rows.startsASnooze)
        assertNotNull(activity.rows.endCondition)
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
    fun `the idle calendar is asked only while the idle rows can show`() {
        // `MainScreen` draws the idle rows only under Do Not Disturb access,
        // and `docs/PRIVACY.md` says the time is read to draw a button — so
        // with access missing or unread the calendar is not asked, the grant
        // that makes the rows showable is what asks it, and a revocation
        // takes the answer off the screen (Codex, PR #256).
        val end = oneMeetingEnding(at().plus(Duration.ofHours(1)))
        ActiveSnoozeStore(context).clear()
        val activity = screen()
        settle()

        // Unread, or read as missing — the startup reading lands on its own
        // thread's schedule, and neither answer can show the rows.
        assertTrue("the setup this rests on", activity.access != PolicyAccess.GRANTED)
        assertEquals("not asked while access is unread or missing", emptyList<Instant>(), activity.meetingOffers)
        assertEquals(0L, activity.idleCalendarReadAtMillis)

        activity.applyAccessForTest(PolicyAccess.GRANTED)
        settle()
        assertEquals("the grant asks", listOf(end), activity.meetingOffers)
        assertTrue(activity.idleCalendarReadAtMillis > 0L)

        activity.applyAccessForTest(PolicyAccess.DENIED)
        settle()
        assertEquals("a revocation takes the times off", emptyList<Instant>(), activity.meetingOffers)

        activity.refreshSnoozingForTest()
        settle()
        assertEquals("and a later re-read does not ask again", emptyList<Instant>(), activity.meetingOffers)
    }

    @Test
    fun `a running snooze's meeting rows do not wait on Do Not Disturb access`() {
        // The gate is the idle offer's alone: the running rows and the
        // notification show under any access reading, and their calendar
        // read stays as it was.
        val end = oneMeetingEnding(at().plus(Duration.ofHours(1)))
        ActiveSnoozeStore(context).arm(snooze())

        val activity = screen()
        settle()

        assertTrue("the setup this rests on", activity.access != PolicyAccess.GRANTED)
        assertEquals(listOf(end), activity.meetingOffers)
    }

    /**
     * Grants the calendar and answers every `Instances` query with one end,
     * so whether the calendar was asked shows in [MainActivity.meetingOffers]
     * rather than having to be inferred.
     */
    private fun oneMeetingEnding(end: Instant): Instant {
        // Cleared per fixture: the recorder below is a companion property, so
        // without this a test that never asked the calendar could read the
        // previous one's selection and pass on it.
        OneMeetingProvider.lastSelection = null
        shadowApp().grantPermissions(android.Manifest.permission.READ_CALENDAR)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, OneMeetingProvider(end))
        return end
    }

    private class OneMeetingProvider(private val end: Instant) : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            lastSelection = selection
            return MatrixCursor(arrayOf(CalendarContract.Instances.END)).apply {
                addRow(arrayOf<Any>(end.toEpochMilli()))
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            /**
             * The selection the last query carried, which is where the window
             * lives — the provider answers with the same row whatever it is
             * asked, so a test that only read the result could not tell a
             * narrowed window from a wide one.
             */
            var lastSelection: String? = null
        }
    }

    @Test
    fun `a shortened cap does not narrow the screen's calendar window`() {
        // The maintainer's report: "when I choose a fixed end time, the next
        // meeting times should not disappear". A chosen time lowers the cap,
        // and this read was bounded by the cap — so the window shrank with it
        // and every meeting past the chosen time went off the screen for good,
        // since nothing re-widens a window the query never asked for.
        //
        // The notification's read goes through `NextMeetings`' `snooze`
        // overload and moved to the backstop with it; this one calls the `cap`
        // overload directly and kept the old bound, which is how it survived.
        val shortened = snooze(capIn = Duration.ofHours(2), ceilingIn = Duration.ofHours(7))
        val end = oneMeetingEnding(at().plus(Duration.ofHours(6)))
        ActiveSnoozeStore(context).arm(shortened)

        val activity = screen()
        settle()

        val selection = requireNotNull(OneMeetingProvider.lastSelection) {
            "the calendar was never asked"
        }
        assertTrue(
            "the window is the backstop: $selection",
            selection.contains(shortened.capCeilingAt.toEpochMilli().toString()),
        )
        assertFalse(
            "and not the cap a chosen time left behind: $selection",
            selection.contains(shortened.capExpiresAt.toEpochMilli().toString()),
        )
        assertEquals(
            "so a meeting past the chosen time is still offered",
            listOf(end),
            activity.meetingOffers,
        )
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

    /** The arm sent since the last drain, or null — an idle row's tap goes out as one. */
    private fun sentArm(): android.content.Intent? {
        var found: android.content.Intent? = null
        while (true) {
            val next = shadowApp().nextStartedService ?: return found
            if (next.action == SnoozeService.ACTION_ARM) found = next
        }
    }

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
