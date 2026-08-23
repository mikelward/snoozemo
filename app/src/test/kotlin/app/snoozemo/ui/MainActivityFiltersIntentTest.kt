package app.snoozemo.ui

import android.provider.Settings
import app.snoozemo.core.PolicyAccess
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * `openFilters`'s actual intent (Codex, PR #88): the screenshot test only
 * proves the row's `onFiltersRow` callback fires, which would pass just as
 * well with the wrong action or a missing extra — exactly the mistake the
 * TODO entry this landed from had made, with the wrong constant names on
 * both. This pins the real values Settings needs to land on Snoozemo's rule
 * — `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` /
 * `Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID`, confirmed by reading AOSP
 * Settings' own manifest and `ZenModeFragmentBase` source, after this test's
 * own first version asserted `NotificationManager`'s near-identically-named
 * but unrelated constants and would have passed just as green against a
 * button that opened nothing (Codex, PR #88 — a second round).
 *
 * `access` and `zenRuleId` are set directly rather than driven through the
 * real `ensureRule()` round trip: that runs on a raw background `Thread`
 * `ensureRuleInBackground` starts, which has no seam a JVM test can advance
 * deterministically — this repo's testing rules rule out papering over that
 * with a sleep or a poll loop.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityFiltersIntentTest {

    @Test
    fun `Filters opens the system's rule screen with the real action and rule id`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.access = PolicyAccess.GRANTED
        activity.zenRuleId = "example-rule-id"

        activity.openFilters()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS, started.action)
        assertEquals(
            "example-rule-id",
            started.getStringExtra(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID),
        )
    }

    @Test
    fun `Filters does nothing when there is no rule to edit`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.access = PolicyAccess.DENIED
        activity.zenRuleId = "example-rule-id"

        activity.openFilters()

        // `filtersRuleId` hides the row on `access` alone, so a call reaching
        // here with access denied — which the row itself should never allow —
        // must still refuse rather than launch a broken deep link.
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }
}
