package app.snoozemo.snooze

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugLogStoreTest {

    private val store get() = DebugLogStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `the log is on by default`() {
        // The maintainer decision behind SPEC.md §4.6: off-by-default
        // guarantees the first occurrence of every unrepeatable failure is
        // the one nobody captured.
        assertTrue(store.isEnabled())
    }

    @Test
    fun `the choice persists`() {
        assertTrue(store.setEnabled(false))
        assertFalse(store.isEnabled())
        assertTrue(store.setEnabled(true))
        assertTrue(store.isEnabled())
    }
}
