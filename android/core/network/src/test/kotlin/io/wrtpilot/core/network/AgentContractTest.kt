package io.wrtpilot.core.network

import io.wrtpilot.core.network.model.BlockResult
import io.wrtpilot.core.network.model.ClientList
import io.wrtpilot.core.network.model.DeviceHistory
import io.wrtpilot.core.network.model.DeviceResult
import io.wrtpilot.core.network.model.EventPage
import io.wrtpilot.core.network.model.GroupList
import io.wrtpilot.core.network.model.GroupResult
import io.wrtpilot.core.network.model.History
import io.wrtpilot.core.network.model.LimitResult
import io.wrtpilot.core.network.model.LiveData
import io.wrtpilot.core.network.model.OffloadResult
import io.wrtpilot.core.network.model.PauseResult
import io.wrtpilot.core.network.model.QosConfig
import io.wrtpilot.core.network.model.Status
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract between the app and the router agent: the fixtures are real
 * replies recorded by the agent's integration test
 * (WRTPILOT_FIXTURES=... router/tests/integration/run.sh).
 *
 * Every reply must decode, and every field the app's models read must be
 * present in the reply: the decoder ignores unknown keys and falls back to
 * defaults, so a renamed field would otherwise go unnoticed.
 */
class AgentContractTest {

    /** Fields the agent legitimately leaves out. */
    private val optional = setOf(
        "status.wan.interface", "status.wan.proto", "status.wan.device", "status.wan.uptime",
        "events.events.mac",
        "qos_get.interface",
    )

    private fun fixture(name: String): JsonObject {
        val stream = javaClass.getResourceAsStream("/fixtures/$name.json")
            ?: throw AssertionError("missing fixture $name.json")
        return WrtJson.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun <T> check(name: String, serializer: KSerializer<T>): T {
        val raw = fixture(name)
        assertEquals("$name: ok", "true", raw["ok"]?.toString())
        val value = WrtJson.decodeFromJsonElement(serializer, raw)
        val expected = WrtJson.encodeToJsonElement(serializer, value)
        val missing = mutableListOf<String>()
        missingKeys(name, expected, raw, missing)
        assertTrue("$name: fields the app reads but the agent does not send: $missing", missing.isEmpty())
        return value
    }

    private fun missingKeys(path: String, expected: JsonElement, actual: JsonElement?, out: MutableList<String>) {
        when (expected) {
            is JsonObject -> {
                val obj = actual as? JsonObject ?: return
                // maps keyed by MAC are compared entry by entry
                for ((k, v) in expected) {
                    val p = "$path.$k"
                    if (k !in obj) {
                        if (p !in optional && !isMacKey(k)) out += p
                    } else {
                        missingKeys(p, v, obj[k], out)
                    }
                }
            }
            is JsonArray -> {
                val arr = actual as? JsonArray ?: return
                if (expected.isNotEmpty() && arr.isNotEmpty()) missingKeys(path, expected[0], arr[0], out)
            }
            else -> Unit
        }
    }

    private fun isMacKey(k: String) = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(k)

    @Test
    fun status() {
        val s = check("status", Status.serializer())
        assertTrue(s.agentVersion.isNotEmpty())
        assertTrue(s.collector.running)
        assertTrue(s.selfMacs.isNotEmpty())
    }

    @Test
    fun clients() {
        val c = check("clients", ClientList.serializer())
        assertTrue(c.clients.isNotEmpty())
        assertTrue(c.clients.all { it.mac.length == 17 })
        assertTrue(c.clients.any { it.isSelf })
    }

    @Test
    fun liveAndHistory() {
        val live = check("live", LiveData.serializer())
        assertTrue(live.ts.isNotEmpty())
        assertEquals(live.ts.size, live.total.rx.size)
        assertTrue(live.devices.values.all { it.rx.size == live.ts.size })
        check("live_totals", LiveData.serializer())

        assertTrue(check("history_minute", History.serializer()).points.isNotEmpty())
        assertTrue(check("history_day", History.serializer()).points.isNotEmpty())
        val all = check("history_all", DeviceHistory.serializer())
        assertTrue(all.devices.isNotEmpty())
        assertTrue(all.points(all.devices.keys.first()).isNotEmpty())
    }

    @Test
    fun groups() {
        val list = check("groups", GroupList.serializer())
        assertTrue(list.groups.isNotEmpty())
        assertTrue("custom" in list.dnsFilters)
        check("set_group", GroupResult.serializer())
    }

    @Test
    fun actions() {
        check("set_device", DeviceResult.serializer())
        assertTrue(check("block", BlockResult.serializer()).mode.isNotEmpty())
        check("set_limit", LimitResult.serializer())
        assertTrue(check("pause", PauseResult.serializer()).pausedUntil > 0)
        assertEquals(-1L, check("pause_indefinite", PauseResult.serializer()).pausedUntil)
        assertTrue(check("pause_tomorrow", PauseResult.serializer()).pausedUntil > 0)
        check("set_offload", OffloadResult.serializer())
        for (name in listOf("unblock", "resume", "forget_device", "delete_group")) {
            assertEquals("$name: ok", "true", fixture(name)["ok"]?.toString())
        }
    }

    @Test
    fun qosAndEvents() {
        check("qos_get", QosConfig.serializer())
        val events = check("events", EventPage.serializer())
        assertTrue(events.events.any { it.type == "new_device" && it.mac != null })
    }
}
