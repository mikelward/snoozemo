package app.snoozemo.crash

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashReportingStoreTest {

    private val store get() = CrashReportingStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `an upgrade that enabled crash reports has still not consented to analytics`() {
        // Codex, PR #166's first P1, and the failure the whole consent card
        // exists to prevent. A user on the switch-only build who turned Crash
        // reports on carries `enabled = true` with no answer recorded. Reading
        // the preference alone at startup — which is what `install()` did —
        // takes that as a yes about analytics too and starts collecting
        // before the card has been drawn, from a question never asked.
        store.setEnabled(true)

        assertTrue("the inherited preference is still there", store.isEnabled())
        assertFalse("but nothing was ever asked", store.hasAnswered())
        assertFalse(
            "so collection is not permitted, whatever the preference says",
            store.collectionPermitted(),
        )
    }

    @Test
    fun `answering is what permits collection, and only with a yes`() {
        store.setEnabled(true)
        store.setAnswered()
        assertTrue("enabled and asked", store.collectionPermitted())

        store.setEnabled(false)
        assertFalse("asked and declined", store.collectionPermitted())
    }

    @Test
    fun `a refused answer write leaves the question unanswered`() {
        // Codex, PR #166. `commit()` updates the process-local map before the
        // disk write it reports on, so a refused write that is left in place
        // reads answered now and unanswered after a process death — the card
        // gone, this session collecting, and the next launch quietly stopping
        // and asking again over a switch still showing on. The rollback keeps
        // the in-memory answer matching what is actually stored, whichever way
        // the write went.
        val refusing = CrashReportingStore(RefusingContext(ApplicationProvider.getApplicationContext()))

        assertFalse("the write was refused", refusing.setAnswered())
        assertFalse("so the map does not claim otherwise", refusing.hasAnswered())
    }

    @Test
    fun `a redundant answer write cannot un-answer someone`() {
        // Codex, PR #166, against the rollback added earlier in the same PR.
        // Both surfaces record an answer on every change, so for anyone past
        // the card the write is redundant — and a rollback that put back a
        // literal `false` would turn one failed redundant write into the card
        // returning and collection stopping, over a choice already made. The
        // early return is what makes the rollback's `false` the value that was
        // actually there rather than a guess.
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("refusing-crash_reporting", Context.MODE_PRIVATE)
        // Written straight to the file the wrapper wraps, since the point is
        // to arrive at `setAnswered` with the answer already stored.
        prefs.edit().putBoolean("answered", true).commit()
        val refusing = CrashReportingStore(RefusingContext(ApplicationProvider.getApplicationContext()))

        assertTrue("already answered, so nothing to write and nothing to fail", refusing.setAnswered())
        assertTrue("and the answer survives", refusing.hasAnswered())
    }

    @Test
    fun `a yes whose write was refused is not recorded as answered`() {
        // Codex, PR #166. The alternative is worse than being asked twice: the
        // answer sticks, the card never returns, and an install whose user
        // tapped Yes please sits permanently opted out with the only trace a
        // failure line on a screen they may never open. A no is different and
        // still recorded — off is already the default, so a refused write
        // changed nothing and their decision stands.
        val refusing = CrashReportingStore(RefusingContext(ApplicationProvider.getApplicationContext()))

        assertFalse("the yes could not be stored", refusing.setEnabled(true))
        assertFalse("so it must not read as answered", refusing.hasAnswered())
    }

    @Test
    fun `off and unasked are different facts`() {
        // The distinction the consent card turns on. A fresh install reads
        // off *and* unanswered; a user who declined reads off and answered.
        // Collapsing them into the enabled flag would re-ask someone who has
        // already said no, every time they opened the app.
        assertFalse(store.isEnabled())
        assertFalse(store.hasAnswered())

        store.setEnabled(false)

        assertFalse("declining is not recorded by the switch alone", store.hasAnswered())
    }

    @Test
    fun `an answer survives the instance, whichever way it went`() {
        for (answer in listOf(true, false)) {
            val fresh = CrashReportingStore(ApplicationProvider.getApplicationContext())
            fresh.setEnabled(answer)
            fresh.setAnswered()

            assertTrue(store.hasAnswered())
            assertEquals(answer, store.isEnabled())
        }
    }

    @Test
    fun `an answer is never unset by a later setting change`() {
        // Settings can flip reporting back and forth for the life of the
        // install; none of that un-asks the question, so the card must not
        // return.
        store.setAnswered()

        store.setEnabled(true)
        store.setEnabled(false)

        assertTrue(store.hasAnswered())
    }

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

/**
 * A context whose preferences apply every edit to the in-memory map and then
 * report the disk write as failed — which is exactly what `commit()` does when
 * storage is full or the file is unwritable, and the reason the store rolls
 * back rather than trusting the map.
 */
private class RefusingContext(base: Context) : ContextWrapper(base) {
    // The store reads through `applicationContext`, which a plain wrapper
    // would hand straight back to the real app context.
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        RefusingPreferences(super.getSharedPreferences("refusing-$name", mode))
}

private class RefusingPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor = RefusingEditor(delegate.edit())
}

private class RefusingEditor(
    private val delegate: SharedPreferences.Editor,
) : SharedPreferences.Editor by delegate {
    // Returned from every builder call, or `putBoolean(...).commit()` would
    // reach the real editor's commit and report success.
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
        delegate.putBoolean(key, value)
        return this
    }

    // The map really is updated first; only the durable half fails.
    override fun commit(): Boolean {
        delegate.commit()
        return false
    }
}
