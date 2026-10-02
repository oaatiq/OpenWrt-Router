package io.wrtpilot.core.network

/**
 * Where a router is and how to reach it.
 *
 * @param host LAN address, hostname or Tailscale address (100.x.y.z / MagicDNS)
 * @param certSha256 pinned SHA-256 fingerprint of the router's self-signed
 *   certificate (hex, no separators); null = use the system trust store.
 */
data class RouterEndpoint(
    val host: String,
    val port: Int,
    val https: Boolean,
    val certSha256: String? = null,
) {
    val baseUrl: String
        get() {
            val h = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            val scheme = if (https) "https" else "http"
            val defaultPort = if (https) 443 else 80
            return if (port == defaultPort) "$scheme://$h" else "$scheme://$h:$port"
        }

    val ubusUrl: String get() = "$baseUrl/ubus"

    companion object {
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443

        /**
         * Parse what a user typed ("192.168.1.1", "https://router.lan:8443",
         * "[fd00::1]") into host/port/https. Returns null when it is not usable.
         */
        fun parse(input: String, defaultHttps: Boolean): RouterEndpoint? {
            var s = input.trim()
            if (s.isEmpty()) return null
            var https = defaultHttps
            when {
                s.startsWith("https://", ignoreCase = true) -> { https = true; s = s.substring(8) }
                s.startsWith("http://", ignoreCase = true) -> { https = false; s = s.substring(7) }
            }
            s = s.substringBefore('/')
            var port: Int? = null
            val host: String
            if (s.startsWith('[')) {
                val end = s.indexOf(']')
                if (end < 0) return null
                host = s.substring(1, end)
                val rest = s.substring(end + 1)
                if (rest.startsWith(':')) port = rest.substring(1).toIntOrNull() ?: return null
            } else if (s.count { it == ':' } == 1) {
                host = s.substringBefore(':')
                port = s.substringAfter(':').toIntOrNull() ?: return null
            } else {
                host = s
            }
            if (host.isEmpty() || host.any { it.isWhitespace() }) return null
            val p = port ?: if (https) HTTPS_PORT else HTTP_PORT
            if (p !in 1..65535) return null
            return RouterEndpoint(host, p, https)
        }
    }
}

data class Credentials(val username: String, val password: String)
