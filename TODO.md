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
- [ ] Gradle + AGP + Compose skeleton, buildable in CI: application ID `app.snoozemo`,
      minSdk 33, targetSdk 36, `versionCode` derived from the `main` commit count.
- [ ] Split the core from the UI in the scaffold itself (maintainer, 2026-08-11), starting
      from `SPEC.md` §11's shape: `:app`, `:tile`, `:core`, `:dnd`, `:presence`. The
      requirement is a seam that keeps the core functional, testable, and buildable on its
      own — a Gradle module is the version the build enforces, so it is the default, but
      the modules may be cut differently if that serves the same end better. Prove it by
      building and testing `:core` alone (`./gradlew :core:test`) with no Android
      dependencies pulled in.
- [ ] Two product flavors, `play` and `direct` (`SPEC.md` §3.4), differing only below
      `PresenceMonitor`. `play` is the default and the one CI builds first.
- [ ] CI workflow (`.github/workflows/android-ci.yml`): build, unit tests with failing-test
      PR comments, lint, and the Roborazzi screenshot job with its `--tests` allow-list.
      **Deliberately not in the first PR** — a workflow that runs `./gradlew` with no Gradle
      project would be red on arrival. It lands with the skeleton above.
- [ ] `docs/PRIVACY.md` backing the hosted privacy policy, plus the Play Data Safety
      answers it has to agree with ("no data collected, no data shared", `SPEC.md` §12).

## Phase 1 (M1) — The DND half

Deliberately first and deliberately small: it proves `setAutomaticZenRuleState` actually
silences the device before anything is built on top of it. On Pixel that is a formality;
the point is that every other line of the app is worthless if it isn't true.

- [ ] `ZenRuleManager`: create one long-lived `AutomaticZenRule` at first successful
      onboarding, persist its id, never churn it per snooze (`SPEC.md` §5.3).
- [ ] API 35+ `AutomaticZenRule.Builder` path with the SDK 33/34 constructor fallback, and
      the 4-arg `Condition` with `SOURCE_USER_ACTION` / `SOURCE_CONTEXT` on API 35+
      (`SPEC.md` §5.4). No `ConditionProviderService` — it is deprecated and unnecessary.
- [ ] `ACCESS_NOTIFICATION_POLICY` onboarding: the settings-screen grant flow, plus a
      listener on `ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` so revocation
      mid-snooze ends the snooze and says why.
- [ ] Default `ZenPolicy`: `INTERRUPTION_FILTER_PRIORITY` allowing alarms, media, system
      sounds, and repeat callers. Total silence available in settings, never the default.
- [ ] Debug arm/release button — no tile, no presence engine, no UI polish.
- [ ] Verify on a real Pixel that the rule silences the device and is visible and
      disableable in Settings (hardware item 3 below).

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

## Phase 6 (M6) — Internal-track release on Play

- [ ] Release plumbing: signing, Play Console setup, the `deploy` job, and the "What's new"
      generation from commit subjects described in `AGENTS.md`.
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
  instead.
