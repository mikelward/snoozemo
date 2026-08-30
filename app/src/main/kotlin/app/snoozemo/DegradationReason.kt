package app.snoozemo

import androidx.annotation.StringRes
import app.snoozemo.core.DegradationCause

/**
 * The user-facing reason behind a degraded mode, or null where there is
 * nothing worth saying (TODO.md; Codex, PR #31). Copy approved by the
 * maintainer, 2026-08-30.
 *
 * Shared rather than per-surface: the ongoing notification and the main
 * screen say the same thing about the same snooze, so the mapping from cause
 * to words lives in one place. A second copy is how the two drift into
 * disagreeing about a phone the user is holding.
 *
 * `NOTHING_WATCHING` is the one cause that still earns no line, and that is
 * deliberate rather than unfinished: it is the app's own wiring, not anything
 * the user did or can act on, so `Timer only` already says everything true
 * about it.
 *
 * `LOCATION_PERMISSION_GONE` and `NO_LOCATION_IN_BACKGROUND` both name a
 * missing grant, and stay separate because they need different things from
 * the user: grant location at all, versus grant it in the background.
 *
 * `NO_LOCATION_IN_BACKGROUND` was held back until 2026-08-30 — naming a state
 * whose way out no UI offers seemed worse than the mode alone — and the
 * maintainer reversed it, on the ground that naming the missing *permission*
 * is itself most of the way out: a user told `background location off` knows
 * what to grant, where `Timer only` alone tells them nothing.
 *
 * That reversal shipped with a caveat that no longer applies, and the caveat's
 * own premise turned out to be wrong twice over. It said granting the
 * permission would not restart tracking until a `Resume tracking` action
 * existed. `SPEC.md` §8.1 now records why that action cannot work at all — a
 * notification-action tap buys location fixes, not a geofence, which needs
 * `ACCESS_BACKGROUND_LOCATION` outright on API 29+ — and the grant returning
 * *does* restart tracking now, through the registration recovery path, with no
 * affordance to tap. Nothing here is owed.
 */
@StringRes
fun degradationReasonRes(cause: DegradationCause?): Int? = when (cause) {
    DegradationCause.LOCATION_SERVICES_OFF -> R.string.ongoing_cause_services_off
    // The distinction this whole line exists for: location is broken, versus
    // location works but cannot place you where you are standing.
    DegradationCause.NO_LOCATION_FIX -> R.string.ongoing_cause_no_fix
    DegradationCause.FIXES_TOO_VAGUE -> R.string.ongoing_cause_weak_signal
    // Names the permission rather than the symptom, because the permission is
    // the thing the user can act on.
    DegradationCause.NO_LOCATION_IN_BACKGROUND -> R.string.ongoing_cause_no_background
    // Names the grant rather than the symptom, like the line above it.
    DegradationCause.LOCATION_PERMISSION_GONE -> R.string.ongoing_cause_permission_gone
    DegradationCause.NOTHING_WATCHING,
    null,
    -> null
}
