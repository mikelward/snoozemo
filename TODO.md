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
      `PresenceMonitor`. `play` is the default; CI's `assembleRelease` builds both on a
      pull request, since a change that compiles in one can break the other. On main
      only `play` is built, by `deploy`'s `bundlePlayRelease`.
- [x] CI workflow (`.github/workflows/ci.yml`): build both flavors, `:core:test` on
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
      answers it has to agree with — "no data collected, no data shared" when this landed,
      since superseded by crash reporting (`SPEC.md` §12; the current answer is the Phase 6
      item below).
      Written from the manifests and the five `SharedPreferences` stores rather than from
      the spec alone, so the "what Snoozemo keeps" table lists what the code actually
      writes and when it is erased. Two things it deliberately does **not** do:
      - **It does not describe the debug log (§4.6)** — *true when this item landed, since
        superseded* (Codex, PR #102). At the time the log wasn't built, and a policy
        describing a feature the app doesn't have is inaccurate in the direction that costs
        trust; the gate was Phase 5's "must describe what the log carries **before** the
        sharing surface ships". Both have since happened: the sharing surface is in, and
        `docs/PRIVACY.md` has a complete `The debug log` section. Nothing here blocks
        hosting the policy.
      - **It is not published by this commit.** Hosting it, and the Play Data Safety form
        it has to agree with, belong to Phase 6's release plumbing — which is the
        *internal-track* release, so both are due before the first build reaches a tester,
        not at some later public launch.
- [ ] Re-verify `docs/PRIVACY.md` against the shipped build before the first release.
      **The manifest half is done** — location and the Wi-Fi read landed in the main
      manifest and background location in `play`'s (2026-08-22), so the earlier note that
      the app declared none of them is stale (Codex, PR #102). What is still ahead of the
      build is behavior: it describes **v1 as specified**, including the departure
      detection that ends a snooze on leaving. That is built and wired on `play` (though
      never yet run on a handset); on `direct` it genuinely is ahead of the build, since
      `DurationOnlyPresenceMonitor` is a stand-in until Phase 7 and every `direct` snooze
      is a timer today. This bullet said "every snooze is duration-only" until the
      2026-08-25 audit. A policy promising less than the app does is the harmful
      direction, so this is the safe one to be wrong in, but what it says Snoozemo keeps
      and does has to match the shipped build on the day it is hosted, not merely
      eventually.

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
      this, so that infrastructure is new too.
      `TileService`/`qsTile` themselves aren't practically unit-testable under Robolectric, so
      `onClick`'s actual `qsTile` writes (as opposed to the decision feeding them) are still verified
      by inspection only — needs a real device.
- [x] **Tile latency on a state change with no tap behind it** — the cap firing, `+30 min` or
      `End now` from the ongoing notification, a release from `MainScreen` (maintainer, 2026-08-24:
      *"still not immediately toggling the tile state/appearance when exiting or entering snooze"*).
      Not latency at all, in the end: `SnoozeTileBridge` sent `TileService.requestListeningState()`,
      which the platform documents as applying "only to tiles that have `META_DATA_ACTIVE_TILE`
      defined as true on their `TileService` Manifest declaration, and **will do nothing
      otherwise**". Snoozemo's tile is not an active tile, so all eight refresh call sites were a
      documented no-op and the tile only ever changed when the shade was closed and reopened —
      including while the user was looking at the ongoing notification an inch below it. Declaring
      the tile active would have fixed the push and cost the two things the passive shade-open bind
      buys (`SPEC.md` §4.2): a countdown recomputed from the record every time the shade opens, and
      the zen rule warmed while a tap may be a moment away. So the delivery moved in-process
      instead — `:tile` and `:app` are one process, and a listening `TileService` is a live object
      in it. `TileRepaintRegistry` is the seam: the tile registers in `onStartListening` and drops
      out in `onStopListening`, and `SnoozeTileBridge.refresh()` notifies it after the record is
      written. A tile that is not listening is not on screen, and `onStartListening` re-reads the
      record when it next is, so there is nothing to queue. Covered by `TileRepaintRegistryTest`
      and `SnoozeTileBridgeTest` — the register/notify half is plain Kotlin precisely because
      `TileService` is not testable. **Still owed: a device check** that the repaint lands
      visibly while the shade is open (hardware item below).
- [x] `ArmTrampolineActivity` (`SPEC.md` §6.9): transparent theme, starts the service in
      `onCreate` before any UI, launched via `startActivityAndCollapse(PendingIntent)`.
- [x] Ongoing notification on channel `snooze_active`, `IMPORTANCE_LOW`, with `End now` and
      `+30 min` actions (`SPEC.md` §4.3).
- [x] Let the ongoing notification and the stuck-rule alert bypass Do Not Disturb (`SPEC.md`
      §5.7, maintainer, 2026-08-23). `setBypassDnd(true)` on `snooze_active` and on a third,
      emergency-only channel `snooze_urgent` created for `showStuckRule()` alone — pure code,
      no separate settings prompt, because arming already requires the same
      `ACCESS_NOTIFICATION_POLICY` access the flag needs to take effect. `snooze_ended`
      deliberately does **not** bypass (see the split below). **Still owed: verify on a real
      device** that the flag actually keeps the cards audible/visible through the app's own
      DND — nothing in this sandbox can confirm the platform honors it (hardware item 12
      below).
- [x] **Split the emergency alert onto its own channel, off `snooze_ended`** (Codex, PR #92;
      maintainer, 2026-08-23). `setBypassDnd` is channel-wide, not scoped to Snoozemo's own
      rule, so a bypassing `snooze_ended` would let a routine notice — `showEnded()`'s
      departure/cap card, an interim "couldn't end the snooze, trying again" — sound through
      an unrelated DND source (a Bedtime schedule, another app's rule) that the user chose for
      reasons that have nothing to do with Snoozemo. Only `showStuckRule()` — the sole way back
      from a phone that may still be silenced by Snoozemo's own rule — genuinely needs to
      survive that; it moved to its own `snooze_urgent` channel and everything else stayed on
      `snooze_ended`, non-bypassing. Retires the "Decisions needing review" entry this replaced.
- [x] **Keep the bypass correct however access was granted, and however late a bypassing alert
      posts** (Codex, PR #92, many rounds — the mechanism below is the final state, arrived at
      after several real gaps were found and fixed in getting there; see PR #92's history for the
      iteration). Channel creation runs from `warm()` at app startup, before onboarding can have
      granted `ACCESS_NOTIFICATION_POLICY` — and the platform only honors `setBypassDnd` from a
      caller that currently holds that access, silently keeping `bypassDnd = false` on a channel
      created without it. `SnoozeNotifications.reapplyDndBypassOnce()` re-issues the channels and
      is called from exactly two places, each immediately before it posts: `showOngoing()` and
      `showStuckRule()` — never from `SnoozeService.armWithCap()`, which was tried and consistently
      turned out to sit ahead of a durable write (the record's save, or `beginRelease()`'s release
      obligation) that a process death could lose. Both call sites are safe for the arm-path rule
      because `beginArming()` has already made its zen-state IPC by the time either one runs. The
      guard checks `NotificationManager.isNotificationPolicyAccessGranted` directly — not whether
      `createNotificationChannel` merely avoided throwing, which is a different and weaker signal —
      and is contained the same way every other binder call in this class is, treating a failed
      read as "not granted" so a later attempt retries. Marked done only once access is actually
      confirmed held, so a no-access attempt (a tile tap before the user has ever granted access)
      never permanently satisfies it over channels that were never actually fixed. Covered by
      `SnoozeNotificationsChannelTest`, including the denied-access case Robolectric's shadow can't
      reach on its own (`setNotificationPolicyAccessGranted`).
- [x] **The ongoing card needed `setOnlyAlertOnce`, and didn't have it** (Codex, PR #92; `SPEC.md`
      §4.3). `showOngoing()` reposts on every `ARMED`/`CHECKING` transition, including routine
      presence evidence flip-flopping while a snooze runs — not just the initial arm. Before this
      PR the channel didn't bypass DND, so a repost re-alerting was a latent bug the platform's
      own filter happened to catch; once `snooze_active` started bypassing Snoozemo's own DND, the
      same repost would have genuinely re-sounded or re-vibrated on every presence re-check. Fixed
      with `setOnlyAlertOnce(true)` — only the first post of the card alerts, later ones update
      quietly — and covered by `SnoozeNotificationsChannelTest` asserting
      `Notification.FLAG_ONLY_ALERT_ONCE`.
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
        maintainer asked for directly. (Superseded 2026-08-24: `Grant` is gone too — see
        `SPEC.md` §5.2.)
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
      (2026-08-22). The snooze *arms* honestly duration-only, by an explicit mode at the one
      call site; the monitor has since landed and consumes what was captured, raising the mode
      when capture completes.
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
      `ACCESS_BACKGROUND_LOCATION` in the `play` flavor's alone, pinned in both directions
      by `DeclaredPermissionsTest`, which runs per flavor variant. It pinned `INTERNET`'s
      absence too when this landed; since crash reporting (`SPEC.md` §12) it pins the split
      instead — `INTERNET` present on `play`, absent on `direct`. The `direct` flavor's foreground-service declarations wait for Phase 7's
      service; the in-app prompt and prominent disclosure are Phase 6's items and need copy.
- [ ] `PresenceMonitor` interface and `GeofencePresenceMonitor`, with everything above the
      interface flavor-agnostic (`SPEC.md` §6.1).
      **Both are built and wired** (audited 2026-08-25): the interface lives in `:core`, the
      `play` flavor implements it with `GeofencePresenceMonitor` and the `direct` flavor with
      `DurationOnlyPresenceMonitor`, and `SnoozeService` starts the monitor on arm and on
      restore, collects its reports, clamps the claimed mode to `supportedModes`, and stops it
      with the snooze. What keeps the box open is code, not just verification: the degradation
      *cause* stopping at the controller (needs approved copy **and** the plumbing to carry the
      cause to it) — plus the on-device verification the whole item is gated on. The
      mode-changed listener that re-registers the fence promptly when location services return
      has since landed (below), closing the recovery half, and so has the §6.7 significant-motion
      trigger — with the resting cadence it pairs with now settled as the backstop's on `play`
      (`SPEC.md` §6.7), so that item is closed too.
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
        confirmation step instead. `SANITY` duty mapped to nothing when this slice landed; the
        §6.10 periodic backstop has since arrived and drives the resting fix — at its own
        30-minute cadence, not §6.7's 10-minute one, which is still unscheduled (see the
        duty-cycle item below).
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
        actually ends — a background-limits service destroy leaves it watching. **Re-registering the fence promptly when
        location services come back on has landed** (Codex, PR #70:
        `GEOFENCE_NOT_AVAILABLE` at registration reports the §8.4 degradation correctly but
        nothing watched for the recovery). It was already *bounded* by the backstop — every
        restore re-registers, so the periodic wake healed an outage in roughly its cadence
        rather than leaving the snooze degraded to the cap — and promptness was the remaining
        value. `LocationModeWatch` (the lifecycle, over a `LocationModeRegistrar` seam) plus
        `PlatformLocationModeWatch` (the `MODE_CHANGED_ACTION` receiver, which also answers the live
        setting) now poke exactly what a
        warm backstop wake pokes — re-register the fence, take one resting fix — the moment the
        setting comes back on. The fence goes **unconditionally**, not gated on the
        registration level the way `RepairPoke` is (Codex, PR #139, second pass): that gate's
        premise is a live outage, which has provably ended here, and the case it hid is real —
        a `GEOFENCE_NOT_AVAILABLE` broadcast sets only the *services* level, so a fence the
        platform had stopped monitoring stayed unregistered until a backstop restore rebuilt
        it. Plus plus an immediate retry of a *running* burst, which the
        resting probe cannot cover (Codex, PR #139): an outage that begins during a
        departure check leaves the duty `ACTIVE`, where the probe is a declared no-op and
        three `ServicesOff` answers have already dropped the cadence to five minutes, so
        without it the one snooze that most needs answering would get nothing from the
        broadcast at all. `CheckingCadence.onPlatformRecovered` forgives a backoff the
        outage earned — the bound still applies from the recovery on, if the provider goes
        on failing — and `CheckingFixes.retryNow` asks now, leaving a request already in
        flight alone so one moment never spends two requests. Reconciled from the monitor's own `send`, the one choke point
        every platform level passes through, so it is armed while and only while there is an
        outage to recover from, and a healthy snooze registers nothing. **The broadcast is not
        sticky, so registering is not enough** (Codex, PR #139, third pass): an outage reported
        late — a `GEOFENCE_NOT_AVAILABLE` observation arriving after the user already fixed the
        setting is the ordinary case — leaves a mode change that has already happened and will
        never be re-delivered. The watch therefore samples `isLocationEnabled` once immediately
        after the handle is installed and treats "already on" exactly as it treats a broadcast.
        Registering *first* is what makes that a closed window rather than a smaller one, the
        same order and the same reason as `PlatformWifiWatch`'s initial read. It **decides nothing
        new**, deliberately: a successful re-registration still clears the registration level and
        only a delivered fix clears services-off, so a spurious firing costs one registration and
        one fix request and can never promote a snooze on nothing. Both classes live in
        `:presence`'s shared source set, like `MotionTrigger`, so Phase 7's `direct` monitor gets
        them without a second implementation.
        **The limit is the motion trigger's, and for the same underlying reason**:
        `MODE_CHANGED_ACTION` is an implicit broadcast and is *not* on the API 26+ exemption list
        (checked against the platform's own list, 2026-08-30), so there is no manifest form of it
        and nothing delivers it to a dead process. On `play` the watch therefore lives as long as
        the service does — covering the window where the app is actually running when the outage
        ends, which is the arm-with-location-off case and the user who reaches for the setting on
        the strength of the notification — and the backstop still covers the rest. On `direct`,
        Phase 7's foreground service makes it cover the whole snooze. Tests:
        `LocationModeWatchTest` (19) over a manual registrar, plus the burst half in
        `CheckingCadenceTest` and `CheckingFixesTest`; `SPEC.md` §6.10 records the
        decision. The recovery callback itself has no unit test — it lives inside
        `callbackFlow` and needs Android and Play Services, the same reason
        `CapabilityLossStore` is untested; only its pure helpers are reachable from
        `GeofencePresenceMonitorTest`.
      - **Two follow-ups this slice deliberately did not take, both recorded rather than
        guessed.**
        - **A snooze on the anchor's Wi-Fi still reports degraded after an outage ends, for up
          to the backstop's cadence** (Codex, PR #139, second pass). The duty is `NONE` there,
          so no fix is taken and the services level — which by design clears only on a
          delivered fix — stands. Codex asked for a one-shot fix that ignores the suppressor;
          declined, because `Presence.fixArrived` runs the §6.6 test on every fix whatever the
          association says, and §6.6's unambiguous shortcut ends a snooze on a *single* reading
          beyond radius + 500 m — so on a network spanning more ground than the anchor's radius
          a fix taken only to tidy a label could end the snooze outright. Over-reporting
          degradation is the safe direction; ending a snooze to correct the label is not. If the
          maintainer wants the label prompt too, the cheap version is a narrower one: clear the
          services level on the mode broadcast **only** when its origin was
          `CheckingFixes.onServicesOff`, which reads the identical `isLocationEnabled` the
          broadcast does — same proof, no fix, no D4 exception — and keep waiting for a fix when
          the origin was `GeofenceObservation.Unavailable`, where "location is on" says nothing
          about whether geofencing is.
        - **A Wi-Fi-only anchor can strand the registration level permanently.** Pre-existing,
          not introduced here: `deliver`'s recovery branch sets
          `registrationDegradation = LOCATION_SERVICES_OFF` and calls `repairOnRecovery()` on
          the fix that clears services-off, but `registerFence` early-returns for an anchor with
          no usable fix, so nothing ever clears what it just set and the snooze reports degraded
          until a restore rebuilds the monitor. One-line guard (`if (anchor.hasUsableFix)`
          around the mark-suspect), left out of this PR to keep it minimal — an anchor with no
          fence has no registration to be suspicious of. Also still to come is
        the on-device verification the whole item is gated on. The grace alarm for
        `graceDeadlineMs` landed with the Wi-Fi suppressor slice.
      - [x] **The grace deadline has to survive process death** (Codex, PR #31, re-flagged and
        partly mitigated on PR #77). `PresenceState` is in-memory only; a service killed after
        arming the five-minute grace alarm came back with no deadline, so the alarm's signal was
        ignored and the snooze ran to the cap — the silence the grace period exists to bound.
        PR #77's `PlatformWifiWatch` fix for the "started already disconnected" gap bounded the
        worst case for a Wi-Fi-only anchor: a cold restore explicitly re-reads the current
        association on registration, so a restore that lands still off the anchor's network
        re-delivers `AnchorWifiLost` — which, with no deadline remembered, re-armed a *fresh*
        five-minute grace from the restore moment rather than resuming the original one.
        **Landed** (maintainer, 2026-08-23): `GraceDeadlineStore` (new, `presence/.../geofence`)
        persists the deadline as a wall-clock instant whenever `GeofencePresenceMonitor` reconciles
        the platform alarm, and `PresenceFeed` gained an optional seed so a restore reads it back
        *before* the bridge's mailbox replay or the Wi-Fi watch's redelivery lands — both already
        do the right thing once the state isn't lying about being fresh: a `GraceElapsed` the
        mailbox held while the process was dead now correctly ends the snooze instead of being read
        as a stale alarm, and `Presence.graceFrom`'s `state.graceDeadlineMs ?: graceFrom(...)`
        preserves the resumed deadline instead of overwriting it with a new one. No new
        signal-delivery code was needed — the fix is entirely in what the fresh state believes at
        construction. The platform alarm is also explicitly re-armed on a restore that found a
        persisted deadline, closing the reboot gap (`AlarmManager` entries don't survive one).
        **Deliberately without `ActiveSnooze.bootReference`'s dual-frame defense** against a
        backwards clock change, unlike this note's own earlier suggestion to mirror it — wall time
        alone is stored, so the frame conversion is identical whether zero or several reboots
        happened since. The cap has nothing above it and needs that defense; the grace deadline is
        a soft mechanism the cap already backstops regardless, so a wound-back clock can make grace
        run long but never longer than the cap. Tests: `PresenceFeedTest` (the seed is honored, and
        a live redelivery doesn't overwrite it) and `GraceDeadlineStoreTest` (the frame arithmetic,
        including across a simulated reboot and an already-overdue restore).
        **Two ordering/identity gaps in the first pass, both caught by Codex on the PR and fixed
        before merge**: the save had to happen *before* `GraceAlarm.reconcile`, not after — a
        process death in between left a real armed alarm with a store that still said null, the
        same failure mode this item exists to close, just narrowed to one line's width. And the
        store needed the same identity check `ActiveSnooze.startedAt` already gives the record
        itself: a snooze that ends *during* grace and dies before `stop()`'s clear runs left a
        leftover deadline that a completely unrelated, later snooze would otherwise inherit and
        could end early. `GraceDeadlineStore` now keys entries to the arm moment
        (`PresenceFeed`'s own seed) and ignores a mismatch.
        **A second Codex pass on the same head found three more, all fixed before merge**: (1)
        clearing the store the instant `feed.accept` produced `Departed` was itself the bug this
        item exists to close, just moved — `send` only *queues* the event, so a process death
        before the collector actually ends the snooze and erases the record found no deadline to
        make sense of the bridge's still-retained `GraceElapsed` replay; skipped now whenever the
        event is `Departed`, leaving `stop()` as the sole clearer once the end is confirmed. (2) the
        arm-moment identity used `sinceElapsedRealtimeMs` translated through the same wall-clock
        arithmetic as the deadline — stable within one process, but the same snooze could compare
        unequal to itself after a reboot or a mid-snooze clock-change restatement, since both shift
        that translation. `PresenceMonitor.start` gained an `armedAtEpochMs` parameter —
        `ActiveSnooze.startedAt`, compared directly with no arithmetic of its own, the one value the
        record itself already trusts as identity for the same reason (`retryStillApplies`). (3) the
        deadline's save and the alarm's reconcile ran outside `feedLock`, so two `deliver` calls on
        different platform callback threads could finish their feed transitions in one order (correctly
        serialized by the lock) and their persistence in the other — stranding a stale deadline. A
        sequence number taken inside `feedLock` and checked before either side effect runs is what
        keeps only the newest transition's belief on disk, without widening the lock to cover a binder
        call and a disk write.
        **A third pass found two more, both fixed before merge**: (1) the save used `apply()`, which
        returns before the write is durable — ordering it before `GraceAlarm.reconcile` proved
        nothing on its own, since a kill between the (unconfirmed) write and the alarm arming was
        the same gap moved rather than closed. `save`/`clear` now use `commit()` and return whether
        the write actually landed; the alarm is only armed on a confirmed save, and a failed one logs
        and leaves that signal ungraced rather than pretend it took (the duration cap alone bounds it
        from there, same as before this PR). (2) the persistence decision — that the deadline
        survives a restart at all, resuming rather than restarting the countdown, and deliberately
        without the cap's clock-tamper defense — was recorded only here, while `SPEC.md` §6.6 still
        described just the platform alarm. Added to §6.6 alongside the existing grace-period
        discussion.
        **A fourth pass found two more, both fixed before merge**: (1) `PresenceFeed`'s seed claimed
        `atAnchorWifi = true` (from the anchor's own recorded SSID) *alongside* a restored non-null
        grace deadline — an internally impossible combination, since only a Wi-Fi loss ever arms one.
        `Presence.associated`'s duplicate guard then silently swallowed the one signal that should
        have cleared it: a genuine return to the anchor's Wi-Fi during the outage read as a repeat of
        what the seed already claimed. `atAnchorWifi` is now seeded false whenever a restored deadline
        is present. (2) even with that fixed, the bridge's mailbox could still replay a held
        `GraceElapsed` and resolve the snooze as `Departed` before the Wi-Fi watch's own synchronous,
        constructor-time check of the *current* association ran — `PresenceState.resolved` ignores
        every signal after, so the live evidence arrived too late to matter. The watch is now
        constructed before the bridge attaches, so a genuine return to Wi-Fi is processed as a real
        transition first. A fifth finding in the same pass, unrelated to correctness: the durable
        `commit()` from the third pass runs synchronously, and `deliver` is reached from main-thread
        platform callbacks (`PlatformWifiWatch`'s in particular) for the life of a snooze, not only on
        the arm path `AGENTS.md` singles out — moved onto a single serialized `Dispatchers.IO` worker,
        submitted from inside the same lock that already orders the writes against each other, so
        submission order and execution order stay the same.
        **A sixth pass found two more, both fixed before merge**: (1) the fourth pass's own fix
        reopened the third pass's durability guarantee — `producer.launch` does not block its caller,
        so the calling callback could return, and the process could be reclaimed, before the launched
        `commit()` ever actually ran, recreating the exact process-death gap this whole item exists to
        close. Reverted to a direct, synchronous call: `AGENTS.md`'s own principle order puts
        never-fail-silently above don't-make-the-user-wait when the two genuinely conflict, and
        `ActiveSnoozeStore.save` already makes this same trade on the arm and release paths for the
        same reason — a two-`Long` write is not the kind of work principle 5 is guarding against. (2)
        a failed *setting* write (as opposed to a failed clear) left that signal merely "ungraced," on
        the assumption a later signal's own save would eventually succeed — but an anchor already off
        its Wi-Fi with no usable fix has location duty `NONE` and may never produce another signal, so
        nothing would ever retry and the snooze could silently run to the cap instead of ending in five
        minutes. A failed setting write now ends the snooze via `PresenceEvent.CapabilityLost` /
        `MONITORING_UNAVAILABLE`, the existing fail-open path; a failed *clearing* write still only
        logs, since that failure follows good news (presence evidence already cleared grace) and
        ending the snooze there would be a punitive overreaction to an unrelated write failure.
        **A seventh pass raised the other side of the sixth pass's own trade-off**: the revert to a
        synchronous `commit()` runs on whichever thread `deliver` is called from — often the main
        thread — on *every* presence signal, not only when the grace deadline actually changes;
        during a check burst's fixes that is a disk write per fix for no reason, which is the real
        main-thread cost, not the commit call itself. Fixed by tracking what the store is confirmed
        to hold (seeded from the restore read, since a restored deadline is already durable) and
        skipping the whole write when the current deadline already matches it — updated only on a
        *confirmed* save, never optimistically, so a failed write still retries on the next signal
        carrying the same value instead of being wrongly believed to have landed. Real transitions —
        Wi-Fi lost, Wi-Fi back, a restore — are rare and still write synchronously, so this closes the
        frequency complaint without touching the durability guarantee the sixth pass fixed, and
        without the larger, riskier change (moving the platform callbacks themselves off the main
        thread) Codex's literal suggestion implied.
        **An eighth pass found two more, both fixed before merge**: (1) the second pass's own fix —
        skip persistence entirely for the `deliver` call that produced `Departed` — only covered that
        one call. `send` still only queues the event, and a *later* signal delivered before the
        collector consumes it and calls `stop()` sees the engine's own already-resolved,
        grace-cleared state (`PresenceState.resolved`) and would clear the durable deadline itself —
        stranding the bridge's retained `GraceElapsed` exactly as the second pass meant to prevent,
        just reachable from a different call. Fixed with a latch (`departedObserved`) set the moment
        any call sees `Departed` and checked by every call after, so the skip now covers the whole
        generation, not just the one delivery. (2) `persistenceLock`, its sequence counter, and the
        "what's already durable" tracker were all local to one `start()`'s `callbackFlow` — no lock
        ordered them against a *different* generation's, so a rapid end-and-rearm could leave an old,
        still-in-flight `deliver` call free to write after a new snooze had already started, clobbering
        the current snooze's identity in the single `SharedPreferences` file every generation shares
        and potentially rearming or canceling the wrong platform alarm. Moved all of it onto the
        instance, gated by a `persistenceGeneration` claimed at the start of every `start()` (and
        retired by `stop()`) under the same lock every write checks — a call from a superseded
        generation now finds no generation to match and writes nothing, full stop, rather than relying
        only on the identity check already in `GraceDeadlineStore` to catch it after the fact.
        **A ninth pass found a genuine gap left unfixed in this PR, flagged for the maintainer rather
        than resolved unilaterally**: the sixth pass's fail-open (`trySend(CapabilityLost(...))` on a
        failed grace-deadline write) is itself only an in-process `Flow` send — if the process dies
        between that `trySend` and `SnoozeService`'s collector actually acting on it, the ending
        decision is lost and a restore starts fresh believing the snooze is still healthy. Correctly
        diagnosed, but not new or unique to grace: `reportRegistration`'s `Fatal` branch and
        `onPermissionLost`'s mapping already end a snooze the identical way, unguarded, since PR
        #70/#72/#75. Making the whole class durable through this window is a systemic redesign, not
        something to invent inside a PR scoped to grace-deadline persistence — flagged in chat and
        left unresolved on the PR thread. **Maintainer decision (2026-08-23): make it durable.**
        Follow-up tracked below.
      - [x] **Make every `CapabilityLost` ending in `GeofencePresenceMonitor` durable through both a
        process death and a reboot** (maintainer decision, 2026-08-23, following the ninth-pass flag
        on PR #91 above). Two call sites end a snooze via a bare `trySend` — `reportRegistration`'s
        `Fatal` branch (which `onPermissionLost` also reaches, by delegating to it) and the grace-
        deadline write-failure branch — neither durable through the window between the send and
        `SnoozeService`'s collector actually consuming it.
        **Landed** (2026-08-23): mirrors the grace mechanism this file already hardens rather than
        folding into `ActiveSnoozeStore` or an alarm-only fix. New `CapabilityLossStore` persists the
        decided cause, keyed like `GraceDeadlineStore` to `armedAtEpochMs`, written with `commit()`;
        new `CapabilityLossAlarm` arms an immediate (`setAndAllowWhileIdle` at "now") platform alarm as
        the actual wake mechanism — a persisted decision alone does nothing if nothing is scheduled to
        act on it, and only the alarm (and, past a reboot, the durable record it wakes into) survives
        the way `GraceAlarm` already does for grace. Both call sites now funnel through one new
        `failCapability(cause)`: save, arm on a confirmed save, send — gated by the same
        `persistenceGeneration` this file's grace persistence already uses, so a stale generation's
        late failure callback cannot write over a fresher snooze's decision. `start()` reads the store
        directly on every restore and ends immediately if a decision is already recorded, independent
        of whether the alarm ever fires; the rest of setup still runs and is torn down once `stop()`
        follows — kept simple rather than restructuring the setup path to skip it, matching this file's
        established bar for how much engineering effort a rare fail-open path earns. `stop()` clears the
        record and cancels the alarm, under the same generation-retirement `-1L` sentinel the grace
        store's clear already uses. `GeofenceObservation` gained a `CapabilityLoss` case (no cause
        aboard — the monitor re-reads the store keyed to its own `armedAtEpochMs`, so a stale alarm
        firing from a superseded snooze is a no-op) ranked *below* both `Exit` and `GraceElapsed` in
        `GeofenceSignalBridge` (corrected in the second Codex pass below — the first version had this
        backwards). Tests: `GeofenceSignalBridgeTest` (the new observation's ranking and retention).
        `CapabilityLossStore` itself is untested at the unit level — like `GraceDeadlineStore`'s own
        `save`/`load`, it needs a real `Context` that `:presence`'s JVM test source set has no harness
        for; unlike `GraceDeadlineStore` there is no pure arithmetic to extract a dedicated test around,
        since a cause carries no clock frame to convert.
        **A first Codex pass on PR #95 found two more, both fixed before merge**: (1) `failCapability`'s
        generation check and its actual store write were two separate `synchronized` blocks — checking,
        then writing outside the lock, left a window where `stop()` could retire the generation and
        clear the store, or a replacement monitor could claim the generation and persist its own loss,
        between the check and the write; a callback resuming after either would then overwrite the
        current, correct entry with a decision belonging to a snooze already over. Fixed by holding the
        lock across the check, the store write, and the alarm arm together, the same shape the grace
        deadline's own persistence already uses. (2) the new `CapabilityLoss` bridge observation is
        retained like an exit or a due grace deadline, but unlike either it never reaches `deliver`, so
        `settlesHeldExit`'s own retirement never runs for it — a stale firing (the record it named
        already cleared, by a confirmed `stop()` or a superseded generation) just logged and left the
        slot occupied, which would replay to every later attach and wake every future teardown for a
        decision that no longer exists. Fixed by retiring the slot explicitly (`GeofenceSignalBridge.settleExit()`)
        whenever the keyed store load comes back empty.
        **A second Codex pass found the ranking itself was backwards, fixed before merge**: the first
        version put `CapabilityLoss` *above* `Exit` in `rank()`, on the reasoning that an ending already
        decided should win the one mailbox slot — but that reasoning only holds once the store confirms
        the prompt is real, and `rank()` runs before any monitor ever checks it. A canceled alarm firing
        late (or one from an already-superseded generation) could win the slot on delivery alone and
        discard a real, retained `Exit` that had nothing else backing it up — the departure evidence
        would be gone, unrecoverable, before any monitor ever saw it. `CapabilityLoss` now ranks *below*
        both `Exit` and `GraceElapsed`: its own payload is separately durable in `CapabilityLossStore`
        and re-checked unconditionally on every restore, so losing the slot to genuine departure evidence
        costs it nothing, while the reverse would have cost everything.
        **A third pass found the first pass's own fix had reintroduced exactly the bug the second pass
        had just closed, fixed before merge**: the empty-load branch called `GeofenceSignalBridge.settleExit()`
        to retire a stale capability-loss prompt, but `settleExit()` clears *whatever* is currently
        retained, not specifically the prompt being handled — with the second pass's ranking fix in
        place, that occupant can now legitimately be a genuine, still-unconfirmed exit or due grace that
        `rank()` correctly kept over the stale prompt, and an unconditional settle would discard it
        anyway, one call later than the ranking bug did. New `GeofenceSignalBridge.settleCapabilityLoss()`
        clears the slot only when it is still actually occupied by a `CapabilityLoss`, leaving any other
        retained observation alone; harmless to skip when a fresher `CapabilityLoss` has since taken the
        slot instead, since (unlike an exit or a grace deadline) its own payload lives in the store, not
        the observation, so a redundant prompt left behind costs nothing a restore's own unconditional
        read wouldn't already catch. Test: `GeofenceSignalBridgeTest` (settling a stale capability loss
        never clears a genuinely retained exit).
        **A fourth pass found a real gap left over from the original design, fixed before merge**: the
        live-delivery success branch (a genuine cause found in the store) consumed the one-shot alarm
        that had just fired and only queued another `trySend` — the exact kind of unacknowledged
        in-process handoff this whole item exists to close. A process death between that `trySend` and
        `SnoozeService`'s collector consuming it would leave the record still on disk with nothing left
        scheduled to prompt another restore. The restore-time replay at the top of `start()` already
        re-armed in this situation, though (as the fifth pass below caught) with its own order backwards
        — this pass fixed the live-delivery path by adding the re-arm before its `trySend`.
        **A fifth pass caught that the restore-time replay's own re-arm was in the wrong order, fixed
        before merge**: it read `trySend` first and `CapabilityLossAlarm.arm` second — a process death
        between the two left the same gap the fourth pass had just closed on the live-delivery path,
        just on this one instead. Swapped so both replay paths now arm before they send.
        **A sixth pass raised two more; one fixed before merge, one declined with reasoning**: (1)
        `CapabilityLossStore.load` fell back to `null` — "no decision recorded" — for a persisted
        cause name this build doesn't recognize (e.g. after a rollback past a build that added a new
        one), even though the identity check just above it had already matched this exact snooze. That
        reads as a healthy snooze and leaves DND on until the duration cap — principle 1's exact
        failure, since the ending decision was genuinely made, only its detail became unreadable.
        Fixed: an unrecognized name now falls back to `CapabilityLossCause.MONITORING_UNAVAILABLE`
        rather than to absence. (2) Codex asked for unit/Robolectric coverage of the production
        save → restore → alarm → clear lifecycle itself, on the grounds that the tests added so far
        only inject in-memory bridge observations and would stay green even if the real persistence or
        alarm wiring were removed. Declined on the thread rather than fixed: `:presence`'s `testPlay`
        source set deliberately carries no Robolectric (SPEC.md's module split, `presence/build.gradle.kts`),
        and this exact class of coverage was already weighed and accepted as out of reach for the
        sibling mechanism — `GraceDeadlineStoreTest`'s own KDoc documents the same trade-off, reviewed
        by Codex on PR #91's second pass without further challenge, and `GraceAlarm` (the grace
        mechanism's own durable wake-up) has no test coverage anywhere in the repository today.
        `CapabilityLossStore` has *less* to unit-test than `GraceDeadlineStore`, not more — no clock
        frame to convert, which is the one piece `GraceDeadlineStoreTest` pins. Adding Robolectric to
        `:presence` to close this would be a real infrastructure decision, not a same-PR fix, so it
        stays a call for the maintainer rather than something to invent unilaterally here.
      - [x] **The degradation *cause* stops at the controller** (Codex, PR #31). `Presence` now
        tells `FIXES_TOO_VAGUE` from `NO_LOCATION_FIX`, and `SnoozeController` maps both to the
        same `TrackingMode`, which is all `SnoozeService.onTrackingChanged` renders from — so the
        notification says the same thing either way and the distinction never reaches the user.
        Fixing it means new user-facing copy, which needs the propose-in-chat-and-approve step
        (`AGENTS.md`, *Translations*), so it is a follow-up rather than part of the engine PR.
        Despite this item's own earlier note, **the two halves did not land together**: the mode
        half below landed first, and this cause-distinction half followed on 2026-08-30.
        **Landed**: the copy was proposed in chat and approved by the maintainer — `location is
        off` / `no location` / `weak location signal`, the third reworded from two rejected
        drafts (`no signal here` reads as cellular bars; `poor location here` reads as a
        judgement about where the user is standing). `ActiveSnooze` carries the cause beside the
        mode, so a restore reposts the reason rather than coming back half-told;
        `ActiveSnoozeStore` persists it, reading an unrecognized or absent value as "no reason"
        rather than refusing the record; `SnoozeController.onPresenceUpdate` now treats a *cause*
        change as news even when the mode holds — without that the card would assert the wrong
        reason until the mode happened to move; and `SnoozeNotifications` appends it.
        **Two causes deliberately got no line**: `NO_LOCATION_IN_BACKGROUND` needs the `Resume
        tracking` affordance that no UI offers yet, and naming a state without its way out is
        worse than the mode alone; `NOTHING_WATCHING` is the app's own wiring, which `Timer only`
        already describes. **The first of those was reversed on 2026-08-30** (maintainer): naming
        the missing permission — `background location off` — is itself most of the way out, since
        it tells the user what to grant where `Timer only` alone tells them nothing. Only
        `NOTHING_WATCHING` stays silent now. `WIFI_GRACE` is excluded as a mode, since its own string already names
        what matters. `SPEC.md` §4.3 records all of it.
        **One trade-off taken knowingly**: an existing test asserted that a cause change under one
        mode reported nothing, to stop the record and card being rewritten on every update. That
        assertion bundled two cases — restating the *same* level, which still reposts nothing, and
        a genuinely different cause, which now does. In marginal conditions the cause can
        alternate between `NO_LOCATION_FIX` and `FIXES_TOO_VAGUE`, so the card can update on that
        cadence; the repost is silent (`setOnlyAlertOnce`) and costs a preferences write and a
        tile refresh, which is the price of the line not lying.
        **And the engine had to change with it** (Codex, PR #141): `Presence.useless` froze the
        cause at whichever flavor first crossed the threshold, so the controller's new comparison
        was unreachable through the engine path and the card could say `no location` indefinitely
        while vague fixes were in fact arriving. That freeze was PR #31's deliberate anti-flapping
        call, made on the reasoning that both causes "say the same thing to the user" — which this
        change is precisely what falsifies. The cause now follows the failures; `nowDegraded` keeps
        its old meaning so the §6.6 grace deadline still arms once. `SPEC.md` §6.1 records the
        reversal, and `failures that alternate do not flap the level` was rewritten to assert the
        new value sequence while keeping the half that did not change — a level, never an event.
        **And the app screen now says it too** (**landed 2026-08-30**, the follow-up this
        deferral named). `MainScreen` rendered the mode from `TrackingMode` alone, so the screen
        and the notification could describe one snooze two ways. `MainScreen` takes the record's
        `degradation` and joins it to the mode from the same mapping the notification reads
        (`degradationReasonRes`, shared rather than copied, so the two cannot drift). No default
        on the new parameter, deliberately: forgetting it would silently drop the reason, which is
        the failure the line exists to prevent, so every caller has to state it.
        - [x] **A restart promoted a degraded snooze back to full before it re-derived the
          problem** (Codex, PR #141; **landed 2026-08-30** in the follow-up PR that deferral
          named). After process death — the common case on `play`, which runs no foreground
          service — the monitor built a fresh `PresenceFeed` whose degradation started null,
          with both platform slots null beside it, so its first update read as a recovery
          nothing observed and the restored `Timer only — no location` became a plain
          `Snoozing` moments after the card was reposted. Worse than a moment's optimism,
          because the engine infers `NO_LOCATION_FIX` by *counting* misses and a fresh feed's
          count starts at zero: re-deriving the truth cost a fresh run of failures, not one
          probe. The mode half predated PR #141 — `modeFor` maps a null cause to the anchor's
          own capability — so both were fixed together.
          **What landed**: `PresenceMonitor.start` carries the record's cause; the geofence
          monitor routes it by slot under PR #75's refutation rule; a seeded feed takes **the
          restart moment** as its staleness floor and resumes with the failure threshold
          already crossed. The floor forced `latestEvidenceMs` (still the arm moment, or a
          held geofence exit is dropped — PR #73) and `lastUnusableAtMs` (the restart) apart,
          which in turn moved SPEC's "evidence of health must be newer than the failure" test
          onto the fresh-fix path in `Presence.fixArrived`, where it had never been enforced.
          `SPEC.md` §6.1 records all of it.
          - [ ] **A services outage still loses its reason across a restart** (Codex, PR #142;
            maintainer, 2026-08-30: out of scope, the happy path is what matters). Only the
            engine's own inferences are resumed. `LOCATION_SERVICES_OFF` is not: the record
            carries the cause without its origin, and it reaches both platform slots, so a
            resumed one can claim neither refutation. Three attempts inside PR #142 each
            overstated — the registration slot and a clear-on-registration both let
            `addGeofences` promote to healthy though it can accept a fence the platform cannot
            monitor, and the services slot is cleared by the first `FixArrived` in `deliver`,
            cached or not. Today's behavior is `main`'s: the reason is lost, the engine
            re-derives one, the watch is unaffected. A real fix needs the slot origin persisted
            (a durable field with the grace deadline's epoch-frame problem) *and* the restart
            floor on that `deliver` clear.
        - [x] **The *mode* was wrong too, during the grace period** (Codex, PR #31; landed
          2026-08-23). `modeFor` picked `WIFI_ONLY` whenever the anchor *had* an SSID, so while
          the grace period ran — Wi-Fi gone, location vague — the notification claimed Wi-Fi was
          tracking a snooze that nothing was tracking. Fixed with a new `TrackingMode.WIFI_GRACE`
          (`Wi-Fi lost — ending soon`, approved by the maintainer over `Wi-Fi changed — ending
          soon` — "lost" names the actual trigger, `AnchorWifiLost`) and a new
          `PresenceUpdate.graceActive` level, restated every update like `degradation` for the
          same PR #33 ordering reason rather than announced once. Checked *ahead of* `degradation`
          in `modeFor`, because grace can start (for a Wi-Fi-only anchor) before enough failed
          observations have accumulated for `degradation` to move off null. Not a rung of its own
          in `SnoozeController.honest()`'s degrade-walk — no real monitor's `supportedModes()`
          ever names it, so it stands or falls with `WIFI_ONLY`'s own support, or it would degrade
          straight to `DURATION_ONLY` for want of an entry nothing was ever going to add.
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
      *watching* and nothing consumed the anchor when this landed. The monitor wiring has
      since arrived and raises the mode once capture completes. Still owed a
      handset: that the flag plus our permissions yields a real SSID end to end (the §6.4
      item below), and what an indoor fix's accuracy actually is.
- [ ] **Register the Wi-Fi callback with `FLAG_INCLUDE_LOCATION_INFO`** (`SPEC.md` §6.4),
      and assert on a real device that the SSID comes back as an SSID. Without the flag a
      `NetworkCallback` requests no location-sensitive data, so `WifiInfo` arrives redacted
      — `UNKNOWN_SSID` and `02:00:00:00:00:00` — no matter which permissions are held
      (Codex, PR #24). This breaks the **SSID anchor**, not just the BSSID, and it fails
      quietly: real objects, plausible strings, an anchor that matches nothing. Any test
      here must reject the placeholders rather than accept them as values.
      **The code half landed**: both callbacks that read an SSID pass the flag
      (`AnchorCaptureRunner`, `PlatformWifiWatch`), and `AnchorCapture` rejects the two
      placeholder values by name rather than treating them as an anchor, with
      `AnchorCaptureTest` pinning that. What is left is only the on-device assertion, which
      the audit found was gated on hardware but missing from the hardware-verification list —
      added there as item 16, under tuning: a redaction that gets through costs the Wi-Fi
      capability through the tracked-degradation path, not silently.
- [x] Three independent wake-up sources feeding one confirmation test (`SPEC.md` §6.10):
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
      - **Ticked 2026-08-25** on an audit of what actually shipped, not on new work: all
        three sources are built, wired into `SnoozeService`, and feeding the one §6.6
        confirmation test. Nothing under §6.10 itself is unbuilt — `TYPE_SIGNIFICANT_MOTION`
        is *not* a fourth wake-up source. But motion is not therefore all deferred: it has two
        separate uses, and only one is a product decision. As an explicit end condition
        (`until I move`) it sits in Phase 6's fallback table, gated on hardware item 2. As a
        §6.7 duty-cycle input it is v1 spec, and that half has since been built and closed —
        its own item below. The
        residuals here stay residuals: best-effort bounds the design accepts, each with its
        own recorded slice.
- [x] **The significant-motion trigger that drives the §6.7 duty cycle** (found by Codex on
      the 2026-08-25 status audit). `Presence` already handles `PresenceSignal.SignificantMotion`
      — suppressed while associated with the anchor's Wi-Fi, escalating to `CHECKING` otherwise —
      and `PresenceTest` covers it, but **nothing produces the signal**: there is no
      `SensorManager.requestTriggerSensor` anywhere in the app, so that branch is dead code.
      The cost is not a missed departure but a slow one: for a coordinate anchor off its Wi-Fi,
      §6.7 says motion is what escalates the 10-minute sanity poll to the 90 s active request,
      so with the trigger absent a geofence exit that is late or dropped waits for the 30-minute
      backstop instead. `TYPE_SIGNIFICANT_MOTION` needs no permission and is a hardware-backed
      one-shot, so this is battery-cheap by design (`SPEC.md` §9). Re-arm the trigger after each
      firing, and suppress it while associated the way the engine already does.
      **The trigger half landed, and covers less than it looks like on `play`** (2026-08-25;
      the limit found by Codex on PR #119). `requestTriggerSensor` registers an in-process
      listener and there is no `PendingIntent` form of it, so the trigger dies with the service
      — which on `play` means it is armed for roughly the minute after each wake rather than for
      the snooze. During that minute a phone picked up escalates immediately instead of waiting
      up to 30 minutes for the next backstop; outside it, nothing changes. That is a real but
      narrow win, and it is close to the argument `SPEC.md` §7 already makes against an
      in-process cap timer — "it dies with the process, so it would only ever cover cases the
      alarm already covers, while making the alarm look optional". It is not quite that, because
      it *does* beat the backstop inside its window, but the shape is the same and the commit
      subject was rewritten to stop promising more.
      **The durable version is a product decision, not more engineering.** Nothing in the
      platform delivers significant motion to a dead process. The one mechanism that would is
      Play Services' Activity Recognition transition API, which takes a `PendingIntent` — and
      needs the `ACTIVITY_RECOGNITION` runtime permission, a new Data Safety answer, and a
      second `play`-only dependency. That is a distribution decision (`AGENTS.md`, *Cost and
      reliability*), so it is recorded here rather than guessed. On `direct`, Phase 7's
      foreground service keeps the process resident and the trigger as written works as §6.7
      intends, with no new permission — which is why both classes live in the shared source set.
      **What landed:** `MotionTrigger` owns the lifecycle over a
      `TriggerRegistrar` seam — armed exactly while the duty is `SANITY`, idempotent because the
      engine restates the duty on every update, and re-armed after every firing, since a trigger
      sensor disarms itself when it fires. `PlatformMotionTrigger` is the `SensorManager` half.
      Both live in `:presence`'s shared source set rather than the `play` flavor's, so Phase 7's
      `direct` monitor gets them without a second implementation. A device with no such sensor,
      or a platform that refuses, is recorded once and never re-asked — hardware cannot appear
      mid-snooze — and said in the log, because a snooze escalating only on the backstop's
      cadence is a real difference a stuck-snooze report has to explain. Tests:
      `MotionTriggerTest` (9), over a manual registrar.
      **The 10-minute resting poll: decided — `play` does not get one** (maintainer, 2026-08-30).
      The resting cadence on `play` is the §6.10 backstop's (~30 min); the 10-minute figure stays
      with `direct`'s foreground service from Phase 7. `SPEC.md` §6.7 records it with the reasoning.
      The third option below — schedule the alarm only where the motion trigger is unavailable —
      was explicitly declined: the sensor is near-universal, so the alarm would exist for a rare
      device while complicating the duty cycle for every device, and such a device still has the
      geofence and the Wi-Fi watch untouched. **Nothing left to build here** — `play` already
      behaves this way, since nothing ever scheduled the poll; what changed is that the spec now
      says so on purpose rather than by omission. The discussion that led there:
      §6.7 pairs the armed trigger with a 10-minute sanity poll and nothing schedules one:
      `CheckingFixes.sanityCheck()` runs only on a transition into `SANITY` or on a `SanityPoke`,
      and the only poke source is the 30-minute `SnoozeBackstop`. The reason this is not a
      straightforward fix is that **§6.7 was written for the foreground-service design**, where
      the process stays alive and an in-process 10-minute timer is exactly right — which is
      `direct`'s Phase 7 shape, not `play`'s. On `play` there is no foreground service, the
      process is reclaimed within about a minute of each wake, so an in-process timer would
      almost never fire, and the only mechanism that would actually deliver a 10-minute cadence
      is a repeating alarm — roughly **6 wakes per hour against the backstop's 2**, each one a
      service start and a location request, which is a real change to §9's budget for a snooze
      that can run eight hours. The same reasoning already recorded for one-shot fixes over
      §6.5's continuous request applies here. Three options, none of them mine to pick:
      - **Amend §6.7** to say the resting cadence is the backstop's on `play`, with the
        10-minute figure applying to `direct`'s foreground service from Phase 7. Cheapest, and
        arguably what the spec already means; costs a documented slower resting cadence.
      - **Add the alarm** and take the battery cost, honestly stated in §9.
      - **Split by capability**: schedule the resting alarm only where the motion trigger is
        unavailable, since covering for a failed trigger is what the poll is for. Costs nothing
        on the devices that have the sensor, which is nearly all of them.
      **Distinct from the deferred motion work** in Phase 6: that item is motion as an explicit
      *end condition* (`until I move`), a product decision gated on hardware item 2. This one is
      motion as a duty-cycle input, which `SPEC.md` §6.7 specifies for v1 — the audit conflated
      the two before Codex separated them.
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
      - The decisions around the test — escalation, the duty cycle (§6.7), the degraded
        report, the grace period — landed as `Presence`, and the platform monitors that
        *deliver* signals have since landed too: all three §6.10 wake-up sources feed it,
        per the item below. This bullet read "still owed" until the 2026-08-25 audit.
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
      2026-08-11), on-device, the current run plus a few recent ones, rotated at start, in
      `cacheDir`. The floor is absolute and needs a test of its own: **no raw coordinates,
      no full SSID/BSSID, no user-typed place name** ever reach it.
      **Landed as the recording half**: `SnoozeDebugLog` in `:core` (bounded buffer, sinks, real
      timestamps with zone offset, the floor test) and, since the retirement, the shared
      `DebugFileSink` from `mikelward/androidlog` (§4.6 rotation, a crashed run set aside under
      its own name rather than holding a single `previous` slot, off-deletes-everything), wired to the
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

- [ ] **Round the end-condition seed to 15 minutes on epoch millis, not 30 on the wall
      clock.** Open question raised by the maintainer (2026-08-28), not yet decided.

      `EndCondition.roundToHalfHour` rounds the seeded end time on the **local wall
      clock**, and its stated reason is that a 30-minute grid over epoch millis only
      lines up with the user's own :00 and :30 in zones whose offset is a whole half
      hour — the three-quarter-hour zones break. That reason is correct, and it is what
      forces the domain layer to resolve daylight-saving gaps and overlaps
      (`zone.rules.getValidOffsets`, the fall-back test) for what is ultimately a
      cosmetic nicety: a suggested time that reads as `2:30` rather than `2:17`.

      A 15-minute grid appears to dissolve it. Measured against the JDK's tz database —
      every zone's current offset plus its next four transitions — all 40 distinct
      offsets are whole multiples of 15 minutes, and exactly four are not multiples of
      30: `+05:45` (Nepal), `+08:45` (Eucla), `+12:45` and `+13:45` (Chatham). So a
      15-minute grid laid over epoch millis lands on local :00/:15/:30/:45 in every
      zone, and the rounding becomes a pure `Instant` → `Instant` operation with no
      `ZoneId` argument at all.

      What that would delete: the wall-clock round trip and the gap/overlap resolution
      with its `getValidOffsets` candidate search. The sub-minute truncation goes too —
      rounding millis to the nearest 900,000 handles `13:59:59.9` without a special
      case. The floor/ceiling clamping is untouched.

      What would **not** be deleted is the `seeds forward through a daylight-saving
      fall-back` test (Codex on PR #127). A tap inside the repeated hour is a real
      clock-change case whatever the rounding does, and what that test asserts — that
      the user is offered a future time near the intended one, not a past or clamped
      one — has to keep holding. Its expected seed changes with the grid; the test
      itself is retargeted, not dropped.

      What it would change for the user: seeds land on quarter hours instead of half
      hours. `SEED_AHEAD` is one hour (`EndCondition.kt`, `SPEC.md` §4.4), and a
      quarter-hour grid lands nearer to it than a half-hour one does — a tap at 13:12
      would offer 14:15 rather than 14:00, three minutes off the intended hour instead
      of twelve.

      That cuts both ways, and the counter-argument is the stronger half (Codex on
      PR #127). `STEP` stays 30 minutes, so a seed landing on `:15` or `:45` puts every
      reachable time on that grid — 13:45, 14:15, 14:45 — and the user can no longer
      `−`/`+` their way to 14:00 or 14:30 at all. Common meeting endpoints become
      unreachable from a seed that a quarter-hour grid made *nearer* to the target.
      Taking the change therefore means either accepting that, or moving `STEP` to 15
      minutes and doubling the taps to cross an hour. Either way it is a visible change
      to the sheet and so a product call.

      The one caveat worth stating: 15-minute alignment is a property of the current tz
      database, not a guarantee of it. No zone has used an offset off a 15-minute
      boundary since 1979 — Kiritimati sat at `-10:40` until 1979-10-01, later than
      Liberia's `-00:44:30`, which ended in 1972. If one ever did, the seed would be
      cosmetically ragged there — never unsafe, since the cap is epoch arithmetic and
      unaffected either way.

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
- [ ] **Screen-navigation lag: the previous screen visibly ghosts behind the new one for about a
      second when switching between `MainScreen`/`SettingsScreen`/`PermissionsScreen`** (user
      report, 2026-08-23). Still not reproduced — no emulator or device in this sandbox. Two rounds
      of investigation have ruled out several *specific* mechanisms (Codex, PR #94: this is a
      correction, not a claim that every software cause is closed — recomposition cost, layout
      cost, cold-JIT paths, and in-budget wrong-pixel rendering are all still open below):
      - No animation or layer-caching API is used anywhere in the app at all —
        `Crossfade`/`AnimatedContent`/`AnimatedVisibility`/`updateTransition`/`animate*AsState`/
        `graphicsLayer`/`drawWithCache`/`RenderEffect` all return zero matches repo-wide (checked
        by grep across every `.kt` file, not just `:app`'s `ui` package). `MainActivity` switches
        screens with a plain, unanimated `when (screen)` inside one `Surface`; none of the three
        screens or `SnoozemoTheme` do I/O or a blocking call in composition that could stall the
        main thread long enough to leave a stale frame on screen — `SnoozemoTheme`'s own
        `remember`-less `ColorScheme` rebuild (real, but only fires when dark mode itself changes,
        never on a screen switch) was found and fixed in PR #93 regardless.
      - **Predictive back is not the cause, and this is no longer a guess.** Read the actual
        `androidx.activity:activity-compose:1.13.0` sources (`BackHandler.kt`,
        `internal/BackHandlerCompat.kt`, `internal/BackHandlerDispatcherCompat.kt`, pulled from
        `maven.google.com` rather than assumed): `BackHandler` *does* register through the new
        `NavigationEventDispatcher`, which does participate in the platform's predictive-back
        protocol — but `BackHandlerCompat.onBackStarted`/`onBackProgressed` are no-ops unless a
        subclass overrides them, and `ComposeBackHandler` (what `BackHandler` actually
        instantiates) only overrides `onBackCompleted`. A callback that intercepts back this way
        keeps the current `Activity` from ever finishing, and the system draws no preview of its
        own for a back press an app callback fully owns — the predictive-back *scale-and-reveal*
        chrome is a courtesy the app opts into by implementing the progress callbacks itself, not
        something the platform imposes on an intercepting callback that ignores them. So this
        rules out predictive back for the back-gesture direction, and a forward tap
        (`onOpenSettings`/`onOpenPermissions`) never touches the back dispatcher at all. The
        `PredictiveBackHandler` alternative API this file previously suggested trying would change
        nothing here for the same reason.
      - `androidx.compose.ui:ui:1.12.0`'s release notes (fetched from
        `developer.android.com/jetpack/androidx/releases/compose-ui`) carry no bug fix or known
        issue about stale frames, ghosting, or `AndroidComposeView` layer invalidation anywhere
        near this version.
      - The notification's content `PendingIntent` already uses `FLAG_ACTIVITY_CLEAR_TOP`, so it
        can't be stacking a second `MainActivity` instance either.

      An OLED-panel or OEM-chrome explanation was floated here and then dropped (maintainer,
      2026-08-23: "I haven't seen this in any other app") — a fair challenge, since a real
      hardware or system-skin effect would show up across every app on the phone, not just this
      one, and it doesn't. **What actually is different about this app is the build it's tested
      as**: when this entry was written `app/build.gradle.kts` turned R8 off outright, so *every*
      build the pipeline could produce — debug or release, CI or local — was unminified and
      unshrunk, and carried no *app-specific* baseline profile of its own (Codex, PR #94: Compose's own library code ships with a default profile baked into its
      AARs regardless — the gap here is app code, `MainScreen`/`SettingsScreen`/`PermissionsScreen`
      and their hot paths, never being AOT-hinted, not Compose itself running fully uncompiled).
      That is a real, well-documented source of visibly slower recomposition/layout than a typical
      installed app ever shows a user, because a normal Play-Store app on the same phone is
      R8-shrunk and often ships its own profile too — a different performance class entirely,
      unrelated to Compose itself being at fault. This fits "not in any other app" far better than
      a hardware artifact would: most other apps on the phone are either not Compose, or are optimized release builds
      from the store, or both, while every Snoozemo build tested so far is neither. **Still needs a
      device to confirm**, and profiling is still the way in — but now pointed at a sharper
      question than "is this a real frame or a display artifact": `adb shell dumpsys gfxinfo
      <package> reset` immediately before reproducing one tap, then `adb shell dumpsys gfxinfo
      <package> framestats` right after — `framestats` is a ring buffer of the last ~120 frames,
      not a live capture of "the exact tap" (Codex, PR #94), so without resetting first an
      unrelated earlier miss can be mistaken for this one. A miss there would show one of these
      screens actually missing its
      frame deadline on an unoptimized build — that's the timing question, and timing is all it
      answers: `framestats` reports per-stage pipeline timestamps (input, animation, layout/measure,
      draw, sync, GPU work), not which stage or which composable is responsible (Codex, PR #94:
      `framestats` alone can't attribute a miss to Compose/layout specifically). Attributing a miss
      to a stage needs a Compose-aware system trace (Android Studio's System Trace/Perfetto with
      composition tracing enabled); Layout Inspector's Recomposition Counts is neither of these —
      it reports recompose/skip *frequency*, not cost, so a cheap composable recomposing often can
      outrank an expensive one that recomposes rarely (Codex, PR #94, twice now on this same tool:
      it doesn't measure elapsed time and doesn't attribute cost either) — it's for spotting
      excessive recomposition as a candidate cause, never for confirming or localizing a slow one;
      only a trace's own per-composable timing does that. `<package>`
      is `app.snoozemo.debug` for a locally-built `assembleDebug` install (Codex, PR #94:
      `app/build.gradle.kts`'s debug block adds the `.debug` suffix, so plain `app.snoozemo` reads
      an uninstalled or co-installed-release package and returns no relevant frames) — `app.snoozemo`
      itself only for a `play`/`direct` release build. And if the reading comes back slow,
      **that makes R8 an experiment to run, not yet a fix to declare** (Codex, PR #94): a slow
      frame shows only that something missed its budget, not that shrinking is what would have
      saved it — plenty of slow-frame causes (excessive recomposition from unstable parameters, a
      genuinely expensive layout pass, a cold-JIT code path) can persist despite R8 rather than
      being resolved by it (Codex, PR #94: R8's inlining and simplification can still touch these
      paths, so "unaffected" overclaims what a slow `framestats` reading alone would show — the
      point is that a slow reading doesn't confirm shrinking *would* fix it, not that shrinking
      *can't*). Confirm with an R8-on/R8-off comparison, or a trace that actually attributes the
      delay to code shrinking changes, rather than reading a slow `framestats` result as proof
      that shrinking was the missing piece.
      **R8 has since landed (Phase 6 below), which changes what to test rather than closing this.**
      R8 has landed, which makes the R8-on/R8-off comparison the sentence above asks for
      possible — but **only between release builds** (Codex, PR #121). A debug APK is never an
      arm of it, whichever way it was built: AGP disables optimization and obfuscation for any
      debuggable build, so a minified debug APK differs from an unminified one by the shrinker
      alone, and a ghost that survives that comparison could still be caused by code the release
      optimizer would have changed. That experiment now needs a build the repo does not have.
      Release minifies on every machine (PR #122), so `./gradlew assembleRelease` and
      `CI=true ./gradlew assembleRelease` are the same R8-on build and comparing them measures
      nothing; `isCiBuild` is gone, so there is no flag left to flip. Getting an R8-off arm
      means a dedicated non-debuggable benchmark build type — debuggable is not an option, since
      AGP disables optimization and obfuscation for any debuggable build — or a temporary local
      edit to `isMinifyEnabled` that is never committed. Note also
      that R8 ships no app-specific baseline profile either way, so the AOT-hinting half of the
      gap described above is untouched by any of this.
      A fast result there narrows the field a different way — it rules out a slow
      composition/layout pass, but `framestats` and HWUI's profile bars measure frame *duration*,
      not frame *contents*, so they cannot on their own clear a defect that renders wrong pixels
      within budget. A fast result still needs actual pixel-level evidence — a screen recording
      (`adb shell screenrecord`) slowed down around the tap — before concluding the cause sits
      outside this codebase. Every timing or hierarchy tool this entry considered (`framestats`,
      HWUI's profile bars, a Perfetto/system trace, Android Studio's Layout Inspector) records
      duration, events, or a view/recomposition snapshot, never a replayable sequence of rendered
      pixels (Codex, PR #94, across three rounds catching each one in turn) — a recording is the
      only one of these that can actually show the ghost after it's gone.
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
- [x] **Only one of `Snooze` / `End snooze` should show at a time** (maintainer, 2026-08-22;
      **landed 2026-08-30 under autopilot** — see *Decisions needing review*). Both used to
      render whenever DND access was granted, with `Snooze` merely disabling itself during a
      snooze and `End snooze` always shown. The split is now on `snoozing == false`, not on
      `snoozing == true`, and that asymmetry is the design: `End snooze` is the one guaranteed
      way to un-silence the phone (`SPEC.md` §7, idempotent), so it may only vanish where the
      screen is *confident* nothing is running — an unread record keeps it. `Snooze` takes the
      opposite treatment, appearing only on that same confident reading, because arming over a
      snooze the screen has not read is how a user loses their cap. `SPEC.md` §4.7 records it.
- [x] **Resolved 2026-08-24 (maintainer) — granted-status text is one word app-wide: `Allow` /
      `Allowed`.** `PermissionsScreen` used to carry two — `Granted` for Do Not Disturb access
      (paired with its own `Grant` action, since that one is a Settings toggle, not a runtime
      prompt) and `Allowed` for notifications and location (paired with `Allow`). The distinction
      cost the user two words to track and told them nothing they needed to act on the row, so
      `setup_action_grant` and `setup_dnd_granted` are gone — every row now says `Allow` while
      something is left to do and `Allowed` once it's in place, whichever mechanism gets it there
      (`SPEC.md` §5.2).
- [x] **Rename `debug_arm`, `debug_release` and the other `debug_*` string IDs**
      (maintainer, 2026-08-22; narrowed 2026-08-23 now the composable rename above has landed).
      **Landed** (2026-08-23) as `arm`, `release`, `rule_failed`, `rule_disabled` — topic-named
      rather than screen-prefixed (maintainer, 2026-08-23: naming a string after whichever
      composable currently renders it is what produced `debug_*` outliving `DebugScreen` in the
      first place). Swept the same fix over the screen split's own new strings while in here:
      `permissions_screen_title` → `permissions_title`, `settings_screen_title` →
      `settings_title`, `main_dnd_banner_title` → `dnd_banner_title` — the last one paired with
      `tile_banner_title`, the sibling banner that already had this right.
- [x] The two rows (`until <time>` seeded at now + 1 h rounded to the half hour, and
      `until I leave`), with `−` / `+` in 30-minute steps, floored at 30 min from now and
      ceilinged at the 8 h backstop. **Landed 2026-08-25** as `EndCondition` in `:core` (the
      seeding, stepping and clamping, with no Android in it) and `EndConditionSheetContent`
      in `:app` (stateless, handed a condition and reporting taps). One property worth
      knowing rather than rediscovering: rounding the seed onto the half hour can leave as
      little as 45 minutes of headroom above the floor, and a step is 30 — so on a tap at
      13:12, whose seed is 14:00, `−` is legitimately dead, because the value below it is
      18 minutes out. It disables rather than clamping onto a ragged 13:42.
- [x] Choosing a time **lowers the cap**; it does not disable departure tracking. Whichever
      comes first wins (`SPEC.md` §7). **Landed** as `SnoozeController.lowerCapTo` — the
      mirror of `extendTo`, refusing the opposite direction — driven by
      `SnoozeService.ACTION_SET_CAP`, which re-arms the alarm, then writes the record, then
      tells the controller, in that order and with `+30 min`'s own rollback. Nothing is said
      to the presence engine, because nothing about tracking changed.
- [x] Dismissing the sheet, or never seeing it, leaves the user correctly snoozed. The scrim
      and the back gesture both just finish the activity; the snooze was armed before the
      sheet existed.
- [x] Setting to disable the sheet entirely — the trampoline then finishes in `onCreate`.
      **Landed 2026-08-25, and inverted: the sheet is off by default** (maintainer, same day),
      so the setting turns it *on* rather than off. `Ask when to unsnooze` on `SettingsScreen`,
      backed by `EndSheetStore`'s own one-key file. The trampoline reads it from the posted
      block, after the service start and never before it, so the gate is off the arm path by
      construction — and on a default install this activity draws nothing at all, exactly as
      it did before the sheet existed. Overlaps `SettingsScreen.kt` with PR #113; the new row
      is kept in that file rather than `SharedComponents.kt`, which #113 is restructuring.
- [x] Screenshot tests for the sheet, wired into the CI allow-list.
      `EndConditionSheetScreenshotTest` records the seeded sheet, the sheet at its floor
      (a disabled `−`), and a refused commit.
- [x] The sheet handles its own insets. It arrives in the trampoline, whose theme is transparent
      and now follows dark mode, but which deliberately declares no edge-to-edge of its own —
      nothing may run between `onCreate` and the service start (`SPEC.md` §6.9), so the call
      belongs after it, in the same posted block the sheet is rendered from. **Landed** that
      way: `enableEdgeToEdge` runs inside `showEndConditionSheet`, and the sheet carries
      `navigationBarsPadding`.

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
- [x] **Degrade to duration-only when the location grant is missing, rather than ending**
      (maintainer, 2026-08-30; **landed**). Both grant-shaped refusals — background never
      granted, and location revoked or downgraded mid-snooze — now keep the snooze armed on the
      duration cap and name which grant is missing, where both previously ended it. The cap is
      mandatory, so the fallback is bounded by construction and ending bought no safety it did
      not already give. Neither falls back to Wi-Fi: an SSID read needs the same grant.
      `SPEC.md` §8.1, §8.2 and §8.3 rewritten with the reasoning they replace.
      `Resume tracking` is **not** being built — §8.1 records why the design cannot work.
- [x] **A grant lost *during* an active Wi-Fi grace no longer ends the snooze** (Codex, PR #149,
      deferred there; **landed** 2026-08-30). Both halves went in together, which is why the
      one-line reordering was refused at the time: reporting `Timer only` while the grace alarm
      still ended the snooze minutes later would have been a card that lies, worse than the honest
      `Wi-Fi lost — ending soon` it replaced.
      - The engine gained `PresenceSignal.LocationAccessLost`, a sensor-layer fact like the rest of
        that list. It clears the running deadline and refuses to arm another, because Wi-Fi stops
        being evidence under a dead grant — an SSID read needs the same permission, and a
        background read without it returns the redaction placeholder, which the watch reports as a
        loss. Without the refusal the very next redacted read would re-arm what the clear had just
        withdrawn.
      - The monitor delivers it from `reportRegistration` on the same classification that sets the
        grant cause, through `deliver` rather than `send`, so the cleared deadline is persisted and
        `GraceAlarm.reconcile` cancels the real alarm on the same pass.
      - `modeFor` now puts a grant loss above `graceActive` — the one cause that does, since it is
        the one that invalidates the Wi-Fi signal itself rather than merely failing beside it.
      - The suppressor lifts only on `PresenceSignal.LocationAccessRestored`, which the monitor
        sends when a geofence registration succeeds (Codex, PR #150, two findings). The engine
        cannot judge this for itself: a delivered fix can be cached from before the revocation,
        and a nameable SSID proves a revoked grant is back but not a missing background one — the
        app reads the SSID fine in the foreground under a while-in-use grant. A registration the
        platform accepts needs `ACCESS_BACKGROUND_LOCATION` outright on API 29+, so it is the one
        proof that covers both. Leaving it latched forever would cost the snooze its five-minute
        backstop for the rest of the cap, so the refutation has to exist — it just has to be the
        real one.
      - Not persisted, deliberately: the monitor re-derives it on every restart when its
        registration is refused again, and a second durable copy behind a different refutation is
        the two-slot mistake of PR #75. `SPEC.md` §6.6 carries the reasoning.
- [ ] **The new degradation does not reach a Wi-Fi-only anchor at all** (Codex, PR #149,
      deferred there). An anchor with an SSID but no usable fix has `Presence.duty == NONE`
      always (`!anchor.hasUsableFix -> LocationDuty.NONE`), and `registerFence` returns early on
      the same test — so neither permission-classifying path is ever entered for it. A grant lost
      on such a snooze surfaces only as a redacted SSID reading as `AnchorWifiLost`, which starts
      grace and ends the snooze in minutes, exactly as before PR #149. Not a regression: it is
      the old behavior, untouched. But it means the 2026-08-30 decision is delivered only for
      anchors that have a fix, and the *most* Wi-Fi-dependent snoozes are the ones it misses.
      Closing it needs a live grant check on the Wi-Fi-only path — a new detection site rather
      than a reclassification, since there is no platform refusal to classify there — plus test
      coverage for that anchor shape.
      **Re-raised on PR #150 and deliberately not half-built there** (Codex again). Two things
      make the obvious narrow version worse than the bug:
      - **The ambiguous case is the common one.** Only an outright *revoked* fine grant makes a
        Wi-Fi loss provably not-evidence. Under a while-in-use grant the read is redacted in the
        background and fine in the foreground, so a loss is indistinguishable from a real
        departure — and suppressing grace for every while-in-use user, including those who
        actually left, is principle 1's failure, not a defense against it.
      - **There is no restoration proof on that path.** `LocationAccessRestored` is delivered
        from a successful `addGeofences`, which a Wi-Fi-only anchor never reaches. So a latch set
        there could never be lifted: the snooze would run to its cap even after the user
        re-granted and genuinely left. Whatever closes this has to bring its own refutation,
        not just its own detection.
      So the shape is: teach the Wi-Fi tracker to report *unreadable* distinctly from *lost*
      (today `AnchorWifiTracker` folds the redaction placeholder into not-associated by design,
      D7), and pair it with a proof the read works again. That is a change to the tracker's
      contract, not a check bolted onto the monitor.
- [ ] **A restored location grant is not noticed until the half-hour backstop** (Codex, PR #150,
      sixth round on this seam; **stopped and handed to the maintainer rather than fixed there**).
      Nothing in the monitor observes a *permission* change. `LocationModeWatch` watches location
      services going on and off; `MainActivity`'s permission callbacks only refresh UI state. So
      when a user re-grants location without touching the system location toggle, the fence is
      re-registered only by `SnoozeBackstop`'s half-hour `WorkManager` wake — and until it fires,
      `CheckingFixes` stays suspended and `locationAccessLost` keeps both grace-arming paths shut.
      A user who leaves inside that window gets neither a fix nor the five-minute grace, so the
      phone stays quiet until the cap.
      - **The suspension is not new** — PR #149 introduced it and `resumeChecking` already waited
        on the same repair. What PR #150 adds is that *grace* is suppressed during the window too,
        so the gap widened from "no confirming fixes" to "no way to end early at all".
      - **Why it was not fixed in #150.** Android broadcasts no permission change, so the trigger
        has to come from the app layer — `MainActivity`'s permission result callbacks, or an
        `onResume` re-check, calling `pokePresenceRepair()`. That is a new cross-module signal
        path, and it arrived as the sixth review round on one seam; bolting it on at that point is
        how the regression two rounds earlier happened. The bound is the backstop period and,
        beyond it, the cap.
      - **Options for the maintainer**: hook the existing permission callbacks (cheapest, but puts
        presence knowledge in the UI layer); shorten the backstop while a grant cause stands (no
        new coupling, more wakeups, still up to the shortened period); or have the app poke the
        repair from `onResume` whenever a grant cause is recorded. The first is probably right,
        since the user re-granting almost always happens in the app.

- [x] **The end-condition sheet never opens from the main screen's Snooze button** (maintainer,
      2026-08-30; **landed**). The flow moved into `EndChoiceController`, shared by the trampoline
      and `MainActivity`, rather than being written out a second time — the maintainer picked that
      over routing the button through the trampoline (a transparent `singleInstance` activity over
      the app, untestable here) or duplicating it. `SPEC.md` §4.4 records the split and what each
      surface still owns.
      Superseded detail from when it was raised: Arming from the Quick Settings tile goes through
      the trampoline and shows the sheet; arming from the button on `MainScreen` does not, so the
      same action offers the end-condition choice from one entry point and silently takes the
      default from the other. Same arm path, so whatever the trampoline does before showing the
      sheet is what the button needs to do too — check that the service still starts within a
      frame either way (`SPEC.md` §6.9, and the arm path is goal 1).

- [ ] **Still open: should `MONITORING_UNAVAILABLE` degrade too?** The narrow reading of the
      2026-08-30 decision was taken: it covers the two *permission* causes, which name a state
      the user can act on. An unclassified geofence refusal still ends the snooze, since there is
      no reason to put on the card. The maintainer's own argument — the cap bounds any
      fallback — applies here as well, so this is worth a decision rather than an assumption.
- [ ] Reboot: re-assert the rule, degraded mode, cap continues from the *original* start
      time. `On restart: resume / end` setting, defaulting to resume (`SPEC.md` §8.3).
- [x] Permission revoked mid-snooze — **policy access** ends the snooze with a reason; **location**
      degrades to duration-only and names the missing grant (maintainer, 2026-08-30; `SPEC.md`
      §8.2, rewritten). One gap left, tracked above: a Wi-Fi-only anchor never reaches it. The
      grace-period gap is closed (`SPEC.md` §6.6).
- [ ] The §8.5 table: airplane mode, location services off, double-arm, short trip and
      return, bad-accuracy anchor, battery saver, uninstall while snoozed.
- [ ] **The read-back may end every snooze that spans a reboot** (Codex, PR #36) — the finding that
      makes the decision below urgent rather than tidy. Boot and app update both enter through
      `ACTION_RESTORE`, which is exactly where the rule-state read-back runs. If the platform resets
      an app-owned rule's condition to `STATE_FALSE` across a reboot — which `restore()` re-asserting
      `STATE_TRUE` exists to handle, so the code already assumes it might — then an armed record over
      that reset reads as **the user turned Do Not Disturb off**: the snooze ends, silently, with the
      platform told it was a user action. That contradicts "the record survives process death and
      reboots".
      - **Needs a device to settle**, and it is the crux: if the condition *does* persist across a
        reboot there is no bug at all. Not answerable in the sandbox (no DND-capable device), so it
        joins the hardware-verification list.
      - Note the direction: the earlier fix that stopped a restore *re-asserting* over a user's
        deactivation is what makes this reachable. The same read-back now has failure modes in both
        directions — re-silencing a phone the user un-silenced, and ending a snooze the user still
        wants — which is the strongest argument that the mechanism is wrong rather than incomplete.

- [ ] **A record written before `armed` existed reads as an unfinished arm** (Codex, PR #36).
      `getBoolean(KEY_ARMED, false)` treats *absent* as *not armed*, but absent only happens for a
      record from a build predating the field — which was, by definition, armed the old way. On an
      update with a live snooze, the next restoring wake-up therefore re-asserts the rule over a Do
      Not Disturb the user may have switched off. **Currently unreachable**: nothing has shipped, so
      no such record exists outside a dev device that updated mid-snooze.
      - The one-line fix is defaulting absent to `true`, which is safe because every record this
        build writes carries the key explicitly. It is *deliberately not applied yet* — it is the
        fourth instance of one bug class in this mechanism, and the pending decision below either
        removes the flag or replaces it with a lifecycle state that answers migration once.
      - **Blocked on the maintainer's call**: keep the zen-rule read-back as it stands, model the
        record's lifecycle explicitly (one state on `ActiveSnooze`, the four causes of "live record
        over an off rule" enumerated in one place), or drop the read-back and let the broadcast
        stand alone. Seven consecutive Codex findings landed in this mechanism; three of them were
        caused by the fix for the previous one.

- [ ] The §8.4 cases: `restricted` standby bucket, force-stop, OEM battery management.
- [x] **The user turning Do Not Disturb off from the shade may silently end our snooze**
      (maintainer, 2026-08-12 — "we should handle that soon"). Done, and the platform question
      that gated it turned out to be documented rather than empirical:
      `ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED` reports `AUTOMATIC_RULE_STATUS_DEACTIVATED` when
      the user switches an app-owned rule off (API 35+), so no interruption-filter inference is
      needed and no wake-up is added.
      - **The broadcast is the timely answer; the read-back is the reliable one.** A receiver can
        be refused registration, and this process only lives between wake-ups, so a snooze can
        outlive the only thing watching it. `ruleActivation()` re-asks on every non-arm wake-up,
        which turns that into *late* rather than *never* — and it has to run **before** the
        restore, because restoring re-asserts the rule and overwrites the evidence (Codex, PR #36).
        Both mechanisms are API 35, which is also the floor this app installs on (minSdk was
        raised to 35 in PR #88, after this item's first draft), so there is no version below
        them to caveat.
      - **It was more urgent than "state drift".** A deactivated rule stays deactivated until its
        owner sets it back to `STATE_FALSE`, so leaving the snooze running would have left the
        *next* tap arming a rule the platform ignores — the tile reading `Snoozing` over a phone
        that still rings. Ending the snooze is what issues that `STATE_FALSE`.
      - `REMOVED` and `DISABLED` fail open as `LOST_CAPABILITY`; a removed rule is recreated when
        idle, a disabled one never is. All of it gated on the rule being ours (§5.6 for reads).
      - **Still owed a device**: that the shade toggle really does produce `DEACTIVATED` for our
        rule on a Pixel.
- [x] **Decide: can a PR merge with deferred review findings?** (found on PR #36,
      2026-08-25). `AGENTS.md` said to resolve a review thread "unless you are deferring the
      work", while the branch ruleset requires every conversation resolved before a merge — so
      any genuinely deferred finding blocked its PR permanently. PR #36 hit exactly that: green
      CI, a clean Codex verdict, five open threads all saying "recorded in `TODO.md`, not doing
      it here".
      **Answered (maintainer, 2026-08-25): recording a to-do and resolving is allowed**, with a
      comment on the thread saying it is tracked for a later PR. `AGENTS.md` amended. The point
      the open thread was protecting — not losing the finding — is what the `TODO.md` entry is
      for; the thread is not the durable place.

- [ ] **A record update erases the release marker** (Codex, PR #36, raised rather than
      built). `ActiveSnoozeStore.save` clears `KEY_RELEASING_REASON` on every write, and its
      comment only justifies the case it was written for — a *new* snooze, which must not be
      born carrying an old one's marker. But `save` is also how a live record is **updated**:
      the boot receiver rebases the clock frame and saves, the controller saves on every
      `ARMED`/`CHECKING` transition. So an app-driven release that turned the rule off and died
      before cleanup can have its only durable cause deleted by an unrelated update — and
      recovery then reads its own ending as the user's, silently, since that is the ending with
      no notification. The same failure §5.8 keeps producing from a different direction.
      **Not fixed on PR #36** because the fix is a judgment call at every `save` call site
      (which writes are "a new arm" and which are "an update"), and that is precisely the kind
      of change that went wrong repeatedly on that PR — three sibling call sites missed across
      as many rounds. Worth doing deliberately: either split `save` into `arm` and `update`, so
      the distinction is in the type rather than in each caller's head, or move the marker out
      of the record's own preferences file so record writes cannot touch it.
      **A third gap in the same subsystem** (Codex, PR #36): `releaseDirectly`, the no-service
      release path, never writes a marker at all. A cap or capability-driven release that
      succeeds there and dies before erasing the record leaves the same live-record-over-an-off-rule
      state with no durable cause, read back as the user's doing. All three want fixing together
      — the marker is only worth having if every release path writes one and no record write
      erases one.
      **The same shape, one field over** (Codex, PR #36): the no-service restore path does not
      record that it re-asserted the rule, so a record written before its rule ever went on
      stays marked unfinished even after that path successfully turns the rule on. Writing it
      there was attempted and withdrawn — three rounds found three ways for it to be wrong,
      ending with the one that has no cheap answer: a write that fails leaves the rule **on**
      with disk saying the arm never finished, which a later wake re-asserts over a user who has
      since switched Do Not Disturb off. Doing it properly needs a durable retry or a rollback
      of the activation it just performed, which is more machinery than a fallback should carry
      — and it is the same question as the rest of this item, namely which writes state what
      about a record and what happens when one of them does not land.
      **And two more on the same subsystem** (Codex, PR #36): *both* of §5.8's endings — the
      status broadcast's and the pre-restore read-back's — call `controller.end` without
      following a refused release with `ensureCapAfterRefusedEnd`. Every other ending on the
      service does: the explicit end, presence, the cap, a clock change. Neither of these
      trigger events is guaranteed to repeat, so a retryable refusal leaves the record, tile
      and notification claiming a snooze, and the rule stuck deactivated, until an unrelated
      wake or the cap. Plausibly one line each; left with the rest because it is the same
      question about what a refused release leaves behind, and because they arrived on this
      PR's eighth and ninth review rounds. **Fix both together** — an earlier note here said
      the broadcast was the *only* such path, which was wrong in exactly the way that lets the
      second one survive a fix.

- [ ] **Tie a snooze to the rule that was enforcing it** (Codex, PR #36, raised rather than
      built). Ownership is answered against whatever rule id the app holds *now*, and nothing
      else. Two cases slip through it, both starting the same way — the user deletes the rule
      and the tile's shade-open `ensureRule()` mints a replacement:
      - **A `REMOVED` broadcast racing that replacement** names an id the app no longer holds,
        so it is read as somebody else's and discarded.
      - **With no process alive to hear the broadcast at all**, the next restoring wake's
        read-back looks at the *replacement* — enabled, `STATE_FALSE` — and reports `INACTIVE`.
        The snooze ends as `DND_TURNED_OFF`, which is silent, when the truth is
        `LOST_CAPABILITY`, which explains itself. A lost capability disappearing behind the one
        ending designed to be quiet is the failure §5.8 keeps re-finding in different clothes.
      **A short-lived memory of the displaced id was tried here and taken out again**
      (2026-08-25). It closed the broadcast race and then produced a fresh defect on each of
      three consecutive review rounds — retained forever, so a late status change ended an
      unrelated later snooze; then consumed, but unconditionally, so a concurrent replacement
      had its own memory wiped instead. Each guard was correct and each exposed the next,
      which is the signal that the mechanism was wrong rather than unfinished: ownership
      inferred from a value that moves needs a new guard for every way it can move. Ownership
      is back to the id the app holds now, and the broadcast race is a known gap again — the
      same one this item exists to close properly.
      **The fix is to stop inferring ownership and record it**: put the enforcing rule's id on
      `ActiveSnooze`, so both the broadcast and the read-back compare against the rule *this
      snooze* was armed with rather than against whatever the app holds now. That also retires
      the displaced-id memory entirely, since there would be nothing left to infer.
      Deliberately not folded into PR #36: it changes the persisted record's shape, and it
      arrived late on a PR whose ownership fixes were themselves generating findings. Worth
      doing on its own, with its own migration story and a clear head.

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
- [x] **Sharing the debug log** (`SPEC.md` §4.6) — the user-facing half of the Phase 3 log,
      matching the sibling repos: a `Share debug logs` action through the system share sheet with a
      copy-to-clipboard fallback (no `INTERNET`, so the share sheet *is* the transport), and a
      post-crash banner offering to share the crashed run or dismiss it. Only a crash raises the
      banner — an ordinary process death, force-stop, or app update leaves the run shareable without
      nagging. Sizing matters: the payload crosses a Binder transaction twice, and an over-large one
      fails both silently, so bound it per section and in total. **The `Share debug logs` action's
      home is `SettingsScreen`** (maintainer, 2026-08-23, once the screen split below landed) —
      beside the debug-log toggle it already carries, not a new screen of its own. **The crash
      banner's home is `MainScreen`** instead (maintainer, refined once the split above landed) —
      the screen the user actually lands on, above even the Do-Not-Disturb-access banner, rather
      than a screen only reached by navigating to Settings. **Landed**: planned in `docs/DEBUG.md`
      first (comparing the sibling Simmo/ClothesCast repos' own implementations, then narrowing —
      Snoozemo has no rules/settings dump to report, so the structured header is build/device/
      permission-and-capability state only), then built as `app/snooze/DebugReport.kt`
      (`DebugReport.share`, mirroring Simmo's `DebugReport`/ClothesCast's `BugReport` shape: an
      injectable-seamed clipboard-then-chooser flow, a pure `buildDebugReportPayload` with its own
      section budgets summing under `MAX_SHARE_PAYLOAD_CHARS`) plus three new `DebugFileSink`/
      `DebugLogging` read methods (`hasPinnedCrash`, `readPreviousOrCrash`, `consumeCrashPin`) and
      a `CrashBanner` composable. The pin is consumed only on a landed clipboard copy — the durable
      proof of delivery `ACTION_SEND` itself can't provide — never on the chooser merely opening,
      so a share that didn't land keeps the crash log for a retry. Covered by `DebugFileSinkTest`,
      `DebugLoggingTest`, `DebugReportTest` (payload assembly, bounds, and the privacy-floor
      regression `docs/PRIVACY.md` promises), `DebugReportShareTest`, `MainScreenScreenshotTest`
      (the crash banner), and `SettingsScreenScreenshotTest` (the share row). Thirty rounds of
      Codex findings on PR #89 landed in the same PR. Six on the crash-pin/dismiss mechanism: a config
      change mid-Share/Dismiss could strand the outcome on the now-dead activity instance — fixed
      with `DebugLogging.watchCrashPinOutcome`/`DebugReport.watchShareOutcome`, mirroring
      `watchSaveOutcome`'s existing single-slot, re-read-the-truth shape, with `crashPending`
      routed through the watch rather than a landed copy alone; `consumeCrashPin`'s copy+delete
      fallback read `runCatching{}.isSuccess` instead of `delete()`'s own return, so a refused
      delete read as consumed — fixed to read the delete's own boolean; a landed copy could still
      consume the pin when the previous-run read had timed out, or failed outright
      (`readText()` throwing) without that being distinguished from a genuinely empty read — fixed
      with `DebugReport.Payload.pinConsumeSafe`, gated on a new `readSucceeded` flag threaded
      through `readPreviousOrCrash` (and a pre-existing bare `Log.w` on that path, the one call
      site in the file not wrapped in its own `runCatching`, fixed alongside it); and Dismiss
      discarded `consumeCrashPin`'s result entirely, leaving a refused consume with no explanation
      — fixed with `DebugLogging.dismissCrashPin`/`watchDismissOutcome`/`lastDismissFailed`,
      mirroring the share-outcome plumbing, surfaced as "Couldn't dismiss — try again" and
      deliberately distinct from a Share's own refused consume, which stays unsurfaced on purpose.
      Three more, unrelated to the pin mechanism: the report's "Location (foreground)" line
      collapsed a coarse-only grant into a plain "denied", indistinguishable from no grant at all
      — fixed by reporting `locationFineGranted`/`locationCoarseGranted` separately and labeling
      the three cases distinctly; turning debug logging off deletes a pinned crash asynchronously
      on the sink's own worker without notifying `watchCrashPinOutcome`, so a banner already on
      screen kept offering to share a file that no longer existed — fixed with an `onDisabled`
      callback on `DebugFileSink.setEnabled`, fired once the delete completes and wired to the
      same watch; `docs/PRIVACY.md`'s "short version" and "What leaves your phone" summaries
      still claimed Android's new-phone transfer was the only way stored data could reach another
      device, contradicting "The debug log" section further down the same policy — fixed to name
      sharing as a second, user-initiated way, still never automatic; a second Share tap while an
      earlier attempt was still collecting its payload started a second unsynchronized thread, so
      whichever attempt happened to finish last in real time won `lastShareFailed` even if it was
      the *older* tap — fixed with an `attempt` ticket on `share()`; and the first version of that
      fix drew the ticket from a field on the activity, which a configuration change resets to
      zero — behind the process-level high-water mark `DebugReport` already compares against, so
      every share the replacement instance fired read as stale — fixed by moving ticket issuance
      into `DebugReport.nextAttempt()` itself, the same owner that compares tickets, so a
      configuration change (which only resets activity-scoped state) can no longer desynchronize
      the two. Two more after that: the ticket fix above still compared an attempt against the
      highest *applied* ticket, so an older attempt could still apply its own outcome the moment
      it completed as long as nothing had been applied yet — even after a newer attempt's ticket
      had already been issued and was still in flight — fixed by comparing against the highest
      *issued* ticket instead, so only the single most-recently-issued attempt can ever apply;
      and the payload's own privacy-floor test fed fixtures that never contained the banned
      values it was asserting against, so it passed regardless of whether the code was correct —
      fixed by splitting it into a genuinely structural test (the header has no string field a
      coordinate/SSID/place name could pass through) and a test that exercises realistic
      banned-looking values in `previousRun`/`recentLog` and documents that those two channels are
      forwarded verbatim, with the real floor held by `SnoozeDebugLog` call-site discipline. Two
      more, both about a "successful" outcome that quietly wasn't the whole story: a share whose
      previous-run read timed out or failed left the shared text silently missing whatever it
      would have shown — a pinned crash included — while still reporting the share as fully
      successful, indistinguishable from a genuinely empty previous run — fixed by having the
      payload say so explicitly (`--- Previous run --- (could not be read in time — try Share
      again)`) instead of rendering the same blank section either way; and turning debug logging
      off could have storage refuse to delete one or more files, which `deleteEverything()` only
      logged before still reporting the disable as a clean success — the pinned crash and its
      banner correctly stayed (`hasPinnedCrash` re-reads the real file), but nothing told the user
      their delete request only partly landed — fixed by threading the delete's actual result
      through `DebugFileSink.setEnabled`'s `onDisabled` callback to a new
      `DebugLogging.lastDisableCleanupFailed`, surfaced on the Settings row distinct from a
      refused setting save. Two more, on that same round's own fixes, folded back into the
      commits that introduced them since each finding was on already-unmerged work: the
      cleanup-failure fix covered the interactive toggle path but not a process restart under an
      already-Off setting, where `DebugFileSink.start(false)` retries the delete but never
      reported its result — fixed by giving `start()` the same `onDisabled` callback
      `setEnabled` has, wired to the same field and watch; and the privacy-floor test's own
      fixture coordinate, meant to look banned, was an actual real-world address (Google's own
      Mountain View headquarters) rather than a plainly fictional pair as `AGENTS.md` requires —
      fixed by replacing both occurrences with `0.000000,0.000000`. Three more, all the same
      shape: a retry only cleared its own activity-local failure flag, never the process-level
      outcome a config change or restart mid-retry would reload — `DebugReport.nextAttempt()` now
      clears `lastShareFailed` the moment a new ticket is issued (safe, since only that ticket's
      own completion may set it again); `DebugLogging.dismissCrashPin()` now clears
      `lastDismissFailed` before its own consume even starts; and `DebugLogging.lastDisableCleanupFailed`
      — which nothing had ever cleared, since only a disable ever wrote it — is now reset on a
      successful re-enable, so it can no longer resurface at a later restart under a switch that
      has been back On for a while. One more from that same round: the attempt ticket only ever
      guarded `lastShareFailed`, never the delivery itself — a second tap while an earlier attempt
      was still collecting its payload still started a second, unsynchronized delivery, so both
      attempts could write the clipboard and open a chooser, unordered, letting a slower older
      attempt overwrite a retry's report on the clipboard or open a second chooser after the user
      had already seen the retry resolve — fixed by serializing delivery itself under a dedicated
      lock, re-checking the ticket once that lock is actually held (not just at entry) so a
      superseded attempt found stale only once it reaches the front of the queue skips delivery
      entirely; kept as a separate lock from the one `nextAttempt()` uses, so issuing a ticket on
      the tap thread never waits on a background attempt's binder/IPC work. One more, round
      sixteen: `setEnabled`'s Off/On calls are ordered on `DebugLogging`'s own worker, but the
      delete a disable triggers runs on the *sink's own separate* worker, asynchronously — so a
      quick Off-then-On could have the re-enable clear `lastDisableCleanupFailed` first, only for
      the disable's own still-in-flight delete callback to complete afterward and overwrite it
      with `true`, showing a cleanup failure under a switch already back On. Fixed with a
      `disableGeneration` counter, bumped by every toggle before dispatching to the sink; a
      callback whose captured generation no longer matches was superseded by a later toggle and
      discards its own outcome instead of applying it — also applied to `install()`'s own startup
      retry, for the same race against an early manual toggle. Two more, round seventeen: a crash
      marker can land without its content ever reaching disk (process death between the marker
      write and the run's own content write), so a pinned `crash.log` could read back blank —
      `wasCrash` reads true from the marker alone, but the old blank check treated it the same as
      a genuinely empty previous run, letting a successful share consume the only evidence a crash
      happened at all without ever having carried it — fixed by folding "a pinned crash whose file
      exists but reads back blank" into the same omission check `pinConsumeSafe` and the payload's
      own warning line already used (the wording was also generalized from "could not be read in
      time" to "could not be included in this report", since it no longer names a single cause);
      and `startShare`/`copyToClipboard` each already catch their own exception internally and
      return false normally, so `share()`'s outer `runCatching` around each seam never actually
      saw it — leaving a landed clipboard copy with a chooser that silently never opened, or a
      failed copy, with no diagnostic explanation anywhere — fixed by logging inside each
      function's own `runCatching`, where the exception is still visible. Two more, round
      eighteen, both on the `disableGeneration` fix: the Settings row's own reconciliation only
      re-read `lastDisableCleanupFailed` when the *requested* value was a disable — but a
      requested *enable* that storage refused never reaches `DebugLogging.setEnabled`'s persisted
      branch at all, leaving `lastDisableCleanupFailed` exactly as it was and the switch snapped
      back to Off, while the screen's gate on the requested value quietly kept showing the
      optimistic clear — fixed by reading the field unconditionally, which is safe since a
      genuinely successful enable already clears it synchronously before this callback runs; and
      gating `onCrashPinOutcome`'s own invocation on the same generation check as the
      cleanup-failure verdict suppressed the pin-state notification too — a crash.log the
      disable's own delete had genuinely removed left `crashPending` stuck true on screen, since
      nothing else re-reads `hasPinnedCrash` while the activity stays on the same screen — fixed
      by firing the watch unconditionally, since whether the file still exists is always this
      callback's own real answer regardless of which generation asked for the delete. Two more,
      round nineteen: the delivery-serialization fix's own ticket check only caught an attempt
      superseded *before* it started delivering, not one whose tap landed while an earlier,
      still-current attempt was already mid-delivery — that earlier attempt can't be interrupted
      once its clipboard write or chooser launch has started, so a tap landing in that window
      still went on to open a second chooser and copy a second time once it reached the front of
      the queue — fixed with a `deliveriesCompleted` generation counter, snapshotted at `share()`'s
      entry: an attempt that finds the counter moved since it started reuses the in-flight
      delivery's own outcome instead of duplicating it, so the outcome it reports still reflects
      what genuinely reached the user rather than a fabricated failure; and `hasPinnedCrash`'s own
      `crash.exists()` check collapsed a genuine I/O failure into the same `false` as an honestly
      absent pin, with nothing logged either way — fixed by logging the sanitized exception before
      falling back to `false`, matching `readPreviousOrCrash`'s own existing pattern. That specific
      failure mode has no dedicated regression test — `File.exists()` doesn't throw from ordinary
      filesystem tricks (a directory-in-place-of-a-file, an occupied path), only from a
      `SecurityManager` denial, and nothing in this suite has a working technique for that; noted
      rather than silently claimed as covered. One more, round twenty, caught on the very fix
      above: the mid-delivery reuse's early return skipped the outcome-application block
      entirely, and the earlier, now-stale attempt's own outcome-application skips too since its
      ticket no longer matches — so a reused *failure* never reached `lastShareFailed` or
      `watchShareOutcome` at all, leaving the user with no failure message even though nothing
      actually delivered. Fixed by applying the reused outcome through the same
      `synchronized(applyLock) { if (attempt >= attemptTicket) { ... } }` check before returning
      it, so the latest ticket still authoritatively updates visible state whether it delivered
      itself or reused an overlapping delivery's result. One more, round twenty-one: that fix
      reused *any* overlapping outcome, success or failure — but reusing a failure means the
      second tap's own clipboard write and chooser launch are never even attempted, so a user who
      taps Share again while a failed first attempt is still resolving gets nothing for it: no
      chooser opens, and they just see the same failure message reappear, indistinguishable from
      the second tap having done nothing at all. Fixed by only reusing a *successful* overlapping
      delivery; a failed one now falls through so the latest attempt gets a genuine retry with its
      own clipboardWrite/chooserLaunch calls. (A second finding on the same head — sanitizing the
      throwable passed to `Log.e` in these same catch blocks — was declined: the codebase's own
      floor for logs is coordinates, SSIDs/BSSIDs, and place names, none of which a clipboard,
      chooser, or file-I/O exception in this path can plausibly carry, and the exact
      `Log.e(TAG, "<description>", it)` shape is already used consistently in dozens of
      already-reviewed call sites throughout this file.) Three more, round twenty-two:
      `docs/PRIVACY.md`'s "what the report contains" list omitted the capture timestamp,
      application id, debuggable flag, and locale, all of which `buildDebugReportPayload`
      genuinely includes — fixed by adding them to the disclosure, none of them anything
      requiring a behavior change. The `deliveriesAtEntry` snapshot the mid-delivery-overlap
      checks (rounds 19–21) rely on was taken inside `share()` itself, on the caller's own
      background thread — but that thread's actual scheduling can lag the tap that started it,
      so a retry tapped while an earlier attempt was still delivering, whose thread wasn't
      scheduled until after that delivery finished, would sample `deliveriesCompleted`
      post-increment and miss the very overlap its tap occurred during, delivering a second
      time regardless — fixed by having `nextAttempt()` capture the snapshot synchronously on
      the tap thread itself (keyed by ticket in a small map `share()` looks up), where
      thread-scheduling delay can't reach it. And `readPreviousOrCrash`'s own initial
      `crash.exists()` check — the one deciding which file to read — wasn't wrapped in its own
      `runCatching` the way `hasPinnedCrash`'s equivalent check (round 20) already was; an
      exception there escaped the worker's `Runnable` entirely, so
      `onResult` was never called and every `Share` then waited out its own two-second timeout
      and logged a misleading "timed out" instead of the real storage failure — fixed by
      wrapping it the same way, returning an explicit failed read instead. One more, round
      twenty-three, on the very next line: the *second* `file.exists()` check in the same
      function — deciding whether the picked file (`crash` or `previous`) is actually there —
      was already wrapped in `runCatching`, but a genuine failure there collapsed to the same
      `false` as an honestly absent file, unlogged, and `readSucceeded` then computed `true`
      from that `false` (`!fileExists`) — reporting a failed check as a clean empty read rather
      than the retry-worthy failure it was. Fixed the same way as the check above it: log the
      exception and return an explicit failed-read result. That specific throw path has the
      same test gap as the two exists()-related fixes before it (28, 33) — `File.exists()`
      doesn't throw from this suite's usual tricks, and this one additionally shares a single
      lazily-resolved directory with the check above it, so a throwing `dirProvider` can't
      isolate just this call either; noted rather than silently claimed as covered. One more,
      round twenty-four, back on `MainActivity`: a retry-enable tapped on a previous instance
      can still be in flight when a configuration change hands off to a replacement — that
      replacement's own `onStart` read of `lastDisableCleanupFailed` runs before the retry's
      write settles, so it can copy the stale pre-completion value, and the tap's own completion
      callback belongs to the dead instance, so nothing else was correcting it.
      `debugLogWatch`'s own callback, which *is* registered on the live replacement, only
      refreshed `debugLogEnabled` and `debugLogSaveFailed` when it fired — never
      `debugLogCleanupFailed` — so a stale "some saved files couldn't be deleted" warning could
      sit under an enabled switch indefinitely. Fixed by refreshing it there too. Also corrected
      that field's own doc comment, which still claimed the read was gated on the requested
      value being a disable — a claim the earlier unconditional-read fix (25) had already made
      false without the comment being updated to match. Three more, round twenty-five: the
      shared report's "Location (background)" line read `grantLabel` on
      `ACCESS_BACKGROUND_LOCATION` even in the `direct` flavor, which never declares that
      permission and never needs it for its foreground-service tracking (`SPEC.md` §3.4) — so a
      direct build always reported "denied", a false capability problem for anyone diagnosing it.
      Fixed with a `backgroundLocationLabel` helper that reads "not required for this build"
      whenever the flavor-specific `locationTrackingNeedsBackgroundPermission` is false, covered
      by a new `DebugReportTest` case across all three (required-and-granted,
      required-and-denied, not-required). `consumeCrashPin`'s copy+delete fallback wrapped
      `crash.exists()`/`renameTo`/`copyTo`/`delete` in a single `runCatching` whose
      `getOrDefault(false)` discarded any exception among them the same way it discarded an
      honest refused-delete `false` — losing the diagnostic a genuine storage failure needs.
      Fixed by logging the exception before falling back; the existing "a crash pin that cannot
      move stays pinned for a retry" fixture already forces `copyTo` to throw (a same-name
      occupied-directory destination) and continues to assert the fallback still resolves
      cleanly, so no new test was needed to cover the throw path itself — only the fix's log
      line is new, and (per this file's established practice for logging-only additions
      elsewhere in this same feature) isn't independently asserted. And `appVersionName`/
      `appVersionCode` silently substituted "unknown"/-1 on a `PackageManager` exception with no
      log line — fixed the same way as `startShare`/`copyToClipboard`'s own logging a few
      rounds back, covered by a new `DebugReportShareTest` case that removes the app's own
      package from the shadow `PackageManager` (forcing the same `NameNotFoundException` a real
      lookup failure would throw) and confirms the share still completes with the documented
      fallback rather than hanging. One more, round twenty-six, fresh evidence after round 28's
      earlier fix: that fix only *logged* a `hasPinnedCrash` metadata-check failure, but still
      collapsed it into the same `false` a genuinely-absent pin reports — so the post-crash
      banner would silently hide a crash that was still really sitting there, unread, on a
      transient I/O failure, with nothing on screen telling the user it couldn't be checked.
      Fixed by giving `hasPinnedCrash` a second `checkSucceeded` parameter, mirroring
      `readPreviousOrCrash`'s existing `readSucceeded` idiom; `MainActivity`'s two call sites now
      leave `crashPending` untouched on a failed check rather than downgrading it to "nothing
      pinned" (the failure itself is already logged at the file layer). Covered by a new
      `DebugFileSinkTest` case using the same throwing-`dirProvider` trick as the earlier
      `readPreviousOrCrash` regression. **Declined**, same round: a suggestion that a Share whose
      clipboard copy landed but whose crash-pin consume then failed should surface that failure
      on the share outcome — this is the exact trade-off finding 6 above already made and tested
      deliberately (`a share that fails to consume the pin still reports what it delivered`): the
      copy genuinely landed, so the share genuinely succeeded, and the file-layer refusal is not
      silent — `crashPending` is re-read (via `watchCrashPinOutcome`, unconditional since round
      26) immediately after, so the banner stays visibly on screen with its own retry affordance
      rather than a distinct message. Replied with the reasoning and resolved. One more, round
      twenty-seven: `deliveriesCompleted`/`lastDeliveryResult` were recorded only *after* the
      pin-consume wait finished, inside the same `deliveryLock` critical section as the wait
      itself — so a retry tapped once an earlier attempt's clipboard write and chooser launch had
      already both genuinely landed, with only the (up to two-second) pin cleanup left
      outstanding, blocked on `deliveryLock` for that same duration before it could even see the
      outcome waiting for it, reading as the retry silently doing nothing. Fixed by recording the
      outcome and releasing `deliveryLock` before the pin-consume wait begins, so a share() call's
      own synchronous wait on its own pin consume no longer holds up anyone else's read of
      `deliveriesCompleted`. Covered by a new `DebugReportShareTest` case: attempt 1's own
      clipboard/chooser calls resolve immediately but its `consumeCrashPin` blocks; attempt 2,
      tapped only once attempt 1 reaches that blocked wait, must resolve within 500ms with its own
      real clipboard write and chooser launch (correctly finding no overlap, since attempt 1 had
      already fully delivered by the time attempt 2's ticket was drawn) rather than waiting out
      attempt 1's still-open cleanup step. Round twenty-eight brought two more findings, one fixed
      and one declined. Fixed: the cold-start `hasPinnedCrash` read in
      `readNotificationsAfterFirstFrame` is the *only* check that ever runs for a fresh process —
      nothing else re-checks until a Share/Dismiss/settings-toggle outcome fires `crashPinWatch` —
      so round twenty-six's "leave `crashPending` alone on a failed check" fix did nothing for a
      failure on that very first read: there was no prior successful reading to preserve, so
      `crashPending` just sat at its compile-time default `false`, indistinguishable from a
      confirmed absence, for that process's entire lifetime. Fixed with one immediate retry on a
      failed cold-start check — enough for the failure this actually guards against (a momentary
      metadata-access hiccup, not a persistent condition); a second consecutive failure is already
      logged at the file layer and left as the one case this can't self-heal without the app's next
      launch. Not independently tested with a forced two-failures-in-a-row fixture — the same test
      gap already accepted for `hasPinnedCrash`'s own failure path at the `MainActivity` layer in
      round twenty-six, for the same reason (no seam to inject a controllably-failing-then-
      succeeding `File.exists()` at this layer); the lower-level mechanism's own correctness is
      covered by `DebugFileSinkTest`. **Declined:** a suggestion that a crash file evicted from
      `cacheDir` between the banner showing and the user's Share tap should surface an explicit
      "try again" warning instead of the share completing looking clean. `SPEC.md` §4.6 is explicit
      that this exact race is anticipated and that silence is the *correct* behavior — "the banner
      checks the file is still there and stays silent if it isn't — offering to share a log that no
      longer exists is worse than saying nothing" — and unlike the earlier timeout/read-failure
      omission cases (4, 5, 34), a genuinely evicted file cannot be recovered by a retry, so a "try
      Share again" message here would be actively misleading rather than merely quiet. The banner
      also isn't left stuck: `consumeCrashPin`'s no-op success on an already-gone file still fires
      the crash-pin watch unconditionally (round 26), which re-reads `hasPinnedCrash` fresh and
      correctly clears `crashPending` once the share completes. Replied quoting the spec and
      resolved. One more, round twenty-nine: `DebugLogging.hasPinnedCrash`/`readPreviousOrCrash`'s
      own fallback for a null `sink` — meaning `install()` either hasn't run yet or genuinely failed
      and left `sink` permanently null — reported a confirmed clean result (`checkSucceeded`/
      `readSucceeded` both `true`) rather than a failed one. A crash.log from a previous run exists
      independently of whether this process's own `install()` call has succeeded, so a missing sink
      can never actually confirm absence — only "not checked", the same as the check itself
      throwing. Fixed by reporting a failed check/read instead, which composes for free with the
      retry `MainActivity`'s cold-start read already applies (round 28) for the ordinary,
      self-healing startup race; a persistent installation failure is the one case genuinely left
      unable to self-heal, matching round 28's own accepted stopping point. Covered by updating the
      two existing "before install" `DebugLoggingTest` cases to assert the corrected (failed, not
      clean) outcome. Round thirty brought two more, both fixed. `readPreviousOrCrash`'s own
      `!fileExists`/`readText()` failure paths still carried the stale `wasCrash = true` from the
      earlier crash-pin check forward even once the file was confirmed gone — reporting a vanished,
      unrecoverable cache-evicted crash as a retry-worthy read failure (the exact case round 28
      declined making loud, but reached this time through a different code path Codex found:
      `readText()` throwing after the file vanished in the gap before it, or the plain existence
      re-check finding it gone). Fixed by reporting confirmed absence (`wasCrash = false`,
      `readSucceeded = true`) for both, distinguishing a genuinely vanished file from a real read
      failure (a directory sitting where a file is expected, `DebugFileSinkTest`'s own fixture) by
      re-checking existence after the failure rather than trusting the exception's type — the first
      version of this fix used `FileNotFoundException`'s type alone and broke that existing test,
      since the JVM throws the identical exception for both cases; caught by the full suite before
      pushing. And `DebugReport`'s five capability-check helpers (`isPolicyAccessGranted`,
      `isPermissionGranted`, `isLocationServicesEnabled`, `isBatterySaverOn`, `isTileAdded`)
      substituted `false` on their own exception, so a transient system-service failure read in the
      shared report exactly like a confirmed denial or a confirmed-off toggle, with the only trace
      of the real cause sitting in logcat the report's recipient never sees. Fixed by returning
      `null` instead, rendered as "unknown" through `grantLabel`/`foregroundLocationLabel`/a new
      `boolLabel` helper. Origin/main moved during this round (an unrelated Play-update-banner PR
      touching the same shared UI files); rebased with three real conflicts resolved by hand
      (interleaved but non-overlapping additions each time) — full suite green throughout, and
      Roborazzi's own screenshot comparisons would have caught any real merge mistake. A
      thirty-first round brought a real fifth: the vanished-file recheck's own `file.exists()`
      call can itself throw, and `runCatching { ... }.getOrDefault(false)` discarded that
      exception the same way it discarded an honest "still exists" `false`, leaving it unlogged
      unlike every sibling check in the function — fixed by logging it before falling back;
      behavior unchanged (a failed recheck still falls through to the retry-worthy path, correctly,
      since it can't confirm the file is actually gone), purely closing the missing diagnostic. No
      dedicated regression test: `File.exists()` doesn't throw under normal JVM I/O conditions
      (only via a `SecurityManager`, unsupported cleanly on JDK 21's test harness), so there's no
      practical way to force this specific branch. A separate finding on the same round was
      **declined and flagged instead of fixed**: a *persistent* `DebugLogging.install()` failure
      leaves `crashPending` at its compile-time `false` forever — the exact residual gap the
      thirtieth round's own fix already named as accepted ("a persistent installation failure is
      the one case left genuinely unable to self-heal"), and the fourth finding on the same
      "boolean collapses a check-failure into confirmed absence" pattern (rounds 28, 30 ×2). Per
      the standing "stop patching once findings on the same mechanism stop converging" guidance,
      this one is recorded below under *Decisions needing review* instead of patched narrowly, and
      the review thread was left open rather than resolved. A thirty-second round brought a real,
      distinct concurrency bug: `deliveriesCompleted++`/`lastDeliveryResult = delivered` were
      written only under `deliveryLock`, but `nextAttempt()` reads `deliveriesCompleted` under
      `applyLock` instead — deliberately, so a tap never blocks on an in-progress delivery — so
      the two different locks guarding the same field gave that read no happens-before guarantee
      against the write, letting a retry's baseline snapshot land inconsistently between the
      counter bump and the result assignment and duplicate a delivery that had already reached the
      user. Fixed by also wrapping the write in `synchronized(applyLock) { ... }`, cheap since that
      lock's critical sections are already brief field writes and it's reentrant; `nextAttempt()`
      itself still never touches `deliveryLock`. No dedicated regression test: the race needs a
      window between two statements with no injectable seam, and now that both writes are atomic
      under one lock, that window no longer exists to construct a test against. A thirty-third
      round brought two more, both fixed. `foregroundLocationLabel` checked a confirmed coarse
      grant before checking whether the fine check had itself failed, so a failed fine check next
      to a confirmed coarse grant read as "granted (coarse only)" — a label that asserts fine is
      confirmed absent, which a failed check never confirmed; fixed with a distinct branch ahead of
      it ("granted (coarse); fine check failed"), leaving "coarse only" for a genuinely confirmed
      fine denial. And a cold start with Do Not Disturb access still missing routes straight to
      `PermissionsScreen` (`applyAccess`'s one-time onboarding routing) — the actual first-landed
      screen in that case, not `MainScreen` — so a crash from before that same cold start went
      unseen until the user finished onboarding and navigated back on their own, contradicting
      `SPEC.md` §4.6's own justification for the banner's placement ("the screen the user actually
      lands on"). Fixed by rendering the same `CrashBanner` on `PermissionsScreen` too, wired to the
      same `crashPending`/`shareFailed`/`dismissFailed` state, rather than suppressing the
      onboarding route — that would strand a user with both a missing permission and a pending
      crash on the disabled Arm screen instead of the interstitial that actually fixes the missing
      permission. `SPEC.md` updated to describe the real landing-screen behavior; new
      `PermissionsScreenScreenshotTest` coverage. A later round found the *third* instance of the
      same class — `onCreate` restores the screen from saved state, so a process killed while the
      user was in Settings is restored there, which also had no banner — and rather than patch a
      third screen, the rule itself was made exhaustive: **every screen renders the banner while a
      crash is pinned** — there were exactly three, so no future routing change could reintroduce
      the gap, and the spec no longer had to reason about which screen the user "lands on" at all.
      (Superseded when the licenses page landed: `SPEC.md` §4.6 now scopes the rule to **every
      screen a cold start can land on**, with `LicensesScreen` a stated exception and its cost
      written down. The reasoning above is unchanged for the three screens it was written about.)
      A thirty-fourth round brought one more, fixed:
      the mid-delivery reuse branch gated on `lastDeliveryResult.reachedUser` alone, but a
      delivery can reach the clipboard or chooser while its payload never actually carried the
      pinned crash (the previous-run read timed out, failed, or read back blank) — so an
      overlapping retry, whose own payload collection might genuinely have captured the crash,
      had its delivery skipped and its pin left unconsumed, making the explicit "try Share again"
      silently do nothing. Fixed by tracking `lastDeliveryPinConsumeSafe` alongside
      `lastDeliveryResult`, written under the same `applyLock` critical section so the pair stays
      consistent, and requiring it for reuse; an incomplete delivery now falls through the same
      way a failed one already does. Two existing mid-delivery tests were also corrected — they
      used `pinConsumeSafe = false` for the delivery they expected to be *reused*, which no longer
      qualifies.
- [x] **Simplified `DebugReport.share()`'s concurrency by coalescing at the tap** (maintainer,
      2026-08-23, in PR #89 itself rather than a follow-up). Twelve of that PR's forty-nine
      findings (10, 11, 12, 21, 27, 29, 30, 32, 40, 46, 49) were all in this one function's
      concurrency, and each fix had added another field or condition to the same mechanism:
      `attemptTicket`, `deliveriesCompleted`, `lastDeliveryResult`, `lastDeliveryPinConsumeSafe`, a
      `deliveriesAtIssue` map, and two locks — all to answer one question, *what happens when the
      user taps Share twice quickly*. **Landed**: `DebugReport.shareInFlight`, raised
      synchronously by `nextAttempt()` on the tap thread and cleared when that attempt's outcome
      lands, with both Share affordances (the Settings row and the crash banner's button) disabled
      on it and reading *Sharing…* while it holds. The second concurrent tap can no longer be made,
      so `deliveriesCompleted`, `lastDeliveryResult`, `lastDeliveryPinConsumeSafe`, the
      `deliveriesAtIssue` map and the whole overlapping-delivery reuse branch are gone — a net
      ~250 lines removed across the class and its tests. `deliveryLock` and the ticket check stay
      as the floor beneath the gate, since gating is a UI contract and the function must still
      behave if a future caller doesn't honor it. The flag is process-level for the same reason
      the ticket is: a configuration change mid-share must not hand the replacement instance a
      re-enabled button for a share still running. It clears before the pin-consume wait, not
      after, for the same reason the outcome always did (round 27) — holding the button disabled
      through up to two seconds of cleanup would read as the app doing nothing.
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
      **`access` settled 2026-08-31 (maintainer): keep the last reading, and say so accurately.**
      The box stays unchecked because one part does not close with it — see the Filters residue
      below. What was real about `access` is narrower than the entry above says, and was being
      mis-stated: the catch
      claimed the next refresh would ask again, which holds for `onStart` and a record change but
      not for the access *broadcast*, where the failed read was itself the notification and nothing
      is queued behind it.
      Two alternatives were weighed and declined. Clearing `access` to unknown reads like the safe
      answer and is not: `MainScreen` gates the entire primary-action block on `GRANTED`, so it
      would take `Release` away from a running snooze over a binder blip, and on a first-read
      failure it changes nothing since `access` is already null. Retrying behind an injected
      executor is the only option that closes the window and stays available if this is ever seen
      in the field — the seam is proven in `SnoozeNotifications`, and an injected clock is what the
      testing rules *require* for time-dependent behavior rather than something to avoid — but it
      was not worth the machinery for a read that fails only when the process is going down anyway.
      **Still open: the stale-*granted* Filters row.** An earlier draft of this entry claimed
      Codex's original `zenRuleId` ask was moot because `filtersRuleId` gates on
      `access == GRANTED`. That gate covers one direction only (Codex, PR #159): it hides the row
      on a stale *denied* reading, and a failed revocation broadcast leaves the opposite — `access`
      stale at `GRANTED`, so the row is still offered, deep-linking a zen rule the revocation
      removed. Left as-is rather than fixed, and recorded rather than closed: it needs the
      revocation and the read failure *together*, it costs a dead link rather than a snooze, and
      clearing `zenRuleId` in the catch would blank a working row every time the read merely
      blipped. Whatever eventually closes the `access` window — the injected-executor retry above
      is the candidate — closes this with it, since the stale reading is the shared cause.
- [x] `docs/PRIVACY.md` must describe what the log carries **before** the sharing surface ships —
      that ordering is the rule, not a preference (AGENTS.md, *Privacy*). **Landed** alongside the
      sharing surface above: "The debug log" section now describes the `Share debug logs` button
      and the crash banner, what the report adds beyond the log itself, and restates the floor.

## Phase 6 (M6) — Internal-track release on Play

- [x] **Open-source licenses page, linked from Settings.** (maintainer, 2026-08-23)
      **Landed**: `LicensesScreen.kt`, reached by a "Licenses" row at the foot of
      `SettingsScreen` beside the version and the privacy policy link — the same shape and
      position as the sibling Simmo repo's. Names only, one row each, with the version and
      the license behind a tap (`SPEC.md` §4.7). The list is read from a committed
      `res/raw/aboutlibraries.json` regenerated by `./gradlew :app:exportBundledLicenses`,
      one per flavor rather than one shared file, since `play` bundles Play's update library
      and `direct` bundles none of it; CI regenerates both and fails on drift.

- [x] **Crash reporting (Firebase Crashlytics), `play` flavor only.** (`SPEC.md` §12,
      `docs/crashlytics.md`) **Landed**: `play` declares `INTERNET` and reports crashes;
      `direct` gains neither the reporter nor the permission, so that flavor still cannot
      open a network connection at all. On by default with an opt-out at Settings → *Crash
      reports*; the `play` manifest starts Crashlytics with collection off and the app
      applies the stored choice at startup, so an opt-out install never begins collecting.
      Crashlytics **without** Firebase Analytics, so the Play "Advertising ID: not used"
      answer stays true (`DeclaredPermissionsTest` fails if `AD_ID` ever appears). The
      build activates it only when `app/google-services.json` exists, so fresh clones,
      forks and every CI job but `deploy` build with it dormant.

      Two follow-ups this leaves, both owned by the maintainer rather than the code:

      - [ ] **Create the Firebase project and set `GOOGLE_SERVICES_JSON`.** Until the
            secret is set in the `production` environment, the released AAB ships with
            crash reporting dormant — a green build that reports nothing, which is worth
            knowing rather than discovering from an empty console. Steps:
            `docs/crashlytics.md`.
      - [ ] **Decide whether a failed delete of queued crash reports needs its own
            warning on the switch** (Codex, PR #113, P2 — **deferred, not declined**).
            `deleteUnsentReports()` returns a `Task` whose completion this code discards, so
            a deletion that fails is reported to the user as a clean opt-out even though
            `docs/PRIVACY.md` promises waiting reports are deleted. The finding is right.

            Not fixed in that PR on purpose. Surfacing it properly means a **third** failure
            state on the row, distinct from "couldn't save this setting" — the shape the
            debug log already has with its separate `lastDisableCleanupFailed` line — and
            that needs new user-facing copy, which is the maintainer's to approve
            (`AGENTS.md`, *Translations* and *Concise copy*) rather than invented while they
            were offline. It also sits inside the mechanism the question below reopens, so
            it may be reshaped by that answer.

            The narrower alternative if a new row state is judged not worth it: observe the
            task and record the failure in the debug log only, so it is at least
            reconstructible, without adding a fourth thing the Settings row can say.

      - [ ] **Decide whether a Settings switch is enough, or Snoozemo needs a consent
            surface** (2026-08-28). Crash reporting now defaults off (`SPEC.md` §12), so the
            *absence* of collection is correct. But a switch the user has to go looking for
            is not the same as having been asked, which is what explicit consent means.
            Type Launcher has a consent card for this; Snoozemo has only the Settings row.
            Cost of adding one: a surface in front of a user who opened the app to snooze.
            Cost of not: reporting stays off for almost everyone, so crash visibility is
            near zero in practice.

      - [ ] **Decide what the off switch means — maintainer's impression recorded, not yet
            confirmed** (2026-08-25). The leaning: off means **no crash reports** — the
            feature, not the network — with defaults staying on for Play
            in-app updates, and `INTERNET` not treated as a problem in itself, since most
            Play apps hold it and defending its absence costs the product without buying the
            user anything. Written down as the working direction; **confirm before any of it
            is worded as a promise in `docs/PRIVACY.md`.**

            **The invariant that does matter: no user data leaves the device, and anything
            that does is under the user's configuration.** That is what users and the EU
            care about, and it is the line to hold and to keep testing. Crash reports carry
            a stack trace, device model and app version — no coordinate, no SSID/BSSID, no
            place name, no snooze timing, and no debug log — because nothing attaches custom
            keys or breadcrumbs. The user can switch even that off. `SPEC.md` §12's floor is
            unchanged, and `DeclaredPermissionsTest` plus the absence of any key-attaching
            code are what keep it honest.

            Consequences already applied, and both safe under either answer: the shipped
            switch already gates the feature, so no behavior changed; and the release
            pipeline's Data Safety gate keys on whether crash reporting is **enabled in the
            build**, not on whether the manifest holds a permission
            (`.github/workflows/ci.yml`, `PLAY_DATA_SAFETY_DECLARED`).

            **What deciding the stronger option would take**, so the choice is costed rather
            than guessed: drive `firebase_data_collection_default_enabled` (verified present
            in `firebase-common`'s `DataCollectionConfigStorage`) from the same switch, then
            confirm on a device with a network monitor that nothing else in the Firebase
            stack still calls out — Firebase Installations declares `INTERNET` of its own and
            this sandbox cannot observe it. The claim is only worth making in
            `docs/PRIVACY.md` if that check is actually done.

      - [ ] **Update the Play Console Data Safety form before the next `play` upload.**
            It moved from "no data collected, no data shared" to *crash logs,
            diagnostics, and device or other IDs — collected, not shared, optional*
            (`docs/play-store-declarations.md`). Shipping crash reporting under the old
            answer is a policy violation, not a stale doc, so this one is blocking rather
            than tidy-up.

            The third type was the maintainer's call (2026-08-25) after Codex pointed out
            that `docs/PRIVACY.md` already says Crashlytics records an installation
            identifier, so omitting it would under-declare. The separate **Advertising
            ID** question stays *no*: that one is about `AD_ID`, which Firebase Analytics
            carries and Snoozemo deliberately does not — `docs/play-store-declarations.md`
            spells the distinction out, since answering it yes off the back of the Data
            Safety row would be wrong.

- [x] Release signing and a `deploy` job that builds a downloadable AAB. **Landed**:
      `signingConfigs["release"]` (`app/build.gradle.kts`) reads the upload keystore from
      `RELEASE_KEYSTORE_FILE` and its companion env vars, attaching only when they're present so
      a fresh clone still builds unsigned; the `deploy` job in
      `.github/workflows/ci.yml` builds `:app:bundlePlayRelease` on every push to `main`
      and publishes it as a downloadable `app-release-aab` workflow artifact, for the manual seed
      upload Play requires (`docs/play-store-internal-track.md`). R8 was the one piece held back
      from this slice; it has since landed as its own follow-up, below.
- [x] **Run the shipping build through R8 — shrinking, optimization and obfuscation**
      (`SPEC.md` §3.7). **Landed**: `isMinifyEnabled` and `isShrinkResources` are on for the
      release build type on every machine, not only in CI (PR #122), and the pull-request build
      job builds the release APKs so that coverage lands on every PR. The debug build deliberately does not run R8:
      AGP disables optimization *and* obfuscation for debuggable builds (it warns if you ask
      anyway), so minifying it could only run the shrinker — a strict subset of what the release
      variants already cover, at the cost of a slower build (Codex, PR #121). So the debug
      build is the one that stays fast to iterate on; a local *release* build runs R8 like
      any other, which is the point — the artifact that ships is the one anyone can reproduce.
      **This started as a shrink-only run (`-dontoptimize -dontobfuscate`) and was changed**
      (maintainer, 2026-08-26): from February 2027 Play requires a minimum of 25% coverage across
      *optimization, shrinking and obfuscation*, measured as DEX code optimization, with reduced
      visibility and publishing capability below the threshold. Shrink-only leaves two of the
      three at zero, so it could never have satisfied it.
      Sizes, release APK, unminified against full R8: `play` 29.2 MB -> 3.2 MB and `direct`
      25.7 MB -> 2.3 MB. But almost all of that is the shrinker: against the *shrink-only* build
      this PR started from (6.6 MB and 4.8 MB) the APKs halve again, while the **AABs barely move
      at all** — 4.3 MB and 3.2 MB, within ~2%, marginally larger. Optimization and obfuscation
      are here for the threshold, not for bytes; say so rather than claiming a size win for them.
      **Verified in the sandbox, not on a device**: both flavors' `assembleDebug` and
      `bundle*Release` build clean with no `missing_rules.txt`; every one of the app's 106 strings
      and all of its own drawables, raw and xml resources are `reachable` in the shrinker's
      `resources.txt` (what it drops is library-owned — `androidx.window` attributes, Play
      Services' sign-in glyphs, androidx-core's pre-Lollipop notification layout assets); every
      manifest-declared component, `BackstopWorker`, and AboutLibraries' `$$serializer` classes
      are present in the shrunk dex. What is still owed is the install: **run a CI-built APK of
      each flavor on a handset** before trusting a shrunk build, and re-check the Licenses screen
      in particular, since its JSON parse is the one reflective path in the app.
      Firebase ships its own shrinker directives — `firebase_common_keep.xml` (which keeps
      `google_app_id`, `gcm_defaultSenderId`, `google_api_key` and the rest of the
      google-services–generated strings) and `firebase_crashlytics_keep.xml` — and both arrive
      with the AARs, so they are in every `play` build's merged resources whether or not the
      plugins are applied. Confirmed present in the shrinker's report; no rule needed here.
- [ ] **PR CI still doesn't run the Crashlytics plugin's own R8-era tasks** (Codex, PR #121).
      The `deploy` job materializes `app/google-services.json` from `GOOGLE_SERVICES_JSON`, so
      only there do the google-services and Crashlytics plugins apply; a pull-request build has
      neither. Most of that gap is narrower than it looks — the plugins add *tasks and generated
      resources*, not classes, so R8's code input is byte-for-byte the same either way, and the
      generated resources are keep-protected by the AARs above. What is genuinely new and
      genuinely unexercised is the mapping-file upload the Crashlytics plugin registers **only
      when minification is on**: turning R8 on activates a deploy-job code path that has never
      run, and the deploy job is where it would first fail. Two things to settle, and both are
      the maintainer's:
      1. **The mapping upload is now required, not pointless** — this reversed when the build
         went from shrink-only to full R8 (2026-08-26). With obfuscation on, a Crashlytics stack
         trace is unreadable without the mapping file for that exact build, so
         `mappingFileUploadEnabled` must stay **on** and the upload must actually work. It still
         cannot be exercised from a sandbox with no `google-services.json`, which makes the first
         `deploy` run after this lands the thing to watch: confirm a `play` crash de-obfuscates in
         the console before trusting the channel. **`direct` has no reporter at all**
         (`SPEC.md` §12), so its traces are now obfuscated with no mapping anywhere — the cost of
         that flavor's independence, recorded so it is a known state rather than a surprise.
      2. Whether a pull-request build should get a Firebase config at all. Codex's suggestion,
         and it would close the gap properly — but it means a secret in a `pull_request`-triggered
         job that runs PR-controlled build code, against a repo that deliberately builds
         Crashlytics-dormant everywhere outside `deploy` (`docs/fork-safe-ci.md`,
         `docs/crashlytics.md`). That is a CI security-posture decision, not an implementation
         detail.
- [ ] **Automatic upload to the Play internal track.** Deliberately not the first `deploy`-job
      PR's scope (maintainer, 2026-08-22): a `r0adkll/upload-google-play` step plus the "What's
      new" generation from commit subjects described in `AGENTS.md`, and their own
      doc/secrets-table additions, were built and then pulled back out so that PR could stay
      focused on the manual-build path.
      **The code half has since landed, opened as its own PR (2026-08-23) rather than merged
      straight to `main`** — the release-notes generator (ported from the sibling
      Simmo/Type Launcher repos' deploy job, unchanged in shape), the `Compose Play Store release
      notes` and `Upload to Play Store internal track` steps, and `docs/play-store-internal-track.md`'s
      matching setup steps (4-6: enable the API, create the service account, grant it "Release to
      testing tracks" only) and secrets-table entry. Every added step gates on
      `PLAY_SERVICE_ACCOUNT_JSON` being present, so the PR is safe to merge on its own — nothing
      uploads until the secret exists.
      **Deliberately held un-merged pending a maintainer check** (maintainer, 2026-08-23): the Play
      Console declarations this doc's own "App content declarations" section describes (Data
      safety, content rating, target audience) and the permissions declaration form once the
      demonstration video lands, plus confirming the service account is actually granted only
      "Release to testing tracks" and never a production-track scope. Merging the workflow change
      itself doesn't publish anything — only adding `PLAY_SERVICE_ACCOUNT_JSON` afterward does —
      but the check is on the PR, not the secret, so a reviewer doesn't have to re-derive from the
      diff what to verify before flipping it on.
- [x] **No Firebase App Distribution here.** Dropped before it was ever built — the sibling
      repos ran it alongside the Play internal track, publishing every push to the same
      testers, and have all since retired it. The internal track is the only channel this
      repo is wiring up; a debug APK is something you build locally, not something CI hands
      out. Decision and the trade that would re-open it: `SPEC.md` §3.7.
- [x] Make a release build **fail** when its version can't be derived from git, rather than
      warning (`app/build.gradle.kts`). The fallback exists so a checkout without git still
      builds; once a build can reach a tester or Play, falling back to versionCode 1 is
      either a rejected upload or a phantom downgrade, and a warning in a CI log is not
      where anyone would find it. Same for the shallow-clone case, which is worse because
      the count *looks* fine — the build already warns; a release build should refuse.
      *(Landed: `checkReleaseVersionDerivation` fails every `pre*ReleaseBuild` when the
      count, hash, or clone depth kept the version from being derived; debug builds, tests,
      and lint on a shallow checkout are untouched.)*
- [ ] **Gate a release build on the merged `playRelease` manifest**, the way
      `checkReleaseVersionDerivation` gates it on a derived version. `DeclaredPermissionsTest`
      asserts the permissions the Play declarations rest on, but unit tests run on the debug
      build type alone (`app/build.gradle.kts` — enabling release unit tests needs
      `compose.ui.test.manifest` moved off `debugImplementation` first, or the screenshot tests
      lose the activity they launch), so it reads `playDebug`. Nothing today makes the two
      differ — no release source set, no `releaseImplementation`, and the permission sets match
      apart from the applicationId suffix on `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — so
      this closes a gap by construction rather than fixing a live bug (Codex, PR #102). A task
      reading `SingleArtifact.MERGED_MANIFEST` for the `playRelease` variant and failing on
      `INTERNET`, `AD_ID`, a typed `FOREGROUND_SERVICE_*`, or any `foregroundServiceType` is
      the shape; hook it the way the version check hooks `pre*ReleaseBuild`.
- [ ] **Maintainer decision: does WorkManager's merged `FOREGROUND_SERVICE` permission matter?**
      The `play` release manifest carries `android.permission.FOREGROUND_SERVICE` and `WAKE_LOCK`
      from WorkManager, though Snoozemo's own manifests request neither and no service declares a
      `foregroundServiceType` (`DeclaredPermissionsTest` pins the typed permissions and the
      service types — not the bare `FOREGROUND_SERVICE`, and not `WAKE_LOCK`, so a change to
      either of those goes unnoticed by it). Play reviews the
      *type*, and there is none — so the expectation is that no Foreground service types section
      appears in Console. Confirm that when filing the other declarations; if a section does
      appear, it needs a decision before upload rather than a form filled in on the spot, since
      `SPEC.md` §3.3's whole argument is that Snoozemo must not enter a foreground-service review.
      Reasoning and the options are in `docs/play-store-declarations.md`.
- [ ] Data Safety declaration: **crash logs, diagnostics, and device or other IDs — collected,
      not shared, optional** (`SPEC.md` §12). This item used to read "no data collected, no data
      shared"; that answer was correct until crash reporting landed and is now false of a
      reporting-enabled `play` build, so filing it would be a policy violation rather than a
      stale note (Codex, PR #113 — it was still standing here after the rest of the sweep). The
      separate **Advertising ID** question stays *not used*. The field-by-field answers and their
      reasoning are in `docs/play-store-declarations.md`, together with every other App content
      questionnaire and the drafted text for the background-location permissions declaration;
      filing them in the Play Console is the maintainer's own step, and publishing a
      reporting-enabled build is gated on it (`PLAY_DATA_SAFETY_DECLARED`).
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
        instead. **Confirmed (maintainer, 2026-08-23, real Pixel 9)**: it shows the real system
        picker, matching ClothesCast's own `LocationSettings.kt`'s identical
        `launch(ACCESS_BACKGROUND_LOCATION)` call — Snoozemo's `MainActivity.kt` already uses the
        same `registerForActivityResult(ActivityResultContracts.RequestPermission())` pattern, so
        there was nothing to change here; this was purely the device-matrix uncertainty the
        earlier note flagged. Filming step 1 (below) is done — the remaining steps are open.
      - **Plan for filming the demonstration video** (maintainer asked for this 2026-08-22; step 1
        done 2026-08-23, steps 2-7 not yet executed — reordered 2026-08-23 to insert a permission
        reset before recording and move arming after it, Codex, PR #98). Order matters — the first
        step below
        decided whether the later ones were even possible to film as written:
        1. ~~Resolve the background-dialog question first~~ — **done (maintainer, 2026-08-23,
           real Pixel 9)**: tapping the `Location` row twice (foreground, then Continue on the
           rationale dialog) shows the real system "Allow all the time" picker. No
           Settings-fallback fix needed; the video can show a real system prompt as planned.
        2. **Reset location permissions before doing anything else** (Codex, PR #98, second
           pass — a real ordering bug in this plan, caught immediately after step 1's
           confirmation left both permissions granted on the test device). Settings → Apps →
           Snoozemo → clear permission + storage, so the `Location` row is back in its
           `ASKABLE` state and `LocationPromptStore`'s denial history is gone too. This has to
           happen **before** arming, not after: `fixLocation()`
           (`app/src/main/kotlin/app/snoozemo/ui/MainActivity.kt:842-856`) opens the app's
           Settings page instead of a dialog once both permissions already read `GRANTED` — so
           filming step 4 below with permissions already granted (as they are right now, from
           confirming step 1) would show nothing but a Settings-page deep link, not the prompts
           the video needs. And resetting *after* arming would revoke the very grant the just-
           armed snooze depends on, degrading or ending it before steps 5-6 could film a real
           departure.
        3. **Enable mock locations**: Developer Options → "Select mock location app", plus one of:
           a joystick/route-simulation app from Play (e.g. "Fake GPS Location-GPS JoyStick" or
           "Fake GPS Location and Joystick" — both use the same official test-provider mechanism,
           no root); or a command-line-driven mock provider for scripted, repeatable moves —
           [android-mock-location-for-development](https://github.com/amotzte/android-mock-location-for-development)
           or its fork
           [android-mock-location-from-command-line](https://github.com/jarridgraham/android-mock-location-from-command-line)
           take lat/lon over `adb shell am broadcast`; Appium's `io.appium.settings` app supports
           the same via `adb shell am start-foreground-service`. Either way this is what stands in
           for a real walk, so the phone never has to physically leave — and a scripted/route
           option can move faster than actual walking speed if a real-time walk would make the
           recording too long.
        4. **Record in one take** (`adb shell screenrecord` or the device's screen recorder),
           starting before tapping the `Location` row: the foreground system dialog → grant →
           the background rationale dialog → Continue → the background system dialog → grant
           "Allow all the time".
        5. **Still in the same recording, now that permissions are granted**: arm a snooze at
           the current location (from the tile or `DebugScreen`) so the geofence registers
           around the phone's real position with full tracking from the start — the
           geofence/presence work landed in Phase 3 as of 2026-08-22, so this is now real, not a
           stub. Armed *after* the permission grant this time (see step 2's own note on why the
           original ordering had this backwards), not before it.
        6. **Still in the same recording**, use the mock-location app to move the reported
           position outside the anchor's geofence radius, and show the exit actually firing —
           the ongoing notification updating and Do Not Disturb turning off — so the video
           demonstrates the permission driving the feature, not just being requested and left
           unused.
        7. Trim and attach the recording to the Permissions Declaration Form alongside a written
           justification. **Correction (2026-08-23): that justification is not actually drafted
           anywhere yet** — `docs/play-store-internal-track.md` only notes that the form exists
           and what it's blocked on; this step still needs the written text itself, not just the
           video.
        - **Open question, checked 2026-08-23, still not settled:** whether Play's reviewers
          accept a video that shows Android's own "mock location app active" status-bar indicator
          during the walk-away portion, or expect an unmistakably real walk instead. Google's own
          Play Console Help page on background location (`support.google.com/googleplay/android-developer/answer/9799150`)
          says the video must "clearly demonstrate the location-based feature," without stating
          how the location change may be produced — no explicit confirmation or prohibition of
          mock/simulated location found in official docs or Play Console Help community threads
          after a real search. The practical reasoning for using mock location anyway: showing a
          real home/office location in a submitted video is itself worth avoiding, and mock
          location via Android's own official Developer Options mechanism is standard practice —
          but this is still a real, unverified risk, not a confirmed-safe path. Still worth a
          direct check (or asking in the Play Console Help community) before relying on it for
          the actual submission — a rejected declaration costs a real review cycle.
- [x] **Resolved 2026-08-24 — `setup_location_allowed` (renamed from `setup_location_granted`)
      reads "Allowed"**, not "Tracking your place": settled by the same app-wide `Allow`/`Allowed`
      standardization above, not as a standalone copy change. This also closed the `direct`-flavor
      concern Codex flagged — "Tracking your place" claimed a capability `DurationOnlyPresenceMonitor`
      (`presence/src/direct/`) cannot provide until Phase 7's foreground-service monitor lands,
      and "Allowed" reads true regardless of what the permission is *for*, so no `direct`-specific
      special case was needed.
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
- [x] **Store graphics under version control, drawn by Android.** Landed as
      `docs/play-store/` — the 512 px app icon and the 1,024 × 500 feature graphic,
      recorded by `PlayStoreGraphicsScreenshotTest`, a Robolectric test with native
      graphics that draws the adaptive icon's own layers through
      `Drawable.draw(Canvas)`. That is Skia, the same rasterizer the launcher uses, so
      the store icon cannot disagree with the launcher icon — it is not an
      interpretation of the same file, it is the same drawing code. The layers come
      from `ic_launcher` itself, so repointing the adaptive icon carries the store
      graphics with it. Both still go up by hand — no API upload path is wired for
      listing graphics.
      **Two earlier attempts went the other way and are worth not repeating.** The
      first transcribed the mark into HTML and screenshotted it with headless Chromium,
      and the copy then needed policing — allowlists for path, group and root
      attributes, for CSS selectors and properties, plus rules for quoting, whitespace,
      comments, multiline and uppercase tags, `!important`, and media queries. Nine
      rounds of Codex review, roughly three real holes each, no sign of converging.
      The second removed the copy and wrote a Pillow renderer that parsed the drawables
      and rasterized them directly; that moved the problem one level down and failed the
      same way — ten more rounds, every finding genuine, each one a piece of 2D
      rasterization it had got wrong (fill rules, caps, joins, closed-contour seams,
      flattening tolerance, gradient projection). Measured against Android on the same
      drawable it still differed in 1.8% of the icon's pixels, by up to 107/255.
      **The lesson generalizes:** reading a file is not knowing what a browser will draw
      from it, and writing a renderer is not knowing whether it matches Android — that
      is not a question a renderer can answer about itself. Use the platform's, which
      runs in a JVM unit test and costs no new dependency.
- [x] **CI notices when the committed store graphics go stale.** The graphics are
      recorded screenshots now, so the screenshot job regenerates them on every PR that
      touches the app and a `Fail on stale Play Store graphics` step fails the build if
      the committed PNGs differ. The font objection that made this awkward is gone with
      the Pillow renderer — type is drawn by Android from the android-all jar, which is
      identical on a runner and locally, where "whatever Liberation Sans the machine
      has" was not.
      Unlike the UI snapshots, drift here is **not** auto-committed: `sync-screenshots`
      delegates to `mikelward/ci-commit-artifact`, which commits one artifact into one
      `dest-path`, and widening that means changing a deliberately narrow fork-safe
      mechanism shared with other repos rather than changing this one. So a stale
      graphic fails and is re-recorded by hand. Worth revisiting if the reusable
      workflow ever takes more than one path. (Simmo auto-commits its own, since it
      still does that inline.)
- [ ] **Record the store graphics from the shipping variant, and retire the source-set
      guard** (Codex, PR #101 — eight findings). The graphics are recorded from
      `playDebug` while Play ships `playRelease`, so a variant-specific icon override
      would leave CI green on artwork nobody installs. `no build type redefines the
      launcher icon` guards that by scanning source sets, and it is a *proxy*: it went
      through nine reshapings (manifest icon, build-type source sets, transitive
      resource names, configuration qualifiers, values parsing, namespace parsing,
      non-`ic_launcher` dependency names, qualifiers beyond `night`, and configuration
      overrides under `main`) and two of those were only closable structurally — parsing
      by using a real XML parser, and configuration by rendering in `+night` and
      comparing plus `no configuration variant redefines the launcher icon`, which
      refuses a qualified alternative outright rather than enumerating what to render.
      The transitive-name one was then closed too, by inverting it: rather than
      following what the icon references, `the icon is built only out of resources named
      after it` asserts that every reference inside a prefix-named icon resource is
      itself prefix-named, so the set the scans cover is closed under reference.
      What remains known-open is two coverage gaps, both recorded rather than patched.
      Density-qualified alternatives: Android does not require
      `drawable-hdpi/ic_launcher_foreground.xml` to hold equivalent artwork, and neither
      rejecting density variants (which breaks the legacy mipmap layout) nor comparing
      them (image similarity across sizes) is a decision a name scan can make. And the
      round-icon comparison renders the two icons and compares pixels, which is the color
      icon only — a round icon aliased to an adaptive icon differing *just* in its
      `monochrome` layer would compare equal, so a themed launcher could show something
      the store does not.
      **The guard has stopped converging** — nine reshapings, each fix drawing the next —
      so further findings against it belong here rather than in another scan.
      The real fix is to record from the variant that ships, which makes the whole class
      moot. It needs release unit tests enabled and `androidx.compose.ui.test.manifest`
      moved to `testImplementation` first — `app/build.gradle.kts` carries a comment
      warning that doing it wrong breaks every screenshot test on a variant with no
      activity to launch. That is a test-configuration change for the whole suite, so it
      wants doing deliberately rather than inside a store-graphics PR.
- [ ] **Screenshots are recorded against the PR head, not the merge ref** (Codex,
      PR #101). The `screenshot-tests` job deliberately checks out the head branch on a
      same-repo PR, so that what it renders matches what `sync-screenshots` would commit
      to. The cost is that a change landing on `main` after the branch was cut is not
      reflected: the PR's recordings — UI snapshots and store graphics alike — can pass
      while the merged result would differ, and `main`'s own run is then the first thing
      to fail. Pre-existing and not specific to the store graphics; closing it means
      rendering against `refs/pull/N/merge` and reconciling that with where the refresh
      commit lands, which is a change to the fork-safe CI design rather than to any one
      test. Simmo has the same shape.
- [x] **The Play icon is cropped to the 72dp a launcher shows.** An adaptive icon's
      layers are a 108dp canvas of which only the central 72dp is visible, so rendering
      the whole canvas made the store mark read a third smaller than the installed one.
      It now crops the way a launcher does, and the mark matches on-device size.
      typelauncher's renderer does the same. Play still applies its own rounded corners,
      so the file stays a full opaque square with no mask baked in. Simmo matches.
- [ ] Approve the store listing copy, then adopt the fastlane metadata layout for it
      (this doc's "Store listing" section). The feature graphic currently carries
      `README.md`'s line as a stand-in and follows the approved short description once
      there is one.
- [ ] Ship to the internal track — the point at which the declaration outcome becomes
      known.

## Release secrets and docs — needs a maintainer pass

- **Reconcile `docs/play-store-internal-track.md` with how the release secrets
  are actually scoped and what CI now does** (Codex, PR #122; deferred there
  deliberately). Two things are out of step and only the maintainer can settle
  either:
  - The doc says the keystore secret is **"Environment scope only — never as
    repository secrets"**. The maintainer reports the secrets are currently
    **repository-scoped**, with a move to environment scope planned but not
    done. So the doc describes the intended end state as though it were
    current. That mismatch already caused one wrong review conclusion, and it
    will mislead the next reader the same way.
  - The doc still promises a fresh repo without the keystore gets a green run
    with the release steps skipped. That is now true only for **forks**: a
    non-fork push to `main` that cannot produce a signed AAB fails in
    `Require a release keystore`, which was a deliberate decision — a release
    that silently never happened used to be indistinguishable from one that
    worked.

  Not fixed in PR #122 on purpose: rewriting the setup contract requires
  knowing which repository is in which state, and inferring that from the same
  document that is wrong about it is how a confident wrong statement gets
  committed. CI behavior itself does not depend on the answer — the skip gates
  on fork status rather than on the secret, so it holds under either scope.

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
12. [ ] **`setBypassDnd(true)` actually keeps `snooze_active` / `snooze_urgent` audible and
        visible through Snoozemo's own zen rule, and `snooze_ended` genuinely does not**
        (`SPEC.md` §5.7). Arm a snooze, confirm the ongoing card and a triggered stuck-rule
        card still alert as expected rather than being filtered like an ordinary notification —
        and confirm a routine `showEnded()`/failure card on `snooze_ended` is filtered normally
        when an *unrelated* DND source (e.g. Bedtime) is active, per the maintainer's decision
        to leave that one subject to other DND rules. Also confirm the per-channel "Override Do
        Not Disturb" toggle in Settings reflects each flag and that switching it off there is
        honored (the user's explicit override, per §5.7). **Test on a fresh install or after
        clearing app data** — like importance, this is set at channel creation.
13. [ ] **What Firebase's own `ContentProvider` costs a cold tile tap** (`SPEC.md` §4.1,
        §12). Firebase initializes during process creation, ahead of `Application.onCreate`
        and therefore ahead of the trampoline — so it is not *inside* the arm path, but it
        is time added before any of Snoozemo's code runs, and "one tap, under a second,
        from a cold process" is the claim it could erode. Measure a cold tile tap to zen
        rule `STATE_TRUE` on a `play` build with a Firebase config against one without, on
        the same device. Nothing in this sandbox can: there is no emulator, and an emulator
        would not answer a cold-start-timing question anyway.
14. [ ] **A crash actually reaches the console, and the opt-out actually stops it**
        (`docs/crashlytics.md`, *Verifying it*). Needs a device and a build made from a
        checkout carrying `google-services.json` — the switch is absent without one, which
        is itself the first thing to check. Crashlytics uploads on the launch *after* the
        crash, so each pass needs a relaunch.
15. [ ] **The tile repaints while the shade is open** (`SPEC.md` §4.2, Phase 2). With the
        shade down and the tile visible, tap `End now` on the ongoing notification below it,
        and let a cap fire with the shade open: the tile should flip to `Snooze here` without
        the shade being closed and reopened. Then confirm the passive bind is still intact —
        reopen the shade twice a few minutes apart and check the countdown subtitle has moved,
        which is what an active tile would have cost. Nothing in this sandbox can check either:
        `TileService` and `qsTile` aren't practically testable, so only the register/notify
        seam behind them is covered by unit tests.
16. [ ] **The Wi-Fi callback returns a real SSID, not a redacted placeholder** (`SPEC.md`
        §6.4, Phase 3). Associate with a network, arm, and confirm the captured anchor names
        the network rather than the redaction placeholders. The flag and the permissions are
        in place and `AnchorCapture.sanitizeSsid` rejects the placeholders by name, so what
        is unverified is only whether the platform hands the real value back on a real
        device. What a redaction costs is **the Wi-Fi capability, tracked**, not a silent
        failure: the rejected value becomes `ssid = null`, `supportedModes` then omits
        `WIFI_ONLY`, and the snooze runs on the coordinate anchor or honestly degrades to
        duration-only and says so. Worth measuring because losing the D4 suppressor changes
        how the product behaves indoors, not because anything fails quietly.

17. [ ] **A CI-built, R8-shrunk APK of each flavor actually runs** (`SPEC.md` §3.7, Phase 6).
        The shrink is verified from R8's own reports — no missing rules, every app-owned
        resource reachable, every manifest component and `BackstopWorker` present in the dex —
        but a shrinking bug is by definition one the reports did not predict, and no build
        this repo has ever installed on a phone was minified, and obfuscation makes that gap
        matter more than shrinking alone did. **It has to be a release build**: nothing in CI
        uploads an APK (Codex, PR #121 — `deploy` publishes only the `app-release-aab`
        artifact), and a debug APK would not do even if one were published, since AGP disables
        optimization and obfuscation for debuggable builds.
        **Build it signed, or it will not install** (Codex, PR #121): the release
        `signingConfig` attaches only when all four `RELEASE_KEYSTORE_*` / `RELEASE_KEY_*`
        variables are set, so a bare `./gradlew assembleRelease` writes
        `app-*-release-unsigned.apk` and `adb install` refuses it. Point the same env vars at the local debug keystore, which needs no secrets:

        ```sh
        RELEASE_KEYSTORE_FILE=$HOME/.android/debug.keystore \
        RELEASE_KEYSTORE_PASSWORD=android RELEASE_KEY_ALIAS=androiddebugkey \
        RELEASE_KEY_PASSWORD=android \
        ./gradlew assembleRelease
        ```

        Verified to produce installable APKs signed by `CN=Android Debug`. The release
        applicationId carries no `.debug` suffix, so it collides with an installed Play build —
        uninstall that first rather than fighting a signature mismatch. Release builds minify
        on any machine now, so those vars alone reproduce exactly what CI builds — `CI=true` is
        no longer needed and `isCiBuild` no longer exists (PR #122). Then walk both flavors: arm
        from the tile, end a snooze, **reboot mid-snooze** (that is the enum
        round-trip through `valueOf` that a renamed constant would break, and the failure would
        be a snooze that never ends), and open the Licenses screen (its JSON parse is the app's
        one reflective read). Once is enough; after that every pull request exercises the
        pipeline.

### The calendar action

Added 2026-08-31 with the feature (`SPEC.md` §4.3). Neither can be answered in this
sandbox: Robolectric has no calendar provider, and Quick Settings' truncation is a device
question.

- [ ] **A real provider answers the `Instances` query.** Grant `READ_CALENDAR`, arm with a
      meeting ending inside the cap, and confirm the third action appears with that end
      time — then that it is *absent* for an all-day entry, a declined invitation, a "free"
      block, and a meeting ending past the cap.
- [ ] **`Until 17:00` fits beside `End now` and `+30 min`.** Three actions is the most this
      card has ever carried, and a notification truncates action labels before it wraps
      them. Check both a 12-hour and a 24-hour device setting, since `5:00 PM` is the longer
      of the two.
- [ ] **The card gains the action rather than flickering.** The read runs after the post, so
      the card goes up with two actions and is reposted with three — confirm that reads as a
      fill-in and not as a card that redrew itself.

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

- [x] **The shared logger's opt-out now does what `SPEC.md` §4.6 promises**
  (raised by Codex, PR #153; **answered by the maintainer 2026-08-31**, and
  fixed in `mikelward/androidlog`). §4.6 says *"Turning the setting off deletes
  what was kept, immediately: the current run and every earlier one still held,
  pinned crashes included."* The library's `purgeOnWorker` deleted **only**
  `current` and `temp` — prior runs survived an opt-out by a decision recorded
  in that repository (Codex, its PR #4) — so every On→Off toggle left each
  `androidlog-prev-*.log`, pinned crashes included, while the setting and
  `optOutPurgeFailed` both reported success.
  **Resolved the app's way, not the library's**: the opt-out now deletes prior
  runs too. The cost is real and was taken deliberately — an opt-out destroys a
  crash the user has not sent yet — on the grounds that §4.6 had already
  promised it, and that a control reporting success over files it left behind
  is the worse failure. Snoozemo is the only consumer with a switch, so it is
  the only app whose behavior changes today.
  **The compounding `start()` defect is fixed with it.** A start that finds
  recording off now purges instead of rotating, so content the user opted out
  of is no longer moved into the shareable prior-run set — and that also closes
  the "killed before the purge drained" window the library had left open, since
  the persisted setting is itself the durable record that gap needed.
  **The app-side replay stays reverted.** `fileSink.onCleared()` after
  attaching purges only `current`, already rotated away by then, so it
  published `optOutPurgeFailed = false` over surviving files. The library fix
  makes it unnecessary.

- **An oversized crash log can never be consumed by sharing** (Codex, PR #153;
  route 3 landed, the real fix still open). `DebugReport.omitted` refuses to consume the
  crash pin whenever the report's own 25,000-character bound dropped any of
  what was read — a guard added earlier on that same PR, because a pinned
  crash can be an older run that newer ordinary ones push out of the tail, and
  consuming it there would lower the banner over a report that never carried
  the crash. For a crash whose runs exceed that bound, the same input truncates
  the same way every time, so the refusal is **permanent**: sharing can never
  lower the banner, and the user has to dismiss it by hand. Not a strand — the
  report still lands, the evidence still exists, and Dismiss still works — but
  it is a control that visibly never does what it says.
  **No fix is available to this app alone.** The question the guard would need
  to ask is "did the *crash's* portion survive", and the concatenated text
  carries no marker saying where each run begins; `PreviousRun` exposes `text`
  and `complete`, with `files` internal and no way to request the crashed run
  on its own. So all three options change `mikelward/androidlog`, which four
  apps compile:
  1. **Per-run boundaries in the handle** — the report could then ask about the
     crash specifically. Biggest, and every consumer re-renders.
  2. **A read for the crashed run alone**, given its own section and its own
     budget, so it is never the thing truncated away. Smaller, but adds a
     second read and a second section to the report format.
  3. **Leave it, and say so in the UI** — tell the user the report could not
     carry the whole crash and that Dismiss is the way to clear it. Cheapest;
     needs approved English copy, so it is not autopilot's to write.
  **Decided (maintainer, 2026-08-31): route 3 for now, with 1 or 2 still to
  do.** The report says `(crash details too large to include - dismiss the
  banner to clear)` when a pinned crash was among what the bound cut off, so
  the user is told rather than left tapping Share. The deadlock itself
  remains — this explains it, it does not fix it.
  **Constraint for whoever takes 1 or 2 (maintainer, 2026-08-31): if there
  are multiple sections they all need limits.** That is already how the report
  works — `MAX_STRUCTURED_CHARS` 4,000 + `MAX_PREVIOUS_RUN_CHARS` 25,000 +
  `MAX_LOG_PAYLOAD_CHARS` 30,000 = 59,000, under `MAX_SHARE_PAYLOAD_CHARS`
  60,000 — so a separate crash section cannot be *added*: its budget has to be
  carved out of the existing 25,000, or the total raised, and 60,000 is there
  because share targets choke past it. That makes route 2 dearer than it first
  looks.
  Note also that no budget scheme removes the deadlock on its own: a single
  crash run larger than its own section still cannot be carried whole. Route 2
  only closes it if a *truncated but present* crash is then treated as
  consumable — which looks right, since the user did send the crash's tail.

- **A skipped ordinary run is never announced in the report** (Codex, PR #153;
  same family as the entry above). When the library cannot read one retained
  run and a readable one sits beside it, `PreviousRun.complete` is false, but
  nothing in the rendered report says a run was left out — the recipient reads
  a partial diagnostic as a whole one.
  **The finding's proposed fix does not work**, which is why this is recorded
  rather than applied: making `DebugReport.omitted` crash-independent would
  change only `pinConsumeSafe`, and the "could not be included" notice lives
  behind `if (previousRun.isNullOrBlank())` — unreachable whenever there is
  text to render. With no crash pinned there is also nothing to consume, so
  the change would be a no-op for exactly the case it names.
  What would actually close it is a **new line in the rendered section** when
  the handle reports itself incomplete. That is report copy a user reads and
  sends, so it wants sign-off; and it can only say *that* a run was skipped,
  never which, for the same missing per-run visibility as the entry above.
  Worth deciding together with it.


- **A seven-failure unit-test run seen once, never reproduced** (2026-08-31).
  One `:app:testDirectDebugUnitTest` run went red with seven failures whose
  shape pointed at recording being *off* when a test expected it on — a
  persisted `DebugLogStore` setting leaking between test classes is the
  standing suspect, since the failures named the drain gate rather than a
  timeout. Two subsequent full `--rerun-tasks` runs on the same tree were
  green (364 tests, 45 classes), and the isolated class was green on its own,
  so there is nothing to fix against yet. Recorded rather than dropped: if it
  returns, make the ordering explicit — restore the persisted setting in
  `DebugLoggingTest`'s `@After` — rather than bumping a timeout or adding a
  sleep, which the testing rules forbid.

- **Align the four app repos' debug loggers.** `ProcessExitReasons.kt` landed
  here as a deliberate copy of Type Launcher's — same file name, function
  names, log-line format and field names — so the logs read identically and a
  future unification is a lift-and-share rather than a reconciliation. The
  loggers underneath differ, and this is the inventory so whoever takes it on
  does not have to rediscover them:
  - **Type Launcher** has a *default-safe type rule* (`LogValue`): a log call is
    a literal format string plus arguments, and an argument reaches the
    Crashlytics breadcrumb mirror only if its type cannot name anything of the
    user's, with `safe(...)` / `sensitive(...)` overriding per value.
    `SnoozeDebugLog` takes a pre-built `String`, so redaction here is whatever
    the call site remembered to do.
  - **`SnoozeDebugLog` has no off-device mirror at all.** Crash reporting
    deliberately attaches no breadcrumbs and no custom keys (`SPEC.md` §12), so
    there is nothing to withhold from and the port invents no redaction
    wrapper. That is why the exit `description` and the timestamps are logged
    in full here but marked `sensitive(...)` in Type Launcher: same values,
    different channel.
  - **All three log by default; only the off-switch differs.** `SnoozeDebugLog`
    records from first launch (`SPEC.md` §4.6, `docs/PRIVACY.md`: the failures
    worth diagnosing happen once and without warning, so a log that starts off
    guarantees the first one is the one nobody captured), and turning it off
    both stops recording and deletes what it kept. Type Launcher and clothescast
    log unconditionally with no user switch at all.
  - clothescast's `DiagLog` and Simmo's logger differ again.

  Unifying them is a bigger piece of work than any one port and was explicitly
  out of scope for the ports (maintainer, 2026-08-28: *"the loggers should be
  aligned, that's likely a bigger thing, but don't diverge them further"*). The
  floor stays per-repo regardless: uniformity must not loosen any repo's
  privacy rules.


Nothing here is scheduled; each is a sequel that follows from something already built
(`SPEC.md` §14).

- [ ] **Reconsider the launcher icon: a dark mark on a white background, not white on
      navy.** (maintainer, 2026-08-23) `ic_launcher_foreground` is white strokes with no
      fill of their own — it doubles as the `monochrome` themed-icon layer, so it carries
      no color, and the launcher's own navy `ic_launcher_background` is what makes it read
      as anything on the home screen. `SnoozemoTitleRow` (`SharedComponents.kt`) draws that
      same bare vector beside every screen's title, matching the sibling Simmo repo's
      `SimmoTopBar` shape — no background shape wrapping it in-app, same as Simmo's own
      `SimmoMark` — and unlike Simmo's foreground (already colored: "the two SIM cards in
      pink and blue and the white check"), `SnoozemoMark` tints the white-only vector to
      `onSurface` so it stays legible in-app in both themes; that in-app half is done. The
      launcher icon itself is untouched — still white on navy — and swapping to a dark
      stroke on white would still be worth doing for the home screen and the Play listing,
      independent of the in-app mark. Its own call to make, not bundled into the PR that
      added `SnoozemoTitleRow`.

- [ ] **Make CI safe to open to external PRs and forks — plan in
      `docs/fork-safe-ci.md`.** (maintainer, 2026-08-23) Adopts
      `mikelward/ci-commit-artifact` for the screenshot-refresh commit (today's
      `screenshot-tests` job runs the PR's own Gradle/Roborazzi code and commits/pushes in
      that same job — the structural risk `ci-commit-artifact`'s README exists to close)
      and migrates `ci.yml` to `pull_request_target` like `typelauncher`, so a PR
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
      (`ci.yml`'s screenshot-tests job, one step per class). A quick look at the
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
      comment-size limit (`.github/workflows/ci.yml`, "Post screenshot diffs as a
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

- [x] **Calendar end times** — landed 2026-08-31 as the ongoing notification's third
      action (`Until 17:00`), not as a sheet seed; `SPEC.md` §4.3 records the reversal and
      why the shape changed. The maintainer chose to build it ahead of the Play
      declarations rather than after them, so the deferral above is spent, not forgotten.
      Still owed on a handset: that a real calendar provider answers the `Instances` query
      the way the emulator-free unit tests assume, and that the third action reads
      acceptably on a Quick Settings-width card.
- [ ] **Calendar action: the last freshness windows, deferred for the maintainer's call**
      (PR #156, Codex rounds 10-11). Two remain, both in the gap between a card being built
      and being posted, and both fixable by versioning the record the same way takedown is
      already versioned:
      - A **timezone change** in that gap leaves `Until 17:00` reading in the old zone. The
        receiver's repost queues a worker, that worker finds the cache populated and returns
        without rebuilding, and the in-flight card wins.
      - A **second record change** in that gap — another degradation, a cap move, a
        replacement — is not caught by the rebuild's generation guard, which the takedown
        bumps and nothing else does. The rebuild can overwrite the newer card with the
        record it loaded a moment earlier.
      **Not taken, deliberately.** Nine rounds of review on this PR each closed a hole and
      each opened the next, all inside one mechanism: what invalidates the cached offer and
      what may repost the card. `AGENTS.md` *Working with PRs* says to stop pushing at that
      point rather than keep patching, so this is where it stops. What it costs while
      unfixed: a wrong wall-clock time on one button, for one snooze, only for a user who
      changes timezone mid-snooze, over an end that is itself correct — nothing is
      mis-scheduled and no snooze is affected. Worth reopening if the maintainer wants the
      mechanism finished; worth leaving if a cosmetic label in a rare case is not worth
      more machinery on the notification path.
- [ ] **Saved places** — name an anchor, give it its own policy and duration cap; the tile
      long-press becomes a picker. The `Anchor` type is already shaped for it.
- [ ] **Settle the backup story** (maintainer, 2026-08-11) — before the first release with
      settings worth keeping, and *not* by leaving `allowBackup="false"` unexamined. Today
      the app stores a transient anchor and nothing else, so no-backup costs nothing; saved
      places, per-place policies, and caps change that, and losing them on a phone swap is
      its own failure. Don't build anything that assumes never. The options, cheapest
      first:
      - `dataExtractionRules` (API 31+, and minSdk is 35) can allow **device-to-device
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
  - The monitors have since landed, so the engine's behavior is now reachable by a user — this
    question is live rather than hypothetical, and answering it is no longer free.
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

- **Guessed under autopilot: reworded the debug log's cleanup-failure line to
  be state-neutral** (2026-08-31). It read *"Off, but some saved files couldn't
  be deleted"*; it now reads *"Some saved files couldn't be deleted"*.
  **Why:** the flag deliberately outlives the Off toggle that set it — turning
  the log back on removes no files, so the warning has to stand — and it is now
  also set at startup, where the switch may be either. The old wording asserted
  Off beside a switch that could be visibly On (Codex, PR #153).
  **The alternative** was to keep the Off-specific wording and render it only
  while disabled, which hides a true warning in the state where the user is
  most likely to be looking at the row.
  **Why it is reversible:** one string. `values-de` is deliberately *not*
  updated — the Translations rule wants the maintainer's sign-off on English
  first — so it carries the old wording and a `TODO: translate` marker until
  this is settled either way.

- **Guessed under autopilot: retire `DebugLogFiles.kt` now and accept a window
  without the on-screen cleanup-failure warning** (2026-08-30). The library reports
  a failed opt-out purge *into the log* and holds the line until one can land;
  this app reports it **on screen** as `debugLogCleanupFailed`, put there by
  Codex on PR #89 because a refused delete leaves real files on disk while the
  switch and every other signal read as if Off fully succeeded.
  **Only that one indicator is affected.** `debugLogSaveFailed` is fed by
  `lastSaveRefused = !persisted` — the *preferences* write outcome from
  `DebugLogStore.setEnabled`, not the file mirror — so it is untouched by the
  sink swap and keeps working throughout.
  **Decided:** take the library as it stands, and add a caller-visible signal to
  it afterward — `saveFailing` / `purgeFailed` plus `addStorageListener`,
  mirroring the `unacknowledgedCrash` + `addCrashListener` shape it already
  uses for the crash banner. Then rewire `debugLogCleanupFailed` onto it.
  **The alternative** was to land that library change first and keep the
  indicators unbroken throughout, at the cost of a second ordering round-trip
  through `@main`.
  **Why it is reversible, and cheap:** the library addition is purely additive,
  so nothing here has to be redone to adopt it — the indicators are rewired, not
  rebuilt. And the window costs no user anything: this app is not on Play yet
  (Phase 8), so the warnings have nobody to fail today. What the interim does
  lose is a developer's live signal on a handset that the log has stopped
  persisting; the failure is still recorded in the log itself either way.

  **What the retirement actually cost, once it landed.** Two things beyond the
  indicator above, both from the shared sink behaving differently rather than
  worse, and both recorded here rather than argued away:

  - **`lastDismissFailed` lost its source too**, for the same reason as
    `debugLogCleanupFailed`: the library's `acknowledgeCrashBanner` and
    `clearPreviousRun` report their trouble into the log rather than to the
    caller. It is rewired by the same `addStorageListener` addition. Nine tests
    asserting the two flags, and two `MainActivityLifecycleTest` cases asserting
    a screen picking their values back up after a restart, were removed with
    them — there is no signal left for them to assert. They come back with the
    listener.

    **What actually came back**, once the listener landed: three tests over the
    two flags, driven through the shared sink rather than the deleted local
    one — an Off toggle whose purge is refused, a dismissal the storage
    refuses, and a share whose handle skipped an unreadable run — plus the one
    `MainActivityLifecycleTest` case (the dismiss outcome missed while
    stopped). Fewer than the nine, deliberately: most of the removed nine
    asserted the *old* file sink's own delete and rename behavior, which is now
    the library's to test and is covered by its suite.
  **Contested by Codex on PR #153, and the deferral did not survive it.** Two
  P1s landed against exactly this entry, and the first was right in a way the
  text above understated: the opt-out persists, `MainActivity.setDebugLog`
  clears the warning, and the user is told a **privacy control** succeeded
  while `androidlog.log` may still be on disk. A `TODO.md` entry records a
  decision; it does not protect a user. A third P1 then widened the same gap
  — an unreadable crash file beside a readable ordinary run gives a handle
  covering only the ordinary run, so `omitted` reads false and a landed copy
  consumes the pin over a report that never carried the crash.

  **Resolved by taking option (1): the library grew the signal first**
  (androidlog#20, 2026-08-31). `PreviousRun.complete` says whether a handle
  covers every run still on disk, and `storageOutcomes` + `addStorageListener`
  publish the opt-out purge and crash-dismissal outcomes on the same
  contract as `unacknowledgedCrash`. All three indicators are wired to it
  here: `lastDisableCleanupFailed` (now the union of the sink's purge and
  this app's own legacy-directory migration, which the sink cannot answer
  for), `lastDismissFailed`, and `omitted`. So the window this entry
  guessed its way into never shipped.

  Two more P1s on the same review needed no library change and are fixed
  with tests: the legacy `cacheDir/debuglog` migration never retried on the
  Off toggle (`cc48be9`), and a pinned crash this app's own
  25,000-character render truncated away, consumed anyway (`6116cb1`). A P2
  on the report's heading is fixed too — the section can carry several runs
  and only one of them crashed, so it reads *Earlier runs (one ended in an
  uncaught exception)* rather than labeling every ordinary restart as the
  crash.

- **Does `androidlog` get a row on the Licenses screen?** (Codex, PR #153, and
  the same finding on clothescast#1176 before it.) `exportBundledLicenses`
  keeps only `ModuleComponentIdentifier` artifacts, and an included-build
  substitution resolves to a `ProjectComponentIdentifier`, so the shared logger
  is filtered out of both `aboutlibraries.json` files even though it is
  compiled into every APK. Declined on clothescast as the wrong place to settle
  it: the screen attributes *other people's* work, `androidlog` is this
  account's own code, and it carries no `LICENSE` — that decision is open in
  its own `TODO.md`. So this is downstream of an unanswered question, not a bug
  introduced here. If it does get a license, the filter needs extending to keep
  included-build components under their substituted coordinates, in **both**
  consuming apps — a fleet change, not a snoozemo one.

  - **An empty crash log now raises no banner.** A crash marker can land without
    the run's content ever reaching disk — process death between the two writes
    — and the library declines to raise a banner over a report with nothing in
    it. This app used to raise it and then refuse to *consume* it, which
    protected the same evidence one step later. The library's answer is the
    better one at the banner, but it is a silent case where this app previously
    said something, so it is written into `SPEC.md` §4.6 rather than left to be
    rediscovered. `DebugReport`'s own refusal to consume an omitted read is
    unchanged and still covered.

- **Deferred, from Codex on PR #151: `runCatching` in `DebugLogFiles.kt` catches
  `Error` as well as the storage exceptions it means to handle.** Raised against
  the purge's new inner guard, but it is the file's pattern rather than that one
  call site — `runCatching` appears about twenty times here, including the outer
  guard wrapping the whole worker task.
  **Why the fix as prescribed would not have worked.** Narrowing only the inner
  guard changes nothing observable: the `Error` would be caught by the outer
  `runCatching` in the same task, logged as "Rotating the debug log failed", and
  the process would continue exactly as it does now. It never reaches the
  uncaught-exception path the finding is aiming for. Achieving that intent means
  narrowing the outer guard too, which governs rotation generally and sits on the
  start path.
  **The real question, and why it is not autopilot's.** Whether a fatal `Error`
  inside the logging subsystem should kill the app to produce a crash report, or
  be contained so a broken logger never takes the product down with it. Principle
  1 pulls one way and the error-handling rule pulls the other, and the answer
  should be one pattern across the file rather than a single narrowed call site.
  Nothing is silently swallowed today either way: the purge reports through
  `onLegacyPurged`, logcat, and a line in the log itself.

- **Move this app onto `mikelward/androidlog`, the shared debug log**
  (autopilot, 2026-08-30). Landed whole: the wiring, the recording half, and
  the file handling.
  - **Done: the composite build.** `settings.gradle.kts` includes the library
    from `.androidlog/` or a sibling `../androidlog`, `:core` takes
    `logging-core` as `api` (it is a plain Kotlin JVM module and
    `:core:verifyNoAndroid` still passes with it on the classpath — the
    library's core is Android-free by the same guard), and CI, the weekly
    `gradle-update` caller and the session-start hook all provision it. No
    version, tag or SHA anywhere: a merge there is in this app's next build.
  - **Every workflow that runs Gradle needs the checkout, not just `ci.yml`.**
    Grepping for `gradlew` finds only `ci.yml` and is the wrong check — a
    reusable-workflow *caller* never contains `gradlew`, the shared workflow
    does. `gradle-update.yml` calls `mikelward/gradle-update`, whose update job
    runs `./gradlew test` + `./gradlew lint` in a clean workspace; without a
    checkout it would die on settings evaluation and the only symptom would be
    a weekly dependency PR that silently stopped arriving. Found the hard way
    in clothescast (#1176).
  - **Done: `SnoozeDebugLog`, `LogValue.kt` and the file handling.**
    `LogValue.kt` is gone, `SnoozeDebugLog` is 74 lines delegating to the
    library, and `DebugLogFiles.kt` is down from 1,154 to the app-specific
    part — the settings gate, the preferences write, the legacy-directory
    migration, and the screen-facing outcome mirrors. The three levels here
    (`event`, `warning`, `failure`) matched the library exactly, so unlike
    clothescast there was no facade gymnastics.
  - **Legacy log files must be deleted on migration, not read** — done in
    PR #151. The reduced rendering is not retroactive, so removing them is the
    only thing that stops lines written under the old full rendering being
    readable. The library grew no API for this in the end — the old directory
    is this app's, not the sink's, so `purgeLegacyDirectory` removes it here
    before the sink is constructed. The flag recording it is set only once the
    directory is confirmed gone, so a refused delete retries at the next start,
    and on an Off toggle, instead of being marked off.

- **DECIDED: `logSummary()` keeps the times, as one `safe(...)` rendering**
  (maintainer, 2026-08-30; raised the same day). The question was what happens
  to the two renderings of `ActiveSnooze.logSummary()`, whose halves differed
  by the snooze's timestamps — `full = "snooze(started=... capAt=... mode=...
  anchor[...])"` against `mirrored = "snooze(mode=... anchor[...])"`. The
  library **renders once, reduced, at ingestion**, deliberately, so that a
  channel added later cannot widen what was already recorded; that made "full
  on device, reduced off device" no longer expressible.
  **The ruling: the times are not sensitive, and they are necessary for
  debugging.** A snooze that ended early — or never ended — cannot be
  diagnosed from `mode=` and the anchor's shape alone, and this app has no
  automatic off-device mirror at all, so the only way they ever leave the
  device is inside a report the user chose to share. So `logSummary()` returns
  a single `safe(...)` string with `startedAt` and `capExpiresAt` in it, and
  the comment that justified the split is retired with the split.
  Landed in the logger swap (PR #151).

- **Which of `Snooze` / `End snooze` shows, decided under autopilot** (2026-08-30). The item
  asked for "a real design answer, not a blind toggle" and listed three options; autopilot took
  the one the item itself flagged as safe — show `End snooze` when `snoozing` is `true` **or**
  `null`, and `Snooze` only on a confident `false`. That keeps the always-reachable guarantee
  (`SPEC.md` §7) intact, since the exit is hidden only where the record has been read and found
  empty, and it happens to yield exactly one button in every state rather than needing a
  separate rule for that.
  **The alternatives, and why not**: a single button that relabels itself by state reads well
  but makes the unknown case incoherent — it would have to pick a label before knowing which
  action it performs. Leaving both as they were is what the maintainer asked to change.
  **Cheap to reverse**: one `if` in `MainScreen.kt` plus the assertions in
  `MainScreenScreenshotTest`; no persistence, no API, and no behavior outside this screen.

- **A discard's completion is not observable, so a failed deletion cannot be
  surfaced** (Codex, PR #131, accepted in part). `FirebaseCrashlytics
  .deleteUnsentReports()` returns `void` — the internal
  `CrashlyticsCore.deleteUnsentReports()` does return a `Task<Void>`, but the
  public facade discards it — so there is nothing to await and no failure to
  show the user. What the code *can* guarantee is that the held reports are
  never sent: `reportActionProvided` is a single-shot
  `TaskCompletionSource` and `trySetResult` no-ops once completed, so
  discarding before enabling claims that decision permanently. The policy is
  worded to that guarantee ("never sent", "discarded when you turn the switch
  on") rather than to a moment by which the bytes are gone. Revisiting means
  the manual reporting flow, where `checkForUnsentReports()` does give a
  `Task` to wait on.

- **A crash captured while reporting was off is discarded on *every* off→on
  crossing, not only the very first** (autopilot, 2026-08-28). A period spent
  switched off is a period the user did not agree to, whether they had never
  answered or had answered no, so both are treated alike. The alternative —
  discarding only on a first-ever opt-in — would let a report captured during a
  deliberate opt-out be released by a later re-opt-in. Reversible: one
  condition in `CrashReporting.setEnabled`, and the cost of being wrong either
  way is at most a lost report, never a sent one.

- **The discard runs ahead of the enable, not after it** (autopilot,
  2026-08-28). A process death between the two then leaves the reports gone
  rather than sent, matching the asymmetry the off path already argues for.
  Reversible, and pinned by `turning it on discards what was captured while it
  was off, before enabling`.

- **Not done, deliberately: never *storing* pre-consent crashes.** The
  maintainer's ask was "don't send (and maybe don't store)". This discards
  them; the reporting library still writes them to disk meanwhile, because
  disabling collection does not uninstall its uncaught-exception handler. Never
  storing them means not initializing Crashlytics until consent exists — a
  startup-ordering change worth costing out on its own.

- **The debug log's type rule landed with no mirror behind it** (autopilot,
  2026-08-28). `LogValue.kt` is ported from Type Launcher and `SnoozeDebugLog`
  now takes a format string plus arguments, but nothing is sent off the device —
  only a file sink and logcat are registered. The alternative was to wait until
  a Crashlytics sink was wanted and port both together. Chose to land the rule
  first because the reverse order means a logger with no redaction is one line
  from becoming an upload channel. Reversible: deleting `LogValue.kt` and taking
  `event(message: String)` back is mechanical, and no behavior depends on it yet.

  **Since superseded on the reversibility half**: the type rule is
  `mikelward/androidlog`'s now, shared with three other apps, so reverting it
  here is no longer this app's call alone. The decision itself still stands as
  recorded.

  **What this does *not* claim: that adding the mirror is then a one-line
  change.** An earlier draft of this entry said so, and a `MirrorSink` type was
  built on that basis and then removed — a speculative off-device path with no
  consumer, whose own correctness nobody could check against a real sink. What
  the type rule buys is that the *arguments* are already classified when a
  mirror is wanted; the mirror itself still has to decide everything outside the
  arguments.

- **A mirror needs its own decision about the timestamp prefix** (Codex, PR #129,
  deferred). `render()` prefixes every entry with wall-clock local time, which no
  argument redaction touches. On device that is the sanctioned diagnostic — an
  inexact alarm landing outside a Doze window cannot be reconstructed without it.
  Off device a state-transition line's timestamp says when someone was asleep or
  in a cinema, which `AGENTS.md`'s *Privacy* rule names as the user's, so a
  mirror would have to omit or coarsen it. Nothing to do while no mirror exists;
  this is a required step of building one, not an open defect.

- **`safe`, `sensitive` and the value classes are public, not internal**
  (autopilot, 2026-08-28). Type Launcher is a single module, so `internal`
  sufficed there; here the call sites are in `:app` and the rule lives in
  `:core`, so the wrappers have to cross the boundary. `logArgumentMayLeaveDevice`
  and `formatLogMessage` stay internal. The alternative — a `:core` facade that
  re-exports them — buys nothing and adds a layer.

- **Wiring a Crashlytics sink is NOT part of this and still needs a real
  answer.** It contradicts two published statements: `AGENTS.md` says the crash
  reporter "attaches no custom keys and no breadcrumbs at all", and
  `docs/PRIVACY.md` says the debug log reaches nobody without an explicit
  share. Turning breadcrumbs on changes what the Data Safety declaration must
  say, which `AGENTS.md` puts with the maintainer whatever mode is in effect.
  Autopilot did not guess this one.

- **The sheet gate reads the snooze record without any guarantee the arm has landed**
  (2026-08-25, Codex on PR #118, declined for that change — and it corrects a claim made
  repeatedly while building it). `shouldOfferSheet` decides from `window.decorView.post`,
  on the assumption that `startService` has already queued `onStartCommand` on the same
  looper. **That assumption is wrong.** `ContextImpl.startServiceCommon` is a synchronous
  binder into `ActivityManagerService`, but the way back is `IApplicationThread`, whose
  `scheduleCreateService` / `scheduleServiceArgs` are oneway callbacks landing on a binder
  thread, which only then `sendMessage(H.SERVICE_ARGS, …)` to the main looper. Nothing
  orders that against a local `post`.
  The failure is intermittent and fails closed: the decision can run first, find no record,
  and finish — so an opted-in user occasionally gets no sheet over a snooze that armed
  correctly a moment later. Annoying, not harmful, and the same outcome as having the
  setting off.
  **The options:**
  1. **Have the service say when the arm resolved**, over an in-memory channel like the one
     `EndChoiceOutcome` already provides, and let the decision await it under a short bound.
     Correct, and the most mechanism: a new channel, a timeout, and a degraded path when the
     bound expires.
  2. **Re-read the record a second time** a frame later before giving up. Cheap, and closes
     most of the window without proving anything.
  3. **Leave it.** The sheet is off by default, the miss is silent and safe, and the snooze
     is armed either way.

  Not guessed here because option 1 is real design and the other two are admissions. Worth
  settling alongside the departure-row entry below, which needs the same post-arm signal.

  **A second way to lose the same offer** (2026-08-30, Codex on PR #152, declined there with
  this note). A configuration change *between* the accepted `arm()` and the posted read
  completing takes the offer with it: the runnable and its generation guard belong to the
  activity being destroyed, and the replacement restores no marker saying an offer was owed,
  so an opted-in user gets the default cap with no sheet. Same window as above, same failure
  shape — a miss that fails closed over a correctly armed snooze — and reached now from the
  app screen as well as the tile.
  Declined rather than fixed because the obvious fix is the mechanism this PR had just
  deleted: a "sheet owed" marker outliving the arm that asked for it. Bounding its lifetime
  makes it defensible but not safe — inside that window the *next* record from anywhere still
  satisfies it, which is the round-two bug in miniature, and the cost of getting it wrong (a
  sheet opening over a snooze this screen never armed) is worse than the cost of the gap (one
  lost refinement in a rotation measured against tens of milliseconds). Option 1 above answers
  both cleanly and neither is worth paying for on its own, so they settle together.

- [x] **A restored sheet was not bound to the snooze it was offering times for**
  (2026-08-30, Codex on PR #152, deferred there — the sixth round on this seam, and
  the point at which it stopped converging). `MainActivity.reconcileSheet` asks only
  whether *some* record still offers a choice, so a sheet left open while the activity
  is stopped survives its own snooze ending and another being armed: the old chosen
  time then applies to the new snooze. It cannot leave the phone silent — the service
  validates any chosen time against the running snooze's own floor and cap — but it
  applies a time to a snooze the user did not choose it for.
  **The fix is small and named**: `ActiveSnooze.startedAt` identifies a snooze, so save
  it in the bundle beside the offer and have `reconcileSheet` dismiss when the live
  record's `startedAt` differs from the one the sheet was seeded against. Roughly a
  bundle key, one field and one comparison, plus a test either side.
  **Deferred rather than done because the seam has stopped converging, not because the
  finding is wrong.** Six review rounds on this sheet lifecycle, each real, and three of
  them on defects the previous round's own fix introduced: the standing sheet-owed marker,
  the un-vetoable swipe dismissal, restore-into-permanent-commit, the stale restored
  sheet, restore dropping single-flight, and now this. `reconcileSheet` — round four's
  fix — is what round six is about. Patching on inside one PR is how the next round gets
  written, so this is the maintainer's call: take it as a follow-up PR, or fold it in and
  accept another round.

- [x] **A refusal settled during restore reseeded against the wrong cap**
  (2026-08-30, Codex on PR #152, deferred there — the seventh round, filed against a
  documentation-only commit, which is the clearest evidence yet that this seam is not
  converging). `MainActivity`'s `ceilingAt` reads the in-memory `activeSnooze`, and that
  field is still null while `onCreate` is restoring — it is filled a moment later by the
  record read. So if a `REFUSED` lands in the gap between the old activity's destruction
  and the restore, `restore` takes it from `EndChoiceOutcome`, the refusal path reseeds,
  and `ceilingFor(null, now)` hands back `now + DEFAULT_CAP` instead of the running
  snooze's real cap. The `+` control can then offer a time past that cap; the service
  honors anything past the cap by doing nothing and reports it applied, so the sheet
  dismisses on a change that never happened. Quietly wrong, which principle 1 ranks
  second-worst — bounded only by needing a rotation, a commit in flight, a refusal landing
  inside that gap, and a sheet that had already sat past its own floor.
  **The trampoline is not affected**: its `ceilingAt` loads the record from disk, so it
  always has the real cap. This is the cost of the app screen's warm copy.
  **Two candidate fixes**, neither obviously right, which is part of why it is deferred:
  hold the buffered refusal until the record read lands, or carry the saved offer's own
  `ceiling` — already in the bundle — into the reseed instead of asking `ceilingAt`.
  Settle it together with the `startedAt` binding above; both are about a restored sheet
  trusting state the screen has not read back yet.
  **Fixed** by giving the offer an identity: the controller records the `startedAt` of the
  snooze it was seeded against, takes its ceiling from that same record, and `reconcile`
  compares the two — dropping an offer whose snooze is gone or replaced, and reseeding one
  whose time has fallen inside the floor. The refusal path reseeds against the offer's own
  ceiling rather than asking the host for one it may not have.
  **And the same seam bites the fresh-arm path, not just restore** (Codex's eighth round,
  same PR, also filed against a documentation-only commit). `offerSheetForThisArm` gates on
  the record it just loaded and then seeds from `activeSnooze` — so an arm made while the
  warm copy is stale (the screen has not caught up with a tile arm, say) passes the gate on
  the real record and offers times against `now + DEFAULT_CAP`. Same wrong ceiling, same
  outcome: a time past the real cap, which the service reports applied and ignores.
  That makes the general statement of this whole family one line — **the ceiling must come
  from the record that passed the gate, never from the warm copy** — and the fix for this
  half is one argument: seed from `loaded.capExpiresAt`. Doing it properly means the
  controller taking the record rather than a `ceilingAt` lambda, which is a signature change
  across both hosts and is why it is not a one-liner.
  **And once more on the delayed-offer path** (Codex's ninth round, same PR, third in a row
  against a documentation-only commit). `offerSheetForThisArm` reads the record off the main
  thread and seeds on the callback, and its only staleness check is for a *newer arm*. If the
  snooze ends in between — the End button, the tile, a departure, its cap — `reconcileSheet`
  may run while no sheet exists yet, do nothing, and then the callback opens a sheet over a
  snooze that is already gone, with no later record change guaranteed to take it down.
  Same shape again: state read at one moment, acted on at another, with nothing tying the two
  together. Which is the argument for fixing this family **as one change** rather than
  case by case — the sheet needs an identity for the snooze it belongs to (`startedAt` is
  the obvious one) checked wherever it is seeded, restored or reconciled, instead of four
  separate guards each covering the path its own round happened to name.
  **A fifth path, same shape** (tenth round, fourth in a row against a documentation-only
  commit): background the screen right after tapping `Snooze` and the offer's worker finishes
  while it is stopped — nothing invalidates the pending offer on `onStop` and the callback
  does not check the lifecycle — so returning later reveals a sheet seeded at the old
  callback's clock, whose `endsAt` may have elapsed. The controller does reseed a refusal, so
  the user recovers after one refused tap rather than being stuck; the offer is still stale
  when they first see it. Add the elapsed-offer case to the same fix: whatever identity the
  sheet carries has to cover *when* it was seeded as well as *what* it was seeded against.

- [x] **`EndChoiceOutcome` held one listener while two hosts shared it**
  (2026-08-30, Codex on PR #152; fixed in the follow-up). `watch` assigned a single
  `listener` slot, which was correct while the tile trampoline was the only surface with a
  sheet. PR #152 gave the app screen one too, in the main task, while the trampoline's
  stays alive in its own `singleInstance` task — so two commits could be outstanding, the
  second watch replaced the first, one answer dismissed the wrong sheet, and the other
  landed with nobody to take it, leaving that sheet committing forever and undismissable.
  **Fixed by giving each commit a request id**: the controller mints one, it travels to the
  service on the intent and comes back with the outcome, and the channel keys both its
  listeners and its held results by it. A restored sheet resumes the request it named
  rather than whatever the channel last held.


- **Rounding drops a wall clock that only exists on the other side of a spring-forward gap**
  (2026-08-25, Codex on PR #118, declined — the fifth consecutive finding on this surface).
  `getValidOffsets` is empty for a local time inside the gap, so that neighbor contributes no
  candidate at all. In New York a 00:50 tap targets 01:50; 02:00 is invalid but normalizes to
  03:00, ten minutes from the target, while the surviving 01:30 candidate is twenty away — so
  the seed lands forty minutes out rather than about an hour. **The options:** include each
  invalid neighbor's `atZone`-normalized instant as a candidate; leave it and accept a
  short seed for one hour a year; or make the whole question moot by changing how rounding
  works at all (see the entry below). Declined here for the same reason as the `+` label
  above — it is the fifth sliver of one one-hour-a-year surface, and the rounding question
  underneath it is unanswered.

- **`+` can move the *displayed* time backward across a daylight-saving fall-back**
  (2026-08-25, Codex on PR #118, declined there — the fourth consecutive finding on this
  one surface). Stepping adds 30 minutes to the *instant*, which is right: the snooze
  genuinely ends half an hour later than before. But in a repeated hour the label follows
  the wall clock, so a sheet reading `1:30` (EDT) steps to `1:00` (EST) under a button
  that says later. **The alternative** is stepping through unambiguous local choices, or
  showing an offset or a date so the two occurrences can be told apart. Both put new copy
  on a sheet whose whole design brief is the shortest thing that still reads (`SPEC.md`
  §4.4), which makes it a product decision rather than a clamp.
  **Why it is recorded rather than fixed**: rounds 12, 17, 18 and 19 were all the same
  one-hour-a-year surface, each fix drawing a new finding one sliver over — the UTC grid,
  then the overlap offset, then the transition boundary, now the step. That is the
  non-convergence `AGENTS.md` names, and the failure here is a confusing label, not a
  snooze that ends at the wrong moment. Same category as the time-zone label staleness
  recorded above, and it wants the same answer at the same time.

- **Whether the seed should be rounded in local time at all** (2026-08-25, raised by the
  maintainer). Rounding on the user's wall clock is what makes `2:00` rather than `2:15`,
  and it is the only reason any of the daylight-saving handling exists. Rounding on the
  epoch instead would delete all of it, at the cost of permanently ragged times in zones
  whose offset is not a whole half hour — Nepal (+05:45), the Chatham Islands (+12:45),
  Eucla (+08:45); India (+05:30) is unaffected. A third option keeps local rounding but
  fixes the offset at the target instant and does plain modular arithmetic from there:
  as short as the epoch version, always lands on a local half hour since every
  transition is itself a multiple of 30 minutes, and gives up only "nearest" for one
  hour a year. **Undecided**, and it is what the entry above should be settled alongside.

- **The end-condition sheet does not follow a time-zone change while it is open**
  (2026-08-25, Codex on PR #118, declined there with this note). The sheet formats the
  chosen instant once; a zone change while it sits open leaves the row reading `14:00`
  for an instant now local-15:00. The *instant* committed is still the one the user
  picked, so the snooze ends when they meant — the label is what goes stale.
  **The alternative** was a `ACTION_TIMEZONE_CHANGED` receiver (or an `onResume`
  re-format) to re-render the row. Declined for this change: it is a new wake-up
  registration on a transient sheet that exists for the seconds after a tile tap, to
  correct a label in a case that needs the user to change zone in exactly that window.
  Reversible: re-formatting on resume is a one-line change if it ever matters, and
  `EndCondition` already holds the instant rather than a rendered string.

- **The departure row commits by changing nothing** (2026-08-25, the end-condition sheet).
  `until I leave` dismisses and leaves the snooze exactly as the tile armed it: departure
  tracking is already on and the 8-hour backstop is already the cap, so there is nothing for
  that row to set. **The alternative** was having it write something anyway — a flag, or a
  cap pinned to the backstop — so the two rows are symmetric in the record. Rejected because
  a write with no effect is a write that can fail, on the one row whose whole promise is that
  dismissing and choosing it are the same outcome (`SPEC.md` §4.4). Reversible: it is one
  callback in `TileTrampolineActivity`.
- ~~**The sheet's ceiling is derived from the clock, not read from the record**~~ —
  **reversed on the same PR** (2026-08-25, Codex on PR #118). The guess was that §6.9 barred
  the read, so the sheet clamped against `now + DEFAULT_CAP`. It doesn't: §6.9 forbids
  *waiting* on the service, and the sheet is decided in the posted block after the start is
  away, where `shouldOfferSheet` was already reading the same warmed file. The constant was
  also wrong on its own terms — a duplicate arm from a stale tile snapshot keeps the snooze
  already running, so the sheet offered an hour over one with ten minutes left and the
  service reported it applied. The ceiling is now the record's own `capExpiresAt`.

- **`until I leave` is offered on what the build tracks, not on what this snooze tracks**
  (2026-08-25, Codex on PR #118, P1 — declined for this change and left open). On `play`,
  `PRESENCE_TRACKS_DEPARTURE` is `true`, so the row is drawn; an anchor that degrades to
  duration-only (`SPEC.md` §6.5) still gets it, and choosing it then leaves the phone quiet
  to the 8-hour cap after the user has left. The degradation says so in the ongoing
  notification — but a user who has just denied the notification permission cannot see that,
  and the tile is their only surface (§4.2).
  It cannot be gated on the record: `beginArming` writes `DURATION_ONLY` on both flavors, and
  the real mode only lands up to 10 s later in `onAnchorCaptured`, while the sheet draws
  within a frame of the arm. So at the moment the row must be drawn the answer does not
  exist, and reading `snooze.mode` would drop the row from every arm including healthy ones.
  **The options, all three:**
  1. **Offer the row and correct it** when `onAnchorCaptured` lands — the sheet is still up,
     so it can redraw. Costs a row changing under the user's finger on a screen whose whole
     job is one tap.
  2. **Hold the sheet** until the anchor resolves — up to 10 s of nothing after a tap the
     user expects to be instant. Against `SPEC.md` §6.9 and the first-frame rule.
  3. **Drop the row** until the mode is knowable — honest, and it removes the feature's
     headline half on the flavor that actually has presence.

  Each trades a different principle, so none is autopilot's to guess. **This is the one open
  item on the sheet that can leave the phone quiet when the user expected it not to be.**
- **The sheet follows the notification-permission dialog rather than replacing it**
  (2026-08-25). A first-run arm now shows the permission dialog, and the sheet after the
  answer, so a tile-first user gets both. **The alternative** was skipping the sheet on the
  run that asked, so the first arm stays a single tap. Rejected because it makes the first
  snooze — the one most likely to want a time bound — the only one that can't have one.
  Reversible: one branch in the permission callback.
- ~~**The sheet ships with no off switch yet**~~ — **settled by the maintainer, 2026-08-25**:
  the sheet ships *off by default*, behind `Ask when to unsnooze` in Settings. Not a guess and
  not pending review; recorded here only because the superseded guess was.

- **The Wi-Fi recheck alarm's period is 15 minutes** (2026-08-24): a Wi-Fi-only anchor has no
  geofence, so nothing durable was watching it once Android stopped the service, and this alarm
  is what restores a reader (`SPEC.md` §6.10). 15 minutes puts the worst-case departure latency
  at about 15 + the 5-minute grace, against the 30 + 5 the backstop alone gave —
  `setAndAllowWhileIdle` tends to fire sooner than the backstop's `WorkManager` period, though the
  cadence is best-effort, not a bound (the cap stays the only hard one; Codex, PR #105). **The
  cost** is 16 extra wakeups over a 4-hour snooze
  (`SPEC.md` §9), each a service start and a network-state read, paid only by anchors with no
  usable fix. **The alternative** was leaving it at the backstop's 30 minutes for no extra
  wakeups, or going to 10 for a tighter bound and half again the wakes. Reversible: one constant.
- **The grace-restore confirmation window is 30 seconds** (`Presence.WIFI_CONFIRM`, 2026-08-24):
  when the grace alarm restores a Wi-Fi-only snooze, a due deadline defers this long for the async
  Wi-Fi callback to confirm a return before the snooze ends (`SPEC.md` §6.6). The callback for an
  already-connected network arrives in well under a second, so 30 s is generous headroom; its only
  cost is that a user who genuinely left and joined a *different* Wi-Fi waits this much longer for
  the snooze to end (fail-open, so a small over-hold, never an under-hold). **The alternative** was
  a tighter 5–10 s (less slack if the restore is slow) or reusing the §6.6 two-fix 30 s gap for
  symmetry. Reversible: one constant.
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
- **Superseded (2026-08-29): the screenshot refresh commit now pushes with a PAT**,
  which retriggers the whole `pull_request` round naturally, so nothing dispatches any
  more. The dispatch had to go rather than merely being tidied away: it reached exactly
  one workflow, so any required check living outside `ci.yml` never reported on a
  refreshed head and the pull request would wait on it forever. `zizmor` is that check
  as soon as the ruleset lists it — the ruleset half is still pending here, so this is
  prevention and a prerequisite rather than a live fix, but clothescast, where `zizmor`
  is already required, hit exactly this on its PR #1166. The record of the dispatch fix
  it replaced is kept below.

- **The screenshot refresh commit re-triggered CI via `workflow_dispatch`**
  (Codex, PR #15; resolved in PR #43, forced by the `gate` check becoming required — a
  refreshed head with no checks would sit blocked forever, not just under-verified). A
  push made with `GITHUB_TOKEN` deliberately starts no workflow run, but a *dispatch*
  made with the same token is GitHub's documented exception, so the refresh step now
  dispatches `ci.yml` onto the branch it just pushed and fails loudly if the
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
- **A persistent `DebugLogging.install()` failure used to leave `crashPending` at its
  compile-time `false` forever** (PR #89, 2026-08-23) — **resolved, option (a) taken.** It was
  the fourth finding on the "a failed check collapses into a confirmed negative" pattern
  (rounds 28, 30 ×2), and was first deferred here as a design question between (a) retrying
  `install()` itself so the missing-`sink` state can heal and (b) a genuine tri-state
  `crashPending`. (a) turned out to be small: `install()` was already idempotent (it returns
  early once `sink` is set), so the only missing piece was a context to retry with. `install()`
  now caches the application context synchronously, and both crash-pin reads call a
  `reinstallIfNeeded()` before giving up — a transient installation failure heals on the next
  read, and a permanent one still reports honestly rather than claiming a confirmed absence.
  `resetForTest()` clears the cached context alongside the sink, so the pre-install tests still
  pin the genuine "never installed" state. (b) stays unbuilt and unneeded: with the retry in
  place there is no longer a state the UI has to render as "can't tell".
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
  and they are absent entirely once the capability is in place. (Superseded 2026-08-24: `Grant`
  is gone too — every row now says `Allow`/`Allowed`, `SPEC.md` §5.2.)
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
      default branch requiring `ci.yml`'s always-reporting `gate`
      job and the `codex` status, plus conversation resolution and
      up-to-date branches, with the auto-merge setting enabled.

- [ ] **Following the push rules correctly red-lines `gate`/`lanes` every time**
      (observed twice on PR #141, 2026-08-30). `ci.yml` triggers on
      `pull_request: [opened, synchronize, reopened, edited]`, and `edited` is there
      deliberately — a retarget changes what the diff is measured against while the head,
      and any `gate`/`lanes` run already minted on it, stays put. `AGENTS.md` separately
      requires the PR title and body to be refreshed *with* the push rather than after it.
      Together those make a second run inevitable seconds after the first: push starts run
      A, the body refresh starts run B, the concurrency group cancels A's heavy jobs, and
      A's `gate`/`lanes` — `if: always()` over `needs: [classify, build, screenshot-tests,
      sync-screenshots]` — read `build=cancelled` and report **failure** on the same head.
      Self-clearing (branch protection reads the most recent run per check name, and B
      mints its own), so it costs noise and a wasted CI cycle rather than a merge — but it
      fires on every correctly-followed push, which is exactly the shape that trains a
      reader to ignore a red required check.
      **The obvious one-liner is a fail-open trap, so this is not a quick fix.** Swapping
      `if: always()` for `if: ${{ !cancelled() }}` makes the superseded run's gate *skip*
      instead of fail — and `ci.yml`'s own trigger comment already records that **GitHub
      counts a SKIPPED required check as satisfied**. A head whose runs were all cancelled
      would then show a satisfied gate having verified nothing, which is the one direction
      the whole gate design refuses. A correct fix has to let `gate` tell "my run was
      superseded" from "the heavy jobs genuinely failed" — report neutral or leave it
      pending rather than skipped, or look up whether a newer run exists for the same head
      — and since that changes the required check's own semantics it is the maintainer's
      call, and it may belong in `mikelward/lanes` rather than here.
      **Cheap partial mitigation meanwhile**: when a push needs no body change, don't make
      one; a lone `synchronize` runs uncancelled and goes green.
      **That mitigation does not cover a UI-touching PR** (observed on PR #144,
      2026-08-30). `sync-screenshots` auto-commits `ci: refresh recorded screenshots` to
      the branch, which is itself a `synchronize` — so any PR that records a new snapshot
      gets its second run from CI rather than from the author, and cancels the first no
      matter how the push was made. Same self-clearing failure, same wasted cycle, and
      nothing the author can decline to do. It strengthens the case for fixing this in
      `gate` itself rather than by pushing more carefully.
      **Better mitigation, when a body change *is* needed: edit the body first, then push**
      (PR #150, 2026-08-30). `AGENTS.md` asks for the refresh *with* the push, which the
      earlier reading took as "push, then edit" — and that is the ordering that lands the
      cancelled run's red `gate` on the **current** head, where branch protection reads it.
      Editing first fires the `edited` run against the *old* head, so when the push
      supersedes it the failure is minted on a SHA nothing gates on, and the push's own run
      is the newest on the head that matters. Same number of runs, same wasted cycle; the
      noise just stops landing where it can be mistaken for a real failure. Does not help
      the `sync-screenshots` case above, which the author does not control.

- [ ] **`deploy`'s display name is load-bearing and now inaccurate.** The job
      is still called `Build and release` after the release build moved to
      `release-build`, because `Build release notes` selects
      `.name == "Build and release"` against the Actions API in two places:
      the release-notes range base, and the guard that stops a rerun
      re-uploading a versionCode. Renaming it blinds both to every run
      recorded after the rename — notes repeat shipped subjects, and a rerun
      gets rejected by Play. Renaming properly means accepting both names in
      the selectors for as long as any pre-rename publish is still reachable
      by the walk, then dropping the old one — the same staged-rename shape
      used for the `gate` → `lanes` check. Caught by Codex on PR #133.
