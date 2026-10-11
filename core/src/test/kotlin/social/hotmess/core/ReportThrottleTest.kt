package social.hotmess.core

import com.audiencekit.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportThrottleTest {
    private val here = Coordinates(47.6145, -122.3210)

    @Test
    fun theFirstReportIsDueStraightAway() {
        val throttle = ReportThrottle()
        assertTrue(throttle.isDue(here, 0))
        assertEquals(0, throttle.waitMs(0))
    }

    @Test
    fun aReportWaitsUntilFiftyMetresOrAMinute() {
        val throttle = ReportThrottle()
        throttle.sent(here, 1_000)

        assertFalse(throttle.isDue(here, 2_000), "same place, a second later")
        assertFalse(throttle.isDue(here.copy(beaconMajor = 1, beaconMinor = 2), 2_000), "a beacon alone waits")
        assertFalse(throttle.isDue(Coordinates(47.6148, -122.3210), 2_000), "about 33 m")
        assertTrue(throttle.isDue(Coordinates(47.6150, -122.3210), 2_000), "about 56 m")
        assertEquals(59_000, throttle.waitMs(2_000))
        assertTrue(throttle.isDue(here, 61_000), "a minute on")
        assertEquals(0, throttle.waitMs(61_000))
    }

    @Test
    fun measuresDistanceOnTheGlobe() {
        val nyne = Coordinates(47.6575451, -117.414777)
        // Seattle to Spokane is about 368 km as the crow flies.
        assertEquals(368.0, here.metersTo(nyne) / 1_000, 5.0)
        assertEquals(0.0, here.metersTo(here.copy(beaconMajor = 3, beaconMinor = 4)))
    }

    @Test
    fun theNearestBeaconDoesNotFlipBetweenBeaconsInRange() {
        val nearest = NearestBeacon()
        assertEquals(1 to 1, nearest.heard(1, 1, rssi = -60, atMs = 0))
        assertNull(nearest.heard(1, 2, rssi = -62, atMs = 100), "a weaker beacon")
        assertNull(nearest.heard(1, 2, rssi = -57, atMs = 200), "only slightly stronger")
        assertNull(nearest.heard(1, 1, rssi = -61, atMs = 300), "the same one again")
        assertEquals(1 to 2, nearest.heard(1, 2, rssi = -50, atMs = 400), "clearly stronger")
        assertNull(nearest.heard(1, 1, rssi = -61, atMs = 5_000))
        assertEquals(1 to 1, nearest.heard(1, 1, rssi = -70, atMs = 10_400), "the nearest went quiet")
    }
}
