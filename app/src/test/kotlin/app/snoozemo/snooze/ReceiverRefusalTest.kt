package app.snoozemo.snooze

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.EndReason
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenFailure
import app.snoozemo.core.ZenOutcome
import app.snoozemo.core.ZenRuleActivation
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
        store.arm(aSnooze())

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
        ActiveSnoozeStore(context).arm(aSnooze())

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
        ActiveSnoozeStore(context).arm(aSnooze())

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
        ActiveSnoozeStore(context).arm(aSnooze())
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
        ActiveSnoozeStore(context).arm(aSnooze())
        val zen = RefusingZen().apply { outcome = ZenOutcome.Applied }

        releaseDirectly(context, EndReason.DURATION_CAP, zen)

        assertEquals(
            listOf(false to app.snoozemo.core.ZenTrigger.CONTEXT),
            zen.calls,
        )
    }

    @Test
    fun `an automatic end without the service records its reason first`() {
        // The no-service twin of the service's own marker (SPEC.md §5.8): this
        // path used to write none, so a cap-driven release that turned the
        // rule off here and died before the erase was read back as the user's
        // own doing (Codex, PR #36). Observed at the moment of the zen write,
        // which is the moment a crash would leave it behind.
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())
        val delegate = RefusingZen().apply { outcome = ZenOutcome.Applied }
        var reasonAtWrite: EndReason? = null
        val zen = object : app.snoozemo.core.ZenController by delegate {
            override fun setSnoozed(
                snoozed: Boolean,
                trigger: app.snoozemo.core.ZenTrigger,
                placeName: String,
                snooze: app.snoozemo.core.SnoozeIdentity?,
            ): ZenOutcome {
                reasonAtWrite = store.releasingReason()
                return delegate.setSnoozed(snoozed, trigger, placeName, snooze)
            }
        }

        releaseDirectly(context, EndReason.DURATION_CAP, zen)

        assertEquals(EndReason.DURATION_CAP, reasonAtWrite)
    }

    @Test
    fun `a manual end without the service writes no marker`() {
        // The trampoline sends the user's own `End now` here when the service
        // refuses to start, and a disk write would sit between their tap and
        // their phone making noise again (Codex, PR #194). A manual ending
        // needs no marker: losing one to a crash falls back to "the user
        // turned Do Not Disturb off", equally silent and equally theirs.
        // Observed through a reason seeded beforehand: the automatic path
        // overwrites it at the write, the manual path must leave it alone.
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())
        store.markReleasing(EndReason.DEPARTURE)
        val delegate = RefusingZen().apply { outcome = ZenOutcome.Applied }
        var reasonAtWrite: EndReason? = null
        val zen = object : app.snoozemo.core.ZenController by delegate {
            override fun setSnoozed(
                snoozed: Boolean,
                trigger: app.snoozemo.core.ZenTrigger,
                placeName: String,
                snooze: app.snoozemo.core.SnoozeIdentity?,
            ): ZenOutcome {
                reasonAtWrite = store.releasingReason()
                return delegate.setSnoozed(snoozed, trigger, placeName, snooze)
            }
        }

        releaseDirectly(context, EndReason.MANUAL, zen)

        assertEquals(EndReason.DEPARTURE, reasonAtWrite)
    }

    @Test
    fun `a refused end without the service discards the reason it recorded`() {
        // An attempt that never completed must not go on to explain some later
        // ending (SPEC.md §5.8) — the user turning Do Not Disturb off next
        // would be told they had hit the time limit.
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())

        releaseDirectly(context, EndReason.DURATION_CAP, RefusingZen())

        assertNull(store.releasingReason())
    }

    @Test
    fun `a refusal with nothing left to release also clears the record`() {
        // Access revoked deletes the app's rules, so every retry would fail the
        // same way forever. Keeping the record would strand the app showing
        // `Snoozing` over a phone that is already ringing (SPEC.md §8.2) —
        // which is principle 2's failure rather than principle 1's, and still
        // worth being exact about.
        ActiveSnoozeStore(context).arm(aSnooze())

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
    fun `a restore that finds the rule still on retires a stale reason`() {
        // A refused release whose clean-up also failed to commit, then a
        // process death: the reason outlives the attempt, and no rewrite of
        // the live record may erase it (Codex, PR #194). The rule reading as
        // on is what proves no release completed, so the reason goes here.
        val store = ActiveSnoozeStore(context)
        val snooze = aSnooze().copy(armed = true)
        store.arm(snooze)
        store.markReleasing(EndReason.DURATION_CAP)

        restoreDirectly(context, snooze, RefusingZen().apply { outcome = ZenOutcome.Applied })

        assertNull("a reason beside a rule still on describes nothing", store.releasingReason())
        assertNotNull("and the snooze itself is untouched", storedSnooze())
    }

    @Test
    fun `a refused restore keeps the snooze running, because it can be retried`() {
        val snooze = aSnooze()
        ActiveSnoozeStore(context).arm(snooze)

        restoreDirectly(context, snooze, RefusingZen())

        assertNotNull(
            "a retryable refusal leaves a live snooze; the record and its cap are the retry",
            storedSnooze(),
        )
    }

    @Test
    fun `the fallback does not re-silence a phone the user un-silenced`() {
        // This path runs precisely when no process was alive to hear the status
        // broadcast, so it is the likeliest one to meet a user who turned Do Not
        // Disturb off while the app was gone. Re-asserting first would overrule
        // them (Codex, PR #36).
        // `armed = true` is what makes an off rule mean the *user* turned it
        // off: the arm completed, so the rule was on and is not any more.
        val snooze = aSnooze().copy(armed = true)
        ActiveSnoozeStore(context).arm(snooze)
        val zen = RefusingZen().apply {
            outcome = ZenOutcome.Applied
            activation = ZenRuleActivation.INACTIVE
        }

        restoreDirectly(context, snooze, zen)

        assertNull("the snooze is over; the record must not survive", storedSnooze())
        assertFalse(
            "the rule must never be turned back on for a user who turned it off",
            zen.calls.any { it.first },
        )
    }

    @Test
    fun `an interrupted arm is completed by the fallback, not erased`() {
        // An off rule looks the same whether the user switched it off or the arm
        // never got it on, and `armed` is the only thing that tells them apart
        // (Codex, PR #36). Reading an unfinished arm as the user's doing would
        // throw away a snooze that only needed finishing.
        val unfinished = aSnooze().copy(armed = false)
        ActiveSnoozeStore(context).arm(unfinished)
        val zen = RefusingZen().apply {
            outcome = ZenOutcome.Applied
            activation = ZenRuleActivation.INACTIVE
        }

        restoreDirectly(context, unfinished, zen)

        assertNotNull("the arm only needed finishing", storedSnooze())
        assertTrue("and finishing it means turning the rule on", zen.calls.any { it.first })
    }

    @Test
    fun `a restore refused for good ends the snooze instead of pretending`() {
        // The distinction `SnoozeController.end` draws, which this fallback was
        // missing before: no access, no rule, or a disabled rule means nothing
        // is silencing the phone and no retry can succeed.
        val snooze = aSnooze()
        ActiveSnoozeStore(context).arm(snooze)

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
        ActiveSnoozeStore(context).arm(expired)
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
