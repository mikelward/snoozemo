package app.snoozemo.snooze

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.TrackingMode
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The anchor's fields survive the round trip through the record — the whole
 * point of persisting them is that a restore reads back the place the snooze
 * was armed at, not a shape with holes in it.
 */
@RunWith(RobolectricTestRunner::class)
class ActiveSnoozeStoreAnchorTest {

    private val now: Instant = Instant.parse("2026-08-22T09:00:00Z")

    private val store = ActiveSnoozeStore(ApplicationProvider.getApplicationContext<Application>())

    private fun record(anchor: Anchor): ActiveSnooze = ActiveSnooze(
        anchor = anchor,
        startedAt = now,
        capExpiresAt = now.plusSeconds(3_600),
        mode = TrackingMode.DURATION_ONLY,
    )

    @Test
    fun `a captured anchor round-trips whole`() {
        // Stock stand-ins, never a device capture (AGENTS.md, *Privacy*).
        val anchor = Anchor(
            lat = 0.0,
            lon = 0.0,
            fixAccuracyM = 20f,
            capturedAt = now,
            ssid = "ExampleWifi",
            bssid = "00:11:22:33:44:55",
        )

        store.save(record(anchor))
        val loaded = store.load()?.anchor

        assertEquals(anchor, loaded)
    }

    @Test
    fun `an empty anchor round-trips as absent fields, not inventions`() {
        store.save(record(Anchor(capturedAt = now)))
        val loaded = store.load()?.anchor

        assertNull(loaded?.ssid)
        assertNull(loaded?.bssid)
        assertNull(loaded?.lat)
        assertNull(loaded?.fixAccuracyM)
    }

    @Test
    fun `a new record clears the previous anchor's bssid`() {
        // Written over rather than merged: a leftover AP identifier from an
        // earlier snooze must not attach itself to a place it was never at.
        store.save(
            record(Anchor(capturedAt = now, ssid = "ExampleWifi", bssid = "00:11:22:33:44:55")),
        )

        store.save(record(Anchor(capturedAt = now, ssid = "OtherWifi")))

        assertNull(store.load()?.anchor?.bssid)
    }
}
