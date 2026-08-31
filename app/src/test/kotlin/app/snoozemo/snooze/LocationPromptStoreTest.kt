package app.snoozemo.snooze

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Same shape as [NotificationPromptStoreTest], doubled: foreground and
 * background each keep their own denial history, and the point worth a test
 * of its own is that the two never leak into each other — a background
 * denial must not blind the foreground reading, or the row would send a user
 * to Settings over a permission the system would still prompt for in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationPromptStoreTest {

    private fun newStore() = LocationPromptStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `the background banner starts undismissed and stays dismissed`() {
        assertFalse(newStore().isBackgroundBannerDismissed())
        newStore().dismissBackgroundBanner()
        assertTrue("the dismissal outlives the instance", newStore().isBackgroundBannerDismissed())
    }

    @Test
    fun `a background grant does not undo the banner dismissal`() {
        // The asymmetry against the denial history below, and the reason
        // this flag lives beside it rather than being derived from it: a
        // grant resets what the *platform* will prompt for, and says nothing
        // about a user who asked not to be shown the banner again. A grant
        // hides the banner on its own anyway, so clearing this would only
        // bring it back after a later revocation — the one case already
        // answered.
        val store = newStore()
        store.dismissBackgroundBanner()
        store.recordBackground(granted = true, rationale = false)

        assertTrue(store.isBackgroundBannerDismissed())
    }

    @Test
    fun `dismissing the banner leaves both denial histories alone`() {
        val store = newStore()
        store.recordForeground(granted = false, rationale = true)
        store.dismissBackgroundBanner()

        assertTrue(store.foregroundEverDenied())
        assertFalse(store.backgroundEverDenied())
    }

    @Test
    fun `a fresh install has no denial behind it, either kind`() {
        val store = newStore()
        assertFalse(store.foregroundEverDenied())
        assertFalse(store.backgroundEverDenied())
    }

    @Test
    fun `a foreground denial survives the instance and leaves background alone`() {
        newStore().recordForeground(granted = false, rationale = true)

        val store = newStore()
        assertTrue(store.foregroundEverDenied())
        assertFalse(store.backgroundEverDenied())
    }

    @Test
    fun `a background denial survives the instance and leaves foreground alone`() {
        newStore().recordBackground(granted = false, rationale = true)

        val store = newStore()
        assertTrue(store.backgroundEverDenied())
        assertFalse(store.foregroundEverDenied())
    }

    @Test
    fun `a reading with nothing new in it leaves either flag alone`() {
        val store = newStore()
        store.recordForeground(granted = false, rationale = true)
        store.recordBackground(granted = false, rationale = true)

        store.recordForeground(granted = false, rationale = false)
        store.recordBackground(granted = false, rationale = false)

        val reread = newStore()
        assertTrue(reread.foregroundEverDenied())
        assertTrue(reread.backgroundEverDenied())
    }

    @Test
    fun `a foreground grant clears only the foreground history`() {
        val store = newStore()
        store.recordForeground(granted = false, rationale = true)
        store.recordBackground(granted = false, rationale = true)

        store.recordForeground(granted = true, rationale = false)

        val reread = newStore()
        assertFalse(reread.foregroundEverDenied())
        assertTrue(reread.backgroundEverDenied())
    }
}
