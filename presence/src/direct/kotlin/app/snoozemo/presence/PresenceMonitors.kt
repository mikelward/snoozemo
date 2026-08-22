package app.snoozemo.presence

import android.content.Context
import app.snoozemo.core.PresenceMonitor

/**
 * The flavor seam's constructor (SPEC.md §3.4): each flavor source set
 * defines this same function, and callers above the seam never know which
 * monitor they got.
 *
 * The parameter is unused here and kept anyway — the two definitions must
 * share a signature or the seam stops being one.
 */
@Suppress("UNUSED_PARAMETER")
fun defaultPresenceMonitor(context: Context): PresenceMonitor =
    DurationOnlyPresenceMonitor()

/**
 * The `play` flavor's wake-up hook, as a no-op: this flavor registers no
 * geofence, so no observation can arrive with nobody to receive it. Exists
 * so the caller above the seam stays flavor-blind.
 */
fun installPresenceWakeup(onWake: () -> Unit) = Unit
