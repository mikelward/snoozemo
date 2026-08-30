package app.snoozemo.snooze

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.snoozemo.R
import app.snoozemo.degradationReasonRes
import app.snoozemo.tile.R as TileR
import app.snoozemo.core.ActiveSnooze
import app.snoozemo.core.DegradationCause
import app.snoozemo.core.EndReason
import app.snoozemo.core.TrackingMode
import app.snoozemo.core.ZenFailure
import app.snoozemo.ui.MainActivity

/**
 * The "you are snoozed" affordance (SPEC.md §4.3) and every reason a snooze
 * ended (§4.5).
 *
 * This is where degraded modes get said out loud: a snooze that is really only a
 * timer must never look like a tracked one, and the notification is where the
 * user is already looking.
 */
class SnoozeNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        ensureChannels()
    }

    /**
     * Creates the three channels, once per process.
     *
     * `createNotificationChannel` is a synchronous binder call and this class is
     * constructed in the service's `onCreate` — which, on a cold tile tap, sits
     * between the tap and the zen rule going on. The flag keeps that to the
     * *first* construction in the process, and [warm] moves that first one
     * ahead of the tap. Idempotent either way: creating a channel that already
     * exists is a no-op to the platform, so a missed warm-up costs latency, not
     * correctness — the channels always exist before anything is posted.
     *
     * Contained for the same reason [post] is, and with more at stake. This runs
     * in the service's `onCreate`, so a throw here doesn't degrade a
     * notification — it takes the whole service down before `onStartCommand`
     * ever runs. On the cap path that is the snooze's last exit: the alarm that
     * woke us is one-shot and spent, and its receiver has already treated the
     * accepted `startService` as sufficient and skipped its own release. The
     * phone would stay silent with nothing left scheduled to bring it back.
     *
     * So a refusal here leaves the app without notifications and lets the
     * release proceed. `channelsCreated` stays false, so the next construction
     * tries again — and posting to a channel that was never created throws,
     * which [post] already contains.
     */
    private fun ensureChannels() {
        if (channelsCreated) return
        runCatching {
            createChannels()
            channelsCreated = true
        }.onFailure {
            Log.e(TAG, "Creating the notification channels failed; continuing without them.", it)
        }
    }

    /**
     * A channel's importance is fixed at creation: once the user can see a
     * channel the level is theirs, and later calls with a different value are
     * ignored. So a device that already ran a build with this channel at
     * `IMPORTANCE_LOW` keeps it there until its app data is cleared — worth
     * knowing when testing, and nothing more than that while nothing has
     * shipped. After release, changing the level would need a new channel id.
     *
     * `setBypassDnd` is not fixed the same way — see [reapplyDndBypass].
     */
    private fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, context.getString(R.string.channel_active), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setBypassDnd(true) },
        )
        // Deliberately does NOT bypass (maintainer, 2026-08-23; SPEC.md §5.7):
        // `showEnded()` and the routine one-shot failures post here, and
        // `setBypassDnd` is global to the channel — it would let them sound
        // through a DND source that has nothing to do with Snoozemo, such as a
        // Bedtime schedule, which is the user's own choice to be quiet and not
        // this app's to override. Only the genuine emergency exit needs to
        // survive that; it has its own channel below.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ENDED, context.getString(R.string.channel_ended), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            // Bypasses: showStuckRule() posts here, and it can fire while the
            // rule Snoozemo just turned on is still active — it is the only way
            // back from a phone that may be silenced with nothing else able to
            // un-silence it (SPEC.md §5.7), so it cannot be the one thing our
            // own DND swallows. Its own channel, separate from the routine
            // notices above, precisely so only this one alert claims that
            // exemption.
            NotificationChannel(CHANNEL_URGENT, context.getString(R.string.channel_urgent), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setBypassDnd(true) },
        )
    }

    /**
     * Re-issues the two bypassing channels so a `setBypassDnd(true)` the
     * platform rejected before this app held `ACCESS_NOTIFICATION_POLICY` is
     * honored once it does.
     *
     * The platform only honors a channel's bypass flag from a caller that
     * currently holds policy access; a channel created without it keeps
     * `bypassDnd = false` regardless of what was requested, silently, and
     * [ensureChannels]'s once-per-process guard means the ordinary construction
     * path — `warm()` at app startup, well before onboarding can have granted
     * anything — never asks again (Codex, PR #92). So this is unconditional,
     * called from wherever the app already reconciles policy access (never the
     * arm path, which cannot pay for this IPC): `createNotificationChannel` on
     * an existing channel is a no-op for whatever hasn't changed, so a call that
     * finds nothing to fix costs a couple of cheap binder round trips and
     * nothing else.
     */
    fun reapplyDndBypass() {
        runCatching { createChannels() }.onFailure {
            Log.e(TAG, "Reapplying the DND-bypass channels failed; a later reconciliation retries.", it)
        }
    }

    /**
     * Closes the one gap [reapplyDndBypass]'s own call sites don't reach: an
     * out-of-band grant, where the user grants Do Not Disturb access directly
     * from system Settings while neither `MainActivity` nor `SnoozeService` is
     * alive to notice, followed by the very first arm of a process that has
     * never observed the change (Codex, PR #92). `ACTION_ARM` deliberately
     * skips `SnoozeService.reconcilePolicyAccess` — the arm-path rule bars
     * policy IPC ahead of the tap — so that reconciliation never runs before
     * these notifications do.
     *
     * Called only from [showOngoing] and `showStuckRule()`, each immediately
     * before it posts — never from `SnoozeService.armWithCap()`, which tried
     * that and twice found the same durability problem: a refused arm can
     * still have left `STATE_TRUE` on the rule (SPEC.md §7.1) and cascade
     * straight into `beginRelease()`, and an attempt placed ahead of either
     * that call's own durable write or the arm's record save (Codex, PR #92,
     * both rounds) risked losing exactly the write meant to survive a process
     * death. Calling from the two posting sites instead means the attempt
     * never sits ahead of anything durable — only ahead of the post it is
     * fixing up for — and is still safe for the arm-path rule: `beginArming`
     * has already made its zen-state IPC by the time either site's post-path
     * reaches this, so a couple of binder round trips here cost nothing the
     * tap-to-silence stretch depends on.
     *
     * Attempted once per process, like [ensureChannels], and only marked done
     * once access is actually held — checked directly, because a caught
     * exception is the wrong signal here. `createNotificationChannel` does not
     * throw for a caller lacking `ACCESS_NOTIFICATION_POLICY`; it returns
     * normally while the platform silently keeps `bypassDnd = false` (that is
     * the whole reason this method exists). A tile tap before the user has
     * ever granted access reaches this just as surely as a granted one does,
     * and treating that "success" as done would leave the guard permanently
     * satisfied over channels that were never actually fixed (Codex, PR #92).
     * Checked before attempting, too, so a no-access arm doesn't spend binder
     * calls achieving nothing. After the first *access-holding* attempt the
     * service's own receiver is registered anyway, and every later grant is
     * caught by [reapplyDndBypass]'s regular call sites instead.
     */
    fun reapplyDndBypassOnce() {
        if (bypassReapplyAttempted) return
        // Contained like every other read here, and with more at stake than
        // most: called from a posting method right after the zen rule has
        // already gone STATE_TRUE, so an escaping exception here would not
        // just skip a convenience — it would unwind the caller before it
        // posts, over a phone DND has already silenced (Codex, PR #92).
        // Treated the same as "not granted": the guard stays false, so a
        // later arm or reconciliation retries.
        val granted = runCatching { manager?.isNotificationPolicyAccessGranted }.getOrElse {
            Log.e(TAG, "Reading policy access failed while reapplying the DND bypass; a later attempt retries.", it)
            null
        }
        if (granted != true) return
        runCatching { createChannels() }.fold(
            onSuccess = { bypassReapplyAttempted = true },
            onFailure = {
                Log.e(TAG, "Reapplying the DND-bypass channels failed; a later arm or reconciliation retries.", it)
            },
        )
    }

    /**
     * Whether anything this app posts would actually reach the shade.
     *
     * Holding `POST_NOTIFICATIONS` is not the same as being heard: the user can
     * switch the app off wholesale in Settings or block either channel
     * individually, and the system then drops the post silently. The setup row
     * would otherwise say `Allowed` over a snooze whose countdown, `End now`
     * and failure messages were all going nowhere (flagged by Codex on PR #18)
     * — principle 2's failure on the screen that exists to prevent it.
     *
     * All three channels count, and none is optional: the ongoing one is the
     * only visible state a 1×1 tile has (SPEC.md §4.2), the ended one carries
     * every reason a snooze stopped (§4.5), and the urgent one is the sole way
     * back from a phone that may still be silenced (§5.7).
     *
     * **A missing channel counts against us**, which is why this is an instance
     * method rather than a static read of the manager. Constructing this class
     * has already run [ensureChannels], so by the time the check happens the
     * channel is either there or its creation was refused — and a refusal is a
     * degraded state, not a neutral one, since posting to a channel that does
     * not exist throws (also Codex, PR #18, on a first version that read null
     * as "not created yet, will be fine").
     *
     * Off the arm path — only the app screen asks this, after its first frame —
     * so the binder calls are affordable here.
     */
    fun canReachTheUser(): Boolean {
        val manager = manager ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return listOf(CHANNEL_ACTIVE, CHANNEL_ENDED, CHANNEL_URGENT).all { id ->
            val channel = manager.getNotificationChannel(id)
            channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    /**
     * The user-facing reason behind a degraded mode, resolved for this
     * context. Which causes earn a line — and why the others don't — is
     * [degradationReasonRes]; the main screen reads the same mapping so the
     * two surfaces cannot disagree about the same snooze.
     */
    private fun reasonFor(cause: DegradationCause?): String? =
        degradationReasonRes(cause)?.let(context::getString)

    fun showOngoing(snooze: ActiveSnooze) {
        reapplyDndBypassOnce()
        val body = when (snooze.mode) {
            TrackingMode.FULL -> context.getString(R.string.ongoing_ends_when_you_leave)
            // Says what it can actually do, not what it wishes it could.
            TrackingMode.WIFI_ONLY -> context.getString(R.string.ongoing_wifi_only)
            // Unverifiable, not "tracking" — the label above this one is what
            // Wi-Fi tracking actually looks like, and this is what happens
            // the instant that stops being true (SPEC.md §6.6).
            TrackingMode.WIFI_GRACE -> context.getString(R.string.ongoing_wifi_grace)
            TrackingMode.DURATION_ONLY -> context.getString(R.string.ongoing_timer_only)
        }
        // Appended only where a reason adds something. FULL carries no cause
        // by construction (`SnoozeController.modeFor` maps a null degradation
        // straight to the anchor's own capability), and WIFI_GRACE is excluded
        // deliberately: `Wi-Fi lost — ending soon` already names the thing
        // that matters, and stacking a second em-dashed clause onto a state
        // that resolves in minutes buys nothing for the length it costs
        // (AGENTS.md, *Concise copy*).
        val withReason = when (snooze.mode) {
            TrackingMode.WIFI_ONLY, TrackingMode.DURATION_ONLY ->
                reasonFor(snooze.degradation)?.let {
                    context.getString(R.string.ongoing_degraded_reason, body, it)
                } ?: body
            TrackingMode.FULL, TrackingMode.WIFI_GRACE -> body
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(TileR.drawable.ic_tile_snooze)
            .setContentTitle(context.getString(R.string.ongoing_title))
            .setContentText(withReason)
            .setOngoing(true)
            // This card is reposted on every ARMED/CHECKING transition, which
            // includes presence evidence flip-flopping (ProbablyLeft then
            // StillHere) while a snooze runs — routine updates, not events the
            // user needs alerted to. Without this, each repost would sound or
            // vibrate again on a channel that now bypasses Snoozemo's own DND
            // (§5.7): a snooze that used to be silent by accident (the platform's
            // own filter caught the repeat alert) would otherwise become audibly
            // noisy by design (Codex, PR #92). Only the first post of this id
            // alerts; later ones update the countdown and text quietly.
            .setOnlyAlertOnce(true)
            // The countdown is the platform's to run, not ours. Formatting it
            // into the text bakes in the value at post time, and this
            // notification is only rebuilt on a state change — so a snooze armed
            // for eight hours would still read "8h 0m left" seven hours later,
            // which is worse than no countdown because it looks current. The
            // chronometer ticks by itself against an absolute deadline.
            //
            // Translated through the remaining time rather than handed the
            // record's deadline directly. The chronometer counts against the
            // *wall* clock, which is the one frame the record's deadline may no
            // longer be in — after a backwards clock change the two disagree,
            // and the countdown would show hours that the cap has no intention
            // of honoring. `remaining` reconciles them; adding it to wall-now
            // puts the answer back in the frame the platform will tick in.
            .setWhen(System.currentTimeMillis() + snooze.remaining(SnoozeClock.read()).toMillis())
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(contentPendingIntent())
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_end_now),
                    trampolinePendingIntent(SnoozeService.ACTION_END, REQUEST_END),
                ).build(),
            )
            // `+30 min` matches the end-condition sheet's step, so extending uses
            // the same unit as choosing (SPEC.md §4.3).
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_extend),
                    trampolinePendingIntent(SnoozeService.ACTION_EXTEND, REQUEST_EXTEND),
                ).build(),
            )
            .build()
        post(ID_ONGOING, notification)
    }

    /**
     * Takes the ongoing notification down without claiming a snooze ended.
     *
     * For the arm that never took: [showOngoing] has already run by then, since
     * the record and the notification go up before the rule (SPEC.md §4.1), so
     * something has to remove the "Snoozing" the user can see over a phone that
     * is about to ring.
     */
    fun cancelOngoing() {
        drop(ID_ONGOING)
    }

    /**
     * Takes down a failure message that a later success has made false.
     *
     * `setAutoCancel(true)` only dismisses on a tap, so `Couldn't snooze` sits
     * in the shade until the user swipes it — and a second tap that works would
     * otherwise leave it there beside the `Snoozing` card, two notifications
     * contradicting each other about the same phone.
     */
    fun cancelFailure() {
        drop(ID_FAILURE)
    }

    fun showEnded(reason: EndReason?) {
        drop(ID_ONGOING)
        val text = when (reason) {
            EndReason.DEPARTURE -> R.string.ended_departure
            EndReason.DURATION_CAP -> R.string.ended_cap
            EndReason.LOST_CAPABILITY -> R.string.ended_lost_capability
            // Neither of these needs a notification: the user just did it and
            // can hear the result. Telling them what they already know is
            // noise, and `DND_TURNED_OFF` is the more emphatic case of it —
            // they turned Do Not Disturb off *because* they wanted the phone
            // back, so a card explaining that the phone is back is worse than
            // nothing (SPEC.md §5.7).
            EndReason.MANUAL, EndReason.DND_TURNED_OFF, null -> return
        }
        post(
            ID_ENDED,
            android.app.Notification.Builder(context, CHANNEL_ENDED)
                .setSmallIcon(TileR.drawable.ic_tile_snooze)
                .setContentTitle(context.getString(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * An arm or release that didn't take is never assumed away (SPEC.md §8.2).
     *
     * With one exclusion, and it is about honesty rather than tidiness. A
     * release that fails with [ZenFailure.nothingLeftToRelease] does not leave
     * the snooze running — nothing is silencing the phone, so the controller
     * completes the end. Saying `Couldn't end the snooze — trying again` there
     * would be false twice over: the snooze *did* end, and nothing is going to
     * try again. The ended notification the same transition posts is the true
     * report of what happened.
     *
     * The two named reasons still speak, because their copy stays true and is
     * the actionable part — a user whose access was revoked needs to know that,
     * not just that the snooze stopped.
     *
     * Returns whether the user can now see this. Callers replaying an
     * explanation they owe from an earlier failed arm keep it until this says
     * yes: the message was already dropped once, and a second silent drop
     * spends the last copy of it.
     */
    fun showFailure(failure: ZenFailure, whileArming: Boolean): Boolean {
        val text = when (failure) {
            ZenFailure.NO_POLICY_ACCESS -> R.string.failure_no_access
            ZenFailure.RULE_DISABLED -> R.string.failure_rule_disabled
            ZenFailure.NO_RULE, ZenFailure.PLATFORM_REFUSED -> when {
                whileArming -> R.string.failure_could_not_start
                // Nothing owed, deliberately — so "delivered" is the honest
                // answer, not a failure a caller should keep retrying.
                failure.nothingLeftToRelease -> return true
                // Its own id, like the stuck-rule card and for the same class
                // of reason: this is the one one-shot a later event *retires*
                // rather than replaces. A release that eventually works cancels
                // it by id — and on the shared id that cancel would also take
                // down whichever unrelated explanation happened to be posted
                // last, such as the access-revocation message an ending posts
                // moments before completing.
                else -> return post(
                    ID_END_FAILURE,
                    android.app.Notification.Builder(context, CHANNEL_ENDED)
                        .setSmallIcon(TileR.drawable.ic_tile_snooze)
                        .setContentTitle(context.getString(R.string.failure_could_not_end))
                        .setAutoCancel(true)
                        .build(),
                )
            }
        }
        return showOneShot(text)
    }

    /**
     * The arm didn't take, for a reason below the zen rule — the cap alarm
     * wouldn't schedule (SPEC.md §7), or the record wouldn't write (§8.1).
     *
     * Deliberately the same message the zen-rule failures use. Which internal
     * step gave way is a debug-log fact; what the user needs from a notification
     * is that the tile tap did not snooze their phone. Naming the step here
     * produced copy nobody outside the code could parse ("Snooze ended — it
     * couldn't be saved" — saved *where*?).
     */
    fun showCouldNotArm() = showOneShot(R.string.failure_could_not_start)

    /** A reboot couldn't reschedule the cap, so the snooze ended (SPEC.md §8.3). */
    fun showCouldNotResume() = showOneShot(R.string.failure_could_not_resume)

    /** The record wouldn't erase, so it may come back until its cap (SPEC.md §8.1). */
    fun showNotForgotten() = showOneShot(R.string.failure_not_forgotten)

    /**
     * Every automatic attempt to turn the rule off has failed, so the user is
     * the only exit left — and this is what hands them one.
     *
     * The one notification here that carries an action rather than only an
     * explanation, because the state it describes has nothing else pointing at
     * it: no record (so the tile reads inactive), no alarm, and no obligation
     * left in the process. Without this the app would have silenced the phone
     * and left no way back inside the app at all.
     *
     * **The notification is the durable part.** It is posted at the moment the
     * retries are exhausted — no detection after the fact is needed, since this
     * runs from the code that is giving up — and once in the shade it outlives
     * the process that posted it, with its action still live. That is why this
     * closes the gap without persisting anything: a stored obligation would
     * have to be read back, identified against whatever snooze exists later,
     * and retired when stale, which is the class of state that has repeatedly
     * gone wrong here. This can only fire when the user taps it, and a user
     * asking for Do Not Disturb off is never the wrong thing to do.
     *
     * The copy says *may* deliberately. All that is known is that a write was
     * refused; whether the rule is actually silencing the phone is exactly what
     * could not be determined.
     *
     * A [reapplyDndBypassOnce] attempt first, deliberately, since this is the
     * *only* place on the failed-arm path that makes one: this can post well
     * after the arm that led here — release escalation can retry across
     * several alarm-scheduled rungs before giving up — and without this
     * nothing on that path would ever retry the bypass before the one alert
     * meant to survive a stuck rule posts on a channel that still doesn't
     * bypass (Codex, PR #92). Cheap and self-limiting: a no-op once it has
     * already succeeded.
     */
    fun showStuckRule(): Boolean {
        reapplyDndBypassOnce()
        return post(
            ID_STUCK,
            android.app.Notification.Builder(context, CHANNEL_URGENT)
                .setSmallIcon(TileR.drawable.ic_tile_snooze)
                .setContentTitle(context.getString(R.string.failure_rule_stuck))
                .setContentText(context.getString(R.string.failure_rule_stuck_body))
                // Ongoing, not auto-cancelling, and the reasoning is worth
                // keeping because it went the other way first. This card is the
                // *only* thing pointing at a phone that may be silenced with
                // nothing else able to un-silence it — so letting it be swiped
                // away reopens exactly the dead end it was added to close. That
                // outweighs the annoyance of an insistent notification about a
                // rule that only *might* be stuck.
                //
                // It is not a trap, which is what makes the trade acceptable:
                // `Unsnooze` clears it, and on the harmless reading — the rule
                // was never on — driving an already-off rule off is a no-op
                // that dismisses the card anyway. A successful arm takes it
                // down too, explicitly.
                //
                // Its **own** id, not the shared `ID_FAILURE` the one-shots
                // use, and that is a correctness point rather than tidiness.
                // This is the only notification in the app carrying a way out
                // of a possibly-silenced phone; on the shared id, an unrelated
                // one-shot — the very next tile tap failing early with
                // `Couldn't snooze`, say — would replace the only `Unsnooze`
                // the user had, while the persisted flag went on saying the
                // rule might be stuck.
                //
                // Worth knowing this is a strong hint rather than a lock:
                // Android 14 made ongoing notifications user-dismissible in most
                // cases, and minSdk is 34. What it reliably buys is exclusion
                // from `Clear all`, which is the accidental loss that matters.
                .setOngoing(true)
                // `Unsnooze` rather than `Turn off`, which sat under a title
                // ending in "may still be on" and read as though it dismissed
                // the notification. This one names what it acts on.
                .addAction(
                    android.app.Notification.Action.Builder(
                        null,
                        context.getString(R.string.action_unsnooze),
                        trampolinePendingIntent(SnoozeService.ACTION_RELEASE_STUCK, REQUEST_RELEASE_STUCK),
                    ).build(),
                )
                // The deliberate way out, which is the point of pairing it with
                // `setOngoing`: a swipe is usually an accident, tapping this is
                // not. A user who knows the rule is fine gets rid of the card
                // without being made to drive a release they don't need.
                //
                // A broadcast, not the trampoline: this only takes a
                // notification down, so starting an activity and a service for
                // it would be a visible flash and a process start for nothing.
                .addAction(
                    android.app.Notification.Action.Builder(
                        null,
                        context.getString(R.string.action_dismiss),
                        PendingIntent.getBroadcast(
                            context,
                            REQUEST_DISMISS_STUCK,
                            Intent(context, NotificationActionReceiver::class.java)
                                .setAction(ACTION_DISMISS_STUCK),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    ).build(),
                )
                .build(),
        )
    }

    /** Takes the stuck-rule card down; it has its own id, so this is separate. */
    fun cancelStuckRule() {
        drop(ID_STUCK)
    }

    /**
     * Takes down a `Couldn't end the snooze — trying again` that a confirmed
     * release has made false.
     *
     * `setAutoCancel(true)` only dismisses on a tap, so once the promised retry
     * *does* end the snooze, the card would otherwise sit in the shade beside
     * `Snooze ended` claiming the opposite (flagged by Codex on PR #8). Called
     * wherever the rule is confirmed off — the same set of places the
     * stuck-rule card comes down, because both are claims that it might not be.
     */
    fun cancelEndFailure() {
        drop(ID_END_FAILURE)
    }

    /** `+30 min` couldn't move the cap alarm, so the cap stands where it was. */
    fun showCouldNotExtend() = showOneShot(R.string.failure_could_not_extend)

    /**
     * A time chosen in the end-condition sheet that the cap would not take
     * (SPEC.md §4.4).
     *
     * Its own card rather than [showCouldNotExtend]'s, because the two failures
     * leave the user in different places: a refused `+30 min` means the snooze
     * ends when it always said it would, while this means it ends *later* than
     * the moment they just picked — the one the message has to be about.
     */
    fun showCouldNotSetEnd() = showOneShot(R.string.failure_could_not_set_end)

    /** `+30 min` with nowhere left to go: the 24 h backstop is absolute (§7). */
    fun showAtMaxDuration() = showOneShot(R.string.extend_at_max)

    /** Returns whether the message reached the shade — see [showFailure]. */
    private fun showOneShot(text: Int): Boolean =
        post(
            ID_FAILURE,
            android.app.Notification.Builder(context, CHANNEL_ENDED)
                .setSmallIcon(TileR.drawable.ic_tile_snooze)
                .setContentTitle(context.getString(text))
                .setAutoCancel(true)
                .build(),
        )

    /**
     * Posts [notification], and never lets a refusal escape.
     *
     * Not a swallowed exception in the sense the error-handling rule bans — the
     * failure is logged and the caller's outcome is chosen deliberately here
     * rather than by default. What it must not do is *propagate*, because of
     * where the first post happens: `showOngoing` runs from the `ARMED`
     * transition, which `beginArming` delivers before returning. A throw there
     * unwinds the arm mid way and leaves a rule that is *on*, plus a record and
     * a cap, with the caller told the arm failed — and the caller's unwind
     * treats a failed arm as nothing to release.
     *
     * So a notification that won't post degrades this snooze to an invisible
     * one; it never turns it into a phantom one. The user still has the tile,
     * which shows `Snoozing` and ends it on a tap, and the cap still fires.
     */
    private fun post(id: Int, notification: android.app.Notification): Boolean =
        runCatching { manager.notify(id, notification) }.fold(
            onSuccess = { true },
            onFailure = {
                Log.e(TAG, "Posting notification $id failed; the snooze continues unseen.", it)
                false
            },
        )

    /** Takes one down, with the same containment [post] has. */
    private fun drop(id: Int) {
        runCatching { manager.cancel(id) }.onFailure {
            Log.e(TAG, "Canceling notification $id failed; it may still be on screen.", it)
        }
    }

    /**
     * Both notification actions go through the trampoline activity rather than
     * straight at the service.
     *
     * The same reasoning the tile already follows, and it applies here for the
     * same reason: a notification action firing `PendingIntent.getService` is
     * *consumed* if the platform or an OEM refuses the background start, and no
     * app code ever learns it was refused. Every other exit in the app — the
     * tile, the screen, the cap alarm's receiver — notices and recovers. These
     * two had no recovery at all.
     *
     * The stakes differ, so the recoveries do. A refused **`End now`** leaves
     * the rule on with the user's exit spent, so the trampoline releases
     * directly. A refused **`+30 min`** strands nothing — the snooze and its
     * cap are untouched and the tap can be repeated — but it must still *say*
     * so, because a button that silently does nothing is the second principle's
     * failure even when the first one is safe.
     *
     * An activity is on the documented exemption list for starting a service.
     * The visible cost is that the shade collapses, as it does for a tile tap.
     */
    private fun trampolinePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, TileTrampolineActivity::class.java)
                .setAction(action)
                // Started from a notification, so there is no task to land in.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Tapping the card itself — not one of its actions — opens the app.
     *
     * Straight to [MainActivity], unlike the two actions above: this is a plain
     * foreground activity launch, which the platform always allows from a
     * notification tap, so there is no refused-background-start case for a
     * trampoline to recover from here. `CLEAR_TOP` collapses back onto an
     * existing instance rather than stacking a second one when the app is
     * already open behind the shade.
     */
    private fun contentPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        /**
         * Creates the channels ahead of the first tap.
         *
         * Called from `Application.onCreate`, which on the tile path runs when
         * the shade binds the tile — before the tap, not between it and the
         * rule. Off the main thread for the same reason the stores are warmed:
         * this is two binder calls and none of them belong in front of an arm.
         */
        fun warm(context: Context) {
            Thread {
                // A bare thread has no handler, so anything escaping here takes
                // the process down — including a process that is mid-arm. The
                // construction is contained already; this covers the rest of it
                // (fetching the service, resolving the strings) for a warm-up
                // whose whole purpose is to be optional.
                runCatching { SnoozeNotifications(context.applicationContext) }.onFailure {
                    Log.e(TAG, "Warming the notification channels failed; the service retries.", it)
                }
            }.start()
        }

        /**
         * Whether this process has already created the channels. Process-wide
         * rather than per-instance because the service builds its own instance
         * on every start, and the point is to pay for them once.
         */
        @Volatile
        private var channelsCreated: Boolean = false

        /** Guards [reapplyDndBypassOnce]; process-wide for the same reason [channelsCreated] is. */
        @Volatile
        private var bypassReapplyAttempted: Boolean = false

        /**
         * Test-only: clears both once-per-process guards, the same way
         * `TestSnoozeService.reset()` clears its own statics — real production
         * code never sees a second process to leak across, so nothing else
         * resets these.
         */
        internal fun resetForTest() {
            channelsCreated = false
            bypassReapplyAttempted = false
        }

        /** Test-only: whether [reapplyDndBypassOnce] has marked itself done. */
        internal fun bypassReapplyAttemptedForTest(): Boolean = bypassReapplyAttempted

        private const val TAG = "SnoozeNotifications"

        const val CHANNEL_ACTIVE = "snooze_active"
        const val CHANNEL_ENDED = "snooze_ended"
        const val CHANNEL_URGENT = "snooze_urgent"
        const val ID_ONGOING = 1
        const val ID_ENDED = 2
        const val ID_FAILURE = 3
        const val ID_STUCK = 4
        const val ID_END_FAILURE = 5
        const val REQUEST_END = 10
        const val REQUEST_EXTEND = 11
        const val REQUEST_RELEASE_STUCK = 12
        const val REQUEST_DISMISS_STUCK = 13
        const val REQUEST_CONTENT = 14

        /** Handled by [NotificationActionReceiver]; takes the card down and nothing else. */
        const val ACTION_DISMISS_STUCK = "app.snoozemo.action.DISMISS_STUCK"
    }
}
