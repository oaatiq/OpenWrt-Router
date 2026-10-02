package io.wrtpilot.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Data returned by the router's "wrtpilot" ubus object (see docs/API.md).
 * Rates are bits per second, byte counters are bytes, times are Unix seconds.
 * Every field has a default so older/newer agents stay compatible.
 */

@Serializable
data class Status(
    @SerialName("agent_version") val agentVersion: String = "",
    @SerialName("openwrt_version") val openwrtVersion: String = "",
    @SerialName("openwrt_description") val openwrtDescription: String = "",
    val model: String = "",
    val hostname: String = "",
    val timezone: String = "",
    val uptime: Long = 0,
    val time: Long = 0,
    val enabled: Boolean = true,
    val wan: Wan = Wan(),
    val offload: Offload = Offload(),
    @SerialName("offload_warning") val offloadWarning: Boolean = false,
    val capabilities: Capabilities = Capabilities(),
    @SerialName("coarse_limiting") val coarseLimiting: Boolean = false,
    val collector: Collector = Collector(),
    @SerialName("self_macs") val selfMacs: List<String> = emptyList(),
)

@Serializable
data class Wan(
    val up: Boolean = false,
    @SerialName("interface") val iface: String? = null,
    val proto: String? = null,
    val device: String? = null,
    val uptime: Long = 0,
    val ipv4: String? = null,
    val ipv6: String? = null,
)

@Serializable
data class Offload(
    val software: Boolean = false,
    val hardware: Boolean = false,
)

@Serializable
data class Capabilities(
    val tc: Boolean = false,
    val ifb: Boolean = false,
    val sqm: Boolean = false,
    val cake: Boolean = false,
    val nftset: Boolean = false,
    val hostapd: Boolean = false,
    val ip: Boolean = false,
)

@Serializable
data class Collector(
    val running: Boolean = false,
    val interval: Int = 2,
)

@Serializable
data class Client(
    val mac: String,
    val name: String = "",
    @SerialName("custom_name") val customName: String = "",
    val hostname: String = "",
    val ip: String = "",
    val ipv6: List<String> = emptyList(),
    /** "2.4G", "5G", "6G", "wifi", "lan" or "unknown" */
    val conn: String = "unknown",
    val ssid: String = "",
    /** dBm, 0 when unknown */
    val signal: Int = 0,
    val online: Boolean = false,
    @SerialName("first_seen") val firstSeen: Long = 0,
    @SerialName("last_seen") val lastSeen: Long = 0,
    @SerialName("rx_bps") val rxBps: Long = 0,
    @SerialName("tx_bps") val txBps: Long = 0,
    @SerialName("today_rx") val todayRx: Long = 0,
    @SerialName("today_tx") val todayTx: Long = 0,
    val group: String = "",
    /** "", "internet" or "wifi" */
    val blocked: String = "",
    @SerialName("blocked_until") val blockedUntil: Long = 0,
    val paused: Boolean = false,
    /** 0 = not paused, -1 = indefinitely, otherwise Unix time */
    @SerialName("paused_until") val pausedUntil: Long = 0,
    @SerialName("paused_by") val pausedBy: String = "",
    @SerialName("schedule_blocked") val scheduleBlocked: Boolean = false,
    @SerialName("dl_limit_kbps") val dlLimitKbps: Int = 0,
    @SerialName("ul_limit_kbps") val ulLimitKbps: Int = 0,
    @SerialName("daily_quota_mb") val dailyQuotaMb: Int = 0,
    @SerialName("quota_action") val quotaAction: String = "notify",
    @SerialName("quota_exceeded") val quotaExceeded: Boolean = false,
    @SerialName("is_self") val isSelf: Boolean = false,
    @SerialName("random_mac") val randomMac: Boolean = false,
) {
    val isWifi: Boolean get() = conn == "2.4G" || conn == "5G" || conn == "6G" || conn == "wifi"
    val isBlocked: Boolean get() = blocked.isNotEmpty()
    val hasInternet: Boolean get() = !isBlocked && !paused && !scheduleBlocked && !quotaExceeded
}

@Serializable
data class ClientList(
    val time: Long = 0,
    val clients: List<Client> = emptyList(),
)

@Serializable
data class Series(
    val rx: List<Long> = emptyList(),
    val tx: List<Long> = emptyList(),
)

@Serializable
data class LiveData(
    val interval: Int = 2,
    val ts: List<Long> = emptyList(),
    val total: Series = Series(),
    val devices: Map<String, Series> = emptyMap(),
)

/** One history bucket: start time, received (download) bytes, sent (upload) bytes. */
data class UsagePoint(val ts: Long, val rx: Long, val tx: Long)

@Serializable
data class History(
    val resolution: String = "minute",
    val mac: String = "",
    val series: List<List<Long>> = emptyList(),
) {
    val points: List<UsagePoint>
        get() = series.filter { it.size >= 3 }.map { UsagePoint(it[0], it[1], it[2]) }
}

@Serializable
data class DeviceHistory(
    val resolution: String = "day",
    val devices: Map<String, List<List<Long>>> = emptyMap(),
) {
    fun points(mac: String): List<UsagePoint> =
        devices[mac].orEmpty().filter { it.size >= 3 }.map { UsagePoint(it[0], it[1], it[2]) }
}

@Serializable
data class Group(
    val id: String,
    val name: String = id,
    val members: List<String> = emptyList(),
    @SerialName("dns_filter") val dnsFilter: String = "off",
    @SerialName("dns_custom") val dnsCustom: List<String> = emptyList(),
    val safesearch: Boolean = false,
    val blocklist: List<String> = emptyList(),
    val schedule: List<String> = emptyList(),
    @SerialName("schedule_enabled") val scheduleEnabled: Boolean = true,
    @SerialName("dl_limit_kbps") val dlLimitKbps: Int = 0,
    @SerialName("ul_limit_kbps") val ulLimitKbps: Int = 0,
    @SerialName("paused_until") val pausedUntil: Long = 0,
    @SerialName("allowed_now") val allowedNow: Boolean = true,
    @SerialName("next_change") val nextChange: Long = 0,
) {
    val paused: Boolean get() = pausedUntil != 0L
    val hasSchedule: Boolean get() = scheduleEnabled && schedule.isNotEmpty()
}

@Serializable
data class GroupList(
    val groups: List<Group> = emptyList(),
    @SerialName("dns_filters") val dnsFilters: List<String> = emptyList(),
)

@Serializable
data class GroupResult(val group: Group)

/** Fields to change on a group; null = leave unchanged. */
data class GroupUpdate(
    val id: String? = null,
    val name: String? = null,
    val dnsFilter: String? = null,
    val dnsCustom: List<String>? = null,
    val safesearch: Boolean? = null,
    val blocklist: List<String>? = null,
    val schedule: List<String>? = null,
    val scheduleEnabled: Boolean? = null,
    val members: List<String>? = null,
    val dlKbps: Int? = null,
    val ulKbps: Int? = null,
)

@Serializable
data class QosConfig(
    val available: Boolean = false,
    val enabled: Boolean = false,
    @SerialName("dl_kbps") val dlKbps: Int = 0,
    @SerialName("ul_kbps") val ulKbps: Int = 0,
    val preset: String = "default",
    @SerialName("interface") val iface: String? = null,
    val presets: List<String> = listOf("default", "gaming", "streaming"),
)

@Serializable
data class Event(
    val id: Long,
    val ts: Long,
    /** "new_device" or "quota_exceeded" */
    val type: String,
    val mac: String? = null,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class EventPage(
    @SerialName("last_id") val lastId: Long = 0,
    /** true when the router's event ids restarted (factory reset) */
    val reset: Boolean = false,
    val events: List<Event> = emptyList(),
)

@Serializable
data class DeviceSettings(
    val mac: String,
    @SerialName("custom_name") val customName: String = "",
    val group: String = "",
    @SerialName("daily_quota_mb") val dailyQuotaMb: Int = 0,
    @SerialName("quota_action") val quotaAction: String = "",
)

@Serializable
data class DeviceResult(val device: DeviceSettings)

@Serializable
data class BlockResult(
    val mac: String = "",
    val mode: String = "",
    @SerialName("blocked_until") val blockedUntil: Long = 0,
)

@Serializable
data class PauseResult(
    @SerialName("paused_until") val pausedUntil: Long = 0,
)

@Serializable
data class LimitResult(
    val coarse: Boolean = false,
)

@Serializable
data class OffloadResult(
    val offload: Offload = Offload(),
)
