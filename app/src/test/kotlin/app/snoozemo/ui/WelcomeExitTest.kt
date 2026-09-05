package app.snoozemo.ui

import app.snoozemo.core.CalendarPermission
import app.snoozemo.core.LocationPermission
import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where leaving the welcome flow lands (`SPEC.md` §4.2).
 *
 * The recap after the cards is the one screen that says what each `Skip` cost,
 * so getting this wrong in either direction is a real failure: too narrow and a
 * user walks past it with capabilities missing, too broad and a user who
 * allowed everything is shown a recap with nothing on it.
 */
class WelcomeExitTest {

    @Test
    fun `everything granted goes straight to the main screen`() {
        assertFalse(needsRecap())
    }

    @Test
    fun `missing Do Not Disturb access needs the recap`() {
        assertTrue(needsRecap(access = PolicyAccess.DENIED))
    }

    @Test
    fun `a permission other than access needs the recap too`() {
        // The bug this covers: the exit tested access alone, so a user who
        // allowed it on card 4 and skipped the rest reached the main screen
        // able to arm with no notification to show status on (Codex, PR #204).
        assertTrue(needsRecap(notifications = NotificationPermission.ASKABLE))
        assertTrue(needsRecap(location = LocationPermission.ASKABLE))
        assertTrue(needsRecap(calendar = CalendarPermission.ASKABLE))
    }

    @Test
    fun `notifications held but not reaching the user still needs the recap`() {
        // Granted is necessary and not sufficient: the permission can be held
        // while the app or a channel is switched off, and the system then drops
        // every post — which is exactly what the row says and why it keeps its
        // button in that state.
        assertTrue(needsRecap(notificationsReachTheUser = false))
    }

    @Test
    fun `an unread capability is not a missing one`() {
        // The readings land after the first frame. Routing on an unread one
        // would send the user to a recap of things nothing has checked yet —
        // the same "briefly absent rather than briefly wrong" discipline the
        // rows themselves follow.
        assertFalse(needsRecap(access = null))
        assertFalse(needsRecap(notifications = null))
        assertFalse(needsRecap(location = null))
        assertFalse(needsRecap(calendar = null))
    }

    @Test
    fun `location counts for nothing on a build that cannot track departure`() {
        // That flavor's permissions screen offers no action on the row, so
        // routing to a recap over it would send the user to a screen with
        // nothing they can do — and invite a grant that buys them nothing.
        assertFalse(
            needsRecap(location = LocationPermission.ASKABLE, tracksDeparture = false),
        )
        assertTrue(
            needsRecap(location = LocationPermission.ASKABLE, tracksDeparture = true),
        )
    }

    @Test
    fun `a fresh install that has not seen the flow opens it`() {
        assertTrue(shouldOpenWelcome(seen = false, freshInstall = { true }))
    }

    @Test
    fun `an install that predates the flow is left alone`() {
        // The flag is absent on an upgraded install exactly as it is on a new
        // one, so reading it alone would march every existing user through
        // onboarding on the update that shipped this (Codex, PR #204).
        assertFalse(shouldOpenWelcome(seen = false, freshInstall = { false }))
    }

    @Test
    fun `a seen install never asks the platform`() {
        // The lookup is a `PackageManager` binder call in front of the first
        // frame, and every launch after the first is a seen one — so passing
        // the answer by value made the common case pay for the rare one
        // (Codex, PR #204).
        var asked = 0

        shouldOpenWelcome(
            seen = true,
            freshInstall = {
                asked++
                true
            },
        )

        assertEquals(0, asked)
    }

    @Test
    fun `having seen it wins either way`() {
        assertFalse(shouldOpenWelcome(seen = true, freshInstall = { true }))
        assertFalse(shouldOpenWelcome(seen = true, freshInstall = { false }))
    }

    /** Everything granted and read, so each test names only what it changes. */
    private fun needsRecap(
        access: PolicyAccess? = PolicyAccess.GRANTED,
        notifications: NotificationPermission? = NotificationPermission.GRANTED,
        notificationsReachTheUser: Boolean = true,
        location: LocationPermission? = LocationPermission.GRANTED,
        calendar: CalendarPermission? = CalendarPermission.GRANTED,
        tracksDeparture: Boolean = true,
    ) = welcomeExitNeedsRecap(
        access = access,
        notifications = notifications,
        notificationsReachTheUser = notificationsReachTheUser,
        location = location,
        calendar = calendar,
        tracksDeparture = tracksDeparture,
    )
}
