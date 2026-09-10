package app.snoozemo.presence

import android.content.Context
import app.snoozemo.core.SnoozeDebugLog

/**
 * The watch behind `When I move` (SPEC.md §4.4): `TYPE_SIGNIFICANT_MOTION`
 * firing ends the snooze, for a snooze the user asked to end that way.
 *
 * **This is the one place motion is a verdict rather than a prompt**, and the
 * asymmetry is deliberate. [MotionTrigger]'s own use — the §6.7 duty cycle —
 * treats a firing as a reason to take a location fix, because motion is not
 * departure and standing up for coffee must not end an automatic snooze. Here
 * the user has said, for this snooze, that moving at all is what they mean by
 * leaving; the sensor's coarseness is the cost they accepted by choosing the
 * row. Nothing about the automatic path changes.
 *
 * **It needs the process resident.** Android delivers no one-shot sensor events
 * to a background app and names a foreground service as the remedy, which is
 * why this is only armed for a snooze whose
 * [app.snoozemo.core.TrackingMode.keepsProcessResident] holds one. Armed
 * anywhere else it would simply never fire, and a snooze would run to its cap
 * behind a promise nothing was keeping.
 *
 * **Whether it fires usefully is an open measurement, not a settled design**
 * (maintainer, 2026-09-10). The worry it ships with is the opposite of silence
 * — that the sensor is too eager, and ends a snooze when the user shifts in
 * their seat rather than when they walk out of the meeting room. That is the
 * annoying failure direction rather than principle 1's, which is what makes it
 * safe to try; `TODO.md` carries the on-device trial that decides whether it
 * stays. The [TriggerRegistrar] seam is what keeps a different signal source —
 * Play Services' activity-recognition transitions, say — a change of registrar
 * rather than a redesign, if the sensor turns out not to discriminate.
 *
 * Confined to one thread, the main thread in production, like [MotionTrigger]
 * itself.
 */
class MotionEndWatch(
    registrar: TriggerRegistrar,
    readElapsedRealtimeMs: () -> Long,
    private val onMoved: () -> Unit,
) : AutoCloseable {

    /**
     * Set by the first firing this watch reports, and never cleared.
     *
     * The snooze ends on that firing, so a second report could only be about a
     * snooze that is already over. [MotionTrigger] re-arms itself after every
     * firing by design — the duty cycle needs it to — and the ending it
     * triggers here is asynchronous, so the window between the callback and
     * [close] is real rather than theoretical: the user walks, the snooze
     * starts ending, they take another step. Without this the second step
     * reports a second ending, and an ending whose zen write was refused is
     * retried from a different reason than the one that started it.
     */
    private var reported = false

    private val trigger = MotionTrigger(
        registrar = registrar,
        readElapsedRealtimeMs = readElapsedRealtimeMs,
        onSignal = {
            if (!reported) {
                reported = true
                SnoozeDebugLog.event("significant motion: ending the snooze (when I move)")
                onMoved()
            }
        },
    )

    /**
     * Matches the platform to [needed]. Idempotent in both directions, like
     * [MotionTrigger.reconcile], so the service may restate it on every record
     * update rather than tracking the transitions itself.
     */
    fun reconcile(needed: Boolean) = trigger.reconcile(needed)

    /**
     * Whether the sensor is actually registered — the platform listening, not
     * merely having been asked.
     *
     * **The caller has to check this, and that is the point** (Codex, PR
     * #252). A device with no significant-motion sensor, or a platform that
     * refuses the registration, leaves this false while everything else looks
     * armed: the record would carry the choice and the ongoing card would
     * promise `or when you move` over an exit that can never fire. Silence is
     * survivable — the cap still bounds the snooze — but a *promise* of an
     * exit that cannot happen is principle 2's failure, so the choice is
     * rolled back rather than kept.
     *
     * Read after [reconcile], never instead of it: arming is what produces the
     * answer.
     */
    val listening: Boolean get() = trigger.listening

    override fun close() = trigger.close()
}

/**
 * The real watch, over `SensorManager`. Shaped like the other factories in this
 * module so the app layer never names a platform class — and in the shared
 * source set rather than a flavor's, because the sensor needs no Play Services
 * and no permission: what gates the feature is the foreground service, which
 * only `play` declares today, and the mode predicate already says so.
 */
fun motionEndWatch(context: Context, onMoved: () -> Unit): MotionEndWatch =
    MotionEndWatch(
        registrar = PlatformMotionTrigger(context),
        readElapsedRealtimeMs = android.os.SystemClock::elapsedRealtime,
        onMoved = onMoved,
    )

/**
 * Whether this device has a significant-motion sensor at all — a permanent
 * property of the hardware, so it is safe to ask once and keep.
 *
 * The screen asks so the `When I move` row is not offered on a phone that
 * could never honor it (SPEC.md §4.4). The service's own rollback still
 * stands behind it, because a *registration* can be refused at runtime on a
 * device that has the sensor; this is what keeps that backstop rare rather
 * than the ordinary path (Codex, PR #252).
 *
 * Needs no permission, and reads no sensor data — it asks the platform
 * whether a default sensor of that type exists and nothing more.
 */
fun deviceHasMotionSensor(context: Context): Boolean =
    context.applicationContext.getSystemService(android.hardware.SensorManager::class.java)
        ?.getDefaultSensor(android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION) != null
