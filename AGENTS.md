---
trigger: always_on
alwaysApply: true
last_modified: 2026-11-12
---

# Snoozemo

An Android app (Kotlin, Compose) that turns on Do Not Disturb until you leave where you
are: a Quick Settings tile arms it, walking away ends it. `SPEC.md` holds the decisions
(change one only with its reason), `TODO.md` the plan, `dev-docs/agent-notes.md` the
how-to. Presence detection has never run on a real phone.

## Fleet conventions

Shared by every mikelward repo and the user-level agent config; canonical in mikelward/conf
`agents/AGENTS.md`. A repo's Project section overrides it.

### This file

- **Session start**. At the start of every session, print the full absolute path of the
  `AGENTS.md` you loaded and its `last_modified` date.
- **Canonical**. AGENTS.md is canonical; `CLAUDE.md` and `GEMINI.md` point to it. Keep it
  brief, generic to all agents (noting per-agent exceptions), and under 22,000 bytes.
  Bump `last_modified` whenever you edit this file.

### Talking to the user

- **Questions**. One at a time; wait for the answer.
- **Messages**. Send one only for a question, a blocker, something to act on, or a change
  of plan. No progress reports or narrating events.
- **Claude Code**. It reliably shows only a turn's last message; put everything there.
- **Replies**. Short, key point first, ending with open PR links and any pending
  question.
- **Network access**. If you want access to a host and you can't access it or a mirror,
  ask the user to allowlist the domain(s) along with the purpose.

### Git

- **Branches**. `<agent>/<topic>` off a fresh `origin/main` (fetch by refspec). Never
  commit to `main`.
- **Your branches**. Push, force-push with lease, and delete them freely. Ask before
  touching others', `main`, settings, secrets or paid services.
- **Commits**. One per logical change; fold in review fixes.
- **Subjects**. Changelog lines: imperative, the user-visible change, ≤ 70 chars. PR
  titles match.
- **Prefixes**. `docs:`, `test:`, `build:`, `refactor:` mark what users won't notice. No
  `feat:` or `fix:`.
- **Model names**. Never in anything pushed.
- **After a merge**. Branch afresh.
- **Shallow clones**. Unshallow before trusting history or calculating a version code.
- **Language**. US English everywhere.

### Pull requests

- **Opening**. Open the PR unasked; update its title and body before each push.
- **Descriptions**. Scope, rationale and test plan, plus the change's privacy,
  reliability and cost impact (say "none" where there is none).
- **TODO.md**. Tick an item off in the PR that implements it, not a follow-up. Don't
  spend a CI run on trivia.
- **Watching**. PR events plus one scheduled check (events miss CI success and Codex's
  👍).
- **Auto-merge**. Enable it on PRs that target `main`; stack dependent ones.
- **Base moved**. After a merge, tell open PRs on that base whose body ends with a session
  URL.
- **Merge or close**. Answer remaining threads and stop watching.

### Reviews

- **Codex**. It reviews automatically. Fix, decline with a reason, or defer to `TODO.md`
  every comment; reply citing the commit, then resolve.
- **Merit**. Codex over-reads rules, privacy especially: quote and decline rather than
  cripple the code. Design changes are the user's call.
- **Verdict**. 👍 is clean, 👀 is reviewing. Only the code review gates merging: its
  findings (review comments, PR comments, reviews) block it. Security reviews often
  hang; don't wait on them.
- **Hung review**. `@codex review` after five quiet minutes, again an hour later, then
  tell the user.
- **After a 👍**. Ask before unneeded changes.
- **Reports**. Give each review's findings in your next message.

### Autonomy

- **Fix proactively**. Lint, test, presubmit, CI and review failures, unasked.
- **Proactive refactors**. Repeated bugs in one area, duplicated code, or a change with
  twins elsewhere: suggest the redesigned or shared version.
- **Drive to merge**. Once the details are agreed, see the change through unattended:
  implement it, open the PR, keep it mergeable (fix CI, answer reviews, rebase when it
  conflicts or falls behind), and merge once CI is green and Codex 👍s, or let
  auto-merge.
- **Drive**. Take `TODO.md` items one at a time and drive each to merge, until done or
  stopped.
- **Autopilot**. Drive without asking, logging guesses in `TODO.md` under *Decisions
  needing review*. Still ask before anything irreversible, outside this repo, or private.
- **Red baseline**. Fix existing failures first, in their own commit.
- **Lasting fixes**. Fix the cause in a repo. Don't propose a step that lasts only this
  session (stopping a service, a setting in this container) or a hand edit to a dotfile:
  neither persists.

### Quality

- **Tests**. Every behavior change gets one; a bug fix's fails first. Assert behavior,
  not structure. No sleeps or retries; never disable a failing check.
- **Docs**. Keep `SPEC.md` and `README.md` current.
- **CI**. Wait for it before claiming success.
- **Errors**. Never swallow one silently: every catch logs it with context (no user
  data), cleans up what the `try` acquired, and handles the case explicitly. To ignore
  one on purpose, say why in a comment and still log it at debug level.
- **Logs**. Every process logs that it started, that it ended and how, and each error,
  where that outlasts it, a logout and a reboot: the journal, or a file under
  `~/.local/state`. Never only `/tmp`, a runtime directory or `/dev/null`.
- **Cost**. State the cost and reliability of new dependencies or services up front.
- **Privacy**. No user data (personal paths, hostnames, tokens, keys, private remotes)
  leaves this machine; use placeholders.
- **Portability**. Flag anything that won't run on Linux and macOS.
- **UI text**. Words a person would say, as few as do the job. Say what a setting does;
  leave out what works by default, what the screen already shows, and how it works.
- **Terminology**. Standard domain terms, never coined ones: "Text console layout is
  Dvorak", not "The text consoles type Dvorak"; "dimming inactive windows", not "the dim".

## Project

### Principles, in order

1. Never leave the phone silent by mistake: any doubt ends the snooze.
2. Never fail silently: the notification says what degraded.
3. Never lose the user's places or settings.
4. Prepare ahead; never block the tile tap.
5. Say why.

### Rules

- **Invariants**. The duration cap always fires, ending is idempotent, and Snoozemo ends
  only its own Do Not Disturb rule.
- **The arm path**. One tap, maybe locked, under a second: start the service within a
  frame, never wait on a location fix, and do no blocking I/O before the snooze is on,
  except the ringer change that must precede it. A PR touching it states what it waits
  for.
- **Logic**. It lives in the plain-Kotlin `SnoozeController`; departure logic is tested
  against recorded location traces.
- **Privacy**. Coordinates, network names and place names never leave the device, and
  the on-device log keeps only coarse state and reasons. Anything new leaving the device
  is a product decision.
- **Play policy questions**. Permissions, foreground services and Data Safety go to the
  user with the policy text, even on autopilot.
- **Battery**. State the cost of any new wakeup, location request or sensor.
- **Build**. `./gradlew test` and `./gradlew lint`; `.claude/hooks/session-start.sh`
  sets up the SDK. Do Not Disturb and geofences need a real phone: say what you couldn't
  verify.
- **Screenshots**. CI records them; each new screenshot class needs its own CI step.
- **What's new**. Title every commit and PR like a changelog line (maintainer,
  2026-10-23): an imperative verb, then the feature in the user's words, sentence case,
  ≤ ~70 chars, e.g. `Fix …`, `Improve …`, `Add …`, an optional parenthetical for how
  (scenario, mechanism, symbols: the body). Each subject ships in the GitHub prerelease
  and Play "What's new" (`docs/play-store-internal-track.md`) as a `• ` bullet,
  oldest first (repeats once).
- **Prefixes**. Prefix a subject with no user-visible effect, overriding the fleet's list:
  `ci:`, `docs:` (incl. `docs/PRIVACY.md`), `internal:`, `refactor:`, `test:`/`tests:`.
  Never `build:`: CI doesn't filter it, so it ships. A shipped dependency is bare (a
  test/build-only bump you write keeps its prefix). Commits touching only `.md` or root
  dot-paths never ship a bullet; prefix them anyway.
- **500 characters**. Play caps "What's new" there (plus a 50-distinct-subject cap), so
  squash supporting commits. The range base is the last run that published — don't
  hand-re-push notes.
- **After a merge**. Report the versionCode (`git rev-list --count origin/main` after an
  unshallowed refspec fetch): `Need versionCode 72 (b81c23d) or higher to test PR #52's
  fix`.
- **Release notes since N**. Up to five `• ` bullets worded as titles in one code block,
  for commits N+1..`origin/main` (fetched and unshallowed; offline, only from a complete
  local `main`, saying it may be behind). One per feature (a reworked one by its final
  state; read bodies where unclear), ranked by what the user risks without it: broken or
  wrong first, polish last. Skip prefixed/docs/CI commits, dependency bumps unless nothing
  else changed (then one bullet: libraries were updated) and anything the range undid;
  range above, omissions below.
- **UI**. 4dp grid, short copy, standard Material icons.
- **Translations**. Wait until the maintainer approves the English.
- **Shared script**. `scripts/publish-github-release.sh` is identical across the Android
  apps: change them all.
