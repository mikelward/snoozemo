package app.snoozemo.presence

import android.content.Context
import app.snoozemo.core.PresenceMonitor
import app.snoozemo.presence.geofence.GeofencePresenceMonitor

import app.snoozemo.presence.geofence.GeofenceSignalBridge

/**
 * The flavor seam's constructor (SPEC.md §3.4): each flavor source set
 * defines this same function, and callers above the seam never know which
 * monitor they got.
 */
fun defaultPresenceMonitor(context: Context): PresenceMonitor =
    GeofencePresenceMonitor(context)

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
const val PRESENCE_TRACKS_DEPARTURE: Boolean = true

/**
 * Installs [onWake] to run when a presence observation arrives with no
 * monitor to receive it — a geofence exit restarting a dead process being
 * the case that matters (SPEC.md §8.1). The observation is held in the
 * bridge's mailbox; [onWake] should restore whatever owns the snooze, whose
 * restarted monitor then collects the held observation on attach. Installed
 * once, at process start, by the layer that can see the service — this
 * module cannot.
 */
fun installPresenceWakeup(onWake: () -> Unit) {
    GeofenceSignalBridge.installWakeup(onWake)
}

/**
 * The §6.10 backstop asking a *running* monitor for one resting fix — the
 * warm half of the periodic probe. A poke with no monitor attached is
 * dropped, deliberately: the backstop that pokes has also restored the
 * service, and the monitor that restore creates takes its own starting
 * probe, so nothing is lost and nothing is woken twice.
 */
fun pokePresenceSanity() {
    GeofenceSignalBridge.deliver(
        app.snoozemo.presence.geofence.GeofenceObservation.SanityPoke(
            android.os.SystemClock.elapsedRealtime(),
        ),
    )
}

/**
 * A warm wake asking the running monitor to re-attempt its fence
 * registration in place — repair that keeps the engine's failure memory,
 * where a monitor restart would forget it and let an unanswered probe
 * promote a broken snooze back to full tracking (Codex, PR #75). Dropped
 * with no monitor attached: a cold restore registers on its own way through.
 */
fun pokePresenceRepair() {
    GeofenceSignalBridge.deliver(
        app.snoozemo.presence.geofence.GeofenceObservation.RepairPoke(
            android.os.SystemClock.elapsedRealtime(),
        ),
    )
}

/**
 * The app layer reporting that a location grant just landed (SPEC.md §8.2)
 * — the permission dialog's result, or a trip to system Settings and back.
 * Android broadcasts no permission change, so this is the only prompt route
 * to a monitor holding a grant-shaped degradation; without it the repair
 * waited for the §6.10 backstop, up to half an hour with §6.6 grace shut.
 * Dropped with no monitor attached, like the other pokes: the service start
 * that carried it restores cold, and a restore re-registers the fence and
 * re-asks the grant on its own way through.
 */
fun pokePresenceGrantRecheck() {
    GeofenceSignalBridge.deliver(
        app.snoozemo.presence.geofence.GeofenceObservation.GrantPoke(
            android.os.SystemClock.elapsedRealtime(),
        ),
    )
}
