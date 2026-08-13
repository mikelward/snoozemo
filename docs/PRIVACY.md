# Snoozemo privacy policy

**Last updated: 2026-08-13. Covers Snoozemo v1 for Android.**

## The short version

**Snoozemo does not send anything you do to anyone.** There is no account to create, no
analytics, no advertising, no crash reporting, and no server for any of it to go to.
Snoozemo does need to know where you are — that is the whole point of "stay quiet until I
leave here" — but that answer is worked out on your phone and never sent anywhere. The one
way what it stores can reach a second device is Android's own new-phone setup transfer,
covered under **Backup**.

## What leaves your phone

Snoozemo sends nothing, anywhere, to anyone.

It does not ask Android for the `INTERNET` permission, so it cannot open a network
connection at all. That is not a promise you have to take on trust: Android enforces it,
and the permission list is in the app's manifest, which anyone can read in the source at
<https://github.com/mikelward/snoozemo>.

Our Google Play Data Safety declaration says the same thing: **no data collected, no data
shared**.

The one thing that can move Snoozemo's data off a phone is Android itself, when you set up
a new phone from your old one. That is a copy you asked for, going to a device you own, and
it is covered under **Backup** below.

## What Snoozemo keeps on your phone

All of this lives in Snoozemo's private app storage, which other apps cannot read.

| What | Why | How long |
|---|---|---|
| That a snooze is running, when it started, and when its time limit runs out | So a snooze survives the app being closed, killed, or the phone rebooting — and so the time limit still ends it | Erased when the snooze ends |
| Where you were when you armed it: coordinates and how accurate the fix was | This is the "here" in "until I leave here". Departures are measured against it | Erased when the snooze ends |
| The name of the Wi-Fi network you were on (SSID) | Losing that network is a fast, battery-free hint that you have left | Erased when the snooze ends |
| The identifier of the particular access point you were on (BSSID) | Captured alongside the network name. Nothing acts on it today, and it is never what a departure is judged against — it is kept because a room is smaller than a network, see below | Erased when the snooze ends |
| The name of the place, if the snooze has one | So the notification can say *Snoozing at Home* rather than just *Snoozing* | Erased when the snooze ends |
| Whether tracking is running fully or has degraded | So the notification can tell you when Snoozemo has lost a sensor and is running on the timer alone | Erased when the snooze ends |
| A scrambled marker of which phone the snooze was started on | So a snooze copied onto a new phone is not resumed there, silencing a phone you never armed it on. It is a one-way hash, never the identifier itself, and only ever compared for a match | Erased when the snooze ends |
| The identifier of the Do Not Disturb rule Snoozemo created | So it reuses one rule instead of leaving a trail of them in your Settings | Erased once Snoozemo next notices you deleted that rule in Settings, or when you uninstall |
| Whether the Quick Settings tile has been added, and whether you dismissed the tile suggestion | So the app stops suggesting something you already did, or already said no to | Until you uninstall |
| Whether you have turned down the notification permission | So the app asks once and then stops asking | Erased as soon as you allow notifications, or when you uninstall |
| Why a snooze failed to start, when it failed while you were not looking | So a tap that quietly did nothing tells you what went wrong, rather than leaving you to guess | Erased when your next snooze starts and makes it moot. It is kept even after the message is shown, in case the message never arrived — if it was waiting on notification permission, showing it is what clears it |
| A note that Do Not Disturb may still be on after Snoozemo lost track of it | So Snoozemo keeps trying to turn its own rule back off, instead of leaving your phone quiet with nothing watching | Erased when the rule is confirmed off, or when a new snooze takes the rule over — seeing the warning is not enough |

There is no history: Snoozemo does not keep a record of past snoozes, past places, or past
Wi-Fi networks. When a snooze ends, the record of it — including the location it was
anchored to — is deleted.

Deleting it is a write to storage, and writes can fail. Snoozemo handles that in two
layers: it first marks the record finished, so anything reading it later knows the snooze
is over, and then erases it — retrying, and letting the next snooze overwrite it. Clearing
storage or uninstalling removes it outright.

If storage refuses *both* writes, a finished snooze can be read back as live and put Do Not
Disturb on again. This is the failure Snoozemo works hardest to bound, so it is worth being
plain about: it is why every snooze carries a time limit that is fixed when you start it
and cannot be extended by a restart. Even in that case the phone comes back, at the limit
you already had.

## Location

Snoozemo uses location for one thing: deciding whether you have walked away from where you
armed the snooze. The only other thing it captures is the access point note described under
**Wi-Fi** below, which nothing currently acts on.

- **Only while a snooze is running.** Arming starts it, ending stops it. With nothing
  snoozed, Snoozemo is not tracking you and is not watching for you to leave. It keeps a
  little ready in memory so that arming is instant, but nothing about where you are is
  written down until you snooze.
- **Compared on your phone.** Snoozemo stores one anchor point and measures your distance
  from it. The comparison, and the decision it leads to, happen on the device.
- **Deliberately coarse in what it keeps.** One point, one radius, no path, no history.
- **Background location.** The Google Play build uses Android's geofencing, which needs
  permission to check your location while the app is not open — otherwise a snooze could
  only end while you were staring at the app, which defeats the purpose. Snoozemo explains
  this before Android's permission prompt, and the build distributed outside Play does not
  ask for it at all.

If you deny location, Snoozemo still works, but it cannot tell when you have left — and
that includes the Wi-Fi hint below, because Android puts the name of your current network
behind the same permission. A snooze you start after that runs on its time limit alone,
and the notification says so rather than pretending it is still watching.

Turning the permission off *while* a snooze is running is different: Snoozemo ends that
snooze as soon as it notices, which may not be the same moment — if nothing has woken the
app in the background, it can be a while. A phone left silent on the strength of something
it can no longer check is the failure this app is most careful to avoid, so whenever
Snoozemo is unsure, it lets the sound back.

## Wi-Fi

Snoozemo reads the name of the network you are connected to, and notices when you leave it.
It does not scan for other networks, and it does not keep a list of the networks you have
used.

It also notes *which* access point you were on, which is a separate identifier from the
network name. **Nothing acts on it today** — it is captured with the anchor and erased with
it — and it is not what a departure is judged against.

It is kept because a room is smaller than a network. In an office the whole floor is one
Wi-Fi name, so leaving the meeting room you are sitting in does not change it, and "quiet
until I leave here" is exactly the case where that matters. If a future version uses this
to notice you have left a room, it will not do so on the access point alone — phones move
between access points while you sit still — and this section will say what it does before
it does it.

Android treats reading the current network's name as a location capability, so this needs
the location permission too — that is Android's rule rather than ours, and it is why
denying location also costs you this. The permission is used for the departure detection
described above and for the two Wi-Fi values described here — the things this policy has
already listed — and for nothing else.

## Do Not Disturb access

Snoozemo asks for Do Not Disturb access so it can turn its own rule on and off. It creates
one rule of its own and only ever changes that one — it does not touch Do Not Disturb rules
belonging to other apps or to your own bedtime and focus schedules, and it does not read
your notifications.

## Notifications

The notification permission is used to show you what a snooze is doing: that it is running,
when it will end, when it has ended, and when something has gone wrong. Nothing is sent
anywhere.

## Backup

Snoozemo sets `allowBackup="false"`, so nothing it stores goes into Android's automatic
cloud backup. That part is deliberate: a point describing where you were is not worth
uploading to anyone's cloud.

One caveat we would rather state than have you assume. On Android 12 and later that setting
reliably turns cloud backup off, but on some manufacturers' phones it does **not** turn off
*device-to-device transfer* — the copy made when you set up a new phone from your old one.
So whether Snoozemo's stored data comes across with you depends on the phone. What it does
not do, on any phone, is sit in a cloud backup.

Snoozemo now asks Android, in its manifest, **not** to carry a running snooze onto a new
phone — along with the tile and permission reminders, which describe a phone that is not the
new one either. That request is the reliable half of this and should be the end of it.

The catch is that it is a request to the same transfer machinery that may already ignore
`allowBackup`, and there is no way to know from inside the app whether a given phone
honored it. So a running snooze also records **which phone it was started on**, and Snoozemo
refuses to resume one that started somewhere else. That is the half that does not depend on
the transfer tool cooperating, and it is there so a new phone never goes quiet for a snooze
you started on an old one.

That record is a scrambled value, not a device identity: it is derived from an Android
identifier through a one-way hash, and the only question ever asked of it is whether it
matches the one on this phone. It is stored with the snooze and erased with it, and
Snoozemo never sends it anywhere — there is no permission in the app that could. The one
way it can move is the same device-to-device transfer described above, and that is not an
exception so much as the point: if the transfer copies the snooze, the marker has to travel
with it, because comparing it is how the new phone knows to refuse. If
Snoozemo cannot read that identifier at all — which happens on some builds — it says so in
its log and carries on without the check rather than ending snoozes it cannot vouch for.

Beyond the running snooze there is usually little to travel: the location anchor is erased
when the snooze ends, so most of the time what could be copied is the short list of settings
in the table above. Those are deliberately left transferable — they are yours, and losing
them on a new phone is its own kind of failure. This section changes when that question is
settled either way.

## Deleting your data

- **Ending a snooze** erases that snooze's record, including its location anchor — with the
  storage-failure caveat described above, which retries rather than gives up.
- **Uninstalling Snoozemo** removes everything above, unconditionally.
- Or, without uninstalling: **Settings → Apps → Snoozemo → Storage → Clear storage**, also
  unconditional. This is the one to use if you want a guarantee rather than a retry.

There is no server holding a copy, so there is nothing to delete anywhere else — with one
exception worth naming: if Snoozemo's data came across to a new phone during setup, the old
phone still has its own copy, and it is deleted the same way.

## Children

Snoozemo is not directed at children. It works the same way for a user of any age: what it
keeps is the short list in the table above, it stays on that phone, and none of it is
collected by us or sent to anyone.

## Changes to this policy

This document lives in the app's source repository, so every change to it is a dated,
public commit at <https://github.com/mikelward/snoozemo>. If Snoozemo ever starts doing
something this policy does not describe, the policy changes first.

## Contact

Questions or concerns: open an issue at
<https://github.com/mikelward/snoozemo/issues>.
