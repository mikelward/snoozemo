package app.snoozemo.snooze

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.ui.WelcomeCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `WelcomeStore` keeps [WelcomeGate] in step, so the trampoline reads the right
 * answer from memory on the arm path (SPEC.md §4.2).
 *
 * The gate is what a tile tap consults instead of disk; if a write to the store
 * did not republish it, a tap could resume a finished flow or snooze during an
 * unfinished one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WelcomeGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("welcome", Context.MODE_PRIVATE).edit().clear().commit()
        WelcomeGate.publish(false)
    }

    @Test
    fun `remembering a card marks the flow unfinished`() {
        WelcomeStore(context).rememberCard(WelcomeCard.WHAT.name)

        assertTrue(WelcomeGate.unfinished())
    }

    @Test
    fun `a replay in progress keeps the gate open`() {
        // Codex, PR #291: a replay is seen and not fresh but still in progress,
        // so the gate has to open for it too — otherwise a tile tap mid-replay
        // arms past the card the user is on. The gate mirrors `shouldOpenWelcome`,
        // where `inProgress` alone reopens the flow.
        val store = WelcomeStore(context)
        store.markSeen()
        store.rememberCard(WelcomeCard.WHAT.name)

        assertTrue(WelcomeGate.unfinished())
    }

    @Test
    fun `a stale warm-up read cannot overwrite a newer write`() {
        // Codex, PR #291: the warm-up worker reads the preferences and then
        // publishes, and a write can land in between. Without the generation
        // guard the worker's stale read would clobber the newer value — here,
        // reopening a flow the user has just left. Driven synchronously through
        // the seam so the interleaving is deterministic, not raced.
        val store = WelcomeStore(context)
        store.markSeen()
        store.rememberCard(WelcomeCard.WHAT.name)
        assertTrue("a replay in progress is unfinished", WelcomeGate.unfinished())

        // The warm-up reads (still sees the replay in progress, so it would
        // publish `unfinished`), but a `forgetCard` — the flow being left —
        // lands before it publishes.
        store.afterWarmReadBeforePublishForTest = { store.forgetCard() }
        store.publishGateFromWarmForTest()

        assertFalse(
            "the stale warm read must not reopen a finished flow",
            WelcomeGate.unfinished(),
        )
    }

    @Test
    fun `leaving the flow finishes it`() {
        // The real exit: `leaveWelcome` marks seen and then forgets the card, so
        // both halves have to land before the gate closes — marking seen alone
        // leaves a replay's breadcrumb behind (above).
        val store = WelcomeStore(context)
        store.rememberCard(WelcomeCard.WHAT.name)
        store.markSeen()
        store.forgetCard()

        assertFalse(WelcomeGate.unfinished())
    }
}
