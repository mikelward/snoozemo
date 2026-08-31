# Crash reporting

Snoozemo's `play` builds report crashes through **Firebase Crashlytics**, behind a
switch in Settings that is **off until the user turns it on** (`SPEC.md` §12). The `direct` flavor has
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

- **Battery and data: negligible, and never a wake-up of its own.** Crashlytics uploads on
  the *next launch* after a crash rather than at crash time, so it adds no wake-up, no
  location request, and no recurring work of its own to `SPEC.md` §9's budget.

  It does **not** follow that no upload ever happens during a snooze, and an earlier draft
  of this page claimed exactly that (Codex, PR #113). A snooze survives process death
  (§8.1), so the launch that carries a queued report can perfectly well be one that happens
  mid-snooze — a tile tap, a boot, a presence wake. The supportable claim is that reporting
  never *causes* a launch or a wake-up, not that snoozes are exempt from it.
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

**What Snoozemo puts in a report**: a stack trace, the device model, the OS version, and
the app version. It attaches **no custom keys** and logs **no custom events**.

**What the Analytics SDK adds to a report on its own**: a **breadcrumb trail**. Crashlytics
picks up Analytics' events automatically once that SDK is in the build — Firebase's
behavior, not something this app calls — so a report carries the automatic events leading up
to the crash. Since Snoozemo logs no custom events, that trail is the SDK's own:
`screen_view`, which names the single activity every install has, and its siblings. None of
them carries the things `SPEC.md` §12's floor forbids — no coordinates, no SSID or BSSID, no
user-typed place name — so the floor holds, but it now holds because *nothing that goes down
the channel is forbidden*, not because there is no channel. Adding a custom Analytics event
later would put its parameters here too, and needs checking against the floor at that point.

**What Crashlytics adds on its own**: a randomly-generated installation identifier, so
repeat crashes on one phone can be told apart, and the approximate time. The identifier is
why the Play Data Safety declaration carries **Device or other IDs** alongside crash logs
and diagnostics (`docs/play-store-declarations.md`), and it is described in
`docs/PRIVACY.md` too. It is app-scoped and is not an advertising identifier.

Keep those two lists together when either changes — an earlier version of this page listed
only the first and called it exhaustive, which made this page narrower than the privacy
policy and the compliance guidance it is supposed to agree with (Codex, PR #113).

The on-device debug log (`SPEC.md` §4.6) is a separate thing and is not part of a report:
it still leaves the phone only when the user shares it by hand.

**Firebase Analytics is here now** (maintainer, 2026-08-31), on `play` beside Crashlytics
and behind the same single consent. It collects only what the SDK collects automatically:
Snoozemo logs no events of its own and sets no custom user properties, so there is no call
site that could attach a coordinate, an SSID or a place name.

Adding it did pull `AD_ID` in, exactly as the earlier note here guessed — and
`DeclaredPermissionsTest` failed the moment the dependency landed, which is what that
assertion was written to do. The Play **Advertising ID** answer stays "not used": `play`'s
manifest removes the permission with `tools:node="remove"` and switches
`google_analytics_adid_collection_enabled` off, so Analytics reports against the
per-install app-instance ID instead. What the Data Safety form did gain is **app
interactions**, alongside the crash logs, diagnostics and device IDs it already declared.

What stays true either way is the thing that matters: **no user data leaves the device**,
and whatever does is under the user's control.

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

1. On a **fresh install**, confirm the consent card appears on the home screen and that
   **nothing has been sent** — no session in the Analytics console, no crash uploaded from
   a crash forced before answering. That is the whole guarantee, and it is the one thing
   only a device can check.
2. Confirm **Settings → Help make Snoozemo better** shows the switch (its absence means the build had
   no config), and that it reads **off** until the card is answered — including on a build
   upgraded from one where the old *Crash reports* switch was left on, which is the case
   the stored answer exists to keep separate from the stored preference.
3. Answer **Yes please**. Force a crash, relaunch, and look for the report in the console.
   Crashlytics uploads on the launch *after* the crash, so the relaunch is required.
   Analytics sessions should start appearing too.
4. Turn the switch off, force another crash, relaunch twice. Nothing should arrive from
   either service — and no new Analytics session on the launches after the opt-out, which
   is the half the crash check alone would not catch.

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

**The opt-out is made durable before this app records it.** Two independent stores
persist here — Snoozemo's preference and Crashlytics' own collection override — and the
SDK writes its override with `Editor.apply()`, which returns before the value is on disk
(verified against the SDK artifact: `DataCollectionArbiter
.storeDataCollectionValueInSharedPreferences` ends in `apply()`). Snoozemo's own store
commits synchronously. Left alone, a process death between the two could come to rest
with Snoozemo's preference durably *off* and the SDK's override durably *on* — and since
that override outranks the manifest's `false`, the next launch would initialize
Crashlytics collecting and could upload a queued report before the gate corrected it.

So the off path flushes the SDK's write to disk before returning, by committing a no-op
edit on the same preferences file (writes to one file are serialized, so waiting on ours
implies the SDK's has landed). That reaches into two Crashlytics-internal names — the
`com.google.firebase.crashlytics` preferences file and the
`firebase_crashlytics_collection_enabled` key — so it checks the key is where it expects
and logs when it is not, rather than silently flushing a file nobody reads. If a future
SDK moves them, the opt-out still applies from the next launch; what is lost is the
guarantee across a process death inside the write window.
