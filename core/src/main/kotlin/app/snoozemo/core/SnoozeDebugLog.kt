package app.snoozemo.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * The on-device debug log's recording half (SPEC.md §4.6): a bounded in-memory
 * buffer of coarse state and reasons, fanned out to sinks the app registers —
 * the persisted file, and a logcat mirror.
 *
 * In `:core` rather than `:app` because the events worth recording are the
 * domain's — state transitions, end reasons, the departure test's arithmetic —
 * and the module split would otherwise force every one of them through a
 * callback just to be written down. Nothing here touches Android: sinks are
 * where the platform comes in.
 *
 * Recording never throws and never blocks: entries are built and appended in
 * memory, and sinks are isolated and expected to enqueue. That is what makes it
 * safe to call from the arm path — the one stretch of this app nothing may sit
 * in — and from inside failure handling, where a second failure must not
 * preempt the first one's recovery.
 *
 * **The privacy floor is enforced by what this API accepts, not by scrubbing**
 * (AGENTS.md, *Privacy*; SPEC.md §4.6): never raw coordinates, never a full
 * SSID or BSSID, never a user-typed place name. There is no redactor here
 * because nothing sanctions those values arriving at all — callers pass enum
 * names, booleans, distances and accuracies in meters, and times. The two
 * places data-shaped values could slip through are closed structurally: a
 * throwable renders as **types and stack frames only, never messages** (a
 * platform exception can quote what it was given, and the Wi-Fi and location
 * stacks are given exactly what the floor bans), and the one sanctioned way to
 * render a snooze is [logSummary], whose own test pins the floor.
 */
object SnoozeDebugLog {

    /** Bounds the buffer; old entries fall off the front. */
    internal const val MAX_ENTRIES = 300

    /** Bounds one entry, so a pathological trace can't dominate the buffer. */
    internal const val MAX_ENTRY_CHARS = 2_000

    /** How many stack frames each link of a cause chain keeps. */
    internal const val MAX_TRACE_FRAMES = 6

    /** How many cause links a chain renders before eliding the rest. */
    private const val MAX_CAUSE_LINKS = 5

    private val buffer = ArrayDeque<String>(MAX_ENTRIES)

    /**
     * Where each entry is mirrored once it is safely in the buffer. Registered
     * from `:app` (the persisted file, logcat); empty in plain JVM tests. Each
     * call is isolated, so one sink's failure can't reach another or the caller.
     */
    private val sinks = java.util.concurrent.CopyOnWriteArrayList<Sink>()

    fun interface Sink {
        fun log(line: String)
    }

    /**
     * Whether anything is recorded at all — the user's setting, applied here
     * rather than only at the file sink, because "off" has to mean off
     * (SPEC.md §4.6, flagged by Codex on PR #62): gating only the file left
     * the buffer collecting through the disabled period and the logcat mirror
     * still writing, and a later re-enable would have persisted everything
     * captured while the user thought nothing was. One gate here covers every
     * sink, since sinks only ever see what recording hands them.
     */
    @Volatile
    private var recording: Boolean = true

    /**
     * Applies the setting. Disabling also **empties the buffer**: entries
     * already collected are exactly what a re-enable in the same process
     * would otherwise write to disk.
     *
     * The flip and the clear share the buffer's lock with the append, so a
     * recorder that passed the cheap unlocked check cannot slip its entry in
     * *after* the clear — the one-entry leak that would otherwise survive
     * into a later re-enable (Codex, PR #62).
     */
    fun setRecording(enabled: Boolean) {
        synchronized(buffer) {
            recording = enabled
            if (!enabled) buffer.clear()
        }
    }

    /**
     * Whether entries are currently being recorded.
     *
     * For callers deciding whether to do the *work* that would produce an
     * entry, not merely whether the entry would be kept. Gathering diagnostics
     * the gate will discard is wasted effort on a path that neighbours the arm
     * path, and on an opted-out install it means the app still collected what
     * the user asked it not to — even if nothing was written (Codex, PR #125).
     */
    val isRecording: Boolean
        get() = recording

    fun addSink(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    /**
     * Wall-clock time for entries, injectable so tests pin the format. Real
     * timestamps are a maintainer decision (SPEC.md §4.6): an inexact alarm
     * landing outside a Doze window cannot be reconstructed from intervals,
     * and a user reports what their own clock said, so entries are written in
     * *local* time.
     *
     * The offset stays on every entry: a line is read in isolation as often as
     * in sequence, and one that cannot place itself is worth less than the
     * characters it saves. The year is dropped, since the log spans two runs.
     */
    @Volatile
    internal var readMillis: () -> Long = System::currentTimeMillis

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSSXX", Locale.US)

    /**
     * Records one line.
     *
     * [format] is a hard-coded format string — a source literal, never a built
     * value — with one `%s` per argument. That split is what makes the log
     * safe to mirror off the device later: the literal cannot name anything of
     * the user's, and each argument is carried or withheld on its own by
     * [logArgumentMayLeaveDevice]. The on-device line always renders every
     * argument in full.
     *
     * Interpolating into [format] defeats it. The type system cannot enforce
     * that, so it is a rule: pass values as arguments.
     */
    fun event(format: String, vararg args: Any?) = record('D', format, args, throwable = null)

    /**
     * A warning with no exception behind it. Same contract as [event].
     *
     * A warning that *does* have an exception goes to [failure]. The throwable
     * is a separate parameter on a separately-named function on purpose: an
     * overload taking it alongside `vararg args` would silently bind it as a
     * formatting argument, so it would render into the text and be findable
     * only in production.
     */
    fun warning(format: String, vararg args: Any?) {
        val throwable = args.filterIsInstance<Throwable>().firstOrNull()
        if (throwable != null) {
            // Rerouted *without* it in the arguments. Left there it has no
            // `%s` to fill, so it renders through `toString()` — restoring the
            // exception message [typesAndFrames] exists to exclude, which a
            // platform exception can fill with an SSID (Codex, PR #129).
            failure(
                throwable,
                "$format [throwable passed to warning(); use failure()]",
                *args.filterNot { it === throwable }.toTypedArray(),
            )
            return
        }
        record('W', format, args, throwable = null)
    }

    /** A warning carrying the exception behind it. Same contract as [event]. */
    fun failure(throwable: Throwable, format: String, vararg args: Any?) =
        record('W', format, args, throwable)

    /** The captured lines, oldest first. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    /**
     * Test-only: empties the buffer so tests start from a known state. Public
     * rather than internal because the sink tests live in `:app`, which is a
     * different module; nothing in production calls it.
     */
    fun clearForTest() {
        recording = true
        synchronized(buffer) { buffer.clear() }
    }

    /** Test-only: detaches all mirrors so tests don't leak a sink into each other. */
    fun clearSinksForTest() {
        sinks.clear()
    }

    /**
     * Renders and records one entry.
     *
     * [format] and [args] are kept apart to here rather than formatted at the
     * call site, so a redacted rendering is derivable when there is something
     * to derive it for. Nothing is: every sink is on-device, and this builds
     * only the full entry.
     */
    private fun record(level: Char, format: String, args: Array<out Any?>, throwable: Throwable?) {
        // The unlocked check is the cheap common case; the locked one below is
        // the correct one. Both exist so a disabled log costs nothing on the
        // arm path while a toggle racing a recorder still cannot land an
        // entry after the disable's clear.
        if (!recording) return
        // Contained whole rather than per step: recording runs on the arm path
        // and inside failure handling, so nothing it does may escape — a lost
        // log line is the accepted cost, a lost snooze is not.
        val entry = runCatching {
            render(level, formatLogMessage(format, args, redactSensitive = false), throwable)
        }.getOrElse { return }
        synchronized(buffer) {
            if (!recording) return
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
        }
        sinks.forEach { runCatching { it.log(entry) } }
    }

    private fun render(level: Char, message: String, throwable: Throwable?): String {
        val timestamp = runCatching {
            TIMESTAMP.format(Instant.ofEpochMilli(readMillis()).atZone(ZoneId.systemDefault()))
        }.getOrElse { "(no timestamp)" }
        val entry = if (throwable == null) {
            "$timestamp $level $message"
        } else {
            "$timestamp $level $message\n${throwable.typesAndFrames().trimEnd()}"
        }
        return if (entry.length > MAX_ENTRY_CHARS) {
            entry.take(MAX_ENTRY_CHARS) + "…(truncated)"
        } else {
            entry
        }
    }

    /**
     * A cause chain as **types and frames only** — deliberately never
     * `getMessage()`, from any link. A platform exception quotes what it was
     * given, and on the paths this log exists for that can be the SSID or the
     * location the floor bans; there is no scrubber here to catch it, so the
     * message is not read at all. The type names the failure and the frames
     * locate it, which is what a bug report needs.
     *
     * Guarded against a cyclic cause chain, and bounded per link and per chain
     * so one throw can't consume the whole entry budget.
     */
    private fun Throwable.typesAndFrames(): String = buildString {
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var prefix = ""
        var links = 0
        var t: Throwable? = this@typesAndFrames
        while (true) {
            val cur = t ?: break
            if (!seen.add(cur)) break
            if (links++ >= MAX_CAUSE_LINKS) {
                appendLine("\t... more causes elided")
                break
            }
            appendLine(prefix + cur.javaClass.name)
            val frames = cur.stackTrace
            val keep = minOf(MAX_TRACE_FRAMES, frames.size)
            for (i in 0 until keep) appendLine("\tat ${frames[i]}")
            if (frames.size > keep) appendLine("\t... ${frames.size - keep} more")
            t = runCatching { cur.cause }.getOrNull()
            prefix = "Caused by: "
        }
    }
}

/**
 * The one sanctioned rendering of a snooze for the debug log, and the place the
 * privacy floor is pinned by a test of its own (SPEC.md §4.6).
 *
 * What it says: the snooze's times (sanctioned — times are the diagnostic),
 * its tracking mode, and what the anchor *has* — whether an SSID was captured,
 * whether a fix was, and the fix's accuracy in meters. What it never says: the
 * SSID, the BSSID, the coordinates, or the place name. Distance and accuracy
 * answer "did the test fire correctly"; the position answers "where do you
 * live", which no bug report needs.
 */
fun ActiveSnooze.logSummary(): LogSummary {
    val anchor = anchor
    val fix = if (anchor.lat != null && anchor.lon != null) {
        "fix accuracy=${anchor.fixAccuracyM ?: "unknown"}m"
    } else {
        "no fix"
    }
    val ssid = if (anchor.ssid != null) "ssid captured" else "no ssid"
    val shape = "mode=$mode anchor[$ssid, $fix]"
    // A [LogSummary] rather than a String, so the composite is not forced to
    // choose between going off device whole and being withheld whole. The
    // times are sanctioned on device and are the diagnostic there; off device
    // they are when someone was asleep or in a cinema, which AGENTS.md's
    // *Privacy* rule names as the user's, so only the shape travels.
    return LogSummary(
        full = "snooze(started=$startedAt capAt=$capExpiresAt $shape)",
        mirrored = "snooze($shape)",
    )
}
