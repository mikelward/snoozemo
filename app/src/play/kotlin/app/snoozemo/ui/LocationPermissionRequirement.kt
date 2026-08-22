package app.snoozemo.ui

/**
 * Whether this flavor's location tracking needs `ACCESS_BACKGROUND_LOCATION`.
 *
 * `play`: yes — the Geofencing API needs the background grant to deliver an
 * exit while this flavor holds no foreground service (`SPEC.md` §3.4), and
 * this flavor's manifest declares the permission for exactly that reason.
 * The `direct` flavor's own copy of this file (`app/src/direct/...`) answers
 * `false`: its foreground service only ever needs while-in-use location, and
 * it declares no restricted permission at all (`DeclaredPermissionsTest`).
 *
 * A flavor source set, mirroring `:presence`'s `defaultPresenceMonitor` seam,
 * rather than a runtime check: asking a flavor that never declares the
 * permission to request it anyway gets an instant, silent denial with no
 * rationale ever offered, which left unguarded made the location settings
 * row's `ASKABLE` state permanent on `direct` (Codex, PR #79) — every
 * `Continue` re-requested a permission the platform will never grant.
 */
internal const val locationTrackingNeedsBackgroundPermission = true
