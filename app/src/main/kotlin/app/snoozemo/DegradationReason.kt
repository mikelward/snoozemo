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
 * Only three causes earn a line, and the omissions are deliberate rather than
 * unfinished:
 * - `NO_LOCATION_IN_BACKGROUND` recovers by an action the user has to take,
 *   and no UI offers it yet — naming the state without the way out would be
 *   worse than the mode alone. It gets its line with that affordance, not
 *   before.
 * - `NOTHING_WATCHING` is the app's own wiring, not anything the user did or
 *   can act on; `Timer only` already says everything true about it.
 */
@StringRes
fun degradationReasonRes(cause: DegradationCause?): Int? = when (cause) {
    DegradationCause.LOCATION_SERVICES_OFF -> R.string.ongoing_cause_services_off
    // The distinction this whole line exists for: location is broken, versus
    // location works but cannot place you where you are standing.
    DegradationCause.NO_LOCATION_FIX -> R.string.ongoing_cause_no_fix
    DegradationCause.FIXES_TOO_VAGUE -> R.string.ongoing_cause_weak_signal
    DegradationCause.NO_LOCATION_IN_BACKGROUND,
    DegradationCause.NOTHING_WATCHING,
    null,
    -> null
}
