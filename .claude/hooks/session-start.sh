#!/bin/bash
# SessionStart hook: provision the Android SDK for Claude Code on the web.
#
# Remote (web) sessions start from a fresh container where ANDROID_HOME points
# at an SDK that is not yet installed, so Gradle builds, unit tests, lint, and
# Roborazzi screenshot tests cannot run until the SDK is seeded. This installs
# the command-line tools and the base platform; AGP auto-installs the compileSdk
# minor platform and a matching build-tools on the first build. Local and
# desktop environments manage their own SDK, so this is a no-op outside the
# remote environment.
set -euo pipefail

# Only provision in the remote (Claude Code on the web) environment.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# mikelward/androidlog — the shared debug log, included as a composite build by
# settings.gradle.kts, which fails evaluation without it. So this runs ahead of
# the SDK work below: whatever the SDK's state, a session without this checkout
# cannot run a single Gradle command.
#
# Refreshed every session rather than cloned once, because container state
# survives between sessions and there is no version here — "what @main says now"
# is the only correct answer.
#
# Best-effort: an unreachable GitHub keeps whatever checkout is already there and
# says so, rather than failing session startup over it.
ANDROIDLOG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/.androidlog"
if [ -d "$ANDROIDLOG_DIR/.git" ]; then
  git -C "$ANDROIDLOG_DIR" fetch --depth 1 origin HEAD \
    && git -C "$ANDROIDLOG_DIR" reset --hard FETCH_HEAD \
    || echo "androidlog: could not refresh $ANDROIDLOG_DIR — keeping the existing checkout." >&2
elif [ ! -d "$ANDROIDLOG_DIR" ]; then
  git clone --depth 1 https://github.com/mikelward/androidlog "$ANDROIDLOG_DIR" \
    || echo "androidlog: clone failed — Gradle cannot configure until $ANDROIDLOG_DIR exists." >&2
fi

ANDROID_SDK_ROOT="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-13114758}"
PLATFORM="platforms;android-37.0"

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT

# Persist the SDK location and tool paths for the rest of the session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_SDK_ROOT\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_SDK_ROOT\""
    echo "export PATH=\"\$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools\""
  } >> "$CLAUDE_ENV_FILE"
fi

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

# 1. Install the command-line tools if they are not already present. Bootstrap
#    with a pinned build just to get a working sdkmanager, then use it to
#    install "cmdline-tools;latest" into the SDK. This keeps the pin a pure
#    fallback that never needs bumping as it ages: the tools that actually end
#    up installed are always the current latest pulled fresh by sdkmanager. The
#    pinned URL is only contacted on a cold SDK, and only to bootstrap.
if [ ! -x "$SDKMANAGER" ]; then
  echo "Bootstrapping Android command-line tools into $ANDROID_SDK_ROOT ..."
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 4 --retry-delay 2 -o "$tmp/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extracted"
  bootstrap_sdkmanager="$tmp/extracted/cmdline-tools/bin/sdkmanager"
  yes | "$bootstrap_sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true
  "$bootstrap_sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" "cmdline-tools;latest" >/dev/null
fi

# 2. Accept licenses (idempotent) and seed the base platform + platform-tools
#    using the freshly installed latest command-line tools.
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "$PLATFORM" "platform-tools" >/dev/null

echo "Android SDK ready at $ANDROID_SDK_ROOT"
