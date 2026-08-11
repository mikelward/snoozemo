package app.snoozemo

import android.app.Application

/**
 * Application entry point.
 *
 * This is where the warming of SPEC.md §4.1's arm path belongs — the zen rule
 * id, the settings, the last known SSID all read here rather than on the tile
 * tap, so arming never waits on disk. Phase 1 onward fills it in.
 */
class SnoozemoApplication : Application()
