package social.hotmess.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.Night
import social.hotmess.core.NightCalendar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * A month-style grid of the next four weeks, each night shaded by how many events it has. Tapping a
 * night selects it; tapping it again clears it.
 */
@Composable
fun NightCalendarView(calendar: NightCalendar, selected: LocalDate?, onSelect: (LocalDate?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(monthTitle(calendar), style = HotMessType.heading, color = tokens.ink)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            calendar.weeks.firstOrNull()?.forEach { night ->
                Text(
                    night.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = HotMessType.caption,
                    color = tokens.inkMuted,
                    modifier = Modifier.weight(1f).clearAndSetSemantics { },
                    textAlign = TextAlign.Center,
                )
            }
        }

        calendar.weeks.forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { night ->
                    NightCell(
                        night = night,
                        isSelected = night.date == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(if (night.date == selected) null else night.date) },
                    )
                }
            }
        }

        BusynessLegend()
    }
}

@Composable
private fun NightCell(night: Night, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border = when {
        isSelected -> tokens.ink
        night.isToday -> tokens.accent
        else -> Color.Transparent
    }
    val label = buildString {
        append(night.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)))
        append(", ")
        append(if (night.count == 0) "no events" else if (night.count == 1) "1 event" else "${night.count} events")
    }
    Column(
        modifier
            .heightIn(min = 48.dp)
            .alpha(if (night.isPast) 0.35f else 1f)
            .background(busynessShade(night.busyness), Radius.lg)
            .border(if (isSelected) 2.5.dp else 1.5.dp, border, Radius.lg)
            .clickable(enabled = !night.isPast, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                this.selected = isSelected
            }
            .padding(vertical = Space.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val ink = if (night.busyness >= 2) tokens.accentInk else tokens.ink
        Text(
            night.date.dayOfMonth.toString(),
            style = HotMessType.subheading.copy(fontWeight = if (night.isToday) FontWeight.Bold else FontWeight.Normal),
            color = ink,
        )
        Text(if (night.count > 0) night.count.toString() else " ", style = HotMessType.bodySmall, color = ink.copy(alpha = 0.8f))
    }
}

/** The accent shades used for 0 through [NightCalendar.MAX_LEVEL]. */
@Composable
private fun busynessShade(level: Int): Color = when {
    level < 1 -> tokens.surfaceSunken
    level == 1 -> tokens.accent.copy(alpha = 0.25f)
    level == 2 -> tokens.accent.copy(alpha = 0.6f)
    else -> tokens.accent
}

@Composable
private fun BusynessLegend() {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(Space.s1, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Quiet", style = HotMessType.bodySmall, color = tokens.inkMuted)
        (0..NightCalendar.MAX_LEVEL).forEach { level ->
            Box(Modifier.size(14.dp).background(busynessShade(level), Radius.sm))
        }
        Text("Busy", style = HotMessType.bodySmall, color = tokens.inkMuted)
    }
}

/** "October", or "October – November" when the four weeks span two months. */
private fun monthTitle(calendar: NightCalendar): String {
    val months = calendar.nights.filterNot { it.isPast }.map { it.date.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) }
    val first = months.firstOrNull() ?: return ""
    val last = months.last()
    return if (first == last) first else "$first – $last"
}
