package social.hotmess.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Times and distances the way the design system writes them: "Fri 9pm", "Fri 9pm · The Wildrose". */
object Formatting {
    /** "Fri 9pm", or "Fri 9:30pm" when it isn't on the hour. */
    fun shortTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val time = instant.atZone(zone)
        val day = time.format(DateTimeFormatter.ofPattern("EEE", locale))
        val hour = time.format(DateTimeFormatter.ofPattern(if (time.minute == 0) "h" else "h:mm", locale))
        val meridiem = time.format(DateTimeFormatter.ofPattern("a", Locale.US)).lowercase(Locale.ROOT)
        return "$day $hour$meridiem"
    }

    /** "Fri 9pm · The Wildrose", or just the time when the venue isn't known. */
    fun eventSubtitle(event: Event, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        listOfNotNull(shortTime(event.startAt, zone, locale), event.venue?.name?.takeIf { it.isNotBlank() })
            .joinToString(" · ")

    /** "Oct 6, 2026, 9:00 PM" for the event screen's start and end rows. */
    fun dateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        instant.atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

    /** "OCT" and "6" for the date tile. */
    fun monthAbbreviation(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        instant.atZone(zone).format(DateTimeFormatter.ofPattern("MMM", locale)).uppercase(locale).trimEnd('.')

    fun dayOfMonth(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = instant.atZone(zone).dayOfMonth.toString()

    /** Metres as "350 m" / "1.2 km", or feet and miles where the locale uses them. */
    fun distance(metres: Double, locale: Locale = Locale.getDefault()): String {
        val imperial = locale.country in setOf("US", "GB", "LR", "MM")
        return if (imperial) {
            val miles = metres / 1609.344
            if (miles < 0.1) "${(metres * 3.28084).toInt()} ft" else "${oneDecimal(miles, locale)} mi"
        } else {
            if (metres < 1000) "${metres.toInt()} m" else "${oneDecimal(metres / 1000, locale)} km"
        }
    }

    private fun oneDecimal(value: Double, locale: Locale): String {
        val text = String.format(locale, "%.1f", value)
        val zero = String.format(locale, "%.1f", 0.0).drop(1)
        return text.removeSuffix(zero)
    }
}
