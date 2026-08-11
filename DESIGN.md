# Snoozemo — Design

**Status:** Draft for review · **Date:** 2026-08-11 · **Platform:** Android (Pixel first, Samsung supported)
**Application ID:** `app.snoozemo`

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
4. **Play-distributable without a restricted-permission review** in the default build.
5. **Works on Pixel and on Samsung One UI**, acknowledging that One UI's background-process
   management is the harder target.

### Non-goals (v1)

- Scheduled or calendar-driven DND. The OS already does this well; Snoozemo is the *ad-hoc,
  place-scoped* case that the OS does badly.
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
| D2 | **No `ACCESS_BACKGROUND_LOCATION`** in the default build | Avoids the Play restricted-permission declaration on *every* track, including internal testing (§3) |
| D3 | Presence tracked by a **user-visible foreground service**, not the Geofencing API | Follows from D2 (Geofencing API requires background location); also far more survivable on Samsung |
| D4 | **Wi-Fi is a suppressor, not a trigger** | Still on the anchor SSID ⇒ definitely still here (skip location entirely). Wi-Fi dropped ⇒ *maybe* left, so escalate to a location check. Never end a snooze on Wi-Fi loss alone |
| D5 | **Implicit anchor**: the tile captures "here" at arm time | Zero setup. Saved places are a later addition, not a prerequisite |
| D6 | **Three independent exits**: departure, max duration, manual | Any one sensor can fail; the phone must always come back |
| D7 | **Fail open, always** | Every ambiguous state resolves toward ending the snooze, not extending it |
| D8 | Geofencing API + background location available as an **opt-in build flavor** | For users who want no ongoing notification and are willing to pay the Play review cost |
| D9 | **Offer "until this meeting ends" only when a meeting is actually in progress** | Keeps the zero-friction one-tap path intact in the common case, and surfaces the choice exactly when it is meaningful (§4.4) |

---

## 3. Distribution: the background-location tradeoff

You asked for more detail here, because it is the decision that shapes the architecture.

### What I found

`ACCESS_BACKGROUND_LOCATION` is a Play **restricted permission**. Completing the Permissions
Declaration Form is required for any active app bundle carrying it — and per Play Console Help, that
alert applies to **releases on the Internal, Closed, and Open test tracks**, not just production.
Until you complete the declaration you cannot publish *any* change, including store listing edits.
So an internal-only track does **not** buy an exemption.

What the declaration asks for: a written justification that background location is *core* to the
app's user-facing feature, a statement of why a while-in-use alternative won't work, and a
demonstration video showing the in-app disclosure and the permission prompt. The bar Google applies
in practice — background location must be essential, not a convenience — is one a "silence my phone
while I'm at the cinema" app is genuinely at risk of failing, because a while-in-use alternative
*does* exist. Which is exactly what this design uses.

### The three options

| | **A. Foreground service** (default) | **B. Geofencing API** | **C. Wi-Fi only** |
|---|---|---|---|
| Background location perm | No | **Yes** | No |
| Play declaration + video | **No** | Yes, all tracks | No |
| Play Services required | No (optional) | **Yes** | No |
| Ongoing notification | **Yes** | No | No |
| Departure latency | 10–90 s (tunable) | 2–6 min | Instant, when it works |
| Battery, 4 h snooze | ~1–3% (§9) | <1% | ~0% |
| Works with no Wi-Fi | Yes | Yes | **No** |
| Survives One UI Sleeping Apps | **Best** | Poor | Poor |
| Survives reboot unattended | Needs a re-arm tap (§8.3) | Yes | Partially |

### Recommendation

**Ship A as the default.** The ongoing notification is the main cost, and it is close to a
non-cost here: you *want* an unmissable "your phone is silenced" indicator with a one-tap "end
snooze" action. Requirement 3 asks for that affordance anyway; option A gets it for free and gets
Samsung reliability along with it. The notification lives at `IMPORTANCE_LOW` (silent, no peek) and
exists only while a snooze is armed, which is bounded by the duration cap.

Keep B behind a `background` product flavor. If you later decide the notification is intolerable,
the presence engine's interface (§6.1) is written so B is a swap of one implementation, and you can
do the Play declaration then. Nothing in v1 forecloses it.

Reject C as a primary mechanism — it is unusable at any place without Wi-Fi, and Wi-Fi drops for
reasons that have nothing to do with leaving. It survives as the *suppressor* half of D4.

---

## 4. User-visible behaviour

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

- **Icon:** a `Zz` glyph. Quick Settings icons are 24 dp single-colour vector drawables, tinted by
  the system — a clock-with-zzz has too much detail to read at that size and would turn to mush once
  flattened to one colour. A bold two-character `Zz` mark, or a crescent moon with a single `z`, is
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
    [ End now ]   [ Add 1 hour ]
```

### 4.4 Choosing an end condition

"Until I leave" is the thesis, but it is not always the *best available* answer. If you are in a
meeting that ends at 15:30, "until 15:30" is sharper than "until I walk out" — you might not walk out
for another hour. The design should offer a better end condition when it can infer one, without
taxing the common case with a menu.

**The rule (D9): tap arms immediately with the best default; a chooser appears only when there is a
genuinely better alternative on offer.**

Concretely, the trampoline activity (§6.9) already exists on the arm path. When a context signal is
present it renders a compact bottom sheet instead of finishing silently; when nothing is on offer it
stays invisible and arms in one tap, exactly as today. Either way the snooze is armed *before* the
sheet is shown, so dismissing it, or the sheet failing to appear, leaves you correctly snoozed.

```
🌙  Snoozing at Home
    ── End when ──────────────────
    ○ I leave here                  ← default, always present
    ● This meeting ends    15:30    ← offered only if a meeting is in progress
    ○ In 1 hour                     ← always present
```

#### Candidates

| End condition | Signal needed | Verdict |
|---|---|---|
| **I leave here** | §6 presence engine | **v1.** The default, always offered |
| **Fixed duration** | none | **v1.** Always offered as a fallback; also the §7 cap |
| **This meeting ends** | `READ_CALENDAR` | **v1, opt-in.** The strongest of these — a real end time, and the moment people most want silence. Details below |
| **Either — whichever comes first** | both | **v1.** Not a fourth option in the list: "meeting ends" implies it, by setting the cap to the meeting end while departure tracking stays armed. Leaving early still ends it early |
| **My next alarm** | `AlarmManager.getNextAlarmClock()` | **Explore.** No permission at all, and a natural fit for a bedtime snooze. Offer only when the next alarm is 3–12 h out, so it doesn't propose a 4-minute snooze |
| **I start moving** | `TYPE_SIGNIFICANT_MOTION` | **Explore.** No permission, already wired up for §6.7. Different intent from "I leave" — useful for "quiet while I'm sitting here", but noisy: standing up to get coffee would end it. Would need a distance confirmation, at which point it is nearly "I leave" |
| **I get home** | reverse geofence on a saved place | **Deferred.** Needs saved places (§14) and, for a place you are not at, background location. Naturally pairs with the option-B flavor |
| **Sunset / bedtime window** | none | **Rejected.** The OS's own scheduled Modes do this properly. Snoozemo is for the ad-hoc case |
| **Screen unlocked N times** | none | **Rejected.** A proxy for attention, not for place or time, and wrong in both directions |

#### The calendar option

`READ_CALENDAR`, queried against `CalendarContract.Instances` for events overlapping now:

```kotlin
val now = System.currentTimeMillis()
CalendarContract.Instances.query(contentResolver, PROJECTION, now, now + 12.hours)
    .filter { it.begin <= now && it.end > now }
    .filter { it.availability == Instances.AVAILABILITY_BUSY }   // ignore "free"/FYI blocks
    .filter { it.allDay == false }                               // an all-day event is not a meeting
    .minByOrNull { it.end }                                      // the soonest-ending overlap
```

Design constraints:

- **Fully optional and lazily requested.** The permission is asked for the first time the user taps
  *End when → this meeting ends*, never during onboarding. If it is not granted the option simply
  never appears, and the app is undiminished. This matters for Play review too: an app that asks for
  calendar access up front, for a secondary feature, invites scrutiny it does not need — see §3's
  general principle, applied again. `READ_CALENDAR` has no restricted-permission declaration form,
  but it is still governed by Play's sensitive-permissions policy and must be visibly tied to a
  user-facing feature. Lazy, in-context requesting is what makes that tie obvious.
- **Read-only, never written, never leaves the device** (there is no `INTERNET` permission to leave
  by, §12).
- **Meetings run over.** Offer *Add 15 min* alongside *Add 1 hour* in the ongoing notification (§4.3)
  when the snooze was armed against a calendar event, and prefer extending over re-arming.
- **Back-to-back meetings.** v1 ends at the current event's end and does not chain into the next one.
  Chaining is tempting and is how you end up silenced all afternoon by a calendar you forgot about;
  the cap would catch it, but surprising the user is still the wrong default.

### 4.5 Ending

When a snooze ends by departure or by cap, post a one-shot dismissible notification:
`Snooze ended — you left Home` / `Snooze ended — 8 hour limit reached`. This is how the user builds
trust that the mechanism works, and how they notice when it fires wrongly.

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
    fun start(anchor: Anchor): Flow<PresenceEvent>   // StillHere, ProbablyLeft, Departed, Degraded
    fun stop()
}
```

Two implementations: `ForegroundPresenceMonitor` (default flavor) and `GeofencePresenceMonitor`
(background flavor, §3 option B). Everything above this line is flavor-agnostic.

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

Fused location, no geofences (D3):

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
means that when you are unambiguously a kilometre away, the phone comes back immediately rather than
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
<!-- Optional, requested lazily on first use of "until this meeting ends" (§4.4).
     Never requested during onboarding; the feature hides itself if not granted. -->
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
val pi = PendingIntent.getActivity(this, 0, Intent(this, ArmTrampolineActivity::class.java),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
startActivityAndCollapse(pi)   // PendingIntent overload, API 34+; Intent overload deprecated in 34
```

`ArmTrampolineActivity` uses `@android:style/Theme.NoDisplay`, starts `SnoozeService`, and calls
`finish()` in `onCreate`. No visible flash, and the FGS start and the subsequent location access are
both squarely inside documented exemptions. Ending a snooze needs no trampoline — stopping a service
is unrestricted.

---

## 7. Exits

All three exits converge on one `endSnooze(reason)` path that sets the rule state `STATE_FALSE`,
stops the service, unregisters callbacks, cancels the cap alarm, and posts the ended-notification.
Idempotent; safe to call twice.

| Exit | Trigger | Notes |
|---|---|---|
| **Departure** | §6.6 confirmation | The intended path |
| **Duration cap** | Default 8 h, configurable 30 min – 24 h | Backstop for every sensor failure |
| **Manual** | Tile tap, notification action, or in-app | Always available, always instant |

A chosen end condition from §4.4 does not add a fourth exit — it *lowers the cap*. Picking "this
meeting ends, 15:30" sets `capExpiresAt` to 15:30 while departure tracking stays fully armed, so
whichever happens first wins and leaving early still ends the snooze early. The 8-hour ceiling
remains as an absolute backstop above any chosen value.

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

Behaviour on boot with an unexpired snooze: re-assert the zen rule (this needs no location and works
fine), start the service in degraded mode, and post the same `Resume tracking` notification as §8.1.
The duration cap continues from its original start time — reboots do not extend a snooze.

Alternative considered and rejected: end the snooze on every reboot. Simpler, but an OTA update
rebooting the phone at 2 a.m. would unsilence a bedtime snooze, which is the exact harm the app
exists to prevent. Make this a setting (`On restart: resume / end`), defaulting to resume.

### 8.4 Others

| Case | Behaviour |
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

Rough budget for a 4-hour snooze on a modern Pixel:

| Scenario | Estimate |
|---|---|
| On anchor Wi-Fi the whole time | <0.5% — network callback only, no location, no sensor polling |
| No Wi-Fi, phone stationary | ~1% — significant-motion trigger plus a 10-minute sanity fix |
| No Wi-Fi, intermittent movement | ~2–3% — 90 s balanced-power fixes during active periods |

The FGS notification itself costs nothing. The dominant term is location fix frequency, which §6.7
drives toward zero in the common case (you are at a place with Wi-Fi, and you are not moving).

---

## 10. Pixel and Samsung

### Pixel

The reference target. Android 16 Modes UI will surface the Snoozemo rule as a first-class Mode.
Nothing OEM-specific required.

### Samsung One UI

Three areas need real-device verification, not assumption:

1. **Sleeping apps / Deep sleeping apps.** One UI's Battery → Background usage limits will put
   infrequently used apps to sleep, which breaks background work. A foreground service is much more
   resistant to this than a geofence registration would be (a second argument for D3), but it is not
   immune. Onboarding should include a Samsung-detected step explaining how to add Snoozemo to
   *Never sleeping apps*.

   Deep-link with `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, which opens the list
   without requiring any permission. Do **not** declare
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — Play restricts its acceptable use, and the direct
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog it enables would be a policy risk for a
   marginal UX gain. Guide the user, don't automate it. Avoid deep-linking into
   `com.samsung.android.lool` internals; those component names change between One UI versions.

2. **Modes and Routines.** One UI has its own DND/Modes layer over AOSP's. Third-party
   `AutomaticZenRule`s have historically appeared under DND schedules, but One UI 8 reorganised this
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
:core         SnoozeController (state machine), Anchor, exits, persistence
:dnd          ZenRuleManager — all NotificationManager/AutomaticZenRule contact
:presence     PresenceMonitor interface
              ├── foreground/  ForegroundPresenceMonitor + SnoozeService  (default flavor)
              └── geofence/    GeofencePresenceMonitor                    (background flavor)
```

`SnoozeController` is a plain Kotlin state machine over the §4.1 diagram, with no Android
dependencies beyond a clock and the two injected interfaces — so it is fully unit-testable, which is
where most of the real complexity lives.

### Stack

Kotlin · Compose + Material 3 · coroutines/Flow · Hilt · DataStore (settings, active-snooze record)
· Room (saved places and snooze history, once §14 lands — not needed for v1's single implicit anchor)
· Play Services Location (`play-services-location`, used for fused location even in the default
flavor; the geofencing surface is only touched by the background flavor).

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
- In-app prominent disclosure before the location permission prompt, explaining the *place* use and
  the fact that tracking runs only while a snooze is armed.
- `android:allowBackup="false"` — a backup of location anchors is not worth the exposure.

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
  and the Play declaration — so it is the natural trigger for adopting the §3 option-B flavor.
- **`ZenDeviceEffects`** — grayscale, dim wallpaper, night mode while snoozed (§5.5).
- **"Until I get home"** and other saved-place reverse geofences (§4.4), which follow from saved
  places plus the option-B flavor.
- **Chaining back-to-back meetings** (§4.4), if using the app shows people actually want it.
- **Wear OS tile.**

---

## 15. Milestones

| | Deliverable |
|---|---|
| **M1** | `ZenRuleManager` + policy-access onboarding. Arm and release from a debug button. Proves the DND half on both devices |
| **M2** | Tile, trampoline, foreground service, notification. Duration cap and manual exit working |
| **M3** | Presence engine: Wi-Fi suppressor, location confirmation, duty cycling. Departure exit working |
| **M4** | End-condition chooser (§4.4): calendar option behind a lazy `READ_CALENDAR` request, next-alarm option, sheet UI |
| **M5** | Edge cases — reboot, service death, permission revocation, degraded modes |
| **M6** | Samsung hardening; onboarding polish; internal track release |

M1 is deliberately first and deliberately small: if `setAutomaticZenRuleState` does not actually
silence a Samsung device (§10.2), that changes the project, and it is much better to learn it in
week one than in week five.

---

## 16. To verify on hardware before committing

1. Does `setAutomaticZenRuleState` genuinely silence a One UI 8 device, and is the rule visible in
   Samsung's Settings? *(highest risk — M1)*
2. Does `Tile.setSubtitle` render on One UI?
3. Does the trampoline activity produce any visible flash on either device?
4. Real battery draw over a 4-hour stationary snooze versus the §9 estimates.
5. Does One UI's Sleeping Apps touch a `location`-typed foreground service in practice?
6. Confirm current Play Console behaviour for a bundle *without* `ACCESS_BACKGROUND_LOCATION` — the
   whole of D2 rests on no declaration being required, and this is worth confirming with a real
   internal-track upload before M5.
