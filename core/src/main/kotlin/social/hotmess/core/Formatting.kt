package social.hotmess.core

import com.audiencekit.CoverCharge
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Currency
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

    /** "5am", or "9:46pm" when it isn't on the hour. */
    fun clock(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = instant.atZone(zone)
        val hour = time.format(DateTimeFormatter.ofPattern(if (time.minute == 0) "h" else "h:mm", Locale.US))
        val meridiem = time.format(DateTimeFormatter.ofPattern("a", Locale.US)).lowercase(Locale.ROOT)
        return "$hour$meridiem"
    }

    /**
     * Names as a sentence: "Sam", "Sam and Alex", "Sam, Alex and Kai", joined with [conjunction]. Past
     * [limit] names the rest are counted: "Sam, Alex and 2 others".
     */
    fun list(items: List<String>, conjunction: String, limit: Int = Int.MAX_VALUE): String {
        if (items.size > limit && limit > 0) {
            val rest = items.size - limit
            return items.take(limit).joinToString(", ") + " $conjunction " + if (rest == 1) "1 other" else "$rest others"
        }
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " $conjunction " + items.last()
        }
    }

    /** "Fri 9pm · The Wildrose", or "Fri 9pm · To be announced" when the venue isn't set yet. */
    fun eventSubtitle(event: Event, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        listOf(shortTime(event.startAt, zone, locale), event.venue?.name?.takeIf { it.isNotBlank() } ?: TO_BE_ANNOUNCED)
            .joinToString(" · ")

    /** Where an event with no venue yet is. */
    const val TO_BE_ANNOUNCED = "To be announced"

    /** "Oct 6, 2026, 9:00 PM" for the event screen's start and end rows. */
    fun dateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        instant.atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

    /** "OCT" and "6" for the date tile. */
    fun monthAbbreviation(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        instant.atZone(zone).format(DateTimeFormatter.ofPattern("MMM", locale)).uppercase(locale).trimEnd('.')

    fun dayOfMonth(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = instant.atZone(zone).dayOfMonth.toString()

    /** "$11.12", or "$10" for a round amount, in the cover's currency (an ISO 4217 code, any case). */
    fun money(cents: Int, currency: String = "usd", locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currency.uppercase(Locale.ROOT)) }
        val digits = format.currency?.defaultFractionDigits?.takeIf { it >= 0 } ?: 2
        val whole = cents % 100 == 0
        format.minimumFractionDigits = if (whole) 0 else digits
        format.maximumFractionDigits = if (whole) 0 else digits
        return format.format(cents / 100.0)
    }

    /** A cover's start, `21:00`, as "9pm", or "9:30pm" when it isn't on the hour; null when it isn't a time. */
    fun clockTime(hhmm: String): String? {
        val time = try {
            LocalTime.parse(hhmm.trim())
        } catch (_: DateTimeParseException) {
            return null
        }
        val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
        val minutes = if (time.minute == 0) "" else ":%02d".format(Locale.ROOT, time.minute)
        return "$hour$minutes${if (time.hour < 12) "am" else "pm"}"
    }

    /** "Cover $11.12 tonight · from 9pm"; another night's leaves out "tonight". */
    fun coverLine(charge: CoverCharge, tonight: Boolean, locale: Locale = Locale.getDefault()): String {
        val price = "Cover ${money(charge.totalCents, charge.currency, locale)}${if (tonight) " tonight" else ""}"
        val from = charge.from?.let(::clockTime) ?: return price
        return "$price · from $from"
    }

    /** A cover's night, `2026-10-09`, as "Fri, Oct 9"; the text as it is when it isn't a date. */
    fun night(isoDate: String, locale: Locale = Locale.getDefault()): String = try {
        LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
    } catch (_: DateTimeParseException) {
        isoDate
    }

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
