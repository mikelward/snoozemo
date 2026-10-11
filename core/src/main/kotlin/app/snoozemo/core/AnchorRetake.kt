package app.snoozemo.core

import kotlin.math.ceil

/**
 * What `Until I leave` over a running snooze watches: the place it had **and**
 * the place the phone is now, as one circle (maintainer, 2026-10-11).
 *
 * Tapping it again somewhere else says "I'm still here, and here is big", not
 * "start over somewhere small" — so the old place stays inside the new one.
 * The result is the **smallest circle containing both circles whole**: centred
 * on the line between the two fixes, radius half the distance plus the
 * radii. One centre and one radius, so the geofence and the departure test do
 * not change shape, and a later retake folds the next spot in the same way.
 *
 * Bounded by [MAX_RADIUS_M]. The radius only ever grows across retakes, and a
 * wild fix — or a tap made after really leaving — would otherwise draw a fence
 * nobody can walk out of. Past the cap the two spots are too far apart to be
 * one place, so the fresh one is taken on its own: the snooze then ends on
 * leaving where the tap happened, which is the earlier of the two answers (D7).
 *
 * Pure, like the rest of the decision logic (SPEC.md §11).
 */
object AnchorRetake {

    /** The largest "here" a retake may draw, in meters. */
    const val MAX_RADIUS_M: Int = 500

    /**
     * The anchor to watch after a retake: [previous] and [fresh] merged, or
     * [fresh] alone when either has no usable fix or the merge would pass
     * [MAX_RADIUS_M].
     *
     * The Wi-Fi half is the fresh one's — the network the phone is on now.
     * The merged accuracy is the vaguer of the two, since the centre is drawn
     * from both points.
     */
    fun merge(previous: Anchor, fresh: Anchor): Anchor {
        if (!previous.hasUsableFix || !fresh.hasUsableFix) return fresh
        val d = Departure.distanceM(
            Fix(lat = fresh.lat!!, lon = fresh.lon!!, accuracyM = fresh.fixAccuracyM!!, elapsedRealtimeMs = 0L),
            previous,
        ) ?: return fresh
        val r1 = previous.radiusM.toDouble()
        val r2 = fresh.radiusM.toDouble()
        // One already contains the other: that one is the smallest.
        val accuracy = maxOf(previous.fixAccuracyM!!, fresh.fixAccuracyM!!)
        if (d + r2 <= r1) {
            return fresh.copy(lat = previous.lat, lon = previous.lon, radiusM = previous.radiusM, fixAccuracyM = accuracy)
        }
        if (d + r1 <= r2) return fresh
        val radius = (d + r1 + r2) / 2
        if (radius > MAX_RADIUS_M) return fresh
        // From the old centre towards the new by (radius - r1). Linear in
        // degrees: at under a kilometre the error is far below fix accuracy.
        // The longitude step is the short way round, so two spots either side
        // of ±180° meet in the middle rather than on the far side of the
        // planet (Codex, PR #318).
        val t = (radius - r1) / d
        val dLon = wrapLongitude(fresh.lon!! - previous.lon!!)
        return fresh.copy(
            lat = previous.lat!! + t * (fresh.lat!! - previous.lat),
            lon = wrapLongitude(previous.lon + t * dLon),
            radiusM = ceil(radius).toInt(),
            fixAccuracyM = accuracy,
        )
    }

    /** [degrees] in (-180, 180]. */
    internal fun wrapLongitude(degrees: Double): Double {
        val wrapped = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (wrapped == -180.0) 180.0 else wrapped
    }

    /** Meters per degree of latitude, for tests that place fixes by distance. */
    internal const val M_PER_DEG_LAT = 111_320.0
}
