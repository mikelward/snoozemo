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
- Cross-device sync, accounts, or any network I/O. The app declares no `INTERNET` permission.
- Wear OS, tablets, foldable-specific UI.
- Automatic *arming* on arrival at a place (geofence enter). Deliberately deferred — see §14.

### The one-sentence product

> Tap Zz. The phone goes quiet. It comes back when you do.

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
the in-app disclosure and the permission prompt.

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

### 3.4 Recommendation — **agreed**

> **Settled:** option B is the primary. Accept the `ACCESS_BACKGROUND_LOCATION` declaration and
> build on the Geofencing API. §3.5's risk assessment stands, and §6.10 covers what to do about the
> API's reliability, but the direction is no longer open.

**Two product flavors, differing only below `PresenceMonitor` (§6.1):**

- **`play`** — option B. Geofencing API, `ACCESS_BACKGROUND_LOCATION`, no ongoing notification.
  This is the shipping build for any Play track, internal included.
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

### 4.3 Notification (while armed)

Channel `snooze_active`, `IMPORTANCE_LOW`, ongoing, not dismissible while the service runs.

```
🌙  Snoozing at Home
    Ends when you leave, or in 3h 40m
    [ End now ]   [ +30 min ]
```

`+30 min` matches the sheet's step (§4.4), so extending uses the same mental unit as choosing.
When the snooze has no time bound the second line reads `Ends when you leave` and the action becomes
`Set a time`, opening the sheet again.

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

**An on-device log, off by default.** The app already declares no `INTERNET` permission (§12), so
nothing leaves the device unless the user hands it over: sharing goes through the system share sheet
(and a copy-to-clipboard fallback), which makes every send an explicit act with a visible
destination. Retention is bounded — the current run plus the previous one, rotated at start, in
`cacheDir`, which is excluded from backup.

> **Open: is off-by-default right?** It is the conservative default, and it has a real cost — the
> failures worth diagnosing (an early release, a stuck snooze, a crash) are unpredictable and
> unrepeatable, so an off-by-default log guarantees the *first* occurrence of each is the one nobody
> captured, and asks the user to reproduce a bug that happens once a week in their pocket. Simmo
> keeps its log always-on for exactly that reason, and the floor below means an always-on log here
> would hold coarse state and reasons rather than anything about where the user was. Sharing stays
> explicit either way, so the question is only about data at rest on the user's own device. It is a
> privacy default rather than an implementation detail, so it is the maintainer's call (`TODO.md`);
> the rest of this section holds under either answer.

**Nothing promises a log that may not exist.** While the log is off, the post-crash banner below
does not appear at all — offering to share a diagnostic that was never recorded would waste the one
moment the user is willing to help, on the failure that most needs reconstruction.

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

**A crashed run says so, and survives rotation.** When a previous run ended in an
uncaught exception, the app's own screen raises a banner offering to share that run or dismiss it,
rather than relying on the user to remember a Settings action. Only a crash raises it — an ordinary
process death, a force-stop, or an app update does not, since those runs' logs stay shareable
without nagging.

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
can disappear before the user acts on it. **The banner checks the file is still there and stays
silent if it isn't** — offering to share a log that no longer exists is worse than saying nothing.
Persisting it outside the cache (`noBackupFilesDir`) would close that window, and is deliberately
not done: a disposable diagnostic belongs in the cache, eviction costs a nice-to-have rather than
anything the user relies on, and the alternative keeps crash logs alive past the retention this
section promises.

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

### 5.3 Rule lifecycle

Create **one** long-lived rule at first successful onboarding, not one per snooze — rules are
user-visible objects and churning them would litter the DND settings screen. Persist the returned id.

```kotlin
// API 35+ (Android 15 and above)
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

`CONDITION_ID` is a stable app-owned URI, e.g. `Uri.parse("snoozemo://snooze")`.

On API 33–34 use the older `AutomaticZenRule(name, owner, configurationActivity, conditionId, policy,
interruptionFilter, enabled)` constructor. `owner` may be null provided `configurationActivity` is
set — a `ConditionProviderService` has not been necessary since API 29, when
`setAutomaticZenRuleState` was introduced as its replacement, and `ConditionProviderService` is
deprecated. Do not implement one.

### 5.4 Turning the rule on and off

```kotlin
fun setSnoozed(on: Boolean, reason: Reason, placeName: String) {
    val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
    val summary = if (on) "Snoozing at $placeName" else "Left $placeName"
    val condition =
        if (Build.VERSION.SDK_INT >= 35)
            Condition(CONDITION_ID, summary, state, reason.toConditionSource())
        else
            Condition(CONDITION_ID, summary, state)
    nm.setAutomaticZenRuleState(ruleId, condition)
}
```

The API 35 `source` argument is worth setting correctly: `Condition.SOURCE_USER_ACTION` when the
user tapped the tile, `Condition.SOURCE_CONTEXT` when the presence engine decided. The platform
surfaces this in the Modes UI so the user can tell "I did this" from "my phone did this."

### 5.5 Zen policy

Default to `INTERRUPTION_FILTER_PRIORITY` with a `ZenPolicy` allowing alarms, media, system sounds,
and calls from repeat callers — the shape most people already expect from DND, and one that keeps a
genuine emergency reachable. Total silence is available in settings but is not the default, because
defaulting a location-triggered mechanism to "nothing gets through" is how you miss something that
matters.

Optionally, on API 35+, attach `ZenDeviceEffects` (`setShouldDimWallpaper`, `setShouldUseNightMode`,
`setShouldDisplayGrayscale`) as an opt-in "make the phone boring too" setting. Nice-to-have, not v1
scope.

### 5.6 Pre-existing DND

If DND is already on when the user arms, Snoozemo still turns its own rule on. Because the platform
merges most-restrictive-wins, this is safe and idempotent. On release, Snoozemo turns off *only its
own rule* — whatever else was making the phone quiet stays. This is the concrete benefit of D1 over
`setInterruptionFilter(INTERRUPTION_FILTER_ALL)`, which would have stomped the other rule.

---

## 6. Presence: deciding when you have left

### 6.1 Interface

```kotlin
interface PresenceMonitor {
    fun start(anchor: Anchor): Flow<PresenceEvent>
    fun stop()
}
```

`PresenceEvent` is `StillHere`, `ProbablyLeft`, `Departed`, `Degraded(cause)`, and
`CapabilityLost(cause)`. The last two must stay separate types, because they demand opposite
responses and the difference cannot be left to a monitor's judgment or to a display string:
**`Degraded` keeps the snooze armed** in a lesser tracking mode with the notification saying so
(§8.1), while **`CapabilityLost` ends it** with `EndReason.LOST_CAPABILITY` (§8.2, D7). A monitor
that reports a revoked location permission as `Degraded` leaves the phone silent with nothing left
to end the snooze — principle 1's failure — so a fatal cause is never reported as a recoverable one.

Two implementations: `GeofencePresenceMonitor` (`play` flavor, §3 option B) and
`ForegroundPresenceMonitor` (`direct` flavor, §3 option A). Everything above this line is
flavor-agnostic — the state machine, the DND handling, the tile, and the §4.4 sheet are all shared,
and neither flavor is aware of the other.

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

### 6.3 Signals and their asymmetry (D4)

| Signal | Meaning | Action |
|---|---|---|
| Associated with anchor SSID | Strong evidence **still here** | Suppress location updates entirely |
| Anchor SSID lost | Weak evidence of leaving | Escalate to `CHECKING`, do not end |
| Significant motion fired | Might be moving | Escalate to `CHECKING` |
| Fix outside radius + hysteresis | Evidence of leaving | Confirm, then end |
| Fix inside radius | Still here | De-escalate to `ARMED` |

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
cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
        val info = caps.transportInfo as? WifiInfo ?: return   // API 31+
        onSsid(info.ssid.trim('"'))
    }
    override fun onLost(n: Network) = onWifiLost()
})
```

Two constraints worth stating plainly:

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
val pi = PendingIntent.getActivity(this, 0, Intent(this, ArmTrampolineActivity::class.java),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
startActivityAndCollapse(pi)   // PendingIntent overload, API 34+; Intent overload deprecated in 34
```

`ArmTrampolineActivity` starts `SnoozeService` in `onCreate` — before any UI — so arming never waits
on rendering. The FGS start and the subsequent location access are then both squarely inside
documented exemptions. Ending a snooze needs no trampoline; stopping a service is unrestricted.

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
3. **A periodic backstop** — a coarse `WorkManager` check on the order of 15–30 minutes while armed,
   purely to catch a geofence that never fired. Cheap, and it bounds worst-case staleness to
   something well short of the duration cap.

Confirmation still runs through the one §6.6 test, so no source can end a snooze on its own evidence.
This layering is why the `play` flavor's departure latency should land near the `direct` flavor's in
the common case, despite the geofence's own numbers.

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
A coroutine timer inside the service handles the normal case; the alarm is the belt-and-braces
version that survives the service dying.

Not implemented: the screen-unlock check-in from the original options. It is a worse version of the
location check — same information, more false positives, more code.

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

### 8.2 Permission revoked mid-snooze

`ACCESS_NOTIFICATION_POLICY` revoked, or location permission downgraded to coarse or denied: end
the snooze, notify with the reason. Do not attempt to limp along silently.

### 8.3 Reboot

`BOOT_COMPLETED` is an exemption for *starting* a location foreground service, but it is **not** on
the while-in-use exemption list — so a service started from boot gets no location, and no unredacted
SSID. There is no way around this without `ACCESS_BACKGROUND_LOCATION`.

Behavior on boot with an unexpired snooze: re-assert the zen rule (this needs no location and works
fine), start the service in degraded mode, and post the same `Resume tracking` notification as §8.1.
The duration cap continues from its original start time — reboots do not extend a snooze.

Alternative considered and rejected: end the snooze on every reboot. Simpler, but an OTA update
rebooting the phone at 2 a.m. would unsilence a bedtime snooze, which is the exact harm the app
exists to prevent. Make this a setting (`On restart: resume / end`), defaulting to resume.

### 8.4 Others

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
4-hour snooze). There is no ongoing notification and no process of ours running between events.

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

`StatusBarManager.requestAddTileService()` (API 33+) works on both; use it during onboarding rather
than telling the user to go edit their shade by hand. It requires the app to be in the foreground
and the tile service to be `exported="true"`, and the system auto-denies after repeated dismissals,
so ask once and never again.

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

Application ID `app.snoozemo`; module packages hang off it (`app.snoozemo.tile`,
`app.snoozemo.dnd`, `app.snoozemo.presence`). The zen rule's condition URI is
`snoozemo://snooze` (§5.3).

**minSdk 33** (Android 13) — gives `requestAddTileService`, `POST_NOTIFICATIONS`, and modern Wi-Fi
APIs without version branches, and covers every Pixel and Samsung flagship still receiving updates
in 2026. **targetSdk 36** (Android 16) — Play requires 36 for new uploads and updates from
31 Aug 2026, so start there. The API 35 `AutomaticZenRule.Builder` / 4-arg `Condition` paths still
need SDK-33/34 fallbacks (§5.3, §5.4); that is the only significant version branching in the app.

### Data

```kotlin
data class ActiveSnooze(
    val anchor: Anchor,
    val startedAt: Instant,
    val capExpiresAt: Instant,
    val placeName: String,          // "Here" until saved/named
    val mode: TrackingMode,         // FULL, WIFI_ONLY, DURATION_ONLY
)
```

Persisted on every state transition so a process death is fully recoverable.

---

## 12. Privacy

- **No `INTERNET` permission.** Nothing can be exfiltrated, and the Play Data Safety declaration is
  "no data collected, no data shared" — trivially true and trivially auditable.
- Coordinates never leave the device. The v1 anchor is discarded when the snooze ends.
- Snooze history (if added) is local, off by default, and clearable.
- **The debug log (§4.6) is the one sanctioned exception, and a narrow one.** It is off by default,
  on-device, bounded to two runs, and leaves the device only when the user shares it through the
  system share sheet. Its floor is absolute: coarse state, reasons, distance from the anchor in
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

---

## 13. Testing

**Unit** — `SnoozeController` transitions; the §6.6 departure test against recorded fix traces
including bad-accuracy jumps; cap arithmetic across DST boundaries.

**Instrumented** — mock location provider to drive synthetic departure traces; `ZenRuleManager`
against a real `NotificationManager` with policy access granted.

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
