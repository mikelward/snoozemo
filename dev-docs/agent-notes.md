# Agent notes

Reference detail for agents working in this repo, moved out of `AGENTS.md` so that file
stays under Antigravity's 24 KB rule-file cap. `AGENTS.md` holds the rules; this holds the
how-to behind a few of them.

## Remote build environments (Cursor Cloud and Claude Code on the web)

- **JDK 21** is pre-installed. The **Android SDK** lives at `/opt/android-sdk`
  (`ANDROID_HOME`). On Claude Code on the web it is *not* pre-installed: the `SessionStart`
  hook at `.claude/hooks/session-start.sh` provisions it (cmdline-tools,
  `platforms;android-37.0`, platform-tools, licenses) at session start. The hook is
  auto-invoked only in Claude Code; under another agent, or if `/opt/android-sdk` is empty
  mid-session, run `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` rather than
  hand-installing.
- The Gradle wrapper downloads Gradle on first run; AGP installs the compileSdk minor
  platform on the first build.
- Key commands: `./gradlew assembleDebug` (build), `./gradlew test` (unit tests),
  `./gradlew lint` (lint), `./gradlew clean`.
- **`:core` has a sandbox-friendly inner build**: `cd core && ../gradlew test` runs it
  through `core/settings.gradle.kts`, which needs no `google()` — an offramp for when the
  outer build's AGP plugin markers can't resolve.
- **`mikelward/androidlog` and `mikelward/androidzoom` (text size) are published
  coordinates**, pinned in `gradle/libs.versions.toml` and bumped by the weekly
  `gradle-update` batch. Don't make either a composite build again: that puts two AGP
  versions in one Gradle invocation and fails configuration outright. To work on a library
  and this app together, `-PandroidlogLocal` / `-PandroidzoomLocal` includes a checkout at
  `.<name>/` or a sibling `../<name>`; drop the property to go back to the published
  version. The settings files carry the rest.
- **No emulator**: KVM is unavailable in the remote environments. Beyond speed, an emulator
  can't answer this app's real questions anyway — whether `setAutomaticZenRuleState`
  actually silences the device, a geofence's exit latency in the field, how One UI's
  Sleeping Apps behaves. Those need a real Pixel and a real Samsung.

## Checking the sandbox

Sandbox config drifts and new forks inherit defaults, so spend ten seconds confirming
before concluding the build can't run here: `command -v sdkmanager`, `ls /opt/android-sdk`,
and `curl -s -o /dev/null -w "%{http_code}" https://maven.google.com/` (200 / 302 / 404 =
reachable; 403 from the sandbox-egress TLS-inspection CA = blocked). If it's blocked when
`AGENTS.md` says it shouldn't be, flag it.

`developer.android.com` is reachable: `curl` it and strip tags. Its reference pages render
client-side, so an HTML-to-text fetch returns only the nav index, which reads as "the page
doesn't document that" and gets platform facts filed as unverifiable.
`android.googlesource.com` *is* blocked; `sdkmanager` offers `sources;android-36`.

## Compose test imports

Extensions need an import, members refuse one: `assertIsEnabled` / `assertIsNotEnabled` are
extensions and must be imported; `assertExists` / `assertDoesNotExist` are members and fail
to compile *with* an import. Both break before tests run, so CI shows no failing-tests
comment. Copy a sibling test's imports, or call it as a method and let Kotlin resolve it.

## Diagnosing a CI failure

Check for the failing-tests PR comment first. No comment means the failure is earlier than
tests (compile, lint, resource merge) — the `*-test-reports` artifacts also contain JUnit
XMLs only when tests actually ran, so an empty one is the same signal. The PR `build` job
builds `refs/pull/<N>/merge` — your branch *merged with main* — so reproduce with
`git merge origin/main --no-commit` before bisecting your own commits. Check whether the
failure is pre-existing on the base commit before debugging.

## Version names and the fixed test version

The versionCode is the commit count. A release is named `<count>+<sha>`, a debug build
`<count>-debug+<branch>@<sha>`, with the branch from `GITHUB_HEAD_REF` on a pull request,
`GITHUB_REF_NAME` on `main` and git locally (sanitized, cut to 30 characters).

CI's unit test, lint and screenshot runs set `-PsnoozemoFixedVersion` (as
`ORG_GRADLE_PROJECT_snoozemoFixedVersion`): versionCode `1`, versionName `test`. The
version lands in the merged manifest those tasks read, so a per-commit one made every commit
miss the Gradle build cache for all of `:app`. The release APKs and AAB use the real version,
and `checkReleaseNotFixedVersion` refuses `packageRelease`, `packageReleaseBundle` and `bundleRelease` with the flag set.
`scripts/version_selection_test.py` (a `build` job step) checks all of it against the real
manifest tasks.
