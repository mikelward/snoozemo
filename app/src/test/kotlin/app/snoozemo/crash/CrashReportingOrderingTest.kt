package app.snoozemo.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `re-applying an on that was already permitted discards nothing`() {
        // Otherwise this would delete the reports the feature exists to
        // collect, every time the switch was touched. Note the store is seeded
        // *answered* as well as enabled: that is what "already on" means now,
        // and seeding only the preference would be the torn-write state the
        // next test is about.
        CrashReportingStore(context).setEnabled(true)
        CrashReportingStore(context).setAnswered()
        steps.clear()

        setEnabledAndWait(true)

        assertEquals(listOf("apply=true"), steps)
    }

    @Test
    fun `an opt-in interrupted between its two writes still discards`() {
        // Codex, PR #166. `enabled` and `answered` commit separately, so a
        // process death between them leaves `enabled = true` with no answer —
        // a state in which nothing was ever permitted to collect, but the raw
        // preference reads on. Keyed on `isEnabled()` the retry skipped the
        // discard and released reports captured while consent was absent;
        // keyed on `collectionPermitted()` it discards, because unanswered is
        // not permitted whatever the preference says.
        CrashReportingStore(context).setEnabled(true)
        steps.clear()

        setEnabledAndWait(true)

        assertEquals(listOf("discard", "apply=true"), steps)
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
    fun `a refused answer write is reported as a refused save`() {
        // Codex, PR #166. The answer write used to be fire-and-forget, so a
        // yes whose answer never reached disk reported success: the card went
        // away, this session collected, and the next launch quietly stopped
        // and asked again over a Settings switch still reading on. The store
        // now rolls the map back, which leaves the card up — so the tap has to
        // be reported as refused, or the user is looking at a question they
        // just answered with nothing to say why.
        var reportedPersisted: Boolean? = null
        val done = CountDownLatch(1)
        CrashReporting.setEnabled(RefusingAnswerContext(context), enabled = true) { persisted ->
            reportedPersisted = persisted
            done.countDown()
        }
        assertTrue("setEnabled never completed", done.await(5, TimeUnit.SECONDS))

        assertEquals(false, reportedPersisted)
        assertTrue("the refusal must be visible to the screen", CrashReporting.lastSaveRefused)
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
    fun `a refused decline on an upgrade does not become consent`() {
        // Codex, PR #166's last P1, and the worst outcome this PR could have
        // shipped: an explicit **No thanks** turning into collection.
        //
        // The install is the one the answer flag exists for — upgraded from
        // the switch-only build, so `enabled = true` with nothing answered. A
        // refused decline rolls the preference back to **on**, and the old
        // condition recorded the answer anyway because the *requested* value
        // was false. `collectionPermitted()` then read on AND answered, so both
        // SDKs started and the card went away, over a storage failure.
        // Only the `enabled` write is refused. A fixture refusing every write
        // makes this test vacuous — the answer write fails too, so
        // `hasAnswered()` reads false whether or not the bug is present, and
        // the assertion passes for a reason unrelated to the condition. Caught
        // by re-running with the old condition restored, which is the check
        // every new guard on this PR got.
        val refusing = RefusingEnabledContext(context)
        // Seeded through the same wrapper, so it is that store's own state
        // rather than the real one's, and directly rather than through the
        // store, because the point is to arrive at the decline with the legacy
        // value already in place.
        refusing.getSharedPreferences("crash_reporting", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", true).commit()

        var reportedPersisted: Boolean? = null
        val done = CountDownLatch(1)
        CrashReporting.setEnabled(refusing, enabled = false) { persisted ->
            reportedPersisted = persisted
            done.countDown()
        }
        assertTrue("setEnabled never completed", done.await(5, TimeUnit.SECONDS))

        val store = CrashReportingStore(refusing)
        assertTrue("the refused write leaves the preference as it was", store.isEnabled())
        assertFalse("but the decline must not be recorded as an answer", store.hasAnswered())
        assertFalse(
            "so nothing may collect — unanswered is never permitted",
            store.collectionPermitted(),
        )
        assertEquals("and the failure has to reach the screen", false, reportedPersisted)
        assertTrue(CrashReporting.lastSaveRefused)
    }

    @Test
    fun `a refused decline on a fresh install still counts as answered`() {
        // The other half, and why the fix reads "the preference agrees with
        // the user" rather than "the write succeeded". Here off is genuinely
        // what they asked for *and* what is stored, so the decline stands and
        // they are not asked again — the original reasoning, correct on this
        // install and only on this one.
        //
        // Only the `enabled` write is refused, which is what makes this the
        // fresh-install counterpart of the test above rather than a different
        // question: a fixture refusing every write could not store an answer
        // either, so it would pass for the wrong reason.
        val refusing = RefusingEnabledContext(context)

        val done = CountDownLatch(1)
        CrashReporting.setEnabled(refusing, enabled = false) { done.countDown() }
        assertTrue("setEnabled never completed", done.await(5, TimeUnit.SECONDS))

        val store = CrashReportingStore(refusing)
        assertFalse("off is what a fresh install already was", store.isEnabled())
        assertTrue("so the decline stands and is not asked twice", store.hasAnswered())
        assertFalse("and collection stays off", store.collectionPermitted())
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

/**
 * Preferences that apply every edit to the in-memory map and then report the
 * disk write as failed — what `commit()` does when storage is full or the file
 * is unwritable.
 */
private class RefusingAnswerContext(base: Context) : android.content.ContextWrapper(base) {
    // The store reads through `applicationContext`, which a plain wrapper
    // would hand straight back to the real app context.
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        RefusingAnswerPreferences(super.getSharedPreferences("refusing-answer-$name", mode))
}

private class RefusingAnswerPreferences(
    private val delegate: android.content.SharedPreferences,
) : android.content.SharedPreferences by delegate {
    override fun edit(): android.content.SharedPreferences.Editor =
        RefusingAnswerEditor(delegate.edit())
}

private class RefusingAnswerEditor(
    private val delegate: android.content.SharedPreferences.Editor,
) : android.content.SharedPreferences.Editor by delegate {
    // Returned from every builder call, or `putBoolean(...).commit()` would
    // reach the real editor's commit and report success.
    override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
        delegate.putBoolean(key, value)
        return this
    }

    // The map really is updated first; only the durable half fails.
    override fun commit(): Boolean {
        delegate.commit()
        return false
    }
}

/**
 * Preferences that refuse only the `enabled` write, leaving the answer write
 * to succeed.
 *
 * The blanket [RefusingAnswerContext] cannot express the fresh-install case:
 * with every write refused, no answer can be stored whatever the condition
 * under test decides, so the assertion would hold for a reason that has
 * nothing to do with it.
 */
private class RefusingEnabledContext(base: Context) : android.content.ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        RefusingEnabledPreferences(super.getSharedPreferences("refusing-enabled-$name", mode))
}

private class RefusingEnabledPreferences(
    private val delegate: android.content.SharedPreferences,
) : android.content.SharedPreferences by delegate {
    override fun edit(): android.content.SharedPreferences.Editor =
        RefusingEnabledEditor(delegate.edit())
}

private class RefusingEnabledEditor(
    private val delegate: android.content.SharedPreferences.Editor,
) : android.content.SharedPreferences.Editor by delegate {
    private var touchedEnabled = false

    override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
        if (key == "enabled") touchedEnabled = true
        delegate.putBoolean(key, value)
        return this
    }

    // The map really is updated first; only the durable half fails, and only
    // for the edit that carried `enabled`.
    override fun commit(): Boolean {
        delegate.commit()
        return !touchedEnabled
    }
}
