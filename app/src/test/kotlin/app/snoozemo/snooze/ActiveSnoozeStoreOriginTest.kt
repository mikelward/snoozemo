package app.snoozemo.snooze

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.Anchor
import app.snoozemo.core.RecordOrigin
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * A snooze armed on one phone must not silence another (SPEC.md §12).
 *
 * The unit that decides this is covered on the JVM by `RecordOriginTest`; what
 * this adds is the wiring, which is where it would actually break — that the
 * stamp is written at all, that it is written on *every* save rather than only
 * at arm, and that `load()` is the door the refusal happens at, so a caller
 * cannot get a foreign record by not knowing to ask.
 *
 * Robolectric rather than a fake store, for the same reason as
 * `NotificationPromptStoreTest`: an in-memory double would pass while nothing
 * reached disk, and surviving the write is the entire mechanism.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveSnoozeStoreOriginTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Spelled out rather than read off the store, whose companion is private —
    // and better this way: these two strings are the on-disk contract, so a
    // rename that would strand every existing record should fail here loudly
    // rather than follow the constant silently. `TileSnapshot` names the file
    // the same way for the same reason.
    private val PREFS_FILE = "active_snooze"
    private val KEY_STAMP = "device_stamp"

    /**
     * Robolectric leaves `ANDROID_ID` null, which is a real device state on
     * modified builds but not the one most tests are about — so every test that
     * needs two identities sets it explicitly, and "this phone" versus "that
     * phone" is just two different values.
     *
     * Note that a null `ANDROID_ID` no longer means the record is
     * unattributable: the stamp folds in `firstInstallTime` too, so Robolectric's
     * default yields [RecordOrigin.THIS_DEVICE] rather than
     * [RecordOrigin.UNVERIFIABLE].
     */
    private fun becomeDevice(id: String) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, id)
        // The stamp is cached for the life of the process — it cannot change on
        // a real device — so a test that swaps identity has to say so.
        DeviceStamp.forget()
    }

    @org.junit.Before
    fun clearStampCache() = DeviceStamp.forget()

    private fun aSnooze(startedAt: Instant = Instant.ofEpochMilli(1_000_000L)) = ActiveSnooze(
        anchor = Anchor(capturedAt = startedAt, ssid = "ExampleWifi"),
        startedAt = startedAt,
        capExpiresAt = startedAt.plus(Duration.ofHours(4)),
        mode = TrackingMode.DURATION_ONLY,
    )

    @Test
    fun `a snooze armed on this phone is restored on this phone`() {
        becomeDevice("phone-one")
        ActiveSnoozeStore(context).arm(aSnooze())

        assertEquals(RecordOrigin.THIS_DEVICE, ActiveSnoozeStore(context).originOfStored())
        assertNotNull(ActiveSnoozeStore(context).load())
    }

    @Test
    fun `a snooze that arrives from another phone is refused`() {
        // The bug in one test: arm on the old handset, then read the same
        // record as the new one. Before the stamp, this returned a live snooze
        // and `BootReceiver` turned Do Not Disturb on with it.
        becomeDevice("old-phone")
        ActiveSnoozeStore(context).arm(aSnooze())

        becomeDevice("new-phone")
        assertEquals(RecordOrigin.ANOTHER_DEVICE, ActiveSnoozeStore(context).originOfStored())
        assertNull(
            "a snooze armed on another phone must not be restored here",
            ActiveSnoozeStore(context).load(),
        )
    }

    @Test
    fun `a record written before stamping existed is refused`() {
        // Simulated by writing the record and then removing the key, which is
        // exactly what such a record looks like on disk.
        becomeDevice("phone-one")
        ActiveSnoozeStore(context).arm(aSnooze())
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STAMP)
            .commit()

        assertEquals(RecordOrigin.UNATTRIBUTED, ActiveSnoozeStore(context).originOfStored())
        assertNull(ActiveSnoozeStore(context).load())
    }

    @Test
    fun `the install time carries attribution when the device identifier is missing`() {
        // `ANDROID_ID` unset, which is Robolectric's default and a real state on
        // modified builds. It used to leave the record unattributable; the stamp
        // now also folds in this install's `firstInstallTime`, which
        // `PackageManager` answers on any device — so the record is still
        // attributed and the snooze still runs (Codex, PR #26).
        ActiveSnoozeStore(context).arm(aSnooze())

        assertEquals(RecordOrigin.THIS_DEVICE, ActiveSnoozeStore(context).originOfStored())
        assertNotNull(
            "a phone without a device identifier must still run its own snoozes",
            ActiveSnoozeStore(context).load(),
        )
    }

    @Test
    fun `a record rewritten mid-snooze keeps its attribution`() {
        // `+30 min` and the clock reconciliation both rewrite the record. If the
        // stamp were written only at arm, the first rewrite would drop it and
        // the snooze would be refused at its next restore — the guard firing on
        // the phone that armed it.
        becomeDevice("phone-one")
        val store = ActiveSnoozeStore(context)
        val snooze = aSnooze()
        store.arm(snooze)

        store.update(snooze.copy(capExpiresAt = snooze.capExpiresAt.plus(Duration.ofMinutes(30))))

        assertEquals(RecordOrigin.THIS_DEVICE, ActiveSnoozeStore(context).originOfStored())
        assertNotNull(ActiveSnoozeStore(context).load())
    }

    @Test
    fun `an update over a running snooze discards the record rather than hiding it`() {
        // Codex, PR #26. An app update is not a boot, so before this the record
        // written by the pre-stamp build read as absent to every caller while
        // the zen rule stayed on — a silence with nothing in the UI to show it
        // and no `End now` to stop it. `MY_PACKAGE_REPLACED` now runs the same
        // repair a reboot does.
        becomeDevice("phone-one")
        ActiveSnoozeStore(context).arm(aSnooze())
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STAMP)
            .commit()
        assertEquals(RecordOrigin.UNATTRIBUTED, ActiveSnoozeStore(context).originOfStored())

        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertNull(
            "the unusable record must be cleared, not left to be re-refused forever",
            ActiveSnoozeStore(context).originOfStored(),
        )
    }

    // There is deliberately no store-level test for `UNCONFIRMABLE` any more.
    // It needs a device that can produce *no* identity at all, and folding
    // `firstInstallTime` into the stamp made that unreachable here — which is
    // the improvement, not a coverage gap. `RecordOriginTest` still covers the
    // classification itself, where the inputs can be stated directly.

    @Test
    fun `a refused release keeps the record and the cap rather than clearing them`() {
        // The branch that owns the record, the stuck-rule obligation, the
        // notification and the only retry (Codex, PR #26). Clearing here would
        // leave a phone silent with nothing left to un-silence it — principle
        // 1's worst case, produced by the cleanup meant to prevent it.
        becomeDevice("old-phone")
        ActiveSnoozeStore(context).arm(aSnooze())
        becomeDevice("new-phone")
        val store = ActiveSnoozeStore(context)
        val origin = store.originOfStored()
        assertEquals(RecordOrigin.ANOTHER_DEVICE, origin)

        discardForeignRecord(context, store, origin!!, RefusingZen())

        assertNotNull(
            "the record is the only thing that can still end this silence",
            store.readUnverified(),
        )
        assertTrue(
            "the durable stuck-rule obligation must be written",
            PendingFailureStore(context).ruleMayBeStuck(),
        )
        assertTrue(
            "a retry must be armed on a path that can still see the record",
            scheduledAlarmIntents().any { it.action == SnoozeService.ACTION_DISCARD_RETRY },
        )
    }

    @Test
    fun `a successful release clears the record`() {
        // The other half of the branch above, so the test proves the condition
        // rather than just one side of it.
        becomeDevice("old-phone")
        ActiveSnoozeStore(context).arm(aSnooze())
        becomeDevice("new-phone")
        val store = ActiveSnoozeStore(context)
        val origin = store.originOfStored()!!

        discardForeignRecord(
            context,
            store,
            origin,
            RefusingZen().apply { outcome = ZenOutcome.Applied },
        )

        assertNull("a released rule means the record has no further job", store.readUnverified())
    }

    @Test
    fun `the stamp on disk is not the identifier it came from`() {
        // §12's floor: equality is the only question asked of this value, so
        // nothing is lost by storing a hash — and a prefs file lifted off a
        // device then correlates with nothing.
        becomeDevice("phone-one")
        ActiveSnoozeStore(context).arm(aSnooze())

        val stored = context
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_STAMP, null)

        assertNotNull(stored)
        assertEquals("a hex SHA-256", 64, stored!!.length)
        assertNull(
            "the raw identifier must not reach disk",
            stored.takeIf { it.contains("phone-one") },
        )
    }
}
