package app.snoozemo.crash

import android.content.Context

/**
 * The `direct` flavor's copy of the `play` flavor's crash reporter — see that
 * file for the full explanation. This flavor holds no Play Services
 * dependency and declares no `INTERNET` permission (`SPEC.md` §3.4), so it
 * cannot open a network connection at all and there is nothing here to report
 * to: [isAvailable] is always false, Settings offers no switch, and the
 * on-device debug log stays this build's only diagnostic.
 *
 * That asymmetry is deliberate rather than an omission. `direct` exists for
 * sideloading and F-Droid, where a proprietary reporter could not ship
 * anyway — and "this build cannot send anything anywhere" is a claim worth
 * keeping literally true of one of the two flavors.
 */
internal object CrashReporter {

    fun isAvailable(context: Context): Boolean = false

    fun apply(context: Context, enabled: Boolean): ReporterOutcome = ReporterOutcome.NO_REPORTER
}
