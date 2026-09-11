package app.snoozemo.snooze

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.SnoozeLifecycle
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
 * The arm count is the one thing a clear keeps: it exists to tell a tap made
 * over "nothing running" that a snooze has since come and gone, whether or
 * not anything was watching when it did (Codex, PR #257). It counts
 * *confirmed* snoozes — the write that first records the rule as on — so an
 * arm refused before that point, which the user never saw, ends no offer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveSnoozeStoreArmCountTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun aSnooze(startedAt: Instant = now) = ActiveSnooze(
        anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
        startedAt = startedAt,
        capExpiresAt = startedAt.plus(Duration.ofHours(4)),
        mode = TrackingMode.DURATION_ONLY,
        lifecycle = SnoozeLifecycle.ARMED,
    )

    private lateinit var store: ActiveSnoozeStore

    @Before
    fun setUp() {
        store = ActiveSnoozeStore(context)
        store.clear()
    }

    @Test
    fun `every arm counts, and a clear keeps the count`() {
        val before = store.armCount()

        store.arm(aSnooze())
        assertEquals(before + 1, store.armCount())

        store.clear()
        assertNull("the record is gone", store.load())
        assertEquals("but the count is not", before + 1, store.armCount())

        store.arm(aSnooze(startedAt = now.plusSeconds(60)))
        assertEquals(before + 2, store.armCount())
    }

    @Test
    fun `an update of a live record does not count as an arm`() {
        store.arm(aSnooze())
        val armed = store.armCount()

        store.update(aSnooze().copy(capExpiresAt = now.plus(Duration.ofHours(1))))

        assertEquals(armed, store.armCount())
    }

    @Test
    fun `an arm that never confirms does not count`() {
        // The provisional record the arm path writes before the rule goes
        // on, then cleared because the zen or record write was refused: no
        // snooze the user could have seen came or went, so an offer made
        // before it still stands (Codex, PR #257).
        val before = store.armCount()

        store.armAsync(aSnooze(startedAt = now).copy(lifecycle = SnoozeLifecycle.ARMING))
        assertEquals("not at the provisional write", before, store.armCount())
        store.clear()

        assertEquals("nor at its clear", before, store.armCount())
    }

    @Test
    fun `a confirmation that did not reach disk does not count, and its unwind keeps it that way`() {
        // `commit()` applies the editor to memory before the disk write it
        // reports on, so a refused confirming write would leave the moved
        // count in the map for the unwind's clear to keep, and a tap waiting
        // on location would be told its offer was over for a snooze that
        // never landed (Codex, PR #257). The fake refuses the way the
        // platform does: it writes, then reports failure.
        val refusing = object : ActiveSnoozeStore(context) {
            override fun persist(editor: android.content.SharedPreferences.Editor): Boolean {
                editor.commit()
                return false
            }
        }
        val before = store.armCount()
        store.armAsync(aSnooze().copy(lifecycle = SnoozeLifecycle.ARMING))

        assertEquals("the write is reported refused", false, refusing.arm(aSnooze()))
        assertEquals("and the count is put back", before, store.armCount())

        store.clear()

        assertEquals("through the unwind", before, store.armCount())
    }

    @Test
    fun `an arm counts once it is confirmed, and once only`() {
        // The tile's path: the provisional record, then the confirmed one
        // written over it once the rule is on. A restore that finishes an
        // `ARMING` record after a crash confirms the same way, through an
        // update — counted there too, and a later update of the live record
        // is not.
        val before = store.armCount()

        store.armAsync(aSnooze().copy(lifecycle = SnoozeLifecycle.ARMING))
        store.arm(aSnooze())
        assertEquals("the arm's own confirmation", before + 1, store.armCount())
        store.clear()

        store.armAsync(aSnooze(startedAt = now.plusSeconds(60)).copy(lifecycle = SnoozeLifecycle.ARMING))
        store.update(aSnooze(startedAt = now.plusSeconds(60)))
        assertEquals("a restore's confirmation", before + 2, store.armCount())
        store.update(aSnooze(startedAt = now.plusSeconds(60)).copy(capExpiresAt = now.plus(Duration.ofHours(1))))
        assertEquals("and not again", before + 2, store.armCount())
    }
}
