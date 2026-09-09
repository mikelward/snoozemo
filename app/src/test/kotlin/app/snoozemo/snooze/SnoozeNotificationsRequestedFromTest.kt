package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import app.snoozemo.R
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every action on the ongoing card names the shade as where it came from
 * (`SnoozeService.EXTRA_REQUESTED_FROM`).
 *
 * This is a diagnostic contract rather than a behavioral one — nothing
 * branches on the extra — but it is the kind that decays silently, which is
 * why it is pinned. The debug log reads the *absence* of the extra as "the
 * tile", because the tile builds its intent in `:tile` and has no dependency
 * on `SnoozeService` to name itself with. So an action added here without the
 * extra does not fail, or look wrong, or say anything at all: it quietly
 * reports the shade's own button as a tile tap, in the one log a user hands
 * over when they cannot explain what ended their snooze.
 *
 * Asserted over *all* the actions rather than the two that exist today, so a
 * third one is covered by this test on the day it is written rather than the
 * day someone remembers to extend it.
 */
@RunWith(RobolectricTestRunner::class)
// A plain `Application` for the reason the sibling notification tests give:
// `SnoozemoApplication.onCreate` starts ringer reconciliation on a daemon
// thread that races the test body for the process-wide ringer lock.
@Config(application = Application::class)
class SnoozeNotificationsRequestedFromTest {

    private val now: Instant = Instant.parse("2026-08-22T09:00:00Z")

    @Before
    fun reset() {
        SnoozeNotifications.resetForTest()
    }

    @Test
    fun `every ongoing action says it came from the notification`() {
        SnoozeNotifications(appContext).showOngoing(snoozeFixture(now))

        val manager = appContext.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications
            .last { shadowOf(it).contentTitle?.toString() == stringOf(R.string.ongoing_title) }

        // The card is expected to carry actions at all: an empty list would
        // make every assertion below vacuously true, which is this test's own
        // failure mode rather than the product's.
        val actions = posted.actions.orEmpty()
        assertEquals("the ongoing card should carry its two actions", 2, actions.size)

        actions.forEach { action ->
            val intent = shadowOf(action.actionIntent).savedIntent
            assertEquals(
                "the action for ${intent.action} did not name where it came from, " +
                    "so the log would read it as a tile tap",
                SnoozeService.REQUESTED_FROM_NOTIFICATION,
                intent.getStringExtra(SnoozeService.EXTRA_REQUESTED_FROM),
            )
        }
    }
}
