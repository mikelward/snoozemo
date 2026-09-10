package app.snoozemo.presence

import app.snoozemo.core.PresenceSignal

/**
 * How an anchor-Wi-Fi signal reads in the debug log, or `null` where it has no
 * line (SPEC.md §4.6).
 *
 * Pure and here rather than inline in [PlatformWifiWatch], because the *choice*
 * is the part worth pinning and the watch itself is a `ConnectivityManager`
 * callback no JVM test can reach. Four signals, four different things to say,
 * and each distinction was paid for:
 *
 * - **Loss says which kind of loss.** A loss is fail-open (`SPEC.md` D7), which
 *   makes "the anchor's network went away" and "nothing could answer, so assume
 *   it did" arrive as the same signal — so a bug report could not say which one
 *   ended a snooze until the two lines existed (Codex, PR #222). "Confirmed
 *   absent" rather than "seen to go": a seed read on a watch that starts with
 *   the phone already off Wi-Fi establishes an absence it never watched begin,
 *   and wording it as a transition would put a claim in the trace the code did
 *   not make.
 * - **Presence was silent, and that was the gap.** Only the losses were
 *   written, so a trace could see the D4 suppressor let go but never see it
 *   take hold — and a snooze that did *not* end while the phone sat on the
 *   anchor's network is a reading with no record of why.
 * - **The two present cases are not one.** Association is the suppressor with
 *   evidence behind it; the seed's unconfirmed form settles nothing and exists
 *   only to make a due grace deadline wait once for the callback that can name
 *   the network. Collapsing them would report a suppressor that is not holding.
 *
 * A boolean and no network name: the SSID never leaves [AnchorWifiTracker]
 * (`AGENTS.md`, *Privacy*).
 */
internal fun anchorWifiTraceLine(signal: PresenceSignal): String? = when {
    signal is PresenceSignal.AnchorWifiAssociated ->
        "anchor Wi-Fi present: association confirmed"

    signal is PresenceSignal.AnchorWifiPresentUnconfirmed ->
        "anchor Wi-Fi present: on some network, name still unread"

    signal is PresenceSignal.AnchorWifiLost && signal.observed ->
        "anchor Wi-Fi lost: absence confirmed"

    signal is PresenceSignal.AnchorWifiLost ->
        "anchor Wi-Fi lost: could not tell, failing open to gone"

    else -> null
}
