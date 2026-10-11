package social.hotmess.core

import com.audiencekit.Coordinates
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paces location reports (ReportLocation): one is due when none has been sent yet, when the position
 * has moved at least [minDistanceMeters] since the last, or when [minIntervalMs] has passed. Fixes and
 * beacon sightings in between wait for the next one, so a burst of them sends one request, not one each.
 */
class ReportThrottle(
    private val minDistanceMeters: Double = MIN_DISTANCE_METERS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    private var last: Coordinates? = null
    private var lastAtMs = 0L

    /** Whether [position] should be reported at [nowMs]. */
    fun isDue(position: Coordinates, nowMs: Long): Boolean {
        val previous = last ?: return true
        return nowMs - lastAtMs >= minIntervalMs || previous.metersTo(position) >= minDistanceMeters
    }

    /** How long after [nowMs] the interval alone makes a report due; 0 when it already is. */
    fun waitMs(nowMs: Long): Long = if (last == null) 0 else (lastAtMs + minIntervalMs - nowMs).coerceAtLeast(0)

    /** Records that [position] was reported at [nowMs]. */
    fun sent(position: Coordinates, nowMs: Long) {
        last = position
        lastAtMs = nowMs
    }

    companion object {
        const val MIN_DISTANCE_METERS = 50.0
        const val MIN_INTERVAL_MS = 60_000L
    }
}

/**
 * Picks the nearest venue beacon from a scan's sightings, as iOS ranging does: the strongest signal
 * heard lately. A scan sees every beacon in range, so going by the latest sighting flips between
 * them; another beacon takes over only once it's clearly stronger ([marginDb]) or the nearest has gone
 * quiet for [staleMs].
 */
class NearestBeacon(private val staleMs: Long = 10_000, private val marginDb: Int = 5) {
    private class Sighting(val major: Int, val minor: Int, val rssi: Int, val atMs: Long)

    private var nearest: Sighting? = null

    /** Records a sighting. Returns the beacon to report when the nearest one changes, else null. */
    fun heard(major: Int, minor: Int, rssi: Int, atMs: Long): Pair<Int, Int>? {
        val current = nearest
        val same = current != null && current.major == major && current.minor == minor
        val takesOver = current == null || same || atMs - current.atMs >= staleMs || rssi >= current.rssi + marginDb
        if (!takesOver) return null
        nearest = Sighting(major, minor, rssi, atMs)
        return if (same) null else major to minor
    }
}

/** The great-circle distance to [other] in metres; beacons don't count. */
fun Coordinates.metersTo(other: Coordinates): Double {
    fun radians(degrees: Double) = Math.toRadians(degrees)
    val dLat = radians(other.latitude - latitude)
    val dLon = radians(other.longitude - longitude)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(radians(latitude)) * cos(radians(other.latitude)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

private const val EARTH_RADIUS_M = 6_371_000.0
