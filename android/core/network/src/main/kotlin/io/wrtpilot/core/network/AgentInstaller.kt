package io.wrtpilot.core.network

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Installs the WrtPilot package on a router from the app, through the
 * router's own API with the root login.
 *
 * The API gives no shell, not even to root: rpcd only runs the commands its
 * ACLs list. So the app uses what LuCI's own pages allow the root login:
 * System > Scheduled Tasks (edit root's crontab, reload cron) to run the
 * published installer once (`install.sh --app <id>`, the same installer as
 * over SSH; the job removes itself), and Status > System Log to follow it.
 * Then it reads the login of the restricted "wrtpilot" user (the package's
 * "wrtpilot-setup" ACL lets root do that), so the root password does not have
 * to be kept.
 */
class AgentInstaller(
    private val ubus: UbusClient,
    private val installerUrl: String = INSTALLER_URL,
    private val pollMillis: Long = 3_000,
    private val startTimeoutMillis: Long = 150_000,
    private val timeoutMillis: Long = 8 * 60_000,
    private val id: String = randomString(8, "0123456789abcdef"),
) {
    /** The login cannot do this (not root, or a router without LuCI). */
    class NotPermitted : Exception("installing needs the router's root login and LuCI")

    /** The router never started the job (cron not running). */
    class NotStarted : Exception("the router did not start the installation")

    /** The installer ran and failed (or did not finish in time); [log] is its output. */
    class Failed(val exitCode: Int, val log: String) : Exception("installer exited with $exitCode")

    /** [started]: the router runs the installer; [log]: its last lines. */
    data class Progress(val started: Boolean, val log: String)

    /** root's crontab before [start] (null: there was none). */
    private var originalCrontab: String? = null

    /** Schedules the installer on the router; returns at once. */
    suspend fun start() {
        try {
            originalCrontab = readOrNull(CRONTAB)
            val kept = originalCrontab.orEmpty().lines().filter { it.isNotBlank() && MARKER !in it }
            write(CRONTAB, (kept + cronLine()).joinToString("\n", postfix = "\n"))
            exec("/etc/init.d/cron", "reload")
        } catch (e: ApiError) {
            throw if (e.isPermission()) NotPermitted() else e
        }
    }

    /**
     * The one-time job: removes itself first, so it runs once even when the
     * download fails. `install.sh --app` reports to the system log itself; when
     * it cannot be downloaded or does not run (an older installer), the job
     * reports the failure so the app does not wait for nothing.
     */
    fun cronLine(): String =
        "* * * * * sed -i /$MARKER/d $CRONTAB;wget -qO $SCRIPT '$installerUrl'&&sh $SCRIPT --app $id||" +
            "{ L='logger -t wrtpilot-install';\$L started $id;\$L $START_FAILED;\$L finished $id rc=1;}"

    /**
     * Waits for the installer to finish, reporting its progress; returns the
     * login to use in the app (user "wrtpilot").
     */
    suspend fun awaitLogin(onProgress: (Progress) -> Unit): Credentials {
        val began = System.nanoTime()
        var started = false
        var lastLog = ""
        var polls = 0
        while (true) {
            delay(pollMillis)
            polls++
            val elapsed = (System.nanoTime() - began) / 1_000_000
            // the installer restarts rpcd (sessions are lost): ride over short outages
            val syslog = runCatching { readSyslog() }.getOrNull()
            val run = syslog?.let { parseRun(it, id) }
            if (run != null) {
                started = true
                lastLog = run.log
            } else if (!started && polls % 3 == 0) {
                // without the system log: the job removes its line when it starts
                runCatching { readOrNull(CRONTAB) }.getOrNull()?.let { started = MARKER !in it }
            }
            onProgress(Progress(started, tail(lastLog)))

            val rc = run?.exitCode ?: if (started && (syslog == null || polls % 4 == 0)) resultFile() else null
            if (rc != null) {
                if (rc != 0) {
                    cleanUp()
                    throw Failed(rc, lastLog.ifEmpty { runCatching { readOrNull(LOG) }.getOrNull().orEmpty() })
                }
                val login = Credentials(AGENT_USER, agentPassword())
                cleanUp()
                return login
            }
            if (!started && elapsed > startTimeoutMillis) {
                cleanUp()
                throw NotStarted()
            }
            if (elapsed > timeoutMillis) {
                cleanUp()
                throw Failed(-1, lastLog)
            }
        }
    }

    /** The exit code left by this run's installer, readable once the package (and its ACL) is in. */
    private suspend fun resultFile(): Int? = runCatching {
        ubus.login(force = true) // ACLs are granted at login: a fresh session sees the package's
        val (code, runId) = readOrNull(RC)?.trim()?.split(' ').orEmpty() + listOf("", "")
        if (runId == id) code.toIntOrNull() else null
    }.getOrNull()

    /** The initial password, or a new one when it was changed (then unknown). */
    private suspend fun agentPassword(): String {
        ubus.login(force = true)
        readOrNull(INITIAL_PASSWORD)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val pw = randomString(16, PASSWORD_ALPHABET)
        val res = exec(AGENT_CLI, "passwd", pw)
        if (res["code"]?.jsonPrimitive?.intOrNull != 0) throw Failed(-1, res["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty())
        return pw
    }

    /** Leaves root's crontab as it was (the job normally removed its line already). */
    private suspend fun cleanUp() {
        runCatching {
            val now = readOrNull(CRONTAB) ?: return
            val kept = now.lines().filter { it.isNotBlank() && MARKER !in it }
            when {
                kept.isEmpty() && originalCrontab == null -> ubus.call("file", "remove", buildJsonObject { put("path", CRONTAB) })
                MARKER in now -> write(CRONTAB, kept.joinToString("\n", postfix = "\n"))
                else -> return
            }
            exec("/etc/init.d/cron", "reload")
        }
    }

    /** The command that reads the system log on this router, once found. */
    private var logCommand: List<String>? = null

    /** The system log as LuCI's log page reads it (24.10+, then 23.05). */
    private suspend fun readSyslog(): String {
        logCommand?.let { return exec(it.first(), *it.drop(1).toTypedArray())["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        var last: ApiError? = null
        for (cmd in LOG_COMMANDS) {
            try {
                val out = exec(cmd.first(), *cmd.drop(1).toTypedArray())["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()
                logCommand = cmd
                return out
            } catch (e: ApiError) {
                last = e
            }
        }
        throw last!!
    }

    private suspend fun exec(command: String, vararg params: String): JsonObject =
        ubus.call("file", "exec", buildJsonObject {
            put("command", command)
            put("params", JsonArray(params.map { JsonPrimitive(it) }))
        })

    private suspend fun write(path: String, data: String) {
        ubus.call("file", "write", buildJsonObject {
            put("path", path)
            put("data", data)
        })
    }

    /** File contents, or null when the file does not exist. */
    private suspend fun readOrNull(path: String): String? = try {
        ubus.call("file", "read", buildJsonObject { put("path", path) })["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
    } catch (e: ApiError.AgentMissing) { // ubus NOT_FOUND
        null
    }

    private fun ApiError.isPermission() =
        this == ApiError.AccessDenied || (this is ApiError.Ubus && status == ApiError.UBUS_PERMISSION_DENIED)

    /** This run in the system log: the installer's lines, and its exit code once finished. */
    data class Run(val log: String, val exitCode: Int?)

    companion object {
        const val INSTALLER_URL =
            "https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh"
        const val AGENT_USER = "wrtpilot"
        private const val MARKER = "wrtpilot-app-install"
        const val START_FAILED = "The router could not download or start the installer."
        private const val TAG = "wrtpilot-install: "
        private const val CRONTAB = "/etc/crontabs/root"
        private const val SCRIPT = "/tmp/wrtpilot-install.sh"
        private const val LOG = "/tmp/wrtpilot-install.log"
        private const val RC = "/tmp/wrtpilot-install.rc"
        private const val INITIAL_PASSWORD = "/etc/wrtpilot/initial_password"
        private const val AGENT_CLI = "/usr/sbin/wrtpilot"
        private val LOG_COMMANDS = listOf(
            listOf("/usr/libexec/syslog-wrapper"),
            listOf("/sbin/logread", "-e", "^"),
            listOf("/usr/sbin/logread", "-e", "^"),
        )
        private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

        /** Finds run [id] in the system log; null when it has not started. */
        fun parseRun(syslog: String, id: String): Run? {
            val lines = syslog.lines().mapNotNull { line ->
                line.indexOf(TAG).takeIf { it >= 0 }?.let { line.substring(it + TAG.length).trimEnd() }
            }
            val start = lines.lastIndexOf("started $id").takeIf { it >= 0 } ?: return null
            val rest = lines.drop(start + 1)
            val end = rest.indexOfFirst { it.startsWith("finished $id rc=") }
            if (end < 0) return Run(rest.joinToString("\n"), null)
            val rc = rest[end].substringAfter("rc=").trim().toIntOrNull() ?: -1
            return Run(rest.take(end).joinToString("\n"), rc)
        }

        /** Last few non-empty lines, for a progress display. */
        fun tail(log: String, lines: Int = 4): String =
            log.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.takeLast(lines).joinToString("\n")

        private fun randomString(length: Int, alphabet: String): String {
            val rnd = SecureRandom()
            return (1..length).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
