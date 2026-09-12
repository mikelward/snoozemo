package app.snoozemo.snooze

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.EndCondition
import app.snoozemo.core.SnoozeDebugLog
import java.time.Instant
import java.time.ZoneId

/**
 * The end-condition sheet's state and its commit lifecycle (SPEC.md §4.4),
 * owned in one place so both ways of arming behave identically.
 *
 * **Why this is shared rather than copied** (maintainer, 2026-08-30). The
 * sheet started life inside `TileTrampolineActivity`, which is where the tile
 * arms — so a snooze armed from the app screen's `Snooze` button silently took
 * the default cap while the tile offered the choice, for the same action.
 * Closing that by writing the flow out a second time in `MainActivity` would
 * have left two copies of a sequence that took a long review to settle, and
 * the settings, the refusal handling and the outcome watching would have
 * drifted apart the first time one of them was touched.
 *
 * What stays with each caller is what genuinely differs: the trampoline's
 * transparent window, `singleInstance` re-entry and rotation handling; the app
 * screen's ordinary composition. What lives here is everything neither of them
 * has a reason to do differently.
 *
 * Not a `ViewModel`: the trampoline already keeps its own retained object for
 * a quite different purpose (`RecreationMarker`), and a second one whose
 * lifetime rules differed between the two hosts is the kind of subtlety this
 * class exists to stop having two of. The host owns the instance and calls
 * [close] when it is done.
 */
internal class EndChoiceController(
    /**
     * The snooze running right now, as the host best knows it.
     *
     * Typed as the record rather than as a bare ceiling, which is what makes it
     * safe where its predecessor was not: the controller compares
     * [ActiveSnooze.startedAt] against the offer's own before using it, so a
     * host that answers with a stale or absent copy is *detected* instead of
     * silently supplying `now + DEFAULT_CAP`.
     *
     * Needed because a cached ceiling can go stale under a fixed identity: a
     * wall-clock change reconciles `capExpiresAt` onto the new frame while
     * `startedAt` deliberately stays put (see `ActiveSnooze.capCeilingAt`), so
     * the same snooze can outlive the ceiling the offer was built with (Codex,
     * PR #155).
     */
    /**
     * What to call this host in the debug log — "the app screen" or "the
     * sheet".
     *
     * Every other tap that reaches the service names its own surface
     * (SPEC.md §4.6), and a refinement is a tap like any other: the rapid
     * arm/end/refine sequence this log exists to reconstruct runs through
     * here, and an `end-condition` line that says only *what* was chosen
     * cannot say which control the user touched to choose it (Codex, PR
     * #238). The controller cannot know this for itself — it is shared by
     * both hosts — so the host says.
     */
    private val surface: String,
    private val currentRecord: () -> ActiveSnooze?,
    /**
     * Hands the chosen time to the service. False means it never dispatched,
     * so no outcome is coming and this settles the commit itself.
     */
    private val chooseEnd: (endsAt: Instant, requestId: Long, forSnooze: Instant?) -> Boolean,
    /**
     * Puts the cap back to its ceiling, so the snooze runs until departure
     * again. Same contract as [chooseEnd] — false means it never dispatched.
     *
     * Separate from [chooseEnd] because it names no time: the target is the
     * record's own `capCeilingAt`, which only the service can read, and
     * sending a time this side computed would be sending a guess about a
     * ceiling a clock change may already have moved.
     */
    private val restoreDeparture: (requestId: Long, forSnooze: Instant?) -> Boolean,
    /**
     * Asks for the snooze to end when the phone moves as well (SPEC.md §4.4).
     * Same contract as [chooseEnd] — false means it never dispatched.
     *
     * Through this lifecycle like the other two, because it can be declined
     * on grounds the screen cannot see: the record write can be refused, and
     * the service rolls the choice back when the platform will not register
     * the sensor (Codex, PR #255). Drawn as a plain choice the row has no
     * state of its own to fall back on, so a refusal has to be reported where
     * the tap happened, exactly as a declined time is.
     */
    private val chooseMotionEnd: (requestId: Long, forSnooze: Instant?) -> Boolean,
    /** Subscribes to what the service said; closed on every settled commit. */
    private val watchOutcome: (requestId: Long, onOutcome: (EndChoiceResult) -> Unit) -> AutoCloseable,
    /** Called when the sheet has nothing left to ask and should go away. */
    private val onDismiss: () -> Unit,
    /**
     * Whether this host also offers the rows with **no snooze running**, as
     * a way to start one (SPEC.md §4.4, maintainer, 2026-09-10).
     *
     * An offer seeded from a null record is such an offer: its identity is
     * null, its ceiling is the cap a snooze would start with, and a tap arms
     * rather than refines. Only a host that says so gets one — the sheets are
     * offered over a snooze that exists, and for them a null record in
     * [reconcile] still means the offer is over.
     */
    private val offersToStart: Boolean = false,
    /** Now, injectable so the refusal re-seed is reachable from a JVM test. */
    private val clock: () -> Instant = { Instant.ofEpochMilli(System.currentTimeMillis()) },
    /** The zone the offered times are rounded in — the user's own. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    /**
     * The time currently on offer, or null while no sheet is up.
     *
     * Held here rather than inside the composable because a second arm can
     * re-seed it under a sheet that is already showing — that arm is a *new*
     * snooze, so the time being offered has to move with it rather than still
     * naming an hour from the first tap.
     */
    var endCondition by mutableStateOf<EndCondition?>(null)
        @VisibleForTesting internal set

    /**
     * Which snooze the current offer was made against — its `startedAt`, which
     * never moves for a given snooze — or null while no sheet is up.
     *
     * **The sheet needs an identity for its snooze, and this is it.** Without
     * one, every check here asks a proxy question: is *some* snooze running,
     * does *some* record still offer a choice. Each proxy is right until a path
     * turns up where it isn't — a sheet outliving its snooze while the screen
     * is stopped, another snooze armed before the user comes back, an offer
     * seeded from a record the screen had not caught up with. Five review
     * rounds on PR #152 each found one of those and each would have taken its
     * own guard; comparing identities answers all of them at once.
     */
    var offerFor by mutableStateOf<Instant?>(null)
        @VisibleForTesting internal set

    /**
     * Whether the standing offer would start a snooze rather than refine one:
     * it names no snooze, on a host that offers to start ([offersToStart]).
     * False when nothing is offered at all.
     */
    val startsASnooze: Boolean get() = offersToStart && endCondition != null && offerFor == null

    /**
     * Whether a chosen time is with the service and unanswered. The rows are
     * inert while it is, so a second tap cannot stack a second commit on the
     * first.
     */
    var committing by mutableStateOf(false)
        // Settable from a test for the same reason [endCondition] is: a
        // Robolectric host has no Compose rule to drive the row with, and a
        // commit in flight across a configuration change is a state worth
        // covering.
        @VisibleForTesting internal set

    /**
     * Whether the service refused the time just chosen. Shown in the sheet
     * rather than dismissing, because a dismissal on a refused tap is
     * indistinguishable from one on an accepted tap.
     *
     * A *refusal* only — a chosen time that applied but left an exit armed is a
     * partial success rather than a refusal, and the line that says so reads
     * from the record's [ActiveSnooze.isPartialTimer] rather than a flag here.
     * Keeping this the one flag saved and restored is what stops a restored
     * line reporting the opposite failure from the one that happened.
     */
    var commitFailed by mutableStateOf(false)
        private set

    /**
     * Whether the standing offer's time is the user's rather than the clock's.
     *
     * Only an offer to start needs this. A running snooze's offer is anchored
     * to a record and is left alone by [reconcile] unless it goes stale, but
     * an offer to *start* is rebuilt on every minute tick ([refreshStart]) —
     * which was free while its steppers armed on the spot and there was
     * nothing to preserve. Now that they step, a rebuild would throw the
     * user's chosen time away once a minute (maintainer, 2026-09-12).
     */
    var steppedByUser by mutableStateOf(false)
        private set

    /**
     * The request the outstanding commit is waiting on, or 0 when none is.
     *
     * Saved and restored with the sheet, because the answer belongs to the
     * *request* rather than to the activity that made it: a rotation replaces
     * the host while the service is still working, and the replacement has to
     * resume listening for that same request rather than for whatever the
     * channel reports next.
     */
    var committingRequestId: Long = 0L
        @VisibleForTesting internal set

    private var outcomeWatch: AutoCloseable? = null

    /**
     * Opens the sheet on a fresh offer, or moves an open one onto a new arm.
     *
     * Re-seeded on every arm rather than only the first: a second arm while
     * the sheet is up armed a *new* snooze, so an hour from the first is no
     * longer the offer being made.
     */
    fun seed(record: ActiveSnooze?, now: Instant = clock()) {
        // The ceiling comes from the record the caller checked, never from a
        // copy it keeps elsewhere: the app screen's warm `activeSnooze` can be
        // null or stale exactly when a sheet is being seeded, and a ceiling of
        // `now + DEFAULT_CAP` then lets the offer walk past the running
        // snooze's real cap — which the service honors by doing nothing while
        // reporting it applied (Codex, PR #152).
        //
        // Nothing about a partial success is carried across a reseed any more:
        // it is derived from the record's [ActiveSnooze.isPartialTimer], so the
        // offer to start becoming the snooze it just made — the one transition
        // that used to need a carried flag (Codex, PR #267) — draws the line
        // again the moment the host reads that snooze's record back.
        offerFor = record?.startedAt
        endCondition = EndCondition.seededAt(now, EndCondition.ceilingFor(record, now), zone())
        commitFailed = false
        // A fresh offer is the clock's again until the user moves it.
        steppedByUser = false
    }

    /**
     * Rebuilds an offer to start against the clock as it is now, keeping a
     * refusal showing and a time the user chose.
     *
     * An untouched offer is clock-derived through and through — the seeded
     * time, the floor, the ceiling — so rebuilding it on every tick costs
     * nothing and keeps its ceiling equal to the cap the service would
     * actually set, forward through ordinary time and back across a wall-clock
     * change alike (Codex, PR #256).
     *
     * **A stepped one is not.** The idle rows' steppers used to arm on the
     * spot, so there was never a chosen position to preserve; they step now
     * (maintainer, 2026-09-12), and rebuilding whole would move the user's
     * time back under them once a minute. So the bounds are refreshed and the
     * time is kept — except when the clock has carried it inside the floor,
     * where a kept time is one the service would decline and reseeding is what
     * leaves a working offer. The ceiling clamp is the same reason in the other
     * direction: a backward clock change can put a stepped time above the cap
     * the arm would set.
     *
     * A no-op when nothing is offered, on a host that does not offer to start,
     * or while a commit is out.
     */
    fun refreshStart(now: Instant = clock()) {
        if (!startsASnooze || committing) return
        val failed = commitFailed
        val standing = endCondition
        val floor = now.plus(ActiveSnooze.MIN_CAP)
        if (standing == null || !steppedByUser || standing.endsAt.isBefore(floor)) {
            seed(null, now)
            commitFailed = failed
            return
        }
        val ceiling = EndCondition.ceilingFor(null, now)
        endCondition = standing.copy(
            endsAt = standing.endsAt.coerceAtMost(ceiling),
            floor = floor,
            ceiling = ceiling,
        )
    }

    fun stepUp() {
        endCondition = endCondition?.stepUp()?.also { steppedByUser = true }
    }

    fun stepDown() {
        endCondition = endCondition?.stepDown()?.also { steppedByUser = true }
    }

    /**
     * Sends a chosen time to the service and **waits to hear what happened**
     * before dismissing. A second tap while one is out is ignored.
     */
    fun commit(endsAt: Instant) =
        dispatch("an end time") { requestId, forSnooze -> chooseEnd(endsAt, requestId, forSnooze) }

    /**
     * Chooses "until I leave" as a real end condition rather than as a
     * dismissal.
     *
     * Lengthens the cap back to the snooze's own ceiling, which is what makes
     * it a choice rather than a label: a snooze shortened to an hour half an
     * hour ago has no other way back to the departure it was armed on in one
     * tap. Bounded by the same ceiling `+30 min` is (SPEC.md §4.3), so it can
     * never run past the backstop the snooze started with.
     *
     * No longer the only commit that lengthens — [commit] moves the cap in
     * whichever direction the chosen time lies (maintainer, 2026-09-11) — but
     * still the only one that names no time.
     *
     * Where a sheet appears at the arm, this has nothing to do — the snooze it
     * is offered over is already running to its ceiling — and that host wires
     * the row to a dismissal instead.
     */
    fun commitDeparture() =
        dispatch("until I leave") { requestId, forSnooze -> restoreDeparture(requestId, forSnooze) }

    /**
     * Chooses "until I move": an exit added beside whatever bounds the snooze
     * already, so it names no time and moves no cap. Applied by doing nothing
     * on a snooze that already ends on motion, like `Until I leave` on one
     * already running to its ceiling.
     */
    fun commitMotionEnd() =
        dispatch("until I move") { requestId, forSnooze -> chooseMotionEnd(requestId, forSnooze) }

    /**
     * The commit lifecycle both choices share: one request out at a time, and
     * nothing dismissed until the service has said what happened.
     *
     * A started service is not an applied change: the alarm can still refuse,
     * the record can still fail to write, and the snooze can have ended in
     * between. Dismissing on the start alone left the user believing a time
     * they could no longer see had been taken — and the card those failures
     * post is a notification, invisible to exactly the tile-first user who
     * denied that permission and for whom the tile is the only surface there
     * is (SPEC.md §4.2; Codex, PR #118).
     *
     * So the watch goes on **before** the start, since the service reports
     * from `onStartCommand` on the same looper and an outcome delivered before
     * the watch existed would be one nobody heard.
     *
     * Staying put on a failure matters twice over: the snooze is still running
     * on the cap it had, so nothing is stranded, and the offer is the one place
     * the user is certain to be looking.
     */
    private fun dispatch(
        choice: String,
        start: (requestId: Long, forSnooze: Instant?) -> Boolean,
    ) {
        if (committing) return
        committing = true
        commitFailed = false
        // A fresh identity, which is what keeps an *earlier* answer from
        // settling this one — whether the earlier request was this sheet's
        // previous tap or the other host's, since both surfaces share the
        // channel now (Codex, PR #152).
        committingRequestId = EndChoiceOutcome.nextRequestId()
        outcomeWatch?.close()
        outcomeWatch = watchOutcome(committingRequestId, ::onOutcome)
        // The identity travels with the choice. The check here is redraw-time
        // hygiene; this is what makes it binding, since the service applies the
        // cap and validates the claim in the same pass (Codex, PR #155).
        val started = start(committingRequestId, offerFor)
        // The tap, named like every other one (SPEC.md §4.6), and carrying
        // whether the service took it. After the start for the reason the
        // trampoline's is: nothing goes between a tap and the work it asks
        // for. Only a tap the controller accepted is recorded — the early
        // return above is a second tap while one is still out, which the user
        // made but the app deliberately ignored, and logging it as a commit
        // would credit a refinement that never happened.
        SnoozeDebugLog.event(
            "tap: $choice from $surface" +
                (if (startsASnooze) " (to start a snooze)" else "") +
                (if (started) "" else " (the service refused to start)"),
        )
        if (!started) {
            SnoozeDebugLog.warning("the service refused to start for a chosen end time")
            onOutcome(EndChoiceResult.REFUSED)
        }
    }

    /** What the service said about the time the user chose. */
    @VisibleForTesting
    internal fun onOutcome(result: EndChoiceResult) {
        outcomeWatch?.close()
        outcomeWatch = null
        committing = false
        committingRequestId = 0L
        // `GONE` dismisses like `APPLIED`, and deliberately: the snooze ended
        // under the sheet — a departure, the cap, a capability loss — so there
        // is nothing left to refine and every later tap would fail the same
        // way. Whatever ended it has posted its own card.
        //
        // For an offer that starts a snooze the two mean the same simpler
        // thing — this offer is over: a snooze is running now, or one came
        // and went since the offer was drawn — and the host's dismissal
        // re-reads the record that says which, and draws what there is.
        //
        // **`PARTIAL` keeps the sheet up.** The time took, so there is nothing
        // to retry for its own sake and nothing to reseed — but an exit stayed
        // armed, and dismissing on that is indistinguishable from dismissing on
        // a clean apply, which is principle 2's failure. The line that says so
        // is derived by the rows from the record's [ActiveSnooze.isPartialTimer]
        // now, so there is no flag to raise here — leaving the sheet up is the
        // whole of it. A second tap on the same row retries the removal: the
        // service's "changes nothing" path still runs it.
        if (result == EndChoiceResult.PARTIAL) return
        if (result != EndChoiceResult.REFUSED) {
            dismiss()
            return
        }
        commitFailed = true
        // A sheet left open long enough can be showing a time that has since
        // fallen inside the floor, and the service declines those rather than
        // quietly substituting a later one. Declining alone would make the
        // sheet a dead end — the same tap failing forever — so the offer is
        // reseeded against the clock as it is now.
        val now = clock()
        val standing = endCondition
        if (standing == null || !standing.endsAt.isBefore(now.plus(ActiveSnooze.MIN_CAP))) return
        // An offer to start needs no record to rebuild from: refused means
        // nothing is running, and the clock is the whole of what seeded it.
        if (startsASnooze) {
            seed(null, now)
            commitFailed = true
            return
        }
        // Rebuilt from the record as it is now, not from the ceiling this offer
        // was built with: a clock change moves `capExpiresAt` under a fixed
        // `startedAt`, so the cached ceiling can name an earlier instant than
        // the snooze actually allows — and reseeding against that could put the
        // new offer below the floor too, refusing every retry (Codex, PR #155).
        val live = currentRecord()
        if (live?.startedAt == offerFor && EndCondition.offersAChoice(live, now)) {
            seed(live, now)
            commitFailed = true
            return
        }
        // The host cannot see a live record for this offer — it may not have
        // read one back yet. Left standing with the failure showing rather than
        // rebuilt from something that isn't this snooze; [reconcile] runs on
        // every record read and always has one, so it settles this.
        SnoozeDebugLog.event("end-condition offer left stale: no live record to rebuild it from")
    }

    /** Takes the sheet down without committing; the snooze stands as armed. */
    fun dismiss() {
        endCondition = null
        offerFor = null
        commitFailed = false
        steppedByUser = false
        onDismiss()
    }

    /**
     * Drops or refreshes an offer the running record can no longer honor.
     *
     * Three ways an offer stops belonging to what is actually running, all of
     * which this answers by comparing [offerFor] against the record rather than
     * asking whether *some* snooze is up (Codex, PR #152):
     *
     * - **Its snooze is over** — a departure, the cap, a capability loss. The
     *   sheet goes, rather than waiting for a tap to come back `GONE`.
     * - **A different snooze is running.** The screen can be stopped across a
     *   snooze ending and another arming; without the identity the replacement
     *   passes for the original and the old chosen time is applied to it.
     * - **The offer has gone stale** — the worker finished while the screen was
     *   away, or it simply sat there, and the time on it has fallen inside the
     *   floor. Reseeded rather than dropped: the snooze is still running and
     *   still refinable, so there is a question worth asking, just not that one.
     *
     * Never touches a commit in flight: its answer is coming and settles the
     * sheet itself, and dismissing underneath would lose the refusal message
     * SPEC.md §4.2 requires reach a user who denied notifications.
     */
    fun reconcile(record: ActiveSnooze?, now: Instant = clock()) {
        val standing = endCondition ?: return
        if (committing) return
        // An offer to start stands while nothing is running and goes the
        // moment something is — the host then seeds the running snooze's own.
        val stillOffered =
            if (startsASnooze) record == null
            else record?.startedAt == offerFor && EndCondition.offersAChoice(record, now)
        if (!stillOffered) {
            SnoozeDebugLog.event("end-condition sheet dropped: it was offering times for another snooze")
            dismiss()
            return
        }
        if (standing.endsAt.isBefore(now.plus(ActiveSnooze.MIN_CAP))) {
            SnoozeDebugLog.event("end-condition sheet reseeded: its offer had gone stale")
            seed(record, now)
        }
    }

    /**
     * Puts a saved sheet back exactly where it was, the chosen time included
     * — stepping to it is the only work the user has done here, and reseeding
     * would silently undo it.
     *
     * Assigns rather than seeds. An answer that arrived while the host was
     * being recreated is held by [EndChoiceOutcome] — no watch existed to
     * receive it — so a commit in flight takes that first and settles.
     *
     * **[configurationChange] is the distinction the rest of this turns on,
     * and it cannot be recovered from the bundle** (Codex, PR #152). Saved
     * instance state looks identical after a rotation and after a process
     * death, and the two need opposite answers, so the caller supplies it from
     * a retained object — one Android hands to the replacement activity down
     * the configuration-relaunch path and nowhere else.
     *
     * - **A configuration change**: the request is genuinely still in flight,
     *   in a process that never went away. So the commit stays a commit — rows
     *   inert, watch kept — and the answer settles it when it lands. Coming
     *   back retryable here would let a second tap dispatch a *second* request
     *   while the first was outstanding, and the outcome channel names no
     *   request: the first answer would then arrive at the retry's watch and be
     *   read as its own, so an old `APPLIED` could dismiss the sheet over a
     *   newer choice the service had refused.
     * - **A process death**: there is nothing left to hear from. The channel is
     *   process-scoped so its held result is gone, and the request died with
     *   the process. So the flag is dropped and no watch is kept — the sheet
     *   comes back usable rather than waiting forever on an answer that cannot
     *   come, which with the swipe veto keyed off the same flag would leave it
     *   neither answerable nor closable. Nothing is in flight to correlate
     *   against, so nothing can be mistaken for it either.
     */
    fun restore(
        condition: EndCondition?,
        wasCommitting: Boolean,
        failed: Boolean,
        /**
         * Whether the restored [condition]'s time is the user's rather than
         * the clock's — [steppedByUser], saved with the rest of the offer.
         * Without it the first [refreshStart] after a rotation reads the
         * restored time as untouched and reseeds over it (Codex, PR #267).
         */
        stepped: Boolean = false,
        configurationChange: Boolean,
        requestId: Long,
        offeredFor: Instant?,
    ) {
        endCondition = condition
        offerFor = offeredFor
        commitFailed = failed
        // No partial flag to restore: the "can still end sooner" line is derived
        // from the record's [ActiveSnooze.isPartialTimer], so it comes back with
        // the record after a rotation or a process death rather than from saved
        // view state, and a restored [commitFailed] can never say "couldn't set
        // the end time" over a time that was in fact set.
        steppedByUser = stepped
        if (!wasCommitting) return
        // Named, so the answer this resumes is the one this sheet asked for.
        // Unnamed it would take whatever the channel happened to be holding,
        // which after the second host arrived can be the other sheet's.
        val alreadyAnswered = if (requestId != 0L) EndChoiceOutcome.takePending(requestId) else null
        if (alreadyAnswered != null) {
            committing = true
            committingRequestId = requestId
            onOutcome(alreadyAnswered)
            return
        }
        // Process death: nothing is coming, so leave it retryable and unwatched.
        if (!configurationChange) return
        // A saved commit with no request to resume cannot be waited on — there
        // is nothing to address an answer to — so it comes back retryable for
        // the same reason a process death does.
        if (requestId == 0L) return
        committing = true
        committingRequestId = requestId
        outcomeWatch?.close()
        outcomeWatch = watchOutcome(requestId, ::onOutcome)
    }

    /** Drops the outcome watch. Idempotent. */
    fun close() {
        outcomeWatch?.close()
        outcomeWatch = null
    }
}
