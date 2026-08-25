# Play Console declarations

Every questionnaire Play Console asks before Snoozemo can reach a track, with the
answer to give and the reason behind it. The mechanics of *getting a build up there*
— keystore, service account, secrets, upload — are in
[`play-store-internal-track.md`](play-store-internal-track.md); this file is only the
declarations.

Two rules govern everything below. **These answers must stay true of the `play`
flavor's shipped manifest**, which is the only build that reaches Play (`SPEC.md`
§3.4). `DeclaredPermissionsTest` covers four of them — `INTERNET` on `play` but never on
`direct` (Data safety), no `AD_ID` on either (Advertising ID), no typed
`FOREGROUND_SERVICE_*` permission and no service declaring a `foregroundServiceType`
on `play` (foreground service types), and the background grant on `play` but never on
`direct`. Everything else here is a
statement about the product, not something a test can hold.

**What that test reads is the `playDebug` merged manifest, not `playRelease`.**
Unit tests run on the debug build type alone here, so a permission arriving through
a release-only overlay or a `releaseImplementation` dependency would not fail it.
Neither exists today — there is no `src/release` or `src/playRelease` source set and
no release-only dependency, and the two merged manifests carry identical permissions
apart from the applicationId suffix on the auto-generated
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — so the test is faithful to what ships.
It is faithful by circumstance rather than by construction, which is why gating a
release build on the merged `playRelease` manifest is an open `TODO.md` item.

And **re-read the
Console's current wording when you submit**: Google revises these forms, and the
April 2026 removal of geofencing as an approved foreground-service use case
(`SPEC.md` §3.3) is this project's own proof that the ground moves.

## Do these first

Three things gate the rest, in this order:

1. **Host the privacy policy.** `docs/PRIVACY.md` exists but is not published
   (`TODO.md` Phase 0/6). Data safety, the store listing, and the permissions
   declaration all take a URL, and Play checks that it resolves. The recorded URL is
   `https://mikelward.github.io/snoozemo/PRIVACY.html`.
2. **Re-verify `docs/PRIVACY.md` against the shipped build** (`TODO.md`). The
   permissions it names are all declared now — location and the Wi-Fi read in the main
   manifest, background location in `play`'s — so the manifest half is done. What is
   still ahead of the build is *behavior*: it describes v1 as specified, including the
   departure detection that ends a snooze when you leave. That is built and wired on
   `play` now, though it has never run on a handset — so on that flavor the policy is
   ahead of what anyone has *seen* work rather than ahead of what ships. On `direct` it
   is genuinely ahead of the build: `DurationOnlyPresenceMonitor` is a stand-in until
   Phase 7's foreground monitor lands, so every `direct` snooze is a timer today.
   Checking that what the policy says Snoozemo keeps and does matches what each shipped
   flavor actually keeps and does is the part that has to be true on the day it is hosted.
3. **Film the demonstration video** (`TODO.md` Phase 3, steps 2–7). Nothing else
   blocks the permissions declaration, and the permissions declaration blocks
   *publishing* a bundle carrying `ACCESS_BACKGROUND_LOCATION` — on the internal track
   as much as production — plus every automated upload through the Publishing API the
   `deploy` job uses. The manual seed upload through Console is the exception, and it
   has to be, since a new listing needs one before the API path works at all
   (`play-store-internal-track.md`).

## App content questionnaires

Play Console → **Policy → App content**. Answers, and why each one is what it is.

| Declaration | Answer | Why |
|---|---|---|
| **Privacy policy** | `https://mikelward.github.io/snoozemo/PRIVACY.html` | Required for any app requesting a sensitive permission. Must resolve before you submit. |
| **App access** | All functionality available without special access | No account, no login, no gated area. Nothing for a reviewer to be given credentials for. |
| **Ads** | No ads | No ad SDK. `INTERNET` is declared on `play`, but the only thing that uses it is crash reporting (`SPEC.md` §12). |
| **Content rating** (IARC) | Utility; no user-generated content, violence, sexuality, gambling, or controlled substances | Expect Everyone. |
| **Target audience and content** | **13 and over**; not directed at children | Maintainer's answer (2026-08-24), from what has cleared review on their other listings. A Do Not Disturb utility has no content or feature aimed at kids, and leaving the under-13 boxes clear is how Play expresses "not directed at children" — which keeps Snoozemo out of the Families program without declaring an adults-only audience it does not have. That matters because `docs/PRIVACY.md` says outright that the app works the same for a user of any age; 13+ agrees with that, where 18+ would not. Note the sibling Simmo repo records 18+ for its own listing, on a rationale specific to it (future travel-eSIM commerce links). |
| **News app** | No | |
| **COVID-19 contact tracing / status** | No | |
| **Data safety** | Collects **crash logs**, **diagnostics**, and **device or other IDs**; shares nothing; all optional | See below — this is the one with substance, and it changed when Crashlytics landed. |
| **Government apps** | No | |
| **Financial features** | None | |
| **Health apps** | No | Not a health app; DND is not a health feature. |
| **Advertising ID** | Not used | No `AD_ID` permission and no analytics SDK. Crashlytics is added *without* Firebase Analytics precisely to keep this true (`SPEC.md` §12); `DeclaredPermissionsTest` fails if `AD_ID` ever appears. |
| **Foreground service types** | *Expect no section* — but read the note below before assuming | No service in the `play` build declares a `foregroundServiceType`, and the type is what Play reviews (`SPEC.md` §3.3). The merged manifest does carry the bare `FOREGROUND_SERVICE` permission, from WorkManager. |

### Open: WorkManager puts `FOREGROUND_SERVICE` in the shipped manifest

Found while pinning these answers with tests, and **not resolved here** — a
foreground-service question is a distribution decision, not an implementation detail
(`AGENTS.md`, *Play policy questions*).

The `play` release manifest merges `android.permission.FOREGROUND_SERVICE` and
`WAKE_LOCK` from WorkManager. Snoozemo's own manifests request neither, and no
service declares a `foregroundServiceType`.

`DeclaredPermissionsTest` pins the second half of that only: no typed
`FOREGROUND_SERVICE_*` permission, and no service carrying a `foregroundServiceType`.
It deliberately does **not** assert the bare `FOREGROUND_SERVICE`, since that one is
present, and it never looks at `WAKE_LOCK` — so either of those appearing or
disappearing leaves the suite green. What is guarded is the thing Play reviews; the
WorkManager pair is described here, not enforced anywhere.

The reading that says this is fine: Play's foreground-service-types declaration is
driven by declared *types*, the permission grants nothing without one, and a service
cannot start in the foreground without a type. The reading that says check anyway: a
permission visible on the store listing is a thing reviewers and users see, and
§3.3's whole argument is that Snoozemo must not end up in a foreground-service
review.

**What to do:** when you open the Console for the other declarations, look at whether
a Foreground service types section appears at all. If it does, that is the answer to
this question and it needs a decision before upload — not a form filled in on the
spot. Removing the permission with `tools:node="remove"` is possible but would need
to be weighed against what WorkManager does with it (`SPEC.md` §8's backstop schedule
runs on WorkManager), so it is not a change to make speculatively.

### Data safety, in detail

**This answer changed on 2026-08-25**, when Crashlytics landed on the `play` flavor
(`SPEC.md` §12). It used to be a flat "no data collected, no data shared", which the
absence of `INTERNET` made trivially verifiable from the manifest. That is still true
of `direct`, but `direct` never reaches Play — so the form now has to be filled in.

Answer **"Does your app collect or share any of the required user data types?" →
Yes**, then, under **App activity / App info and performance**:

| Field | Answer |
|---|---|
| Data type | **Crash logs**, and **Diagnostics** (plus **Device or other IDs** — see below) |
| Collected | Yes |
| Shared | **No** — Firebase Crashlytics processes on Snoozemo's behalf, which Play does not count as sharing |
| Processed ephemerally | No — Crashlytics retains reports (90 days) |
| Required or optional | **Optional** — Settings → *Crash reports* turns it off, which is exactly what Play means by optional |
| Purpose | **App functionality** and **Analytics** |

Declare **no Location**. That is the answer that matters most here and it is not a
stretched reading: the anchor is compared on the phone, is erased when the snooze ends,
and is not attached to a crash report — the app has no code that attaches custom keys or
breadcrumbs at all (`SPEC.md` §12's floor, `docs/crashlytics.md`). Nothing about a place,
a network, or a snooze's timing is transmitted.

#### **Device or other IDs** is declared too (maintainer, 2026-08-25)

Raised by Codex on PR #113: `docs/PRIVACY.md` states plainly that Crashlytics records
a randomly-generated installation identifier so repeat crashes on one phone can be told
apart, and Play's **Device or other IDs** category covers app-scoped installation
identifiers. Declaring only crash logs and diagnostics would then be an
**under**-declaration — the direction with real consequences, where the other direction
costs an extra row.

The maintainer's call was to declare it. That looks accurate rather than merely
cautious — `docs/PRIVACY.md` does say an installation identifier is recorded — but the
decision was made without anyone here having read Google's current wording, so treat the
row as the intended answer, to be checked against the page named below.

| Field | Answer |
|---|---|
| Data type | **Device or other IDs** |
| Collected | Yes |
| Shared | **No** |
| Processed ephemerally | No |
| Required or optional | **Optional** — the same *Crash reports* switch turns it off with everything else |
| Purpose | **App functionality**, **Analytics** |

One thing left to confirm, and it can only make the declaration *more* complete: Google's
own Firebase data disclosure page for the Crashlytics SDK —
<https://firebase.google.com/docs/android/play-data-disclosure> — is **not reachable from
the build sandbox**, so nobody working in this repo has read its current wording. Skim it
when filling in the form and add anything else it lists for `firebase-crashlytics`.

Beyond Location, these three types are the whole declaration.

#### The **Advertising ID** question is a separate one

Worth flagging because the two are easy to run together, and they currently have
different answers:

- **Advertising ID** (App content) is answered **No** for Snoozemo today. That question
  is about the Google Advertising ID and the `AD_ID` permission, and no `AD_ID` appears
  in either flavor's merged manifest — which `DeclaredPermissionsTest` checks, so it is a
  fact about the build rather than a judgment.
- **Device or other IDs** (Data safety) is **Yes**, per the row above, covering the
  Crashlytics installation identifier.

The common understanding is that Firebase **Analytics** is what brings `AD_ID` in, and
that is the reason Snoozemo's Crashlytics dependency is added without it — but that has
not been verified against Google's documentation from this repo, so confirm it rather
than relying on it. The point to carry forward is narrower and safe either way: these are
two different questions, and the answer to one does not settle the other.

**This changes if analytics is added later**, which the maintainer has flagged as
possible. Adding Firebase Analytics (or another SDK carrying `AD_ID`) would likely move
the Advertising ID answer to yes and add data types here, so it is a distribution
decision to take deliberately — `DeclaredPermissionsTest`'s `AD_ID` assertion failing is
the intended prompt for that conversation, not an obstacle to route around.

The form also asks:

- **Data deletion** — no accounts exist, so there is no account-deletion URL to give.
  Uninstalling removes everything on the phone; Crashlytics reports age out after 90
  days, and `docs/PRIVACY.md` gives a contact address for removing them sooner.
- **Encryption in transit** — **Yes**. Crashlytics uploads over HTTPS.

**Two further paths move data off the phone, and neither is collection — worth having
the answer ready rather than discovering it in a reviewer's question.** *Share debug
logs* hands a report to Android's share sheet: the user picks the destination and the
app transmits nothing, which is the user acting, not the app collecting. Android's
own new-phone transfer copies app-private data at the OS's initiative, not
Snoozemo's, and the runtime snooze record is excluded from it by
`res/xml/data_extraction_rules.xml`. Both are described plainly in
`docs/PRIVACY.md`.

**Updating the Console form is a maintainer action.** Nothing in the repo can do it,
and a build that ships crash reporting under the old "no data collected" answer is a
policy violation rather than a stale doc — so this is the one item here that has to
be done before the next `play` upload, not alongside it.

## The background location permissions declaration

Play Console → **App content → Sensitive app permissions / Permissions declaration
form**. Triggered by `ACCESS_BACKGROUND_LOCATION`, which the `play` flavor declares
because the Geofencing API cannot deliver an exit without it while the app holds no
foreground service (`SPEC.md` §3.2, §3.4).

**It applies to internal, closed and open tracks, not just production.** Until it is
filed you cannot publish any change at all, including a store-listing edit
(`SPEC.md` §3.2), and the Publishing API rejects an automated upload outright — which
is why `PLAY_SERVICE_ACCOUNT_JSON` stays out of the repo until this form is on file.
The one thing it does not block is the manual seed upload every new listing needs
first; see `play-store-internal-track.md`, which draws the same line.

The form is short: what the feature is, why a while-in-use permission will not do,
and a video link. Drafts follow — edit them into the Console's own field wording, and
keep them true of the build you are uploading.

### What is the core functionality that requires background location?

> Snoozemo turns on Do Not Disturb until you leave the place you turned it on. One
> tap on a Quick Settings tile silences the phone and captures where "here" is;
> walking away from that place turns Do Not Disturb back off automatically. Ending
> the snooze on departure is the app's single feature — there is no other reason to
> install it.
>
> The app registers one geofence around the point where the snooze was armed and
> ends the snooze on the exit transition. It runs no other location work: nothing is
> tracked when no snooze is running, no path or history is recorded, and the anchor
> point is erased the moment the snooze ends. Location never leaves the device: the
> app sends nothing but crash reports, which contain a stack trace, the device model
> and the app version, and no location data of any kind.

### Why can a foreground (while-in-use) permission not achieve the same result?

> The user arms a snooze and immediately puts the phone in their pocket. That is the
> entire use case: the app is not on screen at the moment it has to notice the
> departure, which is minutes or hours later, so a while-in-use grant expires long
> before the event it exists to detect.
>
> A foreground service would keep the app eligible, but Google's April 15, 2026
> policy update removed geofencing as an approved foreground-service use case and
> directs developers to the Geofence API for this exact pattern. The Geofence API
> requires background location. Snoozemo follows that direction: the `play` build
> runs no foreground service at all and uses geofencing as documented.
>
> Without background location the app degrades to a plain timer — it can only end the
> snooze at a preset time, which is the guessing game the product exists to remove.

### Video

Requirements, per Play Console Help: a link (YouTube preferred, or a Drive-hosted
MP4) showing the in-app disclosure, the permission prompt, and the location-based
feature working. Around 30 seconds; concise beats exhaustive.

The shot list, the permission-reset ordering it depends on, and the open question
about whether a mock location provider is acceptable are in `TODO.md` Phase 3 — that
plan is the source of truth, not this file.

What the video has to show, restated as acceptance criteria:

- The Location row in the app, and the disclosure dialog that precedes the
  **background** prompt specifically — stating what location is for, that tracking
  runs only while a snooze is armed, and that it never leaves the phone.
- The real system "Allow all the time" picker, granted.
- A snooze armed from the tile, and Do Not Disturb visibly on.
- A departure, and Do Not Disturb visibly going off with the notification saying why.

The last one is why this is Phase 3's longest-lead item: it needs a working geofenced
departure to film.

## Also standing between this and a public listing

Not declarations, but they gate the same path and are easy to discover late:

- **A personal developer account created after 13 Nov 2023 must run a 14-day closed
  test with at least 12 testers** before production access (`SPEC.md` §3.5).
  Irrelevant for internal-track-only use; it stands between this and a public listing.
- **Store listing copy** is not under version control yet — see
  `play-store-internal-track.md`. Listing text is user-facing copy and takes the
  concise-copy and US English rules.

## If the declaration is refused

`SPEC.md` §3.5 rates approval as probable but not assured, and the largest single
project risk. The mitigation is already built: the `direct` flavor holds no
restricted permission, needs no Play Services, and is a complete app. A refusal costs
distribution reach, not the product. Do not respond to a refusal by re-declaring the
foreground service — §3.3 walks through why that review is the one to expect to fail,
and an inaccurate declaration is not on the table.
