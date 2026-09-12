#!/usr/bin/env python3
"""Drives the release-notes subject collection from `.github/workflows/ci.yml`.

`deploy` is main-only, so the "Build release notes" step never runs on a pull
request and a regression in it would first surface after reaching `main` — as a
wrong Play "What's new" card, which is the worst place to find one.

The whole collection block is extracted from the workflow and run against a
throwaway git repository, rather than a copy of the helper being tested in
isolation. Two reasons, both about what a test like this gets wrong: a copy
passes forever once the workflow drifts, and exercising only the helper would
stay green if the loop stopped calling it — putting the duplicate bullets
straight back while every assertion still reported ok.

Standard library only, matching the other scripts in this directory — no YAML
parser, so the block is located by its own source. Run it directly:
python3 scripts/release_notes_dedup_test.py
"""

import re
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WORKFLOW = REPO / ".github" / "workflows" / "ci.yml"


def extract_collection_block() -> str:
    """Return the workflow's subject-collection block, dedented.

    Spans `subjects=()` through the loop's `done`, so the helper, the
    qualifying counter, the prefix filter, the housekeeping-path filter and
    the call site are all the shipped text. Fails loudly when the block cannot
    be found: a test that silently exercises nothing is worse than no test,
    and restructuring this step is exactly the change that should fail here
    rather than quietly pass.
    """
    source = WORKFLOW.read_text()
    match = re.search(
        r"^(?P<indent>[ ]*)subjects=\(\)\n"
        r"(?P<body>.*?)\n"
        r'(?P=indent)done <<< "\$shas"\n',
        source,
        re.S | re.M,
    )
    if match is None:
        sys.exit(
            f"the subject-collection block was not found in {WORKFLOW} — it must run "
            "from `subjects=()` to `done <<< \"$shas\"`. If the step was restructured, "
            "update this test with it; do not delete the test."
        )
    block = "subjects=()\n" + match.group("body") + '\ndone <<< "$shas"\n'
    return textwrap.dedent(block)


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=repo, capture_output=True, text=True, check=True
    )
    return result.stdout.strip()


def make_commit(repo: Path, subject: str, filename: str, nonce: int) -> str:
    """Commit `filename` with a distinct body under `subject`.

    The body carries `nonce` because two commits in these fixtures routinely
    share a subject AND a path — that is the case under test — and git refuses
    a commit whose tree is unchanged.
    """
    (repo / filename).parent.mkdir(parents=True, exist_ok=True)
    (repo / filename).write_text(f"{subject} {nonce}\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", subject)
    return git(repo, "rev-parse", "HEAD")


def collect(block: str, commits: list) -> tuple:
    """Run the block over `commits` and return (subjects, qualifying, truncated).

    `commits` is a list of (subject, filename). A setup commit is made first
    and excluded from the range: the production step runs `git diff-tree`
    without `--root`, so a repository's first commit reports no paths at all
    and would be filtered as housekeeping — an artifact of the fixture, not
    the behavior under test.
    """
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        git(repo, "init", "-q", "-b", "main")
        git(repo, "config", "user.email", "test@example.com")
        git(repo, "config", "user.name", "Test")
        make_commit(repo, "setup", "app/setup.kt", 0)

        shas = [
            make_commit(repo, subject, filename, i)
            for i, (subject, filename) in enumerate(commits, start=1)
        ]

        script = (
            "set -euo pipefail\n"
            f"shas=$(printf '%s\\n' {' '.join(shas)})\n"
            + block
            + '\nprintf "Q:%s\\n" "$qualifying"\n'
            + 'printf "T:%s\\n" "$cap_truncated"\n'
            + 'for s in ${subjects[@]+"${subjects[@]}"}; do printf "S:%s\\n" "$s"; done\n'
        )
        result = subprocess.run(
            ["bash", "-c", script],
            cwd=repo,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise AssertionError(f"block exited {result.returncode}: {result.stderr}")
        lines = result.stdout.splitlines()
        subjects = [l[2:] for l in lines if l.startswith("S:")]
        qualifying = int(next(l[2:] for l in lines if l.startswith("Q:")))
        truncated = next(l[2:] for l in lines if l.startswith("T:")) == "true"
        return subjects, qualifying, truncated


def main() -> int:
    block = extract_collection_block()
    failures: list = []

    def check(name: str, got, want) -> None:
        if got != want:
            failures.append(f"{name}: got {got!r}, expected {want!r}")
        else:
            print(f"ok: {name}")

    # The behavior this exists for: two dependency batches in one release
    # window write the same subject, and the card must carry it once.
    subjects, qualifying, truncated = collect(
        block,
        [
            ("Update dependencies", "gradle/libs.versions.toml"),
            ("Update dependencies", "gradle/libs.versions.toml"),
        ],
    )
    check("a repeated subject is collected once", subjects, ["Update dependencies"])
    check("both commits still counted as qualifying", qualifying, 2)

    # The FIRST occurrence survives, keeping its position. The list is
    # oldest-first and both caps drop from the tail, so a repeat must never
    # displace the head.
    subjects, _, _ = collect(
        block,
        [
            ("First", "app/a.kt"),
            ("Second", "app/b.kt"),
            ("First", "app/c.kt"),
        ],
    )
    check("first occurrence keeps its position", subjects, ["First", "Second"])

    # Dropping the date from the batch subject is what makes repeats likely,
    # so a dated and an undated subject must stay distinct.
    subjects, _, _ = collect(
        block,
        [
            ("Update dependencies", "gradle/libs.versions.toml"),
            ("Update dependencies (2026-09-12)", "gradle/libs.versions.toml"),
        ],
    )
    check(
        "a near-miss subject is not collapsed",
        subjects,
        ["Update dependencies", "Update dependencies (2026-09-12)"],
    )

    # Subjects are compared as text: a glob must not match a different
    # subject, and quotes must not be re-split.
    subjects, _, _ = collect(
        block,
        [
            ("Fix * and ? in names", "app/a.kt"),
            ("Fix * and ? in names", "app/b.kt"),
            ("Fix anything", "app/c.kt"),
            ('He said "hi"', "app/d.kt"),
            ('He said "hi"', "app/e.kt"),
        ],
    )
    check(
        "shell metacharacters are compared as text",
        subjects,
        ["Fix * and ? in names", "Fix anything", 'He said "hi"'],
    )

    # The filters still run, and filtered commits never reach the counter —
    # so the cap continues to bound qualifying commits, not walked ones.
    subjects, qualifying, truncated = collect(
        block,
        [
            ("ci: regenerate snapshots", "app/a.kt"),
            ("docs: tidy", "app/b.kt"),
            ("Real change", "app/c.kt"),
        ],
    )
    check("prefixed commits are filtered out", subjects, ["Real change"])
    check("filtered commits are not counted as qualifying", qualifying, 1)

    subjects, _, _ = collect(
        block,
        [
            ("Housekeeping only", "SPEC.md"),
            ("Real change", "app/c.kt"),
        ],
    )
    check("housekeeping-path commits are filtered out", subjects, ["Real change"])

    # The cap bounds the WALK, and after dedup the array no longer measures
    # it: 60 commits sharing one subject leave the array at one entry, so a
    # cap reading the array would walk every one of them. Reading the counter
    # instead, the loop stops at 50 — which is what `qualifying` reports back.
    subjects, qualifying, truncated = collect(
        block,
        [(f"Subject {i // 30}", "app/a.kt") for i in range(60)],
    )
    check("the cap stops the walk at 50 qualifying commits", qualifying, 50)
    check("dedup still applies under the cap", subjects, ["Subject 0", "Subject 1"])
    # The flag is what appends "…" to the card, so a cap hit must set it —
    # under-marking is the false-complete failure PR #97 fixed.
    check("a cap hit marks the notes truncated", truncated, True)

    # And a range that never reaches the cap must leave the flag alone,
    # whatever dedup did to the list — a false "…" claims a bullet was cut
    # when none was.
    subjects, _, truncated = collect(
        block,
        [
            ("Update dependencies", "gradle/libs.versions.toml"),
            ("Update dependencies", "gradle/libs.versions.toml"),
        ],
    )
    check("a collapsed repeat does not mark the notes truncated", truncated, False)

    # An empty result is reachable and must not trip `set -u` on the array.
    subjects, qualifying, truncated = collect(block, [("ci: nothing to ship", "app/a.kt")])
    check("an all-filtered range yields no subjects", subjects, [])
    check("an all-filtered range counts none qualifying", qualifying, 0)

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
