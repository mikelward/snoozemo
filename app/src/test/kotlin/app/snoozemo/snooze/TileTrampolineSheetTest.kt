package app.snoozemo.snooze

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper.getMainLooper
import app.snoozemo.core.EndCondition
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Which tile taps get the end-condition sheet (`SPEC.md` §4.4).
 *
 * The gate is three questions and the third is the one a started service cannot
 * answer: is a snooze actually running? Arming can be refused for missing Do Not
 * Disturb access, a switched-off rule, or a platform refusal, and each of those
 * erases the record on its way out — while `startService` reports success all
 * the same. Gating on the start alone put a sheet in front of an opted-in user
 * for a snooze that did not exist (Codex, PR #118).
 *
 * Robolectric never runs the service, so the record on disk is the whole input:
 * present stands for an arm that took, absent for one that didn't.
 */
@RunWith(RobolectricTestRunner::class)
class TileTrampolineSheetTest {

    /**
     * The clock the *activity* reads, not a fixed literal.
     *
     * The trampoline calls `System.currentTimeMillis()` directly, and it now
     * compares that against the record's cap to decide there is a time worth
     * offering. Robolectric's paused clock only moves forward, so a hardcoded
     * past instant cannot be installed under it — a record capped seven hours
     * after some January noon reads as long expired, and every sheet case fails
     * for the wrong reason. Taking `now` from the same source keeps the two
     * agreeing; every assertion here is relative to it.
     */
    private val now: Instant = Instant.ofEpochMilli(System.currentTimeMillis())

    private var controller: ActivityController<TileTrampolineActivity>? = null

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
        EndSheetStore(appContext).setEnabled(true)
        // Granted, so the arm path goes straight to the sheet decision. Denied,
        // it stops at the notification-permission request first — a real branch
        // with its own reason (SPEC.md §4.2), and not the one under test here.
        shadowOf(appContext).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Unlocked unless a test says otherwise: the sheet is skipped behind the
        // keyguard, and a leaked lock would make every other case here pass for
        // the wrong reason.
        shadowOf(appContext.getSystemService(android.app.KeyguardManager::class.java))
            .setKeyguardLocked(false)
    }

    @After
    fun tearDown() {
        EndChoiceOutcome.takePending()
        controller?.destroy()
        controller = null
        EndSheetStore(appContext).setEnabled(false)
    }

    /**
     * Taps the tile and drains the looper the posted block runs on.
     *
     * Driven all the way to *visible*, not just created: the decision runs from
     * `window.decorView.post`, and on an unattached decor view that lands in the
     * `ViewRootImpl` run queue, which only drains on a traversal. A
     * `create()`-only activity therefore never reaches the branch under test —
     * every case looks alike, and alike is not what any of them assert.
     */
    private fun tapTile(action: String = SnoozeService.ACTION_ARM): TileTrampolineActivity =
        tapTileController(action).get()

    private fun tapTileController(
        action: String = SnoozeService.ACTION_ARM,
    ): ActivityController<TileTrampolineActivity> {
        val intent = Intent(appContext, TileTrampolineActivity::class.java).setAction(action)
        return Robolectric.buildActivity(TileTrampolineActivity::class.java, intent)
            .also { controller = it }
            .setup()
            .also { shadowOf(getMainLooper()).idle() }
    }

    @Test
    fun `an arm that took gets the sheet`() {
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))

        val activity = tapTile()

        assertFalse(
            "a running snooze is exactly what the sheet is for",
            activity.isFinishing,
        )
    }

    @Test
    fun `an arm that was refused gets no sheet`() {
        // No record: the service accepted the start and then declined to arm.
        // The card saying so is already in the shade; a sheet over the top of it
        // would offer to set an end time for nothing, and every tap on it would
        // go nowhere.
        val activity = tapTile()

        assertTrue(
            "no snooze means nothing to refine",
            activity.isFinishing,
        )
    }

    @Test
    fun `the sheet stays off when the setting is off`() {
        EndSheetStore(appContext).setEnabled(false)
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))

        val activity = tapTile()

        assertTrue(
            "off by default means the tile arms and gets out of the way",
            activity.isFinishing,
        )
    }

    @Test
    fun `a configuration change does not arm a second time`() {
        // The tile's action used to be re-dispatched from `onCreate` on every
        // recreation, so rotating the phone while the sheet was up armed again
        // (Codex, PR #118). Before the sheet existed this activity finished
        // within a frame and the window barely existed; the sheet is what makes
        // it long enough to rotate in.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val controller = tapTileController()

        var startsFromTap = 0
        while (shadowOf(appContext).nextStartedService != null) startsFromTap++
        // Guards the guard: if the tap itself stopped arming, the assertion
        // below would pass for entirely the wrong reason.
        assertEquals("the tap itself still arms", 1, startsFromTap)

        controller.recreate()
        shadowOf(getMainLooper()).idle()

        assertNull("a rotation is not a tap", shadowOf(appContext).nextStartedService)
    }

    @Test
    fun `a rotation after the sheet was backgrounded does not arm again`() {
        // The signal used to be `isChangingConfigurations` read in
        // `onSaveInstanceState`. `ActivityThread` saves state only when none is
        // saved yet, so an activity Android had already stopped kept the bundle
        // it wrote *then* — before the configuration change was known, with the
        // flag false — and the relaunch restored it as a fresh tap, arming a
        // second time (Codex, PR #118). Home, rotate, return is the path.
        //
        // This pins the fixed behavior; it does not demonstrate the old bug.
        // Robolectric saves state on the recreation itself rather than
        // reproducing `ActivityThread`'s save-once rule, so the old signal
        // passes here too. The demonstration is the framework source, quoted
        // on the review thread and in `onCreate`.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val controller = tapTileController()

        // Backgrounded: this is where the old signal was written, and where it
        // was written wrong.
        controller.pause().stop()
        while (shadowOf(appContext).nextStartedService != null) Unit

        controller.recreate()
        shadowOf(getMainLooper()).idle()

        assertNull(
            "a rotation is not a tap, whenever the state happened to be saved",
            shadowOf(appContext).nextStartedService,
        )
    }

    @Test
    fun `a configuration change keeps the time the user stepped to`() {
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val controller = tapTileController()
        val activity = controller.get()

        // Two taps of `+`, then a rotation. Reseeding would silently undo the
        // only work the user has done on this screen.
        val stepped = requireNotNull(activity.endCondition).stepUp().stepUp()
        activity.endCondition = stepped

        controller.recreate()
        shadowOf(getMainLooper()).idle()

        assertEquals(stepped.endsAt, controller.get().endCondition?.endsAt)
    }

    @Test
    fun `a tap that restored a killed process is still dispatched`() {
        // This activity is `singleInstance`, so a process Android killed while
        // the sheet sat in the background is restored by the *next tile tap* —
        // and that creation carries saved state, exactly like a rotation.
        // Treating every non-null bundle as a rotation swallowed the tap (Codex,
        // PR #118), which on `End now` is the user's only exit before the
        // notification permission is granted.
        //
        // The bundle here carries no configuration-change marker, which is what
        // a background kill leaves behind.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val killedState = Bundle().apply {
            putBoolean("start_accepted", true)
            putBoolean("sheet_shown", true)
            putLong("ends_at", now.plus(Duration.ofHours(1)).toEpochMilli())
            putLong("floor", now.plus(Duration.ofMinutes(30)).toEpochMilli())
            putLong("ceiling", now.plus(Duration.ofHours(8)).toEpochMilli())
        }
        while (shadowOf(appContext).nextStartedService != null) Unit

        Robolectric.buildActivity(
            TileTrampolineActivity::class.java,
            Intent(appContext, TileTrampolineActivity::class.java)
                .setAction(SnoozeService.ACTION_END),
        ).also { controller = it }
            .create(killedState)
            .start()
            .resume()
            .visible()
            .also { shadowOf(getMainLooper()).idle() }

        assertNotNull(
            "the tap that brought the process back has to reach the service",
            shadowOf(appContext).nextStartedService,
        )
    }

    @Test
    fun `an outcome that lands during a rotation still reaches the sheet`() {
        // The watch belongs to the activity, so a commit in flight across a
        // recreation has nobody listening at the moment the service answers.
        // The replacement sheet has to pick that answer up or it waits forever
        // with every control inert (Codex, PR #118).
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val controller = tapTileController()
        controller.get().committing = true

        // Reported from the gap itself rather than staged beforehand: this
        // fires after the old activity is destroyed — taking its watch with it
        // — and before the replacement has registered one.
        val application = appContext
        val reportInTheGap = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                EndChoiceOutcome.report(EndChoiceResult.APPLIED)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(reportInTheGap)
        try {
            controller.recreate()
        } finally {
            application.unregisterActivityLifecycleCallbacks(reportInTheGap)
        }
        shadowOf(getMainLooper()).idle()

        assertTrue(
            "an applied change dismisses the sheet, whenever the answer arrived",
            controller.get().isFinishing,
        )
        assertNull("and it is consumed, not left for the next tap", EndChoiceOutcome.takePending())
    }

    @Test
    fun `the sheet cannot offer a time later than the snooze already ends`() {
        // The tile arms from its own snapshot, and a stale one sends a second
        // `ACTION_ARM` that the service answers by keeping the snooze already
        // running — so the record can be one with hours left rather than the
        // eight a fresh arm gets. Seeded against a constant, the sheet offered
        // a time past the real cap and the service answered `APPLIED`, because
        // a chosen time no earlier than the cap is honored by doing nothing.
        // The user was shown one time and got another (Codex, PR #118).
        val alreadyRunning = Duration.ofHours(2)
        ActiveSnoozeStore(appContext).save(snoozeFixture(now, capIn = alreadyRunning))

        val activity = tapTile()
        val condition = requireNotNull(activity.endCondition)

        assertEquals(
            "the ceiling is the cap this snooze carries, not a fresh eight hours",
            now.plus(alreadyRunning),
            condition.ceiling,
        )
        assertFalse(
            "and `+` stops there rather than stepping past the moment it ends",
            condition.copy(endsAt = condition.ceiling).canStepUp,
        )
    }

    @Test
    fun `a snooze already inside the floor gets no sheet at all`() {
        // With the cap ten minutes out there is no time the sheet could set:
        // anything inside the thirty-minute floor is declined, and the only
        // values above it are later than the cap, which the service honors by
        // doing nothing and reports as applied — a row promising 12:30 over a
        // snooze ending at 12:10 (Codex, PR #118). A screen with no answer is
        // worse than no screen, so the tap arms and gets out of the way.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now, capIn = Duration.ofMinutes(10)))

        val activity = tapTile()

        assertTrue("nothing to choose means nothing to ask", activity.isFinishing)
        assertNull("and no sheet was seeded behind it", activity.endCondition)
    }

    @Test
    fun `a rotation before the decision runs still reaches the sheet`() {
        // The decision is posted, so it belongs to the activity that posted it.
        // A configuration change landing in that window left the replacement
        // with a started service, no sheet, and nothing that would ever run —
        // a transparent window blank for as long as the user left it there
        // (Codex, PR #118). Built without draining the looper, so the posted
        // block genuinely has not run when the recreation happens.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val controller = Robolectric.buildActivity(
            TileTrampolineActivity::class.java,
            Intent(appContext, TileTrampolineActivity::class.java)
                .setAction(SnoozeService.ACTION_ARM),
        ).also { this.controller = it }.create().start().resume()

        // Not `visible()`: on an unattached decor view the posted block sits in
        // the `ViewRootImpl` run queue, which drains only on a traversal — so
        // this is the activity recreated with its decision genuinely unrun.
        controller.recreate().visible()
        shadowOf(getMainLooper()).idle()

        val activity = controller.get()
        assertFalse("a blank transparent window is the one thing this must not be", activity.isFinishing)
        assertNotNull("the replacement has to make the decision the first one never got to", activity.endCondition)
    }

    @Test
    fun `a second tap during a rotation is not lost behind the old sheet`() {
        // A tile tap arriving while the sheet is up reaches `onNewIntent`,
        // which dispatches it and posts a fresh decision — that tap armed a new
        // snooze, so the offer has to be reseeded. A recreation before the
        // decision ran used to restore the *old* sheet and never ask again,
        // because the branch keyed off whether a sheet had been drawn rather
        // than whether a decision was owed (Codex, PR #118).
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val controller = tapTileController()

        // Two taps of `+`, so a restored sheet is distinguishable from a
        // reseeded one: reseeding is what the second tap is owed.
        val stepped = requireNotNull(controller.get().endCondition).stepUp().stepUp()
        controller.get().endCondition = stepped

        // The second tap, deliberately left un-drained so its decision is still
        // owed when the recreation happens.
        controller.newIntent(
            Intent(appContext, TileTrampolineActivity::class.java)
                .setAction(SnoozeService.ACTION_ARM),
        )
        controller.recreate().visible()
        shadowOf(getMainLooper()).idle()

        assertEquals(
            "the second tap's decision has to reseed, not inherit the old offer",
            EndCondition.seededAt(now, now.plus(Duration.ofHours(7)), ZoneId.systemDefault()).endsAt,
            controller.get().endCondition?.endsAt,
        )
    }

    @Test
    fun `a locked phone gets no sheet`() {
        // Arming locked is a supported case (SPEC.md §4.2), but this activity
        // declares no `showWhenLocked` — a sheet rendered behind the keyguard is
        // one the user cannot see, cannot answer, and would meet on unlocking
        // with a time chosen against a clock that has moved on (Codex, PR #118).
        // The notification-permission request on this same path already skips
        // for exactly this reason.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        shadowOf(appContext.getSystemService(android.app.KeyguardManager::class.java))
            .setKeyguardLocked(true)

        val activity = tapTile()

        assertTrue(
            "the snooze is armed on its default cap; the sheet waits for a phone that can show it",
            activity.isFinishing,
        )
    }

    @Test
    fun `ending a snooze never gets the sheet, however the setting reads`() {
        // A user on their way out of the app's way is the last person to offer a
        // menu to (SPEC.md §7).
        ActiveSnoozeStore(appContext).save(snoozeFixture(now, capIn = Duration.ofHours(3)))
        val activity = tapTile(SnoozeService.ACTION_END)

        assertTrue(activity.isFinishing)
    }
}
