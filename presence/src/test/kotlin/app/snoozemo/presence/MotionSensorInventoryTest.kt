package app.snoozemo.presence

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The inventory line a phone with no significant-motion sensor gets
 * (SPEC.md §4.4), rendered from platform types alone.
 *
 * The rendering is what the fallback is chosen from, so the assertions are on
 * the exact line: which kinds are named, in what order, and that the wake-up
 * mark — the property that decides whether a kind can end a snooze at all —
 * survives. The platform read is one `getSensorList` call and is not
 * exercised here.
 */
class MotionSensorInventoryTest {

    private fun sensor(type: Int, wakeUp: Boolean = false) = MotionSensorPresence(type, wakeUp)

    @Test
    fun `names each kind present, in the table's order, marking the ones that wake`() {
        // Listed out of order on purpose: the platform's own order is
        // whatever the HAL registered, and a stable line is easier to read
        // across two phones' reports.
        val line = describeMotionSensors(
            listOf(
                sensor(Sensor.TYPE_GYROSCOPE),
                sensor(Sensor.TYPE_MOTION_DETECT, wakeUp = true),
                sensor(Sensor.TYPE_ACCELEROMETER),
            ),
        )
        assertEquals("motion sensors present: motion-detect (wake-up), accelerometer, gyroscope", line)
    }

    @Test
    fun `a kind present twice is named once, and wakes if any copy does`() {
        // Phones commonly report a wake-up and a non-wake-up accelerometer;
        // one label answers the question either way.
        val line = describeMotionSensors(
            listOf(
                sensor(Sensor.TYPE_ACCELEROMETER),
                sensor(Sensor.TYPE_ACCELEROMETER, wakeUp = true),
            ),
        )
        assertEquals("motion sensors present: accelerometer (wake-up)", line)
    }

    @Test
    fun `kinds outside the table are dropped, never rendered`() {
        // A vendor-defined type would carry a vendor-defined string; the line
        // renders only from its own labels, so the type is simply absent.
        val vendorType = Sensor.TYPE_DEVICE_PRIVATE_BASE + 7
        val line = describeMotionSensors(
            listOf(
                sensor(vendorType, wakeUp = true),
                sensor(Sensor.TYPE_LIGHT),
                sensor(Sensor.TYPE_STEP_DETECTOR),
            ),
        )
        assertEquals("motion sensors present: step-detector", line)
    }

    @Test
    fun `nothing usable is said as such, not as an empty list`() {
        assertEquals(
            "motion sensors present: none of the kinds a wake-up could use",
            describeMotionSensors(listOf(sensor(Sensor.TYPE_LIGHT))),
        )
        assertEquals(
            "motion sensors present: none of the kinds a wake-up could use",
            describeMotionSensors(emptyList()),
        )
    }
}
