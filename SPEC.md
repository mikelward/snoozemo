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
  badly. The calendar is read once, at arm time, only to seed a suggested end time (§4.4) — the app
  never triggers itself from your calendar.
- Cross-device sync or accounts. The app declares no `INTERNET` permission today — see §12's
  correction for why that is not read as a permanent architectural constraint.
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
| D9 | **Arm first, refine second** — the tile arms on tap, and a sheet then offers a time (default now + 1 h) or "until I leave" | Keeps the zero-friction one-tap path intact while making a time bound one tap away. Calendar seeding deferred to v1.1 (§4.4) |

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
- There is no `INTERNET` permission (§12). Nothing is collected, transmitted, or monetized, and the
  Data Safety form says so. Most background-location rejections are about undisclosed collection and
  sharing; there is nothing here to disclose.
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
it as a workflow artifact; the internal-track upload step is wired but gates on
`PLAY_SERVICE_ACCOUNT_JSON`, which stays unset until the Play Console declarations are filed
(`docs/play-store-internal-track.md`), so a build currently reaches a tester through a hand seed
upload. The `direct` flavor of §3.4 does not ride this channel at all — it is sideloaded today and
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

**The app is three screens, not one** (`TODO.md` Phase 4, landed 2026-08-23). `MainScreen` is the
tile-equivalent Arm/Release control: the app's title, a banner for the one required-and-missing
capability (Do Not Disturb access — nothing on this screen can arm without it), the tile banner
below, and the Snooze/End snooze/Settings controls. `PermissionsScreen` is the interstitial that
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
without finishing it is a trap, not onboarding.

`SettingsScreen` also carries the **update banner** (landed 2026-08-23, `play` flavor only —
§3.4's `direct` flavor is never distributed through Play, so it has nothing to check for): when
Play has a newer Snoozemo waiting, a card offers to fetch it, tracks the download, and then offers
the **restart** that installs it. Always the *flexible* kind — background download, install on a
restart the user chooses — never the immediate kind, which would take the screen over mid-snooze.
Dismiss silences that build and only that build, and only while there is still something to tap:
once the update has downloaded, Restart is the only way to finish it, so the banner drops Dismiss
rather than let a tap strand a fetch already paid for. The check asks the installed Play Store app,
so it sends nothing of the user's anywhere and needs no permission of its own.

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
    [ End now ]   [ +30 min ]
```

`+30 min` matches the sheet's step (§4.4), so extending uses the same mental unit as choosing.

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

#### v1

```
    🌙  Snoozing at Home

        ⏰  until 14:00          [ − ]  [ + ]
        📍  until I leave

        Ends when you leave, either way.
```

- **A sane default, no inference.** The time is seeded at **one hour from now, rounded to the
  nearest half hour** — a tap at 13:12 offers 14:00, not 14:12. Ragged times look like a bug and
  invite pointless fiddling.
- **`−` / `+` adjust in 30-minute steps** without dismissing the sheet. Floor is 30 minutes from now;
  ceiling is the 8-hour backstop (§7). Two taps covers 13:00–15:00, which is most meetings.
- **Two rows, both live.** Tapping a row commits that end condition and dismisses.
- **The helper line is not decoration.** Choosing a time *lowers the cap*; it does not disable
  departure tracking (§7). Walking out at 13:40 still ends the snooze at 13:40. The rows differ only
  in whether there is a time bound below the backstop, and the sheet should say so plainly rather
  than implying they are exclusive modes.

#### Candidates considered

| End condition | Signal needed | Verdict |
|---|---|---|
| **I leave here** | §6 presence engine | **v1.** Always offered |
| **A time, adjustable** | none | **v1.** Seeded at now + 1 h; also the §7 cap |
| **Whichever comes first** | both | **v1.** Not a third row — implied. Setting a time leaves departure tracking armed |
| **This meeting ends** | `READ_CALENDAR` | **v1.1.** Strong, but deferred — see below |
| **My next alarm** | `AlarmManager.getNextAlarmClock()` | **Explore.** No permission at all, and a natural fit for a bedtime snooze. Offer only when the next alarm is 3–12 h out, so it doesn't propose a 4-minute snooze |
| **Wi-Fi goes** | `NetworkCallback.onLost` | **Fallback only, if §6.10 measurement forces it.** Instant and free, but it inverts D4 — it *is* the failure mode we designed around |
| **I start moving** | `TYPE_SIGNIFICANT_MOTION` | **Fallback only, same condition (§6.10).** No permission, already wired for §6.7. But "moved" is not "left" — standing up for coffee would end it |
| **I get home** | reverse geofence on a saved place | **Deferred.** Needs saved places (§14) plus background location, so `play`-flavor only |
| **Sunset / bedtime window** | none | **Rejected.** The OS's own scheduled Modes do this properly |
| **Screen unlocked N times** | none | **Rejected.** A proxy for attention, not place or time, and wrong in both directions |

#### Calendar: recommend deferring past v1

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

#### If and when it lands

`READ_CALENDAR`, queried against `CalendarContract.Instances` for events overlapping now:

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
broadcast. The query above is a single synchronous `ContentProvider` read against a local database,
run once, when the sheet is built. Meeting end is just a number used to seed a time picker.

That makes it stateless too: arm at 13:12, and if the meeting is later moved to end at 15:00,
Snoozemo neither notices nor cares — it committed to 14:00. That is correct. A snooze that silently
re-times itself under you is worse than one that is occasionally stale, and `+30 min` (§4.3) covers
the overrun.

Remaining constraints: read-only, never written, never leaves the device (there is no `INTERNET`
permission for it to leave by, §12); and v1.1 ends at the current event and does **not** chain into
the next one — chaining is how you end up silenced all afternoon by a calendar you forgot about.

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

**An on-device log, on by default** (maintainer, 2026-08-11), with a setting to turn it off. The
app already declares no `INTERNET` permission (§12), so nothing leaves the device unless the user
hands it over: sharing goes through the system share sheet (and a copy-to-clipboard fallback), which
makes every send an explicit act with a visible destination. Retention is bounded — the current run
plus the previous one, rotated at start, in `cacheDir`, which is excluded from backup.

On-by-default is the decision because off-by-default has a cost that only looks small: the failures
worth diagnosing here — an early release, a stuck snooze, a crash — are **unpredictable and
unrepeatable**, so a log that starts off guarantees the *first* occurrence of each is the one nobody
captured, and asks the user to reproduce a bug that happens once a week in their pocket. The
conservative-looking default is the one that makes the product undebuggable in practice.

What it costs is bounded and stated: two runs of coarse state and reasons, on the user's own device,
under the floor below, behind a setting, and shared only by an explicit act. Simmo's log is
always-on for the same reason. The alternative — a privacy gain measured in "two files that never
leave the phone" — buys less than it gives up.

**Turning the setting off deletes what was kept**, immediately: the current run, the previous run,
and any pinned crash. Stopping new writes while leaving the old files sitting in `cacheDir` would be
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
- Build, device, and Android version.

**Entries carry real timestamps** (maintainer, 2026-08-11). Times are diagnostic, not decorative:
an inexact cap alarm (§7) that fires late because it landed outside a Doze maintenance window, cap
arithmetic that goes wrong across a DST boundary (§13), or a user who reports "it ended around 3am"
— none of those can be reconstructed from intervals alone, and a log that cannot answer *when* is
not worth keeping. The times of a user's snoozes are listed as user data in AGENTS.md's *Privacy*
rule, which is why the log's other protections carry the weight instead: it stays on the device,
bounded to two runs, and reaches nobody without an explicit share. That is what the "one sanctioned
exception" in §12 is for.

**The floor is absolute and is not a matter of judgment**: never raw coordinates, never a full
SSID or BSSID, never a user-typed place name. Distance and accuracy answer "did the test fire
correctly"; the position answers "where do you live", which no bug report needs. Anything above the floor is added only with a specific failure it makes diagnosable, and
`docs/PRIVACY.md` describes what the log carries before it ships (AGENTS.md, *Privacy*).

**A crashed run says so, and survives rotation.** When a previous run ended in an uncaught
exception, **every screen** raises a banner — above everything else on it — offering to share that
run or dismiss it, rather than relying on the user to remember a Settings action (maintainer,
2026-08-23). Every screen, rather than "the one the user lands on", because which screen that is
turns out not to be knowable from any one place: it is usually `MainScreen`, but a cold start with
Do Not Disturb access still missing routes straight to `PermissionsScreen` (§4.2), and a process
killed while the user was in Settings is restored *there* from saved state. Both of those were
found as separate bugs against a rule phrased around the landing screen (Codex, PR #89), which is
the argument for the exhaustive rule: there are three screens, all three show it, and no future
routing change can reintroduce the gap. Only a crash raises it — an ordinary process death, a
force-stop, or an app update does not, since those runs' logs stay shareable without nagging.

A crashed run is **pinned, not rotated**: the crash handler leaves a marker, the next start moves
that run to a distinct crash-suffixed name, and ordinary rotation does not overwrite it. Without
that, a restart between the crash and the user's tap would push the crashed run out of the
`previous` slot and the banner would offer a log that had already been overwritten — and this app
restarts a lot, since a snooze can outlive several process deaths.

**The pin holds the `previous` slot; it is not a third run.** The two-run bound is the privacy
bound, so it holds unchanged: while a crash is pinned, an ordinary run that would have rotated into
`previous` is **discarded instead of displacing it**. That is the right way round — an unread crash
explains a failure, and the uneventful run after it explains nothing. Sharing consumes the pin;
Dismiss renames the file off the crash-suffixed name, after which it is an ordinary `previous` run,
shareable from settings and rotated away like any other. A later crash pins again.

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
  fix, so the row is then a statement — `Allowed` — and the screen's remaining controls are the ones
  that snooze. Turning either capability back *off* is Settings' job, reached deliberately, not a
  button that looks like setup on a screen where setup is finished. The cost is real and accepted:
  there is no longer a route from this screen to the Do Not Disturb access toggle for a user who
  wants to revoke it.
- **Every row uses the same verb, regardless of mechanism.** `Allow` while something is left to do,
  `Allowed` once it's in place — for the Settings toggle and a runtime prompt alike. An earlier
  revision split them (`Grant`/`Granted` for the toggle, `Allow`/`Allowed` for a prompt) to flag
  which was which; that distinction cost the user two words to track and told them nothing they
  needed to act on the row, so it was dropped in favor of one consistent pair (revised 2026-08-24,
  maintainer).

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
  poll location at a very slow 10-minute rate purely as a sanity check.
- **Significant motion fired** → switch to the 90 s request above until the state resolves, then
  re-arm the trigger.

A phone sitting on a desk for four hours therefore does essentially no location work.

### 6.8 Foreground service

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- v1.1 only, not v1 (§4.4). Requested in-context from the arm sheet,
     never during onboarding; the feature hides itself if denied. -->
<!-- <uses-permission android:name="android.permission.READ_CALENDAR" /> -->

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

**Anything else this activity does is queued, not called.** `startService` only *posts*
`onStartCommand` to the same main looper, so work done synchronously after that line — including the
binder call inside a permission request — runs *before* the arm rather than after it. The arm keeps
the thread; everything else takes what's left.

**The trampoline is also where the tile-first user is asked for notification permission.** The tile
can be added straight from the Quick Settings editor, so someone may arm many times without ever
opening the app, and the app screen's request never runs for them. Given §4.2 — the tile is 1×1 and
icon-only, so it carries no status — that user would have an armed snooze with no visible state
anywhere, and a failed arm with no explanation. This activity is the one place the tile-first path
passes through. It is skipped on the lock screen, where a dialog can't be answered and arming locked
is a supported case, and the platform's own two-refusal cap stops it becoming a nag.

It uses a **transparent** theme (`Theme.Material3.DayNight.Dialog` over a translucent window), not
`Theme.NoDisplay`, because it hosts the §4.4 sheet and issues the `READ_CALENDAR` runtime request —
neither is possible from a no-display activity. It finishes as soon as the sheet is dismissed or a
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
registration failed, and re-enqueues the backstop itself; it deliberately does not touch the zen
rule, which a running service already owns. Either way the wake asks
the presence monitor for **one resting fix**, so a departure the geofence never reported gets
tested by §6.6 rather than waited out until the cap. The probe re-checks the location grants on
the way, which is what makes a mid-snooze permission revocation detectable at the backstop's
cadence: revocation kills the process, so no in-process watcher can exist, and a scheduled wake
is the only detector Android leaves. The backstop is never load-bearing — the cap alarm is the
floor and is armed independently — and it retires itself on a wake that finds no snooze, so a
cancel lost to process death costs one empty wake, not a standing drain.

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
attempt to resume presence monitoring. If the recreate happened from a background context and
location comes back denied (while-in-use restriction), degrade to duration-cap-only, update the
notification to say so — `Snoozing at Home · location paused, ends in 3h 40m` — and add a
`Resume tracking` action. Tapping a notification action is a documented while-in-use exemption, so
that tap fully restores tracking.

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

`ACCESS_NOTIFICATION_POLICY` revoked, or location permission downgraded to coarse or denied: end
the snooze, notify with the reason. Do not attempt to limp along silently.

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
rule (this needs no location and works fine), start the service in degraded mode, and post the same
`Resume tracking` notification as §8.1. The duration cap continues from its original start time —
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
:dnd          ZenRuleManager — all NotificationManager/AutomaticZenRule contact
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
- Coordinates never leave the device. The v1 anchor is discarded when the snooze ends.
- Snooze history (if added) is local, off by default, and clearable.
- **The debug log (§4.6) is the one sanctioned exception, and a narrow one.** It is on by default
  (maintainer, 2026-08-11) with a setting to switch it off, on-device, bounded to two runs, and
  leaves the device only when the user shares it through the system share sheet — the default is
  about what is recorded *on the user's own phone*, not about anything leaving it. Its floor is absolute: coarse state, reasons, distance from the anchor in
  meters, and fix accuracy — never raw coordinates, never a full SSID or BSSID, never a place name
  the user typed. It exists because the alternative is worse for the user, not better: a snooze that
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
| Arm during a meeting, choose "until it ends", then leave early | Ends on departure, not at the meeting end |
| Arm during a meeting, choose "until it ends", stay put | Ends at the meeting end |
| Arm during an all-day event or a "free" calendar block | Not offered as a meeting |
| Deny `READ_CALENDAR` | Option absent everywhere; nothing else changes |

The force-stop and Samsung rows are the ones most likely to find something. Run them first.

---

## 14. Deferred

- **Saved places.** Name an anchor ("Cinema", "Work"), give it its own policy and duration cap.
  Turns the tile long-press into a picker. The `Anchor` type is already shaped for this.
- **Auto-arm on arrival.** The obvious sequel, and the one that genuinely needs background location
  and the Play declaration — already paid for in the `play` flavor, so this is nearly free there.
- **`ZenDeviceEffects`** — grayscale, dim wallpaper, night mode while snoozed (§5.5).
- **"Until I get home"** and other saved-place reverse geofences (§4.4), which follow from saved
  places plus background location, so `play`-flavor only.
- **Calendar-seeded end times** (§4.4) — the first thing to add once the Play declarations land.
- **Chaining back-to-back meetings** (§4.4), if using the app shows people actually want it.
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
