package app.snoozemo.snooze

/**
 * Whether the anchor's own network is currently associated, for anything
 * drawing the departure readout live (`SPEC.md` §4.6).
 *
 * **In memory and process-wide, never on disk**, for [DepartureObservations]'
 * reasons exactly: this is what the presence engine last said, not part of the
 * snooze, and a snooze that outlives the process comes back knowing nothing
 * until the engine speaks again — which is the truth rather than a gap.
 *
 * It is held beside the reading rather than on it because it is the reason
 * there is no reading: `Presence` asks location for nothing while the anchor's
 * network answers (SPEC.md §6.7), so the two are never both current, and a
 * surface needs the second to explain the absence of the first.
 *
 * **A mirror, not a second copy.** `SnoozeController` writes this on every
 * update through `Listener.onAnchorWifi`, including the resets at arm and
 * restore, so nothing here has to be cleared by hand at the sites where a card
 * might be posted. Doing it the other way — announcing edges only and clearing
 * this by hand — produced three separate bugs in one review pass: a refused
 * release cleared a level the snooze still had, a restore posted its card
 * before the clear ran, and the engine's restore assumption reached the card
 * as fact (Codex, PR #229). One writer removes the class rather than the
 * instances.
 */
internal object AnchorWifi {

    @Volatile
    private var associated: Boolean = false

    /** Whether the anchor's network was associated when the engine last said. */
    fun associated(): Boolean = associated

    /** Records what the engine reported. */
    fun set(value: Boolean) {
        associated = value
    }

    /**
     * Forgets it. Production never needs this — the controller's reset at arm
     * and restore already reports `false` through [set] — but a test sharing
     * this process with an earlier one does.
     */
    fun clear() {
        associated = false
    }
}
