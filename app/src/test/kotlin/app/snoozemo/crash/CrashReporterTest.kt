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

    /**
     * The measurement SDK's own preferences file, which
     * [CrashReporter.awaitAnalyticsOverride] watches. Named here rather than
     * imported because the constant is private to the reporter — if the two
     * ever drift, these tests pass while the production wait watches a file
     * nobody writes, so the name is repeated deliberately and not shared.
     */
    private val measurementPrefs get() =
        context.getSharedPreferences("com.google.android.gms.measurement.prefs", Context.MODE_PRIVATE)

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

    // The tests above cover the dormant-Firebase branch, which is where every
    // Robolectric run lives. The ones below cover what happens *after* that
    // branch, which no test reached before (Codex, PR #166): `apply` returns
    // `NO_REPORTER` here, so the SDK calls and the outcome they produce were
    // unguarded. `CrashReporter.decide` is `apply` with the four SDK effects
    // lifted out, so those decisions are assertable without an initialized
    // FirebaseApp.

    @Test
    fun `one answer is forwarded to both SDKs, whichever way it went`() {
        // The invariant the shared consent rests on: the user is asked one
        // question, so a build where Crashlytics heard yes and Analytics heard
        // nothing — or the reverse — would answer a question nobody was asked.
        for (answer in listOf(true, false)) {
            val effects = RecordingEffects()
            effects.decide(answer)

            assertEquals("crash reports were told the answer", listOf(answer), effects.crashlytics)
            assertEquals("and so was analytics", listOf(answer), effects.analytics)
        }
    }

    @Test
    fun `an opt-out analytics refused is not a finished opt-out`() {
        // The conversion this PR added. Without it the caller persists the
        // switch as off and shows it as off over an SDK that may still be
        // collecting — the promise the card and the Settings row make, broken
        // silently, which is principle 2's failure.
        val effects = RecordingEffects(analyticsApplies = false)

        assertEquals(ReporterOutcome.NOT_DURABLE, effects.decide(enabled = false))
    }

    @Test
    fun `an opt-out analytics refused stops before the reports are dropped`() {
        // Deleting is what discharges the queued-report debt, and it is only
        // safe once the opt-out is actually finished. Reported as not durable
        // *and* still deleted would be the worst of both: the caller retries
        // next launch against reports that are already gone.
        val effects = RecordingEffects(analyticsApplies = false)

        effects.decide(enabled = false)

        assertFalse("the override was not flushed", effects.flushed)
        assertFalse("and nothing was deleted", effects.deleted)
    }

    @Test
    fun `an opt-in analytics refused still applies, since it collects less than allowed`() {
        // Deliberately not symmetric with the opt-out above, and the asymmetry
        // is the decision rather than an oversight: a flag stuck off harms
        // nobody, while refusing the save would discard an answer the user
        // gave and bring the card back because an SDK call threw. The retry is
        // the stored preference, re-applied at the next launch.
        val effects = RecordingEffects(analyticsApplies = false)

        assertEquals(ReporterOutcome.APPLIED, effects.decide(enabled = true))
    }

    @Test
    fun `an opt-out whose override cannot reach disk is not durable either`() {
        val effects = RecordingEffects(flushSucceeds = false)

        assertEquals(ReporterOutcome.NOT_DURABLE, effects.decide(enabled = false))
        assertTrue("the reports are still dropped — that half succeeded", effects.deleted)
    }

    @Test
    fun `a completed opt-out flushes the override before dropping the reports`() {
        val effects = RecordingEffects()

        assertEquals(ReporterOutcome.APPLIED, effects.decide(enabled = false))
        assertEquals("flush, then delete", listOf("flush", "delete"), effects.order)
    }

    @Test
    fun `an analytics override that never lands is not reported as applied`() {
        // Codex, PR #166's P1. `setAnalyticsCollectionEnabled` hands a Runnable
        // to an executor and returns, so returning true at that point claims a
        // durability nothing has established. If the SDK's override never shows
        // up, saying so is the honest answer — the caller converts it to
        // NOT_DURABLE on an opt-out.
        var slept = 0
        val landed = CrashReporter.awaitAnalyticsOverride(
            prefs = measurementPrefs,
            enabled = false,
            attempts = 3,
            sleep = { slept++ },
        )

        assertFalse("nothing was ever written", landed)
        assertEquals("it waited between attempts rather than spinning", 2, slept)
    }

    @Test
    fun `an analytics override is confirmed once the SDK's worker writes it`() {
        // The SDK's write happens on another thread, so the test plays that
        // thread: the first wait is where the value appears. Driven by the
        // injected sleep rather than real elapsed time, so there is no race to
        // lose.
        for (answer in listOf(true, false)) {
            measurementPrefs.edit().clear().commit()
            val landed = CrashReporter.awaitAnalyticsOverride(
                prefs = measurementPrefs,
                enabled = answer,
                attempts = 5,
                sleep = { measurementPrefs.edit().putBoolean(FROM_API_KEY, answer).commit() },
            )

            assertTrue("the override matched what was asked for", landed)
        }
    }

    @Test
    fun `an analytics override that lands on the opposite value is not a match`() {
        // The failure this rules out is a default reading as a match: the key
        // is absent until the SDK writes it, and `getBoolean(key, enabled)`
        // would have returned true for an untouched file half the time.
        measurementPrefs.edit().putBoolean(FROM_API_KEY, true).commit()

        assertFalse(
            "an override still saying on is not a completed opt-out",
            CrashReporter.awaitAnalyticsOverride(
                prefs = measurementPrefs,
                enabled = false,
                attempts = 2,
                sleep = {},
            ),
        )
    }

    @Test
    fun `an opt-in neither flushes nor deletes`() {
        // There is no queued-report debt to discharge on the way in, and the
        // pre-enable discard is the caller's, not this method's.
        val effects = RecordingEffects()

        assertEquals(ReporterOutcome.APPLIED, effects.decide(enabled = true))
        assertFalse(effects.flushed)
        assertFalse(effects.deleted)
    }
}

/** The measurement SDK's key for an override set through its API. */
private const val FROM_API_KEY = "measurement_enabled_from_api"

/**
 * Records what [CrashReporter.decide] asked of each SDK, and lets a test say
 * which of them refuse.
 */
private class RecordingEffects(
    private val analyticsApplies: Boolean = true,
    private val flushSucceeds: Boolean = true,
) {
    val crashlytics = mutableListOf<Boolean>()
    val analytics = mutableListOf<Boolean>()
    val order = mutableListOf<String>()

    val flushed get() = "flush" in order
    val deleted get() = "delete" in order

    fun decide(enabled: Boolean): ReporterOutcome = CrashReporter.decide(
        enabled = enabled,
        setCrashlyticsEnabled = { crashlytics += it },
        setAnalyticsEnabled = { analytics += it; analyticsApplies },
        flushOverride = { order += "flush"; flushSucceeds },
        deleteUnsentReports = { order += "delete" },
    )
}
