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
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
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
        TestSnoozeService.zen.outcome = ZenOutcome.Applied
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
