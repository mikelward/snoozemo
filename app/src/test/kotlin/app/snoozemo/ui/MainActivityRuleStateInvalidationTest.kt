package app.snoozemo.ui

import app.snoozemo.core.ZenRuleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A verified rule state belongs to the check that produced it, and a refresh
 * that could follow a trip to system Settings has to stop showing it.
 *
 * Without this, the permissions screen's Do Not Disturb row keeps claiming the
 * capability after the user has just switched Snoozemo's mode off — or keeps
 * reporting it switched off after they repaired it — for as long as the binder
 * check takes to answer (Codex, PR #171). The state is set directly rather than
 * driven through `ensureRule()`, for the reason `MainActivityFiltersIntentTest`
 * gives: that check runs on a raw background thread with no seam a JVM test can
 * advance deterministically.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityRuleStateInvalidationTest {

    @Test
    fun `a refresh that could follow a Settings trip drops the verified rule state`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.zenRuleState = ZenRuleState.READY

        activity.refreshAccessForTest()

        assertNull(
            "a re-read must not keep showing the previous check's answer",
            activity.zenRuleState,
        )
    }

    @Test
    fun `a record change keeps it, since it cannot follow a Settings trip`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.zenRuleState = ZenRuleState.READY

        activity.refreshAccessForTest(ruleMayHaveChanged = false)

        assertEquals(
            "blanking the row on every record edit would flicker it for nothing",
            ZenRuleState.READY,
            activity.zenRuleState,
        )
    }
}
