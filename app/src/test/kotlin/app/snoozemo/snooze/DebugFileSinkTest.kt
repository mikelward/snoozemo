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
}
