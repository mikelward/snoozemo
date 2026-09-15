# Welcome flow

A sketch of the first-run screens, their order, and their wording. The
**2026-09-15 overhaul** reshaped the set below — cards merged, split and
reordered — and its copy is **proposed, not approved**: per `AGENTS.md` the
maintainer signs off on the English, and sees it on a device, before any of it
is translated. Product facts it leans on are cited to `SPEC.md`; if the two ever
disagree, the spec wins and this file is what gets fixed.

Why this exists: a fresh install once landed on `PermissionsScreen` (`SPEC.md`
§4.2) — three permission rows and nothing that says what the app is for or how
it is meant to be used. The tile is the whole product, and it is invisible until
someone adds it. The flow below says what a new user needs, in the order they
need it, and *then* asks for permissions, so every row on that screen already has
a reason attached.

## Shape

- **Four cards, then the existing permissions screen when a permission is still
  missing** — plus a fifth, the crash/analytics consent, only when crash reporting
  is configured. Each card is one idea, one illustration, a title of at most four words
  and a body of at most two lines. At the default font and display size the layout
  is fixed and nothing scrolls; as the sizes grow the illustration gives way to the
  text first, and only where the text still cannot fit — a small handset at Android's
  largest font and display scale, on the densest cards — does the body scroll
  vertically, with the title row scrolling as part of it and only `Back`, the
  progress dots and `Next` pinned below. The title row is *in* the scroll on
  purpose, as on every other screen: pinned, a wrapped heading at the largest
  font on a short window left the body no viewport at all (Codex, PR #209). The text is
  never truncated and no `Allow` is ever clipped off the bottom: with both "no
  scrolling" and "no truncation" held absolutely there is no valid overflow at those
  sizes (Codex, PR #193), and clipping a grant is the worse of the two. Verify at the
  largest display and font size Android offers before calling any card done.
- **The card's title is the screen's title, in the app's own title row, with
  `Skip` as its trailing action** (maintainer, 2026-09-06). The same
  `SnoozemoTitleRow` every other screen uses, so the mark sits where a user
  already finds it and the title reads as a page heading rather than the first
  line of a body that scrolls away under it.

- **`Back`, the progress dots and `Next` along the bottom** (maintainer,
  2026-09-06): the flow's own two controls with the place in it read between
  them. `Back` goes to the previous card and, off card 1, out of the flow — the
  same lambda the system back gesture runs, so the control and the gesture can
  never disagree. The dots are centered in what the buttons leave rather than in
  the row, since the two buttons are different widths and grow at different
  rates with type size; true centering would have them collide at the sizes
  where the row is tightest.

- **No `Skip` on card 1** (maintainer, 2026-09-06) — the one card where the
  title row's action slot is empty. Offering to leave beside the one line that
  says what the app is invites skipping before there is anything to skip. D7 is
  untouched: back still exits card 1, so the way out is there, just not
  advertised before that line has been read.

  `Skip` and the last card's `Next` both land in the same place:
  `PermissionsScreen` while a permission is still missing, `MainScreen` once nothing
  is — so there is no way through that misses a missing permission, none that shows
  a recap with nothing to recap, and no way to get stuck, which is the same fail-open
  rule the permissions rows follow (§4.2: "a setup flow that cannot be left without
  finishing it is a trap, not onboarding"). "Missing" means a permission the
  build offers and the recap would show an action for: a row suppressed because
  nothing uses it counts for nothing, or a user would be routed to a recap they
  cannot satisfy.
- **A dismissible hint points at the (?) icon once the flow is left**
  (maintainer, 2026-09-05): `Tap (?) to see the tutorial again`, with a
  `Dismiss`. The replay lives behind an icon, which is discoverable only if you
  already know it is there, and the one moment saying so means anything is the
  moment the user has just finished the cards. Quieter than the tile banner —
  that one argues for an action, this only says where something is — and it has
  its own flag, so dismissing the hint is not a statement about the flow.
- **A granted permission's row is dropped from the card entirely** (maintainer,
  2026-09-05). Once there is no action left to offer, the row is a line of text
  the user reads past, so the card ends at whatever still needs them. The recap
  keeps its granted rows — saying what is already in place is that screen's
  whole job — so this is the card's choice, not the row's.
- **Shown once.** A persisted flag records that the flow has been seen; the
  permissions screen's own once-only routing stays as it is (§4.2). Replayable, so the
  cards are not lost once seen, from a **(?) icon in `MainScreen`'s title row, before
  the settings gear** (maintainer, 2026-09-05) — logical order, not physical: the app
  supports RTL, where a `Row` mirrors, so "left" would pin the icons in a direction
  the layout is meant to flip (Codex, PR #203). It is on `MainScreen` rather
  than in Settings because the person who needs it is on the home screen wondering
  what to do; the word `Tutorial` survives as the icon's accessible name.
- **Progress dots, no numbers, no "1 of 4".** A row of dots says enough.
- **Each card offers the grant for the thing it just introduced** (maintainer,
  2026-09-05; remapped by the 2026-09-15 overhaul). Card 1 carries the `Add tile`
  action, card 2 `Allow` for Do Not Disturb access plus the Filters row and the
  live ringer choice, card 3 `Allow` for notifications, card 4 `Allow` for the
  calendar and for location. The button is the same tri-state row
  `PermissionsScreen` already draws (§5.2: the action is a verb, it is offered only
  while the platform will still honor it, and it points at the app's settings once
  the prompts are spent) — the cards embed those rows, they do not re-implement
  them, so the observed-denial flag, the location disclosure sequence (§3.2:
  foreground, then the dialog, then background) and the "no row offers an action
  the platform will ignore" rule all come for free. `Add tile` is allowed here for
  the same reason it is allowed on the banner: it is a button the user tapped, not
  a launch-time prompt (§10). Every button is optional — `Next` never waits on a
  grant — which is the fail-open rule again.
- **Every card is a screenshot test**, wired into CI's allow-list like the four
  screens already are (`AGENTS.md`, *Testing expectations*).
- **A tile tap during the unfinished flow resumes it** (maintainer, 2026-09-07;
  broadened 2026-09-15). Every arm tap before the flow is finished is taken back to
  the card the user was on, **even one that could arm** — so a user part-way through
  is never dropped into a snooze started mid-setup. This is what makes card 1's
  `Add tile` safe ahead of Do Not Disturb access: the "tile without access" tap the
  old order guarded against cannot happen while the cards are open. The trampoline
  decides it from a warmed flag (`WelcomeGate`), read on the arm path and never off
  disk (§6.9).

## The cards

### 1 · What it is, and its tile

> **Snoozemo**
>
> ```
> [ Wi-Fi ]      [ Bluetooth ]
> [ Airplane ]   [ (Snooze) ]      ← ringed
> ```
>
> Silence your phone with one tap.
>
> Ends automatically, so you don't forget.
>
> Quick Settings tile · The one-tap way to snooze          [ Add ]

Illustration: the Quick Settings panel with Snoozemo's tile ringed among the
others — the same tile the user will look for in their own shade. Drawn rather
than screenshotted, so it follows the app's theme and text size and the ring
marks Snoozemo's tile rather than a different tile style the shade will never
show (maintainer, 2026-09-07).

Both lines are build-neutral (maintainer, 2026-09-14): they hold on a
duration-only build as much as on one that tracks departure. The first names the
action — one tap, wherever you are. The second is the promise the rest of the
flow keeps: a snooze always ends, so you can silence the phone without fearing
you have silenced it for good. The 8-hour cap is deliberately not named here
(maintainer, 2026-09-05): it still fires and `Ends automatically` covers it, but
a backstop the user never has to think about does not earn words in onboarding.

**This card also adds the tile** (maintainer, 2026-09-15). The tile is the whole
product, so the first card both shows it and offers to add it, through the same
tile row Settings carries and the main-screen banner offers — never
`PermissionsScreen`, which has no tile row (`Add`, which disappears once the
tile is there).
That leads the flow rather than closing it — a reversal of the 2026-09-08 order
that put the tile last so a tile could not be added before Do Not Disturb access.
That order guarded against a tile tapped without access failing to snooze; the
guard is no longer needed during onboarding, because a tile tap before the flow
is finished resumes the flow rather than snoozing (Shape, above). So the tile
returns to card 1, where "what the app is" is honestly answered by "a tile you
tap, and here it is".

### 2 · The rule it silences with

> **One rule, yours**
>
> Creates a new Do Not Disturb mode that you configure.
>
> While snoozing, set the phone to
> ( Ring | **Vibrate** | Silent )
> [ Allow Do Not Disturb access ]
> Filters · What still gets through            [ Edit ]

Illustration: none; the grants and the ringer choice are the interactive
elements. *One rule, yours* (maintainer, 2026-09-05, kept through the 2026-09-15
overhaul) names what the first sentence promises: Snoozemo adds one rule and
touches nothing else of the user's.

One rule, named `Snoozemo`, created once and never churned (§5.3); the app turns
off *only its own rule* and leaves any other Do Not Disturb alone (§5.6). Do Not
Disturb access is a Settings toggle rather than a runtime prompt (§5.2), and
`Allow` opens a short help dialog first (maintainer, 2026-09-14): the Settings
screen it then reaches is a list the user has to find Snoozemo in and turn on, a
step the button alone does not explain. It is the grant without which nothing
here can snooze at all, so it sits on card 2 — after the tile it configures and
before the two cards on how a snooze ends.

`Filters` is offered, not only named, through the same row `SettingsScreen`
draws. It appears only once there is a rule to edit — access granted and the rule
created — and is absent before that, so it is never a tap with nothing behind it.
The ringer choice is §5.9's ceiling, defaulting to `Vibrate`, written to the same
setting `SettingsScreen`'s *Ring/vibrate* row edits — the setting a user is most
surprised by after the fact, so it earns a place in the flow. It sits **above** the
Do Not Disturb access grant (maintainer, 2026-09-15): a live control the user can
set straight away, ahead of the permission it will apply under.

### 3 · Ending it by hand

> **End manually**
>
> ```
> 🌙  Snoozing                          3:40:12
>     Ends when you leave
>     [ End now ]   [ +30 min ]   [ Until 17:00 ]
> ```
>
> Tap the notification buttons to extend or end the snooze. Tap the notification
> body for more options.
>
> Tapping the tile again also turns it off.
>
> 🔔 Notifications   [ Allow ]

Illustration: the ongoing notification as §4.3 draws it, in its fullest shape — a
render, not a live card, drawn inert (no ripple, set in slightly like a picture)
so nobody tries to tap `End now` on a snooze that is not running. It is the
picture because the notification is where every manual exit lives at once: `End
now` is the user's guaranteed way back to a ringing phone (§7), `+30 min`
extends, and the body opens the end-condition chooser with more options.

The tile is the other manual exit and is named as a quieter note, since it does
what the buttons already do — the same tile that armed the snooze turns it off
again (§4.2, D6). Notifications are requested here rather than merely declared:
without the grant there is no notification to end the snooze from, and no status
bar icon either (§4.3), which is why the grant sits on the card that depicts the
notification. What happens when notifications are denied, the permissions recap
says; this card only introduces them.

### 4 · Ending it by itself

> **Ends automatically**
>
> ```
> [ Until 12:00 ]  [ − ]  [ + ]
> [ Until meeting end ]                        📅
> [ Until I move ]
> [ Until I leave ]
> ```
>
> When you leave, when your meeting ends, or at the time you choose.
>
> 📅 Calendar        [ Allow ]
> 📍 Location        [ Allow ]

Illustration: the end-condition chooser as §4.4 draws it, inert and read as one
node like card 3's render — the one surface that shows every automatic ending at
once: a clock time with its `−`/`+` steppers, the meeting, and the two
departures. Its labels are fixed and fictional (`Until 12:00`, `Until meeting
end`): a real meeting time has no place in a screenshot test's baseline
(`AGENTS.md`, *Privacy*).

The body lists the automatic endings in words, and the two grants below are for
the ones the app cannot offer without permission: the calendar seeds the meeting
end (read only to offer that `Until <time>` action — the app never triggers
itself from the calendar, §1, §4.3), and location the two departures. On a build
that cannot track departure the body drops the "when you leave" clause and the
location row is absent — a grant that buys the user nothing must not be invited
(§3.6), while the calendar row stays, offered on every build.

Splitting the manual and automatic endings across cards 3 and 4 (maintainer,
2026-09-15) replaces the single "how it ends" card of the earlier sketch. That
card carried both stories under one render; two cards let each carry one idea and
put each grant on the card that depicts what it is for.

### 5 · If something goes wrong

> **Help make Snoozemo better?**
>
> Send crash reports and anonymous usage stats so bugs get found and fixed?
> [ No thanks ]                                            [ Yes please ]

Illustration: none. One question and nothing else, and only on a build where
crash reporting is configured — where it is not, there is no fifth card and no
fifth dot (`welcomeCards`).

Crash reports and analytics are a consent, and the consent is asked once (§12):
by the invite card on `MainScreen`, in exactly this wording, and here a screen
earlier. Answered here, the main-screen invite never appears; passed with `Next`,
it still does — an unanswered question is not a "no". Nothing is collected until
the answer is yes. The debug log is not mentioned (maintainer, 2026-09-05): the
card's whole job is one question about data leaving the phone, and a second
sentence about a log that never does is the one most likely to blur the first.

**Answering leaves the flow** (maintainer, 2026-09-05): either button records the
answer and exits, since it is the last card and making the user then find `Done`
asks them to confirm a choice they just made. The exit does not depend on the
stored value changing, so a user repeating an earlier answer is not left with a
tap that looks dead. Last on purpose: after the run of `Allow` buttons rather
than among them, so it does not read as one more of the same, and by then the
user has seen what "help fix bugs" refers to. The affirmative is the trailing,
filled button, with the width between the two so a thumb cannot confuse them —
the same shape as `MainScreen`'s invite, since it is the same question.

### 6 · Permissions

The existing `PermissionsScreen`, unchanged: Do Not Disturb access,
notifications, location and the calendar row — permissions only. The tile is not
on it and never routes to it; a user who skipped card 1 has `MainScreen`'s banner
and `SettingsScreen`'s permanent tile row as the standing routes (§4.2). With
every grant already offered on its own card this screen is a recap, and its job
is what is *still* missing: each row carries the consequence of saying no
(`Snoozes can't end when you leave`, and so on) beside the same `Allow`, and after
cards 1–4 every one of those consequences refers to something the user has just
been shown. `Done` lands on `MainScreen`.

Shown **only when a permission is still missing** (maintainer, 2026-09-05) — one
this flavor offers; a user who allowed everything on the cards lands straight on
the Snooze button. The screen's own once-only routing (§4.2) stays as the
backstop for an install that skipped the flow.

## Not in the flow, on purpose

- **No Samsung step yet.** `SPEC.md` §10 wants a One UI–detected card explaining
  *Never sleeping apps*; it belongs after Phase 8's device verification, shown
  only on One UI, not as a paragraph every Pixel user has to skip.
- **No "how it decides you left".** The departure test (§6.6) is the app's business;
  the user's mental model is "when I leave", and a card explaining Wi-Fi and
  accuracy gates would make the product sound less reliable than it is.
- **No end sheet.** It is off by default (§4.4), its switch stays in Settings, and
  the chosen time card 4 illustrates is the main-screen end-condition chooser's own
  clock row (§4.4) — the render card 4 shows, always present, not the sheet and not
  the notification's `Until <time>` action (which needs a meeting on the calendar).
  Every install has that chooser, so a card or a control for a sheet most users
  never see is one too many (maintainer, 2026-09-05).

## Decided

- The shape and the copy above, as proposed. The 2026-09-15 overhaul is the current
  shape; **the copy stays proposed until it has been seen on a device** before any
  of it is translated (`AGENTS.md`, *Translations*).
- **The shape is a product decision and is recorded as one in `SPEC.md` §4.2** — the
  cards before the permissions screen, a grant on each card, the recap only when a
  permission is missing, the replay icon, shown once, and a tile tap during the flow
  resuming it. The spec is what an implementation is checked against; this file
  carries the wording and the reasoning behind each card.
- The control that replays the flow is a (?) icon in `MainScreen`'s title row, beside
  the settings gear, with `Tutorial` as its accessible name.
- Card 2's ringer choice is a live control, and Do Not Disturb access opens a help
  dialog before the system settings list.
- The order is: what the app is and its tile; the rule it silences with; ending it
  by hand; ending it by itself; then, only where crash reporting is configured, the
  consent.
- The crash-report and analytics consent is the last card and the whole of it: the
  debug log is not mentioned (maintainer, 2026-09-05).

## Open questions for the maintainer

- The copy above is proposed and still to be seen on a device before it is
  translated. `End manually` is the one card title new in this overhaul (card 2
  kept `One rule, yours` and card 4 kept `Ends automatically`), and the split
  cards' bodies may want a pass.
- The accessibility overflow under *Shape* — the body scrolls with the buttons
  pinned, only at sizes where it cannot fit — is a carve-out from "not vertically
  scrollable" taken because no-scroll and no-truncation cannot both hold there. The
  alternative is splitting a dense card in two at those sizes. Say if you would
  rather split.

## Simmo

The same shape — what it is, how it is used, then the permissions interstitial —
with Simmo's own three ideas: rules pick the SIM (or calling app) for every call by
the country you dial; how to write a rule; what the mid-call chooser is for. That
sketch is written in Simmo's own repository once the shape here has been settled,
since the two flows should read as siblings.
