package app.snoozemo.snooze

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.SnoozeDebugLog
import java.time.Instant

/**
 * When the user's meetings end, for the ongoing notification's third action
 * (SPEC.md §4.3).
 *
 * **Times only.** The query asks the provider for one column — `END` — and no
 * title, organizer, location or event id is read at any point. That is not an
 * oversight to be tidied up later: a title on a lock screen is the user's day
 * shown to whoever is holding the phone, and the button needs a time and
 * nothing else (`AGENTS.md`, *Privacy*).
 *
 * **Never on the main thread, and never on the arm path.** This is a
 * `ContentResolver` query across a binder into another app's provider, which is
 * exactly the kind of call SPEC.md §6.9 keeps away from arming. The ongoing
 * notification goes up first with the two actions it has always had; this runs
 * afterwards and the card is reposted if it finds something.
 */
internal object NextMeetings {

    /** Whether the calendar can be read at all; the feature hides itself if not. */
    fun isReadable(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every event end between [now] and [snooze]'s cap, or an empty list.
     *
     * Bounded by the cap because nothing past it can be offered — the service
     * honors a time at or beyond the cap by doing nothing — so a wider window
     * would read more of the user's calendar than the feature can ever use.
     *
     * `Instances` rather than `Events`: a repeating meeting is one row in
     * `Events` and the provider expands it, so querying the events table
     * directly would miss today's occurrence of a weekly stand-up entirely.
     * All-day entries are excluded — they end at midnight and describe a day
     * rather than a meeting, so offering to snooze until one ends is offering
     * the wrong thing.
     */
    fun endsBefore(context: Context, snooze: ActiveSnooze, now: Instant): List<Instant> {
        if (!isReadable(context)) return emptyList()
        val from = now.toEpochMilli()
        val until = snooze.capExpiresAt.toEpochMilli()
        if (until <= from) return emptyList()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, from); ContentUris.appendId(it, until); it.build() }
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.END),
                // Five exclusions, each for a different kind of row that is
                // not a meeting the user is sitting in: a calendar they have
                // switched off is one whose events they have said they do not
                // want to see, an all-day entry is a label on the day rather
                // than something that ends at a useful moment, a declined
                // invitation is one they are not attending, a "free" block is
                // an FYI rather than time they are busy in, and a canceled row
                // is a meeting that is not happening at all (SPEC.md §4.3).
                //
                // `VISIBLE` has to be asked for explicitly. The platform's own
                // `Instances.query` helper adds it; a raw query against
                // `CONTENT_URI` does not, so an old work calendar the user
                // hid would otherwise be free to supply the earliest end and
                // drive the offer (Codex, PR #156).
                //
                // `STATUS` is tested as "not canceled" rather than "is
                // confirmed", and tolerates NULL: a provider keeps a canceled
                // invitation — and a deleted occurrence of a repeating
                // meeting — as a row rather than deleting it, but plenty of
                // ordinary events carry no status at all, and a
                // `STATUS = CONFIRMED` predicate would drop every one of them
                // (Codex, PR #156).
                "${CalendarContract.Instances.VISIBLE} = 1" +
                    " AND ${CalendarContract.Instances.ALL_DAY} = 0" +
                    " AND ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != " +
                    CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED +
                    " AND ${CalendarContract.Instances.AVAILABILITY} != " +
                    CalendarContract.Instances.AVAILABILITY_FREE +
                    " AND (${CalendarContract.Instances.STATUS} IS NULL" +
                    " OR ${CalendarContract.Instances.STATUS} != " +
                    CalendarContract.Events.STATUS_CANCELED + ")" +
                    // The range in the URI selects instances that *overlap*
                    // it, so a meeting running from before the cap to well
                    // after it comes back and its end — a time the snooze
                    // could never reach — is read. `MeetingEnd` discards it,
                    // but only after it has been read, which is a weaker
                    // thing than what `docs/PRIVACY.md` promises: that
                    // Snoozemo never reads further into the calendar than the
                    // running snooze could reach. So the bound is enforced
                    // here, where the promise can actually be kept (Codex,
                    // PR #156). Strictly less than the cap, matching
                    // `MeetingEnd.offerFor` exactly, so nothing offerable is
                    // dropped.
                    //
                    // `end` is quoted, not because it has to be — SQLite
                    // parses the bare keyword in this position, and the test
                    // below was run both ways to check — but because this is
                    // the first predicate to name the column in a *selection*
                    // rather than a projection, and the quoting costs nothing
                    // for not having to depend on that.
                    " AND \"${CalendarContract.Instances.END}\" < " + until,
                null,
                null,
            )?.use { cursor ->
                buildList {
                    val column = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    while (cursor.moveToNext()) {
                        if (!cursor.isNull(column)) add(Instant.ofEpochMilli(cursor.getLong(column)))
                    }
                }
            }.orEmpty()
        } catch (e: RuntimeException) {
            // A provider can be absent, disabled, or throw on a query a
            // particular OEM's calendar does not implement — and the permission
            // can be revoked between the check above and the query. None of
            // that is worth a card: the feature simply does not offer a third
            // action, which is what a user without the permission sees anyway.
            //
            // The exception's message is not logged: a provider is free to put
            // the failing query in it, and that query names the user's calendar
            // (`AGENTS.md`, *Privacy*).
            SnoozeDebugLog.event("calendar: reading the next meeting's end failed (%s)", e.javaClass.simpleName)
            emptyList()
        }
    }
}
