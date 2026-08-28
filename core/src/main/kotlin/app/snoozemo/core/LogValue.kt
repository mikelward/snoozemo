package app.snoozemo.core

/**
 * Decides, per argument, what a line of the debug log may carry off the device.
 *
 * The rule is **default-safe**: a log call is a hard-coded format string plus
 * arguments, and an argument may leave only if its *type* says it cannot name
 * anything of the user's. The format string is a source literal, so it is safe
 * by construction. Everything this app knows about where someone is — an SSID,
 * a BSSID, a place name they typed — arrives as a [String], so every [String]
 * argument is withheld unless the call site says otherwise.
 *
 * Ported from Type Launcher, which arrived at it after a filter that worked the
 * other way round: that one matched tokens against values it had already seen
 * and redacted the hits, so it was correct only for the categories it had been
 * taught. Every review round found another it did not know about, which is the
 * shape of a rule that fails *open*. Inverting the default retires the class —
 * a call site added next year is safe without anyone remembering to teach a
 * filter about it.
 *
 * Nothing leaves the device today: `SnoozeDebugLog` registers a file sink and
 * logcat, and no mirror. This exists so that adding one is a change that cannot
 * quietly widen what is sent (`SPEC.md` §4.6, `AGENTS.md` *Privacy*). The
 * on-device log is unaffected and always renders every argument in full — it is
 * what the user reviews before sharing a report, and the distances, reasons and
 * states in it are what make a misfiring snooze diagnosable.
 */

/** Rendered in place of an argument that may not leave the device. */
internal const val REDACTED_PLACEHOLDER = "<redacted>"

/**
 * Marks a value that may be carried in full even though its type would
 * otherwise withhold it — a string that is genuinely fixed vocabulary rather
 * than anything of the user's (a state name, an end reason, a wake-up source).
 *
 * Reach for this only when the value cannot vary with who is holding the phone.
 * "It looks harmless" is not the test; "a different user would produce the same
 * value" is.
 */
@JvmInline
value class SafeLogValue(val value: Any?)

/**
 * Marks a value that must be withheld even though its type would allow it —
 * a coordinate as a [Double], say, where the type rule alone would let it
 * through.
 *
 * The counterpart to [SafeLogValue], and the reason the type rule is a default
 * rather than a verdict: safety is decided per value, not per category.
 */
@JvmInline
value class SensitiveLogValue(val value: Any?)

/**
 * A summary that has already decided, field by field, what may leave the device
 * — so a composite value is not forced to choose between going off device whole
 * and being withheld whole.
 *
 * A snooze summary is the case this exists for: its mode, times and the fix
 * accuracy in meters are what a report is read for, while the anchor's SSID and
 * place name must not leave. As a plain [String] the whole summary would be
 * withheld, and a failure nobody can diagnose is its own kind of loss.
 */
class LogSummary(
    /** Rendered on device, in full. */
    val full: String,
    /** Rendered off device, with the identifying fields removed. */
    val mirrored: String,
) {
    override fun toString(): String = full
}

/**
 * See [SafeLogValue]. Public rather than internal because the call sites are
 * in `:app` and this is `:core`.
 */
fun safe(value: Any?): SafeLogValue = SafeLogValue(value)

/** See [SensitiveLogValue]. */
fun sensitive(value: Any?): SensitiveLogValue = SensitiveLogValue(value)

/**
 * Whether [argument] may appear in full off the device.
 *
 * Numbers, booleans, chars and enum constants are safe: their whole range is
 * fixed by the code rather than by the device, so one user's value is another's.
 * A distance in meters and a fix accuracy are the cases that matter here — they
 * say whether the departure test worked, which is the diagnostic. Where a
 * number genuinely does identify someone, a coordinate above all, the call site
 * wraps it in [sensitive]; the default is not the verdict.
 *
 * Everything else is withheld. [String] is the case that matters and the reason
 * the default runs this way: it is the type an SSID, a BSSID and a typed place
 * name all arrive as.
 */
internal fun logArgumentMayLeaveDevice(argument: Any?): Boolean = when (argument) {
    is SafeLogValue -> true
    is SensitiveLogValue -> false
    // Carries both renderings and picks between them itself.
    is LogSummary -> true
    null -> true
    is Boolean -> true
    is Char -> true
    is Byte -> true
    is Short -> true
    is Int -> true
    is Long -> true
    is Float -> true
    is Double -> true
    is Enum<*> -> true
    else -> false
}

/** Unwraps a tag, if any, and renders the value the way the on-device log shows it. */
private fun renderLogArgument(argument: Any?, redactSensitive: Boolean): String = when (argument) {
    is SafeLogValue -> argument.value.toString()
    is SensitiveLogValue -> argument.value.toString()
    is LogSummary -> if (redactSensitive) argument.mirrored else argument.full
    else -> argument.toString()
}

/**
 * Substitutes [args] into [format], replacing each `%s` in order. `%%` renders a
 * literal `%`. When [redactSensitive] is set, any argument
 * [logArgumentMayLeaveDevice] withholds renders as [REDACTED_PLACEHOLDER]
 * instead — that is the only difference between the on-device rendering and the
 * mirrored one.
 *
 * Deliberately not `String.format`: this needs no locale (whose default would
 * be a live trap for `%d`), raises no `FormatException` from a stray `%` in a
 * message, and supports exactly the one placeholder the call sites use.
 *
 * A mismatch between placeholders and arguments is surfaced rather than
 * swallowed — a surplus `%s` is left in place and a surplus argument is
 * appended — so a wrong format string reads as obviously wrong in the log
 * instead of quietly dropping the value someone was trying to record. Surplus
 * arguments go through the same redaction as placed ones, so a mismatch can
 * never become a leak.
 */
internal fun formatLogMessage(
    format: String,
    args: Array<out Any?>,
    redactSensitive: Boolean,
): String {
    fun render(argument: Any?): String =
        if (redactSensitive && !logArgumentMayLeaveDevice(argument)) {
            REDACTED_PLACEHOLDER
        } else {
            renderLogArgument(argument, redactSensitive)
        }

    if (args.isEmpty() && '%' !in format) return format

    val out = StringBuilder(format.length + args.size * 8)
    var index = 0
    var next = 0
    while (index < format.length) {
        val char = format[index]
        if (char == '%' && index + 1 < format.length) {
            when (format[index + 1]) {
                's' -> {
                    if (next < args.size) out.append(render(args[next++])) else out.append("%s")
                    index += 2
                    continue
                }
                '%' -> {
                    out.append('%')
                    index += 2
                    continue
                }
            }
        }
        out.append(char)
        index++
    }
    while (next < args.size) {
        out.append(" [unplaced arg] ").append(render(args[next++]))
    }
    return out.toString()
}
