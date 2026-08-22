# Play Store internal testing track

CI builds a signed release AAB on every push to `main` and publishes it as a
downloadable workflow artifact. It does not upload anywhere itself yet —
uploading to the Play internal track automatically is a deliberate follow-up
(a separate PR), not this slice. Until then, uploading the artifact through
Play Console by hand (the "seed upload" every new Play app needs at least
once anyway) is the whole publishing step.

## What gets built

`./gradlew :app:bundlePlayRelease` produces
`app/build/outputs/bundle/playRelease/app-play-release.aab`. Only the `play`
flavor ever reaches Play — `direct` is the sideload/F-Droid fallback
(`SPEC.md` §3.4) and has no Play listing. Play App Signing re-signs the AAB
with its own managed app-signing key on upload, so the upload key generated
below only authenticates to Play — it doesn't sign what testers run.

## When the build runs

The `Build release AAB` and `Upload signed AAB as workflow artifact` steps in
the `deploy` job (`.github/workflows/android-ci.yml`) run only when
`RELEASE_KEYSTORE_BASE64` is non-empty. A fresh repo or fork without that
secret still gets a green run — the steps are skipped, not failed.

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
there's no API path for it (that's also what the follow-up automated-upload
PR will depend on this step having happened already).

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

### 3. Add internal testers

Play Console → Internal testing → Testers tab → "Create email list". Send the
opt-in URL to testers; they follow it once before the app appears in their
Play Store.

## App content declarations

How to answer the Play Console questionnaires (Policy → App content). These
answers must stay consistent with `docs/PRIVACY.md` and `SPEC.md` §12 —
re-check the Console's current wording when actually submitting; Google
revises these forms periodically.

- **Privacy policy**: https://mikelward.github.io/snoozemo/PRIVACY.html
- **Ads**: **No.** Snoozemo embeds no ad SDKs, shows no ads, and declares no
  `INTERNET` permission at all (`SPEC.md` §12), so there is nothing that could
  serve one.
- **App access**: all functionality is available without special access — no
  login, no gated areas.
- **Content rating (IARC questionnaire)**: utility app; no user-generated
  content, no violence, sexuality, gambling, or controlled substances; expect
  an Everyone rating.
- **Target audience**: general audience, not directed at children — a Do Not
  Disturb utility has no content or feature aimed at kids. Do not declare
  appeal to children.
- **Data safety**: **no data collected, no data shared.** Snoozemo declares no
  `INTERNET` permission, so nothing can leave the device at all (`SPEC.md`
  §3.5, §12) — the strongest form this answer can take, and the one already
  recorded as the intended answer in `SPEC.md` before this doc existed.
  Location is used on-device only, to compare the phone's current position
  against the place a snooze started; it is never transmitted, stored beyond
  what `docs/PRIVACY.md` describes, or shared with anyone. The **background
  location permissions declaration** (Play Console → App content →
  Permissions Declaration Form, `SPEC.md` §3.2) is a separate form from Data
  Safety and is Phase 3/6's own longest-lead item — see `TODO.md` — needing a
  working departure to film before it can be submitted.
- **Government app / News app / COVID-19 app / Financial features / Health**:
  No / not applicable to all of them.

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

A future PR wiring up automatic internal-track uploads will add a Google
Cloud service account (Play Android Developer API, a service account key,
and "Release to testing tracks" access granted in Play Console) and a
`PLAY_SERVICE_ACCOUNT_JSON` secret alongside these — not needed for anything
in this doc today.

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
