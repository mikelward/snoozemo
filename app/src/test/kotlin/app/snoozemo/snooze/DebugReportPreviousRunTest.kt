package app.snoozemo.snooze

import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugFileSink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A report reads the earlier runs to fit its own section, keeping the newest.
 * The runs it leaves out are still on disk: sharing deletes nothing.
 */
class DebugReportPreviousRunTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the earlier runs are read to fit the section, newest kept`() {
        val dir = folder.newFolder()
        val older = File(dir, "androidlog-prev-1.log").apply { writeText("the oldest run\n") }
        val newer = File(dir, "androidlog-prev-2.log").apply {
            writeText((0 until 1_200).joinToString("\n") { "line-$it of a talkative run" } + "\n")
        }
        assertTrue(older.setLastModified(1_000L))
        assertTrue(newer.setLastModified(2_000L))
        val sink = DebugFileSink(DebugLog(), dir)
        // The premise: an unbounded read would have carried the older run, so it
        // is the report's own share that leaves it out.
        assertTrue(sink.readPreviousRun()!!.text.contains("the oldest run"))

        val run = readPreviousRunForReport(sink)!!

        assertTrue("${run.text.length}", run.text.length < 25_000)
        assertFalse(run.text, run.text.contains("the oldest run"))
        assertTrue("the newest lines are kept", run.text.endsWith("line-1199 of a talkative run"))
        assertTrue("and nothing was deleted to make it fit", older.exists() && newer.exists())
    }
}
