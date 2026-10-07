package social.hotmess.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.max

/** One night on the events calendar, with its events and how busy it is from 0 to [NightCalendar.MAX_LEVEL]. */
data class Night(
    val date: LocalDate,
    val events: List<Event>,
    val isToday: Boolean,
    val isPast: Boolean,
    val busyness: Int,
) {
    val count: Int get() = events.size
}

/**
 * The next four weeks of nights, a week to a row like a month view, matching the iOS app.
 *
 * The grid starts on the first day of the week containing today, so the first row can hold nights
 * that have already passed. Events starting before 5 AM count towards the night before, since a
 * 1 AM set is part of Friday night, not Saturday.
 */
data class NightCalendar(val weeks: List<List<Night>>) {
    val nights: List<Night> get() = weeks.flatten()

    companion object {
        const val WEEK_COUNT = 4
        const val MAX_LEVEL = 3
        const val NIGHT_ROLLOVER_HOURS = 5L

        fun of(
            events: List<Event>,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
            firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
        ): NightCalendar {
            val weekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val byNight = events.groupBy { night(it.startAt, zone) }
            val days = (0 until WEEK_COUNT * 7).map { weekStart.plusDays(it.toLong()) }
            val busiest = days.filter { !it.isBefore(today) }.maxOfOrNull { byNight[it]?.size ?: 0 } ?: 0

            val nights = days.map { day ->
                val nightEvents = byNight[day].orEmpty().sortedBy { it.startAt }
                Night(
                    date = day,
                    events = nightEvents,
                    isToday = day == today,
                    isPast = day.isBefore(today),
                    busyness = level(nightEvents.size, busiest),
                )
            }
            return NightCalendar(nights.chunked(7))
        }

        /** The night an event belongs to: its start date, or the day before when it starts in the small hours. */
        fun night(start: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
            start.atZone(zone).minusHours(NIGHT_ROLLOVER_HOURS).toLocalDate()

        /** Scales a night's count against the busiest night in view, so small towns use every shade too. */
        fun level(count: Int, busiest: Int): Int {
            if (count <= 0 || busiest <= 0) return 0
            return max(1, ceil(count.toDouble() / busiest * MAX_LEVEL).toInt())
        }
    }
}
