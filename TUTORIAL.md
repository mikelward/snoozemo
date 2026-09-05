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

- **Six cards, then the existing permissions screen when a permission is still
  missing.** Each card is one idea, one illustration, a title of at most four words
  and a body of at most two lines. At the default font and display size the layout
  is fixed and nothing scrolls; as the sizes grow the illustration gives way to the
  text first, and only where the text still cannot fit — a small handset at Android's
  largest font and display scale, on the two densest cards — does the body scroll
  vertically, with `Next`, `Skip` and the progress dots pinned below it. The text is
  never truncated and no `Allow` is ever clipped off the bottom: with both "no
  scrolling" and "no truncation" held absolutely there is no valid overflow at those
  sizes (Codex, PR #193), and clipping a grant is the worse of the two. Verify at the
  largest display and font size Android offers before calling any card done.
- **`Next` on every card, `Skip` on every card, back gesture goes to the previous
  card.** `Skip` and the last card's `Next` both land in the same place:
  `PermissionsScreen` while a permission is still missing, `MainScreen` once nothing
  is — so there is no way through that misses a missing permission, none that shows
  a recap with nothing to recap, and no way to get stuck, which is the same fail-open
  rule the permissions rows follow (§4.2: "a setup flow that cannot be left without
  finishing it is a trap, not onboarding"). "Missing" means a permission *this
  flavor offers* and the recap would show an action for: on `direct`, where the
  location row is suppressed because nothing tracks departure (§3), an ungranted
  location permission counts for nothing, or every `direct` user would be routed to a
  recap they cannot satisfy.
- **Shown once.** A persisted flag records that the flow has been seen; the
  permissions screen's own once-only routing stays as it is (§4.2). Replayable from a
  `Tutorial` button (maintainer, 2026-09-05 — over `Intro` and `Replay intro`), so the
  cards are not lost once seen. It sits on `MainScreen` (maintainer, 2026-09-05), as a
  low-emphasis text button at the foot of the screen below the controls, rather than
  a `SettingsScreen` row — the person who needs it is on the home screen wondering
  what to do, not in Settings.
- **Progress dots, no numbers, no "1 of 6".** Six dots say enough.
- **Each card offers the grant for the thing it just introduced** (maintainer,
  2026-09-05). Card 2 carries `Allow` for location and for the calendar, card 3 the
  `Add tile` action, card 4 `Allow` for notifications, card 5 `Allow` for Do Not
  Disturb access and the live ringer choice. The button is the same tri-state row
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
> One tap. It turns itself back on.

Illustration: the `Zz` mark, large.

The first line is §1's one-sentence product with the meeting clause dropped — the
next card carries that. The second line is the promise the rest of the app keeps:
a snooze always ends (principle 1; the cap, §7).

On the `direct` flavor, until Phase 7 lands, the first line is just `Silence your
phone.`: that build is duration-only (§3), so a first card promising departure there
would set up exactly the silence-until-the-cap the app exists to prevent — and
promising a chosen time is no better while card 2's `Ask me each time` switch is off
by default (Codex, PR #193, twice). The second line does the promising, and the cap
is what makes it true. Card 2 drops its departure line the same way.

### 2 · How it ends

> **It ends by itself**
>
> 📍 When you leave                       [ Allow ]
> ⏰ At a time you choose      Ask me each time [ ○ ]
>
> Whichever comes first — never more than 8 hours.
>
> 📅 In a meeting? One tap on the notification
> ends it when the meeting does.            [ Allow ]

Illustration: none; the lines *are* the picture. The two `Allow` buttons are the
location and calendar rows from `PermissionsScreen`, so a granted row reads as those
rows do — the action gone, and the row's capability sentence (`Snoozes can end when
you leave`, §5.2) in its place; never a bare `Allowed`, which §5.2 retired because it
answers "did the grant land?" rather than "what can the app do now?". Location's
`Allow` runs the whole §3.2 sequence from here
— foreground prompt, disclosure dialog, background prompt — and a user who stops
partway gets the same `Snoozes can't end when you leave` state the row shows in
Settings. The second line's control is not a grant but the `Ask when to unsnooze`
switch — the same setting `SettingsScreen` edits, like card 5's ringer choice.

The two exits the app takes on its own, then the one the user takes. "When you
leave" is the thesis (§4.4). "At a time you choose" is the end sheet's time row
(§4.4) — and the sheet is **off by default**, so a fresh install arms with no time
picker at all and `+30 min` only ever extends. Promising a chosen time to a user who
has no presented way to choose one would leave them silent to the cap expecting an
earlier end, worst on `direct` where it is the only line left (Codex, PR #193). So
the line carries the switch that makes it true; left off, the footer is the honest
reading of the line — the cap. The footer states the cap (§7) and the rule that a
chosen time never switches departure tracking off (§4.4, "the helper line is not
decoration").
The meeting sits *below* the footer and is worded as a tap because that is what it
is: the calendar is read only to offer an `Until <time>` action on the ongoing
notification, seeded from the next meeting's end, and the app never triggers itself
from the calendar (§1, §4.3). Listing it beside the two automatic exits would tell
a user who tapped `Allow` that the meeting competes with departure and the timer by
itself, and leave them silenced after a meeting they never tapped for (Codex,
PR #193).

On the `direct` flavor, until Phase 7 lands, the first line is not true: the build is
duration-only (§3). Drop that line there rather than promise it, and shorten the
footer to `Never more than 8 hours.`; the card still reads.

### 3 · How to start one

> **Snooze from the shade**
>
> Swipe down and tap the **Zz** tile.
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

### 4 · While it runs

> **Adjust from the notification**
>
> **End now** or **+30 min** — and in a meeting, **until it ends**.
> Tap the tile again to end it.
>
> [ Allow notifications ]

Illustration: the ongoing card as §4.3 draws it with the meeting action showing —
its fullest shape, which is why the copy calls that action conditional: it is absent,
never disabled, when there is no calendar access or no meeting worth offering (§4.3,
"absent, never disabled, and never a promise"), so the card must not teach a control
the notification will often not have. The button
is the `POST_NOTIFICATIONS` row; without the grant there is no card to change
anything from, and no status bar icon either (§4.3), which is why this permission is
requested rather than merely declared.

The notification is the status surface and the only place the countdown, the
reason, and the way to extend or end all live (§4.2, "nothing the user needs to
know may live only on the tile"). The tile tap is D6. The card does not mention
what happens when the notification is denied; the permissions recap says that.

### 5 · Your Do Not Disturb rule

> **One editable rule**
>
> Snoozemo adds one Do Not Disturb rule and only ever switches that one on and
> off. Choose what still gets through in Settings › Filters.
>
> While snoozing, set the phone to
> ( Ring | **Vibrate** | Silent )
>
> [ Allow Do Not Disturb access ]

Illustration: none; the ringer choice and the grant are the interactive elements.
Do Not Disturb access is the one grant that is a Settings screen rather than a
dialog (§5.2): `Allow` leaves the app, the user flips the toggle, and on return the
row reads as it does on `PermissionsScreen` — the action gone, its capability
sentence in place. It is the grant without which nothing here can snooze at all,
so it comes last, after the user has seen everything it is for.

One rule, named `Snoozemo`, created once and never churned (§5.3); the app turns off
*only its own rule* and leaves any other Do Not Disturb alone (§5.6). Filters is the
`SettingsScreen` row that deep-links to the system's own editor for the rule's
policy (§4.2); it cannot open from this card because Do Not Disturb access has not
been granted yet, so the card names where it lives instead of offering a button
that opens to nothing. The ringer choice is §5.9's ceiling, defaulting to `Vibrate`,
written to the same setting `SettingsScreen`'s *Ring/vibrate* row edits.

This card is the densest of the five and the one most likely to need cutting. If it
has to lose something, lose the Filters sentence — the row exists in Settings and
the rule is discoverable in the system's Modes screen either way — and keep the
ringer choice, which is the setting a user is most surprised by after the fact.

### 6 · If something goes wrong

> **Help fix bugs**
>
> Send crash reports and anonymous usage stats so bugs get found and fixed?
> [ Yes please ]  [ No thanks ]
>
> A debug log stays on your phone either way. Share it from Settings if a
> snooze misbehaves.

Illustration: none.

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
  as on that one. `direct` ships neither SDK (§12), so on `direct` the question is
  absent and the card is the debug-log sentence alone.
- **The debug log needs no consent and gets no control here**: it is on by default
  because it never leaves the phone (§4.6), and its switch stays in Settings. The
  sentence exists so the user has heard of it *before* a snooze misbehaves — the
  moment a bug report is worth anything is the moment nobody can be told where the
  log is.

The consent is asked here, and this is the **last card** (maintainer, 2026-09-05).
Last on purpose: it is the one question on the cards about data leaving the phone,
and putting it after the run of `Allow` buttons rather than among them keeps it
from reading as one more of the same; the user has also seen by then what the app
does, which is what "help fix bugs" refers to.

### 7 · Permissions

The existing `PermissionsScreen`, unchanged: Do Not Disturb access, notifications,
location and the calendar row — permissions only. The tile is not on it and never
routes to it; a user who skipped card 3 has `MainScreen`'s banner and
`SettingsScreen`'s permanent tile row as the standing routes (§4.2). With every
grant already offered on its own card this screen is a recap, and its job is what is
*still* missing: each row
carries the consequence of saying no (`Snoozes can't end when you leave`, and so on)
beside the same `Allow`, and after cards 2–5 every one of those consequences refers
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
- **No end sheet.** It is off by default (§4.4) and card 2 already covers what it
  offers; a card for a sheet most users never see is a card too many.

## Decided

- The shape and the copy above, as proposed (maintainer, 2026-09-05, "let's try
  what you said"). Still to be seen on a device before any of it is translated.
- **The shape is a product decision and is recorded as one in `SPEC.md` §4.2** — the
  cards before the permissions screen, a grant on each card, the recap only when a
  permission is missing, the replay button, shown once. The spec is what an
  implementation is checked against; this file carries the wording and the reasoning
  behind each card, and the wording stays proposed until it has been seen on a
  device.
- The control that replays the flow is a `Tutorial` button on `MainScreen`.
- Card 5's ringer choice is a live control.
- Each card offers the grant for the thing it introduced.
- The permissions screen at the end appears only when something is still missing.
- The crash-report and analytics consent is asked in the flow, as the last card;
  the debug log gets a sentence on it and no control.

## Open questions for the maintainer

- Cards 4 and 5 were retitled to fit the four-word limit (Codex, PR #193): `Change
  it from the notification` became `Adjust from the notification`, and `One rule,
  yours to edit` became `One editable rule`. Both lose a little — the first no
  longer says *end*, the second no longer says *yours*. `Adjust or end it` and `One
  rule, yours` are the runners-up.
- Whether `Skip` should be visible on card 1, or only from card 2 on — a `Skip` on
  the very first screen invites skipping the whole thing before knowing what it is.
- Whether the flow replays after an app update that adds a card (a version on the
  seen-flag), or only from the `Tutorial` button.
- Card 2's `Ask me each time` switch is an autopilot guess (Codex, PR #193): the
  sheet is off by default (§4.4), so the "at a time you choose" line needed either
  the control or a retreat to describing the cap. The switch keeps the line you
  asked for and follows card 5's pattern of a live setting on the card. The
  alternatives are turning the sheet on by default, or wording the line as the cap
  (`After 8 hours at most`) and leaving the setting to Settings.
- The accessibility overflow under *Shape* — the body scrolls with the buttons
  pinned, only at sizes where it cannot fit — is a carve-out from "not vertically
  scrollable" taken because no-scroll and no-truncation cannot both hold there. The
  alternative is splitting cards 5 and 6 into two at those sizes. Say if you would
  rather split.

## Simmo

The same shape — what it is, how it is used, then the permissions interstitial —
with Simmo's own three ideas: rules pick the SIM (or calling app) for every call by
the country you dial; how to write a rule; what the mid-call chooser is for. That
sketch is written in Simmo's own repository once the shape here has been settled,
since the two flows should read as siblings.
