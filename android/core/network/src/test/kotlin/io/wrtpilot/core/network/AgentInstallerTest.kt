package io.wrtpilot.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Runs the installer against a fake router that enforces the file ACLs a
 * stock OpenWrt with LuCI gives the root login (no shell; crontab, cron
 * reload and the system log; the package's "wrtpilot-setup" group once
 * installed), and plays the one-time cron job as polls go by.
 */
class AgentInstallerTest {
    private lateinit var server: MockWebServer
    private val router = FakeRouter()

    private val files = mutableMapOf<String, String>()
    private val syslog = StringBuilder()
    private val execs = mutableListOf<String>()

    /** LuCI 24.10+ reads the log with syslog-wrapper, 23.05 with logread; null: no log page */
    private var logCommand: String? = "/usr/libexec/syslog-wrapper"
    private var luci = true
    private var packageInstalled = false

    /** what the cron job does on the n-th look at the router */
    private var script: (poll: Int) -> Unit = {}
    private var polls = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = router
        server.start()
        router.handler = ::handle
    }

    @After
    fun tearDown() = server.shutdown()

    private fun readable(path: String) =
        (luci && path == CRONTAB) || (packageInstalled && path in SETUP_READ)

    private fun handle(obj: String, method: String, args: JsonObject): String {
        if (obj != "file") return "[3]"
        val path = args["path"]?.jsonPrimitive?.content
        return when (method) {
            "read" -> when {
                !readable(path!!) -> "[6]"
                path !in files -> "[4]"
                else -> "[0,{\"data\":${JsonPrimitive(files[path])}}]"
            }
            "write" -> if (luci && path == CRONTAB) {
                files[path] = args["data"]!!.jsonPrimitive.content
                "[0]"
            } else "[6]"
            "remove" -> if (luci && path == CRONTAB) {
                files.remove(path)
                "[0]"
            } else "[6]"
            "exec" -> {
                val cmd = (listOf(args["command"]!!.jsonPrimitive.content) +
                    args["params"]!!.jsonArray.map { it.jsonPrimitive.content }).joinToString(" ")
                execs += cmd
                when {
                    luci && cmd == "/etc/init.d/cron reload" -> "[0,{\"code\":0}]"
                    cmd.startsWith("/usr/libexec/syslog-wrapper") && logCommand != "/usr/libexec/syslog-wrapper" -> "[4]"
                    logCommand != null && cmd == logCommand -> {
                        script(++polls)
                        "[0,{\"code\":0,\"stdout\":${JsonPrimitive(syslog.toString())}}]"
                    }
                    packageInstalled && cmd.startsWith("/usr/sbin/wrtpilot passwd ") -> "[0,{\"code\":0}]"
                    else -> "[6]"
                }
            }
            else -> "[3]"
        }
    }

    private fun log(msg: String) {
        syslog.append("Fri Oct  2 16:00:01 2026 user.notice wrtpilot-install: $msg\n")
    }

    /** the job as install.sh --app runs it */
    private fun job(id: String, rc: Int, password: String? = "Abc123def456ghij"): (Int) -> Unit = { poll ->
        when (poll) {
            1 -> {
                files[CRONTAB] = files[CRONTAB].orEmpty().lines().filter { "wrtpilot-app-install" !in it }.joinToString("\n")
                log("started $id")
                log("WrtPilot installer: OpenWrt 24.10.8 (opkg)")
            }
            2 -> log("Installing ...")
            3 -> {
                if (rc == 0) {
                    packageInstalled = true
                    password?.let { files["/etc/wrtpilot/initial_password"] = "$it\n" }
                    log("WrtPilot is installed.")
                } else {
                    log("Error: download failed")
                }
                files["/tmp/wrtpilot-install.rc"] = "$rc $id\n"
                log("finished $id rc=$rc")
            }
        }
    }

    private fun installer(startTimeout: Long = 60_000, timeout: Long = 10_000): AgentInstaller {
        val ep = RouterEndpoint(server.hostName, server.port, https = false)
        val ubus = UbusClient(ep, Credentials("root", "secret"), OkHttpClient())
        return AgentInstaller(ubus, pollMillis = 1, startTimeoutMillis = startTimeout, timeoutMillis = timeout, id = ID)
    }

    @Test
    fun `schedules a one-time job with LuCI's crontab permission`() = runTest {
        files[CRONTAB] = "0 3 * * * /usr/bin/backup.sh\n"
        val inst = installer()
        inst.start()

        val lines = files[CRONTAB]!!.trim().lines()
        assertEquals("0 3 * * * /usr/bin/backup.sh", lines[0])
        assertEquals(inst.cronLine(), lines[1])
        // removes itself before anything else, so a failed download does not repeat every minute
        assertTrue(lines[1].startsWith("* * * * * sed -i /wrtpilot-app-install/d /etc/crontabs/root;"))
        assertTrue(lines[1].contains(AgentInstaller.INSTALLER_URL))
        assertTrue(lines[1].contains("sh /tmp/wrtpilot-install.sh --app $ID||"))
        assertFalse("crond treats # and % specially", lines[1].contains('#') || lines[1].contains('%'))
        assertEquals(listOf("/etc/init.d/cron reload"), execs)
    }

    @Test
    fun `follows the system log and returns the initial wrtpilot login`() = runTest {
        files[CRONTAB] = "0 3 * * * /usr/bin/backup.sh\n"
        script = job(ID, rc = 0)
        val inst = installer()
        inst.start()
        val progress = mutableListOf<AgentInstaller.Progress>()
        val login = inst.awaitLogin { progress += it }

        assertEquals(Credentials("wrtpilot", "Abc123def456ghij"), login)
        assertTrue(progress.first().started)
        assertEquals("WrtPilot installer: OpenWrt 24.10.8 (opkg)\nInstalling ...", progress[1].log)
        assertEquals("0 3 * * * /usr/bin/backup.sh", files[CRONTAB]!!.trim())
    }

    @Test
    fun `reads the log with logread on OpenWrt 23_05 and removes the crontab it created`() = runTest {
        logCommand = "/sbin/logread -e ^"
        script = job(ID, rc = 0)
        val inst = installer()
        inst.start()
        assertEquals(Credentials("wrtpilot", "Abc123def456ghij"), inst.awaitLogin { })
        assertNull("there was no crontab before", files[CRONTAB])
        assertEquals("/etc/init.d/cron reload", execs.last())
    }

    @Test
    fun `sets a new password when the initial one is gone`() = runTest {
        script = job(ID, rc = 0, password = null)
        val inst = installer()
        inst.start()
        val login = inst.awaitLogin { }
        assertEquals("wrtpilot", login.username)
        assertEquals(16, login.password.length)
        assertTrue(execs.contains("/usr/sbin/wrtpilot passwd ${login.password}"))
    }

    @Test
    fun `reports a failed installation with its log`() = runTest {
        script = job(ID, rc = 1)
        val inst = installer()
        inst.start()
        try {
            inst.awaitLogin { }
            fail("expected Failed")
        } catch (e: AgentInstaller.Failed) {
            assertEquals(1, e.exitCode)
            assertTrue(e.log.contains("download failed"))
        }
        assertNull(files[CRONTAB])
    }

    @Test
    fun `fails at once when the router cannot download or start the installer`() = runTest {
        // what the job's fallback branch logs (no internet, or an installer without --app)
        script = { poll ->
            if (poll == 1) {
                files[CRONTAB] = ""
                log("started $ID")
                log(AgentInstaller.START_FAILED)
                log("finished $ID rc=1")
            }
        }
        val inst = installer()
        inst.start()
        try {
            inst.awaitLogin { }
            fail("expected Failed")
        } catch (e: AgentInstaller.Failed) {
            assertEquals(1, e.exitCode)
            assertEquals(AgentInstaller.START_FAILED, e.log)
        }
        assertEquals(1, polls)
    }

    @Test
    fun `without the log page it sees the job start and reads the result file`() = runTest {
        logCommand = null
        val inst = installer()
        inst.start()
        // the job removed itself and finished
        files[CRONTAB] = ""
        packageInstalled = true
        files["/etc/wrtpilot/initial_password"] = "Abc123def456ghij\n"
        files["/tmp/wrtpilot-install.rc"] = "0 $ID\n"
        assertEquals(Credentials("wrtpilot", "Abc123def456ghij"), inst.awaitLogin { })
    }

    @Test
    fun `ignores the result of an earlier run`() = runTest {
        logCommand = null
        val inst = installer(timeout = 300)
        inst.start()
        files[CRONTAB] = ""
        packageInstalled = true
        files["/tmp/wrtpilot-install.rc"] = "0 0badc0de\n"
        try {
            inst.awaitLogin { }
            fail("expected Failed")
        } catch (e: AgentInstaller.Failed) {
            assertEquals(-1, e.exitCode) // timed out waiting for this run's result
        }
    }

    @Test
    fun `a login without LuCI's permissions cannot install`() = runTest {
        luci = false
        try {
            installer().start()
            fail("expected NotPermitted")
        } catch (e: AgentInstaller.NotPermitted) {
            // expected
        }
    }

    @Test
    fun `a job that never starts is taken out again`() = runTest {
        files[CRONTAB] = "0 3 * * * /usr/bin/backup.sh\n"
        val inst = installer(startTimeout = 0)
        inst.start()
        try {
            inst.awaitLogin { }
            fail("expected NotStarted")
        } catch (e: AgentInstaller.NotStarted) {
            // expected
        }
        assertEquals("0 3 * * * /usr/bin/backup.sh", files[CRONTAB]!!.trim())
    }

    @Test
    fun `finds the run in the system log`() {
        val log = """
            Fri Oct  2 15:00:01 2026 user.notice wrtpilot-install: started 11111111
            Fri Oct  2 15:00:05 2026 user.notice wrtpilot-install: finished 11111111 rc=1
            Fri Oct  2 16:00:00 2026 cron.err crond[1234]: USER root pid 99 cmd sed -i /wrtpilot-app-install/d
            Fri Oct  2 16:00:01 2026 user.notice wrtpilot-install: started $ID
            Fri Oct  2 16:00:02 2026 daemon.info dnsmasq[1]: read /etc/hosts
            Fri Oct  2 16:00:03 2026 user.notice wrtpilot-install: Installing ...
        """.trimIndent()
        assertNull(AgentInstaller.parseRun(log, "22222222"))
        assertEquals(AgentInstaller.Run("Installing ...", null), AgentInstaller.parseRun(log, ID))
        val done = "$log\nFri Oct  2 16:00:09 2026 user.notice wrtpilot-install: finished $ID rc=0"
        assertEquals(AgentInstaller.Run("Installing ...", 0), AgentInstaller.parseRun(done, ID))
    }

    private companion object {
        const val ID = "a1b2c3d4"
        const val CRONTAB = "/etc/crontabs/root"
        val SETUP_READ = setOf("/etc/wrtpilot/initial_password", "/tmp/wrtpilot-install.log", "/tmp/wrtpilot-install.rc")
    }
}
