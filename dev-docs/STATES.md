# Tracking states and what the card shows

This is the state model behind the ongoing snooze — the `TrackingMode` a snooze
runs in, how it moves between modes, and the rule that decides what each state's
card, notification and tile put in front of the user. It exists so the display
rule (SPEC.md §4.2, §4.4) can be reasoned about state by state rather than
re-derived from the code each time.

The diagrams render on GitHub directly from the Mermaid source below. Rendered
SVGs are also checked in for viewing outside GitHub —
[`tracking-states-lifecycle.svg`](./tracking-states-lifecycle.svg) and
[`tracking-states-display-rule.svg`](./tracking-states-display-rule.svg) — and
are the source of truth's *output*, not the source: edit the Mermaid blocks here,
then regenerate the SVGs with `mermaid-cli` (`mmdc -i <block>.mmd -o <file>.svg`).

## The tracking-mode lifecycle

A snooze arms into `SETTLING` (the anchor capture is still running, bounded at
10 s so arming never blocks — SPEC.md §4.1), then resolves to the most capable
mode the anchor actually supports (`TrackingMode.from`). From there it degrades —
never silently — as signals are lost, and ends on one of the exits.

```mermaid
stateDiagram-v2
    [*] --> SETTLING: tile tapped (arming)

    SETTLING --> FULL: usable fix
    SETTLING --> WIFI_ONLY: anchor SSID only, no usable fix
    SETTLING --> DURATION_ONLY: neither signal

    FULL --> WIFI_ONLY: fix lost, still on anchor Wi-Fi
    WIFI_ONLY --> FULL: usable fix returns

    FULL --> DURATION_ONLY: location grant or switch lost
    WIFI_ONLY --> DURATION_ONLY: location grant or switch lost

    WIFI_ONLY --> WIFI_GRACE: anchor Wi-Fi dropped, departure unconfirmable (§6.6)
    FULL --> WIFI_GRACE: anchor Wi-Fi gone, then the fix fails (§6.6)
    WIFI_GRACE --> WIFI_ONLY: Wi-Fi or fix recovers
    WIFI_GRACE --> [*]: grace elapses (ends)

    FULL --> [*]: departure confirmed
    WIFI_ONLY --> [*]: departure confirmed
    DURATION_ONLY --> [*]: duration cap
    SETTLING --> [*]: duration cap

    note right of SETTLING
        Any armed state can also end on:
        • the duration cap (failsafe / chosen timer)
        • MOVED, if the user chose "When I move"
        • MANUAL (tile / notification / in-app)
        • DND_TURNED_OFF (user's own shade toggle)
        Losing location degrades to DURATION_ONLY;
        it no longer ends the snooze (maintainer, 2026-08-30).
    end note
```

`WIFI_GRACE` needs both signals gone — the anchor Wi-Fi dropped *and* no usable
fix to confirm a departure. That's usually reached from `WIFI_ONLY`, but also
directly from `FULL`: losing Wi-Fi alone keeps a snooze in `FULL` (its fix still
confirms departures), so grace arms only once that fix later fails — and the
snooze was never on anchor Wi-Fi, so it never became `WIFI_ONLY`.

Degradation carries a *reason* (`DegradationCause`) so the notification can say
which kind of degraded it is — `NO_LOCATION_FIX` (location broken) reads
differently to `FIXES_TOO_VAGUE` (location working, nothing to fix against) even
though both land in the same mode.

## The display rule

Two things go on a card: **the next trigger that moves toward un-snoozing**, and
**a time** — but the time is only shown when it is the most specific end
available. A chosen (shortened) timer is always a real deadline; the 8 h failsafe
is shown only when nothing else is watching for a way out.

```mermaid
flowchart TD
    A["Card for a running snooze"] --> B{"Is a more-specific end than the<br/>8h failsafe running?<br/>chosen timer, or Wi-Fi grace"}
    B -- yes --> S["Show that countdown, and name it:<br/>a time chosen, or 'Wi-Fi lost'.<br/>Still name any exit also armed."]
    B -- no --> C{"Is a departure or motion<br/>exit being watched?"}
    C -- yes --> N["Name the trigger: leave / move.<br/>No time — the exit is the plan."]
    C -- no --> F["Surface the 8h failsafe time —<br/>it is the only automatic end."]
```

The unified rule is **show the most-specific end's time; the 8 h failsafe only
when it *is* the most specific**. "Is a departure being watched" is
`effectiveMode.tracksDeparture`: true for `FULL`, `WIFI_ONLY` and `WIFI_GRACE`;
false for `DURATION_ONLY` and `SETTLING`. So the failsafe surfaces in exactly the
states where presence has no departure it can detect — which folds `SETTLING` in
with the degraded modes for free. `WIFI_GRACE` is departure-watched, so the
failsafe stays hidden — but the grace period is a nearer, real deadline, so its
own short countdown shows instead (see below).

## Per-state mapping

Ordinary snooze (`Until I leave`, no chosen time, no motion exit):

| State | Next trigger named | Time shown | Notes |
|---|---|---|---|
| `SETTLING` | Waiting for location | 8 h failsafe | can't yet tell what will be watched |
| `FULL` | leave | none | departure watched |
| `WIFI_ONLY` | leave | none | departure watched (Wi-Fi) |
| `WIFI_GRACE` | Wi-Fi lost | 5 min grace countdown (or a sooner cap) | not the failsafe; recovers to `WIFI_ONLY` if Wi-Fi/fix returns |
| `DURATION_ONLY` (degraded) | timer | 8 h failsafe | location off / grant gone / no plan |

How the exit flags change it:

- **`Until (time)` chosen** — cap shortened below the failsafe. The countdown
  always shows, whatever the mode, because it is a deadline the user set. Any exit
  that survived the change (the `PARTIAL` fail-open, or a process death
  mid-replacement) is still named alongside it.
- **`When I move` chosen** — motion is an armed exit, so "move" is named and the
  failsafe stays hidden, the same shape as a departure exit — even in
  `DURATION_ONLY`, where motion is the only thing being watched.
- **Both `leave` and `move` armed** — the surfaces differ, on purpose: the main
  screen names only `move` (`Snoozing until you move` — motion is the stricter
  trigger, so it already covers leaving, SPEC §4.4); the notification names both;
  the tile names neither.

## `WIFI_GRACE` display

A departure is technically "being watched", so the 8 h failsafe stays hidden —
but the next end here is not a user action: the anchor Wi-Fi already dropped and a
short grace timeout (§6.6, `Presence.WIFI_GRACE` = 5 min) is ending the snooze
automatically unless something recovers. The decided design is for the card to
name "Wi-Fi lost" and show a **countdown to the grace deadline** — a real,
scheduled end (`graceDeadlineMs`), nearer than the failsafe, so by the unified
rule it is the time to show (maintainer, 2026-09-13). If Wi-Fi or a fix returns,
the snooze goes back to `WIFI_ONLY` and the card returns to "leave".

**How it reaches the surfaces.** The deadline is live presence state, so it is
not stored on the snooze record: the presence update carries it as a level, the
controller holds it only while the mode is `WIFI_GRACE`, the notification asks the
controller when it is built, and the main screen follows a process-wide
publication of it (`GraceDeadlines`, on `DepartureObservations`' terms).
`ActiveSnooze.countdown` picks the time: the grace deadline, or the cap when that
is sooner. It uses the same always-hours copy as the rest of the app
(`0h 4m left`).
