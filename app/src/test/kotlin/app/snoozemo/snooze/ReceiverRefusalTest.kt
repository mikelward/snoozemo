package app.snoozemo.snooze

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.EndReason
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * What the receiver fallbacks do when the platform refuses to change the rule.
 *
 * These paths run precisely when the service could not be started, so they are
 * the app's last word on whether a phone stays silent — and their refusal
 * branches were untestable until `releaseDirectly`, `restoreDirectly` and
 * `discardForeignRecord` took an injectable `ZenController`. PR #26 found three
 * separate principle 1 bugs inside one newly-written branch of this kind, which
 * is the argument for covering the older ones rather than trusting them.
 *
 * The distinction under test throughout is `ZenFailure.nothingLeftToRelease`:
 * `PLATFORM_REFUSED` means a rule that still exists said no, so a retry may
 * work and **the record is the retry mechanism**; `NO_POLICY_ACCESS` and
 * friends mean nothing is silencing the phone and no retry ever can, so keeping
 * the record would strand the app claiming `Snoozing` over a phone that already
 * rings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceiverRefusalTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStampCache() = DeviceStamp.forget()

    private fun aSnooze(
        startedAt: Instant = Instant.now(),
        cap: Duration = Duration.ofHours(4),
    ) = ActiveSnooze(
        anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
        startedAt = startedAt,
        capExpiresAt = startedAt.plus(cap),
        mode = TrackingMode.DURATION_ONLY,
    )

    private fun storedSnooze(): ActiveSnooze? = ActiveSnoozeStore(context).load()

    // --- releaseDirectly ------------------------------------------------

    @Test
    fun `a refused release keeps the record, because the record is the retry`() {
        val store = ActiveSnoozeStore(context)
        store.save(aSnooze())

        val released = releaseDirectly(
            context,
            EndReason.DURATION_CAP,
            RefusingZen(),
        )

        assertFalse("a refused release must not report success", released)
        assertNotNull(
            "the record is what a later retry works from; deleting it ends the retries",
            storedSnooze(),
        )
    }

    @Test
    fun `a refused release leaves a durable obligation behind it`() {
        ActiveSnoozeStore(context).save(aSnooze())

        releaseDirectly(context, EndReason.DURATION_CAP, RefusingZen())

        // The rule may still be on and this process may not survive, so
        // something on disk has to remember that (SPEC.md §7.1). Without it a
        // later start has no reason to suspect the phone is silent.
        assertTrue(
            "a refused release must record that the rule may still be on",
            PendingFailureStore(context).ruleMayBeStuck(),
        )
    }

    @Test
    fun `a release that succeeds clears the record`() {
        ActiveSnoozeStore(context).save(aSnooze())

        val released = releaseDirectly(
            context,
            EndReason.DURATION_CAP,
            RefusingZen().apply { outcome = ZenOutcome.Applied },
        )

        assertTrue(released)
        assertNull(
            "a released snooze must not leave a record a later boot would restore",
            storedSnooze(),
        )
    }

    @Test
    fun `a manual end without the service is credited to the user`() {
        // The trigger reaches the platform's Modes UI, which uses it to tell
        // "I did this" from "my phone did this" (SPEC.md §5.4). This path is
        // where the trampoline sends the user's own `End now` when the service
        // refuses to start, and a hard-coded CONTEXT credited that tap to the
        // app deciding by itself.
        ActiveSnoozeStore(context).save(aSnooze())
        val zen = RefusingZen().apply { outcome = ZenOutcome.Applied }

        releaseDirectly(context, EndReason.MANUAL, zen)

        assertEquals(
            listOf(false to app.snoozemo.core.ZenTrigger.USER_ACTION),
            zen.calls,
        )
    }

    @Test
    fun `an automatic end without the service is not credited to the user`() {
        // The other direction of the same distinction: a cap expiry reported
        // as USER_ACTION would tell the user they ended a snooze they didn't.
        ActiveSnoozeStore(context).save(aSnooze())
        val zen = RefusingZen().apply { outcome = ZenOutcome.Applied }

        releaseDirectly(context, EndReason.DURATION_CAP, zen)

        assertEquals(
            listOf(false to app.snoozemo.core.ZenTrigger.CONTEXT),
            zen.calls,
        )
    }

    @Test
    fun `a refusal with nothing left to release also clears the record`() {
        // Access revoked deletes the app's rules, so every retry would fail the
        // same way forever. Keeping the record would strand the app showing
        // `Snoozing` over a phone that is already ringing (SPEC.md §8.2) —
        // which is principle 2's failure rather than principle 1's, and still
        // worth being exact about.
        ActiveSnoozeStore(context).save(aSnooze())

        releaseDirectly(
            context,
            EndReason.DURATION_CAP,
            RefusingZen().apply {
                outcome = ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS)
            },
        )

        assertNull(storedSnooze())
    }

    // --- restoreDirectly ------------------------------------------------

    @Test
    fun `a refused restore keeps the snooze running, because it can be retried`() {
        val snooze = aSnooze()
        ActiveSnoozeStore(context).save(snooze)

        restoreDirectly(context, snooze, RefusingZen())

        assertNotNull(
            "a retryable refusal leaves a live snooze; the record and its cap are the retry",
            storedSnooze(),
        )
    }

    @Test
    fun `a restore refused for good ends the snooze instead of pretending`() {
        // The distinction `SnoozeController.end` draws, which this fallback was
        // missing before: no access, no rule, or a disabled rule means nothing
        // is silencing the phone and no retry can succeed.
        val snooze = aSnooze()
        ActiveSnoozeStore(context).save(snooze)

        restoreDirectly(
            context,
            snooze,
            RefusingZen().apply {
                outcome = ZenOutcome.NotApplied(ZenFailure.NO_POLICY_ACCESS)
            },
        )

        assertNull(
            "nothing can be retried, so the app must stop claiming a snooze",
            storedSnooze(),
        )
    }

    @Test
    fun `a restore of an already-expired record ends it without touching the rule`() {
        // The clock is checked before the rule, so a cap that passed while the
        // phone was off cannot be re-asserted and then wait for an overdue
        // alarm to undo it.
        val expired = aSnooze(startedAt = Instant.now().minus(Duration.ofHours(9)))
        ActiveSnoozeStore(context).save(expired)
        val zen = RefusingZen().apply { outcome = ZenOutcome.Applied }

        restoreDirectly(context, expired, zen)

        assertEquals(
            "an expired record must be released, never re-asserted",
            listOf(false to app.snoozemo.core.ZenTrigger.CONTEXT),
            zen.calls,
        )
        assertNull(storedSnooze())
    }
}
