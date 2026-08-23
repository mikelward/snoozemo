package app.snoozemo.snooze

import app.snoozemo.core.SnoozeDebugLog
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The §4.6 file mechanics: two runs, rotation at start, the crash pin holding
 * the `previous` slot, and off-deletes-everything. Plain JUnit over a temp
 * directory — the sink takes one, so none of this needs Robolectric.
 */
class DebugFileSinkTest {

    private lateinit var dir: File
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("debuglog").toFile()
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.clearSinksForTest()
    }

    @After
    fun tearDown() {
        // start() chains a crash handler onto the process default; put the
        // original back so instances don't stack across tests.
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        SnoozeDebugLog.clearForTest()
        SnoozeDebugLog.clearSinksForTest()
        dir.deleteRecursively()
    }

    private fun sink(): DebugFileSink = DebugFileSink { dir }

    private fun file(name: String) = File(dir, name)

    private fun startAndAwait(enabled: Boolean = true): DebugFileSink =
        sink().apply {
            start(enabled)
            awaitIdleForTest()
        }

    // --- rotation ---

    @Test
    fun `an ordinary run rotates into previous, replacing the old one`() {
        dir.mkdirs()
        file("current.log").writeText("the run that just ended")
        file("previous.log").writeText("the run before that")

        startAndAwait()

        assertEquals("the run that just ended", file("previous.log").readText())
        assertFalse("rotated aside, not copied", file("current.log").exists())
    }

    @Test
    fun `a crashed run is pinned and takes the previous slot with it`() {
        dir.mkdirs()
        file("current.log").writeText("the run that crashed")
        file("current.log.crash").writeText("1")
        file("previous.log").writeText("an ordinary earlier run")

        startAndAwait()

        assertEquals("the run that crashed", file("crash.log").readText())
        assertFalse(
            "the pin holds the previous slot; two runs is the privacy bound",
            file("previous.log").exists(),
        )
        assertFalse("the marker is consumed so it can't mislabel this run", file("current.log.crash").exists())
    }

    @Test
    fun `an ordinary run is discarded while a crash is pinned`() {
        dir.mkdirs()
        file("crash.log").writeText("the unread crash")
        file("current.log").writeText("an uneventful run after it")

        startAndAwait()

        assertEquals("the unread crash", file("crash.log").readText())
        assertFalse(
            "an unread crash explains a failure; the uneventful run after it explains nothing",
            file("previous.log").exists(),
        )
        assertFalse(file("current.log").exists())
    }

    @Test
    fun `a later crash pins again, replacing the old pin`() {
        dir.mkdirs()
        file("crash.log").writeText("the old crash")
        file("current.log").writeText("the new crash")
        file("current.log.crash").writeText("1")

        startAndAwait()

        assertEquals("the new crash", file("crash.log").readText())
    }

    @Test
    fun `a crash pin that cannot move destroys nothing`() {
        dir.mkdirs()
        file("current.log").writeText("the run that crashed")
        file("current.log.crash").writeText("1")
        file("previous.log").writeText("the retained run")
        // Make the pin's rename *and* its copy fallback fail: the target is a
        // non-empty directory, which `delete`, `renameTo`, and an overwriting
        // `copyTo` all refuse. The old code deleted `previous` before finding
        // out (flagged by Codex on PR #62).
        File(file("crash.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        startAndAwait()

        assertEquals(
            "a pin that failed must not have taken the retained run with it",
            "the retained run",
            file("previous.log").readText(),
        )
        assertEquals(
            "the crashed run stays where it was rather than being deleted",
            "the run that crashed",
            file("current.log").readText(),
        )
        assertTrue(
            "the marker is retained so the next start retries the pin",
            file("current.log.crash").exists(),
        )
    }

    @Test
    fun `a stale marker with no run behind it is consumed`() {
        dir.mkdirs()
        file("current.log.crash").writeText("1")

        startAndAwait()

        assertFalse(
            "a marker with nothing to pin would mislabel this run at the next start",
            file("current.log.crash").exists(),
        )
    }

    @Test
    fun `an ordinary rotation that cannot move destroys nothing`() {
        dir.mkdirs()
        file("current.log").writeText("the run that just ended")
        // Rename and copy both refuse when the target is a non-empty
        // directory; the ended run must survive in place rather than being
        // stranded by a half-done rotation (Codex, PR #62).
        File(file("previous.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        startAndAwait()

        assertEquals("the run that just ended", file("current.log").readText())
    }

    @Test
    fun `one undeletable file does not shield the rest from the off switch`() {
        // Started over an empty directory, with the files laid down after, so
        // the off switch — not the startup rotation — is what meets them.
        val sink = startAndAwait()
        dir.mkdirs()
        file("current.log").writeText("kept while on")
        file("previous.log").writeText("also kept")
        // An occupied directory refuses deletion the same way a failing
        // filesystem does: delete() returns false rather than throwing.
        File(file("crash.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        sink.setEnabled(false)
        sink.awaitIdleForTest()

        assertFalse("every deletable file still goes", file("current.log").exists())
        assertFalse(file("previous.log").exists())
        assertTrue("the refusal itself is real in this fixture", file("crash.log").exists())
    }

    @Test
    fun `a leftover file reports the delete as incomplete, not as success`() {
        // Same fixture as the test above, but pinned to the onDisabled
        // callback's own reported outcome rather than the files directly —
        // a caller has no other way to learn a "successful" disable actually
        // left something behind (Codex, PR #89).
        val sink = startAndAwait()
        dir.mkdirs()
        File(file("crash.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        var allDeleted: Boolean? = null
        sink.setEnabled(false) { allDeleted = it }
        sink.awaitIdleForTest()

        assertEquals(false, allDeleted)
    }

    @Test
    fun `a leftover temp file never survives a start`() {
        dir.mkdirs()
        file("current.log.tmp").writeText("uncommitted, possibly partial")

        startAndAwait()

        assertFalse(file("current.log.tmp").exists())
    }

    // --- mirroring ---

    @Test
    fun `recorded entries reach the current run's file`() {
        val sink = startAndAwait()
        SnoozeDebugLog.addSink(sink)

        SnoozeDebugLog.event("the cap alarm was armed")
        sink.awaitIdleForTest()

        assertTrue(file("current.log").readText().contains("the cap alarm was armed"))
    }

    // --- the setting (SPEC.md §4.6: off deletes what was kept) ---

    @Test
    fun `starting disabled deletes whatever an earlier setting left behind`() {
        dir.mkdirs()
        file("current.log").writeText("kept under the old setting")
        file("previous.log").writeText("also kept")
        file("crash.log").writeText("a pinned crash")

        startAndAwait(enabled = false)

        assertFalse(file("current.log").exists())
        assertFalse(file("previous.log").exists())
        assertFalse("an unshared crash is lost at that moment, by design", file("crash.log").exists())
    }

    @Test
    fun `starting under an already-Off setting reports a retried delete's own outcome`() {
        // A process restart under a setting that was already Off is exactly
        // when a leftover from an earlier refused delete gets retried — the
        // durable retry deleteEverything promises — so this must report its
        // own outcome, not just the toggle path's (Codex, PR #89).
        dir.mkdirs()
        File(file("crash.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        var allDeleted: Boolean? = null
        sink().apply {
            start(enabled = false) { allDeleted = it }
            awaitIdleForTest()
        }

        assertEquals(false, allDeleted)
    }

    @Test
    fun `turning the log off deletes everything and stops writes`() {
        val sink = startAndAwait()
        SnoozeDebugLog.addSink(sink)
        SnoozeDebugLog.event("recorded while on")
        sink.awaitIdleForTest()
        assertTrue(file("current.log").exists())

        sink.setEnabled(false)
        sink.awaitIdleForTest()
        assertFalse(file("current.log").exists())

        SnoozeDebugLog.event("recorded while off")
        sink.awaitIdleForTest()
        assertFalse("a disabled sink writes nothing", file("current.log").exists())
    }

    @Test
    fun `turning the log off deletes a pinned crash and reports the delete's completion`() {
        dir.mkdirs()
        file("current.log").writeText("the run that crashed")
        file("current.log.crash").writeText("1")
        val sink = startAndAwait()
        assertTrue(file("crash.log").exists())

        var disabled = 0
        sink.setEnabled(false) { disabled++ }
        sink.awaitIdleForTest()

        assertFalse("the pin is gone along with everything else", file("crash.log").exists())
        assertEquals(1, disabled)
    }

    @Test
    fun `onDisabled fires even when nothing was pinned`() {
        val sink = startAndAwait()

        var disabled = 0
        sink.setEnabled(false) { disabled++ }
        sink.awaitIdleForTest()

        assertEquals(1, disabled)
    }

    @Test
    fun `onDisabled never fires for an enable, which deletes nothing`() {
        val sink = startAndAwait()

        var disabled = 0
        sink.setEnabled(true) { disabled++ }
        sink.awaitIdleForTest()

        assertEquals(0, disabled)
    }

    // --- the crash handler ---

    @Test
    fun `a crash leaves the marker and the snapshot behind`() {
        val sink = startAndAwait()
        SnoozeDebugLog.addSink(sink)
        SnoozeDebugLog.event("the last thing that happened")

        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        sink.awaitIdleForTest()

        assertTrue("the marker is what pins the run at the next start", file("current.log.crash").exists())
        val snapshot = file("current.log").readText()
        assertTrue(snapshot.contains("the last thing that happened"))
        assertTrue("the crash itself is the final entry", snapshot.contains("uncaught exception"))
        // And the whole cycle: the next start pins this run.
        startAndAwait()
        assertTrue(file("crash.log").readText().contains("uncaught exception"))
    }

    // --- reading and consuming the pin (TODO.md Phase 5, docs/DEBUG.md) ---

    @Test
    fun `hasPinnedCrash is false with nothing pinned`() {
        val sink = startAndAwait()

        var pinned: Boolean? = null
        sink.hasPinnedCrash { pinned = it }
        sink.awaitIdleForTest()

        assertEquals(false, pinned)
    }

    @Test
    fun `hasPinnedCrash is true once a crash is pinned`() {
        dir.mkdirs()
        file("current.log").writeText("the run that crashed")
        file("current.log.crash").writeText("1")
        val sink = startAndAwait()

        var pinned: Boolean? = null
        sink.hasPinnedCrash { pinned = it }
        sink.awaitIdleForTest()

        assertEquals(true, pinned)
    }

    @Test
    fun `readPreviousOrCrash reads the pin over an ordinary previous run`() {
        // Per SPEC.md §4.6 the pin holds the slot, so in practice the two
        // never coexist — this fixture pins deliberately to prove the read
        // still prefers the pin if it somehow found both. No start() here:
        // reading does not need rotation to have run first.
        dir.mkdirs()
        file("crash.log").writeText("the crashed run")
        file("previous.log").writeText("should not be read")
        val sink = sink()

        var text: String? = null
        var wasCrash: Boolean? = null
        var readSucceeded: Boolean? = null
        sink.readPreviousOrCrash { t, c, s -> text = t; wasCrash = c; readSucceeded = s }
        sink.awaitIdleForTest()

        assertEquals("the crashed run", text)
        assertEquals(true, wasCrash)
        assertEquals(true, readSucceeded)
    }

    @Test
    fun `readPreviousOrCrash reads the ordinary previous run when nothing is pinned`() {
        dir.mkdirs()
        file("previous.log").writeText("an ordinary earlier run")
        val sink = startAndAwait()

        var text: String? = null
        var wasCrash: Boolean? = null
        sink.readPreviousOrCrash { t, c, _ -> text = t; wasCrash = c }
        sink.awaitIdleForTest()

        assertEquals("an ordinary earlier run", text)
        assertEquals(false, wasCrash)
    }

    @Test
    fun `readPreviousOrCrash reads the pin, and says it was a crash`() {
        dir.mkdirs()
        file("current.log").writeText("the run that crashed")
        file("current.log.crash").writeText("1")
        val sink = startAndAwait()

        var text: String? = null
        var wasCrash: Boolean? = null
        sink.readPreviousOrCrash { t, c, _ -> text = t; wasCrash = c }
        sink.awaitIdleForTest()

        assertEquals("the run that crashed", text)
        assertEquals(true, wasCrash)
    }

    @Test
    fun `readPreviousOrCrash reads null when there is nothing to read`() {
        val sink = startAndAwait()

        var text: String? = "not yet read"
        var readSucceeded: Boolean? = null
        sink.readPreviousOrCrash { t, _, s -> text = t; readSucceeded = s }
        sink.awaitIdleForTest()

        assertEquals(null, text)
        // Nothing to read is a successful empty read, not a failure — there
        // was no pinned crash to lose (Codex, PR #89).
        assertEquals(true, readSucceeded)
    }

    @Test
    fun `readPreviousOrCrash reports a failed read of a real pinned crash as unsuccessful`() {
        // A directory where a file is expected makes readText() throw
        // without the file having to actually vanish mid-read.
        dir.mkdirs()
        file("crash.log").mkdirs()
        val sink = sink()

        var readSucceeded: Boolean? = null
        var wasCrash: Boolean? = null
        sink.readPreviousOrCrash { _, c, s -> wasCrash = c; readSucceeded = s }
        sink.awaitIdleForTest()

        assertEquals(true, wasCrash)
        // The pin genuinely exists but couldn't be read — this must not
        // read the same as an empty file, or a caller would think it's
        // safe to consume a pin whose crash was never actually included
        // anywhere (Codex, PR #89).
        assertEquals(false, readSucceeded)
    }

    @Test
    fun `consumeCrashPin turns the pin into an ordinary previous run`() {
        dir.mkdirs()
        file("current.log").writeText("the run that crashed")
        file("current.log.crash").writeText("1")
        val sink = startAndAwait()
        assertTrue("precondition: the pin took", file("crash.log").exists())

        var consumed: Boolean? = null
        sink.consumeCrashPin { consumed = it }
        sink.awaitIdleForTest()

        assertEquals(true, consumed)
        assertFalse("the pin is gone", file("crash.log").exists())
        assertEquals(
            "and the run reads back as an ordinary previous run",
            "the run that crashed",
            file("previous.log").readText(),
        )
    }

    @Test
    fun `consumeCrashPin is a no-op success when nothing is pinned`() {
        val sink = startAndAwait()

        var consumed: Boolean? = null
        sink.consumeCrashPin { consumed = it }
        sink.awaitIdleForTest()

        assertEquals("nothing to consume is not a failure", true, consumed)
    }

    @Test
    fun `a crash pin that cannot move stays pinned for a retry`() {
        dir.mkdirs()
        file("current.log").writeText("the run that crashed")
        file("current.log.crash").writeText("1")
        val sink = startAndAwait()
        assertTrue(file("crash.log").exists())
        // Make both the rename and the copy fallback refuse, the same fixture
        // shape the rotation tests above use.
        File(file("previous.log"), "occupied").apply { parentFile!!.mkdirs() }.writeText("x")

        var consumed: Boolean? = null
        sink.consumeCrashPin { consumed = it }
        sink.awaitIdleForTest()

        assertEquals(false, consumed)
        assertTrue("the crash log survives a failed consume", file("crash.log").exists())
    }
}
