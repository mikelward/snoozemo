package app.snoozemo

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the two halves of the startup ringer check's switch: tests get the
 * application that leaves it off, and production keeps it on.
 */
@RunWith(RobolectricTestRunner::class)
class TestSnoozemoApplicationTest {

    @Test
    fun `tests run under the application without the startup ringer check`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()

        assertTrue("robolectric.properties is not being read", app is TestSnoozemoApplication)
        assertFalse((app as SnoozemoApplication).reconcilesRingerOnStart)
    }

    @Test
    fun `the shipped application runs the startup ringer check`() {
        assertTrue(SnoozemoApplication().reconcilesRingerOnStart)
    }
}
