# Welcome flow

A sketch of the first-run screens, their order, and their wording. Copy here is
**proposed, not approved**: nothing in it is a string resource yet, and per
`AGENTS.md` the maintainer signs off on the English before any of it is built or
translated. Product facts it leans on are cited to `SPEC.md`; if the two ever
disagree, the spec wins and this file is what gets fixed.

Why this exists: a fresh install today lands on `PermissionsScreen` (`SPEC.md`
§4.2) — three permission rows and nothing that says what the app is for or how
it is meant to be used. The tile is the whole product, and it is invisible until
someone adds it. The flow below says the three things a new user needs, in the
order they need them, and *then* asks for permissions, so every row on that
screen already has a reason attached.

## Shape

- **Five cards, then the existing permissions screen when a permission is still
  missing.** Each card is one idea, one illustration, a title of at most four words
  and a body of at most two lines. At the default font and display size the layout
  is fixed and nothing scrolls; as the sizes grow the illustration gives way to the
  text first, and only where the text still cannot fit — a small handset at Android's
  largest font and display scale, on the two densest cards — does the body scroll
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

  This went back and forth within the week. `Skip` was withheld from card 1,
  then restored when the three controls sat across the bottom together — a
  middle control appearing from nowhere on card 2 was the worse cost — and
  withheld again once `Skip` left that row for the title bar, where its absence
  is an empty action slot rather than a gap between two buttons. The reasoning
  for withholding never changed; what changed is what it cost.

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
  what to do — and in the title row rather than at the foot below the controls,
  where it would sit under the manual exit and take full width beside it. The
  earlier plan was a low-emphasis `Tutorial` text button there (chosen over `Intro`
  and `Replay intro`); the word survives as the icon's accessible name, since an
  icon-only control is nameless to a screen reader otherwise.
- **Progress dots, no numbers, no "1 of 5".** Five dots say enough.
- **Each card offers the grant for the thing it just introduced** (maintainer,
  2026-09-05). Card 2 carries `Allow` for location, for the calendar and for
  notifications, card 3 `Allow` for Do Not Disturb access and the live ringer
  choice, card 4 the `Add tile` action. The button is the same tri-state row
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

## The cards

### 1 · What it is

> **Snoozemo**
>
> Silence your phone until you leave.
>
> One tap.

Illustration: the `Zzz` mark, large.

The first line is §1's one-sentence product with the meeting clause dropped — the
next card carries that. The second is `One tap.` alone: it used to read `One tap.
It turns itself back on.`, and the second sentence went (maintainer,
2026-09-05). Card 2 is *about* the snooze ending by itself and says so at length,
so promising it here spent the first card's two lines saying what the next one
exists for — and a first screen that front-loads is the one people skip.

The 8-hour cap is deliberately not stated on this card
(maintainer, 2026-09-05). It still fires, and `Ends automatically` covers it —
but a backstop the user never has to think about does not earn words in
onboarding, and naming it invited the reading that eight hours is the point
rather than the floor.

### 2 · How it ends

> **Ends automatically**
>
> ```
> 🌙  Snoozing                          3:40:12
>     Ends when you leave
>     [ End now ]   [ +30 min ]   [ Until 17:00 ]
> ```
>
> When you leave, when your meeting ends, or at the time you choose.
>
> 📍 Location        [ Allow ]
> 📅 Calendar        [ Allow ]
> 🔔 Notifications   [ Allow ]

Illustration: the ongoing notification as §4.3 draws it, in its fullest shape with
the meeting action showing — a render, not a live card, drawn inert (no ripple, set
in slightly like a picture) so nobody tries to tap `End now` on a snooze that is
not running. It is the picture because it is the one surface that shows every way a
snooze ends: `Ends when you leave` is the thesis (§4.4), `Until 17:00` is the
meeting, `End now` is the user's exit, and the countdown is the cap running down
(§7). The copy reads off it rather than describing it — and only the parts of it
that are endings: `+30 min` is in the picture because it is on the card, but it
extends a snooze rather than ending one, so no line promises it.

**The two body lines are the card's own division, and it is load-bearing** (Codex,
PR #198, twice on the same sentence). The title says the snooze ends *by itself*,
so the first line carries only what happens with nobody touching the phone —
departure and the cap. Everything else is a tap, and the meeting is one of them:
the calendar is never a trigger (§1, §4.3), it only seeds an `Until <time>` action
the user still has to press. Written as one list of endings, the line either
promised a time no fresh install can pick or an automatic ending the calendar
never delivers — two rewordings, the same shape. Split, each line is true on its
own terms and neither has to hedge. The verb governs all three items — a list
reading `End now`, the tile, `or when your meeting ends` puts a button, an object
and a time in one series, and the last of them stops looking like something you
press (Codex, again); `tap to end it` fixes that for the whole line at once.

**Merged from two cards** (maintainer, 2026-09-05: "i don't love the bottom sheet, i
was thinking show a render of a system notification"; "1 for sure" to merging). The
first sketch spent one card on the three exits, with an `Ask me each time` switch
for the end sheet's time row (§4.4) to make "at a time you choose" true on a fresh
install where the sheet is off by default, and a second card, *Adjust from the
notification*, on the notification's actions with the same render as its picture.
The two told one story with the same picture, and the switch put a setting most
users never need on the second screen they see.

**The chosen-time line went with the switch, and had to** (Codex, PR #198). The
merged card first kept it, on the reasoning that the notification offers a time
where the sheet does not — but it does not, in the case that matters: `+30 min`
extends the cap rather than moving an ending earlier, and `Until <time>` is absent
without calendar access and a meeting inside the cap (§4.3). A fresh install with
no meeting would have read a promise of a time to pick and found nothing to pick
it with, which is the failure the switch existed to prevent, moved rather than
fixed. So the line names the meeting instead, beside its own `Allow` — the one
chosen end time the product actually offers — and the sheet stays in Settings,
off by default, mentioned nowhere in the flow.

The three `Allow` buttons are the location, calendar and notification rows from
`PermissionsScreen`, so a granted row reads as those rows do — the action gone, and
the row's capability sentence (`Snoozes can end when you leave`, §5.2) in its place;
never a bare `Allowed`, which §5.2 retired because it answers "did the grant land?"
rather than "what can the app do now?". Location's `Allow` runs the whole §3.2
sequence from here — foreground prompt, disclosure dialog, background prompt — and a
user who stops partway gets the same `Snoozes can't end when you leave` state the
row shows in Settings. Each row is about something visible in the render: location
is `Ends when you leave`; the calendar is `Until 17:00`, which the row's own
sentence says appears when a meeting is on the calendar — absent, never disabled,
when there is no calendar access or no meeting worth offering (§4.3, "absent, never
disabled, and never a promise"), which is why the meeting sits in the line of taps
rather than among the endings the card claims outright; notifications are the
card itself — without the grant there is no notification to adjust from, and no
status bar icon either (§4.3), which is why that permission is requested rather
than merely declared. Three rows is the densest a card gets; they are the same
short tri-state rows, and this is the one card on which all three make sense
together.

The calendar is read only to offer that `Until <time>` action, seeded from the next
meeting's end, and the app never triggers itself from the calendar (§1, §4.3); both
the row's sentence and the card's second line are worded as a tap for that reason,
so a user who tapped `Allow` is not told the meeting competes with departure and
the timer by itself (Codex, PR #193, and again on PR #198). The footer's cap (§7) is what the
first body line says. It used to also carry the rule that a chosen time never switches
departure tracking off; that reversed on 2026-09-11 — a chosen time now replaces the other
exits (§4.4), and `Until I leave` is the way back. The tile tap is D6. The notification is the status surface and
the only place the countdown, the reason, and the way to extend or end all live
(§4.2, "nothing the user needs to know may live only on the tile"). The card does
not say what happens when notifications are denied; the permissions recap does.

### 3 · Your Do Not Disturb rule

> **One rule, yours**
>
> Snoozemo adds one Do Not Disturb rule and only ever switches that one on and
> off. Choose what still gets through in Settings › Filters.
>
> While snoozing, set the phone to
> ( Ring | **Vibrate** | Silent )
>
> [ Allow Do Not Disturb access ]
> Filters · What still gets through            [ Edit ]

`Filters` is offered here, not only named (maintainer, 2026-09-05), through the
same row `SettingsScreen` draws. It appears only once there is a rule to edit —
access granted and the rule created — and is simply absent before that, so it is
never a tap with nothing behind it. A card titled *One rule, yours* that gave the
user no way to open it was describing ownership rather than handing it over.

Illustration: none; the ringer choice and the grants are the interactive elements.
Do Not Disturb access is the one grant that is a Settings screen rather than a
dialog (§5.2): `Allow` leaves the app, the user flips the toggle, and on return the
row reads as it does on `PermissionsScreen` — the action gone, its capability
sentence in place. It is the grant without which nothing here can snooze at all,
so it is asked once the user has seen what it is for — after what the app is and
how a snooze ends, and **before** the tile that will do the arming (maintainer,
2026-09-08). It used to come after the tile, on the reasoning that the essential
grant should be last of them. The order is the other way round now because the
two halves fail asymmetrically when someone abandons the flow part way: a tile
added before the grant is a tile whose first tap fails with `NO_POLICY_ACCESS`,
while the grant taken before the tile leaves an app that already snoozes from
its own button, with `MainScreen`'s banner and the Settings row both still
offering the tile later.

One rule, named `Snoozemo`, created once and never churned (§5.3); the app turns off
*only its own rule* and leaves any other Do Not Disturb alone (§5.6). Filters is the
`SettingsScreen` row that deep-links to the system's own editor for the rule's
policy (§4.2), and the card offers it as well as naming it — the row's own null
check keeps it absent until the rule exists, which is what earlier made a button
here look impossible. The ringer choice is §5.9's ceiling, defaulting to `Vibrate`,
written to the same setting `SettingsScreen`'s *Ring/vibrate* row edits.

This card and card 2 are the densest of the five and the ones most likely to need
cutting. If it has to lose something, lose the Filters *sentence* in the body now
that the row itself is there — the row says the same thing and can be acted on —
and keep the ringer choice, which is the setting a user is most surprised by after
the fact.

### 4 · How to start one

> **Snooze from Quick Settings**
>
> Swipe down and tap the **Zzz** tile.
> Works with the phone locked.
>
> [ Add tile ]

Illustration: a Quick Settings panel with the tile highlighted; the tile in its
1×1 icon-only form, since that is the expected presentation (§4.2).

The tile is the arm affordance (§4.2) and the one path that is one tap with the
phone locked (§4.1), so this card leads with it and the app's own Snooze button is
not mentioned — it stays where it is as the fallback. `Add tile` is the same action
`MainScreen`'s banner and `SettingsScreen`'s row offer, and it disappears once the
tile is there, replaced by `Added`. A user who says no here is not asked again by
this card; the permanent row in Settings is the standing route (§4.2).

It follows the rule card rather than leading it (see card 3), which also leaves
the setup run ending on something to do rather than on something to allow.

### 5 · If something goes wrong

> **Help fix bugs**
>
> Send crash reports and anonymous usage stats so bugs get found and fixed?
> [ No thanks ]                                            [ Yes please ]

Illustration: none. One question and nothing else.

**Where the log and analytics settings live** (maintainer, 2026-09-05, "we need to
think about"). Both already have a home in `SettingsScreen` — the *Help make Snoozemo
better* switch and the *Debug log* switch with *Share debug logs* under it — and
neither moves. What this card decides is whether the flow *asks*:

- **Crash reports and analytics are a consent, and the consent is asked once**
  (§12): today by the invite card on `MainScreen`, in exactly this wording, with
  both answers recorded and the card retired once answered. This card is that same
  question, asked a screen earlier. Answered here, the main-screen invite never
  appears; passed with `Next`, it still does — an unanswered question is not a "no",
  and the main screen is where §12 says the question is put for an install that
  never opens Settings. Nothing is collected until the answer is yes, on this card
  as on that one.
- **The debug log is not mentioned at all** (maintainer, 2026-09-05: "we don't want
  to overwhelm"). This reverses the sketch's earlier reasoning, which was that the
  user should have heard of the log *before* a snooze misbehaves, since the moment a
  bug report is worth anything is the moment nobody can be told where the log is.
  That is still true and is still not worth a line here: the card's whole job is one
  question about data leaving the phone, and a second sentence about a log that
  never leaves it is the one most likely to blur the first. The log stays on by
  default (§4.6) with its switch and its share action in Settings, which is where a
  user goes when something has actually gone wrong.

**Answering leaves the flow** (maintainer, 2026-09-05). Either button records the
answer and exits — it is the last card, so answering it is finishing, and making
the user then find `Done` asks them to confirm a choice they just made. The exit
does not depend on the stored value changing: a user who said yes in an earlier
session and says it again here changes nothing for the write to reconcile, so a
value-gated exit would have left that tap looking dead for exactly the person
most likely to make it.

The consent is asked here, and this is the **last card** (maintainer, 2026-09-05).
Last on purpose: it is the one question on the cards about data leaving the phone,
and putting it after the run of `Allow` buttons rather than among them keeps it
from reading as one more of the same; the user has also seen by then what the app
does, which is what "help fix bugs" refers to.

### 6 · Permissions

The existing `PermissionsScreen`, unchanged: Do Not Disturb access, notifications,
location and the calendar row — permissions only. The tile is not on it and never
routes to it; a user who skipped card 4 has `MainScreen`'s banner and
`SettingsScreen`'s permanent tile row as the standing routes (§4.2). With every
grant already offered on its own card this screen is a recap, and its job is what is
*still* missing: each row
carries the consequence of saying no (`Snoozes can't end when you leave`, and so on)
beside the same `Allow`, and after cards 2–4 every one of those consequences refers
to something the user has just been shown. `Done` lands on `MainScreen`.

Shown **only when a permission is still missing** (maintainer, 2026-09-05, "only
when something is missing") — one this flavor offers, as the *Shape* rule says; a
user who allowed everything on the cards lands straight on the Snooze button. The screen's
own once-only routing (§4.2) stays as the backstop for an install that skipped the
flow.

## Not in the flow, on purpose

- **No Samsung step yet.** `SPEC.md` §10 wants a One UI–detected card explaining
  *Never sleeping apps*; it belongs after Phase 8's device verification, as a sixth
  card shown only on One UI, not as a paragraph every Pixel user has to skip.
- **No "how it decides you left".** The departure test (§6.6) is the app's business;
  the user's mental model is "when I leave", and a card explaining Wi-Fi and
  accuracy gates would make the product sound less reliable than it is.
- **No end sheet.** It is off by default (§4.4), its switch stays in Settings, and
  the chosen time card 2 promises is the notification's, which every install has; a
  card or a control for a sheet most users never see is one too many (maintainer,
  2026-09-05).

## Decided

- The shape and the copy above, as proposed (maintainer, 2026-09-05, "let's try
  what you said"). Still to be seen on a device before any of it is translated.
- **The shape is a product decision and is recorded as one in `SPEC.md` §4.2** — the
  cards before the permissions screen, a grant on each card, the recap only when a
  permission is missing, the replay icon, shown once. The spec is what an
  implementation is checked against; this file carries the wording and the reasoning
  behind each card, and the wording stays proposed until it has been seen on a
  device.
- The control that replays the flow is a (?) icon in `MainScreen`'s title row, beside
  the settings gear, with `Tutorial` as its accessible name.
- Card 3's ringer choice is a live control.
- Card 2's first body line is what happens by itself — departure and the cap — and
  its second is the taps, the meeting among them and named as a button: `+30 min`
  extends rather than ends, `Until <time>` needs a meeting, and the calendar is
  never a trigger (Codex, PR #198, three rounds on this one sentence). Written under autopilot, and the copy is proposed like the rest
  of this file.
- Cards 2 and 4 of the first sketch — the three exits, and the notification's
  actions — are one card, illustrated by a render of the ongoing notification, and
  the `Ask me each time` switch is not in the flow (maintainer, 2026-09-05, "1 for
  sure" over keeping a tile card in the slot).
- Each card offers the grant for the thing it introduced.
- The permissions screen at the end appears only when something is still missing.
- The crash-report and analytics consent is asked in the flow, as the last card,
  and it is the whole card: the debug log is not mentioned (maintainer,
  2026-09-05).
- **The affirmative answer is the trailing one, and the pair sits at opposite ends
  of the row** (maintainer, 2026-09-05): `No thanks` leading and low-emphasis,
  `Yes please` trailing and filled, with the width between them rather than an 8dp
  gap. A yes/no pair side by side is two taps a thumb can confuse, and the
  separation is what makes the affirmative one deliberate. `MainScreen`'s invite
  card already had the order right and the spacing wrong; both are now this, since
  it is the same question asked in two places.

## Open questions for the maintainer

- ~~The rule card's title.~~ **Decided (maintainer, 2026-09-05): `One rule, yours`.**
  The four-word cut from `One rule, yours to edit` had produced `One editable
  rule`, which lost the word the card is actually about. `One rule you
  configure` was considered and dropped as too long. *Yours* is also the half
  the body does not already say — the body's second sentence covers editing, so
  a title about editing only repeated it, where this one names what the first
  sentence is really promising: Snoozemo touches nothing else of yours.
- ~~Whether `Skip` is visible on card 1.~~ **Decided (maintainer, 2026-09-06):
  no `Skip` on card 1**, and `Skip` lives in the title row's action slot on
  every other card. Offering to leave beside the one line that says what the app
  is invites skipping before there is anything to skip; once the exit moved out
  of the bottom row, withholding it costs only an empty action slot rather than
  a gap between two buttons. D7 was untouched throughout: back has always exited
  card 1, so the way out existed either way; the question was only whether it
  was named.
- ~~Whether the flow replays after an update that adds a card.~~ **Decided
  (maintainer, 2026-09-05): never automatically.** Anyone who skipped or finished
  it is done with it, and only the (?) icon replays it. So the seen-flag stays a
  plain boolean with no version on it — what was already built, now settled
  rather than assumed.
- The accessibility overflow under *Shape* — the body scrolls with the buttons
  pinned, only at sizes where it cannot fit — is a carve-out from "not vertically
  scrollable" taken because no-scroll and no-truncation cannot both hold there. The
  alternative is splitting cards 2 and 4 into two at those sizes. Say if you would
  rather split.

## Simmo

The same shape — what it is, how it is used, then the permissions interstitial —
with Simmo's own three ideas: rules pick the SIM (or calling app) for every call by
the country you dial; how to write a rule; what the mid-call chooser is for. That
sketch is written in Simmo's own repository once the shape here has been settled,
since the two flows should read as siblings.
