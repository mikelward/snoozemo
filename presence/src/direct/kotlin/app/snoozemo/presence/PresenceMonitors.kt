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
 * Whether this build can ever end a snooze because the user left (SPEC.md §3.4).
 *
 * A capability of the *build*, deliberately, not of a running snooze. The
 * end-condition sheet (§4.4) has to decide whether to offer "until I leave" at
 * the instant the tile is tapped, and a fresh record always says `DURATION_ONLY`
 * — the real mode arrives later, when anchor capture completes. So the record
 * cannot answer this, and the flavor can: `direct` has no presence monitor at
 * all, so departure is not something it will ever report.
 *
 * A `play` build can still degrade to duration-only for an anchor it can't use
 * (§8.4); that is reported where it becomes known, on the ongoing notification.
 * This is the half that is knowable in time to decide what to draw.
 */
const val PRESENCE_TRACKS_DEPARTURE: Boolean = false

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

/**
 * The `play` flavor's grant-recheck poke, as a no-op: this flavor reads no
 * location, so a grant landing changes nothing it watches.
 */
fun pokePresenceGrantRecheck() = Unit
