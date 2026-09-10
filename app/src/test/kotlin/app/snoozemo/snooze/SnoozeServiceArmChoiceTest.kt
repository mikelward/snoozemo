package app.snoozemo.snooze

import app.snoozemo.R
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.ZenOutcome
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An arm that carries the end condition an idle-screen row chose (SPEC.md
 * §4.4, maintainer, 2026-09-10): tapping `Until 2:30 PM` or `Until I move`
 * with nothing running starts the snooze with that end, in one tap.
 *
 * What the row is told means something simpler than over a running snooze,
 * and both directions of it are pinned here: `APPLIED` and `GONE` say a
 * snooze is running now, `REFUSED` says none is. A plain arm carries no
 * request and is told nothing, as it always was.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeServiceArmChoiceTest {

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    /** The commit these intents claim to come from; see [setUp]. */
    private val REQUEST = 44L

    private var reported: EndChoiceResult? = null
    private var watch: AutoCloseable? = null

    @Before
    fun setUp() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied(OWN_RULE_ID)
        TogglableAlarmManager.refuse = false
        ActiveSnoozeStore(appContext).clear()
        reported = null
        EndChoiceOutcome.reset()
        watch = EndChoiceOutcome.watch(REQUEST) { reported = it }
    }

    @After
    fun tearDown() {
        watch?.close()
        watch = null
    }

    private fun armUntil(endsAt: Instant, record: ActiveSnooze? = null) =
        startService(SnoozeService.ACTION_ARM, record) {
            putExtra(SnoozeService.EXTRA_CAP_EXPIRES_AT, endsAt.toEpochMilli())
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

    private fun armUntilMotion() =
        startService(SnoozeService.ACTION_ARM) {
            putExtra(SnoozeService.EXTRA_ENDS_ON_MOTION, true)
            putExtra(SnoozeService.EXTRA_CHOICE_REQUEST_ID, REQUEST)
        }

    private fun stored(): ActiveSnooze? = ActiveSnoozeStore(appContext).load()

    @Test
    fun `a chosen end arms a snooze that caps there`() {
        val chosen = now.plus(Duration.ofHours(1))

        armUntil(chosen)

        assertEquals(chosen, stored()?.capExpiresAt)
        assertEquals("and the row is told it took", EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `a chosen end past the default cap is brought in to it`() {
        // The row was offered under that ceiling, and §7's backstop stays
        // absolute above any chosen value.
        armUntil(now.plus(ActiveSnooze.DEFAULT_CAP).plus(Duration.ofHours(1)))

        assertEquals(now.plus(ActiveSnooze.DEFAULT_CAP), stored()?.capExpiresAt)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `a chosen end inside the floor arms nothing and says so`() {
        // Declined, not moved: the row is drawn against the clock and can sit
        // there, and clamping it up would arm a snooze on a deadline the user
        // was never shown. Refused before anything is armed, so "refused"
        // keeps meaning "nothing is running".
        armUntil(now.plus(ActiveSnooze.MIN_CAP).minus(Duration.ofMinutes(1)))

        assertNull("nothing armed", stored())
        assertEquals(EndChoiceResult.REFUSED, reported)
    }

    @Test
    fun `a plain arm is told nothing`() {
        startService(SnoozeService.ACTION_ARM)

        assertNotNull("the arm itself is unchanged", stored())
        assertNull("no row asked", reported)
        assertNull("and nothing was held for one either", EndChoiceOutcome.takePending(0L))
    }

    @Test
    fun `a row's arm over a running snooze keeps the running one and is told gone`() {
        // Armed from the tile between the row being drawn and the tap. A
        // duplicate arm restates what is running rather than replacing its
        // deadline (SPEC.md §4.2); the row's offer is over, and the screen's
        // next record read seeds the running snooze's own rows.
        val running = snoozeFixture(now, capIn = Duration.ofHours(4))

        armUntil(now.plus(Duration.ofHours(1)), record = running)

        assertEquals(running.capExpiresAt, stored()?.capExpiresAt)
        assertEquals(EndChoiceResult.GONE, reported)
    }

    @Test
    fun `a refused arm is told refused`() {
        // The zen rule will not go on, so nothing is running and the rows
        // stand for a retry with "Couldn't snooze" beside them.
        TestSnoozeService.zen.outcome = ZenOutcome.NotApplied(app.snoozemo.core.ZenFailure.PLATFORM_REFUSED)

        armUntil(now.plus(Duration.ofHours(1)))

        assertNull(stored())
        assertEquals(EndChoiceResult.REFUSED, reported)
    }

    @Test
    fun `until I move as the way to start arms the sensor too`() {
        armUntilMotion()

        assertNotNull(stored())
        assertEquals(true, stored()?.endsOnMotion)
        assertTrue(TestSnoozeService.motionRegistrar.armed)
        assertEquals(EndChoiceResult.APPLIED, reported)
    }

    @Test
    fun `a start whose sensor the phone will not hold still starts, and says so in the shade`() {
        // The snooze is running whether or not the exit took, so the idle
        // offer is over either way — `GONE`, not `APPLIED`, since the choice
        // was not applied to it — and the refusal goes to the shade as a tap
        // with no row behind it does, because by the time it could be shown
        // inline the rows have moved on to the running snooze.
        TestSnoozeService.motionRegistrar.available = false

        armUntilMotion()

        assertNotNull("the snooze is running", stored())
        assertEquals("without the exit that could not be kept", false, stored()?.endsOnMotion)
        assertFalse(TestSnoozeService.motionRegistrar.armed)
        assertEquals(EndChoiceResult.GONE, reported)
        assertTrue(shadeShows(stringOf(R.string.failure_could_not_set_end)))
    }
}
