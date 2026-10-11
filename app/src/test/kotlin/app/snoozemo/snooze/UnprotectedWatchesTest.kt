package app.snoozemo.snooze

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** The protection warning's publication, on `GraceDeadlines`' contract. */
class UnprotectedWatchesTest {

    @Before
    @After
    fun reset() = UnprotectedWatches.clear()

    @Test
    fun `a watcher hears the current value on registration and every change after`() {
        UnprotectedWatches.publish(true)
        val heard = mutableListOf<Boolean>()

        UnprotectedWatches.watch { heard += UnprotectedWatches.latest() }.use {
            UnprotectedWatches.clear()
            UnprotectedWatches.publish(true)
        }

        assertEquals(listOf(true, false, true), heard)
    }

    @Test
    fun `a restated value is not news`() {
        var calls = 0
        UnprotectedWatches.watch { calls++ }.use {
            UnprotectedWatches.publish(true)
            UnprotectedWatches.publish(true)
        }

        assertEquals("registration plus one change", 2, calls)
    }

    @Test
    fun `an earlier handle closing does not unregister a later watcher`() {
        val first = UnprotectedWatches.watch { }
        var heard = false
        UnprotectedWatches.watch { heard = UnprotectedWatches.latest() }.use {
            first.close()
            UnprotectedWatches.publish(true)
        }

        assertEquals(true, heard)
        UnprotectedWatches.clear()
        assertFalse(UnprotectedWatches.latest())
    }
}
