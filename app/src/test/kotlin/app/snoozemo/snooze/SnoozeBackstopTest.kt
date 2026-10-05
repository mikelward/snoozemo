package app.snoozemo.snooze

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Instant
import java.util.concurrent.Executor
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.BackstopProbe
import app.snoozemo.core.DepartureRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * One backstop wake, in each of its three shapes: retiring itself when the
 * snooze is gone, restoring when one is armed, and falling to the alarm
 * ladder when the restore is refused (SPEC.md §6.10).
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeBackstopTest {

    private val now: Instant = Instant.parse("2026-08-22T10:00:00Z")

    private val direct = Executor { it.run() }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)
        ActiveSnoozeStore(appContext).clear()
    }

    private val realProbe = SnoozeBackstop.probe

    /** Every probe the worker asked for, by the snooze it was asked about. */
    private val probed = mutableListOf<ActiveSnooze>()

    @After
    fun restoreProbe() {
        SnoozeBackstop.probe = realProbe
    }

    private fun probeAnswers(outcome: BackstopProbe.Outcome, during: () -> Unit = {}) {
        SnoozeBackstop.probe = { _, snooze ->
            probed += snooze
            during()
            outcome
        }
    }

    /** A context whose service starts are all refused, as on the field device. */
    private val refusing: Context
        get() = object : ContextWrapper(appContext) {
            override fun startService(service: Intent?): ComponentName? =
                throw IllegalStateException("the platform refuses the start")

            override fun getApplicationContext(): Context = this
        }

    private fun runWorker(context: Context = appContext): ListenableWorker.Result =
        TestWorkerBuilder.from(context, BackstopWorker::class.java, direct).build().doWork()

    @Test
    fun `a wake with no snooze retires the schedule`() {
        // The cancel can be lost to process death, so the wake is what stops
        // a dead snooze's backstop from draining the battery forever.
        SnoozeBackstop.schedule(appContext)

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        val infos = WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWork(SnoozeBackstop.NAME).get()
        assertTrue(
            "the schedule must be retired, not left waking for nothing",
            infos.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `a snooze armed mid-retirement keeps its backstop`() {
        // The cancel races arms over the shared unique name: a snooze armed
        // between the wake's empty read and the cancel would lose its fresh
        // schedule (Codex, PR #75). The retire re-reads afterward, so the
        // record that appeared keeps a backstop whichever side lost the race.
        SnoozeBackstop.schedule(appContext)
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))

        SnoozeBackstop.retireStale(appContext)

        val infos = WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWork(SnoozeBackstop.NAME).get()
        assertTrue(
            "the armed snooze must come out of the retirement still watched",
            infos.any { it.state == WorkInfo.State.ENQUEUED },
        )
    }

    @Test
    fun `a wake with a snooze restores the service`() {
        // The restore is the repair: it re-arms the cap, reconciles policy
        // access, re-registers the fence, and collects any held exit — the
        // wake only has to start it.
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        val started = shadowOf(appContext).nextStartedService
        assertEquals(SnoozeService.ACTION_CHECK_CAP, started?.action)
    }

    @Test
    fun `the rejection budget adopts the alarm's count and never refills from it`() {
        // The counter is process state; the alarm is what survives a death.
        // A fired retry carrying a partly spent budget must lower the
        // counter a fresh process refilled — otherwise a persistently broken
        // WorkManager plus a death per alarm waked every minute for the
        // whole snooze (Codex, PR #75) — and a fuller alarm (a presence-wake
        // retry's default) must never refill a counter that has spent more.
        SnoozeBackstop.cancel(appContext)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(SnoozeBackstop.SCHEDULE_RETRIES, SnoozeBackstop.retryBudget())

        SnoozeBackstop.adoptRetryBudget(1)
        assertEquals(1, SnoozeBackstop.retryBudget())
        SnoozeBackstop.adoptRetryBudget(SnoozeBackstop.SCHEDULE_RETRIES)
        assertEquals(1, SnoozeBackstop.retryBudget())

        // A snooze ending refills for the next one, as ever.
        SnoozeBackstop.cancel(appContext)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(SnoozeBackstop.SCHEDULE_RETRIES, SnoozeBackstop.retryBudget())
    }

    @Test
    fun `a refused restore falls to the alarm ladder`() {
        // A worker's process has no alarm receiver's start privileges, so the
        // refusal is handed to the same retry alarm the presence wake uses —
        // never dropped with the wake spent.
        // Probing finds nothing, so this is the ladder alone.
        probeAnswers(BackstopProbe.Outcome.NoFix)
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))

        val result = runWorker(refusing)

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(
            "the retry alarm must be armed to restore from its own window",
            scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_RESTORE },
        )
    }

    @Test
    fun `a refused restore runs the departure test and ends a confirmed departure`() {
        // Field log, 2026-10-05: every backstop restore and every retry alarm
        // was refused, three wakes running, while the user walked 300 m away.
        // The wake now tests departure itself rather than waiting on a start
        // the platform will not grant.
        probeAnswers(BackstopProbe.Outcome.Departed(DepartureRule.TWO_FIX))
        val snooze = snoozeFixture(now)
        ActiveSnoozeStore(appContext).arm(snooze)

        runWorker(refusing)

        assertEquals(listOf(snooze.startedAt), probed.map { it.startedAt })
        assertNull("the snooze is over", ActiveSnoozeStore(appContext).load())
        assertTrue(
            "and no retry is armed to start a service over the release (Codex, PR #310)",
            scheduledAlarmIntents().none { it.action == SnoozeService.ACTION_RESTORE },
        )
    }

    @Test
    fun `a refused restore that finds the user still here ends nothing`() {
        probeAnswers(BackstopProbe.Outcome.StillHere)
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))

        runWorker(refusing)

        assertEquals(1, probed.size)
        assertNotNull(ActiveSnoozeStore(appContext).load())
        assertTrue(
            "the retry is armed once the probe has settled nothing",
            scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_RESTORE },
        )
    }

    @Test
    fun `a snooze replaced during the probe is not ended by it`() {
        // The probe takes minutes; a snooze ended and re-armed in that time
        // was never tested, and its anchor may be where the user stands now.
        val replacement = snoozeFixture(now, startedAgo = java.time.Duration.ZERO)
        probeAnswers(BackstopProbe.Outcome.Departed(DepartureRule.UNAMBIGUOUS)) {
            ActiveSnoozeStore(appContext).arm(replacement)
        }
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))

        runWorker(refusing)

        assertEquals(replacement.startedAt, ActiveSnoozeStore(appContext).load()?.startedAt)
    }

    @Test
    fun `a departure exit withdrawn during the probe is not taken`() {
        // Codex, PR #310: a time-only end chosen mid-probe keeps the start, so
        // identity alone would still end the snooze for a departure the user
        // had just switched off.
        val snooze = snoozeFixture(now)
        probeAnswers(BackstopProbe.Outcome.Departed(DepartureRule.TWO_FIX)) {
            ActiveSnoozeStore(appContext).arm(snooze.copy(endsOnDeparture = false))
        }
        ActiveSnoozeStore(appContext).arm(snooze)

        runWorker(refusing)

        assertNotNull("the snooze runs on to the time the user chose", ActiveSnoozeStore(appContext).load())
    }

    @Test
    fun `a probe that throws ends nothing and keeps the retry`() {
        SnoozeBackstop.probe = { _, _ -> throw IllegalStateException("the probe failed") }
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))

        val result = runWorker(refusing)

        assertEquals(ListenableWorker.Result.success(), result)
        assertNotNull(ActiveSnoozeStore(appContext).load())
        assertTrue(scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_RESTORE })
    }

    @Test
    fun `a restored service does the probing, not the worker`() {
        probeAnswers(BackstopProbe.Outcome.Departed(DepartureRule.TWO_FIX))
        ActiveSnoozeStore(appContext).arm(snoozeFixture(now))

        runWorker()

        assertTrue("the service's own monitor takes the resting fix", probed.isEmpty())
        assertNotNull(ActiveSnoozeStore(appContext).load())
    }
}
