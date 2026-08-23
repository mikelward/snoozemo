package app.snoozemo.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.snoozemo.dnd.AndroidZenController
import app.snoozemo.dnd.PrefsZenRuleIdStore

/**
 * The product's real entry point (SPEC.md §4.2): one tap from the shade, phone
 * possibly locked, zero prior configuration.
 *
 * Deliberately thin. It reads a snapshot and sends an intent; every decision
 * lives in `SnoozeController`. It also stays in `:tile` rather than `:app`,
 * which is why it launches the trampoline by action rather than by class — a
 * compile-time reference would invert the module dependency.
 *
 * The tile is the tightest copy constraint in the app: Quick Settings truncates
 * aggressively, and some presentations (the compact panel, and One UI's
 * variants) show **only the icon**, so the glyph has to carry the meaning on its
 * own and the countdown cannot be the only place the remaining time appears —
 * the ongoing notification carries it too.
 */
class SnoozeTileService : TileService() {

    /**
     * The snapshot [onStartListening] rendered, kept so [onClick] needn't read
     * it again.
     *
     * The read is `SharedPreferences`, which on a cold process blocks on the
     * file's initial load — and a tap is the one place in the app where nothing
     * may block (`AGENTS.md`, the arm path). The shade has to be listening for a
     * tap to reach us at all, so by then this is already filled in.
     *
     * Staleness is safe in both directions, which is what makes reusing it
     * sound rather than merely convenient. If it says "snoozing" and the snooze
     * has since ended, the end is idempotent; if it says "not snoozing" and one
     * has since been armed, `SnoozeService.arm` refuses a duplicate and
     * re-states the running snooze rather than replacing its cap.
     */
    @Volatile
    private var listening: TileSnapshot? = null

    /**
     * The tile has been added to Quick Settings.
     *
     * Recorded because nothing else can answer the question later: the app
     * screen has to decide whether to offer to add the tile, and the platform
     * has no "is it there?" call. This callback and [onTileRemoved] are two of
     * the three moments it volunteers the answer (the third is the result of an
     * add request).
     */
    override fun onTileAdded() {
        super.onTileAdded()
        TilePresenceStore(applicationContext).setAdded(true)
    }

    /** The mirror image, so the screen offers the tile again once it is gone. */
    override fun onTileRemoved() {
        super.onTileRemoved()
        TilePresenceStore(applicationContext).setAdded(false)
    }

    override fun onStartListening() {
        super.onStartListening()
        // The shade is open and a tap may be a moment away, so pull the zen rule
        // id into memory now rather than on the way to STATE_TRUE (SPEC.md
        // §4.1). The Application warms it too, but this covers a tile bound
        // long after process start.
        //
        // Read here rather than warmed on a thread, and the difference is the
        // whole point. `warm()` only narrows the race — a tap landing before
        // its thread finishes still takes the blocking file read, or blocks on
        // the cache lock behind it, and that wait lands between the tap and
        // `STATE_TRUE`. This method is not the tap path: the shade has to be
        // listening for a tap to reach us at all, so paying for the read here
        // takes the wait off the one place it is unaffordable and puts it
        // where there is budget. The snapshot read below already blocks on a
        // preferences file for the same reason.
        PrefsZenRuleIdStore(applicationContext).ruleId()

        // And the rule itself, not only its id (`TODO.md`, deferred from
        // Codex's PR #8 review): the id can be stale — the rule deleted in
        // Settings, or the process dead through a grant — and a tap then takes
        // `setSnoozed`'s slow path, doing a policy-access check, a rule
        // lookup, possibly a creation, and a persist before `STATE_TRUE`, on
        // the one path SPEC.md §4.1 says must not wait. Verifying (and if
        // need be recreating) the rule now moves all of that to the moment
        // the shade opens, where there is budget.
        //
        // On its own thread, unlike the id read above: `ensureRule` can
        // *create* a rule, a heavier binder call than a file read, and this
        // method runs while the shade is opening. A tap that outraces the
        // thread is no worse off than today — the slow path still arms.
        warmZenRule(applicationContext)

        val snapshot = TileSnapshot.read(applicationContext).also { listening = it }
        qsTile?.apply {
            state = if (snapshot.snoozing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            icon = Icon.createWithResource(applicationContext, R.drawable.ic_tile_snooze)
            label = getString(if (snapshot.snoozing) R.string.tile_snoozing else R.string.tile_snooze_here)
            subtitle = snapshot.subtitle(applicationContext)
            contentDescription = label
            stateDescription = snapshot.stateDescription(applicationContext)
            updateTile()
        }
    }

    /**
     * Both taps go through the trampoline activity, never straight to the
     * service: `onClick` is not on the documented list of exemptions for
     * starting a service from the background, while activities are
     * (SPEC.md §6.9).
     *
     * Ending used to skip it, on the reasoning that "stopping work is
     * unrestricted". That confused two different things — what is unrestricted
     * is *stopping* a service, not **starting** one to ask it to stop, which is
     * what an `End now` tap does when the system has already killed the
     * service mid-snooze. A refusal there wouldn't be a slow exit, it would be
     * no exit: the tap that the user reaches for to get their phone back is the
     * one that must never be the thing that fails (SPEC.md §7).
     */
    override fun onClick() {
        super.onClick()
        // Whatever the shade last rendered, rather than a fresh read: see
        // [listening]. The fallback covers a tap the platform delivers without
        // a preceding listen, which should not happen and must not crash if it
        // does — one blocking read is a worse tap, not a broken one.
        val snapshot = listening ?: TileSnapshot.read(applicationContext)
        val paint = TileOptimisticPaint.forTap(snapshot.snoozing)

        // Painted here rather than left to the service's own round trip
        // (`SnoozeTileBridge.refresh` → `requestListeningState` →
        // `onStartListening`): that path is a *request* to rebind, not a
        // delivery guarantee, and on a real shade it visibly lagged a tap by a
        // second or two (`TODO.md` Phase 2). This instance is already alive —
        // the tap just arrived on it — so there is no reason to wait for a
        // fresh bind to say what the tap itself already decided. `updateTile`
        // is only valid from a method the platform called on this tile, which
        // `onClick` is.
        //
        // Safe to paint ahead of the real outcome: a refused arm or end is
        // exactly what `SnoozeTileBridge.refresh` corrects moments later once
        // the service knows, the same reconciliation this tile already relies
        // on for every other stale reading (see [listening]'s own comment).
        // The countdown is left alone rather than guessed — this tap has no
        // duration or record to compute one from, and a wrong number would be
        // worse than a moment of no subtitle at all.
        qsTile?.apply {
            state = if (paint.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(paint.labelRes)
            contentDescription = label
            // TalkBack's own line, not just the visual state: left at whatever
            // `onStartListening` last set, an arm tap would announce "Off"
            // over a tile that now reads Snoozing, and an end tap the
            // opposite (Codex, PR #93). Dropped along with the subtitle for
            // the same reason — no countdown to put in it yet — and the next
            // refresh's `onStartListening` puts the full description back.
            stateDescription = label
            subtitle = null
            updateTile()
        }

        // Carried into [listening] as well as painted, so a second tap
        // arriving before the next `onStartListening` (the trampoline or a
        // permission dialog can keep the shade's listen alive well past this
        // return) derives its own action from what this tap just promised,
        // not from what was true before it (Codex, PR #93). Reading a stale
        // `listening` here would send the *same* action twice — the service
        // treats a repeat arm as a no-op re-statement of the running snooze,
        // never as the end the now-active tile visually promises the next
        // tap will be — leaving the phone snoozed with no other exit in
        // reach. Only `snoozing` is updated; the other fields are stale
        // either way and unread until the next `onStartListening` replaces
        // this object outright.
        listening = snapshot.copy(snoozing = paint.active)

        // The PendingIntent overload, with no fallback: minSdk is 34 (SPEC.md
        // §11), so the deprecated Intent overload this used to need on API 33 is
        // gone along with the version branch.
        //
        // Distinct request codes per action, because PendingIntent equality
        // ignores the action — one code would have let the second tap reuse the
        // first's intent and send the wrong one. Both the action and the request
        // code come from [paint] rather than a second `if (snapshot.snoozing)`
        // here, so the intent can't disagree with what was just painted.
        startActivityAndCollapse(
            PendingIntent.getActivity(
                applicationContext,
                paint.requestCode,
                intentTo(TRAMPOLINE_CLASS).setAction(paint.action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    /**
     * An **explicit** intent at one of `:app`'s components, named by string.
     *
     * Two reasons it can't be the implicit `Intent(action).setPackage(…)` this
     * used to be. An implicit intent only reaches a component with a matching
     * `<intent-filter>`, and since Android 14 only an **exported** one — so the
     * arm path would have forced the trampoline to stay exported, letting any
     * app on the phone silence it, and the end path resolved to nothing at all,
     * because the service has no filter to match.
     *
     * The class names are strings because `:app` depends on `:tile`, not the
     * other way round, so there is nothing to reference. Fully-qualified class
     * names are stable under `applicationIdSuffix`, which the package name is
     * not — hence [Context.getPackageName] for the package and constants here
     * for the classes.
     */
    private fun intentTo(className: String): Intent =
        Intent().setComponent(ComponentName(applicationContext.packageName, className))

    companion object {
        private const val TRAMPOLINE_CLASS = "app.snoozemo.snooze.TileTrampolineActivity"

        /**
         * Verifies the zen rule exists — recreating it if the user deleted it
         * in Settings — on a background thread. Contained whole: the warm-up
         * is optional, so nothing it does may reach the shade.
         *
         * The synchronous body is separate and internal so a test can run it
         * to completion; the thread is what production pays.
         */
        private fun warmZenRule(context: Context) {
            Thread {
                runCatching { warmZenRuleNow(context) }
            }.apply {
                isDaemon = true
                name = "snoozemo-tile-warm"
            }.start()
        }

        /**
         * Public rather than internal only because the tests live in `:app`,
         * which is a different module; nothing in production calls it directly.
         */
        fun warmZenRuleNow(context: Context) {
            AndroidZenController.default(context).ensureRule()
        }
    }
}
