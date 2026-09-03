# Monetization

**Status: exploration, premature, and nothing here is being locked in**
(maintainer, 2026-09-03). It is written as options and their costs, not as a set
of decisions waiting for a signature. `SPEC.md` records what Snoozemo does and
why; `TODO.md` records the plan. This page records the money argument — but its
first conclusion is that nothing here should be built yet, and the reason is
worth stating before anything else.

## Two prerequisites — and a recommendation — come before a price

**The heading said "three gates" until the sixth correction of that phrase**
(Codex, 2026-09-03). Items 1 and 2 are prerequisites: an unverified departure
detector has nothing to sell, and a refused declaration decides which flavor is
priced. Item 3, discovery, is a **recommendation** — it blocks charging from
being *measurable*, not from being right — and it does not gate anything unless
the maintainer adopts it as a gate.

1. **Presence has never run on a handset — and each flavor needs its own run**
   (Codex, 2026-09-03). Snoozemo's whole promise is *"until you leave"*, and the
   two flavors detect departure by different means: `play` by geofence,
   `direct` by `ForegroundPresenceMonitor` (Phase 7). Phase 3's device
   verification and hardware item 2 exercise the `play` path, so **they do not
   clear this gate for `direct`** — which matters precisely in the case that
   sends you there, a refused declaration. `TODO.md` records that.
   Continuing: Charging for a departure detector that has never detected a real
   departure — no emulator has the hardware, and `TODO.md` Phase 3 still owes the
   device verification — is selling a claim nobody has checked. The app's own
   first principle is *never leave the phone silently quiet*; a paid app that
   fails it is a refund, a one-star review, and the end of the listing.
2. **The Play background-location declaration is unresolved.** `SPEC.md` §3.2 is
   blunt about it: `ACCESS_BACKGROUND_LOCATION` is a restricted permission, the
   Permissions Declaration Form gates *every* track including internal, and an
   internal-only track buys no exemption. If that declaration is refused, the
   `play` flavor cannot ship at all — and with it every Play Billing option, which
   is what the rest of this page prices. It is the riskiest open item in the
   project. **It does not end the discussion, though**: `direct` stays shippable
   by design, so a refusal turns the question into "is the sideload build sold, and
   how" (see `direct` below) rather than "there is nothing to sell". Scope this
   gate to `play` and Play Billing, not to pricing as such.
3. **Nobody has discovered it.** True of every app in this family, and honestly
   the binding constraint. See "Marketing" — the cheap work is there, not here.

None of that argues against monetizing eventually. It argues for an ordering:
*ship it, prove it on hardware, get it found, then price it.* Two of those are
gates on the product being real at all — an unverified departure detector has
nothing to sell, and a refused declaration decides which flavor is being priced.
Discovery is the one that is genuinely a judgment call rather than a
prerequisite: pricing before anyone has found the app is not *wrong*, it just has
nothing to price against, and a maintainer who wants the billing plumbing built
early has a reasonable case. This page recommends the ordering; it does not claim
to settle it.

---

## What is never for sale

- **Departure detection.** This is the one that matters, and it disqualifies the
  most obvious tiering idea. See the next section.
- **The duration cap.** It is the backstop that keeps the phone from staying
  silent forever (`SPEC.md` D7, principle 1). A safety mechanism behind a paywall
  is not a business model, it is a liability.
- **Ending a snooze.** Every exit an armed snooze *has* stays free, always, under
  every entitlement state — including a failed or unreachable one. An entitlement
  check that cannot resolve must never hold a phone quiet; see "Price" for the
  fail-open policy that keeps that true.

  **This is about executing an exit, not about which exits exist** (Codex,
  2026-09-03), and the distinction is load-bearing because the tier below sells
  one: "Until I get home" is a paid *end condition*. Selling an additional way to
  end a snooze is not paywalling the ending of one. So the floor is: whatever
  conditions a snooze was armed with, **every one of them fires regardless of
  entitlement state**, and the duration cap fires regardless of all of them. An
  unresolvable entitlement can never remove an exit from a snooze already armed —
  which is the failure principle 1 actually cares about.
- **A feature someone already has, once there are users to take it from.**
  Narrower than "anything already shipped": *revoking* working behavior is what
  earns review bombs, while *gating a built feature for new installs* is ordinary
  and is not ruled out (maintainer, 2026-09-03). **That phrasing assumes existing
  users are grandfathered, and nothing here adopts that** (Codex, 2026-09-03) —
  see the tier section, where retention is a stated preference and grandfathering
  is explicitly not a policy. So this bullet describes the shape a gate takes
  *if* retention is chosen; it is not a second, quieter guarantee of it. Snoozemo is unreleased, so today the distinction costs nothing —
  which is the point of the timing argument below.

## Why "pay for geofencing" is the wrong split

The tempting tier is *free = timer, paid = leave-detection*. It does not survive
contact with the product.

**The free tier would be a worse version of something the OS already does for
free.** `SPEC.md`'s non-goals say it outright: scheduled DND is the case Android
handles well, and Snoozemo is the *ad-hoc, place-scoped* case it handles badly.
Strip out the place-scoped half and what is left is a DND timer competing with a
built-in — which nobody installs, so nobody ever sees the upsell.

And the one-sentence product is *"Silence your phone until you leave or your
meeting ends."* Sell the "until you leave" and the free app cannot say its own
tagline.

**And it cannot be drawn along the flavor line either.** `direct` is duration-only
*today*, but that is an implementation state, not the design: `SPEC.md` §3.4
settles two flavors "differing only below `PresenceMonitor`", and `TODO.md` Phase 7
adds `ForegroundPresenceMonitor` + `SnoozeService` so `direct` detects departure
too — by foreground service (option A) rather than geofence (option B). Since
pricing is deferred until after the gates above, and Phase 7 is one of the things
that may land in between, a tier built on "only `play` has presence" would be
pricing a temporary gap. **Both flavors detect departure; they differ in
mechanism, battery profile and Play exposure** — which is a difference in *how*,
not in whether there is a product.

---

## `direct` should be free — as a decision, not because it has to be

**Play Billing is out for `direct`, but not for the reason it first appears.**
The Billing library talks to the installed Play Store app over IPC rather than
opening its own sockets, so "no `INTERNET`" is not what rules it out. What rules
it out is distribution: `direct` is a sideload build, and Play Billing is for
apps Play distributed. Keeping `direct` networkless costs nothing here.

**And that leaves other ways to charge, which are choices rather than
impossibilities:** an offline license key or license file, or simply selling the
sideload build outright somewhere else. None needs `INTERNET`, and none touches
the reason the flavor exists.

**The recommendation is still that `direct` is free forever** — but as a product
decision with a reason, which is a different thing from a constraint. `SPEC.md`
§3.2 and goal 4 make `direct` the fallback if the Play background-location
declaration is refused: a fully-functional sideload build needing no restricted
permissions. That makes it insurance, and insurance with a price on it is not
insurance — the users who would reach for it are the ones Play has already
failed, and charging them for the workaround is the wrong moment to introduce a
transaction. It also has no billing surface to maintain, which for a build that
may never be needed is most of its value.

Two things worth writing down rather than leaving to be rediscovered:

- The alternative reading — **retire `direct` so the billing story is uniform** —
  would throw away the contingency that exists precisely because Play might say
  no. Don't take it as a side effect of a pricing decision.
- **If `direct` ever is priced, two mechanisms stay open** and this page does not
  pick between them (Codex, 2026-09-03) — the open decision below leaves both
  live, so naming one here would have closed it by accident:
  - **Sell the APK outright.** Simplest: no in-app entitlement at all, no license
    check, no *key* to lose or reissue. The build the buyer downloaded *is* the
    entitlement — but the file itself can still be lost, and then the channel
    decides (Codex, 2026-09-03): a storefront offers a redownload, a payment link
    and a manual send do not. **Updates take the same qualifier** (Codex,
    2026-09-03): a storefront usually ships its own update service, so it is only
    the payment-link route that owes an update channel of its own — an earlier
    draft said the option had none unless one was built, which made the mechanism
    look intrinsically worse to maintain than the channel makes it. Its cost is
    that distribution choice — **and refunds are one-way** (Codex, 2026-09-03): with no license check there is nothing to revoke, so a refunded
    buyer keeps a working app. That is the same enforceability gap listed under
    the license option below, not an advantage this option has over it.
  - **An offline license key or file**, which allows a free download and a paid
    unlock, matching the `play` flavor's shape. Its cost is a support surface:
    lost keys, reinstalls, device changes — and **refunds are one-way here too**
    (Codex, 2026-09-03). An earlier draft said "no refund path", which is wrong
    in the same way the paid-APK bullet above was: the seller can always return
    the money. What a networkless app cannot do is *revoke* the key or file it
    already issued, so the buyer keeps working software after the refund. The two
    mechanisms are level on this, which is why it is now stated under both.
  Either way the cost is support and distribution rather than a technical veto,
  and either way it is a real decision — which is the argument for `direct`
  staying free above.

---

## What could actually be sold

`SPEC.md` §14 already lists the deferred features, and it is a better paid-tier
shortlist than anything invented from scratch: each is genuinely additional, none
is load-bearing for the core promise, and the free app is complete without them.

1. **Saved places.** Name an anchor ("Cinema", "Work"), give it its own policy and
   duration cap; the tile long-press becomes a picker. The `Anchor` type is
   already shaped for it. **The strongest candidate** — it is the feature a happy
   user asks for after a month, which is exactly when they will pay.
2. **Auto-arm on arrival.** The sequel, and §14 notes the *permission* is free in
   the `play` flavor because the background-location declaration is already paid
   for — §14 now says the battery is not, which is this bullet's point.
   High perceived value ("it just knows"), no recurring **developer** cost, and
   completely absent from the free experience rather than removed from it.
   **But it is not free to the user, and "nearly free" was only ever about the
   permission** (Codex, 2026-09-03). Auto-arm needs saved-place geofences
   registered *while no snooze is active* — which is work v1 deliberately does not
   do at all, since today every wakeup happens inside an armed snooze and §6.7's
   duty cycle drives it to zero when the phone is still. So this candidate adds a
   standing background-location cost against §9's battery budget, and its duty
   cycle is **unmeasured**: measure it on hardware before treating it as a
   low-cost tier item. `TODO.md` now carries the shape of that measurement *and*
   its pass criterion, set before the run rather than after it (Codex,
   2026-09-03) — a threshold chosen once the number is known would clear any
   result and is no gate at all. It is also the one candidate whose cost lands on people who
   have *not* bought it, if the geofences are registered before the unlock is
   checked — so the registration has to be gated on the entitlement, not on the
   saved place existing.
3. **The calendar end-time offer** — the "meeting support" idea. Worth being
   careful here: `SPEC.md` §4.3 already reads the calendar to offer an end time on
   the ongoing notification, so it is a *shipped* feature rather than a §14
   candidate, and gating it later would be the revoke this page argues against —
   which is a strong preference, **not a guarantee this page can make** (Codex,
   2026-09-03). An earlier version said flatly that if it ships free it stays
   free, settling for this one candidate exactly what the retention bullet below
   says is proposed and not adopted, and what `TODO.md` records as decided case
   by case. If it does become a guarantee it goes into `SPEC.md` then, like any
   other. What is *sellable without touching that question at all* is what §14
   defers: **chaining back-to-back meetings**, which the spec deliberately stops
   short of today.
4. **`ZenDeviceEffects`** — grayscale, dimmed wallpaper, night mode while snoozed
   (§5.5). Pure delight, zero risk, and it makes the paid tier *visible* every time
   a snooze is armed, which a background feature never is.
5. **"Until I get home"** and other saved-place reverse geofences, which follow
   from saved places anyway — **`play`-only, and that matters here.** `SPEC.md`
   §4.4 marks this exit deferred and `play`-flavor only because it needs
   background location on top of saved places (§14), and `direct` intentionally
   declares no restricted permissions. So if `direct` ever carries a paid tier,
   this candidate is not in it, and the tier is not identical across the two
   flavors (Codex, 2026-09-03). Candidates 1, 3 and 4 are flavor-neutral;
   candidate 2 (auto-arm on arrival) needs the same background location and is
   `play`-only for the same reason. Any `direct` tier is therefore **saved places,
   meeting chaining and `ZenDeviceEffects`** — thinner, and a thing to know before
   pricing the two flavors the same.

That is a coherent tier with a name: **places and automation.** The free app is
*one tap, here, now*; the paid app remembers your places — and on `play`, arms
itself. The "arms itself" half is the `play`-only part, per the split above, so
copy that promises automatic arming is `play` copy; a `direct` tier's honest
promise stops at remembering places (Codex, 2026-09-03).

**Not on the list:** anything that changes what happens when a sensor fails. Every
degraded path and every fallback in the §3.6 ladder is identical at both tiers, and
**every exit a snooze was armed with fires identically** — the paid tier can add an
end condition (candidate 5) but never weaken the handling of one, per the floor
above.

---

## Price

**One-time, ~$3–5, as an in-app unlock.**

The instinct that "a few people would stretch to $10 if it's good" is probably
true of the people who find it and love it — and that is the problem: at $10 the
price is doing the filtering, when discovery is already filtering hard enough. A
$3–5 unlock converts more of a small audience than $10 converts of the same
audience, and the goal at this stage is *learning what converts*, which needs
conversions to learn from.

Reasons this is one-time rather than recurring:

- **No recurring developer cost.** Nothing here bills a per-user API. There would
  be nothing to fund.
- **The core is set-and-forget.** A user arms the tile and never opens the app,
  which is the app working. That profile churns hard on a subscription.
- **The category expectation** for a small utility is a one-time unlock.

**In-app unlock, not a paid listing.** Nobody buys a DND app they have not felt
work — the moment that sells this is the first time the phone comes back to life
on its own as the user walks out. That has to happen for free, first.

Play's cut on a one-time in-app purchase is 15% under the first $1M/year, so
~$2.55–4.25 net — **but that rate is a program the developer account has to be
enrolled in**, not an automatic default. Unenrolled, the standard 30% applies and
the same $3–5 nets ~$2.10–3.50. Confirm enrollment in Play Console before
budgeting on either figure; it is a prerequisite of the price, not a detail after
it, and it applies to the sibling apps' pages too.

**The entitlement has to fail open, and this app has less room than most to get
it wrong.** Billing cannot always resolve — the device is offline, the Play Store
is unavailable, the service errors — and the two obvious defaults are both wrong:
locking the paid surfaces strips a paying user's saved places mid-trip, while
trusting a cached purchase forever mishandles a refund. **The shape below is a
proposal, not an adopted design** — it is what the siblings settled on, offered so
the tier is not priced without an answer. If the tier is decided, the policy is an
architecture and persistence decision and goes in `SPEC.md` at that point, per this
repo's own rule about where such decisions live; nothing here belongs in `SPEC.md`
while the tier itself is exploration.

- **Cache the entitlement durably and treat "cannot determine" as "use the last
  known value", never as a downgrade** (ClothesCast's `docs/ROADMAP.md` states it
  in those words; simmo's Phase 10 requires a cached entitlement that works offline
  and through a Play outage).
- **The cold-cache case needs its own answer, because there is no last known
  value** (Codex, 2026-09-03): a purchaser who reinstalls, or sets up a second
  device, while Billing is unreachable has an empty cache and no way to resolve
  it. "Briefly missing" is wrong there — it lasts until Billing recovers, which
  could be the whole session. So the initial unknown state is **its own state, not
  a downgrade**: show the paid surfaces as *unavailable pending a check* rather
  than absent or locked, keep retrying in the background, and never present it as
  "you don't own this" — a purchaser told they don't own what they bought is a
  refund. The one thing that must not happen is the reverse error: granting the
  tier to an unknown cache would make every fresh install paid until Billing
  answered.
- **For a one-time unlock, ownership is permanent and there is nothing to expire.**
  The only downgrade is a **positively confirmed refund or revocation** — no grace
  window, no expiry path, because inventing one would be a way to take the unlock
  off a legitimate buyer for no reason. (Grace and expiry belong to a recurring
  SKU, which is not what this page recommends; simmo's Phase 10 needs them because
  it prices a subscription.)
- **Re-check on a schedule, never on a path the user is waiting on.**
- **Nothing on the tile-tap path, the exit paths, or the duration cap makes a
  *blocking* Billing call or an execution-time entitlement check** — so **no
  billing failure can leave a phone quiet.** That is the floor, and it holds
  absolutely. It is deliberately narrower than "nothing consults the entitlement"
  (Codex, 2026-09-03): a tier built on saved places or "until I get home" is
  unenforceable unless *choosing* one reads the entitlement, so the pre-warmed
  cached value may gate **which end conditions are offered**. That is a screen the
  user is looking at, so a paid condition can be shown as paid there.

  **It may never gate the arm itself, and "the next arm is refused" was wrong**
  (Codex, 2026-09-03). `SPEC.md` §4.1: *"Arming must never feel slow or refuse."*
  It is goal 1 — one tap from the shade, phone possibly locked, zero prior
  configuration — and an entitlement is not a reason to break it. So a lapsed or
  revoked entitlement **narrows the menu, never blocks the tap**: the free
  current-location snooze is always there to arm, and a paid condition that is no
  longer owned falls back to it rather than refusing. The failure a paywall may
  cause is *"you get the free snooze instead"*, said in the notification — never
  *"no snooze"*.

  **And it may never gate execution.** Once a snooze is armed, every recorded exit
  and the duration cap fire without asking Billing anything, whatever the cache
  says by then. An entitlement that lapses mid-snooze ends nothing and blocks
  nothing.
- **What a billing failure *can* cost, by cache state** — and an earlier draft
  got this wrong in both directions, first understating it and then
  overstating it (Codex, 2026-09-03):
  - **Warm cache, entitled:** **nothing is lost.** The last known value says
    entitled, so the paid surfaces stay available right through the outage. That
    is the whole point of the durable cache, and the summary must not say
    "unavailable" here.
  - **Warm cache, not entitled:** unchanged — they were not available anyway.
  - **Cold cache** (a reinstall or a second device while Billing is unreachable):
    there is no last known value, so the paid surfaces read "checking" — possibly
    for the **whole session, and in principle until Billing recovers.**

  So the checking state is reserved for a cold cache, never used to hide a
  feature from someone whose cache says they own it.

**Revisit upward, never downward.** Raising a price is routine; cutting one tells
every early buyer they overpaid.

---

## Migrating existing free users

**There is nothing to migrate yet, and that is an asset — but it expires per
feature, not at first release.** Snoozemo is unreleased (`TODO.md` Phase 6 is the
internal-track release), and a paid tier can exist from the first public build
with no grandfathering question. **The expiry is not that build** (Codex,
2026-09-03; an earlier version of this paragraph implied it was, which is the
deadline the section below spends three paragraphs killing): every tier candidate
is `SPEC.md` §14-deferred and absent from the app, so releasing creates nobody who
has them. What spends the asset is **shipping a candidate free**, once, per
feature — and that is the only clock here.

That is an argument for *deciding* the tier early, not for shipping it early —
**the two prerequisites above still stand, and the discovery recommendation with
them**, and it is worth being precise about which this argument is and isn't in
tension with (Codex, 2026-09-03; "all three gates" here was the fifth copy of a
phrase the sections below retract):

- **The two prerequisites block shipping a price**, and they are upstream of
  everything here: an unverified departure detector has nothing to sell, and a
  refused declaration decides *which flavor* is even being priced.
- **Discovery — the recommendation, not a third gate — bears on charging, not
  deciding.** Nothing about settling
  the tier's shape competes with the marketing work — they use different hours
  and neither waits on the other. The ordering this page *recommends* is *ship,
  prove, get found, then price* — recommends, not settles, per the section
  above; a decision recorded now costs nothing and
  changes nothing about that sequence.

**The "decide before release" deadline this section used to claim does not
actually hold, and the correction is a better rule** (Codex, 2026-09-03). Every
candidate in the tier above — saved places, auto-arm, meeting chaining,
`ZenDeviceEffects`, reverse geofences — is deferred in `SPEC.md` §14 and **absent
from the app today.** So releasing the current product creates nobody who has
them, and they can debut as paid features a year after release without taking
anything from anyone. There is no one-time window closing at the first release,
and pretending there is added urgency the facts don't support.

**What is actually true is narrower and more useful:** grandfathering only
becomes necessary if a tier candidate **ships free first.** So the rule is not a
date, it is a discipline — *don't ship a candidate free and then gate it.* Which
means the decision worth making early is only "is this feature going to be paid?",
asked **once per feature, before that feature ships**, not a deadline hanging over
the whole tier.

The condition is **a candidate shipping free before it is gated**, not release
itself (Codex, 2026-09-03): if the tier decision waits but every candidate stays
absent, nothing was ever given away and no grandfathering machinery is needed. It
is only when something ships free first that the question arises — and then the
mechanism is weaker than it looks and should not be promised as permanent:

- **A reinstall loses it, and that much is certain.** Grandfathering on
  `PackageManager.getPackageInfo().firstInstallTime` predating the cutoff works
  until the user reinstalls — at which point the timestamp resets *and* the marker
  is gone, and a genuine early user is reclassified as new. No mechanism recovers
  that.
- **A phone swap is a different case, and it is open rather than lost.**
  `allowBackup="false"` is a decision about *cloud* backup; device-to-device
  transfer is governed separately by `data_extraction_rules.xml`, and `SPEC.md`
  §12 explicitly corrects the premise that today's config loses settings by design
  — user settings stay transferable and the migration question is undecided. So
  whether a marker survives a swap **depends on where it is stored and what those
  rules say when it ships** — and the question now has both a principle and a
  deadline (maintainer, 2026-09-03). The principle: **we don't hold a user's data
  captive**, the neighbor of the quality bar's "never lose the user's settings" —
  never withhold them either, and never behind a payment. The deadline: the
  platform requires backup and restore from **2027**, so `allowBackup="false"` is
  a setting with an expiry rather than a stable premise.
  It is worth deciding *with* the migration
  question rather than assuming the pessimistic answer. Don't design around a loss
  that hasn't been chosen.
- **So don't promise permanence, and don't build a mechanism either** (maintainer,
  2026-09-03). A claim durable against *every* path needs an identity recoverable
  outside app-private data — an account, or a Play purchase a free user by
  definition doesn't have — which is far larger than the tier it would serve. And
  at this app's user count the alternative is not a Settings row that grants the
  unlock to anyone who asks; it is **looking at the case and deciding.** An
  earlier draft specified that row as policy, which is machinery for a problem
  that is currently a conversation.
- **Which is the strongest argument for asking the question early, per feature —
  not for a release deadline** (Codex, 2026-09-03). An earlier draft turned this
  into "introduce the tier at the first public build", which the timing section
  above retracts and which this bullet then quietly reinstated: every candidate is
  `SPEC.md` §14-deferred and absent, so the first public build creates nobody to
  grandfather whether or not it carries a tier, and a deadline there would push
  billing work ahead of the two prerequisites and the discovery recommendation
  alike. What is actually true is narrower —
  **decide "is this one paid?" before the feature ships free**, once, per feature.
- **Gating new installs is fine; revoking is the move to avoid.** A feature that
  stops working for someone who had it is the review-bomb move; a built feature
  that new installs pay for is not. **Stated as a strong preference, not a rule**
  (Codex, 2026-09-03) — an earlier "never revoke" was an absolute, and retaining a
  feature for whoever already had it *is* grandfathering, so the flat version
  contradicted the paragraph directly below it and `TODO.md`'s record that this is
  decided case by case. Nothing here guarantees retention.

  **Whether existing users are grandfathered is proposed here, not adopted**
  (Codex, 2026-09-03). An earlier version of this bullet promised it flatly, two
  paragraphs after saying not to build a mechanism for it and against `TODO.md`
  recording it as decided case by case — so an implementer could not tell whether
  it was a product guarantee or an option. It is an option, and this page adopts
  nothing: at about two users the answer is judgment on the evidence, and if it
  ever becomes a guarantee it goes into `SPEC.md` at that point, with whatever
  mechanism can actually keep it.

---

## Marketing

### The pitch is already written, and it is unusually good

> **Silence your phone until you leave.**

That is a complete product in six words, it names a problem everyone has had, and
no built-in does it. Most apps in this family need their pitch invented; this one
needs it *distributed*.

### Sell the moments, not the mechanism

Nobody searches for "place-scoped do not disturb". They remember the moment their
phone went off in a meeting. The listing, the screenshots and every post should
lead with the situations: **a meeting, a cinema, a library, a lecture, a
bedtime.** Each one is a search term and a story; "geofence-based zen rule
automation" is neither.

### Listing

Play's title field allows 30 characters and is weighted for search. "Snoozemo"
alone is a coined word nobody types. `Snoozemo — DND until you leave` (30) spends
the rest on the terms people actually search: *do not disturb*, *silence phone*,
*focus*, *DND until I leave*. **Not *auto DND*** (Codex, 2026-09-03): auto-arm is
deferred and hardware-gated (`SPEC.md` §14), and today the product needs an
explicit tile tap, so that term would advertise behavior the release cannot
perform. It becomes available if auto-arm ships.

The Quick Settings tile is a genuine discovery asset once installed, and worth a
screenshot of its own — "one tap from the shade, phone locked" is a concrete
promise and it photographs well.

### Where the audience is

Digital-wellbeing and focus communities, Android automation forums (Tasker and
Home Assistant users will immediately understand what this replaces and how much
setup it saves), and — the underrated one — **people who ask "how do I make my
phone shut up in meetings"**, which is a recurring question in every Android
support forum with no good answer today. Answering it honestly, in the thread, is
better distribution than any listing edit.

---

## What this shares with the sibling apps

- **`direct` is why this app cannot follow a fleet-wide billing plan
  uniformly**, per above: Play Billing is out for a sideload build, so any shared
  approach has to tolerate one flavor with no Play billing surface. Three
  possibilities, and this page picks only the first: **free** (the recommendation),
  **sold as a paid APK** (no entitlement at all — the download *is* the purchase),
  or **an offline license**. Naming only the license route here would send a future
  shared plan off to design and maintain a licensing system when treating the APK
  as the purchase may be all that is needed (Codex, 2026-09-03).
- **Build billing once, in one app, end to end** — do not start with a shared
  library. Extract only after a second app needs it and the shape has settled, the
  order `androidlog` was extracted in.
- **ClothesCast should go first.** It is the only sibling with a genuine marginal
  cost per user, its billing design is already worked out end to end, and this app
  inherits the learning for free.
- **`docs/PRIVACY.md` changes for any purchase flow; the Play Data Safety form
  changes only if the flow actually collects something.** A local-only entitlement
  flag is on-device processing, which `SPEC.md` §12 already distinguishes from
  collection, so it is not a Data Safety disclosure on its own — and `direct` is
  not distributed through Play at all, so no `direct` mechanism reaches that form.
  What would is whatever the `play` purchase flow sends off the device (a purchase
  token to a server, say, as ClothesCast's design does). Scope the declaration to
  that, verified against the current policy text, rather than declaring by default:
  an over-broad declaration is a false statement about this app as surely as a
  missing one is.

---

## Open questions, and what each way out costs

Nothing here needs an answer today — this whole page's first conclusion is that
monetizing Snoozemo is premature. Each question is written as the choice plus
what each branch costs, so the reasoning is on the page when one of them does
come up.

**1. Nothing before the gates**, and they are not the same kind of gate — but
only two of the three are prerequisites.
- Hardware verification of departure detection comes first and **can end the
  discussion outright**: an undetected departure means there is no product to
  price. It is per flavor, since `play` and `direct` use different detectors.
- The Play declaration outcome comes first too, but a refusal only closes Play
  Billing and **redirects** the question to `direct` rather than ending it.
- **Discovery is the recommendation, not a third prerequisite** (Codex,
  2026-09-03). It is listed here because a summary that omitted it let a reader
  proceed straight from the other two — but it blocks charging from being
  *measurable*, not from being right, and it gates nothing unless the maintainer
  adopts it. The first two are prerequisites; this one is a judgment call.

**2. `direct`'s price.** This page recommends free forever, because `direct` is
insurance against a Play refusal rather than a product — and that reading is
confirmed rather than changed by the maintainer intending a public Play release
for every app in the fleet (2026-09-03): `play` is the destination, `direct` the
contingency, so the refusal outcome above matters more, not less.
- *Free*: `direct` stays the escape hatch it exists to be, and the sideload
  audience — the one most likely to pay for exactly this app — pays nothing.
- *Paid APK*: a price with no billing integration at all — the build the buyer
  downloaded *is* the entitlement, so there is no key to lose or reissue. **The
  file itself is another matter, and depends on the channel** (Codex,
  2026-09-03): a buyer who deletes it or changes device needs a redownload,
  which a storefront gives and a manual send does not — see the distribution
  bullet below. What this option removes is the licensing system, not every
  support request.
- *Offline license key*: a free download with a paid unlock, matching `play`'s
  shape, and a licensing system to build for what is currently zero users.
- **Restore and updates belong to the distribution channel, not to either
  mechanism** (Codex, 2026-09-03). A paid APK sold through a storefront can have
  redownload and updates; sold as a payment link and a manual send it has
  neither. An offline key is restorable only if something reissues it — the
  section above lists lost keys and reinstalls as exactly its support burden. So
  the question under both is *what distribution is being paid for*, and neither
  option is intrinsically the recoverable one.
- Whichever way, it should be chosen rather than inherited from "Billing doesn't
  reach `direct`" — Play Billing is IPC, so the real blocker is sideload
  distribution, not the permission set.

**3. What the paid tier actually is.** `SPEC.md` §14's places-and-automation
candidates are the strongest set on offer.
- *Places + automation (saved places, per-place policy, auto-arm)*: the only
  candidates that are recurring-value rather than one-off, and the `play`-only
  ones (auto-arm, reverse geofences) split the tier by flavor, so `direct` buyers
  would be paying for a smaller thing.
- *Something else entirely*: nothing else in the spec is both absent today and
  plausibly worth money.
- *No tier*: entirely defensible while the app has no users, and it is the
  status quo.
- Auto-arm's cost is the user's battery, and it is unmeasured — against a gate
  `TODO.md` now fixes in advance rather than at results time. That is a real
  input to whether it can headline a paid tier, and a candidate that fails the
  gate is not one to price.

**4. The price, if there is one.** $3–5 one-time is what this page argues for,
against the instinct that $10 is reachable.
- *$3–5*: matches a utility that does one thing, and it is the range where an
  unknown app from an unknown developer gets an impulse yes.
- *$10*: reachable *if someone already loves the app*, which is the condition
  Snoozemo does not meet yet — nobody has found it. The maintainer's own framing
  ("if they ever discover it") is the argument against pricing for it today.
- *Subscription*: recurring revenue, and a recurring charge for something with no
  recurring cost to the developer, which is the churn risk.
- **The Play fee does not decide it** (Codex, 2026-09-03). A subscription is 15%
  from day one with no enrollment; a one-time product is 15% too, below the first
  $1M, **if the account is enrolled** — so in the enrolled case the rates are
  identical and there is no fee argument either way. The 30% figure applies only
  to an unenrolled one-time product, which makes it an argument for enrolling in
  Play Console rather than an argument for a subscription.

**5. Which candidates are paid, asked per feature.** Not a release deadline —
there isn't one, since every candidate is deferred and absent today (see the
timing section, which retracts the earlier "before the first public release"
framing).
- *Decide before each feature ships free*: the cheap moment, because a feature
  that has shipped free is expensive to take back.
- *Ship everything free and decide later*: no decision cost now, and every later
  paywall then forces a **grandfathering-or-revocation** call rather than being a
  clean gate (Codex, 2026-09-03). Gating new installs while existing users keep
  what they have is a real option — it is exactly what `TODO.md` leaves to be
  decided case by case — so this branch's cost is that it converts a free choice
  into that decision, once per feature, not that every later paywall must take
  something away.

Alongside all of them: the listing and the forum answers, which need no decision
— and this page **recommends** doing them before a price rather than requiring it
(Codex, 2026-09-03). Two earlier drafts wrote it as an ordering, first over every
question and then over shipping a price; both made an explicitly unadopted
prerequisite read as a gate, which is the thing five other corrections on this
page were about. What is true without overstating it: charging into an audience
nobody has assembled cannot be *measured*, so a price shipped first teaches
little. What is also true: question 5 is due before any candidate ships free,
which can easily come first, and the forum outreach waits on a **public install
path** that does not exist yet — an open or production Play track, or the
`direct` route, whichever arrives (`TODO.md`, "Public rollout and discovery").
Naming both matters, because if the background-location declaration is refused
the Play half never arrives and `direct` is the only path there is. Nothing here
gates anything unless the maintainer says it does.
