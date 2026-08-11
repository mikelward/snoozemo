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
      minSdk 33, targetSdk 36, `versionCode` derived from the `main` commit count.
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
- [ ] When the screenshot job lands, its diff-comment upsert must not repeat the silent
      drop this repo just fixed on the failing-test comment: the sibling repos' version
      uses a bare `curl -sS` for the lookup, the PATCH, and the POST, so an HTTP error
      exits 0. The lookup is the awkward one — failing it open posts a duplicate comment
      instead of updating the existing one — so it needs an explicit HTTP-status check
      rather than a straight `--fail-with-body`. Same fix is queued in
      `mikelward/typelauncher` and `mikelward/simmo`, where the job already exists.
- [ ] Launcher icon and tile mark. The scaffold ships a placeholder `Z` vector; the real
      mark is drawn with the tile in Phase 2, where `SPEC.md` §4.2's constraint applies (24
      dp, single color, legible flattened). The tile icon is **any drawable the app
      supplies** — there is no system catalog to pick from — declared as `android:icon` on
      the `TileService` and swappable at runtime via `Tile.setIcon`. What is fixed is the
      *treatment*: the system tints it per tile state, so only the alpha channel survives
      and the asset is effectively a silhouette.
- [ ] `docs/PRIVACY.md` backing the hosted privacy policy, plus the Play Data Safety
      answers it has to agree with ("no data collected, no data shared", `SPEC.md` §12).

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

- [ ] `SnoozeTileService`: `Zz` icon, `Snooze here` / `Snoozing` labels, subtitle countdown,
      `setStateDescription` for TalkBack, arm-on-tap and end-on-tap, long-press to settings
      via `QS_TILE_PREFERENCES` (`SPEC.md` §4.2). Arming works with the device locked — no
      `unlockAndRun()`.
- [ ] `ArmTrampolineActivity` (`SPEC.md` §6.9): transparent theme, starts the service in
      `onCreate` before any UI, launched via `startActivityAndCollapse(PendingIntent)`.
- [ ] Ongoing notification on channel `snooze_active`, `IMPORTANCE_LOW`, with `End now` and
      `+30 min` actions (`SPEC.md` §4.3).
- [ ] `SnoozeController` state machine (IDLE / ARMING / ARMED / CHECKING / RELEASED) as
      plain Kotlin over an injected clock — the unit-test surface for everything that
      follows.
- [ ] Duration cap: 8 h default, configurable 30 min – 24 h, `AlarmManager
      .setAndAllowWhileIdle` as the backstop plus an in-service coroutine timer for the
      normal case. Armed *before* anything that can throw.
- [ ] `endSnooze(reason)` as the single idempotent exit path, and the one-shot ended
      notification that names the reason (`SPEC.md` §4.5).
- [ ] `requestAddTileService()` during onboarding — asked once, never again.
- [ ] Persist `ActiveSnooze` on every transition (DataStore), so process death is
      recoverable.
- [ ] **Move the policy-access listener off the activity** (flagged by Codex on PR #5).
      Phase 1 registers `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` in
      `onStart`/`onStop`, so access revoked while the app is backgrounded mid-snooze isn't
      noticed until the user reopens it — the state reconciles on return, but "ends the
      snooze and says why" (`SPEC.md` §8.2) needs to happen when it happens, not when
      someone looks. It belongs on the service that owns the running snooze, which is what
      this phase builds; an activity is structurally the wrong host for it, and process
      death would defeat an `Application`-scoped receiver anyway.

## Phase 3 (M3) — Presence: the `play` flavor

- [ ] `PresenceMonitor` interface and `GeofencePresenceMonitor`, with everything above the
      interface flavor-agnostic (`SPEC.md` §6.1).
- [ ] Anchor capture at arm time — SSID not BSSID (`SPEC.md` §6.2) — with the ≤10 s ceiling
      that degrades to Wi-Fi-only or duration-only rather than blocking the arm.
- [ ] Three independent wake-up sources feeding one confirmation test (`SPEC.md` §6.10):
      geofence exit, Wi-Fi loss via `NetworkCallback`, and a 15–30 min `WorkManager`
      backstop. No source ends a snooze on its own evidence.
- [ ] The departure test itself (`SPEC.md` §6.6): accuracy gate, 50 m hysteresis, two
      qualifying fixes ≥30 s apart *or* one unambiguous fix beyond radius + 500 m. Covered
      by recorded fix traces including bad-accuracy jumps.
- [ ] Wi-Fi as suppressor only (D4): associated with the anchor SSID suppresses location
      work entirely; loss escalates to `CHECKING` and never ends a snooze on its own.
- [ ] **The on-device debug log** (`SPEC.md` §4.6), landing here rather than later because this is
      the phase that needs it: hardware item 2 asks for every geofence callback measured against a
      ground-truth departure over a week of ordinary use, and there is no way to collect that by
      watching a phone. Records state transitions and their reasons, which wake-up source fired, the
      departure test's distance and accuracy arithmetic, tracking-mode changes, cap arming and
      firing, and permission state. On by default with a setting to turn it off (maintainer,
      2026-08-11), on-device, current run plus previous, rotated at start, in `cacheDir`. The floor is absolute and needs a test of its own: **no raw coordinates,
      no full SSID/BSSID, no user-typed place name** ever reach it.
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

- [ ] The two rows (`until <time>` seeded at now + 1 h rounded to the half hour, and
      `until I leave`), with `−` / `+` in 30-minute steps, floored at 30 min from now and
      ceilinged at the 8 h backstop.
- [ ] Choosing a time **lowers the cap**; it does not disable departure tracking. Whichever
      comes first wins (`SPEC.md` §7).
- [ ] Dismissing the sheet, or never seeing it, leaves the user correctly snoozed.
- [ ] Setting to disable the sheet entirely — the trampoline then finishes in `onCreate`.
- [ ] Screenshot tests for the sheet, wired into the CI allow-list.

## Phase 5 (M5) — Edge cases and degraded modes

- [ ] Service killed and recreated: re-assert the zen rule, resume tracking, and where a
      background context can't get location, degrade to duration-only with a
      `Resume tracking` notification action (`SPEC.md` §8.1).
- [ ] Reboot: re-assert the rule, degraded mode, cap continues from the *original* start
      time. `On restart: resume / end` setting, defaulting to resume (`SPEC.md` §8.3).
- [ ] Permission revoked mid-snooze — policy access or location — ends the snooze with a
      reason (`SPEC.md` §8.2).
- [ ] The §8.4 table: airplane mode, location services off, double-arm, short trip and
      return, bad-accuracy anchor, battery saver, uninstall while snoozed.
- [ ] Pre-existing DND: Snoozemo arms on top and, on release, turns off only its own rule
      (`SPEC.md` §5.6).
- [ ] **Sharing the debug log** (`SPEC.md` §4.6) — the user-facing half of the Phase 3 log,
      matching the sibling repos: a `Share debug logs` action through the system share sheet with a
      copy-to-clipboard fallback (no `INTERNET`, so the share sheet *is* the transport), and a
      post-crash banner offering to share the crashed run or dismiss it. Only a crash raises the
      banner — an ordinary process death, force-stop, or app update leaves the run shareable without
      nagging. Sizing matters: the payload crosses a Binder transaction twice, and an over-large one
      fails both silently, so bound it per section and in total.
- [ ] `docs/PRIVACY.md` must describe what the log carries **before** the sharing surface ships —
      that ordering is the rule, not a preference (AGENTS.md, *Privacy*).

## Phase 6 (M6) — Internal-track release on Play

- [ ] Release plumbing: signing, Play Console setup, the `deploy` job, and the "What's new"
      generation from commit subjects described in `AGENTS.md`.
- [ ] Make a release build **fail** when its version can't be derived from git, rather than
      warning (`app/build.gradle.kts`). The fallback exists so a checkout without git still
      builds; once a build can reach a tester or Play, falling back to versionCode 1 is
      either a rejected upload or a phantom downgrade, and a warning in a CI log is not
      where anyone would find it. Same for the shallow-clone case, which is worse because
      the count *looks* fine — the build already warns; a release build should refuse.
- [ ] Data Safety declaration: "no data collected, no data shared" (`SPEC.md` §12).
- [ ] In-app prominent disclosure before the location permission prompt, and the
      demonstration video the background-location declaration needs.
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

### Tuning — these change details, not direction

4. [ ] Real battery draw over a 4-hour stationary snooze versus the `SPEC.md` §9 estimates,
       per flavor.
5. [ ] Whether the §4.4 sheet is right at all: is now + 1 h a sane default, are 30-minute
       steps the right granularity, and does anyone reach for the time row often enough to
       justify the calendar in v1.1?
6. [ ] Does the trampoline activity produce any visible flash (`SPEC.md` §6.9)?

### Samsung, at Phase 8

7. [ ] Does `setAutomaticZenRuleState` silence a One UI 8 device, and is the rule visible
       and disableable in Samsung's own Settings?
8. [ ] Does `Tile.setSubtitle` render on One UI?
9. [ ] Does Sleeping Apps interfere with geofence delivery, or with a `location`-typed
       foreground service in the `direct` flavor?

## Deferred

Nothing here is scheduled; each is a sequel that follows from something already built
(`SPEC.md` §14).

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
      - `dataExtractionRules` (API 31+, and minSdk is 33) can allow **device-to-device
        transfer while disabling cloud backup** — settings survive a new phone without a
        place list reaching Google's servers. Probably the answer.
      - Full auto-backup, which does put it in the cloud (encrypted with the device PIN on
        modern Android) — a real privacy question, and one that touches the Data Safety
        answers, so it is the maintainer's call and not autopilot's.
      - A user-initiated export/import file: no ambient copies, but nobody does it before
        losing the phone.
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
- [ ] **Wear OS tile.**

## Decisions needing review

Judgment calls made without an explicit answer from the maintainer. Each is reversible;
none is load-bearing yet.

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
