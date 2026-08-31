package app.snoozemo.snooze

import android.Manifest
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import app.snoozemo.R
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.EndReason
import app.snoozemo.core.TrackingMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver

/**
 * The window between asking the calendar and hearing back (SPEC.md §4.3).
 *
 * The read is a cross-process query and the answer reposts the ongoing card,
 * so the snooze can end or be replaced while it is out. Reposting for the
 * record captured before the query would put `Snoozing` back over a phone that
 * has just been let ring — principle 1's failure arriving through a diagnostic
 * nicety (Codex, PR #156).
 *
 * Driven by holding the executor's runnable rather than by timing: the test
 * *is* the race, and a `Thread.sleep` version of it would pass most of the time
 * either way. The provider is faked because Robolectric ships none, and
 * without a row to find nothing reposts and the whole scenario passes
 * vacuously.
 */
@RunWith(RobolectricTestRunner::class)
class SnoozeNotificationsCalendarTest {

    /**
     * Real now, not a frozen instant: `SnoozeClock` reads the system clock and
     * this test cannot fake it, so the fixture is built around the same clock
     * the code under test will read.
     */
    private val now: Instant = Instant.now()

    /** The calendar read, held rather than run, so the test decides when it lands. */
    private val held = mutableListOf<Runnable>()

    /** An end that clears the 30-minute floor and sits well inside the cap. */
    private val meetingEnd: Instant = now.plus(Duration.ofHours(1))

    private lateinit var provider: OneMeetingProvider

    @Before
    fun reset() {
        SnoozeNotifications.resetForTest()
        SnoozeNotifications.readCalendar = Executor { held.add(it) }
        // Without the grant the read never starts, and there is no window to
        // race.
        shadowOf(appContext).grantPermissions(Manifest.permission.READ_CALENDAR)
        provider = OneMeetingProvider(meetingEnd)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        ActiveSnoozeStore(appContext).clear()
    }

    /** Answers any `Instances` query with a single end time, and counts them. */
    private class OneMeetingProvider(private val end: Instant) : ContentProvider() {

        /** How many times the calendar has actually been asked. */
        var queries: Int = 0
            private set

        /**
         * Run as the query is answered, so a test can place an event *inside*
         * the window between the read and the post — which is the only place
         * some of these races exist.
         */
        var onQuery: () -> Unit = {}

        /** Cleared for the case where the calendar answers with nothing. */
        var hasMeeting: Boolean = true

        /** The selection the last query carried, for asserting what it excludes. */
        var lastSelection: String? = null
            private set

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            queries++
            lastSelection = selection
            onQuery()
            return MatrixCursor(arrayOf(CalendarContract.Instances.END)).apply {
                if (hasMeeting) addRow(arrayOf(end.toEpochMilli()))
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private fun ongoingCards(): List<android.app.Notification> =
        shadowOf(appContext.getSystemService(NotificationManager::class.java))
            .allNotifications
            .filter { shadowOf(it).contentTitle?.toString() == stringOf(R.string.ongoing_title) }

    /** The ongoing card as it stands now — later posts replace it by id. */
    private fun currentOngoing(): android.app.Notification =
        requireNotNull(
            shadowOf(appContext.getSystemService(NotificationManager::class.java))
                .getNotification(SnoozeNotifications.ID_ONGOING),
        )

    /**
     * Runs [steps] one per `showOngoing` post, in order, and nothing once they
     * run out — so a test names only the firings it cares about.
     */
    private fun atEachPost(steps: List<() -> Unit>) {
        val remaining = ArrayDeque(steps)
        SnoozeNotifications.betweenReadAndPost = { remaining.removeFirstOrNull()?.invoke() }
    }

    private fun ongoingIsUp(): Boolean =
        shadowOf(appContext.getSystemService(NotificationManager::class.java))
            .getNotification(SnoozeNotifications.ID_ONGOING) != null

    @Test
    fun `the answer adds the third action while the snooze still runs`() {
        // The ordinary path, asserted first: the guard below must not reject
        // it, or the feature would never appear at all.
        val record = snoozeFixture(now)
        ActiveSnoozeStore(appContext).save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        assertEquals("the first card carries the two it always had", 2, ongoingCards().last().actions.size)

        held.single().run()

        assertEquals("the answer should have added one", 3, ongoingCards().last().actions.size)
    }

    @Test
    fun `an answer that lands after the snooze ended does not put the card back`() {
        val record = snoozeFixture(now)
        val store = ActiveSnoozeStore(appContext)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        assertNotNull("the calendar read should have been queued", held.firstOrNull())

        // The snooze ends while the query is out: the record is erased and the
        // card comes down, exactly as a departure or the cap leaves things.
        store.clear()
        notifications.cancelOngoing()
        assertFalse(ongoingIsUp())

        held.single().run()

        assertFalse(
            "a calendar answer must not revive a snooze that is over",
            ongoingIsUp(),
        )
    }

    @Test
    fun `a teardown that cancels before erasing the record still blocks the repost`() {
        // The ordering a record check alone cannot see: teardown takes the card
        // down first and erases the record after, so in the gap the store still
        // reads as a snooze running and the stale answer would post over it —
        // leaving `Snoozing` with nothing scheduled to remove it (Codex,
        // PR #156, second round). The generation counter is what catches this.
        val record = snoozeFixture(now)
        val store = ActiveSnoozeStore(appContext)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        val staleRead = held.single()

        // Card down, record still on disk — the gap.
        notifications.cancelOngoing()
        assertFalse(ongoingIsUp())

        staleRead.run()

        assertFalse(
            "a cancelled card must not come back while the record lags behind it",
            ongoingIsUp(),
        )
    }

    @Test
    fun `a degraded snooze does not get its old tracking line back`() {
        // Presence can drop a snooze from FULL to DURATION_ONLY while the query
        // is out. Identity and cap are untouched by that, so the answer is
        // still *for* this snooze — but the card built alongside it carries the
        // old mode, and posting it would put `Ends when you leave` back over a
        // snooze that is now only a timer. §4.3's whole point is that a snooze
        // which is really a timer must never look tracked (Codex, PR #156).
        val store = ActiveSnoozeStore(appContext)
        val record = snoozeFixture(now)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        val staleRead = held.single()

        val degraded = record.copy(
            mode = TrackingMode.DURATION_ONLY,
            degradation = DegradationCause.NO_LOCATION_FIX,
        )
        store.save(degraded)
        notifications.showOngoing(degraded)
        val degradedText = shadowOf(currentOngoing()).contentText.toString()

        staleRead.run()

        assertEquals(
            "the stale card put the tracked wording back over a degraded snooze",
            degradedText,
            shadowOf(currentOngoing()).contentText.toString(),
        )
    }

    @Test
    fun `the offer reaches the degraded card without another state change`() {
        // The other half, and the one an earlier version of this test papered
        // over by calling `showOngoing` a third time (Codex, PR #156): nothing
        // *else* happens after a degradation, so the two runnables already
        // queued are the whole story. The first caches and finds its own card
        // stale; the second finds the cache populated and returns. Unless the
        // first rebuilds for the live record, the snooze sits on two actions
        // until some unrelated transition — which on a duration-only snooze may
        // be the cap, hours away.
        val store = ActiveSnoozeStore(appContext)
        val record = snoozeFixture(now)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        val degraded = record.copy(
            mode = TrackingMode.DURATION_ONLY,
            degradation = DegradationCause.NO_LOCATION_FIX,
        )
        store.save(degraded)
        notifications.showOngoing(degraded)
        assertEquals("both posts queue, since neither has an answer yet", 2, held.size)

        // Drain what is queued, and only that — no third post from the test.
        val queries = provider.queries
        while (held.isNotEmpty()) held.removeAt(0).run()

        assertEquals("the action never arrived", 3, currentOngoing().actions.size)
        assertEquals(
            "and the card is the degraded one, not the tracked one it replaced",
            stringOf(R.string.ongoing_timer_only),
            shadowOf(currentOngoing()).contentText.toString().substringBefore(" —"),
        )
        assertEquals("one calendar read, not two", queries + 1, provider.queries)
    }

    @Test
    fun `an ordinary ending blocks the repost too, not just a cancel`() {
        // `showEnded` is the teardown a stale answer is most likely to race —
        // it is what a departure and the cap both go through — so a takedown
        // that skipped the lock and the counter would leave the commonest case
        // of all outside the protocol (Codex, PR #156).
        val record = snoozeFixture(now)
        val store = ActiveSnoozeStore(appContext)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        val staleRead = held.single()

        // Ended, with the record not yet erased — the gap.
        notifications.showEnded(EndReason.DEPARTURE)
        assertFalse(ongoingIsUp())

        staleRead.run()

        assertFalse(
            "an ended snooze must not have its card put back by a calendar answer",
            ongoingIsUp(),
        )
    }

    @Test
    fun `a revocation during the query is not overwritten by its answer`() {
        // The window is narrow and specific: the permission has to go *after*
        // the query has already returned a row, so the worker holds a real
        // three-action card, and *before* it posts. Revoked any earlier and the
        // query itself comes back empty, which is why this is driven from
        // inside the provider rather than around it.
        val record = snoozeFixture(now)
        ActiveSnoozeStore(appContext).save(record)
        val notifications = SnoozeNotifications(appContext)
        provider.onQuery = { shadowOf(appContext).denyPermissions(Manifest.permission.READ_CALENDAR) }

        notifications.showOngoing(record)
        held.single().run()

        assertEquals(
            "the answer outlived the permission it was read under",
            2,
            currentOngoing().actions.size,
        )
    }

    @Test
    fun `a card posted after the answer was cached is not overwritten by it`() {
        // The window the generation counter was widened for. The record is
        // loaded, the answer cached, and *then* something posts a newer card —
        // a degradation, here. The repost that follows was decided against the
        // older state, so it has to lose.
        //
        // Driven from the seam rather than by timing, because both halves live
        // in one runnable and nothing else can get between them.
        val store = ActiveSnoozeStore(appContext)
        val record = snoozeFixture(now)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)
        val degraded = record.copy(
            mode = TrackingMode.DURATION_ONLY,
            degradation = DegradationCause.LOCATION_SERVICES_OFF,
        )
        var degradedText: String? = null
        SnoozeNotifications.betweenAnswerAndRepost = {
            store.save(degraded)
            notifications.showOngoing(degraded)
            degradedText = shadowOf(currentOngoing()).contentText.toString()
        }

        notifications.showOngoing(record)
        held.single().run()

        assertEquals(
            "the repost overwrote a card posted after the answer was cached",
            degradedText,
            shadowOf(currentOngoing()).contentText.toString(),
        )
    }

    @Test
    fun `an answer with no meeting does not repost over a newer card`() {
        // The repost builds from the *persisted* record, and
        // `onTrackingChanged` posts the correct in-memory card even when its
        // `store.save` is refused — so in that window the store is behind the
        // display. When the answer adds nothing, reposting can only put the
        // pre-degradation mode line back, and there is no third action to be
        // gained in exchange.
        //
        // The failed save is modeled by leaving the store holding the old
        // record while the degraded card is posted, which is exactly the state
        // a refused write produces.
        val store = ActiveSnoozeStore(appContext)
        val record = snoozeFixture(now)
        store.save(record)
        provider.hasMeeting = false
        val notifications = SnoozeNotifications(appContext)
        notifications.showOngoing(record)

        val degraded = record.copy(
            mode = TrackingMode.DURATION_ONLY,
            degradation = DegradationCause.LOCATION_SERVICES_OFF,
        )
        notifications.showOngoing(degraded)
        val degradedText = shadowOf(currentOngoing()).contentText.toString()

        // Both posts queued a read before either answer landed; the second
        // finds the cache the first wrote and returns.
        held.toList().forEach { it.run() }

        assertEquals(
            "an answer that adds nothing reposted the stale persisted mode",
            degradedText,
            shadowOf(currentOngoing()).contentText.toString(),
        )
    }

    @Test
    fun `a card built before the answer landed picks it up after posting`() {
        // The other side of the same race, and the one the generation guard
        // alone cannot fix. A card is built from a cache that is empty, the
        // worker commits its answer and reposts, and *then* the older card
        // reaches the lock — it takes the generation, so the worker's repost is
        // abandoned, and what stands is the two-action card. Nothing reposts
        // the ongoing card on a timer, so it would stand until the next state
        // change.
        //
        // Driven from the seam because the read and the post are one method,
        // and the gap between them is not otherwise reachable. Queued rather
        // than a single lambda: the worker's own repost runs through the same
        // method and fires the seam again, so each firing takes the next entry
        // and an empty queue is a no-op.
        val store = ActiveSnoozeStore(appContext)
        val record = snoozeFixture(now)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)
        notifications.showOngoing(record)

        val degraded = record.copy(
            mode = TrackingMode.DURATION_ONLY,
            degradation = DegradationCause.LOCATION_SERVICES_OFF,
        )
        store.save(degraded)
        atEachPost(listOf({ held.single().run() }))

        notifications.showOngoing(degraded)

        assertEquals(
            "the card built before the answer landed kept the stale offer",
            3,
            currentOngoing().actions.size,
        )
    }

    @Test
    fun `the follow-up post loses to a takedown that beat it`() {
        // The follow-up carries a guard of its own — the generation its own
        // first post wrote — and this is what that guard is for: the phone has
        // been let ring between the two, so correcting the offer would put
        // `Snoozing` back over it. The record is left in place, since a
        // teardown cancels the card before erasing it.
        //
        // Third entry, because the seam fires on the outer post, on the
        // worker's repost, and again before the follow-up.
        val store = ActiveSnoozeStore(appContext)
        val record = snoozeFixture(now)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)
        notifications.showOngoing(record)

        atEachPost(
            listOf(
                { held.single().run() },
                {},
                { notifications.cancelOngoing() },
            ),
        )

        notifications.showOngoing(record)

        assertFalse(
            "the follow-up put the card back over a phone that had been let ring",
            ongoingIsUp(),
        )
    }

    @Test
    fun `a takedown after the answer was cached is not undone by the repost`() {
        // The other thing landing in that gap, and the reason the guard cannot
        // simply be "post whatever is freshest": a takedown means the phone has
        // been let ring, and reposting would put `Snoozing` back over it. The
        // record is deliberately left in place, since a teardown cancels the
        // card before erasing it.
        val record = snoozeFixture(now)
        ActiveSnoozeStore(appContext).save(record)
        val notifications = SnoozeNotifications(appContext)
        SnoozeNotifications.betweenAnswerAndRepost = { notifications.cancelOngoing() }

        notifications.showOngoing(record)
        held.single().run()

        assertFalse("the repost put a canceled card back up", ongoingIsUp())
    }

    @Test
    fun `an answer for a replaced snooze does not repost the old countdown`() {
        // Both cards carry the same id, so the stale one does not stack — it
        // *replaces*. What the user would see is the previous snooze's
        // countdown standing over the snooze actually running, which is why
        // this asserts the deadline rather than a count.
        val store = ActiveSnoozeStore(appContext)
        val record = snoozeFixture(now)
        store.save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        val staleRead = held.single()

        // A different snooze, ending much sooner, is running by the time the
        // first answer lands.
        val replacement = snoozeFixture(now, startedAgo = Duration.ZERO, capIn = Duration.ofHours(2))
        store.save(replacement)
        notifications.showOngoing(replacement)
        val deadlineOnScreen = currentOngoing().`when`

        staleRead.run()

        assertEquals(
            "the replaced snooze's countdown came back over the running one",
            deadlineOnScreen,
            currentOngoing().`when`,
        )
    }

    @Test
    fun `a grant part way through a snooze is not answered from the denied cache`() {
        // The row that grants this permission asks the service to repost, and
        // that repost is the only chance a running snooze gets. Serving it from
        // the entry cached while the calendar was unreadable means the action
        // never appears for the snooze the user granted access *for* (Codex,
        // PR #156).
        val record = snoozeFixture(now)
        ActiveSnoozeStore(appContext).save(record)
        shadowOf(appContext).denyPermissions(Manifest.permission.READ_CALENDAR)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        assertTrue("nothing to read, so nothing should be queued", held.isEmpty())
        assertEquals(2, currentOngoing().actions.size)

        shadowOf(appContext).grantPermissions(Manifest.permission.READ_CALENDAR)
        notifications.showOngoing(record)
        held.single().run()

        assertEquals("the grant should have reached the card", 3, currentOngoing().actions.size)
    }

    @Test
    fun `a revoked permission does not leave the offered time on the card`() {
        val record = snoozeFixture(now)
        ActiveSnoozeStore(appContext).save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        held.single().run()
        assertEquals(3, currentOngoing().actions.size)

        // Taken back mid-snooze. The time is still in the cache, but the
        // permission it was read under is gone, so the card must stop offering
        // it rather than keeping a promise the app can no longer make.
        held.clear()
        shadowOf(appContext).denyPermissions(Manifest.permission.READ_CALENDAR)
        notifications.showOngoing(record)

        assertEquals("a revoked calendar must not keep its offer", 2, currentOngoing().actions.size)
    }

    @Test
    fun `two posts before the first answer land only one provider read`() {
        // The check that keeps a read from being queued happens at *queue*
        // time, so two posts in quick succession — two state transitions, or a
        // grant and the repost it triggers — both see no cache and both queue.
        // One thread runs them in order, and the second must find the first
        // answer already in hand rather than re-querying (Codex, PR #156).
        val record = snoozeFixture(now)
        ActiveSnoozeStore(appContext).save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        notifications.showOngoing(record)
        assertEquals("both posts queue, since neither has an answer yet", 2, held.size)

        val queries = provider.queries
        held.forEach(Runnable::run)

        assertEquals(
            "the second read should have found the first answer, not asked again",
            queries + 1,
            provider.queries,
        )
    }

    @Test
    fun `an event on a hidden calendar is not offered`() {
        // A calendar the user has switched off is one whose events they have
        // said they do not want to see, so it must not supply the earliest end.
        // `Instances.query` adds the visibility predicate for you; a raw query
        // against `CONTENT_URI` does not (Codex, PR #156).
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(snoozeFixture(now))
        held.single().run()

        assertTrue(
            "the query must exclude calendars the user has hidden",
            provider.lastSelection.orEmpty().contains("${CalendarContract.Instances.VISIBLE} = 1"),
        )
    }

    /**
     * Which rows the production selection actually keeps, evaluated by SQLite
     * rather than matched as text.
     *
     * The trap here is one a string-contains assertion sails past: NULL, where
     * both `status = CONFIRMED` and a bare `status != CANCELED` silently drop
     * every event that carries no status. Running the real string is the only
     * way that shows up — and it is also what says whether `end`, a SQL
     * keyword, parses unquoted in a selection (it does; the quoting in the
     * query is precaution, not necessity).
     *
     * Each row is otherwise an ordinary meeting — visible, timed, accepted,
     * busy — so the only thing under test is the column the case varies.
     */
    private fun rowsKeptBySelection(rows: List<Triple<String, Int?, Instant>>): List<String> {
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        SnoozeNotifications(appContext).showOngoing(snoozeFixture(now))
        held.single().run()
        val selection = requireNotNull(provider.lastSelection) { "the query carried no selection" }

        return SQLiteDatabase.create(null).use { db ->
            db.execSQL(
                "CREATE TABLE instances (" +
                    "label TEXT, " +
                    "${CalendarContract.Instances.VISIBLE} INTEGER, " +
                    "${CalendarContract.Instances.ALL_DAY} INTEGER, " +
                    "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} INTEGER, " +
                    "${CalendarContract.Instances.AVAILABILITY} INTEGER, " +
                    "${CalendarContract.Instances.STATUS} INTEGER, " +
                    "\"${CalendarContract.Instances.END}\" INTEGER)",
            )
            rows.forEach { (label, status, endsAt) ->
                db.execSQL(
                    "INSERT INTO instances VALUES (?, 1, 0, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        label,
                        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
                        CalendarContract.Instances.AVAILABILITY_BUSY,
                        status,
                        endsAt.toEpochMilli(),
                    ),
                )
            }
            db.query("instances", arrayOf("label"), selection, null, null, null, null)
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        }
    }

    @Test
    fun `a canceled meeting is not offered, and a status-less one still is`() {
        // A provider keeps a canceled invitation as a row rather than deleting
        // it, and a deleted occurrence of a repeating meeting is stored the
        // same way — so without this the button would offer to end the snooze
        // for a meeting that is not happening (Codex, PR #156).
        val soon = now.plus(Duration.ofHours(2))

        val kept = rowsKeptBySelection(
            listOf(
                Triple("confirmed", CalendarContract.Events.STATUS_CONFIRMED, soon),
                Triple("no status", null, soon),
                Triple("canceled", CalendarContract.Events.STATUS_CANCELED, soon),
            ),
        )

        assertEquals(
            "a canceled row must be excluded, and a status-less one kept",
            listOf("confirmed", "no status"),
            kept,
        )
    }

    @Test
    fun `a meeting ending after the cap is never read`() {
        // The range in the URI selects instances that *overlap* it, so a
        // meeting straddling the cap comes back and its end — a time the
        // snooze could never reach — would be read before `MeetingEnd`
        // discarded it. `docs/PRIVACY.md` promises Snoozemo never reads
        // further into the calendar than the running snooze could reach, so
        // the bound belongs in the query, not only in the filter afterwards
        // (Codex, PR #156).
        //
        // The fixture's cap is `now + 7h`; the straddling row starts inside
        // the window like any real one and simply runs past it.
        val kept = rowsKeptBySelection(
            listOf(
                Triple("inside the cap", null, now.plus(Duration.ofHours(6))),
                Triple("straddles the cap", null, now.plus(Duration.ofHours(9))),
            ),
        )

        assertEquals(
            "an end past the cap must not come back from the provider at all",
            listOf("inside the cap"),
            kept,
        )
    }

    @Test
    fun `a timezone change reposts, so the offered time is re-formatted`() {
        // The offer is stored as an `Instant`, which no timezone touches — but
        // the button's *label* is a local time formatted once and then left on
        // screen for hours. Flying with a snooze armed would otherwise leave it
        // naming a wall-clock time the phone no longer agrees with, while the
        // end it sets is correct (Codex, PR #156). A repost is the whole fix,
        // so what this pins is that the broadcast asks for one.
        ActiveSnoozeStore(appContext).save(snoozeFixture(now))
        while (shadowOf(appContext).nextStartedService != null) Unit

        TimeChangedReceiver().onReceive(appContext, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        val started = generateSequence { shadowOf(appContext).nextStartedService }
            .firstOrNull { it.component?.className == SnoozeService::class.java.name }
        assertEquals(SnoozeService.ACTION_REFRESH, started?.action)
    }

    @Test
    fun `a timezone change with no snooze running starts nothing`() {
        ActiveSnoozeStore(appContext).clear()
        while (shadowOf(appContext).nextStartedService != null) Unit

        TimeChangedReceiver().onReceive(appContext, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        assertNull(
            "no card to re-format, so no reason to start the service",
            shadowOf(appContext).nextStartedService,
        )
    }

    @Test
    fun `an answered snooze is not asked about twice`() {
        val record = snoozeFixture(now)
        ActiveSnoozeStore(appContext).save(record)
        val notifications = SnoozeNotifications(appContext)

        notifications.showOngoing(record)
        held.single().run()
        held.clear()

        // The repost the answer itself triggered is what would loop without the
        // cache; so is every later state change reposting this card.
        notifications.showOngoing(record)

        assertTrue("the answer should have been cached, not re-asked", held.isEmpty())
    }
}
