package app.snoozemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
 * INTERNET is now part of that split rather than absent everywhere: `play`
 * declares it for crash reporting and Firebase Analytics, both behind one
 * consent (SPEC.md §12), and `direct` must not, so
 * "this build cannot open a network connection" stays literally true of the
 * sideload flavor. Asserting both directions is what stops a dependency
 * quietly merging the permission into `direct` — the way that guarantee would
 * break without anyone deciding it.
 */
@RunWith(RobolectricTestRunner::class)
class DeclaredPermissionsTest {

    /**
     * The flavor, read from the versionName suffix the build script sets,
     * because BuildConfig generation carries no flavor field in this project.
     * If the suffix convention changes, the assertions that use this fail
     * loudly rather than silently guarding the wrong flavor.
     */
    private val isDirectFlavor: Boolean by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.packageManager
            .getPackageInfo(context.packageName, 0).versionName.orEmpty()
            .endsWith("-direct")
    }

    private val declared: List<String> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()
    }

    /** The merged manifest's application-level metadata, per flavor. */
    private val metaData: Map<String, String> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bundle = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
        bundle?.keySet().orEmpty().associateWith { bundle?.get(it).toString() }
    }

    @Test
    fun `every Firebase SDK is switched off in the manifest`() {
        // Firebase initializes every SDK from one `ContentProvider`, before
        // `Application.onCreate`, so an SDK with no default here decides for
        // itself whether to collect — and can record and upload before the
        // stored answer has even been read. The runtime setters are what turn
        // collection on; these are what stop it starting.
        //
        // Asserted as a *set* rather than one assertion per SDK: Analytics was
        // added to the sibling launcher without its default and no per-SDK
        // test noticed, so a fourth SDK added later must not be able to ship
        // the same way.
        val switches = setOf(
            "firebase_crashlytics_collection_enabled",
            "firebase_analytics_collection_enabled",
        )

        if (isDirectFlavor) {
            // `direct` has no Firebase at all, so it must not carry the
            // switches either — their presence would mean an SDK had been
            // merged into the flavor whose reason to exist is not having one.
            assertTrue(
                "direct ships no Firebase, so it declares none of its switches",
                metaData.keys.none { it in switches },
            )
            return
        }

        assertEquals(
            "every Firebase collection switch must be declared and default off",
            switches.associateWith { "false" },
            metaData.filterKeys { it in switches },
        )
        // A separate switch from collection, so its absence would not show up
        // above. The AD_ID permission is removed outright in the main
        // manifest; this is the SDK-side half of the same decision.
        assertEquals("false", metaData["google_analytics_adid_collection_enabled"])
    }

    @Test
    fun `both flavors hold the shared location family`() {
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in declared)
        // Beside FINE because Android 12+ lets the user downgrade the grant;
        // a request for FINE alone would be refused, not downgraded.
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in declared)
        assertTrue(Manifest.permission.ACCESS_WIFI_STATE in declared)
        // Merged in from `:presence`, which needs it to register the network
        // callback the SSID read goes through (SPEC.md §6.4).
        assertTrue(Manifest.permission.ACCESS_NETWORK_STATE in declared)
    }

    @Test
    fun `both flavors hold the calendar read, and it is not restricted`() {
        // In the main manifest rather than `play`'s, unlike background
        // location: this is a plain runtime permission with no Play
        // declaration behind it and no network implication, so it does not
        // cross the line `direct` exists to hold (SPEC.md §3.4, §4.3).
        assertTrue(Manifest.permission.READ_CALENDAR in declared)
        // And it stays a *read*. Nothing in this app writes to a calendar, so
        // a WRITE grant appearing here would be a dependency pulling in a
        // capability the policy does not describe.
        assertFalse(
            "Snoozemo never writes to a calendar (docs/PRIVACY.md)",
            Manifest.permission.WRITE_CALENDAR in declared,
        )
    }

    @Test
    fun `only the play flavor carries the restricted background grant`() {
        val restricted = Manifest.permission.ACCESS_BACKGROUND_LOCATION in declared
        if (isDirectFlavor) {
            assertFalse("direct ships with no restricted permission (SPEC.md §3.4)", restricted)
        } else {
            assertTrue("play is the Geofencing build and needs the grant", restricted)
        }
    }

    @Test
    fun `only the play flavor can reach the network`() {
        val network = Manifest.permission.INTERNET in declared
        if (isDirectFlavor) {
            assertFalse(
                "direct must not be able to open a network connection (SPEC.md §3.4, §12)",
                network,
            )
        } else {
            assertTrue(
                "play declares INTERNET for crash reporting and analytics (SPEC.md §12)",
                network,
            )
        }
    }

    @Test
    fun `nothing can read the advertising ID`() {
        // Play's Advertising ID declaration is answered "not used"
        // (docs/play-store-declarations.md). A dependency merging this in makes
        // that answer false without anyone deciding it — the same failure shape
        // INTERNET has above, and the same reason to assert it here.
        //
        // Unconditional, INTERNET's split notwithstanding. This was written
        // while Crashlytics shipped without Firebase Analytics, saying that if
        // Analytics ever arrived this assertion failing would be the prompt to
        // decide the Advertising ID and Data Safety answers deliberately rather
        // than an obstacle to route around.
        //
        // That is exactly what happened (PR #166): Analytics does merge AD_ID —
        // confirmed by this test failing the moment the dependency landed, not
        // by reading Google's docs — and the decision was to keep the answer at
        // "not used", so `play`'s manifest removes the permission with
        // `tools:node="remove"` and switches `google_analytics_adid_collection_enabled`
        // off. The assertion stays for the next SDK that would merge it back in.
        assertFalse(
            "AD_ID would falsify the Advertising ID declaration",
            "com.google.android.gms.permission.AD_ID" in declared,
        )
    }

    @Test
    fun `the play flavor declares no foreground service type`() {
        // What Play actually reviews is the *type* — the location type's
        // approved use cases are the ones SPEC.md §3.3 walks through failing,
        // and the April 2026 update named geofencing as a non-approved use of
        // it. So the invariant worth pinning is that no service declares a
        // type at all, which is why the play build owes no foreground-service
        // declaration in Play Console.
        //
        // Deliberately not asserted: the bare android.permission
        // .FOREGROUND_SERVICE, which WorkManager merges in and which the app's
        // own manifests never request. It grants nothing on its own — a
        // service still needs a declared type to start in the foreground — but
        // it does mean "the play build declares no foreground service" is true
        // of Snoozemo's code rather than of the merged manifest.
        //
        // Scoped to play: direct is option A (SPEC.md §3.4) and gains a typed
        // service at Phase 7, where none of this review applies.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        if (isDirectFlavor) return

        val typed = declared.filter {
            it.startsWith("android.permission.FOREGROUND_SERVICE_")
        }
        assertTrue("play must request no typed foreground-service permission, found: $typed", typed.isEmpty())

        val services = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services
            .orEmpty()
        val withType = services.filter { it.foregroundServiceType != 0 }.map { it.name }
        assertTrue(
            "play must declare no foregroundServiceType (SPEC.md §3.3), found: $withType",
            withType.isEmpty(),
        )
    }
}
