package app.snoozemo.snooze

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The grace deadline's publication, on `DepartureObservations`' contract. */
class GraceDeadlinesTest {

    @Before
    @After
    fun reset() = GraceDeadlines.clear()

    @Test
    fun `a watcher hears the current value on registration and every move after`() {
        GraceDeadlines.publish(100L)
        val heard = mutableListOf<Long?>()

        GraceDeadlines.watch { heard += GraceDeadlines.latest() }.use {
            GraceDeadlines.publish(200L)
            GraceDeadlines.clear()
        }

        assertEquals(listOf(100L, 200L, null), heard)
    }

    @Test
    fun `a restated value is not news`() {
        var calls = 0
        GraceDeadlines.watch { calls++ }.use {
            GraceDeadlines.publish(100L)
            GraceDeadlines.publish(100L)
        }

        assertEquals("registration plus one move", 2, calls)
    }

    @Test
    fun `an earlier handle closing does not unregister a later watcher`() {
        val first = GraceDeadlines.watch { }
        var heard: Long? = null
        GraceDeadlines.watch { heard = GraceDeadlines.latest() }.use {
            first.close()
            GraceDeadlines.publish(300L)
        }

        assertEquals(300L, heard)
        GraceDeadlines.clear()
        assertNull(GraceDeadlines.latest())
    }
}
