# Play Store internal testing track

CI builds a signed release AAB on every push to `main` and publishes it as a
downloadable workflow artifact. Once `PLAY_SERVICE_ACCOUNT_JSON` is
configured (see "Required secrets" below), it also uploads that AAB to the
Play internal testing track automatically, with release notes built from the
push's own qualifying commit subjects. Until that secret is added, uploading
the artifact through Play Console by hand (the "seed upload" every new Play
app needs at least once anyway, and the only path for the very first
release) is the whole publishing step.

**Do not add `PLAY_SERVICE_ACCOUNT_JSON` — the switch that turns automatic
upload on — until the Play Console declarations below are actually filed**
(Data safety, content rating, target audience, the permissions declaration
form once Phase 3/6's demonstration video is ready) **and the service
account's granted access is confirmed to be the minimum this doc asks for**
("Releases: Release to testing tracks" only — never a production-track
grant). The upload workflow itself is safe to merge without the secret: every
step it adds gates on `PLAY_SERVICE_ACCOUNT_JSON` being present, so a repo
without it still gets a green `deploy` job that builds and artifacts the AAB
exactly as before, uploading nothing.

**This isn't only a policy nicety for the automated path — the Play
Developer Publishing API this action calls enforces it as a hard
precondition** (maintainer, 2026-08-23, from direct experience elsewhere): a
release containing a declared restricted permission (here,
`ACCESS_BACKGROUND_LOCATION`, `play`-flavor only) is rejected by the API
itself if the Permissions Declaration Form isn't on file, independent of
whatever a manual Console upload lets through. So adding
`PLAY_SERVICE_ACCOUNT_JSON` before that form is filed doesn't just risk
shipping without the declaration — it makes every automated upload fail
outright, every push to `main`, until the form is submitted. That form is
gated on filming the departure-in-progress demonstration video (`TODO.md`,
Phase 3/6) — until that's done, adding the secret has no upside and a real
downside (a permanently red `deploy` job on every push).

## What gets built

`./gradlew :app:bundlePlayRelease` produces
`app/build/outputs/bundle/playRelease/app-play-release.aab`. Only the `play`
flavor ever reaches Play — `direct` is the sideload/F-Droid fallback
(`SPEC.md` §3.4) and has no Play listing. Play App Signing re-signs the AAB
with its own managed app-signing key on upload, so the upload key generated
below only authenticates to Play — it doesn't sign what testers run.

## When the build and upload run

The `Build release AAB` and `Upload signed AAB as workflow artifact` steps in
the `deploy` job (`.github/workflows/android-ci.yml`) run only when
`RELEASE_KEYSTORE_BASE64` is non-empty. A fresh repo or fork without that
secret still gets a green run — the steps are skipped, not failed.

`Compose Play Store release notes` and `Upload to Play Store internal track`
additionally require `PLAY_SERVICE_ACCOUNT_JSON` to be non-empty, and both
skip whenever `Build release notes` found nothing release-worthy in the push
(every commit was `ci:`/`docs:`/`internal:`/`refactor:`/`test:`/`tests:`-prefixed,
or every changed path was a `.md` file or a root dotfile/dotdir — AGENTS.md's
housekeeping-commit rule) — no Play-worthy "What's new" entry exists, so
shipping a release would be redundant.

The release `signingConfig` in `app/build.gradle.kts` is also only attached
when `RELEASE_KEYSTORE_FILE` is set, so a local
`./gradlew :app:bundlePlayRelease` without the env vars produces an unsigned
AAB rather than a build failure.

## One-time Play Console setup

### 1. Create the app on Play Console

https://play.google.com/console → "Create app":

- **App name**: `Snoozemo`
- **Default language**: English (United States)
- **App or game**: App; **Free or paid**: Free
- **Package name**: `app.snoozemo` (must match `applicationId` in
  `app/build.gradle.kts`)

Complete the required declarations under "App content" using the answers
recorded in "App content declarations" below.

### 2. Upload the first AAB

The first AAB for any new app must be uploaded through the Play Console UI;
there's no API path for it — automatic upload (steps 4-6 below) only works
for a listing that already has at least one release.

**Option A — let CI build it (recommended).** Add the four release-keystore
secrets from the table below and push to `main`. The `Build release AAB` step
always publishes the signed AAB as a workflow artifact called
`app-release-aab` — download it from the Actions UI.

**Option B — build locally:**

```sh
RELEASE_KEYSTORE_FILE=/path/to/release.keystore \
RELEASE_KEYSTORE_PASSWORD=<password> \
RELEASE_KEY_PASSWORD=<password> \
RELEASE_KEY_ALIAS=snoozemo \
./gradlew :app:bundlePlayRelease
```

Either way, upload `app-play-release.aab` via Play Console → Internal testing
→ Create new release, and accept Play App Signing when prompted.

Once the seed upload is done and the declarations checklist at the top of
this doc is satisfied, finish steps 4-6 below to add
`PLAY_SERVICE_ACCOUNT_JSON` and let the next push to `main` upload
automatically.

### 3. Add internal testers

Play Console → Internal testing → Testers tab → "Create email list". Send the
opt-in URL to testers; they follow it once before the app appears in their
Play Store.

### 4. Enable the Google Play Android Developer API

Go to
https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com
and enable the API on a Google Cloud project of your choice. Play Console
links itself to this project automatically the first time a service account
from it is granted access.

### 5. Create the service account

In the **same** Cloud project:

1. https://console.cloud.google.com/iam-admin/serviceaccounts → "Create
   service account".
   - **Name**: `play-publisher` (anything works — this is just for your own
     bookkeeping).
   - No roles needed at the Cloud project level. Click "Done".
2. Click into the new service account → "Keys" tab → "Add key" → "Create new
   key" → JSON. Save the downloaded JSON; this becomes the
   `PLAY_SERVICE_ACCOUNT_JSON` secret.

### 6. Grant the service account access in Play Console

Play Console → Users and permissions → Invite new users → paste the service
account email (`play-publisher@<project>.iam.gserviceaccount.com`). On the
"App permissions" tab, add Snoozemo and grant:

- **Releases: Release to testing tracks**

That is the minimum scope for an internal-track upload, and the only scope
this doc asks for — **do not grant production-track release** without a
separate, deliberate decision (`AGENTS.md`, *Play policy questions*). Save
and confirm the invite.

It can take a few minutes for the permission to propagate before the API
will accept uploads from the service account.

## App content declarations

Every Play Console questionnaire, the answer to give, and the reason behind
it now live in **[`play-store-declarations.md`](play-store-declarations.md)**,
alongside the drafted text for the background-location permissions
declaration. Short version: no ads, no data collected, no data shared, not
directed at children, and a separate permissions declaration form that gates
publishing — on the internal track as much as production — and every
automated upload through the Publishing API, until it is filed. It does not
gate the manual seed upload in step 2 above, which a new listing needs
before the API path exists at all.

## Store listing (fastlane metadata)

Not yet set up for this repo. Simmo and Type Launcher keep Play listing text
under version control as [fastlane's](https://fastlane.tools/) standard
supply layout (`fastlane/metadata/android/<locale>/`) even without running
the `fastlane` tool itself — worth adopting here too once the listing copy is
approved (`AGENTS.md`, *Translations*), so it is reviewable in PRs rather than
pasted into Play Console by hand with no diff.

Listing text is user-facing copy: US English and the concise-copy rules in
`AGENTS.md` apply, and commits touching it get user-readable subjects (no
`docs:`/`internal:` prefix).

The listing's **graphics** are version-controlled already, in
`docs/play-store/` — the 512 px app icon and the 1,024 × 500 feature graphic,
both recorded by `PlayStoreGraphicsScreenshotTest`, which draws the adaptive
icon's own layers with Android's renderer, so there is no second copy of the
mark to edit out of step with the app icon and no renderer of ours that could
draw it differently. CI regenerates them on every PR that touches the app and
fails the build if the committed PNGs are stale. Like the AAB's release notes
they go up by hand, since no API upload path is wired for them; that README
covers the Play slots and how to regenerate.

## Generating the upload keystore

Keep this keystore safe. It is only the upload credential — Play App Signing
holds the app-signing key that actually reaches devices — so losing it never
bricks the listing or blocks users from updating. Recovery is an upload key
reset in Play Console (Setup → App integrity), where you register a new
certificate and Google switches the accepted upload key over; budget a
couple of business days. Accepting Play App Signing at the seed upload
(step 2) is what keeps a lost key that cheap.

```sh
KEYSTORE_PASSWORD=$(openssl rand -hex 24)
keytool -genkeypair \
  -keystore release.keystore \
  -alias snoozemo \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=Snoozemo Release, O=Snoozemo, C=US" \
  -validity 36500 \
  -keyalg RSA \
  -keysize 2048
base64 -w0 release.keystore > release.keystore.b64
echo "Password: $KEYSTORE_PASSWORD"
```

**Store `release.keystore` and its password in a password manager before
deleting anything.** GitHub secrets are write-only: once pasted, the value
can never be read back out, so the password manager is the only copy. Then
paste the base64 contents and password into the secrets below and **delete
both `release.keystore` and `release.keystore.b64`** — the `.b64` copy is
exactly as sensitive as the keystore itself (it's the same key, just
re-encoded) and `.gitignore` only covers it as defense in depth; it isn't a
substitute for deleting it. If this is the very first AAB (step 2), keep
`release.keystore` around until the upload key is enrolled in Play App
Signing — but the `.b64` copy can go as soon as it's pasted into the secret,
since nothing further needs it.

## Required secrets

Add these in repo Settings → Environments → `production` (the `deploy` job
runs in that environment; restrict its deployment branches to `main`).

**Environment scope only — never as repository secrets.** A repository
secret is readable by any workflow run on any branch, and this repo is
public with agents pushing branches to it; an environment secret behind the
`main`-only deployment branch policy is reachable only from a `main` deploy.
Secrets are not a boundary against write access either way, which is the
other reason the value belongs in a password manager rather than only in
GitHub.

| Secret | Description |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded PKCS12 keystore bytes (`base64 -w0 release.keystore`). |
| `RELEASE_KEYSTORE_PASSWORD` | Random hex string set when the keystore was generated. |
| `RELEASE_KEY_PASSWORD` | Same value as `RELEASE_KEYSTORE_PASSWORD` (PKCS12 convention). |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore. Use `snoozemo` to match the snippet above. |
| `PLAY_SERVICE_ACCOUNT_JSON` | Full JSON contents of the service account key downloaded in step 5. Adding this is what turns automatic upload on — see the checklist at the top of this doc before adding it. |

## Release notes

The `Build release notes` step (`.github/workflows/android-ci.yml`) walks
every commit since the last release that actually published and collects the
subject of each one worth shipping, per `AGENTS.md`'s "Commit messages"
convention: `ci:`/`docs:`/`internal:`/`refactor:`/`test:`/`tests:`-prefixed
commits and pure housekeeping commits (every changed path a `.md` file or a
root dotfile/dotdir) are dropped. The surviving subjects become a
`• `-bulleted list, oldest-first, written to `whatsnew-en-US` for the
`r0adkll/upload-google-play` action to pick up — the same list Play Console
shows under the release. If nothing in a push qualifies, the upload is
skipped rather than shipping a release with no user-facing notes.

## versionCode

Play rejects an AAB whose `versionCode` is `<=` the highest already on any
track. `versionCode` derives from `git rev-list --count HEAD` (see
`app/build.gradle.kts`), which increases monotonically as long as `main` only
moves forward. CI checks out with `fetch-depth: 0` so the count isn't
truncated by a shallow clone, and `checkReleaseVersionDerivation` refuses to
build a release variant at all when it can't prove that.

## Troubleshooting

- **`The Android App Bundle was not signed.`** — the release `signingConfig`
  didn't attach. Confirm `RELEASE_KEYSTORE_BASE64` is set and the
  materialize step ran.
- **`APK specifies a version code that has already been used.`** — usually a
  shallow clone truncated `git rev-list --count HEAD`; check the checkout ran
  with `fetch-depth: 0`.
- **`Package not found: app.snoozemo`** — the listing doesn't exist yet
  (step 1).
- **`The caller does not have permission`** — the service account doesn't
  have "Release to testing tracks" on the app yet, or the invite hasn't
  propagated. Re-check Play Console → Users and permissions.
- **An upload rejected over the declared `ACCESS_BACKGROUND_LOCATION`
  permission** (wording varies by API response) — the Permissions
  Declaration Form isn't filed yet. This is a hard block on the automated
  path specifically (see the note at the top of this doc); a manual Console
  upload may not surface the same rejection the same way. File the form
  (needs the demonstration video, `TODO.md` Phase 3/6) before adding
  `PLAY_SERVICE_ACCOUNT_JSON`, not after.
