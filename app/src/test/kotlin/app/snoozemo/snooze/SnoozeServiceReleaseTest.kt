package app.snoozemo.snooze

import app.snoozemo.R
import app.snoozemo.core.EndReason
import app.snoozemo.core.ZenOutcome
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The performing half of SPEC.md §7.1, against a platform that refuses.
 *
 * Each test here is a bug that reached review on PR #11 — none of them was
 * caught by a test, because until this harness existed none of them *could* be.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceReleaseTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
    }

    @Test
    fun `a cap-lost retry carries the reason it was armed for`() {
        // The alarm outlives the process, so the reason has to travel on it.
        // Without this the identified retry reported every end as a capability
        // failure — a duration cap became "Snoozemo lost something".
        val record = snoozeFixture(now, capIn = java.time.Duration.ofHours(7))
        startService(SnoozeService.ACTION_CAP_LOST, record) {
            putExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, record.startedAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_END_REASON, EndReason.DURATION_CAP.name)
        }

        val retry = scheduledAlarmIntents().last { it.action == SnoozeService.ACTION_CAP_LOST }

        assertEquals(
            EndReason.DURATION_CAP.name,
            retry.getStringExtra(SnoozeService.EXTRA_END_REASON),
        )
    }

    @Test
    fun `a cap expiry is not reported as a failed resume`() {
        // The release *succeeds* here, which is the case that matters and the
        // one the bug was about: nothing else posts afterwards, so whatever
        // this branch said is what the user is left looking at — beside
        // `Snooze ended · time limit reached`, under a different id, so it sits
        // there contradicting it rather than replacing it.
        //
        // With a refusing platform this assertion would pass for the wrong
        // reason: the ladder reaches `HandOff`, whose message shares
        // `ID_FAILURE` with this one and overwrites it within the same call.
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
        val record = snoozeFixture(now)
        startService(SnoozeService.ACTION_CAP_LOST, record) {
            putExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, record.startedAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_END_REASON, EndReason.DURATION_CAP.name)
        }

        assertFalse(
            "a duration cap must not claim the app could not resume",
            shadeShows(stringOf(R.string.failure_could_not_resume)),
        )
    }

    @Test
    fun `a reboot that could not resume still says so`() {
        // The other half of the gate: the message is right for the situation it
        // was written for, and removing it there would be the opposite failure.
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
        val record = snoozeFixture(now)
        startService(SnoozeService.ACTION_CAP_LOST, record) {
            putExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, record.startedAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_END_REASON, EndReason.LOST_CAPABILITY.name)
        }

        assertTrue(
            "a lost cap is exactly what this message is for",
            shadeShows(stringOf(R.string.failure_could_not_resume)),
        )
    }

    @Test
    fun `a refused manual end keeps trying rather than waiting for a distant cap`() {
        // The snooze caps seven hours out, so re-arming it is not a retry of
        // the end the user asked for. Stopping there left the phone quiet for
        // the rest of the snooze with nothing trying and nothing said.
        val record = snoozeFixture(now, capIn = java.time.Duration.ofHours(7))
        startService(SnoozeService.ACTION_END, record)

        val armed = scheduledAlarmIntents()
        assertTrue(
            "the ladder must continue past a cap that is hours away",
            armed.any { it.action == SnoozeService.ACTION_CAP_LOST },
        )
    }

    @Test
    fun `a refused end on an expired snooze leaves the due cap to it`() {
        // The case the early return was right for: the record is already past
        // its cap, so the re-armed wake-up is due and retries within moments.
        // Escalating here would buy a second successor for the same moment.
        val record = snoozeFixture(now, capIn = java.time.Duration.ofMinutes(-5))
        startService(SnoozeService.ACTION_END, record)

        assertTrue(
            "an already-due cap is the retry, so no release-retry alarm is needed",
            scheduledAlarmIntents().none { it.action == SnoozeService.ACTION_CAP_LOST },
        )
    }

    @Test
    fun `a stale could-not-end card comes down once the release works`() {
        // The card promises a retry, so once a retry actually ends the snooze
        // it is claiming the opposite of the `Snooze ended` beside it — and
        // `setAutoCancel` only dismisses on a tap, so nothing else removes it
        // (flagged by Codex on PR #8).
        val record = snoozeFixture(now)
        startService(SnoozeService.ACTION_END, record)
        assertTrue(
            "the refused end must have told the user it is retrying",
            shadeShows(stringOf(R.string.failure_could_not_end)),
        )

        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")
        startService(SnoozeService.ACTION_END, record)

        assertFalse(
            "a release that worked must retire the claim that it couldn't",
            shadeShows(stringOf(R.string.failure_could_not_end)),
        )
    }

    @Test
    fun `an ending that explains itself keeps the explanation`() {
        // The guard on the fix above: the could-not-end card is retired by id,
        // not by blanket-canceling the shared one-shot id — which would also
        // take down the access-revocation message posted moments before the
        // same ending completes, the one actionable thing the user is owed.
        TestSnoozeService.zen.outcome =
            ZenOutcome.NotApplied(app.snoozemo.core.ZenFailure.NO_POLICY_ACCESS)
        val record = snoozeFixture(now)
        startService(SnoozeService.ACTION_END, record)

        assertTrue(
            "losing access ends the snooze, and the reason must survive the ending",
            shadeShows(stringOf(R.string.failure_no_access)),
        )
    }

    @Test
    fun `a failed arm still discharges an outstanding stuck-rule obligation`() {
        // ACTION_ARM skips the discharge on the way in to keep the arm path
        // clean, justified by "a successful arm clears the flag anyway" — true
        // of a successful arm, not of one refused before or at the zen write,
        // which used to stop with nothing scheduled while an older rule may
        // still have been silencing the phone (flagged by Codex on PR #8).
        PendingFailureStore(appContext).rememberRuleMayBeStuck()
        TestSnoozeService.zen.outcome =
            ZenOutcome.NotApplied(app.snoozemo.core.ZenFailure.NO_POLICY_ACCESS)

        startService(SnoozeService.ACTION_ARM)

        assertFalse(
            "an arm refused with nothing silencing the phone settles the obligation",
            PendingFailureStore(appContext).ruleMayBeStuck(),
        )
    }

    @Test
    @org.robolectric.annotation.Config(shadows = [RefusingAlarmManager::class])
    fun `an arm stopped by a refused cap alarm also discharges the obligation`() {
        // The other failed-arm branch: this one dies *before* the zen write, at
        // the cap alarm the arm refuses to proceed without (SPEC.md §7), so the
        // zen-refusal test above cannot reach it — its shadow platform grants
        // every alarm. This shadow refuses them, which is the only way to make
        // `CapAlarm.armCheckIn` fail on a JVM (flagged by Codex on PR #61).
        PendingFailureStore(appContext).rememberRuleMayBeStuck()
        TestSnoozeService.zen.outcome = ZenOutcome.Applied("refusing-zen-rule-id")

        startService(SnoozeService.ACTION_ARM)

        assertEquals(
            "the discharge must have driven the leftover rule off — and never on",
            listOf(false to app.snoozemo.core.ZenTrigger.CONTEXT),
            TestSnoozeService.zen.calls,
        )
        assertFalse(
            "a confirmed-off rule settles the obligation",
            PendingFailureStore(appContext).ruleMayBeStuck(),
        )
    }

    @Test
    fun `a refused release is asked for as the reason it started with`() {
        // The trigger reaches the platform's Modes UI, so a cap expiry reported
        // as USER_ACTION tells the user they ended a snooze they didn't.
        val record = snoozeFixture(now)
        startService(SnoozeService.ACTION_CAP_LOST, record) {
            putExtra(SnoozeService.EXTRA_RECORD_STARTED_AT, record.startedAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_END_REASON, EndReason.DURATION_CAP.name)
        }

        val releases = TestSnoozeService.zen.calls.filter { !it.first }
        assertTrue("the release must have been attempted", releases.isNotEmpty())
        assertTrue(
            "a cap expiry is not the user acting",
            releases.none { it.second == app.snoozemo.core.ZenTrigger.USER_ACTION },
        )
    }
}

/**
 * An `AlarmManager` that refuses every schedule, which no real shadow does.
 *
 * The default shadow grants them all, so the arm branch that fails *at the cap
 * alarm* — before any zen call — was unreachable by a test until this. Only
 * `setAndAllowWhileIdle` throws: the app schedules everything through it, and
 * the rest of the shadow keeps behaving so cancels stay harmless.
 */
@org.robolectric.annotation.Implements(android.app.AlarmManager::class)
class RefusingAlarmManager : org.robolectric.shadows.ShadowAlarmManager() {
    @org.robolectric.annotation.Implementation
    override fun setAndAllowWhileIdle(
        type: Int,
        triggerAtMillis: Long,
        operation: android.app.PendingIntent,
    ) {
        throw SecurityException("The test platform refuses every alarm.")
    }
}
