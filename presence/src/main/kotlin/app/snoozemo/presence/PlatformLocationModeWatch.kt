package app.snoozemo.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import app.snoozemo.core.SnoozeDebugLog

/**
 * The platform half of [LocationModeWatch]: `LocationManager
 * .MODE_CHANGED_ACTION` through a context-registered receiver.
 *
 * No permission is involved — the broadcast says only whether the *device's*
 * location setting is on, never where anything is, which is also why it
 * carries nothing this app has to keep off SPEC.md §4.6's log.
 *
 * Registered `RECEIVER_NOT_EXPORTED`: this is a protected system broadcast,
 * so no other app can send it, and the flag says so rather than leaving the
 * receiver reachable by anything that guesses the action.
 *
 * Context-registered, and it has to be: `MODE_CHANGED_ACTION` is an implicit
 * broadcast and is not one of the exemptions a manifest receiver may still
 * take on API 26+, so there is no durable form of this to register. See
 * [LocationModeWatch] for what that costs on each flavor.
 *
 * Callbacks arrive on the main thread, the same confinement every other
 * source feeding the monitor's `deliver` uses.
 */
internal class PlatformLocationModeWatch(context: Context) : LocationModeRegistrar {

    private val appContext = context.applicationContext

    private val locations = appContext.getSystemService(LocationManager::class.java)

    /**
     * The live setting, for [LocationModeWatch]'s post-registration sample.
     * Null when there is no manager to ask — the same "cannot be watched"
     * answer [watch] gives, so neither path pretends to know.
     */
    override fun isEnabled(): Boolean? = locations?.isLocationEnabled

    override fun watch(onEnabled: () -> Unit): AutoCloseable? {
        val locations = locations ?: return null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != LocationManager.MODE_CHANGED_ACTION) return
                // Read live rather than taken from `EXTRA_LOCATION_ENABLED`.
                // The extra states the transition that was broadcast; the
                // read states what is true now, and "now" is the only thing
                // worth spending a registration and a fix request on. A
                // read that is fresher than the broadcast is the direction
                // that helps: it cannot claim location is on when it has
                // since gone off again.
                val enabled = runCatching { locations.isLocationEnabled }
                    .onFailure {
                        // Contained: the mode genuinely changed, so the poke
                        // this suppresses would probably have been the right
                        // one — but a repair on an unreadable subsystem is a
                        // guess, and the backstop still heals the outage.
                        SnoozeDebugLog.failure(it, "location-mode read refused; no repair poked")
                    }
                    .getOrDefault(false)
                // Fired only on the way *on*. The broadcast also carries
                // changes between location modes while it stays enabled,
                // and those say nothing about an outage ending.
                if (enabled) onEnabled()
            }
        }
        return try {
            appContext.registerReceiver(
                receiver,
                IntentFilter(LocationManager.MODE_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED,
            )
            AutoCloseable {
                runCatching { appContext.unregisterReceiver(receiver) }
                    .onFailure {
                        // Unregistering an already-gone receiver is a
                        // lifecycle wrinkle, not a leak the process can act
                        // on; logged, never swallowed.
                        SnoozeDebugLog.failure(it, "location-mode watch unregister failed")
                    }
            }
        } catch (e: RuntimeException) {
            // Mapped to the same "cannot be watched" answer a missing
            // manager gives, which is the one [LocationModeWatch] records
            // once and stops asking about.
            SnoozeDebugLog.failure(e, "location-mode receiver registration refused")
            null
        }
    }
}
