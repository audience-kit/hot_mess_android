package social.hotmess.core

import com.audiencekit.Coordinates

/**
 * Which position the app reports: the device's, with the nearest beacon once one is heard, or in a
 * test build the venue the tester is pretending to be at ("Pretend I'm here" on a venue). While
 * pretending, device fixes and beacons are kept but not reported, so stopping goes straight back to
 * the real position.
 */
class PositionTracker(private val simulationAllowed: Boolean) {
    private var device: Coordinates? = null
    private var simulated: Coordinates? = null

    /** The venue being pretended at, if any. */
    var simulatedVenue: String? = null
        private set

    /** What to report now. */
    val current: Coordinates? get() = simulated ?: device

    /** Records a fix from the device. True when it changes what's reported. */
    fun deviceFix(latitude: Double, longitude: Double): Boolean {
        device = Coordinates(latitude, longitude, device?.beaconMajor, device?.beaconMinor)
        return simulated == null
    }

    /** Attaches the nearest beacon to the device's position. True when it changes what's reported. */
    fun beacon(major: Int, minor: Int): Boolean {
        // Only attach beacon identifiers to a position we actually have.
        val position = device ?: return false
        if (position.beaconMajor == major && position.beaconMinor == minor) return false
        device = position.copy(beaconMajor = major, beaconMinor = minor)
        return simulated == null
    }

    /** Reports this venue's position instead of the device's. False, and ignored, outside test builds. */
    fun simulate(latitude: Double, longitude: Double, venueName: String): Boolean {
        if (!simulationAllowed) return false
        simulated = Coordinates(latitude, longitude)
        simulatedVenue = venueName
        return true
    }

    /** Goes back to the device's position. True when the app was pretending. */
    fun stopSimulating(): Boolean {
        if (simulated == null) return false
        simulated = null
        simulatedVenue = null
        return true
    }
}
