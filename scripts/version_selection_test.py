#!/usr/bin/env python3
"""Checks which versionCode and versionName each build gets (app/build.gradle.kts).

CI's unit test, lint and screenshot runs pass -PsnoozemoFixedVersion so a per-commit
version doesn't make every commit miss the Gradle build cache; everything installed or
shipped must keep the real one. A regression either way stays invisible to the unit tests
(they never read the version) and to CI (which runs green with any version), so this runs
the real manifest tasks and reads what they merged:

- with the flag, versionCode 1, named 0.1.0-test;
- without it, debug builds are named 0.1.<count>-debug+<branch>@<sha>, the branch taken
  from GITHUB_HEAD_REF, then GITHUB_REF_NAME, then git, sanitized and cut to 30 characters,
  and release builds keep 0.1.<count>+<sha>, the count being git's commit count;
- packaging a release, as an APK or a Play bundle, with the flag set is refused.

Needs the Android SDK and a full clone, as the build job has. Run it directly:
python3 scripts/version_selection_test.py
"""

import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FLAG = "-PsnoozemoFixedVersion"

failures = []


def check(ok, message):
    print(("ok: " if ok else "FAIL: ") + message)
    if not ok:
        failures.append(message)


def gradle(*args, expect_success=True, branch_env=None):
    env = dict(os.environ)
    # The flag must come from the arguments alone, not a CI step's environment, and the branch
    # from branch_env alone, not whichever ref this run happens to be on.
    env.pop("ORG_GRADLE_PROJECT_snoozemoFixedVersion", None)
    env.pop("GITHUB_HEAD_REF", None)
    env.pop("GITHUB_REF_NAME", None)
    env.update(branch_env or {})
    # The refusal below fails on purpose; keep it out of setup-gradle's build results so it
    # doesn't suppress the job's cache cleanup.
    env["GRADLE_ACTIONS_SKIP_BUILD_RESULT_CAPTURE"] = "true"
    result = subprocess.run(
        [str(REPO / "gradlew"), "--console=plain", *args],
        cwd=REPO, env=env, capture_output=True, text=True,
    )
    if expect_success and result.returncode != 0:
        sys.stdout.write(result.stdout[-4000:] + result.stderr[-4000:])
        sys.exit(f"gradle {' '.join(args)} failed")
    return result


def merged_version(module, variant):
    manifest = (REPO / module / "build/intermediates/merged_manifest" / variant
                / f"process{variant.capitalize()}MainManifest/AndroidManifest.xml")
    text = manifest.read_text()
    code = re.search(r'android:versionCode="(\d+)"', text)
    name = re.search(r'android:versionName="([^"]*)"', text)
    if code is None or name is None:
        sys.exit(f"no version in {manifest}")
    return int(code.group(1)), name.group(1)


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True,
                          check=True).stdout.strip()


MANIFEST_TASKS = [f":app:process{v.capitalize()}MainManifest" for v in ("debug", "release")]

count = int(git("rev-list", "--count", "HEAD"))
sha = git("rev-parse", "--short", "HEAD")

gradle(*MANIFEST_TASKS, FLAG)
for module, offset in (("app", 0),):
    for variant in ("debug", "release"):
        code, name = merged_version(module, variant)
        check(code == offset + 1, f"{module} {variant} with the flag: versionCode {code}")
        check(name == "0.1.0-test", f"{module} {variant} with the flag: versionName {name}")

# Which branch a debug build names: a pull request's head branch over the pushed ref, then
# git's own answer, each with characters outside [A-Za-z0-9._/-] replaced and cut to 30.
local_branch = git("rev-parse", "--abbrev-ref", "HEAD")
BRANCH_CASES = [
    ({"GITHUB_HEAD_REF": "feature/a b+c", "GITHUB_REF_NAME": "1/merge"}, "feature/a-b-c"),
    ({"GITHUB_HEAD_REF": "x" * 40}, "x" * 30),
    ({"GITHUB_HEAD_REF": "", "GITHUB_REF_NAME": "main"}, "main"),
    ({}, re.sub(r"[^A-Za-z0-9._/-]", "-", local_branch)[:30]),
]
for branch_env, branch in BRANCH_CASES:
    gradle(*MANIFEST_TASKS, branch_env=branch_env)
    for module, offset in (("app", 0),):
        code, name = merged_version(module, "debug")
        check(code == offset + count, f"{module} debug {branch_env}: versionCode {code}")
        expected = f"0.1.{count}-debug+{branch}@{sha}"
        check(name == expected, f"{module} debug {branch_env}: versionName {name}, want {expected}")
        code, name = merged_version(module, "release")
        check(code == offset + count, f"{module} release {branch_env}: versionCode {code}")
        check(name == f"0.1.{count}+{sha}", f"{module} release {branch_env}: versionName {name}")

for module in (":app",):
    # Every way to package a release: the APK (packageRelease) and the Play bundle, both through
    # its lifecycle task (bundleRelease) and run directly (packageReleaseBundle).
    for task in ("packageRelease", "packageReleaseBundle", "bundleRelease"):
        graph = gradle(f"{module}:{task}", FLAG, "--dry-run").stdout
        check(f"{module}:checkReleaseNotFixedVersion" in graph,
              f"{module}:{task} runs the fixed-version guard")
    refused = gradle(f"{module}:checkReleaseNotFixedVersion", FLAG, expect_success=False)
    check(refused.returncode != 0 and "can't be packaged" in refused.stdout + refused.stderr,
          f"{module}: the guard refuses the flag")
    allowed = gradle(f"{module}:checkReleaseNotFixedVersion", expect_success=False)
    check(allowed.returncode == 0, f"{module}: the guard passes without the flag")

if failures:
    sys.exit(f"{len(failures)} version check(s) failed")
print("version selection verified")
