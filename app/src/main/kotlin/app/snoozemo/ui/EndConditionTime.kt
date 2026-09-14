package app.snoozemo.ui

/**
 * [instant] as the user's own phone writes a time — their 12/24-hour setting,
 * their locale, their zone.
 *
 * Through `DateFormat.getTimeFormat` rather than a fixed pattern: an
 * end-condition row offering "14:00" to someone whose phone says "2:00 PM"
 * everywhere else reads as a different app's screen.
 *
 * **[rememberSheetTimeFormatter] is the form to use from a composable**, and
 * this one is for the callers that format once — a notification action, a test
 * fixture. A screen that keeps rows up for the length of a snooze re-formats
 * them on every minute tick, and `getTimeFormat` reads the 12/24-hour setting
 * each time it is asked (Codex, PR #234).
 */
internal fun formatSheetTime(context: android.content.Context, instant: java.time.Instant): String =
    android.text.format.DateFormat.getTimeFormat(context)
        .format(java.util.Date(instant.toEpochMilli()))

/**
 * [formatSheetTime] as a composable remembers it: the same rendering, cached so
 * a screen that shows a time on every minute tick does not re-read the format
 * each frame.
 */
@androidx.compose.runtime.Composable
internal fun rememberSheetTimeFormatter(): (java.time.Instant) -> String {
    val context = androidx.compose.ui.platform.LocalContext.current
    // **Keyed on everything `getTimeFormat` actually reads**, which took three
    // rounds to get right (Codex, PR #234) — the first version keyed on the
    // configuration alone and I wrote a comment claiming a 12/24-hour change
    // arrives as one. It does not: `Configuration` carries the locale, and
    // nothing else here.
    //
    // So all three, and each for a change the others miss: the configuration
    // for the locale, the zone because `getTimeFormat` bakes the default one
    // into the formatter it returns, and the 12/24-hour preference because it
    // lives in `Settings.System` and moves neither of the others.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // **And on the time zone, which the configuration does not carry** (Codex,
    // PR #234). `getTimeFormat` bakes the default zone into the formatter it
    // returns, and a zone change moves no `Configuration` field — so a cached
    // one goes on writing the old local time while the instant behind it is
    // unchanged. After travel that is a row labeled 14:00 that in fact keeps
    // the phone silent until 15:00. Read per composition, which is a static
    // field rather than the settings lookup the cache exists to avoid; the
    // screen's minute tick is what bounds how long a stale label can stand.
    val zone = java.util.TimeZone.getDefault().id
    // The one key that is not free: `Settings.System` is read per composition
    // rather than per row. That still leaves the saving the cache exists for —
    // the resource lookup and the formatter's own construction — and a key
    // that cannot see a change is not a cache, it is a stale value.
    val hours24 = android.text.format.DateFormat.is24HourFormat(context)
    val format = androidx.compose.runtime.remember(configuration, zone, hours24) {
        android.text.format.DateFormat.getTimeFormat(context)
    }
    return androidx.compose.runtime.remember(format) {
        { instant: java.time.Instant -> format.format(java.util.Date(instant.toEpochMilli())) }
    }
}
