package app.snoozemo.presence.geofence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import app.snoozemo.core.Fix
import app.snoozemo.core.SnoozeDebugLog

/** [BurstScheduler] over the main-thread handler — the production one. */
internal class AndroidBurstScheduler : BurstScheduler {

    private val handler = Handler(Looper.getMainLooper())

    override fun post(block: () -> Unit) {
        handler.post(block)
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit): AutoCloseable {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return AutoCloseable { handler.removeCallbacks(runnable) }
    }
}

/**
 * The real [FixRequester]: one `getCurrentLocation` through the platform
 * fused provider, classified into a [FixOutcome] by SPEC.md §6.1's
 * recoverable/fatal split. Called and calling back on the main thread.
 *
 * The permission check covers **both** halves of the play flavor's grant.
 * `ACCESS_FINE_LOCATION` going is the obvious loss; the background half going
 * — the user moving location access from "Allow all the time" to "While
 * using" — is the same loss wearing a subtler face, because this flavor's
 * entire watch runs from the background (SPEC.md §3.2): the geofence stops
 * delivering and these confirming requests stop answering, so treating it as
 * recoverable degradation would leave the snooze armed until its cap with
 * nothing watching (flagged by Codex on PR #72). Both are fatal.
 */
internal class PlatformFixRequester(context: Context) : FixRequester {

    private val appContext = context.applicationContext

    override fun request(onOutcome: (FixOutcome) -> Unit): AutoCloseable {
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ).firstOrNull {
            appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing != null) {
            // The grant was held when the snooze armed, so absent here means
            // revoked or downgraded mid-snooze. Never the permission's own
            // name beyond this coarse note — the log's floor (AGENTS.md,
            // *Privacy*) doesn't bar it, but the cause enum is what travels.
            SnoozeDebugLog.warning("checking fix: location grant gone; failing open")
            onOutcome(FixOutcome.PermissionLost)
            return NOTHING_TO_CANCEL
        }
        val lm = appContext.getSystemService(LocationManager::class.java)
        if (lm == null || !lm.isLocationEnabled) {
            SnoozeDebugLog.event("checking fix: location services off")
            onOutcome(FixOutcome.ServicesOff)
            return NOTHING_TO_CANCEL
        }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { lm.hasProvider(it) }
        if (provider == null) {
            SnoozeDebugLog.warning("checking fix: no usable location provider")
            onOutcome(FixOutcome.NothingRecoverable)
            return NOTHING_TO_CANCEL
        }

        val cancel = CancellationSignal()
        try {
            lm.getCurrentLocation(provider, cancel, appContext.mainExecutor) { location ->
                if (cancel.isCanceled) return@getCurrentLocation
                if (location == null || !location.hasAccuracy()) {
                    // No reading, or one §6.6 cannot gate — "never compare
                    // raw distance" makes an accuracy-less fix the same as
                    // none.
                    SnoozeDebugLog.event("checking fix: none available")
                    onOutcome(FixOutcome.NothingRecoverable)
                } else {
                    onOutcome(
                        FixOutcome.Delivered(
                            Fix(
                                lat = location.latitude,
                                lon = location.longitude,
                                accuracyM = location.accuracy,
                                elapsedRealtimeMs = location.elapsedRealtimeMillis,
                            ),
                        ),
                    )
                }
            }
        } catch (e: SecurityException) {
            // The grant went between the check and the call — the same fatal
            // outcome as the check above, just later.
            SnoozeDebugLog.warning("checking fix: request refused, permission gone", e)
            onOutcome(FixOutcome.PermissionLost)
            return NOTHING_TO_CANCEL
        } catch (e: RuntimeException) {
            SnoozeDebugLog.warning("checking fix: request failed", e)
            onOutcome(FixOutcome.NothingRecoverable)
            return NOTHING_TO_CANCEL
        }
        return AutoCloseable { cancel.cancel() }
    }

    private companion object {
        /** For requests answered before anything cancelable existed. */
        val NOTHING_TO_CANCEL = AutoCloseable { }
    }
}
