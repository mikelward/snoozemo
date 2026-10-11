package app.snoozemo.snooze

/**
 * The running Wi-Fi grace period's deadline, published by the running service
 * for the main screen to count down to — `DepartureObservations`' contract
 * for a different reading.
 *
 * In elapsed realtime, the controller's own value; the controller stays the
 * single writer and this is cleared wherever the departure reading is, so it
 * never outlives the snooze or the grace that set it.
 */
internal object GraceDeadlines {

    @Volatile
    private var latest: Long? = null

    @Volatile
    private var onChange: (() -> Unit)? = null

    fun latest(): Long? = latest

    /** Records [deadlineMs] (null for none), telling the watcher only when it moved. */
    fun publish(deadlineMs: Long?) {
        if (latest == deadlineMs) return
        latest = deadlineMs
        onChange?.invoke()
    }

    fun clear() = publish(null)

    /** One watcher, called once on registration so subscribe-and-read is atomic. */
    fun watch(listener: () -> Unit): AutoCloseable {
        onChange = listener
        listener()
        return AutoCloseable { if (onChange === listener) onChange = null }
    }
}
