# TODO

Phased plan toward the product in `SPEC.md`. Each phase should land as its own PR (or small
stack), fully unit-tested, with `./gradlew test` and `./gradlew lint` green.

Phases map one-to-one onto the milestones `SPEC.md` refers to by letter (M1–M8), so a
reference like "validated at M8" in the spec resolves here. Everything is **Pixel and the
`play` flavor** unless stated; Samsung ships too (goal 5), sequenced after the Pixel
release rather than dropped.

## Phase 0 — Repo scaffold

- [x] `AGENTS.md` (+ `CLAUDE.md` / `GEMINI.md` symlinks), adapted from the sibling
      `mikelward/typelauncher` and `mikelward/simmo` repos.
- [x] `SPEC.md` — the design, renamed from `DESIGN.md` to match the sibling repos'
      convention (`SPEC.md` = product and architecture decisions, `TODO.md` = the plan).
- [x] `TODO.md`, `README.md`, `.gitignore`.
- [x] Claude Code web session hook to provision the Android SDK
      (`.claude/hooks/session-start.sh` + `.claude/settings.json`).
- [x] Gradle + AGP + Compose skeleton, buildable in CI: application ID `app.snoozemo`,
      minSdk 33 (raised to 34 in Phase 2, below), targetSdk 36, `versionCode` derived from
      the `main` commit count.
- [x] Split the core from the UI in the scaffold itself (maintainer, 2026-08-11), starting
      from `SPEC.md` §11's shape: `:app`, `:tile`, `:core`, `:dnd`, `:presence`. The
      requirement is a seam that keeps the core functional, testable, and buildable on its
      own — a Gradle module is the version the build enforces, so it is the default, but
      the modules may be cut differently if that serves the same end better. Prove it by
      building and testing `:core` alone (`./gradlew :core:test`) with no Android
      dependencies pulled in.
      **Landed as a plain Kotlin JVM module** — the Android SDK is not on `:core`'s compile
      classpath at all, so the seam is enforced by the build rather than by discipline, and
      CI runs `:core:test` on its own as the check that keeps it that way.
- [x] Two product flavors, `play` and `direct` (`SPEC.md` §3.4), differing only below
      `PresenceMonitor`. `play` is the default; CI's `assembleDebug` builds both, since a
      change that compiles in one can break the other.
- [x] CI workflow (`.github/workflows/android-ci.yml`): build both flavors, `:core:test` on
      its own, unit tests with failing-test PR comments, lint. The Roborazzi screenshot job
      lands with the first real UI (Phase 2/4) and the `deploy` job with the release
      plumbing (Phase 6) — an empty screenshot allow-list is only a check nobody reads.
- [x] Roborazzi screenshot job, now that there is a screen to record (`DebugScreen`, Phase 2).
      Eight snapshots — every state the screen can be in, light and dark, including the two
      *unread* states, since what this screen must never do is answer a question it hasn't
      asked the platform yet. Recording is opt-in via `-Proborazzi.test.record`, so
      `./gradlew test` still runs them as ordinary render-and-assert tests rather than
      rewriting the committed PNGs on every machine.
- [x] Copy the sibling repos' diff-comment upsert as it
      stands *now*, not as it was: all four repos post through `gh api`, which exits
      non-zero on both an HTTP error and an unparsable body, so the lookup needs no
      hand-rolled status test. Two rules carry over regardless of transport. The lookup
      must **skip, not guess** — failing it open posts a duplicate comment instead of
      updating the existing one, and every later run duplicates again. And it must
      paginate (`--paginate`), or past 100 issue comments the marker falls off page one
      and produces that same duplicate from a different cause.
- [x] Launcher icon and tile mark — three descending `Z`s, one geometry shared by the tile
      drawable, the launcher foreground, and the monochrome layer, so the shade and the home
      screen show one app. Recorded in both tints by `TileMarkScreenshotTest` at the tile's
      real size, since light-on-dark is the direction that fills in. **Still owed a device:** how
      it masks on a round launcher, and how One UI draws it. **Good enough for now, not
      finished** (maintainer, 2026-08-12): the mark ships as it is and gets another pass
      later, so treat it as the current answer rather than the settled one. What follows is the reasoning
      it was drawn against. The tile icon is **any drawable the app
      supplies** — there is no system catalog to pick from — declared as `android:icon` on
      the `TileService` and swappable at runtime via `Tile.setIcon`. What is fixed is the
      *treatment*: the system tints it per tile state, so only the alpha channel survives
      and the asset is effectively a silhouette.
      - **Draw it for both tints, and check it in both** (maintainer, 2026-08-12). The
        active tile inverts — light background when off, dark when on, matching the system
        Do Not Disturb tile — and the system tints the glyph to contrast with whichever it
        drew. So the same asset is dark-on-light *and* light-on-dark, and the failure mode
        is one-directional: thin strokes and tight counters read cleanly as dark-on-light
        and fill in as light-on-dark. On a 1×1 tile this mark is the whole of the
        armed/inactive signal (`SPEC.md` §4.2), so a glyph that survives only one tint
        loses the state outright.
      - **The placeholder's single `Z` is gone** — three `Z`s, per the maintainer, who
        rejected a two-glyph `Zz` draft on sight (2026-08-12): *"it's zaggy and only has
        two z's, I think it should have 3"*. Both halves of that mattered. The count is
        literal; **zaggy** was the filled-wedge construction — a Z built as two bars and a
        hard diagonal reads as a lightning bolt at 24 dp. The mark is now **stroked with
        round caps and joins**, which reads as handwriting, and it is what makes three
        glyphs fit at all: an even-weight stroke costs less area than a filled wedge, so
        the smallest `z` keeps its counters open under the tint that blooms.
- [x] `docs/PRIVACY.md` backing the hosted privacy policy, plus the Play Data Safety
      answers it has to agree with ("no data collected, no data shared", `SPEC.md` §12).
      Written from the manifests and the five `SharedPreferences` stores rather than from
      the spec alone, so the "what Snoozemo keeps" table lists what the code actually
      writes and when it is erased. Two things it deliberately does **not** do:
      - **It does not describe the debug log (§4.6).** The log isn't built, and a policy
        that describes a feature the app doesn't have is inaccurate in the direction that
        costs trust. The gate stays where it was — the log's own PR adds the section, and
        Phase 5's "must describe what the log carries **before** the sharing surface ships"
        is what holds it.
      - **It is not published by this commit.** Hosting it, and the Play Data Safety form
        it has to agree with, belong to Phase 6's release plumbing — which is the
        *internal-track* release, so both are due before the first build reaches a tester,
        not at some later public launch.
- [ ] Re-verify `docs/PRIVACY.md` against the shipped manifest before the first release.
      It describes **v1 as specified**, so it names location, background location and the
      Wi-Fi read, none of which the app declares yet (Phase 3). That is the safe direction
      to be wrong in — a policy promising less than the app does is the harmful one — but
      it has to be true on the day it is hosted, not merely true eventually.

## Phase 1 (M1) — The DND half

Deliberately first and deliberately small: it proves `setAutomaticZenRuleState` actually
silences the device before anything is built on top of it. On Pixel that is a formality;
the point is that every other line of the app is worthless if it isn't true.

- [x] `ZenRuleManager`: create one long-lived `AutomaticZenRule` at first successful
      onboarding, persist its id, never churn it per snooze (`SPEC.md` §5.3). Landed as
      `AndroidZenController` in `:dnd` behind a `ZenController` contract in `:core`; it
      re-checks that the platform still has the rule rather than trusting the persisted id,
      since the user can delete it from Settings.
- [x] API 35+ `AutomaticZenRule.Builder` path with the SDK 33/34 constructor fallback, and
      the 4-arg `Condition` with `SOURCE_USER_ACTION` / `SOURCE_CONTEXT` on API 35+
      (`SPEC.md` §5.4). No `ConditionProviderService` — it is deprecated and unnecessary.
- [x] `ACCESS_NOTIFICATION_POLICY` onboarding: the settings-screen grant flow, plus a
      listener on `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` so revocation
      mid-snooze ends the snooze and says why. The decision itself is a pure function in
      `:core` (`PolicyAccessChange`) with tests, since it is the fail-open rule in code.
- [x] Default `ZenPolicy`: `INTERRUPTION_FILTER_PRIORITY` allowing alarms, media, system
      sounds, and repeat callers. Total silence is still to be offered in settings, and is
      never the default.
- [x] Debug arm/release button — no tile, no presence engine, no UI polish.
- [ ] **Still owed: verify on a real Pixel** that the rule silences the device and is
      visible and disableable in Settings (hardware item 3 below). Nothing in this sandbox
      can answer it — there is no emulator, and an emulator could not answer it anyway.
      Until someone runs it, Phase 1 is written but not proven.

## Phase 2 (M2) — Tile, trampoline, notification, cap

- [x] `SnoozeTileService`: `Zz` icon, `Snooze here` / `Snoozing` labels, subtitle countdown,
      `setStateDescription` for TalkBack, arm-on-tap and end-on-tap, long-press to settings
      via `QS_TILE_PREFERENCES` (`SPEC.md` §4.2). Arming works with the device locked — no
      `unlockAndRun()`.
- [x] **Tile latency on the tap itself**: `onClick` now paints `qsTile` optimistically —
      state, label and content description flip to the new value immediately, before
      `startActivityAndCollapse` — rather than waiting on `SnoozeTileBridge.refresh()`'s round
      trip through `SnoozeService` and `TileService.requestListeningState()` (maintainer,
      2026-08-23; the fix this entry itself proposed). That request has no delivery guarantee
      and is what was visibly lagging a tap by a second or two. The countdown subtitle is left
      null rather than guessed — this tap has no duration to compute one from — and the later
      `SnoozeTileBridge.refresh()` still corrects everything, including a refused arm/end,
      once the service knows the real outcome. The action/render decision itself is `TileOptimisticPaint`
      (Codex, PR #93), a plain Kotlin type with no `TileService`/`Tile`, covered by
      `TileOptimisticPaintTest` for both starting states — `:tile` carried no test source set before
      this, so that infrastructure is new too. **Still open and unverified on a device**: a state
      change with no tap behind it — the cap firing, `+30 min` from the notification, a manual
      release from `MainScreen` — has no live `TileService` instance to paint from and still depends
      entirely on `requestListeningState()`'s own latency, which nothing here measures or bounds.
      `TileService`/`qsTile` themselves aren't practically unit-testable under Robolectric, so
      `onClick`'s actual `qsTile` writes (as opposed to the decision feeding them) are still verified
      by inspection only — needs a real device.
- [x] `ArmTrampolineActivity` (`SPEC.md` §6.9): transparent theme, starts the service in
      `onCreate` before any UI, launched via `startActivityAndCollapse(PendingIntent)`.
- [x] Ongoing notification on channel `snooze_active`, `IMPORTANCE_LOW`, with `End now` and
      `+30 min` actions (`SPEC.md` §4.3).
- [ ] **Let the ongoing notification bypass Do Not Disturb, or guide the user to allow it**
      (maintainer, 2026-08-22). Once a snooze is armed, DND is on by definition — so the very
      notification telling the user they're snoozed, and carrying `End now`, can itself be
      silenced by the zen rule it's reporting on, unless the channel is exempted. Two routes,
      not yet chosen between: `NotificationChannel.setBypassDnd(true)` on `snooze_active` (a
      code change, and Android still lets the user override it per-channel in Settings anyway),
      or leaving the channel as-is and pointing the user at
      `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` → "Override Do Not Disturb" the first time
      a snooze arms. Needs a decision on which (or both) before implementing, and interacts with
      `SPEC.md` §5.6's "Snoozemo touches only its own rule" invariant — bypassing DND is a
      per-channel importance setting, not a zen-rule change, so it shouldn't conflict, but worth
      confirming on a device rather than assuming.
- [x] `SnoozeController` state machine (IDLE / ARMING / ARMED / CHECKING / RELEASED) as
      plain Kotlin over an injected clock — the unit-test surface for everything that
      follows. Covers the three invariants directly: the cap fires (and can't be made to
      fire early by a stray alarm), `end` is idempotent from any state, and every ambiguous
      presence event resolves toward ending.
- [x] Duration cap: 8 h default, configurable 30 min – 24 h, `AlarmManager
      .setAndAllowWhileIdle`. Armed *before* anything that can throw, and a snooze that
      can't schedule it doesn't arm at all — the alarm **is** the cap, with no in-process
      timer behind it, so arming without it would create a snooze with no time bound
      (flagged by Codex on PR #8).
- [x] Restore an active snooze after reboot: `RECEIVE_BOOT_COMPLETED` and a receiver that
      re-arms the cap alarm from the record and restarts the service (`SPEC.md` §8.1, §8.3).
      The alarm is re-armed in the receiver, before and independently of the service start,
      so the cap lands even if starting the service is refused (flagged by Codex on PR #8). A boot
      that *can't* reschedule the cap ends the snooze instead of restoring it — restoring would
      re-assert the rule with no guaranteed exit, which is the one state `SPEC.md` §7 forbids.
- [x] Fail open on a release that finds nothing left to release. A failed release keeps the record
      so the next cap check or tap retries — but only where a rule still exists. Policy access
      revoked deletes the app's rules, so retrying would fail identically forever and the record
      would strand the app showing `Snoozing` over a ringing phone, never keeping `SPEC.md` §8.2's
      promise (flagged by Codex on PR #8). `ZenFailure.nothingLeftToRelease` draws the line.
- [x] Request `POST_NOTIFICATIONS` at runtime. Declaring it grants nothing since Android 13,
      so without the request every degraded-mode and failure message was dropped by the
      system — the app failed silently by default (flagged by Codex on PR #8). Asked from
      the app screen **and from the trampoline**: the tile can be added straight from the
      Quick Settings editor, so a tile-first user may never open the app, and with the tile
      running 1×1 that user would have an armed snooze with no visible state anywhere. The
      trampoline is the one place that path passes through; the request is queued behind the
      service start so it never delays the arm, and skipped on the lock screen.
- [x] Keep the tile's intents **explicit** (`ComponentName` by string, since `:app` depends on
      `:tile` and not the reverse). The implicit `Intent(action).setPackage(…)` they started as
      forced the trampoline to stay `exported`, which let any app on the phone silence it — and the
      end intent resolved to nothing at all, since the service has no `<intent-filter>` to match
      (found while addressing the export finding on PR #8).
- [ ] **The policy-access watch has a gap, and the fix needs a device.** The receiver is
      registered dynamically because `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` is
      not on the manifest-receiver exemption list, so it lives only as long as
      `SnoozeService` — and an ordinary started service can be stopped in the background
      (flagged by Codex on PR #8). Every non-arm wake-up now reconciles, and `MainActivity`
      reconciles on open, so the window closes on the next event; but between them the tile
      and notification can claim a snooze the platform already dropped. Note the harm is a
      **stale claim, not a silent phone** — revoking access deletes the app's rule, so the
      phone rings. Verify on a device whether a manifest receiver gets this broadcast at all
      before designing anything heavier; if not, the options are a foreground service in the
      `play` flavor (which `SPEC.md` §3 deliberately avoids) or living with reconciliation.
- [x] One owner for "is a snooze running". The Phase 1 debug screen kept its own
      `DebugSnoozeFlag` and drove the zen rule directly, which was harmless until the tile's
      long-press landed on that same screen: its End button then turned DND off while leaving
      the record, cap alarm, service, tile and notification intact, and the next sticky
      recreation re-asserted the rule with no user action behind it (flagged by Codex on
      PR #8). The screen now reads `ActiveSnoozeStore` and routes arm/end through
      `SnoozeService`; `DebugSnoozeFlag` is deleted.
- [x] Warm the zen rule id at process start and when the tile starts listening, so a
      cold-process tile tap doesn't wait on a `SharedPreferences` disk read immediately before
      `STATE_TRUE` (flagged by Codex on PR #8). `SnoozemoApplication` finally does the warming
      its own comment promised since Phase 0.
- [x] Always leave a successor for a spent cap alarm. It is one-shot, so a release the platform
      refused, or a record that wouldn't erase, would be revisited by nothing (flagged by Codex
      on PR #8). Restoring also re-arms the cap from the record unconditionally — the alarm
      firing is one of the things that starts the service, so a restore that trusted it could
      re-assert the rule with no exit behind it.
- [x] Never depend on `startService` succeeding for the cap. The cap alarm is one-shot and
      has already fired by the time its receiver runs, so a refused service start there is
      the loss of the snooze's last exit, not a lost wake-up (flagged by Codex on PR #8 —
      correcting a comment of ours that claimed the opposite). `CapAlarmReceiver` now
      releases the rule itself when the service can't be started, and reschedules a short
      retry if even that is refused.
- [ ] **User-facing copy is provisionally approved and wants a second pass** (maintainer,
      2026-08-11): "strings seem ok … I'll tweak them to be shorter later". Three were
      already shortened on their read — `Couldn't start the snooze` now covers every reason
      an arm didn't take (which internal step gave way is a debug-log fact, not something a
      notification should name), `Couldn't resume snoozing` replaced a sentence about time
      limits and restarts, and `Snooze ended — it couldn't be saved` was deleted for being
      unparseable outside the code. The rest ship as they are.
      - **Named for the next pass** (maintainer, 2026-08-12): `Snooze ended — Snoozemo can't keep
        it going` is too long and wants shortening, and where the *initial* snooze is what failed
        the message should read more like `Can't snooze`. Worth separating the two cases while
        rewording, since one message currently has to cover both: a snooze that ran and then lost
        the capability, and one that never got going at all. The arm-failure path already has its
        own string (`Couldn't snooze`) — the question is whether the tense is right and whether
        the ended-message ever fires early enough to be describing an arm.
- [x] `endSnooze(reason)` as the single idempotent exit path, and the one-shot ended
      notification that names the reason (`SPEC.md` §4.5).
- [x] **Fix the access flow — the status line has to be the thing you tap** (maintainer,
      2026-08-12). `Snoozemo needs Do Not Disturb access` read like an action and wasn't one; the
      only live target was a separate `Grant access` button, so tapping the sentence that named the
      problem did nothing. **Landed** as one tappable row per capability — name, state, and what the
      tap does — with the whole row as the target, and the flow gone over end to end rather than
      that one line patched. Recorded in `SPEC.md` §5.2.
      - **It is not a runtime permission**, which was part of why the old shape was confusing:
        Do Not Disturb access is a Settings toggle reached with
        `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`, so the user leaves the app, flips it, and
        comes back — there is no in-app dialog and no result callback (`SPEC.md` §5.2). What the
        app *does* own is noticing the return: the service reconciles on every non-arm wake-up and
        the screen reconciles on open, so the state updates by itself once they are back.
      - **`POST_NOTIFICATIONS` is the one that really is a runtime prompt**, and the two sit next
        to each other on the same screen looking alike. Each row now carries an action line in the
        same position — `Opens Settings` or `Tap to allow` — so the difference is stated rather
        than left to be discovered by tapping.
      - **The third state is the one that made this more than a layout change.** The system shows
        the notification prompt at most twice and then silently ignores every request, so a row
        that always asked would be a *new* dead tap in place of the old one. `NotificationPermission`
        in `:core` decides between the three, and needs a persisted "has a denial ever landed" flag
        to do it: `shouldShowRequestPermissionRationale` reads `false` both before the first denial
        and after the last. A **denial**, not a request — a dialog dismissed without an answer costs
        nothing, so counting launches would strand a user who pressed back once (Codex, PR #18). The
        trampoline records it too — the two prompts belong to the app, not to each surface, and a
        tile-first user can be through both without opening the screen.
      - Covers the tile-first path too: a user who added the tile from the Quick Settings editor
        may reach this screen only after a failed arm, so it is as much a repair surface as an
        onboarding one.
      - Still owed a device: whether `ACTION_APP_NOTIFICATION_SETTINGS` lands where expected on
        Pixel and One UI, and how the rows read at the user's font scale.
      - **The app screen no longer asks for notifications by itself** — the row's `Tap to allow` is
        the affordance, and the automatic request was actively hostile once the row existed: tap the
        granted row, switch notifications off in Settings, come back, and the screen asked to turn
        them back on over a choice just made deliberately (Codex, PR #18). The tile trampoline still
        asks on its own, and must — a Quick-Settings-editor user may never open the screen, and for
        them a denied permission is an armed snooze with no visible state anywhere. Reversible, but
        reversing it needs a way to tell onboarding from a return-from-Settings.
      - **Known gap, deliberately left open: an install whose prompts were already exhausted before
        the flag existed** reads as never-denied forever, so its notifications row offers a prompt
        that never appears — the dead tap this item exists to remove, on the one install that
        cannot report it. Costs nothing today (the app is unreleased, so only a dev build can be in
        that state) and a reinstall clears it. Two candidate signals were considered and neither can
        be settled here: inferring from whether the request paused the caller (an auto-denied
        request may still start and finish the permission controller, so the inference is unsound —
        Codex, PR #18, after a first attempt shipped it), and reading the raw
        `onRequestPermissionsResult` arrays, where an **empty** array is the documented signal for a
        dismissed dialog as against a real answer — but `ActivityResultContracts.RequestPermission`
        collapses both to `false`, so it needs the deprecated API. **Pick one on hardware**, by
        denying twice and then watching what a further request does.
        - **A managed device reaches the same dead end by a different route** (Codex, PR #18): where
          an administrator policy fixes `POST_NOTIFICATIONS` to denied, there is no rationale and no
          stored denial on a *fresh* install either, so the row offers a prompt the platform will
          never show. Same gap, same absent remedy — `getPermissionFlags` needs a signature
          permission and `DevicePolicyManager.getPermissionGrantState` needs to be the admin, so an
          ordinary app cannot read either. Worth re-checking if Snoozemo is ever aimed at managed
          fleets; until then it shares whatever fix the item above gets.
- [x] **Offer to add the tile from the app screen.** `requestAddTileService` behind a third setup
      row, shown only while the tile is missing. `TilePresenceStore` in `:tile` keeps the answer,
      written from `onTileAdded` / `onTileRemoved` and from the request's own result — the three
      moments the platform volunteers it, since nothing can be *asked* whether the tile is there.
      - **Not an automatic prompt during onboarding, which is what this item originally said**
        (autopilot, 2026-08-13). The row is the same shape the access flow just took, Google's own
        guidance is to call `requestAddTileService` in response to a user action rather than on
        launch, and PR #18 had already removed an unprompted dialog for behaving exactly this way.
        Reversible — one call at the end of the post-first-frame read — and worth the maintainer's
        eye, since "asked once, never again" was their wording.
      - The default is **added**, so the row does not flash on every launch before the store is
        read; the store's own default is **missing**, so a fresh install is offered the tile. The
        two disagree deliberately: the transient wrong answer should be the invisible one, and the
        durable wrong answer should be the one that offers rather than hides.
- [x] **Keep the app screen out from under the system bars, and make each row offer a verb**
      (maintainer, 2026-08-13). Reported as the screen appearing behind the status bar and cutout —
      which it was: nothing declared edge to edge and nothing padded for insets, and every window
      is drawn edge to edge whatever it asks for from targetSdk 35 onward. **Landed** as
      `enableEdgeToEdge()` plus `safeDrawingPadding()` outside the scroll, with the trampoline's
      transparent theme re-parented so it follows dark mode too, and the setup rows reshaped in the
      same pass (`SPEC.md` §5.2, §11).
      - **Prior art followed rather than invented**: the sibling Simmo repo's `applyEdgeToEdgeForThemeMode`
        + per-screen `safeDrawingPadding()`, and its `GrantRow` — label block, then either a button
        or nothing. Simmo passes an explicit `SystemBarStyle` because its theme can be pinned Light
        or Dark against the system setting; Snoozemo's follows the system, so the default `auto`
        styling cannot disagree with what the app draws and the helper is not needed here.
      - **`safeDrawing`, not `systemBars`, and outside the scroll.** A cutout on a rotated phone
        takes a side inset the bars alone do not describe; inside the scroll the padding would move
        with the content and let a row slide under the status bar mid-scroll.
      - **The rows now carry `Grant` / `Allow` / `Add`, and nothing once the capability is in
        place.** Reverses two entries under *Decisions needing review* below — the granted rows
        stay tappable, and the `Opens Settings` / `Tap to allow` copy — both of which the
        maintainer asked for directly.
      - **Robolectric reports no insets**, so the tests dispatch a set onto the content view: with
        zero insets, a screen that handles them and one that ignores them render identically, and
        the snapshots would have kept passing through the reported bug. `EdgeToEdgeScreenshotTest`
        is in the CI allow-list.
      - Still owed a device: how the rows read at a large font scale with a button beside them, and
        whether a punch-hole cutout in landscape leaves the first row where the test says it does.
- [x] **The app screen keeps a snooze button, and the screen leads with the tile** (maintainer,
      2026-08-13: *"we should have a main view that has a snooze button but … we should strongly
      encourage using the tile"*, then *"dismiss forever from main is fine, always have it in
      settings"*). The question was whether the tile is the *only* arm path at release — the
      screen's `Snooze` / `End snooze` is marked Phase 1 scaffolding that "never reaches a release"
      and nothing was scheduled to replace it. **This revises a decision the spec already stated**
      (§4.2's "the tile is the arm affordance") rather than filling a gap, so §4.2 carries the
      revision beside the original line (Codex, PR #22). Only the tile reaches the trampoline *to
      arm*; the notification actions reach it too, but only to end or extend a running snooze.
      - **Landed**: a dismissible banner leading with the tile, above a permanent tile row.
        Dismissal is forever and is not re-raised when the tile is removed; the row is what makes
        that safe, and it becomes a statement rather than an offer once the tile is there.
      - Still to do: **promote the debug arm/end controls to real ones** — real copy through the
        propose-in-chat step, and no longer deleted when the debug screen is.
      - `Don't ask again` rather than `Not now`, since the dismissal is permanent and "not now"
        promises a return that never comes (Codex, PR #22). Landed first as `Don't show again`
        without the propose-copy step and flagged; **the maintainer approved the shorter form**
        (2026-08-13), which is also the more common idiom for a dismissal that sticks.
      - **The permanent tile row stays visible while the banner is showing** (autopilot,
        2026-08-13, after the maintainer had no preference). The two do overlap — both offer to
        add the tile — but hiding the row is what the banner's own justification rules out:
        dismissal is forever precisely *because* the row outlives it. Hide the row and the
        banner's `Don't ask again` becomes a promise the screen cannot keep, since a user who
        dismisses before adding the tile would be left with no route at all until the banner
        logic happened to bring one back. Cheap to revisit if the doubled offer looks noisy on a
        real screen — it is a single `if` — but the ordering matters: any change here has to
        answer the permanence question first.
      - "Always have it in settings" is a row on the main screen for now, because there is no
        settings screen yet. When one lands, that row is what moves into it.
- [x] Real anchor capture on the arm path — landed with the Phase 3 capture item below
      (2026-08-22). The snooze still *arms* honestly duration-only, by an explicit mode at the
      one call site, until the monitor consumes what was captured.
- [x] **minSdk 34** (maintainer, 2026-08-11). The tile's `startActivityAndCollapse` needed the
      deprecated `Intent` overload on API 33; raising the floor deleted the version branch and the
      lint suppression together, so the arm path is a single code path again. Reasoning recorded in
      `SPEC.md` §11.
      - [x] **Raised again, to minSdk 35** (maintainer, 2026-08-23, PR #88). The Filters row's
        `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` needs Modes UI, an Android 15+ AOSP feature
        flag with no lower-API equivalent that reaches the same per-rule screen — unlike the 33→34
        move, there was no version-branch alternative to delete, only Android 14 devices to drop.
        Reasoning recorded in `SPEC.md` §11.
- [ ] Verify what the tile looks like in the **compact/collapsed** Quick Settings panel on Pixel
      and One UI. Some presentations show the icon only — no label, no subtitle — so the glyph has
      to carry the meaning alone. Widens the §10 question about whether `Tile.setSubtitle` renders
      at all. **No longer blocking:** the maintainer settled the direction (2026-08-11) — they run
      the tile as **1×1**, so icon-only is the expected presentation, the notification carries the
      status, and nothing load-bearing goes on the tile (`SPEC.md` §4.2). What is left is a polish
      check plus one real design consequence: the icon has to carry armed-vs-inactive on its own,
      with no label beside it, which is an input to the Phase 0 tile-mark item.
- [x] Persist `ActiveSnooze` on every transition, so process death is recoverable. Landed on
      `SharedPreferences` rather than DataStore, deliberately: it is read on the arm path and at
      every service start, and must not cost a coroutine or a disk wait at tap time. Settings,
      which are neither hot nor on that path, still go to DataStore when they land.
- [x] **Move the policy-access listener off the activity** (flagged by Codex on PR #5) — it now
      lives on `SnoozeService` for as long as a snooze runs.
- [ ] ~~Move the policy-access listener off the activity~~ (superseded by the line above).
      Phase 1 registers `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` in
      `onStart`/`onStop`, so access revoked while the app is backgrounded mid-snooze isn't
      noticed until the user reopens it — the state reconciles on return, but "ends the
      snooze and says why" (`SPEC.md` §8.2) needs to happen when it happens, not when
      someone looks. It belongs on the service that owns the running snooze, which is what
      this phase builds; an activity is structurally the wrong host for it, and process
      death would defeat an `Application`-scoped receiver anyway.

## Phase 3 (M3) — Presence: the `play` flavor

- [x] **Declare the location permissions** (approved by the maintainer, 2026-08-22, for
      sideload testing): fine + coarse and `ACCESS_WIFI_STATE` in the shared manifest,
      `ACCESS_BACKGROUND_LOCATION` in the `play` flavor's alone, pinned in both directions —
      and `INTERNET`'s absence with them — by `DeclaredPermissionsTest`, which runs per
      flavor variant. The `direct` flavor's foreground-service declarations wait for Phase 7's
      service; the in-app prompt and prominent disclosure are Phase 6's items and need copy.
- [ ] `PresenceMonitor` interface and `GeofencePresenceMonitor`, with everything above the
      interface flavor-agnostic (`SPEC.md` §6.1).
      - [x] **The engine above the interface**: `Presence`, a pure state machine in `:core` over
        (state, signal, anchor). Owns escalation and de-escalation, the §6.7 duty cycle, the
        degraded-tracking report, and the §6.6 grace period. Landed with `PresenceTest` (37),
        written as sequences rather than single calls — a router rebooting, a provider emitting
        junk for ten minutes, an alarm arriving after the reason for it went away.
      - [x] `GeofencePresenceMonitor` itself: registration, the exit callback, and the
        recoverable/fatal split at the platform boundary — a degraded *level* on the
        `PresenceUpdate` versus a `CapabilityLost` event, which is the one distinction the monitor
        must never get wrong (`SPEC.md` §6.1). Needs a handset to verify.
        **Landed as the first slice** (2026-08-22): the fence registers with a mutable
        PendingIntent at a non-exported receiver, exits cross `GeofenceSignalBridge` into the
        shared `PresenceFeed`, and every refusal classifies through
        `GeofenceRegistrationFailure` (only `GEOFENCE_NOT_AVAILABLE` degrades; the permission
        codes and everything unclassified fail open). The `direct` flavor compiles against a
        `DurationOnlyPresenceMonitor` behind the same `defaultPresenceMonitor` seam.
        **Second slice landed** (2026-08-22): the confirming fixes — `CheckingFixes` one-shot
        `getCurrentLocation` requests, started and stopped by the engine's own duty, paced by
        `CheckingCadence` (30 s per §6.6's gap, backing off to 5 min after three unanswered
        requests). One-shots rather than §6.5's continuous request, deliberately: that request
        belongs to the `direct` flavor's Phase 7 foreground service, and a background app gets
        continuous location throttled to nothing — §6.10's design takes one fix per
        confirmation step instead. `SANITY` duty maps to nothing yet; its resting-coverage role
        belongs to the §6.10 periodic backstop when that slice lands.
        **Third slice landed** (2026-08-22): the service wiring. `SnoozeService` starts
        `defaultPresenceMonitor` with the captured anchor once the arm completes (and again on
        restore — the presence analog of re-asserting the rule), collects every report into
        `SnoozeController.onPresenceUpdate`, and stops the watch with the snooze. The mode's
        warrant moved with it: `PresenceMonitor.supports(anchor)` states the most capable mode
        the running machinery can honestly claim, and the controller clamps every mode to that
        ceiling (`SPEC.md` §6.1) — so a fenced anchor is genuinely `FULL` now, an SSID-only one
        stays a timer until the Wi-Fi watch lands, and the `direct` flavor stays duration-only
        by its own monitor's answer.
        **Restore-from-wake landed with it** (sharpened by Codex on PR #73: with no foreground
        service the process dies routinely mid-snooze, so the geofence broadcast restarting a
        dead process is the field's *common* case, and a dropped exit is a departure never
        confirmed). The bridge is a one-slot mailbox: an undeliverable observation is held —
        coalesced by rank, an exit never displaced by an availability report — the
        app-installed wake-up starts `SnoozeService.restore` (a refused start arms a 60 s
        check-in alarm as the durable retry), and every attach of a restored monitor delivers
        the held observation, so a death mid-confirmation re-runs the check rather than losing
        it. The engine's arm-time evidence seed — now passed through `start` by the caller,
        derived from the record's stored clock frame — is what retires the slot for every
        later snooze. The geofence itself is durable by design: `awaitClose` releases only
        in-process resources, and the fence comes down solely in `stop()`, when the snooze
        actually ends — a background-limits service destroy leaves it watching. Still to come
        in later slices: **re-registering the
        fence when location services come back on** (Codex, PR #70: `GEOFENCE_NOT_AVAILABLE` at
        registration reports the §8.4 degradation correctly but nothing retries, so the snooze
        stays degraded until the cap even after the user re-enables location — the mode-changed
        listener belongs with the same device-state watching the checking burst needs) and the
        on-device verification the whole item is gated on. The grace alarm for
        `graceDeadlineMs` landed with the Wi-Fi suppressor slice.
      - [ ] **The grace deadline has to survive process death** (Codex, PR #31, re-flagged and
        partly mitigated on PR #77). `PresenceState` is in-memory only; a service killed after
        arming the five-minute grace alarm comes back with no deadline, so the alarm's signal is
        ignored and the snooze runs to the cap — the silence the grace period exists to bound.
        PR #77's `PlatformWifiWatch` fix for the "started already disconnected" gap bounds the
        worst case for a Wi-Fi-only anchor: a cold restore now explicitly re-reads the current
        association on registration, so a restore that lands still off the anchor's network
        re-delivers `AnchorWifiLost` and re-arms a *fresh* five-minute grace from the restore
        moment — one extra grace window of latency, not silence to the cap. A fenced anchor that
        was already degraded when it died still has the gap open until this lands. The monitor is
        what owns persistence, so this lands with it, and there are two frames to get right: the
        deadline is elapsed realtime, so it survives process death but **not** a reboot, where the
        alarm is gone too and the boot restore has to re-derive it. `ActiveSnooze.bootReference`
        already exists for exactly this problem on the duration cap.
      - [ ] **The degradation *cause* stops at the controller** (Codex, PR #31). `Presence` now
        tells `FIXES_TOO_VAGUE` from `NO_LOCATION_FIX`, and `SnoozeController` maps both to the
        same `TrackingMode`, which is all `SnoozeService.onTrackingChanged` renders from — so the
        notification says `Wi-Fi only` either way and the distinction never reaches the user.
        Fixing it means new user-facing copy, which needs the propose-in-chat-and-approve step
        (`AGENTS.md`, *Translations*), so it is a follow-up rather than part of the engine PR.
        - **And the *mode* is wrong too, during the grace period** (Codex, PR #31). `modeFor`
          picks `WIFI_ONLY` whenever the anchor *has* an SSID, so while the grace period runs —
          Wi-Fi gone, location vague — the notification claims Wi-Fi is tracking a snooze that
          nothing is tracking. `DURATION_ONLY` is not the fix: it says only the cap will end
          this, when in fact a five-minute grace period will. **No existing `TrackingMode`
          describes "unverifiable, ending shortly unless something recovers"**, which is what
          the user actually needs to read, so this wants the same approved-copy step and
          probably a mode of its own. Both halves land together.
      - [x] **Recovering from degraded mode has no path back** (found while building the engine).
        The engine forgot a degradation once location or the anchor's Wi-Fi returned, but the
        controller left the `TrackingMode` lowered, so the notification kept reporting degraded
        tracking for the rest of the snooze. Fixed in PR #33, then **rebuilt in PR #34**: the first
        version announced each recovery as an event and took nine review rounds, because every
        ordering question became "did the announcement survive?" Health is now a level on
        `PresenceUpdate` and the controller acts on the difference, which is why none of that
        machinery — `Degraded`, `TrackingRecovered`, `PresenceEvidence`, `pendingRecovery`, the
        paired callbacks — exists any more. **Do not reintroduce it**: a recovery that has to be
        delivered is a recovery that can be lost, and `SPEC.md` §6.1 records the four invariants
        that survive whatever shape the engine takes.
- [x] Anchor capture at arm time — with the ≤10 s ceiling that degrades to Wi-Fi-only or
      duration-only rather than blocking the arm. **The SSID is the anchor; the connected
      BSSID is recorded alongside it** (`SPEC.md` §6.2). Those are two different
      statements, and an earlier "SSID not BSSID" here read as an instruction to skip the
      BSSID entirely, which would leave `Anchor.bssid` permanently null and the room
      feature unbuildable without a second capture change (Codex, PR #24). Nothing in v1
      *acts* on the BSSID — it is only captured, and `docs/PRIVACY.md` says so.
      **Landed 2026-08-22**: the assembly rules (accuracy gate, redaction-placeholder
      rejection, first-usable-answer-wins, the ceiling) are pure in `:core`'s
      `AnchorCapture`; the platform half (`AnchorCaptureRunner`, shared by both flavors)
      starts strictly after `STATE_TRUE`. The captured anchor is recorded but the snooze
      still arms with an explicit `DURATION_ONLY`, because a mode is a claim about what is
      *watching* and nothing consumes the anchor until the monitor wiring lands — that
      slice replaces the explicit mode with `TrackingMode.from(anchor)`. Still owed a
      handset: that the flag plus our permissions yields a real SSID end to end (the §6.4
      item below), and what an indoor fix's accuracy actually is.
- [ ] **Register the Wi-Fi callback with `FLAG_INCLUDE_LOCATION_INFO`** (`SPEC.md` §6.4),
      and assert on a real device that the SSID comes back as an SSID. Without the flag a
      `NetworkCallback` requests no location-sensitive data, so `WifiInfo` arrives redacted
      — `UNKNOWN_SSID` and `02:00:00:00:00:00` — no matter which permissions are held
      (Codex, PR #24). This breaks the **SSID anchor**, not just the BSSID, and it fails
      quietly: real objects, plausible strings, an anchor that matches nothing. Any test
      here must reject the placeholders rather than accept them as values.
- [ ] Three independent wake-up sources feeding one confirmation test (`SPEC.md` §6.10):
      geofence exit (landed, PR #73), the `WorkManager` backstop (landed — see below), and
      Wi-Fi loss via `NetworkCallback` (landed with the D4 suppressor below). No source
      ends a snooze on its own evidence.
      - **The periodic backstop landed**: a 30-minute `WorkManager` wake while armed that
        restores the service (re-arming the cap, reconciling policy access, re-registering
        the fence, collecting any held exit) and takes **one resting fix** through the
        burst's own requester, so a departure the geofence never reported gets tested by
        §6.6. It retires itself on a wake that finds no record.
      - The backstop's wake is also where a **mid-snooze location revocation** gets noticed
        (Codex, PR #73): revoking a runtime permission kills the process, so no in-process
        watcher can exist. The resting probe re-checks the grants each wake and a lost
        grant fails open as `LOST_CAPABILITY`, same as registration's — closing the
        revoked-while-ARMED window to roughly the backstop's cadence (best-effort under
        Doze and battery saver — `SPEC.md` §6.10) instead of the cap.
      - Same shape, same wake (Codex, PR #73): **a fence the arm never got to establish**.
        The record says `FULL` before Play Services confirms the asynchronous registration
        (the arm path cannot wait on it), so a process reclaimed in that instant leaves a
        full-tracking record with no fence behind it. Every restore re-registers, so the
        backstop's periodic restore bounds the healing to roughly its cadence (best-effort,
        `SPEC.md` §6.10) instead of the cap.
      - The kill-mid-confirmation residual (the in-memory mailbox dying with the process)
        is likewise bounded to roughly the backstop's cadence now (best-effort, `SPEC.md` §6.10): the backstop's resting probe re-tests presence
        even though the held exit is gone. Full `PresenceState` persistence remains its
        own recorded slice.
- [x] The departure test itself (`SPEC.md` §6.6): accuracy gate, 50 m hysteresis, two
      qualifying fixes ≥30 s apart *or* one unambiguous fix beyond radius + 500 m. Covered
      by recorded fix traces including bad-accuracy jumps. Landed in `:core` as `Departure`
      — pure, no Android, no clock — with `DepartureTest` (17) replaying traces for the
      vague cell fix, the GPS jump, the walk to the end of the garden, the burst of fixes,
      and the anchor with no coordinates at all.
      - **One property worth knowing rather than discovering:** the unambiguous shortcut
        means a *single* fix ends a snooze when its margin clears radius + 500 m, so a
        provider reporting a confident wrong fix 900 m out is believed. That is §6.6's
        deliberate direction — principle 1 prefers a snooze that ends early to one that
        keeps a phone silent — and it has its own test saying so rather than being left
        implied. It is also why the *margin* must clear 500 m, not the raw distance.
      - **The traces are synthesized, not recorded** (Codex, PR #30), and `AGENTS.md` asks
        for recorded ones. Recording needs a handset walking a real building, and the app
        has no trace recorder yet — so this is the same dependency as the maintainer's
        home walk, and it closes when the recorder lands later in Phase 3. The synthesized
        traces stay either way: what they cannot do is surprise their author with the
        accuracy pattern a real provider emits, which is precisely what a recorded trace
        adds rather than replaces.
      - **Worth measuring in the field: does clearing the window on an inconclusive fix
        delay real departures?** (Codex, PR #30.) §6.6 asks for two *consecutive*
        qualifying fixes, so a reading that cannot place the user closes the window — which
        is the literal rule and stops an ambiguous fix joining two isolated outliers. But
        leaving a building is exactly when fixes go vague, so in poor signal a genuine
        departure may need several attempts before two clean readings land in a row. That
        delays confirmation rather than preventing it (more fixes keep arriving, Wi-Fi loss
        escalates independently, and the cap bounds it), and the recorded walk is what would
        show whether the delay is material.
      - **The maintainer has sanctioned the middle option** (2026-08-13): let an
        inconclusive fix *hold* the confirmation window open without extending it —
        "seems completely reasonable, keep that as an option". So this is a live
        alternative rather than a contingency, and whoever measures the delay does not
        need to re-open the question of whether it is allowed. What it means concretely:
        an ambiguous reading neither confirms nor resets, so two qualifying fixes either
        side of one still confirm on the *original* window's clock — the vague fix costs
        nothing, and an outlier still cannot bridge to another outlier without a second
        qualifying reading. The literal reading of §6.6's "consecutive" ships until the
        walk says otherwise, because that is the conservative direction, not because the
        alternative is unavailable.
      - Still owed: the platform monitors that *deliver* signals. The decisions around the
        test — escalation, the duty cycle (§6.7), the degraded report, the grace period —
        landed as `Presence`; what remains is the three wake-up sources (§6.10) feeding it.
- [x] Wi-Fi as suppressor only (D4): associated with the anchor SSID suppresses location
      work entirely; loss escalates to `CHECKING` and never ends a snooze on its own.
      Landed as `AnchorWifiTracker` (the pure transition machine) + `PlatformWifiWatch`
      (the `NetworkCallback` registration) feeding the engine's existing signals, with the
      §6.6 **grace alarm** (`GraceAlarm`, inexact like the cap) armed from the engine's
      deadline and delivered back through the bridge — the piece without which `WIFI_ONLY`
      was a labeled timer. The suppressor also gates the backstop's resting probe: on the
      anchor's Wi-Fi, no location work at all (§6.7).
- [x] **The on-device debug log** (`SPEC.md` §4.6), landing here rather than later because this is
      the phase that needs it: hardware item 2 asks for every geofence callback measured against a
      ground-truth departure over a week of ordinary use, and there is no way to collect that by
      watching a phone. Records state transitions and their reasons, which wake-up source fired, the
      departure test's distance and accuracy arithmetic, tracking-mode changes, cap arming and
      firing, and permission state. On by default with a setting to turn it off (maintainer,
      2026-08-11), on-device, current run plus previous, rotated at start, in `cacheDir`. The floor is absolute and needs a test of its own: **no raw coordinates,
      no full SSID/BSSID, no user-typed place name** ever reach it.
      **Landed as the recording half**: `SnoozeDebugLog` in `:core` (bounded buffer, sinks, real
      timestamps with zone offset, the floor test) and `DebugFileSink` in `:app` (the §4.6
      rotation, the crash pin holding the `previous` slot, off-deletes-everything), wired to the
      state transitions, end reasons, zen refusals, policy-access decisions, cap arming and
      firing, clock changes, and the no-service releases. `docs/PRIVACY.md` now describes it.
      Ported from Simmo's `DebugLog`/`DebugFileSink` with §4.6's differences. Three pieces land
      elsewhere, deliberately:
      - **The wake-up-source and departure-arithmetic records** go in with the monitors that
        produce them (the `GeofencePresenceMonitor` item above) — the log API is ready for them.
      - **The settings row** that turns it off — **landed** (2026-08-22) with the maintainer's
        copy, the toggle/install race serialized onto one worker, only a persisted choice
        applied (both deferred from Codex's PR #62 review), and a failed save said under the
        row with the approved `Couldn't save this setting`.
      - **The sharing surface and post-crash banner** are Phase 5's item, unchanged — the crash
        pin already preserves what the banner will offer.
- [x] **Maintainer decision: is the debug log off by default?** Answered — **on by default**
      (maintainer, 2026-08-11), with a setting to turn it off. Off would have guaranteed that the
      first occurrence of every unrepeatable failure was the one nobody captured. Recorded in
      `SPEC.md` §4.6 with the reasoning.
- [ ] Departure latency instrumented against ground truth, on-device (hardware item 2).
- [ ] **Submit the background-location declaration** as soon as there is a working
      departure to film. Longest-lead item and the largest project risk (`SPEC.md` §3.5);
      it runs in parallel with Phases 4–5 rather than blocking them.

## Phase 4 (M4) — End-condition sheet

`SPEC.md` §4.4 is explicitly provisional — treat its mockups as a starting point.

- [x] **Split the permission-setup rows from the Arm/Release view** (maintainer, 2026-08-22).
      **Landed** (maintainer, 2026-08-23) as three screens rather than the two the placeholder
      above weighed: `MainScreen` (the tile-equivalent Arm/Release control, a banner for the one
      required-and-missing permission, the tile banner, Settings entry), `PermissionsScreen` (the
      interstitial — the DND/notification/location setup rows, reached automatically the first
      time DND access reads as missing, or from Settings any time after), and `SettingsScreen`
      (the permanent tile row, the debug-log switch, the Permissions entry). Resolves the rename
      below as a side effect — the `DebugScreen` composable and file are gone, replaced by these
      three named ones and a shared `SharedComponents.kt` — but the `debug_*` string IDs still
      carry the old name; see below. **Does not** resolve "only one of `Snooze`/`End snooze`
      should show at a time" below — deliberately: `MainScreen`'s buttons keep the exact gating
      the old `DebugScreen` had (both render only once `access == PolicyAccess.GRANTED`), so this
      split stays scoped to moving the screens apart and doesn't also make that design call.
      - **The routing decision**: `MainActivity` auto-navigates to `PermissionsScreen` once, the
        first time a fresh DND-access reading comes back missing — not on every later revocation,
        which lands on `MainScreen`'s banner instead (`SPEC.md` §8.2's own recovery path, so a
        snooze that loses access mid-run doesn't yank the user off whatever they were doing).
        There is no persisted "onboarding complete" flag: nothing forces the interstitial once DND
        access is granted, and there's no cost to asking again on a later cold start if it still
        isn't (D7, "fail open" — never trap, never nag past what the state actually requires).
- [x] **`MainScreen` shows the current snooze's status** (maintainer, 2026-08-23) — the comment
      left on the screen split above suggested this needed presence tracking (Phase 3) first;
      it didn't, since `ActiveSnooze.mode`, `.placeName` and `.remaining()` are already populated on
      every record today, duration-only snoozes included. **Landed**: `MainScreen` shows the mode
      line (`Ends when you leave` / `Wi-Fi only` / `Timer only`) and the remaining time whenever a
      snooze is running, reusing the exact copy the ongoing notification and the tile already use
      (`SPEC.md` §4.3) rather than inventing a third phrasing. Not a live per-second countdown — it
      repaints on a record change and on a once-a-minute tick while the activity is visible
      (Codex, PR #87), matching the display's own granularity rather than the notification's own
      chronometer, which stays the one live countdown. Place name is deliberately left out: it is
      always literally `"Here"` today (saved/named places are unbuilt — "Saved places" below), and
      the notification doesn't show it either, so surfacing it here first would only read as
      filler — it will render for free once saved places land, no `MainScreen` change needed then.
      - **To-do (maintainer, 2026-08-23): reconsider ticking every second instead of once a minute.**
        Not a correctness question — a faster tick is fine either way — purely whether the display
        should ever show seconds, motivated by matching the ongoing notification's own chronometer
        rather than reading coarser than it. Minutes-only stands until that's revisited (maintainer,
        same day: "minutes is fine").
      - **To-do (maintainer, 2026-08-23): reconsider the `Wi-Fi only` copy itself.** Maybe
        `Ends when disconnected from {network}` reads better than the generic mode label — it
        names the actual condition rather than the mechanism. Raised alongside a further idea
        worth exploring together: **stating every end condition at once as a list**, rather than
        one mode label standing in for whichever single condition currently governs. Maintainer's
        sketch:
        ```
        Ends
        * when you leave
        * when {network} disconnects
        * when {event name} ends
        * in 1 hour
        ```
        Interacts with two things already tracked separately: `FULL`/`WIFI_ONLY`/`DURATION_ONLY`
        are today an internal capability ceiling that collapses to one label, not necessarily the
        shape user-facing copy should take, and the "{event name} ends" line is **calendar-seeded
        end times** above — unbuilt, waiting on the Play declarations. Not started: needs new copy
        (proposed in chat, approved, then translated — *Translations*) and touches the
        notification/tile too if adopted there, not just `MainScreen`.
- [ ] **Only one of `Snooze` / `End snooze` should show at a time** (maintainer, 2026-08-22;
      still open 2026-08-23 — the screen split above deliberately didn't touch this). Today both
      buttons render together whenever DND access is granted (`MainScreen.kt`, after the split) —
      `Snooze` merely disables itself while a snooze is running, but `End snooze` is *always* shown
      and enabled, even when `snoozing == false`. That's not an oversight: the comment above it
      cites `SPEC.md` §7 — manual exit is "always available, always instant," and `endSnooze` is
      idempotent, so a stale or unread `snoozing` value must never be what blocks the one
      guaranteed way to un-silence the phone. Hiding `End snooze` whenever `snoozing == false`
      would reintroduce exactly that risk if the reading is ever wrong. Needs a real design
      answer, not a blind toggle — options include showing `End snooze` only when `snoozing` is
      `true` **or** still `null` (unknown defaults to showing the safety net, confidently-false
      hides it), a single button that relabels itself by state, or leaving both gated behind
      `access == GRANTED` as they are today. Whatever's chosen has to preserve the
      idempotent-and-always-reachable guarantee.
- [ ] **Reconsider granted-status text as a single word app-wide** (maintainer, 2026-08-23).
      `PermissionsScreen` currently uses two — `Granted` for Do Not Disturb access (paired with its
      `Grant` action, since that one is a Settings toggle, not a runtime prompt) and `Allowed` for
      notifications and location (paired with `Allow`). The maintainer asked whether this should
      instead be one word everywhere; checked the sibling Simmo repo for a precedent and found none
      to follow — Simmo's `GrantRow` (`AGENTS.md`'s citation for this row's shape) says `Done`, not
      `Granted` or `Allowed`, and Simmo has no permissions *settings* screen of its own to compare
      against, only its one-time onboarding flow. Deferred rather than guessed at; needs a real
      answer, not a coin flip between two similarly-defensible options.
- [x] **Rename `debug_arm`, `debug_release` and the other `debug_*` string IDs**
      (maintainer, 2026-08-22; narrowed 2026-08-23 now the composable rename above has landed).
      **Landed** (2026-08-23) as `arm`, `release`, `rule_failed`, `rule_disabled` — topic-named
      rather than screen-prefixed (maintainer, 2026-08-23: naming a string after whichever
      composable currently renders it is what produced `debug_*` outliving `DebugScreen` in the
      first place). Swept the same fix over the screen split's own new strings while in here:
      `permissions_screen_title` → `permissions_title`, `settings_screen_title` →
      `settings_title`, `main_dnd_banner_title` → `dnd_banner_title` — the last one paired with
      `tile_banner_title`, the sibling banner that already had this right.
- [ ] The two rows (`until <time>` seeded at now + 1 h rounded to the half hour, and
      `until I leave`), with `−` / `+` in 30-minute steps, floored at 30 min from now and
      ceilinged at the 8 h backstop.
- [ ] Choosing a time **lowers the cap**; it does not disable departure tracking. Whichever
      comes first wins (`SPEC.md` §7).
- [ ] Dismissing the sheet, or never seeing it, leaves the user correctly snoozed.
- [ ] Setting to disable the sheet entirely — the trampoline then finishes in `onCreate`.
- [ ] Screenshot tests for the sheet, wired into the CI allow-list.
- [ ] The sheet handles its own insets. It arrives in the trampoline, whose theme is transparent
      and now follows dark mode, but which deliberately declares no edge-to-edge of its own —
      nothing may run between `onCreate` and the service start (`SPEC.md` §6.9), so the call
      belongs after it, in the same posted block the sheet is rendered from.

## Phase 5 (M5) — Edge cases and degraded modes

- [x] **Make the refused-release escalation one pure decision in `:core`** (`SPEC.md` §7.1).
      **Landed** as `ReleaseEscalation` + `ReleaseProgress` + `ReleaseStep`, with the service and
      the no-service receiver path as its two performers; `SnoozeService` lost ~95 lines and the
      duplicated ladders are gone. Not quite behavior-preserving in the end — three of the copies
      were out of line with the ordering the spec states, and bringing them into line is what the
      move was for. The differences are listed under *Decisions needing review* below.
      Originally scoped as behavior-preserving: the ladder is already store obligation → arm alarm → retry in process →
      tell the user → exhausted. What changes is that it stops being written out separately in the
      service's two escalations, the cap receiver, the boot receiver and the trampoline fallback,
      and becomes a state → next-step function each of them advances.
      - **Why now rather than as polish.** Codex's review of PR #8 produced *nine consecutive
        rounds* of real findings in this ladder, every one an instance of a single copy forgetting
        a shared rule, and **four of them were introduced by the fix to the previous one**. The
        defect rate tracks the number of copies, so removing the copies is the fix; fixing the
        instances is not converging.
      - **The shape**: inputs are `ruleOff`, `recordPresent`, `obligationStored`, `alarmArmed`,
        `inProcessAttemptsUsed`, `userTold` — each meaning *did this actually take*, not *was it
        attempted*, which is the distinction the duplicated versions kept losing. Output names
        every rung including `Exhausted`, so no caller can fall off the end while still claiming a
        retry is underway.
      - **The payoff is testability, not tidiness.** None of these branches is reachable without a
        platform that refuses a zen write, so today the whole ladder is argued rather than
        executed and review is the only thing testing it. As a pure function it is reachable from
        a JVM test — the same argument `SPEC.md` §11 makes for `SnoozeController`. Cover each rung
        and each "attempted but refused" transition; add a case whenever a field bug shows one.
      - **Expect a round or two of review findings on the new shape itself.** That is fine and is
        the point: they will land against something a test can reach.
      - Carries no user-visible change, so it is a `refactor:` commit — but the tests it makes
        possible are the deliverable, not the move.
- [ ] Service killed and recreated: re-assert the zen rule, resume tracking, and where a
      background context can't get location, degrade to duration-only with a
      `Resume tracking` notification action (`SPEC.md` §8.1).
- [ ] Reboot: re-assert the rule, degraded mode, cap continues from the *original* start
      time. `On restart: resume / end` setting, defaulting to resume (`SPEC.md` §8.3).
- [ ] Permission revoked mid-snooze — policy access or location — ends the snooze with a
      reason (`SPEC.md` §8.2).
- [ ] The §8.5 table: airplane mode, location services off, double-arm, short trip and
      return, bad-accuracy anchor, battery saver, uninstall while snoozed.
- [ ] The §8.4 cases: `restricted` standby bucket, force-stop, OEM battery management.
- [ ] **The user turning Do Not Disturb off from the shade may silently end our snooze**
      (maintainer, 2026-08-12 — "we should handle that soon"). §5.6 covers the *pre-existing*
      case at arm time; this is the state changing underneath a running snooze. If switching
      DND off deactivates Snoozemo's rule, the snooze is over while the record, the tile and
      the notification all still say it is running — state drift in the direction that makes
      the app look broken rather than unsafe, but drift the user caused deliberately and will
      expect us to notice.
      - **Confirm the platform behavior first**, because it decides whether there is anything
        to build: does turning DND off from the shade deactivate an app-owned
        `AutomaticZenRule`, leave it active-but-overridden, or neither? On the hardware list.
      - If it does deactivate: treat it as an ordinary end — clear the record, take the
        notification down, update the tile, and give it its own `EndReason` so §4.5's "every
        ending has a reason" holds. It is the user's own action, so there is nothing to warn
        about and nothing to retry.
      - **Observable without new cost**: `ACTION_INTERRUPTION_FILTER_CHANGED`, plus reading
        our own rule's state back. Register it beside the policy-access receiver, which has
        the same process-lifetime limit (§8.4) — so this is reliable on the wake-ups we
        already have and **must not** justify adding one.
      - The two neighboring cases are deliberately *not* in scope: DND turned on by the user
        or another app while we are idle (harmless; at most the tile reads "not snoozing"
        beside a quiet phone), and another app's rule ending while ours is on (nothing to do).
- [ ] **A reboot that stays locked outlasts the cap** (flagged by Codex on PR #8).
      `BOOT_COMPLETED` reaches credential-unaware components only after the *first unlock*,
      and the snooze record lives in credential-protected storage, so a phone rebooted
      mid-snooze and left locked past its cap never runs the boot receiver — while the zen
      rule may still be on from before. Fixing it means a minimal record in device-protected
      storage (the cap instant and the rule id, nothing more — the anchor and place name stay
      credential-protected), a direct-boot-aware receiver on `LOCKED_BOOT_COMPLETED`, and a
      release path that works with only that. Worth confirming on hardware first: whether
      DND survives a reboot at all, and whether `setAutomaticZenRuleState` is callable
      during direct boot — the fix is shaped differently if either answer is no.
- [x] **A backwards wall-clock change can outlast the cap** (flagged by Codex on PR #8).
      The cap alarm was `RTC_WAKEUP` and the expiry test read `Clock.systemUTC()`, so both
      moved with the wall clock: setting the date back kept DND on past the 24 h backstop,
      which is principle 1's failure. Fixed as `SPEC.md` §7 now describes — the record
      stores its wall-clock deadline alongside the clock offset it was written under, the
      cap alarm is `ELAPSED_REALTIME_WAKEUP`, every cap decision takes the smaller of what
      the two clocks say is left, each reboot restates the offset (ending the snooze if that
      restate cannot be written, since a stale offset is believed rather than ignored), and
      a `TIME_SET` change folds the remaining time back into the wall deadline (uptime's
      answer would otherwise die with the next boot) and
      re-checks the cap and ends the snooze only if it is already due. That receiver
      deliberately **never re-arms from the record as found** — against a stale offset a
      backwards change would compute a longer delay than the one already armed and replace a
      correct alarm with an overlong one, which is the overrun this item exists to remove;
      the one sanctioned re-arm is the post-restate one the closed sub-item below describes,
      when the frame being read was written that instant. The change is performed
      through the running service wherever one will start, since the record and the
      controller are two copies of the same snooze and repairing only the first leaves
      `+30 min` to write the pre-change deadline back over it; a record carrying **no**
      offset ends on a clock change rather than being stamped with a frame it was never
      measured in. An earlier attempt
      using a process-wide anchored clock is recorded in §7 as the trap it turned out to
      be: it overran the cap whenever the record crossed a process boundary.
      - ~~Still open: a forward change that does **not** clear the deadline leaves the record
        reading the shortened wall answer while the armed alarm still counts the original
        elapsed delay~~ — **closed** (2026-08-21), exactly as this entry proposed: both
        performers re-arm the cap immediately after a restate reaches disk, when the frame is
        provably fresh; the never-re-arm rule still governs every other path, and §7 carries
        the amendment with the safety argument (an armed alarm implies a this-boot offset, and
        where the offset really is stale no alarm survived the reboot to be replaced). A
        refused re-arm ends the snooze like a refused restate write does — the record would
        otherwise promise a deadline no scheduled alarm honors, with only a log saying why
        (Codex, PR #63). Pinned by the forward, backward, refused-re-arm, and no-service
        fallback tests in `SnoozeServiceClockChangeTest`.
      - Still open, and shared with the locked-boot item above: a clock moved **while the
        phone is off**, or a reboot that stays locked so the boot receiver never restates
        the offset. Wall time is already wrong by the time anything runs, with no second
        frame left to check it against, so the 24 h ceiling is the only backstop and a
        smaller shift is undetectable. The direct-boot record fixes both at once.
- [ ] Pre-existing DND: Snoozemo arms on top and, on release, turns off only its own rule
      (`SPEC.md` §5.6).
- [ ] **Bug: turning off system Bedtime Mode while a Snoozemo snooze is still armed shows every
      notification** (maintainer, 2026-08-23), when it should stay silenced — Snoozemo's own zen
      rule is still `STATE_TRUE`, so the platform's composed interruption filter should still be
      restrictive regardless of what any other rule just did. Needs investigating on a real device
      (`android.app.AutomaticZenRule`/`NotificationManager` compose multiple active rules by their
      own logic, not this app's, and the sandbox has no DND-capable emulator) before guessing at a
      fix — candidates include a stale `policyAccess()`/rule-state read after the Bedtime toggle,
      Bedtime's own rule taking exclusive ownership of the interruption filter instead of union-ing
      with Snoozemo's, or a One UI-specific composition quirk (`SPEC.md` §10). Not touched by the
      screen-split PR that logged this.
- [ ] **Sharing the debug log** (`SPEC.md` §4.6) — the user-facing half of the Phase 3 log,
      matching the sibling repos: a `Share debug logs` action through the system share sheet with a
      copy-to-clipboard fallback (no `INTERNET`, so the share sheet *is* the transport), and a
      post-crash banner offering to share the crashed run or dismiss it. Only a crash raises the
      banner — an ordinary process death, force-stop, or app update leaves the run shareable without
      nagging. Sizing matters: the payload crosses a Binder transaction twice, and an over-large one
      fails both silently, so bound it per section and in total. **Home is `SettingsScreen`**
      (maintainer, 2026-08-23, once the screen split below landed) — beside the debug-log toggle
      it already carries, not a new screen of its own.
- [x] **A `SettingsScreen` button to the system zen rule's own interruption-filter screen**
      (maintainer, 2026-08-23), labeled `Filters` — lets the user edit which calls, messages,
      alarms and apps break through Snoozemo's rule, which used to be reachable only by finding
      the rule in system DND settings by hand. Distinct from the DND-access row's
      `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` (that grants the *permission*; this opens the
      *rule's own* configuration). **Landed**, on the third try at the target, each wrong guess
      caught by a stronger check than the last: this entry's own draft named
      `ACTION_AUTOMATIC_ZEN_RULE_SETTINGS`/`EXTRA_AUTOMATIC_ZEN_RULE_ID` on `NotificationManager`,
      neither of which exists there; PR #88's first landed version instead used
      `NotificationManager.ACTION_AUTOMATIC_ZEN_RULE`/`EXTRA_AUTOMATIC_RULE_ID`, which do exist
      and passed a same-PR Robolectric test asserting exactly those values, but Codex found no
      receiver for that action anywhere in AOSP Settings; reading AOSP Settings' manifest and
      `ZenModeFragmentBase` source directly (not javadoc alone) turned up the real pair —
      `android.provider.Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` with
      `Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID` — a different class than either earlier guess, and
      the one actually declared as an exported intent-filter on `Settings$ModeSettingsActivity`.
      That intent-filter is gated behind the `android.app.modes_ui` feature flag ("Modes"), which
      AOSP ties to API 35 with no lower-API path to the same screen (Codex, PR #88, a fourth
      round). Rather than probing `PackageManager.resolveActivity` per device and hiding the row
      when it comes back empty, minSdk was raised to 35 (`SPEC.md` §11) — the row's own repair
      surface for a genuinely refused `startActivity` (`openSettings()`'s existing `runCatching`)
      was already there for actual platform refusals; a *guaranteed*-absent receiver on part of
      the install base called for dropping that part of the install base, not for a second code
      path to detect and work around it. `ZenController` now exposes the rule id it already holds
      via `ruleId()` (a memory read once warmed, like the arm path's own id read). The row is
      hidden, not disabled, whenever DND access isn't granted or no rule exists yet
      (`MainActivity.filtersRuleId`).
- [ ] **`refreshAccess()`'s own `zen.policyAccess()` read has no failure handling beyond "leave
      state as it was and hope the next refresh corrects it"** (Codex, PR #88) — the comment on its
      catch already says as much ("the screen keeps whatever it last knew, and the next refresh
      asks again"), and that's pre-existing, not something the Filters row introduced. Codex's
      specific ask — clear `zenRuleId` when this read throws — was left out of PR #88 as out of
      scope for a row that only *reads* the existing `access` field, not because the underlying
      concern is wrong: a genuinely stuck `access`/`zenRuleId` after a transient read failure,
      with no subsequent refresh ever succeeding, is a real (if narrow) gap. Worth a proper look at
      `refreshAccess()`'s failure handling in general — what it does for `access` itself, not a
      Filters-specific patch — if this comes up again or gets revisited for other reasons.
- [ ] `docs/PRIVACY.md` must describe what the log carries **before** the sharing surface ships —
      that ordering is the rule, not a preference (AGENTS.md, *Privacy*).

## Phase 6 (M6) — Internal-track release on Play

- [x] Release signing and a `deploy` job that builds a downloadable AAB. **Landed**:
      `signingConfigs["release"]` (`app/build.gradle.kts`) reads the upload keystore from
      `RELEASE_KEYSTORE_FILE` and its companion env vars, attaching only when they're present so
      a fresh clone still builds unsigned; the `deploy` job in
      `.github/workflows/android-ci.yml` builds `:app:bundlePlayRelease` on every push to `main`
      and publishes it as a downloadable `app-release-aab` workflow artifact, for the manual seed
      upload Play requires (`docs/play-store-internal-track.md`). R8 stays off — a separate
      follow-up once there is a device to verify a shrunk build against.
- [ ] **Automatic upload to the Play internal track.** Deliberately not this PR's scope
      (maintainer, 2026-08-22): a `r0adkll/upload-google-play` step plus the "What's new"
      generation from commit subjects described in `AGENTS.md`, and their own doc/secrets-table
      additions, were built and then pulled back out so that PR could stay focused on the
      manual-build path. Follow-up work: the release-notes generator (ported from the sibling
      Simmo/Type Launcher repos' deploy job, unchanged in shape), the Play service account and
      its Google Cloud/Console setup, and the `PLAY_SERVICE_ACCOUNT_JSON` secret. **Play Console
      setup itself (the account, the app listing, the declarations)** is the maintainer's own
      one-time work either way, tracked in `docs/play-store-internal-track.md` rather than here
      since it's account/console state, not code.
- [x] Make a release build **fail** when its version can't be derived from git, rather than
      warning (`app/build.gradle.kts`). The fallback exists so a checkout without git still
      builds; once a build can reach a tester or Play, falling back to versionCode 1 is
      either a rejected upload or a phantom downgrade, and a warning in a CI log is not
      where anyone would find it. Same for the shallow-clone case, which is worse because
      the count *looks* fine — the build already warns; a release build should refuse.
      *(Landed: `checkReleaseVersionDerivation` fails every `pre*ReleaseBuild` when the
      count, hash, or clone depth kept the version from being derived; debug builds, tests,
      and lint on a shallow checkout are untouched.)*
- [ ] Data Safety declaration: "no data collected, no data shared" (`SPEC.md` §12). The intended
      answer and its reasoning are recorded in `docs/play-store-internal-track.md`; filing it in
      the Play Console questionnaire is the maintainer's own step.
- [x] In-app prominent disclosure before the location permission prompt (`SPEC.md` §3.2/§12).
      **Landed**, rewritten 2026-08-22 to match the sibling ClothesCast repo's shape — which has
      already cleared Play review with it — after the original full-screen version drew several
      rounds of Codex findings the maintainer judged as more machinery than Play actually needs:
      the `Location` row (`LocationPermission`, mirroring `NotificationPermission`'s tri-state
      shape; `LocationPromptStore` tracks foreground and background denial history separately,
      mirroring `NotificationPromptStore`) launches the foreground request (fine + coarse)
      directly, the same as every other row — no disclosure precedes it. Only the *background*
      request gets one, since that is Play's restricted permission: a small `AlertDialog` shown
      after foreground is granted, stating what location is for, that tracking only runs while a
      snooze is armed, and that it never leaves the phone, with Continue launching
      `ACCESS_BACKGROUND_LOCATION` and Not now dismissing. No dedicated screen, no navigation
      state, no `BackHandler` — a dismissable dialog needs none of that. Covered by
      `LocationPermissionTest` (:core), `LocationPromptStoreTest`, and Roborazzi screenshot tests
      for the row's three states. **Still owed:** the demonstration video (below).
      - **Specifically flagged by Codex on the original version: whether
        `backgroundLocationPermission.launch()` can show the "Allow all the time" dialog at
        all**, versus needing to route the user to the app's location-permission Settings page
        instead. Softened by evidence, not settled: ClothesCast's own
        `LocationSettings.kt` calls the identical `launch(ACCESS_BACKGROUND_LOCATION)` and its
        code comment states this deep-links to the real system picker on its tested devices —
        but that's a different app's device matrix, not proof for Snoozemo's own minSdk/target,
        so still verify on a real Pixel before relying on it for the demonstration video. Not
        changed blind either way: routing to Settings preemptively would be worse UX on whichever
        OS versions the in-place dialog *does* work on, and a snooze still degrades gracefully
        either way (`SPEC.md` §3.6) — a UX gap in the settings row, not a principle 1/2 safety
        issue.
      - **Plan for filming the demonstration video** (maintainer asked for this 2026-08-22; not
        yet executed). Order matters — the first step below decides whether the later ones are
        even possible to film as written:
        1. **Resolve the background-dialog question first** (the sub-bullet above) — install a
           debug build on the real Pixel, reset Snoozemo's location permission and the app's
           storage (Settings → Apps → Snoozemo → clear permission + storage, so
           `LocationPromptStore`'s denial history is gone too), and tap the `Location` row twice
           (once for foreground, then Continue on the rationale dialog) to see whether
           `backgroundLocationPermission.launch()` actually shows "Allow all the time" on this
           device's OS version. If it does not, a Settings-fallback fix is needed **before**
           filming — the video has to show a real system prompt, not a request that silently
           no-ops.
        2. **Arm a snooze at the current location** (from the tile or `DebugScreen`) so the
           geofence registers around the phone's real position — the geofence/presence work
           landed in Phase 3 as of 2026-08-22, so this is now real, not a stub.
        3. **Enable mock locations**: Developer Options → "Select mock location app", plus either
           a small mock-GPS app from Play or a one-line `LocationManager.addTestProvider` /
           `setTestProviderLocation` script. This is what stands in for a real walk, so the phone
           never has to physically leave.
        4. **Record in one take** (`adb shell screenrecord` or the device's screen recorder),
           starting before tapping the `Location` row: the foreground system dialog → grant →
           the background rationale dialog → Continue → the background system dialog → grant
           "Allow all the time".
        5. **Still in the same recording**, use the mock-location app to move the reported
           position outside the anchor's geofence radius, and show the exit actually firing —
           the ongoing notification updating and Do Not Disturb turning off — so the video
           demonstrates the permission driving the feature, not just being requested and left
           unused.
        6. Trim and attach the recording to the Permissions Declaration Form alongside the
           written justification already drafted in `docs/play-store-internal-track.md`.
        - **Open question, not settled here:** whether Play's reviewers accept a video that
          shows Android's own "mock location app active" status-bar indicator during the
          walk-away portion, or expect an unmistakably real walk instead. Worth a quick check of
          Play's current guidance before relying on mock locations for the actual submission —
          flagging rather than guessing, since a rejected declaration costs real review-cycle
          time.
- [ ] **Copy candidates for `setup_location_granted`**, in place of "Tracking your place":
      "Unsnooze when you leave a location", or simply "Allowed" to match the DND and notification
      rows' granted state. Not applied yet — changing it would churn the screenshot snapshots for
      a cosmetic tweak; revisit next time this row's copy is touched for another reason, and
      settle between the two then.
      - **Also flagged by Codex: on `direct`, this string can render while
        `DurationOnlyPresenceMonitor` (`presence/src/direct/`) is the only monitor wired up** —
        `direct` supports only `DURATION_ONLY` until Phase 7's foreground-service monitor lands,
        so "Tracking your place" claims a capability the build cannot currently provide. Settle
        together with the copy candidates above: "Allowed" reads true regardless of what the
        permission is *for*, so switching to it would fix both the `direct` accuracy problem and
        the copy candidates in one change, without a `direct`-specific special case.
- [x] **Stop a transferred snooze from silencing a new phone** (Codex, PR #23) — a
      principle 1 bug and a prerequisite for shipping below. If an OEM transfers
      app-private data despite `allowBackup="false"`, an unexpired `active_snooze` lands on
      the new phone and `BootReceiver` restores whatever record it finds, re-asserting the
      zen rule (`SPEC.md` §8.3) — a new phone going quiet on its first boot for a snooze
      armed on a different device. **Both proposed fixes landed, because they fail in
      different places:**
      - `res/xml/data_extraction_rules.xml` declares a `<device-transfer>` exclude — the
        maintainer's suggestion, and the better mechanism, since the record never arrives.
        It names `active_snooze`, `pending_failure`, `notification_prompt`, `zen_rule` and
        `tile_presence`: each one *acts* on the new phone rather than merely sitting there
        (a stale failure notice, a suppressed permission prompt, a rule id that could name
        someone else's rule, a tile claimed but absent). **Excluded by name, never in
        bulk** — saved places and per-place policies stay transferable, so *Settle the
        backup story* below is exactly as open as it was.
      - The record also carries a **device stamp** (`RecordOrigin`, `DeviceStamp`), and
        `ActiveSnoozeStore.load()` refuses one written elsewhere. This is the backstop: the
        platform note that makes the bug possible says OEM behavior "varies", so a tool
        that ignores `allowBackup` may equally ignore `dataExtractionRules`, and nothing
        can enumerate which. The stamp is a **salted SHA-256 of `ANDROID_ID`**, never the
        raw value — equality is the only question asked of it. **It folds in this install's
        `firstInstallTime` as well as `ANDROID_ID`** (Codex, PR #26): the two fail
        independently, so a phone where `ANDROID_ID` reads null still has an identity, and
        the "no identity at all" state that would have restored a transferred record
        unchecked stops being reachable in practice rather than being argued about. It is
        stamped by the
        store on every write rather than by callers at arm, so no construction site can
        write a record the device cannot later vouch for.
      - **A device that cannot stamp restores anyway, and that asymmetry is the design.**
        Refusing there would end every snooze on such a handset at its next process death:
        failing closed, not safe. `RecordOrigin.UNVERIFIABLE` is about the device's
        capability; `UNATTRIBUTED` (this device stamps, the record doesn't) is about the
        record, and refuses. Found by running the tests — the first version refused both
        and broke 8 existing tests, which was the design telling the truth.
      - Covered by `RecordOriginTest` (JVM, 7) and `ActiveSnoozeStoreOriginTest`
        (Robolectric, 6), including the bug itself: arm as one phone, read as another.
      - **Still owed a device:** whether a real OEM transfer honors `<device-transfer>` at
        all. The stamp is what makes that question non-urgent rather than answered.
      - **The shape worth remembering, because three of PR #26's findings were the
        same fact:** hiding a record from `ActiveSnoozeStore.load()` silently removes it
        from *every* mechanism that resolves a record that way — the cap check, the
        release ladder's successors, and `TimeChangedReceiver`'s clock-change handling.
        A record that is refused is therefore a record with no cap, no retry and no
        clock safety unless something is built for it explicitly. Anything future that
        adds a reason to refuse a record inherits this and should say how it is covered.
      - **The refused-release branch is now tested** (Codex, PR #26). If
        `setSnoozed(false)` is refused, the record and cap are kept and a *dedicated*
        `ACTION_DISCARD_RETRY` carries the obligation — the ordinary release ladder
        cannot, because every one of its successors resolves the record through `load()`,
        which is exactly what refuses this record. `discardForeignRecord` takes an
        injectable `ZenController` (defaulted, so no caller had to change) and
        `RefusingZen` drives both sides of the branch. **The other receiver paths now
        take the same seam too** — `releaseDirectly` and `restoreDirectly`, via a shared
        `androidZen(context)` factory, covered by `ReceiverRefusalTest` (7). Converting
        them turned up a real defect rather than only enabling tests: `restoreDirectly`'s
        expired-record and nothing-left-to-release branches each built a *second*
        controller instead of forwarding their own.
        Superseded note kept for the shape of the problem: it was covered by
        inspection
        only. `discardForeignRecord` builds its `AndroidZenController` inline, exactly as
        `releaseDirectly` beside it does, so `RefusingZen` cannot be injected the way
        `TestSnoozeService` injects it. The escalation it delegates to
        (`escalateWithoutService`) *is* covered. Fixing this properly means giving the
        receiver paths in `CapAlarm.kt` an injectable controller the way the service has
        one — worth doing, and larger than this PR.
- [ ] Ship to the internal track — the point at which the declaration outcome becomes
      known.

## Phase 7 (M7) — The `direct` flavor

Insurance, not a parallel product (`SPEC.md` §3.4). It sits after the internal-track
release; bring it forward only if the declaration is refused, at which point it becomes the
whole project.

- [ ] `ForegroundPresenceMonitor` + `SnoozeService` behind the same interface, with the
      `location` foreground-service type.
- [ ] The §6.7 duty cycle: no location work while on the anchor SSID; significant-motion
      trigger plus a 10-minute sanity fix while off it; 90 s balanced-power fixes only while
      resolving.
- [ ] `LocationManager` fallback (`PROVIDER_FUSED`, or `NETWORK_PROVIDER` below API 31) for
      devices without Play Services.
- [ ] No restricted permissions, no Play Services dependency — verify by inspecting the
      merged manifest of the `direct` variant in CI.

## Phase 8 (M8) — Samsung One UI

Not descoped, just not allowed to gate the Pixel release. Keep the OEM-specific seam small
from the start so this is additive rather than surgery.

- [ ] Sleeping Apps / Background usage limits: a Samsung-detected onboarding step pointing
      at `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. Do **not** declare
      `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and don't deep-link into
      `com.samsung.android.lool` internals.
- [ ] Verify the zen rule is created, actually silences a One UI 8 device, and is
      discoverable and disableable in Samsung's Settings.
- [ ] Verify `Tile.setSubtitle` renders; ship the label fallback that folds the countdown
      into the label if it doesn't.
- [ ] Re-measure geofence delivery under Sleeping Apps (hardware item 2 on Samsung).

## Hardware verification

`SPEC.md` is written from documentation and mechanism, not measurement. These are the items
that can only be settled on a real device, ordered by risk.

### Blocking — these can change the project

1. [ ] **The background-location declaration** (`SPEC.md` §3.5). The largest open risk and
       the only one that cannot be resolved by writing code. Submit during Phase 3.
       Everything else here is a tuning question; this one is binary.
2. [ ] **Geofence exit latency and hit rate, measured** (`SPEC.md` §6.10). Log every
       geofence callback against a ground-truth departure time over at least a week of
       ordinary use, Wi-Fi on and off, including a stationary-overnight case. Pixel first;
       repeat on Samsung at Phase 8. If the three-source layering closes the gap, the
       fallback end conditions stay off the roadmap.
3. [ ] `setAutomaticZenRuleState` genuinely silences the device. A formality on Pixel — do
       it at Phase 1 anyway, since every other line of the app assumes it.
4. [ ] **`startService` from the cap alarm and the boot receiver actually starts it.** Both
       run while the app should be on the temporary allowlist that background-start
       restrictions exempt — the alarm because `setAndAllowWhileIdle` grants a window, the
       boot receiver because `BOOT_COMPLETED` does — but "should be" is documentation, and
       the cap alarm failing to reach the service is a snooze that never ends. The refusal
       is logged rather than swallowed today; if a device refuses it, the release path has
       to move into the receiver itself (`goAsync`) or onto a foreground service. Reboot
       during a snooze and let a short cap expire in Doze on both Pixel and One UI.

5. [ ] **App Standby Buckets, and whether the cap survives `restricted`** (`SPEC.md` §8.4).
       Stock Android, no OEM needed: an app in the `restricted` bucket has
       `setAndAllowWhileIdle` and jobs throttled to roughly once a day, which would delay the
       duration cap — principle 1's backstop — by up to that. Expected to be low-risk in
       practice, because arming is a tile tap and app interaction promotes toward the active
       bucket, so the exposure is long snoozes on a phone where Snoozemo is otherwise unused.
       Force the state rather than waiting for it: `adb shell am set-standby-bucket
       app.snoozemo restricted`, arm a short cap, and see when it fires. Do the same for
       force-stop, where the expected answer is that there is *no* in-app recovery and the
       user's route back is the system DND toggle.

6. [ ] **What the shade's DND toggle does to an app-owned rule.** Blocks the Phase 5 item
       above: with a Snoozemo snooze running, turn Do Not Disturb off from Quick Settings and
       read our `AutomaticZenRule` back. Does it deactivate, stay active-but-overridden, or
       something else? The answer decides whether there is a bug to fix or only a comment to
       write. Repeat on One UI at Phase 8 — this is the sort of thing Samsung changes.

### Tuning — these change details, not direction

7. [ ] Real battery draw over a 4-hour stationary snooze versus the `SPEC.md` §9 estimates,
       per flavor.
8. [ ] Whether the §4.4 sheet is right at all: is now + 1 h a sane default, are 30-minute
       steps the right granularity, and does anyone reach for the time row often enough to
       justify the calendar in v1.1?
9. [ ] Does the trampoline activity produce any visible flash (`SPEC.md` §6.9)?
10. [ ] **Check the raised ongoing notification on a device** (`SPEC.md` §4.3). It moved from
        `IMPORTANCE_LOW` to `IMPORTANCE_DEFAULT` (maintainer, 2026-08-12) — low grouped it,
        which made it less discoverable and put an extra tap in front of `End now` and
        `+30 min`. Confirm the intended effect: both actions reachable without expanding a
        group, the countdown legible at a glance, and no genuine intrusion over several hours
        (the premise is that the user isn't looking at the phone during a snooze, and it is
        in Do Not Disturb anyway). Also check the status-bar icon reads at that size while
        snoozing. **Test on a fresh install or after clearing app data** — channel importance
        is fixed at creation, so a device that ran an earlier build keeps the old level.
11. [ ] **Does the `SettingsScreen` Filters row's `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS`
        deep link actually resolve on a real device** (`TODO.md`'s own Phase 5 entry, PR #88)?
        Confirmed against AOSP Settings' manifest and source rather than guessed, and minSdk was
        raised to 35 so every supported device carries the Modes UI feature flag that action's
        intent-filter is gated behind — but AOSP source is a floor, not a guarantee against an
        OEM fork shipping without it. Not a crash either way if one does
        (`openSettings()`'s `runCatching` around `startActivity`), but confirm the row lands on
        the right screen on a real Pixel first, then Samsung at Phase 8.

### Samsung, at Phase 8

10. [ ] Does `setAutomaticZenRuleState` silence a One UI 8 device, and is the rule visible
       and disableable in Samsung's own Settings?
9. [ ] Does `Tile.setSubtitle` render on One UI?
10. [ ] Does Sleeping Apps interfere with geofence delivery, or with a `location`-typed
        foreground service in the `direct` flavor?

## Dependency updates

- [x] **Adopt `mikelward/gradle-update`** — the weekly Gradle catalog updater.
      Wired via the caller workflow in `.github/workflows/gradle-update.yml`.

## Deferred

Nothing here is scheduled; each is a sequel that follows from something already built
(`SPEC.md` §14).

- [ ] **Make CI safe to open to external PRs and forks — plan in
      `docs/fork-safe-ci.md`.** (maintainer, 2026-08-23) Adopts
      `mikelward/ci-commit-artifact` for the screenshot-refresh commit (today's
      `screenshot-tests` job runs the PR's own Gradle/Roborazzi code and commits/pushes in
      that same job — the structural risk `ci-commit-artifact`'s README exists to close)
      and migrates `android-ci.yml` to `pull_request_target` like `typelauncher`, so a PR
      can no longer rewrite the workflow definition to forge a green required check. Six
      ordered milestones, each its own PR; the doc has the full architecture, the
      `CI_COMMIT_ARTIFACT_TOKEN` PAT reuse from `typelauncher`, why the trigger switch also
      requires a new GitHub App for the `lanes` status (an Actions check-run can never
      satisfy a PR's required check under `pull_request_target` — it attributes to the
      base branch's tip, not the PR head), and the zizmor policy exceptions needed.

- [ ] **Screenshot job: surface a missing `--tests` class clearly instead of Gradle's raw
      output.** (maintainer, 2026-08-23) When a screenshot job step's `--tests` filter
      matches no class — branch behind `main`, a renamed test, a step added without its
      class — Gradle's actual "No tests found for given includes: [...]" line is buried
      after a page of unrelated `NO-SOURCE`/compile noise from other modules
      (`android-ci.yml`'s screenshot-tests job, one step per class). A quick look at the
      log shows only the `NO-SOURCE` lines and no clear signal that the whole job is about
      to fail. Worth a step that checks the named class exists before invoking Gradle, or
      an explicit failure message that names the missing class up front. Not built; ran into
      this while chasing a stale-merge-ref failure on PR #83 that turned out to be a
      behind-`main` branch, not a real bug.

- [ ] **Screenshot-diff PR comment: prioritize which screenshots survive truncation.**
      (maintainer, 2026-08-23) Suggested dropping dark-mode variants before light ones if
      the comment ever needs to drop entries, on the theory that a dark capture is usually
      the same state as its light counterpart and carries less unique signal. Not built —
      the truncation cap was instead switched to a character budget against GitHub's
      comment-size limit (`.github/workflows/android-ci.yml`, "Post screenshot diffs as a
      PR comment" step), so in practice this never triggers for the current screenshot
      count. Worth revisiting if the comment ever does truncate in the field.

- [ ] **Not v1 — let a snooze choose its zen policy, or which of several rules to use.**
      Ruled out for v1 by the ownership constraint below (maintainer, 2026-08-12).
      Today Snoozemo owns exactly one `AutomaticZenRule` with one `ZenPolicy` (`SPEC.md`
      §5.5: priority filter, alarms and repeat callers through). §5.5 already contemplates
      "total silence is available in settings"; this is the general version — a small set
      of named policies (say `Normal`, `Total silence`, `Calls only`) that the tile's sheet
      or a per-place setting can pick between.
      - **The constraint to check first**: an app can only drive rules it *owns* (§5.6).
        So this cannot mean "activate the user's existing Sleeping or Driving mode" — it
        means Snoozemo creating and owning several rules, or rewriting its one rule's
        policy at arm time. Those are different designs with different costs: several rules
        clutter the user's Modes list but are switchable instantly; rewriting one rule is
        tidier but is a `setAutomaticZenRule` call on the arm path, which §4.1 does not
        have budget for — it would have to happen at settings-change time, not at arm time.
      - Interacts with **saved places** below, which already wants a per-place policy, and
        with `ZenDeviceEffects` on API 35+ (§5.5's "make the phone boring too" idea).
      - Worth a device check before designing: how do several app-owned rules present in
        the Modes UI, and does the user experience them as clutter?

- [ ] **Calendar-seeded end times** — the first thing to add once the Play declarations
      land. `READ_CALENDAR`, requested in-context from the sheet, feature hides itself if
      denied. Kept out of v1 so a second sensitive permission doesn't ride along with the
      one that can sink the project.
- [ ] **Saved places** — name an anchor, give it its own policy and duration cap; the tile
      long-press becomes a picker. The `Anchor` type is already shaped for it.
- [ ] **Settle the backup story** (maintainer, 2026-08-11) — before the first release with
      settings worth keeping, and *not* by leaving `allowBackup="false"` unexamined. Today
      the app stores a transient anchor and nothing else, so no-backup costs nothing; saved
      places, per-place policies, and caps change that, and losing them on a phone swap is
      its own failure. Don't build anything that assumes never. The options, cheapest
      first:
      - `dataExtractionRules` (API 31+, and minSdk is 34) can allow **device-to-device
        transfer while disabling cloud backup** — settings survive a new phone without a
        place list reaching Google's servers. Probably the answer.
      - Full auto-backup, which does put it in the cloud (encrypted with the device PIN on
        modern Android) — a real privacy question, and one that touches the Data Safety
        answers, so it is the maintainer's call and not autopilot's.
      - A user-initiated export/import file: no ambient copies, but nobody does it before
        losing the phone.
      - **The starting point is not where this item assumed it was** (Codex, PR #23).
        `allowBackup="false"` on its own does not mean "nothing migrates": Android
        documents that for apps targeting API 31+ it disables cloud backup but, on some
        manufacturers' devices, **does not disable device-to-device transfer**. With no
        `dataExtractionRules` declared, a phone swap did whatever the OEM does. So
        this is not "no backup, decide later" — it is *undecided*, and the direction that
        needs a positive action has flipped: allowing D2D costs a rule that says so,
        refusing it costs a `<device-transfer>` exclude, and doing nothing picks neither.
      - **A `dataExtractionRules` file now exists, and it does not settle this.** The
        transferred-snooze fix (Phase 6) added one, but its `<device-transfer>` section
        names only runtime state that misbehaves on a new phone. Saved places and
        per-place policies are not in it and stay transferable, which is the status quo
        rather than a decision — so this item is unchanged except that the mechanism is
        now wired up and the answer is one `<exclude>` or its absence.
        Still the maintainer's call. It needed saying in both places, so `AGENTS.md`
        principle 3's "loses settings by design" is corrected too: the rule it illustrates
        is untouched, only the platform fact under it. A false premise left in the file
        every agent loads is how a later change quietly assumes D2D is already off.
        **The maintainer approved both `AGENTS.md` edits** (2026-08-13) — this one, and the
        `developer.android.com` note under *Remote build environments* recording that the
        reference pages are reachable and a fetch tool saying otherwise is the tool.
- [ ] **Auto-arm on arrival** — the obvious sequel, and nearly free in the `play` flavor
      where background location is already paid for.
- [ ] **"Until I get home"** and other saved-place reverse geofences — needs saved places
      plus background location, so `play`-flavor only.
- [ ] **`ZenDeviceEffects`** — grayscale, dim wallpaper, night mode while snoozed
      (`SPEC.md` §5.5).
- [ ] **Explicit fallback end conditions** (`until Wi-Fi goes`, `until I move`) — only if
      hardware item 2 shows the three-source layering isn't enough. Preference order is:
      fix it invisibly, then have the app pick the fallback itself and say so, and only
      then expose them as standing user choices (`SPEC.md` §6.10).
- [ ] **Chaining back-to-back meetings**, if using the app shows people actually want it.
- [ ] **"Until I leave this room"** (maintainer, 2026-08-13) — the use that justifies keeping
      `Anchor.bssid`. A room is smaller than an SSID: in an office the whole floor is one
      network, so SSID loss cannot notice you leaving the meeting room you are sitting in,
      which is exactly the case a "quiet until I leave here" snooze is for. Design
      constraints in `SPEC.md` §6.2 — the short version:
      - Reading the *connected* AP is cheap (`WifiInfo.getBSSID()`, no scan, same
        permission as the SSID). "Is the room AP still in range" is not: that needs scan
        results, `startScan` is throttled to 4 per 2 min, and cached `getScanResults` is
        free but only as fresh as whatever else on the phone happened to scan.
      - **A BSSID change does not mean you moved.** Phones roam between APs while
        stationary, and band steering moves a phone between the 2.4 and 5 GHz radios of the
        *same* AP, which present different BSSIDs.
      - So it is a **trigger for a check, never a verdict** — D4's asymmetry one level
        down — with a dwell before it counts (maintainer: *"out of range for 5 minutes or
        something"*).
      - **The check cannot be §6.6's, and the exit criterion is undesigned** (Codex,
        PR #24). §6.6 tests against a 150 m radius on a balanced-power request; a room is
        ten meters, so it can never fire. **Do not conclude from that that location is
        useless here** — an earlier draft did, reasoning from `MAX_ANCHOR_ACCURACY_M` (a
        rejection ceiling, not the accuracy of a fix). A tighter gate with a
        higher-accuracy request is a different test and has not been evaluated.
      - **Measure before designing.** Whether a location test can corroborate a room exit
        turns on how accurate an indoor fix really is, and what a higher-accuracy duty
        cycle costs against §9's budget — plausibly affordable, since a room snooze is a
        meeting rather than eight hours, but that is a guess. If it can corroborate, D4
        stands and the design gets much easier; only if it cannot does the Wi-Fi-only
        verdict (and the D4 exception it needs) come into play.
      - What makes a Wi-Fi-only verdict acceptable is the **failure direction**: it errs
        toward ending early, which rings a phone a minute before the meeting ends, rather
        than toward staying quiet, which is principle 1's failure.
      - Needs a device: whether roaming in a real office is rare enough for this to be
        usable at all is a measurement, not an argument. **The same walk should record fix
        accuracy**, since one trace answers both questions — how often the phone roams
        while stationary, and whether an indoor fix is ever tight enough to corroborate.
      - `docs/PRIVACY.md` currently says nothing acts on the BSSID. That stops being true
        with this feature, so its PR updates the policy in the same change.
- [ ] **Wear OS tile.**

## Open questions from the maintainer (2026-08-13)

Raised while the presence engine was being built, and kept open deliberately: each one changes
what the product *is*, so none is autopilot's to settle. Recorded here rather than answered.

- **Should a snooze ever end without a confirmed departure?** Asked as: *"I'm worried about the
  case that I'm at a movie and Snoozemo unsnoozes prematurely."* That case is real and it is
  reachable by exactly one path — the §6.6 grace period, which ends a snooze when the anchor's
  Wi-Fi is gone *and* location cannot place anyone. A cinema produces both. The confirmed
  departure test is close to safe there (indoor readings are vague, and vague ends nothing); the
  remaining hole is a confidently wrong fix past the unambiguous margin.
  - **The proposed fix, and it is a good one: gate the fail-open endings on significant motion.**
    `TYPE_SIGNIFICANT_MOTION` needs no permission, costs approximately nothing, and a phone in a
    pocket in a cinema seat never fires it. Confirmed departures and the duration cap would stay
    unconditional; only the grace period would additionally require "has moved since the last
    confirmed presence". This is the maintainer's *"only after the end of my meeting AND if I
    moved"*, in the form the sensors can actually support.
  - **The cost**: a phone that leaves in a bag or a car boot without triggering the trigger sensor
    would hold its snooze to the cap. Whether that is rare is a measurement, not an argument.
  - **The strongest argument on the other side** (Codex, PR #31), worth having in front of whoever
    decides: a geofence exit that arrives while the anchor's Wi-Fi is still associated, followed by
    location going vague, currently arms no grace period at all — so a phone that really did leave
    while still *claiming* to be on the network holds its snooze to the cap. The engine declines to
    end there because association is D4's strong presence evidence and the geofence is the signal
    §6.10 documents as unreliable; but the same reasoning that keeps a cinema quiet is what leaves
    that case silent. The two questions are one question, and they should be answered together.
  - Nothing ships either way until the monitors land, so the engine's current behavior is not yet
    reachable by a user.
- **Should ending be a prompt rather than an action, at least sometimes?** Asked as: *"prompt me to
  unsnooze when I unlock as an option, rather than only always unsnoozing automatically."* The
  natural shape is not a global switch but a split: a *confirmed* departure ends the snooze
  silently, while an *ambiguous* one waits and asks at the next unlock. It does not fix the cinema
  case on its own — you do not unlock in a cinema, which is the good half — but if it replaced
  automatic ending outright, a phone that has genuinely left stays silent until the user happens to
  unlock, which is principle 1's failure with a friendlier name.
- **How much of the app is presence, and how much is just "DND is on and nothing says so"?** Asked
  as: *"I wonder how much of the app is unneeded if we detect when the system is in DND and just
  show a persistent notification saying 'tap to turn off Do Not Disturb'."*
  - The observation behind it, from the maintainer's Pixel: **bedtime mode posts a notification and
    plain DND does not.** That asymmetry is because bedtime mode is Digital Wellbeing — an ordinary
    app, doing several things at once — while DND is a platform state surfaced the way airplane
    mode is, with a status-bar glyph and a lit tile. So there is no notification to tap for DND the
    user turned on themselves, from any source.
  - **What the reduction would delete**: all of Phase 3, the location permissions, the geofence, the
    battery budget of §9, and — the big one — the background-location declaration of §3.5, which is
    the project's largest single risk. What it keeps: the tile, the zen rule, the cap, the
    notification.
  - **What it gives up**: the promise. A notification still has to be noticed and tapped; the
    product exists because people forget. Worth noting the two are not exclusive — noticing DND
    that *something else* turned on (a schedule, bedtime, a manual toggle) is a distinct, much
    cheaper feature that Snoozemo does not have today and could ship regardless of what happens to
    presence.
  - **The cheap version cannot be built as described** (verified against the platform behavior-changes
    doc, 2026-08-14). An app targeting Android 15+ "can no longer change the global state or policy of
    Do Not Disturb (either by modifying user settings, or turning off DND mode)"; `setInterruptionFilter`
    is redirected into an *implicit* `AutomaticZenRule` owned by the caller, and the doc is explicit
    that the change bites exactly when an app "is calling `setInterruptionFilter(INTERRUPTION_FILTER_ALL)`
    and expects that call to deactivate an `AutomaticZenRule` that was previously activated by their
    owners." So a notification reading `tap to turn off Do Not Disturb` would turn off nothing the user
    turned on: DND from the Quick Settings toggle, a schedule, or bedtime mode all belong to someone
    else. **Detecting** it is still fine — `getCurrentInterruptionFilter()` under the policy access the
    app already holds — so the honest version of the idea is a notification that says DND is on and
    deep-links into Settings. That is two taps and a settings screen instead of one tap, which is most
    of what made the reduction attractive.
  - The same constraint is why the Pixel asymmetry exists: bedtime mode's notification turns off *its
    own* rule, which is the one thing any app can do. It is also `SPEC.md` D1 and §5.6 restated —
    Snoozemo ends only the rule it created, and that is the mechanism the platform sanctions, so
    nothing in the current design is threatened by this.
  - **Half of this is now answered** (maintainer, 2026-08-14, on reading the platform finding
    above): *"so it just means we need this after all, the notification only approach won't work,
    we also need to start and own the DND."* The detect-and-offer app was never a smaller Snoozemo
    — it was a different app that cannot do the one thing it promises. Owning the rule is what
    makes ending a snooze possible at all, so `SPEC.md` D1 and §5 stand as the product's floor
    rather than an implementation choice.
  - **What stays open is presence, and only presence**: *"it's an open question whether we need to
    geofence, maybe showing a persistent notification is enough. tbd"* Note the reduced version is
    stronger than it looked an hour ago, and for the same reason: because Snoozemo owns the rule, a
    persistent notification with an end action is a **reliable one-tap exit**, not a deep link into
    Settings. So the comparison is no longer "notification versus presence" — it is whether
    *leaving* should end a snooze on top of an exit that already works, against Phase 3's whole
    cost: the location permissions, the geofence, §9's battery budget, and §3.5's
    background-location declaration.
  - **Owed**: that written comparison against the current spec — what presence adds, what dropping
    it deletes, and what each costs — before anything is decided. Nothing in Phase 3 is being built
    against this question in the meantime; the engine work already landed stands either way, since
    a snooze that ends on a duration cap needs the same controller.

## Decisions needing review
- **Consider an in-app banner (or similar Settings-surfaced cue) for a
  changed hosted privacy policy.** `docs/PRIVACY.md` now rides the docs lane
  and is never forced into release notes (2026-08-22 — see AGENTS.md
  "Commit messages" and `.github/lanes.conf`), on the reasoning that Play's
  "What's new" is a store-listing field an installed user never sees at
  update time — so a wording change there bought no real visibility anyway.
  That leaves installed users with no signal at all when the policy changes
  under them; a banner or Settings cue that detects "the hosted policy
  changed since you last saw it" would close that gap. Checked commit
  frequency across this app and its siblings: bursty during a feature's
  initial buildout, then roughly monthly or less once the app stabilizes —
  so a change-detection banner isn't worth building speculatively now.
  Worth a second look if this app (or a sibling) reaches a stage where
  policy changes carry real stakes.
- **The grace alarm is inexact** (`setAndAllowWhileIdle`, autopilot 2026-08-22): the exact
  form costs the `SCHEDULE_EXACT_ALARM` permission — a distribution question (`SPEC.md` §3)
  — and the cap already accepts the same inexactness. A deferred grace holds the snooze a
  little longer, in Doze conditions that mean the device is stationary. **The alternative**
  is asking the maintainer to take the permission. Reversible: one call site.
- **A Wi-Fi watch that fails to register reports itself as a loss** (autopilot,
  2026-08-22): the registration failing is extraordinary (`TooManyRequestsException`
  class), and the alternative — a `WIFI_ONLY` snooze looking watched while nothing
  watches — is the snooze that never ends. The cost is a snooze that ends ~5 min after a
  registration hiccup on an SSID-only anchor, the accepted direction (D7). Reversible: the
  failure branch could report a degradation instead once a cause exists for it.
- **A redacted SSID reads as not associated** (autopilot, 2026-08-22): redaction means
  location access is gone, so nothing can vouch for the association, and an unvouched
  suppressor holding a snooze quiet is what D7 forbids. Escalation settles it like any
  other. **The alternative** — treating redaction as "still associated" — keeps the quiet
  through a revocation, principle 1's direction. Reversible: one line in the tracker.
- **The backstop's period is 30 minutes, the coarse end of §6.10's 15–30 range** (autopilot,
  2026-08-22). 16 deferrable wakes over an 8-hour snooze matches §9's "handful of wakeups"
  budget line, at the cost of a ~30-minute typical staleness bound (best-effort — the period defers in
  Doze and under battery saver, `SPEC.md` §6.10) where 15 would halve it for double the wakes. **The alternative** was 15 minutes (`WorkManager`'s own floor),
  which hardware item 2's latency measurements may yet argue for. Reversible: one constant
  (`SnoozeBackstop.PERIOD_MINUTES`).
- **The backstop schedules in both flavors, not only `play`** (autopilot, 2026-08-22). The
  `direct` flavor has no fence for the probe (its poke is a no-op), but the wake's restore
  still re-arms the cap and reconciles policy access — the ride-along §9's table always
  wanted. **The alternative** was a play-only schedule, saving `direct` 16 deferrable wakes
  per 8 h at the cost of a flavor split in the service. Reversible: move the schedule call
  behind the flavor seam.
- **Arm-time capture uses the platform `LocationManager` (fused provider) in both flavors**
  (autopilot, 2026-08-22), not `FusedLocationProviderClient`. On every GMS device this app
  targets the platform fused provider is backed by the same engine, a one-shot capture is not
  where fix quality is won, and one shared class beats a second flavor seam. **The alternative**
  was a play-flavored capture through Play Services Location, which could matter if handset
  testing shows the platform provider serving stale or slow fixes at arm time. Reversible: one
  class behind one seam (`SnoozeService.beginAnchorCapture`).
- **A captured anchor arms with an explicit `DURATION_ONLY` until something watches it**
  (autopilot, 2026-08-22). `TrackingMode.from(anchor)` would claim Wi-Fi or full tracking that
  no running machinery performs — the notification would say `ends when you leave` over a snooze
  only the cap will end, which is principle 2's failure. **The alternative** was to wire the
  monitor in the same change; it was split out because the monitor's consumption (the
  duty-driven location loop) is its own review-sized slice. Reversible: one argument at one call
  site, deleted by the monitor-wiring slice.
- ~~**`ACCESS_NETWORK_STATE` is now declared**~~ — **approved by the maintainer** (2026-08-22,
  "yes to access network state"), asked per the *Play policy questions* rule with the policy
  context: a normal install-time permission, not on Play's sensitive list, no Data Safety
  impact. Declared by `:presence`'s manifest because registering the network callback the SSID
  read goes through requires it — lint caught a real `SecurityException` waiting on the first
  sideload — and pinned by `DeclaredPermissionsTest`.
- **Anchor capture seeds from the last known fix, gated to 60 s of age** (autopilot,
  2026-08-22). A tile-tap arm drops to the background within a frame of the trampoline
  finishing, and a while-in-use grant then delivers fresh fixes throttled or not at all (Codex,
  PR #71) — the synchronous cached read inside the tap's start is the one answer that cannot
  lose that race. Sixty seconds at walking pace is under 100 m, inside the anchor's 150 m
  radius, and a stale seed errs open (an anchor the user is not at reads as a departure, not a
  snooze that won't end). **The alternative** was a foreground component holding location
  access through capture, which in the `play` flavor is the §3 minefield and in `direct` is
  Phase 7's monitor anyway. Reversible: one constant, one read. What a device would settle:
  how often the seed is present and fresh in practice, and whether the live request ever
  beats the ceiling from the background.
- **The no-Wi-Fi arm waits out the full 10 s ceiling** (autopilot, 2026-08-22). Nothing
  user-visible waits on it — `Snoozing` is posted the moment the rule is on — so the cost is a
  late ARMED record, and the alternative (a shorter Wi-Fi sub-deadline) was one more constant
  and one more race to review. Reversible: one event on the pure machine.
- ~~**Report tracking *health* on every step instead of recovery *events***~~ — **done** (PR #34).
  Nine Codex rounds on #33, four of them in code written during that PR, all shared one shape: a
  recovery was a one-shot announcement, so every ordering question became "did the announcement
  survive?" Health is now a level on `PresenceUpdate`, restated on every update the way
  `LocationDuty` always was, and the controller acts on the difference between what it is told and
  what it believes. Deleted with it: `PresenceEvent.Degraded`, `TrackingRecovered`,
  `PresenceEvidence`, `PresenceState.pendingRecovery` and its deliver/preserve/invalidate rules,
  `SnoozeController.restoreTracking`, and the paired `onDegraded`/`onTrackingRestored` callbacks
  (now one `onTrackingChanged`). "Reported once, not once per bad fix" stopped being an engine rule
  and became a property of the comparison.
- **A snooze that *becomes* unverifiable now ends on the same 5-minute grace period as one that
  armed that way** (autopilot, 2026-08-13). `SPEC.md` §6.6 wrote the grace period for the anchor
  that never had a fix; the engine also starts it when a healthy snooze loses the anchor's Wi-Fi
  *and* location stops being able to place anyone. The two states are identical in the only respect
  that matters — nothing left can confirm a departure — and without this the ordinary route out of
  a building ends in silence until the duration cap. **The alternative was to leave it to the cap**,
  which is defensible on the grounds that ending on a timer discards evidence that might still
  arrive; against that, principle 1 says a snooze that ends early is a small annoyance and one that
  never ends is the product failing. Reversible in one place: the grace period is armed from a
  single branch and the constant is one value. What a device would settle: how often a real walk
  produces three unusable fixes in a row while the user has not gone anywhere.
- **A geofence exit escalates even while the phone is still associated with the anchor's SSID**
  (autopilot, 2026-08-13). D4 makes Wi-Fi association strong evidence of presence and the spec's
  table had no row for the two subsystems disagreeing. Escalating costs one location request, which
  then settles it either way; deferring to Wi-Fi would mean a real departure is missed whenever the
  phone holds a stale association or the SSID exists at both ends of a walk. Recorded in §6.3.
  Reversible: one branch, and the test that pins it names the trade. Note this is the one case
  where checking outranks the Wi-Fi suppressor in the duty cycle — otherwise the escalation would
  change the phase and ask for nothing, which is how it first shipped (Codex, PR #31).
- **Three unusable readings in a row is the threshold for calling tracking degraded** (autopilot,
  2026-08-13). One vague fix is ordinary; three at the 90-second checking rate is about four and a
  half minutes of location saying nothing. Pure tuning, and the field measurement that would settle
  it is the same recorded walk the departure test is waiting on.
- **The screenshot refresh commit now re-triggers CI via `workflow_dispatch`**
  (Codex, PR #15; resolved in PR #43, forced by the `gate` check becoming required — a
  refreshed head with no checks would sit blocked forever, not just under-verified). A
  push made with `GITHUB_TOKEN` deliberately starts no workflow run, but a *dispatch*
  made with the same token is GitHub's documented exception, so the refresh step now
  dispatches `android-ci.yml` onto the branch it just pushed and fails loudly if the
  dispatch is refused. The dispatched run is a non-PR event, so `classify` sends it down
  the code lane and every heavy job runs against the refreshed head; its check runs land
  on that head SHA and satisfy the ruleset like any other. This fix was not among the
  three priced earlier because it costs none of what they cost: no secret to rotate, the
  auto-commit convention stays, and no work moves onto the PR author.
  - The trigger only works once it exists on `main` (GitHub dispatches workflows it can
    see on the default branch), so the first refresh after PR #43 merges is the first
    covered one; a refresh before then fails the job loudly rather than stranding the PR
    silently.
  - Related and now fixed: a *failed* refresh push used to warn and exit 0, which could
    leave the job green over drifted snapshots (most plausibly on a Dependabot PR, whose
    token is read-only). That path now fails the job.
  - **Same root, second symptom** (Codex, PR #15): because the job must check out the head
    *branch* to have somewhere to push, it records against the branch alone while the build
    job checks GitHub's merge ref — so a visual regression that only appears when the base
    and the PR are combined is not caught before merge. It is caught *after*: the `main`
    run records against merged code with the drift check enabled, so it goes red rather
    than landing silently. If the snapshot suite grows enough that catching it post-merge
    is expensive, the fix that doesn't fight the refresh is a verify-only pass on the merge
    ref in the `build` job (`-Proborazzi.test.verify=true`, no checkout override, no push).
  - **Third symptom, same root** (Codex, PR #15): the screenshot job runs PR-controlled
    Gradle code and then does token-bearing git work in the same job. Every git call there
    now runs with hooks, `core.fsmonitor` and credential helpers disabled, and the token is
    interpolated into the push URL rather than exported to the step — but `PATH` is still
    the build's to poison, so a planted `git` earlier on `PATH` is not covered. Only the
    split-job shape closes that: record in this job, upload the PNGs as an artifact, and
    commit/push from a second job that checks out clean and runs no build code. Cost is a
    second runner and artifact plumbing. Worth doing if this repo ever takes branches from
    people without write access, since today a same-repo branch already implies push
    rights.


Judgment calls made without an explicit answer from the maintainer. Each is reversible;
none is load-bearing yet.

- **The debug log renders a throwable as exception types and stack frames, never `getMessage()`**
  (autopilot, 2026-08-21). A platform exception quotes what it was given, and on the Wi-Fi and
  location stacks that is exactly what the §4.6 floor bans — and unlike Simmo, whose log scrubs
  phone numbers out of messages, Snoozemo has no sanctioned scrubber (coordinates in free text are
  plain decimals; a reliable redactor for them does not exist). The cost is real: "permission
  denied" texts and file paths are lost, and only the type and frames locate a failure. Reversible
  by adding a message line with an allowlist of exception types known to carry no user data, if
  the field shows types-and-frames alone leaves failures undiagnosable.
- **The debug-log settings row landed** (2026-08-22), resolving the 2026-08-21 entry that held it
  for copy. The description is the maintainer's own wording — **`Save snooze details to help fix
  issues`** — and the title stayed the proposed **`Debug log`**; the maintainer's message quoted
  only the one line, read here as replacing the description (autopilot guess: if the quote was
  meant as the title, it's a two-string swap). Both inherited PR #62 findings are fixed: install
  and toggle now share one FIFO worker so the setting applies in call order, and only a
  `commit()` that returned true is applied — a refused write reverts the switch to what is
  actually stored. The failed-save line under the row landed with the approved
  **`Couldn't save this setting`** (maintainer, 2026-08-22), completing the row's failure
  surface. Codex's PR #68 round added two refinements: the store restores its process-local
  value when `commit()` fails (the map is updated before the disk write it reports on), and
  the screen follows the store through `DebugLogStore.observe` with reads held off while a
  tap's write is still on the worker.
- **`Couldn't end the snooze — trying again` has its own notification id rather than sharing the
  one-shot failure id** (autopilot, 2026-08-21). The deferred PR #8 finding said "cancel
  `ID_FAILURE` on the successful-release path", but that id is shared by every one-shot — and an
  ending that loses policy access posts its explanation on it moments before completing, so the
  literal fix would cancel the one actionable message the user is owed. The card is the app's only
  one-shot that a later event *retires* rather than replaces, which is the same argument that gave
  the stuck-rule card `ID_STUCK`. Cost: a stale `Couldn't snooze` and a `trying again` card can now
  coexist in the shade briefly, where the shared id would have replaced one with the other.
  Reversible — collapse the id back and accept the blanket cancel.

- **A refused end no longer settles for the snooze's own cap when that cap is hours away.**
  `ensureCapAfterRefusedEnd` used to re-arm the cap and stop. That is right on an *expired*
  record — the wake-up is already due, so it retries the release within moments — and wrong
  on a live one: an `End now` refused an hour into an eight-hour snooze left the phone quiet
  for the remaining seven, with the app having stopped trying and said nothing. The cap is
  still restored either way; the ladder now continues unless the cap is what ends this.
  Reversible by restoring the unconditional early return. Costs one 30 s retry alarm and, on
  hand-off, the `Couldn't end the snooze — trying again` card on a path that previously said
  nothing — which is the honest message, since it now really is trying.

- **Every hand-off in the release subsystem drops the end reason, and each one had to be
  re-attached by hand.** Codex found the same defect four times in this PR — the in-process
  retry, the no-service hand-off, the service hand-off, and the durable retry alarm — because
  each crosses a boundary that carries no context of its own: a delayed callback, an
  `Intent`, a `PendingIntent`. All four are fixed, but the *shape* is the finding: a fifth
  boundary added later will drop it again, silently, and the symptom is only ever a wrong
  word in a notification the user sees once. Worth considering a single carrier — the reason
  bundled with the identity, threaded as one value — rather than a parameter each site
  remembers to pass. Left as-is for now because that is a wider change than this PR, and
  every current site is covered.

- **`SnoozeService` is `open` and builds its DND controller through an overridable factory,
  purely so tests can make the platform refuse.** Every branch of the release escalation is
  reached only on a refused zen write, which no device or emulator produces on demand, so
  without this the whole of §7.1's performing half is unreachable by any test — which is how
  five defects in it reached review rather than a red build. Production overrides nothing.
  The alternative is moving the performers' remaining decisions into `:core`, which is
  bigger and probably the better end state; this is the cheap version and is reversible by
  deleting the factory. Recorded in `SPEC.md` §11 with the reasoning.
  - Still *not* covered: the failed-arm unwind (zen write lands, `save` fails), which needs
    a store that can be told to refuse — the next seam if that path grows another bug.

- **`DESIGN.md` renamed to `SPEC.md`** rather than keeping both. The sibling repos split
  product/architecture decisions (`SPEC.md`) from the plan (`TODO.md`), and the design doc
  was already the former; keeping two overlapping documents is how they drift apart.
  Alternative: leave `DESIGN.md` in place and write a thin `SPEC.md` beside it. Reversible
  with `git mv` — the history follows the rename.
- **Milestones moved out of the spec into this file** (as Phases 1–8, letters preserved),
  along with the hardware-verification list. Same reason: the spec says what and why, the
  plan says when. Reversible.
- **No CI workflow in the scaffold PR.** A workflow calling `./gradlew` with no Gradle
  project would be red on arrival and would train everyone to ignore a red check.
  Alternative: land a no-op workflow now and fill it in. It lands with the Gradle skeleton
  instead. *(Landed in the following PR, once there was a project to build.)*

Guessed while building the scaffold (autopilot, 2026-08-11):

- **`:core` is a plain Kotlin JVM module, not an Android library.** It is the strongest
  reading of "a seam that keeps the core buildable and testable on its own" — the Android
  SDK is not on its classpath, so the boundary can't erode by accident. Alternative: an
  Android library module, which would allow `Context` in later. Reversible by swapping the
  plugin; the cost of reversing grows only if domain code starts depending on Android.
- **Toolchain versions pinned to match the sibling repos** — AGP 9.2.0, Kotlin 2.2.20,
  Gradle 9.4.1, Compose BOM 2026.05.01, JDK 17 target. Lint notes AGP 9.3.1 exists;
  matching Simmo is worth more than being current until there is a reason. Reversible.
- **CI trimmed to build + `:core:test` + test + lint.** No screenshot job (nothing to
  record) and no deploy job (no signing, no Play track). Both are already on the phase list.
- **`.debug` application-ID suffix, and no R8 anywhere yet.** The sibling repos' CI-vs-local
  suffix split and R8-in-CI both exist to protect a shipping build; neither is meaningful
  before Phase 6, and adding them now would be config nobody has tested. Reversible.
- **A placeholder launcher icon** (a plain `Z` vector on a dark background) and a framework
  XML theme, so `assembleDebug` produces something installable. The real mark is a Phase 2
  design item under `SPEC.md` §4.2's 24 dp single-color constraint.
- **One placeholder user-facing string** (`Not built yet — see TODO.md`) on the placeholder
  screen. It is the only copy in the app and it will be deleted, so it did not go through
  the usual propose-copy-in-chat step.
- **`PresenceMonitor` defined in `:core`, not `:presence`** (Codex, PR #3). `SPEC.md` §11's
  tree put the interface in `:presence`, which cannot work: the controller takes one by
  injection while `:presence` depends on `:core` for `Anchor`, so the contract has to sit
  with its consumer or the modules form a cycle. The spec now says so, and the same shape
  applies to the DND interface in Phase 1. Reversible only by merging the modules, so worth
  a second opinion if the layering is ever reconsidered.

Guessed while unifying the refused-release ladder (autopilot, 2026-08-12):

- **The ladder's rung order is the spec's, so three call sites changed behavior.** `SPEC.md` §7.1
  says the durable obligation is written first, always. `releaseRecordlessRule` and the no-service
  `releaseDirectly` both armed their alarm first and stored the obligation only once the in-process
  rung was reached, so a refused alarm followed by a teardown left nothing written down about a rule
  that may still be on. They now store first. Alternative: keep each site's order and make the
  decision take an ordering parameter — which is the duplication back in a new shape. Reversible,
  but reversing it re-opens the bug.
- **`ensureCapAfterRefusedEnd` and `rescheduleIfUnfinished` now walk the whole ladder.** They used to
  stop at one alarm attempt and a message; they now get the in-process retry and the user's exit like
  every other path. Strictly more successors behind a refused release, at the cost of holding the
  service up longer in the rare case. Reversible by giving those two sites a shorter ladder.
- **The give-up makes one further alarm attempt when the stuck-rule card will not post.** Previously
  only the recordless path did this. It costs one wake-up, and only where nothing at all has landed —
  no obligation, no alarm, no card. Reversible by dropping `MAX_ALARM_ATTEMPTS` to 1.
- **`Couldn't end the snooze — trying again` is now posted only where a record exists.** The message
  names a snooze, and the recordless path has none to name; that path's honest message is the
  stuck-rule card, which it already gets. Reversible — one condition.
- **`rescheduleIfUnfinished` asks for the identified release-retry alarm instead of a plain cap
  check.** Both work there, since the record really is expired; the identified one carries the
  instruction rather than re-deriving it and cannot act on a snooze armed since. Reversible.
- **`tellTheUser` was deleted rather than honored.** It was threaded through both escalations and
  read by neither — the give-up posted the card regardless, as its own comment said. Not a judgment
  call so much as a finding, recorded here because deleting a parameter looks like one.

Guessed while building the tile and cap (autopilot, 2026-08-12):

- [x] **Resolved 2026-08-12 — the missing "the arm failed and DND may still be on" copy.**
  The last-resort branch of the refused-arm path used to log and stop, because the only
  string that fit said `Couldn't end the snooze — trying again`, which contradicted the
  `Couldn't snooze` already on screen and promised a retry that had just failed to schedule.
  The maintainer approved `Do Not Disturb may still be on` / `Snoozemo couldn't turn it
  off.` with `Unsnooze` and `Dismiss` actions, so both give-up paths now say the true thing and
  offer the
  exit. The debug screen's `failure_could_not_end` reuse is unchanged and still accurate
  there, since that path really is retrying.
- **`Already at the 24-hour limit` → `Already at the time limit`.** The old copy named a
  number that stopped being true when `+30 min`'s ceiling was corrected from 24 h to the
  8-hour default (Codex, PR #8 — `SPEC.md` §7 says `+30 min` may not push past the
  backstop). Dropping the number keeps it true whatever a place's cap turns out to be,
  rather than swapping in `8-hour` and having to change it again when per-place caps land.
  Alternative: state the actual ceiling. Reversible — one string, no locales yet, and it is
  in the wording pass the maintainer already has open.

Guessed while making the clock change survive a reboot (autopilot, 2026-08-12):

- **A record with no stored offset now ends on a clock change instead of being restated.**
  Codex asked for this on PR #14, and the reasoning holds on its own: such a record is read
  against wall time alone, wall time is what just moved, and nothing can recover by how much
  — so restating it stamps a moved reading with a frame it was never measured in, and the
  24 h ceiling only catches shifts far larger than the ones a user actually makes. The
  outcome without it is *no worse than before* (the deadline written is the one already
  there), so this is a genuine judgment call rather than a bug fix: it trades a snooze that
  ends early for one whose bound nobody can read. Principle 1 settles it that way, and it
  matches the boot receiver's answer when it cannot restate its own offset. Only records
  written before the offset existed can reach it — the app is unreleased, so in practice
  none — and reversing it is one branch in `ClockChange`.
- **The clock change is performed through the running service, which starts one if the
  process is gone.** That is a service start, a rule re-assertion and an alarm re-arm on
  every `TIME_SET` that changes anything, where the receiver alone would have written one
  preferences record. Taken because the alternative leaves the controller's copy stale for
  `+30 min` to write back — a repair undone by the button beside it. The cost is bounded by
  how rarely a clock is actually set, and the same start already happens on the expired
  path. Reversible by having the receiver write the record and merely notify the service.

## Keeping the phone alive: the options ledger

Everything considered for "how does Snoozemo stay able to end a snooze", with why each was or was
not taken, and **what would make us revisit it**. Written down because the same menu keeps coming
back around, and the reasoning is expensive to reconstruct (`SPEC.md` §3.3, §3.6, §8.4).

The decision as of 2026-08-12 is **build nothing extra**: the duration cap alone is the floor, and
Phase 3's wake-up sources give the rest for free.

| Option | Cost | Status | Revisit when |
|---|---|---|---|
| **Duration cap alarm** (`setAndAllowWhileIdle`) | One alarm per snooze. No permission, no review | **Built.** The floor everything else sits on | Never remove. If a change makes the cap depend on a service, that change is wrong |
| **`WorkManager` periodic backstop**, 30 min while armed | Deferrable and batched — the cheap kind of periodic | **Built** (`SPEC.md` §6.10). Exists for geofence reliability; the policy-access check rides along free — each wake restores the service, whose reconcile-on-every-wake covers it | Confirmed: the wake's restore runs the service's platform reconcile, so policy access is re-read each wake |
| **Geofence exit + Wi-Fi loss callbacks** | Event-driven, ~free | **Planned, Phase 3.** The primary and secondary sources | — |
| **Foreground service** (`specialUse`) | Cheapest at runtime — no wakeups at all — but a Play review | **No, and scoped: not on Play, not for v1.** A permission is not spent on revocation handling. `direct` has one anyway from Phase 7 | Core functionality genuinely requires it. The case to build is in `SPEC.md` §3.3 |
| **Self-rescheduling `setAndAllowWhileIdle` chain** | The expensive periodic form: punches through Doze, ~32 wakeups per 8 h snooze, puts a floor under §6.7's duty cycle | **No.** Duplicates the `WorkManager` backstop at higher cost | Only if something genuinely needs to beat Doze, which policy-access reconciliation does not |
| **Manifest-registered policy-access receiver** | Free if it worked | **No.** `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` is not on the implicit-broadcast exemption list | A future platform version exempts it |
| **`READ_PHONE_STATE`** to catch an incoming call as a wake-up | A permission, and a sensitive one | **No** (maintainer leans no, 2026-08-12). The benefit arrives passively anyway: a call ends Doze, which flushes our deferred alarms and jobs without us listening for anything | Only if measurement shows Doze deferral is materially hurting, *and* nothing cheaper fixes it. Expect the answer to stay no |
| **Accept the gap** | Free | **Taken**, for the policy-access watch specifically — it fails safe (`SPEC.md` §8.2, §8.4) | If revocation ever stops deleting the rule, this becomes principle 1 and must be re-taken |

Two structural rules fall out of the above and constrain future work: the **cap never depends on a
process staying alive**, and the **release obligation never lives only in memory** — alarm first,
in-process second (`SPEC.md` §8.1).

## Deferred review findings (Codex, PR #8)

Real but non-blocking, deferred rather than dismissed: the PR took every P1 and parked these. All
five are staleness, attribution or missing-status bugs — nothing here leaves the phone silently
quiet, and nothing here loses an exit. Two of them are in `MainActivity`, which Phase 4 replaces
with real onboarding and settings, so they may be fixed by deletion.

- [x] **The tile warms the zen rule *id*, but not the rule itself.** `onStartListening` calls
  `ruleId()`, which fills the cache from disk — it does not check that the rule still exists.
  Where the id is absent or stale (rule deleted in Settings, or the process died while the
  user was granting access), the next tap reaches `setSnoozed`'s slow path and does a
  policy-access check, a rule lookup, possibly a creation, and a persist — all before
  `STATE_TRUE`, on the one path `SPEC.md` §4.1 says must not wait.
  - **Consequence is latency, not correctness**: the slow path still creates the rule and
    arms. So this is a quality bug on the primary interaction, not a snooze that fails.
  - **Not a one-line fix, which is why it waits.** `ensureRule()` lives on
    `AndroidZenController`, which needs a `ComponentName` for the rule's
    `configurationActivity` pointing at `MainActivity` in `:app` — and `:tile` deliberately
    holds no compile-time reference to `:app` (it launches the trampoline by action for the
    same reason). Doing this properly needs a seam: either the reverse of
    `SnoozeTileBridge`, or letting `:dnd` resolve the configuration activity itself so the
    controller can be built without `:app`.
  - It must also stay **off the main thread** — `ensureRule` can create a rule, which is a
    heavier binder call than the id read, and `onStartListening` runs while the shade is
    opening.
  - **Landed** (2026-08-21), via the second of the two seams this entry sketched: `:dnd`
    resolves the configuration activity itself. `AndroidZenController.CONFIGURATION_ACTIVITY_CLASS`
    names `MainActivity` by string — the same shape as the tile's explicit trampoline intent,
    pinned to the real class by a test in `:app` — and `AndroidZenController.default(context)`
    is now the one construction every production caller shares (the service, the receivers,
    the screen, and the tile's new warm). `onStartListening` verifies-and-recreates the rule
    on a daemon thread beside the existing id read; a tap that outraces it is no worse off
    than before.

- [x] **A stale `Couldn't end the snooze` survives the retry that succeeds.** `showEnded` drops
  `ID_ONGOING` only, so a failure posted under `ID_FAILURE` by an earlier refusal stays in the shade
  after a later cap or manual retry works. Same class as the successful-arm cleanup, on the other
  path. Fix: cancel `ID_FAILURE` on the successful-release path.
  **Fixed, but not by the recorded fix** — the card moved to its own id instead (see *Decisions
  needing review*): canceling the shared `ID_FAILURE` wholesale would also take down whichever
  unrelated explanation was posted last, such as the access-revocation message an ending posts
  moments before completing. It now comes down at every point the rule is confirmed off — the same
  set of places the stuck-rule card does, because both are claims that it might not be.

- [x] **A stale "rule is switched off in Settings" survives the user re-enabling it.**
  `ensureRuleInBackground` returns early on `READY`/`MISSING_ACCESS` without touching `lastOutcome`,
  so the screen keeps claiming the rule is disabled after arming works again. Fix: retire *only* the
  stale rule-status message — `lastOutcome` is shared with the arm/end paths, so blanket-clearing it
  would wipe messages that are still true. (`MainActivity`; may be moot after Phase 4.)
  **Fixed as recorded** (2026-08-22): a `READY` answer retires the two rule-status messages and
  nothing else; the decision (`StaleRuleClaim`) lives in `:core` with the rationale for why
  `MISSING_ACCESS` retires nothing — it observes nothing about the rule.

- [x] **A manual release through the no-service fallback is attributed to automation.**
  `releaseDirectly` hard-codes `ZenTrigger.CONTEXT`, so on API 35+ the platform Modes UI credits the
  user's own `End now` to the app deciding by itself, contrary to `SPEC.md` §5.4 — which is explicit
  that the source lets the user tell "I did this" from "my phone did this". Fix: pass the trigger
  through, `USER_ACTION` for `EndReason.MANUAL`, as the controller path already does. **Fixed as
  recorded**, with both directions pinned in `ReceiverRefusalTest`.

- [x] **An access read can survive `onStop` and act after the next `onStart`.** The lifecycle guard
  and the generation counter together still leave a window: the same activity instance can become
  `STARTED` again, and a worker holding a stale `DENIED` can land after that but before the deferred
  refresh bumps the generation — ending a snooze armed in between. Fix: increment
  `latestAccessRefresh` in `onStop`, invalidating everything from the previous visible session.
  (`MainActivity`; may be moot after Phase 4.)
  **Fixed as recorded** (2026-08-22), pinned by `MainActivityLifecycleTest`, which fails without
  the bump.

- [x] **A refused manual `End now` waits for the original cap instead of retrying soon.**
  When the zen write returns `PLATFORM_REFUSED` and `ensureCapAfterRefusedEnd` manages to re-arm
  the *original* cap, it returns satisfied — but on an early end that deadline can be hours away,
  so the phone stays quiet until then while the notification says Snoozemo is trying again. The
  no-service path already handles this by picking `ACTION_CAP_LOST` (which means "end it, whatever
  the clock says") over a plain cap check; the service path was never given the same treatment.
  Fix: arm the identified short release retry as well as restoring the cap, so the retry happens in
  minutes rather than at the deadline. Costs one extra wake-up, and only on a refused end.
  **Already fixed by the §7.1 unification** — `ensureCapAfterRefusedEnd` now escalates through the
  ladder unless the re-armed cap is already due, which arms exactly that identified retry; pinned
  by `a refused manual end keeps trying rather than waiting for a distant cap`. Checked off on
  re-reading rather than re-implemented.

- [x] **A failed arm doesn't discharge an outstanding stuck-rule obligation.** `ACTION_ARM` is
  excluded from the reconcile on purpose — a preferences read and a possible zen write between the
  tap and `STATE_TRUE` is what `SPEC.md` §4.1 forbids — and that was justified by "a successful arm
  clears the flag anyway". True of a *successful* arm; not of one that fails **before** reaching zen,
  which is what a refused `CapAlarm.arm()` is. The service then stops with the tile inactive, a
  one-shot `Couldn't snooze`, and nothing scheduled, while an older rule may still be silencing the
  phone.
  - **The `Dismiss` action is what sharpens it.** Giving the card its own id protects it from being
    *replaced*, not from being deliberately removed — and dismissing it is a supported action. Once
    it is gone the notification is no longer a successor, so the flag is the only thing left, and
    this is the path that skips it.
  - Fix: reconcile after an arm that fails **without taking ownership of the rule**, rather than on
    the way in — which keeps the arm path clean while giving the failed-arm branch the discharge
    every other start already has.
  - Bounded meanwhile: the snooze that left the rule on still has its own cap, and any later
    non-arm start still discharges the obligation.
  - **Fixed as recorded**: the discharge is one shared helper, run by every non-arm start on the
    way in and by the two arm branches that fail without touching or taking over the rule — the
    refused cap alarm, and a zen refusal that means nothing is silencing the phone. The
    `PLATFORM_REFUSED` arm branch already hands the obligation to the ladder, and the failed-save
    unwind owns the rule it is releasing, so neither needed it.

- [ ] **A refused `notify` leaves the ongoing notification missing for the whole snooze.**
  `showOngoing` discards `post`'s result, so a transient throw with `POST_NOTIFICATIONS` *granted*
  loses the countdown, the degraded-mode line, `End now` and `+30 min` until something else happens
  to re-post — and on a duration-only snooze nothing does, because there are no intermediate
  wake-ups between the arm and the cap.
  - **Deferred because the honest fix is a battery decision, not a one-liner.** Retrying in
    process dies with the service, which is the case that matters; scheduling a retry alarm buys
    a rare recovery with a wake-up on every snooze, against `SPEC.md` §9's budget. The cheap
    version — re-post on the *next* wake-up the snooze already pays for — is free but does
    nothing for the duration-only snooze this is about. Phase 3's `WorkManager` backstop changes
    that arithmetic: once there is a periodic wake-up anyway, the re-post rides along for nothing.
  - **Not an exit lost, which is why it is a P2.** The tile still renders active and still ends
    the snooze on a tap, and the cap still fires. What is lost is the status surface, and only
    on a throw the platform does not usually make.

## Maintainer decisions

New questions that are not autopilot's to guess go here, unchecked, with the options and what each
costs.

- [ ] **Is the stuck-rule notification right to be non-dismissible?** Started at `setOngoing(true)`
  on the maintainer's lean (2026-08-12), paired with an explicit `Dismiss` action so a deliberate
  "I know, leave me alone" is available while an accidental swipe is not. The argument for is that
  this card is the only thing pointing at a rule that may still be silencing the phone, so losing it
  to a stray swipe reopens the dead end it was added to close. The argument against is that it is
  insistent about a rule that only *might* be stuck, and the title says so.
  - Worth knowing before deciding: on API 34+ `setOngoing` is a strong hint rather than a lock —
    Android 14 made ongoing notifications user-dismissible in most cases. What it reliably buys is
    exclusion from `Clear all`, which is the accidental loss that actually matters. So the choice is
    softer than it looks either way.
  - Settle it on a device, once there is one to look at: does an un-swipeable card here read as
    protective or as nagging? Reversible in one line.

- [x] **Resolved 2026-08-12 — `SPEC.md` §3.4 said the `play` flavor had "no ongoing notification";
  §4.2 said the countdown, the degraded-mode reason and the way to end a snooze live *only* there.**
  Both could not hold for a 1x1 icon-only tile. Maintainer had no strong preference and delegated
  the call. Decided: **both flavors post it**, and §3.4's wording was what was wrong — what option B
  buys is the absence of a *foreground service*, not of notifications. Full reasoning is recorded
  inline in `SPEC.md` §3.4 as a dated amendment, which is the authoritative copy; §9's battery table
  carried the same conflation and was corrected with it. No code change: this is what the code
  already did.

- [x] **Resolved 2026-08-12 — nothing watches for a revoked Do Not Disturb grant for most of a
  snooze.** The policy-access receiver is registered dynamically by `SnoozeService`, which is an
  ordinary started service (no `startForeground` anywhere). Android can stop it once the app is
  backgrounded, and `START_STICKY` does not restore a service the system stopped under
  background-execution limits — so on an eight-hour snooze a revocation at hour one may go unnoticed
  until the cap fires.

  **Decided: build nothing now.** Two maintainer principles settled it. *A permission is not spent
  on revocation handling* — on the `play` flavor a foreground service buys nothing the Geofencing
  API does not already do, so it would exist only for this watch and some retry state. And *the most
  battery-efficient option wins*: an idle foreground service is effectively free, but we are not
  entitled to one here, and the remaining option — a self-rescheduling ~15 minute
  `setAndAllowWhileIdle` chain — is the *expensive* one. It would add ~32 wakeups per eight-hour
  snooze and put a floor under §6.7's duty cycle, which exists to drive work to zero while the phone
  is stationary.

  The distinction that decides it is **new wakeups versus wakeups already being paid for.** §9
  already budgets a 15–30 minute `WorkManager` backstop for the `play` flavor in Phase 3 — and it is
  there for geofence unreliability (§6.10), not for this. Reconciling policy access on a wake-up
  that is happening anyway costs nothing extra; building a fresh alarm chain now, when there are
  currently *zero* wakeups between arming and the cap, adds every one of them. Same for `direct`,
  whose Phase 7 foreground service makes the watch event-driven and free.

  So the ranking is: an idle foreground service (event-driven, no wakeups) is cheapest but we are
  not entitled to one; piggybacking an existing wake-up is next and is what Phase 3 gives us; a
  purpose-built periodic chain is the most expensive and is the only thing available today. Hence
  wait.

  **And the gap fails in the safe direction, which is the strongest reason of all.** §8.2: once
  access is revoked the platform has already deleted the rule, so *nothing is silencing the phone* —
  DND is off and calls ring through. Noticing late therefore costs a stale `Snoozing` on the tile
  and in the notification, not a phone stuck quiet. That is principle 2 (the UI lying), not
  principle 1 (silent through something that mattered), and the user finds out the moment their
  phone rings. A `WorkManager` backstop deferring in Doze is acceptable precisely because the
  failure it is late to notice is one the platform has already resolved safely.

  Worth stating what would change that: if a future design ever made revocation leave DND *on* —
  a rule the platform keeps rather than deletes — this stops being a cosmetic gap and becomes a
  principle 1 one, and the decision above has to be re-taken.

  Cheapest to reverse: if it turns out to matter before Phase 3, the alarm chain is a small,
  self-contained addition. **Phase 3 must confirm the backstop actually reconciles policy access**,
  or this reopens.

  The `specialUse` justification worth keeping if core functionality ever needs a foreground
  service is recorded in `SPEC.md` §3.3; the plan for losing a mechanism entirely is §3.6.

- [x] **Resolved 2026-08-12 — the recordless release's bounded give-up was a dead end.**
  After an uncertain arm (the platform accepted `STATE_TRUE` on a rule it then refused to
  reset), the record is erased, no alarm will schedule, and the in-process retry gives up
  after ten attempts — leaving no record, no alarm and no obligation. The tile read inactive
  and the failure notification had no action, so the user had no affordance either.

  **Decided: option B, give the user the affordance.** The give-up now posts
  `Do Not Disturb may still be on` / `Snoozemo couldn't turn it off.` with `Unsnooze` and
  `Dismiss` actions (copy approved by the maintainer in chat, 2026-08-12).

  What settled it was noticing that **the notification is itself the durable part**, which
  makes B strictly less machinery than A rather than more. It is posted from the code that
  is giving up — no detection after the fact is needed — and once in the shade it outlives
  the process, with its action still live. So no state is persisted, nothing has to be read
  back, identified against a later snooze, or retired when stale, which is the class of
  state that produced most of this PR's findings. And it cannot misfire: it only acts when
  the user taps it, and a user asking for Do Not Disturb off always wants exactly that.
  Option A's automatic recovery had the opposite property.

  **Amended the same day, twice, because the "no persisted state" claim did not survive
  review.** The notification is durable *once posted* — but Codex showed two lifecycles
  where it is never posted or never seen, and in both the app was left exactly where this
  entry says it should not be. So the obligation is now persisted after all
  (`PendingFailureStore.rememberRuleMayBeStuck`), written *before* the first attempt rather
  than at the give-up:
  - **The service can be stopped mid-retry.** It is an ordinary started service, so Android
    may stop it under background-execution limits, and `onDestroy` cancels the delayed
    callback — the code that posts the give-up notification is simply never reached.
    Recording the obligation at the give-up meant the common teardown recorded nothing.
  - **`POST_NOTIFICATIONS` may be denied**, which this entry originally waved off by saying
    the existing replay covers it. It did not: `ACTION_REFRESH` replayed only `ArmFailure`,
    never the stuck-rule card, so on a tile-first install the exit was permanently absent.
    `ACTION_REFRESH` now re-attempts the release and re-posts if it is still refused.

  The flag carries **no identity**, unlike every other retry here, and that is what keeps it
  from being the state-sync hazard this entry feared: it means "our own rule may be on with
  no snooze behind it", every reader re-checks that there is still no record before acting,
  and driving an already-off rule off is a no-op. It is cleared on a confirmed release, on a
  new snooze taking ownership, and on a successful arm.

  The user can still swipe the card away, but it is `setOngoing` now (maintainer lean), so
  it survives `Clear all`; whether that is right is the open item above.

  `releaseDirectly`'s last-resort branch now posts the same notification, replacing a
  `Couldn't end the snooze — trying again` that was false on that path in both directions.

Guessed while making the access flow tappable (autopilot, 2026-08-12):

- **The permission allowlist cannot be installed from inside this repo, and the attempt was
  dropped.** `.claude/settings.json` is broadened here — it is read by desktop and CLI sessions
  rooted in this repo — but Claude Code on the web does not read it, and a `SessionStart` hook
  cannot fix that: it runs after the client has loaded its permission settings, and the sandbox is
  ephemeral, so a `$HOME/.claude/settings.json` written from the hook helps neither this session nor
  the next (maintainer, 2026-08-13; independently flagged by Codex on PR #18 after the hook version
  was pushed). **The durable fix is the environment's own setup script**, configured in the Claude
  Code web environment and not in version control — so it is the maintainer's to apply. The list it
  needs is the `permissions.allow` array in `.claude/settings.json`; the scheduler entries are the
  load-bearing ones, since the PR-watch loop arms its next check with no human present and a prompt
  there ends the watch silently.

- **New user-facing copy that did not go through the propose-in-chat step.** Six strings, all on
  the setup rows: `Do Not Disturb access` / `Granted` / `Snoozemo can't snooze without it`,
  `Notifications` / `Allowed` / `Snoozemo can't show what a snooze is doing`, plus the two action
  lines and a `Couldn't open Settings` failure. It replaces `Snoozemo needs Do Not Disturb access`,
  `Grant access` and the transient `Notifications are off — …`. Reversible — no locales yet, and
  this screen's copy is already in the wording pass the maintainer has open. **Approved as they
  stand** (maintainer, 2026-08-12: "strings seem ok"), so they are the current answer rather than
  an open question; they still belong to that later wording pass like the rest of the screen's
  copy. **The action lines were replaced on 2026-08-13** at the maintainer's request: `Opens
  Settings` and `Tap to allow` and `Tap to add` are now the buttons `Grant`, `Allow` and `Add`,
  and they are absent entirely once the capability is in place.
- ~~**The rows stay tappable once granted**~~ — **reversed** (maintainer, 2026-08-13: "if it's
  allowed i'm not sure we need a button at all"). A granted row now shows its state and no control,
  which is the sibling Simmo repo's `GrantRow` shape. The objection recorded here still stands and
  is the cost being paid: there is no route from this screen to the Do Not Disturb access toggle
  for a user who wants to revoke it, and no route to the notification settings for one who wants to
  mute the app. Reversible — the `action` argument becomes unconditional again.
- ~~**Tapping the notifications row when the permission is granted opens the app's notification
  settings**~~ — **reversed** with the item above, and only for the fully-working state: held but
  blocked in Settings still shows `Allow`, because that one is broken and repairable.
- **A new one-boolean preferences file (`notification_prompt`) rather than a key on an existing
  one.** `PendingFailureStore`'s file is about a snooze that failed; this is about the app's own
  request history and is read by two activities. Reversible — one key, one file, and nothing
  depends on the separation.
- **`checkSelfPermission` and `shouldShowRequestPermissionRationale` are read on the main thread**,
  unlike the policy-access and record reads beside them, which are off-thread. Neither is a binder
  round trip and both run after the first frame, so this adds nothing to the frame; the
  alternative is a third generation-counter dance for a read that cannot block. Reversible if a
  device shows otherwise.
- **`startActivity` for a Settings screen is contained and reports failure** rather than crashing
  or being swallowed. An OEM build or a restricted profile without the screen would otherwise take
  the app down from a tap on a row describing a problem. Reversible, but reversing it picks one of
  those two outcomes.

- **The privacy policy is written for v1 as specified, not for the code as it stands.** It
  names location, background location and the Wi-Fi read, none of which the app declares
  yet. The alternative — describe only today's permissions — produces a document that is
  accurate this week and understates the app by the first release, and understating is the
  harmful direction for a privacy policy. Reversible by trimming those sections; the
  re-verify item in Phase 0 is what stops it being forgotten either way.

- ~~**The policy's contact is the repo's issue tracker, not an email address**~~
  **Answered: `mikel@mikelward.com`** (maintainer, 2026-08-13), matching the sibling apps
  — Simmo's policy closes with the same address under the same `## Contact` heading and
  the same sentence, so Snoozemo now reads identically. Autopilot had left this open on
  the grounds that publishing an address cannot be undone; the maintainer's steer was to
  use theirs "or whatever we use in the other app privacy policies", which turned out to
  be the same thing.

- ~~**The BSSID is disclosed as an open question rather than justified or removed**~~
  **Answered: it stays** (maintainer, 2026-08-13). Codex asked in PR #23 whether
  `Anchor.bssid` earned its place, since the diagnostic that would consume it may not
  record a full BSSID and the field dies with the anchor. The maintainer named the use
  autopilot could not: *"a way to know if I've left a meeting room"*. Diagnostics was never
  the interesting justification — **a room is smaller than an SSID**, and room-scale is
  exactly what this app is for. Recorded in `SPEC.md` §6.2 with the platform constraints
  that bound it, and `docs/PRIVACY.md` now says the field is captured-and-unacted-on rather
  than under review. The feature itself is the item below.

## Review and merge gates

- [x] Add the shared consumer check (`codex-review-check.yml` from
      mikelward/codex-review) if it applies to this repository's
      codex-review setup — see its `docs/CONSUMER.md`. `codex-review.yml`
      already publishes the `codex` status here, and it must remain the
      only workflow holding `statuses: write`.
- [ ] Verify the settings half of the fleet's bar: a ruleset on the
      default branch requiring `android-ci.yml`'s always-reporting `gate`
      job and the `codex` status, plus conversation resolution and
      up-to-date branches, with the auto-merge setting enabled.
