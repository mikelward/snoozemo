package app.snoozemo.crash

/**
 * What applying a choice to the crash reporter actually achieved.
 *
 * A boolean was not enough: "there is no reporter here" and "there is one and
 * the opt-out could not be made durable" are both *not success*, and they call
 * for opposite handling — the first is the ordinary `direct`/unconfigured case
 * where this app's own preference is the whole truth, the second means the
 * preference must **not** be recorded as off, because doing so would create
 * exactly the split state the flush exists to prevent (Codex, PR #113).
 */
internal enum class ReporterOutcome {
    /** No reporter in this build, so nothing to apply and nothing owed. */
    NO_REPORTER,

    /** Applied, and — for an opt-out — durable. */
    APPLIED,

    /**
     * Applied in memory, but the SDK's own persisted override could not be
     * forced to disk, so it may still read `on` after a process death. Only
     * ever returned for an opt-out; enabling has nothing to guarantee.
     */
    NOT_DURABLE,
}
