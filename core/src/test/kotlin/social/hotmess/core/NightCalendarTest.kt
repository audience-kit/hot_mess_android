package social.hotmess.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class NightCalendarTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 7) // a Wednesday

    private fun event(id: String, day: Int, hour: Int) =
        Event(id = id, name = id, startAt = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant())

    @Test
    fun laysOutFourSundayFirstWeeksAroundToday() {
        val calendar = NightCalendar.of(emptyList(), today, zone)

        assertEquals(4, calendar.weeks.size)
        assertEquals(List(4) { 7 }, calendar.weeks.map { it.size })
        assertEquals(LocalDate.of(2026, 10, 4), calendar.nights.first().date)
        assertEquals(3, calendar.nights.count { it.isPast })
        assertEquals(listOf(today), calendar.nights.filter { it.isToday }.map { it.date })
    }

    @Test
    fun countsSmallHoursEventsTowardsTheNightBefore() {
        val events = listOf(event("headliner", 9, 22), event("after-hours", 10, 2), event("tea-dance", 10, 16))
        val counts = NightCalendar.of(events, today, zone).nights.associate { it.date to it.count }

        assertEquals(2, counts[LocalDate.of(2026, 10, 9)])
        assertEquals(1, counts[LocalDate.of(2026, 10, 10)])
    }

    @Test
    fun shadesNightsRelativeToTheBusiestOne() {
        assertEquals(listOf(0, 1, 2, 3), listOf(0, 1, 3, 6).map { NightCalendar.level(it, busiest = 6) })
    }
}
