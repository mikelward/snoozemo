package app.snoozemo.snooze

import android.app.Application
import android.media.AudioManager
import app.snoozemo.R
import app.snoozemo.core.SnoozeRinger
import app.snoozemo.dnd.PrefsRingerLoanStore
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.TrackingMode
import app.snoozemo.ui.formatSheetTime
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * The rendered degraded line (SPEC.md §4.3, §8.1) — the one surface this
 * whole plumbing exists to change, asserted where the user actually reads it.
 *
 * The controller and store tests either side of this one prove the cause is
 * computed and persisted; neither would notice a reversed mapping, a dropped
 * `withReason` join, or a reason leaking onto a mode that deliberately
 * excludes it, because none of them posts a notification (Codex, PR #141).
 * So these assert `contentText` verbatim.
 *
 * Deliberately compared against the string resources rather than literals:
 * the point is that each cause reaches its own line, not what the English
 * happens to say this week — and a literal here would have to be edited by
 * whoever reworded the copy, which is the reader least likely to think a
 * test failure means anything.
 */
@RunWith(RobolectricTestRunner::class)
// A plain `Application`, not `SnoozemoApplication`: its `onCreate` starts
// `reconcileRingerInBackground` on a daemon thread, and Robolectric builds the
// application for every test — so that thread races the test body for the
// process-wide ringer lock. Reaching it while no snooze record is on disk, it
// does exactly its job: drops the ceiling as stale and hands the loan back,
// under a fixture that put both there by hand. The tests below reach that path
// deliberately where they mean to; a stray copy of it running under every
// statement is what made them fail about one run in ten.
@Config(application = Application::class)
class SnoozeNotificationsDegradationTest {

    private val now = Instant.parse("2026-08-22T09:00:00Z")

    @Before
    fun reset() {
        SnoozeNotifications.resetForTest()
        // No ceiling in force, so the ringer clause (SPEC.md §5.9) adds nothing
        // and these assertions stay about the *mode* line they exist for. The
        // three tests that are about the clause record one for themselves.
        PrefsRingerLoanStore(appContext).recordChoice(null)
        appContext.getSystemService(AudioManager::class.java).ringerMode =
            AudioManager.RINGER_MODE_NORMAL
    }

    /**
     * The other axis (SPEC.md §5.9), and it is a separate clause on purpose:
     * the mode line says whether the snooze will end correctly, this says
     * whether it is as quiet as the user asked. A ceiling that did not hold is
     * something the user *hears*, so the card names it rather than leaving the
     * debug log as the only record.
     */
    @Test
    fun `a phone louder than the chosen ceiling says so on the card`() {
        PrefsRingerLoanStore(appContext).recordChoice(SnoozeRinger.VIBRATE)
        appContext.getSystemService(AudioManager::class.java).ringerMode =
            AudioManager.RINGER_MODE_NORMAL

        assertEquals(
            expected(R.string.ongoing_ends_when_you_leave, R.string.ongoing_cause_still_ringing),
            postedOngoing(TrackingMode.FULL, cause = null),
        )
    }

    @Test
    fun `a vibrating phone under a silent ceiling is named as vibrating`() {
        PrefsRingerLoanStore(appContext).recordChoice(SnoozeRinger.SILENT)
        appContext.getSystemService(AudioManager::class.java).ringerMode =
            AudioManager.RINGER_MODE_VIBRATE

        // Not "still ringing", which over a buzzing phone is simply wrong.
        assertEquals(
            expected(R.string.ongoing_ends_when_you_leave, R.string.ongoing_cause_still_vibrating),
            postedOngoing(TrackingMode.FULL, cause = null),
        )
    }

    @Test
    fun `a ceiling over an unreadable ringer hedges rather than naming a mode`() {
        PrefsRingerLoanStore(appContext).recordChoice(SnoozeRinger.VIBRATE)
        // No `AudioManager` to read: the ceiling is not holding, and which mode
        // the phone is in is what nobody can say (copy approved by the
        // maintainer, 2026-09-02).
        shadowOf(appContext as android.app.Application)
            .setSystemService(android.content.Context.AUDIO_SERVICE, null)

        assertEquals(
            expected(R.string.ongoing_ends_when_you_leave, R.string.ongoing_cause_ringer_unknown),
            postedOngoing(TrackingMode.FULL, cause = null),
        )
    }

    @Test
    fun `a phone at or below the ceiling adds no clause`() {
        PrefsRingerLoanStore(appContext).recordChoice(SnoozeRinger.VIBRATE)
        appContext.getSystemService(AudioManager::class.java).ringerMode =
            AudioManager.RINGER_MODE_SILENT

        // Quieter than the `Vibrate` ceiling, which is left alone rather
        // than raised — so there is nothing to report.
        assertEquals(
            stringOf(R.string.ongoing_ends_when_you_leave),
            postedOngoing(TrackingMode.FULL, cause = null),
        )
    }

    /**
     * The ongoing card as it stands, located by its id rather than its title:
     * a plain timer-only card folds its two lines into a `Snoozing until …`
     * headline (maintainer, 2026-09-12), so the constant title is no longer the
     * card's identity.
     */
    private fun ongoingCard(
        mode: TrackingMode,
        cause: DegradationCause?,
        endsOnDeparture: Boolean = true,
        endsOnMotion: Boolean = false,
    ): android.app.Notification {
        SnoozeNotifications(appContext).showOngoing(
            snoozeFixture(now).copy(
                mode = mode,
                degradation = cause,
                endsOnDeparture = endsOnDeparture,
                endsOnMotion = endsOnMotion,
            ),
        )
        val manager = appContext.getSystemService(android.app.NotificationManager::class.java)
        return requireNotNull(shadowOf(manager).getNotification(SnoozeNotifications.ID_ONGOING))
    }

    /** The condition line the ongoing card most recently carried. */
    private fun postedOngoing(
        mode: TrackingMode,
        cause: DegradationCause?,
        endsOnDeparture: Boolean = true,
        endsOnMotion: Boolean = false,
    ): String? =
        shadowOf(ongoingCard(mode, cause, endsOnDeparture, endsOnMotion)).contentText?.toString()

    /**
     * The folded headline a plain timer-only card carries: `Snoozing until …`,
     * with the cap time formatted exactly as the card formats it, so this stays
     * about which line the fact lands on rather than the wording of the clock.
     */
    private fun foldedTitle(): String =
        appContext.getString(
            R.string.snoozing_until_time,
            formatSheetTime(appContext, now.plus(Duration.ofHours(7))),
        )

    private fun expected(modeString: Int, causeString: Int) =
        appContext.getString(
            R.string.ongoing_degraded_reason,
            stringOf(modeString),
            stringOf(causeString),
        )

    /**
     * Principle 2's distinction, at the surface that has to carry it: a card
     * that is a timer because the user chose one is a different thing from one
     * that is a timer because location died. A chosen timer suppresses its
     * cause and folds into the `Snoozing until …` headline; a snooze still
     * trying to track keeps the cause on its `Timer only — …` line and cannot
     * fold. The folding here is the evidence the cause was dropped.
     *
     * The failure this guards is one-sided: narrowing the mode without
     * narrowing the cause rendered the user's own choice as `Timer only — weak
     * signal`, blaming the machinery for it — so the counterpart, a snooze
     * still watching for a departure that keeps naming its cause, is asserted
     * separately below (Codex, PR #267).
     */
    @Test
    fun `a chosen timer folds into the headline and drops its stale cause`() {
        // `endsOnDeparture = false` makes this a chosen-time snooze:
        // effectiveMode is DURATION_ONLY and effectiveDegradation is null, so the
        // FIXES_TOO_VAGUE the record still carries is suppressed — the card would
        // read `Timer only — weak signal`, blaming the machinery for the user's
        // own choice, if it were not (Codex, PR #267). With nothing to caveat,
        // the two lines fold into one headline naming the end time (maintainer,
        // 2026-09-12), which is the same evidence that the cause was dropped.
        val card = ongoingCard(
            TrackingMode.WIFI_ONLY,
            DegradationCause.FIXES_TOO_VAGUE,
            endsOnDeparture = false,
        )
        assertEquals(foldedTitle(), shadowOf(card).contentTitle.toString())
        assertNull(shadowOf(card).contentText)
    }

    @Test
    fun `a timer-only snooze that ends on movement names the movement, not the timer`() {
        // Composed, the two read `Timer only, or when you move` — a card
        // contradicting itself (Codex, PR #267). Reachable from a chosen time
        // followed by `Until I move`. The movement exit is the only end
        // condition worth naming; the timer is the countdown already on the
        // card.
        assertEquals(
            stringOf(R.string.ongoing_ends_when_you_move),
            postedOngoing(
                TrackingMode.WIFI_ONLY,
                cause = null,
                endsOnDeparture = false,
                endsOnMotion = true,
            ),
        )
    }

    @Test
    fun `a snooze still watching for a departure names both exits`() {
        // The other direction, and why this is narrower than the screen's
        // rule: departure is still armed, so the card names it *and* the
        // movement exit rather than dropping one.
        assertEquals(
            appContext.getString(
                R.string.ongoing_or_when_you_move,
                stringOf(R.string.ongoing_ends_when_you_leave),
            ),
            postedOngoing(
                TrackingMode.FULL,
                cause = null,
                endsOnDeparture = true,
                endsOnMotion = true,
            ),
        )
    }

    @Test
    fun `a snooze still watching for a departure keeps naming its cause`() {
        assertEquals(
            expected(R.string.ongoing_wifi_only, R.string.ongoing_cause_weak_signal),
            postedOngoing(
                TrackingMode.WIFI_ONLY,
                DegradationCause.FIXES_TOO_VAGUE,
                endsOnDeparture = true,
            ),
        )
    }

    @Test
    fun `location switched off says so`() {
        assertEquals(
            expected(R.string.ongoing_timer_only, R.string.ongoing_cause_services_off),
            postedOngoing(TrackingMode.DURATION_ONLY, DegradationCause.LOCATION_SERVICES_OFF),
        )
    }

    @Test
    fun `no fix at all says so`() {
        assertEquals(
            expected(R.string.ongoing_timer_only, R.string.ongoing_cause_no_fix),
            postedOngoing(TrackingMode.DURATION_ONLY, DegradationCause.NO_LOCATION_FIX),
        )
    }

    @Test
    fun `fixes too vague say so, and not the same thing as no fix`() {
        assertEquals(
            expected(R.string.ongoing_timer_only, R.string.ongoing_cause_weak_signal),
            postedOngoing(TrackingMode.DURATION_ONLY, DegradationCause.FIXES_TOO_VAGUE),
        )
    }

    /**
     * The distinction the PR exists for, asserted as a distinction rather
     * than as two independent equalities: the two causes map to one
     * `TrackingMode`, so a mapping that returned the same string for both
     * would satisfy every test above and still ship the bug.
     */
    @Test
    fun `each rendered cause gets a line of its own`() {
        val lines = listOf(
            DegradationCause.LOCATION_SERVICES_OFF,
            DegradationCause.NO_LOCATION_FIX,
            DegradationCause.FIXES_TOO_VAGUE,
            DegradationCause.NO_LOCATION_IN_BACKGROUND,
        ).map { postedOngoing(TrackingMode.DURATION_ONLY, it) }

        assertEquals(lines.size, lines.toSet().size)
    }

    /** Wi-Fi is still tracking, so the mode says `Wi-Fi only` and the reason follows it. */
    @Test
    fun `a Wi-Fi-only snooze carries the reason too`() {
        assertEquals(
            expected(R.string.ongoing_wifi_only, R.string.ongoing_cause_weak_signal),
            postedOngoing(TrackingMode.WIFI_ONLY, DegradationCause.FIXES_TOO_VAGUE),
        )
    }

    /**
     * Names the missing permission, which is the thing the user can act on
     * (maintainer, 2026-08-30, reversing the earlier decision to stay silent
     * here). Granting it does not by itself restart tracking — that still
     * wants `Resume tracking` (`TODO.md`) — but `Timer only` alone told the
     * user nothing at all.
     */
    @Test
    fun `the background-location cause names the permission`() {
        assertEquals(
            expected(R.string.ongoing_timer_only, R.string.ongoing_cause_no_background),
            postedOngoing(TrackingMode.DURATION_ONLY, DegradationCause.NO_LOCATION_IN_BACKGROUND),
        )
    }

    /**
     * The app's own wiring carries no reason line of its own, so a
     * NOTHING_WATCHING snooze is a plain timer-only card and folds into the
     * `Snoozing until …` headline like any other (maintainer, 2026-09-12).
     */
    @Test
    fun `the nothing-watching cause folds into the headline`() {
        val card = ongoingCard(TrackingMode.DURATION_ONLY, DegradationCause.NOTHING_WATCHING)
        assertEquals(foldedTitle(), shadowOf(card).contentTitle.toString())
        assertNull(shadowOf(card).contentText)
    }

    /**
     * The plain case the fold exists for: duration tracking, no cause, no
     * movement exit. `Snoozing` over `Timer only` said nothing the end time
     * doesn't say better, so the two become one headline (maintainer,
     * 2026-09-12). The `contentText` is dropped, not left empty.
     */
    @Test
    fun `a plain timer-only snooze folds its two lines into one headline`() {
        val card = ongoingCard(TrackingMode.DURATION_ONLY, null)
        assertEquals(foldedTitle(), shadowOf(card).contentTitle.toString())
        assertNull(shadowOf(card).contentText)
    }

    /**
     * `Wi-Fi lost — ending soon` already names what matters, and a second
     * em-dashed clause on a state that resolves in minutes costs length for
     * nothing (AGENTS.md, *Concise copy*). Asserted with a cause set, since
     * the exclusion is the mode's and not the cause's.
     */
    @Test
    fun `the grace period says only that it is ending, whatever the cause`() {
        assertEquals(
            stringOf(R.string.ongoing_wifi_grace),
            postedOngoing(TrackingMode.WIFI_GRACE, DegradationCause.NO_LOCATION_FIX),
        )
    }

    /**
     * Unreachable through `SnoozeController.modeFor`, which maps a null
     * degradation straight to the anchor's own capability — but asserted
     * anyway, because "by construction" is a property of today's caller and
     * this renderer is what a future one would reach.
     */
    @Test
    fun `full tracking never appends a reason`() {
        assertEquals(
            stringOf(R.string.ongoing_ends_when_you_leave),
            postedOngoing(TrackingMode.FULL, DegradationCause.NO_LOCATION_FIX),
        )
    }

    /** Guards the join itself: the reason is appended, not substituted. */
    @Test
    fun `the degraded line keeps the mode as well as the reason`() {
        // A cause is present, so this card does not fold — it keeps its
        // condition line, and `postedOngoing` returns it rather than null.
        val line = requireNotNull(
            postedOngoing(TrackingMode.DURATION_ONLY, DegradationCause.LOCATION_SERVICES_OFF),
        )

        assertTrue(line.contains(stringOf(R.string.ongoing_timer_only)))
        assertTrue(line.contains(stringOf(R.string.ongoing_cause_services_off)))
    }
}
