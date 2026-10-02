package io.wrtpilot.core.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One exported usage record. */
data class UsageRecord(
    val router: String,
    val device: String,
    val mac: String,
    /** Unix seconds (start of the bucket) */
    val ts: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

/** RFC 4180 CSV export of usage data (ISO dates, plain digits). */
object CsvExport {
    private val header = listOf("router", "device", "mac", "date", "download_bytes", "upload_bytes")

    fun write(records: List<UsageRecord>, zone: ZoneId, daily: Boolean): String {
        val fmt = if (daily) DateTimeFormatter.ISO_LOCAL_DATE else DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val sb = StringBuilder()
        sb.append(header.joinToString(",")).append("\r\n")
        for (r in records) {
            val date = Instant.ofEpochSecond(r.ts).atZone(zone).toLocalDateTime().format(fmt)
            sb.append(escape(r.router)).append(',')
                .append(escape(r.device)).append(',')
                .append(r.mac).append(',')
                .append(date).append(',')
                .append(r.rxBytes).append(',')
                .append(r.txBytes).append("\r\n")
        }
        return sb.toString()
    }

    fun escape(s: String): String {
        // neutralise spreadsheet formulas, quote when needed
        val safe = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
