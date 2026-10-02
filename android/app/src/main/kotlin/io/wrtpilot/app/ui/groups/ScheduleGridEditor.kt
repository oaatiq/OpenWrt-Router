package io.wrtpilot.app.ui.groups

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.common.currentLocale
import io.wrtpilot.core.domain.ScheduleGrid
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Grid day index (0 = Monday) for each row, starting with the locale's first day of week. */
fun dayOrder(locale: Locale): List<Int> {
    val first = WeekFields.of(locale).firstDayOfWeek.value - 1
    return (0 until 7).map { (first + it) % 7 }
}

fun dayName(day: Int, locale: Locale, style: TextStyle = TextStyle.SHORT): String =
    DayOfWeek.of(day + 1).getDisplayName(style, locale)

private fun hourText(hour: Int, locale: Locale): String =
    if (hour == 24) "24:00" else LocalTime.of(hour, 0).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

/** "Mon: 07:00–08:00, 16:00–21:00" per day, for summaries and accessibility. */
fun describeGrid(grid: ScheduleGrid, locale: Locale, noneText: String): List<String> =
    dayOrder(locale).map { d ->
        val ranges = grid.ranges(d)
        val text = if (ranges.isEmpty()) noneText else ranges.joinToString(", ") {
            "${hourText(it.first, locale)}–${hourText(it.last + 1, locale)}"
        }
        "${dayName(d, locale, TextStyle.FULL)}: $text"
    }

/**
 * Week × hours grid. Tap or drag across cells to allow / block hours
 * (the first cell touched decides whether the drag paints or erases).
 * Tapping a day name opens a time-range dialog for that day.
 */
@Composable
fun ScheduleGridEditor(
    grid: ScheduleGrid,
    onChange: (ScheduleGrid) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val locale = currentLocale()
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val days = remember(locale) { dayOrder(locale) }
    val current by rememberUpdatedState(grid)
    val emit by rememberUpdatedState(onChange)
    var editDay by remember { mutableStateOf<Int?>(null) }

    val allowedColor = MaterialTheme.colorScheme.primary
    val blockedColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val disabledAlpha = if (enabled) 1f else 0.38f
    val rowHeight = 30.dp
    val gap = 2.dp
    val summary = describeGrid(grid, locale, context.getString(R.string.schedule_no_internet)).joinToString("; ")

    Column(modifier.semantics { contentDescription = summary }) {
        // hour labels
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(44.dp))
            Box(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth()) {
                    for (h in listOf(0, 6, 12, 18)) {
                        Text(
                            hourText(h, locale),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(44.dp)) {
                for (d in days) {
                    Box(
                        Modifier
                            .height(rowHeight + gap)
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { editDay = d },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(dayName(d, locale), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height((rowHeight + gap) * 7)
                    .pointerInput(enabled, rtl, days) {
                        if (!enabled) return@pointerInput
                        val cellW = size.width / ScheduleGrid.HOURS.toFloat()
                        val rowH = size.height / 7f
                        fun cellAt(pos: Offset): Pair<Int, Int>? {
                            val row = floor(pos.y / rowH).toInt()
                            var col = floor(pos.x / cellW).toInt()
                            if (row !in 0 until 7 || col !in 0 until ScheduleGrid.HOURS) return null
                            if (rtl) col = ScheduleGrid.HOURS - 1 - col
                            return days[row] to col
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val start = cellAt(down.position) ?: return@awaitEachGesture
                            val paint = !current.isAllowed(start.first, start.second)
                            var g = current.with(start.first, start.second, paint)
                            emit(g)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                val cell = cellAt(change.position)
                                if (cell != null && g.isAllowed(cell.first, cell.second) != paint) {
                                    g = g.with(cell.first, cell.second, paint)
                                    emit(g)
                                }
                                change.consume()
                            }
                        }
                    },
            ) {
                val cellW = size.width / ScheduleGrid.HOURS
                val rowH = size.height / 7f
                val gapPx = gap.toPx()
                days.forEachIndexed { row, d ->
                    for (h in 0 until ScheduleGrid.HOURS) {
                        val col = if (rtl) ScheduleGrid.HOURS - 1 - h else h
                        val color = if (grid.isAllowed(d, h)) allowedColor else blockedColor
                        drawRoundRect(
                            color = color.copy(alpha = color.alpha * disabledAlpha),
                            topLeft = Offset(col * cellW + gapPx / 2, row * rowH + gapPx / 2),
                            size = Size(cellW - gapPx, rowH - gapPx),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                }
            }
        }
    }

    editDay?.let { d ->
        DayRangeDialog(
            day = d,
            grid = grid,
            locale = locale,
            onDismiss = { editDay = null },
            onApply = { range ->
                editDay = null
                var g = grid.withDay(d, false)
                if (range != null) for (h in range) g = g.with(d, h, true)
                onChange(g)
            },
            onCopyToAll = {
                editDay = null
                onChange(grid.copyDay(d, (0 until 7).filter { it != d }))
            },
        )
    }
}

/** Accessible alternative to painting: one allowed time range for a day. */
@Composable
private fun DayRangeDialog(
    day: Int,
    grid: ScheduleGrid,
    locale: Locale,
    onDismiss: () -> Unit,
    onApply: (IntRange?) -> Unit,
    onCopyToAll: () -> Unit,
) {
    val existing = grid.ranges(day)
    val initial = if (existing.isEmpty()) 8f..20f else existing.first().first.toFloat()..(existing.last().last + 1).toFloat()
    var range by remember { mutableStateOf(initial) }
    val start = range.start.roundToInt()
    val end = range.endInclusive.roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dayName(day, locale, TextStyle.FULL)) },
        text = {
            Column {
                Text(stringResource(R.string.schedule_day_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    "${hourText(start, locale)} – ${hourText(end, locale)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 0f..24f,
                    steps = 23,
                )
                Row {
                    TextButton(onClick = { onApply(null) }) { Text(stringResource(R.string.schedule_none_this_day)) }
                    TextButton(onClick = { onApply(0 until 24) }) { Text(stringResource(R.string.schedule_all_day)) }
                }
                TextButton(onClick = onCopyToAll) { Text(stringResource(R.string.schedule_copy_all)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(if (end > start) start until end else null) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        modifier = Modifier.padding(8.dp),
    )
}
