package app.snoozemo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The foreground-grant → background-disclosure ordering `SPEC.md` §12
 * requires: a fine grant must open the rationale dialog rather than
 * launching the background request directly, and only on flavors that need
 * background location at all. Runs under both flavor variants
 * (`testPlayDebugUnitTest` / `testDirectDebugUnitTest`), so the assertion is
 * written in terms of [locationTrackingNeedsBackgroundPermission] rather than
 * a hardcoded expectation — `play` sees the dialog open, `direct` never does.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityLocationDisclosureTest {

    @Test
    fun `a fine grant opens the background rationale only on flavors that need it`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.onForegroundLocationResult(fineGranted = true)

        assertEquals(
            "showBackgroundLocationRationale must track " +
                "locationTrackingNeedsBackgroundPermission after a fine grant",
            locationTrackingNeedsBackgroundPermission,
            activity.showBackgroundLocationRationale,
        )
    }

    @Test
    fun `a coarse-only result never opens the background rationale`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.onForegroundLocationResult(fineGranted = false)

        assertFalse(activity.showBackgroundLocationRationale)
    }

    @Test
    fun `Continue closes the rationale dialog`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showBackgroundLocationRationale = true

        activity.beginBackgroundLocationRequest()

        assertFalse(
            "Continue must close the dialog, or it stays over the next system prompt",
            activity.showBackgroundLocationRationale,
        )
    }
}
