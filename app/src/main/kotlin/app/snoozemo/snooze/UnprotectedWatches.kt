package app.snoozemo.snooze

/**
 * Whether the running service's watch is unprotected — it wants a foreground
 * service, an attempt to take one was refused, and it holds none — published
 * for the main screen, on [GraceDeadlines]' contract.
 *
 * The service's own reading (`foregroundHost.watchIsUnprotected`) is live
 * state on the one running instance, and the card asks it directly. The
 * screen cannot reach that instance, and the record must not carry a mirrored
 * copy of a live reading (the `atAnchorWifi` lesson), so the service publishes
 * each change here and withdraws it when the card or the service goes.
 */
internal object UnprotectedWatches {

    @Volatile
    private var latest: Boolean = false

    @Volatile
    private var onChange: (() -> Unit)? = null

    fun latest(): Boolean = latest

    /** Records [unprotected], telling the watcher only when it changed. */
    fun publish(unprotected: Boolean) {
        if (latest == unprotected) return
        latest = unprotected
        onChange?.invoke()
    }

    fun clear() = publish(false)

    /** One watcher, called once on registration so subscribe-and-read is atomic. */
    fun watch(listener: () -> Unit): AutoCloseable {
        onChange = listener
        listener()
        return AutoCloseable { if (onChange === listener) onChange = null }
    }
}
