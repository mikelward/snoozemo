package app.snoozemo.presence

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The platform half of [MotionTrigger]: `TYPE_SIGNIFICANT_MOTION` through
 * `SensorManager.requestTriggerSensor`.
 *
 * No permission is involved — that is the point of using this sensor rather
 * than the accelerometer directly, which would need a foreground service to
 * sample in the background and would spend far more than the escalation is
 * worth (SPEC.md §6.7, §9).
 *
 * Callbacks are marshaled onto the main thread, the same confinement every
 * other source feeding the monitor's `deliver` uses. A cancel is idempotent
 * from this side: the handle drops its listener first, so a firing that
 * crosses a cancel in flight finds nothing to call.
 */
internal class PlatformMotionTrigger(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : TriggerRegistrar {

    private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)

    override fun arm(onFired: () -> Unit): AutoCloseable? {
        val manager = sensors ?: return null
        // Asked per arm rather than cached: the answer cannot change on a
        // running device, but a null manager already forced the null-check
        // above, and one more getter costs nothing next to the registration.
        val sensor = manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return null

        // Two flags, not one, because firing and canceling are different
        // facts about the same registration and a single flag conflated them
        // (Codex, PR #119). `onTrigger` arrives on the sensor's thread and
        // hands the callback to the main thread, so a cancel can land in
        // between; with one flag the firing had already cleared it, the cancel
        // returned early, and the queued callback ran anyway — delivering a
        // motion signal for a registration its owner had already given up on.
        // Atomics because the two threads really do both touch these.
        val fired = AtomicBoolean(false)
        val canceled = AtomicBoolean(false)
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                if (canceled.get() || !fired.compareAndSet(false, true)) return
                // Re-checked on the main thread, not only here: the cancel this
                // is racing may not have happened yet at this point.
                handler.post { if (!canceled.get()) onFired() }
            }
        }
        return if (manager.requestTriggerSensor(listener, sensor)) {
            AutoCloseable {
                if (!canceled.compareAndSet(false, true)) return@AutoCloseable
                // A trigger sensor disarms itself when it fires, so canceling
                // after that has nothing left to cancel. Setting the flag above
                // still matters — it is what suppresses the queued callback.
                if (!fired.get()) manager.cancelTriggerSensor(listener, sensor)
            }
        } else {
            // A refusal is not an exception here — the platform returns
            // false — so it is mapped to the same "no trigger available"
            // answer a missing sensor gives, which is the one [MotionTrigger]
            // records once and stops asking about.
            null
        }
    }
}
