package io.wrtpilot.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AgentInstallerTest {
    private lateinit var server: MockWebServer
    private val router = FakeRouter()

    /** files on the fake router; missing ones answer "not found" like rpcd */
    private val files = mutableMapOf<String, String>()
    private val commands = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = router
        server.start()
        router.handler = { obj, method, args ->
            when {
                obj == "file" && method == "exec" -> {
                    commands += args["params"]!!.jsonArray[1].jsonPrimitive.content
                    "[0,{\"code\":0}]"
                }
                obj == "file" && method == "read" -> {
                    val data = files[args["path"]!!.jsonPrimitive.content]
                    if (data == null) "[4]" else "[0,{\"data\":${kotlinx.serialization.json.JsonPrimitive(data)}}]"
                }
                else -> "[3]"
            }
        }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun installer(): AgentInstaller {
        val ep = RouterEndpoint(server.hostName, server.port, https = false)
        return AgentInstaller(UbusClient(ep, Credentials("root", "secret"), OkHttpClient()), pollMillis = 1, timeoutMillis = 5_000)
    }

    @Test
    fun `runs the published installer in the background and returns the wrtpilot login`() = runTest {
        val inst = installer()
        inst.start()
        val script = commands.single()
        assertTrue(script.contains(AgentInstaller.INSTALLER_URL))
        assertTrue("must not block rpcd", script.trimEnd().endsWith("&"))

        files["/tmp/wrtpilot-install.log"] = "Downloading ...\nInstalling ...\n\nUser:     wrtpilot\n"
        files["/tmp/wrtpilot-install.rc"] = "0\n"
        files["/etc/wrtpilot/initial_password"] = "Abc123def456ghij\n"
        val progress = mutableListOf<String>()
        val login = inst.awaitLogin { progress += it }

        assertEquals(Credentials("wrtpilot", "Abc123def456ghij"), login)
        assertEquals("Downloading ...\nInstalling ...\nUser:     wrtpilot", progress.last())
    }

    @Test
    fun `sets a new password when the initial one is gone`() = runTest {
        val inst = installer()
        inst.start()
        files["/tmp/wrtpilot-install.rc"] = "0"
        val login = inst.awaitLogin { }
        assertEquals("wrtpilot", login.username)
        assertEquals(16, login.password.length)
        assertTrue(commands.last().contains("wrtpilot passwd '${login.password}'"))
    }

    @Test
    fun `reports a failed installation with its log`() = runTest {
        val inst = installer()
        inst.start()
        files["/tmp/wrtpilot-install.log"] = "Error: download failed\n"
        files["/tmp/wrtpilot-install.rc"] = "1"
        try {
            inst.awaitLogin { }
            fail("expected Failed")
        } catch (e: AgentInstaller.Failed) {
            assertEquals(1, e.exitCode)
            assertTrue(e.log.contains("download failed"))
        }
    }

    @Test
    fun `a login without command rights cannot install`() = runTest {
        router.handler = { _, _, _ -> "[6]" }
        try {
            installer().start()
            fail("expected NotPermitted")
        } catch (e: AgentInstaller.NotPermitted) {
            // expected
        }
    }
}
