package app.snoozemo.ui

import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.EndSheetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper.getMainLooper
import java.time.Duration
import java.time.Instant

/**
 * Arming from the app screen offers the same end-condition sheet the tile
 * does (SPEC.md §4.4; maintainer, 2026-08-30).
 *
 * It did not, and the split was invisible: the same action asked when it came
 * from the shade and silently took the default cap when it came from the
 * button.
 *
 * **These cover the cases where no sheet opens, and that is a limitation, not
 * a choice.** `ModalBottomSheet` is a popup window, and a popup does not
 * settle under Robolectric — the same shape as the `Dialog` + `TextField`
 * cascade `AGENTS.md` records. A case that actually opens one here runs for
 * nine minutes and is then skipped, so the composing half cannot be asserted
 * at this level at all. The trampoline's equivalent test works only because
 * that sheet is drawn over a hand-built `Box` rather than a popup.
 *
 * What that leaves covered elsewhere, deliberately and where a JVM test can
 * reach it: the offer's three gates are each pure and tested
 * (`EndConditionChoiceTest`), and the sheet's own behavior once seeded —
 * stepping, committing, refusal, restore across a configuration change — is
 * `EndChoiceControllerTest`. What no automated test here can show is the sheet
 * appearing over this screen; that is on the device-check list.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivitySheetTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun snoozeWithCapIn(gap: Duration): ActiveSnooze {
        val now = Instant.now()
        return ActiveSnooze(
            anchor = Anchor(capturedAt = now, ssid = "ExampleWifi"),
            startedAt = now,
            capExpiresAt = now.plus(gap),
            mode = TrackingMode.DURATION_ONLY,
        )
    }

    /**
     * The offer's reads run inline here, so each test asserts on a settled
     * state rather than racing a thread — the ordering is explicit rather than
     * waited on (`AGENTS.md`, no papering over racy tests).
     */
    private fun screen(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get().also {
            it.runOffMainThread = { work -> work() }
        }

    /**
     * Drains the offer's own post — which is deliberate in production, so the
     * record the service writes from `onStartCommand` exists by the time it
     * reads — and then its post back to the main thread.
     */
    private fun settle() {
        shadowOf(getMainLooper()).idle()
    }

    @Test
    fun `with the setting off it arms and gets out of the way`() {
        // One tap and nothing in the way is goal 1; a user who has not asked
        // to be asked should never be.
        EndSheetStore(context).setEnabled(false)
        ActiveSnoozeStore(context).save(snoozeWithCapIn(ActiveSnooze.DEFAULT_CAP))
        val activity = screen()

        activity.offerSheetForThisArm()
        settle()

        assertNull(activity.sheet.endCondition)
    }

    @Test
    fun `a cap already inside the floor offers nothing`() {
        // The service declines anything inside `MIN_CAP` and honors anything
        // past the cap by doing nothing, so the sheet would be a screen the
        // user cannot answer.
        EndSheetStore(context).setEnabled(true)
        ActiveSnoozeStore(context).save(snoozeWithCapIn(ActiveSnooze.MIN_CAP.minusMinutes(5)))
        val activity = screen()

        activity.offerSheetForThisArm()
        settle()

        assertNull(activity.sheet.endCondition)
    }

    @Test
    fun `an arm that never landed leaves nothing owed`() {
        // The service accepted the start and then failed to arm — no policy
        // access, a zen rule that would not go on — so no record was written.
        // This is one shot: there is no debt left behind that a later,
        // unrelated arm could satisfy by opening a sheet nobody asked for
        // (Codex, PR #152).
        EndSheetStore(context).setEnabled(true)
        ActiveSnoozeStore(context).clear()
        val activity = screen()

        activity.offerSheetForThisArm()
        settle()
        assertNull(activity.sheet.endCondition)

        // A snooze arrives later from somewhere else entirely — the tile.
        ActiveSnoozeStore(context).save(snoozeWithCapIn(ActiveSnooze.DEFAULT_CAP))
        activity.activeSnooze = ActiveSnoozeStore(context).load()
        settle()

        assertNull("no sheet this screen never asked for", activity.sheet.endCondition)
    }

    @Test
    fun `a sheet restored over a snooze that ended is dropped`() {
        // `onSaveInstanceState` cannot tell a rotation from a process death.
        // After a death the snooze can be long over — a departure, its own cap
        // — and putting the sheet back unchecked offers times against nothing
        // (Codex, PR #152).
        EndSheetStore(context).setEnabled(true)
        ActiveSnoozeStore(context).save(snoozeWithCapIn(ActiveSnooze.DEFAULT_CAP))
        val activity = screen()
        activity.offerSheetForThisArm()
        settle()
        assertNotNull("precondition: a sheet is up", activity.sheet.endCondition)

        // The record is gone by the time the record read answers.
        activity.reconcileSheet(record = null, seenAtGeneration = activity.sheetGenerationForTest)

        assertNull(activity.sheet.endCondition)
    }

    @Test
    fun `a snooze ending under an open sheet takes the sheet with it`() {
        // The live half of the same check: no tap needed to discover it.
        EndSheetStore(context).setEnabled(true)
        ActiveSnoozeStore(context).save(snoozeWithCapIn(ActiveSnooze.DEFAULT_CAP))
        val activity = screen()
        activity.offerSheetForThisArm()
        settle()

        // A cap now inside the floor: there is no time left worth offering.
        activity.reconcileSheet(
            record = snoozeWithCapIn(ActiveSnooze.MIN_CAP.minusMinutes(5)),
            seenAtGeneration = activity.sheetGenerationForTest,
        )

        assertNull(activity.sheet.endCondition)
    }

    @Test
    fun `a record read from before the arm cannot drop the new sheet`() {
        // `refreshSnoozing`'s reads finish in whatever order the disk returns
        // them, so one begun before the arm carries a record from before it.
        // Without the generation guard that stale `null` would tear down the
        // sheet the arm just put up.
        EndSheetStore(context).setEnabled(true)
        ActiveSnoozeStore(context).save(snoozeWithCapIn(ActiveSnooze.DEFAULT_CAP))
        val activity = screen()
        val beforeTheArm = activity.sheetGenerationForTest

        activity.offerSheetForThisArm()
        settle()
        assertNotNull("precondition: a sheet is up", activity.sheet.endCondition)

        activity.reconcileSheet(record = null, seenAtGeneration = beforeTheArm)

        assertNotNull(activity.sheet.endCondition)
    }

    @Test
    fun `a commit in flight is never dismissed underneath`() {
        // The answer is coming and settles the sheet itself; dismissing here
        // would lose the refusal message SPEC.md 4.2 requires reach a user who
        // denied notifications.
        EndSheetStore(context).setEnabled(true)
        ActiveSnoozeStore(context).save(snoozeWithCapIn(ActiveSnooze.DEFAULT_CAP))
        val activity = screen()
        activity.offerSheetForThisArm()
        settle()
        activity.sheet.committing = true

        activity.reconcileSheet(record = null, seenAtGeneration = activity.sheetGenerationForTest)

        assertNotNull(activity.sheet.endCondition)
    }
}
