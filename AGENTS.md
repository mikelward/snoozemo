# Snoozemo

Android app that puts the phone into Do Not Disturb **until you leave where you are right
now** — one tap on a Quick Settings tile arms it, walking away disarms it (Kotlin, Compose,
Gradle multi-module). Product and architecture decisions live in `SPEC.md`; the phased plan
lives in `TODO.md`. This repo mirrors the engineering conventions of the sibling Type
Launcher (`mikelward/typelauncher`) and Simmo (`mikelward/simmo`) repos; when a convention
is underspecified here, Simmo's `AGENTS.md` is the tiebreaker.

**Status: both halves are built; presence has never run on a handset.** The Gradle build,
the module split, the two flavors and CI are green, and so is the DND half: the tile arms
and ends a snooze, the record survives process death and reboots, and the duration cap holds
across a clock change. On `play`, presence ends a snooze when you leave — three wake-up
sources feeding one departure test — and degrades to duration-only, saying so, when the
anchor supports nothing better; `direct` is duration-only until Phase 7. Phase 3 still owes real
code — the §6.7 motion trigger, the degradation *cause* reaching the user — alongside the
on-device verification (`TODO.md` Phase 3).
Every rule below is live, screenshot tests included: the four screens (`MainScreen`,
`PermissionsScreen`, `SettingsScreen`, `LicensesScreen`) each record through their own
`*ScreenshotTest`, and a new one needs its own step in the CI allow-list or it records
nothing.

## Project documentation

- **Keep this file short — one-liners over essays.** Every rule here loads into every
  agent's context at the start of every session, so a paragraph where a sentence would do
  taxes every future task. State the rule and the failure it prevents; the incident
  narrative belongs in the commit message.
- Keep `SPEC.md` up to date when changing product behavior, architecture, persistence,
  permissions, navigation, or testing strategy.
- **`SPEC.md` records product, functionality, and architecture decisions — not low-level
  implementation detail.** It captures *what* Snoozemo does and *why* a design was chosen
  (the zen-rule mechanism, the Wi-Fi-as-suppressor asymmetry, the three independent exits,
  the two distribution flavors), so a reader can understand and QA the product from the
  spec. Ask "would this still be true and worth stating if the implementation were
  rewritten?" — if not, leave it in the code and its comments. Exact dp values, private
  helper names, and which composable holds a flag are code, not spec.
- Keep `TODO.md` current: check items off as they land, add newly discovered work to the
  right phase.
- **A decision that changed belongs in `SPEC.md` with its reason, not silently swapped.**
  The spec is written as a chain of decisions and rationale (D1–D9, §3's distribution
  argument); a reversal that replaces the conclusion without touching the reasoning leaves
  the next reader — human or agent — re-deriving an argument that has already been had.

## Engineering quality bar

These are the principles, in priority order. Where a specific rule below seems to conflict
with one of them, the principle wins and the rule is what needs fixing.

1. **Never leave the phone silently quiet.** A phone that stays silent through something
   that mattered is the failure that gets this app deleted, and it is strictly worse than a
   phone that rings when it could have stayed quiet. Every ambiguous state resolves toward
   **ending the snooze** (`SPEC.md` D7, "fail open"), and the duration cap is the backstop
   that holds even when every sensor has failed. A snooze that ends is a small annoyance; a
   snooze that never ends is the whole product failing.
2. **Never fail silently.** The second-worst outcome is doing the wrong thing quietly —
   staying armed when presence tracking has actually died, degrading to duration-only
   without saying so, or ending a snooze for a reason the user cannot reconstruct. If
   Snoozemo can't do the right thing, it does the safe thing **and says so**: the ongoing
   notification is where the user is already looking, so a degraded mode says so there
   (`Snoozing at Home · location paused, ends in 3h 40m`), and every state transition
   leaves a reason behind it.
3. **Never lose the user's settings.** Saved places, per-place policies and caps, the zen
   rule's policy, the tile's presence in the shade — the user configured that, and none of
   it is reproducible from anywhere else. Where a real constraint seems to force a loss,
   the loss is a **last resort after the alternatives are exhausted**, and any genuine
   trade-off is **the user's to make, stated plainly**, not taken quietly on their behalf.
   `allowBackup="false"` is a choice about the *cloud* and does not decide what a phone
   swap does — migration is **undecided, not off** (`SPEC.md` §12, `TODO.md`), so don't
   write code or copy treating never-backing-up as permanent, or as current.
4. **Do the work ahead of time.** Anything the arm path needs should already be in memory
   before the tile is tapped: the zen rule id, the settings, the last known SSID. Arming
   must never feel slow or refuse (`SPEC.md` §4.1) — preparation is what buys that.
5. **Don't make the user wait.** Nothing that blocks a thread belongs on the tile-tap path,
   in the trampoline activity's `onCreate`, or in front of a screen's first frame. The
   trampoline starts the service *before* any UI, so arming never waits on rendering
   (`SPEC.md` §6.9).
6. **Say why.** Every non-obvious action gets its reason recorded where the next reader
   will need it — a comment for a subtle mechanism, the PR for a design trade-off, the
   user-visible notification for anything the user would otherwise have to guess at. "Why"
   is the expensive thing to reconstruct later; the "what" is in the diff.

Concretely, every change must hold the line on **correctness, the arm path, and jank-free
UI**:

- **Correctness**: the change matches `SPEC.md` and the user's stated intent, handles the
  obvious edge cases (no Wi-Fi, no location fix, permission revoked mid-snooze, reboot,
  process death, location services off system-wide, battery saver, configuration change),
  and preserves existing invariants. Three stay hard: **the duration cap always fires**,
  **`endSnooze` is idempotent** (`SPEC.md` §7), and **Snoozemo turns off only its own zen
  rule** — never another app's or a user schedule's (`SPEC.md` §5.6). New behavior is
  covered by a unit test; when fixing a bug, add a test that fails before the fix and
  passes after.
- **The arm path.** One tap from the shade, phone possibly locked, in under a second, with
  zero prior configuration — that is goal 1 and it is the whole product's first impression.
  In practice: the trampoline activity starts the service within one frame of `onCreate`
  and before any UI; `ARMING` never blocks on a location fix (10 s ceiling, then degrade to
  Wi-Fi-only or duration-only and say so); no synchronous disk, `PackageManager`,
  telephony, or `NotificationManager` policy IPC between the tap and the zen rule going
  `STATE_TRUE`. Any change touching the tile, trampoline, or `SnoozeController` arm path
  must state in the PR what that path now reads and what it waits for.
- **Jank-free UI**: the end-condition sheet appears over whatever the user was doing, right
  after a tap they expect to be instant, so the first frame must be the real content — no
  flash of a blank window from the transparent trampoline (`SPEC.md` §6.9), no spinner
  where a rendered sheet was possible. Usual Compose discipline applies:
  `remember`/`derivedStateOf`/stable types, no I/O in composition, and warm at startup
  rather than reading in `onCreate`.

When you cannot verify one of these locally (no emulator, no DND-capable device in the
sandbox), say so explicitly in the chat update — "verified by unit test; the zen rule
actually silencing the device needs a device check" — rather than implying all were
checked. Real-device DND behavior, geofence latency, and One UI's Sleeping Apps are the
pillars most often still owed — `TODO.md`'s hardware-verification list tracks them.

## Spacing

Stick to a 4dp grid for every padding/margin/spacing value (`4`, `8`, `12`, `16`, `24`,
...); reuse values already used by sibling composables; symmetry by default, and any
asymmetry gets a one-sentence justification in the PR. Flag off-grid or inconsistent
spacing you notice even outside the diff (file, line, proposed fix) without silently fixing
it in the same commit.

## Git workflow

- **These rules assume an `origin` remote.** If the environment supports remote Git, the
  absence of `origin` is a configuration error: say so and stop rather than improvising a
  local substitute. Sandboxes without remote Git support (such as Codex cloud) may continue
  on the provided local branch without fetching or rebasing from `origin/main`; commit the
  work locally, clearly report that it was not pushed and that no PR was opened, and leave
  remote Git operations for a capable environment.
- **Branch naming.** Feature branches are prefixed with the agent's own short name:
  `<agent>/<short-topic>` (`claude/...` for Claude Code, `codex/...` for Codex, and so on).
  One topic per branch; never commit to `main`. The placeholder `<agent>` stands in for
  whichever prefix you use — don't hard-code `claude/` unless you *are* Claude Code.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene *before*
  engaging with the rest of the message:** `git fetch origin main`, cut a fresh
  `<agent>/<short-topic>` branch off `origin/main`, announce the switch. Where the sandbox
  has no remote, the cue can't be honored as written — a fresh branch needs a base that
  contains the merge, and an offline checkout can't fetch one; say so and ask for a synced
  checkout rather than branching off a stale `main`.
- **After a merge, take a fresh `<agent>/<short-topic>`** — don't reset the merged name
  onto the new base. Its remote ref still points at the pre-merge tip, so
  `origin/<branch>..HEAD` keeps spanning the merged commits and unpushed-work checks
  report your own merged history back at you. When a sandbox pins the branch name so a
  fresh one isn't available, reset it. No short check reliably separates "already merged"
  from "not yet merged" here: a rebase merge rewrites the commits, a squash merge
  collapses them, `main` moves on underneath so a tip-to-tip diff reports upstream drift
  as branch work, the remote ref can hold a commit the local one doesn't, and no tree
  comparison sees the uncommitted work a `--hard` reset would erase. Don't reach for
  `--force-with-lease` as the safety net either — fetching updates the remote-tracking ref
  the lease compares against, so a commit you have already fetched passes the lease
  unnoticed.
- **The agent authors; whoever merges takes over the committer line.** A squash or rebase
  merge rewrites the committer to the person who pressed the button — the repo owner
  normally, the agent itself when it merges under *drive*. That's expected either way —
  never re-author or amend already-merged commits to "fix" authorship or signing.
- **Never raise the authorship or committer question.** The rewrite above is expected and
  needs no comment: no PR note, no chat caveat, no explanation of the mechanism, no offer
  to "fix" it. Raising it every session is the noise this bullet exists to stop.
- **Branches under your own agent prefix are yours.** Create, reset, force-push, and
  delete any branch carrying *your* prefix freely — no permission, no announcement,
  including a name whose work has already merged and commits a reviewer has already
  commented on. Another tool's prefix (`codex/…` when you are Claude) and anyone else's
  branch are not yours: check before touching them. `main` is never force-pushed or
  rewritten.
- In environments with remote Git support, always start work from the latest `origin/main`:
  `git fetch origin main` and rebase the working branch onto it before the first commit,
  even when the branch already exists. Resolve conflicts rather than abandoning the rebase,
  and never push commits on an out-of-date base when a fast-forward rebase onto
  `origin/main` was possible.
- **Use `git worktree` when it's available.** Give each branch its own worktree instead of
  switching branches in place, so work in progress on one branch isn't disturbed by work on
  another.
- **Structure the branch as a sequence of logical commits, rebasing and squashing as
  needed.** Each commit is one coherent change (a feature step, a fix, a refactor) that
  stands on its own — buildable and green by itself. The repo rebase-merges, so every
  commit lands on `main` individually with its own subject, blame lines, and bisect step.
  Squash fixups into the commit they amend, split unrelated changes into separate commits,
  and reorder so the history reads as steps toward the change, not the order the work
  happened to occur in.
- Clean up the unmerged commit history before requesting review and again before merge
  (`git rebase -i origin/main`): iteration leaves `fix CI` / `address review` / `wip`
  churn, and nothing squashes it for you — a messy branch ships a messy `main`. After
  rewriting, force-push with `git push --force-with-lease` (never bare `--force`).
- **Unshallow before answering anything that depends on git history depth.** The sandbox
  clones shallow, so `git rev-list --count`, `git log` past the shallow boundary, blame,
  and any "how many commits / what versionCode is this" question return wrong answers
  without warning. If `git rev-parse --is-shallow-repository` says `true`, run
  `git fetch --unshallow origin main` first — do it once at the start of any session that
  will report a versionCode — then re-check. It exits 0 even when it deepened nothing, so
  if `--is-shallow-repository` is still `true`, say the history is truncated instead of
  quoting a versionCode.

## Commit messages

- Write every subject for end users, sentence case, plain English, no internal symbol
  names, ≤ ~70 characters; engineering detail goes in the body. This repo follows the
  sibling repos' release pipeline: every release-worthy commit subject in a push to `main`
  ships as a bullet in the Play "What's new" list (once the `deploy` job
  lands — `TODO.md` Phase 8).
- Because the repo rebase-merges, the PR title never lands on `main` — each commit's own
  subject does. Title **every** commit on the branch by these rules, not just the PR.
- Keep non-user-facing commits out of release notes with a subject prefix, used precisely
  (the prefix is a promise the commit has **no user-visible effect**):
  - `ci:` — CI / workflow plumbing.
  - `docs:` — documentation only. `docs/PRIVACY.md` is ordinary docs like the rest of
    `docs/` — Play's "What's new" is a store-listing field a user installing an update
    never sees at update time, so a policy-wording change gains nothing from forcing its
    way into release notes (`TODO.md`).
  - `internal:` — build config, dependency upgrades, other plumbing.
  - `refactor:` — behavior-preserving code changes.
  - `test:` / `tests:` — test-only changes.
- **Housekeeping paths are dropped whatever the subject says.** A commit whose every
  changed path is a `.md` file (at any depth) or a root dotfile / dotdir (`.github/`,
  `.claude/`, `.gitignore`, …) never reaches the notes, prefixed or not. Prefix those
  commits anyway, so the intent is explicit and the subject never reads like a shippable
  bullet.
- **Surviving subjects ship as a `• `-bulleted list, oldest-first** — always bulleted, even
  when only one commit qualifies, so the Play card always renders the same shape.
  Bodies are always dropped: nothing below the subject line reaches a user.
- **Play caps "What's new" at 500 characters per language.** CI measures the full formatted
  output — the `• ` bullets, the newline separators, and the `…` truncation marker all
  count — then drops whole trailing subjects (oldest-first preserved at the head) until it
  fits and appends `…`; a 50-subject cap backstops unusually long ranges. So don't line up
  a long stack of small commits when one of them tells the user-facing story on its own —
  squash the supporting work into it.
- **The range base is the last `main` run that actually published**, not the previous push.
  If a `main` run goes red before distributing, or skips publishing because its secrets are
  missing, those commits stay queued and ship with the next real release instead of being
  lost. Nothing to do differently — just don't hand-re-push notes after a failed release.

## Autonomy

- **Open the PR without being asked.** Pushing a finished branch and opening its pull
  request are one step, not two — don't park a branch waiting for "please open a PR." The
  exception is an explicit instruction not to ("just commit", "no PR yet"), which holds
  until the user lifts it. This file is the repo owner's standing request for that PR, so a
  client-level rule reading "open a PR only when the user explicitly asks" is already
  satisfied — the ask is here, and it doesn't need repeating per branch.
- **Watch your own PRs by subscription, plus one scheduled check.** Have a
  subscription — Claude Code makes one when you open a PR; where a client
  doesn't, call `subscribe_pr_activity`. It delivers reviews, comments and CI
  failures. It cannot deliver CI *success*, a push, the merge, Codex's clean
  verdict (a reaction), or Codex never answering at all — so keep exactly one
  check armed for as long as the PR is open (each event and each check costs
  a model turn). Under drive, arm auto-merge at PR open too — but only where
  the ruleset makes the Codex verdict a required check AND requires
  conversations resolved: where CI is the only requirement it merges before
  Codex has answered, and an open review comment holds nothing back on its own.
  - Settle the fired trigger first thing in the turn, not last. It may have
    silently re-armed rather than retired — update the one that survived,
    replace the one that didn't, and end the turn with exactly one pending.
  - Check the fire time you got against the one you asked for — a 4-minute
    request has come back as 64. Prefer a relative delay: the scheduler's
    clock is not this container's, so an absolute time computed here can be
    rejected as already past. Re-time it, or say the watch isn't armed.
  - A few minutes out while CI or the current head's Codex verdict is
    outstanding; longer once only a human is left; short again after a push.
  - A PR reading `dirty` — always — or `behind` where the ruleset requires
    branches up to date, needs a rebase onto its base and a lease-guarded
    force-push. Nothing reports a base advance, so only this check catches
    it. Fetch both refs by explicit refspec, unshallow a shallow clone, and
    rebase onto the fetched `origin/<base>` — not always `main`, never the
    local branch a fetch leaves behind. Confirm before you rebase that your
    branch has every commit the remote head has, and before you push that
    the head has not moved since the tip you noted before fetching: the push
    flags do not reliably refuse a rewind, a commit you never fetched, or
    one you fetched and did not rebase onto, and overwriting any of them
    loses someone's work. If either fails, or you can't tell, stop and ask.
  - Name the PR, and say what to re-read rather than what you read. A SHA or
    a list of which PRs are open goes stale before it fires; one PR number
    does not, and the trigger has to be matchable to it.
  - Merged or closed, take one last reply-or-resolve pass — a review can
    land after the merge — then cancel it and unsubscribe. `list_triggers`
    spans the account, so match this session and this PR before updating
    or deleting one; an update reschedules whatever it matches as surely
    as a delete cancels it.
- **If a scheduler or GitHub call prompts, say so once and carry on.** Permissions load at
  session start, so writing a settings file mid-session can't fix the session you're in.
- **"Drive" means run the loop automatically**: pick the next task, implement it, open the
  PR, wait for the automatic Codex review, address every comment, merge once CI is green and
  Codex's verdict for the current head is in — then pick the next actionable `TODO.md` item
  and go around again. Actionable means ready to build: skip anything explicitly deferred or
  waiting on a product decision rather than guessing the decision. Driving ends when the
  work runs out or the user says stop, not when one PR merges.
- **A red baseline is the next task.** Before pulling anything from `TODO.md`, run
  `./gradlew test` and `./gradlew lint` and get them green. A preexisting failure is work
  to do, not a thing to classify as "unrelated" and step around — deciding it's out of
  scope is exactly the call that goes wrong, and the cost is every later PR merged onto an
  unverified tree. Fix it first (as its own first commit, per *Testing expectations*), then
  pick the task. That section's "genuinely unrelated, out of scope" escape hatch is the
  only way past a red tree, and it needs a real answer from the user — not a call you make
  on your own, and not one autopilot guesses.
- **"Autopilot" is drive without blocking on the user.** Wherever drive would stop and
  ask, autopilot takes its best guess and keeps going, preferring the option that is
  cheapest to undo or change later. Record each guess in `TODO.md` under a `Decisions
  needing review` heading — what was decided, what the alternative was, and why it's
  reversible — creating the heading if it isn't there, so nothing guessed silently becomes
  permanent. While autopilot is in effect it outranks *Asking questions*' "stop and wait
  for the answer"; that rule governs everywhere else. The carve-out is for destructive or
  irreversible actions *outside* the loop — rewriting shared history, deleting work,
  anything reaching a system beyond this repo — which still wait for a real answer. The
  loop's own steps don't count: committing, pushing, opening a PR, reading its CI and review state, arming the next scheduled check, and merging a green PR
  are authorized here, so autopilot must not stall on them — the carve-out is aimed at
  destructive writes to systems outside the repo, not at the loop's own GitHub reads and
  follow-ups. Privacy uncertainty is never inside the loop either: if you can't tell
  whether something is user data — a coordinate, an SSID, a place name, a device
  identifier — it waits for a real answer, since a push can't be un-published and a
  `TODO.md` note doesn't retract it.
- **Play policy questions are never autopilot's to guess.** `SPEC.md` §3 turns on what
  Google's policy currently says, and it has already moved once (the April 2026 removal of
  geofencing as an approved foreground-service use case). A change to the flavor split, the
  declared permissions, the foreground-service type, or the Data Safety answers is a
  distribution decision with a real chance of sinking the project — bring it to the user
  with the policy text you are reading it from, whatever mode is in effect.

## Working with PRs

- Prefer the `mcp__github__*` MCP tools for GitHub operations; the `gh` CLI is not
  installed in the sandbox. If your client exposes neither, say so rather than guessing at
  the outcome of an operation you couldn't perform.
- **"Drive to merge"** is the PR stretch of *drive* (see **Autonomy** above): open the PR,
  wait for the automatic Codex review, address every review comment — fix it if you agree,
  reply on the thread saying why if you don't — and merge once CI is green and Codex's
  verdict for the current head is in.
- **Merge when green and Codex has passed the current head.** Once a PR's CI is green and
  Codex has finished its pass with no unaddressed suggestions (its "no suggestions" outcome
  is a 👍 reaction, and `get_reviews` names the commit it read; suggestion threads count as
  addressed once fixed or answered), rebase-merge the PR without waiting for a further
  go-ahead, then pick up the next `TODO.md` item.
- **Codex is the automated reviewer on this repo** — not Copilot. Its reviews are triggered
  automatically; you don't request them, except when nothing has come back five minutes
  after a push — that means it never picked the push up.
- **Address Codex comments automatically — don't wait to be asked.** When a Codex review
  lands, treat each comment like a real review note: read it, decide whether it's a real
  issue or a false positive, and if it's real, fix it in the same PR. Fold the fix into the
  commit it belongs to (rebase / `--fixup`) rather than tacking on an "address review"
  commit, per the logical-commits rule under *Git workflow*. Group several small fixes into
  one commit when they share a topic.
- **`resolve_review_thread` works — pass the thread ID, not a comment ID.**
  `mcp__github__pull_request_read` / `get_review_comments` returns each thread's node ID
  (`PRRT_*`) on `review_threads[].id`; pass that straight to
  `mcp__github__resolve_review_thread` as `threadId`. A comment's node ID (`PRRC_*`) fails
  with `Could not resolve to PullRequestReviewThread node` — they're different objects. So
  reply *then* resolve, with no "replied-but-unresolved" caveat to report.
- **Report when Codex finishes reviewing a fresh push** — a one-liner naming the SHA and
  comment count, e.g. `Codex reviewed 87d9f02 — 0 comments` or `Codex reviewed 87d9f02 — 3
  comments, addressing now`. Tie it to the *latest* pushed SHA so a stale review of a
  superseded commit isn't conflated with the current state.
- **Read the Codex verdict, don't infer it.** It reacts to the PR body (`issue_read` →
  `reactions`), not to a review thread, whose `Useful?` bar reads true on any PR it has
  commented on. `eyes` means reading, `+1` means clean, and Codex revokes it on push — so a
  visible one belongs to the visible head, and `+1` with green CI is a merge. The count
  names no author, so leave PR-body reactions to Codex: nobody else's is revoked, and a
  review is the attributable form, naming the commit it read. Findings arrive as review
  comments, as a top-level comment, or as a review — read `get_review_comments`,
  `get_comments` and `get_reviews` to the last page, since all three page oldest first — and
  they block the merge until fixed or rebutted; an acknowledgement is not an answer. Nothing
  from Codex since the push, five minutes on, means it never picked it up — comment `@codex
  review`, once. Reading the verdict is a protocol, not a glance: a state report draws on
  ALL the sources — the PR-body reactions, the reviews, the review comments and issue
  comments to their last pages, and the `codex` commit status where the ruleset requires it
  (a separate API surface from check runs) — because the reaction is only the clean channel,
  and `updated_at` moving without a reaction usually means unread findings.
- **Judge every review comment on merit, whoever wrote it.** Verify the claim before
  acting; if it doesn't hold up, reply saying why and decline.
- **A review comment citing a rule is a *reading* of that rule, not the rule.** Go back to
  what this file actually says before acting on it. Codex misreads the privacy rules in
  particular, and reliably in one direction: they are the rules where a stricter reading
  always feels like the safe answer — and isn't, so an over-strict finding is one to push
  back on rather than comply with. Over-applying them removes capability the user needs,
  quietly, under cover of caution: the diagnostic that would have explained a snooze that
  misfired in someone's pocket, the timestamps that would have shown an alarm firing late
  outside a Doze window. A product that can't be debugged fails its users too. Three
  outcomes, and only three:
  - **The comment is right** — the code or spec really does break a rule as written. Fix
    it, in the same PR, and say so on the thread.
  - **The comment reads the rule more strictly than it is written** — it cites a floor that
    doesn't list the thing it objects to, or infers a prohibition from a principle. Reply
    on the thread quoting what the rule actually says, decline, and leave the capability
    alone. Do **not** narrow the product to make a reviewer comfortable.
  - **It's a genuine conflict** — the rule as written really does forbid something the
    product genuinely needs, or two rules point opposite ways. That is the maintainer's
    call and nobody else's: **flag it for review**, in the chat reply and in `TODO.md`,
    with what the rule says, what the product needs, and what each choice costs. Don't
    resolve it yourself in either direction, and don't quietly pick the restrictive option
    because it looks defensible — a capability removed to satisfy a misreading is
    expensive to notice and expensive to get back.

  Autopilot does not change this: guessing is allowed on reversible implementation calls,
  not on what a privacy rule means.
- Never leave a review comment thread silently dismissed: answer on the thread — a
  disagreement is an answer, so say why — then resolve it. When a comment is a false
  positive, say why on the thread.
- **Deferring a finding is an answer too, and it resolves the thread** (maintainer,
  2026-08-25). Record it in `TODO.md`, say on the thread that it is tracked there for a
  later PR, and resolve. The branch ruleset requires every conversation resolved before a
  merge, so a thread left open for deferred work blocks its PR permanently — and the
  `TODO.md` entry, not the thread, is the durable place a finding lives. What is not
  allowed is resolving without both halves: no entry, or no comment naming it, is the
  silent dismissal the rule above forbids.
- **Report the Android `versionCode` after every merge to `main`.** Fetch `main` and run
  `git rev-list --count origin/main` (`app/build.gradle.kts` derives the versionCode from
  this count, once Phase 0 lands it). Report it as e.g. `Need versionCode 72 (b81c23d) or
  higher to test PR #52's fix` — number, short SHA, and a one-clause summary of what the
  change gates. The user needs this to know which Play internal-track / locally-built APK contains
  their fix.
- Link every open PR in the stack (one URL per line — the "View PR" chip sticks to the
  first link and hides the rest, anthropics/claude-code#46625) whenever you push, summarize
  CI, or invite review.
- Refresh the PR title and body with the push, not after it, so they describe the full,
  latest state of the branch — re-read `git diff origin/main...HEAD` and patch whatever
  drifted.
- Skip echo events silently. Replies posted via `mcp__github__*` come back moments later as
  webhook events authored by the same identity; if the body matches a comment you just
  posted, it's your own echo — continue without comment. Anything you didn't just author
  still gets the usual reply-or-resolve handling.
- On CI failure: check for the failing-tests PR comment first; no comment means the failure
  is earlier than tests (compile, lint, resource merge) — the `*-test-reports` artifacts
  also only contain JUnit XMLs when tests actually ran, so an empty one is the same signal.
  The PR `build` job builds `refs/pull/<N>/merge` — your branch *merged with main* — so
  reproduce with `git merge origin/main --no-commit` before bisecting your own commits.
  Check whether the failure is pre-existing on the base commit before debugging.

## Talking to the user

- **One question at a time.** Never stack multiple questions in a single turn — ask the
  most important one, wait for the answer, then ask the next if you still need it. A wall
  of bundled questions is harder to answer than a short back-and-forth.
- **Don't interrupt.** Never fire off a question while the user is still typing. Let them
  finish; a half-typed message isn't an invitation to jump in.
- **Respond to a mid-turn message immediately.** When the user sends a message while you're
  still working — surfaced as a "sent while you were working" interjection — address it in
  your very next output, before starting or continuing any further tool call, even if it's
  only one sentence. Don't let it queue up behind an in-flight chain of tool calls.
- **Keep replies short — don't dump a full page.** Lead with the single most important
  point and stop. If there's more, say the first point and ask whether they're ready for
  the next one rather than emptying everything at once.
- **Don't report your own caught mistakes.** A wrong turn you noticed and fixed before it
  reached the user is not news — no "one thing worth flagging", no post-mortem of your own
  reasoning, no inside baseball about the loop. Say what the work is and what's
  outstanding. A mistake that reached their repo, cost them something, or changes what
  they should do is different: say that plainly.
- **End the turn by restating any pending decision.** If you're waiting on an answer — a
  question you asked, or a guess autopilot recorded for review — the last line of the reply
  is that question, written out in about a sentence. A back-reference ("as asked above")
  isn't actionable when the question is pages back or was never actually put into words;
  restate it every turn until it's answered. Nothing pending, no line. This governs replies
  the user reads: a scheduled check that finds nothing new re-arms silently and produces no
  reply at all, so there is nothing to restate.

## Asking questions

- Ask questions as plain chat messages. Claude specifically: never use `AskUserQuestion`,
  Claude Code's multiple-choice question prompt — it's broken in the Claude mobile app, so
  a question asked through it may be unanswerable. Chat keeps the question, its context,
  and the answer in one readable thread.
- After asking, stop and wait for the answer. Don't proceed on an assumed answer, pick a
  "recommended" option yourself, or keep working on the part the question affects.
- Acknowledge every answer explicitly before acting on it, so it's clear the answer was
  received and how it was understood.
- Whenever you change direction — because of an answer, something discovered in the code, a
  failing check, or any other reason — say so immediately in chat: what changed, and why.
  Never let a plan silently drift from what was last stated.

## Error handling

- **Don't silently swallow exceptions.** A bare `catch (_: Throwable) {}` or
  `catch (e: Exception) { /* ignore */ }` hides real failures in the field and burns hours
  when something eventually breaks. Every catch block needs to do three things: **log** the
  exception with enough context for a reader to identify the failed call — the operation,
  the failure mode — but **sanitized context only**, routed through the usual logger rather
  than a bare `Log.e`. Never log coordinates, an SSID/BSSID, or a place name; the *Privacy*
  rule applies to logs too, so redact or summarize ("departure test failed: no fix within
  10 s", not where); **clean up** what the `try` block acquired — network callbacks, sensor
  trigger registrations, location update requests, geofence registrations, partial writes,
  in-progress UI state — so a failure doesn't leak resources or leave state half-mutated
  (`use { … }` / `finally`); and **handle the edge case explicitly** — pick how the caller
  sees this failure (default value, null, sentinel error result, rethrow as a domain
  exception) rather than letting control fall through. Catching `Throwable` (or a blanket
  `Exception`) also swallows `CancellationException`, which breaks structured concurrency —
  narrow the type, or rethrow `CancellationException` first.
- **On the snooze lifecycle this rule has teeth.** A swallowed exception anywhere between
  the tile tap and `setAutomaticZenRuleState(STATE_TRUE)` leaves the user believing they
  are snoozed when they aren't; a swallowed exception on the *release* path leaves the
  phone silent with nothing left to turn it back on, which is principle 1's failure. Every
  catch on either path ends in an explicit outcome — arm and say what degraded, or end the
  snooze and say why — never a silent fall-through. The duration cap
  (`AlarmManager.setAndAllowWhileIdle`) is the last line of defense and must be armed
  before anything that can throw, not after.
- If you genuinely do want to ignore a specific failure, name the reason in a one-line
  comment and still log at debug so it's traceable.

## Privacy

- **Never put user data in any artifact that leaves this machine.** That includes commit
  subjects and bodies, PR titles / descriptions / comments, review replies, issue text,
  branch names, code comments, test fixtures, screenshot snapshots, and anything else that
  ends up on GitHub, the Play Console, or in logs. This app handles location by definition
  — **coordinates, Wi-Fi SSIDs and BSSIDs, place names the user typed, the times and
  durations of their snoozes, and anything derivable about where they live, work, or sleep**.
  None of it goes into a commit, a PR, a bug reproduction, or a test fixture. If a
  user-supplied bug report contains a real SSID or a real address, paraphrase in the commit
  / PR — don't quote verbatim. When in doubt, ask before pushing.
- **The test is whether a value is somebody's, not whether the name is real.** Fixtures and
  docs use stock stand-ins — `Home`, `Work`, `Cinema` as place names, an SSID like
  `ExampleWifi`, coordinates like `(0.0, 0.0)` or a plainly-fictional pair. That is fine
  and it stays. What is banned is a *particular person's* data — the SSID of a real router,
  the coordinates of a real home, a real address — lifted from a device or a bug report.
  Roborazzi snapshots are the easy mistake: they ship to the repo, so record them from
  fixture data, never from a device with a real anchor captured.
- **Anything new that leaves the device (analytics, a map tile, a geocoder) is a product
  decision, not an implementation detail.** It changes the Play Data Safety declaration and
  is worth calling out plainly (see *Cost and reliability*). Crash reporting has already
  been through that gate and landed on `play` (`SPEC.md` §12); the next thing has to go
  through it too, and it still owes the same floor as everything else on this list:
  location data and the user's own configuration stay off any channel that leaves the
  device unless the user has been told plainly and agreed. Nothing sent off the device may
  carry a coordinate, an SSID/BSSID, or a user-typed place name — which is why the crash
  reporter attaches no custom keys and no breadcrumbs at all.
- **The floor below is a list, and the list is exhaustive.** What it names is forbidden
  absolutely; what it does not name is a judgment call, and the answer to a judgment call
  is not "add it to the floor to be safe". Widening the floor by inference is how the app
  ends up unable to explain its own failures — see *Working with PRs* for what to do when a
  reviewer argues for a stricter reading (short version: quote the rule, decline, and bring
  a genuine conflict to the maintainer instead of resolving it by removing the capability).
- **An on-device debug log, if one is added, is the one sanctioned exception, and a narrow
  one.** Diagnosing a snooze that ended early — or never ended — needs a record of what the
  presence engine saw. Such a log may carry **coarse state and reasons**: which state the
  controller was in, which of the three wake-up sources fired, whether the anchor SSID was
  associated, and the *result* of the departure test. The floor is absolute: never raw
  coordinates, never a full SSID/BSSID, never a user-typed place name. Distance from the
  anchor in meters and fix accuracy are the diagnostic value; the position is not. Anything
  above the floor gets added only with a specific failure it makes diagnosable, and
  `docs/PRIVACY.md` must describe what the log carries before it ships.

## Language and spelling

Use US English everywhere people read English: user-facing strings, commit subjects and
bodies, PR titles/descriptions, comments, KDoc, identifiers, docs (`SPEC.md`, `TODO.md`,
`docs/`), and this file. Every one of those counts — a comment or a `TODO.md` entry is read
as much as a string is.

The forms this family of repos keeps getting wrong, so check them by name: **`behavior`**
(not "behaviour"), **`gray`** (not "grey" — including `grayed`, `graying`), **`canceled` /
`canceling`** (one `l`), and **`-ize`** over `-ise` (`customize`, `organize`, `recognize`,
`optimize`, `minimize`). Others: `color`, `dialog`, `license`, `center`, `labeled`,
`traveling`, `meter` (not "metre"), `kilometer`, `neighbor`, `defense`.

Platform/third-party API spellings stay as the framework spells them —
`CancellationException` and `awaitCancellation` keep their double `l` (that word is spelled
the same either way), `android:color`, `Color`, `AutomaticZenRule` and friends are never
rewritten to match this rule. This is about US-vs-UK spelling, not about adding locales.

## Concise copy

Keep user-facing text short. A label, action, or title should carry only the words the user
needs — drop framing verbs and prefixes the surrounding UI already implies. On the tile,
`Snoozing` beats `Snooze is active`; in the sheet, `until I leave` beats `Keep snoozing
until I leave this place`. Prefer the shortest phrasing that stays unambiguous; when a
longer form is genuinely needed for clarity, say why in the PR. This applies to strings,
dialog/button text, notification text, and screen titles. (It's the same instinct as the
≤70-char commit-subject rule — say it in fewer words.)

The tile is the tightest constraint in the app: Quick Settings truncates aggressively and
the subtitle carries a countdown (`SPEC.md` §4.2), so tile copy is written to the shortest
form that still reads, and verified on a device rather than in a preview.

## Translations

**The rule: the maintainer approves English copy before anything is translated.** Propose
new copy in chat and wait for sign-off. Unsettled copy — anything autopilot wrote, anything
the maintainer wants to see on a device first — is not worth translating yet, since
rewording one string throws away every locale's work on it. Leave it with the deferral
markers below and fan it out once it is decided.

**Where the translation lands is not part of the rule**: the same PR as its approved
English, or a follow-up, both correct. An English string changed alongside its locale
entries is not a violation and is not to be flagged as one.

Deferral markers: the new base string carries a per-string
`tools:ignore="MissingTranslation"` and a `<!-- TODO: translate -->` comment, both removed
when the approved copy is fanned out. A *reworded existing* key needs no `tools:ignore` —
that check fires only for a key **absent** from a locale — but a deferred rewording still
takes the `TODO` comment, the only record that its locales are stale. Escape apostrophes
(`\'`) in any locale's string resources.

## Remote build environments (Cursor Cloud and Claude Code on the web)

- **JDK 21** is pre-installed. **Android SDK** lives at `/opt/android-sdk` (`ANDROID_HOME`).
  On Claude Code on the web the SDK is *not* pre-installed; the `SessionStart` hook at
  `.claude/hooks/session-start.sh` provisions it (cmdline-tools, `platforms;android-37.0`,
  platform-tools, licenses) at session start. If `/opt/android-sdk` is empty mid-session,
  run `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` rather than hand-installing.
- The Gradle wrapper auto-downloads Gradle on first run; AGP auto-installs the compileSdk
  minor platform on the first build.
- Key commands: `./gradlew assembleDebug` (build), `./gradlew test` (unit tests),
  `./gradlew lint` (lint), `./gradlew clean`.
- **`:core` also has a sandbox-friendly inner build**: `cd core && ../gradlew test` runs it
  through `core/settings.gradle.kts`, which needs no `google()` — an offramp for when the
  outer build's AGP plugin markers can't resolve (ported from clothescast).
- **No emulator practicality**: KVM is unavailable in the remote environments. Beyond
  speed, an emulator cannot answer this app's real questions anyway — whether
  `setAutomaticZenRuleState` actually silences the device, what a geofence's exit latency
  is in the field, or how One UI's Sleeping Apps behaves. Those need a real Pixel and a
  real Samsung; say so when reporting verification status.

## Testing expectations

- Code changes must include or update unit tests; product logic belongs in the pure domain
  layer where it is testable without Android. `SnoozeController` is deliberately a plain
  Kotlin state machine over a clock and two injected interfaces (`SPEC.md` §11) — that is
  where most of the real complexity lives, and it should be reachable by a JVM test with no
  Robolectric and no emulator. A change that pushes decision logic into a `Service`, a
  `TileService`, or a composable, where it can only be tested on a device, is going the
  wrong way.
- **The departure test gets trace-driven tests, not hand-waved ones.** `SPEC.md` §6.6's
  accuracy gate, hysteresis, and two-fix confirmation exist to reject specific real-world
  failure shapes — a GPS jump, a 500 m-accuracy cell fix, a walk to the end of the garden.
  Cover each with a recorded fix trace, and add the trace when a bug is found in the field.
- UI changes must include or update Robolectric + Roborazzi screenshot tests, wired into
  `.github/workflows/ci.yml`. The screenshot job records against an explicit
  `--tests` allow-list (one step per screenshot class), so a new `*ScreenshotTest` class
  that isn't added there never records in CI even when it passes locally — add a
  `Run … screenshot tests` step alongside the test class.
- **A screenshot test must wrap the composable in everything the activity wraps it in.**
  `MainActivity` composes `SnoozemoTheme { Surface { … } }`; wrapping in the theme alone
  drops the `Surface`, so a dark snapshot renders over the host's light window background
  and reads as a theming bug.
- The screenshot job auto-commits recording drift back to same-repo PR branches
  (`ci: refresh recorded screenshots`) and auto-posts a before/after diff comment on the
  PR. After CI runs on a UI-touching push, `git pull` before pushing again, and expect the
  refresh commit rather than hand-committing recorded PNGs.
- **Compose test imports: extensions need one, members refuse one.** `assertIsEnabled` /
  `assertIsNotEnabled` are extensions and must be imported; `assertExists` /
  `assertDoesNotExist` are members and fail to compile *with* an import. Both break before
  tests run, so CI shows no failing-tests comment. Copy a sibling test's imports, or call
  it as a method and let Kotlin resolve it.
- Run `./gradlew test` and `./gradlew lint` before pushing when the environment can;
  otherwise say clearly what was verified by inspection only.
- **Fix any preexisting test failures as the *first* commit of the series.** If the tree is
  already red when you start a task, don't stack your work on a broken baseline. Land the
  fix first, on its own commit, so the reason each test goes red is attributable to a single
  change. If the failure is genuinely unrelated and out of scope, say so in the first
  response and confirm before skipping past it — don't silently report a task "done" with
  the tree still red.
- **Don't paper over racy / flaky tests** with `Thread.sleep`, a retry loop, or a bumped
  timeout. If a test depends on ordering (coroutine dispatch, recomposition, a Robolectric
  frame), make the ordering explicit — a test dispatcher you advance, `runTest`, a gate you
  release from the test. A test that passes "most of the time" is broken; rewrite it or fix
  the underlying cause. Time-dependent behavior (the duration cap, the 30 s confirmation
  gap, the 10 s arming ceiling) is driven by an injected clock and a test dispatcher, never
  by real elapsed time.
- **Don't disable a failing check** (a test, lint, a Roborazzi comparison) to make it pass —
  fix the underlying issue.
- **Verify the sandbox state before assuming it either way.** Sandbox config drifts and new
  forks inherit defaults, and an agent is easily fooled by a confidently-stated premise —
  including the one in this file. Spend ten seconds confirming before concluding the build
  can't run here: `command -v sdkmanager`, `ls /opt/android-sdk`, and a
  `curl -s -o /dev/null -w "%{http_code}"` at `https://maven.google.com/` (200 / 302 / 404 =
  reachable; 403 from the sandbox-egress TLS-inspection CA = blocked). If you find it
  blocked when this file says it shouldn't be, flag it.
- **`developer.android.com` is reachable — `curl` it and strip tags.** Its reference pages
  render client-side, so an HTML-to-text fetch returns only the nav index, which reads as
  "the page doesn't document that" and gets platform facts filed as unverifiable.
  `android.googlesource.com` *is* blocked; `sdkmanager` offers `sources;android-36`.

## Cost and reliability

- **Call out cost and reliability up front** when recommending new infrastructure or a new
  external call (a third-party API, a crash/analytics service, an added Firebase surface,
  any network lookup). Include a rough dollar figure — free-tier vs. paid thresholds and
  $/month at expected traffic — and note reliability implications: new failure modes, rate
  limits, added latency, extra points of failure, and what the user sees if the dependency
  is down. If the impact is effectively zero, say so rather than omitting the note.
- **`INTERNET` has landed on `play` and stays off `direct`** — Crashlytics, `SPEC.md` §12,
  `docs/crashlytics.md`. So the line to hold now is the flavor split, not the permission:
  anything that would give `direct` network access, or that would add a second thing using
  `play`'s, is a distribution decision to bring to the user, with its Data Safety
  consequence named alongside the dollar figure. See *Privacy*.
- **Battery is this app's other running cost, paid by the user.** A snooze can be armed for
  eight hours, so a change that adds a wakeup, a location request, a sensor registration,
  or a `WorkManager` period is a battery change and gets stated as one against `SPEC.md`
  §9's budget — including the case where the phone is stationary and the duty cycle (§6.7)
  should have driven the work to zero.

## CI timing

- **Report significant CI timing regressions.** After CI finishes on a push, compare
  against recent runs of *the same job on the same kind of ref*. Only call out significant
  slowdowns (rule of thumb: >25% or >30s on a job under ~5min) — don't narrate routine
  wobble. Name the likely cause: a new heavy dependency, Robolectric cold start, a slow new
  test, cache invalidation.
- **Compare like with like: PR against PR, `main` against `main`.** The `main` run does
  release work a PR run skips, so it is legitimately slower. Comparing a PR job against a
  `main` number, or against a *step* time rather than the job total, manufactures a
  regression that doesn't exist.
