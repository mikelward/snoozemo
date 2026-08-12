package app.snoozemo.snooze

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one fact this store keeps, and the three ways it can be got wrong.
 *
 * Everything above it is already covered on the JVM — `NotificationPermission`
 * takes `everDenied` as an argument, and the screen takes the resulting enum —
 * so a regression *here* would route users to the wrong repair action with no
 * test going red (flagged by Codex on PR #18). Robolectric rather than a fake,
 * because the thing worth testing is that it survives: an in-memory double
 * would pass while the write never reached disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPromptStoreTest {

    private fun newStore() = NotificationPromptStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a fresh install has no denial behind it`() {
        assertFalse(newStore().everDenied())
    }

    @Test
    fun `the first denial is recorded and survives the instance`() {
        newStore().record(granted = false, rationale = true)

        // A new instance, because the point is the disk and not the object:
        // the screen and the tile each build their own.
        assertTrue(newStore().everDenied())
    }

    @Test
    fun `a reading with nothing new in it leaves the flag alone`() {
        val store = newStore()
        store.record(granted = false, rationale = true)

        // No rationale and not granted is what a never-denied permission and a
        // permanently denied one both look like, so it says nothing either way.
        // Writing `false` here is the bug this branch exists to avoid: it would
        // erase the one fact that tells those two apart, and the row would go
        // back to offering a prompt the system has stopped showing.
        store.record(granted = false, rationale = false)

        assertTrue(newStore().everDenied())
    }

    @Test
    fun `that same reading does not invent a denial either`() {
        newStore().record(granted = false, rationale = false)

        assertFalse(newStore().everDenied())
    }

    @Test
    fun `a grant clears the history`() {
        val store = newStore()
        store.record(granted = false, rationale = true)

        // Granting resets the denial count the system itself keeps, so a
        // permission later revoked from Settings starts again with its prompts
        // available. A flag left set would send that user to Settings for a
        // dialog the app could have shown in place.
        store.record(granted = true, rationale = false)

        assertFalse(newStore().everDenied())
    }
}
