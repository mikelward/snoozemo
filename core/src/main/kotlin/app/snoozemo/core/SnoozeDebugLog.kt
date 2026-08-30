package app.snoozemo.core

import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.SafeLogValue
import com.mikelward.androidlog.safe

/**
 * The on-device debug log's recording half (SPEC.md §4.6): a bounded in-memory
 * buffer of coarse state and reasons, fanned out to sinks the app registers —
 * the persisted file, and a logcat mirror.
 *
 * Now [mikelward/androidlog](https://github.com/mikelward/androidlog), which
 * this app's own logger was one of the four sources for. The name and every
 * method stay put because the library's surface is a strict superset of what
 * was here, so no call site moves; what changes is that the bugs get fixed once
 * for four apps instead of once each, which the divergence between those four
 * copies had already shown does not happen.
 *
 * Kept in `:core` rather than `:app` for the reason it always was: the events
 * worth recording are the domain's — state transitions, end reasons, the
 * departure test's arithmetic — and the module split would otherwise force
 * every one of them through a callback just to be written down. The library's
 * `logging-core` is a plain Kotlin JVM module and `:core:verifyNoAndroid`
 * still passes with it on the classpath, so that boundary is unchanged.
 *
 * Every bound the previous implementation set — 300 entries, 2,000 chars per
 * entry, 6 stack frames per cause link, 5 links — is the library's default,
 * because they were taken from here. So this declares none of them.
 *
 * **The privacy floor is enforced by what this API accepts, not by scrubbing**
 * (AGENTS.md, *Privacy*; SPEC.md §4.6): never raw coordinates, never a full
 * SSID or BSSID, never a user-typed place name. There is no redactor because
 * nothing sanctions those values arriving at all — callers pass enum names,
 * booleans, distances and accuracies in meters, and times. The two places
 * data-shaped values could slip through are closed structurally: a throwable
 * renders as **types and stack frames only, never messages** (a platform
 * exception can quote what it was given, and the Wi-Fi and location stacks are
 * given exactly what the floor bans), and the one sanctioned way to render a
 * snooze is [logSummary], whose own test pins the floor.
 */
object SnoozeDebugLog : DebugLog()

/**
 * The one sanctioned way to put a snooze in the log.
 *
 * What it says: the end-condition mode, whether an SSID was captured, whether a
 * fix was, the fix's accuracy in meters, and the snooze's start and cap times.
 * What it never says: the SSID, the BSSID, the coordinates, or the place name.
 * Distance and accuracy answer "did the test fire correctly"; the position
 * answers "where do you live", which no bug report needs.
 *
 * The times are in, and deliberately (maintainer, 2026-08-30). They used to be
 * split off into a second rendering withheld from anything leaving the device,
 * on the reasoning that off device they say when someone was asleep or in a
 * cinema. That is retired: they are not sensitive, they are *necessary for
 * debugging* — a snooze that ended early or never ended cannot be diagnosed
 * from the mode and the anchor's shape alone — and this app has no automatic
 * off-device mirror at all, so the only way they leave is inside a report the
 * user chose to share.
 *
 * [safe] rather than a bare string because the floor withholds every untagged
 * `String` by default; this one is a summary the call site has already decided
 * the contents of, which is exactly what the tag is for.
 */
fun ActiveSnooze.logSummary(): SafeLogValue {
    val anchor = anchor
    val fix = if (anchor.lat != null && anchor.lon != null) {
        "fix accuracy=${anchor.fixAccuracyM ?: "unknown"}m"
    } else {
        "no fix"
    }
    val ssid = if (anchor.ssid != null) "ssid captured" else "no ssid"
    return safe("snooze(started=$startedAt capAt=$capExpiresAt mode=$mode anchor[$ssid, $fix])")
}
