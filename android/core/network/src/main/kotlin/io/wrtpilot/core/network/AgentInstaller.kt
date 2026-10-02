package io.wrtpilot.core.network

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Installs the WrtPilot package on a router from the app, through the
 * router's own API with a root session (rpcd's `file` object, as LuCI uses
 * it): the published installer is started in the background on the router,
 * the app follows its log, then reads the login of the restricted
 * "wrtpilot" user so the root password does not have to be kept.
 */
class AgentInstaller(
    private val ubus: UbusClient,
    private val installerUrl: String = INSTALLER_URL,
    private val pollMillis: Long = 2_000,
    private val timeoutMillis: Long = 6 * 60_000,
) {
    /** The login cannot run commands (not root, or rpcd without its file plugin). */
    class NotPermitted : Exception("installing needs the router's root login")

    /** The installer ran and failed; [log] is its output. */
    class Failed(val exitCode: Int, val log: String) : Exception("installer exited with $exitCode")

    /** Starts the installer on the router; returns at once. */
    suspend fun start() {
        val script = """
            rm -f $LOG $RC
            ( wget -q -O $SCRIPT '$installerUrl' && sh $SCRIPT; echo ${'$'}? > $RC ) > $LOG 2>&1 < /dev/null &
        """.trimIndent()
        try {
            exec(script)
        } catch (e: ApiError) {
            throw when (e) {
                ApiError.AccessDenied, ApiError.AgentMissing -> NotPermitted()
                is ApiError.Ubus -> if (e.status == ApiError.UBUS_PERMISSION_DENIED) NotPermitted() else e
                else -> e
            }
        }
    }

    /**
     * Waits for the installer to finish, reporting the last lines of its log;
     * returns the login to use in the app (user "wrtpilot").
     */
    suspend fun awaitLogin(onProgress: (String) -> Unit): Credentials {
        val started = System.nanoTime()
        while (true) {
            delay(pollMillis)
            // the installer restarts the web server and rpcd: ride over short outages
            val log = runCatching { read(LOG) }.getOrNull()
            if (log != null) onProgress(tail(log))
            val rc = runCatching { read(RC) }.getOrNull()?.trim()
            if (rc != null && rc.isNotEmpty()) {
                val code = rc.toIntOrNull() ?: -1
                if (code != 0) throw Failed(code, log.orEmpty())
                return Credentials(AGENT_USER, agentPassword())
            }
            if ((System.nanoTime() - started) / 1_000_000 > timeoutMillis) throw Failed(-1, log.orEmpty())
        }
    }

    /** The initial password, or a new one when it was changed (then unknown). */
    private suspend fun agentPassword(): String {
        runCatching { read(INITIAL_PASSWORD).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val pw = randomPassword()
        exec("/usr/sbin/wrtpilot passwd '$pw' >/dev/null")
        return pw
    }

    private suspend fun exec(script: String) {
        ubus.call("file", "exec", buildJsonObject {
            put("command", "/bin/sh")
            put("params", JsonArray(listOf(JsonPrimitive("-c"), JsonPrimitive(script))))
        })
    }

    private suspend fun read(path: String): String =
        ubus.call("file", "read", buildJsonObject { put("path", path) })["data"]?.jsonPrimitive?.contentOrNull.orEmpty()

    companion object {
        const val INSTALLER_URL =
            "https://github.com/oaatiq/OpenWrt-Router/releases/download/router-latest/install.sh"
        const val AGENT_USER = "wrtpilot"
        private const val LOG = "/tmp/wrtpilot-install.log"
        private const val RC = "/tmp/wrtpilot-install.rc"
        private const val SCRIPT = "/tmp/wrtpilot-install.sh"
        private const val INITIAL_PASSWORD = "/etc/wrtpilot/initial_password"

        /** Last few non-empty lines, for a progress display. */
        fun tail(log: String, lines: Int = 4): String =
            log.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.takeLast(lines).joinToString("\n")

        private fun randomPassword(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..16).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
