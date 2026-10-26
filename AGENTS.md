---
trigger: always_on
alwaysApply: true
last_modified: 2026-10-26
---

# Snoozemo

`CLAUDE.md` and `GEMINI.md` are symlinks to this file. Edit `AGENTS.md` and bump `last_modified`.

**At the start of every session, print the full absolute path of the `AGENTS.md` you
loaded and its `last_modified` date** (front matter), so a stale or wrong copy is caught first.

Snoozemo is an Android app (Kotlin, Compose) that puts the phone into Do Not Disturb **until
you leave where you are right now**: a Quick Settings tile tap arms it, walking away disarms
it. Decisions live in `SPEC.md`, the phased plan in `TODO.md`. Mirrors
`mikelward/typelauncher` and `mikelward/simmo` (Simmo's `AGENTS.md` is the tiebreaker).

**Status: both halves are built; presence has never run on a handset** — Phase 3 owes
on-device verification (`TODO.md`). Every rule below is live.

Keep this file short — one-liners over essays, **under 22,000 bytes** (`wc -c AGENTS.md`):
Antigravity caps rule files at 24 KB and silently truncates the tail. State the rule and the
failure it prevents; incident narratives belong in commits; how-to detail lives in
`dev-docs/agent-notes.md`.

## Project documentation

- Keep `SPEC.md` current when changing behavior, architecture, persistence, permissions,
  navigation, or testing. It records *what* and *why* (zen-rule mechanism, Wi-Fi as
  suppressor), not detail ("still true if rewritten?"; dp values, helper names, which
  composable holds a flag are code).
- **A changed decision goes in `SPEC.md` with its reason** — the spec is a chain of decisions
  (D1–D9, §3); swapping a conclusion silently makes the next reader re-derive it.
- Keep `TODO.md` current: check items off, file new work in the right phase.
- Developer docs live in `dev-docs/` (docs lane, `.github/lanes.conf`); `docs/` is
  user/store-facing.

## Engineering quality bar

Principles in priority order; where a rule below conflicts, the principle wins:

1. **Never leave the phone silently quiet** — worse than ringing needlessly. Every ambiguous
   state ends the snooze (`SPEC.md` D7, fail open); the duration cap holds when every sensor
   fails.
2. **Never fail silently.** Do the safe thing **and say so** in the ongoing notification
   (`Snoozing at Home · location paused, ends in 3h 40m`); never stay armed with tracking
   dead, degrade silently, or end a snooze for a reason the user can't reconstruct.
3. **Never lose the user's settings** (places, per-place policies and caps, zen-rule policy,
   the tile). Loss is a last resort after the alternatives; a real trade-off is the user's,
   stated plainly. `allowBackup="false"` is about the cloud — phone-swap migration is
   **undecided, not off** (§12); no code or copy treats it as permanent or current.
4. **Do the work ahead of time**: zen rule id, settings, last SSID in memory before the tap;
   arming never feels slow or refuses (§4.1).
5. **Don't make the user wait**: nothing blocking on the tap path, in the trampoline's
   `onCreate`, or before a first frame; the service starts before any UI (§6.9).
6. **Say why** — comments, PRs, and the notification for anything the user would guess at.

Every change holds:

- **Correctness**: matches `SPEC.md`; handles no Wi-Fi, no fix, permission revoked
  mid-snooze, reboot, process death, location off, battery saver, config change. Hard
  invariants: **the duration cap always fires**, **`endSnooze` is idempotent** (§7),
  **Snoozemo ends only its own zen rule** (§5.6). New behavior gets a unit test; a bug fix a
  test failing before and passing after.
- **The arm path** (one tap, maybe locked, under a second, no setup): service started within a
  frame of `onCreate`; `ARMING` never blocks on a fix (10 s, then Wi-Fi- or duration-only, said);
  no synchronous disk, `PackageManager`, telephony or `NotificationManager` policy IPC before
  `STATE_TRUE`, **except the ringer ceiling**: our own `setRingerMode` turns DND off, so it
  must precede `STATE_TRUE` (§5.9); warmed, bounded, yielded to by recovery, and no
  precedent — count the real cost of anything added in `RingerController.quiet`. A PR
  touching the tile, trampoline or `SnoozeController` arm path states what it reads and
  waits for.
- **Jank-free UI**: the sheet's first frame is real content — no blank trampoline flash, no
  spinner where a sheet was possible; `remember`/`derivedStateOf`/stable types, no I/O in
  composition, warm at startup.

When unable to verify locally, say so ("unit-tested; silencing needs a device"); `TODO.md`'s
hardware list tracks what's owed.

## Spacing, copy, icons

- 4dp grid; reuse sibling values; symmetry by default (justify asymmetry in the PR). Flag
  off-grid spacing outside the diff (file, line, fix) without fixing it in the same commit.
- Shortest unambiguous copy (`Snoozing`, not `Snooze is active`); justify longer in the PR.
  Tile copy (truncated, carries a countdown, §4.2) is verified on a device.
- **Standard Material icons by default**; ask if none or two fit.

## Privacy

- **Never put user data in an artifact leaving this machine** (commits, PRs, replies, issues,
  branches, comments, fixtures, snapshots, logs): **coordinates, SSIDs/BSSIDs, typed place
  names, snooze times and durations, anything revealing where someone lives, works or
  sleeps**. Paraphrase bug reports; ask when in doubt.
- **The test is whose a value is, not whether the name is real**: `Home`, `ExampleWifi`,
  `(0.0, 0.0)` are fine; a real router's SSID or a real home's coordinates are not. Record
  snapshots from fixtures, never a device with a real anchor.
- **Anything new leaving the device is a product decision** changing Play Data Safety. Crash
  reporting passed that gate (§12); nothing off-device carries a coordinate, SSID/BSSID or
  place name, and location or configuration leaves only with plain consent. **No custom keys
  or custom Analytics events** — their parameters reach crash breadcrumbs (§12).
- **The floor is an exhaustive list**; unlisted things are judgment calls, never floor by
  inference, or the app can't explain its own failures.
- **On-device debug log (narrow exception)**: coarse state and reasons — controller state,
  which wake-up source fired, anchor-SSID association, departure-test *result*, distance in
  meters and fix accuracy, never the position; a new field needs a failure it diagnoses.
  `docs/PRIVACY.md` must describe what's **privacy-relevant** (user data, anything Play's
  data-use policies cover, counts of the user's places/snoozes, snooze durations) before it
  ships; app internals need no line (maintainer, 2026-10-13). The app writes no raw
  coordinate, full SSID/BSSID or typed place name; only `logSummary()` renders a snooze. Text
  Snoozemo doesn't author (exception messages, exit-reason descriptions) is accepted, not
  scrubbed (§4.6). Automatic off-device channels stay absolute.

## Error handling

- **Don't silently swallow exceptions.** Every catch **logs** through the usual logger with
  sanitized context ("departure test failed: no fix within 10 s", not where), **cleans up**
  what `try` acquired (callbacks, sensor triggers, location requests, geofences, partial
  writes, UI state — `use`/`finally`), and **handles the case explicitly**. Narrow the type
  or rethrow `CancellationException` first; to ignore a failure on purpose, say why and log
  at debug.
- **On the snooze lifecycle this has teeth**: a swallowed exception before `STATE_TRUE` leaves
  the user wrongly believing they're snoozed; on release, a silent phone with nothing to undo
  it. Every catch there arms-and-says-what-degraded or ends-and-says-why. The cap
  (`AlarmManager.setAndAllowWhileIdle`) is armed before anything that can throw.

## Testing expectations

- Logic lives in the pure domain layer: `SnoozeController` is plain Kotlin over a clock and two
  injected interfaces (§11), JVM-tested; logic in a `Service`, `TileService` or composable is
  going the wrong way.
- **The departure test gets trace-driven tests** — §6.6's accuracy gate, hysteresis and
  two-fix confirmation each against a recorded fix trace (GPS jump, 500 m cell fix, end of the
  garden); add the trace when a field bug is found.
- UI changes get Robolectric + Roborazzi tests; each `*ScreenshotTest` class needs its own step
  in CI's `--tests` allow-list or it never records. Wrap in `SnoozemoTheme { Surface { … } }`
  as `MainActivity` does. CI commits screenshot drift (`ci: refresh recorded screenshots`) and
  posts a diff comment: `git pull` before pushing again; don't hand-commit PNGs. Compose test
  imports fail compilation either way round (`dev-docs/agent-notes.md`).
- Run `./gradlew test` and `./gradlew lint` before pushing when you can; else say what was
  verified by inspection. Verify the sandbox before assuming it either way
  (`dev-docs/agent-notes.md`).
- **Fix preexisting failures as the first commit** (confirm before skipping as unrelated). **No
  sleeps, retries or bumped timeouts** — inject the clock and dispatcher. **Never disable a
  failing check.**

## Git workflow

- Assumes `origin` (missing where remote Git works: say so and stop); without remote Git,
  commit locally and say nothing was pushed.
- Branches: `<agent>/<short-topic>` (`claude/…` Claude Code, `gemini/…` Gemini /
  Antigravity, `codex/…` Codex); one topic each, never `main`.
- **Your `<agent>/` branches are yours** — create, reset, force-push, delete the ones this
  session created or was assigned (this file is the standing grant); don't fold unrelated
  work into a pinned branch. Check before touching others'; `main` is never rewritten.
- **Sync before you start**: `git fetch origin +refs/heads/main:refs/remotes/origin/main`
  (`git fetch origin main` leaves single-branch `origin/main` stale), rebase before the first
  commit, resolving conflicts, never pushing on a stale base; if unable to fetch, say so. One
  `git worktree` per branch.
- **One commit per logical change**, each green (rebase-merge lands each one): fold fixups and
  review fixes in, no `fix CI`/`wip` churn. `--force-with-lease`, never bare `--force`.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene first:** fetch
  main by refspec, cut a fresh `<agent>/<short-topic>` off `origin/main`, announce the switch;
  offline, ask for a synced checkout. Reset a merged name only when the sandbox pins it (why:
  `dev-docs/agent-notes.md`).
- **Committer line**: the agent authors; merges rewrite the committer; never re-author, amend
  or mention it. Not a finding.
- **Unshallow before history-depth queries** (`git rev-list --count`, deep `git log`,
  blame, versionCode; the session-start hook does it only in Claude Code): if
  `git rev-parse --is-shallow-repository` is `true`, run `scripts/unshallow.sh` (or
  `git fetch --unshallow origin +refs/heads/main:refs/remotes/origin/main`) and re-check;
  if still `true`, say history is truncated.

## Commit messages

- **Title like a changelog line** (maintainer, 2026-10-23): an imperative verb, then the
  feature in the user's words, sentence case, ≤ ~70 chars (`Fix …`, `Improve …`, `Add …`),
  an optional parenthetical for how (scenario, mechanism, symbols: the body). Each subject
  ships in the GitHub prerelease and Play "What's new" (`docs/play-store-internal-track.md`):
  title **every** commit and PR for users.
- **Prefix a subject with no user-visible effect**: `ci:`, `docs:` (incl. `docs/PRIVACY.md`),
  `internal:`, `refactor:`, `test:`/`tests:`. A shipped dependency is bare (a test/build-only
  bump you write keeps its prefix).
- Commits touching only `.md` or root dot-paths never ship a bullet; prefix them anyway.
  Subjects ship oldest-first as `• ` bullets (repeats once). **Play caps "What's new" at 500
  characters** (and 50 distinct subjects), so squash supporting commits. The range base is
  the last run that published — don't hand-re-push notes.
- **No model identifier** — never name the coding agent's model or version (`Opus 4.8`,
  `Sonnet`, `Gemini …`, `claude-*`, `gemini-*`) in a commit, PR, comment, or pushed
  artifact; keep it to chat. Generic attribution (`Co-Authored-By: Claude` / `Gemini`,
  `Claude-Session:`, "Generated with Claude Code") is fine.

## Autonomy

- **Open the PR without being asked** (this file is the standing request), unless told "just
  commit / no PR yet".
- **Watch your own PRs by subscription + one scheduled check**: the subscription misses CI
  success, merges and Codex's `+1`, so keep exactly one relative-delay check naming the PR
  armed while it's open (`send_later` in Claude Code; `schedule` in Antigravity), settling a
  fired one first. Under drive, auto-merge only where the ruleset requires the Codex verdict
  and resolved conversations. Detail in `dev-docs/agent-notes.md`.
- **Read `mergeable_state` at every check, wake and drive step**: if `dirty` or required
  `behind`, fetch both refs by refspec (unshallowing), confirm your branch has every remote
  head commit, rebase onto `origin/<base>`, confirm the head hasn't moved since the tip you
  noted before fetching, and `--force-with-lease` push unasked; if either check fails or you
  can't tell, stop and ask.
- **On merge or close**, one last reply-or-resolve pass, then cancel the check (match session
  and PR in `list_triggers`) and unsubscribe, if subscribed.
- **Wake PRs a merge leaves behind**: after your PR merges, message every other open PR on
  that base whose body ends with a reachable session URL that its base moved (protocol and
  receiver checks: `dev-docs/agent-notes.md`).
- **A scheduler or GitHub prompt is said once, then carry on.**
- **"Drive"**: loop through actionable `TODO.md` items (not deferred or awaiting a product
  decision) — implement, PR, address review, then rebase-merge with no further go-ahead once
  CI is green, Codex passed the head and every suggestion thread is fixed or answered (*drive
  to merge*) — until done or stopped. **"Autopilot"**: drive without blocking (outranking
  stop-and-wait), logging each cheapest-to-undo guess under `TODO.md` *Decisions needing
  review* (what, alternative, why reversible); loop steps never stall it, but destructive or
  irreversible acts outside the loop, anything beyond this repo, and privacy doubt (a
  coordinate, SSID, place name, device ID) wait for a real answer.
- **Play policy is never autopilot's guess**: §3 rests on Google's policy, which moved in April
  2026 (geofencing dropped as a foreground-service use). Permission, foreground-service-type or
  Data Safety changes go to the user with the policy text, in any mode.
- **Ordered work ("next", "then", "after that", "follow-up")** may open in parallel but merges
  after every earlier session item and named PR (any repo, no time cap; hold and ask if one
  stalls, autopilot included; no auto-merge until its turn; check the session record).
- **A red baseline is the next task** — green `./gradlew test`/`lint` before pulling from
  `TODO.md`, fixed as its own first commit (skipping as unrelated needs the user's answer,
  never autopilot's guess).

## Working with PRs

- Prefer `mcp__github__*` where mounted (Claude Code needs them), else `gh`, else say so.
- **Codex is the automated reviewer** (not Copilot). Address every comment unasked — fix and
  fold into its commit, or decline with reasons — then push, reply citing the sha, and
  resolve (`resolve_review_thread` or `gh api graphql` `resolveReviewThread` takes `PRRT_*`,
  not `PRRC_*`). Never silently dismiss; skip your own echoes (did *I* just post this body?).
- **Deferring resolves the thread too** (maintainer, 2026-08-25): a `TODO.md` entry plus a
  thread reply naming it, then resolve (the ruleset requires resolved conversations).
- **A comment citing a rule is a *reading* of it** — check what this file says; over-strict
  privacy readings quietly cost diagnostics (why a snooze misfired in a pocket). Right → fix.
  Stricter than written → quote the rule, decline, `@codex review`; escalate if it re-raises.
  Genuine conflict → flag it in chat and `TODO.md` with each choice's cost; never resolve it
  yourself, autopilot included.
- **A second verified finding in the same mechanism is a design question**: ask whether another
  design deletes the class, say what you chose on the thread; a design change is the
  maintainer's call, autopilot included.
- **Read the Codex verdict on the PR body** (`eyes` reading, `+1` clean, revoked on push;
  `+1` + green CI = merge; leave body reactions to Codex), plus reviews, review comments and
  comments to the last page and the `codex` status (`updated_at` moving with no reaction
  usually means unread findings). Findings block the merge until fixed, rebutted or
  deferred; an acknowledgment isn't an answer. Nothing back five minutes after a push or
  poke, or stuck at running (👀) ten minutes on: `@codex review`; a poke that hangs the
  same way gets a second an hour later, and only a second that hangs too is escalated,
  never the first (maintainer, 2026-10-22).
- **Once Codex +1s a head, make no unnecessary change without asking** (maintainer,
  2026-10-13); required rebases, red-CI fixes and drive/autopilot steps proceed.
- **Report each review in your next message**: commit read (say if no longer the head),
  finding count, one bullet per finding (most blocking first: claim, location, action);
  this outranks short replies; ask only the top question.
- **Update title/body before each push** (body first) to the full diff; with no PR yet, push,
  then update as soon as one is opened. **Link every open PR in the stack, one URL per
  line** (the "View PR" chip shows only the first).
- **After every merge to `main`, report versionCode** (`git rev-list --count origin/main`
  after a refspec fetch): `Need versionCode 72 (b81c23d) or higher to test PR #52's fix`.
- **"Release notes since N"**: up to five `• ` bullets worded as titles, one per feature,
  ranked by what the user risks without it (broken or wrong first, polish last), for commits
  N+1..`origin/main` (recipe in `dev-docs/agent-notes.md`).
- **On CI failure**, follow `dev-docs/agent-notes.md` (failing-tests comment first; CI builds
  the PR merged with `main`).

## Talking to the user

- **One question at a time**, in plain chat or the client's question tool
  (`AskUserQuestion` in Claude Code, `ask_question` in Antigravity); then stop and wait (no
  assumed answer, self-picked "recommended" option, or work on what it affects) and
  acknowledge the answer before acting. Never ask while the user is typing.
- **Answer a mid-turn message first**, before any further tool call (a merge cue's hygiene
  runs first; its announced switch is the answer). **Claude Code only** (maintainer,
  2026-10-25): its app reliably shows just a turn's last message, so reply only there,
  answering a mid-turn message at a stopping point soon.
- **Message only when the user needs to see it** (maintainer, 2026-10-15): each notifies
  their phone. Speak up only for a question or blocker, something to merge or act on, a
  merge, an autopilot decision (to review, never waited on), or a change from the plan,
  `SPEC.md` or their instructions (review-prompted included); never for progress (a
  client's nudge included), a review or push alone (it rides the next message), routine
  machinery, or a mistake fixed before reaching the repo. The session-start `AGENTS.md`
  line still opens every session.
- **Keep replies short**, leading with the key point.
- **End the turn by restating any pending decision** (a question, or an autopilot guess
  awaiting review) as its last line, written out, every turn until answered. Nothing
  pending, no line; a quiet scheduled check produces no reply.

## Language and spelling

US English everywhere people read it (strings, commits, PRs, comments, KDoc, identifiers,
docs): **`behavior`**, **`gray`**/`grayed`, **`canceled`**/`canceling`, **`-ize`**, `color`,
`dialog`, `license`, `center`, `labeled`, `traveling`, `meter`, `neighbor`, `defense`.
Framework spellings stay (`CancellationException`, `AutomaticZenRule`).

## Translations

**The maintainer approves English copy before translation**: propose in chat and wait, as
unsettled copy (autopilot's, or awaiting a device look) does. Where the translation lands
(same PR or a follow-up), or English changing with its locales, is no violation. A new base
string carries `tools:ignore="MissingTranslation"` and `<!-- TODO: translate -->`, removed
on fan-out; a reworded key takes only the comment. Escape `\'` in every locale.

## Remote build environments

SDK provisioning (`CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` where
`/opt/android-sdk` is empty; never hand-install) and the pinned, never-composite
`androidlog`/`androidzoom`: `dev-docs/agent-notes.md`. No KVM: DND, geofence and Sleeping
Apps need a real Pixel and Samsung.

## Cost and reliability

- Call out cost and reliability up front for new infrastructure or external calls (dollar
  figure, failure modes, rate limits, latency, what the user sees when down; say so if nil).
- **`INTERNET` ships** for Crashlytics and Analytics (§12, `docs/crashlytics.md`) behind one
  consent; a second thing sending off the device is the user's distribution decision, named
  with its Data Safety consequence.
- **Battery is the user's running cost**: state a new wakeup, location request, sensor or
  `WorkManager` period against §9's budget, incl. the stationary case §6.7 drives to zero.

## CI timing

Report a significant CI slowdown (>25% or >30 s on a job under ~5 min) with its likely cause,
comparing the same job's total (not a step) on the same kind of ref (PR vs PR, `main` vs
`main`).
