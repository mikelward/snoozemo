package app.snoozemo

/**
 * The application every Robolectric test gets unless it names its own
 * (`src/test/resources/robolectric.properties`): the real one, minus the
 * startup ringer check.
 *
 * That check is a daemon thread that takes a process-wide lock five seconds
 * after it starts, so one started by any test lands in whichever test happens
 * to be running then. Tests that exercise it call `reconcileRingerInBackground`
 * themselves and join the thread it returns.
 */
class TestSnoozemoApplication : SnoozemoApplication() {
    override val reconcilesRingerOnStart: Boolean get() = false
}
