package app.snoozemo.snooze

import android.app.NotificationManager
import android.os.Looper.getMainLooper
import app.snoozemo.core.EndReason
import app.snoozemo.core.SnoozeLifecycle
import app.snoozemo.core.SnoozeRecordState
import app.snoozemo.core.ZenOutcome
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Every ending stops the record reading as live **before** it takes the
 * ongoing card down.
 *
 * The backstop worker posts a missing card for a snooze whose record still
 * reads as live (`SnoozeNotifications.postIfStillLive`). That is race-free
 * only in this order: a card taken down while the record still read as live
 * would let the worker put `Snoozing` back over an ending in progress. So the
 * order is pinned here, for the service's release and the no-service one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [RecordAtCardTakedown::class])
class TeardownOrderTest {

    private val now: Instant = Instant.parse("2026-08-22T10:00:00Z")

    @Before
    fun reset() {
        TestSnoozeService.reset(now)
        TestSnoozeService.zen.outcome = ZenOutcome.Applied(OWN_RULE_ID)
        ActiveSnoozeStore(appContext).clear()
        RecordAtCardTakedown.recordLive.clear()
    }

    @Test
    fun `the service's ending forgets the record before the card comes down`() {
        val running = snoozeFixture(now)
        ActiveSnoozeStore(appContext).arm(running)
        ActiveSnoozeStore(appContext).setState(SnoozeRecordState(SnoozeLifecycle.ARMED))
        SnoozeNotifications(appContext).showOngoing(running)

        startService(SnoozeService.ACTION_END)
        shadowOf(getMainLooper()).idle()

        assertTrue("the snooze ended", ActiveSnoozeStore(appContext).load() == null)
        assertTrue("the card came down", RecordAtCardTakedown.recordLive.isNotEmpty())
        assertEquals(
            "the record already read as gone each time",
            List(RecordAtCardTakedown.recordLive.size) { false },
            RecordAtCardTakedown.recordLive,
        )
    }

    @Test
    fun `the no-service release marks the record released before the card comes down`() {
        val running = snoozeFixture(now)
        ActiveSnoozeStore(appContext).arm(running)
        SnoozeNotifications(appContext).showOngoing(running)

        releaseDirectly(
            appContext,
            EndReason.MANUAL,
            RefusingZen().apply { outcome = ZenOutcome.Applied(OWN_RULE_ID) },
        )

        assertTrue("the card came down", RecordAtCardTakedown.recordLive.isNotEmpty())
        assertEquals(
            List(RecordAtCardTakedown.recordLive.size) { false },
            RecordAtCardTakedown.recordLive,
        )
    }
}

/** Records, at each takedown of the ongoing card, whether the record still read as live. */
@Implements(NotificationManager::class)
class RecordAtCardTakedown : ShadowNotificationManager() {

    @Implementation
    override fun cancel(tag: String?, id: Int) {
        note(id)
        super.cancel(tag, id)
    }

    @Implementation
    override fun cancel(id: Int) {
        note(id)
        super.cancel(id)
    }

    private fun note(id: Int) {
        if (id == SnoozeNotifications.ID_ONGOING) recordLive += ActiveSnoozeStore(appContext).load() != null
    }

    companion object {
        /** Static, like every shadow's state; cleared by the test. */
        val recordLive = mutableListOf<Boolean>()
    }
}
