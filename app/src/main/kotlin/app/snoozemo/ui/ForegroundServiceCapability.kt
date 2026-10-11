package app.snoozemo.ui

/**
 * Whether this flavor can hold a foreground service at all.
 *
 * `play`: yes — it declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`
 * and `android:foregroundServiceType="location"` on `SnoozeService`, because a
 * watched snooze needs the process to survive long enough to hear what the
 * geofence delivers (`SPEC.md` §6.10).
 *
 * **What reads it is `When I move` (`SPEC.md` §4.4.)** That exit needs the
 * process kept alive to hear a one-shot sensor, and on a build with no
 * foreground service the promotion is refused outright — so the row would arm
 * a watch the platform reclaims, over a card promising an exit that cannot
 * come. It used to be excluded here by accident, through the tracking-mode
 * gate that also kept it off duration-only snoozes; removing that gate is what
 * exposed the case and made this its own question (Codex, PR #252).
 *
 * A flavor source set rather than a runtime check, mirroring
 * [locationTrackingNeedsBackgroundPermission] and `:presence`'s
 * `defaultPresenceMonitor` seam: what a build declares is fixed when it is
 * built, and asking the platform at runtime would answer for the device rather
 * than for the manifest.
 */
internal const val buildHoldsForegroundService = true
