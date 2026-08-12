package app.snoozemo.snooze

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Asks the platform to re-read the tile after a state change, so the shade stops
 * showing `Snoozing` on a phone that isn't.
 */
object SnoozeTileBridge {
    fun refresh(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                // The running app's package, not the release application ID:
                // debug builds carry `.debug` (build.gradle.kts), so a hard-coded
                // name pointed at a component that isn't installed and every
                // refresh was silently a no-op — on exactly the build anyone
                // testing this would have on their phone. Class names are stable
                // under applicationIdSuffix; package names are not.
                ComponentName(context.packageName, "app.snoozemo.tile.SnoozeTileService"),
            )
        }.onFailure {
            // A stale tile is a cosmetic failure, not a silent phone — logged
            // rather than escalated, but never swallowed.
            Log.w("SnoozeTileBridge", "Requesting a tile refresh failed; the tile may be stale.", it)
        }
    }
}
