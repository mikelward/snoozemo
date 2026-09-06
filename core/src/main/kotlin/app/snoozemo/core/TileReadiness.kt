package app.snoozemo.core

/**
 * Whether a tile tap should open the app instead of silently doing nothing
 * (`SPEC.md` §4.1).
 *
 * The tile is reachable long before — and long after — the welcome flow: it can
 * be added from `MainScreen`'s banner, from `SettingsScreen`, or by an install
 * that predates the flow entirely, and a permission granted once can be revoked
 * in system settings or auto-revoked by Android for an unused app. So ordering
 * the flow's cards is not enough on its own, and this is the check that holds
 * the line wherever the tile came from.
 *
 * **Required means the app can do nothing at all, not that it is degraded.**
 * Two capabilities qualify, and only two:
 * - **Do Not Disturb access**, without which there is no rule to turn on, so
 *   the tap produces no snooze by any route.
 * - **Notifications**, which is where every report the app has goes: the
 *   degraded-mode line, the ended-snooze card, `Couldn't snooze`. Without them
 *   a failure is not just a failure, it is a silent one.
 *
 * Everything else degrades and says so, which is the opposite of this: no
 * location falls back to duration-only and reports it in the ongoing
 * notification, and no calendar drops the calendar action (§4.3). Those are
 * working states, and sending the user to a screen about them would be nagging
 * rather than explaining.
 *
 * **Never a reason to refuse the arm.** The caller runs this *after* starting
 * the service, so §4.1's "arming must never feel slow or refuse" is untouched:
 * the snooze is attempted exactly as before and this only decides whether the
 * user is also shown why it could not work.
 *
 * **An unread capability is not a missing one**, the same exclusion
 * `welcomeExitNeedsRecap` makes: a null is a reading that has not landed, and
 * routing on one would open the app over a question nothing has asked yet.
 *
 * **Nor is one a runtime prompt can still fix.** [NotificationPermission.ASKABLE]
 * is deliberately not a reason to route: the caller already answers it with the
 * system dialog, which is one tap where a screen is a detour. Only
 * [NotificationPermission.BLOCKED], where the platform ignores further
 * requests, leaves the app's settings as the single live route — and that is
 * what this gate is for.
 *
 * A pure function rather than a method on the activity, for the reason its
 * sibling gives: this decides between explaining a dead tap and interrupting a
 * working one, and it belongs where a JVM test can enumerate its cases.
 *
 * @param activeChannelEnabled whether the channel a running snooze reports on
 *   is still enabled. Granted is necessary and not sufficient: the permission
 *   can be held while the user switches that channel off in system settings,
 *   and the system then drops the post. Narrower than
 *   `SnoozeNotifications.canReachTheUser()`'s three-channel aggregate on
 *   purpose — a silenced `snooze_ended` channel does not make a *running*
 *   snooze invisible, so it is not this gate's business (maintainer,
 *   2026-09-06).
 */
fun tileTapNeedsSetup(
    access: PolicyAccess?,
    notifications: NotificationPermission?,
    activeChannelEnabled: Boolean?,
): Boolean {
    val accessMissing = access?.let { it != PolicyAccess.GRANTED } == true
    val notificationsMissing = notifications == NotificationPermission.BLOCKED ||
        activeChannelEnabled == false
    return accessMissing || notificationsMissing
}
