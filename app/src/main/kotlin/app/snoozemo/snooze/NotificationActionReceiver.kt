package app.snoozemo.snooze

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "NotificationAction"

/**
 * Notification actions that only change what is on screen, and touch no snooze
 * state at all.
 *
 * Today that is one action: `Dismiss` on the stuck-rule notification
 * ([SnoozeNotifications.showStuckRule]). That card is deliberately `ongoing`,
 * because it is the only thing pointing at a rule that may still be silencing
 * the phone and an accidental swipe would lose it — so the user needs a
 * *deliberate* way to say "I know, leave me alone", and this is it.
 *
 * A receiver rather than the trampoline, which every other notification action
 * goes through. The trampoline exists to start the service from a context
 * allowed to do so, and it costs an activity launch and a visible window. None
 * of that is warranted to cancel a notification: there is no service to start,
 * nothing to release, and no refusal worth recovering from. A broadcast to our
 * own explicit component does the whole job.
 *
 * Deliberately *not* a release. Dismiss means "stop telling me", not "turn Do
 * Not Disturb off" — the neighboring `Unsnooze` action is what does that, and
 * conflating them would end a snooze the user never asked to end.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SnoozeNotifications.ACTION_DISMISS_STUCK) return
        // Contained for the usual reason a receiver is: an escaping exception
        // here is an ANR-adjacent crash reported against a tap whose entire job
        // was to remove a notification. The failure mode of not cancelling is
        // that the card stays up, which is exactly the state the user was
        // already looking at.
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .cancel(SnoozeNotifications.ID_STUCK)
        }.onFailure {
            Log.e(TAG, "Dismissing the stuck-rule notification failed; it stays on screen.", it)
        }
    }
}
