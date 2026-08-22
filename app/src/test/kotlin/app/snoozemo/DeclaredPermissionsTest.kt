package app.snoozemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the permission split of SPEC.md §3.4 — the one place the two flavors'
 * constraints genuinely diverge. This suite runs once per flavor variant, so
 * the same assertions guard both directions: `play` must carry the background
 * grant the Geofencing API needs, and `direct` must never gain a restricted
 * permission, because shipping without one is that flavor's reason to exist.
 *
 * INTERNET is asserted absent unconditionally: SPEC.md §12's "no data
 * collected, no data shared" is auditable precisely because nothing can leave
 * the device, and a dependency quietly merging the permission in is the way
 * that guarantee would break without anyone deciding it.
 */
@RunWith(RobolectricTestRunner::class)
class DeclaredPermissionsTest {

    private val declared: List<String> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()
    }

    @Test
    fun `both flavors hold the shared location family`() {
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in declared)
        // Beside FINE because Android 12+ lets the user downgrade the grant;
        // a request for FINE alone would be refused, not downgraded.
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in declared)
        assertTrue(Manifest.permission.ACCESS_WIFI_STATE in declared)
    }

    @Test
    fun `only the play flavor carries the restricted background grant`() {
        val restricted = Manifest.permission.ACCESS_BACKGROUND_LOCATION in declared
        // The flavor read from the versionName suffix the build script sets,
        // because BuildConfig generation is off in this project. If the suffix
        // convention changes this test fails loudly on the assertion below
        // rather than silently guarding the wrong flavor.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val versionName = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName.orEmpty()
        if (versionName.endsWith("-direct")) {
            assertFalse("direct ships with no restricted permission (SPEC.md §3.4)", restricted)
        } else {
            assertTrue("play is the Geofencing build and needs the grant", restricted)
        }
    }

    @Test
    fun `nothing can reach the network`() {
        assertFalse(
            "INTERNET would end the no-data-leaves-the-device guarantee (SPEC.md §12)",
            Manifest.permission.INTERNET in declared,
        )
    }
}
