package app.snoozemo.ui

import android.os.Looper
import app.snoozemo.core.PolicyAccess
import app.snoozemo.core.ZenController
import app.snoozemo.core.ZenRuleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the permissions screen is allowed to claim about Snoozemo's mode while a
 * check of it is outstanding, and what it falls back to when that check never
 * answers (Codex, PR #171, across four rounds).
 *
 * `zenRuleState` is the last verified answer and is never thrown away; a
 * refresh that could follow a trip to system Settings marks it as no longer
 * current, and `renderableRuleState` is what the screen actually sees. So a
 * check that ends without an answer needs nothing restored — the last verified
 * one simply becomes current again.
 *
 * The state is set directly rather than driven through a real `ensureRule()`
 * round trip, for the reason `MainActivityFiltersIntentTest` gives: that check
 * runs on a raw background thread with no seam a JVM test can advance
 * deterministically, and this repo's rules rule out a sleep or a poll loop.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityRuleStateInvalidationTest {

    @Test
    fun `a refresh that could follow a Settings trip stops claiming the old answer`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.zenRuleState = ZenRuleState.READY

        activity.refreshAccessForTest()

        assertNull(
            "a re-read must not keep claiming the previous check's answer",
            activity.renderableRuleState,
        )
        assertEquals(
            "but the answer itself is kept, to fall back on",
            ZenRuleState.READY,
            activity.zenRuleState,
        )
    }

    @Test
    fun `a record change keeps claiming it, since it cannot follow a Settings trip`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.zenRuleState = ZenRuleState.READY

        activity.refreshAccessForTest(ruleMayHaveChanged = false)

        assertEquals(
            "blanking the row on every record edit would flicker it for nothing",
            ZenRuleState.READY,
            activity.renderableRuleState,
        )
    }

    @Test
    fun `a record change takes over an outstanding check rather than stranding it`() {
        // It does not start one of its own, but it does supersede the refresh
        // that did — so it has to own the marker, or its own check finishes
        // against a generation the marker does not hold and the row stays
        // hidden for good (Codex, PR #171).
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(2) { settle(activity) }
        activity.zenRuleState = ZenRuleState.READY

        activity.refreshAccessForTest()
        activity.refreshAccessForTest(ruleMayHaveChanged = false)
        settle(activity)

        assertEquals(
            "the later refresh's check must be able to retire the marker",
            ZenRuleState.READY,
            activity.renderableRuleState,
        )
    }

    @Test
    fun `a failed access read leaves the last verified answer showing`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        activity.zenRuleState = ZenRuleState.DISABLED
        activity.zenForTest = object : ZenController by activity.zenForTest {
            override fun policyAccess(): PolicyAccess = throw SecurityException("refused")
        }

        activity.refreshAccessForTest()
        settle(activity)

        assertEquals(
            "a read that answered nothing must not blank what the screen knew",
            ZenRuleState.DISABLED,
            activity.renderableRuleState,
        )
    }

    @Test
    fun `a failed rule check leaves it showing too`() {
        // The second place the chain can end without an answer: access reads
        // fine, and `ensureRule()` is what throws.
        //
        // Started, unlike the tests above: `applyAccess` acts only from
        // STARTED, and it is what reaches the rule check at all. So the
        // startup refreshes it brings with it are settled first, leaving
        // nothing pending to bump the generation past the call under test.
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(2) { settle(activity) }
        activity.zenRuleState = ZenRuleState.DISABLED
        activity.zenForTest = object : ZenController by activity.zenForTest {
            override fun policyAccess(): PolicyAccess = PolicyAccess.GRANTED
            override fun ensureRule(): ZenRuleState = throw SecurityException("refused")
        }

        activity.refreshAccessForTest()
        settle(activity)

        assertEquals(
            "a rule check that answered nothing must not blank the row either",
            ZenRuleState.DISABLED,
            activity.renderableRuleState,
        )
    }

    /**
     * Drains both threads a refresh can start, then the looper each answers on
     * — in that order, since each posts its answer rather than writing it.
     */
    private fun settle(activity: MainActivity) {
        activity.lastAccessRead?.join()
        shadowOf(Looper.getMainLooper()).idle()
        activity.lastRuleCheck?.join()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
