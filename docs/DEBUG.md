# Debug log sharing — implementation plan

Planning doc for `TODO.md` Phase 5's "Sharing the debug log" item (`SPEC.md` §4.6). Docs
only — no code in this PR. Written by copying the shape of the sibling repos' own
implementations (Simmo's `DebugReport.kt`, ClothesCast's `diag/BugReport.kt` +
`LastCrashBanner.kt`) and narrowing it to what Snoozemo's SPEC actually asks for.

## What already exists (Phase 3)

The recording and persistence half is fully built and unchanged by this work. Both
halves have since moved to [mikelward/androidlog](https://github.com/mikelward/androidlog),
the shared debug log this app's own logger was one of four sources for; the names below
stay put, so nothing in this plan moved with them:

- `core/SnoozeDebugLog` — the in-memory ring buffer (`snapshot()`) and the privacy floor:
  this app never writes a raw coordinate, SSID/BSSID or typed place name into the log. Now a
  thin delegate to the library's `DebugLog`. **Since androidlog 1.0.44 the floor is a
  boundary, not an ingestion filter**, so what this device keeps is whole — an untagged
  `String` renders in full, and a throwable carries its message as well as its type and
  frames. Whoever raised the error composes that message — framework, runtime or bundled
  library — so it is the one text the app does not author;
  SPEC.md §4.6 records it as the floor's single exception and why it is accepted rather
  than scrubbed.
- `app/snooze/DebugLogFiles.kt` — `DebugLogStore` (the on/off setting) and `DebugLogging`
  (install + the settings-screen glue). Rotation, the crash pin and delete-on-off are the
  library's `DebugFileSink` now, not this app's.
- `SettingsScreen` already carries the on/off switch (`DebugLogRow`).

None of that reads its own files back out. This plan is entirely about the *reading* and
*sharing* half: a `Share debug logs` action on `SettingsScreen` (maintainer, 2026-08-23 — not a
new screen) and a post-crash banner on `MainScreen` — the screen the user actually lands on,
above even the Do-Not-Disturb-access banner (maintainer, refined during implementation), rather
than a screen reached only by navigating to Settings.

## What the sibling repos do

**Simmo (`DebugReport.kt`)**: builds a plain-text payload (build/device info, permission
grants, a full settings/rules dump, the in-memory log, the previous run's log if it didn't
exit cleanly), copies it to the clipboard, and fires `Intent.ACTION_SEND` through a chooser.
Both routes are best-effort and independent; a failure notifies the user only if *neither*
landed. The previous run's file is deleted only after the clipboard copy is confirmed —
never on the chooser's say-so, since `ACTION_SEND` has no delivery callback. Injectable seams
(`payloadCollect`, `clipboardWrite`, `chooserLaunch`) make every combination unit-testable
without a real `Activity` or `ClipboardManager`.

**ClothesCast (`diag/BugReport.kt` + `ui/today/LastCrashBanner.kt`)**: the same
clipboard-then-chooser shape, plus a `LastCrashBannerCard` shown on the main screen when an
unacknowledged crash file exists — `Share report` and `Dismiss`, both of which mark the
crash acknowledged so the banner doesn't reappear. Crash state is a process-wide
`StateFlow<Boolean>`, refreshed on `ON_RESUME` so a crash written by another process is
picked up without a restart.

Snoozemo takes the payload/delivery shape from both and the crash-banner shape from
ClothesCast, but differs in two ways forced by its own architecture and SPEC:

- **No settings/rules dump.** Simmo's payload is dominated by the user's rules; Snoozemo has
  no equivalent structured state worth dumping — the log *is* the report. The structured
  header shrinks to build/device/permissions only.
- **No cross-process crash polling.** Snoozemo is single-process (`ZenRuleIdStore`'s own
  comment notes no `android:process` anywhere), so ClothesCast's `ON_RESUME` re-check for a
  crash written by a sibling process doesn't apply — a crash pin is read once, at the first
  frame after this app's own (re)start, the same discipline every other `MainActivity` row
  already follows.
- **Callback-based, not `Flow`.** `:app` uses `Thread` + callback throughout (`MainActivity`,
  `DebugLogging` itself) rather than coroutines/`StateFlow` — `kotlinx-coroutines-android` is
  a dependency but is used in exactly one file (`SnoozeService`) today. New code follows the
  established local convention rather than introducing a second concurrency style for one
  feature.

## What Snoozemo's version needs

### 1. Reading the persisted files

Methods on **`DebugLogging`**, the app's wrapper — not on the sink. When this was planned the
sink was this app's own; it is `mikelward/androidlog`'s now, and the split fell out as: the
library owns the files (`readPreviousRun`, `clearPreviousRun`, `acknowledgeCrashBanner`,
`requestCrashRecompute`, `addCrashListener`), and `DebugLogging` owns the app-shaped question
each of these asks of it. All are enqueued on `DebugLogging`'s own FIFO worker (never blocking
the caller) and answer through a callback, the same shape `DebugLogging.setEnabled` uses:

- `hasPinnedCrash(onResult: (pinned: Boolean, checkSucceeded: Boolean) -> Unit)` — whether an
  unacknowledged crash run is still on disk, and whether the check could be made at all.
- `readPreviousOrCrash(onResult: (run: PreviousRun?, wasCrash: Boolean, readSucceeded: Boolean) -> Unit)`
  — the unshared prior runs, oldest first, as the shared logger's handle. Several are kept side
  by side (SPEC.md §4.6), so a crashed run does not displace an ordinary one; a crashed run
  carries its own suffix rather than occupying a single `previous` slot. **The handle is passed
  to the caller and never held here**: it is what lets a delivered report consume exactly the
  files it was built from, so two overlapping shares cannot have the first destroy a run only
  the second had read.
- `consumeCrashPin(run: PreviousRun?, onResult: (Boolean) -> Unit)` — what a landed Share
  performs: `clearPreviousRun(run)` for exactly the files behind `run`, then
  `acknowledgeCrashBanner()`. A null handle consumes nothing, which is the safe direction for a
  caller that was given nothing.
- `dismissCrashPin()` — Dismiss without sending, over the library's
  `acknowledgeCrashBanner()`. Takes the run off its crash-suffixed name, after which it is an
  ordinary prior run: still shareable, pruned by age like any other. A refusal leaves the
  banner **up**, by construction rather than by a second code path.

Each no-ops safely (`onResult` with the "nothing to report" answer) when no sink is installed
yet — mirroring `DebugLogging.setEnabled`'s own "not installed" handling.

### 2. Building the payload

New file, `app/snooze/DebugReport.kt` (package-mate of `DebugLogFiles.kt` — needs
`Context`/`Intent`/`ClipboardManager`, so it stays in `:app`, not `:core`).

Sections, in order:

1. **Header** — `Snoozemo debug log`, captured timestamp.
2. **Build** — version name/code, build type, application id, debuggable.
3. **Device** — manufacturer, model, Android release + SDK int, locale.
4. **State** — DND policy access, notification permission, location permission (foreground
   and background), whether location services are on system-wide, battery-saver state,
   whether the tile has been added. Every one of these is a fact already in `SPEC.md`'s
   correctness checklist ("no Wi-Fi, no location fix, permission revoked mid-snooze, ...")
   and each is a plausible whole answer to "why didn't it end" (SPEC.md §4.6).
5. **Earlier runs** — present only when `readPreviousOrCrash` found something. Plural
   because the handle covers every unshared prior run as one text with no marker saying
   where each begins, and `wasCrash` is the global banner state rather than a fact about
   one of them: `(one ended in an uncaught exception)` when it is true, otherwise
   unlabeled. Naming *which* run crashed would need per-run metadata the handle does not
   carry.
6. **Recent log** — `SnoozeDebugLog.snapshot()`, newest-last, the same shape Simmo's
   "Recent log" section uses.

No settings/rules section — see above.

### 3. Size bounds

Same reasoning as both siblings: the payload crosses Binder twice (into the clipboard, then
again in the chooser's `ACTION_SEND` extra), the per-process buffer is shared and ~1 MB, and
strings parcel as UTF-16 (2 bytes/char). Proposed budgets, scaled down from Simmo's (which
carries a full rule/SIM dump Snoozemo has no equivalent of) toward ClothesCast's smaller
figures, since Snoozemo's structured section is closer to ClothesCast's build/device/permissions
header than to Simmo's rule dump:

| Section | Budget (chars) |
|---|---|
| Total (`MAX_SHARE_PAYLOAD_CHARS`) | 60,000 |
| Structured header (build/device/state) | 4,000 |
| Previous/crashed run | 25,000 |
| Recent log | 30,000 |

Numbers are a starting point for the implementation PR, not a commitment — they get pinned
by that PR's own bounds test (mirroring `DebugReportBoundsTest` / `BugReportBoundsTest`),
and can move if a real payload doesn't fit comfortably.

### 4. Delivery

`Intent.ACTION_SEND`, `type = "text/plain"`, `EXTRA_SUBJECT = "Snoozemo debug log —
$versionName"`, `EXTRA_TEXT = <payload>`, wrapped in `Intent.createChooser`. Clipboard copy
(`ClipData.newPlainText`) is attempted independently and always, not gated on the chooser.
`Toast`/on-row failure text only when *both* fail to land — matching the `DebugLogRow`
pattern already on this screen (a short message under the tapped control, not a `Toast`,
since nothing else on this screen uses one).

The crash pin is consumed **only when the clipboard copy is confirmed** — the durable proof
of delivery, since `ACTION_SEND` has no send/selection callback and firing the chooser is not
proof the user completed a share. A share whose clipboard copy failed leaves the crash file
in place for a retry, per AGENTS.md principle 3 ("never lose the user's settings" extended to
the one piece of crash evidence that explains a stuck or early-ended snooze).

### 5. UI

- **`Share debug logs`** row on `SettingsScreen`, beside the existing `DebugLogRow` — reuses
  `SetupRow`'s shape (title, status, a verb button) even though sharing is repeatable rather
  than a capability to fix once, so its action never drops away. A failure line under it
  (both routes failed) follows the `debugLogSaveFailed` row's own shape — inside the row, not
  a `Toast`, not appended below the scrolling column.
- **Crash banner** on `MainScreen`, above even the Do-Not-Disturb-access banner, shown only
  while `hasPinnedCrash()` is true — modeled on ClothesCast's `LastCrashBannerCard`: title,
  body, `Dismiss` (text button, consumes the pin without sharing) and `Share` (filled button,
  shares — which itself consumes the pin only on a landed clipboard copy, so a failed share
  leaves the banner up for a retry rather than silently dropping the crash). `MainScreen`
  rather than `SettingsScreen`, refined during implementation (maintainer): it is the screen
  the user actually lands on, and a crash is exactly the thing that should not wait for a
  navigation to Settings to be seen. The `Share debug logs` row's own home is unaffected.
- Read once, at the first frame after this activity's own (re)start — see "No cross-process
  crash polling" above. **A completed Share or Dismiss does not write the screen's state
  directly from its own completion callback** — a configuration change can recreate the
  activity while the call is still in flight, and that closure would then update a dead
  instance invisibly to the user (Codex, PR #89). Instead `DebugLogging.watchCrashPinOutcome`
  and `DebugReport.watchShareOutcome` mirror `DebugLogging.watchSaveOutcome`'s existing
  shape exactly: a single-slot, process-level callback that any live instance can register
  for in `onStart`/unregister in `onStop`, fired after the operation completes, which the
  observer answers by re-reading the current truth rather than trusting a captured value.

### 6. Tests

- The file-level read/consume behavior — rename semantics, the copy+delete fallback
  (crucially reading `crash.delete()`'s own return, not `runCatching{}.isSuccess`, which reads
  true on a refused delete that threw nothing — Codex, PR #89), idempotency when the file is
  already gone. **Owned by `mikelward/androidlog`'s own `DebugFileSinkTest` now**, not this
  repository's — the sink moved there, and so did its tests. What stays here is
  `DebugLoggingTest`, covering the wrapper: the "not installed" answers, the settings gate, the
  legacy-directory migration, and the screen-facing outcome mirrors.
- A payload-builder test (mirrors Simmo's `buildDebugReportPayload` / ClothesCast's
  `buildBugReportPayload`) — pure function, unit-tested for section assembly and truncation.
- A share-flow test (mirrors `DebugReportShareTest` / `BugReportShareTest`) — the four
  clipboard×chooser outcome combinations, and that the pin is consumed on and only on a
  landed clipboard copy.
- A **floor test**, called out explicitly because `docs/PRIVACY.md` already promises one
  ("as a hard rule with its own automated test"): the built payload never contains a raw
  coordinate, a full SSID/BSSID, or a user-typed place name **that this app wrote**,
  exercised against a log/state containing genuinely realistic-looking fixture values for
  all three. It does not cover a thrown error's own message, which the app does not
  author — SPEC.md §4.6's single exception.
- Coverage for the two outcome watches themselves: fires after completion, closing stops it
  from hearing later completions, and a later registration doesn't get evicted by an earlier
  instance's deferred close — the same three properties `DebugLoggingTest` already pins for
  `watchSaveOutcome`.
- `MainScreenScreenshotTest` gains cases for the crash banner (present/absent);
  `SettingsScreenScreenshotTest` gains one for the share row's failure state.

### 7. Docs that must land in the same PR as the feature

- `docs/PRIVACY.md`'s "The debug log" section currently says "there is currently no sharing
  feature" (written when the log was recording-only) — it must describe the real feature
  before the code ships (`TODO.md`'s own item, AGENTS.md *Project documentation*: "before the
  sharing surface ships... is the rule, not a preference").
- `TODO.md` — check the item off; log any autopilot guess (the exact size budgets above, the
  hidden-vs-disabled question) under `Decisions needing review` if it isn't resolved by the
  time that PR lands.

`SPEC.md` §4.6 already describes most of the target behavior (on-by-default, share sheet +
clipboard fallback, the crash pin) — it now also names `MainScreen` as the banner's home,
added once that was refined during implementation.
