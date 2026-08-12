package app.snoozemo.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSnoozeTest {

    private val start: Instant = Instant.parse("2026-08-11T09:00:00Z")

    private val anchorWithFix = Anchor(
        lat = 0.0,
        lon = 0.0,
        fixAccuracyM = 25f,
        capturedAt = start,
        ssid = "ExampleWifi",
    )

    /** A plausible device uptime at the moment the fixture snooze is armed. */
    private val uptimeAtStart = Duration.ofHours(30).toMillis()

    private val bootReference = start.toEpochMilli() - uptimeAtStart

    private fun snooze(cap: Duration = ActiveSnooze.DEFAULT_CAP) = ActiveSnooze(
        anchor = anchorWithFix,
        startedAt = start,
        capExpiresAt = start.plus(cap),
        mode = TrackingMode.from(anchorWithFix),
        bootReference = bootReference,
    )

    /**
     * Both clocks at [instant], agreeing: no reboot and no clock change since
     * the fixture was armed. The two frames only diverge in the tests that make
     * them diverge on purpose.
     */
    private fun at(instant: Instant) = ClockReading(
        wallMillis = instant.toEpochMilli(),
        uptimeMillis = uptimeAtStart + Duration.between(start, instant).toMillis(),
    )

    @Test
    fun `remaining counts down to the cap`() {
        val snooze = snooze()

        assertEquals(Duration.ofHours(8), snooze.remaining(at(start)))
        assertEquals(Duration.ofHours(4), snooze.remaining(at(start.plusSeconds(4 * 3600))))
    }

    @Test
    fun `remaining is floored at zero once the cap has passed`() {
        // A negative countdown would reach the notification as "ends in -4m".
        val snooze = snooze()

        assertEquals(Duration.ZERO, snooze.remaining(at(start.plus(Duration.ofHours(12)))))
    }

    @Test
    fun `the cap fires on its exact instant, not after it`() {
        // Fail open: an ambiguous boundary resolves toward ending the snooze.
        val snooze = snooze()

        assertFalse(snooze.isExpired(at(start.plus(Duration.ofHours(8)).minusMillis(1))))
        assertTrue(snooze.isExpired(at(start.plus(Duration.ofHours(8)))))
        assertTrue(snooze.isExpired(at(start.plus(Duration.ofHours(9)))))
    }

    @Test
    fun `an anchor with a vague fix is not usable for departure tests`() {
        val vague = Anchor(
            lat = 0.0,
            lon = 0.0,
            fixAccuracyM = Anchor.MAX_ANCHOR_ACCURACY_M + 1f,
            capturedAt = start,
        )

        assertFalse(vague.hasUsableFix)
        assertTrue(vague.copy(fixAccuracyM = 25f).hasUsableFix)
    }

    @Test
    fun `an anchor with no fix at all is not usable, however precise the SSID`() {
        // Arming indoors with no signal: Wi-Fi-only mode, not a pretend fix.
        val wifiOnly = Anchor(capturedAt = start, ssid = "ExampleWifi")

        assertFalse(wifiOnly.hasUsableFix)
    }

    @Test
    fun `a snooze defaults to the eight hour cap and an unnamed place`() {
        val snooze = snooze()

        assertEquals(ActiveSnooze.DEFAULT_PLACE_NAME, snooze.placeName)
        assertEquals(start.plus(Duration.ofHours(8)), snooze.capExpiresAt)
    }

    @Test
    fun `tracking mode is the most capable one the anchor supports`() {
        assertEquals(TrackingMode.FULL, TrackingMode.from(anchorWithFix))

        val noFix = Anchor(capturedAt = start, ssid = "ExampleWifi")
        assertEquals(TrackingMode.WIFI_ONLY, TrackingMode.from(noFix))

        val nothing = Anchor(capturedAt = start)
        assertEquals(TrackingMode.DURATION_ONLY, TrackingMode.from(nothing))
    }

    @Test
    fun `a fix too vague to test against does not count as full tracking`() {
        // The failure this guards: arming indoors on a 500 m cell fix and
        // recording FULL, so the notification claims tracking that cannot work.
        val vagueFix = anchorWithFix.copy(fixAccuracyM = Anchor.MAX_ANCHOR_ACCURACY_M + 1f)

        assertEquals(TrackingMode.WIFI_ONLY, TrackingMode.from(vagueFix))
        assertEquals(
            TrackingMode.DURATION_ONLY,
            TrackingMode.from(vagueFix.copy(ssid = null)),
        )
    }

    @Test
    fun `extending moves the cap out by the step`() {
        val snooze = snooze(Duration.ofHours(1))

        assertEquals(
            start.plus(Duration.ofMinutes(90)),
            snooze.extendedCap(Duration.ofMinutes(30)),
        )
    }

    @Test
    fun `extending stops at the backstop`() {
        // Repeated taps may not walk a snooze past the 8-hour default (SPEC.md
        // §7), measured from the start rather than from the current cap.
        val snooze = snooze(Duration.ofMinutes(7 * 60 + 50))

        assertEquals(
            start.plus(ActiveSnooze.DEFAULT_CAP),
            snooze.extendedCap(Duration.ofMinutes(30)),
        )
    }

    @Test
    fun `extending never reaches the configurable maximum`() {
        // The bug this pins: clamping to MAX_CAP let sixteen taps take a
        // default snooze from 8 hours to 24 — the backstop every other exit
        // falls back to, walked past half an hour at a time.
        var cap = ActiveSnooze.DEFAULT_CAP
        repeat(40) {
            cap = Duration.between(start, snooze(cap).extendedCap(Duration.ofMinutes(30)))
        }

        assertEquals(ActiveSnooze.DEFAULT_CAP, cap)
    }

    @Test
    fun `extending at the ceiling returns the cap unchanged`() {
        // How the caller tells "extended" from "cannot extend" without
        // re-deriving the clamp: nothing moved, so nothing is claimed to.
        val snooze = snooze(ActiveSnooze.DEFAULT_CAP)

        assertEquals(snooze.capExpiresAt, snooze.extendedCap(Duration.ofMinutes(30)))
    }

    @Test
    fun `a cap already past the backstop declines rather than jumping back`() {
        // Only a per-place setting could produce one, and none exists yet — but
        // a clamp that returned the ceiling here would *shorten* the snooze on
        // a tap that asked to lengthen it, which is the one direction `+30 min`
        // must never move.
        val snooze = snooze(Duration.ofHours(12))

        assertEquals(snooze.capExpiresAt, snooze.extendedCap(Duration.ofMinutes(30)))
    }

    @Test
    fun `a retry applies to the record it was queued for`() {
        val snooze = snooze()

        assertTrue(ActiveSnooze.retryStillApplies(snooze, snooze.startedAt))
    }

    @Test
    fun `a retry does not apply to a newer snooze's record`() {
        // The failure this guards, in both directions: a durable erase retry
        // outlives the snooze it was armed for, fires after the user has armed
        // a new one, and takes that snooze's cap with it while its zen rule
        // stays on — and a durable release retry ends that new snooze outright,
        // blaming a reboot that never happened.
        val newer = snooze().copy(startedAt = start.plusSeconds(60))

        assertFalse(ActiveSnooze.retryStillApplies(newer, start))
    }

    @Test
    fun `a retry applies when there is no record left to protect`() {
        assertTrue(ActiveSnooze.retryStillApplies(null, start))
    }

    @Test
    fun `an unidentified retry is not refused`() {
        // No caller can produce one, and refusing it would strand a released
        // record that a later cold start would restore into a live snooze.
        val snooze = snooze()

        assertTrue(ActiveSnooze.retryStillApplies(snooze, null))
    }

    @Test
    fun `a retry is not confused by a cap that moved`() {
        // startedAt is the identity precisely because the cap does not stay
        // put: `+30 min` moves it, and matching on it would let a retry queued
        // before an extension find "a different record" and give up.
        val snooze = snooze()
        val extended = snooze.copy(capExpiresAt = snooze.extendedCap(Duration.ofMinutes(30)))

        assertTrue(ActiveSnooze.retryStillApplies(extended, snooze.startedAt))
    }

    @Test
    fun `a retry is not confused by a snooze whose tracking degraded`() {
        // The other two fields that move under a live snooze. A release retry
        // is armed on the reboot path, where the mode and the anchor are the
        // most likely things to have changed by the time it fires — matching on
        // the whole record would drop a retry that is still the right one.
        val snooze = snooze()
        val degraded = snooze.copy(
            mode = TrackingMode.DURATION_ONLY,
            anchor = snooze.anchor.copy(ssid = null, lat = null, lon = null),
        )

        assertTrue(ActiveSnooze.retryStillApplies(degraded, snooze.startedAt))
    }

    // The duration cap is the backstop that holds when every sensor has failed,
    // so what follows walks each way the two clocks can disagree. Each case
    // states the true elapsed time independently of what either clock claims.

    @Test
    fun `winding the clock back mid-snooze does not extend the cap`() {
        // The failure this whole mechanism exists for, and it was reachable
        // from Settings in two taps: wind the date back and Do Not Disturb
        // stayed on past the backstop, indefinitely.
        val snooze = snooze()

        // Eight real hours pass, so the cap is due. The user has meanwhile set
        // the date back three hours, which is all wall time knows about.
        val movedBack = ClockReading(
            wallMillis = start.plus(Duration.ofHours(5)).toEpochMilli(),
            uptimeMillis = uptimeAtStart + Duration.ofHours(8).toMillis(),
        )

        assertEquals(Duration.ZERO, snooze.remaining(movedBack))
        assertTrue("a wound-back clock must not hold the phone quiet", snooze.isExpired(movedBack))
    }

    @Test
    fun `a small backwards shift is caught, not just an implausible one`() {
        // The case a "remaining time longer than MAX_CAP is a moved clock"
        // check cannot see. An hour is well under the ceiling, so the record
        // still looks entirely plausible — and an hour of unasked-for silence
        // is exactly the complaint the cap exists to prevent.
        val snooze = snooze()
        val shiftedByAnHour = ClockReading(
            wallMillis = start.plus(Duration.ofHours(7)).toEpochMilli(),
            uptimeMillis = uptimeAtStart + Duration.ofHours(8).toMillis(),
        )

        assertEquals(Duration.ZERO, snooze.remaining(shiftedByAnHour))
        assertTrue(snooze.isExpired(shiftedByAnHour))
    }

    @Test
    fun `winding the clock forward ends the snooze early`() {
        // The safe direction, and deliberately left alone: a snooze that ends
        // early is a small annoyance, one that never ends is the product
        // failing (principle 1). Only two real hours have passed here.
        val snooze = snooze()
        val movedForward = ClockReading(
            wallMillis = start.plus(Duration.ofHours(9)).toEpochMilli(),
            uptimeMillis = uptimeAtStart + Duration.ofHours(2).toMillis(),
        )

        assertTrue(snooze.isExpired(movedForward))
    }

    @Test
    fun `the cap survives a reboot, which resets the uptime clock`() {
        // Uptime cannot cross a boot, so the stored offset stops describing the
        // device and its answer becomes meaningless. It is also never *smaller*
        // than the truth — the uptime spent before the reboot is time it cannot
        // see — so wall time, which is exact here, wins the comparison.
        val snooze = snooze()

        // Three hours after arming, of which the phone spent two switched off.
        val afterReboot = ClockReading(
            wallMillis = start.plus(Duration.ofHours(3)).toEpochMilli(),
            uptimeMillis = Duration.ofMinutes(5).toMillis(),
        )

        assertEquals(Duration.ofHours(5), snooze.remaining(afterReboot))
        assertFalse("a reboot must not end a snooze that has time left", snooze.isExpired(afterReboot))
    }

    @Test
    fun `a reboot still lets the cap fire once it is due`() {
        val snooze = snooze()
        val afterReboot = ClockReading(
            wallMillis = start.plus(Duration.ofHours(8)).toEpochMilli(),
            uptimeMillis = Duration.ofMinutes(5).toMillis(),
        )

        assertTrue(snooze.isExpired(afterReboot))
    }

    @Test
    fun `a record with no stored offset falls back to the wall clock`() {
        // What a record written before the offset existed looks like. It gets
        // the behavior it was already relying on rather than a guessed offset,
        // which would be believed and could be wrong in either direction.
        val legacy = snooze().copy(bootReference = null)

        assertEquals(Duration.ofHours(3), legacy.remaining(at(start.plus(Duration.ofHours(5)))))
        assertTrue(legacy.isExpired(at(start.plus(Duration.ofHours(8)))))
    }

    @Test
    fun `an offsetless record whose deadline outruns the ceiling is treated as expired`() {
        // The only defense left for a record that cannot say which frame its
        // deadline was written in. No snooze can legitimately have more than
        // the maximum cap left, so a record claiming more is a clock that moved
        // while nothing was alive to notice — and how much real time actually
        // passed is unknowable. Fail open (SPEC.md D7).
        val legacy = ActiveSnooze(
            anchor = anchorWithFix,
            startedAt = start,
            capExpiresAt = start.plus(Duration.ofDays(30)),
            mode = TrackingMode.from(anchorWithFix),
            bootReference = null,
        )

        assertTrue(legacy.isExpired(at(start)))
    }

    @Test
    fun `a full-length snooze is not mistaken for a moved clock at arm`() {
        // The boundary the fail-open above must not swallow: a snooze armed for
        // the maximum cap has exactly MAX_CAP left on its first check.
        val snooze = snooze(cap = ActiveSnooze.MAX_CAP)

        assertFalse("a full-length snooze must not end at arm", snooze.isExpired(at(start)))
    }

    @Test
    fun `a reboot then a backwards clock change would outlast the cap unrebased`() {
        // The hole a minimum of two clocks does not close on its own: the reboot
        // invalidates the uptime answer, the backwards change inflates the wall
        // answer, and the smaller of two overestimates is still an overestimate.
        val snooze = snooze()

        // Rebooted 2 h in, then 6 h of new uptime — eight real hours, so the cap
        // is due — with the wall clock meanwhile set back three hours.
        val afterRebootAndShift = ClockReading(
            wallMillis = start.plus(Duration.ofHours(5)).toEpochMilli(),
            uptimeMillis = Duration.ofHours(6).toMillis(),
        )

        assertFalse(
            "this is the state the boot receiver must not leave behind",
            snooze.isExpired(afterRebootAndShift),
        )
    }

    @Test
    fun `restating the offset at boot makes the cap hold across a later shift`() {
        // What the boot receiver actually writes back, and the repair for the
        // case above: at boot the wall clock is the trustworthy frame, so
        // adopting the new boot's offset re-anchors the deadline to a clock the
        // user cannot wind back for the rest of this boot.
        val atBoot = ClockReading(
            wallMillis = start.plus(Duration.ofHours(2)).toEpochMilli(),
            uptimeMillis = Duration.ofMinutes(1).toMillis(),
        )
        val rebased = snooze().rebasedOnto(atBoot)

        // Six hours of uptime later the cap is due, and the wall clock has been
        // set back three hours in the meantime.
        val afterShift = ClockReading(
            wallMillis = start.plus(Duration.ofHours(5)).toEpochMilli(),
            uptimeMillis = Duration.ofHours(6).plusMinutes(1).toMillis(),
        )

        assertTrue("the cap must fire on real elapsed time", rebased.isExpired(afterShift))
    }

    @Test
    fun `restating the offset does not move the deadline`() {
        // It re-anchors the frame, nothing else. Recomputing the deadline from
        // the boot's wall reading would make an already-overdue cap look fresh.
        val atBoot = ClockReading(
            wallMillis = start.plus(Duration.ofHours(2)).toEpochMilli(),
            uptimeMillis = Duration.ofMinutes(1).toMillis(),
        )
        val snooze = snooze()
        val rebased = snooze.rebasedOnto(atBoot)

        assertEquals(snooze.capExpiresAt, rebased.capExpiresAt)
        assertEquals(Duration.ofHours(6), rebased.remaining(atBoot))
    }

    @Test
    fun `a cap already overdue at boot stays overdue after restating`() {
        val snooze = snooze()
        val atBoot = ClockReading(
            wallMillis = start.plus(Duration.ofHours(9)).toEpochMilli(),
            uptimeMillis = Duration.ofMinutes(1).toMillis(),
        )

        assertTrue(snooze.rebasedOnto(atBoot).isExpired(atBoot))
    }

    @Test
    fun `a backwards shift then a reboot would lose the cap without reconciling`() {
        // The mirror of the reboot-then-shift case, and it fails for the
        // opposite reason: after a backwards change only *uptime* knows how long
        // is really left, and uptime does not survive a boot. Left in memory,
        // the truth is destroyed by the reboot and the untouched wall deadline
        // is all the next boot finds.
        val snooze = snooze()

        // Two real hours in, clock set back three. Uptime is right, wall is not.
        val afterShift = ClockReading(
            wallMillis = start.minus(Duration.ofHours(1)).toEpochMilli(),
            uptimeMillis = uptimeAtStart + Duration.ofHours(2).toMillis(),
        )
        assertEquals(Duration.ofHours(6), snooze.remaining(afterShift))

        // Reboot a moment later. Rebasing alone adopts the inflated wall value.
        val atBoot = ClockReading(
            wallMillis = afterShift.wallMillis + 60_000,
            uptimeMillis = 60_000,
        )
        assertEquals(
            "rebasing alone cannot recover what the reboot destroyed",
            Duration.ofHours(9).minusMinutes(1),
            snooze.rebasedOnto(atBoot).remaining(atBoot),
        )

        // Reconciling at the clock change writes the truth into wall time first,
        // so the reboot has something correct to adopt.
        val reconciled = requireNotNull(snooze.reconciledOnto(afterShift))
        assertEquals(
            Duration.ofHours(6).minusMinutes(1),
            reconciled.rebasedOnto(atBoot).remaining(atBoot),
        )
    }

    @Test
    fun `reconciling leaves a forward shift where it already was`() {
        // Wall time is already the smaller of the two after a forward change, so
        // the recomputed deadline lands exactly where it was — no-op by
        // construction rather than by a special case.
        val snooze = snooze()
        val movedForward = ClockReading(
            wallMillis = start.plus(Duration.ofHours(3)).toEpochMilli(),
            uptimeMillis = uptimeAtStart + Duration.ofHours(1).toMillis(),
        )

        assertEquals(snooze.capExpiresAt, snooze.reconciledOnto(movedForward)?.capExpiresAt)
    }

    @Test
    fun `an offsetless record cannot be reconciled at all`() {
        // The trap this returns null for: with no second frame, the "remaining"
        // folded back in *is* the moved wall reading, so the write preserves the
        // error exactly and then stamps it with an offset it was never measured
        // in. The deadline that comes out looks framed and is not, which is
        // worse than one that admits it carries none.
        val legacy = snooze().copy(bootReference = null)
        val afterShift = ClockReading(
            wallMillis = start.minus(Duration.ofHours(1)).toEpochMilli(),
            uptimeMillis = uptimeAtStart + Duration.ofHours(2).toMillis(),
        )

        assertNull(legacy.reconciledOnto(afterShift))
    }

    @Test
    fun `extending cannot walk a reconciled snooze past the backstop`() {
        // Reconciling moves the deadline into the frame the user just set, but
        // `startedAt` stays where it is — it is the snooze's identity, and every
        // queued retry names it. So a ceiling derived from `startedAt` is left
        // behind in the old frame with the whole shift as slack, and `+30 min`
        // spends it: an eight-hour backstop reached three hours late, by the one
        // button it exists to bound.
        val snooze = snooze(Duration.ofHours(4))
        val elapsed = Duration.ofHours(2)
        val afterShift = ClockReading(
            wallMillis = start.plus(elapsed).minus(Duration.ofHours(3)).toEpochMilli(),
            uptimeMillis = uptimeAtStart + elapsed.toMillis(),
        )

        var reconciled = requireNotNull(snooze.reconciledOnto(afterShift))
        repeat(16) {
            reconciled = reconciled.copy(
                capExpiresAt = reconciled.extendedCap(Duration.ofMinutes(30)),
            )
        }

        assertEquals(
            "no number of taps may buy more than the eight-hour backstop",
            ActiveSnooze.DEFAULT_CAP,
            elapsed.plus(reconciled.remaining(afterShift)),
        )
    }

    @Test
    fun `reconciling an undisturbed clock changes nothing`() {
        // The common case: TIME_SET fires for a trivial correction and both
        // frames already agree, so nothing is written.
        val snooze = snooze()
        val undisturbed = at(start.plus(Duration.ofHours(2)))

        assertEquals(snooze, snooze.reconciledOnto(undisturbed))
    }
}
