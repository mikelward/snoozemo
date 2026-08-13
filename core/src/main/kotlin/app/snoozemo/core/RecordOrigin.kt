package app.snoozemo.core

/**
 * Whether a persisted snooze record was written by the device now reading it
 * (SPEC.md §12).
 *
 * A record that arrives by device-to-device transfer *acts* on the new phone:
 * `BootReceiver` restores whatever it finds and re-asserts the zen rule, so a
 * snooze armed on the old handset can take the new one quiet with nothing the
 * user did behind it — principle 1's failure, reached without any bug in the
 * snooze logic itself.
 *
 * `res/xml/data_extraction_rules.xml` is the primary defense and keeps the
 * record from arriving at all. This is the backstop for transfer tools that
 * ignore it: the same platform note that makes the bug possible says OEM
 * behavior "varies", and a tool that disregards `allowBackup` may equally
 * disregard `dataExtractionRules`.
 */
enum class RecordOrigin {
    /** Written by this device. Safe to restore. */
    THIS_DEVICE,

    /**
     * Written by a device that stamped it differently. This is the transferred
     * record the whole mechanism exists to catch.
     */
    ANOTHER_DEVICE,

    /**
     * This device stamps its records, and this one carries no stamp — so
     * whatever wrote it, it was not this device in its current state.
     *
     * Two things reach here: a record written by a build from before stamping
     * existed, and a record transferred from a handset that could not stamp.
     * They are indistinguishable, and only one of them is safe, so this refuses.
     * The cost is a snooze that was in flight across an app upgrade ending at
     * its next restore — one occurrence, bounded, and in the fail-open
     * direction — against the benefit of catching a transfer from an older
     * build, which is exactly the transfer most likely to happen.
     */
    UNATTRIBUTED,

    /**
     * The record carries a stamp and this device cannot produce one, so the
     * claim cannot be confirmed.
     *
     * **Refuses**, and the difference from [UNVERIFIABLE] below is the whole
     * reason both exist (Codex, PR #26). A device that cannot stamp cannot have
     * written a *stamped* record, so a stamp here is positive evidence that
     * something else did — the transferred case, arriving on exactly the phone
     * least able to detect it. An earlier version folded this into
     * [UNVERIFIABLE] and restored it, which disabled the backstop precisely
     * where it was needed.
     *
     * The cost is a legitimate snooze ended early if a device that normally
     * stamps momentarily cannot — and that is the fail-open direction (D7),
     * paid by the phone ringing rather than by a phone that stays silent for a
     * snooze nobody here started.
     */
    UNCONFIRMABLE,

    /**
     * Neither the record nor this device carries a stamp, so there is nothing
     * to compare and nothing suspicious about the absence.
     *
     * **This one restores**, and the asymmetry with [UNATTRIBUTED] and
     * [UNCONFIRMABLE] is the important part of this type. Those describe a
     * record that does not fit its device; this describes a device with no
     * identity at all, holding a record that matches that fact. Refusing here
     * would not fail safe, it would fail *closed*: a handset that can never
     * stamp writes records that can never be attributed, so every snooze on it
     * would end at the next process death and the app would simply not work
     * while looking like it was being careful. The declarative exclude in
     * `res/xml/data_extraction_rules.xml` still applies, so this degrades to
     * the protection the app had before stamping existed rather than to none.
     *
     * It is a degraded mode, and principle 2 means it is logged where the next
     * reader will find it rather than passing as normal.
     *
     * **It should also now be nearly unreachable** (Codex, PR #26). Reaching it
     * takes a device that can produce *no* identity, and `DeviceStamp` folds in
     * this install's first-install time as well as `ANDROID_ID` precisely so
     * that a phone missing one still has the other. What is left here is the
     * case where both are unavailable — and the objection that this state could
     * still restore a transferred record is why the fallback exists rather than
     * why this value refuses: refusing it would break every snooze on a device
     * in that state, while the fallback removes the state instead.
     */
    UNVERIFIABLE,

    ;

    /**
     * Whether a record of this origin may be restored into a live snooze.
     *
     * The asymmetry is deliberate: refusing wrongly ends a snooze early, which
     * is an annoyance; restoring wrongly leaves a phone silent that no one
     * asked to be silent, on a device whose owner has no idea Snoozemo is why.
     * So a record is restored only on a positive match — or when no device
     * could have checked it in the first place, which is [UNVERIFIABLE] and is
     * about capability rather than suspicion.
     */
    val mayRestore: Boolean
        get() = this == THIS_DEVICE || this == UNVERIFIABLE

    /** Whether this outcome is worth telling the log about. */
    val isDegraded: Boolean
        get() = this == UNVERIFIABLE

    companion object {
        /**
         * Compares the stamp [recorded] in a snooze record against [current],
         * this device's stamp.
         *
         * A device with no identity of its own cannot judge a record that has
         * none either — reading *that* as suspicious is the mistake that turns
         * a safety net into an outage. But it can still judge a record that
         * carries a stamp, because it cannot have written one, and reading that
         * as merely unverifiable is the mistake that disables the backstop on
         * the phone that most needs it. Hence two outcomes rather than one.
         *
         * Blank counts as absent throughout. A stamp that is present but empty
         * is a write that half-failed, and comparing two empty strings as equal
         * would turn the most suspicious record on disk into a match.
         */
        fun of(recorded: String?, current: String?): RecordOrigin = when {
            // Both absent: a device with no identity holding a record that
            // agrees. Nothing to compare, nothing out of place.
            current.isNullOrBlank() && recorded.isNullOrBlank() -> UNVERIFIABLE
            // Stamped record, unstampable device. This device cannot have
            // written it, so the stamp is evidence rather than noise.
            current.isNullOrBlank() -> UNCONFIRMABLE
            recorded.isNullOrBlank() -> UNATTRIBUTED
            recorded == current -> THIS_DEVICE
            else -> ANOTHER_DEVICE
        }
    }
}
