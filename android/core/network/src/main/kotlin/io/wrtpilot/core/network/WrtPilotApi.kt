package io.wrtpilot.core.network

import io.wrtpilot.core.network.model.BlockResult
import io.wrtpilot.core.network.model.ClientList
import io.wrtpilot.core.network.model.DeviceResult
import io.wrtpilot.core.network.model.DeviceHistory
import io.wrtpilot.core.network.model.DeviceSettings
import io.wrtpilot.core.network.model.EventPage
import io.wrtpilot.core.network.model.Group
import io.wrtpilot.core.network.model.GroupList
import io.wrtpilot.core.network.model.GroupResult
import io.wrtpilot.core.network.model.GroupUpdate
import io.wrtpilot.core.network.model.History
import io.wrtpilot.core.network.model.LimitResult
import io.wrtpilot.core.network.model.LiveData
import io.wrtpilot.core.network.model.Offload
import io.wrtpilot.core.network.model.OffloadResult
import io.wrtpilot.core.network.model.PauseResult
import io.wrtpilot.core.network.model.QosConfig
import io.wrtpilot.core.network.model.Status
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** Block modes understood by the agent. */
enum class BlockMode(val wire: String) { INTERNET("internet"), WIFI("wifi") }

/** History bucket sizes. */
enum class Resolution(val wire: String) { MINUTE("minute"), HOUR("hour"), DAY("day") }

/** How long to pause. */
sealed interface PauseFor {
    data class Seconds(val seconds: Int) : PauseFor
    data object UntilTomorrow : PauseFor
    data object Indefinitely : PauseFor
}

/**
 * Typed access to the router's `wrtpilot` ubus object. Argument types must
 * match the agent's declaration exactly (rpcd rejects e.g. 1 for a boolean).
 */
class WrtPilotApi(
    val ubus: UbusClient,
    private val json: Json = WrtJson,
) {
    suspend fun status(): Status = get("status")

    suspend fun clients(): ClientList = get("clients")

    /** Recent rates; [devices] false returns only the router totals. */
    suspend fun live(macs: List<String> = emptyList(), samples: Int = 150, devices: Boolean = true): LiveData =
        get("live") {
            put("macs", JsonArray(macs.map(::JsonPrimitive)))
            put("samples", samples)
            put("devices", devices)
        }

    /** [mac] empty = all devices summed. [since] Unix seconds. */
    suspend fun history(mac: String, resolution: Resolution, since: Long): History =
        get("history") {
            put("mac", mac)
            put("resolution", resolution.wire)
            put("since", since.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }

    /** Per-device series in one call (mac "*"). */
    suspend fun historyAll(resolution: Resolution, since: Long): DeviceHistory =
        get("history") {
            put("mac", "*")
            put("resolution", resolution.wire)
            put("since", since.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }

    suspend fun setDevice(
        mac: String,
        name: String? = null,
        group: String? = null,
        dailyQuotaMb: Int? = null,
        quotaAction: String? = null,
    ): DeviceSettings = get<DeviceResult>("set_device") {
        put("mac", mac)
        name?.let { put("name", it) }
        group?.let { put("group", it) }
        dailyQuotaMb?.let { put("daily_quota_mb", it) }
        quotaAction?.let { put("quota_action", it) }
    }.device

    suspend fun forgetDevice(mac: String) {
        exec("forget_device") { put("mac", mac) }
    }

    /** [durationS] 0 = until unblocked. */
    suspend fun block(mac: String, mode: BlockMode, durationS: Int = 0): BlockResult =
        get("block") {
            put("mac", mac)
            put("mode", mode.wire)
            if (durationS > 0) put("duration_s", durationS)
        }

    suspend fun unblock(mac: String) {
        exec("unblock") { put("mac", mac) }
    }

    /** 0 = no limit. Returns true when the router can only police (coarse limiting). */
    suspend fun setDeviceLimit(mac: String, dlKbps: Int, ulKbps: Int): Boolean =
        get<LimitResult>("set_limit") {
            put("mac", mac)
            put("dl_kbps", dlKbps)
            put("ul_kbps", ulKbps)
        }.coarse

    suspend fun setGroupLimit(group: String, dlKbps: Int, ulKbps: Int): Boolean =
        get<LimitResult>("set_limit") {
            put("group", group)
            put("dl_kbps", dlKbps)
            put("ul_kbps", ulKbps)
        }.coarse

    suspend fun groups(): GroupList = get("groups")

    suspend fun setGroup(update: GroupUpdate): Group = get<GroupResult>("set_group") {
        update.id?.let { put("id", it) }
        update.name?.let { put("name", it) }
        update.dnsFilter?.let { put("dns_filter", it) }
        update.dnsCustom?.let { list -> put("dns_custom", JsonArray(list.map(::JsonPrimitive))) }
        update.safesearch?.let { put("safesearch", it) }
        update.blocklist?.let { list -> put("blocklist", JsonArray(list.map(::JsonPrimitive))) }
        update.schedule?.let { list -> put("schedule", JsonArray(list.map(::JsonPrimitive))) }
        update.scheduleEnabled?.let { put("schedule_enabled", it) }
        update.members?.let { list -> put("members", JsonArray(list.map(::JsonPrimitive))) }
        update.dlKbps?.let { put("dl_kbps", it) }
        update.ulKbps?.let { put("ul_kbps", it) }
    }.group

    suspend fun deleteGroup(id: String) {
        exec("delete_group") { put("id", id) }
    }

    suspend fun pauseDevice(mac: String, duration: PauseFor): Long =
        pause(duration) { put("mac", mac) }

    suspend fun pauseGroup(group: String, duration: PauseFor): Long =
        pause(duration) { put("group", group) }

    suspend fun resumeDevice(mac: String) {
        exec("resume") { put("mac", mac) }
    }

    suspend fun resumeGroup(group: String) {
        exec("resume") { put("group", group) }
    }

    suspend fun qos(): QosConfig = get("qos_get")

    suspend fun setQos(enabled: Boolean, dlKbps: Int, ulKbps: Int, preset: String): QosConfig =
        get("qos_set") {
            put("enabled", enabled)
            put("dl_kbps", dlKbps)
            put("ul_kbps", ulKbps)
            put("preset", preset)
        }

    suspend fun setOffload(software: Boolean, hardware: Boolean): Offload =
        get<OffloadResult>("set_offload") {
            put("software", software)
            put("hardware", hardware)
        }.offload

    suspend fun events(sinceId: Long): EventPage =
        get("events") { put("since_id", sinceId.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }

    suspend fun apply() {
        exec("apply")
    }

    // ------------------------------------------------------------------

    private suspend fun pause(duration: PauseFor, target: JsonObjectBuilder.() -> Unit): Long =
        get<PauseResult>("pause") {
            target()
            when (duration) {
                is PauseFor.Seconds -> put("duration_s", duration.seconds)
                PauseFor.UntilTomorrow -> put("until", "tomorrow")
                PauseFor.Indefinitely -> put("until", "indefinite")
            }
        }.pausedUntil

    private suspend fun exec(method: String, args: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val res = ubus.call(OBJECT, method, buildJsonObject(args))
        checkOk(res)
        return res
    }

    private suspend inline fun <reified T> get(method: String, noinline args: JsonObjectBuilder.() -> Unit = {}): T =
        decode(exec(method, args), serializer())

    private fun <T> decode(obj: JsonObject, strategy: DeserializationStrategy<T>): T =
        try {
            json.decodeFromJsonElement(strategy, obj)
        } catch (e: Exception) {
            throw ApiError.Protocol("unexpected reply: ${e.message}", e)
        }

    private fun checkOk(res: JsonObject) {
        val ok = (res["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok == false) {
            throw ApiError.Agent(
                res["error"]?.jsonPrimitive?.contentOrNull ?: AgentErrors.INTERNAL,
                res["message"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    companion object {
        const val OBJECT = "wrtpilot"
    }
}
