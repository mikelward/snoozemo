package app.snoozemo.presence

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import app.snoozemo.core.SnoozeDebugLog
import com.mikelward.androidlog.safe
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One reading of how the phone is lying: gravity along the screen's axis,
 * and whether the proximity sensor is covered. Null means the phone has no
 * such sensor.
 */
data class PostureSample(val gravityZ: Float?, val proximityNear: Boolean?) {

    /**
     * Whether either half answered. A reading with neither is no reading —
     * the deadline says so instead — but one half alone is still worth a
     * line: a proximity sensor that never reports on some hardware must not
     * cost the gravity reading beside it (Codex, PR #258).
     */
    val hasAny: Boolean get() = gravityZ != null || proximityNear != null

    /**
     * The fixed vocabulary the log carries — a posture and a proximity state,
     * never the raw numbers. Gravity is ±9.8 m/s² along the axis that points
     * out of the screen, so a phone lying flat reads near one end or the
     * other and anything held or propped reads in between; the band is wide
     * enough that a table with a slight tilt still reads as flat.
     */
    fun describe(): String {
        val posture = when {
            gravityZ == null -> "no gravity reading"
            gravityZ >= FLAT_THRESHOLD -> "face up"
            gravityZ <= -FLAT_THRESHOLD -> "face down"
            else -> "on edge"
        }
        val proximity = when (proximityNear) {
            null -> "no proximity reading"
            true -> "sensor covered"
            false -> "sensor clear"
        }
        return "$posture, $proximity"
    }

    companion object {
        /** m/s² along z, beyond which the phone counts as lying flat. */
        const val FLAT_THRESHOLD = 7f
    }
}

/**
 * The platform half of [PostureTrace], shaped like [TriggerRegistrar] so a
 * JVM test can be the sensors: [PlatformPostureSensors] is the real one.
 */
interface PostureSensors {
    /**
     * Takes one reading and hands it to [onSample], then stops listening.
     * Called back exactly once: with the reading — whatever had answered by
     * a short deadline, so one silent sensor does not cost the other's
     * value — or with null when nothing at all had, which is what a
     * background process sees, since Android withholds sensor events from
     * one. A null
     * *return* means nothing on this phone can answer at all. Closing the
     * handle drops a reading still in flight; the trace never does, but a
     * test may.
     */
    fun sample(onSample: (PostureSample?) -> Unit): AutoCloseable?

    /**
     * Arms one firing of the pick-up gesture. Null means the phone has no
     * such sensor. The platform disarms the sensor when it fires, like
     * significant motion, so the caller re-arms after each firing.
     */
    fun watchPickUp(onFired: () -> Unit): AutoCloseable?
}

/**
 * Records the phone's posture at each step a snooze moves through, and every
 * pick-up gesture while one runs — into the debug log, and nowhere else.
 *
 * **A measurement, not a feature** (maintainer, 2026-09-11). The question it
 * exists to answer is whether flipping the phone face down, or picking it
 * back up, tracks the user's actual snooze boundaries well enough to be a way
 * to start or end one. Nothing here acts on a reading: a pick-up that fires
 * ends nothing, a face-down phone arms nothing. `TODO.md` carries the trial
 * that decides whether either becomes a control, and this comes out with it
 * either way.
 *
 * **Sampled, never streamed.** One gravity and proximity reading per state
 * transition — a registration that lasts one event — and the pick-up gesture
 * as a one-shot the hardware batches, so a snooze pays nothing while the
 * phone sits still (SPEC.md §9). A continuous accelerometer listener across an
 * eight-hour snooze was the alternative and is exactly what this avoids.
 *
 * **Never on the arm path.** The service samples on every transition except
 * `ARMING`, which sits between the tap and the rule going on; the first
 * reading of a snooze is taken at `ARMED`, once the phone is already quiet.
 *
 * **A reading arrives only while the process is foreground.** Android
 * withholds sensor events — continuous, on-change and one-shot alike — from
 * a background app, so the readings this collects come from a snooze whose
 * foreground service holds the process (a tracked one, on `play`) and from
 * transitions the screen is open for. Everywhere else the platform half
 * gives up after a short deadline and the trace says `no reading arrived`,
 * which is a measurement too: it says which transitions the trial cannot
 * see, rather than leaving a gap that reads like a sensor that never fired.
 * A reading is never taken later and attributed to an earlier transition.
 *
 * Confined to one thread, the main thread in production, like
 * [MotionEndWatch].
 */
class PostureTrace(private val sensors: PostureSensors) : AutoCloseable {

    /**
     * The last reading that landed with anything in it, and the moment it
     * was taken at. An ending away from the screen mostly cannot be read —
     * the ended card's post gives the foreground service back in the same
     * pass, and the read's events land after that — so the ending's line
     * carries the last posture the process *could* see, which for a phone
     * lying still is the posture it ended in (Codex, PR #258). Holding the
     * service until the read settles would cross SPEC.md §3.4's line, a
     * location service for a snooze doing no location work, and is not
     * this trace's call to make; `TODO.md` carries it.
     */
    private var lastSeen: Pair<String, PostureSample>? = null

    /**
     * Which reading [lastSeen] came from, in the order readings were asked
     * for. Two readings can be in flight at once — a transition's and a
     * pick-up's beside it — and land in either order, so "last seen" is the
     * newest *asked*, not the newest *landed*: an older one landing late must
     * not move it backward (Codex, PR #258).
     */
    private var lastSeenOrder = 0L

    /** [SnoozeDebugLog.erasures] as of the last time this trace touched the log. */
    private var logErasures = SnoozeDebugLog.erasures

    /**
     * Everything this trace remembers *about the log* is the log's memory,
     * not the trace's (Codex, PR #258, twice — first the last-seen posture,
     * then the explanations): which lines it has taken, and the last posture
     * it took. Off empties the log, so on the first touch after an erasure
     * the trace forgets with it — the explanations are said again, since a
     * fresh log with no `no sensor` line reads an absent sensor as a silent
     * week, and a last-seen posture is gone, since surfacing it would be
     * data the user asked the app to delete. The permanent answers
     * ([noSensors], [noPickUp]) stay: they are about the phone, and gate
     * only whether the platform is asked again. Called on every entry, so
     * a toggle between two touches is caught at the next one; a disable
     * landing between this check and the line going in is caught by the
     * line's own refusal, since nothing is kept from a line the log did
     * not take.
     */
    private fun followLog() {
        val now = SnoozeDebugLog.erasures
        if (now == logErasures) return
        logErasures = now
        noSensorsSaid = false
        noPickUpSaid = false
        unobservableSaid = false
        lastSeen = null
        lastSeenOrder = 0L
    }
    private var readOrder = 0L

    private var pickUp: AutoCloseable? = null

    /** Which pick-up registration the next firing may belong to; see [MotionTrigger]. */
    private var generation = 0L

    private var pickUpWanted = false

    /**
     * The phone has none of the sensors / no pick-up gesture — permanent
     * answers, asked once. Kept apart from whether the log has *accepted*
     * the line saying so: the log discards a line while recording is off,
     * and a latch set on the attempt would leave a log turned on later
     * unable to tell an absent sensor from a silent one (Codex, PR #258).
     * So each `…Said` is set only when the log took the line, and the
     * answer itself is what stops the platform being asked again.
     */
    private var noSensors = false
    private var noSensorsSaid = false
    private var noPickUp = false
    private var noPickUpSaid = false

    private var dead = false

    /**
     * Takes one reading and logs it against [moment] — the transition, or
     * the pick-up, it belongs to.
     *
     * **Every reading is independent** (Codex, PR #258, twice). A first cut
     * dropped the reading in flight when the next moment arrived, so the two
     * could not land out of order; but two transitions inside the two-second
     * window — `ARMED` to `CHECKING`, say — then cost the first its line,
     * against the promise of a reading at every transition, and a pick-up
     * beside a transition cost the arm posture it was going to be compared
     * with. So nothing drops a reading now: each lands against its own
     * moment or times out on its own, ordering is handled where it matters
     * (last-seen goes by the order asked, not the order landed), and the
     * platform half's deadline bounds every one.
     */
    fun sample(moment: String) {
        if (dead) return
        followLog()
        // No reading while the log is off (Codex, PR #258): the log would
        // refuse the line, so the registration — up to two seconds of
        // gravity and proximity, per transition and per pick-up — would be
        // paid for nothing. The pick-up *watch* is not gated the same way: a
        // hardware-batched one-shot costs nothing while it waits, and keeping
        // it armed is what lets a log turned on mid-snooze see the next
        // pick-up without a hook from the toggle into this trace.
        if (!SnoozeDebugLog.isRecording) return
        read(moment)
    }

    private fun read(moment: String) {
        val order = ++readOrder
        val handle = sensors.sample { reading ->
            followLog()
            if (reading == null) {
                val seen = lastSeen
                if (seen == null) {
                    SnoozeDebugLog.event("posture %s: no reading arrived", safe(moment))
                } else {
                    SnoozeDebugLog.event(
                        "posture %s: no reading arrived; last seen %s (%s)",
                        safe(moment),
                        safe(seen.second.describe()),
                        safe(seen.first),
                    )
                }
            } else {
                val taken = SnoozeDebugLog.event("posture %s: %s", safe(moment), safe(reading.describe()))
                if (taken && order > lastSeenOrder) {
                    lastSeenOrder = order
                    lastSeen = moment to reading
                }
            }
        }
        if (handle == null) {
            noSensors = true
            if (!noSensorsSaid) noSensorsSaid = SnoozeDebugLog.event("posture: no sensor to read on this phone")
        }
    }

    /**
     * Said once per stretch the watch is wanted and cannot be kept — once
     * the log has taken it, like the two above.
     */
    private var unobservableSaid = false

    /**
     * Matches the pick-up watch to [needed] — armed while a snooze runs,
     * canceled otherwise. Idempotent both ways, so the service restates it on
     * every transition.
     *
     * [processHeld] is whether something keeps the process foreground — the
     * snooze's own foreground service, on the builds and modes that take
     * one. A one-shot sensor delivers nothing to a background app, so a
     * watch armed without that would sit live and hear nothing, and the
     * trial would read a week of silence as a week of no pick-ups (Codex,
     * PR #258). It is not armed, and the log says once that pick-ups cannot
     * be observed for this snooze, which is the honest reading of that week.
     */
    fun reconcilePickUp(needed: Boolean, processHeld: Boolean = true) {
        if (dead) return
        followLog()
        pickUpWanted = needed && processHeld
        if (!pickUpWanted) {
            pickUp?.close()
            pickUp = null
            if (!needed) {
                unobservableSaid = false
            } else if (!unobservableSaid) {
                unobservableSaid =
                    SnoozeDebugLog.event("posture: pick-ups cannot be observed; nothing holds the process")
            }
            return
        }
        unobservableSaid = false
        if (pickUp != null) return
        if (noPickUp) {
            if (!noPickUpSaid) noPickUpSaid = SnoozeDebugLog.event("posture: no pick-up gesture sensor on this phone")
            return
        }
        armPickUp()
    }

    /** Whether the pick-up gesture is registered right now. */
    val watchingPickUp: Boolean get() = pickUp != null

    private fun armPickUp() {
        val armIdentity = ++generation
        val handle = sensors.watchPickUp { onPickedUp(armIdentity) }
        if (handle == null) {
            noPickUp = true
            noPickUpSaid = SnoozeDebugLog.event("posture: no pick-up gesture sensor on this phone")
            return
        }
        pickUp = handle
    }

    private fun onPickedUp(armIdentity: Long) {
        if (armIdentity != generation) return
        // Spent in firing, like a trigger sensor: dropped before the re-arm.
        pickUp = null
        if (dead || !pickUpWanted) return
        followLog()
        SnoozeDebugLog.event("posture: pick-up gesture fired; nothing acted on it")
        // The reading that goes with it: what the phone was doing when it
        // was picked up is half of what the trial is measuring.
        sample("on pick-up")
        armPickUp()
    }

    /**
     * Ends the watch for good. Readings still in flight are left to land:
     * the service closes this on the way out of an ending, and the ending
     * posture is half of what the trial compares, so dropping them here
     * would race away exactly that line (Codex, PR #258). The platform half
     * bounds each with a deadline and unregisters itself either way, and the
     * log it writes to outlives the service.
     */
    override fun close() {
        dead = true
        pickUpWanted = false
        pickUp?.close()
        pickUp = null
    }
}

/** The real trace, over `SensorManager`. No permission is involved for any of the three sensors. */
fun postureTrace(context: Context): PostureTrace =
    PostureTrace(PlatformPostureSensors(context))

/**
 * The platform half: the fused gravity sensor, proximity, and the pick-up
 * gesture. Callbacks are marshaled onto the main thread, and every
 * registration is dropped after its first event, so a reading costs one
 * event and a pick-up watch costs nothing until it fires.
 *
 * **Gravity, never the raw accelerometer** (Codex, PR #258). The
 * accelerometer reports gravity *plus* whatever the hand is doing, and one
 * sample of it during a pick-up can cross the flat threshold on a phone
 * that is nowhere near flat. The fused sensor is what isolates gravity,
 * and a phone without one gets `no gravity reading` rather than a guess.
 */
internal class PlatformPostureSensors(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val readElapsedRealtimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) : PostureSensors {

    private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)

    private companion object {
        const val PICK_UP_GESTURE_STRING_TYPE = "android.sensor.pick_up_gesture"

        /**
         * How long a reading may take, in elapsed real time. A foreground
         * process hears a gravity or proximity sample within a frame or two;
         * a background one hears nothing at all, and this is what turns that
         * silence into a line.
         *
         * **Real time, not uptime** (Codex, PR #258). The handler's delay
         * runs on uptime, which stops in deep sleep, so a phone that slept
         * through the window could wake hours later with the delay still
         * pending, hear its first sensor event, and record the posture it
         * has *now* against a transition long over. Every event is checked
         * against the real-time deadline first, and one past it closes the
         * reading with what had arrived in time rather than with itself.
         */
        const val SAMPLE_DEADLINE_MS = 2_000L
    }

    override fun sample(onSample: (PostureSample?) -> Unit): AutoCloseable? {
        val manager = sensors ?: return null
        val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val proximity = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (gravity == null && proximity == null) return null

        // Each sensor answers at most once, and the reading goes out when
        // every sensor that exists has answered — or as null when the
        // deadline passes first. `settled` is the one-shot gate all three
        // exits share: the reading, the deadline, and a close. The fields are
        // touched from the sensor thread; the callback runs on the main one.
        val settled = AtomicBoolean(false)
        var gravityZ: Float? = null
        var near: Boolean? = null
        var gravityDue = gravity != null
        var proximityDue = proximity != null
        val lock = Any()
        var listener: SensorEventListener? = null
        var deadline: Runnable? = null
        val askedAt = readElapsedRealtimeMs()
        fun finish(reading: PostureSample?) {
            if (!settled.compareAndSet(false, true)) return
            listener?.let { manager.unregisterListener(it) }
            deadline?.let { handler.removeCallbacks(it) }
            handler.post { onSample(reading) }
        }
        fun deliverIfComplete() {
            val reading = synchronized(lock) {
                if (gravityDue || proximityDue) return
                PostureSample(gravityZ, near)
            }
            finish(reading)
        }
        /** Whatever answered in time, or nothing at all. */
        fun giveUp() {
            val partial = synchronized(lock) { PostureSample(gravityZ, near) }
            finish(partial.takeIf { it.hasAny })
        }
        val reader = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // An event past the deadline describes the phone now, not the
                // moment asked about; it closes the reading without joining it.
                if (readElapsedRealtimeMs() - askedAt > SAMPLE_DEADLINE_MS) {
                    giveUp()
                    return
                }
                synchronized(lock) {
                    when (event.sensor.type) {
                        Sensor.TYPE_GRAVITY -> {
                            if (gravityDue) {
                                gravityZ = event.values.getOrNull(2)
                                gravityDue = false
                            }
                        }
                        Sensor.TYPE_PROXIMITY -> {
                            if (proximityDue) {
                                near = event.values.getOrNull(0)?.let { it < event.sensor.maximumRange }
                                proximityDue = false
                            }
                        }
                    }
                }
                deliverIfComplete()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = reader
        // The wake for a phone that stays awake; a phone that slept through
        // it is caught by the check above on its first event instead.
        val giveUpOnTime = Runnable { giveUp() }
        deadline = giveUpOnTime
        var any = false
        if (gravity != null) {
            if (manager.registerListener(reader, gravity, SensorManager.SENSOR_DELAY_NORMAL)) {
                any = true
            } else {
                synchronized(lock) { gravityDue = false }
            }
        }
        if (proximity != null) {
            if (manager.registerListener(reader, proximity, SensorManager.SENSOR_DELAY_NORMAL)) {
                any = true
            } else {
                synchronized(lock) { proximityDue = false }
            }
        }
        if (!any) return null
        handler.postDelayed(giveUpOnTime, SAMPLE_DEADLINE_MS)
        return AutoCloseable {
            // Settles without a callback: the caller closing it has moved on.
            if (!settled.compareAndSet(false, true)) return@AutoCloseable
            manager.unregisterListener(reader)
            handler.removeCallbacks(giveUpOnTime)
        }
    }

    override fun watchPickUp(onFired: () -> Unit): AutoCloseable? {
        val manager = sensors ?: return null
        // The SDK hides the pick-up gesture's type constant, so it is found by
        // the string type the sensor HAL names it with. Absent on a phone whose
        // HAL does not expose it, which the trace says once.
        val sensor = manager.getSensorList(Sensor.TYPE_ALL)
            .firstOrNull { it.stringType == PICK_UP_GESTURE_STRING_TYPE }
            ?: return null
        // The HAL defines the gesture as a one-shot, and a one-shot takes
        // only the trigger API — `registerListener` throws on one (Codex, PR
        // #258). Decided from the sensor's own reporting mode rather than
        // assumed, so a HAL that exposes it as on-change still registers.
        if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
            return manager.armTrigger(sensor, handler, onFired)
        }
        val fired = AtomicBoolean(false)
        val canceled = AtomicBoolean(false)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (canceled.get() || !fired.compareAndSet(false, true)) return
                manager.unregisterListener(this)
                handler.post { if (!canceled.get()) onFired() }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return if (manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            AutoCloseable {
                if (!canceled.compareAndSet(false, true)) return@AutoCloseable
                if (!fired.get()) manager.unregisterListener(listener)
            }
        } else {
            null
        }
    }
}
