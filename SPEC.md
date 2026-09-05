# Snoozemo

**Status:** Draft for review · **Date:** 2026-08-11 · **Platform:** Android (Pixel first, Samsung supported)
**Application ID:** `app.snoozemo`

This is the product and architecture spec: what Snoozemo does and *why* each design was
chosen. The phased plan and the hardware-verification list are in `TODO.md` (§15);
engineering conventions are in `AGENTS.md`. Nothing here is built yet.

Snoozemo puts the phone into Do Not Disturb **until you leave where you are right now**. One tap on a
Quick Settings tile arms it; walking away disarms it. No timers to guess at, no remembering to turn
DND back off.

---

## 1. Goals and non-goals

### Goals

1. **One tap to arm.** From the Quick Settings shade, with the phone possibly locked, in under a
   second, with zero prior configuration.
2. **Automatic release on departure.** DND ends when you actually leave, detected from Wi-Fi
   association plus location, without the user thinking about it.
3. **Never silently strand the user.** A hard maximum duration and an unmistakable "you are snoozed"
   affordance, so a failed sensor can't silence the phone indefinitely.
4. **Distributable on Google Play**, accepting that this means passing a background-location
   declaration (§3) — with a fully-functional sideload build that needs no restricted permissions as
   the fallback if that fails.
5. **Both Pixel and Samsung One UI, in that order.** Samsung is a real target, not a maybe — but
   Pixel and Play distribution lead the sequence. Where the two conflict, Pixel wins; where they
   don't, nothing should be built in a way that makes One UI support harder later.

### Non-goals (v1)

- Scheduled DND. The OS already does this well; Snoozemo is the *ad-hoc, place-scoped* case it does
  badly. The calendar is read only to offer an end time on the ongoing notification (§4.3) — the app
  never triggers itself from your calendar. Nothing watches it: no observer, no sync adapter, no
  background job, and a time already chosen does not move when the meeting does.
- Cross-device sync or accounts. Nothing about a snooze, a place, or the user's settings leaves
  the phone. The `play` flavor does declare `INTERNET`, for crash reporting and Firebase
  Analytics, both behind one consent the user has to give first (§12); `direct` declares none
  at all.
- Wear OS, tablets, foldable-specific UI.
- Automatic *arming* on arrival at a place (geofence enter). Deliberately deferred — see §14.

### The one-sentence product

> Silence your phone until you leave or your meeting ends.

---

## 2. Key decisions, and why

| # | Decision | Rationale |
|---|---|---|
| D1 | Control DND via **`AutomaticZenRule`**, never `setInterruptionFilter` | Required for apps targeting Android 15+; also composes correctly with the user's other rules |
| D2 | **Two flavors, chosen by distribution channel** — `play` uses the Geofencing API and `ACCESS_BACKGROUND_LOCATION`; `direct` uses a foreground service and no restricted permissions | Play's April 2026 policy removed geofencing as an approved foreground-service use case and directs it to the Geofence API, so the FGS route is not viable on Play (§3) |
| D3 | The presence engine is **behind one interface with two implementations** | The flavors differ only below `PresenceMonitor` (§6.1); all product behavior, DND handling, and UI is shared |
| D4 | **Wi-Fi is a suppressor, not a trigger** | Still on the anchor SSID ⇒ definitely still here (skip location entirely). Wi-Fi dropped ⇒ *maybe* left, so escalate to a location check. Never end a snooze on Wi-Fi loss alone |
| D5 | **Implicit anchor**: the tile captures "here" at arm time | Zero setup. Saved places are a later addition, not a prerequisite |
| D6 | **Three independent exits**: departure, max duration, manual | Any one sensor can fail; the phone must always come back |
| D7 | **Fail open, always** | Every ambiguous state resolves toward ending the snooze, not extending it |
| D8 | **Build the `play` flavor first, on Pixel** | Pixel and Play are the priority targets. Nothing blocks developing `play` — the declaration gates *distribution*, not local installs — so the earlier testability argument for `direct`-first does not hold |
| D9 | **Arm first, refine second** — the tile arms on tap, and a sheet then offers a time (default now + 1 h) or "until I leave" | Keeps the zero-friction one-tap path intact while making a time bound one tap away. The calendar landed instead on the ongoing notification (§4.3), leaving the arm path untouched |

---

## 3. Distribution and permissions: the decision that shapes everything

This is the section to read first, because Play policy — not Android's APIs — determines the
architecture, and the answer is less comfortable than it first appears.

### 3.1 The three technical options

| | **A. Foreground service** | **B. Geofencing API** | **C. Wi-Fi only** |
|---|---|---|---|
| `ACCESS_BACKGROUND_LOCATION` | No | **Yes** | No |
| Play Services required | No | **Yes** | No |
| Ongoing notification | **Yes** | No | No |
| Departure latency | 10–90 s (tunable) | 2–6 min | Instant, when it works |
| Battery, 4 h snooze | ~1–3% (§9) | <1% | ~0% |
| Works with no Wi-Fi | Yes | Yes | **No** |
| Survives One UI Sleeping Apps | **Best** | Poor | Poor |
| Survives reboot unattended | Needs a re-arm tap (§8.3) | Yes | Partially |
| **Publishable on Play** | **Unlikely — see 3.3** | Yes, with declarations | Yes |

C is not viable as a primary mechanism: unusable anywhere without Wi-Fi, and Wi-Fi drops for reasons
that have nothing to do with leaving. It survives as the *suppressor* half of D4, in both flavors.

### 3.2 The background-location gate (option B)

`ACCESS_BACKGROUND_LOCATION` is a Play **restricted permission**. The Permissions Declaration Form
must be completed for any active bundle carrying it, and per Play Console Help that alert applies to
releases on the **Internal, Closed, and Open test tracks**, not only production — until it is
completed you cannot publish any change at all, including store-listing edits. An internal-only
track buys **no exemption**. It asks for a written justification that background location is core to
a user-facing feature, why a while-in-use alternative will not do, and a demonstration video showing
the in-app disclosure and the permission prompt. The disclosure is a small dialog shown only before
the *background* prompt specifically — the foreground request has no dialog in front of it — stating
what location is used for, that tracking only runs while a snooze is armed, and that it never leaves
the phone (§12), before that one system prompt appears. The two platform permissions are asked for
in the sequence the platform actually honors — foreground (fine + coarse) first, then the dialog,
then background on its Continue — rather than making the user tap the settings row a second time,
because the platform will not grant background access without foreground already held. The settings
row that leads to it follows the same tri-state shape as the Do Not Disturb and notification rows
(§5.2): states the gap, offers the fix while the system will still honor it, and points at the app's
permission settings once it won't. Missing either permission never blocks a snooze — see §3.6's
fallback ladder — so the row and the disclosure are purely the repair surface, not a gate on arming.

### 3.3 Why the foreground-service route does not survive Play review

The original plan here was option A specifically to dodge 3.2. That plan does not survive contact
with the current policy.

Foreground service types are themselves declared and **reviewed** in Play Console (Policy → App
content), with a description and a demonstration video per type. The `location` type's approved use
cases are "long-running use cases that require location access, such as navigation and location
sharing" — and Android's own documentation, in the same page that defines the type, says: *"If your
app needs to be triggered when the user reaches specific locations, consider using the geofence API
instead."*

Then, in the **April 15, 2026 policy update, Google removed geofencing as an approved foreground
service use case**, explicitly directing developers to the Geofence API for it, with a 30-day
compliance window.

Snoozemo's foreground service does one thing: notice when the user leaves a place. That is
geofencing under any honest description, and it is now a named non-approved use of the type. Option
A is therefore not a way *around* the background-location declaration on Play — it is a way into a
different review that this app should expect to fail. There is no framing of it as navigation or
location sharing that is both accurate and approvable, and submitting an inaccurate declaration is
not on the table.

**A narrower foreground service is a different question, and the answer is still no — for a
different reason.** A service that does *no* location work, existing only to keep the process
resident, is not a named non-approved use of anything: it would be declared `specialUse`, which Play
reviews case by case. `location` would not apply and claiming it would be inaccurate;
`shortService` caps out around three minutes; `systemExempted` is not available to ordinary apps.

The honest justification, if one is ever needed, is **"the user has put the device into a temporary
silent state that the app has promised to end, and the service exists to guarantee the ringer comes
back"** (maintainer, 2026-08-12). That is user-initiated, user-visible and time-bounded, and it is a
materially stronger case than §3.3's, which fails on a named exclusion rather than on doubt. Written
down here so it does not have to be reconstructed later.

**Scope of the "no": not on Play, and not for v1** (maintainer, 2026-08-12) — deliberately narrower
than a permanent ruling. On the `play` flavor such a service would buy nothing the Geofencing API
does not already do; it would exist only to hold a permission-revocation watch and some retry state,
and **a permission is not spent on revocation handling**. That leaves two doors open on purpose: the
`direct` flavor has a foreground service anyway (§3.4), where none of this review applies, and a
later version may find core functionality that genuinely requires one on Play. If it does, the case
above is the one to build — not a fresh one improvised under time pressure.

### 3.4 Recommendation — **agreed**

> **Settled:** option B is the primary. Accept the `ACCESS_BACKGROUND_LOCATION` declaration and
> build on the Geofencing API. §3.5's risk assessment stands, and §6.10 covers what to do about the
> API's reliability, but the direction is no longer open.

**Two product flavors, differing only below `PresenceMonitor` (§6.1):**

- **`play`** — option B. Geofencing API, `ACCESS_BACKGROUND_LOCATION`, **no foreground service**.
  This is the shipping build for any Play track, internal included.

  > **Amended 2026-08-12** (maintainer: no strong preference, decision delegated). This bullet
  > used to read "no ongoing notification", which was the wrong thing to write down. What option B
  > buys is the absence of a *foreground service* — and therefore of the mandatory,
  > non-dismissible notification the platform attaches to one, plus the
  > `foregroundServiceType` declaration that is the actual Play-policy exposure (§3.5).
  >
  > An ordinary ongoing notification is a different object: it needs no service, no
  > foreground-service type, and no permission beyond `POST_NOTIFICATIONS`, which both flavors
  > already declare. Reading the old wording literally would have forbidden it in the shipping
  > build, and §4.2 is unambiguous that a 1×1 icon-only tile leaves **nowhere else** for the
  > countdown, the degraded-mode reason, or `End now` to live. That is not a trade worth making
  > to satisfy a phrase, so **both flavors post the ongoing notification**; only `direct` posts
  > one the system requires.
- **`direct`** — option A. Foreground service, no restricted permissions, no Play Services
  dependency. For sideloaded APKs and F-Droid, and the better build on Samsung. **Insurance, not a
  parallel product**: it exists so a refused declaration is a distribution setback rather than a dead
  project, and it should never hold up `play`.

This is not hedging. The two channels genuinely have different constraints and the divergence is
confined to one interface. But the priority is not symmetric: **`play` on Pixel is the product**, and
`direct` is the fallback that makes §3.5's risk survivable. Build `play` first (D8); keep `direct`
compiling and tested, but do not let it set the schedule.

### 3.5 Will the Play declarations be approved?

Honest answer: **probably, but it is not assured, and it is the single biggest project risk.**

Arguing for approval:

- Background location is not incidental here — it is the *entire* app. Snoozemo has one feature and
  it is location-triggered. "Why won't while-in-use work" has a clean answer: the whole point is that
  the user puts the phone in their pocket and stops interacting with it.
- Google's own policy now routes this exact use case to the Geofence API, which requires the
  permission. The declaration can say so directly, and that is a strong argument to make in the form.
- **No location the background grant produces is collected, transmitted, or monetized** (§12).
  The `play` build does declare `INTERNET`, and two things use it — crash reporting and
  Firebase Analytics, behind one consent the user has to give first. Neither carries a
  coordinate, an SSID/BSSID or a place name.

  The Data Safety form **does** declare **Approximate location** (maintainer, 2026-09-01), and
  that is not a weakening of this argument as long as it is stated precisely: what is declared
  is the coarse country Google derives from the network address an Analytics request arrives
  on, which every network request carries and which owes nothing to the location permissions.
  Nothing the geofence, the SSID read or a location fix produces reaches any off-device
  channel. So the argument does not rest on the app sending only one thing, nor now on it
  declaring no location at all; it rests on **none of the declared data being derived from the
  grant this declaration is asking for**. Most background-location rejections are about
  undisclosed collection and sharing of the location a *permission* obtained; there is none of
  that here, and the form says what there is instead of understating it.
- The in-app prominent disclosure and the permission flow are straightforward to demonstrate on
  video, and the feature is visibly the app's headline function.

Arguing against:

- Reviewers reject background-location apps at a high rate, and appeals are slow and opaque.
- A first-party alternative arguably exists — Android's own Modes can be schedule-triggered — and a
  reviewer may treat location-triggered DND as a convenience rather than a necessity.
- Personal developer accounts created after 13 Nov 2023 must additionally run a 14-day closed test
  with 12 testers before production access. Irrelevant for internal-track-only use, but it stands
  between this and a public listing.

**Mitigation, in order:** ship `direct` to your own devices immediately and prove the product works;
submit the `play` flavor to the internal track early, so the declaration outcome is known before much
is invested in polish; and if the declaration is refused, `direct` via sideload or F-Droid remains a
complete, fully-functional app — you lose distribution reach, not the app.

Given the answer "Play internal track at a bare minimum": be aware that this specifically does not
avoid either declaration, and the internal track is where the background-location declaration should
be exercised first, precisely to find out.

### 3.6 If a mechanism is taken away — the contingency

**Assume this ground moves.** Google removed geofencing as an approved foreground-service use case
in April 2026 with a 30-day compliance window (§3.3); background location has tightened at almost
every release; `specialUse` is reviewed case by case and could narrow. The realistic planning
assumption is that **any one of the three mechanisms may become unavailable within a few Android
versions**, and the app should degrade rather than break (maintainer, 2026-08-12).

It already can, and this is the point worth internalizing: **the contingency is the fallback ladder
the app runs anyway** (§6.10, D7), applied permanently rather than per-snooze.

| What is lost | What still works | What the user sees |
|---|---|---|
| Geofencing (or the FGS behind it) | Wi-Fi suppressor + duration cap | `Snoozing · ends when you leave Wi-Fi, or in 3h 40m` |
| Wi-Fi as well | Duration cap alone | `Snoozing · ends in 3h 40m` — an honest timer |
| — | The cap is the floor and cannot be taken away | The phone always comes back |

Three properties make that ladder hold, and each is a constraint on future work rather than a
feature to be added later:

1. **The duration cap is not a service and not a permission.** It is one
   `AlarmManager.setAndAllowWhileIdle`, which needs no declaration and no review. Every mechanism
   above it can be withdrawn and the phone still comes back on time. Nothing may be built that makes
   the cap depend on a service staying alive.
2. **The release obligation never lives *only* in a process.** Escalation is alarm-first,
   in-process second, precisely so that losing the right to keep a process alive costs latency, not
   the exit itself (§8).
3. **`PresenceMonitor` is the seam** (§3.4, §6.1). A withdrawn mechanism is an implementation swap
   below one interface, not an app rewrite — which is also what keeps the two flavors from diverging
   anywhere else.

So the plan on losing a mechanism is: drop to the next rung, say so in the ongoing notification
(§4.3 — a degraded snooze must never look like a tracked one), and ship. The product gets less
clever; it does not stop working, and it never gets less safe.

### 3.7 How a build reaches a tester

**The Play internal track is the channel this repo is building toward, and Firebase App
Distribution is deliberately not a second one.** Today `deploy` builds the signed AAB and publishes
it two ways — as a workflow artifact, and attached to a **GitHub prerelease** named by its
`versionCode` — while the internal-track upload step is wired but gates on
`PLAY_SERVICE_ACCOUNT_JSON`, which stays unset until the Play Console declarations are filed
(`docs/play-store-internal-track.md`), so a build currently reaches a tester through a hand seed
upload. **Neither artifact is published on every push, and the prerelease answers "what is the
latest release-worthy build" rather than "what is at the tip of `main`"** (Codex, 2026-09-03) — the
two diverge exactly when the commits since the last release carry no user-visible change, which is
what a reader doing the seed upload has to know. Which pushes produce which artifact is CI
mechanics and lives in `docs/play-store-internal-track.md`, not here. The prerelease is what makes
the hand seed practical rather than a scramble: the artifact expires and is reachable only from its own
run, so "which build shipped, and where is it" had no durable answer. It is not a second distribution channel — nobody installs an AAB — but a permanent,
linkable record of what shipped, carrying the same "What's new" text the Play card will. Its
condition is the Play upload's without the service account, which is a strict subset, so the
invariant runs one way: every Play upload will be preceded by a prerelease, never the reverse, and
this repo's present keystore-only state is exactly the case that buys. For that record to answer
"which build is the latest release-worthy one" it has to be read top-down, so a build whose deploy runs late — the shared
queue is not ordered by push — publishes nothing rather than landing an older `versionCode` at the
top of the list. **Nothing**, not just no prerelease: it stands its Play upload down too, since Play
accepts an older bundle whenever the newer run's own upload skipped or failed, and a Play release
ahead of the newest prerelease is the invariant above running backwards. The `direct` flavor of §3.4 does not ride this channel at all — it is sideloaded today and
F-Droid is its intended path at scale — since its whole point is a route Play does not gate.

App Distribution is the obvious second channel and was rejected on the sibling repos' evidence
rather than in the abstract: clothescast, Simmo and Type Launcher all ran it *alongside* the
internal track, both published on every push to `main`, and both reached the same testers — so all
three have since retired it. What it cost was a second console to keep in sync, a second signing
identity (a stored debug keystore, so tester installs upgraded in place rather than colliding), and
its own secrets, in exchange for a copy of a release that was already going out. What it bought was
speed: App Distribution delivers in seconds where the internal track takes minutes to hours of Play
caching. That is the trade to re-open if internal-track latency ever becomes the thing holding
testing up — and only then; a duplicate channel is not free, and the one being duplicated is also
the route to alpha, beta and production.

**The release build goes through R8, with shrinking, optimization and obfuscation all on.**
`isMinifyEnabled` and `isShrinkResources` are on for the release build type on every machine,
not only in CI, and the debug build never runs R8 at all (see below for why not). Gating
minification on `CI=true` was the earlier decision and it was wrong: the release build is the
artifact that ships, so it has to be the artifact anyone can reproduce. A local release build
that skipped R8 meant the one build worth smoke-testing was the one nobody could smoke-test,
and it hid exactly the defects R8 introduces — reflection, serialization, and the enum
constant names this app round-trips through persistence and a reboot.

Full R8 is a **distribution requirement, not a size preference**. From February 2027 Play
requires a minimum of 25% coverage across *optimization, shrinking and obfuscation*, measured as
DEX code optimization, and an app under the threshold loses store visibility and publishing
capability. A shrink-only run — `-dontoptimize -dontobfuscate`, which is where this started —
leaves two of those three dimensions at zero, so it was never an option for the `play` build.
`direct` runs the same configuration: a second pipeline would be a second set of failures to
find, and F-Droid does not object to an optimized build.

What that costs, stated plainly because it is a real loss:

- **Crash traces arrive obfuscated.** On `play` they are readable only if the Crashlytics
  mapping file is uploaded with the build. On `direct` there is no crash reporter at all (§12),
  so a trace from a sideloaded build — in a logcat, in a user's bug report — cannot be
  de-obfuscated by anyone. That is the price of the flavor's independence, not a defect to fix.
- **The optimizer and obfuscator can break code the shrinker alone would not**, and it is
  reflection they break: anything resolved by name at runtime. Three stores here persist an enum
  by `name()` and read it back with `valueOf` — a renamed constant would make a snooze record
  written by one build unreadable by the next, which is D7's failure rather than a cosmetic one.
  `app/proguard-rules.pro` keeps those names, and the enums, serializers, worker class name and
  manifest components are verified against R8's mapping on every variant.

**The pull-request build job builds the release APKs, and that is where all the R8 coverage
is.** The debug build deliberately does not run R8: AGP disables optimization *and* obfuscation
for any debuggable build, so minifying it could only ever run the shrinker — a strict subset of
what the release variants run anyway, bought at the cost of a slower build and an AGP warning.
Everything that can actually break under R8, and everything Play measures, lives in the release
variants; building them on every pull request is what keeps the post-merge `deploy` from being
the first run of the code Play receives.

Worth knowing what this is and is not worth in bytes. The release APK drops by around 90%
against an unminified build — but nearly all of that is the shrinker, and against a shrink-only
run the **AABs barely move at all**, within about 2% and marginally larger. Optimization and
obfuscation are here because the threshold requires them, not because they save space.

---

## 4. User-visible behavior

### 4.1 States

```
        ┌─────────┐   tile tap / arm      ┌─────────┐
        │  IDLE   │ ────────────────────► │ ARMING  │  acquire fix + SSID (≤10 s)
        └─────────┘                       └────┬────┘
             ▲                                 │ anchor captured
             │                                 ▼
             │                            ┌─────────┐
             │  ◄──────────────────────── │  ARMED  │ ◄──┐ DND on, rule state TRUE
             │   departure / cap / tile   └────┬────┘    │
             │                                 │ Wi-Fi lost, or motion + fix
             │                                 ▼         │
             │                            ┌──────────┐   │ back inside / re-associated
             │  ◄──────────────────────── │ CHECKING │ ──┘
             │   confirmed outside        └──────────┘
        ┌────┴────┐
        │ RELEASED│  DND off, brief toast/notification "Snooze ended — you left Home"
        └─────────┘
```

`ARMING` failing to get a fix within 10 s does **not** block: the snooze arms in Wi-Fi-only mode if
connected to a network, or in duration-only mode if not, and says so in the notification. Arming
must never feel slow or refuse.

### 4.2 The tile

- **Icon:** a `Zz` glyph. Quick Settings icons are 24 dp single-color vector drawables, tinted by
  the system — a clock-with-zzz has too much detail to read at that size and would turn to mush once
  flattened to one color. A bold two-character `Zz` mark, or a crescent moon with a single `z`, is
  the most legible option that still says "snooze".
- **Inactive:** label `Snooze here`, no subtitle.
- **Active:** label `Snoozing`, subtitle `Home · 3h 40m left` (`Tile.setSubtitle`, API 29+), plus
  `Tile.setStateDescription` for TalkBack.
- **Tap while inactive:** arm. **Tap while active:** end the snooze immediately (D6).
- **Long press:** opens the app, via an activity registered for
  `android.service.quicksettings.action.QS_TILE_PREFERENCES`.
- **Locked device:** arming works locked — no `unlockAndRun()` wrapper. The whole point is a
  one-tap action from the shade. Ending also works locked. Only the settings screen requires unlock.
- **The tile tracks the snooze, not just its own taps.** A snooze can start or end with no tile tap
  behind it — the duration cap firing, `End now` or `+30 min` on the ongoing notification, a release
  from the app screen — and the notification the user taps sits in the same shade as the tile, so
  the tile has to change with it rather than at the next time the shade is reopened.

**The tile stays a *passive* tile** (2026-08-24). Android offers an opt-in "active" mode whose whole
purpose is letting an app push a tile update at will, and Snoozemo deliberately does not take it:
an active tile is bound only when it asks to be or when a tap needs delivering, and the shade-open
bind is what recomputes the countdown subtitle from the record and what warms the zen rule while a
tap may be a moment away (§4.1). Giving up a live countdown and a warm arm path to gain a push is
the wrong trade for this product. The consequence is that the platform's own
`requestListeningState` push — which the API documents as doing nothing for a tile that has not
opted in — is unavailable, so a state change reaches an on-screen tile in-process instead: `:tile`
and `:app` are one process, and a listening tile is a live object in it.

**The app screen keeps a snooze button of its own, and the tile is still the way in** (maintainer,
2026-08-13). The two are not competing front doors. The button exists so the app is self-sufficient
— a user who never adds the tile has an installed app that can still do the thing it is for, rather
than one that cannot do anything with no route back — and it replaces the Phase 1 debug control
rather than disappearing with it. But the tile is easier and is *where people already go to silence
a phone*, which is the behavior this app attaches to rather than one it has to teach; so the screen
should push toward the tile rather than presenting a symmetrical choice. Note what the button can
never be: one tap from the shade with the phone locked (§4.1). It is a fallback and an
onboarding-time convenience, not a second primary path.

This **revises** the line below rather than sitting beside it: "the tile is the arm affordance" was
written when the tile was the only one, and it stays true as a statement of which path the product
leads with — it is no longer true as a statement that the tile is the *only* way to start a snooze.

**The app is four screens, not one** (`TODO.md` Phase 4, landed 2026-08-23; the fourth,
`LicensesScreen`, arrived later — §4.7). `MainScreen` is the
tile-equivalent Arm/Release control: the app's title, a banner for the one required-and-missing
capability (Do Not Disturb access — nothing on this screen can arm without it), the tile banner
below, and the Snooze/End snooze/Settings controls — **exactly one of Snooze and End snooze at a
time**, split on a confident "nothing is running" rather than on a confident "something is". End
snooze is the one guaranteed way back to a ringing phone (§7), so it disappears only where the
screen has actually read the record and found nothing; while that reading is still unknown it
stays, and Snooze — which could otherwise arm over a snooze the screen has not seen, costing the
user the deadline they were promised — is the one that waits. `PermissionsScreen` is the interstitial that
carries the DND, notification and location setup rows — reached automatically the first time DND
access reads as missing (so a fresh install lands there rather than on a screen whose Arm button is
disabled with nothing yet explaining why), and from `SettingsScreen`'s Permissions entry any time
after. Only that first reading routes automatically; a later revocation mid-snooze surfaces on
`MainScreen`'s banner instead (§8.2's own recovery path), so losing access never yanks the user off
whatever they were doing. `SettingsScreen` holds everything touched rarely — usually never: the
permanent tile row, the debug-log switch, the Permissions entry, and a Filters row that deep-links
straight to the system's own screen for editing which calls, messages, alarms and apps break
through Snoozemo's rule — previously reachable only by finding the rule in system DND settings by
hand (landed 2026-08-23). Filters is hidden, not shown disabled, whenever Do Not Disturb access
isn't granted or the rule doesn't exist yet — a button that opens to nothing is worse than no
button. None of the rows is gated behind another being resolved first — leaving any of them is
always one tap, on the same "fail open" principle the duration cap itself follows (D7): a setup
flow that cannot be left
without finishing it is a trap, not onboarding. `LicensesScreen` is a leaf off `SettingsScreen`'s
foot (§4.7), and the only screen reached from exactly one place — which is why Back there returns
to Settings rather than to `MainScreen`.

**A welcome flow precedes the permissions screen on a fresh install** (maintainer, 2026-09-05; not
yet built — `TODO.md` Phase 6, sketched with its copy in `TUTORIAL.md`). The route above — a fresh
install lands on `PermissionsScreen` — put three permission rows in front of someone who had not
yet been told what the app is for or how it is used, and the tile, which is the whole product, is
invisible until someone adds it. So a short run of fixed cards comes first, each one idea: what the
app is; how a snooze ends; the tile; the notification's actions; the one Do Not Disturb rule and
the ringer choice (§5.9); and, last, the crash-report and analytics consent (§12) with a mention of
the on-device debug log (§4.6). Each card offers the grant for the thing it just introduced, drawn
as the same tri-state rows `PermissionsScreen` uses (§5.2), and `Next` never waits on one — the
rows' own fail-open rule. `PermissionsScreen` then follows only when a permission is still missing,
as the recap, and its once-only routing stays as the backstop for an install that skipped the
flow; a user who allowed everything on the cards lands on `MainScreen`. The flow is shown once, on
a persisted flag, and replayable from a `Tutorial` button on `MainScreen` — the person who needs it
again is on the home screen wondering what to do, not in Settings. The shape is decided; the words
are not: nothing is a string resource until the maintainer has seen the copy (`AGENTS.md`,
*Translations*), and until the flow lands the fresh-install route above is unchanged.

**Both `SettingsScreen` and `MainScreen` carry the update banner** (landed 2026-08-23, extended
to the home screen 2026-08-30, `play` flavor only —
§3.4's `direct` flavor is never distributed through Play, so it has nothing to check for): when
Play has a newer Snoozemo waiting, a card offers to fetch it, tracks the download, and then offers
the **restart** that installs it. Always the *flexible* kind — background download, install on a
restart the user chooses — never the immediate kind, which would take the screen over mid-snooze.
Dismiss silences that build and only that build, and only while there is still something to tap:
once the update has downloaded, Restart is the only way to finish it, so the banner drops Dismiss
rather than let a tap strand a fetch already paid for. The check asks the installed Play Store app,
so it sends nothing of the user's anywhere and needs no permission of its own.

It sits on the home screen for the same reason the crash banner does: which screen the user lands
on is not something the feature should have to reason about, and `MainScreen` is the one they land
on by default — an update offered only behind Settings is offered only to whoever was already
going there. Below the tile banner rather than above it, because a missing tile blocks the
product's whole first impression while an update is worth acting on with nothing broken meanwhile.

The screen leads with a **banner** urging the tile, dismissed **once and forever**, above a tile
**entry that is permanent** (maintainer, 2026-08-13) — the banner lives on `MainScreen`, the entry
on `SettingsScreen`, in different screens now that the split above landed, but the relationship is
unchanged. Those go together: a banner that can be sent away for good is only safe because the entry
outlives it, and an entry that were itself conditional would put the user back in the dead end where
saying no once costs them the tile. The entry states the tile's state either way and offers to add
it only while it is missing — once the tile is there it is a statement, not an offer, because there
is nothing left to create. Its dismissal is not re-raised when the tile is later removed; the entry
is the standing route, so the banner never has to ask twice.

`SettingsScreen`'s foot carries the app version, a link to the hosted privacy policy, and the
app's own mark beside every screen's title (landed 2026-08-23) — the same shape as the sibling
Simmo repo's own Settings screen, so a user who has met one meets the same footer in the other.
None of these are settings to change; they are where the app states what it is and points at its
own policy, same as the tile row above states rather than offers once nothing is left to do.

**The tile is the arm affordance; the notification is the status surface** (maintainer,
2026-08-11). The maintainer runs the tile in its **1×1 form**, which shows the icon alone — no
label, no subtitle — so icon-only is the *expected* presentation here, not a degraded edge case.
One UI may not render `Tile.setSubtitle` at all (§10) and what does render is truncated hard, but
those are secondary: the primary case already shows nothing but a glyph.

So **nothing the user needs to know may live only on the tile.** The place, the countdown, the
reason a snooze degraded, and the way to extend or end it all live in the ongoing notification
(§4.3), which has room, is where the user is already looking, and renders the same on every device.
The tile still sets its label and subtitle for the presentations that show them; it just isn't
load-bearing. This is what principle 2 ("never fail silently") depends on in practice, and it means
the fallback of folding the remaining time into the tile label (§10) is a nicety, not a fix.

**`MainScreen` mirrors the same two facts while the app is open** (landed 2026-08-23): the mode
(`Ends when you leave` / `Wi-Fi only` / `Wi-Fi lost — ending soon` / `Timer only`) and the remaining time, reusing the exact
copy the notification and the tile already render rather than a third phrasing for the same state.
Not a live per-second countdown, unlike the notification's own chronometer — it repaints on a
record change and on a once-a-minute tick while the activity is visible, which matches the
display's own granularity (`Xh Ym left`) rather than repainting faster than the text can change. A
per-second clock here too would just be one more thing to keep in sync with the notification's for
no benefit the user doesn't already have. The record's own place name is left out of it, on
purpose: it is always literally `"Here"` today (saved/named places are unbuilt — `TODO.md`, "Saved
places"), and the notification doesn't show it either, so surfacing it here first would only read
as filler.

**Including the degraded reason, on the same line and in the same words** (landed 2026-08-30). The
mode line joins its cause exactly as §4.3's card does — `Timer only — no location` — from the same
mapping, so the two surfaces cannot drift into phrasing one snooze two ways. Reason enough on its
own: the ongoing notification is swipeable, silenceable by the user's own channel settings, and
sometimes simply not the surface they opened, so a screen that showed only `Timer only` would read
as a setting somebody chose rather than as the thing that went wrong. The same three causes earn a
line and the same modes append it — `FULL` carries no cause by construction, `WIFI_GRACE` already
names what matters — which is stated once in §4.3 and holds here.

**And it says so when no snooze is running** (landed 2026-08-25): the same line reads `Not
snoozing`. Idle used to render as an empty gap, which made "nothing is running" and "the record
hasn't been read yet" look identical — the only thing separating them was whether the Snooze
button happened to be enabled, which is principle 2's failure in miniature: the app's actual state
knowable only by inference. The unread case keeps the gap, deliberately: an idle claim over a
snooze this screen has not finished reading is the one wrong thing the line could say, and it is
the wrong direction to be wrong in.

**The active tile inverts, and that is the platform's doing, not ours.** A Quick Settings tile
cannot specify a background: the system draws it from `Tile.state`, so `STATE_ACTIVE` while a
snooze runs gives the same light-when-off / dark-when-on treatment as the system's own Do Not
Disturb tile (maintainer, 2026-08-12). Matching it is deliberate — a user reading the shade for
"why is my phone quiet" sees the two tiles agree — and it is free, so nothing here is worth
hand-rolling. What it costs is a constraint on the icon: the system tints the glyph to contrast
with whichever background it just drew, so the *same* asset is rendered dark-on-light and
light-on-dark, and it has to hold up both ways.

Two consequences for the icon itself. It carries the armed/inactive distinction alone, so the
tinted-silhouette treatment (§4.2's icon note) has to read at a glance in both states with no text
beside it — and in both tints, which is the sharper half of that requirement, since a mark can
survive one and fill in on the other. And the countdown is *only* in the notification **as ambient
status** — visible without the user doing anything, which is what a status bar icon and an ongoing
card both are. `MainScreen` mirrors the same two facts now (landed 2026-08-23, above), but only
while the user has already opened the app to look; a snooze whose notification the user has never
granted permission for still has no *ambient* status at all — nothing on screen, nothing in the
status bar — until they think to check. That gap is why `POST_NOTIFICATIONS` is requested rather
than merely declared (§5.2), not the mere existence of a countdown somewhere in the app.

### 4.3 Notification (while armed)

Channel `snooze_active`, `IMPORTANCE_DEFAULT`, ongoing, not dismissible while the service runs.

```
🌙  Snoozing                          3:40:12
    Ends when you leave
    [ End now ]   [ +30 min ]   [ Until 17:00 ]
```

`+30 min` matches the sheet's step (§4.4), so extending uses the same mental unit as choosing.

**The third action is the next meeting's end, and it is there only when there is one worth
offering** (maintainer, 2026-08-31). The idea is the case the app is most often used for: a snooze
taken *for* a meeting should be able to end when the meeting does, without the user working out
the time and stepping to it. Snoozemo reads `READ_CALENDAR` for event **end times only** —
bounded by the snooze's own cap, since nothing past it can be offered — and shows the earliest end
that is both later than the 30-minute floor and earlier than the cap. Anything else is a button
that can only fail or that would change no deadline at all, and either is worse than two buttons
that work.

**Absent, never disabled, and never a promise.** No calendar permission, no meeting, nothing
inside the cap: the card carries its usual two actions and says nothing about a capability the
user did not ask for. That is also why this is the one permission whose absence costs a single
action rather than a mode — it is stated on the permissions screen as a gap in what is *offered*,
not a degraded snooze.

**The time is formatted by the phone, not by Snoozemo**, through the same helper the sheet uses —
so the button reads `17:00` or `5:00 PM` exactly as the rest of the device does, and the two
surfaces cannot render the same instant two ways.

**The read happens after the card is posted, never before it.** A `ContentResolver` query into
another app's provider is precisely the kind of call §6.9 keeps off the arm path, so the
notification goes up with the two actions it has always had and gains the third a moment later if
there is one — the same show-now-fill-in-later trade every screen here makes. The answer is cached
against the snooze's identity *and* its current cap, so a `+30 min` re-asks and a card reposted on
every state change does not.

**The action carries the snooze it was offered for, and the service declines a claim that no
longer matches** (§4.4's identity check). A notification is exactly where that matters: the card
sits in the shade while the phone is in a pocket, and a snooze that ended and was replaced in
between must not take a time chosen for the previous one. A refusal this action meets — an offered
time that has since fallen inside the floor — is said in the shade, because unlike a sheet row it
has nowhere to say it inline.

Tapping the card itself, rather than one of its two actions, opens `MainScreen` (landed
2026-08-23) — the notification is the one thing a snoozing user is already looking at, so it is
also the natural route back into the app for anyone who wants more than `End now` or `+30 min`.

**Tap-to-open is a plain foreground activity launch, not routed through the trampoline the two
actions use.** `End now` and `+30 min` fire `PendingIntent.getService` indirectly through
`TileTrampolineActivity`, because a background service start the platform or an OEM refuses is
*consumed silently* — no app code ever learns it was refused — and the trampoline is what notices
and recovers. An activity launch from a notification tap carries no such refusal case: the platform
always honors it, so there is nothing here for a trampoline to catch.

**The countdown is the platform's chronometer, not text Snoozemo formats.** A notification is only
rebuilt on a state change, so a remaining time written into the body is the value it had when the
snooze was armed — still reading `8h 0m left` seven hours later, which is worse than showing nothing
because it looks current. `setUsesChronometer` against the absolute cap ticks by itself and cannot
go stale. It also means the body says only what *kind* of snooze this is — `Ends when you leave`,
`Wi-Fi only`, `Wi-Fi lost — ending soon`, `Timer only` — which is the part that actually needs words.

**A degraded card names the reason, not just the mode** (maintainer, 2026-08-30). The mode alone
cannot carry it: `NO_LOCATION_FIX` and `FIXES_TOO_VAGUE` collapse to the same mode and mean
opposite things to a user — location is broken, versus location is working and simply cannot place
you where you are standing, which is not a fault at all. A line that reads identically either way
is the degraded report failing at the one job §8.1 gives it. So the reason is appended to the mode:
`Timer only — weak location signal`.

Four causes earn a line — `location is off`, `no location`, `weak location signal`, `background
location off`. `NOTHING_WATCHING` does not, and that omission is deliberate: it is the app's own
wiring, not anything the user did or can act on, and `Timer only` already says everything true
about it. `WIFI_GRACE` is excluded as a mode for the same kind of reason: `Wi-Fi lost — ending
soon` already names what matters, and a second clause on a state that resolves in minutes costs
length for nothing.

**`NO_LOCATION_IN_BACKGROUND` was the fifth, and its silence was reversed** (maintainer,
2026-08-30). It had been held back on the rule that naming a state without its way out is worse
than the mode alone — this one recovers only through a `Resume tracking` affordance no UI offers
yet. What that reasoning missed is that *naming the missing permission is itself most of the way
out*: a user told `background location off` knows what to grant, where `Timer only` alone tells
them nothing and reads as a choice somebody made. The rest of the way out is still owed, and
honestly so — granting the permission does not restart tracking until `Resume tracking` exists
(`TODO.md`), so the line is a true diagnosis with an incomplete remedy rather than a false one.

The reason travels **on the snooze record**, not beside it, because the card is reposted from a
restored record after every process death — a reason held only in memory would come back missing
while the mode it explains came back intact, which is the exact half-told state this exists to end.
It follows that a *cause* change is news even when the mode does not move; the alternative leaves
the card asserting the wrong reason until the mode happens to change.

**This card only alerts once.** It is reposted on every ARMED/CHECKING transition while a snooze
runs — routine presence re-checks, not events the user needs alerted to — and the channel bypasses
Snoozemo's own Do Not Disturb (§5.7), so a repost that re-sounded or re-vibrated on every re-check
would be genuinely noisy rather than merely a latent one DND used to catch by accident. Only the
first post of the card alerts; later ones update the countdown and text quietly.

**Not `IMPORTANCE_LOW`, deliberately** (maintainer, 2026-08-12) — this started low, on the reasoning
that an ongoing status card should stay out of the way, and that was wrong for this particular card.

Low importance groups it, which costs two things this notification cannot afford: it is **less
discoverable**, and both `End now` and `+30 min` sit behind an extra tap to expand the group. That
is the wrong shape for a product whose whole claim is one tap to arm and one tap to end. It also
has real work to do beyond looking tidy — it is what tells the user the snooze **is** working, and
what explains why their other notifications have gone quiet. A card that answers "why is my phone
silent?" is worth nothing filed where it isn't found.

The usual cost of raising importance doesn't apply here either: during a snooze the premise is that
the user **isn't looking at their phone**, and the phone is in Do Not Disturb regardless, so a more
prominent card interrupts nothing. The trade is discoverability against adjacency to the system's
own entry, and discoverability wins.

One practical consequence: **a channel's importance is fixed at creation.** Once the user can see a
channel the level is theirs, and later calls with a different value are ignored — so a device that
already ran a build at the old level keeps it until its app data is cleared. Pre-release that is a
testing note and nothing more. After release the same change would need a new channel id, and by
then leaving existing users where they are is the *correct* behavior, not a workaround.

Endings and failures are unaffected: they stay on `snooze_ended` (`IMPORTANCE_DEFAULT`), which they
already were.

**The status bar carries the mark for free.** An ongoing notification puts its small icon in the
status bar, so the tile's glyph is on screen for the whole snooze — a persistent, always-visible
"this is why your phone is quiet" that costs nothing extra and survives the shade being closed.
That makes it a third place the same 24 dp silhouette has to read (§4.2's tile, both tints, and
here), at the smallest size of the three and tinted to a single color by the system. It is also the
one status surface that disappears entirely if `POST_NOTIFICATIONS` is denied, which is the other
half of why the permission is requested rather than merely declared (§5.2).

Endings and failures are not part of that question: they stay on `snooze_ended`
(`IMPORTANCE_DEFAULT`), because those have to be *noticed* rather than filed.

The cost is that the silent section collapses, so the countdown and `End now` / `+30 min` may need
an expand to reach. On a 1×1 tile that carries no status (§4.2) this notification is the only place
the snooze is legible, so how much survives collapsing is a real question and a device check
(`TODO.md`) — including whether the shade ever bundles the two cards together.

### 4.4 Choosing an end condition

> **Status: provisional.** The direction — arm instantly, refine in a sheet — looks right, but the
> specifics are not settled. Treat the mockups as a starting point, not a spec.

"Until I leave" is the thesis, but it is not always the *best available* answer. If you are in a
meeting that ends at 14:00, "until 14:00" is sharper than "until I walk out" — you might not walk out
for another hour. So the sheet offers a time as well as a place, without taxing the common case.

**The rule (D9): the tile arms immediately with a sane default, then shows a sheet that refines it.
Dismissing the sheet — or never seeing it — leaves you correctly snoozed.**

The trampoline activity (§6.9) already sits on the arm path. It starts the service first, then
renders a compact bottom sheet. Arming never waits on the UI, so the one-tap path survives.

**Every way of arming offers the sheet, not just the tile** (2026-08-30). It was the tile's alone,
so the same action asked when it came from the shade and silently took the default cap when it came
from the app screen's `Snooze` button — a split the user has no way to predict and no way to see.
Both now drive one shared flow, so the setting, the times offered, and what a refusal does cannot
drift apart between them.

What each surface keeps is only what genuinely differs. The tile arms from a transparent activity
with nothing behind it, so it draws its own scrim; the app screen has a real screen to sit on and
uses the platform's modal sheet. And the two learn the snooze's cap differently — the tile loads the
record, the app screen already keeps it warm — which is why the ceiling is supplied to the flow
rather than read by it: neither surface may put a disk wait in front of the sheet.

The app screen also cannot decide at the moment of the tap, because the service has only just been
asked to arm and the record that says what cap to offer against does not exist yet. It waits for the
next record it reads — which it reads off the main thread anyway — and opens the sheet on the first
frame it can be honest on. A cap already inside the floor still offers nothing (§7's `MIN_CAP`), on
either path.

#### v1

```
    🌙  Snoozing at Home

        ⏰  until 14:00          [ − ]  [ + ]
        📍  until I leave

        Ends when you leave, either way.

                                        [ OK ]
```

- **Off by default, behind `Ask when to unsnooze` in Settings.** One tap from the shade with
  nothing in the way is goal 1, and the sheet — however cheap — is something between the tap
  and getting on with what you were doing. A user who wants to be asked says so once; a user
  who doesn't never has to. So on a default install the trampoline still draws nothing at all
  and finishes as soon as the service start is queued, exactly as it did before the sheet
  existed. This inverts the debug log's own default (§4.6) on purpose: that one is off-by-
  default's mirror because an uncaptured failure is unrepeatable, while a sheet not shown
  costs nothing that can't be had by turning it on.
- **A sane default, no inference.** The time is seeded at **one hour from now, rounded to the
  nearest half hour** — a tap at 13:12 offers 14:00, not 14:12. Ragged times look like a bug and
  invite pointless fiddling.
- **`−` / `+` adjust in 30-minute steps** without dismissing the sheet. Floor is 30 minutes from now;
  ceiling is the 8-hour backstop (§7). Two taps covers 13:00–15:00, which is most meetings.
- **Two rows, both live — where there are two.** Tapping a row commits that end condition
  and dismisses. `until I leave` is drawn only on a build that tracks departure: `direct` is
  duration-only until §3's Phase 7, and there it is a single row with no helper line, because
  a row promising an end nothing behind it can deliver is worse than no row.
- **`OK` is the explicit way out, and it accepts the time as shown** (maintainer,
  2026-09-01). The rows commit on tap, but they read as labels rather than buttons: after
  stepping `−`/`+` to a time there was nothing on screen that said *done*, and the two exits
  that were obvious — the scrim and the back gesture — both discard the time just chosen.
  This does not add a third end condition. It is the time row's own commit under a control
  shaped like one, so the sheet has a terminal action a user can find without having to know
  that a card is tappable. It is on every build, including the duration-only one, since it
  is the sheet's confirm rather than anything the departure row was carrying.
  A user who meant `until I leave` and pressed `OK` out of habit gets a *shorter* snooze,
  not a longer one — choosing a time only lowers the cap and leaves departure tracking armed
  — which is the fail-open direction D7 asks for, so the ambiguity costs nothing that
  matters. The alternative considered was making the rows a selection that only `OK`
  commits; rejected because it charges every user a second tap on the app's one-tap path.
- **The helper line is not decoration.** Choosing a time *lowers the cap*; it does not disable
  departure tracking (§7). Walking out at 13:40 still ends the snooze at 13:40. The rows differ only
  in whether there is a time bound below the backstop, and the sheet should say so plainly rather
  than implying they are exclusive modes.
- **The sheet does its own arithmetic; the service has the final word.** §6.9 forbids the
  trampoline *waiting* on the service it has just started, not reading what that service has
  already written: the sheet is decided after the start is away, so it reads the record to learn
  the cap the running snooze actually carries and offers nothing later. It seeds and steps against
  the clock from there, and the service re-clamps whatever is committed. Two clamps rather than
  one, on purpose: the sheet's keeps `−` from offering times the service would refuse, and the
  service's keeps a value chosen against a stale reading from outliving it. The ceiling has to be
  the record's own cap and not a fresh backstop, because a duplicate arm from a stale tile snapshot
  keeps the snooze already running (§4.2) — offering an hour over a snooze with ten minutes left
  would be honored by doing nothing and reported as applied.
- **`until I leave` commits by changing nothing.** Departure tracking is already armed and the
  backstop is already the cap, so that row is the snooze exactly as the tile left it — which is
  also why dismissing the sheet and choosing that row are the same outcome, as the rule above
  requires. It is offered on the strength of what the *build* tracks, which is not the same
  question as what this snooze ended up tracking: a `play` anchor that degrades to duration-only
  (§6.5) still gets the row. The degradation says so where the user is looking, but the row is a
  promise made before the answer is known — see `TODO.md`.
- **A step that would land inside the floor disables its button rather than clamping onto it.**
  Rounding the seed onto the half hour can leave less than a step of headroom, and a control
  whose promise is half-hour steps must not answer a tap with a ragged time.

#### Candidates considered

| End condition | Signal needed | Verdict |
|---|---|---|
| **I leave here** | §6 presence engine | **v1** on `play`, offered whenever the build tracks departure. `direct` is duration-only until Phase 7 (§3), and drops the row rather than promising it |
| **A time, adjustable** | none | **v1.** Seeded at now + 1 h; also the §7 cap |
| **Whichever comes first** | both | **v1.** Not a third row — implied. Setting a time leaves departure tracking armed |
| **This meeting ends** | `READ_CALENDAR` | **Landed 2026-08-31**, as a notification action rather than a sheet row — see below |
| **My next alarm** | `AlarmManager.getNextAlarmClock()` | **Explore.** No permission at all, and a natural fit for a bedtime snooze. Offer only when the next alarm is 3–12 h out, so it doesn't propose a 4-minute snooze |
| **Wi-Fi goes** | `NetworkCallback.onLost` | **Fallback only, if §6.10 measurement forces it.** Instant and free, but it inverts D4 — it *is* the failure mode we designed around |
| **I start moving** | `TYPE_SIGNIFICANT_MOTION` | **Fallback only, same condition (§6.10).** No permission, already wired for §6.7. But "moved" is not "left" — standing up for coffee would end it |
| **I get home** | reverse geofence on a saved place | **Deferred.** Needs saved places (§14) plus background location, so `play`-flavor only |
| **Sunset / bedtime window** | none | **Rejected.** The OS's own scheduled Modes do this properly |
| **Screen unlocked N times** | none | **Rejected.** A proxy for attention, not place or time, and wrong in both directions |

#### Calendar: deferred past v1, then reversed

The obvious next step is seeding that time from the meeting you are actually in — `until 14:00 ·
Design review` — with a small **Use my calendar** button on the card triggering the `READ_CALENDAR`
runtime request in place, re-seeding the sheet on grant without re-arming, and disappearing once
answered. That is the right shape when it lands, and it is deliberately a plain button rather than a
designed promo: the permission has to earn itself, and if nobody taps it, that is the answer.

**But I would keep it out of v1**, for two reasons:

1. **It adds a second sensitive permission to the same Play review.** §3.5 already puts the
   background-location declaration at the top of the risk list. Presenting a reviewer with an app
   that wants background location *and* calendar access, for a feature the app visibly works without,
   is unforced risk on the one thing that can sink the project. Get the hard permission approved, then
   add the easy one.
2. **The `−`/`+` already covers it.** Meetings end at :00 and :30. From a default of 14:00 you are at
   most a tap or two from any realistic meeting end. The calendar saves those taps and the small
   effort of knowing what time it is — real, but not v1-shaped.

The v1 sheet is forward-compatible with it: the calendar only changes what the time is *seeded to*
and adds a subtitle. No new rows, no new exits, no state-machine change.

**Reversed, and built now** (maintainer, 2026-08-31, asked directly and answered "build it now,
`READ_CALENDAR` and all"). The two arguments above are answered rather than overturned:

1. The Play-review argument was about **ordering**, and the order is the maintainer's to set. The
   declarations are not filed yet (`docs/play-store-declarations.md` still owes all three of its
   "do these first" items), so this is a decision to present both permissions together, taken with
   §3.5's risk in view rather than in spite of it. Nothing about the Data Safety answers moves:
   the calendar is read on the device, one column, and no part of it is transmitted, written, or
   put in the debug log — so it is not *collection* in the form's sense, and §12's floor is
   untouched.
2. The `−`/`+` argument was right about *effort* and wrong about *knowing*: the taps are cheap,
   but they still require the user to work out what time the meeting ends. A button that already
   says `Until 17:00` removes the part that was never about tapping.

**Two things changed shape in the building**, and both are improvements on what is written above
rather than concessions:

- **It is a notification action, not a sheet seed.** The maintainer's own framing was
  `[End now] [+30 min] [Until 17:00]` — a third button on the card §4.3 already puts in front of a
  snoozing user, rather than a change to a sheet that only appears at arm time and only for a user
  who opted into it. It is also the surface where the answer is still useful an hour in: the sheet
  is gone by then, and the card is not. The `Use my calendar` button described above was never
  built; the permission is asked for from the permissions screen's own row instead, where every
  other capability is.
- **The soonest end after the floor, not the meeting overlapping *now*.** The overlap filter loses
  the case the feature is most for: a snooze armed at 13:55 for a 14:00 meeting overlaps nothing
  yet, and would be offered nothing. The floor and the cap already bound it to ends that would
  actually change something, so no third rule is needed to keep it sensible.

**And one thing did not change**: the read is still stateless, still nothing watched, and still
ends at one event without chaining into the next.

#### As built

`READ_CALENDAR`, queried against `CalendarContract.Instances` between now and the snooze's own cap
— the cap bounds it because the service honors a time at or past the cap by doing nothing, so a
wider window would read more of the user's calendar than the feature could ever use. The bound is
asked for, not filtered for: a range URI selects instances that merely *overlap* it, so a meeting
straddling the cap would otherwise have its end read and then discarded, which is weaker than what
`docs/PRIVACY.md` promises. Rows from a calendar the user has
switched off are excluded, along with all-day rows, declined invitations, canceled meetings and "free"/FYI blocks; the earliest remaining end that clears the
30-minute floor is the one offered. **Ends only** — one column, no title, organizer, location or
event id, because a time is the whole of what the button needs and a title would put a meeting's
name on a lock screen.

The read runs off the main thread and **after** the card is posted, not before it: a
`ContentResolver` query into another app's provider is exactly what §6.9 keeps off the arm path.
The answer is cached against the snooze's identity and its current cap, so a `+30 min` re-asks and
a card reposted on every state change does not.

**A timezone change reposts the card.** The offer is held as an instant, which no timezone
touches, but the button's *label* is a local time formatted once and then left on screen for hours
— so flying with a snooze armed would leave it naming a wall-clock time the phone no longer agrees
with, over an end that is in fact correct. Rebuilding re-formats it in the zone now in force.
Nothing else here moves on a timezone change: every alarm counts in elapsed realtime and every
stored time is absolute.

**The cache is keyed on calendar access as well as the snooze.** "No permission" is cached as
firmly as a time, so without that key a grant arriving mid-snooze would be answered from the denied
entry for the rest of it — the third action never appearing for the snooze the user granted access
*for* — and a revoked permission would leave its offer standing. A repost follows any change in
whether the calendar can be read — the in-app grant, a grant or revocation taken in Settings, which
has no result callback of its own, and the app's first look at a snooze the tile armed without it.

**An answer that outlives its snooze is dropped, not posted — and the check is atomic with
teardown.** The query is long enough for the snooze to end or be replaced while it is out, and the
answer *reposts*, so a calendar read could otherwise put `Snoozing` back over a phone that had just
been let ring, with nothing scheduled to take it down. Checking the record's *identity* before posting is
not enough on its own, twice over: teardown is free to cancel the card before erasing the record,
and in that order the record still reads as a snooze running; and a snooze whose tracking has since
degraded keeps its identity, so posting a card built before the degradation would put `Ends when you
leave` back over what is now only a timer. So the check and the post are serialized against taking
the card down, and what they check is the record's identity and cap (an
extension makes the answer stale rather than wrong), everything else the card is drawn from, and a
counter the takedown bumps — every takedown, the ordinary ending as much as an aborted arm, since
the ordinary one is the teardown a stale answer is most likely to race. Calendar access is re-read
there too, so a permission taken back mid-query cannot be overwritten by the answer it authorized. A change to any of it drops the *stale* card but keeps the answer, and
rebuilds from the record as it now stands — dropping it outright would leave a snooze that degraded
mid-query sitting on two actions until some unrelated transition, which on a duration-only snooze
may be the cap. Nothing on the arm path pays
for this: the lock covers a preferences read and a `NotificationManager` call, with the card built
outside it.

The shape originally written down, for the record:

```kotlin
val now = System.currentTimeMillis()
CalendarContract.Instances.query(contentResolver, PROJECTION, now, now + 12.hours)
    .filter { it.begin <= now && it.end > now }
    .filter { it.availability == Instances.AVAILABILITY_BUSY }   // ignore "free"/FYI blocks
    .filter { !it.allDay }                                       // an all-day event is not a meeting
    .minByOrNull { it.end }                                      // the soonest-ending overlap
```

**There is no calendar "trigger."** Worth stating plainly, because it removes a lot of imagined
machinery: nothing watches the calendar. No observer, no sync adapter, no background job, no
broadcast. The query is a single `ContentProvider` read against a local database, and the answer is
cached for as long as the process lives. Meeting end is just a number on a button.

**The cache is per-process, and a re-read after process death is correct rather than a gap.** The
offer commits nothing: it is a suggestion the user may tap, and until they do, the snooze ends
where it always would. So a restored snooze asking again — and possibly offering a different end,
because the meeting moved — is the button being *right*, not the app re-timing anything behind the
user's back. What would be wrong is the opposite: a durable record of an old answer, outliving the
process to offer a time that no longer matches the calendar.

**A time the user has actually chosen is a different thing, and that one does not move.** Tapping
`Until 17:00` sets the cap and is done with the calendar; if the meeting is later moved to 18:00,
Snoozemo neither notices nor cares. A snooze that silently re-times itself under you is worse than
one that is occasionally stale, and `+30 min` (§4.3) covers the overrun.

Remaining constraints: read-only, never written, never leaves the device (nothing transmits it —
crash reporting and Firebase Analytics are what use the network, and neither carries calendar
data, §12); nothing
calendar-derived reaches the debug log either (§12's floor); and the offer ends at one event and
does **not** chain into the next one — chaining is how you end up silenced all afternoon by a
calendar you forgot about.

### 4.5 Ending

When a snooze ends by departure or by cap, post a one-shot dismissible notification:
`Snooze ended — you left Home` / `Snooze ended — 8 hour limit reached`. This is how the user builds
trust that the mechanism works, and how they notice when it fires wrongly.

### 4.6 The debug log, and sharing it

Matches the sibling repos' diagnostic (Simmo's `Share debug logs` and post-crash banner), because
this app has the same problem in a worse form: **the entire mechanism runs while nobody is
looking.** A snooze that ended in the user's pocket at 14:40, or one that never ended at all, leaves
no trace the user can read, and "it ended early" is not a reproducible bug report. Without a log,
the two failures that matter most — an early release and a stuck snooze — are exactly the two that
cannot be diagnosed.

**An on-device log, on by default** (maintainer, 2026-08-11), with a setting to turn it off.
Nothing in it leaves the device unless the user hands it over: it is attached to neither of the
things that transmit — a crash report or an Analytics event (§12) — so sharing goes through the
system share sheet (and a
copy-to-clipboard fallback), which makes every send an explicit act with a visible destination. Retention is bounded — the current
run plus a few recent ones, rotated at start, in `cacheDir`, which is excluded from backup.

On-by-default is the decision because off-by-default has a cost that only looks small: the failures
worth diagnosing here — an early release, a stuck snooze, a crash — are **unpredictable and
unrepeatable**, so a log that starts off guarantees the *first* occurrence of each is the one nobody
captured, and asks the user to reproduce a bug that happens once a week in their pocket. The
conservative-looking default is the one that makes the product undebuggable in practice.

What it costs is bounded and stated: a handful of recent runs of coarse state and reasons, on the
user's own device, under the floor below, behind a setting, and shared only by an explicit act. The
count is a retention setting, not a promise — it is the shared logger's, one number for every app
using it — and the protections that carry the weight are the floor, the device boundary and the
explicit share, none of which move with it. Simmo's log is always-on for the same reason. The
alternative — a privacy gain measured in "a few files that never leave the phone" — buys less than
it gives up.

**Turning the setting off deletes what was kept**, immediately: the current run and every earlier
one still held, pinned crashes included. Stopping new writes while leaving the old files sitting in `cacheDir` would be
a privacy control that doesn't do the thing its name promises — and worse, those files would no
longer rotate, so they would outlive every log the feature normally keeps. The cost is real and
accepted: an unshared crash report is lost at that moment. That is the right way round, because the
user has just said they don't want this kept.

**What it records.** Enough to reconstruct a snooze's life, and nothing about where that life
happened:

- Every state transition (§4.1) with its reason, and the `EndReason` a snooze ended on.
- Which of the three wake-up sources fired (§6.10) — geofence exit, Wi-Fi loss, periodic backstop —
  and, for each, what the departure test concluded.
- The departure test's arithmetic: **distance from the anchor in meters, the fix's accuracy in
  meters**, whether the accuracy gate passed, and which confirmation rule matched (§6.6). This is
  the diagnostic value; the position is not.
- Whether the anchor SSID was associated — the boolean, never the SSID.
- Tracking-mode changes and their cause (§8.1), so a snooze that quietly degraded to duration-only
  is visible after the fact.
- The cap: that it was armed, that it fired, and whether the alarm or the in-service timer got there
  first.
- Permission and capability state at each decision — notification-policy access, location permission
  and its precision, whether location services are on system-wide, battery-saver state. A denied
  permission is often the whole answer to "why didn't it end".
- **Why the previous processes ended**, read from the platform at startup: Android's own exit
  reason, the process importance at the time — the priority Android had assigned the process, which
  says whether the system counted it as work the user was aware of (a visible screen, but equally a
  running receiver or foreground service) rather than a background reclaim — the exit status and
  time, and the platform's free-text description. Plus this package's install and last-update times,
  which is what an exit reason is read against — an exit whose timestamp sits beside the update time
  is the installer replacing the APK, not a failure. This closes the one blind spot the rest of the
  log cannot cover: an uncaught exception is the only death the app can observe from the inside, so
  an ANR, a native crash, an out-of-memory reclaim, an OEM's app standby, or an update all leave the
  log simply restarting with no explanation. A snooze that never ended because the process was killed
  and nothing restored the watch is principle 1's failure, and it is indistinguishable from a bug in
  the state machine without this. The description is system-composed and can name the component that
  stopped us; it stays on the device like the rest of the log.
- **The error, when something fails**: the exception's type, its stack frames, and the sentence
  describing it, for each link of the cause chain. The type says what broke and the frames say
  where, but on the paths this log exists for the message is routinely the whole answer — which
  precondition failed, which setting was refused — and the two either side of it are useless
  without it. The message is composed by whoever raised the error, not always the framework: a
  refused worker submission comes from the Java runtime, a failed opt-out write from the crash
  reporter. It carries the same consequence as the exit-reason description above: **Snoozemo does
  not write that text, so it can quote what the app handed the API it came from.** See the floor below, which this narrows. It is bounded
  and flattened — a few hundred characters, newlines collapsed — so one throw cannot push out the
  frames or forge a line that reads like the log's own.
- Build, device, and Android version.

**Entries carry real timestamps, in local time** (maintainer, 2026-08-11). Times are diagnostic, not decorative:
an inexact cap alarm (§7) that fires late because it landed outside a Doze maintenance window, cap
arithmetic that goes wrong across a DST boundary (§13), or a user who reports "it ended around 3am"
— none of those can be reconstructed from intervals alone, and a log that cannot answer *when* is
not worth keeping. The times of a user's snoozes are listed as user data in AGENTS.md's *Privacy*
rule, which is why the log's other protections carry the weight instead: it stays on the device,
keeps only recent runs, and reaches nobody without an explicit share. That is what the "one sanctioned
exception" in §12 is for.

Timestamps carry the local date, time and zone offset, and no year: `08-28
19:00:00.123`. The year says nothing a log this short-lived needs.

The offset is announced as its own marker line — at the head of the log and again wherever it
changes — rather than repeated on every entry. **That reverses `PR #128`, which tried the
marker and reverted it**, and the reason the reversal is safe is that the mechanism is not the
one #128 tried. There the marker was a stored line like any other, so every truncation point
the log has could drop it and orphan the local timestamps beneath it. In the shared logger
(`mikelward/androidlog`) the offset is a property of each retained line, and the markers are
**synthesized at render time** — one ahead of the oldest surviving line and one at every change
within the window — so no amount of eviction or trimming can leave timestamps without an offset
to read them against. The trim is anchor-aware and charges each marker against its own budget,
which is what keeps that guarantee true of the persisted file and not just the buffer.

The cost #128 was paying for is gone with it: a line read in isolation — grepped, or quoted
into a bug report — no longer carries its own offset. That is the deliberate trade. This log
is read as a run far more often than a line is lifted out of one, and the
per-line offset was being paid on every entry to serve the rarer case.

**The floor governs what Snoozemo writes, and it is not a matter of judgment**: the app never puts
a raw coordinate, a full SSID or BSSID, or a user-typed place name into the log. Distance and
accuracy answer "did the test fire correctly"; the position answers "where do you live", which no
bug report needs. `ActiveSnooze.logSummary()` is the one sanctioned way to render a snooze and its
own test pins this; every other value reaches the log as an enum, a boolean, a number or a time.

**One text is not Snoozemo's to write, and that is the floor's single exception** (maintainer,
2026-08-31): a thrown error's own message, and the exit-reason description beside it. Neither is
composed by Snoozemo — the exit reason is Android's, and a message belongs to whoever raised the
error, which is not always the framework: a refused worker submission comes from the Java runtime
and a failed opt-out write from the crash reporter, and both are logged this way. An error can
quote what it was given — the Wi-Fi and location stacks are
handed exactly what the floor above keeps out — so it is possible in principle for one to surface
in the log. It is accepted for the reason the rest of the log is: the message is frequently the
only thing that explains a failure, dropping it leaves a type and frames that answer nothing, and
this log stays on the device and reaches nobody without an explicit share. **Snoozemo does not
scrub it**, because scrubbing rendered text is a net that is only ever correct for the categories
it has been taught — the design the shared logger (`mikelward/androidlog`) exists to replace.

This is the same rule in all four apps using that logger, deliberately: the device's own copy is
whole, and the reduction applies to anything leaving without the user in the loop. Snoozemo has no
such channel today. A per-app opt-out was considered and rejected — four loggers that behave
differently is the divergence the shared library was extracted to end.

Anything above the floor is added only with a specific failure it makes diagnosable, and
`docs/PRIVACY.md` describes what the log carries before it ships (AGENTS.md, *Privacy*).

**A crashed run says so, and survives rotation.** When a previous run ended in an uncaught
exception, **every screen** raises a banner — above everything else on it — offering to share that
run or dismiss it, rather than relying on the user to remember a Settings action (maintainer,
2026-08-23). Every screen, rather than "the one the user lands on", because which screen that is
turns out not to be knowable from any one place: it is usually `MainScreen`, but a cold start with
Do Not Disturb access still missing routes straight to `PermissionsScreen` (§4.2), and a process
killed while the user was in Settings is restored *there* from saved state. Both of those were
found as separate bugs against a rule phrased around the landing screen (Codex, PR #89), which is
the argument for the exhaustive rule: every screen a cold start can *land* on shows it, and no
future routing change can reintroduce the gap.

**`LicensesScreen` is the one deliberate exception** (maintainer, 2026-08-25). It is a read-only
reference list reached by one deliberate tap, and a crash banner over it is noise rather than the
thing its reader came for. The cost is real and was accepted rather than argued away: `screen` is
restored from saved state, so a process killed while the licenses were open comes back there with
the banner not yet raised. One Back returns to Settings, which raises it — the banner is deferred
by a tap, never lost. This is the boundary the rule above draws: every screen a run can *start* on,
not every screen that exists. Only a crash raises it — an ordinary process death, a
force-stop, or an app update does not, since those runs' logs stay shareable without nagging.

A crashed run is **marked, not overwritten**: the crash handler leaves a marker, the next start
moves that run aside under a crash-suffixed name, and ordinary rotation never writes over it.
Without that, a restart between the crash and the user's tap would leave the banner offering a log
that had already gone — and this app restarts a lot, since a snooze can outlive several process
deaths.

**A crashed run does not displace an ordinary one.** Prior runs are kept side by side up to the
shared logger's retention count, so a crash and the uneventful restarts after it coexist and a
report carries them oldest-first. That replaces an earlier single-slot design in which an ordinary
run was *discarded* while a crash was pinned, to hold a two-run bound; nothing needs discarding
once there is more than one slot. Sharing consumes the runs the report was built from; Dismiss
takes a run off the crash-suffixed name, after which it is an ordinary prior run, shareable from
settings and pruned by age like any other. A later crash marks again.

**An empty crash log raises no banner.** A crash marker can land without the run's own content ever
reaching disk — process death between the two writes — and a banner offering a report with nothing
in it is worse than silence. The cost is real: a crash that left no content is not announced. It is
accepted because the banner's whole promise is that there is something to send.

The log lives in `cacheDir`, which the system may reclaim under storage pressure, so a pinned crash
can disappear before the user acts on it — and a user who switched logging off has none to begin
with. **The banner checks the file is still there and stays silent if it isn't** — offering to share
a log that no longer exists is worse than saying nothing.
Persisting it outside the cache (`noBackupFilesDir`) would close that window, and is deliberately
not done: a disposable diagnostic belongs in the cache, eviction costs a nice-to-have rather than
anything the user relies on, and the alternative keeps crash logs alive past the retention this
section promises.

**Sharing is repeatable, so only one share runs at a time** (2026-08-23). Both Share affordances —
the permanent Settings row and the crash banner's own button — are disabled while a share is
resolving, and say *Sharing…* rather than silently refusing the tap. The alternative, letting a
second tap start a second concurrent share and working out afterwards which one the user meant, was
built first and removed: reconciling two in-flight deliveries needed a completed-delivery counter,
the last delivery's outcome and pin-safety, and a map of per-attempt snapshots, and that machinery
was itself the single largest source of defects in this feature (PR #89). Coalescing at the tap
deletes the question instead of answering it, and reads better besides — a disabled button that says
what it is doing beats a tap that resolves into some other attempt's outcome. The delivery path stays
serialized underneath as a floor, so the guarantee does not depend on every caller honoring the gate.

### 4.7 Open-source licenses

The Settings foot carries a **Licenses** row, beside the privacy policy, opening a page that lists
every third-party component this build bundles and the license each ships under. Names only, one
row each — the list runs to around ninety entries, and a version and license on every row turns a
scannable index into a wall; tapping a name opens the version, who wrote it, and a link out to the
full license text.

**The authors are part of the attribution, not decoration.** Apache-2.0 §4 asks that attribution
travel with the code, and almost everything bundled here ships under it; a page naming only the
license has stated the terms without stating who they are for. So the details dialog names the
component's declared developers, or the organization that published it where no developer is
named, and omits the line entirely where the component's own metadata names nobody — an empty
"By" would be a claim about authorship the export cannot support. Names only: the export also
carries organization URLs, and a second link per component would bury the license link the dialog
exists for.

**One attribution list per flavor, not one shared list.** `play` bundles Play's in-app update
library and the Play Services stack beneath it; `direct` bundles none of it (§3.4). A shared list
would have the sideload build claiming to ship Play code it does not contain, which is the opposite
of what an attribution page is for. Each flavor's list names what that flavor's APK actually
bundles rather than what the dependency graph mentions, and is kept current by the build rather
than by hand, so a dependency bump cannot quietly leave either one stale.

**A link that cannot open says so.** On a device with nothing able to handle a web link, the tap
would otherwise be absorbed and read as the app being broken — principle 2's failure, not a
graceful degradation. The dialog states the failure instead, and clears it on the next attempt. No
fallback route to the URL is offered: where there is no browser there is nowhere to send it, and a
copy control would be more chrome than a two-line metadata dialog earns.

**Rows are ordered by the name shown, not by dependency coordinate.** The coordinates are hidden
and there is no search, so the displayed name is the only thing a reader can scan by. The ordering
is the page's own guarantee rather than a side effect of how the list happens to be parsed.

---

## 5. Do Not Disturb mechanism

### 5.1 Why `AutomaticZenRule`

Apps targeting Android 15 (API 35) and above can no longer change the global DND state or policy.
`setInterruptionFilter()` and `setNotificationPolicy()` still compile, but the platform now silently
redirects them into an *implicit* `AutomaticZenRule` it manages on the app's behalf. Contributing an
explicit rule instead means:

- The rule appears by name in Settings → Notifications → Do Not Disturb (and in the Android 16
  **Modes** UI), so the user can see and disable Snoozemo's influence without uninstalling it.
- It composes with the user's other rules under the platform's most-restrictive-wins merge, rather
  than fighting them.
- Turning it off can't accidentally clobber a DND state some other app or schedule turned on.

### 5.2 Permission

`android.permission.ACCESS_NOTIFICATION_POLICY` — manifest-declared, but granted by the user through
a settings screen, not a runtime dialog:

```kotlin
val nm = getSystemService(NotificationManager::class.java)
if (!nm.isNotificationPolicyAccessGranted) {
    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
}
```

Listen for `NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED` to react when it
is granted or revoked. Revocation mid-snooze ⇒ end the snooze and tell the user (D7).

#### How the app screen presents this, and `POST_NOTIFICATIONS` beside it

Snoozemo asks for three things on this screen, and they are not all the same kind of thing. Do Not
Disturb access is the settings screen above — the user leaves the app, flips a toggle, and comes
back, with no in-app dialog and no result callback. `POST_NOTIFICATIONS` (§4.3) and location (§3.2)
are both genuine runtime prompts that appear in place — location's own disclosure-then-permission
sequence is described there rather than repeated here. They sit next to each other on the same
screen, so the same rules apply to all three:

- **The action is the target, and it is a verb.** Each capability is one row carrying its name, its
  state, and — while something is actually left to do — a button that does it: `Allow`, `Add`. Two
  earlier shapes are ruled out by that sentence. A status line that names a problem and
  does nothing when tapped is the original defect. Making the *whole row* the target fixed it but
  spent the fix on a second problem: the action then had to be described rather than offered
  (`Opens Settings`, `Tap to add`), which reads as a note about what is going to happen, and a row
  that stayed tappable once granted kept offering something to a user with nothing to do (revised
  2026-08-13, maintainer).
- **No row offers an action the platform will ignore.** The system shows the notification prompt
  until it has been denied twice and then silently drops every later request, so past that point the
  row stops offering a prompt and points at the app's notification settings instead. Telling the two
  states apart needs a persisted flag, because `shouldShowRequestPermissionRationale` reports `false`
  both before the first denial and after the last. What it records is an **observed denial**, not a
  request: a dialog the user dismisses without answering costs nothing, so counting launches would
  strand that user in the settings-only state with a prompt still available. A grant clears the flag,
  since granting resets the history the system counts. Both surfaces that ask record what they
  learn, the tile trampoline included — the two prompts are the app's in total, not each surface's,
  and a tile-first user can be through both without opening the app — but only from the **answer**
  to a request, never before one, because nothing on the arm path may read or write a preferences
  file or call the package manager. The tile therefore does not make this distinction at arm time
  at all; it asks whenever the permission is missing, and the system drops the request if the
  prompts are gone.
- **One state is knowingly not covered.** An install whose prompts were already exhausted before
  the flag existed reads as never-denied and cannot correct itself: the platform reports no
  rationale from then on and exposes no permission-state API to an ordinary app. That row keeps
  offering a prompt that will not appear — the defect this design exists to remove, on the one
  install that cannot report it. It costs nothing today, since the app is unreleased and no install
  predates the flag, and closing it needs a device to choose between the candidate signals
  (`TODO.md`) rather than a guess. An unverifiable inference was tried and withdrawn.
- **A capability that is in place offers nothing.** The button is absent once the row has nothing to
  fix, so the row is then a statement — of what snoozes can now do — and the screen's remaining
  controls are the ones that snooze. Turning either capability back *off* is Settings' job, reached deliberately, not a
  button that looks like setup on a screen where setup is finished. The cost is real and accepted:
  there is no longer a route from this screen to the Do Not Disturb access toggle for a user who
  wants to revoke it.
- **Every row uses the same verb, regardless of mechanism.** `Allow` while something is left to do,
  and no button once it's in place — for the Settings toggle and a runtime prompt alike. An earlier
  revision split them (`Grant`/`Granted` for the toggle, `Allow`/`Allowed` for a prompt) to flag
  which was which; that distinction cost the user two words to track and told them nothing they
  needed to act on the row, so it was dropped in favor of one consistent pair (revised 2026-08-24,
  maintainer).
- **The status line names the capability, not the grant** (revised 2026-09-01, maintainer). Each row
  reads `Snoozes can …` when the permission is held and `Snoozes can't …` when it is not — the same
  sentence either way, differing by one word. This **replaces** `Allowed` as the granted-state
  status, which the 2026-08-24 revision above had standardized across every row.

  The reason that standardization is no longer right: `Allowed` answers "did the grant land?", which
  the user already knows because they just did it, and says nothing about what it bought. The screen
  then reads as a checklist to clear rather than a description of what the app can do — and the cost
  falls hardest on the one permission that gates nothing, where "Allowed" left a user with no way to
  tell what they had gained by granting it or would lose by declining. Naming the capability in both
  states makes the row answer the question the user actually has at that moment, and makes declining
  an informed choice rather than an unexplained gap.

  The verb pairing above is unaffected — that rule governs the **button**, which still reads `Allow`
  and still disappears once there is nothing to fix. What changed is the line beneath it.

  **A capability claim waits for everything it rests on to be read** (Codex, PR #171). The
  grant and the rule are read separately, the second answering after the first, so a row that
  rendered on the grant alone would claim the capability for that window and take it back when
  the rule turned out to be off. Every row here is already absent until its own state is read;
  a row that depends on two waits for both. A row whose *missing* state needs nothing further —
  no grant, so no rule to be in the way — still renders immediately.

  **A verified answer is kept and marked stale, never thrown away.** A re-check stops the
  screen claiming the old answer, but the answer itself survives, so a check that ends without
  producing a new one — a refused binder read, a rule lookup that throws — leaves the row
  saying what was last known rather than vanishing. The alternative, clearing and restoring,
  needs a restore on every path that can end without an answer and still loses a race between
  two overlapping re-checks.

  **A row that reports a problem carries the button that fixes it, whatever the problem is**
  (maintainer, 2026-09-01). The access row keeps `Allow` when the grant is held but Snoozemo's
  own mode is switched off, and that button opens the mode's settings screen rather than the
  policy-access one the grant already cleared. The verb does not change with the target: it
  reads correctly against the switch it reaches. A rule Snoozemo could not create at all keeps
  no button — there is nothing there for the user to do.

  **A row names what its own permission buys, and hedges nothing else** (maintainer, 2026-09-01).
  Every capability has runtime prerequisites — the meeting has to be running, you have to actually
  leave the house — and they are obvious to the user, so qualifying the copy for them ("can offer
  to end with a meeting") buys nothing and costs the plain sentence. Prerequisites carried by
  *another row* are visible in that row: whether the notification the meeting's end time arrives on
  can reach the user is what the notifications row says. What does get its own status is a
  capability the build genuinely lacks — `direct` and departure (§3) — because no other row says so.

### 5.3 Rule lifecycle

Create **one** long-lived rule at first successful onboarding, not one per snooze — rules are
user-visible objects and churning them would litter the DND settings screen. Persist the returned id.

```kotlin
val rule = AutomaticZenRule.Builder("Snoozemo", CONDITION_ID)
    .setType(AutomaticZenRule.TYPE_OTHER)
    .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    .setZenPolicy(defaultPolicy)
    .setConfigurationActivity(ComponentName(ctx, SettingsActivity::class.java))
    .setTriggerDescription("While you're at a place you snoozed")
    .setManualInvocationAllowed(true)
    .setIconResId(R.drawable.ic_zz)
    .setEnabled(true)
    .build()
val ruleId = nm.addAutomaticZenRule(rule)   // requires ACCESS_NOTIFICATION_POLICY
```

`CONDITION_ID` is a stable app-owned URI, e.g. `Uri.parse("snoozemo://snooze")`. This `Builder` is
API 35+ only; minSdk is 35 (raised from 34, PR #88 — §11), so there is no older-constructor fallback
to carry.

### 5.4 Turning the rule on and off

```kotlin
fun setSnoozed(on: Boolean, reason: Reason, placeName: String) {
    val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
    val summary = if (on) "Snoozing at $placeName" else "Left $placeName"
    val condition = Condition(CONDITION_ID, summary, state, reason.toConditionSource())
    nm.setAutomaticZenRuleState(ruleId, condition)
}
```

The `source` argument is worth setting correctly: `Condition.SOURCE_USER_ACTION` when the user
tapped the tile, `Condition.SOURCE_CONTEXT` when the presence engine decided. The platform surfaces
this in the Modes UI so the user can tell "I did this" from "my phone did this."

### 5.5 Zen policy

Default to `INTERRUPTION_FILTER_PRIORITY` with a `ZenPolicy` allowing alarms, media, system sounds,
and calls from repeat callers — the shape most people already expect from DND, and one that keeps a
genuine emergency reachable. Total silence is available in settings but is not the default, because
defaulting a location-triggered mechanism to "nothing gets through" is how you miss something that
matters.

Optionally attach `ZenDeviceEffects` (`setShouldDimWallpaper`, `setShouldUseNightMode`,
`setShouldDisplayGrayscale`) as an opt-in "make the phone boring too" setting. Nice-to-have, not v1
scope.

### 5.6 Pre-existing DND

If DND is already on when the user arms, Snoozemo still turns its own rule on. Because the platform
merges most-restrictive-wins, this is safe and idempotent. On release, Snoozemo turns off *only its
own rule* — whatever else was making the phone quiet stays. This is the concrete benefit of D1 over
`setInterruptionFilter(INTERRUPTION_FILTER_ALL)`, which would have stomped the other rule.

### 5.7 Bypassing our own DND

Two of the app's three notification channels — `snooze_active` (§4.3) and the emergency-only
`snooze_urgent` — bypass Do Not Disturb. Without that, arming a snooze puts the phone into the very
interruption filter that would silence the ongoing "Snoozing" card telling the user it worked, and
the one alert that exists to hand back a phone that may still be stuck silenced by Snoozemo's own
rule (`showStuckRule()`, §4.5) — which cannot itself be a casualty of that silence.

`snooze_ended` deliberately does **not** bypass (maintainer, 2026-08-23). A channel's bypass flag is
honored by the platform's DND filtering regardless of which source is currently imposing DND, so a
bypassing channel would let a routine notice — `showEnded()`'s departure/cap card, an interim
"couldn't end the snooze, trying again" — sound through an unrelated DND that has nothing to do with
Snoozemo, such as a Bedtime schedule or another app's rule. That is the user's own choice to be
quiet, and not this app's to override on their behalf (§5.5's "total silence is the user's choice"
is the same instinct). Only the genuine emergency exit needs to survive an unrelated DND source, so
only `snooze_urgent` — carrying `showStuckRule()` alone — claims that exemption; everything else
that used to share `snooze_ended`'s bypass now respects whatever else is silencing the phone, same
as any ordinary app's notifications would (Codex, PR #92, raised the mechanism; the split itself is
the maintainer's call).

The platform only honors a channel's bypass flag from a caller that currently holds
`ACCESS_NOTIFICATION_POLICY` (§5.2) — which arming requires, but channel *creation* can happen
earlier than that, at first app launch, well before onboarding can have granted anything. So the
bypass is kept correct across every point where access can turn up afterward: the ordinary
access-granted flow, an out-of-band grant made directly in system Settings while the app was not
running to notice, and — the case that matters most — an arm that gets refused after the rule has
already gone `STATE_TRUE` and cascades straight into the stuck-rule alert without ever reaching the
success path (§7.1). None of this adds policy IPC ahead of the tap; it rides on IPC the arm path
already pays for once the zen write itself has been attempted (Codex, PR #92).

It is a per-channel importance flag, not a zen-rule change, so it does not touch §5.6's "Snoozemo
touches only its own rule" invariant. The user can still switch it off per-channel in Settings
(`ACTION_CHANNEL_NOTIFICATION_SETTINGS` → "Override Do Not Disturb"); that is their explicit choice
to make, not a default Snoozemo takes on their behalf.

### 5.8 The user turning Do Not Disturb off

**Turning DND off from the shade ends the snooze**, as if the user had tapped the tile.

**Two mechanisms, because one of them can fail quietly.** The *timely* answer is a broadcast:
`ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED` carries `AUTOMATIC_RULE_STATUS_DEACTIVATED` for a rule
the user switched off, so this needs no polling and no inference from the interruption filter. The
*reliable* answer is reading `getAutomaticZenRuleState` back on every wake-up: a receiver can be
refused registration, and this process only lives between wake-ups, so a snooze can outlive the only
thing watching it. Re-asking turns that failure into **late rather than never** — the same bargain
§5.2's policy-access reconcile already strikes. Neither adds a wake-up of its own.

**The two paths must agree.** The broadcast and the read-back describe the same platform states, one
promptly and one late, so they resolve to the same reasons — a rule that is **disabled** or
**removed** is a lost capability either way. That needs saying because the platform makes it easy to
get wrong: a disabled rule reports the same `STATE_FALSE` as one the user just switched off, so the
condition alone would tell the user they turned Do Not Disturb off when in fact Snoozemo can no
longer enforce anything. Which explanation they get must not depend on whether our process happened
to be alive.

**"Cannot tell" is never "off", and "deleted" is not "turned off".** The read answers four ways, not
two, because collapsing them loses a distinction the user can see. Only an explicit *inactive* ends a
snooze as the user's own doing; a rule that has been **deleted** ends it as a lost capability, which
says why (§4.5) rather than ending in the silence appropriate to something the user just did. And
*unreadable* ends nothing at all — a failed binder call reading as "the user turned it off" would
turn the phone back on in the middle of a meeting, which is the failure the whole app is arranged
against.

**An off rule with a live record has two explanations, and the platform reports only one of them.**
`STATE_FALSE` says the rule is off, not *who* turned it off — so a process that died between turning
the rule off itself and clearing its record would come back, find exactly that, and blame the user
for an ending it decided on. Because the user's own ending is the one deliberately kept silent
(§4.5), the real reason would disappear along with the notification explaining it. Snoozemo
therefore records **why** a release is being attempted before attempting it, and prefers that
recorded reason over the inference — but only for the endings *it* decided on. A manual ending needs
no marker: losing one to a crash falls back to "the user turned Do Not Disturb off", which is
equally silent and equally the user's, so nothing observable changes. That is what keeps the tile-tap
path free of disk work, and it is a consequence of the design rather than a concession to it. A
marker whose release then fails is discarded, since an attempt that never completed must not go on
to explain some later ending. Every release path Snoozemo decides on writes it, the no-service
fallback included, and only a **new arm** or a completed release clears it: a rewrite of the live
record — a clock rebase, an extension, a tracking change — leaves it alone, because the reason it
would erase may belong to a release of that very snooze still in flight. A refused release on any
of the app-decided endings re-arms the cap and escalates rather than waiting, since neither a status
broadcast nor a restoring wake-up is guaranteed to repeat. A marker found at a restoring wake-up
beside a rule the platform reports still **on** belongs to a release that never completed — a
refusal whose own clean-up failed to commit — and is discarded there, so it cannot explain some
later ending; an unreadable rule retires nothing. It is not the same as marking the record released: that marker
suppresses the record, and writing it before a zen write that can still fail would strand a live
snooze with Do Not Disturb on and nothing left to turn it back off.

**Only where it can act, and never over an unfinished arm.** The read-back runs on the wake-ups that
restore, not on the explicit endings — those are already on their way to turning the rule off, so
reading its state first buys nothing and puts policy IPC between the user's tap and their phone
making noise again. And because the record is written *before* the rule (§4.1), a record over an off
rule is ambiguous in a second way: the arm may simply never have completed. Snoozemo records that
the rule went on **the moment it goes on** — not when the anchor later arrives, which can be the
full 10 s ceiling away — and **on the record itself**, so the two can be told apart — an interrupted arm is
**finished** rather than read as a deactivation and silently discarded. On the record and not beside
it, because a marker stored separately is wiped by the next ordinary update of the record it
describes, and a running snooze that reads back as an unfinished arm gets its rule re-asserted over a
Do Not Disturb the user switched off.

**The read has to happen before the restore.** A process that died before the user turned Do Not
Disturb off never heard the broadcast, so the next wake-up's state read is the only thing that can
notice — and restoring a persisted snooze re-asserts the rule, which overwrites exactly that
evidence. Reading first is what stops Snoozemo re-silencing a phone its user deliberately
un-silenced; a snooze whose rule is already off is picked back up only to be ended, never to be
re-asserted.

Both are API 35, and so is the floor this app installs on (§11), so every device that can run
Snoozemo has them. An earlier draft here recorded a gap on API 34 — true when written, and made
moot by the minSdk raise that followed it.

Ending is not merely tidier than letting the snooze run on; **it is what makes the next snooze
work**. A deactivated rule stays deactivated until its owner sets it back to `STATE_FALSE`, so a
snooze left running here would leave the *following* tap turning on a rule the platform ignores —
the tile reading `Snoozing` over a phone that still rings, which is principle 1's failure reached by
doing nothing. Ending the snooze issues exactly that `STATE_FALSE`.

It gets its own `EndReason` so §4.5's "every ending has a reason" holds, but **no notification**: the
user turned DND off because they wanted the phone back, and a card telling them the phone is back is
worse than silence. Two related statuses fail open (D7) instead, because a rule that is `REMOVED` or
`DISABLED` can no longer enforce anything: a running snooze ends as `LOST_CAPABILITY`, and a removed
rule is recreated only when nothing is running. A **disabled** one is never re-enabled behind the
user — they did that deliberately.

Every one of these is gated on the rule being *ours*, and **ours means the rule this snooze was
armed with** — recorded on the snooze the moment the rule goes on, and again on every re-assertion
(2026-09-05), as the rule the state write itself reports rather than one read back after it, since
a replacement can be minted between the two. §5.6's "only its own rule" governs reading as well as writing: another app's mode
ending, or a user schedule's, must never end a Snoozemo snooze. The comparison is against the
recorded rule rather than the id the app holds *now*, because the two part company in one ordinary
sequence — the user deletes the rule, and the tile's next shade-open mints a replacement. Judged
against the current id, that sequence went silent twice over: the original's `REMOVED` broadcast
named an id the app no longer held and read as somebody else's, and the next wake-up's read-back
inspected the replacement — enabled, and off — and reported the user turning Do Not Disturb off. A
lost capability, which explains itself, hidden behind the one ending kept quiet. Remembering the
displaced id instead of recording the rule was rejected: ownership inferred from a value that moves
needs a new guard for every way it can move. A record written before it named its rule falls back
to the current id, which is the behavior this replaces.

Two neighboring cases are deliberately out of scope: the user turning DND *on* while we are idle
(at worst the tile reads `not snoozing` beside a quiet phone), and another app's rule ending while
ours is on (nothing to do).

### 5.9 How loud a snooze may be

Do Not Disturb decides **who** reaches you during a snooze; it says nothing about **how loudly**
they arrive. §5.5's policy lets calls and messages from the senders the user has chosen through, and
those then ring at the normal ringer volume — which is the wrong answer for the commonest reason to
reach for a snooze. Neither `ZenPolicy` nor `ZenDeviceEffects` carries a ring-or-vibrate choice, and
the platform's own Modes UI offers none: the ringer is a separate axis, and the global ringer mode
is the only thing that can express it.

So Snoozemo carries a **ceiling** — `Ring`, `Vibrate`, `Silent`, defaulting to `Vibrate`
(maintainer, 2026-09-02) — and lowers the ringer to it for the duration of a snooze. Settings
offers it as one sentence completed by a dropdown: *When snoozing set the phone to* `Vibrate`.

**A ceiling, not a value forced on the phone** (maintainer, 2026-09-02), which is the same thing the
volume panel's own bell / vibrate / mute control is, so it is what a user already expects. Two
consequences follow, and both are deliberate:

- A phone **already quieter** than the ceiling is left alone. Silent under a `Vibrate` snooze
  stays silent; raising it would be the app making a phone louder than its owner set it, which is
  not this setting's job in either direction.
- `Ring` is not "force audible" — it imposes nothing and never touches the ringer, so a
  phone its owner keeps on vibrate goes on vibrating.

`Vibrate` is a ceiling on the platform's terms too. `RINGER_MODE_VIBRATE` always vibrates the
*ringer*, but a notification vibrates only if the user's own vibrate setting is on — so an allowed
*message* may arrive with no buzz at all. Accepted rather than worked around: there is no API to
force it, and forcing it would assert a floor this setting deliberately does not have.

**No new permission.** `AudioManager.setRingerMode` is documented as disallowed, from API 24
onward, for adjustments that would toggle Do Not Disturb unless the app holds Notification Policy
Access — which is §5.2's `ACCESS_NOTIFICATION_POLICY`, the grant without which there is no snooze to
be quiet for. Android 15's restriction on changing global Do Not Disturb names `setInterruptionFilter`
and `setNotificationPolicy` only; the ringer is untouched by it.

**The ringer is borrowed, and the loan is what gives it back.** Ringer mode is global device state
that outlives the process and the reboot, so a snooze that quiets the phone and then loses the way
back leaves it quiet with nothing anywhere that knows better — principle 1's failure, in its most
literal form. Six rules keep that from happening, and each one prefers the audible mistake:

1. **The way back is recorded before the ringer moves**, durably, and the borrow is *declined* if
   that record cannot be written — and the *cached* record is dropped with it, because a `commit`
   that returns false has still updated this process's own copy. Left there, a service recreated
   without the process dying would finish that phantom borrow and quiet the phone, and the next
   process death would reload a disk with no loan at all. Ringing through one snooze is the cheaper
   error. That ordering
   leaves a window — a loan on disk and a ringer that has not moved — so the record carries whether
   the change was **applied**, and a snooze re-asserted with an unapplied loan *finishes* it rather
   than skipping it. Skipping was the failure: a process death in that window left the phone loud
   for the snooze's whole length, with rule 2 politely declining to touch it. Both halves of that
   marker are evidence rather than assumption: it is set only by a write that could be **read
   back**, since a setter that silently did nothing while the read-back failed would otherwise be
   recorded as applied and every later re-assertion would take no action at all; and finishing an
   unapplied loan requires the live mode to be exactly where the record says it was **found**,
   since anything else means somebody else moved it first and finishing would raise a phone its
   owner has since quieted.
2. **The loan is never overwritten, and neither is the choice.** An arm re-asserted after process
   death, or by the cap alarm's own re-arm, finds a loan outstanding and takes nothing — a second
   borrow would record the quiet mode as the way back and no later release could make the phone
   audible again. The *choice* the snooze is running under is captured once, at its first arm, and a
   re-assertion reuses it rather than reading the setting again: otherwise a ceiling changed
   mid-snooze would be applied by the next restore, which is precisely what this design defers.
   The record names the snooze it belongs to — its start instant, the one field a snooze carries
   unchanged from first arm to last re-assertion — and is honored only for that snooze. Without
   that, a record one snooze left behind (a clear that never reached disk, a retry that lost its
   race to the next arm) was read as the next snooze's own, and a stale `Silent` could quiet a
   snooze the user had configured as `Ring`. A record naming another snooze is read the audible
   way: the setting is read afresh and the record becomes this snooze's. A record naming no
   snooze predates owners and may be either a snooze restored across that upgrade or a stale
   one, so the **more audible** of it and the setting is taken — right in both cases — and it
   becomes this snooze's from then on. A loan still outstanding under a record being replaced —
   another snooze's, or an owner-less one adopted at a louder ceiling — is handed back first:
   left standing it would block a second borrow and keep the phone at the old ceiling with
   nothing to report. A hand-back that is refused declines the replacement altogether, leaving
   the old record in place for its retry ladder, so nothing ever claims a ceiling the phone is
   not at — and says so at once through the stuck-ringer notice (rule 5), because that ladder's
   retries leave a loan alone while a snooze is live and this snooze's own release is the next
   thing that hands it back.
3. **The ceiling is forgotten only once the release is confirmed.** The ringer is handed back
   *before* the zen rule goes off, so that a refused rule write cannot leave a quiet phone with no
   loan — and a refused rule write **keeps the snooze running**. Clearing the record with the
   hand-back would then leave a live snooze whose ceiling nothing remembers: the card could not
   report the shortfall it is now certainly having, and the next restore would adopt the choice
   meant for the following snooze. Confirmed does not only mean the rule was turned off: a release
   that finds no policy access, no rule, or the rule already disabled has established that nothing
   of Snoozemo's is silencing the phone, which is the same thing the ceiling was recorded to
   outlast. Only a platform that *refused* the write leaves something still to release, and only
   there is the record kept. An **arm** reaching those same readings ends the snooze rather than
   starting one, so it hands the ringer back and forgets the ceiling exactly as a release does —
   because nothing else would. A re-assertion that finds nothing to silence is finalized without a
   second zen call, which would otherwise leave a loan taken before the process died holding the
   phone quiet with no retry scheduled. A *refused* arm is deliberately not in that set: it keeps
   the snooze armed for the cap to retry, so the loan is still owed to a snooze still running. And
   a refused *release* is the mirror of it — the snooze keeps running, so the ringer handed back
   before the rule write goes back down again. That hand-back is unconditional on purpose, and the
   window it buys is microseconds wide only when the release succeeds; on a refusal it would
   otherwise stay open, phone above its ceiling, until some later re-assertion. The idle check below drops it on its own terms: having established
   that no snooze is running, it clears the ceiling on **every** path out — loan or no loan, handed
   back or not — because nothing calls the release path's forget for a snooze that ended without
   one, and a record left there is read by the next arm as its own.
4. **A ringer the user moved mid-snooze is theirs.** The loan records what Snoozemo set as well as
   what it found, so a live mode that no longer matches means the user has taken over: the record is
   dropped and the mode left alone — and *stays* alone: the hand-back reports the disown, so a
   release the platform then refuses does not re-apply the ceiling over it, which would find no
   loan, borrow again, and undo the very change this recognized. Where the live mode *cannot be read*, the ringer is handed back
   anyway — the user's own change cannot be ruled out, but a phone left quiet after a snooze it was
   told had ended is the worse of the two.
5. **A refused hand-back is retried, not finalized.** The release path retries the write a bounded
   few times — a borrow that succeeded proves the device accepts the call, so a refusal there is
   almost certainly transient. Where all of them fail it asks for a **durable successor**: an
   `AlarmManager` wake-up whose receiver re-runs the hand-back with no service and no snooze to
   resolve, since the loan is its whole subject. That alarm exists because a completed release
   erases the record and cancels every other alarm, so the loan on its own schedules nothing — and
   the checks at process start and app-open are likely rather than guaranteed. It is armed only
   after those refused writes, so in the normal case it wakes nothing (§9). A tally that cannot be
   stored **ends** the sequence rather than restarting it: each firing may wake a fresh process, which
   would read the same stale count and schedule the same first delay forever, which is the unbounded
   wake-up the pacing exists to prevent. **When the sequence ends,
   the user is told** — a notification (`Ringer not restored` / `Change it in the volume panel.`),
   because the snooze has ended and taken the ongoing card with it, and the loan alone is not
   something a user can read; it comes down again the moment any later hand-back succeeds or the
   user's own change disowns the loan. **Its interval doubles
   and its sequence ends** — a minute, then two, up to an hour, for ten rounds — because a refusal
   can also be *permanent* (a fixed-volume policy appeared, notification-policy access was revoked),
   and nothing about the next attempt would be different: a fixed interval would buy a wake-up a
   minute, indefinitely, for a write that cannot land. The tally lives on the loan, so an alarm
   waking a fresh process resumes the sequence rather than restarting it. Ending the *scheduling* is
   not giving the ringer up: the loan stays, and the two checks below plus the next snooze's release
   still retry from it. A record that cannot be **read** takes the same ladder rather than being
   mistaken for a finished hand-back: there may be a loan under it, and the release that reaches
   it would otherwise turn the rule off and forget the ceiling with nothing left watching. The
   idle check takes it too, and more sharply: that is what the retry alarm's own receiver runs, so
   a one-shot alarm reaching an unreadable record is already spent and returning without a
   successor would leave nothing scheduled at all. A **record that will not go away** takes the
   ladder as well: `commit` returning false leaves the row on disk while this process reads it as
   gone, so the next process finds it back — a stale ceiling can quiet a snooze configured as
   `Ring`, and a stale loan whose set mode the user happens to pick becomes a hand-back that
   overrides them. So does an unreadable answer to *whether a snooze is running*: that resolves to
   "running", which keeps a phone meant to be quiet quiet, but it is not an answer — and reached
   from the one-shot alarm it would otherwise spend that alarm and schedule nothing over a loan
   that may be genuinely stranded. With nothing borrowed it asks for nothing, since there is
   nothing to come back for. Those retries carry no notice at either end, since the ringer itself is already
   where it belongs. Where
   even the tally cannot be written there the wake-ups stop *silently* — a store that cannot be
   read cannot say a loan exists, and a stuck-ringer notice over a phone nothing ever touched is a
   false alarm. Where the *alarm itself* is refused there is one rung below it — this process is
   alive, since it is running that very callback, so its own delayed handler is a real successor.
   Its budget is per episode rather than per process — a rung that succeeds clears the count, or it
   would be spent on behalf of the next stranded loan — and the work happens **inside** that
   callback rather than on a thread it starts: with no alarm
   scheduled and possibly no component owning the process, a thread started and abandoned there can
   be killed before it writes, and this rung exists precisely because it is the last thing left.
   Alongside it: the loan
   is re-checked at process start and whenever the app is opened, and the next snooze's release is
   the last backstop, since its arm declines to borrow over a stale loan.
6. **The loan's decisions are serialized process-wide.** Reading the loan and reading whether a
   snooze is running have to be one atomic decision, not two ordered reads: a cold tile tap runs an
   arm alongside the start-up check, and separately they can interleave into the check handing back
   a stale loan just after the arm declined to borrow over that same loan — a running snooze with no
   ceiling at all. So the hand-back check evaluates "is a snooze running" inside the same lock the
   borrow takes.

**A ceiling that does not hold is said out loud.** Refusals are rare — a fixed-volume device, a
loan that could not be written, a platform rejection — but the user *hears* the difference, so the
ongoing notification carries it as its own clause beside the tracking mode (`Ends when you leave —
still ringing`). It is **observed, not remembered**: the card re-reads the live mode against the
ceiling on every post, which covers a refused borrow and a ringer the user turned back up
mid-snooze with one mechanism, and clears itself when either is put right. The ceiling it is judged
against is the one **in force for the running snooze**, from the choice recorded when that snooze
armed and forgotten when it ends. Its own record rather than the loan's: a ceiling can be in force
with nothing borrowed — a phone already quiet enough is left alone, and a refused change is exactly
the case worth reporting — and the live setting cannot stand in for it either, since a choice changed
mid-snooze governs the *next* snooze and reading it would make the card lie in both directions. It
records the **choice**, not the mode it implies, so that `Ring` — which has no ceiling — is still a
record: without one, a re-assertion would find nothing and adopt whatever the setting said by then.
An outstanding loan is not a substitute for that record in either direction, because it does not say
*whose* snooze it is: one left behind by a refused hand-back would otherwise become the next
snooze's ceiling, running it quieter or louder than the user chose and reporting no shortfall.

A live mode that cannot be read at all is a third answer rather than silence (`may still ring`):
arming faced the same unreadable mode and declined to borrow, so a ceiling in force is certainly not
holding — and *which* mode the phone is in is precisely the unknown, so the clause hedges instead of
naming one.
The two clauses are independent because they answer different questions — whether the snooze will end correctly, and
whether it is as quiet as was asked.

**Driven from the zen controller, not from each caller.** `setSnoozed` is the one call every arm and
release in the app already passes through — the service, the cap alarm, the release backstop, the
restore path — so the ceiling is applied there and no path can forget it. The order is nested:
arming sets the rule first and lowers the ringer only once the rule is confirmed on (nothing may
come between the tap and `STATE_TRUE`, and a refused arm has taken nothing to give back), while
releasing hands the ringer back *before* the rule goes off, so a failed release still has the loan
and every retry path intact. A failed ringer change never fails the snooze; it is reported and the
snooze stands.

**One thing about `Silent` is unresolved and flagged rather than guessed at** (`TODO.md`).
A `Silent` ceiling is the only one whose hand-back leaves `RINGER_MODE_SILENT`, and
`setRingerMode`'s reference says an adjustment that *would toggle* Do Not Disturb is permitted
precisely when the app holds notification-policy access — which Snoozemo does. If that coupling is
still live on API 35+, such a hand-back could turn off a manual Do Not Disturb or another app's
rule, which §5.6 forbids outright. The premise could not be settled from the documentation, and
each candidate mitigation trades that possible breach for a likely breach of principle 1, so the
option ships as the maintainer asked for it with the decision and the device check recorded.

The choice governs the **next** snooze, not one already running. Retargeting a live loan has no
ordering that survives a process death in the middle — the record can name the old mode over a phone
in the new one, or the reverse, and a later release then cannot tell a stale loan from the user's own
change. Deferred rather than guessed at (`TODO.md` Phase 4).

---

## 6. Presence: deciding when you have left

### 6.1 Interface

```kotlin
interface PresenceMonitor {
    fun start(anchor: Anchor, sinceElapsedRealtimeMs: Long): Flow<PresenceUpdate>
    fun stop()
    fun supportedModes(anchor: Anchor): Set<TrackingMode>
}
```

**The evidence seed is the caller's to supply, and it is the arm moment, not "now".** The engine
refuses observations older than evidence it has already accepted, seeded so that nothing from
before the snooze can steer it. A monitor seeding with its own start time gets that right at arm —
they coincide — and exactly wrong at restore: there, "now" post-dates the very exit the restart was
woken to collect, so the one observation that mattered would be dropped as stale. Only the caller
holds the record whose stored clock frame can restate the arm moment, so the seed rides in through
`start`.

**`supportedModes` is the mode's warrant, and it belongs to the monitor** (added while wiring the
monitor into the service, 2026-08-22). A `TrackingMode` is a claim about what is *watching*, and
the anchor's fields alone cannot back it: an anchor with an SSID reads as Wi-Fi-trackable, but
whether anything actually watches that SSID depends on which monitor is running and which of its
slices exist — the `direct` flavor's stand-in watches nothing at all. So the monitor states the
modes it can honestly run for a given anchor, and the controller lowers every mode it ever
computes — at arm, on restore, and on every update — to the nearest supported one. Without that,
the first presence report's null degradation would silently promote the mode back to the anchor's
paper capability, undoing the arm's honesty one update later.

A **set**, not a single best-mode ceiling, and the shape was forced in review: degradation moves
*through* modes a ceiling cannot vouch for. A fenced anchor's best mode is full tracking, but when
location degrades, the fallback claim is Wi-Fi-only — and whether that is honest depends on
whether anything watches Wi-Fi, which a ceiling of "full" cannot say. Duration-only is always
treated as supported: the cap needs no sensor.

Each `PresenceUpdate` carries two things of deliberately different shapes: an **event**, which is
news and usually absent, and a **tracking health level**, restated every time whether it moved or
not.

`PresenceEvent` is `StillHere`, `ProbablyLeft`, `Departed`, and `CapabilityLost(cause)` — the four
things that can *happen*. `CapabilityLost` must stay a type of its own rather than a value the
controller interprets: **a degraded level keeps the snooze armed** in a lesser tracking mode with the
notification saying so (§8.1), while **`CapabilityLost` ends it** with `EndReason.LOST_CAPABILITY`
(§8.2, D7). A monitor that reported a revoked location permission as mere degradation would leave
the phone silent with nothing left to end the snooze — principle 1's failure — so a fatal cause is
never reported as a recoverable one.

**A `CapabilityLost` ending survives a process death or a reboot in the window before it is
consumed**, not only handed off in-process (`GeofencePresenceMonitor`, `play` flavor). Reporting the
event alone is a `Flow` send with nothing behind it — if the process dies between that and
`SnoozeService`'s collector actually acting on it, the decision is lost, and a restore starts fresh
believing the snooze is still healthy, which is exactly the failure the grace deadline's own
persistence (below) exists to close for the other ending path. The cause is written to disk before
the event is sent, and a near-immediate platform alarm is armed as the wake-up — a persisted decision
with nothing scheduled to act on it would otherwise wait for whatever *next* restarts the process,
worst case the duration cap. A restore reads the record directly and ends immediately, independent of
whether the alarm ever fires.

**Health is a level and not an event, and that is a decision with a history.** It was originally
reported as a pair of events, one for degrading and one for recovering, and every ordering question
then became *did the announcement survive?* — through an escalation that outranked it, through a
Wi-Fi association that suppressed location before it could be said again, through a second fix that
overwrote it, through a staleness gate that dropped it. A level cannot be lost in transit, so none
of those questions exist. The controller compares what it is told against what it believes and acts
on the difference, which also makes "report it once, not once per bad fix" a property of the
comparison rather than a rule the engine has to remember.

**A degradation must be withdrawn once tracking recovers.** One the app announced and then never
took back is a false statement about its own state, and the kind that teaches the user to disbelieve
the line that matters when it is true (principle 2).

**The recorded cause tracks the failure happening now, not the one that started the run**
(revised 2026-08-30; it used to hold whichever flavor crossed the threshold first). The original
rule froze it deliberately, reasoning that the two causes lowered tracking identically and read the
same to the user, so restating a changed one bought a rewritten notification for nothing. That
stopped being true the moment the card began rendering them as different sentences (§4.3): frozen,
a walk from a weak-signal spot into one with no fixes at all leaves the card saying `weak location
signal` for the rest of the run, which is the stale reason §8.1 exists to prevent. The flapping
that argument was guarding against is real and is accepted rather than denied — alternating
failures now restate an alternating cause — but it stays a *level* and never an event, so the card
is reposted silently and nothing alerts.

**Health is about location, and Wi-Fi is not evidence about location.** Rejoining the anchor's
network proves Wi-Fi works and says nothing whatever about whether location started working, so it
does not clear a degradation — which is what stops a snooze claiming `FULL` while nothing is
watching for a departure. Replacing a stale degraded line with a false healthy one is the same
failure in the more dangerous direction.

**The two halves of a fix go stale at different rates.** *Where the user was* expires as soon as
newer evidence lands (§6.6's staleness rule). *That location managed a reading good enough to
measure with* does not expire at all — the subsystem either did or it didn't. So a reading too old
to say where anyone is can still be the thing that says location is working again — which matters
because the next thing that happens may be rejoining the anchor's network, and that suppresses
location entirely (§6.7).

**A restart resumes the degradation it left, rather than starting healthy.** The monitor's own
levels live in the process, and on the `play` flavor the process is reclaimed between wakes — so a
rebuilt watch that began at "nothing is wrong" would send a first update saying exactly that, and
the card would drop from a degraded line to a plain one moments after being reposted, on no
evidence at all. Worse than a moment's optimism: the engine infers `NO_LOCATION_FIX` by counting
misses, so re-deriving the truth costs a whole fresh run of failures, not one probe. The snooze
record carries the cause across the restart and the monitor seeds it back — each cause into the
slot whose refutation actually fits it, since a services-off fact and an inference from counted
misses are answered by different evidence.

**The floor a restored degradation gets is the restart, not the arm**, and the difference is the
ordinary case rather than a corner of one: a snooze that arms healthy, banks a good reading ten
minutes in, and only then starts failing leaves a cached fix that post-dates the arm and pre-dates
the trouble. A restarted process cannot vouch for a reading it never saw arrive, so only one that
does arrive to it can retract the failures. Two boundaries therefore separate at a restart — what
counts as *fresh evidence* is still the arm moment, because raising it would discard the held
departure the restart was woken to deliver — which is why the rule below has to hold on both the
stale and the fresh path, and not only where a reading is old enough to be obviously suspect.

Two smaller consequences follow from the same "what does this process actually know" question. The
**run of failures continues across the restart** — only the process ended — so a resumed
degradation resumes with its threshold already crossed, or the reason would keep naming the flavor
that failed *before* the restart while a wake that manages one probe can never re-cross it.

**Only the engine's own inferences are resumed; a platform-layer cause is not.** The record carries
the cause without its origin, and "location services are off" can come from either of the two
mechanisms whose different refutations §6.1 keeps apart — so a resumed one can claim neither, and
every way of resolving that ambiguity toward recovery overstates. Such an outage therefore loses
its *reason* across a process death and the engine re-derives what it can, which is the
understating direction and what the app did before. Resuming it safely needs the origin recorded
alongside the cause (`TODO.md`).

**Evidence of health must be newer than the failure it claims is over.** The same cached and batched
delivery that makes the rule above necessary can also hand over a reading captured *before* the
trouble started; accepting that would restore full tracking on evidence older than the problem, and
hide a degradation while departure tracking is still broken. That is the overstating direction, so the
boundary is explicit: capability evidence counts only if it post-dates the last unusable
observation.

Two implementations: `GeofencePresenceMonitor` (`play` flavor, §3 option B) and
`ForegroundPresenceMonitor` (`direct` flavor, §3 option A). Everything above this line is
flavor-agnostic — the state machine, the DND handling, the tile, and the §4.4 sheet are all shared,
and neither flavor is aware of the other.

**The judgment lives above the interface, not inside a monitor.** A monitor's job is to deliver what
its sensors said — a geofence fired, Wi-Fi went, here is a fix — and nothing else. Which signals are
worth escalating for, how hard location should be running (§6.7), when to admit tracking has
degraded (§8.1), and when an unverifiable state has gone on long enough to end the snooze (§6.6) are
all decided once, in one place, from those signals. Two reasons, and the second is the load-bearing
one: the flavors would otherwise drift into subtly different products, and every rule that lives in
a monitor can only be tested on a device, where the sequences that break it — a router rebooting, a
provider emitting junk for ten minutes, an alarm arriving after the reason for it went away — cannot
be replayed on demand.

### 6.2 The anchor

Captured once at arm time:

```kotlin
data class Anchor(
    val lat: Double?, val lon: Double?, val fixAccuracyM: Float?, val capturedAt: Instant,
    val ssid: String?,          // the SSID we were associated with, if any
    val bssid: String?,         // recorded for diagnostics only — see below
    val radiusM: Int = 150,     // default; per-place override later
)
```

**SSID, not BSSID, is the anchor.** In any building with a mesh or multiple APs, the BSSID changes
as you roam between access points while you have obviously not gone anywhere. Anchoring on BSSID
would produce constant false departures in exactly the large venues the app is most useful in.

**`bssid` stays, and it has a use: the meeting room** (maintainer, 2026-08-13). Codex asked in PR
#23 whether the field earned its place — carried "for diagnostics", but §4.6's log may not record a
full BSSID and the field dies with the anchor, so nothing could consume it. The answer is that
diagnostics was never the interesting use: **a room is smaller than an SSID**. In an office the
whole floor is one network, so SSID loss cannot tell you that you left the room you were sitting
in, and room-scale is exactly the case a "quiet until I leave here" snooze is for.

What the platform will and will not give us, because it bounds the design:

- **The connected AP is cheap.** `WifiInfo.getBSSID()` costs one read, needs no scan, and rides the
  `ACCESS_FINE_LOCATION` we already hold for the SSID (§6.4). Signal strength on that AP
  (`getRssi()`) is equally cheap.
- **"Still in range" is not directly askable.** That needs scan results. `startScan` is throttled
  to 4 calls per 2 minutes (§6.4) and costs real battery; cached `getScanResults` is free but its
  freshness depends on whatever else on the phone happened to scan, so an absent AP means "nobody
  has seen it recently", not "you left". Usable as corroboration, never as the trigger.
- **A BSSID change does not mean you moved.** Phones roam between APs while sitting still, and band
  steering moves a phone between the 2.4 and 5 GHz radios of *the same* AP, which present different
  BSSIDs. This is the original reason SSID is the anchor, and it does not stop being true. The
  maintainer's framing is the more general one (2026-08-13): *"we could switch networks without
  moving"* — a hop to a guest SSID, or off Wi-Fi and back, reaches the same place without roaming
  being involved at all.

**One BSSID, the connected one, captured at arm time.** `Anchor.bssid` is a single value, not a
list: nothing scans, so Snoozemo knows which AP it is associated with and nothing else about the
room. The check that follows is correspondingly narrow — compare the currently-connected BSSID
against the anchor's, and act only on a difference.

So the rule is D4's asymmetry again, one level down: **a BSSID change is a hint that triggers a
check, never a departure on its own** (confirmed by the maintainer, 2026-08-13: *"keeping it as a
check again event is good for now"*).

**But the check it triggers cannot be §6.6's** (Codex, PR #24), and an earlier draft of this
section said it could, which was wrong in a way worth keeping written down. §6.6 is a *location*
test: a fix outside the anchor's 150 m radius, plus hysteresis and confirmation. Walking out of a
meeting room and down the corridor is ten meters. **§6.6 as it stands cannot resolve a room** —
that much is true by construction, not by argument. Reusing it here was a plausible-sounding
shortcut that quietly made the feature impossible.

**Whether *some* location test could contribute is open, and an earlier draft closed it too
early** (Codex, PR #24). That draft reasoned from `MAX_ANCHOR_ACCURACY_M = 200f` to "location
cannot resolve a room, at all" — but that constant is the *rejection ceiling*, the worst fix the
capture will accept, not the accuracy a fix actually has. A qualifying fix can be far better than
200 m, so the ceiling proves nothing about the best case. What §6.6 actually rules out is its own
150 m radius and its balanced-power request; a tighter gate with a higher-accuracy request is a
different test that has not been evaluated.

It should be evaluated **before** the D4 question below, because if location can corroborate at
room scale then D4 survives untouched, which is a better outcome than an exception to it. Two
things decide it, and both are measurements rather than arguments: **how accurate an indoor fix
actually is** in the buildings this is for, and **what a higher-accuracy duty cycle costs** against
§9's budget — plausibly affordable here, since a room snooze is a meeting rather than eight hours,
but that is a guess until measured. The office walk `TODO.md` already asks for should record fix
accuracy alongside AP roaming, since one trace answers both.

Failing that, the room case needs **its own exit criterion resting on Wi-Fi alone**: the connected
BSSID differing from the anchor's, held for a dwell (the maintainer's "5 minutes or something"),
possibly corroborated by signal strength on the anchor AP falling away first (`getRssi()` is as
cheap as the BSSID read) or by the anchor AP's absence from cached scan results. It is **not
designed yet**, and this section deliberately does not pretend otherwise.

**That criterion contradicts D4, and reconciling them is the maintainer's call, not this
section's** (Codex, PR #24). D4 and §6.3 are unambiguous: Wi-Fi is a suppressor and an escalation
hint, and a snooze never ends on Wi-Fi loss alone — because routers reboot, bands drop in far
rooms, and phones hop to captive portals, any of which would end a snooze wrongly. A room-scale
verdict resting on Wi-Fi alone does exactly what D4 forbids. Whether that is *unavoidable* depends
on the measurement above: if a tighter location test can corroborate at room scale, D4 is satisfied
and none of this arises.

The argument for a scoped exception is the **failure direction**, which is not symmetric at the two
scales. A false "you left" rings a phone in a meeting that had a minute to run — an annoyance. A
false "you're still here" leaves someone silently unreachable, which is principle 1's failure. The
cheap signal errs toward the first, and the duration cap backstops both. D4's rationale is written
about the place-scale snooze, where a spurious end wastes the whole feature; it may or may not
survive being applied to a room the user opted into.

So v1.1 needs, in order: **measure indoor fix accuracy** and see whether a tighter location test
corroborates a room exit; and only if it does not, **D4 amended with a room-scoped exception**
(stated as a decision with its reasoning, per the way every other reversal in this document is
recorded) or the feature dropped. The amendment is not made here — autopilot does not get to
quietly widen a numbered decision, and "the room case needs it" is an argument for the maintainer
to weigh rather than a licence.

Scoped as **v1.1 or later**: v1 keeps capturing the field and does not act on it. `docs/PRIVACY.md`
describes it as captured-and-unacted-on until the feature lands, which is honest and becomes wrong
the moment it does — so the feature's PR updates the policy in the same change.

### 6.3 Signals and their asymmetry (D4)

| Signal | Meaning | Action |
|---|---|---|
| Associated with anchor SSID | Strong evidence **still here** | Suppress location updates entirely |
| Anchor SSID lost | Weak evidence of leaving | Escalate to `CHECKING`, do not end |
| Significant motion fired | Might be moving | Escalate to `CHECKING` |
| Fix outside radius + hysteresis | Evidence of leaving | Confirm, then end |
| Fix inside radius | Still here | De-escalate to `ARMED` |
| Fix that places nobody | Nothing either way | Stay where we are; report if it persists |
| Geofence exit while still associated | The two subsystems disagree | Escalate anyway; a fix settles it |

Two rows there are easy to get wrong in the same direction. A reading whose uncertainty swallows the
whole question — 400 m out with ±500 m of accuracy — is not weak evidence of presence, it is no
evidence at all, and folding it into "still here" would have the duty cycle back off precisely when
tracking has stopped working. And a geofence exit that arrives while the phone is still on the
anchor's network is worth one location request rather than a shrug: the cost of checking is a fix,
the cost of trusting Wi-Fi blindly is a departure the app never notices.

The asymmetry matters. Wi-Fi dropping is a terrible departure signal on its own — the router
reboots, the user toggles Wi-Fi off to save battery, the 5 GHz band drops in a far room, the phone
switches to a captive-portal network. Any of those would end the snooze wrongly. But Wi-Fi *staying
associated* is excellent evidence of presence, and it is free. So it earns its place as a
power-saving suppressor and an escalation hint, never as the thing that ends a snooze.

### 6.4 Wi-Fi API

`WifiManager.getConnectionInfo()` is deprecated since API 31. Use a network callback:

```kotlin
val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .build()
// FLAG_INCLUDE_LOCATION_INFO is not optional here — see below.
cm.registerNetworkCallback(
    request,
    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val info = caps.transportInfo as? WifiInfo ?: return   // API 31+
            onSsid(info.ssid.trim('"'))
            onBssid(info.bssid)                                    // §6.2, captured only
        }
        override fun onLost(n: Network) = onWifiLost()
    },
)
```

**The flag is load-bearing, and an earlier version of this snippet omitted it** (Codex, PR #24).
A `NetworkCallback` built with the no-argument constructor requests no location-sensitive data, so
the `WifiInfo` reached through `transportInfo` comes back *redacted* — the SSID as
`WifiManager.UNKNOWN_SSID` and the BSSID as `02:00:00:00:00:00` — regardless of the permissions
held. The failure is quiet: real objects, plausible strings, an anchor that never matches anything.
Note this is not a room-feature problem that arrived with §6.2's BSSID; **it breaks the SSID
anchor, which is v1's mechanism**. The flag needs `ACCESS_FINE_LOCATION` and location services on
to actually deliver the fields, which is the same gate the next bullet describes.

**Confirmed against the platform reference**, quoting the two pages that decide it:

> **`ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO`** (API 31) — "In Android 12
> and above, by default the sent objects do not contain any location information, **even if the app
> holds the necessary permissions** […] Without this flag any `NetworkCapabilities` provided via the
> callback does not include location sensitive information."

> **`WifiInfo`** — "In the connected state, access to location sensitive fields requires the same
> permissions as `WifiManager.getScanResults`. If such access is not allowed, `getSSID()` will
> return `WifiManager.UNKNOWN_SSID` and `getBSSID()` will return `"02:00:00:00:00:00"`."

The "even if the app holds the necessary permissions" clause is the whole trap: holding
`ACCESS_FINE_LOCATION` is not sufficient, so a permission audit finds nothing wrong.

**Still owed a device**, but for a narrower question than before: that the flag *plus* our
permissions and location-services state actually yields a real SSID on a handset, in both flavors.
The platform contract is settled; the end-to-end path is not. A test that accepts `UNKNOWN_SSID` as
a value would hide exactly this, so Phase 3's assertion rejects the placeholders rather than
tolerating them.

Two further constraints worth stating plainly:

- Reading SSID requires `ACCESS_FINE_LOCATION` **and** location services enabled, on all current
  versions. `NEARBY_WIFI_DEVICES` with `neverForLocation` does *not* remove that requirement for
  these APIs — it is for apps that manage Wi-Fi connections without deriving position. We are
  literally deriving position, so we declare `ACCESS_FINE_LOCATION` and do not declare
  `NEARBY_WIFI_DEVICES` at all. There is no permission saving available from the Wi-Fi route.
- `ACCESS_FINE_LOCATION` is a while-in-use permission. SSID reads from a true background state
  return a redacted `<unknown ssid>`. This is fine while our location-typed foreground service is
  running; it is the crux of the reboot problem in §8.3.

We do **not** scan for nearby networks (`startScan`/`getScanResults`). Scanning is throttled to 4
calls per 2 minutes for foreground apps since Android 9, costs meaningfully more battery, and the
connected-network signal plus location already covers the cases we care about.

### 6.5 Location API

Used by the `direct` flavor, which cannot register geofences (§3.1). The `play` flavor uses the
Geofencing API instead and needs none of this.

```kotlin
LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 90_000L)
    .setMinUpdateDistanceMeters(50f)
    .setMinUpdateIntervalMillis(30_000L)
    .setWaitForAccurateLocation(false)
    .build()
```

`BALANCED_POWER_ACCURACY` is roughly city-block accuracy from Wi-Fi and cell, without waking GPS —
correct for a 150 m decision boundary. `HIGH_ACCURACY` would burn GPS to answer a question that does
not need GPS.

If Play Services is absent (unlikely on Pixel or Samsung, but the app should not hard-crash), fall
back to `LocationManager` with `PROVIDER_FUSED` on API 31+, or `NETWORK_PROVIDER` below that.

### 6.6 The departure test

Never compare raw distance to radius; a 500 m-accuracy cell fix "outside" a 150 m radius means
nothing. Gate on accuracy:

```kotlin
fun isOutside(fix: Location, a: Anchor): Boolean {
    val d = haversine(fix, a)
    return d - fix.accuracy > a.radiusM + HYSTERESIS_M      // HYSTERESIS_M = 50
}
```

Then require **confirmation**: two consecutive qualifying fixes at least 30 s apart, *or* one fix
where `d - accuracy > radiusM + 500`. The first rule kills GPS-jump false positives; the second
means that when you are unambiguously a kilometer away, the phone comes back immediately rather than
making you wait out a debounce.

Anchor with no location fix at all (arming indoors with no signal): Wi-Fi-only mode. Losing the
anchor SSID escalates, but with no location to confirm with, resolve after a 5-minute grace period
in which Wi-Fi does not return — then end (D7, fail open).

**The same grace period covers a snooze that *becomes* unverifiable, not only one that armed that
way.** A snooze can arm with good coordinates and then lose both signals — walk out of a building,
the anchor's network goes, and every fix since has been too vague to place anyone. That state is
indistinguishable from the Wi-Fi-only one in the only respect that matters: nothing left can confirm
a departure. Without this, the ordinary route into the worst case would be silence until the
duration cap, hours later, for a user who left the building five minutes in. So the grace period
starts when location gives up rather than only when it never started, and it is called off the
moment either signal answers again.

**A dead location grant calls grace off rather than running it** (landed 2026-08-30). The grace
period is a bet that Wi-Fi's silence means something, and under a missing location grant it does
not: reading an SSID needs `ACCESS_FINE_LOCATION` too, and a background read without it comes back
redacted, which the Wi-Fi watch reports as the anchor's network having gone away. So a snooze whose
permission was revoked — or which never had the background grant — would be told the network
vanished whether or not the phone moved, and would end five minutes after the *permission* changed.
That is the ending §8.2's degrade-to-timer decision exists to prevent, arriving by another door. The
monitor therefore tells the engine outright when it classifies a grant-shaped refusal, which clears
any running deadline, cancels the real alarm, and arms no further one until something proves the
grant is back. That proof is a geofence registration the platform accepts, and only that: it
requires `ACCESS_BACKGROUND_LOCATION` outright on API 29+, so it cannot succeed unless both grants
are genuinely held. The two proofs the engine could see for itself were tried and are both wrong —
a delivered fix can be cached from before the revocation, and a nameable SSID proves a *revoked*
grant is back while proving nothing about a missing background one, since the app reads the SSID
fine in the foreground under a while-in-use grant and redacted again the moment it is not. One
proof, refuting the same level the monitor already withdraws on it. The snooze runs on the duration cap in the meantime, which is mandatory, so
the fallback is bounded by construction. The mode says `Timer only` and names the missing grant;
without the cancellation it would say that while an alarm quietly ended the snooze anyway, which is
why the two land together.

**The deadline survives the process dying, which it does routinely with no foreground service**
(landed 2026-08-23). The real countdown is a platform `AlarmManager` alarm, which is durable on
its own; what is not is the engine's own memory of *why* — a service killed mid-grace and
restarted comes back with no record of the deadline unless something persisted it, and without
that a due alarm reads as a stale one and the snooze runs to the duration cap instead, silently
losing exactly the five minutes this section promises. The deadline is written to disk as a
wall-clock instant on every change and read back translated into whichever boot is asking,
resuming the original countdown rather than restarting a fresh one — the same failure the earlier
mitigation (re-arming a fresh five minutes on every restore) only bounded rather than closed.
Deliberately **without** the duration cap's defense against a backwards clock change (§7,
`ActiveSnooze.bootReference`): the cap is the backstop with nothing above it and needs that
defense; the grace deadline is a softer mechanism the cap already bounds regardless, so a clock
wound back during an outage can make grace run longer than five minutes, but never longer than
the cap itself.

**A grace alarm that restores the snooze must not end it before the Wi-Fi watch can say the user
came back** (landed 2026-08-24). When the grace alarm is what wakes a dead process, the restored
monitor rebuilds the Wi-Fi watch and replays the due deadline in the same breath — but the watch's
synchronous seed cannot name the network (`getNetworkCapabilities` redacts the SSID; only the async
callback sees it), so a user who returned to the anchor's Wi-Fi during the outage is not yet known
to be back at the instant the deadline resolves. Left alone, the replay ends a snooze the user
returned to. So the seed reports a third state beside present/absent — *Wi-Fi is up but unnamed* —
and a due deadline meeting it **defers once** for a short confirmation window (30 s, measured from
when the restored monitor actually handles the firing — not the alarm's fire time, which a slow
restore can leave tens of seconds stale) rather than ending, giving the async callback time to
confirm the return and call the deadline off. If the
callback instead names a different network, or none comes, the window's own firing ends the snooze:
fail-open is preserved (D7), delayed by the window, never lost. This closes a race that predated
the durable Wi-Fi watch — the phone rejoining its own network mid-outage silently ended the
snooze — surfaced when that watch made the redaction limit explicit. **"Once" is durable, not
per-process:** whether the deferral has been spent is persisted beside the deadline, because a
process death inside the window would otherwise let the restored seed grant a fresh deferral and
extend the deadline again on every reclamation — holding DND to the cap, the opposite failure. The
bound resets only when the deadline itself clears (a genuine recovery), so a later outage is a new
episode that earns its own single deferral.

**The notification says so while grace runs, not just once it ends** (landed 2026-08-23, Codex, PR
#31). `TrackingMode.WIFI_ONLY` means "Wi-Fi is what's tracking this" — but the grace period exists
precisely because Wi-Fi just stopped being able to say that, and reusing the same label for both
told a user mid-outage that something was watching when nothing was. `TrackingMode.WIFI_GRACE`
(`Wi-Fi lost — ending soon`) is the distinct claim for exactly that window: unverifiable, and ending
automatically unless Wi-Fi or location recovers first. It is not a new capability tier — nothing
watches it that does not also watch `WIFI_ONLY` — so `SnoozeController.honest()` treats the two as
standing or falling together rather than degrading the grace label to `DURATION_ONLY` for want of a
monitor that names it explicitly. The engine reports whether grace is running as a level
(`PresenceUpdate.graceActive`), restated on every update exactly like `degradation` and for the same
PR #33 reason: an announced-once event is a race waiting to lose to whichever ordering swallows it.
Restated levels never go backwards either (2026-09-04): the monitor's platform callbacks arrive on
more than one thread, so two transitions can be computed in one order and reach the controller in
the other, and a superseded update publishes its **event** on the newer levels rather than its own
— a departure decided a moment earlier is still a departure, while the grace and suppressor state
the controller already holds is never rewound to what an older signal saw.

**A run of readings that place nobody is reported, not absorbed.** One vague fix is ordinary —
walking past a lift shaft produces one. Several in a row means location has stopped answering, and
the user is owed that in the notification (§8.1) rather than left with a snooze that looks tracked
and is not. The count is what distinguishes the two; reporting the first would make the line noise,
and noise is how a user learns to ignore the line that matters.

### 6.7 Duty cycle

This is where the battery budget is won:

- **Associated with anchor SSID** → no location updates at all. Sleep on the network callback.
- **Not associated** → register `Sensor.TYPE_SIGNIFICANT_MOTION` via
  `SensorManager.requestTriggerSensor`. It is a hardware-backed one-shot trigger, requires **no
  permission**, and costs approximately nothing. While it has not fired, the phone has not moved, so
  poll location slowly, purely as a sanity check — at the resting cadence below.
- **Significant motion fired** → switch to the 90 s request above until the state resolves, then
  re-arm the trigger.

A phone sitting on a desk for four hours therefore does essentially no location work.

**The resting cadence is per flavor, and on `play` it is the §6.10 backstop's** (maintainer,
2026-08-30). This section was written for the foreground-service design, where the process stays
alive and an in-process 10-minute timer is exactly right — which is `direct`'s shape from Phase 7,
and there the 10 minutes stands. `play` runs no foreground service (§3.4): the process is reclaimed
within about a minute of each wake, so an in-process timer would almost never fire, and the only
mechanism that would actually deliver a 10-minute cadence is a repeating alarm — roughly **6 wakes
an hour against the backstop's 2**, each one a service start and a location request, for a snooze
that can run eight hours. That is a real charge against §9's budget, and the resting state is
precisely where §9 is meant to be won.

So `play` schedules no resting poll of its own: the backstop's own wake carries the resting probe,
and the resting cadence is therefore ~30 minutes. **This holds whether or not the device has a
significant-motion sensor** — the maintainer's call, and the point worth stating, because the
tempting middle option is to schedule the alarm only where the sensor is missing. That was
declined: the sensor is nearly universal, so the alarm would exist for a rare device while
complicating the duty cycle for every device, and a resting snooze on such a device is still
bounded — the geofence and the Wi-Fi watch are both unaffected, since the poll is a *sanity check*
behind them, not the mechanism. What such a device loses is escalation latency while resting away
from the anchor's Wi-Fi: it waits for the backstop rather than for motion. The duration cap is
unchanged and remains the only hard bound (D7).

### 6.8 Foreground service

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- Landed 2026-08-31 (§4.3). Requested from the permissions screen's own
     row, never during onboarding; the feature hides itself if denied. -->
<uses-permission android:name="android.permission.READ_CALENDAR" />

<service android:name=".presence.SnoozeService"
         android:foregroundServiceType="location"
         android:exported="false" />
```

`startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)`. The `location`
type has **no Android 15 timeout** (unlike `dataSync`, which is capped at 6 hours) and is **not** on
the Android 15 list of types banned from `BOOT_COMPLETED` receivers. Both facts matter for a service
that may legitimately run for the 8-hour cap and needs to survive a restart.

### 6.9 Starting the service from the tile — a real constraint

`TileService.onClick` is **not** on the documented list of exemptions for starting a foreground
service from the background. In practice the tile's process is bound at foreground importance and a
direct `startForegroundService` usually works, but "usually" is not a design.

**Use an invisible trampoline activity.** Activities *are* a documented exemption, for both the FGS
background-start restriction and the while-in-use permission restriction:

```kotlin
// TileService.onClick
val pi = PendingIntent.getActivity(this, 0, Intent(this, TileTrampolineActivity::class.java),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
startActivityAndCollapse(pi)   // PendingIntent overload, API 34+; Intent overload deprecated in 34
```

`TileTrampolineActivity` starts `SnoozeService` in `onCreate` — before any UI — so arming never waits
on rendering. The FGS start and the subsequent location access are then both squarely inside
documented exemptions.

**Ending goes through it too.** An earlier version routed `End now` straight to the service on the
grounds that stopping work is unrestricted — which confused *stopping* a service, something the app
may always do, with **starting** one to ask it to stop, which is what an end tap does once the
system has killed the service mid-snooze. That start is subject to the same background restriction
as the arm's, and a refusal there is not a slow exit but no exit: the tap the user reaches for to
get their phone back is the one that must never fail (§7).

**Anything else this activity does is queued, not called.** `startService` does not run the service:
it is a binder round trip into `ActivityManagerService`, which comes back through a oneway
`IApplicationThread` callback that posts `onStartCommand` to this looper from a binder thread. So
work done synchronously after that line — including the binder call inside a permission request —
runs *before* the arm rather than after it. The converse does not follow: nothing orders the arm
ahead of a block this activity posts, so a later read of the snooze record is a best-effort one. The arm keeps
the thread; everything else takes what's left.

**The trampoline is also where the tile-first user is asked for notification permission.** The tile
can be added straight from the Quick Settings editor, so someone may arm many times without ever
opening the app, and the app screen's request never runs for them. Given §4.2 — the tile is 1×1 and
icon-only, so it carries no status — that user would have an armed snooze with no visible state
anywhere, and a failed arm with no explanation. This activity is the one place the tile-first path
passes through. It is skipped on the lock screen, where a dialog can't be answered and arming locked
is a supported case, and the platform's own two-refusal cap stops it becoming a nag.

It uses a **transparent** theme (`Theme.Material3.DayNight.Dialog` over a translucent window), not
`Theme.NoDisplay`, because it hosts the §4.4 sheet and the notification-permission dialog — neither
is possible from a no-display activity. It issues no runtime permission request of its own: the
`READ_CALENDAR` request belongs to the permissions screen (§4.3), deliberately off the arm path. It finishes as soon as the sheet is dismissed or a
row is committed, and finishes immediately in `onCreate` if the sheet is disabled in settings.

This activity is on the critical path of the app's only interaction, so it carries a hard budget:
service started within one frame of `onCreate`, sheet rendered without a visible flash of a blank
window, and correct behavior when launched over the lock screen (§4.2).

---

### 6.10 Geofencing quality, and the fallback ladder

The `play` flavor's departure detection rests on the Play Services Geofencing API, so it is worth
being precise about what that API is and is not good at — this is the difference between the app
feeling reliable and feeling haunted.

#### Battery: genuinely cheap

Low, and for a structural reason rather than a tuning one:

> "On most devices, the geofence service uses only network location for geofence triggering. The
> service uses this approach because network location consumes much less power, it takes less time
> to get discrete locations, and most importantly it's available indoors."

No GPS wakeups. Registration is handed to a system process that is already computing network
location for other reasons, so an idle geofence is close to free — well under 1% for a 4-hour snooze,
and materially cheaper than the `direct` flavor's foreground service. `setNotificationResponsiveness`
trades latency for power on top of that; at our 150 m radius the default is already fine and there is
little left to win. Battery is **not** the reason to worry about this API.

#### Reliability: the actual risk

The same design choice that makes it cheap makes it fragile, and the documentation is candid:

- **Wi-Fi off** — "your application might never get geofence alerts", depending on radius, device
  model, and Android version.
- **No data connection inside the fence** — network location needs one, so "alerts might not be
  generated."
- **Latency** — usually under 2 minutes, but 2–3 minutes under Background Location Limits, and up to
  ~6 minutes if the device has been stationary.
- **OEM battery management** — One UI's Sleeping Apps degrades this further (§10).

And the field reports are worse than the documentation: geofence transitions firing hours late, or
not at all, with Samsung on Android 12 singled out repeatedly. Assume some of this is still true.

The irony is exact: geofencing is least reliable when Wi-Fi is off, which is precisely when
Snoozemo most needs it, because our Wi-Fi suppressor (D4) has nothing to work with either.

#### Do not rely on the geofence alone

Treat the geofence as **one wake-up source among three**, not as the mechanism. All three are cheap,
and they fail independently:

1. **Geofence exit** — primary. Also a documented exemption for starting a foreground service from
   the background, so its callback can start a short-lived service to confirm.
2. **Wi-Fi loss** (`NetworkCallback.onLost`) — free, instant, and completely independent of Play
   Services. In the `play` flavor we already hold `ACCESS_BACKGROUND_LOCATION`, so this can trigger a
   one-shot `getCurrentLocation()` and run the §6.6 departure test directly, without waiting for the
   geofence to notice. This is the single highest-value addition, because it covers the common case
   (leaving a Wi-Fi place) with no reliance on the flaky path at all.
   **Built**, as D4's watch in the `play` monitor: a `NetworkCallback` on the Wi-Fi transport
   feeds the association and its loss into the engine — association suppresses location work
   entirely (including the backstop's resting probe), loss escalates into the same §6.6
   confirmation as every other source, with the checking burst's one-shots as the fix request.
   The §6.6 **grace alarm** landed with it, because a Wi-Fi-only mode is dishonest without it:
   the engine sets the deadline but cannot wake a phone, so the monitor arms a real alarm —
   inexact (`setAndAllowWhileIdle`), like the cap and for the same reason: exactness costs the
   `SCHEDULE_EXACT_ALARM` permission, a distribution question, and the deferral concentrates
   while stationary. A watch that fails to register reports itself as a loss rather than
   holding a snooze on an unwatched claim — the fail-open direction. `WIFI_ONLY` is therefore
   a *watched* mode at last: an SSID-only anchor gets a real watch, and a fenced anchor that
   loses location degrades to Wi-Fi rather than to the bare timer.
   **The watch is in-process, and that needed a durable half** (landed 2026-08-24, from a field
   report). A `NetworkCallback` lives in a process; this flavor runs no foreground service (§3.4),
   and Android stops the snooze's ordinary service within about a minute of the app going to the
   background — so the watch closes with it. A fenced anchor loses nothing, because the fence is
   registered with the system and outlives the process. An anchor with **no usable fix has no
   fence**, so its snooze was left with nothing listening at all: the user walked out, the phone
   moved to mobile data, and nobody noticed until the 30-minute backstop happened to wake — or,
   deferred in Doze, until the duration cap. Nothing event-driven closes this. Android delivers no
   implicit Wi-Fi or connectivity broadcast to a manifest receiver, and the `PendingIntent` form of
   `registerNetworkCallback` corresponds to `onAvailable` only, so there is no durable "this network
   went away" to subscribe to. The stand-in is therefore a repeating alarm (15 minutes,
   `setAndAllowWhileIdle`, which tends to fire sooner than the `WorkManager` period Doze defers to
   a maintenance window) that restores the service and rebuilds the watch to re-read the
   association — armed only where there is no fence, canceled when the snooze ends, and
   self-retiring, since only a running monitor arms the next one. For these snoozes §6.6's promise
   becomes *typically noticed within about 15 minutes, ended 5 minutes later*, where a live watch
   ends them 5 minutes after the loss itself. **Typically, not guaranteed**: a while-idle alarm's
   cadence is best-effort like the backstop's — deep Doze, a restricted standby bucket, or OEM
   battery management can push a firing well past the period — so the duration cap stays the only
   hard bound, and this shortens the common-case latency rather than bounding the worst case.
3. **A periodic backstop** — a coarse `WorkManager` check on the order of 15–30 minutes while armed,
   purely to catch a geofence that never fired. Cheap, and it keeps typical staleness to its
   cadence — best-effort, not a hard bound (see below); the duration cap remains the only
   absolute one.

Confirmation still runs through the one §6.6 test, so no source can end a snooze on its own evidence.
This layering is why the `play` flavor's departure latency should land near the `direct` flavor's in
the common case, despite the geofence's own numbers.

**The backstop (3) is built**, and its design is deliberately thin: a `WorkManager` periodic wake
(30 minutes — the coarse end of the range, matching §9's budget) that repairs nothing itself.
The period is a cadence, not a guarantee: `WorkManager` defers in Doze and under battery saver,
so the staleness bound is best-effort rather than hard. The deferral concentrates where it costs
least — Doze requires a stationary device, so the wake runs late precisely while the user is
still there, and the motion of actually leaving is what ends Doze and flushes the deferred work.
Battery saver and OEM throttling can stretch it further; the duration cap remains the only
absolute bound (D7).
Each wake restores the service. A cold restore — the process died — runs every repair a wake
performs: re-arm the cap, re-assert the rule, reconcile policy access, re-register the fence,
collect any held exit. A warm one re-checks the cap against the clock, re-registers a fence whose
registration failed, re-posts the ongoing card from the running record, and re-enqueues the
backstop itself; it deliberately does not touch the zen rule, which a running service already
owns. The re-post is what bounds two things the card otherwise had no wake for (2026-09-04): a
`notify` the platform refused, which on a duration-only snooze nothing else would retry before
the cap, and the ringer-shortfall clause (§5.9), which is read at the post and which nothing
listens for — so a phone turned back up above its ceiling now reads correctly within a wake,
warm or cold. It is a plain rebuild at about four binder calls, and the card is ongoing,
alert-once and stably timed, so nothing in the shade moves — and it is posted silently outright,
because after a refused post it is the platform's *first* sight of the card, which alert-once
does not cover, on a channel that bypasses Snoozemo's own DND. The same holds for every post
of the card but the arm's own: a restore's transition, a clock or tracking change, a late
notification grant — each can be the first post after a refusal or a reboot, and none of them
is the moment the user tapped. Either way the wake asks
the presence monitor for **one resting fix**, so a departure the geofence never reported gets
tested by §6.6 rather than waited out until the cap. The probe re-checks the location grants on
the way, which is what makes a mid-snooze permission revocation detectable at the backstop's
cadence: revocation kills the process, so no in-process watcher can exist, and a scheduled wake
is the only detector Android leaves. The backstop is never load-bearing — the cap alarm is the
floor and is armed independently — and it retires itself on a wake that finds no snooze, so a
cancel lost to process death costs one empty wake, not a standing drain.

**Recovering from a location-services outage is prompt where it can be, and the backstop's
otherwise.** When location is switched off mid-snooze the fence stops being monitorable and
fixes stop arriving (§8.4); the app says so and degrades, and the repair — re-register the
fence, take one fix — is exactly what a backstop wake already performs. What was missing was
anything listening for the outage *ending*, so a user who turned location back on waited up
to the backstop's cadence to be properly watched again. Snoozemo now watches the
location-mode broadcast while, and only while, it is holding such a degradation — sampling the
setting once as it starts, since the broadcast is not sticky and an outage reported late leaves
a change that has already happened — and pokes
that same repair the moment the setting comes back on — the fence unconditionally, since the
outage whose existence argued against re-registering into it has provably ended, and a
`GEOFENCE_NOT_AVAILABLE` broadcast otherwise leaves a fence unregistered with only the
services level to show for it. It decides nothing new: a re-registration that succeeds is what
clears the registration level, and only a delivered fix clears services-off, so the watch can
never promote a snooze on its own say-so.

**It does not force a fix past D4's suppressor, deliberately.** On the anchor's Wi-Fi the duty
is `NONE`, so no fix is taken and the services level stands until real evidence arrives — the
snooze reports degraded tracking while it is in fact watched, which is over-reporting in the
safe direction, and the backstop's own restore clears it. The alternative — one fix taken
purely to clear the label — is not a battery quibble but a correctness one: §6.6's test runs on
every fix whatever the association says, and its unambiguous shortcut ends a snooze on a
*single* reading beyond radius + 500 m. On a network covering more ground than the anchor's
radius that fix could end the snooze outright, with nothing having suggested a departure. D4
exists to refuse exactly that trade, and a housekeeping probe is the weakest possible reason to
make an exception to it. The broadcast
is implicit and so undeliverable to a dead process, which sets the honest limit: on `play`
this covers the window where the app is actually running — an arm with location off, or a
user reaching for the setting on the strength of the notification — and the backstop still
covers the rest; on `direct`, Phase 7's foreground service makes it cover the whole snooze.
It costs nothing while a snooze is healthy, because a healthy snooze registers no watch at
all.

#### To-do: explicit fallback end conditions

If, after measuring on hardware (see `TODO.md`, hardware verification item 2), geofencing is still unacceptable on Samsung, expose the two signals that
do not depend on it as end conditions the user can pick outright:

| Row | Mechanism | Honest assessment |
|---|---|---|
| **until Wi-Fi goes** | `NetworkCallback.onLost` on the anchor SSID | Instant, free, no Play Services, no location fix. But it inverts D4 — it *is* the failure mode we designed around, and it will end snoozes when the router reboots or you toggle Wi-Fi. Only defensible as an explicit user choice, where the user knows Wi-Fi is the real boundary for that place |
| **until I move** | `TYPE_SIGNIFICANT_MOTION`, no permission | Instant, free, works with no Wi-Fi and no data. But "moved" is not "left" — standing up ends it. Genuinely useful for "quiet while I'm sitting here", which is a real and different intent |

**Preference, in order.** First, fix it invisibly: the three-source layering above should absorb most
geofence flakiness without the user ever choosing a mechanism. Second, if a place is reliably bad,
have the app pick the fallback itself and *say so* in the sheet — `until Wi-Fi goes` shown in place of
`until I leave`, because the geofence has proven unreliable here — which keeps the user's mental model
about places rather than sensors. Only third, and only if both fail, expose them as standing options.

The reluctance is not aesthetic. Asking "until Wi-Fi goes, or until you move?" pushes an
implementation detail the user cannot evaluate onto the user, and the answer they want is always
"until I leave." But you are right that it may be needed, and the state machine already treats every
exit as a cap-lowering condition (§7), so adding these rows is UI work rather than architecture work.
Nothing here has to be decided now.

## 7. Exits

All three exits converge on one `endSnooze(reason)` path that sets the rule state `STATE_FALSE`,
stops the service, unregisters callbacks, cancels the cap alarm, and posts the ended-notification.
Idempotent; safe to call twice.

| Exit | Trigger | Notes |
|---|---|---|
| **Departure** | §6.6 confirmation | The intended path |
| **Duration cap** | Default 8 h, configurable 30 min – 24 h | Backstop for every sensor failure |
| **Manual** | Tile tap, notification action, or in-app | Always available, always instant |

A time chosen in the §4.4 sheet does not add a fourth exit — it *lowers the cap*. Picking 14:00 sets
`capExpiresAt` to 14:00 while departure tracking stays fully armed, so whichever comes first wins and
leaving early still ends the snooze early. The 8-hour default remains an absolute backstop above any
chosen value, and `+30 min` may not push past it.

The cap uses `AlarmManager.setAndAllowWhileIdle` — **inexact on purpose**. Exact alarms need
`SCHEDULE_EXACT_ALARM`, which is no longer auto-granted on Android 14+ and carries its own Play
policy scrutiny, and a cap that fires at 8h04m instead of 8h00m is indistinguishable to the user.

**The alarm is the cap; there is nothing behind it.** An earlier draft here described a coroutine
timer inside the service handling the normal case with the alarm as belt-and-braces. That timer was
never built and should not be: it dies with the process, so it would only ever cover cases the
alarm already covers, while making the alarm look optional. The consequence is a rule the code has
to hold: **a snooze whose cap alarm could not be scheduled does not arm**, and a reboot that cannot
reschedule it ends the snooze instead of restoring it (§8.3). A snooze with no time bound is the
one state this app must never reach.

**The cap is measured against the clock the user cannot move.** Both of its enforcers originally
rode wall time — an `RTC_WAKEUP` alarm at the record's expiry, and an expiry test reading
`Clock.systemUTC()` — so winding the date back in Settings moved both out together and kept Do Not
Disturb on past the 24 h backstop, indefinitely. That is principle 1's failure, two taps away. The
asymmetry matters, and it is about which direction is *dangerous* rather than what either one does:
a backwards change extends a snooze, which is the failure above; a forward change can only shorten
one, so it needs no defense. Only backwards is worth defending against.

Neither clock alone can fix it. `SystemClock.elapsedRealtime` counts from boot across sleep and
nothing can move it, but it resets on every boot, so nothing written in it survives one — and the
record must survive reboots (§8.3). Wall time is the only frame that does survive, and it is the
frame the user can move. So the record stores its deadline in wall time **and** the offset between
the two clocks (`wall − uptime`) that was in force when it was written, and every cap decision takes
the **smaller** of what the two clocks then say is left. That single rule is exact whenever either
clock is trustworthy — uptime is exact when the clock has moved, wall time is exact across a reboot,
and across a reboot the uptime answer can only ever *overestimate* what is left, so it never wins
the comparison wrongly — and where neither is trustworthy it errs toward ending the snooze (D7).

**Every reboot restates the offset**, and the minimum rule depends on it. A reboot resets uptime, so
the stored offset describes a boot that no longer exists and the deadline has only wall time left to
be read against — which holds until the wall clock moves, and then nothing catches it: reboot first,
wind back second, and *both* answers are too high, so their minimum is too high as well. The boot
receiver therefore writes the new boot's offset back to the record before it re-arms anything. The
deadline itself is untouched, since wall time is the trustworthy frame at that moment and
recomputing it would make an already-overdue cap look fresh.

**A restate that cannot be written ends the snooze**, the same answer as a cap that cannot be
scheduled. A stale offset is not a harmless degradation to wall-clock-only — it is still *believed*
by every later reader, so a backwards change afterwards leaves both answers too high and any re-arm
off that record schedules the cap past its own deadline rather than at it. Nothing after the fact can
restore a trustworthy frame; the write is the only way to keep one. So the snooze ends rather than
running on a bound nothing can rely on (D7).

What that cannot cover is a clock moved *while the phone was off*, or a phone rebooted and left
locked so the receiver never runs (`TODO.md` tracks the latter with the direct-boot work it shares a
cause with). Wall time is then already wrong when it arrives and there is no second frame left to
check it against; the 24 h ceiling is the only remaining backstop, and a smaller shift is not
detectable at all.

**A clock change is itself a wake-up, and it may only end a snooze — never extend one.** Counting in
elapsed realtime is what stops a backwards change pushing the alarm out, and the price is the other
direction: a clock moved *forward* past the deadline does not move the alarm either, so the snooze
would run to its original real duration while the ongoing notification — which ticks against wall
time — sat at zero. A countdown that finished over a phone that is still silent is principle 2's
failure, so `TIME_SET` re-checks the cap and ends the snooze if it is due. It fires only when
something actually sets the clock, so it adds nothing to §9's battery budget on an undisturbed
phone.

It also **writes the record back**, and that is the half that is easy to miss. A backwards change
leaves uptime as the only frame that knows how long is really left — and that knowledge lives in
memory, as the gap between the stored offset and the current uptime, which the next boot destroys.
The boot would then find an untouched, now-inflated wall deadline, adopt it, and hold Do Not Disturb
open by exactly the size of the shift. So the remaining time is folded back into wall time while wall
time can still be read. A forward change is a no-op here by construction, since wall time is already
the smaller of the two.

**The 8-hour backstop moves with the deadline**, which is why the record carries it rather than
measuring it from the moment the snooze started. The start time is the snooze's identity — every
queued retry names it — so it stays where it is when the deadline is restated, and a backstop
derived from it would be left in the frame the user just left, holding the whole shift as slack for
`+30 min` to spend. Wound back three hours, an eight-hour snooze could then be extended to eleven,
which is the backstop failing at exactly the button §4.3 bounds.

**The change is performed through the running service wherever one will start**, and only performed
by the broadcast receiver itself when the service refuses. The record and the controller are two
copies of the same snooze: writing only the first repairs the record and leaves the running snooze
carrying the pre-change deadline, which the next thing to write from memory — `+30 min` — puts
straight back on disk. A repair the button beside it undoes is not a repair. What a clock change
*means* is therefore decided in one place and performed in two, so the no-service path, which no
device reaches on purpose, cannot drift from the one that matters.

**A record with no stored offset does not survive a clock change.** Such a record is read against
wall time alone, and wall time is exactly what has just moved — by an amount and in a direction
nothing can recover, since there is no second clock left to compare against. Restating it would only
stamp the moved reading as though it had been measured, handing every later reader a deadline that
*looks* framed and is not; the 24 h ceiling catches a shift big enough to make the deadline
implausible, and a three-hour one sails past it. So the snooze ends instead (D7), which is the same
answer the boot receiver gives when it cannot restate its offset. Only records written before the
offset existed can reach this, and only at the moment the clock is set.

It deliberately does **not** re-arm from the record as found. Recomputing the delay is only
trustworthy while the record's offset describes the current boot, and the two cases above are
exactly when it does not — so against a stale offset a backwards change would produce a *longer*
delay than the one already armed, and re-arming would replace a correct alarm with an overlong one,
reintroducing the overrun. Leaving the existing alarm alone cannot do that: it was armed in elapsed
realtime and nothing since has moved it.

**Amended 2026-08-21: the cap is re-armed immediately after a successful restate — that is the one
moment the rule above does not apply, and it closes the precision this section previously gave up.**
A forward jump that did not clear the deadline used to leave the countdown reading short of what
the alarm would honor: the record and notification showed the shortened wall answer while the alarm
still counted the original elapsed delay, so the countdown hit zero over a phone that stayed silent
until the alarm fired. Once the restated record has reached disk its frame is provably fresh — the
write is this instant's — so a delay recomputed from it is the true remaining, and re-arming pulls
the alarm in to the moment the countdown now names. The stale-offset trap cannot reach this: an
armed alarm implies an offset restated for this boot (every armer writes the frame it uses, and a
boot that cannot restate ends the snooze), and where the offset really is stale no alarm survived
the reboot at all, so even an overlong re-arm adds a bound where none existed. A restate that fails
to write still ends the snooze, and so does a re-arm the platform refuses, for the same reason: the
record would then promise a deadline the scheduled alarm will not honor, and after a forward jump
the countdown would sit at zero over a silent phone for the size of the shift. A cap that cannot be
scheduled where it is promised ends the snooze, and the ended notification is the account the user
gets of it.

A process-wide anchored clock — wall time sampled once at process start and advanced by uptime —
reads as the obvious fix and is a trap worth recording, because it was tried. The record outlives
the process, so a deadline stamped in one process's anchored frame is read by the next process
against a different anchor, and the mismatch is a cap that overruns by however far the clock moved.
Anchoring hides the clock change instead of measuring it.

Records written before the offset existed carry none, and fall back to wall time alone — the
behavior they were already relying on. For those, a deadline further off than the 24 h ceiling is
treated as already fired: no snooze can legitimately have more than the maximum cap left, so a
record claiming more is a clock that moved while nothing was alive to notice, and how much real time
actually passed is unknowable.

**A release the platform refuses keeps the snooze — unless there is nothing left to release.** The
record is what retries: it holds the cap alarm, the notification, and the tile's `Snoozing`, so the
next cap check or tap tries again, and clearing it would leave the rule on with nothing that knows
to retry. But that reasoning only holds while a rule still exists to drive. Where the failure says
otherwise — policy access revoked (the platform deletes the app's rules), the rule missing, the
rule switched off — every retry would fail identically forever, and keeping the record strands the
app claiming `Snoozing` over a phone that is already ringing. So those complete the end. Only a
platform refusal of a rule that still exists is worth retrying.

Not implemented: the screen-unlock check-in from the original options. It is a worse version of the
location check — same information, more false positives, more code.

### 7.1 The escalation ladder is one decision, not five copies

> **Status: built.** `ReleaseEscalation` in `:core`, with the service and the no-service
> receiver path as its two performers.

When a release is refused, the app escalates: store the obligation, schedule an alarm, retry in
process, tell the user, give up. That sequence used to be written out **separately in five entry
points** — the service's recordless and recorded escalations, the cap alarm's receiver, the boot
receiver, and the trampoline's no-service fallback — because each is reached when a different
mechanism has failed and none can assume the others are available.

That duplication is the design problem. Every copy has to independently remember the same
non-obvious rules, and over one review pass of this code, nine separate findings were instances of
one of them being forgotten: persisting *before* yielding rather than at the give-up, checking
whether a write or a post actually took rather than that it was attempted, retiring the previous
successor before arming a new one, clearing the obligation when the rule is confirmed off. Four of
those were introduced by the fix to the previous one. A ladder whose whole purpose is "never leave
the phone silently quiet" cannot be a shape where each site is trusted to remember.

**So the ladder becomes a pure decision in `:core`** — the current state of what has actually
succeeded in, the next step out — with each call site reduced to *advance, perform, report the
result*. Three properties make it worth the move:

- **The ordering is stated once.** The obligation is durable and everything after it is not: an
  alarm can be canceled by an unrelated cleanup, and an in-process retry dies with an ordinary
  started service. So it is written first, always, and the ordering is a property of the decision
  rather than a comment repeated five times.
- **The inputs are "did this take", never "did we try".** A refused `commit`, an alarm the platform
  declined and a `notify` that threw are all *not done*, and each one has to move the ladder on
  rather than be assumed.
- **Running out is a named state.** `Exhausted` is reachable and explicit, so no path can fall off
  the end while still telling the user something is trying — which is the specific dishonesty
  §4.5's copy rules exist to prevent.

The testing argument is the strongest one. **None of these branches is reachable without a platform
that refuses a zen write**, so no device test and no emulator can exercise them; as prose spread
over five files they were argued rather than executed, and review was the only thing checking them.
As a pure function over a state, the whole ladder is reachable from a JVM test, which is exactly the
reasoning §11 already applies to `SnoozeController`.

**Two performers, not five.** The service walks the ladder with a delayed callback as its
in-process rung; the receiver path walks the same ladder with *starting the service* as that rung,
one attempt rather than ten, because a broadcast receiver has neither the budget nor the lifetime
for more. Everything else — which alarm, when the user is told, when to stop — is the decision's,
not theirs.

**One input changes what a caller does rather than when**: whether a record still describes the
snooze. With one, the ladder asks for the identified "end it, whatever the clock says" alarm; with
none, the plain wake-up that can drive off a rule nothing on disk describes. Asking for the wrong
one is silent — a plain cap check on an early refused end restores the snooze, finds it unexpired,
declines to act, and has spent the retry.

---

## 8. Edge cases and failure modes

Every one of these resolves toward **ending the snooze** (D7). A phone that rings when it should have
been quiet is a small annoyance. A phone that stays silent through something important is the
failure that gets the app deleted.

### 8.1 Service killed by the system

Return `START_STICKY`. On recreate with a persisted active snooze: re-assert the zen rule state, and
attempt to resume presence monitoring. If location comes back denied — a while-in-use grant with
no background grant behind it — degrade to duration-only and say which grant is missing:
`Timer only — background location off`.

**Both anchor shapes get that, and reaching the Wi-Fi-only one takes a different instrument**
(2026-08-31). An anchor with a fix registers a geofence, so a revoked grant announces itself when
`addGeofences` is refused and a restored one when a registration succeeds. A Wi-Fi-only anchor
never calls either, so nothing told it — and the consequence was worse than silence, because D7
has a redacted SSID read as *not associated*: the same revoked grant that merely degraded a
fix-having snooze made a Wi-Fi-only one report a departure the user never made and end on the
five-minute grace. The monitor therefore asks the permission directly for that shape: when Wi-Fi reports a loss,
before that loss is spent; at restore; and again on each 15-minute Wi-Fi recheck, which is also
what refutes it — a latch nothing could
lift would silence a user who really did leave, the one direction principle 1 refuses. A live
permission read proves only that the two grants are held, so it clears a grant cause and never a
location-services outage; that stays for the fix that refutes it.

**Asking when the loss is reported is what covers a live snooze.** The recheck owns the periodic
case and the restore the cold one, but a user who revokes location while a snooze is running is
served by neither: the callback arrives redacted within moments, and a grace deadline armed on it
ends the snooze about ten minutes before the next recheck. The question is therefore asked on the
transition itself, and only in the latch direction — a loss is not the moment to declare a
restoration.

Restoring the grant **rebuilds the Wi-Fi watch**, because the grant returning does not repair what
its absence wrote: every redacted callback left the watch holding a placeholder for the network and
its tracker holding *not associated*, and no callback is dispatched merely because a permission was
granted. A real departure would then read as a repeat of the loss already reported and say nothing.
Re-registering is what fixes it — a fresh registration dispatches the current networks, unredacted,
into a tracker whose first report is a transition by definition (D7).
That holds for both anchor shapes (2026-09-04): a fenced anchor with an SSID carries the same watch
as D4's suppressor, the revocation poisons it the same way, and the registration that proves its
grant back dispatches no callback either — so the registration-success path runs the same
restoration, rebuild included, as the recheck does.

**A grant landing in the app is noticed by the app** (2026-09-04). Android broadcasts no
permission change, and a revocation kills the process, so nothing in the monitor can watch a
*permission* move; a restored grant was noticed only by the backstop's next wake or the 15-minute
Wi-Fi recheck, and with §6.6 grace shut for that whole window a user who left inside it stayed
quiet to the cap. Re-granting almost always happens in the app — the prompt, or Settings and back
— so the main screen's own permission reading is the detector: when either grant rises while a
snooze is running (an unread first reading counts, as the calendar's does — and a fine grant with
the background half still denied counts, since it turns "permission off" into "background
location off" on the card), it asks the service to re-check, and the running monitor re-asks each anchor shape the way it learned of
the loss — a fence through a registration whose latched refusal it can refute, a Wi-Fi-only
anchor through the permission read. It decides nothing new: the same refusals and reads that lift
the latch on the periodic paths lift it here, only sooner, and a cold start restores on the way
in instead. The limit is the location-mode watch's: a grant taken in system App info with the
app never opened again reaches nothing until the backstop, which stays the bound.

**There is no `Resume tracking` action, and the earlier design for one was wrong** (2026-08-30).
It reasoned that a notification-action tap is a documented while-in-use exemption and so "fully
restores tracking". The exemption is real but buys *location fixes* for its window, not a
geofence: geofencing requires `ACCESS_BACKGROUND_LOCATION` outright on API 29+, with no
foreground-context carve-out. Under a while-in-use grant the fence cannot register from any
context, so no tap could have restored it. Naming the missing grant is what the user can act on;
granting it is the fix, and there is nothing for the app to offer alongside.

**Re-asserting is for resuming, never for ending.** A wake-up whose purpose is to *end* the snooze —
the user's `End now`, or a boot that could not reschedule the cap — takes over the persisted record
without touching the zen rule, then turns it off. Re-asserting first would silence the phone for the
moment before the release, and a release refused after that leaves it silenced behind the very exit
the user chose. Ending needs the record, not the rule, and it never assumes what state the platform
was in.

**A released record says so on disk, because erasing it can fail.** The record is the app's evidence
that a snooze is running, so when a release succeeds but the erase doesn't, what is left behind is
evidence for something that is over — and every wake-up that isn't the erase retry restores from it,
re-asserting the rule and taking the phone quiet again until the old cap, with nothing the user did
behind it. So the record carries a released marker, written *before* the erase is attempted, and a
marked record reads as absent everywhere: restoring, adopting, refusing a duplicate arm, and the
tile. The marker is not a guarantee — it is another write to the same store that just refused one —
but it turns a likely wrong state into an unlikely one, and the cap still bounds whatever survives.

### 8.2 Permission revoked mid-snooze

`ACCESS_NOTIFICATION_POLICY` revoked: end the snooze, notify with the reason. Nothing is silencing
the phone any more, so there is nothing left to keep running.

**Location revoked or downgraded to coarse no longer ends the snooze** (maintainer, 2026-08-30).
It degrades to duration-only and says `Timer only — location permission off`. The old rule ended
it, reasoning from D7 that tracking we cannot do is the ambiguous state to resolve toward ending.
What that reasoning left out is the **duration cap**: it is mandatory and user-set (§4.4), so
duration-only is bounded by construction — the backstop principle 1 names is already in place.
Ending on top of it bought no safety the cap did not already give, and cost the user the snooze
they asked for. The card names the missing grant, so this is a degradation the user can act on,
not a silent limp.

**Wi-Fi is not the fallback here, and cannot be.** Reading an SSID needs `ACCESS_FINE_LOCATION`
and location services on (§6.4) — there is no separate Wi-Fi permission — so a revoked grant takes
Wi-Fi with it, and a background read under a while-in-use grant returns the redaction placeholder,
which the tracker reads as *not associated* by design. A `WIFI_ONLY` claim in either state would
report a departure on every wake with the phone sitting on its own network. Both grant-shaped
degradations therefore go straight to duration-only, whatever the anchor holds.

**A withheld network name is the detection, not a probe for why** (maintainer, 2026-08-31).
Every route to an unreadable SSID — a revoked grant, a while-in-use grant read from the
background, the system location switch off — hands back the same placeholder, and each one
blocks location fixes too, so none of them leaves a signal to fall back to. Asking *which*
means keeping a list of platform gates current, and the list was already incomplete: a
services outage held both grants, so the permission probe found nothing wrong and let the
loss through as a departure. So Snoozemo reads the refusal itself. A redacted read declares
that location access is gone, which shuts the §6.6 grace period; naming the cause is a
separate, best-effort step that only decides what the card says, and it always names
something. The snooze then runs duration-only and says so, which is what it was already
owed — the change is that nothing has to explain the outage before that can happen.

**The declaration follows the loss it explains, and must.** The obvious order is the wrong
one: reporting the refusal first runs the whole recovery path — including the checks that
can immediately declare the outage over — ahead of the departure that motivated it, so the
latch is raised and dropped and only then does the loss arrive, with nothing left
suppressing grace. Declaring afterward is safe because the engine *withdraws* a deadline
rather than declining to arm one, so the loss arms grace and the declaration cancels it,
durably. Every path that records one of these causes must deliver that declaration —
a path that merely records it leaves an armed deadline with nothing to cancel it, which
is the same failure by a different route.

**So the line that matters is "location data is withheld", not "a grant is missing".** Both
questions that turn on it — what mode the card claims, and when an outage is over — ask
about the reads, and the system location switch withholds an SSID exactly as a dead grant
does. For the *mode* the line is drawn one step further in: the card follows the
suppression the engine is actually applying, not the cause recorded beside it. The two
come apart in both directions — a cause left on the grant side claimed `Wi-Fi only` with
every grace path already shut, and a cause recorded without a suppressor (a stale
unavailable-fence observation arriving after the switch is back on) claimed `Timer only`
while a grace period could still end the snooze at the anchor. Only the suppressor answers
what is running. Left on the grant side, a services outage claimed `Wi-Fi only` while every grace path
was already shut, and on a Wi-Fi-only anchor it could never be declared over at all: there
is no geofence registration to succeed and lift it, so a real departure afterward ran
silently to the cap. A restoration still needs both grants *and* the switch, so nothing is
declared repaired on a guess.

**A restoration needs the proof that covers the outage.** A geofence the platform accepts is
proof of the two grants, because geofencing requires them outright — but it is not proof the
system location switch is on, since the platform will accept a fence it cannot then monitor.
So a registration success lifts a withholding cause only once the switch answers too;
otherwise the refusal stands, which keeps the recovery watch armed and leaves the outage to
be declared over by the thing that can actually see it end. This is a demand for *more*
proof, never a test of which cause happens to be latched: a grant restored during a services
outage is still restored, by that watch, the moment the outage ends.

**A while-in-use grant is not an edge case here.** With no foreground service on this flavor
(§3), every read Snoozemo makes runs from the background, so an install that granted location
but not *all the time* has no working presence signal at all — its snoozes always run to the
timer. That is a state the user chose and can undo, so the app says so where they will see
it: a dismissible banner on the home screen naming the consequence rather than the permission
— the permanent location row already names the permission — and worded as an offer rather
than a warning, since nothing is broken and there is a capability to switch on.

**What still ends the snooze is the failure we cannot name.** A geofence refused for a reason this
build cannot classify, or a monitor that will not restart, stays fatal: there is no reason to put
on the card and nothing for the user to act on, which is exactly the shape D7 is about.

Revocation is the case that makes §7's release rule matter. Turning the rule off is itself refused
once access is gone — the platform has already deleted the rule — so a release path that retried on
every failure would never complete this ending, and the promise above would go unkept while the app
sat showing `Snoozing`. The failure means there is nothing left silencing the phone, so the snooze
ends on it rather than retrying against it.

### 8.3 Reboot

`BOOT_COMPLETED` is an exemption for *starting* a location foreground service, but it is **not** on
the while-in-use exemption list — so a service started from boot gets no location, and no unredacted
SSID. There is no way around this without `ACCESS_BACKGROUND_LOCATION`.

Behavior on boot with an unexpired snooze: **re-arm the cap alarm first**, then re-assert the zen
rule (this needs no location and works fine), and start the service in degraded mode, naming the
missing grant exactly as §8.1 does. (Earlier revisions posted a `Resume tracking` action here too;
§8.1 records why that design does not work.) The duration cap continues from its original start time —
reboots do not extend a snooze.

The cap comes first and is armed straight from the persisted record, without going through the
service, because it needs only an `AlarmManager` handle — so it still lands if starting the service
from `BOOT_COMPLETED` is refused.

**Nothing on the release side may depend on the service starting.** The cap alarm is one-shot, so by
the time its receiver runs the alarm is spent: if the service cannot be started there, no scheduled
thing is left to end the snooze. Same for the boot path once its cap has failed to reschedule. So
both release the rule directly from the receiver — a few binder calls, well inside a receiver's
budget, driving Snoozemo's own rule off exactly as the controller would (§5.6, never anyone else's)
— and reschedule a short retry if even that is refused. A cap that fires late is a far smaller
failure than one that never fires; and when nothing schedulable is left at all, the user is asked to
end it by hand rather than left with a quiet phone and no explanation.

**An unknown state is retryable; only a state known to be safe ends a snooze.** The two are easy to
conflate and the cost is asymmetric. "The rule is gone" ends the snooze and erases the record; "we
could not find out" must not, because the rule may still be silencing the phone and the record is the
only thing that could ever turn it off. So a failed *lookup* is a platform refusal, not a missing
rule, and a refused re-assertion on restore keeps the snooze rather than declaring it over.

**A record whose cap has passed is released, never re-asserted.** Restoring one that already expired
would silence the phone again on the way to ending it — briefly if the release works, indefinitely if
it doesn't. The clock is checked before the rule.

**A scheduled wake-up carries its own purpose.** The cap and the record-erase retry are separate
alarms with separate actions, because a recreated service reads them oppositely: a cap wake-up picks
the record back up and re-asserts the rule, while an erase retry exists to dispose of a record whose
snooze already ended. Sharing one alarm meant a retry could restart the process, be read as a cap,
and silence the phone again over a snooze the user had ended. For the same reason, restoring happens
in `onStartCommand`, where the action is known, and not in `onCreate`, where it isn't.

**A spent cap alarm always leaves a successor.** The alarm is one-shot, so once it has fired,
anything it left undone — a release the platform refused, a record that wouldn't erase — would
otherwise never be revisited. Every path that consumes the alarm without finishing the job schedules
a short retry, and restoring a snooze re-arms the cap from the record unconditionally rather than
trusting an alarm that may have been the very thing that woke the process.

**The cap is one absolute instant, settled before anything is scheduled or recorded.** The alarm and
the record must name the same moment. Deriving it twice — once for the alarm, once again from a
later clock reading for the record — puts them milliseconds apart, and an alarm that fires just
*before* its own record counts as expired is a spent alarm and a snooze with no duration exit left. And if the cap itself cannot be rescheduled, the snooze **ends**
rather than being restored: restoring would re-assert the rule with no guaranteed exit behind it,
which is §7's one forbidden state. The rule is driven off explicitly rather than assumed cleared by
the reboot.

Alternative considered and rejected: end the snooze on every reboot. Simpler, but an OTA update
rebooting the phone at 2 a.m. would unsilence a bedtime snooze, which is the exact harm the app
exists to prevent. Make this a setting (`On restart: resume / end`), defaulting to resume.

### 8.4 The system putting us in the sin bin

The failure this section exists for is **not** the user revoking a permission. That one fails safe:
§8.2, the platform deletes the rule, so nothing is silencing the phone and calls ring through. The
dangerous direction is the opposite one — **the rule stays active while the thing that would turn it
off stops being allowed to run.** Then DND holds and nothing in the app is scheduled to end it,
which is principle 1's failure arriving through no fault of the user.

Three mechanisms can do that, and they are not equally survivable:

| Mechanism | What it does to us | Survivable? |
|---|---|---|
| **App Standby Buckets** — `restricted` | `setAndAllowWhileIdle` and jobs throttled to roughly once a day | Yes, but the cap can be a day late |
| **OEM battery management** — One UI Sleeping Apps and friends (§10) | Alarms and jobs cancelled, process not restarted | Partially — §10's mitigations |
| **Force-stop** by the user | Alarms cancelled **and** broadcasts no longer delivered, including `BOOT_COMPLETED`, until the app is next launched manually | **No in-app recovery** |

**Buckets are the one to keep in proportion.** Arming is a tile tap, and app interaction is the
primary signal that promotes an app toward the active bucket, so a user who armed minutes ago is
very unlikely to be sitting in `restricted`. The exposure is long snoozes on a phone where Snoozemo
is otherwise unused. Measurable with `adb shell am set-standby-bucket`; on the hardware list.

**Force-stop has no fix, and the honest mitigation is the one the platform already provides.** A
force-stopped app cannot schedule, cannot receive broadcasts, and cannot be woken by anything short
of the user opening it. But force-stop also removes the ongoing notification, so the user is left
looking at DND on with no Snoozemo card, and the system's own DND toggle turns it off. Snoozemo
should not pretend to recover from this; it should be sure it never *needs* to, which is what §8.1's
alarm-first escalation is for.

**Doze deferral is the mild case, not one of these.** An inexact alarm held by Doze is not dropped —
it fires at the next maintenance window or when the device leaves Doze, and ordinary activity (an
incoming call, the user unlocking) ends Doze. So a cap that misses its moment fires shortly after
the phone next does anything, and the staleness is bounded by device use rather than open-ended.
Worth stating because it is easy to read §7's "inexact on purpose" as a much weaker guarantee than
it is.

### 8.5 Others

| Case | Behavior |
|---|---|
| Airplane mode / Wi-Fi off mid-snooze | Wi-Fi signal goes unavailable, not "lost". Fall through to location; if location is also unavailable, degrade to duration-only and say so |
| Location services disabled system-wide | Degrade to duration-only, update notification |
| Arm twice at the same place | Idempotent; second tap ends it (§4.2) |
| Move a short distance and return | Hysteresis plus two-fix confirmation absorbs this |
| Anchor captured with a bad fix (accuracy > 200 m) | Discard the coordinates, run Wi-Fi-only if associated, otherwise duration-only. Never anchor on a fix too vague to test against |
| Battery saver on | Location updates throttle. Accept the added latency; the cap still holds |
| User uninstalls while snoozed | The platform removes the app's zen rules with the app |

---

## 9. Battery

Rough budget for a 4-hour snooze on a modern Pixel. These are estimates from the mechanisms involved,
not measurements — `TODO.md`'s hardware-verification list says to measure them.

**`play` flavor (Geofencing API)** — negligible in every case, well under 1%. A registered geofence
is monitored by a system process using **network location only**, never GPS (§6.10), so an idle
geofence rides on location work the device is already doing. Our additions are a Wi-Fi network
callback (event-driven, free) and a 15–30 minute `WorkManager` backstop (a handful of wakeups over a
4-hour snooze). **A Wi-Fi-only anchor costs more, and knowingly**: with no fence to outlive the
process, its snooze also carries the §6.10 recheck alarm at 15 minutes — 16 extra wakeups over a
4-hour snooze, each a service start and a network-state read, no location request and no radio. That
is the price of the alternative being a snooze that never ends (principle 1), and it is paid only by
the degraded anchor: a snooze with a usable fix arms no such alarm. There is no foreground service
and no process of ours running between events — the
ongoing notification this flavor does post (§3.4) costs nothing, since a posted notification holds
no process up and wakes nothing.

**`direct` flavor (foreground service)** — higher, and dominated entirely by location fix frequency,
which §6.7's duty cycle drives toward zero in the common case:

| Scenario | Estimate |
|---|---|
| On anchor Wi-Fi the whole time | <0.5% — network callback only, no location, no sensor polling |
| No Wi-Fi, phone stationary | ~1% — significant-motion trigger plus a 10-minute sanity fix |
| No Wi-Fi, intermittent movement | ~2–3% — 90 s balanced-power fixes during active periods |

The FGS notification itself costs nothing.

So battery is not a reason to prefer `direct`, and not a reason to fear `play`. The `play` flavor is
the cheaper of the two by a clear margin — its problem is reliability (§6.10), not power, and the
three-source layering that fixes the reliability is itself nearly free.

---

## 10. Pixel and Samsung

### Pixel

The reference target. Android 16 Modes UI will surface the Snoozemo rule as a first-class Mode.
Nothing OEM-specific required.

### Samsung One UI

A real target, sequenced second (goal 5). Validated at M8 rather than M1 — not descoped, just not
allowed to gate the Pixel release. Deferring is safe because everything below is verification or
settings guidance rather than architecture: nothing here, if it fails, sends the design back to the
drawing board. And the `direct` flavor is independently the stronger build on One UI, so there is a
good answer available even in the bad cases.

One thing this does buy: keep the OEM-specific work — battery-optimization guidance, tile-rendering
fallbacks — behind a small seam from the start, so M8 is additive rather than surgery.

Three areas need real-device verification, not assumption:

1. **Sleeping apps / Deep sleeping apps.** One UI's Battery → Background usage limits will put
   infrequently used apps to sleep, which breaks background work. A foreground service is much more
   resistant to this than a geofence registration would be — which is why `direct` is the better build
   on Samsung even though `play` is the shipping one — but it is not
   immune. Onboarding should include a Samsung-detected step explaining how to add Snoozemo to
   *Never sleeping apps*.

   Deep-link with `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, which opens the list
   without requiring any permission. Do **not** declare
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — Play restricts its acceptable use, and the direct
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog it enables would be a policy risk for a
   marginal UX gain. Guide the user, don't automate it. Avoid deep-linking into
   `com.samsung.android.lool` internals; those component names change between One UI versions.

2. **Modes and Routines.** One UI has its own DND/Modes layer over AOSP's. Third-party
   `AutomaticZenRule`s have historically appeared under DND schedules, but One UI 8 reorganized this
   UI substantially. Verify that (a) the rule is created, (b) `setAutomaticZenRuleState` actually
   silences the device, and (c) the rule is discoverable and disableable in Samsung's Settings.

3. **Quick Settings.** One UI's tile rendering differs — in particular, verify that
   `Tile.setSubtitle` is displayed at all, since the countdown text lives there. Have a fallback
   that folds the remaining time into the label if not.

`StatusBarManager.requestAddTileService()` (API 33+) works on both, and is how the tile gets added
— never by telling the user to go edit their shade by hand. It requires the app to be in the
foreground and the tile service to be `exported="true"`, and the system auto-denies after repeated
dismissals.

**It is offered from a row on the app screen, not fired automatically at launch** (revised
2026-08-13; the original text here said to ask once during onboarding and never again). Three
things moved the decision:

- The setup rows exist now (§5.2), and mixing an unprompted dialog with a permanent row is the
  precise pattern that had to be removed from the notification permission — the dialog pre-empts
  the row's own affordance and fires over a choice the user may have just made.
- Google's guidance is to call it in response to a user action rather than on launch, which is also
  what keeps the system's auto-deny out of reach: a request that only ever follows a tap is not
  one the user can accumulate dismissals against.
- "Ask once and never again" needs a persisted asked-flag and leaves a user who declined with no
  route back. The row needs no flag and stays available.

The row is **permanent** and states the tile's presence either way, offering to add it only while
it is missing (§4.2 — it was conditional when first built, and became permanent when the banner
above it gained a forever-dismissal that needs something to outlive it). Nothing can *ask* whether
the tile is there, so its presence is tracked from the three moments the platform volunteers it —
the tile being added, being removed, and the result of a request.

Until one of those has answered, the row renders **nothing**. A permanent row cannot carry a
default: it would assert `Added` on the first frame of a cold launch and then correct itself, which
is the same unread-state discipline the access and notification rows follow (§5.2). The *store*
still defaults to missing, so a fresh install is offered the tile — the durable wrong answer must
offer rather than hide, since a false positive would conceal the route to the product's whole
interaction.

---

## 11. Architecture

```
:app          UI (Compose), onboarding, settings, trampoline activity
:tile         SnoozeTileService — thin, delegates to :core
:core         SnoozeController (state machine), Anchor, exits, persistence,
              and the interfaces the controller is injected with
:dnd          the device's quiet state — all NotificationManager/AutomaticZenRule
              contact, and the ringer ceiling (§5.9) driven with it
:presence     PresenceMonitor implementations
              ├── geofence/    GeofencePresenceMonitor                    (`play` flavor)
              └── foreground/  ForegroundPresenceMonitor + SnoozeService  (`direct` flavor)
```

**Contracts live in `:core` with their consumer; implementations live in the Android
modules.** `PresenceMonitor` is defined in `:core`, not `:presence`, because the controller
takes one by injection while `:presence` needs `:core` for `Anchor` — defining it the other
way round is a dependency cycle, not a style preference. The same holds for the DND
interface `:dnd` implements.

`SnoozeController` is a plain Kotlin state machine over the §4.1 diagram, with no Android
dependencies beyond a clock and the two injected interfaces — so it is fully unit-testable, which is
where most of the real complexity lives.

**The core is split from the UI in the scaffold itself, not once the app grows** (maintainer,
2026-08-11). That seam is what keeps Android types — `Context`, `Location`, `TileService`, a
composable's state — out of the state machine; introduced later it stops being a boundary and
becomes a refactor nobody has time for, and the decision logic ends up in a `Service` where only a
device can test it.

**Where decision logic genuinely cannot leave an Android component, the component gets a
seam instead** (maintainer, 2026-08-12). The escalation behind a refused zen write (§7.1) is
the case that forced this: its ladder moved to `:core` and is covered there, but *performing*
each rung — which alarm is armed, what reason it carries, what the user is left reading — can
only happen in the service. Every one of those branches is reached only when the platform
**refuses**, which no device and no emulator will do on demand, so the whole area was
reachable by review and nothing else. Five real defects shipped into review from it in a
single change. The service therefore builds its DND controller through an overridable factory
rather than a constructor call, which is enough to make refusal expressible; everything else
those tests observe — notifications, alarms and the extras inside their pending intents — is
the real thing under Robolectric. Faking those would have hidden two of the five, which were
*in* an intent extra and a notification id.

The module tree above is the starting shape, not the requirement. What is required is a seam that
keeps the core **functional, testable, and buildable on its own** — a Gradle module boundary is the
version of that the build enforces for you, so it is the default, but the scaffold may cut the
modules differently (fewer, or drawn elsewhere) if that serves the same end better. What it may not
do is leave the core reachable only through the UI or an Android component.

### Stack

Kotlin · Compose + Material 3 · coroutines/Flow · Hilt · DataStore (settings, active-snooze record)
· Room (saved places and snooze history, once §14 lands — not needed for v1's single implicit anchor)
· Play Services Location (`play-services-location`, used for fused location even in the default
flavor; the geofencing surface is only touched by the `play` flavor, and the `direct` flavor
degrades to `LocationManager` if Play Services is absent, §6.5).

**Every window is drawn edge to edge, and that is a description before it is a choice.** Android 15
made it the behavior for anything targeting SDK 35 and up, and Android 16 removed the opt-out, so
the only decision left is whether the app handles the consequences. It does, in three places: the
activity declares it (`enableEdgeToEdge`), which is also what makes the bars transparent and picks
icon contrast to sit over what the app draws; every screen pads itself by **`safeDrawing`** — system
bars *and* display cutout, outside any scroll container, so a row cannot slide under the status bar
as the user scrolls; and the XML themes are DayNight, including the trampoline's transparent one,
because with the bars transparent the background showing through them is the app's own.

Application ID `app.snoozemo`; module packages hang off it (`app.snoozemo.tile`,
`app.snoozemo.dnd`, `app.snoozemo.presence`). The zen rule's condition URI is
`snoozemo://snooze` (§5.3).

**minSdk 35** (Android 15) — gives `requestAddTileService`, `POST_NOTIFICATIONS`, and modern Wi-Fi
APIs without version branches, and Modes UI, the AOSP feature that makes the `SettingsScreen`
Filters row's target resolve (§4.2's screen split).

The floor started at 33 for the first three of those reasons, and was raised to 34 by the
maintainer once the tile landed (2026-08-11). API 33's `startActivityAndCollapse` takes an
`Intent`; the overload that takes a `PendingIntent` — the one that isn't deprecated, and the one
every current example uses — is API 34+. Carrying 33 meant a version branch plus a deprecation
suppression on the single hottest path in the app, the tile tap that arms a snooze (§4.1, §6.9),
which is the last place that should carry a rarely-exercised second code path. Android 13 devices
were the cost; a correct, single-path arm was the benefit, and the arm path is goal 1.

Raised again, to 35, by the maintainer once Filters landed (2026-08-23, PR #88). Its deep link —
`Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS`, confirmed against AOSP Settings' own manifest and
source — is declared behind the `android.app.modes_ui` feature flag, which AOSP ties to API 35 and
has no lower-API equivalent that reaches the same per-rule screen: unlike the 33→34 move, there was
no version branch to write instead, only Android 14 devices to carry the row without or drop
entirely. Dropped, since a permanently-hidden row on part of the install base is a worse trade than
the same floor bump goal 1's `startActivityAndCollapse` already crossed once for a smaller reason.
Covers every Pixel and Samsung flagship still receiving updates in 2026.

**targetSdk 36** (Android 16) — Play requires 36 for new uploads and updates from 31 Aug 2026, so
start there. minSdk 35 means the `AutomaticZenRule.Builder` / 4-arg `Condition` paths (§5.3, §5.4)
need no version branch at all — the last significant one, the SDK-34 `AutomaticZenRule` constructor
fallback, was deleted along with the minSdk raise (PR #88).

### Data

```kotlin
data class ActiveSnooze(
    val anchor: Anchor,
    val startedAt: Instant,
    val capExpiresAt: Instant,
    val placeName: String,          // "Here" until saved/named
    val mode: TrackingMode,         // FULL, WIFI_ONLY, WIFI_GRACE, DURATION_ONLY
)
```

Persisted on every state transition so a process death is fully recoverable.

---

## 12. Privacy

The user-facing statement of everything below is `docs/PRIVACY.md`, which backs the hosted policy
Play links to. It is written from the manifests and the stores rather than from this section, so it
is a check on the section as much as a restatement of it: a store that keeps something this list
doesn't mention shows up as a row with no rationale behind it.

- **No `INTERNET` permission, today.** Nothing can be exfiltrated, and the Play Data Safety
  declaration is "no data collected, no data shared" — trivially true and trivially auditable.
- **Correction: this is not read as a permanent architectural constraint** (maintainer,
  2026-08-23). The original framing treated the absence of `INTERNET` as a standing guarantee
  this app defends — AGENTS.md required any proposal to add it be brought to the user as a
  distribution decision before landing "in passing". That guarantee is retired: a crash
  reporter (Crashlytics or similar) is expected in a later change, gated by a user-facing
  opt-out so a user who wants zero network activity still gets it, on by default so the common
  case gets reliable crash visibility without an extra step. What does not change is the floor
  the rest of this section states: location data and the user's own configuration stay off any
  channel that leaves the device unless the user has been told plainly and agreed — a crash
  reporter answers "did Snoozemo crash and why", not "where is this person and what did they
  configure". When it lands, the Play Data Safety declaration, `docs/PRIVACY.md`, and the
  `AndroidManifest.xml` comment above the (then-present) `INTERNET` permission all need to
  change together, not just the code.
- **That is now what happened: Crashlytics is in, on `play` only** (2026-08-25). The bullet
  above is the decision; this is its resolution, and the two bullets are the pair to read
  together. Firebase Crashlytics reports crashes from the `play` flavor, which therefore
  declares `INTERNET`. `docs/crashlytics.md` carries the operational detail (cost — $0/month,
  no metered tier; setup; how to verify it on a device).

  Four things about the shape of it are decisions rather than implementation:

  - **`play` only, and that is not a convenience.** `direct` exists to be the build with no
    restricted permission and no Play Services dependency (§3.4), so it gains neither the
    reporter nor `INTERNET` — "this build cannot open a network connection" stays literally
    true of one of the two flavors, auditable from its manifest, and an F-Droid build could
    not carry a proprietary reporter anyway. `DeclaredPermissionsTest` pins both directions,
    and a release build refuses to package a merged manifest that breaks them: `play` must
    carry `INTERNET` and `ACCESS_BACKGROUND_LOCATION` and nothing Play would review (§3.3);
    `direct` must carry neither.
  - **Analytics joins it, and the two share one consent** (maintainer, 2026-08-31; all four
    sibling apps are going the same way). Firebase Analytics ships on `play` alongside
    Crashlytics, collecting only what the SDK collects automatically — app opens, session
    length, screen views, app and OS updates, clear-data and uninstall, and the engagement
    time attached to each — with **no custom events and no custom user properties**, which is
    what keeps §12's floor intact: there is no call site that could
    attach a coordinate, an SSID or a place name. The qualifier is load-bearing (Codex,
    PR #166): the SDK derives user properties of its own — device model, coarse country —
    so an unqualified "no user properties" would claim more than is true and contradict the
    automatic collection described in the same breath. **State that invariant, not the SDK's
    event list**: Google decides what Analytics collects automatically and can add to it,
    so a spec or policy written as a closed enumeration goes stale without anyone touching
    this repo, while "no custom events, no custom user properties" is a fact about Snoozemo
    that a reader can check against the code. The automatic screen view is worth naming
    for what it is *not* (Codex, PR #166): Snoozemo is a single activity with every screen
    a composable inside it, so that event names the activity on every install and reports
    nothing about which part of the app was used. Making it mean something would take
    logging screen events deliberately, which is adding collection — a decision to take on
    its merits, not a gap to close so a sentence comes true. `direct` gains neither, for the reason the next bullet gives.
    One switch governs both, because the user is asked one question: no build offers them
    separately, and no answer turns on only one of them. **That is not a promise that the
    two are always live together** (Codex, PR #166). A failed opt-*in* deliberately leaves
    Crashlytics on with Analytics off until the next launch — reverting would discard an
    answer the user gave, and holding crash reporting off would punish their yes for an
    unrelated SDK failure — so the gap is named in the debug log and closed by the startup
    gate re-applying the stored preference. The opt-*out* direction has no such gap: **a
    "no" is not finished until it is durable in both** (Codex, PR #166): each SDK persists
    its own override with `apply()`, so the app waits for that override to reach disk
    before recording the answer, and says the opt-out is incomplete rather than silently
    showing a switch off over an SDK still allowed to collect. Analytics is the sharper
    case of the two — it collects on its own, so a stale "on" surviving a process death has
    no gate of ours left to shut, and its setter returns before the SDK has even queued the
    write.
    `docs/PRIVACY.md` and `docs/play-store-declarations.md` moved with it — the declaration
    gains **App interactions**, since declaring only crash logs once Analytics is in the
    build would be an under-declaration.
  - **Asked once, on the main screen, and nothing is collected until the answer is yes.**
    Off-by-default is only half a decision: an install that never opens Settings has
    decided by default, and the default loses every crash. So the question is put where the
    user already is, both answers are recorded — a decline is a recorded "no", not an
    absence, so it is never re-asked — and the card is retired the moment it is answered.
    simmo's `AnalyticsInviteCard` is the prior art and Snoozemo follows it, so the apps
    read alike.
  - **Off until the user turns it on** (maintainer, 2026-08-28, across all four sibling
    apps; reverses the on-by-default decision in the bullet above). Crash reporting sends
    data off the device, and that needs the user's explicit agreement first — an install
    that has never been asked has not given it. The original reasoning was that a reporter
    starting off loses the first, unrepeatable crash; that is true and is not something the
    app weighs against consent. §4.6's identical argument for the debug log is untouched,
    since that log never leaves the phone.

    The mechanism was already here: the `play` manifest starts Crashlytics with collection
    **off** and the app applies the stored choice at startup. Only the stored default
    changed — absent now reads as "not agreed".

    **That question is now settled: a card, not just a switch.** It asks once, in the wording
    the sibling apps share, and until it is answered nothing is collected. Both halves are
    required — the stored preference *and* a recorded answer — because an install upgrading
    from the switch-only build carries a yes about crash reports that was never a yes about
    anything else. Moving the Settings switch counts as answering too: it is the same
    decision reached from a different screen, and treating it otherwise let a fresh install
    turn collection on with the card still standing.

  - **Analytics ships, and the `AD_ID` question was decided rather than avoided.** The
    earlier position was Crashlytics *without* Firebase Analytics, on the understanding that
    Analytics is what brings the `AD_ID` permission in and that Play's "Advertising ID: not
    used" answer was worth more than the console's crash-free-users percentage. That
    understanding held: adding Analytics failed `DeclaredPermissionsTest`'s `AD_ID`
    assertion, exactly as that test was written to do.

    What changed is the conclusion, not the reasoning. Rather than choose between Analytics
    and the declaration, the `play` manifest removes the permission outright
    (`tools:node="remove"`) and switches `google_analytics_adid_collection_enabled` off, so
    the answer stays "not used" and Analytics reports against the per-install app-instance
    ID — specific to this app, unjoinable with activity elsewhere, reset on clear-data. The
    cost is the ads-adjacent surface (audience export, inferred demographics), which
    Snoozemo has no use for. `DeclaredPermissionsTest` still asserts `AD_ID` absent on both
    flavors, and now also asserts every Firebase collection switch is declared and off on
    `play` and absent on `direct`.
  - **The floor is unchanged, but the reason it holds has changed, and that is worth stating
    plainly.** A crash report is a stack trace, a device model, a version — and, since
    Analytics joined the build, a **breadcrumb trail**: Crashlytics picks up Analytics' events
    automatically once the SDK is present, which is Firebase's behavior and not something this
    app opts into (Codex, PR #166; maintainer accepted it, 2026-08-31).

    Snoozemo attaches no custom keys and logs no custom events, so what rides that trail is the
    SDK's automatic events — `screen_view` naming the single activity every install has, and
    its siblings — and none of them carries a coordinate, an SSID or BSSID, or a user-typed
    place name. So the floor holds; what it rests on is now *nothing that goes down the channel
    is forbidden* rather than *there is no channel*.

    **That is a weaker guarantee, and it makes one thing a decision rather than a detail:**
    adding a custom Analytics event would put its parameters into crash reports as well as into
    Analytics, so it needs checking against this floor at the point it is added — not assumed
    safe because the reporter attaches nothing. The debug log stays on the phone and still
    leaves it only when the user shares it by hand.

  **What the off switch means: the feature, not the network** (maintainer's reading, 2026-08-25 —
  `TODO.md` carries it as the working direction awaiting confirmation, not a closed decision).
  Turning
  crash reporting off stops crash reports. It does not claim the process opens no connection at
  all, and it does not need to: **the permission is not the invariant — user data leaving the
  device is.** Most Play apps hold `INTERNET`, and treating its presence as a problem in itself
  costs the product without buying the user anything.

  So the line this section defends is unchanged and is the one worth testing: nothing derivable
  about where the user lives, works or sleeps leaves the phone, and what does leave is under the
  user's control. A crash report carries a stack trace, a device model and a version — no
  coordinate, no SSID/BSSID, no place name, no snooze timing, no debug log — and the user can
  switch it off. The corollary is that **gates key on whether a feature is on, not on whether a
  permission is held**, which is why the release pipeline's Data Safety gate asks whether crash
  reporting is enabled in the build rather than inspecting the manifest.

  **Play Data Safety moved with it**, as the bullet above required: from "no data collected,
  no data shared" to **crash logs, diagnostics, device or other IDs, app interactions, and
  approximate location — collected, not shared, optional**. The fifth is the coarse country
  Google derives from the network address an Analytics request arrives on: the app transmits
  no location, but Android's *Declare your app's data use* names deriving location from an IP
  address under Location and draws no line between what the app collects and what a processor
  derives, so declaring is what matches the guidance (maintainer, 2026-09-01, reversing an
  earlier leaning not to). The third type is the Crashlytics installation
  identifier, which
  `docs/PRIVACY.md` describes: it is what lets repeat crashes on one phone be told apart, and
  omitting it from the form would under-declare (maintainer, 2026-08-25, on Codex's reading in
  PR #113). It is app-scoped and is **not** an advertising identifier — the separate
  Advertising ID declaration stays "not used", and that answer is checked rather than asserted,
  by `DeclaredPermissionsTest`. The fourth type is Analytics' automatic events, which
  Play's taxonomy files under **App interactions**; it arrived with the analytics bullet
  above and by the same rule, since declaring only crash logs once Analytics is in the
  build would under-declare exactly as omitting the installation identifier would. `docs/play-store-declarations.md` carries the field-by-field
  answers; updating the Play Console form is a maintainer action the code cannot do.
- Coordinates never leave the device. The v1 anchor is discarded when the snooze ends.
- Snooze history (if added) is local, off by default, and clearable.
- **The debug log (§4.6) is the one sanctioned exception, and a narrow one.** It is on by default
  (maintainer, 2026-08-11) with a setting to switch it off, on-device, holding only recent runs, and
  leaves the device only when the user shares it through the system share sheet — the default is
  about what is recorded *on the user's own phone*, not about anything leaving it. Its floor is
  what Snoozemo writes: coarse state, reasons, distance from the anchor in meters, and fix
  accuracy — never raw coordinates, never a full SSID or BSSID, never a place name the user typed.
  The one exception is text Snoozemo does not author, a thrown error's own message — whoever
  raised it, framework, runtime or bundled library — and Android's exit-reason description, which
  §4.6 states in full. It exists because the alternative is worse for the user, not better: a snooze that
  misfires while the phone is in a pocket is otherwise undiagnosable, and "it sometimes ends early"
  is a bug that never gets fixed.
- In-app prominent disclosure before the location permission prompt, explaining the *place* use and
  the fact that tracking runs only while a snooze is armed.
- `android:allowBackup="false"` — a backup of location anchors is not worth the exposure.
  **This is a starting position, not a permanent one.** It is the right default while the
  only thing the app stores is a transient anchor; it stops being obviously right once
  there are saved places, per-place policies, and caps — settings the user built and cannot
  reproduce, where losing them on a phone swap is its own failure. The two questions are
  separable, and Android lets them be answered separately (`dataExtractionRules`, API 31+):
  **device-to-device transfer is not cloud backup**, so "your settings survive a new phone"
  does not have to mean "your places are in Google's cloud". Settled before the first
  release that has settings worth keeping (`TODO.md`).
- **Correction: today's config does not actually decide the migration question** (Codex,
  PR #23). The premise that a device migration currently loses settings *by design* does
  not hold. For apps targeting API 31+, Android documents that `allowBackup="false"`
  disables cloud backup but, **on devices from some manufacturers, does not disable
  device-to-device transfer**. Snoozemo targets 36, so what happens on a phone swap was the
  OEM's choice rather than ours — not "off", and not knowably "on" either. It changes what
  the decision *costs*: choosing D2D-transfer-yes is not a new exposure so much as writing
  down what may already happen, and choosing no requires a `<device-transfer>` exclude
  rather than the absence of a setting.
- **The runtime state now carries that exclude; the settings question is still open.**
  `res/xml/data_extraction_rules.xml` names `active_snooze`, `pending_failure` and
  `notification_prompt` under `<device-transfer>`, because each of those *acts* on the new
  phone rather than merely existing there (the bullet below). It deliberately excludes by
  name rather than in bulk: saved places, per-place policies and caps are what principle 3
  says must survive a phone swap, so they stay transferable and the migration decision
  stays exactly as open as it was. Adding a file to that list is a decision that the user
  loses it on a new phone.
- **The active snooze record is the part of this that is urgent, and it is a principle 1
  problem rather than a settings one** (Codex, PR #23). A first pass at the correction
  above called migration non-urgent "while the only durable state is a zen rule id and
  three flags". That is wrong: `active_snooze` is durable too, and it is the one record
  that *does something* when it lands somewhere new. `BootReceiver` loads whatever record
  it finds and, if the cap can still be armed, re-asserts the zen rule (§8.3) — so on an
  OEM that transfers app-private data, a snooze armed on the old phone can silence the new
  one on its first boot, with nothing the user did behind it. The absolute wall-clock cap
  bounds it, and a swap slower than the remaining cap makes it moot, but a swap is usually
  faster than that. **Neither branch of the settings decision fixes this**, which is why it
  did not wait on it.
- **Both halves of that fix are in, and they are not redundant.** The `<device-transfer>`
  exclude above is the declarative half and does the real work; the record also carries a
  **device stamp**, and a restore that finds a stamp other than this device's ends the
  snooze instead of asserting the rule. The exclude is the better mechanism — it means the
  record never arrives — but it is only as good as the transfer tool's respect for it, and
  the very sentence that makes this bug possible says OEM behavior "varies". A tool that
  ignores `allowBackup` may equally ignore `dataExtractionRules`, and there is no way to
  enumerate which. So the stamp is the backstop for the case the declaration does not
  reach, which is the same fail-open discipline D7 applies everywhere else: the ambiguous
  state resolves toward ending the snooze. The stamp is a salted hash, never the raw
  identifier, and Snoozemo never transmits it — §12's floor is unchanged. It does travel on
  the one path that copies the record itself, which is not a leak but the mechanism: if a
  transfer carries the snooze across, the stamp has to come too, or the new phone has
  nothing to compare against.

---

## 13. Testing

**Unit** — `SnoozeController` transitions; the §6.6 departure test against recorded fix traces
including bad-accuracy jumps; cap arithmetic across DST boundaries.

**Instrumented** — mock location provider to drive synthetic departure traces; `ZenRuleManager`
against a real `NotificationManager` with policy access granted.

**Release publishing** — driven against a stubbed release API on every pull request, because the
release job runs only on `main` and would otherwise first be exercised by the merge that ships it.
Its logic exists for publications that have already partly failed, which is the worst place to
find a mistake, and the invariants it holds are ones no reader can check by inspection: a release
never becomes visible without its bundle, and a superseded run publishes nothing, so the newest
publication is always the newest build.

**Screenshot** — every state a screen can reach, recorded with Roborazzi under Robolectric and
committed to the repo. The point is the *states*, not the pixels: the surfaces this app has are
mostly about what it knows and doesn't yet know — access unread, record unread, degraded — and
those are exactly the states a refactor silently collapses into a plausible default. A committed
image is the only artifact that makes such a collapse show up in review. The tile mark is
recorded the same way and for a sharper reason: the system tints it per tile state, so it must
be checked in both directions, at its real size, or the failure only appears on a device
(§4.2).

**Store graphics** — the Play listing's app icon and feature graphic are *recorded*, by the same
Robolectric-with-native-graphics machinery, drawing the launcher drawables through Android's own
renderer. They are deliverables rather than assertions, and they live under `docs/play-store/`
rather than in the snapshot tree, but the reason they are tests is the invariant they enforce: the
store icon must be *drawn the way a launcher draws it*, by the platform's own rasterizer, so it
cannot disagree with the installed icon about shapes, strokes or color. Any transcription — an exported PNG, an HTML facsimile, a renderer of our own
— can be reviewed indefinitely and still differ, because "does this match Android" is not a
question it can answer about itself; this repo tried the first two and measured the third still
differing inside the mark. Framing follows from the same principle: the icon is cropped to the part
of the adaptive layers a launcher actually shows rather than to the whole layer canvas, so the mark
is the size a person sees on their home screen — the store listing sits beside the installed icon in a user's mind, and
a mark a third smaller in one than the other reads as carelessness. Recording them in CI is also
what keeps them fresh: a launcher-drawable
change with no re-render fails the build rather than leaving artwork that quietly stopped matching
the app.

Insets are dispatched by the test rather than assumed: Robolectric reports none of its own, so a
screen that ignores the status bar and one that pads for it render identically, and the defect that
prompted the edge-to-edge work would have been invisible to every snapshot in the suite. The
edge-to-edge tests hand the window a phone's worth of bars and a cutout, then assert positions —
the first row below the status bar, the way out above the gesture bar, scrolled to the end.

CI records rather than verifies, and commits the recording back to the branch. Rendering differs
between a laptop and a runner — fonts, renderer version — and a verify-only job makes that
difference indistinguishable from a UI change; recording on one machine makes the committed
images mean "what CI draws", which is the only claim a snapshot can honestly make. The
trade-offs this buys are recorded with the decision (`TODO.md`): the refresh commit is pushed
with a token that cannot trigger CI, and the job records against the branch rather than the
merge result.

**Manual matrix**, per device (Pixel + a Samsung):

| Scenario | Expected |
|---|---|
| Arm at home on Wi-Fi, walk 500 m | Ends within ~2 min |
| Arm, toggle Wi-Fi off, stay put | Does **not** end |
| Arm, router reboots | Does **not** end |
| Arm somewhere with no Wi-Fi, drive away | Ends within ~2 min |
| Arm, leave phone stationary 8 h | Ends at cap, not before |
| Arm, reboot phone | Still snoozed, degraded-mode notification, resumes on tap |
| Arm, force-stop app | DND state resolves; no permanently stuck silence |
| Arm on Samsung with Sleeping Apps on, wait 4 h | Still tracking |
| Arm while DND already on from a bedtime schedule, then leave | Snoozemo's rule off, bedtime rule untouched |
| Arm with no meeting in progress | No sheet, armed in one tap |
| Arm during a meeting, tap `Until <time>`, then leave early | Ends on departure, not at the meeting end |
| Arm during a meeting, tap `Until <time>`, stay put | Ends at the meeting end |
| Arm during an all-day event or a "free" calendar block | Not offered as a meeting |
| Arm minutes before a meeting starts | Its end is offered, since the offer is not gated on overlapping now |
| Arm with the next meeting ending past the cap | No third action; the cap already ends the snooze sooner |
| Tap `Until <time>` on a card left in the shade past the offered time | Refused, and said in the shade |
| Deny `READ_CALENDAR` | Third action absent everywhere; nothing else changes |

The force-stop and Samsung rows are the ones most likely to find something. Run them first.

---

## 14. Deferred

- **Saved places.** Name an anchor ("Cinema", "Work"), give it its own policy and duration cap.
  Turns the tile long-press into a picker. The `Anchor` type is already shaped for this.
- **Auto-arm on arrival.** The obvious sequel, and the one that genuinely needs background location
  and the Play declaration — already paid for in the `play` flavor, so the *permission* is free
  there. **The battery is not** (2026-09-03): auto-arm would be the first thing watching geofences
  while nothing is snoozed, a standing cost §9's budget has never measured, so it is gated on a
  hardware measurement rather than assumed cheap. `TODO.md` carries that gate.
- **`ZenDeviceEffects`** — grayscale, dim wallpaper, night mode while snoozed (§5.5).
- **"Until I get home"** and other saved-place reverse geofences (§4.4), which follow from saved
  places plus background location, so `play`-flavor only.
- **Chaining back-to-back meetings** (§4.3), if using the app shows people actually want it. The
  offer deliberately ends at one event: a card that walked itself forward through a packed
  afternoon would keep the phone quiet for a stretch nobody asked for.
- **Wear OS tile.**

---

## 15. Plan and open verification

The phased plan lives in `TODO.md`, which defines milestones **M1–M8** — every reference to
a milestone in this spec resolves there. So does the list of things that can only be
settled on real hardware: the background-location declaration, measured geofence exit
latency, and the One UI questions in §10. Nothing in this document has been verified on a
device yet, so read every number in §9 and every reliability claim in §6.10 as reasoning
from mechanism, not measurement.

Two of those are worth restating here because they can change the design rather than tune
it: the **background-location declaration** (§3.5) is binary and is the single biggest
project risk, and **measured geofence exit latency** (§6.10) is what decides whether the
fallback end conditions are ever needed.
