package io.wrtpilot.app.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.wrtpilot.app.R
import io.wrtpilot.core.domain.ByteUnit
import io.wrtpilot.core.domain.RateUnit
import io.wrtpilot.core.domain.Units
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/*
 * Locale-aware formatting. Numbers follow the app language (Arabic gets
 * Arabic-Indic digits, French a decimal comma); units are translated.
 * Technical identifiers (IP, MAC) are wrapped with [ltr] so they keep their
 * order inside right-to-left text.
 */

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()

fun formatNumber(value: Double, locale: Locale, fractionDigits: Int = Units.fractionDigits(value)): String {
    val nf = NumberFormat.getNumberInstance(locale)
    nf.maximumFractionDigits = fractionDigits
    nf.minimumFractionDigits = 0
    return nf.format(value)
}

fun formatInt(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

fun formatRate(context: Context, bitsPerSecond: Long, locale: Locale): String {
    val s = Units.scaleRate(bitsPerSecond)
    val n = formatNumber(s.value, locale)
    return when (s.unit) {
        RateUnit.BPS -> context.getString(R.string.unit_bps, n)
        RateUnit.KBPS -> context.getString(R.string.unit_kbps, n)
        RateUnit.MBPS -> context.getString(R.string.unit_mbps, n)
        RateUnit.GBPS -> context.getString(R.string.unit_gbps, n)
    }
}

fun formatBytes(context: Context, bytes: Long, locale: Locale): String {
    val s = Units.scaleBytes(bytes)
    val n = formatNumber(s.value, locale)
    return when (s.unit) {
        ByteUnit.B -> context.getString(R.string.unit_b, n)
        ByteUnit.KB -> context.getString(R.string.unit_kb, n)
        ByteUnit.MB -> context.getString(R.string.unit_mb, n)
        ByteUnit.GB -> context.getString(R.string.unit_gb, n)
        ByteUnit.TB -> context.getString(R.string.unit_tb, n)
    }
}

/** "5 Mbit/s" from kbit/s limits. */
fun formatKbps(context: Context, kbps: Int, locale: Locale): String =
    formatRate(context, kbps.toLong() * 1000, locale)

@Composable
fun rateText(bitsPerSecond: Long): String = formatRate(LocalContext.current, bitsPerSecond, currentLocale())

@Composable
fun bytesText(bytes: Long): String = formatBytes(LocalContext.current, bytes, currentLocale())

@Composable
fun kbpsText(kbps: Int): String = formatKbps(LocalContext.current, kbps, currentLocale())

fun clockText(epochSeconds: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSeconds).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

/** Time if today, weekday + time within a week, else a short date. */
fun momentText(epochSeconds: Long, locale: Locale, nowSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val now = Instant.ofEpochSecond(nowSeconds).atZone(zone)
    val time = t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return when {
        t.toLocalDate() == now.toLocalDate() -> time
        kotlin.math.abs(t.toEpochSecond() - now.toEpochSecond()) < 6 * 86_400 ->
            t.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + time
        else -> t.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))
    }
}

fun dateText(epochSeconds: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSeconds).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

/** "just now", "5 minutes ago", "yesterday"… */
fun agoText(context: Context, epochSeconds: Long, nowSeconds: Long): String {
    val d = (nowSeconds - epochSeconds).coerceAtLeast(0)
    val res = context.resources
    return when {
        epochSeconds <= 0 -> context.getString(R.string.time_never)
        d < 60 -> context.getString(R.string.time_just_now)
        d < 3600 -> res.getQuantityString(R.plurals.time_minutes_ago, (d / 60).toInt(), (d / 60).toInt())
        d < 86_400 -> res.getQuantityString(R.plurals.time_hours_ago, (d / 3600).toInt(), (d / 3600).toInt())
        else -> res.getQuantityString(R.plurals.time_days_ago, (d / 86_400).toInt(), (d / 86_400).toInt())
    }
}

/** "1 h 20 min", "15 min" */
fun durationText(context: Context, seconds: Long): String {
    val res = context.resources
    val m = ((seconds + 59) / 60).coerceAtLeast(1)
    val h = m / 60
    val rest = (m % 60).toInt()
    return when {
        h == 0L -> res.getQuantityString(R.plurals.duration_minutes, rest, rest)
        rest == 0 -> res.getQuantityString(R.plurals.duration_hours, h.toInt(), h.toInt())
        else -> context.getString(
            R.string.duration_hours_minutes,
            res.getQuantityString(R.plurals.duration_hours, h.toInt(), h.toInt()),
            res.getQuantityString(R.plurals.duration_minutes, rest, rest),
        )
    }
}

/** Keeps IPs / MACs / host names left-to-right inside RTL text (Unicode isolate). */
fun ltr(text: String): String = "\u2066$text\u2069"

fun nowSeconds(): Long = System.currentTimeMillis() / 1000
