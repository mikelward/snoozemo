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

/**
 * The `play` flavor's backstop probe, as a no-op: this flavor watches no
 * location, so there is nothing for a resting fix to test. The backstop's
 * restore is still worth its wake here — it re-arms the cap and reconciles
 * policy access — which is why the caller pokes unconditionally.
 */
fun pokePresenceSanity() = Unit

/**
 * The `play` flavor's fence-repair poke, as a no-op: this flavor registers
 * no fence, so there is nothing to re-attempt.
 */
fun pokePresenceRepair() = Unit
