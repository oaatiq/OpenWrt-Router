package io.wrtpilot.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Fake rpcd/uhttpd: records calls and answers from a handler. */
class FakeRouter : Dispatcher() {
    val calls = mutableListOf<Triple<String, String, JsonObject>>()
    var validSession = "s1"
    var nextSession = "s1"
    var password = "secret"
    var handler: (obj: String, method: String, args: JsonObject) -> String = { _, _, _ -> "[0,{\"ok\":true}]" }

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.path != "/ubus") return MockResponse().setResponseCode(404)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val params = body["params"]!!.jsonArray
        val sid = params[0].jsonPrimitive.content
        val obj = params[1].jsonPrimitive.content
        val method = params[2].jsonPrimitive.content
        val args = params[3].jsonObject
        calls += Triple(obj, method, args)
        val id = body["id"]!!.jsonPrimitive.int

        if (obj == "session" && method == "login") {
            val ok = args["password"]?.jsonPrimitive?.content == password
            return reply(id, if (ok) "[0,{\"ubus_rpc_session\":\"$nextSession\",\"timeout\":300}]" else "[6]")
        }
        if (sid != validSession) {
            return MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":-32002,\"message\":\"Access denied\"}}")
        }
        return reply(id, handler(obj, method, args))
    }

    private fun reply(id: Int, result: String) =
        MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}")
}

class WrtPilotApiTest {
    private lateinit var server: MockWebServer
    private val router = FakeRouter()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = router
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun api(password: String = "secret"): WrtPilotApi {
        val ep = RouterEndpoint(server.hostName, server.port, https = false)
        return WrtPilotApi(UbusClient(ep, Credentials("wrtpilot", password), OkHttpClient()))
    }

    @Test
    fun `logs in lazily and decodes clients`() = runTest {
        router.handler = { obj, method, _ ->
            assertEquals("wrtpilot", obj)
            assertEquals("clients", method)
            """[0,{"ok":true,"time":1790000000,"clients":[
                {"mac":"aa:bb:cc:dd:ee:01","name":"Kid laptop","ip":"192.168.1.10","conn":"5G","signal":-51,
                 "online":true,"rx_bps":4200000,"tx_bps":120000,"today_rx":5000000000,"blocked":"","paused":true,
                 "paused_until":-1,"group":"kids","is_self":false,"future_field":{"x":1}}]}]"""
        }
        val list = api().clients()
        assertEquals(listOf("session" to "login", "wrtpilot" to "clients"), router.calls.map { it.first to it.second })
        val c = list.clients.single()
        assertEquals("Kid laptop", c.name)
        assertEquals(5_000_000_000L, c.todayRx)
        assertTrue(c.isWifi)
        assertTrue(c.paused)
        assertEquals(-1L, c.pausedUntil)
        assertFalse(c.hasInternet)
    }

    @Test
    fun `re-login once when the session expired`() = runTest {
        val api = api()
        router.handler = { _, _, _ -> "[0,{\"ok\":true,\"agent_version\":\"0.1.0\"}]" }
        assertEquals("0.1.0", api.status().agentVersion)

        // router restarted: old session is gone, a new one is issued on login
        router.validSession = "s2"
        router.nextSession = "s2"
        assertEquals("0.1.0", api.status().agentVersion)
        assertEquals(2, router.calls.count { it.second == "login" })
    }

    @Test
    fun `wrong password maps to BadCredentials`() = runTest {
        try {
            api(password = "nope").status()
            fail("expected BadCredentials")
        } catch (e: ApiError) {
            assertTrue(e is ApiError.BadCredentials)
        }
    }

    @Test
    fun `agent errors carry their code`() = runTest {
        router.handler = { _, _, _ -> "[0,{\"ok\":false,\"error\":\"self_block\",\"message\":\"no\"}]" }
        try {
            api().block("aa:bb:cc:dd:ee:01", BlockMode.INTERNET)
            fail("expected agent error")
        } catch (e: ApiError.Agent) {
            assertEquals(AgentErrors.SELF_BLOCK, e.code)
        }
    }

    @Test
    fun `missing object means the agent is not installed`() = runTest {
        router.handler = { _, _, _ -> "[4]" }
        try {
            api().status()
            fail()
        } catch (e: ApiError) {
            assertTrue(e is ApiError.AgentMissing)
        }
    }

    @Test
    fun `http 404 when ubus over http is disabled`() = runTest {
        val ep = RouterEndpoint(server.hostName, server.port, https = false)
        val client = UbusClient(ep.copy(), Credentials("u", "p"), OkHttpClient())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        try {
            client.login()
            fail()
        } catch (e: ApiError.Http) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun `argument types match the agent declaration`() = runTest {
        router.handler = { _, method, _ ->
            when (method) {
                "pause" -> "[0,{\"ok\":true,\"paused_until\":-1}]"
                "set_group" -> "[0,{\"ok\":true,\"group\":{\"id\":\"kids\",\"name\":\"Kids\",\"schedule\":[\"mon 07:00-08:00\"]}}]"
                else -> "[0,{\"ok\":true}]"
            }
        }
        val api = api()
        assertEquals(-1L, api.pauseGroup("kids", PauseFor.Indefinitely))
        api.pauseDevice("aa:bb:cc:dd:ee:01", PauseFor.Seconds(900))
        api.setGroup(
            io.wrtpilot.core.network.model.GroupUpdate(
                id = "kids", safesearch = true, schedule = listOf("mon 07:00-08:00"), members = listOf("aa:bb:cc:dd:ee:01")
            )
        )
        api.setDeviceLimit("aa:bb:cc:dd:ee:01", 5000, 0)

        val pauseGroup = router.calls.first { it.second == "pause" }.third
        assertEquals("kids", pauseGroup["group"]!!.jsonPrimitive.content)
        assertEquals("indefinite", pauseGroup["until"]!!.jsonPrimitive.content)

        val pauseDev = router.calls.filter { it.second == "pause" }[1].third
        assertEquals("900", pauseDev["duration_s"].toString())

        val group = router.calls.first { it.second == "set_group" }.third
        assertEquals("true", group["safesearch"].toString())
        assertTrue(group["schedule"] is JsonArray)
        assertFalse(group.containsKey("name"))

        val limit = router.calls.first { it.second == "set_limit" }.third
        assertEquals("5000", limit["dl_kbps"].toString())
    }

    @Test
    fun `history points`() = runTest {
        router.handler = { _, _, args ->
            assertEquals("day", args["resolution"]!!.jsonPrimitive.content)
            "[0,{\"ok\":true,\"resolution\":\"day\",\"mac\":\"\",\"series\":[[1790000000,100,20],[1790086400,300,40]]}]"
        }
        val h = api().history("", Resolution.DAY, 0)
        assertEquals(2, h.points.size)
        assertEquals(300L, h.points[1].rx)
    }

    @Test
    fun `endpoint parsing`() {
        assertEquals(RouterEndpoint("192.168.1.1", 80, false), RouterEndpoint.parse("192.168.1.1", false))
        assertEquals(RouterEndpoint("router.lan", 8443, true), RouterEndpoint.parse("https://router.lan:8443/cgi-bin", false))
        assertEquals(RouterEndpoint("fd00::1", 443, true), RouterEndpoint.parse("[fd00::1]", true))
        assertEquals(RouterEndpoint("100.64.0.5", 80, false), RouterEndpoint.parse("http://100.64.0.5", true))
        assertEquals(null, RouterEndpoint.parse("bad host", false))
        assertEquals(null, RouterEndpoint.parse("host:99999", false))
        assertEquals("http://[fd00::1]:8080", RouterEndpoint("fd00::1", 8080, false).baseUrl)
        assertEquals("https://router.lan", RouterEndpoint("router.lan", 443, true).baseUrl)
    }

    @Test
    fun `fingerprint helpers`() {
        assertEquals("ab:cd:01", Tls.formatFingerprint("ABCD01"))
        assertEquals("abcd01", Tls.normalizeFingerprint("AB:CD:01"))
    }
}
