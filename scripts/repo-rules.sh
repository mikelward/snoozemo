#!/usr/bin/env bash
# Applies this repo's settings and branch rules via the GitHub API, so they
# are recorded here rather than living only in the settings UI. Idempotent:
# run it whenever the rules below change, from any machine where `gh` is
# authenticated with admin access to the repo.
#
# What it enforces, and why (maintainer decisions, 2026-08-22):
# - Rebase merging only: the release-notes pipeline ships each commit's own
#   subject, so a stray squash or merge commit from the UI would break it.
# - Pull request creation restricted to collaborators.
# - A `main` ruleset: linear history, no force pushes or deletion, PRs only,
#   conversations resolved before merge, and the `lanes` + `codex` statuses
#   required. "Require branches up to date" stays OFF deliberately: every
#   push to main would force a rebase, a full CI run, and a fresh Codex
#   round on every open PR, and the lanes gate already refuses to report a
#   verdict computed against a base that moved.
set -euo pipefail

repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || true)
repo=${1:-${repo:-mikelward/snoozemo}}
echo "Applying repo rules to $repo"

echo "- merge methods: rebase only; delete merged branches"
gh api -X PATCH "repos/$repo" \
  -F allow_merge_commit=false \
  -F allow_squash_merge=false \
  -F allow_rebase_merge=true \
  -F delete_branch_on_merge=true >/dev/null

# The pull-request-access setting (GitHub changelog 2026-02-13). Best-effort:
# the field may not be in the PATCH schema yet everywhere; a refusal here
# means set it in Settings > General > Pull requests instead.
echo "- pull request creation: collaborators only (best-effort)"
if ! gh api -X PATCH "repos/$repo" \
  -f pull_request_creation_permission=collaborators_only >/dev/null 2>&1; then
  echo "  ! the API refused pull_request_creation_permission; set it in the settings UI"
fi

ruleset=$(cat <<'JSON'
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": true } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [
          { "context": "lanes" },
          { "context": "codex" } ] } }
  ]
}
JSON
)

existing=$(gh api "repos/$repo/rulesets" --jq '.[] | select(.name == "main") | .id' | head -1)
if [ -n "$existing" ]; then
  echo "- updating existing ruleset 'main' (id $existing)"
  printf '%s' "$ruleset" | gh api -X PUT "repos/$repo/rulesets/$existing" --input - >/dev/null
else
  echo "- creating ruleset 'main'"
  printf '%s' "$ruleset" | gh api -X POST "repos/$repo/rulesets" --input - >/dev/null
fi

echo "Done. Current rulesets:"
gh api "repos/$repo/rulesets" --jq '.[] | "  \(.id) \(.name) [\(.enforcement)]"'
