package app.snoozemo.snooze

import app.snoozemo.core.SnoozeLifecycle
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.EndReason
import app.snoozemo.core.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * The release markers survive what they have to and no more (SPEC.md §5.8).
 *
 * A single `save` used to serve a new arm and every later rewrite of a live
 * record alike, and cleared the release reason on each — so a clock rebase or
 * a tracking update wiped the reason a release in flight had just recorded,
 * and a process that died before clearing up read its own ending back as the
 * user's (Codex, PR #36). The distinction is in the type now: [ActiveSnoozeStore.arm]
 * clears, [ActiveSnoozeStore.update] does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveSnoozeStoreMarkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun aSnooze() = ActiveSnooze(
        anchor = Anchor(capturedAt = now, ssid = "ExampleWifi"),
        startedAt = now,
        capExpiresAt = now.plus(Duration.ofHours(4)),
        mode = TrackingMode.DURATION_ONLY,
        lifecycle = SnoozeLifecycle.ARMED,
    )

    @Before
    fun clearStampCache() = DeviceStamp.forget()

    @Test
    fun `a record update keeps the release reason`() {
        val store = ActiveSnoozeStore(context)
        val snooze = aSnooze()
        store.arm(snooze)
        store.markReleasing(EndReason.DURATION_CAP)

        // The shapes that rewrite a live record: a later cap, a changed mode.
        store.update(snooze.copy(capExpiresAt = snooze.capExpiresAt.plus(Duration.ofMinutes(30))))
        store.update(snooze.copy(mode = TrackingMode.WIFI_ONLY))

        assertEquals(EndReason.DURATION_CAP, store.releasingReason())
    }

    @Test
    fun `a new arm clears a stale release reason`() {
        // The case the clearing was written for: a new snooze must not be born
        // carrying the reason an earlier one's failed clean-up left behind.
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())
        store.markReleasing(EndReason.DEPARTURE)

        store.arm(aSnooze().copy(startedAt = now.plus(Duration.ofHours(1))))

        assertNull(store.releasingReason())
    }

    @Test
    fun `the rule a snooze was armed with survives the write`() {
        // What the broadcast gate and the read-back compare against (SPEC.md
        // §5.8); an in-memory double would pass while nothing reached disk.
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze().copy(ruleId = "the-enforcing-rule"))

        assertEquals("the-enforcing-rule", store.load()?.ruleId)
        assertEquals("the-enforcing-rule", store.enforcingRuleId())
    }

    @Test
    fun `a record that named no rule reads as naming none`() {
        // A record from before the field existed, or one not yet confirmed on
        // a rule: null, so the reads fall back to the current id rather than
        // inventing one.
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())

        assertNull(store.load()?.ruleId)
        assertNull(store.enforcingRuleId())
    }

    @Test
    fun `a record update does not resurrect a released snooze`() {
        // The other marker on the same file, for the same reason: a released
        // record rewritten by some straggling update must stay released, or a
        // later restore turns Do Not Disturb back on after the snooze is over.
        val store = ActiveSnoozeStore(context)
        val snooze = aSnooze()
        store.arm(snooze)
        store.markReleased()

        store.update(snooze.copy(mode = TrackingMode.WIFI_ONLY))

        assertNull(store.load())
    }

    @Test
    fun `an update promotes the lifecycle but never demotes it`() {
        // The invariant that replaces "arm clears the markers, update doesn't"
        // being remembered at each call site: an ordinary rewrite of a live
        // record cannot walk a release another process just recorded back to
        // ARMED, whatever the in-memory copy it is holding says.
        val store = ActiveSnoozeStore(context)
        val snooze = aSnooze()
        store.arm(snooze)
        store.markReleasing(EndReason.DURATION_CAP)

        store.update(snooze.copy(placeName = "Work"))

        assertEquals(SnoozeLifecycle.RELEASING, store.state().lifecycle)
        assertEquals(EndReason.DURATION_CAP, store.state().releasingReason)
    }

    @Test
    fun `a new arm starts the lifecycle over`() {
        // The one write that goes backwards, because a new snooze must not be
        // born carrying the last one's ending.
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())
        store.markReleasing(EndReason.DEPARTURE)

        store.arm(aSnooze().copy(lifecycle = SnoozeLifecycle.ARMING))

        assertEquals(SnoozeLifecycle.ARMING, store.state().lifecycle)
        assertNull(store.state().releasingReason)
    }

    @Test
    fun `a user's ending steps a refused release back to armed`() {
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())
        store.markReleasing(EndReason.DEPARTURE)

        store.supersedeRelease()

        assertEquals(SnoozeLifecycle.ARMED, store.state().lifecycle)
        assertNull(store.state().releasingReason)
    }

    @Test
    fun `a released record is refused by load and says so in its state`() {
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())

        store.markReleased()

        assertNull(store.load())
        assertEquals(SnoozeLifecycle.RELEASED, store.state().lifecycle)
    }

    @Test
    fun `a record from before the lifecycle key reads its old flags`() {
        // Migration lives in the store and nowhere else. The three flags that
        // used to encode this are read back into the one state, so a record
        // written by an older build keeps meaning what it meant.
        val prefs = context.getSharedPreferences("active_snooze", Context.MODE_PRIVATE)
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())

        prefs.edit()
            .remove("lifecycle")
            .putBoolean("armed", false)
            .commit()
        assertEquals(SnoozeLifecycle.ARMING, store.state().lifecycle)

        prefs.edit().putBoolean("armed", true).commit()
        assertEquals(SnoozeLifecycle.ARMED, store.state().lifecycle)

        prefs.edit().putString("releasing_reason", EndReason.DEPARTURE.name).commit()
        assertEquals(SnoozeLifecycle.RELEASING, store.state().lifecycle)
        assertEquals(EndReason.DEPARTURE, store.state().releasingReason)

        prefs.edit().putBoolean("released", true).commit()
        assertEquals(SnoozeLifecycle.RELEASED, store.state().lifecycle)
    }

    @Test
    fun `a record predating the armed flag entirely reads as armed`() {
        // The migration question left open on PR #36, answered once here: absent
        // meant "not armed" to a build that had the key, but a record older than
        // the key was armed the old way. Reading it as ARMING would re-assert
        // the rule over a Do Not Disturb the user had switched off.
        val prefs = context.getSharedPreferences("active_snooze", Context.MODE_PRIVATE)
        val store = ActiveSnoozeStore(context)
        store.arm(aSnooze())

        prefs.edit()
            .remove("lifecycle")
            .remove("armed")
            .commit()

        assertEquals(SnoozeLifecycle.ARMED, store.state().lifecycle)
    }
}
