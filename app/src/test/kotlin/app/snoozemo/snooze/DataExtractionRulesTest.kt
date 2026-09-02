package app.snoozemo.snooze

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.snoozemo.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * What a phone swap may and may not carry (SPEC.md §12).
 *
 * `data_extraction_rules.xml` excludes **by name**, which is the kind of list
 * that rots quietly: a new preferences file is runtime state nobody remembered
 * to name, and the failure only shows up on somebody's new handset. A ringer
 * loan reached that state once (Codex, PR #176), so the invariant is pinned
 * here rather than left to review.
 *
 * The negative half matters as much: the *chosen* ceiling is configuration and
 * must survive the swap (principle 3), so a future tidy-up that excludes the
 * whole family in bulk fails this too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataExtractionRulesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `every device-local preferences file is left behind on a phone swap`() {
        val excluded = deviceTransferExclusions()

        // Read the parse before asserting against it: a list derived from a
        // parse that found nothing would otherwise pass every check below.
        assertTrue("no device-transfer exclusions were parsed", excluded.isNotEmpty())

        listOf(
            // The running snooze and the failure it may still owe.
            "active_snooze.xml",
            "pending_failure.xml",
            // Prompts already shown, over permissions that do not transfer.
            "notification_prompt.xml",
            "location_prompt.xml",
            // This phone's own zen rule id, and this phone's own tile.
            "zen_rule.xml",
            "tile_presence.xml",
            // The borrowed ringer, its retry tally, and the ceiling in force.
            "ringer_loan.xml",
        ).forEach { file ->
            assertTrue("$file must not follow the user onto a new phone", file in excluded)
        }
    }

    @Test
    fun `the chosen ceiling does follow the user onto a new phone`() {
        // Configuration, not runtime state: the user picked it and nothing else
        // holds it, so excluding this one would be losing their setting.
        assertTrue("snooze_ringer.xml" !in deviceTransferExclusions())
    }

    /** The `sharedpref` paths named under `<device-transfer>`. */
    private fun deviceTransferExclusions(): Set<String> {
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        val excluded = mutableSetOf<String>()
        var inDeviceTransfer = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "device-transfer" -> inDeviceTransfer = true
                    "exclude" -> if (inDeviceTransfer) {
                        val domain = parser.getAttributeValue(null, "domain")
                        val path = parser.getAttributeValue(null, "path")
                        if (domain == "sharedpref" && path != null) excluded += path
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "device-transfer") inDeviceTransfer = false
            }
        }
        return excluded
    }
}
