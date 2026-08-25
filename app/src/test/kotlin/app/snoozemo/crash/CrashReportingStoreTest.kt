package app.snoozemo.crash

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashReportingStoreTest {

    private val store get() = CrashReportingStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `crash reporting is on by default`() {
        // SPEC.md §12's decision: a reporter that starts off guarantees the
        // first crash — the one nobody saw coming — is the one nobody
        // captured. The opt-out in Settings is what buys that default
        // honestly.
        assertTrue(store.isEnabled())
    }

    @Test
    fun `the choice persists`() {
        assertTrue(store.setEnabled(false))
        assertFalse(store.isEnabled())
        assertTrue(store.setEnabled(true))
        assertTrue(store.isEnabled())
    }

    @Test
    fun `warming leaves the stored choice alone`() {
        assertTrue(store.setEnabled(false))
        store.warm()
        assertFalse(store.isEnabled())
    }
}
