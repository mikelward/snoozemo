package app.snoozemo.snooze

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * The degraded notification's *reason* has to survive process death, or the
 * user is told that something is degraded and never what — the state the
 * whole cause-plumbing exists to end (TODO.md; Codex, PR #31).
 *
 * Robolectric rather than a fake store, for the same reason
 * `ActiveSnoozeStoreOriginTest` gives: an in-memory double would pass while
 * nothing reached disk, and surviving the write is the entire mechanism.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveSnoozeStoreDegradationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun aSnooze(
        degradation: DegradationCause?,
        startedAt: Instant = Instant.ofEpochMilli(1_000_000L),
    ) = ActiveSnooze(
        anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
        startedAt = startedAt,
        capExpiresAt = startedAt.plus(Duration.ofHours(4)),
        mode = TrackingMode.DURATION_ONLY,
        degradation = degradation,
    )

    @Test
    fun `the recorded reason survives a restore`() {
        ActiveSnoozeStore(context).save(aSnooze(DegradationCause.FIXES_TOO_VAGUE))

        assertEquals(
            DegradationCause.FIXES_TOO_VAGUE,
            ActiveSnoozeStore(context).load()?.degradation,
        )
    }

    @Test
    fun `recovering clears the reason on disk rather than leaving the old one`() {
        // A snooze that recovered must not come back still explaining a
        // degradation it no longer has.
        val store = ActiveSnoozeStore(context)
        store.save(aSnooze(DegradationCause.NO_LOCATION_FIX))

        store.save(aSnooze(degradation = null))

        assertNull(ActiveSnoozeStore(context).load()?.degradation)
    }

    @Test
    fun `a record written without a reason reads as none`() {
        // What every record written before this field existed looks like: the
        // mode still restores, and the notification simply renders it alone.
        ActiveSnoozeStore(context).save(aSnooze(degradation = null))

        val restored = ActiveSnoozeStore(context).load()
        assertEquals(TrackingMode.DURATION_ONLY, restored?.mode)
        assertNull(restored?.degradation)
    }

    @Test
    fun `a cause this build does not recognize reads as none, not a crash`() {
        // A record written by a build that knew a cause this one does not.
        // Losing the reason costs the notification some detail; refusing to
        // load would cost the snooze its cap.
        ActiveSnoozeStore(context).save(aSnooze(DegradationCause.NO_LOCATION_FIX))
        context.getSharedPreferences("active_snooze", Context.MODE_PRIVATE)
            .edit()
            .putString("degradation", "SOMETHING_A_LATER_BUILD_ADDED")
            .commit()

        val restored = ActiveSnoozeStore(context).load()
        assertEquals(TrackingMode.DURATION_ONLY, restored?.mode)
        assertNull(restored?.degradation)
    }
}
