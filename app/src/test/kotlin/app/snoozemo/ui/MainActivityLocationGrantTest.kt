package app.snoozemo.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.presence.PRESENCE_TRACKS_DEPARTURE
import app.snoozemo.snooze.ActiveSnoozeStore
import app.snoozemo.snooze.SnoozeService
import app.snoozemo.snooze.snoozeFixture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A location grant that lands while a snooze is running (SPEC.md §8.2).
 *
 * Android broadcasts no permission change, so a monitor that degraded on a
 * lost grant learned it was back only from the backstop's next wake — up to
 * half an hour with the §6.6 grace period shut, so a user who left inside
 * that window stayed quiet to the cap (Codex, PR #150). This screen's own
 * permission reading is the one place that sees the grant land, and it asks
 * the service to re-check.
 *
 * Runs under both flavor variants, so the expectation is written in terms of
 * [PRESENCE_TRACKS_DEPARTURE]: `play` asks, `direct` — which reads no
 * location — never starts the service for it.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityLocationGrantTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    @Before
    fun clearRecord() {
        // Every test below states its own snooze state; a record left by a
        // sibling would silently turn the first-read poke on or off.
        ActiveSnoozeStore(app).clear()
    }

    /** Runs the deferred first-frame reads the screen queues on every start. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Every `SnoozeService` action this screen has asked for since the last
     * drain. A list rather than the next one: the same reads start the
     * service for other reasons — the calendar row's first reading reposts,
     * and `direct` ends a record whose mode it cannot honor — and this test
     * is about whether the grant's own action is among them.
     */
    private fun startedServiceActions(): List<String?> = buildList {
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: break
            if (intent.component?.className == SnoozeService::class.java.name) add(intent.action)
        }
    }

    private fun assertGrantPoked(message: String) {
        val actions = startedServiceActions()
        if (PRESENCE_TRACKS_DEPARTURE) {
            assertTrue(message, actions.contains(SnoozeService.ACTION_LOCATION_GRANTED))
        } else {
            assertFalse(
                "direct reads no location, so a grant is nothing to re-ask",
                actions.contains(SnoozeService.ACTION_LOCATION_GRANTED),
            )
        }
    }

    private fun assertGrantNotPoked(message: String) {
        assertFalse(message, startedServiceActions().contains(SnoozeService.ACTION_LOCATION_GRANTED))
    }

    private fun drainStartedServices() {
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @Test
    fun `a grant taken in Settings re-asks the running monitor`() {
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        shadowOf(app).denyPermissions(*locationPermissions)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        // Everything the screen did while starting is not what this is about.
        drainStartedServices()

        // The trip to Settings, and back.
        controller.pause().stop()
        shadowOf(app).grantPermissions(*locationPermissions)
        controller.start().resume()
        settle()

        assertGrantPoked("returning with the grant has to reach the monitor, or grace stays shut until the backstop")
    }

    @Test
    fun `a grant from the prompt re-asks the running monitor`() {
        // The runtime prompt's own result, delivered through the same read.
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        shadowOf(app).denyPermissions(*locationPermissions)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        drainStartedServices()

        shadowOf(app).grantPermissions(*locationPermissions)
        activity.onForegroundLocationResult(fineGranted = true)
        settle()

        assertGrantPoked("the prompt's own result has to reach the monitor")
    }

    @Test
    fun `a fine-only grant re-asks too`() {
        // The background half still denied leaves the combined reading below
        // `GRANTED`, but the monitor acts on it now: a latched "permission
        // off" reclassifies to "background location off", so the card names
        // the half still missing instead of the one just restored (Codex,
        // PR #185).
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        shadowOf(app).denyPermissions(*locationPermissions)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        drainStartedServices()

        controller.pause().stop()
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        controller.start().resume()
        settle()

        assertGrantPoked("a fine-only grant is news the monitor reclassifies on")
    }

    @Test
    fun `a first read with a snooze already running re-asks`() {
        // The tile arms without this screen ever existing (SPEC.md §4.2), so
        // a grant taken in system App info before the app is first opened
        // reaches this screen with no previous reading to compare against.
        // The poke is free when nothing is latched, and the only route to a
        // monitor that is.
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        shadowOf(app).grantPermissions(*locationPermissions)

        Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()

        assertGrantPoked("a first read over a running snooze has to re-ask")
    }

    @Test
    fun `a grant with no snooze running starts nothing`() {
        // Nothing is watching, so there is nothing to re-ask — and a service
        // started on every grant with no snooze would be a start for nobody.
        shadowOf(app).denyPermissions(*locationPermissions)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        drainStartedServices()

        controller.pause().stop()
        shadowOf(app).grantPermissions(*locationPermissions)
        controller.start().resume()
        settle()

        assertGrantNotPoked("nothing is running, so there is nothing to re-ask")
    }

    @Test
    fun `an ordinary resume with the grant long held starts nothing`() {
        // A poke on every resume would start the service each time the user
        // opens the app; the transition is what this is about.
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        shadowOf(app).grantPermissions(*locationPermissions)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        drainStartedServices()

        controller.pause().stop().start().resume()
        settle()

        assertGrantNotPoked("nothing changed, so nothing should be re-asked")
    }

    @Test
    fun `a revocation starts nothing from here`() {
        // The other direction is not this screen's: a revocation kills the
        // process, the cold restore is what sees it, and the monitor reads the
        // loss itself from the refusal it gets.
        ActiveSnoozeStore(app).arm(snoozeFixture(java.time.Instant.now()))
        shadowOf(app).grantPermissions(*locationPermissions)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        drainStartedServices()

        controller.pause().stop()
        shadowOf(app).denyPermissions(*locationPermissions)
        controller.start().resume()
        settle()

        assertGrantNotPoked("a revocation is the cold restore's to see, not this screen's")
    }
}
