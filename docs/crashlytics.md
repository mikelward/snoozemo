# Crash reporting

Snoozemo's `play` builds report crashes through **Firebase Crashlytics**, behind a
switch in Settings that is **on by default** (`SPEC.md` §12). The `direct` flavor has
no reporter at all: it carries no Play Services dependency and declares no `INTERNET`
permission, so that build cannot send anything anywhere (`SPEC.md` §3.4).

This page is the setup, and the reasoning that is too operational for `SPEC.md`.

## What it costs

**$0/month, with no paid tier to fall off.** Crashlytics is free on every Firebase
plan, including Spark, and is not metered by crash volume, user count, or retention.
There is no threshold at which a Snoozemo-sized install base starts being billed. The
Firebase project itself needs no billing account for Crashlytics alone — adding a
*different* Firebase product later is what would change that, and adding one is a
decision, not a dependency bump.

## What it costs the user

- **Battery and data: negligible, and none of it while snoozing.** Crashlytics uploads
  on the *next* launch after a crash, not at crash time, so it adds no wake-up, no
  location request, and nothing to `SPEC.md` §9's budget while a snooze is armed.
- **Cold start: a real, small cost.** Firebase initializes from its own
  `ContentProvider` during process creation, ahead of `Application.onCreate` — so it is
  ahead of the trampoline and the tile's arm path rather than inside it, but it is
  still time added before the app's own code runs. This has not been measured on a
  device; `TODO.md` carries it as a hardware-verification item, because `SPEC.md` §4.1's
  "one tap, under a second, from a cold process" is the claim it could erode.

## Reliability

Nothing about a snooze depends on it. Every call is asynchronous, made from
`CrashReporting`'s own worker thread, and the reporter being unreachable — offline, an
outage, a build with no config — costs a lost crash report and nothing else. There is
no path from Crashlytics to arming, ending, or the duration cap, and no user-visible
failure when it is down.

## What is sent, and what is not

A stack trace, the device model, the OS version, and the app version. That is all:
Snoozemo attaches **no custom keys and no breadcrumbs**, so there is no mechanism here
that could carry the things `SPEC.md` §12's floor forbids — no coordinates, no SSID or
BSSID, no user-typed place name. The on-device debug log (`SPEC.md` §4.6) is a separate
thing and still leaves the phone only when the user shares it by hand.

**Firebase Analytics is deliberately not added.** Crashlytics works without it (the
cost is the "crash-free users" percentage in the console, not the reports themselves),
and Analytics is what pulls in the `AD_ID` permission — which would falsify the Play
"Advertising ID: not used" declaration. `DeclaredPermissionsTest` asserts `AD_ID` is
absent on both flavors, so adding Analytics fails CI rather than shipping quietly.

## Setting it up

The build activates Crashlytics **only when `app/google-services.json` exists**. It is
untracked (`.gitignore`), so a fresh clone, a fork, and every CI job except the release
build compile the SDK in but never initialize it — `CrashReporter.isAvailable` then
reports false, Settings draws no switch, and nothing is collected.

1. In the [Firebase console](https://console.firebase.google.com/), create a project
   (or open the existing one) and add an **Android app** with package name
   `app.snoozemo`.
2. To get reports from local debug builds too, add a second Android app for
   `app.snoozemo.debug`. Optional — without it, the plugin fails a debug build with
   *"No matching client found for package name"*, so add both clients or neither.
3. Download `google-services.json` and put it at `app/google-services.json`. Do not
   commit it.
4. For CI: paste the same file's contents into a repository secret named
   `GOOGLE_SERVICES_JSON`, in the **`production`** environment alongside the release
   keystore secrets (`docs/play-store-internal-track.md`). The `deploy` job writes it
   back to `app/google-services.json` before building the AAB. Without the secret the
   release build still succeeds — with crash reporting dormant, which is worth knowing
   rather than discovering from an empty console.

`google-services.json` is not a credential: it ships inside every Play build and names
the project rather than authenticating to it. It is kept out of the repo so a fork's
builds cannot file crashes into this project's console, not because it is secret.

## Verifying it

There is no way to check this from the sandbox — no emulator, and an emulator could not
answer the question anyway. On a device, with a build made from a checkout that has the
config:

1. Launch the app, confirm **Settings → Crash reports** shows the switch (its absence
   means the build had no config).
2. Force a crash, relaunch, and look for the report in the console. Crashlytics uploads
   on the launch *after* the crash, so the relaunch is required.
3. Turn the switch off, force another crash, relaunch twice. Nothing should arrive.

## How the opt-out actually works

`AndroidManifest.xml` in `src/play` sets `firebase_crashlytics_collection_enabled` to
**false**, so Crashlytics starts every process collecting nothing.
`CrashReporting.install`, called from `Application.onCreate`, reads the stored choice
and applies it. Two consequences worth knowing:

- An install where the user has opted out never begins collecting, rather than
  collecting until the app gets around to saying stop.
- A crash between process creation and that call is not reported. That is the right way
  round: an unreported crash is a missing diagnostic, a wrongly-reported one breaks a
  promise made in Settings.

Turning the switch off also calls `deleteUnsentReports()`. Crashlytics honors the
collection switch only from the next launch, so without that delete an opt-out would
leave whatever the session had already captured waiting to upload.
