package app.snoozemo.snooze

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.dnd.PrefsZenRuleIdStore
import app.snoozemo.tile.SnoozeTileService
import app.snoozemo.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The tile's rule warm-up, deferred from Codex's PR #8 review: the id alone
 * can be stale — the rule deleted in Settings — and a tap then pays the slow
 * path between itself and `STATE_TRUE`.
 */
@RunWith(RobolectricTestRunner::class)
class TileRuleWarmTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the deep-link class the modules agree on by string is the real one`() {
        // `:dnd` names the configuration activity by string because it cannot
        // reference `:app`; this pin is what makes that coupling safe — a
        // rename of the activity fails here instead of silently breaking the
        // rule's deep link in Settings and the Modes UI.
        assertEquals(
            MainActivity::class.java.name,
            AndroidZenController.CONFIGURATION_ACTIVITY_CLASS,
        )
    }

    @Test
    fun `warming the tile prepares the rule ahead of the tap`() {
        // The shade opening is where the preparation budget lives (SPEC.md
        // §4.1): after the warm, the rule exists and its id is persisted, so
        // the tap's `setSnoozed` takes the fast path.
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(true)

        SnoozeTileService.warmZenRuleNow(context)

        assertNotNull(
            "the warm must leave a created rule's id behind for the tap to use",
            PrefsZenRuleIdStore(context).ruleId(),
        )
    }
}
