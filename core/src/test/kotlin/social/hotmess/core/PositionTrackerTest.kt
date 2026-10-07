package social.hotmess.core

import com.audiencekit.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionTrackerTest {
    private val nyne = Coordinates(47.6575451, -117.414777)

    @Test
    fun reportsTheDevicePositionWithItsBeacon() {
        val tracker = PositionTracker(simulationAllowed = false)
        assertFalse(tracker.beacon(1, 2), "no beacon without a position")

        assertTrue(tracker.deviceFix(47.61, -122.32))
        assertTrue(tracker.beacon(1, 2))
        assertFalse(tracker.beacon(1, 2), "the same beacon again changes nothing")
        assertTrue(tracker.deviceFix(47.62, -122.33))

        assertEquals(Coordinates(47.62, -122.33, 1, 2), tracker.current)
    }

    @Test
    fun pretendingReportsTheVenueUntilItStops() {
        val tracker = PositionTracker(simulationAllowed = true)
        tracker.deviceFix(47.61, -122.32)

        assertTrue(tracker.simulate(nyne.latitude, nyne.longitude, "Nyne"))
        assertEquals(nyne, tracker.current)
        assertEquals("Nyne", tracker.simulatedVenue)

        assertFalse(tracker.deviceFix(47.70, -122.40), "device fixes aren't reported while pretending")
        assertFalse(tracker.beacon(3, 4))
        assertEquals(nyne, tracker.current)

        assertTrue(tracker.stopSimulating())
        assertNull(tracker.simulatedVenue)
        assertEquals(Coordinates(47.70, -122.40, 3, 4), tracker.current, "back to the latest real position")
        assertFalse(tracker.stopSimulating())
    }

    @Test
    fun stoppingWithNoFixReportsNothing() {
        val tracker = PositionTracker(simulationAllowed = true)
        tracker.simulate(nyne.latitude, nyne.longitude, "Nyne")
        tracker.stopSimulating()

        assertNull(tracker.current)
    }

    @Test
    fun storeBuildsCannotPretend() {
        val tracker = PositionTracker(simulationAllowed = false)
        tracker.deviceFix(47.61, -122.32)

        assertFalse(tracker.simulate(nyne.latitude, nyne.longitude, "Nyne"))
        assertNull(tracker.simulatedVenue)
        assertEquals(Coordinates(47.61, -122.32), tracker.current)
    }
}
