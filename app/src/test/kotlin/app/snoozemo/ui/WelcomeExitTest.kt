package app.snoozemo.ui

import app.snoozemo.core.NotificationPermission
import app.snoozemo.core.PolicyAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // allowed it and skipped the rest reached the main screen able to arm
        // with no notification to show status on (Codex, PR #204).
        assertTrue(needsRecap(notifications = NotificationPermission.ASKABLE))
    }

    @Test
    fun `only access and notifications route the exit now`() {
        // Location and calendar left the tutorial (maintainer, 2026-09-14): the
        // cards no longer ask for them, so there is no card "no" to catch, and
        // `welcomeExitNeedsRecap` no longer takes them — both stay on the
        // standalone permissions screen. With access and notifications
        // satisfied, the exit is clear; each of the two that remain still
        // routes it on its own (asserted above).
        assertFalse(needsRecap())
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
        // Unless a run of the flow is still open (Codex, PR #220): a replay is
        // seen and not fresh, and without this a blocked tile tap during one
        // landed on the recap instead of the card the user was on.
        assertTrue(shouldOpenWelcome(seen = true, freshInstall = { false }, inProgress = true))
    }

    @Test
    fun `a remembered card resolves only against the cards this build shows`() {
        val withTelemetry = welcomeCards(collectsTelemetry = true)
        val without = welcomeCards(collectsTelemetry = false)

        assertEquals(
            WelcomeCard.TILE,
            rememberedWelcomeCard(WelcomeCard.TILE.name, without),
        )
        // Codex, PR #220: a build with no crash reporter configured drops the
        // telemetry card — a breadcrumb naming it used to come back as a card
        // the flow does not contain, where `Next` did nothing and the dots read
        // card 1 while a fifth card was on screen.
        assertEquals(
            WelcomeCard.TELEMETRY,
            rememberedWelcomeCard(WelcomeCard.TELEMETRY.name, withTelemetry),
        )
        assertNull(rememberedWelcomeCard(WelcomeCard.TELEMETRY.name, without))
        // The same answer for a card a later build removed, and for no memory
        // at all — which is the point of one function for both.
        assertNull(rememberedWelcomeCard("A_CARD_THAT_WAS_REMOVED", without))
        assertNull(rememberedWelcomeCard(null, without))
    }

    @Test
    fun `a current-order breadcrumb resumes in place`() {
        // A name under the current key is by definition in the current order,
        // so it is never rewound — even one that sits after the rule card.
        assertEquals(
            WelcomeCard.ENDS.name,
            WelcomeCardMemory.resolve(current = WelcomeCard.ENDS.name, legacy = null),
        )
    }

    @Test
    fun `a pre-reorder breadcrumb never resumes past the rule card`() {
        // The invariant every reorder protects: a legacy name for any card
        // after the rule rewinds to the rule, so a mid-flow app update cannot
        // walk the user past the one grant without which nothing snoozes
        // (maintainer, 2026-09-14; earlier the tile, 2026-09-08).
        for (after in listOf(WelcomeCard.ENDS, WelcomeCard.TILE, WelcomeCard.TELEMETRY)) {
            assertEquals(
                WelcomeCard.RULE.name,
                WelcomeCardMemory.resolve(current = null, legacy = after.name),
            )
        }
        // The rule itself, and the card before it, resume in place.
        assertEquals(
            WelcomeCard.RULE.name,
            WelcomeCardMemory.resolve(current = null, legacy = WelcomeCard.RULE.name),
        )
        assertEquals(
            WelcomeCard.WHAT.name,
            WelcomeCardMemory.resolve(current = null, legacy = WelcomeCard.WHAT.name),
        )
    }

    @Test
    fun `no breadcrumb resolves to nothing`() {
        assertNull(WelcomeCardMemory.resolve(current = null, legacy = null))
    }

    /** Everything granted and read, so each test names only what it changes. */
    private fun needsRecap(
        access: PolicyAccess? = PolicyAccess.GRANTED,
        notifications: NotificationPermission? = NotificationPermission.GRANTED,
        notificationsReachTheUser: Boolean = true,
    ) = welcomeExitNeedsRecap(
        access = access,
        notifications = notifications,
        notificationsReachTheUser = notificationsReachTheUser,
    )
}
