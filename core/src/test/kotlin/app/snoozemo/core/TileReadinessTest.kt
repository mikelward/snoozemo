package app.snoozemo.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileReadinessTest {

    @Test
    fun `everything granted needs no setup`() {
        assertFalse(
            tileTapNeedsSetup(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                activeChannelEnabled = true,
            ),
        )
    }

    @Test
    fun `missing policy access needs setup`() {
        // The tap produces no snooze by any route without this, so explaining
        // beats the silent nothing the user gets otherwise.
        assertTrue(
            tileTapNeedsSetup(
                access = PolicyAccess.DENIED,
                notifications = NotificationPermission.GRANTED,
                activeChannelEnabled = true,
            ),
        )
    }

    @Test
    fun `blocked notifications need setup`() {
        // The platform ignores further requests, so the app's settings are the
        // only live route and a prompt would never appear.
        assertTrue(
            tileTapNeedsSetup(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.BLOCKED,
                activeChannelEnabled = true,
            ),
        )
    }

    @Test
    fun `askable notifications do not need setup`() {
        // The caller answers this with the runtime dialog — one tap, where a
        // screen would be a detour. Routing here would replace a better remedy
        // with a worse one, which is why the gate reads BLOCKED and not
        // "anything other than granted".
        assertFalse(
            tileTapNeedsSetup(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.ASKABLE,
                activeChannelEnabled = true,
            ),
        )
    }

    @Test
    fun `granted notifications with the active channel switched off needs setup`() {
        // Granted is necessary and not sufficient: the permission can be held
        // while the channel a running snooze reports on is off, and the system
        // then drops the post — an invisible snooze, which is the failure this
        // gate exists to prevent.
        assertTrue(
            tileTapNeedsSetup(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                activeChannelEnabled = false,
            ),
        )
    }

    @Test
    fun `an unread capability is not a missing one`() {
        // Null is a reading that has not landed. Routing on one would open the
        // app over a question nothing has asked yet — the same exclusion
        // `welcomeExitNeedsRecap` makes, and for the same reason.
        assertFalse(
            tileTapNeedsSetup(
                access = null,
                notifications = null,
                activeChannelEnabled = null,
            ),
        )
    }

    @Test
    fun `an unread channel reading does not by itself need setup`() {
        // Both halves of the notification test are read separately, so an
        // unread channel must not turn a granted permission into a missing
        // capability. Without this the first tap of every cold process would
        // route, which is the false positive that would make the gate hated.
        assertFalse(
            tileTapNeedsSetup(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                activeChannelEnabled = null,
            ),
        )
    }

    @Test
    fun `a degraded capability is not a required one`() {
        // Location and the calendar are deliberately not inputs: both degrade
        // and report themselves, so routing on them would interrupt a working
        // snooze. This pins the omission — a signature that grew one of them
        // would fail to compile here rather than silently widen the gate.
        assertFalse(
            tileTapNeedsSetup(
                access = PolicyAccess.GRANTED,
                notifications = NotificationPermission.GRANTED,
                activeChannelEnabled = true,
            ),
        )
    }
}
