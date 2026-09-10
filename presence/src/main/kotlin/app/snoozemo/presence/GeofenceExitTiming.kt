package app.snoozemo.presence

/**
 * How late a geofence exit arrived, for the debug log — **or that nobody
 * knows** (SPEC.md §4.6).
 *
 * The lag is the number a field trace is read for: an exit can be delivered
 * long after the crossing, and Doze and batching are the suspects that have to
 * be separated from an engine that simply never asked. But the platform does
 * not always attach a triggering location, and the receiver's fallback for a
 * missing one is *the delivery time itself* — so subtracting the observation's
 * timestamp from the delivery downstream reports **≈0 lag for exactly the
 * events that carried no timing**, clearing the case on the evidence it most
 * lacks (Codex, PR #232).
 *
 * Hence a nullable crossing rather than a number that is always there: the two
 * facts are only distinguishable at the receiver, so the absence is recorded
 * as an absence there, and there is no single field left downstream for a
 * later reader to subtract from by mistake.
 *
 * Pure, so the distinction is pinned by a JVM test; the receiver it serves is
 * a `BroadcastReceiver` over a Play Services payload no unit test can build.
 */
internal fun geofenceExitDeliveryNote(crossedAtMs: Long?, deliveredAtMs: Long): String =
    if (crossedAtMs == null) {
        "geofence exit delivered; platform attached no crossing time"
    } else {
        "geofence exit delivered ${deliveredAtMs - crossedAtMs} ms after the crossing"
    }
