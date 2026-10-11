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
never on the chooser's say-so, since `ACTION_SEND` has no delivery callback. (Snoozemo keeps
that confirmation gate but, since 2026-09-30, deletes nothing on a share — see §5.) Injectable seams
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
library owns the files (`readPreviousRun`, `acknowledgeCrashBanner`,
`requestCrashRecompute`, `addCrashListener`), and `DebugLogging` owns the app-shaped question
each of these asks of it. All are enqueued on `DebugLogging`'s own FIFO worker (never blocking
the caller) and answer through a callback, the same shape `DebugLogging.setEnabled` uses:

- `hasPinnedCrash(onResult: (pinned: Boolean, checkSucceeded: Boolean) -> Unit)` — whether an
  unacknowledged crash run is still on disk, and whether the check could be made at all.
- `sinkForReport()` — the installed sink, handed to a report. The report reads the earlier
  runs from it on its own thread, through androidlog's `DebugReport.collect`, so a slow read
  never parks this worker and the opt-out queued behind it. `Unavailable` when there should
  be a sink and none could be handed over; the report then says the earlier runs could not
  be included.
- `dismissCrashPin()` — Dismiss without sending, over the library's
  `acknowledgeCrashBanner()`. Takes the run off its crash-suffixed name, after which it is an
  ordinary prior run: still shareable, pruned by age like any other. The banner comes down
  even when the run cannot be renamed or read (maintainer, 2026-10-11): the log stays, and the
  banner returns only when a later check reads it as a crash. Only a worker that refuses the
  dismissal leaves it up, and says so on the screen.

Each no-ops safely (the "nothing to report" answer) when no sink is installed
yet — mirroring `DebugLogging.setEnabled`'s own "not installed" handling.

### 2. Building the payload

`app/snooze/DebugReport.kt` (package-mate of `DebugLogFiles.kt`) builds this app's section and
hands it to androidlog's `DebugReport.collect`, which appends the earlier runs after it and holds
the whole report to a size that pastes. Every app in the fleet shares through the same two
calls, `collect` and `deliver`, so the reports and the banner behave the same everywhere.

Sections, in order:

1. **Header** — `Snoozemo debug log`, captured timestamp.
2. **Build** — version name/code, build type, application id, debuggable.
3. **Device** — manufacturer, model, Android release + SDK int, locale.
4. **State** — DND policy access, notification permission, location permission (foreground
   and background), whether location services are on system-wide, battery-saver state,
   whether the tile has been added. Every one of these is a fact already in `SPEC.md`'s
   correctness checklist ("no Wi-Fi, no location fix, permission revoked mid-snooze, ...")
   and each is a plausible whole answer to "why didn't it end" (SPEC.md §4.6).
5. **Recent log** — `SnoozeDebugLog.boundedSnapshot(...)`, newest-last: the ring's newest
   lines, with any pinned lines the ring has since evicted (why the previous processes
   ended) put back ahead of them. The heading counts the lines shown and says older ones
   may be dropped, rather than claiming a total the trimmed list can't know.

No settings/rules section — see above.

After this section the library appends **Earlier runs (newest last)**: the prior runs, oldest
first, read to fit their share with the newest kept. When there should be a log and none could
be handed over, this section ends with that heading and a line saying the earlier runs could not
be included, rather than reading like a report with none to send.

### 3. Size bounds

The report is read by pasting it: into a chat, a message, an email or an issue, as plain text
with no attachment. So the whole of it has to survive a paste, and a paste keeps less than the
share sheet's Binder transfer allows. Reports at the old 60,000 characters were cut at about their
first 20,000 when pasted, and what went was the newest log lines, the ones that explain the bug.
The ceiling is androidlog's (`DebugReport.MAX_REPORT_CHARS`), set by the library so every app's
report stays pasteable:

| Section | Budget (chars) |
|---|---|
| Total (androidlog's `MAX_REPORT_CHARS`) | 18,000 |
| Earlier runs (androidlog's `MAX_EARLIER_RUNS_CHARS`, heading included) | 4,000 |
| This app's section (`APP_SECTION_CHARS`, androidlog's) | 14,000 |
| — structured header (build/device/state) | 4,000 |
| — recent log | the rest of the section |

Each part has its own cap, so a long one cannot crowd out another, and the most important is
what fits: the build and state first, then the earlier runs, newest kept, then this run's log,
which takes the room the rest left. The log is fetched to fit that room
(`DebugReport.logLines`): why the previous processes ended first, then the newest lines.

### 4. Delivery

androidlog's `DebugReport.deliver`, on the main thread: a clipboard copy and an
`ACTION_SEND` chooser (`EXTRA_SUBJECT = "Snoozemo debug log — $versionName"`), each attempted
regardless of the other. The on-row failure text shows only when *both* fail — matching the
`DebugLogRow` pattern already on this screen (a short message under the tapped control, not a
`Toast`, since nothing else on this screen uses one).

**Sharing deletes nothing.** Whether a report was actually sent can't be known: the chooser
reports nothing back, and a clipboard copy is only a copy. The earlier runs stay on disk under
the library's own retention, and a later report may carry some of the same lines. A landed
clipboard copy lowers the crash banner for every crash, one too old for the report included; that
crash's log stays on disk, still shareable.

### 5. UI

- **`Share debug logs`** row on `SettingsScreen`, beside the existing `DebugLogRow` — reuses
  `SetupRow`'s shape (title, status, a verb button) even though sharing is repeatable rather
  than a capability to fix once, so its action never drops away. A failure line under it
  (both routes failed) follows the `debugLogSaveFailed` row's own shape — inside the row, not
  a `Toast`, not appended below the scrolling column.
- **Crash banner** on `MainScreen`, above even the Do-Not-Disturb-access banner, shown only
  while `hasPinnedCrash()` is true — modeled on ClothesCast's `LastCrashBannerCard`: title,
  body, `Dismiss` (text button, consumes the pin without sharing) and `Share` (filled button,
  shares — which lowers the banner only on a landed clipboard copy, so a failed share leaves
  it up for a retry). Neither deletes
  the log (maintainer, 2026-09-30): consuming the pin lowers the banner, and the runs age out. `MainScreen`
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

- The file-level read/acknowledge behavior — rename semantics, the copy+delete fallback
  (crucially reading `crash.delete()`'s own return, not `runCatching{}.isSuccess`, which reads
  true on a refused delete that threw nothing — Codex, PR #89), idempotency when the file is
  already gone. **Owned by `mikelward/androidlog`'s own `DebugFileSinkTest` now**, not this
  repository's — the sink moved there, and so did its tests. What stays here is
  `DebugLoggingTest`, covering the wrapper: the "not installed" answers, the settings gate, the
  legacy-directory migration, and the screen-facing outcome mirrors.
- A payload-builder test (mirrors Simmo's `buildDebugReportPayload` / ClothesCast's
  `buildBugReportPayload`) — pure function, unit-tested for section assembly and truncation.
- A share-flow test (`DebugReportShareTest`) — the delivery's outcome passed through, the tap
  ordering, and, against the real library, that a share lowers the banner and keeps the run.
  The clipboard×chooser rules themselves are androidlog's, tested there.
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
