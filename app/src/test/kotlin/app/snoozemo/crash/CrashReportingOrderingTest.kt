package app.snoozemo.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two durable writes `setEnabled` makes — this app's preference and
 * Crashlytics' own collection override — persist independently, so a process
 * death between them leaves them disagreeing. Which disagreement is survivable
 * is not symmetric: Crashlytics' override outranks the manifest's `false` on
 * the next launch, so an opt-out that reached only this app's store would meet
 * an SDK still holding `true` and could upload a queued report before
 * `install` reapplied the choice (Codex, PR #113).
 *
 * So the ordering is the correctness argument, and these pin it: **off reaches
 * the SDK first, on reaches the store first**, leaving a torn write pointing at
 * "not collecting" either way.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReportingOrderingTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** What the SDK was told, and what the store still said at that moment. */
    private val applications = mutableListOf<Pair<Boolean, Boolean>>()
    private lateinit var realApply: (Context, Boolean) -> ReporterOutcome

    /** Recorded in the same list, so a discard's position among the applies is visible. */
    private val steps = mutableListOf<String>()
    private lateinit var realDiscard: (Context) -> Unit

    @Before
    fun recordApplications() {
        realApply = CrashReporting.applyToReporter
        realDiscard = CrashReporting.discardPendingReports
        applications.clear()
        steps.clear()
        CrashReporting.discardPendingReports = { steps += "discard" }
        CrashReporting.applyToReporter = { ctx, enabled ->
            steps += "apply=$enabled"
            // The store is read *inside* the hook, so the recording captures
            // the interleaving rather than the end state — which is the whole
            // question here.
            applications += enabled to CrashReportingStore(ctx).isEnabled()
            ReporterOutcome.APPLIED
        }
    }

    @After
    fun restore() {
        CrashReporting.applyToReporter = realApply
        CrashReporting.discardPendingReports = realDiscard
        CrashReportingStore(context).setEnabled(true)
    }

    @Test
    fun `turning it on discards what was captured while it was off, before enabling`() {
        // Collection-off stops the reporter sending, not capturing: the
        // uncaught-exception handler is installed either way, so a crash while
        // reporting was off sits on disk unsent. Enabling without discarding
        // first releases it — a report from a period the user had not agreed
        // to (Codex, ClothesCast PR #1161, against the same design).
        //
        // The discard is asserted *before* the enable for the same reason the
        // off path applies before it stores: a process death between the two
        // must leave the reports gone rather than sent.
        CrashReportingStore(context).setEnabled(false)
        steps.clear()

        setEnabledAndWait(true)

        assertEquals(listOf("discard", "apply=true"), steps)
    }

    @Test
    fun `re-applying an on that was already on discards nothing`() {
        // Otherwise this would delete the reports the feature exists to
        // collect, every time the switch was touched.
        CrashReportingStore(context).setEnabled(true)
        steps.clear()

        setEnabledAndWait(true)

        assertEquals(listOf("apply=true"), steps)
    }

    @Test
    fun `turning it off discards through the disable, not separately`() {
        // The off path's delete already lives inside CrashReporter.apply, and
        // has to: it is sequenced with the durable flush of the collection
        // override. A second discard here would be redundant, and hanging it
        // ahead of the disable would undo that ordering.
        CrashReportingStore(context).setEnabled(true)
        steps.clear()

        setEnabledAndWait(false)

        assertEquals(listOf("apply=false"), steps)
    }

    private fun setEnabledAndWait(enabled: Boolean) {
        val done = CountDownLatch(1)
        CrashReporting.setEnabled(context, enabled) { done.countDown() }
        assertTrue("setEnabled never completed", done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `turning reporting off reaches the SDK before the store`() {
        CrashReportingStore(context).setEnabled(true)

        setEnabledAndWait(false)

        // The first thing that happens is the SDK being told to stop, while
        // this app's preference still reads on. A process death from here on
        // leaves collection off, which is the survivable direction.
        assertEquals(false to true, applications.first())
        // And it is told exactly once — the successful opt-out needs no
        // second application, since the pre-apply already matched.
        assertEquals(listOf(false to true), applications)
    }

    @Test
    fun `an opt-out the SDK cannot make durable leaves the preference alone`() {
        // The split state this whole ordering exists to rule out: our
        // preference durably off while the SDK's own override may still read
        // on. Recording the opt-out anyway would build it, so the tap is
        // refused instead and the switch shows its existing failure line.
        CrashReportingStore(context).setEnabled(true)
        CrashReporting.applyToReporter = { _, _ -> ReporterOutcome.NOT_DURABLE }

        var reportedPersisted: Boolean? = null
        val done = CountDownLatch(1)
        CrashReporting.setEnabled(context, enabled = false) { persisted ->
            reportedPersisted = persisted
            done.countDown()
        }
        assertTrue("setEnabled never completed", done.await(5, TimeUnit.SECONDS))

        assertEquals(false, reportedPersisted)
        assertTrue("the refusal must be visible to the screen", CrashReporting.lastSaveRefused)
        assertTrue("the stored choice must be left as it was", CrashReportingStore(context).isEnabled())
    }

    @Test
    fun `turning reporting on reaches the store before the SDK`() {
        CrashReportingStore(context).setEnabled(false)

        setEnabledAndWait(true)

        // The mirror image: the preference is already on when the SDK is told
        // to start, so a death in between leaves the SDK still off — a missed
        // report rather than an unwanted upload.
        assertEquals(listOf(true to true), applications)
    }
}
