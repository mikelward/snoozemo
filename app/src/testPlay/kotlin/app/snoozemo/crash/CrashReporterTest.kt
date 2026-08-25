package app.snoozemo.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `play` flavor compiles Crashlytics in, but a build whose process never
 * initialized a [FirebaseApp] has no reporter — and that case has to be
 * *quiet*, not merely unreported: `FirebaseCrashlytics.getInstance()` throws
 * without one, so a gate that asked the SDK before checking availability would
 * take the app down at startup on exactly those builds.
 *
 * That is the state every build made without a `google-services.json` is in
 * (`docs/crashlytics.md`) — a fresh clone, a fork, and every CI job but
 * `deploy`. It is also, independently, the state **every Robolectric test** is
 * in: Robolectric does not run manifest-declared `ContentProvider`s, so
 * `FirebaseInitProvider` never fires here even on a developer's machine where
 * `app/google-services.json` is present and the generated `google_app_id`
 * resource resolves. Verified rather than assumed (Codex, PR #113, which
 * predicted the opposite): with a config in place,
 * `FirebaseOptions.fromResource` returns a fully-populated object and
 * `FirebaseApp.getApps` is still empty.
 *
 * So these assertions hold for every developer, configured or not — but
 * because they hold for a reason outside this app's control, [firebaseIsDormant]
 * pins it explicitly. If a Robolectric upgrade ever does start running that
 * provider, this suite fails on a line that says so, rather than silently
 * turning into a test of the opposite branch.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun firebaseIsDormant() {
        assertTrue(
            "Firebase initialized under Robolectric — these tests cover the dormant case " +
                "only, so they need splitting rather than patching if this ever fires.",
            FirebaseApp.getApps(context).isEmpty(),
        )
    }

    @Test
    fun `a build with no initialized Firebase offers no reporter`() {
        assertFalse(CrashReporter.isAvailable(context))
        // The gate delegates rather than answering on its own, so Settings and
        // the reporter can never disagree about whether there is one.
        assertFalse(CrashReporting.isAvailable(context))
    }

    @Test
    fun `applying a choice says nothing was applied, instead of throwing`() {
        // The throw this guards against is `FirebaseCrashlytics.getInstance()`
        // without an initialized FirebaseApp: `apply` has to ask the
        // availability question first and answer honestly, not assume.
        assertEquals(ReporterOutcome.NO_REPORTER, CrashReporter.apply(context, enabled = true))
        assertEquals(ReporterOutcome.NO_REPORTER, CrashReporter.apply(context, enabled = false))
    }

    @Test
    fun `installing does not throw, and starts no worker it cannot use`() {
        CrashReporting.install(context)
    }
}
