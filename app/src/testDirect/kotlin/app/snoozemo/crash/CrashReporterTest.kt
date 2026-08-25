package app.snoozemo.crash

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `direct` ships without a crash reporter, and that is the flavor's reason to
 * exist (`SPEC.md` §3.4): no Play Services dependency, no `INTERNET`
 * permission, nothing that could send a report anywhere. `DeclaredPermissions`
 * pins the manifest half; this pins the code half, so a future change that
 * moved the Crashlytics dependency from `playImplementation` to
 * `implementation` fails here rather than shipping a reporter into the
 * sideload build.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `there is no reporter to offer`() {
        assertFalse(CrashReporter.isAvailable(context))
        assertFalse(CrashReporting.isAvailable(context))
    }

    @Test
    fun `applying a choice reports that nothing was applied`() {
        // Not silently "fine": the caller logs the unavailability rather than
        // believing it turned something on (AGENTS.md, *Error handling*).
        assertEquals(ReporterOutcome.NO_REPORTER, CrashReporter.apply(context, enabled = true))
        assertEquals(ReporterOutcome.NO_REPORTER, CrashReporter.apply(context, enabled = false))
    }

    @Test
    fun `installing on a build with no reporter is a no-op that does not throw`() {
        CrashReporting.install(context)
    }
}
