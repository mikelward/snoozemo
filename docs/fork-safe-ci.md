# Making CI safe to open to external PRs and forks

Snoozemo's CI (`android-ci.yml`) currently trusts every pull request the same
way: it runs on plain `pull_request`, so GitHub already withholds repo
secrets from fork PRs, but the job **definitions themselves** are read from
whatever the PR branch contains — a PR could rewrite `classify`/`build`/
`gate` to forge a green required check. Nothing in this repo depends on that
today because there are no external contributors yet, but it's the blocker
to accepting one safely.

`mikelward/typelauncher` already solved this; this doc maps its architecture
onto snoozemo and breaks the migration into ordered, independently-mergeable
milestones. Two things are true throughout: **no agent can create the
credentials this needs** (a PAT, a GitHub App, a branch-protection ruleset
change) — those are the maintainer's steps, called out where they block —
and **every milestone below should be its own PR**, reviewed and merged
before the next one starts, so a mistake in the security-relevant jobs is
caught with the smallest possible blast radius.

**Ordering constraint this whole plan depends on**: milestones 2 and 3 add
real secrets (`CI_COMMIT_ARTIFACT_TOKEN`, then `LANES_APP_PRIVATE_KEY`)
while still on plain `pull_request` — and under `pull_request` a same-repo
PR's own branch controls the workflow YAML *and* receives the full
`secrets` context (only fork PRs get secrets withheld). Until milestone 5
switches to `pull_request_target`, any same-repo PR could rewrite
`android-ci.yml` to exfiltrate whichever secret already exists. That's safe
right now only because snoozemo has exactly one collaborator — **don't
grant anyone else push/collaborator access, and don't accept a fork PR,
between landing milestone 2 and finishing milestone 5.** If that changes
first, rotate the credentials and reconsider the ordering rather than
proceeding on schedule.

## Why `pull_request_target`, and what it actually changes

`pull_request_target` loads the workflow **definition** from the base branch
(`main`) regardless of what the PR itself contains — a PR touching
`android-ci.yml` can no longer rewrite `classify`/`build`/`gate` to forge a
green check. That's the one thing plain `pull_request` can't give us: GitHub
already protects secrets from fork PRs there, but not the job definitions.

The trade **is** the fork safety this doc exists to build, not a side
effect: under `pull_request_target`, every job gets the **full `secrets`
context regardless of fork status** — the fork-only secret withholding
`pull_request` relies on does not apply. So the rule has to flip from
"forks don't get secrets" to an explicit, audited one: **no job that
executes PR-supplied code (the `build` and `screenshot-tests` render steps)
may reference `secrets.*` anywhere**, full stop, and every checkout in a
PR-content-reading job must explicitly resolve `refs/pull/<pr>/merge` —
under `pull_request_target` the *default* ref resolution is the **base**
branch's tip, not the PR's, so a step that forgets this silently validates
the wrong tree instead of failing loudly.

`classify` and `finalize` reading `.github/lanes.conf` from that same merge
ref is deliberate, not a hole this migration reopens: could a PR widen its
own copy of `lanes.conf` to reclassify a sensitive file as docs and skip
review of it? No — `mikelward/lanes`'s engine hard-codes the policy file
itself as always "code," decided by the engine rather than the policy it's
reading (`lanes.mjs`'s `isDocs()`, before consulting any rule), so a PR that
touches `lanes.conf` at all can never get `docs_only: true` regardless of
what its own copy's rules say. Pointing `classify`/`finalize` at the base
branch's `lanes.conf` instead — the more obviously "safe-looking" choice —
would actually be wrong: it's what typelauncher's own comments warn against,
since `classify` and `finalize` disagreeing about which copy is in force is
its own correctness bug.

## The check-run attribution problem this drags in

`gate`/`lanes` today work because branch protection requires an ordinary
GitHub Actions check-run by that name. **That stops working under
`pull_request_target`**: a job's own ambient check-run always attributes to
the *base* branch's tip under this trigger, never the PR's head, so it can
never satisfy a PR's required check no matter what the ruleset asks for.
typelauncher's fix is a two-job pattern that publishes the verdict as a
**commit status** instead, via a dedicated GitHub App's installation token
(never the job's own `GITHUB_TOKEN`):

- **`init`** (no `needs:`, no `if:`, runs on every trigger including
  `push`) posts a `pending` status before anything else runs, so the
  status exists — and is attributed to the right commit — for the whole
  run's duration. Which commit isn't always the PR head: `mikelward/lanes`'s
  own `statusSha()` resolves the PR's head SHA for `pull_request`/
  `pull_request_target`, but falls back to `GITHUB_SHA` for everything else
  (`lanes.mjs:850-863`) — so `init` succeeds cleanly on a push to `main`
  too, posting a status nothing there needs to read. Verified against the
  source specifically because `classify` gaining `needs: [init]` below
  would otherwise be a real risk to main-push CI (build, screenshot-tests,
  `deploy`) if `init` could fail or get skipped on a non-PR trigger — it
  can't, on either count, since typelauncher's own `init` job carries no
  `if:` either.
  **`classify` must declare `needs: [init]`** (everything downstream
  already depends on `classify`, so this alone serializes the whole
  chain) — without it, `classify`/`build`/`screenshot-tests` are scheduled
  concurrently with `init` rather than after it, and on a same-head rerun
  (the workflow's `edited` event, e.g. a title/body-only PR edit) the
  *previous* run's `success` status stays live and merge-satisfying for
  however long `init` takes to overwrite it with `pending` — a real, if
  narrow, window where a PR could merge against a stale verdict. Confirm
  whether typelauncher's own `init`/`classify` carry this same gap before
  copying its shape verbatim; fix it here regardless.
- **`finalize`** (`needs: [init, classify, build, screenshot-tests,
  sync-screenshots]`, `if: !cancelled()`) re-derives the same verdict `gate`
  computes today and publishes it as the final status through the same App.

Both call `mikelward/lanes@main` with `app-id`/`app-private-key` inputs
instead of (or alongside, during the transition) today's plain `mode: gate`
Actions job. **This needs a new GitHub App**, separate from the
`CI_COMMIT_ARTIFACT_TOKEN` PAT: created and installed on this repo by the
maintainer, with two new secrets (typelauncher's naming: `LANES_APP_ID`,
`LANES_APP_PRIVATE_KEY`). Once it's reporting, the branch protection ruleset
has to be pointed at the new `lanes` **commit status** instead of the old
`gate`/`lanes` **check-run** — `mikelward/lanes`'s own README has the
staged-rename procedure snoozemo already followed once for the `gate` →
`lanes` check-run rename, and the same shape applies here (run both in
parallel until the new one has reported at least once on a real PR, then
flip the ruleset, then delete the old path).

## The screenshot-refresh piece

Separate from the trigger migration: adopt `mikelward/ci-commit-artifact`
for the screenshot commit-back, matching typelauncher's `sync-screenshots` +
`post-screenshot-diff` split —

- `screenshot-tests` (the render job) drops its commit/push/dispatch steps
  and only uploads the `roborazzi-screenshots` artifact — it still runs
  PR-controlled Gradle code, so per the rule above it must hold no secrets.
  Its `permissions:` block shrinks to `contents: read` (just the checkout)
  — the `write`/`actions: write` grants the single combined job holds
  today move to `sync-screenshots` below. **Keep today's existing "Fail on
  screenshot drift when a refresh cannot be pushed" step here** (same
  reach — fork PRs and pushes to `main`, the contexts `sync-screenshots`
  is gated away from below): dropping it along with the commit logic would
  silently remove the only thing that catches a renderer producing
  different pixels than the committed snapshots on exactly the paths that
  have no commit-back to fall back on. **Not literally unchanged past
  milestone 5, though**: its `if:` today tests
  `github.event_name != 'pull_request'`, and once milestone 5 renames the
  trigger to `pull_request_target` that string is never `'pull_request'`
  again for *any* PR run — same-repo included — so the condition would
  fire on every PR and fail the render job before `sync-screenshots` gets
  a chance, breaking the refresh path the whole migration exists to keep
  working. Update the literal to `'pull_request_target'` as part of
  milestone 5's own trigger-rename work, not as an afterthought.
- **`gate`/`lanes` must add `sync-screenshots` to their own `needs:` and
  fold its result into the verdict** (`results: ... sync=${{
  needs.sync-screenshots.result }}`, alongside `build`/`screenshots`),
  treating its same-repo-only `skip` as valid rather than a failure — this
  applies from milestone 1 onward, not just once `finalize` exists in
  milestone 3+. Without it, a render that succeeds but a sync that fails
  (a rejected push, a vanished branch) is invisible to the required check:
  splitting one job that failed as a whole into three means the check that
  used to depend on all of it now has to depend on all three explicitly,
  or the split itself opens the gap this migration exists to close.
- **`sync-screenshots`** (`needs: [classify, screenshot-tests]`, same-repo
  PRs only) is a bare `uses:
  mikelward/ci-commit-artifact/.github/workflows/commit-artifact.yml@main`
  call: `artifact-name: roborazzi-screenshots`, `dest-path:
  app/src/test/snapshots/images`, `branch-ref`/`expected-head-sha` from the
  PR event, and `secrets: push-token: ${{ secrets.CI_COMMIT_ARTIFACT_TOKEN
  }}` (the maintainer's existing typelauncher PAT, reused here — no
  `dispatch-workflow` needed once `push-token` is set, since the
  authenticated push retriggers `pull_request_target` on its own).
  **Needs its own `permissions: contents: write, pull-requests: write,
  actions: write` declared on this caller job — all three, not just the
  two this call actually exercises.** Verified live while landing
  milestone 1 (PR #90): GitHub's static check at call time requires the
  caller to grant *at least* every permission the called workflow's own
  job declares, regardless of whether this particular call path uses it —
  `commit-artifact.yml`'s `commit` job declares
  `contents: write, pull-requests: write, actions: write`, so omitting
  `pull-requests: write` here (reasoned, at the time this doc was first
  written, as safe since `comment-marker` stays unset — that reasoning
  covers runtime behavior, not the platform's static permission check)
  produces an outright `startup_failure`, not a runtime permission error:
  `.github/workflows/android-ci.yml (Line: N, Col: N): Error calling
  workflow '…'. The nested job 'commit' is requesting 'pull-requests:
  write', but is only allowed 'pull-requests: none'.` `pull-requests:
  write` is genuinely dead weight for what this call does — `comment-marker`
  stays unset, and the PR comment is still posted by `post-screenshot-diff`
  instead (matching typelauncher's split, not the hub workflow's own
  built-in one) — but the platform demands it anyway.
- **`post-screenshot-diff`** (`needs: [sync-screenshots]`) checks out
  `needs.sync-screenshots.outputs.commit-sha` (the hub workflow's own
  authoritative result — never a locally-made commit that might not have
  actually landed) **with `fetch-depth: 0`** and posts the PR comment. The
  ported script computes `git merge-base HEAD "origin/${GITHUB_BASE_REF}"`
  — `actions/checkout`'s default shallow clone doesn't fetch the base
  branch at all, so without `fetch-depth: 0` (already present on today's
  combined job's own checkout, easy to drop by omission when the checkout
  step gets copied into a new job) that call fails before the comment can
  post. **Needs its own `permissions: contents: read, pull-requests:
  write`** for the same reason as above. Port snoozemo's existing
  byte-budget truncation logic here (`android-ci.yml`'s current `Post
  screenshot diffs as a PR comment` step, PR #83) rather than
  typelauncher's older fixed-20-item version — snoozemo's is strictly
  better and already proven.

## zizmor

`pull_request_target` and an unpinned `@main` reference are both refused by
zizmor's defaults. typelauncher's `.github/zizmor.yml` carries the narrowly-
scoped exceptions this migration needs: a `dangerous-triggers` ignore for
each `pull_request_target` workflow (with the job-definition-tampering
reasoning inline), and an `unpinned-uses` policy entry for
`mikelward/ci-commit-artifact/.github/workflows/commit-artifact.yml` (and
`mikelward/lanes`, already present). Copy the shape, not the file — reason
through each exception for snoozemo's own jobs rather than pasting
typelauncher's.

## Setup steps: what actually allowlists the push

Adding a secret to the repo is the whole "allowlist" step here — there is
no separate approval screen. What makes a push count as a real, CI-
triggering actor instead of a loop-prevented `GITHUB_TOKEN` push is simply
*which credential authenticates it*, so the steps below are the entire
mechanism, not a subset of it.

### The `CI_COMMIT_ARTIFACT_TOKEN` PAT (unlocks milestone 2)

1. Check whether the fine-grained PAT already used for `typelauncher` covers
   this repo too: **github.com → your avatar → Settings → Developer
   settings → Personal access tokens → Fine-grained tokens**, open it, and
   look at **Repository access**. A fine-grained PAT can be scoped to
   several repositories at once — if it says "Only select repositories" and
   `snoozemo` isn't in the list, either edit the token to add `snoozemo`
   (only works before it expires and only if it wasn't issued with a fixed
   single-repo scope), or generate a new one.
2. To generate a new one: same **Personal access tokens → Fine-grained
   tokens** page → **Generate new token**. Set **Resource owner** to
   `mikelward`, **Repository access → Only select repositories →
   snoozemo** (add `typelauncher` too if consolidating onto one token),
   and under **Repository permissions** set **Contents: Read and write**
   — that's the only permission `ci-commit-artifact`'s push step needs.
   Leave everything else at **No access**.
3. Add it to snoozemo: repo → **Settings → Secrets and variables →
   Actions → Repository secrets → New repository secret**. Name:
   `CI_COMMIT_ARTIFACT_TOKEN`. Value: the token from step 1 or 2.

### The `lanes` GitHub App (unlocks milestones 3–4)

Only needed if milestones 3–6 go ahead — skip this if the answer to the
second open question below is "not yet."

1. **github.com → your avatar → Settings → Developer settings → GitHub
   Apps → New GitHub App.** Name it something like `snoozemo-lanes` (App
   names are globally unique across GitHub). Homepage URL can be the repo
   URL. Uncheck **Webhook active** — this App only mints tokens for
   `actions/create-github-app-token`-style use, it doesn't need to receive
   events.
2. Under **Repository permissions**, set **Commit statuses: Read and
   write**. Nothing else — no Contents, no Pull requests. **Metadata: Read-
   only** is added automatically and can't be removed; that's fine.
3. **Create GitHub App**, then **Generate a private key** on the App's own
   settings page — this downloads a `.pem` file. Note the **App ID** shown
   near the top of the same page.
4. **Install the App**: from the App's settings page, **Install App** →
   pick the `mikelward` account → **Only select repositories → snoozemo**.
   This install step is the actual allowlisting — a valid App ID and key
   mint no usable token on a repo the App isn't installed on.
5. Add two repo secrets the same way as above: `LANES_APP_ID` (the numeric
   ID from step 3) and `LANES_APP_PRIVATE_KEY` (the full contents of the
   downloaded `.pem` file, including the `-----BEGIN`/`-----END` lines).

### The branch-protection ruleset (unlocks milestone 4, then 5–6)

**Repo → Settings → Rules → Rulesets**, open the ruleset that currently
requires the `gate`/`lanes` check-run. Under its status-check requirement,
add the new `lanes` **commit status** (it'll show up as a selectable check
once `init`/`finalize` have reported at least once on a real PR — land
milestone 3 first, open a throwaway PR to make it report, then come back
here). Leave the old `gate`/`lanes` check-run required until the new one
has proven itself; remove it only as the last step of milestone 4.

## Milestones

Ordered so each is independently valuable and low-risk relative to the next;
stop and reassess between any two if something doesn't verify as expected.

1. **Adopt `ci-commit-artifact` for the screenshot refresh, staying on plain
   `pull_request`.** Split `screenshot-tests` into the render job +
   `sync-screenshots` (bare `uses:` call) + `post-screenshot-diff`, exactly
   as above — including the drift-fail step staying in the render job and
   `gate`/`lanes` gaining `sync-screenshots` in their own `needs:` and
   verdict, both from this milestone on, not deferred to milestone 3 —
   but with `dispatch-workflow: android-ci.yml` in place of `push-token`
   for now, since `ci-commit-artifact`'s own validation treats that as
   safe under plain `pull_request`. This is the structural security fix
   (commit/push no longer shares a job with PR-controlled Gradle code)
   landed and verified independent of everything below. **Blocked on
   nothing** — no new secret needed yet.
2. **Flip `sync-screenshots` to `push-token: ${{
   secrets.CI_COMMIT_ARTIFACT_TOKEN }}`**, still on plain `pull_request`.
   Drops `dispatch-workflow` and the `pr` workflow_dispatch input's
   screenshot-refresh use. **Blocked on**: the maintainer adding
   `CI_COMMIT_ARTIFACT_TOKEN` as a repo secret (the typelauncher PAT reuse
   already agreed on). Verify a real screenshot-drift PR actually gets a
   fresh, authenticated push and a real `pull_request` re-run before calling
   this done.
3. **Create the `lanes` GitHub App and wire up `init`/`finalize` as a
   commit-status path, running *alongside* today's `gate`/`lanes` check-run
   jobs** (both report; ruleset still requires the old one). **Blocked on**:
   the maintainer creating and installing the App, and adding
   `LANES_APP_ID`/`LANES_APP_PRIVATE_KEY`. This can land and prove itself on
   plain `pull_request` — nothing here requires the trigger switch yet.
   **Add the workflow-level `concurrency` group here, not in milestone 5**:
   a commit status has no ordering guarantee the way a check-run's own
   history does — two overlapping runs on the same head (a `synchronize`
   and an `edited` firing close together, say) can have their `finalize`
   jobs complete and publish in either order, so a newer run's correct
   verdict can be overwritten by an older, slower run's stale one. That's
   still just informational while `gate`/`lanes` stays the actual required
   check, but milestone 4 makes the commit status the *sole* required
   verdict — the protection has to already be in place by then, not added
   a milestone late once the exposure is real.
   **The key matters, not just "add a group"**: `github.ref` alone is the
   wrong key — under `pull_request_target` (milestone 5) it resolves to the
   *base* branch (`refs/heads/main`) for every PR run, so a bare-`github.ref`
   group would serialize or cancel unrelated PRs against each other and
   leave their statuses stuck pending. Use typelauncher's actual formula,
   which stays correct across the milestone 5 trigger switch without
   needing to change again: `github.event.pull_request.number` when a PR
   context exists (identical on `pull_request` and `pull_request_target`),
   falling back to `github.ref` only for `push`/PR-less `workflow_dispatch`
   — `cancel-in-progress` on the PR path, not on anything that can reach
   `deploy`, so a superseded PR run is cancelled but an in-flight release
   upload never is.
4. **Switch the ruleset to require the new `lanes` commit status**, then
   delete the old check-run-based `gate`/`lanes` jobs — the same staged-
   rename shape already used once in this repo (`mikelward/lanes`'s README).
   **Blocked on**: the maintainer changing branch protection (not available
   to a session without ruleset API access, per this repo's own `TODO.md`
   precedent for the original `gate` → `lanes` rename).
5. **Switch the trigger itself to `pull_request_target`**, add the zizmor
   policy exceptions, and audit every job per the secrets/checkout rules
   above (`build`, `screenshot-tests` hold no secrets; every PR-content
   checkout resolves the merge ref explicitly — the `concurrency` group
   already landed in milestone 3). This is the highest-risk step and
   should land only once 1–4 are proven working on real PRs.
   - **Carries a real casualty that needs its own fix, not a silent
     drop**: today's `build` job posts the failing-tests PR comment from
     *inside* itself, using `secrets.GITHUB_TOKEN` with
     `pull-requests: write`, *after* Gradle (PR-controlled code) has
     already run — exactly the pattern this migration exists to close.
     Making `build` secret-free breaks it. Add a clean follow-up job
     (`needs: [build]`, **`if: always() && needs.build.result ==
     'failure'`** — the `always()` is load-bearing, not redundant: an
     `if:` with no explicit status-check function is implicitly wrapped in
     `success()`, and `success()` is false whenever a `needs:` job failed,
     so a bare `if: needs.build.result == 'failure'` would silently never
     run at all, the exact opposite of the point. Not bare `always()`
     alone either — `build` is *skipped*, not run, on a docs-only PR, so
     nothing ever uploads `unit-test-reports`, and downloading an artifact
     that was never created would fail on every legitimate docs-only PR
     and drag a false red into `finalize`'s dependencies. Matching
     `post-screenshot-diff`'s
     own shape: downloads the `unit-test-reports` artifact `build` already
     uploads and posts the comment from there, no secrets anywhere near
     PR-controlled code. Keep it in `finalize`'s `needs:` list. Losing
     this without a replacement would silently break the diagnostic
     contract `AGENTS.md` documents elsewhere ("no comment means the
     failure happened before tests ran") — the comment would just stop
     existing, misdirecting every future CI-failure triage.
     `build.result == 'failure'` alone isn't quite enough, though: today's
     upload step already sets `if-no-files-found: ignore`, so a failure
     early enough that no report directory exists yet at all (a compile
     error, before Gradle reaches any test) means `build` failed but still
     uploaded nothing. The download step in the follow-up job needs to
     tell that apart from a download that fails for an unrelated reason
     (a flaky API call, a permissions slip) — **not** a blanket
     `continue-on-error: true` on the download step itself, which would
     swallow both alike and post "no comment" for an infrastructure
     hiccup exactly as if tests had never run, silently trading one
     diagnostic-contract violation for another. Check whether the artifact
     exists first (`actions/github-script` against the run's own artifact
     list, or equivalent) and branch on that specifically: a confirmed
     not-found exits cleanly with no comment (the correct outcome here),
     anything else — including the download itself failing — surfaces as
     a real job failure. **Needs its own `permissions: actions: read,
     pull-requests: write`** — same pattern as every other job in this
     doc, easy to drop by omission on a job added after the others: the
     artifact-existence check needs `actions: read`, posting the comment
     needs `pull-requests: write`, and the workflow-level `permissions: {}`
     grants neither by default.
     Unlike `sync-screenshots`, the reporter's own result does **not**
     need feeding into `finalize`'s `results:` string alongside it: its
     `if:` runs it if and only if `needs.build.result == 'failure'`, so
     every path that reaches it has already turned the verdict red via
     `build`'s own result — there's no scenario where the reporter fails
     independently of an already-failing build the way a `sync-screenshots`
     push failure can happen against an otherwise-green render. Wiring it
     in anyway wouldn't be wrong, just wouldn't change any pass/fail
     outcome — `needs: [build]` is enough for correctness here.
6. **Decide whether to actually accept fork PRs** — the trigger migration
   makes it *safe* to, but doesn't by itself change repo visibility or
   contribution policy. That's a separate decision for the maintainer, not
   implied by finishing this migration.

## Open questions for the maintainer

- Same PAT across repos: is `CI_COMMIT_ARTIFACT_TOKEN` scoped per-repo
  (fine-grained PATs can cover multiple repositories) or does snoozemo need
  its own token even though the underlying account is the same as
  typelauncher's?
- Is a second GitHub App (Milestone 3) worth it for snoozemo's current
  scale, or should the `gate`/`lanes` check-run mechanism just stay as-is
  until this repo has real external contributors — i.e., do milestones 1–2
  alone (the screenshot piece) without ever doing 3–6?
