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
 * (AGENTS.md, *Privacy*; SPEC.md §4.6): this app never puts a raw coordinate, a
 * full SSID or BSSID, or a user-typed place name into the log. There is no
 * redactor because nothing sanctions those values arriving at all — callers pass
 * enum names, booleans, distances and accuracies in meters, and times, and the
 * one sanctioned way to render a snooze is [logSummary], whose own test pins it.
 *
 * **Two things that used to be true here are not, and both changed with the
 * shared logger's boundary rule** (androidlog 1.0.44; maintainer, 2026-08-31).
 * The floor moved from ingestion to the boundary: what this device keeps is
 * whole, and the reduction applies to a rendering that is *leaving*.
 *
 * - An untagged `String` is **no longer withheld** from the device's own copy.
 *   It renders in full here and as the placeholder only in
 *   `formatLogMessage(..., leavingDevice = true)`. [safe] therefore does not
 *   mean "keep this"; it means "carry this off the device too".
 * - A throwable renders **its message as well as its type and frames**. That
 *   was the second structural closure for the floor above, and losing it is
 *   deliberate: an exception can quote what it was given, and the Wi-Fi and
 *   location stacks are handed exactly what the floor keeps out, so it is
 *   possible in principle for one of those values to reach the log inside a
 *   message Snoozemo did not write — the framework's, the runtime's, or a
 *   bundled library's. Accepted rather than scrubbed — see SPEC.md §4.6,
 *   which records the trade and the reason a per-app opt-out was rejected.
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
 * [safe] rather than a bare string because this summary is one the call site has
 * already decided the contents of, so it is fit to leave the device as well as
 * to sit in the log — which is what the tag means since the boundary rule
 * (androidlog 1.0.44). An untagged `String` reaches this device's own log in
 * full either way; the tag governs the rendering that leaves.
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
