package app.snoozemo.snooze

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one fact this store keeps — the same shape and the same reasons as
 * [NotificationPromptStoreTest], on the permission behind the ongoing
 * notification's third action (SPEC.md §4.3). Robolectric rather than a fake,
 * because what is worth testing is that the write survives the instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarPromptStoreTest {

    private fun newStore() = CalendarPromptStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a fresh install has no denial behind it`() {
        assertFalse(newStore().everDenied())
    }

    @Test
    fun `the first denial is recorded and survives the instance`() {
        newStore().record(granted = false, rationale = true)

        assertTrue(newStore().everDenied())
    }

    @Test
    fun `a reading with nothing new in it leaves the history alone`() {
        // Both ends of the history look like this — never denied, and denied
        // as often as the system allows — so writing `false` here is the bug:
        // it would erase the one thing that tells them apart.
        newStore().record(granted = false, rationale = true)

        newStore().record(granted = false, rationale = false)

        assertTrue(newStore().everDenied())
    }

    @Test
    fun `a grant clears it, because granting resets what the system counts`() {
        // A permission granted and later revoked from Settings starts again
        // with its prompts available; a stale flag would send that user to
        // Settings instead of asking.
        newStore().record(granted = false, rationale = true)

        newStore().record(granted = true, rationale = false)

        assertFalse(newStore().everDenied())
    }
}
