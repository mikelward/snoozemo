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
    fun `crash reporting is off until the user turns it on`() {
        // SPEC.md §12, reversed 2026-08-28: reporting leaves the device, so it
        // waits for the user's explicit agreement, and an install that has
        // never been asked has not given it. The manifest already starts
        // Crashlytics with collection off; this is the other half — the stored
        // default the app applies at startup.
        assertFalse(store.isEnabled())
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
