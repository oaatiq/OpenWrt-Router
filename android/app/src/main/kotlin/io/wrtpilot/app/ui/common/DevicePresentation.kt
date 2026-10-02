package io.wrtpilot.app.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.wrtpilot.app.R
import io.wrtpilot.core.domain.DeviceClassifier
import io.wrtpilot.core.domain.DeviceKind
import io.wrtpilot.core.domain.OuiTable
import io.wrtpilot.core.network.model.Client

/* How a device is presented: name, kind (icon), connection, restriction. */

fun Client.vendor(oui: OuiTable?): String? = oui?.lookup(mac)

fun Client.kind(oui: OuiTable?): DeviceKind =
    DeviceClassifier.classify(customName.ifBlank { null }, hostname.ifBlank { null }, vendor(oui))

fun Client.displayName(context: Context, oui: OuiTable?): String = when {
    name.isNotBlank() -> name
    randomMac -> context.getString(R.string.device_private)
    else -> vendor(oui)?.let { context.getString(R.string.device_by_vendor, it) }
        ?: context.getString(R.string.device_unknown)
}

@Composable
fun Client.displayName(oui: OuiTable?): String = displayName(LocalContext.current, oui)

fun connectionLabel(context: Context, conn: String): String = when (conn) {
    "2.4G" -> context.getString(R.string.conn_wifi_24)
    "5G" -> context.getString(R.string.conn_wifi_5)
    "6G" -> context.getString(R.string.conn_wifi_6)
    "wifi" -> context.getString(R.string.conn_wifi)
    "lan" -> context.getString(R.string.conn_wired)
    else -> context.getString(R.string.conn_unknown)
}

/** Why the device has no internet right now, or null. */
fun Client.restrictionLabel(context: Context, nowSeconds: Long): String? {
    val locale = context.resources.configuration.locales.get(0)
    return when {
        blocked == "wifi" -> context.getString(R.string.state_wifi_blocked)
        blocked == "internet" -> context.getString(R.string.state_blocked)
        paused && pausedUntil == -1L -> context.getString(R.string.state_paused)
        paused -> context.getString(R.string.state_paused_until, momentText(pausedUntil, locale, nowSeconds))
        quotaExceeded -> context.getString(R.string.state_quota)
        scheduleBlocked -> context.getString(R.string.state_bedtime)
        else -> null
    }
}

/** "Wi‑Fi 5 GHz · 192.168.1.10" or "Offline · seen 2 h ago" */
fun Client.subtitle(context: Context, oui: OuiTable?, nowSeconds: Long): String {
    val parts = mutableListOf<String>()
    if (online) {
        parts += connectionLabel(context, conn)
        if (ip.isNotBlank()) parts += ltr(ip)
    } else {
        parts += context.getString(R.string.offline)
        if (lastSeen > 0) parts += context.getString(R.string.seen_ago, agoText(context, lastSeen, nowSeconds))
    }
    if (name.isNotBlank()) vendor(oui)?.let { parts += it }
    return parts.joinToString(" · ")
}
