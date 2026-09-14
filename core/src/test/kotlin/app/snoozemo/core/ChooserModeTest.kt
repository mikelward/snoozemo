package app.snoozemo.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shared chooser-mode cache both `:app` (the trampoline) and `:tile` read,
 * with the write-generation guard that keeps a slow warm-up read from clobbering
 * a newer write (Codex, PR #284).
 */
class ChooserModeTest {

    @Before fun setUp() = ChooserMode.resetForTest()

    @After fun tearDown() = ChooserMode.resetForTest()

    @Test
    fun `defaults to off until the warm-up lands`() {
        assertFalse(ChooserMode.isOn())
    }

    @Test
    fun `reflects the last published value`() {
        ChooserMode.publish(true)
        assertTrue(ChooserMode.isOn())
        ChooserMode.publish(false)
        assertFalse(ChooserMode.isOn())
    }

    @Test
    fun `a stale read does not clobber a newer write`() {
        // A read captures the generation, then a write lands before the read
        // publishes: the read must decline, or the next tap takes the opposite
        // route to the toggle the user just made.
        val seen = ChooserMode.beginRead()
        ChooserMode.publish(true) // the newer write
        ChooserMode.publishIfUnchanged(false, seen) // the stale read tries to publish

        assertTrue("the newer write wins over the stale read", ChooserMode.isOn())
    }

    @Test
    fun `an uncontended read publishes its value`() {
        val seen = ChooserMode.beginRead()
        ChooserMode.publishIfUnchanged(true, seen)

        assertTrue(ChooserMode.isOn())
    }
}
